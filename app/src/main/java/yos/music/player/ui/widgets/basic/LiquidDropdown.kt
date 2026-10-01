package yos.music.player.ui.widgets.basic

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur as contentBlur
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.IntrinsicMeasureScope
import androidx.compose.ui.layout.IntrinsicMeasurable
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasurePolicy
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import kotlin.math.ceil
import kotlin.math.min

// =====================================================================
// 动画参数（移植自 NexioSchedule ListPopupLayout 本地参数，逐值对齐）
// =====================================================================

private val FractionEnterAnimSpec =
    spring<Float>(dampingRatio = 0.78f, stiffness = 232f, visibilityThreshold = 0.0001f)
private val FractionExitAnimSpec =
    spring<Float>(dampingRatio = 0.78f, stiffness = 400f, visibilityThreshold = 0.0001f)
private val AlphaEnterAnimSpec = tween<Float>(durationMillis = 120)
private val AlphaExitAnimSpec = tween<Float>(durationMillis = 320)

private val PopupMinWidth = 200.dp
private val PopupMaxWidth = 288.dp
private val PopupMinHeight = 50.dp
private val PopupCornerRadius = 25.dp
private val PopupShadowPadding = 24.dp

/** 缩放区间：0.24 → 1.0（Nexio 原值） */
private const val ScaleBase = 0.24f

/**
 * 弹层动画进度：[fraction] 是揭示进度（与面板同源，弹簧会过冲越过 1 再回落），
 * [position] 是本次展开**实际**的朝向（由放置结果回写，不是调用方的意图——
 * 下方放不下会翻到上方，联动位移的方向必须跟着真实朝向走）。
 */
data class LiquidDropdownProgress(
    val fraction: Float,
    val position: LiquidDropdownLayoutPosition,
)

/** 弹层放置方向：决定裁剪揭示方向与缩放锚点角 */
data class LiquidDropdownLayoutPosition(
    val showBelow: Boolean = true,
    val showAbove: Boolean = false,
    val isRightAligned: Boolean = true,
)

/**
 * 弹层相对锚点的摆位。
 *
 * Miuix 0.9.4 的级联弹层（CascadingListPopupLayout.computeSecondaryRect）把子面板摆在
 * 触发行的顶边（盖住它，行本身变成子面板的标题行）；本项目的二级菜单只有两行入口，
 * 盖住后父面板会完全消失、读不出堆叠层次，所以堆叠模式改成**贴该行下缘**，
 * 保留父面板在上方露出（缩 0.95 + 压暗）。左缘仍与锚点行对齐，宽度同父面板。
 */
enum class LiquidDropdownPlacement {
    /** 现有行为：右缘内收 27dp、顶边压在锚点行上方 31dp（覆盖锚点是 Nexio 的原生语义）。*/
    OverlayAnchor,

    /** 堆叠子面板：可见左缘 == 锚点行左缘，可见顶边 == 锚点行下缘 + 间隙。*/
    StackBelowAnchor,
}

/** 堆叠子面板与锚点行之间的间隙（让两块表面读得出是分开的）。*/
private val StackGap = 10.dp

/** 子面板展开时父面板的退让量（Miuix PRIMARY_SHRUNK_SCALE）。*/
private const val RecedeScale = 0.95f

private data class LiquidDropdownPositionResult(
    val offset: IntOffset,
    val layoutPosition: LiquidDropdownLayoutPosition,
)

/**
 * 窗口系定位（移植 Nexio liquidDropdownPositionProvider，px 偏移换算为 dp）：
 * 右缘对齐锚点行右缘内收 27dp；默认在锚点下方且整体上移 31dp，
 * 下方空间不足翻到上方，上下都不足时垂直居中；最终 clamp 进窗口。
 * [outerSize] 是含阴影透明外扩的面板外框（参与放置与钳制）；
 * [visibleSize] 是可见面板（拟合方向判断用，避免高菜单被误判放不下）。
 */
private fun Density.calculateDropdownPosition(
    anchorBounds: IntRect,
    windowBounds: IntRect,
    outerSize: IntSize,
    visibleSize: IntSize,
    alignCenterHorizontally: Boolean = false,
    placement: LiquidDropdownPlacement = LiquidDropdownPlacement.OverlayAnchor,
): LiquidDropdownPositionResult {
    val offsetDeltaX = 27.dp.roundToPx()
    val offsetDeltaY = 31.dp.roundToPx()
    val padPx = PopupShadowPadding.roundToPx()

    if (placement == LiquidDropdownPlacement.StackBelowAnchor) {
        // 堆叠：面板外框含 24dp 透明阴影外扩，所以可见边缘 = 外框边缘 + padPx
        val stackX = anchorBounds.left - padPx
        val gapPx = StackGap.roundToPx()
        val spaceBelow = windowBounds.bottom - (anchorBounds.bottom + gapPx)
        val spaceAbove = (anchorBounds.top - gapPx) - windowBounds.top
        val offsetY: Int
        val position: LiquidDropdownLayoutPosition
        when {
            spaceBelow > visibleSize.height -> {
                offsetY = anchorBounds.bottom + gapPx - padPx
                position = LiquidDropdownLayoutPosition(
                    showBelow = true, showAbove = false, isRightAligned = false
                )
            }
            spaceAbove > visibleSize.height -> {
                offsetY = anchorBounds.top - gapPx - visibleSize.height - padPx
                position = LiquidDropdownLayoutPosition(
                    showBelow = false, showAbove = true, isRightAligned = false
                )
            }
            else -> {
                offsetY = anchorBounds.center.y - outerSize.height / 2
                position = LiquidDropdownLayoutPosition(
                    showBelow = false, showAbove = false, isRightAligned = false
                )
            }
        }
        val clampedStack = IntOffset(
            x = stackX.coerceIn(
                windowBounds.left,
                (windowBounds.right - outerSize.width).coerceAtLeast(windowBounds.left),
            ),
            y = offsetY.coerceIn(
                windowBounds.top,
                (windowBounds.bottom - outerSize.height).coerceAtLeast(windowBounds.top),
            ),
        )
        return LiquidDropdownPositionResult(clampedStack, position)
    }

    // 居中模式：菜单上边中点对齐锚点上边中点（缩放原点即锚点，"从被按控件长出"）
    val offsetX = if (alignCenterHorizontally) {
        anchorBounds.center.x - outerSize.width / 2
    } else {
        anchorBounds.right - outerSize.width + offsetDeltaX
    }
    val spaceBelow = windowBounds.bottom - anchorBounds.bottom
    val spaceAbove = anchorBounds.top - windowBounds.top
    val offsetY: Int
    val position: LiquidDropdownLayoutPosition
    if (spaceBelow > visibleSize.height) {
        offsetY = anchorBounds.top - offsetDeltaY
        position = LiquidDropdownLayoutPosition(showBelow = true)
    } else if (spaceAbove > visibleSize.height) {
        offsetY = anchorBounds.bottom - outerSize.height + offsetDeltaY
        position = LiquidDropdownLayoutPosition(showBelow = false, showAbove = true)
    } else {
        offsetY = anchorBounds.top + anchorBounds.height / 2 - outerSize.height / 2
        position = LiquidDropdownLayoutPosition(showBelow = false, showAbove = false)
    }

    val clamped = IntOffset(
        x = offsetX.coerceIn(
            windowBounds.left,
            (windowBounds.right - outerSize.width).coerceAtLeast(windowBounds.left),
        ),
        y = offsetY.coerceIn(
            windowBounds.top,
            (windowBounds.bottom - outerSize.height).coerceAtLeast(windowBounds.top),
        ),
    )
    return LiquidDropdownPositionResult(clamped, position)
}

/**
 * 液态玻璃下拉弹层（移植自 NexioSchedule"默认首页"的 OverlayDropdown 弹层链：
 * ListPopupLayout + ListPopupContent + ListPopupColumn，动画与视觉逐值对齐）。
 *
 * 宿主：优先渲染进页面级 [LocalTitleOverlayHost]（Title 页根 Box 顶层，与页面同窗口
 * 同帧——Nexio 的 root Scaffold 宿主同思路），没有独立 Popup 窗口的创建/合成延迟，
 * 展开弹簧的起步不会被截断；无宿主时退回 androidx Popup 独立窗口。
 *
 * 动画：单 fraction 驱动——缩放 0.24→1、transformOrigin 从锚点角插值到中心、
 * 方向性裁剪揭示（revealLimitHeight>0 时从两行薄片展开）、内容 blur 8dp→0；
 * 进入 spring(0.78/232) + alpha tween(120)，退出 spring(0.78/400) + alpha tween(320)，
 * 阴影迟到淡入（fraction≥0.78）与提前消失（≥0.99）。
 *
 * 玻璃：[backdrop] 非 null 且 API≥33 时 vibrancy+blur 采样背后的页面内容，
 * 否则走近实色兜底面板。
 *
 * @param anchorBounds 触发行在窗口坐标系中的边界（调用方经 onGloballyPositioned 捕获）
 * @param onFractionProgress 弹层动画进度回调（逐帧，包含真实朝向）：驱动触发元件本身的
 *               联动（淡出/收缩/沿揭示方向微移），用法见 [rememberLiquidDropdownFollowState]
 * @param placement 相对锚点的摆位；二级堆叠子面板传 [LiquidDropdownPlacement.StackBelowAnchor]
 * @param zIndex 同一宿主内多块面板相叠时的绘制序（宿主 slots 是 HashMap 快照，插入序不可控，
 *               靠 Box 内的 placeOrder 显式抬层）；子面板传大于父面板的值
 * @param recedeFraction 父面板退让进度 0..1（子面板展开时）：乘 0.95 缩放并盖一层压暗遮罩，
 *                       语义同 Miuix CascadingPrimaryContent 的 primaryScale + maskAlpha
 */
@Composable
fun LiquidDropdownLayout(
    expanded: Boolean,
    anchorBounds: IntRect,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
    revealLimitHeight: Dp = 0.dp,
    backdrop: Backdrop? = null,
    alignCenterHorizontally: Boolean = false,
    surfaceAlpha: Float? = null,
    placement: LiquidDropdownPlacement = LiquidDropdownPlacement.OverlayAnchor,
    zIndex: Float = 0f,
    recedeFraction: (() -> Float)? = null,
    onFractionProgress: ((LiquidDropdownProgress) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val host = LocalTitleOverlayHost.current
    if (host != null) {
        // 同窗口渲染（首选）：弹层挂在 Title 页根 Box 顶层，与页面同窗口同帧，
        // 展开动画与 Nexio 的 LaunchedEffect(show) 同帧起跑。
        // 注册必须在 SideEffect（apply 阶段）写入——组合期写宿主状态不会触发宿主重组。
        // 无条件常驻注册（关闭时 body 自渲染为空），退出动画期间内容不会被卸载。
        val slotKey = remember { Any() }
        SideEffect {
            host.set(slotKey) {
                LiquidDropdownBody(
                    expanded = expanded,
                    anchorBounds = anchorBounds,
                    onDismissRequest = onDismissRequest,
                    onDismissFinished = onDismissFinished,
                    revealLimitHeight = revealLimitHeight,
                    backdrop = backdrop,
                    alignCenterHorizontally = alignCenterHorizontally,
                    surfaceAlpha = surfaceAlpha,
                    placement = placement,
                    zIndex = zIndex,
                    recedeFraction = recedeFraction,
                    onFractionProgress = onFractionProgress,
                    content = content,
                )
            }
        }
        DisposableEffect(slotKey) { onDispose { host.remove(slotKey) } }
    } else {
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset.Zero,
            properties = PopupProperties(focusable = false),
        ) {
            LiquidDropdownBody(
                expanded = expanded,
                anchorBounds = anchorBounds,
                onDismissRequest = onDismissRequest,
                onDismissFinished = onDismissFinished,
                revealLimitHeight = revealLimitHeight,
                backdrop = backdrop,
                alignCenterHorizontally = alignCenterHorizontally,
                surfaceAlpha = surfaceAlpha,
                placement = placement,
                zIndex = zIndex,
                recedeFraction = recedeFraction,
                onFractionProgress = onFractionProgress,
                content = content,
            )
        }
    }
}

/**
 * 弹层本体（宿主/Popup 两条路径共用）：动画引擎 + 全屏点外关闭/返回层 + 定位面板。
 */
@Composable
private fun LiquidDropdownBody(
    expanded: Boolean,
    anchorBounds: IntRect,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    revealLimitHeight: Dp,
    backdrop: Backdrop?,
    alignCenterHorizontally: Boolean,
    surfaceAlpha: Float?,
    placement: LiquidDropdownPlacement,
    zIndex: Float,
    recedeFraction: (() -> Float)?,
    onFractionProgress: ((LiquidDropdownProgress) -> Unit)?,
    content: @Composable () -> Unit,
) {
    val fractionProgress = remember { Animatable(0f) }
    val alphaProgress = remember { Animatable(0f) }
    var internalVisible by remember { mutableStateOf(false) }
    var layoutPosition by remember { mutableStateOf(LiquidDropdownLayoutPosition()) }
    var anchorOrigin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val currentOnFractionProgress by rememberUpdatedState(onFractionProgress)
    val revealLimitHeightPx = with(LocalDensity.current) { revealLimitHeight.toPx() }

    LaunchedEffect(expanded) {
        if (expanded) {
            internalVisible = true
            launch { fractionProgress.animateTo(1f, FractionEnterAnimSpec) }
            launch { alphaProgress.animateTo(1f, AlphaEnterAnimSpec) }
        } else {
            if (!internalVisible) return@LaunchedEffect
            launch { fractionProgress.animateTo(0f, FractionExitAnimSpec) }
            alphaProgress.animateTo(0f, AlphaExitAnimSpec)
            fractionProgress.snapTo(0f)
            alphaProgress.snapTo(0f)
            internalVisible = false
            currentOnDismissFinished()
        }
    }

    LaunchedEffect(Unit) {
        currentOnFractionProgress?.let { callback ->
            // 朝向也进快照：它是在 measure 阶段回写的，单靠 fraction 变化发不出新朝向
            snapshotFlow { LiquidDropdownProgress(fractionProgress.value, layoutPosition) }
                .collect { callback(it) }
        }
    }

    if (!expanded && !internalVisible) return

    // 弹层根在窗口原点（alignment TopStart + 零偏移），全屏承接点外关闭与返回键；
    // 面板在布局期用窗口坐标自定位（与 Nexio layout 阶段定位同思路）
    var scrimOrigin by remember { mutableStateOf(IntOffset.Zero) }

    BackHandler(enabled = expanded) { currentOnDismissRequest() }
    Box(
        modifier = Modifier
            .zIndex(zIndex)
            .fillMaxSize()
            .onGloballyPositioned {
                scrimOrigin = IntOffset(it.positionInWindow().x.toInt(), it.positionInWindow().y.toInt())
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { currentOnDismissRequest() })
            }
    ) {
        Box(
            modifier = Modifier
                .layout { measurable, constraints ->
                    val padPx = PopupShadowPadding.roundToPx()
                    val minHeightPx = PopupMinHeight.roundToPx()
                    val maxHeightPx = (constraints.maxHeight - 2 * padPx)
                        .coerceAtLeast(minHeightPx)
                    val placeable = measurable.measure(
                        constraints.copy(
                            minWidth = PopupMinWidth.roundToPx()
                                .coerceAtMost(constraints.maxWidth),
                            minHeight = 0,
                            maxHeight = maxHeightPx,
                        )
                    )
                    val outerSize = IntSize(placeable.width, placeable.height)
                    val windowBounds = IntRect(0, 0, constraints.maxWidth, constraints.maxHeight)
                    // 拟合方向判断用可见面板高度（去掉阴影的透明外扩）：
                    // 含外扩会把高菜单误判为"下方放不下"而被钳到屏幕顶端、脱离锚点行
                    val visibleSize = IntSize(
                        (outerSize.width - 2 * padPx).coerceAtLeast(1),
                        (outerSize.height - 2 * padPx).coerceAtLeast(1),
                    )
                    val result = calculateDropdownPosition(
                        anchorBounds, windowBounds, outerSize, visibleSize,
                        alignCenterHorizontally, placement
                    )
                    if (result.layoutPosition != layoutPosition) {
                        layoutPosition = result.layoutPosition
                    }
                    // 缩放原点：右对齐取菜单右上角，居中模式取上边中点（都是最靠近锚点的点），
                    // 随 fraction 从该点插值到中心——"从被按控件逐渐展开成弹层整体"
                    val origin = TransformOrigin(
                        pivotFractionX = when {
                            alignCenterHorizontally -> 0.5f
                            result.layoutPosition.isRightAligned -> 1f
                            else -> 0f
                        },
                        pivotFractionY = if (result.layoutPosition.showAbove) 1f else 0f,
                    )
                    if (origin != anchorOrigin) {
                        anchorOrigin = origin
                    }
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place(
                            IntOffset(
                                x = result.offset.x - scrimOrigin.x,
                                y = result.offset.y - scrimOrigin.y,
                            )
                        )
                    }
                }
        ) {
            LiquidDropdownContent(
                fractionProgress = { fractionProgress.value },
                alphaProgress = { alphaProgress.value },
                layoutPosition = layoutPosition,
                originAnchor = anchorOrigin,
                backdrop = backdrop,
                revealLimitHeightPx = revealLimitHeightPx,
                surfaceAlpha = surfaceAlpha,
                recedeFraction = recedeFraction,
                content = content,
            )
        }
    }
}

/**
 * 弹层视觉本体（移植 Nexio ListPopupContent）：阴影迟到淡入、缩放/透明度/锚点角
 * transformOrigin、方向性裁剪揭示、内容模糊渐入、玻璃面板与边缘光。
 */
@Composable
private fun LiquidDropdownContent(
    fractionProgress: () -> Float,
    alphaProgress: () -> Float,
    layoutPosition: LiquidDropdownLayoutPosition,
    originAnchor: TransformOrigin,
    backdrop: Backdrop?,
    revealLimitHeightPx: Float,
    surfaceAlpha: Float?,
    recedeFraction: (() -> Float)?,
    content: @Composable () -> Unit,
) {
    val isDark = isFlamingoInDarkMode()
    val containerColor = Color.White withNight Color(0xFF242424)
    // surfaceAlpha 覆盖：调用方（如播放页音质下拉）可单独指定更通透的表面不透明度
    val glassSurfaceAlpha = surfaceAlpha ?: if (isDark) 0.8f else 0.72f
    val fallbackSurfaceAlpha = 0.97f
    val edgeLightColor = if (!isDark) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.4f)
    val density = LocalDensity.current

    // 阴影：进入 fraction≥0.78 渐显（tween 200），退出 ≥0.99 消失（tween 50；中断即无）
    val shadowAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        var prevFraction = fractionProgress()
        var shadowVisible = false
        var animationJob: Job? = null
        snapshotFlow { fractionProgress() }
            .collect { current ->
                val isEntering = current >= prevFraction
                prevFraction = current
                val newVisible = if (isEntering) current >= 0.78f else current >= 0.99f
                if (newVisible != shadowVisible) {
                    shadowVisible = newVisible
                    animationJob?.cancel()
                    animationJob = launch {
                        if (newVisible) {
                            shadowAlpha.animateTo(1f, tween(200))
                        } else if (shadowAlpha.value >= 1f) {
                            shadowAlpha.animateTo(0f, tween(50))
                        } else {
                            shadowAlpha.snapTo(0f)
                        }
                    }
                }
            }
    }

    Box(
        modifier = Modifier
            .padding(PopupShadowPadding)
            .drawBehind {
                val alpha = shadowAlpha.value
                if (alpha <= 0f) return@drawBehind
                val baseAlpha = (32 * alpha).toInt().coerceIn(0, 255)
                // 注意用 DrawScope 自身的 density（外层同名 val 是组合域的 LocalDensity）
                val blurRadius = 16f * this.density
                val nativePath = android.graphics.Path().apply {
                    addRoundRect(
                        0f, 0f, size.width, size.height,
                        PopupCornerRadius.toPx(), PopupCornerRadius.toPx(),
                        android.graphics.Path.Direction.CW
                    )
                }
                val paint = android.graphics.Paint().apply {
                    color = android.graphics.Color.argb(baseAlpha, 0, 0, 0)
                    maskFilter = BlurMaskFilter(
                        blurRadius.coerceAtLeast(0.1f),
                        BlurMaskFilter.Blur.NORMAL
                    )
                }
                drawIntoCanvas { canvas ->
                    canvas.nativeCanvas.drawPath(nativePath, paint)
                }
            }
    ) {
        Box(
            modifier = Modifier
                .graphicsLayer {
                    // 与 Nexio 一致：fraction 不裁剪，spring(0.78) 过冲到 ~1.03 再回落，
                    // 缩放/锚点角都跟着越过目标再弹回——这是原版曲线的收尾手感
                    val fraction = fractionProgress()
                    val scale = ScaleBase + (1f - ScaleBase) * fraction
                    // 退让：子面板展开时父面板缩到 0.95（Miuix PRIMARY_SHRUNK_SCALE），
                    // 与进入缩放共用同一个 transformOrigin（从弹层出生角缩）
                    val recede = recedeFraction?.invoke() ?: 0f
                    val shrunk = scale * (1f - (1f - RecedeScale) * recede)
                    scaleX = shrunk
                    scaleY = shrunk
                    alpha = alphaProgress()
                    // 缩放锚点：从锚点角插值到中心（Nexio 同公式）
                    transformOrigin = TransformOrigin(
                        pivotFractionX = originAnchor.pivotFractionX +
                                (0.5f - originAnchor.pivotFractionX) * fraction,
                        pivotFractionY = originAnchor.pivotFractionY +
                                (0.5f - originAnchor.pivotFractionY) * fraction,
                    )
                }
                // 方向性裁剪揭示（在 blur 外层，裁掉模糊产生的圆角溢出）
                .popupClipReveal(
                    fractionProgress = fractionProgress,
                    layoutPosition = layoutPosition,
                    revealLimitHeightPx = revealLimitHeightPx,
                )
                // 内容模糊渐入：进入 8dp→0，退出 0→8dp（API<31 Modifier.blur 无效果，自动跳过）
                .contentBlur(radius = (8f * (1f - fractionProgress())).dp)
                .then(
                    if (backdrop != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = {
                                // 圆角随缩放反向放大保持视觉圆角不变；
                                // avgScale 与 Nexio 一样不做下限裁剪
                                val avgScale = ScaleBase + (1f - ScaleBase) * fractionProgress()
                                YosRoundedCornerShape(
                                    CornerSize(with(density) { (PopupCornerRadius.toPx() / avgScale).toDp() })
                                )
                            },
                            effects = {
                                vibrancy()
                                blur(24.dp.toPx())
                            },
                            highlight = { null },
                            shadow = { null },
                            onDrawSurface = {
                                drawRect(containerColor.copy(alpha = glassSurfaceAlpha))
                            }
                        )
                    } else {
                        Modifier.background(
                            containerColor.copy(alpha = fallbackSurfaceAlpha),
                            shape = YosRoundedCornerShape(PopupCornerRadius),
                        )
                    }
                )
                .rectEdgeLight(
                    shapeProvider = {
                        val avgScale = ScaleBase + (1f - ScaleBase) * fractionProgress()
                        panelShape(size, PopupCornerRadius.toPx() / avgScale)
                    },
                    color = edgeLightColor,
                    strokeWidth = 0.28f.dp,
                    blurRadius = 0.8f.dp,
                )
        ) {
            content()
            // 退让遮罩：盖在行之上、裁进面板圆角。alpha 在绘制期读，不逐帧重组。
            // 颜色用黑（同 Miuix 的 windowDimming x 0.5），深浅色一致都是"往后退"
            if (recedeFraction != null) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .graphicsLayer { alpha = recedeFraction() }
                        .background(
                            color = Color.Black.copy(alpha = 0.24f),
                            shape = YosRoundedCornerShape(PopupCornerRadius)
                        )
                )
            }
        }
    }
}

/** 圆角反向补偿（与 Nexio rememberDynamicCornerRadiusShape 同式，半径不做 clamp）。 */
private fun DrawScope.panelShape(size: Size, cornerPx: Float): Shape {
    return YosRoundedCornerShape(CornerSize(cornerPx.toDp()))
}

/**
 * 方向性裁剪揭示（移植 Nexio popupClipReveal）：
 * 下方展开=从顶向下；上方=从底向上；居中=从中心向两侧；
 * revealLimitHeightPx>0 且有方向时，可见高度从薄片高度展开到完整高度。
 * 裁剪路径用反向补偿圆角的 squircle，动画中视觉圆角恒定。
 */
private fun Modifier.popupClipReveal(
    fractionProgress: () -> Float,
    layoutPosition: LiquidDropdownLayoutPosition,
    revealLimitHeightPx: Float,
): Modifier = drawWithCache {
    val path = Path()
    onDrawWithContent {
        val progress = fractionProgress().coerceIn(0f, 1f)
        if (progress <= 0f) return@onDrawWithContent

        val height = size.height
        val visibleHeight =
            if (revealLimitHeightPx > 0f && (layoutPosition.showBelow || layoutPosition.showAbove)) {
                (revealLimitHeightPx + (height - revealLimitHeightPx) * progress)
                    .coerceIn(0f, height)
            } else {
                height
            }
        if (visibleHeight <= 0f) return@onDrawWithContent

        val clipStart = when {
            layoutPosition.showBelow -> 0f
            layoutPosition.showAbove -> height - visibleHeight
            else -> height * (0.5f - 0.5f * progress)
        }

        val fraction = fractionProgress()
        val avgScale = ScaleBase + (1f - ScaleBase) * fraction
        val outline = panelShape(Size(size.width, visibleHeight), PopupCornerRadius.toPx() / avgScale)
            .createOutline(
                size = Size(size.width, visibleHeight),
                layoutDirection = layoutDirection,
                density = this@drawWithCache,
            )
        path.rewind()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
            is Outline.Rectangle -> path.addRect(outline.rect)
        }
        if (clipStart == 0f) {
            clipPath(path) {
                this@onDrawWithContent.drawContent()
            }
        } else {
            translate(top = clipStart) {
                clipPath(path) {
                    translate(top = -clipStart) {
                        this@onDrawWithContent.drawContent()
                    }
                }
            }
        }
    }
}

/**
 * 圆角矩形边缘光（Uniform 等效实现，与 LiquidTopBarButton.circleEdgeLight 同方案）：
 * 描边画在形状边界上再裁剪进形状内，NORMAL 模糊 + ADD 混合。
 * 对照 Nexio rememberDefaultEdgeLight：白 0.7/0.4、0.28dp 描边、0.8dp 模糊。
 */
private fun Modifier.rectEdgeLight(
    shapeProvider: DrawScope.() -> Shape,
    color: Color,
    strokeWidth: Dp,
    blurRadius: Dp,
): Modifier = drawWithCache {
    val path = Path()
    onDrawWithContent {
        drawContent()
        if (color.alpha <= 0f || strokeWidth <= 0.dp) return@onDrawWithContent
        val outline = shapeProvider().createOutline(
            size = size,
            layoutDirection = layoutDirection,
            density = this@drawWithCache,
        )
        path.rewind()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
            is Outline.Rectangle -> path.addRect(outline.rect)
        }
        val strokePx = ceil(strokeWidth.toPx().coerceAtMost(size.minDimension / 2f)) * 2f
        val blurPx = blurRadius.toPx()
        val nativePath = path.asAndroidPath()
        drawIntoCanvas { canvas ->
            val paint = android.graphics.Paint().apply {
                this.color = color.toArgb()
                style = android.graphics.Paint.Style.STROKE
                this.strokeWidth = strokePx
                if (blurPx > 0f) {
                    maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
                }
                xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.ADD)
            }
            canvas.save()
            canvas.nativeCanvas.clipPath(nativePath)
            canvas.nativeCanvas.drawPath(nativePath, paint)
            canvas.restore()
        }
    }
}

// =====================================================================
// 锚点联动：触发元件随弹层揭示进度收缩/下沉/淡出
// （Nexio OverlayDropdownMenu 里作用在行尾内容上的 contentAlpha 手法，扩展到整个触发元件）
// =====================================================================

/** 进入时隐藏、退出时提前恢复的滞回阈值（Nexio 原值：0.15 / 0.2）*/
private const val FollowHideThreshold = 0.15f
private const val FollowShowThreshold = 0.2f

/** 联动淡出的时长（Nexio contentAlpha 原值 tween(180)）*/
private val FollowAlphaAnimSpec = tween<Float>(durationMillis = 180)

/**
 * 弹层进度 → 触发元件变换的联动状态。
 *
 * 为什么需要它：弹层的可见顶边压在锚点上方 7dp（Nexio 原生的"盖住锚点"语义），
 * 触发元件就住在玻璃底下。不做联动时要么透过 0.6 的表面看见按钮和面板内容叠在一起，
 * 要么（旧写法）在点击那一帧就把图标淡掉——面板还没长到位，先露出一个洞。
 *
 * 两路进度有意分开：
 * - **位移与缩放直接用面板的 fraction**（原样透传，含 spring(0.78/232) 的过冲）——
 *   元件和面板是同一条曲线，看上去像被面板带着走；
 * - **淡出走滞回**（进入 ≥0.15 才开始隐、退出 ≤0.2 就恢复，tween 180）——
 *   面板盖住之前不提前消失，面板还没收完就先把按钮放回去。
 */
class LiquidDropdownFollowState internal constructor(
    internal val shiftPx: Float,
    internal val openScale: Float,
) {
    /** 面板揭示进度（含过冲）*/
    internal val fraction = mutableFloatStateOf(0f)

    /** 揭示方向的 Y 分量：+1 面板向下长、-1 向上长、0 居中或上下都塞不下 */
    internal val directionY = mutableFloatStateOf(0f)

    /** 淡出进度 0..1（由滞回判定驱动）*/
    internal val hidden = Animatable(0f)

    /** 接给 [LiquidDropdownLayout.onFractionProgress]：`onFractionProgress = follow::onProgress` */
    fun onProgress(progress: LiquidDropdownProgress) {
        fraction.floatValue = progress.fraction
        directionY.floatValue = when {
            progress.position.showBelow -> 1f
            progress.position.showAbove -> -1f
            else -> 0f
        }
    }
}

/**
 * [rememberLiquidDropdownFollowState] 的变换层。
 *
 * 只在绘制期读状态（graphicsLayer 内），每帧不重组。[shiftPx] 已经过 LocalDensity 换算，
 * 密度变化（多窗口 / 旋转）会重建状态。
 *
 * **关键约束**：位移与缩放会改变元件的视觉位置，所以锚点的 `onGloballyPositioned` 必须挂在
 * 这个节点的**上游**（未被变换的外层节点，或将变换下沉到内层子节点）——否则锚点被联动带着走、
 * 弹层又跟着锚点重定位，形成逐帧放大的回环抖动。
 */
fun Modifier.liquidDropdownAnchorFollow(state: LiquidDropdownFollowState): Modifier =
    graphicsLayer {
        val reveal = state.fraction.floatValue
        // 收缩：跟面板同一条弹簧，过冲时元件也会多缩一点再弹回
        val scale = 1f - (1f - state.openScale) * reveal
        scaleX = scale
        scaleY = scale
        translationY = state.directionY.floatValue * state.shiftPx * reveal
        alpha = 1f - state.hidden.value
    }

/**
 * 触发元件与弹层的联动状态。调用方把同一个实例分两头接上：
 * 元件侧 `Modifier.liquidDropdownAnchorFollow(follow)`，弹层侧
 * `onFractionProgress = follow::onProgress`。
 *
 * 默认幅度刻意压得很小（6dp 下沉 + 缩到 0.9），目的是让它和面板 0.24→1 的缩放在一起不抢戏；
 * 想要更内敛可以把 [shift] 送到 0dp，只留淡出。
 */
@Composable
fun rememberLiquidDropdownFollowState(
    shift: Dp = 6.dp,
    openScale: Float = 0.9f,
): LiquidDropdownFollowState {
    val density = LocalDensity.current
    val state = remember(shift, openScale, density) {
        LiquidDropdownFollowState(
            shiftPx = with(density) { shift.toPx() },
            openScale = openScale.coerceIn(0.5f, 1f),
        )
    }
    LaunchedEffect(state) {
        var prevFraction = state.fraction.floatValue
        var visible = state.hidden.value < 0.5f
        var animJob: Job? = null
        snapshotFlow { state.fraction.floatValue }.collect { current ->
            val isEntering = current >= prevFraction
            prevFraction = current
            val newVisible =
                if (isEntering) current < FollowHideThreshold else current < FollowShowThreshold
            if (newVisible != visible) {
                visible = newVisible
                animJob?.cancel()
                animJob = launch {
                    state.hidden.animateTo(
                        targetValue = if (newVisible) 0f else 1f,
                        animationSpec = FollowAlphaAnimSpec,
                    )
                }
            }
        }
    }
    return state
}

/**
 * 面板可用高度上限：锚点到窗口底的余量减去阴影外扩（24dp）与一点余量。
 *
 * 用 [LocalConfiguration] 的 screenHeightDp（不含系统栏的稳定尺寸）而不是窗口实高：
 * 偏小一侧，宁可少给空间也不要动画中途触发方向翻转。锚点还没测到时给一个宽上限
 * （下一帧就会被真实锚点纠正）。多窗口/旋转下配置变化会重组，上限跟着重算。
 */
@Composable
fun liquidDropdownHeightCap(anchorBounds: IntRect, fallback: Dp = 420.dp): Dp {
    if (anchorBounds == IntRect.Zero) return fallback
    val density = LocalDensity.current
    val screenPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    return with(density) { (screenPx - anchorBounds.bottom).coerceAtLeast(0f).toDp() }
        .minus(40.dp)
        .coerceAtLeast(150.dp)
}

// =====================================================================
// 弹层内容列：宽度取前 8 个子项最大固有宽度，clamp 在 200~288dp，支持滚动
// （移植 Nexio ListPopupColumn 的测量策略）
// =====================================================================

private const val MaxItemsForWidth = 8

@Composable
fun LiquidDropdownColumn(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val scrollState = rememberScrollState()
    val measurePolicy = remember {
        object : MeasurePolicy {
            override fun MeasureScope.measure(
                measurables: List<Measurable>,
                constraints: Constraints,
            ): androidx.compose.ui.layout.MeasureResult {
                val minPx = PopupMinWidth.roundToPx()
                val maxPx = PopupMaxWidth.roundToPx()
                val widthCount = min(MaxItemsForWidth, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(constraints.maxHeight)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val upper = maxOf(maxPx, constraints.minWidth).coerceAtMost(constraints.maxWidth)
                val lower = maxOf(minPx, constraints.minWidth).coerceAtMost(upper)
                val listWidth = maxIntrinsic.coerceIn(lower, upper)
                val childConstraints = constraints.copy(
                    minWidth = listWidth,
                    maxWidth = listWidth,
                    minHeight = 0,
                )

                val placeables = ArrayList<Placeable>(measurables.size)
                var listHeight = 0
                for (i in measurables.indices) {
                    val p = measurables[i].measure(childConstraints)
                    placeables.add(p)
                    listHeight += p.height
                }
                return layout(listWidth, listHeight) {
                    var currentY = 0
                    for (p in placeables) {
                        p.placeRelative(0, currentY)
                        currentY += p.height
                    }
                }
            }

            override fun IntrinsicMeasureScope.minIntrinsicHeight(
                measurables: List<IntrinsicMeasurable>,
                width: Int,
            ): Int {
                val minPx = PopupMinWidth.roundToPx()
                val maxPx = PopupMaxWidth.roundToPx()
                val widthCount = min(MaxItemsForWidth, measurables.size)
                var maxIntrinsic = 0
                for (i in 0 until widthCount) {
                    val w = measurables[i].maxIntrinsicWidth(Int.MAX_VALUE)
                    if (w > maxIntrinsic) maxIntrinsic = w
                }
                val listWidth = maxIntrinsic.coerceIn(minPx, maxPx)
                return measurables.sumOf { it.minIntrinsicHeight(listWidth) }
            }
        }
    }

    Layout(
        content = content,
        modifier = modifier
            .height(IntrinsicSize.Min)
            .verticalScroll(state = scrollState),
        measurePolicy = measurePolicy,
    )
}

// =====================================================================
// 菜单行（移植 Nexio DropdownImpl）：17dp squircle 高亮、选中 primary 着色 + 勾
// =====================================================================

/**
 * @param selected 是否为当前选中项（primary 着色 + 勾图标）
 * @param isFirst/isLast 行首行尾额外 8dp 上下内边距（Nexio DropdownImpl 同规则）
 * @param enabled false 时置灰且不响应点击（用于"已证明不可用"的档位）
 * @param chip 文本尾部小胶囊：语义标注**不得**再拼进 [text]——拼进去会让那一行比
 *             其他行长一截，用户把它读成图标，而且标记会随状态在不同行之间跳（历史上
 *             "（本首）"的观感问题）。传 null 时行布局与旧版逐像素一致。
 * @param trailing 自定义尾部插槽（只读摘要行用它显示当前值），与 [chip] 共存时 chip 在前
 * @param modifier 挂在整行最外层（先于行自身 8dp 内缩进）：二级子面板里的行用它做缩进
 * @param showSelectedIcon false 时不渲染尾部勾选位：入口行（点开子面板那种）没有"选中"
 *                         语义，留着会白占 20dp，把 trailing 内容往左挤一截
 */
@Composable
fun LiquidDropdownRow(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    enabled: Boolean = true,
    chip: String? = null,
    modifier: Modifier = Modifier,
    showSelectedIcon: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    // 文字统一单色：深色模式白、浅色模式黑，不随选中态换 primary（选中只靠尾部的勾表达）。
    // 用 withNight 而不是 MaterialTheme.colorScheme.onBackground：后者在主题派生色下
    // 深浅两态都不是纯黑/纯白，玻璃面板上会跟着底色发灰
    val contentColor = Color.Black withNight Color.White
    val selectedColor = MaterialTheme.colorScheme.primary
    Row(
        modifier = modifier
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = if (isFirst) 8.dp else 0.dp,
                bottom = if (isLast) 8.dp else 0.dp,
            )
            .clip(YosRoundedCornerShape(17.dp))
            // 参考实现行容器透明（liquidGlassDropdownColors 的 container/selected
            // 均为 Transparent）：选中态只靠文字与勾着色，没有高亮色块；
            // 行高内容自适应（MinHeight 56dp 只用于对话框模式）
            // 关掉默认涟漪：M3 水波会在这层玻璃上画出一块方形色斑（选中态本来就只靠
            // 文字与勾表达，没有高亮块），与整个 liquid 家族的无涟漪语言不一致
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 10.5.dp)
            .alpha(if (enabled) 1f else 0.4f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = text,
            fontSize = 15.6.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            modifier = Modifier.weight(1f),
        )
        if (chip != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Box(
                modifier = Modifier
                    .clip(YosRoundedCornerShape(4.dp))
                    .background(contentColor.copy(alpha = 0.10f))
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = chip,
                    fontSize = 10.5.sp,
                    lineHeight = 12.sp,
                    color = contentColor.copy(alpha = 0.75f),
                )
            }
        }
        if (trailing != null) {
            if (chip == null) Spacer(modifier = Modifier.width(6.dp))
            trailing()
        }
        if (showSelectedIcon) {
            Icon(
                painter = painterResource(id = R.drawable.ic_checkmark),
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = if (selected) selectedColor else Color.Transparent,
            )
        }
    }
}
