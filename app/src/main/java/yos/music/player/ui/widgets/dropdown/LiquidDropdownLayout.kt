package yos.music.player.ui.widgets.dropdown

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import yos.music.player.ui.widgets.basic.LiquidDropdownColumn
import yos.music.player.ui.widgets.basic.LocalBottomOverlayHeight
import yos.music.player.ui.widgets.basic.LocalTitleOverlayHost
import kotlin.math.roundToInt

// =====================================================================
// 弹层宿主 + 入口
//
// NexioSchedule 1.6.5 的弹窗由 root Scaffold 的 popupHost 渲染（MiuixPopupUtils.PopupLayout
// → MiuixPopupHost，enableWindowDim=false、无转场）。本项目对应物是页面级
// [LocalTitleOverlayHost]（Title 页根 Box 顶层、与页面同窗口同帧）；没有宿主时退回
// androidx Popup 独立窗口。
//
// 与 popupHost 的分工和原版一致：**动画状态住调用方作用域**（ListPopupLayout 里的
// Animatable），宿主只负责渲染，不持有任何动画状态。
// =====================================================================

/** 竖直覆盖量（Nexio `liquidDropdownPositionProvider` 原值 5dp）。 */
private val LiquidDropdownAnchorOffsetY = 5.dp

/**
 * 水平居中于锚点的定位提供者（本项目自有几何：播放页音质胶囊）。
 *
 * 竖直规则与 `liquidDropdownPositionProvider` 完全一致（同样的 5dp 覆盖量、同样的
 * 上下都不够时居中），只把水平对齐换成长边中点对齐 —— 胶囊在时间行居中，面板跟着居中。
 */
@Composable
fun centeredDropdownPositionProvider(
    anchorOffsetY: Dp = LiquidDropdownAnchorOffsetY,
): PopupPositionProvider {
    val density = LocalDensity.current
    val offsetYDelta = with(density) { anchorOffsetY.roundToPx() }
    return remember(offsetYDelta) {
        object : PopupPositionProvider {
            private val margins = PaddingValues(horizontal = 0.dp, vertical = 0.dp)

            override fun calculatePosition(
                anchorBounds: IntRect,
                windowBounds: IntRect,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
                popupMargin: IntRect,
                alignment: PopupPositionProvider.Align,
            ): PopupPositionResult {
                val offsetX = anchorBounds.left + anchorBounds.width / 2 - popupContentSize.width / 2
                val spaceBelow = windowBounds.bottom - anchorBounds.bottom
                val spaceAbove = anchorBounds.top - windowBounds.top
                val offsetY: Int
                val showBelow: Boolean
                val showAbove: Boolean
                if (spaceBelow > popupContentSize.height) {
                    offsetY = anchorBounds.top - offsetYDelta
                    showBelow = true
                    showAbove = false
                } else if (spaceAbove > popupContentSize.height) {
                    offsetY = anchorBounds.bottom - popupContentSize.height + offsetYDelta
                    showBelow = false
                    showAbove = true
                } else {
                    offsetY = anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
                    showBelow = false
                    showAbove = false
                }
                val clampedOffset = IntOffset(
                    x = offsetX.coerceIn(
                        windowBounds.left,
                        (windowBounds.right - popupContentSize.width - popupMargin.right).coerceAtLeast(windowBounds.left),
                    ),
                    y = offsetY.coerceIn(
                        (windowBounds.top + popupMargin.top).coerceAtMost(windowBounds.bottom - popupContentSize.height - popupMargin.bottom),
                        windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
                    ),
                )
                return PopupPositionResult(clampedOffset, showBelow, showAbove)
            }

            override fun getMargins(): PaddingValues = margins
        }
    }
}

/** 堆叠子面板与锚点行之间的间隙（让两块表面读得出是分开的）。 */
private val StackGap = 10.dp

/**
 * 堆叠子面板定位提供者（本项目自有语义，原版无对应物）：贴锚点行下缘、左缘对齐。
 *
 * 原版 Miuix 级联弹层把子面板顶边对齐触发行**顶边**（行变成子面板标题行）；本项目
 * 二级菜单只有两行入口，那样父面板会被整个盖掉，所以改成贴行下缘、保留父面板露出。
 */
@Composable
fun stackBelowAnchorPositionProvider(gap: Dp = StackGap): PopupPositionProvider {
    val density = LocalDensity.current
    val gapPx = with(density) { gap.roundToPx() }
    return remember(gapPx) {
        object : PopupPositionProvider {
            private val margins = PaddingValues(horizontal = 0.dp, vertical = 0.dp)

            override fun calculatePosition(
                anchorBounds: IntRect,
                windowBounds: IntRect,
                layoutDirection: LayoutDirection,
                popupContentSize: IntSize,
                popupMargin: IntRect,
                alignment: PopupPositionProvider.Align,
            ): PopupPositionResult {
                val offsetX = anchorBounds.left + popupMargin.left
                val spaceBelow = windowBounds.bottom - (anchorBounds.bottom + gapPx)
                val spaceAbove = (anchorBounds.top - gapPx) - windowBounds.top
                val offsetY: Int
                val showBelow: Boolean
                val showAbove: Boolean
                if (spaceBelow > popupContentSize.height) {
                    offsetY = anchorBounds.bottom + gapPx
                    showBelow = true
                    showAbove = false
                } else if (spaceAbove > popupContentSize.height) {
                    offsetY = anchorBounds.top - gapPx - popupContentSize.height
                    showBelow = false
                    showAbove = true
                } else {
                    offsetY = anchorBounds.top + anchorBounds.height / 2 - popupContentSize.height / 2
                    showBelow = false
                    showAbove = false
                }
                val clampedOffset = IntOffset(
                    x = offsetX.coerceIn(
                        windowBounds.left,
                        (windowBounds.right - popupContentSize.width - popupMargin.right).coerceAtLeast(windowBounds.left),
                    ),
                    y = offsetY.coerceIn(
                        (windowBounds.top + popupMargin.top).coerceAtMost(windowBounds.bottom - popupContentSize.height - popupMargin.bottom),
                        windowBounds.bottom - popupContentSize.height - popupMargin.bottom,
                    ),
                )
                return PopupPositionResult(clampedOffset, showBelow, showAbove)
            }

            override fun getMargins(): PaddingValues = margins
        }
    }
}

/**
 * 弹窗渲染层：全屏 Box + 事件消费垫层 + 内容。
 *
 * 垫层对齐原版 `MiuixPopupUtils.PopupEntry` 的 enableWindowDim=false 分支：
 * 弹窗展开期间把指针事件全吃在这层，页面不会在弹窗底下被拖动/点击。
 * 弹窗自己的 ListPopupLayout 里还有一层 detectTapGestures（点外关闭），两者叠着用。
 */
/**
 * 弹层渲染层：全屏 Box + 事件消费垫层 + 内容。
 *
 * 垫层对齐原版 `MiuixPopupUtils.PopupEntry` 的 enableWindowDim=false 分支：
 * 弹窗展开期间把指针事件全吃在这层，页面不会在弹窗底下被拖动/点击。
 * 弹窗自己的 ListPopupLayout 里还有一层 detectTapGestures（点外关闭），两者叠着用。
 *
 * ⚠️ 这里**不**套 `AnimatedVisibility`：
 * 原版 `PopupEntry` 那层是给「宿主常驻、由 AnimatedVisibility 自己跑进退场」的架构用的。
 * 本项目退场完全由 `ListPopupLayout` 的三个 Animatable 负责，且 `internalVisible`
 * 一旦置 false，`ListPopupLayout` 立刻 early return → `popupHost` 不再被调用 →
 * 本组件同帧离开组合。AnimatedVisibility 的 exit 根本轮不到执行，属于死代码；
 * 留着反而在**展开**时多拖一帧（首次打开会空一帧）。
 */
@Composable
private fun DropdownPopupLayer(
    visible: Boolean,
    zIndex: Float,
    content: @Composable () -> Unit,
) {
    Box(modifier = Modifier.zIndex(zIndex).fillMaxSize()) {
        if (visible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent()
                                val changes = event.changes
                                for (i in changes.indices) changes[i].consume()
                            }
                        }
                    }
            )
        }
        content()
    }
}

/**
 * 弹层宿主（原版 `MiuixPopupUtils.PopupLayout` 的等价物）：
 * 有页面级弹层宿主就注册进去（与页面同窗口同帧），否则退回 androidx Popup 独立窗口。
 */
@Composable
internal fun DropdownPopupHost(
    visible: Boolean,
    zIndex: Float,
    forceWindow: Boolean = false,
    content: @Composable () -> Unit,
) {
    val host = if (forceWindow) null else LocalTitleOverlayHost.current
    if (host != null) {
        val slotKey = remember(host) { Any() }
        val currentVisible by rememberUpdatedState(visible)
        val currentZIndex by rememberUpdatedState(zIndex)
        val currentContent by rememberUpdatedState(content)
        SideEffect {
            host.set(slotKey) {
                DropdownPopupLayer(
                    visible = currentVisible,
                    zIndex = currentZIndex,
                ) {
                    currentContent()
                }
            }
        }
        DisposableEffect(slotKey) {
            onDispose { host.remove(slotKey) }
        }
    } else {
        val currentVisible by rememberUpdatedState(visible)
        val currentZIndex by rememberUpdatedState(zIndex)
        val currentContent by rememberUpdatedState(content)
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset.Zero,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            DropdownPopupLayer(
                visible = currentVisible,
                zIndex = currentZIndex,
            ) {
                currentContent()
            }
        }
    }
}

/**
 * 液态玻璃下拉弹层入口（1.6.5 引擎）。
 *
 * 与原版的差异只在本项目自有语义上：
 * - [recedeFraction] 二级堆叠时父面板退让（原版无对应物）；
 * - [forceWindowHost] 在宿主内容里再开弹窗时强制走独立窗口（避免嵌套注册宿主）。
 *
 * @param collapseSize 触发区实测尺寸：弹窗从这块内容原位长成面板（原版同参数）
 * @param collapseContent 收起态显示的内容（触发区副本），随容器长大淡出
 */
@Composable
fun LiquidDropdownLayout(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit = {},
    backdrop: Backdrop? = null,
    alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.End,
    positionProvider: PopupPositionProvider = liquidDropdownPositionProvider(),
    maxHeight: Dp? = null,
    zIndex: Float = 0f,
    forceWindowHost: Boolean = false,
    collapseSize: IntSize? = null,
    collapseExtra: DpSize = DpSize.Zero,
    collapseContent: (@Composable () -> Unit)? = null,
    dragSelectState: DropdownPanelDragSelectState? = null,
    recedeFraction: (() -> Float)? = null,
    surfaceAlpha: Float? = null,
    onFractionProgress: ((Float) -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    ListPopupLayout(
        show = expanded,
        popupHost = { visible, hostContent ->
            DropdownPopupHost(
                visible = visible,
                zIndex = zIndex,
                forceWindow = forceWindowHost,
                content = hostContent,
            )
        },
        popupPositionProvider = positionProvider,
        alignment = alignment,
        enableWindowDim = false,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        maxHeight = maxHeight,
        minWidth = ListPopupDefaults.MinWidth,
        liquidGlassBackdrop = backdrop,
        onFractionProgress = onFractionProgress,
        collapseSize = collapseSize,
        collapseExtra = collapseExtra,
        collapseContent = collapseContent,
        dragSelectState = dragSelectState,
        recedeFraction = recedeFraction,
        bottomInset = LocalBottomOverlayHeight.current,
        surfaceAlpha = surfaceAlpha,
        content = content,
    )
}

/** 全屏点外关闭层（原版 MorePopupMenus 里调用方手写的透明 clickable Box）。 */
@Composable
fun DropdownDismissScrim(onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(onDismiss) {
                detectTapGestures(onTap = { onDismiss() })
            }
    )
}

/**
 * 带列表的弹窗（原版 `overlay/OverlayListPopup.kt` + `popup/LiquidOverlayDropdownPopup.kt`
 * 的组合体）：内容固定是「一列菜单行」，跟手滑选状态由本组件自建并下发给行。
 *
 * 与直接用 [LiquidDropdownLayout] 的区别只有两点：
 * - 内容包了一层 `ListPopupColumn`（宽度 200~288dp 自适应 + 内滚）；
 * - 自建 [DropdownPanelDragSelectState]，并把 `fitsOnScreen` 接上（一屏装不下时
 *   纵向手势归滚动、只保留点选，两种手势不互抢）。
 *
 * @param collapseSize 触发区实测尺寸（弹窗从这块内容原位长成面板）
 * @param collapseContent 收起态显示的内容（触发区副本），随容器长大淡出
 * @param dragSelectState 调用方自带的跟手滑选状态。触发区若要「长按展开后不停手直接滑选」，
 *   必须把**同一个实例**交给触发区的 [dropdownTriggerGesture] 与这里；
 *   不传则本组件自建（老调用点行为不变）。
 */
@Composable
fun LiquidOverlayDropdownPopup(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onDismissFinished: () -> Unit,
    backdrop: Backdrop? = null,
    collapseSize: IntSize? = null,
    collapseExtra: DpSize = DpSize(14.dp, 10.dp),
    collapseContent: (@Composable () -> Unit)? = null,
    positionProvider: PopupPositionProvider = liquidDropdownPositionProvider(),
    alignment: PopupPositionProvider.Align = PopupPositionProvider.Align.End,
    maxHeight: Dp? = null,
    surfaceAlpha: Float? = null,
    columnModifier: Modifier = Modifier,
    zIndex: Float = 0f,
    forceWindowHost: Boolean = false,
    recedeFraction: (() -> Float)? = null,
    dragSelectEnabled: Boolean = true,
    dragSelectState: DropdownPanelDragSelectState? = null,
    content: @Composable () -> Unit,
) {
    // 跟手滑选状态必须由调用方持有：弹窗在独立窗口/宿主任意一侧，菜单项要靠它跨层回传选中。
    // 调用方传了就用它（触发区与面板共享同一份），否则自建。
    val sharedDragSelect = dragSelectState ?: remember(dragSelectEnabled) {
        if (dragSelectEnabled) DropdownPanelDragSelectState() else null
    }
    val dragSelectState = if (dragSelectEnabled) sharedDragSelect else null
    LiquidDropdownLayout(
        expanded = show,
        onDismissRequest = onDismissRequest,
        onDismissFinished = onDismissFinished,
        backdrop = backdrop,
        alignment = alignment,
        positionProvider = positionProvider,
        maxHeight = maxHeight,
        zIndex = zIndex,
        forceWindowHost = forceWindowHost,
        collapseSize = collapseSize,
        collapseExtra = collapseExtra,
        collapseContent = collapseContent,
        dragSelectState = dragSelectState,
        recedeFraction = recedeFraction,
        surfaceAlpha = surfaceAlpha,
    ) {
        LiquidDropdownColumn(
            modifier = columnModifier,
            onFitsOnScreen = dragSelectState?.let { state -> { fits -> state.fitsOnScreen = fits } },
            content = content,
        )
    }
}
