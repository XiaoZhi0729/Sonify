package yos.music.player.ui.widgets.basic

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveImageLayoutTest {
    @Test
    fun keepsPhoneCardsAtLeastMinimumSize() {
        val layout = calculateAdaptiveImageLayout(
            containerWidth = 411.dp,
            startPadding = 20.dp,
            endPadding = 20.dp
        )

        assertEquals(2, layout.itemCount)
        assertTrue(layout.itemSize >= DiscoveryMinImageSize)
    }

    @Test
    fun addsCardsWhenTabletHasMoreRoom() {
        val layout = calculateAdaptiveImageLayout(
            containerWidth = 1280.dp,
            startPadding = 20.dp,
            endPadding = 20.dp
        )

        assertEquals(7, layout.itemCount)
        assertTrue(layout.itemSize >= DiscoveryMinImageSize)
    }

    @Test
    fun usesOneMinimumCardForNarrowWindows() {
        val layout = calculateAdaptiveImageLayout(
            containerWidth = 80.dp,
            startPadding = 20.dp,
            endPadding = 20.dp
        )

        assertEquals(1, layout.itemCount)
        assertEquals(DiscoveryMinImageSize, layout.itemSize)
    }

    @Test
    fun exactMinimumWidthDoesNotCreateAnExtraColumn() {
        val layout = calculateAdaptiveImageLayout(
            containerWidth = DiscoveryMinImageSize + 40.dp,
            startPadding = 20.dp,
            endPadding = 20.dp
        )

        assertEquals(1, layout.itemCount)
        assertEquals(DiscoveryMinImageSize, layout.itemSize)
    }
}
