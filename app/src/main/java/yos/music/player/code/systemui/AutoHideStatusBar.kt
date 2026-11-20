package yos.music.player.code.systemui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.widgets.basic.pinStatusBarTopInset
import yos.music.player.ui.widgets.basic.releaseStatusBarTopInsetPin

/**
 * 播放页自动隐藏状态栏（设置项 [SettingsLibrary.NowPlayingAutoHideStatusBar]）。
 *
 * 播放页展开期间隐藏系统状态栏，让背景/封面铺满到屏幕顶端；**内容位置不变**——
 * 顶部 inset 由 `RawWindowInsets` 的钉住机制维持在隐藏前的实测值上。
 *
 * ## 只隐藏状态栏，不碰导航条
 * 用户只要求状态栏。导航条侧 `RawWindowInsets` 已有 20dp 基线做过"隐藏小白条位置不变"
 * 的校准，跟着一起隐藏会打乱那套既有几何，且底部手势区一消失就没有退出全屏的手势入口。
 *
 * ## 行为：顶边下滑可临时唤出
 * [WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE]——从屏幕顶边下滑
 * 会短暂叠出状态栏（看时间/电量），约 2s 自动再隐藏。这是沉浸式播放器的标准行为，
 * 也是用户拍板的选择。临时叠出的状态栏是系统覆盖层、不参与本应用布局，
 * 所以唤出/消失全程都不会引起内容位移。
 *
 * ## 为什么挂在 MainActivity 而不是播放页内部
 * 播放页不是 Activity 也不是路由，是常驻在 MainActivity 底壳里的节点
 * （由 `yosBottomSheetConfig.progress` 连续控制显隐）。窗口属性只能由
 * Activity 级节点写——与 `LyricHdrWindowGate` 同一范式、同一挂载位置。
 */

/**
 * 播放壳展开比例 → 是否隐藏状态栏。滞回门禁（0.72 进 / 0.62 出）。
 *
 * 为什么不用 `showNowPlaying` 的 0.70：拖拽在阈值附近来回抖时，状态栏会一闪一闪。
 * 进得晚一点、出得早一点，中间留 0.1 的死区。阈值整体比 0.70 靠后，也保证
 * 收起动画里状态栏先回来，不会出现"主页顶栏已经露出、状态栏还没回"的错位帧。
 *
 * 单独成 composable 是为了让状态只重组这个小节点：直接写进 MainActivity 大作用域，
 * 每次跨界都会让整块外壳重组一次。
 */
@Composable
fun StatusBarAutoHideWindowGate(progress: () -> Float) {
    val currentProgress by rememberUpdatedState(progress)
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { currentProgress() }.collect { p ->
            expanded = if (expanded) p > 0.62f else p > 0.72f
        }
    }
    // 设置项在组合体内读：开关一切换，这个小节点就重组、effect 的 key 变化。
    val wanted = SettingsLibrary.NowPlayingAutoHideStatusBar && expanded
    StatusBarWindowEffect(active = wanted)
}

/**
 * 真正写窗口属性的地方。
 *
 * 所有调用都包 runCatching：`insetsController` 在建窗早期可能为空，
 * 部分 ROM 的 `hide()` 也会抛 IllegalStateException。
 */
@Composable
private fun StatusBarWindowEffect(active: Boolean) {
    val view = LocalView.current
    if (view.isInEditMode) return

    DisposableEffect(view, active) {
        val window = view.context.findActivity()?.window
        if (window == null) {
            return@DisposableEffect onDispose { }
        }
        val controller = runCatching {
            WindowCompat.getInsetsController(window, view)
        }.getOrNull()

        if (active) {
            // 基线由 ProvideRawWindowInsets 的布局监听持续记录（最近一次真实非零读数），
            // 这里只进入钉住态。必须在 hide() 之前——hide 之后读数就归零了。
            pinStatusBarTopInset()
            runCatching {
                controller?.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller?.hide(WindowInsetsCompat.Type.statusBars())
            }
            YosDiagnostics.log(
                "SYSBAR",
                "hide" to true,
                "controller" to (controller != null)
            )
        }

        onDispose {
            // 离开组合（收起播放页 / 关开关 / Activity 销毁）必须把状态栏放回来，
            // 否则整个应用会一直无状态栏，用户只能重启。
            runCatching {
                controller?.show(WindowInsetsCompat.Type.statusBars())
                controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_DEFAULT
            }
            // 解除钉住要等系统真的把状态栏恢复可见（show 是异步的），
            // 否则顶部留白会先塌一帧再弹回来。
            releaseStatusBarTopInsetPin(view)
            if (active) {
                YosDiagnostics.log("SYSBAR", "hide" to false)
            }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
