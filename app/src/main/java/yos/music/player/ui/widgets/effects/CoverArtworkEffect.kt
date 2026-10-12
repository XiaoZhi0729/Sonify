/*
 * Adapted from Cresto's about-page card interactions, Apache-2.0.
 * Sources: app/ui/modifier/TiltModifier.kt (tiltOnPress) and
 *          app/ui/modifier/RippleModifier.kt (shaderRipple / RIPPLE_SHADER).
 * Modified for Sonify: three-phase feedback so the cover can yield to the shell's
 * vertical collapse drag (contact = tilt/scale only, silently revoked with a non-bouncy
 * tween; ripple/highlight/haptics only after the press is confirmed), press timing from
 * a raw Initial-pass pointer observer (clickable's PressInteraction is dispatched too
 * late for a fast tap), one host-side driver rendered on BOTH cover surfaces (the
 * Album↔Lyric morph hands the drawing over to the top-bar cover layer), Compose-drawn
 * soft ring instead of the AGSL ripple, values read only in the layer/draw phase.
 * See assets/licenses/cresto-NOTICE.txt.
 */
package yos.music.player.ui.widgets.effects

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import yos.music.player.ui.theme.YosRoundedCornerShape

// —— 以下参数逐项对齐 Cresto「关于」页那张卡片 ——
// 按压弹簧：smooth(300ms) 即 stiffness = (2π / 0.3)²、临界阻尼；松手 bouncy(0.2) 即阻尼比 0.5。
private const val CoverArtworkStiffness = 438.6f
private const val CoverArtworkPressDamping = 1f
private const val CoverArtworkReleaseDamping = 0.5f
// tiltOnPress(maxTilt)：Cresto「关于」页用 10°，其默认是 20°。轻点反馈要一眼看得出，
// 取 18°（比 10° 强近一倍）；这是"力度"的主旋钮。
private const val CoverArtworkMaxTilt = 18f
// 力度保底：轻点常落在封面正中，纯按位置算会得到 ≈0° 的倾角，立体感全无。
// 保证至少这个比例的力度（正中时退回"上沿略后退"的基础倾斜）。
private const val CoverArtworkMinTiltFraction = 0.45f
// 按下缩到 0.97：轻点常落在封面正中，此时按位置算的倾角接近 0（正面压下去本就不该歪），
// 缩放就是唯一对所有触点都成立的按压反馈——留足一档，别让它被忽略。
private const val CoverArtworkPressScaleDrop = 0.03f
private const val CoverArtworkCameraDistance = 12f
// 松手时倾角快速归零（不参与回弹）：160ms 内基本走完，封面落位时已经摆正。
private const val CoverArtworkTiltReleaseMillis = 160
// 抬手后"补满按下"的时长：点击是立即切页的，按下那半段（长按约 300ms 才到位）会在飞行里
// 继续推。用短 tween 而不是原按压弹簧，是为了让整段动效在封面落位（morph ≈350ms）前收尾。
private const val CoverArtworkCatchUpMillis = 140L
// Phase 2：竖直位移判定为"要收起播放页"的阈值，取 3.5dp——**早于系统 slop（≈8dp）**，
// 让被撤销的接触反馈在拖拽刚开始（约 1~2 帧）就收干净，而不是等 slop 判负后才弹回去。
private val CoverArtworkVerticalIntent = 3.5.dp
// Phase 2 撤销：无弹性快回。用带阻尼比的弹簧会在拖动中"弹一下"，那就是可见残影。
private const val CoverArtworkVetoMillis = 70L
// Phase 3：手指留在 slop 内够久（= 确认是按压而非拖拽）才放庆祝三件套。
private const val CoverArtworkConfirmMillis = 140L
// 描边高光点亮/淡出（点亮的动效由 celebrate 驱动，不是按下进度——按下不等于确认）。
private const val CoverArtworkGlowInMillis = 90
private const val CoverArtworkGlowOutMillis = 220
// shaderRipple：800ms 线性扩散，白色 0.08f，BlendMode.Plus；环半径 1.8 × 封面边长（Cresto 为 2000px）。
private const val CoverArtworkRippleMillis = 800
private const val CoverArtworkRippleExtent = 1.8f
private const val CoverArtworkRippleAlpha = 0.08f
private const val CoverArtworkRippleBlur = 0.4f
private const val CoverArtworkRippleThickness = 0.05f
private const val CoverArtworkRippleMax = 10
// 描边高光：确认按压时最亮 0.35f，静止态完全不可见（不动现有静止观感）。
private const val CoverArtworkRimAlpha = 0.35f
private val CoverArtworkRimWidth = 4.dp
private val CoverArtworkDefaultCorner = 8.dp

/** 一条涟漪：原点（按下指尖位置，大封面局部坐标）+ 0→1 的线性进度。 */
internal class CoverArtworkRipple(
    val origin: Offset,
    val progress: Animatable<Float, AnimationVector1D>
)

/**
 * 封面交互动效的共享状态：手势节点（大封面外框）写入，驱动协程推进 Animatable，
 * 渲染面（大封面 / 顶栏小封面）只读。
 *
 * 为什么要分"驱动"与"渲染面"两层：Album↔歌词/列表转场时**谁在画封面会换人**——
 * 大封面那一层当场停画，改由顶栏小封面那一层被 morph 绘制。动效若挂在渲染节点里，
 * 交接瞬间就跟着那一层一起消失，于是"点一下什么都看不到"。驱动放在宿主（播放页）里
 * 常驻，两个面各自渲染同一份状态。
 *
 * 另：动效触发不看 clickable 的 `PressInteraction`——**快速轻点时它要等到抬手之后才派发**
 * （真机实测：onClick 已执行、手势早已结束，Press/Release 才到），轻点会整段丢动效。
 */
@Stable
internal class CoverArtworkState {
    /** 手指按住中；拖拽让位（外壳收起手势接管）时立刻置 false。 */
    internal val pressed = mutableStateOf(false)

    /** 本次按下被判定为"拖拽让位"：接触反馈立即静默收回，不补满、不庆祝。 */
    internal val dragged = mutableStateOf(false)

    /** 按下时的触点（大封面节点局部坐标）：涟漪原点；倾角按它相对中心算。 */
    internal val position = mutableStateOf(Offset.Zero)

    /** 大封面尺寸（px）：另一渲染面做同伦映射的基准。 */
    internal val size = mutableStateOf(IntSize.Zero)

    // 动画值：只由 CoverArtworkDriver 推进。
    /** Phase 1 接触反馈：倾斜 + 缩放。 */
    internal val press = Animatable(0f)
    internal val tiltX = Animatable(0f)
    internal val tiltY = Animatable(0f)
    /** Phase 3 庆祝：描边高光的点亮程度（涟漪由 [ripples] 自管）。 */
    internal val glow = Animatable(0f)
    internal val ripples = mutableStateListOf<CoverArtworkRipple>()

    /** 本次按压是否已确认（庆祝只发一次）。只在驱动里读写，不需要参与重组。 */
    internal var confirmed = false
}

/**
 * 建立封面状态并启动驱动。**必须在播放页宿主（跨页面切换常驻）里调用一次**，
 * 不要放进某个会随转场离开组合的渲染子树。
 *
 * @param feedback 庆祝触感（确认是按压之后才给，不是按下瞬间）。
 */
@Composable
internal fun rememberCoverArtworkState(
    feedback: () -> Unit = {}
): CoverArtworkState {
    val state = remember("CoverArtwork_state") { CoverArtworkState() }
    CoverArtworkDriver(state, feedback)
    return state
}

/**
 * 唯一的动画驱动，三阶段：
 *
 * - **Phase 1 接触**（按下）：只给倾斜 + 缩放。"表面被按下去"是最便宜、也最容易无声撤回的信号。
 * - **Phase 2 意图判定**：外壳收起只有竖直方向，所以竖直位移一超
 *   [CoverArtworkVerticalIntent]（早于系统 slop）就判定"要收起播放页"，**无弹性静默收回**
 *   接触反馈，整段手势交还外壳；庆祝三件套一次都不放。
 * - **Phase 3 确认**：手指留在 slop 内够久（[CoverArtworkConfirmMillis]）或抬手且未被撤销，
 *   才放涟漪 + 描边高光 + 触感。轻点是在抬手/切页那一刻放，正好跟着封面飞走。
 */
@Composable
private fun CoverArtworkDriver(state: CoverArtworkState, feedback: () -> Unit) {
    val scope = rememberCoroutineScope()
    val currentFeedback = rememberUpdatedState(feedback)

    val pressSpec = remember("CoverArtwork_pressSpec") {
        spring<Float>(
            dampingRatio = CoverArtworkPressDamping,
            stiffness = CoverArtworkStiffness,
            visibilityThreshold = 0.0001f
        )
    }
    val releaseSpec = remember("CoverArtwork_releaseSpec") {
        spring<Float>(
            dampingRatio = CoverArtworkReleaseDamping,
            stiffness = CoverArtworkStiffness,
            visibilityThreshold = 0.0001f
        )
    }
    val catchUpSpec = remember("CoverArtwork_catchUpSpec") {
        tween<Float>(
            durationMillis = CoverArtworkCatchUpMillis.toInt(),
            easing = FastOutSlowInEasing
        )
    }
    val tiltReleaseSpec = remember("CoverArtwork_tiltReleaseSpec") {
        tween<Float>(
            durationMillis = CoverArtworkTiltReleaseMillis,
            easing = FastOutSlowInEasing
        )
    }
    val vetoSpec = remember("CoverArtwork_vetoSpec") {
        tween<Float>(
            durationMillis = CoverArtworkVetoMillis.toInt(),
            easing = FastOutSlowInEasing
        )
    }
    val glowInSpec = remember("CoverArtwork_glowInSpec") {
        tween<Float>(
            durationMillis = CoverArtworkGlowInMillis,
            easing = FastOutSlowInEasing
        )
    }
    val glowOutSpec = remember("CoverArtwork_glowOutSpec") {
        tween<Float>(
            durationMillis = CoverArtworkGlowOutMillis,
            easing = FastOutSlowInEasing
        )
    }

    LaunchedEffect(Unit) {
        snapshotFlow { state.pressed.value }
            .distinctUntilChanged()
            // 丢掉订阅时的初值（false）：否则进播放页会被当成一次"抬手"，
            // 凭空播一遍按下+回弹。
            .drop(1)
            .collectLatest { pressedNow ->
                val size = state.size.value
                val at = state.position.value
                val tilt = tiltTargets(at, size)
                if (pressedNow) {
                    // Phase 1：接触反馈；同时安排 Phase 3 的长按路径。
                    state.confirmed = false
                    state.glow.snapTo(0f)
                    coroutineScope {
                        launch { state.press.animateTo(1f, pressSpec) }
                        launch { state.tiltX.animateTo(tilt.x, pressSpec) }
                        launch { state.tiltY.animateTo(tilt.y, pressSpec) }
                        launch {
                            delay(CoverArtworkConfirmMillis)
                            // 拖拽早在 Phase 2 撤销了；仍按住才确认。
                            if (state.pressed.value && !state.dragged.value) {
                                confirmCelebration(state, at, scope, currentFeedback.value)
                            }
                        }
                    }
                } else if (state.dragged.value) {
                    // Phase 2：让位给外壳收起拖拽——无弹性静默收回，不补满、不庆祝。
                    android.util.Log.d("CoverFx", "fx up：拖拽让位，静默收回")
                    coroutineScope {
                        launch { state.press.animateTo(0f, vetoSpec) }
                        launch { state.tiltX.animateTo(0f, vetoSpec) }
                        launch { state.tiltY.animateTo(0f, vetoSpec) }
                        launch { state.glow.animateTo(0f, vetoSpec) }
                    }
                } else {
                    // Phase 3（轻点路径）：抬手即确认，庆祝跟着切页/飞行一起发生。
                    confirmCelebration(state, at, scope, currentFeedback.value)
                    coroutineScope {
                        launch { state.press.animateTo(1f, catchUpSpec) }
                        launch { state.tiltX.animateTo(tilt.x, catchUpSpec) }
                        launch { state.tiltY.animateTo(tilt.y, catchUpSpec) }
                        delay(CoverArtworkCatchUpMillis)
                    }
                    coroutineScope {
                        launch { state.press.animateTo(0f, releaseSpec) }
                        launch { state.tiltX.animateTo(0f, tiltReleaseSpec) }
                        launch { state.tiltY.animateTo(0f, tiltReleaseSpec) }
                        launch { state.glow.animateTo(0f, glowOutSpec) }
                    }
                }
            }
    }
}

/**
 * Phase 3：庆祝三件套——涟漪 + 描边高光 + 触感。只发一次（[CoverArtworkState.confirmed]）。
 * 涟漪挂在常驻 scope 上：松手或下一次按压都不会打断它的扩散。
 */
private fun confirmCelebration(
    state: CoverArtworkState,
    at: Offset,
    scope: CoroutineScope,
    feedback: () -> Unit
) {
    if (state.confirmed) return
    state.confirmed = true
    feedback()
    val progress = Animatable(0f)
    val ripple = CoverArtworkRipple(at, progress)
    if (state.ripples.size >= CoverArtworkRippleMax) state.ripples.removeAt(0)
    state.ripples += ripple
    scope.launch {
        progress.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = CoverArtworkRippleMillis,
                easing = LinearEasing
            )
        )
        state.ripples.remove(ripple)
    }
    scope.launch { state.glow.animateTo(1f, tween(CoverArtworkGlowInMillis)) }
}

/**
 * 触点 → 倾角（度）：朝按下的一侧倾斜，边缘倾满 ±[CoverArtworkMaxTilt]，
 * 横轴取负（与 Cresto `tiltOnPress` 同向）。
 *
 * 力度有 [CoverArtworkMinTiltFraction] 保底，方向仍取触点方向（只放大、不转向）；
 * 触点落在正中时方向退化，退回"上沿略后退"的基础倾斜——保证任何落点都看得出被按下去。
 */
private fun tiltTargets(at: Offset, size: IntSize): Offset {
    if (size.width <= 0 || size.height <= 0) return Offset.Zero
    val nx = (at.x / size.width) * 2f - 1f
    val ny = (at.y / size.height) * 2f - 1f
    val magnitude = hypot(nx, ny).coerceIn(0f, 1f)
    if (magnitude < 1e-3f) {
        return Offset(-CoverArtworkMinTiltFraction * CoverArtworkMaxTilt, 0f)
    }
    val k = max(magnitude, CoverArtworkMinTiltFraction) / magnitude * CoverArtworkMaxTilt
    return Offset(-ny * k, nx * k)
}

/**
 * 封面手势节点（挂在**大封面外框**上）：单击开/关歌词。
 *
 * - `clickable` 只负责**点击判定与无障碍语义**（拖拽超 slop 或被别人消费时它自己不会触发
 *   点击），无涟漪 indication（封面是图片，不该出现水波纹）。点击**立即生效**：封面飞向
 *   顶栏与按下动效同时跑，动效由两个渲染面接力画完。
 * - `pointerInput` 在 Initial pass **只观察、绝不消费**：给出按下时刻/触点，并在竖直位移
 *   超 [CoverArtworkVerticalIntent]（早于系统 slop）或事件被别人消费时判为"拖拽让位"，
 *   让接触反馈立刻静默收回。本项目根节点那个"控件焦点占用"观察层就是同一写法。
 *
 * @param enabled 按压/点击时才求值的闸门（小封面态必须为 false）。传 lambda 而不是
 *                Boolean：调用点每次重组都会新建实例，闭包里的状态因此在那一刻读取。
 */
@Composable
internal fun Modifier.coverArtworkGesture(
    state: CoverArtworkState,
    enabled: () -> Boolean,
    onClick: () -> Unit
): Modifier {
    val isEnabled = rememberUpdatedState(enabled)
    val currentOnClick = rememberUpdatedState(onClick)

    return this
        .onSizeChanged { state.size.value = it }
        .pointerInput(Unit) {
            val verticalIntentPx = CoverArtworkVerticalIntent.toPx()
            try {
                awaitPointerEventScope {
                    var downAt = Offset.Zero
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.pressed }
                        if (change == null) {
                            // 抬手：结束本次按压（点击由 clickable 负责，这里只管动效）
                            if (state.pressed.value) state.pressed.value = false
                            continue
                        }
                        if (change.changedToDownIgnoreConsumed()) {
                            if (!isEnabled.value()) {
                                state.pressed.value = false
                                continue
                            }
                            downAt = change.position
                            state.dragged.value = false
                            state.position.value = change.position
                            state.pressed.value = true
                            android.util.Log.d("CoverFx", "gesture down：pos=$downAt")
                        } else if (state.pressed.value && !state.dragged.value) {
                            val dx = change.position.x - downAt.x
                            val dy = change.position.y - downAt.y
                            // 竖直意图（外壳收起只有竖直方向）优先于一切：早于系统 slop 判定，
                            // 让接触反馈在拖拽刚开始就收回，不留下"闪一下"的残影。
                            val verticalIntent = abs(dy) > verticalIntentPx &&
                                abs(dy) > abs(dx) * 1.2f
                            if (change.isConsumed || verticalIntent ||
                                hypot(dx.toDouble(), dy.toDouble()) >
                                viewConfiguration.touchSlop
                            ) {
                                state.dragged.value = true
                                state.pressed.value = false
                                android.util.Log.d(
                                    "CoverFx",
                                    "gesture veto：dx=$dx dy=$dy"
                                )
                            }
                        }
                    }
                }
            } finally {
                // 手势取消 / 离开组合：复位，别让"按下态"卡住。
                state.pressed.value = false
            }
        }
        .clickable(
            interactionSource = remember("CoverArtwork_clickSource") {
                MutableInteractionSource()
            },
            indication = null
        ) {
            if (!isEnabled.value()) return@clickable
            android.util.Log.d("CoverFx", "click：立即切页")
            currentOnClick.value()
        }
}

/**
 * 交互动效的**渲染面**：可以挂在大封面上，也可以挂在顶栏小封面上（转场交接后由它接着画）。
 *
 * 硬约束（都是本项目已经踩过的坑，改这里前先读）：
 *
 * 1. 必须挂在 **sharedElement 之内**（即 `.then(sharedElementModifier)` 之后）：morph 的
 *    落点读节点布局坐标，变换层若在 sharedElement 外层，按压尚未回位就切页时会把缩放/旋转
 *    渗进 morph 源矩形。
 * 2. 所有动画值只在 graphicsLayer / onDrawWithContent 块内读取：在组合里读会让按压弹簧
 *    每帧重组整棵封面子树（含 Coil 请求重建），是逐帧重组的坑。
 * 3. 除大封面之外的渲染面（69dp 顶栏封面）必须做**同伦映射**：画在它那一层的坐标/尺寸，
 *    会被 morph 等比放大到动画矩形上，所以涟漪原点、描边宽、cameraDistance 都要按
 *    "本层尺寸 / 大封面尺寸"缩放，观感才与大封面一致（涟漪环半径本来就是尺寸相对值，无需处理）。
 *
 * @param cornerRadius 本渲染面自身的圆角（大封面 8dp、顶栏封面 5dp），用于描边高光贴合。
 */
@Composable
internal fun Modifier.coverArtworkEffect(
    state: CoverArtworkState,
    cornerRadius: Dp = CoverArtworkDefaultCorner
): Modifier {
    val shape: Shape = remember("CoverArtwork_shape", cornerRadius) {
        YosRoundedCornerShape(cornerRadius)
    }
    return this
        .graphicsLayer {
            // 同伦映射：本层尺寸 / 大封面尺寸（大封面上为 1）。
            val ref = state.size.value
            val scale = if (ref.width > 0) size.width / ref.width.toFloat() else 1f
            // Phase 1 接触反馈：朝按下的一侧倾斜，同时缩到 0.97，相机距离同步缩放保持透视一致。
            rotationX = state.tiltX.value
            rotationY = state.tiltY.value
            val pressedScale = 1f - CoverArtworkPressScaleDrop * state.press.value
            scaleX = pressedScale
            scaleY = pressedScale
            cameraDistance = CoverArtworkCameraDistance * density * scale
        }
        .drawWithCache {
            val ref = state.size.value
            val scale = if (ref.width > 0) size.width / ref.width.toFloat() else 1f
            // outline / path / brush / stroke 只在尺寸变化时重算，绘制期零分配。
            val outline = shape.createOutline(size, layoutDirection, this)
            val path = outline.coverArtworkPath()
            val rimBrush = Brush.verticalGradient(
                0f to Color.White.copy(alpha = CoverArtworkRimAlpha),
                1f to Color.White.copy(alpha = CoverArtworkRimAlpha * 0.12f)
            )
            val rimStroke = Stroke(width = CoverArtworkRimWidth.toPx() * scale)
            onDrawWithContent {
                drawContent()
                // 本节点是正方形，四个角落在封面圆角之外：不裁剪就会在封面角上留下白晕。
                clipPath(path) {
                    // Phase 3：描边高光跟随"确认"而不是"按下"——按下去不等于确认。
                    val glow = state.glow.value
                    if (glow > 0.002f) {
                        drawOutline(
                            outline = outline,
                            brush = rimBrush,
                            alpha = glow,
                            style = rimStroke,
                            blendMode = BlendMode.Plus
                        )
                    }
                    state.ripples.forEach { ripple ->
                        drawCoverArtworkRipple(
                            origin = ripple.origin * scale,
                            progress = ripple.progress.value
                        )
                    }
                }
            }
        }
}

/** Outline → Path：Compose 没有公开 API，与 CrestoDialogEffects 同款私有实现。 */
private fun Outline.coverArtworkPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

/**
 * 一条涟漪：从原点扩散出去的软环。
 *
 * 复刻 Cresto RIPPLE_SHADER 的几何与透明度曲线（半径 = 进度 × 满半径、环厚 5%、
 * 软边 40%、先 ease-out 渐入 20% 再 ease-in 渐出，白色 Plus 叠加），但用径向渐变而不是
 * AGSL runtime shader——不依赖 API 33、没有 shader 编译风险，各机型观感一致。
 *
 * 透明端色必须是"白 + alpha 0"而不是 Color.Transparent（后者是黑 + alpha 0，
 * 插值中途会掺黑出灰边）。
 */
private fun DrawScope.drawCoverArtworkRipple(origin: Offset, progress: Float) {
    if (progress <= 0.01f) return
    val extent = size.maxDimension * CoverArtworkRippleExtent
    val radius = progress * extent
    val thickness = extent * CoverArtworkRippleThickness
    val blur = extent * CoverArtworkRippleBlur
    val outer = radius + thickness + blur
    if (outer <= 0f) return

    val ramp = if (progress < 0.2f) {
        val t = progress / 0.2f
        1f - (1f - t) * (1f - t)
    } else {
        val t = (progress - 0.2f) / 0.8f
        1f - t * t
    }
    val alpha = ramp * CoverArtworkRippleAlpha
    if (alpha <= 0.001f) return

    val white = Color.White.copy(alpha = alpha)
    val clear = Color.White.copy(alpha = 0f)
    val inner = radius - thickness - blur
    val stops = buildList {
        if (inner > 0f) add(inner / outer to clear)
        add((radius - thickness).coerceAtLeast(0f) / outer to white)
        add((radius + thickness).coerceAtMost(outer) / outer to white)
        add(1f to clear)
    }
    drawRect(
        brush = Brush.radialGradient(
            colorStops = stops.toTypedArray(),
            center = origin,
            radius = outer
        ),
        blendMode = BlendMode.Plus
    )
}
