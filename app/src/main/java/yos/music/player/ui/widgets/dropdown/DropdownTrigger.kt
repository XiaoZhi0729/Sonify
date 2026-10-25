package yos.music.player.ui.widgets.dropdown

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

// =====================================================================
// 弹窗触发区：把「触发内容实测尺寸 → collapseSize」与「展开时硬切隐藏」
// 这两件事收成一个状态，照抄 NexioSchedule 1.6.5 的 OverlayDropdownMenu 用法：
//
//   val trigger = rememberDropdownTriggerState(expanded)
//   Box(Modifier.dropdownTriggerMeasure(trigger)) {
//       Content(Modifier.dropdownTriggerAlpha(trigger))
//       LiquidDropdownLayout(collapseSize = trigger.size, collapseContent = { Content() },
//                            onDismissFinished = trigger::onDismissFinished, ...)
//   }
//
// 判据不能用 fraction：退场 spring 阻尼 0.85 会过冲到负值并在 0 附近振荡，
// 拿阈值判断会在振荡途中误判结束 → 触发内容提前冒出来。onDismissFinished 是
// 所有退场动画结束、snapTo(0) 之后才发的信号。
//
// 与官方 1.6.5 `OverlayDropdownMenu` 逐字一致：
//   - 展开：延后 2 帧隐藏（第一帧让弹窗画出收起态，第二帧确保已上屏）
//   - 退场：onDismissFinished 后压 3 帧再显示（"否则弹窗还在屏幕上会重叠"）
//
// 这 3 帧不是可以随意优化掉的经验值：它配合宿主侧的 `AnimatedVisibility`
// （见 LiquidDropdownLayout.DropdownPopupLayer）——弹窗内容在 targetState=false 后
// 还要走完一次 transition 才真正从组合移除，这 3 帧正好覆盖那段撤下延迟。
// 少了它 → 触发区文字与残留面板同帧可见（重影）；把撤下时机提前 → 空白（闪）。
// =====================================================================

@Stable
class DropdownTriggerState internal constructor(private val scope: CoroutineScope) {

    /** 触发区实测尺寸（px）：弹窗收起态的起点尺寸。 */
    var size by mutableStateOf(IntSize.Zero)
        internal set

    /** true = 触发区可见。展开后延后两帧才置 false，退场完成压 3 帧再置回 true。 */
    internal var visibleState by mutableStateOf(true)

    /** 触发内容透明度：**硬切**，没有半透明中间态。 */
    val alpha: Float get() = if (visibleState) 1f else 0f

    internal fun onExpanded() {
        scope.launch {
            // 第一帧让弹窗画出收起态，第二帧确保它已上屏，否则中间会空一帧
            repeat(2) { withFrameNanos { } }
            visibleState = false
        }
    }

    /**
     * 接给弹层的 onDismissFinished。
     *
     * **立即**显示触发区，不压帧。依据（本项目这条链上成立、官方也成立）：
     *
     * 1. 位置精确重合：收起态矩形 = 触发区尺寸 + collapseExtra(14,10dp)，
     *    其中心比触发区偏左 7dp/偏上 5dp；而定位偏移
     *    `LiquidDropdownAnchorOffsetX/Y = 7dp/5dp` 把面板整体右移/下移同样的量
     *    → 两者精确抵消，f=0 时 collapseContent 与触发区文字像素级重合。
     * 2. 浓度相同：collapseContent 的 alpha = 1 - f×1.7，f=0 时恰好 = 1（全显）。
     * 3. 内容相同：两处渲染的是同一份「选中值 + 箭头」。
     *
     * 所以在 onDismissFinished 当帧切换，屏幕上看到的是同一位置、同一内容、同一浓度
     * 的文字 —— 没有任何视觉变化。反过来压 3 帧，就会出现「弹窗已撤、触发区还没显示」
     * 的空白窗口（关闭时文字闪一下）。
     *
     * 注：官方 `repeat(3)` 的注释是「否则弹窗还在屏幕上会重叠」，针对的是宿主层
     * （AnimatedVisibility）比状态晚若干帧撤下的架构；本项目退场由 ListPopupLayout
     * 自己驱动、`internalVisible` 置 false 后同帧就不再渲染，没有那段延迟。
     */
    fun onDismissFinished() {
        visibleState = true
    }
}

@Composable
fun rememberDropdownTriggerState(expanded: Boolean): DropdownTriggerState {
    val scope = rememberCoroutineScope()
    val state = remember { DropdownTriggerState(scope) }
    LaunchedEffect(expanded) {
        if (expanded) state.onExpanded()
    }
    return state
}

/** 上报触发区实测尺寸（挂在触发内容外层，弹窗在它的子树里）。 */
fun Modifier.dropdownTriggerMeasure(state: DropdownTriggerState): Modifier =
    onSizeChanged { state.size = it }

/** 触发内容的硬切透明度（展开即消失、退场动画跑完立刻出现）。 */
fun Modifier.dropdownTriggerAlpha(state: DropdownTriggerState): Modifier =
    alpha(state.alpha)

/** 弹窗内容的默认最小宽度（原版 ListPopupDefaults.MinWidth）。 */
internal val DropdownMinWidth = 200.dp
