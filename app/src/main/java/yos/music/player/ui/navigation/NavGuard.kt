package yos.music.player.ui.navigation

import android.os.SystemClock

/**
 * 导航防抖（防「双击穿透」）：一次导航后极短窗口内的后续导航一律忽略。
 *
 * 典型场景：快速双击列表卡片时，第一下已经开始页面转场，第二下会落到**新页面**
 * 恰好位于同一坐标的可点元素上（如专辑详情页顶部的歌手名链接），导致误跳转。
 *
 * **单层原则（本轮教训）**：一层调用链里只允许一处 [run]。
 * `NewAlbumCard` 曾在内部自带 run，而外层调用方（艺人页/大全页 onOpen）也包了
 * run——内层刚记录时间戳，外层立刻落在窗口内被拦，导航被自己吞掉，表现为
 * 「除置顶卡外所有专辑都点不开」。组件内部一律不设防抖，由最外层导航发起方负责。
 */
object NavGuard {
    private const val WINDOW_MS = 450L
    private var lastNavigationAt = 0L

    /** 允许时执行 [block]（并记录时间），处于防抖窗口内则忽略。 */
    fun run(block: () -> Unit) {
        val now = SystemClock.uptimeMillis()
        if (now - lastNavigationAt < WINDOW_MS) return
        lastNavigationAt = now
        block()
    }
}
