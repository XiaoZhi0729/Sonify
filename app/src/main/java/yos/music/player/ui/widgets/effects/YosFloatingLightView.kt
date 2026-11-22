package yos.music.player.ui.widgets.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.widget.ImageView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.request.ImageRequest
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.code.utils.others.BitmapResolver
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundEffect
import yos.music.player.data.waveform.PlaybackLevelBridge
import yos.music.player.ui.widgets.effects.pearmesh.PearMeshBackground
import yos.music.player.ui.widgets.effects.pearmesh.PearMeshMesh
import yos.music.player.ui.widgets.effects.pearmesh.PearMeshState
import yos.music.player.ui.widgets.effects.pearmesh.isPearMeshSupported
import kotlin.random.Random
import yos.music.player.ui.pages.NowPlayingPage
import yos.music.player.ui.widgets.basic.YosWrapper

/**
 * 背景糊图的进程级缓存：键 = album Uri + 目标像素档，值 = imageResolve 全管线
 * （饱和度×3 + 烘焙压暗 + 高斯模糊）产出的最终位图。
 *
 * 为什么必须有：糊好的 Drawable 原本是页内 remember 状态，离开页面即销毁——
 * 重进画廊/播放页必然重跑整条管线并 450ms 淡入，实机观感即"背景每次重新加载"。
 * 位图极小（96/32px 档，≤36KB/张），LruCache 12 张的内存开销可忽略；
 * 缓存位图被多个 Drawable 共享（只读），BackgroundLayer 各自 newDrawable+mutate 互不干扰。
 */
private object FloatingLightBitmapCache {
    private val cache = object : android.util.LruCache<String, Bitmap>(12) {}
    fun key(uri: Uri, targetPx: Int) = "${uri}|$targetPx"
    fun get(key: String): Bitmap? = cache.get(key)
    fun put(key: String, bitmap: Bitmap) = cache.put(key, bitmap)
}

@Composable
fun YosFloatingLight(
    modifier: Modifier,
    album: () -> Uri?,
    isPlaying: () -> Boolean,
    nowPage: () -> String,
    showMiniPlayer: Boolean,
    enableMesh: Boolean = false,
    /**
     * 切歌过渡的唯一时间轴（外壳层建立）。非空时背景不再自己排 450ms 交叉淡化，
     * 而是按 [TrackArtworkTransition.layers] / [TrackArtworkTransition.progress]
     * 与封面同步换图——封面淡完背景正好也换完。
     *
     * null = 保留原 `AnimatedContent` 行为（画廊页那两份实例不走切歌过渡）。
     */
    crossfade: TrackArtworkTransition? = null,
    /**
     * 忽略 [showMiniPlayer] 的"暂停"语义，只要界面可见就把动效跑起来。
     *
     * ★这个参数是为引导页预览加的，修的是一个"开关看起来完全没用"的 bug：
     *   `showMiniPlayer = true` 的语义是"只画静态糊图、不跑 Ken Burns",
     *   而内部实现是把它并进了 `active = RESUMED && !showMiniPlayer` 这一个门。
     *   引导页的预览舞台传了 `showMiniPlayer = true`（因为它要隐藏真实迷你条），
     *   于是**动效时间轴永远不启动** —— 用户切「背景动态效果」开/关看到的画面
     *   逐像素相同。判据：一个参数承担两件事（布局 + 动效门）必然出这种 bug，
     *   必须拆开。
     */
    animateAlways: Boolean = false,
    /**
     * 强制走流体网格分支，**不看用户的 [SettingsLibrary.NowplayingBackgroundMode]**。
     *
     * 只给引导页预览用：那一屏要展示的是这个 App 最有辨识度的背景，
     * 不该因为用户此刻的设置恰好是"慢速漂移"就展示一个平铺糊图。
     * 它不改用户设置（纯展示），且仍需设备支持 GLES3。
     */
    forceMesh: Boolean = false,
    /**
     * 可选的背景模式覆写（只读展示，不修改 [SettingsLibrary]）：
     * - `null`：沿用系统设置（默认行为）；
     * - `0`：关闭动态效果；
     * - `1`：强制慢速漂移（Ken Burns）；
     * - `2`：强制流体网格（PearMesh，需设备支持 GLES3，不支持时自动退回慢速漂移）。
     */
    overrideBackgroundMode: Int? = null
) {
    val context = LocalContext.current
    val meshSupported = remember { isPearMeshSupported(context) }
    val effectiveBgEffect = when (overrideBackgroundMode) {
        null -> NowplayingBackgroundEffect
        0 -> false
        else -> true
    }
    val effectiveMeshMode = when (overrideBackgroundMode) {
        null -> forceMesh || SettingsLibrary.NowplayingBackgroundMode == SettingsLibrary.BACKGROUND_MODE_MESH
        2 -> true
        else -> false
    }
    // 流体网格模式接管背景时，糊图这条管线整个不跑（网格自己在 GL 里调色 + 模糊）
    val meshActive = enableMesh &&
            effectiveBgEffect &&
            effectiveMeshMode &&
            meshSupported

    var meshReady by remember { mutableStateOf(false) }
    LaunchedEffect(meshActive) {
        if (!meshActive) meshReady = false
    }
    val meshAlphaState = animateFloatAsState(
        targetValue = if (meshActive && meshReady) 1f else 0f,
        animationSpec = tween(durationMillis = 220),
        label = "meshAlpha"
    )
    val meshOpaque by remember(meshActive) {
        derivedStateOf { meshActive && meshAlphaState.value >= 0.999f }
    }

    Box(modifier = modifier) {
        if (!meshOpaque) {
            if (crossfade != null) {
                // 共享时间轴分支：每层各自持有一份 drawable，节点按图层 key 复用，
                // 过渡结束裁回一张时旧节点离开组合、位图随之释放。
                // 首帧（图层表还是空的）先按当前 album 画一张，不出黑闪。
                val layers = crossfade.layers.ifEmpty { listOf(album()) }
                YosWrapper {
                    layers.forEachIndexed { index, layer ->
                        key(layer) {
                            FloatingLightLayer(
                                uri = layer as? Uri,
                                modifier = Modifier.fillMaxSize(),
                        isPlaying = isPlaying,
                        nowPage = nowPage,
                        showMiniPlayer = showMiniPlayer,
                        alpha = {
                            // 背景用"只把新图淡上来"而不是互补交叉：两张图都是全屏不透明底图，
                            // 最终合成与互补交叉逐像素等价，但新图没就绪时旧图保持 alpha=1，
                            // 不会出现"旧图先淡掉、新图还没到"的透底变暗。
                            backgroundLayerAlpha(index, layers.size, crossfade.progress.value)
                        },
                        useKenBurns = !meshActive && effectiveBgEffect,
                        animateAlways = animateAlways
                    )
                }
            }
        }
            } else {

    val drawable = remember {
        mutableStateOf<Drawable?>(null)
    }
    val albumUri = album()

    YosWrapper {
        LaunchedEffect(albumUri, effectiveBgEffect) {
            if (albumUri == null) return@LaunchedEffect
            // 缓存命中：糊好的位图直接挂载，跳过整条管线（重进页背景即时出图）
            val hit = FloatingLightBitmapCache.get(
                FloatingLightBitmapCache.key(albumUri, targetPx(effectiveBgEffect))
            )
            if (hit != null) {
                drawable.value = hit.toDrawable(context.resources)
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) {
                val nextDrawable = loadFloatingLightDrawable(context, albumUri, effectiveBgEffect)
                    ?: return@withContext
                // 仅在结果完整生成后替换，切歌期间保留上一张背景。
                withContext(Dispatchers.Main) {
                    if (album() == albumUri) {
                        drawable.value = nextDrawable
                    }
                }
            }
        }
    }

    YosWrapper {
        val useBackground = remember("YosFloatingLight_useBackground") {
            derivedStateOf {
                album() == null
            }
        }

        AnimatedContent(
            targetState = drawable.value,
            transitionSpec = {
                // 首次出现（null → 图，含缓存命中的重进页）不淡入：450ms fadeIn 是
                // "每次进页背景慢慢浮现"观感的另一半来源；换图（图 → 图）保留交叉淡化
                if (initialState == null) {
                    fadeIn(tween(0)) togetherWith fadeOut(tween(durationMillis = 1))
                } else {
                    fadeIn(animationSpec = tween(450)) togetherWith
                        fadeOut(
                            animationSpec = tween(
                                durationMillis = 1,
                                delayMillis = 450
                            )
                        )
                }
            },
            modifier = Modifier.fillMaxSize(),
            contentKey = { it }
        ) { currentDrawable ->
            // 用户裁定：压暗效果在所有子页面（含歌词页）恒显示，不再随 nowPage 撤掉。
            // 原第三层全屏 OVERLAY ImageView 已烘焙进 imageResolve（bakeDarkOverlay），层序少一层全屏合成。
            BackgroundLayer(
                drawable = currentDrawable,
                useKenBurns = !meshActive && effectiveBgEffect,
                isPlaying = isPlaying,
                nowPage = nowPage,
                showMiniPlayer = showMiniPlayer,
                fallbackToBlack = useBackground.value,
                animateAlways = animateAlways
            )
        }
    }
            }
        }

        if (meshActive) {
            PearMeshBackgroundLayer(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = meshAlphaState.value },
                album = album,
                isPlaying = isPlaying,
                showMiniPlayer = showMiniPlayer,
                animateAlways = animateAlways,
                onFirstFrameRendered = { meshReady = true }
            )
        }
    }
}

/**
 * 背景的图层 alpha：**只淡入新图**，旧图全程保持不透明。
 *
 * 两张图都是铺满全屏的不透明底图，`旧图 alpha=1 + 新图 alpha=p` 的合成结果
 * 与互补交叉（`1-p` / `p`）逐像素相同；但新图还没解码出来时旧图仍是 alpha 1，
 * 不会露出下面的纯黑底（互补交叉会先淡掉旧图，观感是"切歌时背景闪一下暗"）。
 */
internal fun backgroundLayerAlpha(index: Int, count: Int, progress: Float): Float = when {
    count <= 1 -> 1f
    index >= count - 1 -> progress
    else -> 1f
}

/** 背景源图的目标解码档：动态效果要 96px 才够 Ken Burns 的 1.26 倍放大。 */
private fun targetPx(useKenBurns: Boolean = NowplayingBackgroundEffect): Int =
    if (useKenBurns) 96 else 32

/**
 * 糊图管线的唯一入口（load）：命中进程级缓存直接返回，否则解码 → 烘焙 → 模糊 → 入缓存。
 *
 * 就绪闸门（`rememberTrackArtworkTransition` 的 prepare）与绘制层共用这一个函数，
 * 所以"闸门等到的"就是"绘制层马上要画的"那一份，不存在重复解码。
 */
private suspend fun loadFloatingLightDrawable(
    context: Context,
    uri: Uri,
    useKenBurns: Boolean = NowplayingBackgroundEffect
): Drawable? {
    val targetPx = targetPx(useKenBurns)
    val cacheKey = FloatingLightBitmapCache.key(uri, targetPx)
    FloatingLightBitmapCache.get(cacheKey)?.let { return it.toDrawable(context.resources) }
    return withContext(Dispatchers.IO) {
        // 按最终目标尺寸直接解码（bitmapCompress 的目标 px），不再先解原图再压缩
        val request = ImageRequest.Builder(context)
            .data(uri)
            .size(targetPx)
            .build()
        val thisBitmap = context.imageLoader.execute(request).drawable?.toBitmap()?.run {
            BitmapResolver.bitmapCompress(this)
        } ?: return@withContext null
        val blurred = imageResolve(context, thisBitmap, bakeDarkOverlay = true)
        // 糊好的结果进进程级缓存后归缓存所有，不可 recycle（thisBitmap 是
        // 模糊前的源图，仍归本次请求所有，照旧回收）
        FloatingLightBitmapCache.put(cacheKey, blurred)
        thisBitmap.recycle()
        blurred.toDrawable(context.resources)
    }
}

/**
 * 背景源图预热（切歌过渡的就绪闸门调用）。
 *
 * 与 [loadFloatingLightDrawable] 同一条缓存键、同一条管线：等它返回，就意味着
 * 新背景第一帧已经有图，不会出现"背景先白一下再淡上来"。
 */
internal suspend fun warmUpFloatingLightArtwork(context: Context, uri: Uri) {
    loadFloatingLightDrawable(context, uri)
}

/**
 * 共享时间轴下的单层背景。
 *
 * 每层自己按 uri 取图（闸门已把结果放进进程缓存，通常是一次命中），
 * 于是"过渡结束裁掉旧图层"= 该节点离开组合 = 它持有的请求与位图引用一起释放。
 */
@Composable
private fun FloatingLightLayer(
    uri: Uri?,
    modifier: Modifier,
    isPlaying: () -> Boolean,
    nowPage: () -> String,
    showMiniPlayer: Boolean,
    alpha: () -> Float,
    useKenBurns: Boolean = NowplayingBackgroundEffect,
    animateAlways: Boolean = false
) {
    val context = LocalContext.current
    var drawable by remember(uri, useKenBurns) { mutableStateOf<Drawable?>(null) }
    LaunchedEffect(uri, useKenBurns) {
        if (uri == null) return@LaunchedEffect
        val loaded = loadFloatingLightDrawable(context, uri, useKenBurns)
        if (loaded != null) drawable = loaded
    }
    BackgroundLayer(
        drawable = drawable,
        useKenBurns = useKenBurns,
        isPlaying = isPlaying,
        nowPage = nowPage,
        showMiniPlayer = showMiniPlayer,
        fallbackToBlack = uri == null,
        layerAlpha = alpha,
        animateAlways = animateAlways
    )
}


/**
 * 流体网格背景（Pear Wall 移植）。
 *
 * 与糊图分支的关键差异：
 * - 封面按 512px 解码后原样交给 GL —— Pear Wall 在 fragment shader 里自己完成饱和/压暗/
 *   抖动，不能再叠 [imageResolve] 那套「饱和×3 + 压暗 + 高斯」，否则会双重处理。
 * - 切歌淡化由渲染器的 `uTransitionMix`（0.5s）负责，外层不再套 AnimatedContent。
 * - 生命周期驱动渲染线程启停，播放暂停时动画时钟冻结（画面停住不回弹）。
 */
@Composable
private fun PearMeshBackgroundLayer(
    modifier: Modifier,
    album: () -> Uri?,
    isPlaying: () -> Boolean,
    showMiniPlayer: Boolean,
    animateAlways: Boolean = false,
    onFirstFrameRendered: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val albumUri = album()

    val state = remember { PearMeshState() }
    val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    // 与 BackgroundLayer 同一个口径：showMiniPlayer 只在 animateAlways=false 时当动效门。
    val foreground = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)
    val active = foreground && (animateAlways || !showMiniPlayer)
    val playing = remember(isPlaying) { derivedStateOf(isPlaying) }.value
    LaunchedEffect(playing, active) {
        state.setPlaybackPlaying(playing && active)
    }

    // 网格样式。开了「每次随机」就在进入时抽一套（排除当前值，保证真的换了），
    // 这个决定只在 state 创建时做一次，之后由设置项直接驱动。
    val randomPreset = SettingsLibrary.NowplayingBackgroundRandomPreset
    val portraitPreset = SettingsLibrary.NowplayingBackgroundPortraitPreset
    val landscapePreset = SettingsLibrary.NowplayingBackgroundLandscapePreset
    val initialPresets = remember {
        if (randomPreset) {
            Random.nextInt(PearMeshMesh.PortraitPresetCount) to
                    Random.nextInt(PearMeshMesh.LandscapePresetCount)
        } else {
            portraitPreset to landscapePreset
        }
    }
    LaunchedEffect(portraitPreset, landscapePreset, randomPreset) {
        if (randomPreset) {
            state.setPortraitPreset(initialPresets.first)
            state.setLandscapePreset(initialPresets.second)
        } else {
            state.setPortraitPreset(portraitPreset)
            state.setLandscapePreset(landscapePreset)
        }
    }

    val flowSpeed = SettingsLibrary.NowplayingBackgroundFlowSpeed
    LaunchedEffect(flowSpeed) {
        state.setFlowSpeed(flowSpeed)
    }

    // 模糊强度与渲染质量：两个都直接进渲染器快照，无需额外换算层
    val blurStrength = SettingsLibrary.NowplayingBackgroundBlurStrength
    LaunchedEffect(blurStrength) {
        state.setBlurMultiplier(PearMeshState.blurMultiplierFor(blurStrength))
    }
    val renderQuality = SettingsLibrary.NowplayingBackgroundRenderQuality
    LaunchedEffect(renderQuality) {
        state.setRenderScale(PearMeshState.renderQualityScale(renderQuality))
        state.setTargetFrameRate(PearMeshState.renderQualityFps(renderQuality))
    }

    // 律动强度：关掉时设为 0，渲染器因此退回 1.0 倍（与完全不分析等价，且不再有开销）
    val pulseEnabled = SettingsLibrary.NowplayingBackgroundPulse
    val pulseStrength = SettingsLibrary.NowplayingBackgroundPulseStrength
    LaunchedEffect(pulseEnabled, pulseStrength) {
        state.setPulseIntensity(
            if (pulseEnabled) PearMeshState.pulseIntensityFor(pulseStrength) else 0f
        )
        if (!pulseEnabled) PlaybackLevelBridge.reset()
    }

    LaunchedEffect(albumUri) {
        if (albumUri == null) return@LaunchedEffect
        val bitmap = withContext(Dispatchers.IO) { decodeMeshArtwork(context, albumUri) }
        // 切歌期间上一张还在渲染时就别换：渲染器自己会做 0.6s 交叉淡化
        if (bitmap != null && album() == albumUri) {
            state.setArtwork(bitmap)
        }
    }

    PearMeshBackground(
        state = state,
        modifier = modifier.fillMaxSize(),
        renderingActive = active,
        onFirstFrameRendered = onFirstFrameRendered,
    )
}

@Composable
private fun BackgroundLayer(
    drawable: Drawable?,
    useKenBurns: Boolean,
    isPlaying: () -> Boolean,
    nowPage: () -> String,
    showMiniPlayer: Boolean,
    fallbackToBlack: Boolean,
    /**
     * 本层不透明度。切歌过渡期间由共享进度驱动，在**图层阶段**读取（不重组）；
     * 静止态恒为 1，与改造前完全一致。
     *
     * 参数名刻意不叫 `alpha`：`graphicsLayer` 的接收者也有 `alpha` 属性，
     * 同名的闭包参数会把它遮蔽掉（`alpha = alpha()` 会编译失败）。
     */
    layerAlpha: () -> Float = { 1f },
    /**
     * true = 只要界面可见就跑动效，忽略 [showMiniPlayer]。
     *
     * ★`showMiniPlayer` 本来就只该管"迷你条要不要占位"，它被并进动效门之后
     *   变成一个隐藏的"暂停动效"开关 —— 引导页预览传了 true，于是
     *   「背景动态效果」这个开关在那一屏完全看不出任何变化。见 [YosFloatingLight]。
     */
    animateAlways: Boolean = false
) {
    val context = LocalContext.current
    val layerDrawable = remember(drawable) {
        drawable?.constantState?.newDrawable(context.resources)?.mutate()
    }
    val lifecycleState =
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    // 动效门拆成两件事：界面是否可见（lifecycle），以及布局上要不要"装作没有播放"。
    val foreground = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)
    val active = foreground && (animateAlways || !showMiniPlayer)
    val playing = remember(isPlaying) { derivedStateOf(isPlaying) }.value
    val running = playing && active
    // 漂移相位：暂停/后台时 LaunchedEffect 取消，相位保持 → 画面冻结在当前帧；
    // 恢复后从原相位继续（与旧 KenBurnsView.pause/resume 语义一致）。
    val phaseSec = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(running, useKenBurns) {
        if (!running || !useKenBurns) return@LaunchedEffect
        var lastNanos = -1L
        while (true) {
            kotlinx.coroutines.delay(28L)
            withFrameNanos { now ->
                if (lastNanos < 0L) {
                    lastNanos = now
                    return@withFrameNanos
                }
                val dtSec = ((now - lastNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
                lastNanos = now
                if (dtSec > 0f) {
                    phaseSec.floatValue += dtSec
                }
            }
        }
    }

    val layerAlphaProvider = layerAlpha
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 切歌过渡期间本层不透明度（图层阶段读取，不触发重组）；静止态恒 1。
            .graphicsLayer { alpha = layerAlphaProvider() }
            .then(if (fallbackToBlack) Modifier.graphicsLayer { alpha = 1f } else Modifier)
    ) {
        if (fallbackToBlack) {
            Box(Modifier.fillMaxSize().background(Color.Black))
        }

        if (layerDrawable != null) {
            // 唯一的全屏图像层。动态模式每帧只写图层变换参数（scale/translation），
            // 不重画内容、不重组；幅度上限 ≤ (最小 scale − 1)/2 保证任何相位都盖满全屏。
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (useKenBurns) Modifier.graphicsLayer {
                            val t = phaseSec.floatValue
                            val scale = DRIFT_SCALE_BASE +
                                DRIFT_SCALE_AMP * sin(t * DRIFT_SCALE_FREQ)
                            scaleX = scale
                            scaleY = scale
                            translationX = DRIFT_PAN_AMP * size.width *
                                sin(t * DRIFT_PAN_X_FREQ + 1.3f)
                            translationY = DRIFT_PAN_AMP * size.height *
                                sin(t * DRIFT_PAN_Y_FREQ + 4.1f)
                        } else Modifier
                    )
            ) { view ->
                view.setImageDrawable(layerDrawable)
            }
        }
    }
}

// 慢漂移参数：缩放 1.20–1.32（19s 周期），位移 5.5%（X 24s / Y 17s）。
// 红线：DRIFT_PAN_AMP ≤ (BASE−AMP−1)/2 = 0.08，任何相位都盖满全屏不露边。
private const val DRIFT_SCALE_BASE = 1.26f
private const val DRIFT_SCALE_AMP = 0.06f
private const val DRIFT_SCALE_FREQ = (2.0 * PI / 19.0).toFloat()
private const val DRIFT_PAN_AMP = 0.055f
private const val DRIFT_PAN_X_FREQ = (2.0 * PI / 24.0).toFloat()
private const val DRIFT_PAN_Y_FREQ = (2.0 * PI / 17.0).toFloat()
private const val DRIFT_FRAME_MS = 1000f / 30f

/**
 * 流体网格分支的封面解码尺寸。原版 Pear Wall 解码上限是 2048px 后再按 `inSampleSize`
 * 二分降采样；我们最终只用 1/4 分辨率渲染 + 重度模糊，512px 已经远超所需。
 */
private const val MESH_ARTWORK_PX = 512

/** 网格背景的封面缓存键。就绪闸门与绘制层共用，避免两边各写一份字符串。 */
private fun meshArtworkCacheKey(uri: Uri): String = "mesh|$uri|$MESH_ARTWORK_PX"

/** 网格背景源图解码（含 ARGB_8888 归一化，GL 不接受硬件位图/RGB_565）。 */
private suspend fun decodeMeshArtwork(context: Context, uri: Uri): Bitmap? {
    val cacheKey = meshArtworkCacheKey(uri)
    FloatingLightBitmapCache.get(cacheKey)?.let { return it }
    val request = ImageRequest.Builder(context)
        .data(uri)
        .size(MESH_ARTWORK_PX)
        // Coil 在 API 26+ 默认交付 Bitmap.Config.HARDWARE，而 GLUtils.texImage2D
        // 不接受硬件位图（抛 "invalid Bitmap format"）。必须关掉。
        .allowHardware(false)
        .build()
    return context.imageLoader.execute(request).drawable?.toBitmap()?.let { decoded ->
        // GL 只读纹理，也要求 8888；RGB_565/HARDWARE 都传不上去
        val uploadable = if (decoded.config == Bitmap.Config.ARGB_8888) {
            decoded
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        }
        if (uploadable !== decoded) decoded.recycle()
        uploadable.also { FloatingLightBitmapCache.put(cacheKey, it) }
    }
}

/**
 * 网格背景源图预热（切歌过渡的就绪闸门调用）。
 *
 * 网格的换图由 GL 线程按 0.6s 完成，但源图**必须**先解好：否则切歌瞬间纹理还没上传，
 * 封面已经淡完、背景还停在上一首的模糊里。
 */
internal suspend fun warmUpMeshArtwork(context: Context, uri: Uri) {
    withContext(Dispatchers.IO) { decodeMeshArtwork(context, uri) }
}

/**
 * 封面 → 背景糊图管线：ARGB_8888 复制 → 饱和度×3 → 压暗 →（可选）烘焙压暗层 → 真高斯模糊。
 * [bakeDarkOverlay] 仅背景路径传 true：等价烘焙原第三层全屏 OVERLAY ImageView
 * （同图 OVERLAY 滤镜后按 0.618 alpha 叠回），首页取色遮罩等共享调用方保持默认 false 不变。
 */
fun imageResolve(
    context: Context,
    image: Bitmap,
    moreLight: Boolean = false,
    bakeDarkOverlay: Boolean = false
): Bitmap {
    var resizedBitmap = image.copy(Bitmap.Config.ARGB_8888, true)
    resizedBitmap.applyCanvas {
        val paint = Paint()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = true

        val saturationMatrix = ColorMatrix()
        saturationMatrix.setSaturation(3f)

        paint.colorFilter = ColorMatrixColorFilter(saturationMatrix)
        drawBitmap(resizedBitmap, 0f, 0f, paint)

        if (moreLight) {
            drawColor((0x1AFFFFFF).toInt())
            drawColor((0xFFFFFFFF).toInt(), PorterDuff.Mode.OVERLAY)
            drawColor((0x52FFFFFF).toInt())
            drawColor((0xBFFFFFFF).toInt(), PorterDuff.Mode.OVERLAY)
        } else {
            drawColor((0x33000000).toInt(), PorterDuff.Mode.OVERLAY)
            drawColor((0x40000000).toInt())

            if (bakeDarkOverlay) {
                val layerPaint = Paint().apply { alpha = (0.618f * 255f).toInt() }
                val layer = saveLayer(null, layerPaint)
                drawBitmap(
                    resizedBitmap, 0f, 0f,
                    Paint().apply {
                        isFilterBitmap = true
                        colorFilter = PorterDuffColorFilter(
                            (0x33000000).toInt(),
                            PorterDuff.Mode.OVERLAY
                        )
                    }
                )
                restoreToCount(layer)
            }
        }
    }
    // 真高斯模糊：与原 Toolkit.blur 同一套算法（框架自带 ScriptIntrinsicBlur，
    // 无需恢复 renderscript-toolkit aar 与 gradle 配置）。输出与输入同尺寸，
    // 放大铺满全屏不再有马赛克。切歌频率的一次性成本，毫秒级。
    val blurred = gaussianBlur(context, resizedBitmap)
    if (blurred !== resizedBitmap) {
        resizedBitmap.recycle()
    }
    return blurred
}

/**
 * 框架 RenderScript 高斯模糊（ScriptIntrinsicBlur，API 17+）。与已删除的
 * renderscript-toolkit-release.aar 的 Toolkit.blur 是同一套内核的移植，输出等价。
 * [source] 不被 recycle；返回值归调用方所有（除非 radius 无效直接返回原图）。
 */
@Suppress("DEPRECATION")
fun gaussianBlur(context: Context, source: Bitmap, radius: Float = 25f): Bitmap {
    if (radius <= 0f || source.width <= 0 || source.height <= 0) return source
    val rs = RenderScript.create(context)
    return try {
        val input = Allocation.createFromBitmap(rs, source)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius.coerceAtMost(25f))
        script.setInput(input)
        script.forEach(output)
        val result = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        output.copyTo(result)
        result
    } finally {
        rs.destroy()
    }
}