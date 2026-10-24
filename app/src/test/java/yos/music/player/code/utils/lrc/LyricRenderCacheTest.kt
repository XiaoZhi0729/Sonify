package yos.music.player.code.utils.lrc

import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricRenderCacheTest {
    private val factory = YosLrcFactory()

    @Test
    fun ordinaryLrcKeepsSourceTimestampForLibraryFocusNormalization() {
        val entries = factory.formatLrcEntries(
            "[00:01.000]First line\n[00:03.000]Second line"
        )
        val lines = buildSyncedLyrics(entries, emptyList()).lines

        assertEquals(1000, lines[0].start)
        assertEquals(1000, lines[0].end)
        assertEquals(3000, lines[1].start)
        assertEquals(3000, lines[1].end)
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
}
