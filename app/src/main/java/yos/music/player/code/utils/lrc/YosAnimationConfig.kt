package yos.music.player.code.utils.lrc

import androidx.compose.runtime.Stable

/**
 * YosLyricView 动画效果控制类
 *
 * 当前只有较少选项，等待后续开发
 *
 * @param ignoreSystemAnimationScale 是否忽略系统设置的 Animator 动画时长。注意此项有严重的兼容性问题，建议保持默认设置
 */
@Stable
data class YosAnimationConfig(
    val ignoreSystemAnimationScale: Boolean = false
)