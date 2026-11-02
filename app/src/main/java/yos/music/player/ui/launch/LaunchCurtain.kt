package yos.music.player.ui.launch

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import kotlin.math.pow

/**
 * 启动幕布：整屏品牌红，中间按 Logo 轮廓挖一个洞，洞里直接就是首页。
 *
 * 这不是"圆形遮罩 + 之后淡入内容"，而是**Logo 本身就是窗口**：红色面一直铺满屏幕，
 * 只有 Logo 轮廓围出的那块区域透出下面的首页。轮廓随动画放大，直到完全盖住屏幕，
 * 红色自然消失。
 *
 * 实现要点：
 *  1. 轮廓用 Logo 的真实矢量顶点做 [Path]（不是圆形/方块），九个顶点乘同一个缩放系数，
 *     所以放大到整屏时边缘依然锐利，不会因为位图放大而糊。
 *  2. 挖洞用**一个** [Path] + [PathFillType.EvenOdd]（整屏矩形 + Logo 轮廓）一次 drawPath，
 *     不建离屏层、不用 clipPath，也不给任何 backdrop 录制层加 alpha。
 *  3. 动画值只在**绘制期**读取（[drawWithContent] 里读 [state]），不进组合、不触发重组。
 *  4. 整段结束后幕布离开组合，运行期零成本。
 */

/** 蓄力时长（毫秒）。 */
private const val ChargeDurationMs = 260

/** Logo 初始缩放（相对 [LogoBaseDp]）与蓄力后的最小缩放。 */
private const val LogoScaleStart = 1.9f
private const val ChargeShrink = 0.74f

/** Logo 的基准宽度：设计稿里 76dp 宽的字形。 */
private const val LogoBaseDp = 76f

/**
 * 铺满整屏所需的缩放系数（相对 [LogoBaseDp]）。
 *
 * 实测扫描：对 360×800 ~ 900×1400 各尺寸求"Logo 完全盖住屏幕"的最小倍数，
 * 结果稳定落在 `屏宽dp × 0.0812` 附近（因为屏幕比例变化时是按宽度先盖满）。
 * 乘 1.06 留余量，保证完全覆盖、不残留一条红边。
 */
private const val CoverScalePerWidthDp = 0.0812f * 1.06f

/** Logo 轮廓顶点（来自 `ic_launcher_foreground.xml` 的 pathData），单位为 viewport。 */
private val LogoPoints = floatArrayOf(
    149f, 56.5f,
    149f, 34.5f,
    149f, 32f,
    84f, 46.5f,
    82.5f, 116.5f,
    43f, 125.5f,
    43f, 159.38f,
    104f, 146f,
    105.5f, 66f
)

/** 上述顶点的包围盒中心（viewport 单位）：缩放围绕它进行，Logo 才会原地长。 */
private const val LogoCenterX = 96f
private const val LogoCenterY = 95.69f

/** Logo 的 viewport 宽度，用于把 viewport 单位换算成 dp。 */
private const val LogoViewportWidth = 106f

// ── 铺开阶段（真 spring）───────────────────────────────────────────────────
//
// 曾经用"恒定比率的指数插值"：log(scale) 随时间线性增长，肉眼就是**匀速推镜**，
// 用户反馈"看起来像线性的、特别不爽快"。改成真正的 Compose spring：
// 起步快、尾段自然减速；弹簧的过冲会越过"铺满"阈值，而超过阈值后幕布直接停画，
// 所以那点回弹完全不可见，不会出现"涨过头又缩回去"的停顿感。
private const val GrowDampingRatio = 0.62f
private const val GrowStiffness = 105f

// ── 首页浮现 ───────────────────────────────────────────────────────────────
//
// 以"铺开进度"（0..1）为轴，而不是毫秒：spring 的时长由物理参数决定，
// 按时间轴硬编码会在不同设备/刷新率上错位。
private const val RevealBlockCount = 8
private const val RevealStartGrow = 0.88f
private const val RevealStaggerGrow = 0.0075f
private const val RevealSpanGrow = 0.06f

/**
 * 幕布需要展示的动画状态。
 *
 * 全部只在绘制期读：不要把它们当组合期状态用；给首页各块取进度请用 [LocalLaunchReveal]。
 */
class LaunchCurtainState {
    /** 当前 Logo 缩放倍数。绘制期读取。 */
    val logoScale = mutableFloatStateOf(LogoScaleStart)

    /** 铺开进度 0..1；spring 下会短暂略超 1（过冲），绘制侧按"铺满"截断。 */
    val grow = mutableFloatStateOf(0f)

    /** 整段是否已结束（宿主据此把幕布移出组合）。 */
    var finished by mutableStateOf(false)
        private set

    internal fun markFinished() {
        finished = true
    }
}

/**
 * 首页各块的浮现进度来源。
 *
 * [active] 为 false 表示"没有开幕动画"（热启动、动画已结束、无障碍关闭动画），
 * 此时 [of] 恒返回 1f，首页表现与改动前完全一致。
 *
 * [active] 变化会换掉整个实例：消费方按 CompositionLocal 身份变化重组，
 * 于是动画结束后所有浮现修饰符都会被摘掉，运行期不残留任何图层。
 */
class LaunchRevealScope(
    val active: Boolean,
    private val progress: (Int) -> Float
) {
    /** @return 已经过缓动的 0..1 浮现进度（可直接当 alpha 用） */
    fun of(index: Int): Float = if (active) progress(index) else 1f
}

val LocalLaunchReveal = compositionLocalOf { LaunchRevealScope(false) { 1f } }

/** 铺满所需的缩放倍数；屏幕尺寸变化时按屏宽重算。 */
internal fun coverScale(screenWidthDp: Float): Float =
    maxOf(LogoScaleStart * 1.2f, screenWidthDp * CoverScalePerWidthDp)

/**
 * 按当前缩放系数构造 Logo 轮廓 Path。
 *
 * @param scale 相对 [LogoBaseDp] 的倍数
 * @param center 屏幕中心（px）
 */
private fun buildLogoPath(scale: Float, center: Offset, density: Float): Path {
    val unitPx = LogoBaseDp * density / LogoViewportWidth * scale
    return Path().apply {
        for (i in LogoPoints.indices step 2) {
            val x = center.x + (LogoPoints[i] - LogoCenterX) * unitPx
            val y = center.y + (LogoPoints[i + 1] - LogoCenterY) * unitPx
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}

/**
 * 幕布本体。挂在所有内容之上（含迷你条/全屏播放页），整段结束后由宿主移出组合。
 */
@Composable
fun LaunchCurtain(
    state: LaunchCurtainState,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current.density
    val windowInfo = LocalWindowInfo.current
    val screenWidthDp = with(LocalDensity.current) {
        windowInfo.containerSize.width.toFloat() / density
    }

    // 幕布色**必须与系统闪屏背景逐像素相同**（colors.xml 的 launch_curtain）。
    // 曾经这里是 #F54047 → #E64366 的对角渐变，而系统闪屏只能填纯色，
    // 两者交接时背景会明显变一次色（用户反馈"背景的颜色不一样"）。
    val curtain = Color(0xFFF54047)
    // 洞内不填色：它透出的是下方首页自己的底色，
    // 于是浅色主题看起来是白 Logo、深色主题是黑 Logo —— 这是"窗口"语义的必然结果，
    // 也正是系统闪屏 Logo 必须跟随主题（见 SplashTheme.Dark）的原因。
    val edgeGlow = Color(0xFFFFFFFF)

    val cover = coverScale(screenWidthDp)

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                // 幕布**不消费** drawContent：它是不透明的遮挡层，
                // 下面的首页由宿主另外正常绘制，这里只画红色与洞。
                val scale = state.logoScale.floatValue

                // 铺满判定：轮廓完全盖住屏幕后不再绘制任何东西，
                // 避免最后一帧残留一圈极细的红边；spring 的过冲也因此不可见。
                if (scale >= cover) return@drawWithContent

                drawCurtain(scale, cover, curtain, edgeGlow, density)
            }
    )
}

/** 画红色幕布 + 挖洞 + 边缘光晕。 */
private fun DrawScope.drawCurtain(
    scale: Float,
    cover: Float,
    curtain: Color,
    edgeGlow: Color,
    density: Float
) {
    val center = Offset(size.width / 2f, size.height / 2f)
    val logo = buildLogoPath(scale, center, density)

    // 整屏矩形 + Logo 轮廓，EvenOdd 一次画完 = 红色面中间挖出 Logo 形状的洞。
    val outer = Path().apply {
        addRect(Rect(0f, 0f, size.width, size.height))
        addPath(logo)
        fillType = PathFillType.EvenOdd
    }

    drawPath(path = outer, color = curtain)

    // 洞边缘的一圈微光：让"这里是 Logo 的轮廓"被看见。
    // 越接近铺满越弱，铺满前自然消失。
    val glow = (1f - (scale / cover).coerceIn(0f, 1f)).let { it * it }
    if (glow > 0.01f) {
        drawPath(
            path = logo,
            color = edgeGlow.copy(alpha = 0.22f * glow),
            style = Stroke(width = (2.5f * density) * (1f + 0.4f * (1f - glow)))
        )
    }
}

/**
 * 创建幕布状态并驱动动画。
 *
 * @param play 是否播放（热启动/旋转恢复时为 false，直接跳过）
 * @param ready 系统闪屏是否已撤场；为 false 时不启动，等到 true 才起跑
 * @param onFinish 整段结束回调（宿主据此把幕布移出组合）
 */
@Composable
fun rememberLaunchCurtainState(
    play: Boolean,
    ready: Boolean,
    onFinish: () -> Unit
): LaunchCurtainState {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val windowInfo = LocalWindowInfo.current
    val screenWidthDp = with(LocalDensity.current) {
        windowInfo.containerSize.width.toFloat() / density
    }
    val cover = coverScale(screenWidthDp)

    // 系统关闭动画时直接跳到终态：与 Skeleton 的无障碍门控一致。
    val systemAnimated = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f) > 0f
    }

    val state = remember { LaunchCurtainState() }

    LaunchedEffect(play, ready, systemAnimated, cover) {
        if (!play || !systemAnimated) {
            state.logoScale.floatValue = cover
            state.grow.floatValue = 1f
            state.markFinished()
            onFinish()
            return@LaunchedEffect
        }
        // 必须等系统闪屏撤场：闪屏是实心红，幕布此刻已有 Logo 形状的洞，
        // 在它淡出期间起跑会让动画演在闪屏背后。
        if (!ready) return@LaunchedEffect

        // ① 蓄力：从静止尺寸平滑缩到最小，收住劲。
        val chargeStart = LogoScaleStart
        val chargeEnd = LogoScaleStart * ChargeShrink
        val charge = Animatable(chargeStart)
        charge.animateTo(
            targetValue = chargeEnd,
            animationSpec = tween(ChargeDurationMs, easing = FastOutSlowInEasing)
        ) {
            state.logoScale.floatValue = value
        }

        // ② 铺开：spring 驱动，指数映射到缩放倍数。
        //    指数映射让"进度"和"观感放大"成线性关系；spring 保证起步有力、收尾自然减速。
        val grow = Animatable(0f)
        grow.animateTo(
            targetValue = 1f,
            animationSpec = spring(
                dampingRatio = GrowDampingRatio,
                stiffness = GrowStiffness,
                visibilityThreshold = 0.0001f
            )
        ) {
            state.grow.floatValue = value
            state.logoScale.floatValue =
                chargeEnd * (cover / chargeEnd).pow(value.coerceAtLeast(0f))
        }

        state.logoScale.floatValue = cover
        state.grow.floatValue = 1f
        state.markFinished()
        onFinish()
    }

    return state
}

/**
 * 第 [index] 块的浮现进度（easeOutCubic 缓动后的 0..1）。
 *
 * 轴是"铺开进度"而不是毫秒：spring 的时长由物理参数决定，
 * 按毫秒硬编码会在不同刷新率/设备上错位。
 */
internal fun staggerReveal(grow: Float, index: Int): Float {
    val start = RevealStartGrow + index * RevealStaggerGrow
    val u = ((grow - start) / RevealSpanGrow).coerceIn(0f, 1f)
    val inv = 1f - u
    return 1f - inv * inv * inv
}

/** 首页浮现的块数（供调用方分配 index）。 */
internal val LaunchRevealBlockCount = RevealBlockCount

/**
 * 让某个首页模块参与"自下向上浮现"。
 *
 * 读的是 [LocalLaunchReveal]，并且**全部在绘制期求值**：
 * [Modifier.graphicsLayer] 的 block 版本每帧在绘制阶段执行，只让本图层失效，
 * 不会引起重组（本工程已多次踩到"组合期读动画值 → 整页重组"的坑）。
 *
 * alpha 用 [CompositingStrategy.ModulateAlpha]：默认 Auto 会为父级建一个全屏离屏缓冲
 * （即 `alpha caused saveLayer`），ModulateAlpha 把 alpha 乘进每条绘制指令、不开层。
 *
 * 没有开幕动画时**不挂任何修饰符**，静止态图层拓扑与改动前逐帧一致。
 */
@Composable
fun launchRevealModifier(index: Int): Modifier {
    val scope = LocalLaunchReveal.current
    if (!scope.active) return Modifier
    return Modifier.graphicsLayer {
        val p = scope.of(index)
        compositingStrategy = CompositingStrategy.ModulateAlpha
        alpha = p
        translationY = (1f - p) * 26f * density
    }
}
