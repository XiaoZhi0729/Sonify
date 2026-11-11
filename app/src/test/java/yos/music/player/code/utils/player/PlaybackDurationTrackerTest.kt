package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * CSCC `duration` 的计时口径（[PlaybackDurationTracker]）。
 *
 * 上报的 duration 决定服务器怎么记账，三点不能改坏：
 *  1. 暂停期间不累计（否则挂机一夜记满时长）；
 *  2. seek 不产生额外时长（按墙钟而非 position 差）；
 *  3. 切歌 = 前一首结账（一段播放的边界）。
 */
class PlaybackDurationTrackerTest {

    private var now = 1_000L
    private val tracker = PlaybackDurationTracker { now }

    private fun advance(ms: Long) {
        now += ms
    }

    @Test
    fun `counts wall clock while playing`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(30_000)
        val segment = tracker.end()
        assertEquals(30_000L, segment!!.durationMs)
    }

    @Test
    fun `pause stops the clock`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(10_000)
        tracker.setPlaying(false)
        advance(60_000) // 暂停 1 分钟，不该计入
        tracker.setPlaying(true)
        advance(20_000)
        assertEquals(30_000L, tracker.end()!!.durationMs)
    }

    @Test
    fun `started while paused credits nothing until playback`() {
        // 切歌时常处于缓冲态，必须传 playing=false；否则缓冲等待会被算成收听
        tracker.start(songId = "a", mixsongid = "1", playing = false)
        advance(45_000)
        tracker.setPlaying(true)
        advance(15_000)
        assertEquals(15_000L, tracker.end()!!.durationMs)
    }

    @Test
    fun `seek neither adds nor resets accumulated time`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(10_000)
        tracker.onSeek()
        advance(500)   // seek 执行耗时不计入下一段
        advance(9_500)
        assertEquals(20_000L, tracker.end()!!.durationMs)
    }

    @Test
    fun `seek while paused does not start the clock`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        tracker.setPlaying(false)
        advance(5_000)
        tracker.onSeek()
        advance(5_000)
        assertEquals(0L, tracker.end()!!.durationMs)
    }

    @Test
    fun `repeated pause and resume is idempotent`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(10_000)
        tracker.setPlaying(false)
        tracker.setPlaying(false) // 重复回调不能重复结算
        advance(10_000)
        tracker.setPlaying(true)
        tracker.setPlaying(true)
        advance(10_000)
        assertEquals(20_000L, tracker.end()!!.durationMs)
    }

    @Test
    fun `switching songs bills the previous segment`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(25_000)
        val previous = tracker.snapshot(PlaybackEndReason.SWITCHED)
        assertEquals("1", previous!!.mixsongid)
        assertEquals(25_000L, previous.durationMs)
        assertEquals(PlaybackEndReason.SWITCHED, previous.reason)
        // 旧段结账后必须清空，否则下一首会把上一首的时长带过去
        assertNull(tracker.snapshot())
    }

    @Test
    fun `new segment starts from zero`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(25_000)
        tracker.snapshot(PlaybackEndReason.SWITCHED)
        tracker.start(songId = "b", mixsongid = "2", playing = true)
        advance(8_000)
        val segment = tracker.end()!!
        assertEquals("2", segment.mixsongid)
        assertEquals(8_000L, segment.durationMs)
    }

    @Test
    fun `end without tracking returns null`() {
        assertNull(tracker.end())
    }

    @Test
    fun `pause and seek before start are ignored`() {
        // 服务刚起来时播放器会先派发状态回调，此时还没有段
        tracker.setPlaying(false)
        tracker.setPlaying(true)
        tracker.onSeek()
        assertFalse(tracker.isTracking)
        assertNull(tracker.end())
    }

    @Test
    fun `discard drops the segment without reporting`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(60_000)
        tracker.discard()
        assertFalse(tracker.isTracking)
        assertNull(tracker.end())
    }

    @Test
    fun `short playback is below the reporting threshold`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(14_999)
        val segment = tracker.end()!!
        assertFalse(segment.reachesThreshold(15_000))
    }

    @Test
    fun `threshold is inclusive at the boundary`() {
        tracker.start(songId = "a", mixsongid = "1", playing = true)
        advance(15_000)
        assertTrue(tracker.end()!!.reachesThreshold(15_000))
    }
}
