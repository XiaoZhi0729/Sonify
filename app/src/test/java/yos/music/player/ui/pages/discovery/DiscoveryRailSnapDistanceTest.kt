package yos.music.player.ui.pages.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class DiscoveryRailSnapDistanceTest {
    @Test
    fun preservesNaturalTargetForFastForwardFling() {
        assertEquals(15, targetPage(start = 5, suggested = 15, velocity = 12000f))
    }

    @Test
    fun preservesNaturalTargetForFastBackwardFling() {
        assertEquals(5, targetPage(start = 15, suggested = 5, velocity = -12000f))
    }

    @Test
    fun keepsNearbyTargetsForSlowGestures() {
        assertEquals(5, targetPage(start = 5, suggested = 5, velocity = 0f))
        assertEquals(6, targetPage(start = 5, suggested = 6, velocity = 300f))
    }

    @Test
    fun preservesTargetsAtBothEnds() {
        assertEquals(0, targetPage(start = 5, suggested = 0, velocity = -12000f))
        assertEquals(19, targetPage(start = 5, suggested = 19, velocity = 12000f))
    }

    private fun targetPage(start: Int, suggested: Int, velocity: Float): Int =
        DiscoveryRailSnapDistance.calculateTargetPage(
            startPage = start,
            suggestedTargetPage = suggested,
            velocity = velocity,
            pageSize = 500,
            pageSpacing = 0
        )
}
