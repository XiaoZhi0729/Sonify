package yos.music.player.widget

import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Test
import yos.music.player.code.NotificationPlaybackMode
import yos.music.player.code.nextNotificationMode
import yos.music.player.code.notificationModeOf

class SonifyWidgetStateTest {

    @Test
    fun `buildOrderedQueue puts lastTrack first and wraps around`() {
        val q = (1..5).map {
            WidgetTrackSnapshot(mediaId = "id-$it", title = "Song $it", artist = "Artist $it")
        }
        val last = WidgetTrackSnapshot(mediaId = "id-3", title = "Song 3", artist = "Artist 3")

        val ordered = SonifyWidgetState.buildOrderedQueue(last, q)

        assertEquals(listOf("id-3", "id-4", "id-5", "id-1", "id-2"), ordered.map { it.mediaId })
    }

    @Test
    fun `buildOrderedQueue deduplicates and caps at maxItems`() {
        val q = (1..30).map {
            WidgetTrackSnapshot(mediaId = "id-${it % 25}", title = "Song $it", artist = "Artist")
        }
        val last = WidgetTrackSnapshot(mediaId = "id-2", title = "Song 2", artist = "Artist")

        val ordered = SonifyWidgetState.buildOrderedQueue(last, q, maxItems = 20)

        assertEquals(20, ordered.size)
        assertEquals("id-2", ordered.first().mediaId)
        assertEquals(ordered.size, ordered.map { it.mediaId }.toSet().size)
    }

    @Test
    fun `buildOrderedQueue falls back to lastTrack when queue is empty`() {
        val last = WidgetTrackSnapshot(mediaId = "id-1", title = "Only", artist = "Solo")

        val ordered = SonifyWidgetState.buildOrderedQueue(last, emptyList())

        assertEquals(listOf(last), ordered)
    }

    @Test
    fun `notification playback mode cycles through all four states`() {
        var shuffle = false
        var repeat = Player.REPEAT_MODE_OFF

        val visited = mutableListOf<NotificationPlaybackMode>()
        repeat(4) {
            visited += notificationModeOf(shuffle, repeat)
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

        assertEquals(
            listOf(
                NotificationPlaybackMode.ORDER,
                NotificationPlaybackMode.REPEAT_ALL,
                NotificationPlaybackMode.REPEAT_ONE,
                NotificationPlaybackMode.SHUFFLE,
            ),
            visited,
        )
    }

    @Test
    fun `marquee focus planner evaluates title and artist overflow independently`() {
        assertEquals(
            MarqueeOverflowState(titleOverflows = true, artistOverflows = true),
            MarqueeFocusPlanner.decideOverflowFromWidths(600f, 580f, 500),
        )
        assertEquals(
            MarqueeOverflowState(titleOverflows = false, artistOverflows = true),
            MarqueeFocusPlanner.decideOverflowFromWidths(420f, 520f, 500),
        )
        assertEquals(
            MarqueeOverflowState(titleOverflows = false, artistOverflows = false),
            MarqueeFocusPlanner.decideOverflowFromWidths(500.4f, 300f, 500),
        )
    }

    @Test
    fun `marquee focus planner defaults priority to title unless only artist overflows`() {
        assertEquals(
            MarqueeFocusTarget.TITLE,
            MarqueeFocusPlanner.decideFromWidths(600f, 580f, 500),
        )
        assertEquals(
            MarqueeFocusTarget.ARTIST,
            MarqueeFocusPlanner.decideFromWidths(420f, 520f, 500),
        )
        assertEquals(
            MarqueeFocusTarget.NONE,
            MarqueeFocusPlanner.decideFromWidths(420f, 300f, 500),
        )
    }
}
