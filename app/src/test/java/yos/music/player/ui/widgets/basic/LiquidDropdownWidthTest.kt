package yos.music.player.ui.widgets.basic

import androidx.compose.ui.unit.Constraints
import org.junit.Assert.assertEquals
import org.junit.Test

class LiquidDropdownWidthTest {
    @Test
    fun capsListWidthWhenAvailableWidthIsNarrowerThanContent() {
        val result = resolveDropdownIntrinsicListWidth(
            maxIntrinsicWidth = 720,
            minPx = 600,
            maxPx = 864,
            availableWidthPx = 648,
        )
        assertEquals(648, result)
    }

    @Test
    fun preservesMinMaxClampingWhenWidthIsUnboundedOrWiderThanMax() {
        assertEquals(
            720,
            resolveDropdownIntrinsicListWidth(
                maxIntrinsicWidth = 720,
                minPx = 600,
                maxPx = 864,
                availableWidthPx = Constraints.Infinity,
            ),
        )
        assertEquals(
            600,
            resolveDropdownIntrinsicListWidth(
                maxIntrinsicWidth = 500,
                minPx = 600,
                maxPx = 864,
                availableWidthPx = 1080,
            ),
        )
        assertEquals(
            864,
            resolveDropdownIntrinsicListWidth(
                maxIntrinsicWidth = 1000,
                minPx = 600,
                maxPx = 864,
                availableWidthPx = 1080,
            ),
        )
    }

    @Test
    fun clampsBelowMinPxWhenAvailableWidthIsVeryNarrowWithoutThrowing() {
        val result = resolveDropdownIntrinsicListWidth(
            maxIntrinsicWidth = 720,
            minPx = 600,
            maxPx = 864,
            availableWidthPx = 480,
        )
        assertEquals(480, result)
    }

    @Test
    fun fallsBackToMinMaxClampingWhenAvailableWidthIsNonPositive() {
        assertEquals(
            720,
            resolveDropdownIntrinsicListWidth(
                maxIntrinsicWidth = 720,
                minPx = 600,
                maxPx = 864,
                availableWidthPx = 0,
            ),
        )
        assertEquals(
            600,
            resolveDropdownIntrinsicListWidth(
                maxIntrinsicWidth = 400,
                minPx = 600,
                maxPx = 864,
                availableWidthPx = -1,
            ),
        )
    }
}
