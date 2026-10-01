package yos.music.player.ui.pages.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class RecentListeningRailLogicTest {
    @Test
    fun prependingSongAtStartRevealsTheNewHead() {
        assertEquals(
            RecentListeningUpdatePlan.RevealHead(oldHeadIndex = 1),
            planRecentListeningUpdate(
                previousKeys = listOf("E", "D", "C", "B", "A"),
                nextKeys = listOf("F", "E", "D", "C", "B", "A"),
                wasAtStart = true
            )
        )
    }

    @Test
    fun firstPrependDoesNotDependOnASecondUpdate() {
        val plan = planRecentListeningUpdate(
            previousKeys = listOf("E", "D", "C", "B", "A"),
            nextKeys = listOf("F", "E", "D", "C", "B", "A"),
            wasAtStart = true
        )

        assertEquals(RecentListeningUpdatePlan.RevealHead(1), plan)
    }

    @Test
    fun updatesWhileBrowsingLaterItemsKeepTheViewport() {
        assertEquals(
            RecentListeningUpdatePlan.KeepViewport,
            planRecentListeningUpdate(
                previousKeys = listOf("E", "D", "C", "B", "A"),
                nextKeys = listOf("F", "E", "D", "C", "B", "A"),
                wasAtStart = false
            )
        )
    }

    @Test
    fun replayingAnExistingSongStillRevealsTheMovedHead() {
        assertEquals(
            RecentListeningUpdatePlan.RevealHead(oldHeadIndex = 1),
            planRecentListeningUpdate(
                previousKeys = listOf("E", "D", "C", "B", "A"),
                nextKeys = listOf("C", "E", "D", "B", "A"),
                wasAtStart = true
            )
        )
    }

    @Test
    fun initialLoadDoesNotAnimate() {
        assertEquals(
            RecentListeningUpdatePlan.KeepViewport,
            planRecentListeningUpdate(
                previousKeys = emptyList(),
                nextKeys = listOf("E", "D", "C"),
                wasAtStart = true
            )
        )
    }
}
