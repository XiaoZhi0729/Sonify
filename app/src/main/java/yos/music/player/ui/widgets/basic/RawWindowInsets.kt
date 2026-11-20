package yos.music.player.ui.widgets.basic

import android.view.View
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
 * 状态栏顶 inset 的「钉住」状态（播放页自动隐藏状态栏用）。
 *
 * 为什么需要：系统隐藏状态栏后 `systemBars().top` 会归零，播放页 6 处
 * `statusBarsPadding()` 与两处高度算式会同时塌掉，整页上跳一个状态栏高度。
 * 而设计要求是**内容位置一个像素都不动、只有背景/封面铺满到顶端**，因此隐藏期间
 * 顶部读数必须沿用隐藏前的实测值。
 *
 * 为什么记实测值而不是写死常量：状态栏高度随机型/DPI/刘海变化（本机 152px，
 * 其它机型不同），写死常量在别的设备上必然错位。
 *
 * 为什么基线是「最近一次真实非零读数」而不是 pin 那一刻的读数：隐藏期间读到的
 * 恒为 0，若在已隐藏态重新 pin（例如旋转后重建），当场读值会得到 0；
 * 而旋转回竖屏时状态栏仍是隐藏的，再也没有机会量到真值。保留最近一次非零读数
 * 才能覆盖"横屏↔竖屏来回转"的情形。
 *
 * 为什么按方向生效：横屏状态栏在**侧边**，顶 inset 合法为 0。若在横屏也套用竖屏
 * 的基线，横屏播放页顶部会被凭空压掉一个状态栏高度。故 [apply] 只在竖屏生效。
 *
 * 只在布局监听/`post` 里写状态，不在组合期写（组合期写快照会触发反复重组）。
 * 未钉住、方向不匹配或还没量到过基线时，[apply] 原样返回 —— 与历史行为完全一致。
 */
private object StatusBarTopPin {
    /** 最近一次读到的非零顶 inset（px）。0 表示本进程还没量到过。 */
    var baselinePx: Int by mutableIntStateOf(0)
        private set

    /** 是否处于「隐藏状态栏但布局按原值让位」的钉住态。 */
    var pinned: Boolean by mutableStateOf(false)
        private set

    /** 由 [ProvideRawWindowInsets] 的布局监听调用，记录真实读数作基线。 */
    fun recordReading(topPx: Int) {
        if (topPx > 0 && topPx != baselinePx) baselinePx = topPx
    }

    fun pin() {
        pinned = true
    }

    fun release() {
        pinned = false
    }

    /**
     * 把原始读数套上钉住值。
     *
     * [landscape] 时必须原样返回：横屏顶 inset 本就该是 0，钉住会压低内容。
     */
    fun apply(
        raw: androidx.core.graphics.Insets,
        landscape: Boolean
    ): androidx.core.graphics.Insets {
        if (!pinned || landscape || baselinePx <= 0) return raw
        val top = maxOf(raw.top, baselinePx)
        if (top == raw.top) return raw
        return androidx.core.graphics.Insets.of(raw.left, top, raw.right, raw.bottom)
    }
}

/**
 * 开始钉住状态栏顶 inset。必须紧接在真正调用 `hide()` 之前调用，
 * 由 `code/systemui/AutoHideStatusBar.kt` 的窗口效果独占使用，其它地方不要调。
 */
fun pinStatusBarTopInset() = StatusBarTopPin.pin()

/**
 * 解除钉住，但只在系统状态栏**真的已经回来**之后。
 *
 * 时序问题：`show()` 是异步的，紧接着读 inset 往往还是 0。那时若立即解除钉住，
 * 顶部留白会在这一帧塌成 0、下一帧又弹回来（一次可见的上跳）。
 *
 * 判据用 `isVisible(statusBars)` 而不是「inset > 0」：横屏下状态栏在侧边、
 * 顶 inset 本就是 0，用 inset 判会永远解不开。逐帧重试有限次；仍不可见就保持钉住
 * （此时竖屏读数被钳在真实基线、横屏不套用，都不影响布局）。
 */
fun releaseStatusBarTopInsetPin(view: View, attemptsLeft: Int = 6) {
    view.post {
        val root = runCatching { ViewCompat.getRootWindowInsets(view) }.getOrNull()
        val visible = root != null && root.isVisible(WindowInsetsCompat.Type.statusBars())
        if (visible) {
            StatusBarTopPin.release()
        } else if (attemptsLeft > 0) {
            releaseStatusBarTopInsetPin(view, attemptsLeft - 1)
        }
    }
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
        // 首次布局前先把当前读数记成基线，避免"还没发生过布局事件就第一次 pin"时
        // 没有基线可用（那种情况下钉住等于没钉，顶部会塌一次）。
        StatusBarTopPin.recordReading(insetsState.value.top)
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val next = view.readRawSystemBars()
            // 记录真实读数作为钉住基线：隐藏状态栏期间这里是 0，不会覆盖已有值。
            StatusBarTopPin.recordReading(next.top)
            if (next != insetsState.value) {
                insetsState.value = next
            }
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    // 横屏时状态栏在侧边、顶 inset 本就该是 0，钉住只在竖屏生效。
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val landscape = configuration.screenWidthDp > configuration.screenHeightDp
    // 钉住态只在「播放页自动隐藏状态栏」期间为真；为假时 apply() 原样返回，
    // 与历史行为逐字节相同。这里在组合期只**读**状态，不写。
    CompositionLocalProvider(
        LocalRawWindowInsets provides StatusBarTopPin.apply(insetsState.value, landscape),
        content = content
    )
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

/**
 * 输入法（软键盘）遮挡高度：px，0 表示键盘没弹。
 *
 * ★为什么不直接用 foundation 的 `WindowInsets.ime`：本工程在 HyperOS 上
 *   Compose 侧的 WindowInsets 恒派发 0（见文件头），`imePadding()` 因此完全失效。
 *   引导页的手机号输入行必须知道"键盘有没有弹"，只能走同一个
 *   `ViewCompat.getRootWindowInsets()` 数据源。
 *
 * 用 [WindowInsetsCompat.Type.ime] 而不是 `ime()`：前者是"键盘占的高度"，
 * 键盘收起时归 0；后者在收起时等于导航条高度（不能当"是否弹出"的判据）。
 */
@Composable
fun rawImeBottomPx(): Int {
    val view = LocalView.current
    val state = remember("RawWindowInsets_ime") { mutableStateOf(0) }
    DisposableEffect(view) {
        val listener = android.view.ViewTreeObserver.OnGlobalLayoutListener {
            val next = runCatching {
                ViewCompat.getRootWindowInsets(view)
                    ?.getInsets(WindowInsetsCompat.Type.ime())
                    ?.bottom ?: 0
            }.getOrDefault(0)
            if (next != state.value) state.value = next
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(listener)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(listener) }
    }
    return state.value
}

/** 键盘是否弹起（按"IME 高度 > 0"判定）。 */
@Composable
fun rawImeVisible(): Boolean = rawImeBottomPx() > 0

/** 输入法 padding（raw 版 `imePadding()`，非消费语义）。 */
@Composable
fun Modifier.rawImePadding(): Modifier {
    val px = rawImeBottomPx()
    if (px <= 0) return this
    val bottom = with(LocalDensity.current) { px.toDp() }
    return this.padding(bottom = bottom)
}

/** 状态栏顶 inset（px）。 */
@Composable
fun rawStatusBarsTopPx(): Int = currentRawInsets().top
/** 状态栏顶 inset（dp）。 */
@Composable
fun rawStatusBarsTopDp(): Dp = with(LocalDensity.current) { currentRawInsets().top.toDp() }

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
