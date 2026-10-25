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

    /** 接给弹层的 onDismissFinished。 */
    fun onDismissFinished() {
        scope.launch {
            // 压满 3 帧再显示：弹窗节点撤下后渲染还要一帧才真正不画
            repeat(3) { withFrameNanos { } }
            visibleState = true
        }
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
