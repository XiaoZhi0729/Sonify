package yos.music.player.ui.widgets.basic

import android.graphics.BlurMaskFilter
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
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
import androidx.compose.ui.graphics.painter.Painter
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
import yos.music.player.ui.widgets.liquid.glassBlurScale
import yos.music.player.ui.widgets.liquid.InteractiveHighlight
import yos.music.player.ui.widgets.dropdown.DropdownPanelDragSelectState
import yos.music.player.ui.widgets.dropdown.LocalDropdownPanelDragSelect
import yos.music.player.ui.widgets.dropdown.computeDragTransform
import yos.music.player.ui.widgets.dropdown.dropdownPanelDragSelect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlin.math.ceil
import kotlin.math.min

// =====================================================================
// 动画参数（移植自 NexioSchedule ListPopupLayout 本地参数，逐值对齐）
// =====================================================================

// 动画参数与 1.6.5 LiquidGlassDropdownMenu 逐值对齐（2026-10-24 用户裁决：
// 三入口保留本旧引擎结构，动画整套换用新版规格）
private val FractionEnterAnimSpec =
    spring<Float>(dampingRatio = 0.77f, stiffness = 220f, visibilityThreshold = 0.0001f)
private val FractionExitAnimSpec =
    spring<Float>(dampingRatio = 0.85f, stiffness = 650f, visibilityThreshold = 0.0001f)
private val AlphaEnterAnimSpec = tween<Float>(durationMillis = 120)
private val AlphaExitAnimSpec = tween<Float>(durationMillis = 400)

/**
 * 缩放锚点独立动画：对齐新版 LiquidGlassDropdownMenu 的 originProgress 规格
 * （进场 spring(0.77/420)、退出 340ms CubicBezier(0,0,0,1)）。
 */
private val OriginEnterAnimSpec =
    spring<Float>(dampingRatio = 0.77f, stiffness = 420f, visibilityThreshold = 0.0001f)
private val OriginExitAnimSpec =
    tween<Float>(durationMillis = 340, easing = CubicBezierEasing(0f, 0f, 0f, 1f))

private val PopupMinWidth = 200.dp
private val PopupMaxWidth = 288.dp
private val PopupMinHeight = 50.dp
private val PopupCornerRadius = 25.dp
private val PopupShadowPadding = 24.dp

/**
 * 裁剪揭示的起始尺寸：面板从被按按钮大小的方块长出（NexioSchedule 1.6.1 的
 * DropdownClipShape 用 42dp，与其右上角玻璃圆钮同径；本项目的 TitleBarIcon
 * 玻璃圆钮同为 42dp）。
 */
private val DropdownButtonDiameter = 42.dp

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
    /** 缩放锚点水平位（1f 右对齐 / 0.5f 居中 / 0f 左对齐），供触发元件决定让位方向 */
    val pivotX: Float = 1f,
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
 * 方向性裁剪揭示已随 1.6.1 更换为"从按钮方块双向生长"（dropdownClipReveal）、
 * 内容 blur 8dp→0；
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
    backdrop: Backdrop? = null,
    alignCenterHorizontally: Boolean = false,
    surfaceAlpha: Float? = null,
    placement: LiquidDropdownPlacement = LiquidDropdownPlacement.OverlayAnchor,
    zIndex: Float = 0f,
    recedeFraction: (() -> Float)? = null,
    onFractionProgress: ((LiquidDropdownProgress) -> Unit)? = null,
    dragSelectState: DropdownPanelDragSelectState? = null,
    content: @Composable () -> Unit,
) {
    var retainedForExit by remember { mutableStateOf(false) }
    val currentExpanded by rememberUpdatedState(expanded)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    SideEffect {
        if (expanded) retainedForExit = true
    }
    if (!expanded && !retainedForExit) return

    val finishDismiss: () -> Unit = {
        if (!currentExpanded) {
            retainedForExit = false
            currentOnDismissFinished()
        }
    }
    val host = LocalTitleOverlayHost.current
    if (host != null) {
        // 保留宿主内容直到退出动画完成，关闭态不占用 slot 或独立窗口。
        val slotKey = remember(host) { Any() }
        SideEffect {
            host.set(slotKey) {
                LiquidDropdownBody(
                    expanded = expanded,
                    anchorBounds = anchorBounds,
                    onDismissRequest = onDismissRequest,
                    onDismissFinished = finishDismiss,
                    backdrop = backdrop,
                    alignCenterHorizontally = alignCenterHorizontally,
                    surfaceAlpha = surfaceAlpha,
                    placement = placement,
                    zIndex = zIndex,
                    recedeFraction = recedeFraction,
                    onFractionProgress = onFractionProgress,
                    dragSelectState = dragSelectState,
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
                onDismissFinished = finishDismiss,
                backdrop = backdrop,
                alignCenterHorizontally = alignCenterHorizontally,
                surfaceAlpha = surfaceAlpha,
                placement = placement,
                zIndex = zIndex,
                recedeFraction = recedeFraction,
                onFractionProgress = onFractionProgress,
                dragSelectState = dragSelectState,
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
    backdrop: Backdrop?,
    alignCenterHorizontally: Boolean,
    surfaceAlpha: Float?,
    placement: LiquidDropdownPlacement,
    zIndex: Float,
    recedeFraction: (() -> Float)?,
    onFractionProgress: ((LiquidDropdownProgress) -> Unit)?,
    dragSelectState: DropdownPanelDragSelectState?,
    content: @Composable () -> Unit,
) {
    val fractionProgress = remember { Animatable(0f) }
    val originProgress = remember { Animatable(0f) }
    val alphaProgress = remember { Animatable(0f) }
    var internalVisible by remember { mutableStateOf(false) }
    var layoutPosition by remember { mutableStateOf(LiquidDropdownLayoutPosition()) }
    var anchorOrigin by remember { mutableStateOf(TransformOrigin(1f, 0f)) }
    val currentOnDismissRequest by rememberUpdatedState(onDismissRequest)
    val currentOnDismissFinished by rememberUpdatedState(onDismissFinished)
    val currentOnFractionProgress by rememberUpdatedState(onFractionProgress)
    // 迷你条 + 底栏遮住的高度：面板测量上限与定位边界都要避开这块区域
    val bottomOverlayPx = with(LocalDensity.current) {
        LocalBottomOverlayHeight.current.toPx()
    }

    // 三轨并行（对齐 NexioSchedule 1.6.1 LiquidGlassDropdownMenu）：
    // fraction 驱动缩放/裁剪，originProgress 驱动缩放锚点从按钮角移向中心（独立曲线），
    // alphaProgress 驱动整体淡入淡出；退出三轨并行，alpha（最长）收尾后统一归零
    LaunchedEffect(expanded) {
        if (expanded) {
            internalVisible = true
            launch { fractionProgress.animateTo(1f, FractionEnterAnimSpec) }
            launch { originProgress.animateTo(1f, OriginEnterAnimSpec) }
            launch { alphaProgress.animateTo(1f, AlphaEnterAnimSpec) }
        } else {
            if (!internalVisible) {
                currentOnDismissFinished()
                return@LaunchedEffect
            }
            launch { fractionProgress.animateTo(0f, FractionExitAnimSpec) }
            launch { originProgress.animateTo(0f, OriginExitAnimSpec) }
            alphaProgress.animateTo(0f, AlphaExitAnimSpec)
            fractionProgress.snapTo(0f)
            originProgress.snapTo(0f)
            alphaProgress.snapTo(0f)
            internalVisible = false
            currentOnDismissFinished()
        }
    }

    LaunchedEffect(Unit) {
        currentOnFractionProgress?.let { callback ->
            // 朝向也进快照：它是在 measure 阶段回写的，单靠 fraction 变化发不出新朝向；
            // pivotX 同理（锚点角决定触发元件的让位方向）
            snapshotFlow {
                LiquidDropdownProgress(
                    fractionProgress.value,
                    layoutPosition,
                    anchorOrigin.pivotFractionX,
                )
            }.collect { callback(it) }
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
                    val safeBottomPx = (constraints.maxHeight - bottomOverlayPx)
                        .coerceAtLeast(0f)
                        .toInt()
                    val maxHeightPx = (safeBottomPx - 2 * padPx)
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
                    val windowBounds = IntRect(0, 0, constraints.maxWidth, safeBottomPx)
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
                originProgress = { originProgress.value },
                alphaProgress = { alphaProgress.value },
                layoutPosition = layoutPosition,
                originAnchor = anchorOrigin,
                backdrop = backdrop,
                surfaceAlpha = surfaceAlpha,
                recedeFraction = recedeFraction,
                dragSelectState = dragSelectState,
                content = content,
            )
        }
    }
}

/**
 * 弹层视觉本体（移植 Nexio ListPopupContent，动画对齐 1.6.1 LiquidGlassDropdownMenu）：
 * 阴影迟到淡入、缩放/透明度、独立锚点角 transformOrigin、从按钮方块生长的裁剪揭示、
 * 内容独立透明度与模糊渐入、玻璃面板与边缘光。
 */
@Composable
private fun LiquidDropdownContent(
    fractionProgress: () -> Float,
    originProgress: () -> Float,
    alphaProgress: () -> Float,
    layoutPosition: LiquidDropdownLayoutPosition,
    originAnchor: TransformOrigin,
    backdrop: Backdrop?,
    surfaceAlpha: Float?,
    recedeFraction: (() -> Float)?,
    dragSelectState: DropdownPanelDragSelectState?,
    content: @Composable () -> Unit,
) {
    val isDark = isFlamingoInDarkMode()
    val containerColor = Color.White withNight Color(0xFF242424)
    // surfaceAlpha 覆盖：调用方（如播放页音质下拉）可单独指定更通透的表面不透明度
    val glassSurfaceAlpha = surfaceAlpha ?: if (isDark) 0.8f else 0.72f
    val fallbackSurfaceAlpha = 0.97f
    val edgeLightColor = if (!isDark) Color.White.copy(alpha = 0.7f) else Color.White.copy(alpha = 0.4f)
    val density = LocalDensity.current
    // 按压高光 + 跟手形变（与 LiquidGlassDropdownMenu 同款 InteractiveHighlight）：
    // 光晕随展开收放、峰值 0.05（近白底加白会截断成白斑，参照物同值）
    val coroutineScope = rememberCoroutineScope()
    val interactiveHighlight = remember(coroutineScope, isDark) {
        InteractiveHighlight(
            animationScope = coroutineScope,
            radiusScale = { 1f - 0.6f * fractionProgress().coerceIn(0f, 1f) },
            radiusBaseDp = 150.dp,
            lightPeakAlpha = 0.05f,
            isDarkMode = isDark,
        )
    }
    val hapticFeedback = LocalHapticFeedback.current

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

    // 内容透明度：对齐新版规格 (f-0.3)/0.4，进出同一条曲线（不再区分方向）
    val contentAlpha = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        snapshotFlow { fractionProgress() }
            .collect { current ->
                contentAlpha.snapTo(((current - 0.3f) / 0.4f).coerceIn(0f, 1f))
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
                    // 按压/拖拽形变（与 LiquidGlassDropdownMenu 同款 computeDragTransform）：
                    // 按压缩放、沿拖动方向拉伸、tanh 阻尼跟手——玻璃、边光、内容整体变形
                    val drag = computeDragTransform(
                        width = size.width,
                        height = size.height,
                        fraction = fraction,
                        pressProgress = interactiveHighlight.pressProgress,
                        offset = interactiveHighlight.offset,
                        density = this,
                    )
                    scaleX = shrunk * drag.scaleX
                    scaleY = shrunk * drag.scaleY
                    translationX = drag.translationX
                    translationY = drag.translationY
                    // 材质/外壳透明度：对齐新版规格 f*5（0.2 饱和——出现早、消失晚）
                    alpha = (fractionProgress() * 5f).coerceIn(0f, 1f)
                    // 缩放锚点：从锚点角插值到中心——但走**独立的** originProgress 曲线
                    // （进场 spring(0.78/500)、退出 tween(450)+发散收敛），与缩放弹簧解绑，
                    // 退出时面板"飘回"按钮的手感由此而来（NexioSchedule 1.6.1 同款）
                    transformOrigin = TransformOrigin(
                        pivotFractionX = originAnchor.pivotFractionX +
                                (0.5f - originAnchor.pivotFractionX) * originProgress(),
                        pivotFractionY = originAnchor.pivotFractionY +
                                (0.5f - originAnchor.pivotFractionY) * originProgress(),
                    )
                }
                // 跟手滑选：面板手势层（按下→命中→逐帧改命中→松手执行）；
                // 行组件读到 LocalDropdownPanelDragSelect 后自动从 clickable 切到登记模式。
                // 按压高光/形变由滑选手势经观察回调直接驱动——滑选消费事件后，
                // 自挂的 inspectDragGestures 会看到 isConsumed 而取消（高光一闪就没的真因）
                .then(
                    if (dragSelectState != null) {
                        Modifier
                            .onGloballyPositioned { coordinates ->
                                // 行登记把 boundsInRoot 换算成面板局部坐标的基准
                                // （轴向区间与横截区间都要用，两个原点都写）
                                val bounds = coordinates.boundsInRoot()
                                dragSelectState.panelTopInRoot = bounds.top
                                dragSelectState.panelLeftInRoot = bounds.left
                            }
                            .dropdownPanelDragSelect(
                                state = dragSelectState,
                                fraction = { fractionProgress() },
                                hapticFeedback = hapticFeedback,
                                onPointerDown = { interactiveHighlight.externalDragStart(it) },
                                onPointerMove = { interactiveHighlight.externalDragMove(it) },
                                onPointerUp = { interactiveHighlight.externalDragEnd() },
                            )
                    } else {
                        // 无滑选的场合：高光用自己的手势层（不与谁抢事件）
                        Modifier.then(interactiveHighlight.gestureModifier)
                    }
                )
                // 裁剪揭示（在 blur 外层，裁掉模糊产生的圆角溢出）：
                // 从被按按钮大小的方块沿锚点角双向长成完整面板（Nexio 1.6.1 同款）
                .dropdownClipReveal(
                    fractionProgress = fractionProgress,
                    pivotX = originAnchor.pivotFractionX,
                    showBelow = layoutPosition.showBelow,
                    showAbove = layoutPosition.showAbove,
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
                                // 玻璃模糊强度倍率（effects 内读取由库 observeReads 观察）
                                blur(24.dp.toPx() * glassBlurScale)
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
            // 按压高光层：必须夹在玻璃壳之上（否则被材质盖住）、内容之下
            // （否则 Plus 加色会把菜单项的高亮底色冲白）
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .then(interactiveHighlight.modifier)
            )
            // 内容独立透明度（Nexio 1.6.1 同款）：进场随 fraction 0.2→1 渐显，
            // 退出时面板先保持全显、fraction 过半才开始淡出——玻璃壳与内容的
            // 消隐节奏错开，是新版"壳先走、内容后散"手感的来源
            Box(modifier = Modifier.graphicsLayer { alpha = contentAlpha.value }) {
                CompositionLocalProvider(
                    LocalDropdownPanelDragSelect provides dragSelectState
                ) {
                    content()
                }
            }
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
 * 裁剪揭示（对齐 NexioSchedule 1.6.1 LiquidGlassDropdownMenu 的 DropdownClipShape）：
 * 可见区域从**被按按钮大小**（[DropdownButtonDiameter] 见方）的方块沿锚点角
 * 双向长成完整面板——宽高都参与生长，不再是旧版的"全宽薄片向下展开"。
 *
 * [pivotX] 是缩放锚点水平位（1f 右对齐 / 0.5f 居中 / 0f 左对齐，与 transformOrigin
 * 同源），方块固定在锚点角不动；垂直方向跟随摆位（下方展开贴顶、上方贴底、居中）。
 * 裁剪在未缩放坐标系中进行，尺寸与圆角都除以 scale 反向补偿，动画中视觉尺寸与
 * 视觉圆角都从按钮平滑长到面板。
 */
private fun Modifier.dropdownClipReveal(
    fractionProgress: () -> Float,
    pivotX: Float,
    showBelow: Boolean,
    showAbove: Boolean,
): Modifier = drawWithCache {
    val path = Path()
    onDrawWithContent {
        val progress = fractionProgress().coerceIn(0f, 1f)
        if (progress <= 0f) return@onDrawWithContent

        val scale = ScaleBase + (1f - ScaleBase) * progress
        val buttonPx = DropdownButtonDiameter.toPx()
        // 目标视觉尺寸：progress=0 时为按钮方块，progress=1 时为完整面板；
        // clip 在未缩放坐标系，尺寸除以 scale 补偿 graphicsLayer 缩放
        val targetWidth = buttonPx + (size.width - buttonPx) * progress
        val targetHeight = buttonPx + (size.height - buttonPx) * progress
        val clipWidth = (targetWidth / scale).coerceAtMost(size.width)
        val clipHeight = (targetHeight / scale).coerceAtMost(size.height)

        val left = when {
            pivotX >= 0.99f -> size.width - clipWidth
            pivotX <= 0.01f -> 0f
            else -> (size.width - clipWidth) / 2f
        }
        val top = when {
            showBelow -> 0f
            showAbove -> size.height - clipHeight
            else -> (size.height - clipHeight) / 2f
        }

        val outline = panelShape(
            Size(clipWidth, clipHeight),
            PopupCornerRadius.toPx() / scale,
        ).createOutline(
            size = Size(clipWidth, clipHeight),
            layoutDirection = layoutDirection,
            density = this@drawWithCache,
        )
        path.rewind()
        when (outline) {
            is Outline.Rounded -> path.addRoundRect(outline.roundRect)
            is Outline.Generic -> path.addPath(outline.path)
            is Outline.Rectangle -> path.addRect(outline.rect)
        }
        translate(left = left, top = top) {
            clipPath(path) {
                translate(left = -left, top = -top) {
                    this@onDrawWithContent.drawContent()
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
// 锚点联动：触发元件随弹层揭示进度让位（NexioSchedule 1.6.1 同款）或收缩下沉淡出
// =====================================================================

/** 进入时隐藏、退出时提前恢复的滞回阈值（Nexio 原值：0.15 / 0.2）*/
private const val FollowHideThreshold = 0.15f
private const val FollowShowThreshold = 0.2f

/** 联动淡出的时长（Nexio contentAlpha 原值 tween(180)）*/
private val FollowAlphaAnimSpec = tween<Float>(durationMillis = 180)

/**
 * 让位位移（NexioSchedule 1.6.1 MorePopupMenus 原值）：菜单向下生长时，
 * 触发按钮向左 100dp、向下 45dp 滑开，全程可见地"给面板让位"。
 */
private val FollowSlideX = 100.dp
private val FollowSlideY = 45.dp

/**
 * 弹层进度 → 触发元件变换的联动状态。
 *
 * 两种联动模式（按面板摆位自动分流），两者都以「按钮彻底消失」收尾：
 * - **让位滑开**（面板向下生长且右缘对齐锚点——顶栏"更多"按钮的标定场景）：
 *   按钮沿 `(-100dp, +45dp)` 滑开（NexioSchedule 1.6.1 的 buttonFraction offset
 *   手法），同时随揭示进度淡出。**必须淡出**：滑开量是固定值，只有面板足够高
 *   （≳105dp，约两行以上）时按钮落点才落在玻璃底下被盖住；单行矮菜单（≈63dp）里
 *   按钮会正好从面板下缘探出来，悬在列表上像一颗残留的圆钮；
 * - **收缩下沉淡出**（面板向上翻 / 居中摆位）：
 *   沿用旧版下沉 6dp + 缩 0.9 + 滞回淡出，避免按钮透过 0.6 表面与面板行内容叠影。
 */
class LiquidDropdownFollowState internal constructor(
    internal val shiftPx: Float,
    internal val openScale: Float,
) {
    /** 面板揭示进度（含过冲）*/
    internal val fraction = mutableFloatStateOf(0f)

    /** 揭示方向的 Y 分量：+1 面板向下长、-1 向上长、0 居中或上下都塞不下 */
    internal val directionY = mutableFloatStateOf(0f)

    /** 面板缩放锚点水平位：1f 右对齐 / 0.5f 居中 / 0f 左对齐 */
    internal val pivotX = mutableFloatStateOf(1f)

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
        pivotX.floatValue = progress.pivotX
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
        val slideAway = state.directionY.floatValue > 0.5f &&
                state.pivotX.floatValue >= 0.99f
        if (slideAway) {
            // 让位滑开（NexioSchedule 1.6.1 同款）：面板从按钮位置向下生长，
            // 按钮向左下平移让位。用 graphicsLayer 的 translation 而不是
            // Modifier.offset——offset 会改布局坐标，锚点 onGloballyPositioned 会
            // 跟着更新、弹层重定位，形成回环。
            // 位移**同时**淡出：面板高度不够（单行菜单）时按钮落点会露在面板下缘外，
            // 只位移不淡出就会留下一颗悬空圆钮。
            translationX = -FollowSlideX.toPx() * reveal
            translationY = FollowSlideY.toPx() * reveal
            alpha = (1f - reveal).coerceIn(0f, 1f)
        } else {
            // 面板向上翻/居中：按钮滑不开，沿用收缩+下沉+滞回淡出
            val scale = 1f - (1f - state.openScale) * reveal
            scaleX = scale
            scaleY = scale
            translationY = state.directionY.floatValue * state.shiftPx * reveal
            alpha = 1f - state.hidden.value
        }
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
