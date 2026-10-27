package yos.music.player.code.utils.lrc

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricRenderCacheTest {
    private val factory = YosLrcFactory()

    /**
     * 行级 LRC 的行结束时间必须回填成下一行起点，不能停在 `end == start`。
     *
     * 库的 `LyricsPlaybackTimeline` 只在 `end > start` 时把行登记进焦点集合，零长度行
     * 永远进不去 → focus 退化成「下一个还没开始的行」（滚动超前一行），并且
     * `LyricsPreparation` 的 `timing.end > timing.start` 不成立 → 不建扫色窗口，整行恒为未唱色。
     */
    @Test
    fun lineLevelLrcTakesEndFromNextLineStart() {
        val entries = factory.formatLrcEntries(
            "[00:01.000]First line\n[00:03.000]Second line"
        )
        val lines = buildSyncedLyrics(entries, emptyList()).lines

        assertEquals(1000, lines[0].start)
        assertEquals(3000, lines[0].end)
        assertEquals(3000, lines[1].start)
        assertEquals(6000, lines[1].end)
    }

    @Test
    fun finalLineLevelLineFallsBackToBoundedDuration() {
        val entries = factory.formatLrcEntries("[00:01.000]Only line")
        val line = buildSyncedLyrics(entries, emptyList()).lines.single()

        assertEquals(1000, line.start)
        assertEquals(4000, line.end)
    }

    @Test
    fun wordTimedLrcKeepsItsOriginalSyllableTiming() {
        val entry = factory.formatLrcEntries(
            "[00:01.000]Fir[00:01.500]st"
        ).single()
        val line = buildSyncedLyrics(listOf(entry), emptyList()).lines.single()
            as KaraokeLine.MainKaraokeLine

        assertEquals(1000, line.start)
        assertEquals(1500, line.syllables[0].end)
        assertEquals(1500, line.syllables[1].start)
        assertEquals(4000, line.end)
    }

    /** 逐字行的末词由下一行首戳回填，回填逻辑不得把它改写成行起点。 */
    @Test
    fun wordTimedLineEndIsNotOverwrittenByNextLineStart() {
        val entries = factory.formatLrcEntries(
            "[00:01.000]Fir[00:01.500]st\n[00:02.000]Next"
        )
        val lines = buildSyncedLyrics(entries, emptyList()).lines

        // 末词结束时间由下一行首戳（2000）回填，本就行起点之后，无需兜底也不得被改写
        assertEquals(2000, lines[0].end)
        assertEquals(2000, lines[1].start)
        assertEquals(5000, lines[1].end)
    }

    /** 每一行都必须是非零长度，否则库不会把它当焦点行。 */
    @Test
    fun everyLineHasPositiveDuration() {
        val entries = factory.formatLrcEntries(
            "[00:01.000]A\n[00:03.000]B\n[00:05.000]C"
        )
        val lines = buildSyncedLyrics(entries, emptyList()).lines

        assertTrue(lines.all { it.end > it.start })
    }
}
