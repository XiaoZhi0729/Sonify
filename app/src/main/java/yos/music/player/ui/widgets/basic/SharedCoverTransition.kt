package yos.music.player.ui.widgets.basic

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.lerp

/**
 * 记录最近一次点击入口的封面圆角，供目标端（详情封面）转场使用。
 *
 * shared element 转场期间 overlay 会把画面交叉淡化到目标端内容，源端自身的
 * 插值不可见；因此"圆角平滑过渡"必须由目标端完成：进入时从来源圆角渐变到
 * 自身圆角，返回（退出）时反向。各入口在点击时写入自己的静止圆角：
 * 行封面 3.5dp、搜索网格 8dp、方卡 7dp；未记录（进程重建直达详情）默认 7dp。
 */
object SharedCoverStyle {
    var lastSourceCorner: Dp = Dp(7f)
}

/**
 * 共享封面转场进度：静止（Visible）为 1，进入起点（PreEnter）与退出终点（PostExit）为 0。
 */
@Composable
fun AnimatedVisibilityScope.rememberSharedCoverProgress(): State<Float> =
    transition.animateFloat(
        transitionSpec = { tween(300) },
        label = "shared-cover-progress"
    ) { state ->
        when (state) {
            EnterExitState.PreEnter -> 0f
            EnterExitState.Visible -> 1f
            EnterExitState.PostExit -> 0f
        }
    }

/**
 * 目标端封面圆角：进入/返回转场中从来源圆角渐变到 [own]，静止恒为 [own]。
 */
@Composable
fun AnimatedVisibilityScope.rememberEnterCoverCorner(own: Dp): Dp {
    val progress = rememberSharedCoverProgress()
    return lerp(SharedCoverStyle.lastSourceCorner, own, progress.value)
}

/** 目标端封装：无转场 scope 时恒为 [own]。 */
@Composable
fun enterCoverCorner(
    animatedVisibilityScope: AnimatedVisibilityScope?,
    own: Dp
): Dp = if (animatedVisibilityScope != null) {
    with(animatedVisibilityScope) { rememberEnterCoverCorner(own) }
} else own
