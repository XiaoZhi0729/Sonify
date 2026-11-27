package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MochiOverscrollSnapFlingTest {

    private class TestFrameClock(private val stepNanos: Long = 16_000_000L) : MonotonicFrameClock {
        private var currentTimeNanos = 0L

        override suspend fun <R> withFrameNanos(onFrame: (frameTimeNanos: Long) -> R): R {
            currentTimeNanos += stepNanos
            return onFrame(currentTimeNanos)
        }
    }

    private class BoundedFakeScrollScope(
        initialOffset: Float,
        private val maxOffset: Float,
    ) : ScrollScope {
        var offset: Float = initialOffset
            private set

        val canScrollForward: Boolean
            get() = offset < maxOffset

        val canScrollBackward: Boolean
            get() = offset > 0f

        override fun scrollBy(pixels: Float): Float {
            val target = (offset + pixels).coerceIn(0f, maxOffset)
            val consumed = target - offset
            offset = target
            return consumed
        }
    }

    @Test
    fun cannotScrollInDirectionMatchesBoundaryFlags() {
        assertTrue(cannotScrollInDirection(canScrollForward = false, canScrollBackward = true, velocity = 800f))
        assertFalse(cannotScrollInDirection(canScrollForward = true, canScrollBackward = true, velocity = 800f))
        assertTrue(cannotScrollInDirection(canScrollForward = true, canScrollBackward = false, velocity = -800f))
        assertFalse(cannotScrollInDirection(canScrollForward = true, canScrollBackward = true, velocity = -800f))
        assertFalse(cannotScrollInDirection(canScrollForward = false, canScrollBackward = false, velocity = 0f))
    }

    @Test
    fun coerceScrollValueToTargetClampsSignedTargets() {
        assertEquals(0f, coerceScrollValueToTarget(120f, 0f), 0.001f)
        assertEquals(200f, coerceScrollValueToTarget(260f, 200f), 0.001f)
        assertEquals(150f, coerceScrollValueToTarget(150f, 200f), 0.001f)
        assertEquals(-200f, coerceScrollValueToTarget(-260f, -200f), 0.001f)
        assertEquals(-150f, coerceScrollValueToTarget(-150f, -200f), 0.001f)
    }

    @Test
    fun calculateLazyListBoundaryDistancePxHandlesStartEndOffscreenAndEmpty() {
        assertNull(
            calculateLazyListBoundaryDistancePx(
                velocity = 500f,
                totalItemsCount = 0,
                viewportEndOffset = 1000,
                afterContentPadding = 100,
                firstItemOffset = null,
                lastItemOffset = null,
                lastItemSize = null,
            ),
        )

        // Start edge: item 0 scrolled 240px past start
        assertEquals(
            240f,
            calculateLazyListBoundaryDistancePx(
                velocity = -1200f,
                totalItemsCount = 10,
                viewportEndOffset = 1000,
                afterContentPadding = 200,
                firstItemOffset = -240,
                lastItemOffset = null,
                lastItemSize = null,
            )!!,
            0.001f,
        )

        // End edge with start-aligned afterContentPadding:
        // lastItemOffset + lastItemSize + afterContentPadding - viewportEndOffset = (750 + 600 + 350) - 1000 = 700
        assertEquals(
            700f,
            calculateLazyListBoundaryDistancePx(
                velocity = 1500f,
                totalItemsCount = 10,
                viewportEndOffset = 1000,
                afterContentPadding = 350,
                firstItemOffset = null,
                lastItemOffset = 750,
                lastItemSize = 600,
            )!!,
            0.001f,
        )

        // Boundary item not visible yet -> null
        assertNull(
            calculateLazyListBoundaryDistancePx(
                velocity = 1500f,
                totalItemsCount = 10,
                viewportEndOffset = 1000,
                afterContentPadding = 350,
                firstItemOffset = null,
                lastItemOffset = null,
                lastItemSize = null,
            ),
        )
    }

    @Test
    fun calculatePagerBoundaryDistancePxHandlesFullAndMultiCardPagers() {
        // Full-width style: 5 pages, pageSize=900, spacing=0, padding=40+40, viewport=980
        // totalContent = 5*900 + 40 + 40 = 4580, maxScroll = 3600
        assertEquals(
            0f,
            calculatePagerBoundaryDistancePx(
                velocity = -900f,
                pageCount = 5,
                pageSize = 900,
                pageSpacing = 0,
                beforeContentPadding = 40,
                afterContentPadding = 40,
                viewportMainAxisSize = 980,
                visiblePageIndex = 0,
                visiblePageOffset = 0,
            )!!,
            0.001f,
        )
        assertEquals(
            3600f,
            calculatePagerBoundaryDistancePx(
                velocity = 900f,
                pageCount = 5,
                pageSize = 900,
                pageSpacing = 0,
                beforeContentPadding = 40,
                afterContentPadding = 40,
                viewportMainAxisSize = 980,
                visiblePageIndex = 0,
                visiblePageOffset = 0,
            )!!,
            0.001f,
        )

        // Multi-card fixed pageSize: 10 pages, pageSize=320, spacing=0, padding=40+40, viewport=1080
        // maxScroll = 10*320 + 80 - 1080 = 2200.
        // At page 4 with offset -120 -> currentScroll = 4*320 - (-120) = 1400 -> forward dist = 800, backward dist = 1400
        assertEquals(
            800f,
            calculatePagerBoundaryDistancePx(
                velocity = 1000f,
                pageCount = 10,
                pageSize = 320,
                pageSpacing = 0,
                beforeContentPadding = 40,
                afterContentPadding = 40,
                viewportMainAxisSize = 1080,
                visiblePageIndex = 4,
                visiblePageOffset = -120,
            )!!,
            0.001f,
        )
        assertEquals(
            1400f,
            calculatePagerBoundaryDistancePx(
                velocity = -1000f,
                pageCount = 10,
                pageSize = 320,
                pageSpacing = 0,
                beforeContentPadding = 40,
                afterContentPadding = 40,
                viewportMainAxisSize = 1080,
                visiblePageIndex = 4,
                visiblePageOffset = -120,
            )!!,
            0.001f,
        )
    }

    @Test
    fun shouldDecayIntoBoundaryChecksAbsoluteDecayAgainstDistance() {
        assertTrue(shouldDecayIntoBoundary(decayOffset = 1500f, distanceToBoundaryPx = 900f))
        assertTrue(shouldDecayIntoBoundary(decayOffset = -1500f, distanceToBoundaryPx = 900f))
        assertFalse(shouldDecayIntoBoundary(decayOffset = 700f, distanceToBoundaryPx = 900f))
        assertFalse(shouldDecayIntoBoundary(decayOffset = 900f, distanceToBoundaryPx = 900f))
        assertFalse(shouldDecayIntoBoundary(decayOffset = 1500f, distanceToBoundaryPx = null))
    }

    @Test
    fun performMochiBoundaryDecayReturnsLeftoverVelocityOnEndAndStartImpact() = runBlocking {
        withContext(TestFrameClock()) {
            val forwardScope = BoundedFakeScrollScope(initialOffset = 500f, maxOffset = 1000f)
            val (hitEnd, endVelocityLeft) = forwardScope.performMochiBoundaryDecay(
                initialVelocity = 6000f,
                decayAnimationSpec = exponentialDecay(),
                canScrollForward = { forwardScope.canScrollForward },
                canScrollBackward = { forwardScope.canScrollBackward },
            )
            assertTrue(hitEnd)
            assertEquals(1000f, forwardScope.offset, 0.5f)
            assertTrue("Expected positive leftover velocity on end impact, got $endVelocityLeft", endVelocityLeft > 500f)

            val backwardScope = BoundedFakeScrollScope(initialOffset = 500f, maxOffset = 1000f)
            val (hitStart, startVelocityLeft) = backwardScope.performMochiBoundaryDecay(
                initialVelocity = -6000f,
                decayAnimationSpec = exponentialDecay(),
                canScrollForward = { backwardScope.canScrollForward },
                canScrollBackward = { backwardScope.canScrollBackward },
            )
            assertTrue(hitStart)
            assertEquals(0f, backwardScope.offset, 0.5f)
            assertTrue("Expected negative leftover velocity on start impact, got $startVelocityLeft", startVelocityLeft < -500f)
        }
    }
}
