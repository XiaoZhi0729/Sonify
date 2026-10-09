package yos.music.player

import org.junit.Assert.*
import org.junit.Test

/**
 * 播放壳圆角时间轴（MainActivity.shellRadiusPx / isShellSettledExpanded）。
 *
 * 期望行为（用户定版）：展开动画**全程**从迷你条胶囊半径长到"屏幕圆角"，
 * 动画结束落位之后才切直角（真全屏）。因此这里钉四条：
 *  1. 行程端点：progress=0 是胶囊半径，progress=1 是屏幕圆角（不是 0）；
 *  2. 行程内单调增大，中途不回切、不跳变；
 *  3. 动画结束（拖拽交还 + 运动 Job 清空）后为 0；
 *  4. 运动 Job 卡住时由端点几何兜底切 0 —— 这正是"全屏四角仍留圆角"的回归现场；
 *     而手指仍按在壳上（拖拽中）时不得提前切直角。
 */
class ShellCornerRadiusCurveTest {
    private val pill = 70f        // 迷你条胶囊半径（px）
    private val corner = 156f     // 屏幕圆角（px，本机 48dp）
    private val anchor = 2300f    // 展开锚点（px）

    private fun radius(
        progress: Float,
        dragActive: Boolean = false,
        motionJobActive: Boolean = true,
        offsetPx: Float = progress * anchor,
        anchorPx: Float = anchor
    ) = shellRadiusPx(
        progress = progress,
        collapsedRadiusPx = pill,
        screenCornerPx = corner,
        dragActive = dragActive,
        motionJobActive = motionJobActive,
        offsetPx = offsetPx,
        anchorPx = anchorPx
    )

    @Test fun travelGoesFromPillToScreenCornerNotToZero() {
        assertEquals(pill, radius(0f), 0.001f)
        // 动画途中（Job 存活、还没贴到端点）：progress=1 也仍是屏幕圆角
        assertEquals(corner, radius(1f, offsetPx = anchor - 40f), 0.001f)
    }

    @Test fun travelIsMonotonicallyRounder() {
        var previous = radius(0f)
        var p = 0f
        while (p <= 1f) {
            val next = radius(p, offsetPx = p * anchor - 40f)
            assertTrue("radius shrank at progress=$p: $previous -> $next", next >= previous - 0.001f)
            assertTrue("radius above screen corner at progress=$p: $next", next <= corner + 0.001f)
            previous = next
            p += 0.005f
        }
    }

    @Test fun squareOnlyAfterTheAnimationEnds() {
        assertEquals(0f, radius(1f, motionJobActive = false, offsetPx = anchor), 0.001f)
    }

    @Test fun hungMotionJobStillTurnsSquareOncePinnedAtEndpoint() {
        // 落位 Job 尾部的 withFrameNanos 停止出帧时永不返回：Job 仍非 null，
        // 但 offsetY 已精确贴在端点 —— 必须判为落位，否则全屏永久留圆角。
        assertEquals(0f, radius(1f, motionJobActive = true, offsetPx = anchor), 0.001f)
        assertEquals(0f, radius(1f, motionJobActive = true, offsetPx = anchor - 0.5f), 0.001f)
        // 还在途中（离端点 1px 以上）则保持圆角，不提前切
        assertTrue(radius(1f, motionJobActive = true, offsetPx = anchor - 8f) > 0f)
    }

    @Test fun heldAtTopDuringDragKeepsScreenCorner() {
        assertEquals(corner, radius(1f, dragActive = true, motionJobActive = false, offsetPx = anchor), 0.001f)
    }

    @Test fun collapsedRestKeepsPillRadius() {
        assertEquals(pill, radius(0f, motionJobActive = false, offsetPx = 0f), 0.001f)
    }

    @Test fun zeroAnchorIsNotTreatedAsSettled() {
        // 几何未就绪（anchor==0）时不能靠兜底判落位，否则首帧就直角
        assertTrue(radius(1f, motionJobActive = true, offsetPx = 0f, anchorPx = 0f) > 0f)
    }

    @Test fun settledPredicateIgnoresTheLowerHalfOfTheTravel() {
        assertFalse(isShellSettledExpanded(0.4f, false, false, 0.4f * anchor, anchor))
        assertTrue(isShellSettledExpanded(0.6f, false, false, 0.6f * anchor, anchor))
        assertFalse(isShellSettledExpanded(1f, true, false, anchor, anchor))
    }
}
