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
    // 收起态那颗钮与顶栏返回键同色（页面底色驱动的自适应亮度）。
    // 页面底色 ≠ App 主题色的页面（在线专辑/歌单详情）必须传 true + 页面文字色。
    adaptiveLuminance: Boolean = false,
    contentColor: Color? = null,
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

    // 顶栏材质透明度：滚动时收拾起态按钮（与顶栏玻璃圆钮同一信号源）
    val barGlass = LocalTitleBarGlass.current
    val materialAlpha = if (barGlass != null) barGlass.backdropAlpha() else 1f
    val pageBackdrop = LocalTitlePageBackdrop.current ?: remember { emptyBackdrop() }
    // 采样源优先级（从高到低）：
    // 1. 调用方显式传的 backdrop；
    // 2. 顶栏玻璃上下文（仅「工具栏液态玻璃」开启时存在）：页面内容 + 顶栏材质表面；
    // 3. 顶栏菜单采样源（开关两种状态都下发）：同上组合层 —— 关闭玻璃时顶栏是
    //    14dp 均匀模糊 + 0.7 页面色，同样导出进 barSurfaceBackdrop，故这里采到的
    //    仍是"模糊后的表面"而不是栏下原始内容；
    // 4. 页面内容层（兜底：不在顶栏槽内、或页面无 Title 脚手架时）。
    val resolvedBackdrop = backdrop ?: barGlass?.backdrop
        ?: LocalTitleBarMenuBackdrop.current ?: pageBackdrop
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
                adaptiveLuminance = adaptiveLuminance,
                contentColor = contentColor,
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
