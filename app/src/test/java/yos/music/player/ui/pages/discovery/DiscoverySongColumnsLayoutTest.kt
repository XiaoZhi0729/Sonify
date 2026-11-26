package yos.music.player.ui.pages.discovery

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoverySongColumnsLayoutTest {

    @Test
    fun phonePortraitUsesSingleColumnWithZeroGap() {
        val layout = calculateDiscoverySongColumnsLayout(containerWidth = 412.dp)
        assertEquals(1, layout.columnsPerViewport)
        assertEquals(353.dp, layout.pageWidth)
        assertEquals(0.dp, layout.columnEndGap)
    }

    @Test
    fun tabletPortraitUsesTwoColumnsWithGap() {
        val layout = calculateDiscoverySongColumnsLayout(containerWidth = 700.dp)
        assertEquals(2, layout.columnsPerViewport)
        assertEquals((700.dp - 20.dp - 39.dp) / 2, layout.pageWidth)
        assertEquals(14.dp, layout.columnEndGap)
    }

    @Test
    fun tabletLandscapeCapsAtThreeColumns() {
        val layout = calculateDiscoverySongColumnsLayout(containerWidth = 1280.dp)
        assertEquals(3, layout.columnsPerViewport)
        assertEquals((1280.dp - 20.dp - 39.dp) / 3, layout.pageWidth)
        assertEquals(14.dp, layout.columnEndGap)
    }

    @Test
    fun narrowSplitScreenClampsPageWidthSafely() {
        val layout = calculateDiscoverySongColumnsLayout(containerWidth = 40.dp)
        assertEquals(1, layout.columnsPerViewport)
        assertEquals(100.dp, layout.pageWidth)
        assertEquals(0.dp, layout.columnEndGap)
    }
}
