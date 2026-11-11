package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import yos.music.player.ui.widgets.basic.SkeletonShimmer
import yos.music.player.ui.widgets.basic.skeleton

/**
 * 私人电台板块的骨架屏（**只画内容，不画标题**）。
 *
 * `HomeSkeletonSection`（`Discovery.kt` 的 slot）已经画了 24dp 顶部 padding 和
 * 标题行，所以这里不能再画一遍——上版重复了一遍 header，加载态会比真实内容
 * 高出 34dp，数据到位交叉淡化时整个板块会「往上跳一下」。
 *
 * 几何直接引用 [FmStageHeight] / [FmGlassWidth] / [FmRailStart] 等真实常量：
 * 骨架的职责是"精确复刻版式"，自己再抄一份数字只会两边慢慢漂移。
 *
 * ★板块铺满屏宽（封面要能从屏幕外进来），缩进由 [FmRailStart] 单独给，
 *   不再用 `padding(horizontal = 20.dp)` 把整块框窄。
 */
@Composable
internal fun HomeSkeletonPersonalFm(shimmer: SkeletonShimmer) {
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .height(FmStageHeight)
            .clipToBounds()
    ) {
        // 圆封面占位：起点与真实 Pager 的 contentPadding(start) 一致（含 rail 缩进）
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = fmPagerStartPadding())
                .height(FmStageHeight),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(FmCircleGap)
        ) {
            repeat(3) {
                Box(
                    Modifier
                        .size(FmCircleDiameter)
                        .clip(CircleShape)
                        .skeleton(shimmer, cornerRadius = FmCircleDiameter / 2)
                )
            }
        }

        // 玻璃板占位：左缩进 FmRailStart、上下内缩、尺寸与真实玻璃同源
        Box(
            Modifier
                .padding(start = FmRailStart, top = FmGlassInsetY)
                .size(width = FmGlassWidth, height = FmGlassHeight)
                .clip(RoundedCornerShape(FmGlassCorner))
                .skeleton(shimmer, cornerRadius = FmGlassCorner)
        )
    }
}
