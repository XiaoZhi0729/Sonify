package yos.music.player.code

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 通知栏「播放模式」合并键的四态映射（[notificationModeOf] / [nextNotificationMode]）。
 *
 * 为什么值得单测：通知栏只剩一颗键来表达两路**独立**状态（shuffle / repeat），
 * 「显示哪一态」和「点一下推到哪一态」是两张必须成对的表。本项目在同类双向映射上
 * 踩过「点 A 显示 B」的坑（网格预设那张表只做了写入方向），所以这里把两张表都钉死。
 */
class NotificationPlaybackModeTest {

    // ---------- 派生：两路状态 → 显示态 ----------

    @Test
    fun `shuffle off and repeat off shows order`() {
        assertEquals(
            NotificationPlaybackMode.ORDER,
            notificationModeOf(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_OFF)
        )
    }

    @Test
    fun `repeat all shows repeat all`() {
        assertEquals(
            NotificationPlaybackMode.REPEAT_ALL,
            notificationModeOf(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_ALL)
        )
    }

    @Test
    fun `repeat one shows repeat one`() {
        assertEquals(
            NotificationPlaybackMode.REPEAT_ONE,
            notificationModeOf(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `shuffle on shows shuffle`() {
        assertEquals(
            NotificationPlaybackMode.SHUFFLE,
            notificationModeOf(shuffleModeEnabled = true, repeatMode = Player.REPEAT_MODE_OFF)
        )
    }

    /**
     * 应用内两颗独立键允许按成「随机 + 单曲循环」并存，通知栏必须收敛到某一态而不是
     * 无图标可画。约定随机优先——这条边界是合并方案的固有取舍，改优先级就会改变这里。
     */
    @Test
    fun `shuffle wins over repeat one when both are on`() {
        assertEquals(
            NotificationPlaybackMode.SHUFFLE,
            notificationModeOf(shuffleModeEnabled = true, repeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `shuffle wins over repeat all when both are on`() {
        assertEquals(
            NotificationPlaybackMode.SHUFFLE,
            notificationModeOf(shuffleModeEnabled = true, repeatMode = Player.REPEAT_MODE_ALL)
        )
    }

    // ---------- 推进：点一下到哪一态 ----------

    @Test
    fun `order advances to repeat all`() {
        assertEquals(
            NotificationPlaybackMode.REPEAT_ALL,
            nextNotificationMode(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_OFF)
        )
    }

    @Test
    fun `repeat all advances to repeat one`() {
        assertEquals(
            NotificationPlaybackMode.REPEAT_ONE,
            nextNotificationMode(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_ALL)
        )
    }

    @Test
    fun `repeat one advances to shuffle`() {
        assertEquals(
            NotificationPlaybackMode.SHUFFLE,
            nextNotificationMode(shuffleModeEnabled = false, repeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    @Test
    fun `shuffle advances back to order`() {
        assertEquals(
            NotificationPlaybackMode.ORDER,
            nextNotificationMode(shuffleModeEnabled = true, repeatMode = Player.REPEAT_MODE_OFF)
        )
    }

    /**
     * 「随机 + 单曲循环」并存时点一下必须落到顺序，而不是从单曲那一路继续推。
     * 否则用户会看到「明明是随机图标，点一下却进了列表循环」这种不自洽的跳变。
     */
    @Test
    fun `clicking while shuffle and repeat one are both on lands on order`() {
        assertEquals(
            NotificationPlaybackMode.ORDER,
            nextNotificationMode(shuffleModeEnabled = true, repeatMode = Player.REPEAT_MODE_ONE)
        )
    }

    // ---------- 成对性：连点四下必须回到起点 ----------

    @Test
    fun `four clicks cycle back to the starting mode`() {
        // 从每个可能的起始态各转一圈，用「推进后的目标态」重新派生，模拟真实落盘后的读回
        val starts = listOf(
            NotificationPlaybackMode.ORDER to (false to Player.REPEAT_MODE_OFF),
            NotificationPlaybackMode.REPEAT_ALL to (false to Player.REPEAT_MODE_ALL),
            NotificationPlaybackMode.REPEAT_ONE to (false to Player.REPEAT_MODE_ONE),
            NotificationPlaybackMode.SHUFFLE to (true to Player.REPEAT_MODE_OFF)
        )
        for ((expectedStart, state) in starts) {
            var shuffle = state.first
            var repeat = state.second
            assertEquals(
                "起始态派生不一致：$expectedStart",
                expectedStart,
                notificationModeOf(shuffle, repeat)
            )
            // 每步都把目标态写回（applyNotificationMode 的等价效果），再派生回来核对
            repeat(4) {
                when (nextNotificationMode(shuffle, repeat)) {
                    NotificationPlaybackMode.ORDER -> {
                        shuffle = false
                        repeat = Player.REPEAT_MODE_OFF
                    }

                    NotificationPlaybackMode.REPEAT_ALL -> {
                        shuffle = false
                        repeat = Player.REPEAT_MODE_ALL
                    }

                    NotificationPlaybackMode.REPEAT_ONE -> {
                        shuffle = false
                        repeat = Player.REPEAT_MODE_ONE
                    }

                    NotificationPlaybackMode.SHUFFLE -> {
                        shuffle = true
                        repeat = Player.REPEAT_MODE_OFF
                    }
                }
            }
            assertEquals("四步没有回到起点：$expectedStart", expectedStart, notificationModeOf(shuffle, repeat))
        }
    }
}
