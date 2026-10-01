package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.code.utils.player.CrossfadePolicy.CrossfadeDecision
import yos.music.player.code.utils.player.CrossfadePolicy.PREFETCH_LEAD_MS
import yos.music.player.code.utils.player.CrossfadePolicy.WINDOW_MIN_MS

class CrossfadePolicyTest {
    private val fiveMinutes = 5 * 60_000L

    private fun windowOf(durationMs: Long, wantSec: Int): Long =
        CrossfadePolicy.windowMs(durationMs, wantSec) ?: -1L

    @Test
    fun windowKeepsUserDurationForNormalSongs() {
        assertEquals(5_000L, windowOf(fiveMinutes, 5))
        assertEquals(10_000L, windowOf(fiveMinutes, 10))
    }

    @Test
    fun windowShrinksInsteadOfFadingAwayShortSongs() {
        assertEquals(10_000L, windowOf(30_000L, 20))
        assertEquals(1_500L, windowOf(4_500L, 5))
        assertNull(CrossfadePolicy.windowMs(2_000L, 5))
    }

    @Test
    fun windowRejectsNonsenseDurations() {
        assertNull(CrossfadePolicy.windowMs(fiveMinutes, 0))
        assertNull(CrossfadePolicy.windowMs(fiveMinutes, -3))
        assertNull(CrossfadePolicy.windowMs(0L, 5))
        assertNull(CrossfadePolicy.windowMs(-1L, 5))
    }

    @Test
    fun everyUiOptionWorksForANormalSong() {
        CrossfadePolicy.DurationOptionsSec.forEach { seconds ->
            assertEquals(seconds * 1_000L, windowOf(fiveMinutes, seconds))
            assertTrue(windowOf(fiveMinutes, seconds) >= WINDOW_MIN_MS)
        }
        assertTrue(CrossfadePolicy.DurationOptionsSec.contains(CrossfadePolicy.DEFAULT_DURATION_SEC))
    }

    @Test
    fun armOpensOnlyWithinConfiguredWindowAndLeadTime() {
        val window = 5_000L
        assertTrue(CrossfadePolicy.shouldArm(window + PREFETCH_LEAD_MS, window))
        assertTrue(CrossfadePolicy.shouldArm(window, window))
        assertFalse(CrossfadePolicy.shouldArm(window + PREFETCH_LEAD_MS + 1L, window))
        assertFalse(CrossfadePolicy.shouldArm(0L, window))
        assertFalse(CrossfadePolicy.shouldArm(-1L, window))
    }

    @Test
    fun nextTrackAlwaysStartsAtZero() {
        assertEquals(0L, CrossfadePolicy.NEXT_TRACK_START_MS)
    }

    @Test
    fun notRenderingDoesNotMutePrimaryBeforeLeadTimeout() {
        assertEquals(
            CrossfadeDecision.Wait,
            CrossfadePolicy.crossfade(
                secondaryRendering = false,
                remainingWallMs = 4_000L,
                elapsedWallMs = 1_000L,
            )
        )
    }

    @Test
    fun renderingStartsCrossfadeWithoutAnyTailRealignment() {
        assertEquals(
            CrossfadeDecision.Start,
            CrossfadePolicy.crossfade(
                secondaryRendering = true,
                remainingWallMs = 4_000L,
                elapsedWallMs = 1_000L,
            )
        )
    }

    @Test
    fun silentSecondaryEventuallyFallsBackToHardCut() {
        assertEquals(
            CrossfadeDecision.AbortNotRendering,
            CrossfadePolicy.crossfade(
                secondaryRendering = false,
                remainingWallMs = 3_000L,
                elapsedWallMs = PREFETCH_LEAD_MS,
            )
        )
        assertEquals(
            CrossfadeDecision.AbortTooLate,
            CrossfadePolicy.crossfade(
                secondaryRendering = true,
                remainingWallMs = 0L,
                elapsedWallMs = PREFETCH_LEAD_MS,
            )
        )
    }

    @Test
    fun rampNeverShorterThanMinimumOverlap() {
        assertEquals(300L, CrossfadePolicy.rampMs(120L))
        assertEquals(1_800L, CrossfadePolicy.rampMs(1_800L))
    }

    @Test
    fun curvesHaveExactEndpointsAndMonotonicGains() {
        assertEquals(0f, CrossfadePolicy.volumeIn(0f), 1e-6f)
        assertEquals(1f, CrossfadePolicy.volumeIn(1f), 1e-6f)
        assertEquals(1f, CrossfadePolicy.volumeOut(1f, 0f), 1e-6f)
        assertEquals(0f, CrossfadePolicy.volumeOut(1f, 1f), 1e-6f)
        assertEquals(0.4f, CrossfadePolicy.volumeOut(0.4f, 0f), 1e-6f)

        var previousOut = 1f
        var previousIn = 0f
        var t = 0f
        while (t <= 1.000001f) {
            assertTrue(CrossfadePolicy.isEqualPower(t))
            val out = CrossfadePolicy.volumeOut(1f, t)
            val input = CrossfadePolicy.volumeIn(t)
            assertTrue(out <= previousOut + 1e-6f)
            assertTrue(input >= previousIn - 1e-6f)
            previousOut = out
            previousIn = input
            t += 0.01f
        }
    }

    @Test
    fun equalPowerCurveAvoidsMiddleVolumeDip() {
        assertTrue(CrossfadePolicy.volumeIn(0.5f) > 0.65f)
    }
}
