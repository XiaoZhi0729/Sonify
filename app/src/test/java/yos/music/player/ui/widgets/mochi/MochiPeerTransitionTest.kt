package yos.music.player.ui.widgets.mochi

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MochiPeerTransitionTest {

    @Test
    fun `scaleForCamera is 1 at endpoints and reaches minScale beyond shrinkDistance`() {
        val minScale = 0.92f
        val shrinkDist = 0.35f

        assertEquals(1f, MochiPeerTransitionMath.scaleForCamera(0f, 0f, 1f, minScale, shrinkDist), 0.0001f)
        assertEquals(1f, MochiPeerTransitionMath.scaleForCamera(1f, 0f, 1f, minScale, shrinkDist), 0.0001f)
        assertEquals(minScale, MochiPeerTransitionMath.scaleForCamera(0.5f, 0f, 1f, minScale, shrinkDist), 0.0001f)
        assertEquals(minScale, MochiPeerTransitionMath.scaleForCamera(0.35f, 0f, 1f, minScale, shrinkDist), 0.0001f)

        val mid = MochiPeerTransitionMath.scaleForCamera(0.175f, 0f, 1f, minScale, shrinkDist)
        assertTrue(mid > minScale && mid < 1f)
        assertEquals(0.96f, mid, 0.001f)
    }

    @Test
    fun `retargetOrigin preserves scale continuously when changing target mid-flight`() {
        val minScale = 0.92f
        val shrinkDist = 0.35f
        val camera = 0.22f
        val oldOrigin = 0f
        val oldGoal = 1f

        val scaleBefore = MochiPeerTransitionMath.scaleForCamera(camera, oldOrigin, oldGoal, minScale, shrinkDist)
        val newGoal = -1f
        val newOrigin = MochiPeerTransitionMath.retargetOrigin(camera, oldOrigin, oldGoal, newGoal)
        val scaleAfter = MochiPeerTransitionMath.scaleForCamera(camera, newOrigin, newGoal, minScale, shrinkDist)

        assertEquals(scaleBefore, scaleAfter, 0.0001f)
    }

    @Test
    fun `shouldCommitSwipe handles distance threshold, directional fling, reverse fling, and missing neighbor`() {
        // Past halfway commits
        assertTrue(MochiPeerTransitionMath.shouldCommitSwipe(offset = 0.55f, velocityPagesPerSec = 0f, hasNeighbor = true))
        assertTrue(MochiPeerTransitionMath.shouldCommitSwipe(offset = -0.55f, velocityPagesPerSec = 0f, hasNeighbor = true))

        // Fast fling in drag direction commits before halfway
        assertTrue(MochiPeerTransitionMath.shouldCommitSwipe(offset = 0.15f, velocityPagesPerSec = 2.0f, hasNeighbor = true))
        assertTrue(MochiPeerTransitionMath.shouldCommitSwipe(offset = -0.15f, velocityPagesPerSec = -2.0f, hasNeighbor = true))

        // Fast fling opposite to offset cancels
        assertFalse(MochiPeerTransitionMath.shouldCommitSwipe(offset = 0.25f, velocityPagesPerSec = -2.5f, hasNeighbor = true))

        // Slow sub-threshold drag cancels
        assertFalse(MochiPeerTransitionMath.shouldCommitSwipe(offset = 0.25f, velocityPagesPerSec = 0.5f, hasNeighbor = true))

        // Missing neighbor never commits
        assertFalse(MochiPeerTransitionMath.shouldCommitSwipe(offset = 0.8f, velocityPagesPerSec = 3.0f, hasNeighbor = false))
    }

    @Test
    fun `resolveTargetSlot handles rest drag, mid-flight rapid double-flick, reverse cancel, and bounds`() {
        // At rest near 0, small drag without fling stays at 0
        assertEquals(
            0,
            MochiPeerTransitionMath.resolveTargetSlot(
                camera = 0.2f,
                startAnchorSlot = 0,
                gestureDelta = 0.2f,
                velocityPagesPerSec = 0.3f,
                minSlot = 0,
                maxSlot = 5,
            )
        )

        // At rest near 0, fast upward fling advances to 1
        assertEquals(
            1,
            MochiPeerTransitionMath.resolveTargetSlot(
                camera = 0.15f,
                startAnchorSlot = 0,
                gestureDelta = 0.15f,
                velocityPagesPerSec = 2.0f,
                minSlot = 0,
                maxSlot = 5,
            )
        )

        // Mid-flight: interrupted at camera=0.6 (heading toward startAnchorSlot=1) and flicked forward again -> advances to 2
        assertEquals(
            2,
            MochiPeerTransitionMath.resolveTargetSlot(
                camera = 0.6f,
                startAnchorSlot = 1,
                gestureDelta = 0.12f,
                velocityPagesPerSec = 2.5f,
                minSlot = 0,
                maxSlot = 5,
            )
        )

        // Mid-flight: interrupted at camera=0.6 (startAnchorSlot=1) and flicked backward -> returns to 0
        assertEquals(
            0,
            MochiPeerTransitionMath.resolveTargetSlot(
                camera = 0.6f,
                startAnchorSlot = 1,
                gestureDelta = -0.1f,
                velocityPagesPerSec = -2.2f,
                minSlot = 0,
                maxSlot = 5,
            )
        )

        // Opposite velocity to gesture direction does not trigger fling advance; rounds to nearest slot
        assertEquals(
            1,
            MochiPeerTransitionMath.resolveTargetSlot(
                camera = 0.6f,
                startAnchorSlot = 1,
                gestureDelta = 0.1f,
                velocityPagesPerSec = -2.5f,
                minSlot = 0,
                maxSlot = 5,
            )
        )

        // Clamped to minSlot..maxSlot bounds
        assertEquals(
            5,
            MochiPeerTransitionMath.resolveTargetSlot(
                camera = 5.1f,
                startAnchorSlot = 5,
                gestureDelta = 0.2f,
                velocityPagesPerSec = 3.0f,
                minSlot = 0,
                maxSlot = 5,
            )
        )
        assertEquals(
            0,
            MochiPeerTransitionMath.resolveTargetSlot(
                camera = -0.1f,
                startAnchorSlot = 0,
                gestureDelta = -0.2f,
                velocityPagesPerSec = -3.0f,
                minSlot = 0,
                maxSlot = 5,
            )
        )
    }

    @Test
    fun `MochiSmoothGradient smoothstep and stops match smoothstep endpoints and midpoint`() {
        assertEquals(0f, MochiSmoothGradient.smoothstep(-0.5f), 0.0001f)
        assertEquals(0f, MochiSmoothGradient.smoothstep(0f), 0.0001f)
        assertEquals(0.5f, MochiSmoothGradient.smoothstep(0.5f), 0.0001f)
        assertEquals(1f, MochiSmoothGradient.smoothstep(1f), 0.0001f)
        assertEquals(1f, MochiSmoothGradient.smoothstep(1.5f), 0.0001f)

        val stops = MochiSmoothGradient.stops(Color.Black, Color.Transparent)
        assertEquals(MochiSmoothGradient.Stops + 1, stops.size)
        assertEquals(0f, stops.first().first, 0.0001f)
        assertEquals(1f, stops.first().second.alpha, 0.0001f)
        assertEquals(0.5f, stops[stops.size / 2].first, 0.0001f)
        // Compose Color 内部按 8-bit 通道打包（128/255 ≈ 0.50196）
        assertEquals(0.5f, stops[stops.size / 2].second.alpha, 0.005f)
        assertEquals(1f, stops.last().first, 0.0001f)
        assertEquals(0f, stops.last().second.alpha, 0.0001f)
    }
}
