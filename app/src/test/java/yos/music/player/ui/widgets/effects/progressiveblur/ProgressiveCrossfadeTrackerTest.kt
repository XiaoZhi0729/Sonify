package yos.music.player.ui.widgets.effects.progressiveblur

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressiveCrossfadeTrackerTest {

    @Test
    fun coldStartNeverSnapshotsAndRendersAtFullMix() {
        val tracker = ProgressiveCrossfadeTracker()

        val first = tracker.advance(epoch = 0L, rawProgress = 0f, geometryChanged = false)
        assertFalse(first.shouldSnapshotCurrentBase)
        assertFalse(first.isBlending)
        assertEquals(1f, first.effectiveMix, 0f)

        tracker.onBaseRendered()
        val steady = tracker.advance(epoch = 0L, rawProgress = 1f, geometryChanged = false)
        assertFalse(steady.shouldSnapshotCurrentBase)
        assertFalse(steady.isBlending)
        assertEquals(1f, steady.effectiveMix, 0f)
    }

    @Test
    fun standardTrackSwitchSnapshotsOnceAndInterpolatesToCompletion() {
        val tracker = ProgressiveCrossfadeTracker()
        tracker.advance(epoch = 0L, rawProgress = 1f, geometryChanged = false)
        tracker.onBaseRendered()

        val start = tracker.advance(epoch = 1L, rawProgress = 0f, geometryChanged = false)
        assertTrue(start.shouldSnapshotCurrentBase)
        assertTrue(start.isBlending)
        assertEquals(0f, start.effectiveMix, 0f)

        val mid = tracker.advance(epoch = 1L, rawProgress = 0.5f, geometryChanged = false)
        assertFalse(mid.shouldSnapshotCurrentBase)
        assertTrue(mid.isBlending)
        assertEquals(0.5f, mid.effectiveMix, 1e-6f)

        val end = tracker.advance(epoch = 1L, rawProgress = 1f, geometryChanged = false)
        assertFalse(end.shouldSnapshotCurrentBase)
        assertFalse(end.isBlending)
        assertEquals(1f, end.effectiveMix, 0f)

        val afterEnd = tracker.advance(epoch = 1L, rawProgress = 1f, geometryChanged = false)
        assertFalse(afterEnd.shouldSnapshotCurrentBase)
        assertFalse(afterEnd.isBlending)
        assertEquals(1f, afterEnd.effectiveMix, 0f)
    }

    @Test
    fun rapidSkipHoldsMonotonicProgressBeforeNewEpochThenSnapshotsOnNewEpoch() {
        val tracker = ProgressiveCrossfadeTracker()
        tracker.advance(epoch = 0L, rawProgress = 1f, geometryChanged = false)
        tracker.onBaseRendered()

        val epoch1Start = tracker.advance(epoch = 1L, rawProgress = 0f, geometryChanged = false)
        assertTrue(epoch1Start.shouldSnapshotCurrentBase)
        assertTrue(epoch1Start.isBlending)

        val epoch1Mid = tracker.advance(epoch = 1L, rawProgress = 0.45f, geometryChanged = false)
        assertEquals(0.45f, epoch1Mid.effectiveMix, 1e-6f)

        // Simulate 1-frame snapTo(0f) race arriving before epoch increments to 2.
        val raceFrame = tracker.advance(epoch = 1L, rawProgress = 0f, geometryChanged = false)
        assertFalse(raceFrame.shouldSnapshotCurrentBase)
        assertTrue(raceFrame.isBlending)
        assertEquals(0.45f, raceFrame.effectiveMix, 1e-6f)

        val epoch2Start = tracker.advance(epoch = 2L, rawProgress = 0f, geometryChanged = false)
        assertTrue(epoch2Start.shouldSnapshotCurrentBase)
        assertTrue(epoch2Start.isBlending)
        assertEquals(0f, epoch2Start.effectiveMix, 0f)

        val epoch2End = tracker.advance(epoch = 2L, rawProgress = 1f, geometryChanged = false)
        assertFalse(epoch2End.isBlending)
        assertEquals(1f, epoch2End.effectiveMix, 0f)
    }

    @Test
    fun alreadyCompletedProgressSkipsSnapshotAndBlending() {
        val tracker = ProgressiveCrossfadeTracker()
        tracker.advance(epoch = 0L, rawProgress = 1f, geometryChanged = false)
        tracker.onBaseRendered()

        val step = tracker.advance(epoch = 1L, rawProgress = 1f, geometryChanged = false)
        assertFalse(step.shouldSnapshotCurrentBase)
        assertFalse(step.isBlending)
        assertEquals(1f, step.effectiveMix, 0f)
    }

    @Test
    fun geometryChangeMidCrossfadeCancelsOutgoingSnapshot() {
        val tracker = ProgressiveCrossfadeTracker()
        tracker.advance(epoch = 0L, rawProgress = 1f, geometryChanged = false)
        tracker.onBaseRendered()

        val start = tracker.advance(epoch = 1L, rawProgress = 0.2f, geometryChanged = false)
        assertTrue(start.shouldSnapshotCurrentBase)
        assertTrue(start.isBlending)

        val resized = tracker.advance(epoch = 1L, rawProgress = 0.5f, geometryChanged = true)
        assertFalse(resized.shouldSnapshotCurrentBase)
        assertFalse(resized.isBlending)
        assertEquals(1f, resized.effectiveMix, 0f)

        // Next frame in the same epoch remains non-blending rather than resurrecting the stale snapshot.
        val next = tracker.advance(epoch = 1L, rawProgress = 0.7f, geometryChanged = false)
        assertFalse(next.isBlending)
        assertEquals(1f, next.effectiveMix, 0f)
    }

    @Test
    fun surfaceResetPreventsSnapshottingBeforeNextBaseFrame() {
        val tracker = ProgressiveCrossfadeTracker()
        tracker.advance(epoch = 0L, rawProgress = 1f, geometryChanged = false)
        tracker.onBaseRendered()

        tracker.onSurfaceReset()

        val afterReset = tracker.advance(epoch = 1L, rawProgress = 0f, geometryChanged = false)
        assertFalse(afterReset.shouldSnapshotCurrentBase)
        assertFalse(afterReset.isBlending)
        assertEquals(1f, afterReset.effectiveMix, 0f)
    }
}
