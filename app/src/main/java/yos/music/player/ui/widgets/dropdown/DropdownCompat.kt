package yos.music.player.ui.widgets.dropdown

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import com.kyant.backdrop.edgelight.edgeLight
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import kotlin.math.PI
import kotlin.math.sin

// =====================================================================
// NexioSchedule 1.6.5 弹层引擎的宿主侧适配层。
//
// 原版依赖 miuix / kyant backdrop fork 的若干符号，本项目用与它们等价的既有实现顶替，
// 语义逐项对齐（见交接文档 §1.5）。组件本体（ListPopup / ListPopupLayout /
// LiquidGlassDropdownMenu）保持逐字移植，差异只集中在下面这几处。
// =====================================================================

/** SinOut 缓动（Nexio `anim/SinOutEasing.kt` 逐字）。 */
internal val SinOutEasing: Easing = Easing { fraction ->
    sin((fraction * PI / 2).toFloat())
}

/** 原版 AppMaterialSettings.chromeLensEnabled()：本项目恒开（与既有玻璃控件一致）。 */
internal val LocalChromeLensEnabled = staticCompositionLocalOf { true }

/** 原版 PredictiveBackSettings.enabled：本项目恒开。 */
internal val LocalPredictiveBackEnabled = staticCompositionLocalOf { true }

/**
 * 原版 `utils/NavigationBack.kt` 的 Android 实现（androidx.navigationevent）。
 *
 * 本项目返回桥是 HouseBackDispatcherOwner，没有手势进度通道，因此这里降级为
 * BackHandler：`backProgress()` 恒为 null，跟手分支不触发，返回键/返回手势照常关闭弹层。
 */
internal class NavigationBackState {
    val isTransitioningBack: () -> Boolean = { false }

    val backProgress: () -> Float? = { null }

    @Composable
    fun handler(
        enabled: Boolean,
        onBackCompleted: () -> Unit,
        onBackCancelled: () -> Unit,
    ) {
        BackHandler(enabled = enabled) { onBackCompleted() }
    }
}

@Composable
internal fun rememberNavigationBack(): NavigationBackState = remember { NavigationBackState() }

/** 原版 `MiuixTheme.colorScheme.surfaceContainer`（浅 FAFAFA / 深 242424）。 */
@Composable
internal fun dropdownSurfaceContainer(): Color = Color(0xFFFAFAFA) withNight Color(0xFF242424)

/** 原版 `isAppDarkTheme()`。 */
@Composable
internal fun isDropdownDarkTheme(): Boolean = isFlamingoInDarkMode()

/**
 * 官方 fork 的 `rememberDefaultEdgeLight` 直通（本项目只需固定浅/深两档）。
 *
 * 已不再自写描边：fork 自带完整的 `com.kyant.backdrop.edgelight` 实现
 * （`Modifier.edgeLight(shape, EdgeLight)`），其内部走 RuntimeShader，
 * 与官方 1.6.5 视觉一致；自写的 ADD 混合描边只是等效近似。
 */
@Composable
internal fun rememberDropdownEdgeLight(): com.kyant.backdrop.edgelight.EdgeLight =
    com.kyant.backdrop.edgelight.rememberDefaultEdgeLight(
        isLightTheme = !isDropdownDarkTheme(),
    )

/** 官方 `Modifier.edgeLight(shape, edgeLight)` 直通（扩展函数，需 import 调用）。 */
internal fun Modifier.dropdownEdgeLight(
    shape: Shape,
    spec: com.kyant.backdrop.edgelight.EdgeLight,
): Modifier = this.edgeLight(shape = shape, edgeLight = spec)

/**
 * 原版 `top.yukonga.miuix.kmp.theme.LocalDismissState`：弹窗内容里读「关闭本弹窗」的通道。
 * 本项目菜单行直接持有 onClick，仅作为移植引擎的兼容符号保留。
 */
internal val LocalDismissState = androidx.compose.runtime.compositionLocalOf<() -> Unit> { {} }


/**
 * 原版 `com.kyant.capsule.ContinuousRoundedRectangle` 的便捷构造。
 *
 * **已改用官方 fork 自带的实现**（`com.kyant.capsule.ContinuousRoundedRectangle`，
 * 随 backdrop fork 一起移植进来），不再自写钳制版：
 * - 官方类本身就是 `RoundedRectangularShape`，lens 的折射 SDF 直接可用；
 * - 官方类内部已处理「半径超过半边长」的退化（42dp 收起态自然胶囊化成正圆），
 *   不会在四方向鼓出三角。
 *
 * 保留这个函数名只是为了让调用点少改，语义与官方完全一致。
 */
internal fun squircle(cornerRadius: Dp, smoothBand: Boolean = false): Shape =
    com.kyant.capsule.ContinuousRoundedRectangle(cornerRadius)
