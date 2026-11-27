package yos.music.player.ui.widgets.dropdown

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.widgets.basic.LocalTitleBarGlass
import yos.music.player.ui.widgets.basic.LocalTitleBarMenuBackdrop
import yos.music.player.ui.widgets.basic.LocalTitleOverlayHost
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType

/**
 * 顶栏「更多」的**本体化玻璃菜单**（照抄 NexioSchedule 1.6.5 CourseScheduleApp.kt:711-914
 * MorePopupMenus 的接线）。
 *
 * 关键结构（原版如此，勿自创）：
 * 1. 顶栏槽里只放一个 **42dp 占位 Box**（不可见，仅上报槽顶/槽右 = 收起态按钮应在的位置）；
 * 2. 菜单本体（收起态就是那颗 42dp 玻璃钮）住在**全屏弹层**里：`Box(fillMaxSize +
 *    graphicsLayer{clip=false; translation = 槽位 - 24dp 阴影留白})`，`TopEnd` 对齐；
 * 3. 点外关闭由**调用方手写全屏透明 scrim**（垫在菜单之下）；
 * 4. 槽位未测量时的 fallback：statusBarsTop - 20dp 或 17dp。
 *
 * 收起态与展开态是**同一个控件**（容器变换）：42dp 圆在 fraction 0→1 之间长成
 * 200dp × 自然高的面板，这就是 1.6.5「更多」按钮的全部动画来源。
 *
 * @param modifier 作用在顶栏占位槽上（调用方原有的 end padding 套在这里）
 */
@Composable
fun TitleBarGlassMenu(
    expanded: Boolean,
    onExpand: () -> Unit,
    onDismiss: () -> Unit,
    triggerIcon: ImageVector,
    triggerContentDescription: String? = null,
    modifier: Modifier = Modifier,
    fraction: Animatable<Float, *> = remember { Animatable(0f) },
    offsetPx: () -> Offset = { Offset.Zero },
    backdrop: Backdrop? = null,
    // 收起态那张面在「工具栏液态玻璃」关闭时的图标色（页面底色 ≠ App 主题色的页面，
    // 如在线专辑/歌单详情，传页面文字色）。玻璃开启时图标恒为黑/白 85%，
    // 材质与底栏胶囊同源（固定表面色 + 页面提取色 tint），本参数不参与。
    contentColor: Color? = null,
    // 圆底未出现时收起态图标的颜色（同返回键的 idleIconTint）：页面底色不是主题白/黑时
    // 传页面文字色，否则固定黑/白会与封面底色融为一体。
    idleIconTint: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 槽位：收起态按钮应有的位置（root 坐标 px）。原版只上报 y，这里连右缘一起量，
    // 让 42dp 钮精确落在占位槽上（不靠 9dp 那种机型相关的手调值）。
    val slotTopPx = remember { mutableFloatStateOf(0f) }
    val slotRightPx = remember { mutableFloatStateOf(0f) }

    Box(
        modifier = modifier
            .size(42.dp)
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                slotTopPx.floatValue = bounds.top
                slotRightPx.floatValue = bounds.right
            }
    )

    // 顶栏材质透明度：玻璃开启时随滚动渐隐渐显；关闭时恒为 1f（常显半透明磨砂圆钮）
    val glassOn = SettingsLibrary.BarBlurEffect
    val barGlass = LocalTitleBarGlass.current
    val materialAlpha = if (!glassOn) 1f else if (barGlass != null) barGlass.backdropAlpha() else 1f
    val pageBackdrop = LocalTitlePageBackdrop.current ?: remember { emptyBackdrop() }
    // 采样源优先级（从高到低）：
    // - 液态玻璃开启：调用方显式传的 backdrop > 顶栏玻璃上下文 > 顶栏菜单采样源 > 页面内容层；
    // - 液态玻璃关闭：直接采样页面原始内容层（避免在已模糊的 barSurfaceBackdrop 上二次叠糊，
    //   使按钮与面板保持与顶栏完全一致的 14dp × frostedBlurScale 单次模糊，跨过顶栏底边也无接缝）。
    val resolvedBackdrop = if (glassOn) {
        backdrop ?: barGlass?.backdrop
            ?: LocalTitleBarMenuBackdrop.current ?: pageBackdrop
    } else {
        backdrop ?: pageBackdrop
    }
    val hapticFeedback = LocalHapticFeedback.current
    val density = LocalDensity.current
    val shadowPaddingPx = with(density) { LiquidGlassDropdownShadowPadding.toPx() }
    val fallbackAnchorTopPx = with(density) { 17.dp.toPx() }

    val onExpandState = rememberUpdatedState(onExpand)
    val onDismissState = rememberUpdatedState(onDismiss)

    val layer: @Composable () -> Unit = {
        TitleBarMenuLayer(
            expanded = expanded,
            onDismiss = { onDismissState.value() },
            slotTopPx = { slotTopPx.floatValue },
            slotRightPx = { slotRightPx.floatValue },
            shadowPaddingPx = shadowPaddingPx,
            fallbackAnchorTopPx = fallbackAnchorTopPx,
        ) {
            LiquidGlassDropdownMenu(
                show = expanded,
                backdrop = resolvedBackdrop,
                fraction = fraction,
                onDismiss = { onDismissState.value() },
                onBackProgress = {},
                onBackCancelled = {},
                triggerIcon = triggerIcon,
                triggerContentDescription = triggerContentDescription,
                onExpand = {
                    hapticFeedback.performHapticFeedback(HapticFeedbackType.Confirm)
                    onExpandState.value()
                },
                offsetPx = offsetPx,
                materialAlpha = materialAlpha,
                contentColor = contentColor,
                idleIconTint = idleIconTint,
                content = content,
            )
        }
    }

    val host = LocalTitleOverlayHost.current
    if (host != null) {
        val slotKey = remember(host) { Any() }
        val currentLayer by rememberUpdatedState(layer)
        SideEffect {
            host.set(slotKey) { currentLayer() }
        }
        DisposableEffect(slotKey) {
            onDispose { host.remove(slotKey) }
        }
    } else {
        val currentLayer by rememberUpdatedState(layer)
        Popup(
            alignment = Alignment.TopStart,
            offset = IntOffset.Zero,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        ) {
            currentLayer()
        }
    }
}

/**
 * 全屏菜单层（原版 MorePopupMenus 的 Box 结构逐条对应）：
 * 外层 Box(fillMaxSize) → scrim（展开时）+ 平移层（槽顶 - 阴影留白，TopEnd 对齐）。
 */
@Composable
private fun TitleBarMenuLayer(
    expanded: Boolean,
    onDismiss: () -> Unit,
    slotTopPx: () -> Float,
    slotRightPx: () -> Float,
    shadowPaddingPx: Float,
    fallbackAnchorTopPx: Float,
    content: @Composable () -> Unit,
) {
    val layerTopPx = remember { mutableFloatStateOf(0f) }
    val layerRightPx = remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                layerTopPx.floatValue = bounds.top
                layerRightPx.floatValue = bounds.right
            }
    ) {
        if (expanded) {
            DropdownDismissScrim(onDismiss = onDismiss)
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    clip = false
                    val measuredTop = slotTopPx()
                    // 占位槽未测量时退回原版 fallback
                    val anchorTop = if (measuredTop > 0f) measuredTop else fallbackAnchorTopPx
                    translationY = anchorTop - layerTopPx.floatValue - shadowPaddingPx
                    // 收起态 42dp 钮的右缘对齐占位槽右缘：菜单外层自带 24dp 阴影留白
                    val measuredRight = slotRightPx()
                    translationX = if (measuredRight > 0f) {
                        measuredRight - layerRightPx.floatValue + shadowPaddingPx
                    } else {
                        0f
                    }
                },
            contentAlignment = Alignment.TopEnd,
        ) {
            content()
        }
    }
}
