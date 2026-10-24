package yos.music.player.ui.pages

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class TabletLandscapeCoverSizingTest {
    @Test
    fun keepsTargetCoverSizeWhenRemainingSlotIsLargeEnough() {
        assertEquals(
            444f,
            tabletLandscapeFittedCoverSize(444.dp, 452.dp).value,
            0.001f
        )
    }

    @Test
    fun fitsCoverIntoHeightRemainingAfterTitleAndControls() {
        assertEquals(
            252f,
            tabletLandscapeFittedCoverSize(354.dp, 260.dp).value,
            0.001f
        )
    }

    @Test
    fun collapsesCoverBeforeConsumingControlHeight() {
        assertEquals(
            0f,
            tabletLandscapeFittedCoverSize(300.dp, 6.dp).value,
            0.001f
        )
    }
}
