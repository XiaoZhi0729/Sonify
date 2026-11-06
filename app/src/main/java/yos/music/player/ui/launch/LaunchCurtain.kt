package yos.music.player.ui.launch

import android.provider.Settings
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import yos.music.player.ui.theme.background
import yos.music.player.ui.theme.backgroundDark
import yos.music.player.ui.theme.primary
import yos.music.player.ui.theme.withNight

/**
 * 启动过渡：**纯淡入淡出**，不缩放、不挖洞、不铺开。
 *
 * 画面结构只有两层：
 *  1. 铺满整屏的**页面底色**（浅色=白 / 深色=黑）。
 *  2. 正中的品牌 Logo。
 *
 * 时序：底色上的 Logo 淡入（轻微上浮 + 微缩放）→ 静置 → 上浮淡出；随后底色淡去，
 * 首页各块按顺序淡入并自下上浮。整段结束后幕布离开组合，运行期零成本。
 *
 * 为什么底色必须等于页面底色（而不是品牌红）：
 *  底色与首页表面同色时，"底色淡去"这一步在视觉上**根本看不见**，交接是无缝的；
 *  观众只会看到「Logo 浮现 → Logo 退场 → 内容浮现」这三拍，中间没有任何变色。
 *  这也让系统闪屏可以做到逐像素同色：闪屏背景就是页面底色，闪屏图标就是同一个红 Logo。
 *
 * 动画值**只在绘制期读取**（[drawWithContent] 里读 [state]），不进组合、不触发重组；
 * 首页各块用 [LocalLaunchReveal] 在绘制期取自己的进度。
 */

// ── 时间轴（毫秒）────────────────────────────────────────────────────────────
//
// 节奏取自设计稿「纯净」档（用户拍板）按 0.5× 慢放后的观感：
// 设计稿 1× 是 logoIn 110 / 静置到 240 / 淡出至 330，内容 272 起、错开 26、单块 290；
// 下面的常量就是那一档的 2× 展开。
//
// 三条约束（都来自逐帧实测，改之前先读）：
//  ① **Logo 的存活区间必须盖住内容起点**（logoOut < contentStart）。否则 Logo 退完、
//     内容还没亮，中间会出现几十毫秒纯底色空屏，读作"卡一下"。
//  ② 底色窗口要短，且要早于内容变亮。底色幕布画在内容**之上**，它若长时间半透明，
//     会把刚浮现的深色标题冲淡成灰白（= 又是雾）。底色与页面同色，这段本来就看不见，
//     只是用来挡住冷启动早期的帧，所以压到 140ms 足够。
//  ③ 单块时长不要超过约 600ms：更长的单块会让每块在低 alpha 区停留太久，观感变拖沓。

/** Logo 淡入结束。 */
private const val LogoInEndMs = 220

/** Logo 开始淡出。 */
private const val LogoOutStartMs = 480

/** Logo 完全消失（晚于内容起点，见约束①）。 */
private const val LogoOutEndMs = 660

/** 底色开始淡去。 */
internal const val CurtainFadeStartMs = 480

/** 底色完全消失（早于内容变亮，见约束②）。 */
internal const val CurtainFadeEndMs = 620

/** 首个内容块（大标题与底栏）开始浮现。 */
internal const val ContentStartMs = 544

/** 相邻内容块之间的错开量。 */
internal const val ContentStaggerMs = 52

/** 单块浮现时长。 */
internal const val ContentDurationMs = 580

/** 整段总时长（毫秒）：544 + 6×52 + 580 = 1436。 */
internal const val LaunchTotalMs = 1436

/**
 * 等系统闪屏撤场的最长时长（毫秒）。
 *
 * 幕布是不透明遮挡层，时钟停住 = 整屏永久停在底色上（浅色主题下就是白屏）。
 * 所以"等闪屏"必须是有限等待：超时就照常起跑，宁可交接差一点，也不能把用户卡住。
 */
private const val SplashWaitFallbackMs = 120L

// ── Logo 几何 ───────────────────────────────────────────────────────────────
//
// 两段开屏必须画成**同一个大小**。目标值是**真机实测**的，不是估算：
//
//   首屏（系统闪屏）实测字形 182px = 56.0dp 宽（1280×2772 / 520dpi 的机器）。
//   首屏那侧由 drawable 的占空比决定：平台把 ic_splash_logo 拉伸铺满
//   [SplashBoxDp] 的容器，字形占 [GlyphWidthUnits]/[SplashViewport]（≈19.9%）。
//   Compose 侧直接用同一个 dp 值 [LogoGlyphDp]，两段因此恒等。
//
// 为什么**不能**用系统闪屏图标 View 的 bounds 去换算尺寸：
// 实测过——那个 View 约 188dp，而 drawable 实际按 288dp 的盒子铺满。
// 按 View 尺寸算会把应用内 Logo 缩小近 1.8 倍（上一版就是这样，
// 表现为"首屏偏大 + 进入后变小"）。所以尺寸一律不取实测值。

/** 字形目标宽度（dp）。取自首屏真机实测值；Compose 侧直接用它。 */
private const val LogoGlyphDp = 56f

/** 系统闪屏图标容器边长（dp）。与 `ic_splash_logo.xml` 的 width/height 一致。 */
private const val SplashBoxDp = 288f

/** `ic_splash_logo.xml` 的 viewport 边长（path 单位）；字形占 GlyphWidthUnits/本值。 */
private const val SplashViewport = 532f

/** 字形在自身坐标系里的宽度（path 单位，即 149 - 43）。 */
private const val GlyphWidthUnits = 106f

/** Logo 轮廓顶点（来自 `ic_launcher_foreground.xml` 的 pathData）。 */
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

/** 轮廓包围盒中心（viewport 单位）：缩放围绕它进行，Logo 才会原地长。 */
private const val LogoCenterX = 96f
private const val LogoCenterY = 95.69f

/** 内容块上浮距离（dp）。 */
private const val RevealLiftDp = 16f

/**
 * 系统闪屏图标容器的实测尺寸。
 *
 * 两段的大小都由"容器宽 × 字形在 viewport 中的占比"推出，因此这里量到的容器像素
 * 直接参与 Compose 的绘制换算 —— 容器是 288dp 还是别的值都能自动对上，
 * 不依赖任何写死的 dp 假设。位置不取这里的值：两段都以屏幕正中为准，
 * 避免系统把图标抬高后，"进入后的动画 Logo"跟着偏离中心。
 *
 * [logoSizePx] 为 0 表示没量到（热启动/无系统闪屏），此时按 [SplashBoxDp] 兜底。
 */
class LaunchStartMetrics {
    /** 系统闪屏图标的容器边长（px）；0 = 未测量。 */
    val logoSizePx = mutableFloatStateOf(0f)

    internal fun update(containerPx: Float) {
        logoSizePx.floatValue = containerPx
    }
}

/**
 * 幕布需要展示的动画状态。
 *
 * 全部只在绘制期读；给首页各块取进度请用 [LocalLaunchReveal]。
 */
class LaunchCurtainState {
    /** 时间轴进度 0..1，线性推进。绘制期读取。 */
    val clock = mutableFloatStateOf(0f)

    /** 整段是否已结束（宿主据此把幕布移出组合）。 */
    var finished by mutableStateOf(false)
        private set

    internal fun markFinished() {
        finished = true
    }

    /**
     * 强制收场。
     *
     * 幕布是一层不透明遮挡，一旦它没有退场，用户看到的就是一整屏底色（浅色下=白屏）。
     * 宿主用看门狗兜底调用：任何意外（等待条件没到、动画被取消、掉帧卡住）都不能让
     * 这层永久盖住首页。
     */
    fun forceFinish() {
        clock.floatValue = 1f
        finished = true
    }

    /** 第 [index] 块的浮现进度（缓动后的 0..1），绘制期调用。 */
    fun revealAt(index: Int): Float {
        val ms = clock.floatValue * LaunchTotalMs
        // 索引语义来自调用方：0=大标题，1..6=六个内容板块，7=底栏。
        // 标题与底栏属于"页面框架"，和 Logo 一起先就位（rank 0）；
        // 六个板块随后逐块跟上（rank 1..6）。框架先立、内容再填，
        // 读起来是"页面浮现"而不是"一堆卡片排队进场"。
        val rank = if (index <= 0 || index >= 7) 0 else index
        val start = ContentStartMs + rank * ContentStaggerMs
        return easeOutCubic(((ms - start) / ContentDurationMs).coerceIn(0f, 1f))
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

/** 缓出三次方：起步快、收尾稳。 */
private fun easeOutCubic(u: Float): Float {
    val inv = 1f - u
    return 1f - inv * inv * inv
}

/** 缓入二次方：离开时不抢戏。 */
private fun easeInQuad(u: Float): Float = u * u

/** Logo 淡入结束前的状态（只有"没有系统闪屏可接"的冷启动路径才会用到）。 */
private fun logoIn(p: Float): Triple<Float, Float, Float> {
    val e = easeOutCubic(p)
    return Triple(e, (1f - e) * 6f, 0.94f + 0.06f * e)
}

/**
 * 按当前时间轴求 Logo 的 alpha / 纵向位移(dp) / 缩放。
 *
 * [handoff] 为 true 表示"系统闪屏已经把同一个 Logo 画在屏幕上了"（[LaunchStartMetrics]
 * 量到了几何）。此时**第一帧就必须是满不透明度**：系统闪屏一撤，Compose 立刻以同一个
 * Logo 接住，观众看到的是一次连续的开屏；若还从 alpha 0 淡入，就会出现
 * "Logo 先消失一下再淡出来"的闪断 —— 那才是真的分成两段。
 *
 * 交接之后的时间轴完全不变：静置 → 上浮淡出 → 内容浮现。
 */
internal fun logoAt(ms: Float, handoff: Boolean = false): Triple<Float, Float, Float> {
    if (handoff) {
        // 从"已经显现"的中段直接接上，跳过淡入与入场位移。
        if (ms < LogoOutStartMs) return Triple(1f, 0f, 1f)
    } else if (ms < LogoInEndMs) {
        return logoIn((ms / LogoInEndMs).coerceIn(0f, 1f))
    } else if (ms < LogoOutStartMs) {
        return Triple(1f, 0f, 1f)
    }
    val p = easeInQuad(
        ((ms - LogoOutStartMs) / (LogoOutEndMs - LogoOutStartMs)).coerceIn(0f, 1f)
    )
    return Triple(1f - p, -10f * p, 1f + 0.02f * p)
}

/** 按当前时间轴求底色不透明度。 */
internal fun curtainAt(ms: Float): Float {
    if (ms <= CurtainFadeStartMs) return 1f
    val u = ((ms - CurtainFadeStartMs) / (CurtainFadeEndMs - CurtainFadeStartMs))
        .coerceIn(0f, 1f)
    return 1f - easeOutCubic(u)
}

/**
 * 幕布本体。挂在所有内容之上，整段结束后由宿主移出组合。
 *
 * @param startMetrics 系统闪屏 Logo 的实测几何；有值时 Logo 直接沿用它的尺寸与位置，
 *                     让系统闪屏交接给 Compose 时不发生任何跳变。
 */
@Composable
fun LaunchCurtain(
    state: LaunchCurtainState,
    modifier: Modifier = Modifier,
    startMetrics: LaunchStartMetrics? = null
) {
    val density = LocalDensity.current.density

    // 底色必须与首页表面、系统闪屏背景逐像素相同。
    // 它是"页面自己的底色"，不是品牌色：这样底色淡去时画面不变，交接天然无缝。
    val surface = background withNight backgroundDark
    // Logo 恒为品牌红：浅色白底、深色黑底上都是同一个 Logo，不做任何反色。
    val logoColor = primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .drawWithContent {
                // 幕布不消费 drawContent：它是不透明(或半透明)的遮挡层，
                // 下面的首页由宿主另外正常绘制。
                val ms = state.clock.floatValue * LaunchTotalMs

                val curtain = curtainAt(ms)
                if (curtain > 0.001f) {
                    drawRect(color = surface.copy(alpha = curtain))
                }

                // 已经量到系统闪屏容器尺寸 = 屏幕上本来就有同一个 Logo，
                // 此时跳过淡入，第一帧就以满不透明度接住（否则会闪断成两段）。
                // 已经量到系统闪屏容器尺寸 = 屏幕上本来就有同一个 Logo，
                // 此时跳过淡入，第一帧就以满不透明度接住（否则会闪断成两段）。
                val containerPx = startMetrics?.logoSizePx?.floatValue ?: 0f
                val handoff = containerPx > 0f
                val (alpha, liftDp, scale) = logoAt(ms, handoff)
                if (alpha > 0.001f) {
                    // 尺寸只看写死的 dp 目标，**不看实测容器**：
                    // 目标字形宽 = LogoGlyphDp，一个 path 单位 = 目标宽 / 字形单位数。
                    // 首屏那张 drawable 用同样的字形占比如铺满 288dp 容器，
                    // 算出来也是同一个 dp，两段因此恒等。
                    val unitPx = LogoGlyphDp * density / GlyphWidthUnits * scale
                    // 位置一律取屏幕正中：系统闪屏在某些机型上会把图标抬高一点，
                    // 若沿用它的中心，"进入后的动画 Logo" 就不在正中了。
                    val center = Offset(
                        size.width / 2f,
                        size.height / 2f + liftDp * density
                    )
                    drawPath(
                        path = buildLogoPath(center, unitPx),
                        color = logoColor.copy(alpha = alpha)
                    )
                }
            }
    )
}

/** 按 viewport 单位换算出的 [unitPx] 构造 Logo 轮廓 Path。 */
private fun buildLogoPath(center: Offset, unitPx: Float): Path {
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

    LaunchedEffect(play, ready, systemAnimated) {
        if (!play || !systemAnimated) {
            state.forceFinish()
            onFinish()
            return@LaunchedEffect
        }
        // 等系统闪屏撤场能让首帧更干净，但**绝不能无限等**：
        // 幕布是不透明遮挡层，时钟停在 0 = 整屏永远是底色（浅色下就是白屏）。
        // 所以最多等一小会儿，超时立刻起跑。
        if (!ready) {
            kotlinx.coroutines.delay(SplashWaitFallbackMs)
        }

        // 用"帧推进"而不是 Animatable：Animatable 在某些机型上会因动画时长/取消
        // 语义停在半路，而这层是不透明的，停住就等于白屏。
        // 这里以真实时间直接推进时钟，并保证一定会走到终点。
        val startedAt = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val elapsedMs = ((now - startedAt) / 1_000_000f)
            state.clock.floatValue = (elapsedMs / LaunchTotalMs).coerceIn(0f, 1f)
            if (state.clock.floatValue >= 1f) break
        }

        state.forceFinish()
        onFinish()
    }

    return state
}

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
        translationY = (1f - p) * RevealLiftDp * density
    }
}
