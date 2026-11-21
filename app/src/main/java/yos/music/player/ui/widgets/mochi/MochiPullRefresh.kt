// Ported from mochi-compose (Apache-2.0), adapted to cormor overScrollVertical

package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.Animatable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * 下拉刷新状态（UIRefreshControl）：读 overScrollVertical 的越界位移，拉过阈值就开始刷新
 * （不等松手），刷新中内容停在转圈下面，结束后弹簧收回。
 *
 * 用法：列表加 `.overScrollVertical(offsetObserver = state.offsetObserver)`，
 * 首个 item 加 [mochiRefreshHold]，再放一个 [MochiRefreshIndicator]。
 */
@Stable
class MochiRefreshState internal constructor(
    private val triggerPx: Float,
    private val holdPx: Float,
    internal val rearmPx: Float,
) {
    private var overscroll by mutableFloatStateOf(0f)

    /** 传给 overScrollVertical 的 offsetObserver */
    val offsetObserver: (Float) -> Unit = { overscroll = it }

    /** 转圈在转（含刷新结束前的最短显示时间） */
    var isRefreshing: Boolean by mutableStateOf(false)
        internal set

    /** 1 = 内容停在转圈下面，结束时弹簧收回 0 */
    internal val holding = Animatable(0f)

    internal fun overscrollY(): Float = overscroll

    fun pullFraction(): Float = (overscroll / triggerPx).coerceIn(0f, 1f)

    /** 布局里多占的高度（px） */
    fun hold(): Float = (holdPx - overscroll.coerceAtLeast(0f)).coerceAtLeast(0f) * holding.value

    /** 转圈所在空隙高度（px）：越界位移 + 停住高度 */
    fun gap(): Float = overscroll.coerceAtLeast(0f) + hold()
}

object MochiRefreshDefaults {
    val Trigger: Dp = 96.dp
    val Hold: Dp = 56.dp
    val Rearm: Dp = 16.dp
    val IndicatorSize: Dp = 24.dp

    /** 即使刷新很快返回，转圈也至少显示这么久，避免一闪而过 */
    const val MinShowMillis: Long = 500L
}

/**
 * @param refreshing 外部的刷新中状态（调用方在 [onRefresh] 里置 true，完成后置 false）
 * @param onRefresh 为 null 时不触发
 */
@Composable
fun rememberMochiRefreshState(refreshing: Boolean, onRefresh: (() -> Unit)?): MochiRefreshState {
    val density = LocalDensity.current
    val state = remember(density) {
        with(density) {
            MochiRefreshState(
                triggerPx = MochiRefreshDefaults.Trigger.toPx(),
                holdPx = MochiRefreshDefaults.Hold.toPx(),
                rearmPx = MochiRefreshDefaults.Rearm.toPx(),
            )
        }
    }
    val refreshingNow = rememberUpdatedState(refreshing)
    val currentOnRefresh = rememberUpdatedState(onRefresh)
    val view = LocalView.current
    val haptics = remember(view) { MochiHaptics(view, MochiHapticProfile.IosLike) }

    // 拉过阈值触发（不等松手）；收回到一小段以内才能再次触发
    LaunchedEffect(state) {
        var armed = true
        snapshotFlow { state.overscrollY() }.collect { y ->
            if (y < state.rearmPx) armed = true
            val cb = currentOnRefresh.value
            if (cb != null && armed && !state.isRefreshing && state.pullFraction() >= 1f) {
                armed = false
                state.isRefreshing = true
                haptics.threshold()
                state.holding.snapTo(1f)
                cb()
            }
        }
    }

    // 外部直接把 refreshing 置 true（非手势触发）：也停住并转起来
    LaunchedEffect(refreshing) {
        if (refreshing && !state.isRefreshing) {
            state.isRefreshing = true
            state.holding.animateTo(1f, MochiMotions.responseSpring(0.4f, 1f))
        }
    }

    // 收尾：等外部 refreshing 回到 false，并保证最短显示时间，然后弹簧收回
    LaunchedEffect(state.isRefreshing) {
        if (!state.isRefreshing) return@LaunchedEffect
        val start = System.currentTimeMillis()
        // onRefresh 后调用方可能晚一帧才把 refreshing 置 true，给 300ms 等它
        withTimeoutOrNull(300L) { snapshotFlow { refreshingNow.value }.first { it } }
        snapshotFlow { refreshingNow.value }.first { !it }
        val left = MochiRefreshDefaults.MinShowMillis - (System.currentTimeMillis() - start)
        if (left > 0) delay(left)
        state.holding.animateTo(0f, MochiMotions.responseSpring(0.4f, 1f))
        state.isRefreshing = false
    }
    return state
}

/** 刷新中让内容往下让出停住的高度（放在首个 item 上，测量阶段读，不重组） */
fun Modifier.mochiRefreshHold(state: MochiRefreshState): Modifier = layout { m, c ->
    val p = m.measure(c)
    val h = state.hold().roundToInt()
    layout(p.width, p.height + h) { p.place(0, h) }
}

/**
 * 下拉刷新转圈：放在列表上沿（顶栏下沿），在上沿与被拉下来的内容之间居中。
 * @param scrollAway 列表首项已滚出的像素数；刷新中用户继续下滚时，转圈跟着上移并淡出
 */
@Composable
fun MochiRefreshIndicator(
    state: MochiRefreshState,
    modifier: Modifier = Modifier,
    color: Color = MochiProgressDefaults.activityColor(),
    scrollAway: () -> Float = { 0f },
) {
    MochiActivityIndicator(
        modifier.graphicsLayer {
            val gap = state.gap()
            val away = scrollAway()
            translationY = gap / 2f - MochiRefreshDefaults.IndicatorSize.toPx() / 2f - away
            alpha = smoothstep(8.dp.toPx(), 28.dp.toPx(), gap) *
                (1f - smoothstep(0f, 40.dp.toPx(), away))
        },
        size = MochiRefreshDefaults.IndicatorSize,
        color = color,
        reveal = if (state.isRefreshing) null else ({ state.pullFraction() }),
    )
}
