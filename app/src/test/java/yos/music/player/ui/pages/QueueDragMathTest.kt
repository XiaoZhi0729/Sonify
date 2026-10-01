package yos.music.player.ui.pages

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueDragMathTest {
    @Test
    fun scrollAndFingerMovementShareOneLogicalOffset() {
        val initial = QueueDragGeometryState(fromIndex = 2, originTopPx = 600f)
        val state = initial.afterFingerMove(40f).afterScroll(64f)

        assertEquals(104f, state.logicalOffsetPx, 0.001f)
        assertEquals(536f, state.untransformedTopPx, 0.001f)
        assertEquals(640f, state.draggedTopPx, 0.001f)
        assertEquals(4, QueueDragMath.targetIndex(2, state.logicalOffsetPx, 64, 5))
    }

    @Test
    fun listScrollKeepsDraggedRowUnderFinger() {
        val before = QueueDragGeometryState(fromIndex = 2, originTopPx = 700f)
        val after = before.afterScroll(64f)

        assertEquals(before.draggedTopPx, after.draggedTopPx, 0.001f)
    }

    @Test
    fun targetIndexUsesSymmetricHalfRowBoundary() {
        assertEquals(3, QueueDragMath.targetIndex(2, 32f, 64, 5))
        assertEquals(1, QueueDragMath.targetIndex(2, -32f, 64, 5))
        assertEquals(4, QueueDragMath.targetIndex(2, 2.6f * 64f, 64, 5))
        assertEquals(0, QueueDragMath.targetIndex(2, -5f * 64f, 64, 5))
    }

    @Test
    fun targetIndexRejectsEmptyOrInvalidGeometry() {
        assertNull(QueueDragMath.targetIndex(2, 0f, 64, 0))
        assertNull(QueueDragMath.targetIndex(2, 0f, 0, 5))
        assertNull(QueueDragMath.targetIndex(2, Float.NaN, 64, 5))
    }

    @Test
    fun touchSlopPreventsStaticLongPressFromActivatingDrag() {
        assertEquals(false, QueueDragMath.hasCrossedTouchSlop(0f, 8f))
        assertEquals(false, QueueDragMath.hasCrossedTouchSlop(8f, 8f))
        assertEquals(true, QueueDragMath.hasCrossedTouchSlop(8.01f, 8f))
        assertEquals(true, QueueDragMath.hasCrossedTouchSlop(-9f, 8f))
    }

    @Test
    fun edgeScrollUsesDraggedCenterAndStopsAtBounds() {
        assertEquals(
            2.25f,
            QueueDragMath.edgeScrollDelta(732f, 0f, 800f, 80f, 15f, true, true),
            0.001f
        )
        assertEquals(
            -15f,
            QueueDragMath.edgeScrollDelta(0f, 0f, 800f, 80f, 15f, true, true),
            0.001f
        )
        assertEquals(
            0f,
            QueueDragMath.edgeScrollDelta(732f, 0f, 800f, 80f, 15f, true, false),
            0.001f
        )
        assertEquals(
            0f,
            QueueDragMath.edgeScrollDelta(732f, 0f, 800f, 80f, -15f, true, true),
            0.001f
        )
    }

    @Test
    fun draggedTopIsClampedToVisibleViewport() {
        assertEquals(100f, QueueDragMath.clampDraggedTop(20f, 64, 100, 500), 0.001f)
        assertEquals(436f, QueueDragMath.clampDraggedTop(900f, 64, 100, 500), 0.001f)
        assertEquals(220f, QueueDragMath.clampDraggedTop(220f, 64, 100, 500), 0.001f)
    }

    @Test
    fun targetIndexSupportsReverseDirectionAcrossRows() {
        assertEquals(3, QueueDragMath.targetIndex(1, 130f, 64, 4))
        assertEquals(0, QueueDragMath.targetIndex(2, -130f, 64, 4))
    }

    @Test
    fun dropTargetUsesDisplayedPositionWhenFingerLeavesViewport() {
        // 手指在视口底边之下 264px：显示位置被钳在 736（800-64），
        // 源行槽位 600 → 显示位移 136 → 只前进 2 行，而不是按手指算 6 行
        val displacement = QueueDragMath.displayedDisplacementPx(
            draggedTopPx = 1000f,
            untransformedTopPx = 600f,
            itemHeightPx = 64,
            viewportStartPx = 0,
            viewportEndPx = 800
        )
        assertEquals(136f, displacement, 0.001f)
        assertEquals(2, QueueDragMath.targetIndex(0, displacement, 64, 10))

        // 手指在视口内：显示位移与原始逻辑位移一致
        assertEquals(
            40f,
            QueueDragMath.displayedDisplacementPx(640f, 600f, 64, 0, 800),
            0.001f
        )

        // 手指越过顶边：钳到视口顶 100，位移 = 100 - 480 = -380
        assertEquals(
            -380f,
            QueueDragMath.displayedDisplacementPx(-100f, 480f, 64, 100, 800),
            0.001f
        )
    }
}
