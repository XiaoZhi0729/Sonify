package yos.music.player.ui.pages.library

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class FmCoverStackPhysicsTest {

    @Test
    fun `wrapIndex wraps negative and overflowing indices cleanly`() {
        assertEquals(0, FmCoverStackPhysics.wrapIndex(0, 5))
        assertEquals(4, FmCoverStackPhysics.wrapIndex(-1, 5))
        assertEquals(3, FmCoverStackPhysics.wrapIndex(-2, 5))
        assertEquals(0, FmCoverStackPhysics.wrapIndex(-5, 5))
        assertEquals(2, FmCoverStackPhysics.wrapIndex(7, 5))
        assertEquals(0, FmCoverStackPhysics.wrapIndex(3, 0))
    }

    @Test
    fun `nearestEquivalentScroll picks shortest circular distance`() {
        // 当前在 0 附近，目标切到最后一首（index=9, size=10）时应映射到 -1 而非 +9
        assertEquals(-1f, FmCoverStackPhysics.nearestEquivalentScroll(0.1f, 9, 10), 0.001f)
        // 当前在 9 附近，目标切到 0（index=0, size=10）时应映射到 10
        assertEquals(10f, FmCoverStackPhysics.nearestEquivalentScroll(9.2f, 0, 10), 0.001f)
        // 当前在 4，目标切到 5
        assertEquals(5f, FmCoverStackPhysics.nearestEquivalentScroll(4f, 5, 10), 0.001f)
    }

    @Test
    fun `cardTransform is continuous across all integer boundaries`() {
        val boundaries = listOf(-1f, 0f, 1f, 2f, 3f)
        val eps = 0.0005f
        for (b in boundaries) {
            val atB = FmCoverStackPhysics.cardTransform(b, isCompactPhone = false)
            if (b > -1f) {
                val left = FmCoverStackPhysics.cardTransform(b - eps, isCompactPhone = false)
                assertEquals("offsetX discontinuity at $b", atB.offsetXDp, left.offsetXDp, 0.25f)
                assertEquals("offsetY discontinuity at $b", atB.offsetYDp, left.offsetYDp, 0.25f)
                assertEquals("scale discontinuity at $b", atB.scale, left.scale, 0.01f)
                assertEquals("rotationZ discontinuity at $b", atB.rotationZ, left.rotationZ, 0.25f)
                assertEquals("alpha discontinuity at $b", atB.alpha, left.alpha, 0.01f)
            }
            if (b < 3f) {
                val right = FmCoverStackPhysics.cardTransform(b + eps, isCompactPhone = false)
                assertEquals("offsetX discontinuity at $b", atB.offsetXDp, right.offsetXDp, 0.25f)
                assertEquals("offsetY discontinuity at $b", atB.offsetYDp, right.offsetYDp, 0.25f)
                assertEquals("scale discontinuity at $b", atB.scale, right.scale, 0.01f)
                assertEquals("rotationZ discontinuity at $b", atB.rotationZ, right.rotationZ, 0.25f)
                assertEquals("alpha discontinuity at $b", atB.alpha, right.alpha, 0.01f)
            }
        }
    }

    @Test
    fun `cardTransform alpha vanishes at both ends and zIndex decreases with depth`() {
        val exited = FmCoverStackPhysics.cardTransform(-1f, isCompactPhone = false)
        val front = FmCoverStackPhysics.cardTransform(0f, isCompactPhone = false)
        val second = FmCoverStackPhysics.cardTransform(1f, isCompactPhone = false)
        val third = FmCoverStackPhysics.cardTransform(2f, isCompactPhone = false)
        val hiddenTail = FmCoverStackPhysics.cardTransform(3f, isCompactPhone = false)

        assertEquals(0f, exited.alpha, 0.001f)
        assertEquals(1f, front.alpha, 0.001f)
        assertEquals(0f, hiddenTail.alpha, 0.001f)

        assertTrue(exited.zIndex >= front.zIndex)
        assertTrue(front.zIndex > second.zIndex)
        assertTrue(second.zIndex > third.zIndex)
        assertTrue(third.zIndex > hiddenTail.zIndex)
    }

    @Test
    fun `rubberBand is symmetric and bounded by dimension`() {
        val dim = 14f
        val small = FmCoverStackPhysics.rubberBand(5f, dim)
        val large = FmCoverStackPhysics.rubberBand(500f, dim)
        val negLarge = FmCoverStackPhysics.rubberBand(-500f, dim)

        assertTrue(small > 0f && small < 5f)
        assertTrue(large > small && large < dim)
        assertEquals(large, abs(negLarge), 0.0001f)
    }

    @Test
    fun `projectTarget handles slow snapback, slow threshold commit, and fast multi-flick`() {
        // 拖得太少且无速度 -> 弹回原位 0
        assertEquals(
            0,
            FmCoverStackPhysics.projectTarget(
                currentScroll = 0.15f,
                velocityCardsPerSec = 0f,
                gestureNetScroll = 0.15f,
                startScroll = 0f
            )
        )

        // 慢拖过 28% 阈值 -> 切下一首 1
        assertEquals(
            1,
            FmCoverStackPhysics.projectTarget(
                currentScroll = 0.32f,
                velocityCardsPerSec = 0.1f,
                gestureNetScroll = 0.32f,
                startScroll = 0f
            )
        )

        // 向右轻微一甩（负速度） -> 切上一首 -1
        assertEquals(
            -1,
            FmCoverStackPhysics.projectTarget(
                currentScroll = -0.12f,
                velocityCardsPerSec = -18f,
                gestureNetScroll = -0.12f,
                startScroll = 0f
            )
        )

        // 超高速左甩 -> 受 MAX_STEP_PER_FLICK 限制
        val fastTarget = FmCoverStackPhysics.projectTarget(
            currentScroll = 0.4f,
            velocityCardsPerSec = 200f,
            gestureNetScroll = 0.4f,
            startScroll = 0f
        )
        assertEquals(FmCoverStackPhysics.MAX_STEP_PER_FLICK, fastTarget)
    }

    @Test
    fun `pressWeight and focusOverlayProgress are continuous without midpoint jumps`() {
        assertEquals(1f, FmCoverStackPhysics.pressWeight(0f), 0.0001f)
        assertEquals(0.5f, FmCoverStackPhysics.pressWeight(0.5f), 0.0001f)
        assertEquals(0.5f, FmCoverStackPhysics.pressWeight(-0.5f), 0.0001f)
        assertEquals(0f, FmCoverStackPhysics.pressWeight(1f), 0.0001f)
        assertEquals(0f, FmCoverStackPhysics.pressWeight(-1f), 0.0001f)

        val pwLeft = FmCoverStackPhysics.pressWeight(0.499f)
        val pwRight = FmCoverStackPhysics.pressWeight(0.501f)
        assertTrue(abs(pwLeft - pwRight) < 0.01f)

        assertEquals(1f, FmCoverStackPhysics.focusOverlayProgress(0f), 0.0001f)
        assertEquals(0f, FmCoverStackPhysics.focusOverlayProgress(0.5f), 0.0001f)
        assertEquals(0f, FmCoverStackPhysics.focusOverlayProgress(-0.5f), 0.0001f)
        val foLeft = FmCoverStackPhysics.focusOverlayProgress(0.259f)
        val foRight = FmCoverStackPhysics.focusOverlayProgress(0.261f)
        assertTrue(abs(foLeft - foRight) < 0.02f)
    }

    @Test
    fun `cardTransform has smooth offsetY across zero, monotonic scale, and 1-to-1 exit tracking`() {
        // offsetY 在 d=0 处无向上折角（d < 0 与 d > 0 均平滑下沉，斜率接近 0）
        val eps = 0.01f
        val yAtZero = FmCoverStackPhysics.cardTransform(0f, isCompactPhone = false).offsetYDp
        val yNeg = FmCoverStackPhysics.cardTransform(-eps, isCompactPhone = false).offsetYDp
        val yPos = FmCoverStackPhysics.cardTransform(eps, isCompactPhone = false).offsetYDp
        assertTrue(yNeg >= yAtZero - 0.001f)
        assertTrue(yPos > yAtZero)

        // d in 0..3 时 scale 严格单调递减
        var prevScale = Float.MAX_VALUE
        for (i in 0..30) {
            val d = i / 10f
            val s = FmCoverStackPhysics.cardTransform(d, isCompactPhone = false).scale
            assertTrue("scale should monotonically decrease at d=$d", s < prevScale)
            prevScale = s
        }

        // 离开初段 offsetX 斜率严格匹配 dragStepDp（1:1 跟手）
        val tiny = 0.001f
        val x0 = FmCoverStackPhysics.cardTransform(0f, isCompactPhone = false).offsetXDp
        val xExit = FmCoverStackPhysics.cardTransform(-tiny, isCompactPhone = false).offsetXDp
        val slope = (x0 - xExit) / tiny
        assertEquals(FmCoverStackPhysics.dragStepDp(isCompactPhone = false), slope, 0.25f)
    }

    @Test
    fun `dragYWeight is C0 and C1 continuous across -1, 0, and 1`() {
        assertEquals(1f, FmCoverStackPhysics.dragYWeight(0f), 0.0001f)
        assertEquals(0.25f, FmCoverStackPhysics.dragYWeight(0.5f), 0.0001f)
        assertEquals(0.5f, FmCoverStackPhysics.dragYWeight(-0.5f), 0.0001f)
        assertEquals(0f, FmCoverStackPhysics.dragYWeight(-1f), 0.0001f)
        assertEquals(0f, FmCoverStackPhysics.dragYWeight(1f), 0.0001f)

        val leftNearZero = FmCoverStackPhysics.dragYWeight(-0.001f)
        val rightNearZero = FmCoverStackPhysics.dragYWeight(0.001f)
        assertTrue(abs(leftNearZero - rightNearZero) < 0.001f)
        assertTrue(abs(leftNearZero - 1f) < 0.001f)
    }

    @Test
    fun `inverseRubberBand accurately inverts rubberBand for positive and negative values`() {
        val dim = FmCoverStackPhysics.VERTICAL_RUBBER_LIMIT_DP
        for (raw in listOf(-25f, -8f, -1.5f, 0f, 1.5f, 8f, 25f)) {
            val damped = FmCoverStackPhysics.rubberBand(raw, dim)
            val recovered = FmCoverStackPhysics.inverseRubberBand(damped, dim)
            assertEquals("inverse mismatch for raw=$raw", raw, recovered, 0.01f)
        }
    }
}
