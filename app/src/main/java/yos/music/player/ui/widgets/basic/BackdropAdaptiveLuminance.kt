package yos.music.player.ui.widgets.basic

import android.graphics.Bitmap
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.effects.colorControls
import yos.music.player.code.utils.others.GlassDiagnostics
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.coroutines.coroutineContext
import kotlin.math.abs

/**
 * 大面积玻璃表面的自适应内容色（AdaptiveLuminanceGlass demo 同款黑↔白翻转）。
 * null = 所在表面未启用自适应，消费方必须回退主题色，不允许把 null 当白色用。
 */
val LocalGlassContentColor = staticCompositionLocalOf<Color?> { null }

/**
 * demo 同款映射：平均亮度 0..1 → [-1,1] 保号平方（平方让中段更迟钝、两端更陡）。
 * l>0 = 玻璃背后偏亮，l<0 = 偏暗。
 *
 * 亮侧封顶 [BrightLuminanceCap]：demo 的 backdrop 是彩色壁纸，永远不会接近纯白；
 * 我们的浅色主题页面大面积是白底，采样 1.0 时 l=+1 会把玻璃压成
 * contrast=0 + brightness=0.5 的纯白平片（用户看到的就是它）。封顶后白背景
 * 仍保留 40% 对比与可见的模糊/折射调制，暗侧与其余区间数值与 demo 完全一致。
 */
internal const val BrightLuminanceCap = 0.6f

internal fun backdropSignedLuminance(luminance: Float): Float {
    val signed = luminance * 2f - 1f
    return (signed * abs(signed)).coerceIn(-1f, BrightLuminanceCap)
}

/** 亮背景提亮更多、暗背景也稍提亮（demo 同款数值映射）。 */
internal fun adaptiveBrightness(l: Float): Float = if (l > 0f) 0.1f + 0.4f * l else 0.1f + 0.3f * l

/** 背景越亮对比度越低（白纱感），暗背景保持原对比。 */
internal fun adaptiveContrast(l: Float): Float = if (l > 0f) 1f - l else 1f

/**
 * 模糊半径调制：亮背景磨砂更强（×2 封顶），暗背景更通透（×0.25 下限）。
 * 即 LiquidTopBarButton 8+8l / 8+6l 的比值形式，任意组件基底半径通用。
 */
fun adaptiveBlurPx(basePx: Float, l: Float): Float =
    if (l > 0f) basePx * (1f + l) else basePx * (1f + 0.75f * l)

/**
 * 表面纱不透明度：亮背景维持磨砂面（0.5），暗背景趋向透明（0.10）。
 * demo 的玻璃没有 surface 色层；固定 50% 白纱盖在调暗的 backdrop 上
 * 就是深色页面"发白"的来源。luminance 传原始 0..1 采样亮度。
 */
internal fun adaptiveSurfaceVeilAlpha(luminance: Float): Float =
    0.10f + 0.40f * luminance.coerceIn(0f, 1f)

/**
 * 自适应 vibrancy：与固定 [com.kyant.backdrop.effects.vibrancy] 同为一个
 * ColorMatrixColorFilter（saturation 1.5），只多出 brightness/contrast 两个矩阵常数，
 * 成本不变。l 由 [AdaptiveBackdropLuminance.l] 绘制期直读。
 */
fun BackdropEffectScope.adaptiveGlassColorControls(l: Float) {
    colorControls(
        brightness = adaptiveBrightness(l),
        contrast = adaptiveContrast(l),
        saturation = 1.5f
    )
}

/**
 * 共享的自适应亮度设施：把玻璃窗口内的 backdrop 内容录进 [GraphicsLayer]，
 * 5×5 采样 Rec.709 平均亮度（首版移植自 AndroidLiquidGlass AdaptiveLuminanceGlass
 * demo，见 LiquidTopBarButton），平滑后驱动 colorControls/blur 与内容色。
 *
 * 线程与节流：RESUMED 期 250ms 一拍，record 版本号去重——backdrop 不重绘就不采样，
 * 所以静止画面零成本、动画期（调用方停画 backdrop 时）也零成本。
 *
 * onDrawBackdrop 必须是类属性单实例：库内 DrawBackdropElement.equals 逐项比较回调，
 * 内联新建的 lambda 每次重组都判"参数变了"→ invalidateDrawCache 风暴（LiquidBottomTabs
 * NavContainerOnDrawBackdrop 同款教训）。
 */
@Stable
class AdaptiveBackdropLuminance internal constructor(
    private val layer: GraphicsLayer,
    private val tag: String,
    initialLuminance: Float
) {
    internal val sampledLuminance = mutableStateOf(initialLuminance)
    internal val displayedLuminance = mutableStateOf(initialLuminance)
    private val recordVersion = longArrayOf(0L)
    private val warningLogged = booleanArrayOf(false)
    private var hasLoggedFirstSample = false
    private var lastLoggedLuminance: Float? = null
    private var lastLoggedSampleAt = 0L

    private var luminanceState: () -> Float = { initialLuminance }
    private var contentColorState: () -> Color =
        { if (initialLuminance > 0.5f) Color.Black else Color.White }
    private var displayFrozenState: () -> Boolean = { false }

    internal fun bind(
        luminance: State<Float>,
        contentColor: State<Color>,
        displayFrozen: () -> Boolean
    ) {
        luminanceState = luminance::value
        contentColorState = contentColor::value
        displayFrozenState = displayFrozen
    }

    private fun submitSample(luminance: Float) {
        sampledLuminance.value = luminance
        if (!displayFrozenState()) {
            displayedLuminance.value = luminance
        }
        GlassDiagnostics.state(
            "display_state:$tag",
            "sampled=${"%.3f".format(sampledLuminance.value)} displayed=${"%.3f".format(displayedLuminance.value)} frozen=${displayFrozenState()}"
        )
    }

    /** 原始平滑亮度（0..1），供需要亮度本身的调用方（如表面色插值）使用。 */
    fun luminance(): Float = luminanceState()

    /** demo 同款保号平方映射，effects 内直读，变化只触发重绘不触发重组。 */
    fun l(): Float = backdropSignedLuminance(luminanceState())

    /** 自适应内容色：亮背景黑字、暗背景白字，tween(1000) 平滑。 */
    fun contentColor(): Color = contentColorState()

    /**
     * 稳定单实例 onDrawBackdrop：照常画 backdrop 内容，另录一份纯 backdrop 进采样层。
     * 录制内容只有 backdrop 本身——表面色、高光、内容组件一律不进 layer，
     * 这是 layerBackdrop 录制层内禁放 drawBackdrop 消费者（RenderThread 递归崩溃）的
     * 安全前提。
     */
    val onDrawBackdrop: DrawScope.(drawBackdrop: DrawScope.() -> Unit) -> Unit = { drawBackdrop ->
        drawBackdrop()
        recordBackdrop(drawBackdrop)
    }

    /** 已有自定义 onDrawBackdrop 的调用方（如 PlayerShell 的运动期门禁）在原门禁内补录。 */
    fun DrawScope.recordBackdrop(drawBackdrop: DrawScope.() -> Unit) {
        layer.record { drawBackdrop() }
        recordVersion[0]++
        GlassDiagnostics.tick(
            "luminance_record:$tag",
            "version=${recordVersion[0]} size=${size.width.toInt()}x${size.height.toInt()}"
        )
    }

    private fun recordSample(version: Long, luminance: Float, source: String) {
        val now = SystemClock.uptimeMillis()
        val previous = lastLoggedLuminance
        if (!hasLoggedFirstSample) {
            hasLoggedFirstSample = true
            lastLoggedLuminance = luminance
            lastLoggedSampleAt = now
            GlassDiagnostics.event(
                if (source == "fallback") "sample_first_fallback" else "sample_first",
                "tag=$tag version=$version luminance=${"%.3f".format(luminance)}"
            )
            return
        }
        val delta = luminance - (previous ?: luminance)
        if (abs(delta) >= SampleLogDelta || now - lastLoggedSampleAt >= SampleLogIntervalMs) {
            lastLoggedLuminance = luminance
            lastLoggedSampleAt = now
            GlassDiagnostics.event(
                "sample_update",
                "tag=$tag version=$version luminance=${"%.3f".format(luminance)} delta=${"%+.3f".format(delta)} source=$source"
            )
        }
    }

    suspend fun samplingLoop(lifecycle: Lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            var sampledVersion = 0L
            var emptyVersion = -1L
            var consecutiveFailures = 0
            val pixels = IntArray(25)
            while (coroutineContext.isActive) {
                delay(SampleIntervalMs)
                val version = recordVersion[0]
                if (version == 0L || version == sampledVersion) continue
                try {
                    val image = layer.toImageBitmap()
                    val androidBitmap = image.asAndroidBitmap()
                    var scaledBitmap: Bitmap? = null
                    var ownedSamplingBitmap: Bitmap? = null
                    try {
                        scaledBitmap = checkNotNull(
                            Bitmap.createScaledBitmap(androidBitmap, 5, 5, true)
                        )
                        ownedSamplingBitmap = if (scaledBitmap.config == Bitmap.Config.HARDWARE) {
                            checkNotNull(scaledBitmap.copy(Bitmap.Config.ARGB_8888, false))
                        } else if (scaledBitmap === androidBitmap) {
                            null
                        } else {
                            scaledBitmap
                        }
                        val bitmap = ownedSamplingBitmap ?: scaledBitmap
                        for (row in 0 until 5) for (column in 0 until 5) {
                            val x = column * (bitmap.width - 1) / 4
                            val y = row * (bitmap.height - 1) / 4
                            pixels[row * 5 + column] = bitmap.getPixel(x, y)
                        }
                        val luminance = liquidBackdropLuminance(pixels)
                        consecutiveFailures = 0
                        if (luminance != null) {
                            submitSample(luminance)
                            sampledVersion = version
                            recordSample(version, luminance, "normal")
                        } else if (emptyVersion == version) {
                            sampledVersion = version
                        } else {
                            // 首帧录制可能还没就绪：即使没有新绘制也再试一次。
                            emptyVersion = version
                        }
                    } catch (e: Exception) {
                        if (e is CancellationException) throw e
                        val fallback = checkNotNull(androidBitmap.copy(Bitmap.Config.ARGB_8888, false))
                        try {
                            for (row in 0 until 5) for (column in 0 until 5) {
                                val x = column * (fallback.width - 1) / 4
                                val y = row * (fallback.height - 1) / 4
                                pixels[row * 5 + column] = fallback.getPixel(x, y)
                            }
                            val luminance = liquidBackdropLuminance(pixels)
                            consecutiveFailures = 0
                            if (luminance != null) {
                                submitSample(luminance)
                                sampledVersion = version
                                recordSample(version, luminance, "fallback")
                            } else if (emptyVersion == version) {
                                sampledVersion = version
                            } else {
                                emptyVersion = version
                            }
                        } finally {
                            fallback.recycle()
                        }
                    } finally {
                        ownedSamplingBitmap?.recycle()
                        if (scaledBitmap !== androidBitmap && scaledBitmap?.config != Bitmap.Config.HARDWARE) {
                            scaledBitmap?.recycle()
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (!warningLogged[0]) {
                        warningLogged[0] = true
                        GlassDiagnostics.warning("sample_failed", "tag=$tag version=$version", e)
                        Log.w(tag, "Adaptive backdrop sampling failed", e)
                    }
                    // 先等新绘制，再按记录退避，避免持续失败引发采样风暴。
                    consecutiveFailures = (consecutiveFailures + 1).coerceAtMost(5)
                    if (consecutiveFailures >= 2) sampledVersion = version
                    delay((1000L shl consecutiveFailures).coerceAtMost(30_000L))
                }
            }
        }
    }

    private companion object {
        const val SampleIntervalMs = 250L
        const val SampleLogIntervalMs = 2_000L
        const val SampleLogDelta = 0.05f
    }
}

/**
 * 取共享自适应亮度设施。enabled=false 或 API<31（GraphicsLayer.toImageBitmap 门禁，
 * 与 LiquidTopBarButton 现行门禁一致）返回 null，调用方保持现有静态材质分支。
 *
 * [initialLuminance] 用主题底色先验（浅色 1f / 深色 0f），避免首帧从中间值闪变。
 */
@Composable
fun rememberAdaptiveBackdropLuminance(
    enabled: Boolean,
    tag: String,
    initialLuminance: Float,
    freezeDisplay: Boolean = false
): AdaptiveBackdropLuminance? {
    val supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val available = enabled && supported
    GlassDiagnostics.state(
        "luminance_gate:$tag",
        "enabled=$enabled supportedApi=$supported sdk=${Build.VERSION.SDK_INT} available=$available"
    )
    if (!available) return null
    val layer = rememberGraphicsLayer()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val luminance = remember(tag) {
        GlassDiagnostics.event("luminance_instance", "tag=$tag initial=$initialLuminance")
        AdaptiveBackdropLuminance(layer, tag, initialLuminance)
    }
    val freezeState = rememberUpdatedState(freezeDisplay)
    val frozenReader = remember(luminance) { { freezeState.value } }
    LaunchedEffect(luminance, lifecycle) {
        luminance.samplingLoop(lifecycle)
    }
    LaunchedEffect(luminance, freezeDisplay) {
        if (!freezeDisplay) {
            val sampled = luminance.sampledLuminance.value
            if (luminance.displayedLuminance.value != sampled) {
                GlassDiagnostics.event(
                    "luminance_unfreeze",
                    "tag=$tag sampled=${"%.3f".format(sampled)}"
                )
                luminance.displayedLuminance.value = sampled
            }
        }
    }
    val animatedLuminance = animateFloatAsState(
        targetValue = luminance.displayedLuminance.value,
        animationSpec = tween(1000),
        label = "${tag}Luminance"
    )
    val adaptiveContentColor = animateColorAsState(
        targetValue = if (luminance.displayedLuminance.value > 0.5f) Color.Black else Color.White,
        animationSpec = tween(1000),
        label = "${tag}ContentColor"
    )
    luminance.bind(animatedLuminance, adaptiveContentColor, frozenReader)
    return luminance
}
