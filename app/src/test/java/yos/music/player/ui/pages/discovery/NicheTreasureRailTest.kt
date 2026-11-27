package yos.music.player.ui.pages.discovery

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class NicheTreasureRailTest {

    @Test
    fun formatMusicStoryHeaderZeroPadsOneBasedIndex() {
        assertEquals("MUSIC\nSTORY001", formatMusicStoryHeader(0))
        assertEquals("MUSIC\nSTORY005", formatMusicStoryHeader(4))
        assertEquals("MUSIC\nSTORY012", formatMusicStoryHeader(11))
    }

    @Test
    fun calculateMusicStoryCardWidthClampsToReadableRange() {
        assertEquals(296.dp, calculateMusicStoryCardWidth(340.dp))
        assertEquals(318.dp, calculateMusicStoryCardWidth(390.dp))
        assertEquals(344.dp, calculateMusicStoryCardWidth(800.dp))
    }

    @Test
    fun calculateStartAlignedRailEndPaddingLeavesRoomToLeftAlignLastCard() {
        assertEquals(150.dp, calculateStartAlignedRailEndPadding(390.dp, 220.dp))
        assertEquals(52.dp, calculateStartAlignedRailEndPadding(390.dp, 318.dp))
        assertEquals(20.dp, calculateStartAlignedRailEndPadding(200.dp, 220.dp))
    }
}
