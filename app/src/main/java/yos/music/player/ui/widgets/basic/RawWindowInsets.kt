package yos.music.player.ui.widgets.basic

import android.view.View
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/*
 * Raw window insets 提供层（替代 accompanist-insets 0.16.1）。
 *
 * 为什么需要：HyperOS（targetSdk 34 + edge-to-edge）给本应用窗口派发的
 * WindowInsets 恒为 0（appBounds 被系统扣为 [0,152][1280,2707]，但派发到
 * AndroidComposeView 的 insets 是 0——已用探针实测，手动重派发也无法恢复），
 * accompanist 全家与 foundation 的 statusBarsPadding/navigationBarsPadding/
 * WindowInsets.statusBars 因此全部读到 0，顶栏/底栏/把手全部贴边且对手势条
 * 开关无感。而 ViewCompat.getRootWindowInsets() 始终返回系统真实值
 * （top=152 / bottom=65），本层即以它为唯一数据源。
 *
 * 语义：与 accompanist 0.16.1 对齐——padding 助手【不消费】insets（嵌套
 * padding 各自生效），height 助手 = inset + additional。迁移各调用点时
 * 只需把 com.google.accompanist.insets.* 的 import 换成本包同名函数。
 *
 * 响应性：OnGlobalLayoutListener 监听布局变化时重读 raw insets，值有变才
 * 写 state。手势条开关/旋转通常伴随 Activity 重建或全局布局，可覆盖；
 * 纯系统侧 inset 变化（无任何布局事件）理论上收不到，HyperOS 下无已知场景。
 */

private val LocalRawWindowInsets = compositionLocalOf { androidx.core.graphics.Insets.of(0, 0, 0, 0) }

private fun View.readRawSystemBars(): androidx.core.graphics.Insets {
    val raw = ViewCompat.getRootWindowInsets(this) ?: return androidx.core.graphics.Insets.of(0, 0, 0, 0)
    return raw.getInsets(WindowInsetsCompat.Type.systemBars())
}

/**
 * 以系统真实 insets（getRootWindowInsets）为数据源向子树提供 insets。
 * 挂在 Activity 组合根部，替代 accompanist 的 ProvideWindowInsets。
 */
@Composable
fun ProvideRawWindowInsets(content: @Composable () -> Unit) {
    val view = LocalView.current
    val insetsState = remember("RawWindowInsets_state") { mutableStateOf(view.readRawSystemBars()) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val next = view.readRawSystemBars()
            if (next != insetsState.value) {
                insetsState.value = next
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    CompositionLocalProvider(LocalRawWindowInsets provides insetsState.value, content = content)
}

@Composable
private fun currentRawInsets(): androidx.core.graphics.Insets = LocalRawWindowInsets.current

/**
 * 导航条让位基线：隐藏小白条后系统 inset 归零，但底栏/迷你条/播放页底行需要
 * 保持与小白条显示时相同的位置（开关小白条位置不变），故所有导航条【底部】
 * 读数统一取 max(真实 inset, 基线)。基线 = 常见手势条高度 20dp（onyx 实测
 * inset=65px=20dp，开启时即真实值、观感零变化）；三键导航等更大 inset 仍按
 * 真实值让位。状态栏侧不受影响，保持真实值。
 */
private val NavigationBarsBaseline = 20.dp

@Composable
private fun navigationBarsBottomClampedPx(): Int =
    maxOf(currentRawInsets().bottom, with(LocalDensity.current) { NavigationBarsBaseline.roundToPx() })

/** 状态栏顶 inset（px）。 */
@Composable
fun rawStatusBarsTopPx(): Int = currentRawInsets().top

/** 导航条底 inset（px，带 20dp 基线，开关小白条不变）。 */
@Composable
fun rawNavigationBarsBottomPx(): Int = navigationBarsBottomClampedPx()

/** 导航条底 inset（dp，带 20dp 基线，开关小白条不变）。 */
@Composable
fun rawNavigationBarsBottomDp(): Dp =
    with(LocalDensity.current) { navigationBarsBottomClampedPx().toDp() }

/** 状态栏 padding（accompanist 同名替代，非消费语义）。 */
@Composable
fun Modifier.statusBarsPadding(): Modifier {
    val top = with(LocalDensity.current) { currentRawInsets().top.toDp() }
    return if (top > 0.dp) this.padding(top = top) else this
}

/** 导航条 padding（accompanist 同名替代，非消费语义，底部带 20dp 基线）。 */
@Composable
fun Modifier.navigationBarsPadding(): Modifier {
    val bottom = with(LocalDensity.current) { navigationBarsBottomClampedPx().toDp() }
    return this.padding(bottom = bottom)
}

/** 高度 = 状态栏 inset + additional（accompanist 同名替代）。 */
@Composable
fun Modifier.statusBarsHeight(additional: Dp = 0.dp): Modifier {
    val top = with(LocalDensity.current) { currentRawInsets().top.toDp() }
    return this.height(top + additional)
}

/** 高度 = 导航条 inset + additional（accompanist 同名替代，底部带 20dp 基线）。 */
@Composable
fun Modifier.navigationBarsHeight(additional: Dp = 0.dp): Modifier {
    val bottom = with(LocalDensity.current) { navigationBarsBottomClampedPx().toDp() }
    return this.height(bottom + additional)
}
