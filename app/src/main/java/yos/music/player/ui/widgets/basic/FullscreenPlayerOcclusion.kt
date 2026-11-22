package yos.music.player.ui.widgets.basic

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 底层导航页面是否被全屏播放页完全遮挡（progress >= 0.99 && 未拖拽 && 无落位动画）。
 * 仅应在绘制阶段或协程快照流中读取，避免播放页展开/收起临界点触发整树重组。
 */
val LocalOccludedByFullscreenPlayer = staticCompositionLocalOf<() -> Boolean> { { false } }
