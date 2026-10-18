package yos.music.player.ui.pages.discovery

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeDragMathTest {

    @Test
    fun targetIndexUsesVariableHeights() {
        val heights = listOf(100, 200, 100)
        val tops = listOf(0f, 100f, 300f)
        val centers = tops.mapIndexed { i, t -> i to (t + heights[i] / 2f) } // 50, 200, 350
        assertEquals(0, HomeDragMath.targetIndex(centers, 50f, 2))
        assertEquals(1, HomeDragMath.targetIndex(centers, 200f, 2))
        assertEquals(2, HomeDragMath.targetIndex(centers, 320f, 2))
    }

    @Test
    fun targetIndexSingleOrEmptyIsZero() {
        assertEquals(0, HomeDragMath.targetIndex(listOf(0 to 50f), 999f, 0))
        assertEquals(0, HomeDragMath.targetIndex(emptyList(), 10f, 3))
    }

    @Test
    fun scrollWhileFingerFixedAdvancesTarget() {
        // 手指不动、列表自滚一帧后落点索引应变化正确
        val heights = listOf(100, 200, 100)
        var tops = listOf(0f, 100f, 300f)
        fun centers() = tops.mapIndexed { i, t -> i to (t + heights[i] / 2f) }
        val draggedCenter = 200f
        assertEquals(1, HomeDragMath.targetIndex(centers(), draggedCenter, 2))
        tops = tops.map { it - 100f } // 上滚一帧
        assertEquals(2, HomeDragMath.targetIndex(centers(), draggedCenter, 2))
        tops = tops.map { it - 100f } // 再上滚一帧
        assertEquals(2, HomeDragMath.targetIndex(centers(), draggedCenter, 2))
    }

    @Test
    fun displacementShiftsBetweenRange() {
        // target 在下方：区间 (from, target] 向上让一格
        assertEquals(-120f, HomeDragMath.displacementFor(2, 1, 3, 120), 0.001f)
        assertEquals(-120f, HomeDragMath.displacementFor(3, 1, 3, 120), 0.001f)
        assertEquals(0f, HomeDragMath.displacementFor(1, 1, 3, 120), 0.001f)
        assertEquals(0f, HomeDragMath.displacementFor(4, 1, 3, 120), 0.001f)
        // target 在上方：区间 [target, from) 向下让一格
        assertEquals(120f, HomeDragMath.displacementFor(1, 3, 1, 120), 0.001f)
        assertEquals(120f, HomeDragMath.displacementFor(2, 3, 1, 120), 0.001f)
        assertEquals(0f, HomeDragMath.displacementFor(3, 3, 1, 120), 0.001f)
    }

    @Test
    fun rubberBandDampsBeyondBounds() {
        assertEquals(5f, HomeDragMath.rubberBand(5f, 0f, 10f), 0.001f)
        assertEquals(-3f, HomeDragMath.rubberBand(-10f, 0f, 10f, 0.3f), 0.001f)
        assertEquals(13f, HomeDragMath.rubberBand(20f, 0f, 10f, 0.3f), 0.001f)
    }

    @Test
    fun draggedTranslationKeepsUnderFingerWhileScrolling() {
        // 位移 = fingerOffset + consumedScroll（列表自滚时被拖块始终跟随手指）
        val g0 = HomeDragGeometry(
            fromIndex = 2,
            originTopPx = 300f,
            draggedHeightPx = 100,
            fingerOffsetPx = 40f,
            consumedScrollPx = 0f
        )
        assertEquals(
            40f,
            HomeDragMath.draggedTranslationY(g0.draggedTopPx, g0.untransformedTopPx, 100, 1000),
            0.001f
        )
        val g1 = g0.afterScroll(120f)
        assertEquals(
            160f,
            HomeDragMath.draggedTranslationY(g1.draggedTopPx, g1.untransformedTopPx, 100, 1000),
            0.001f
        )
    }

    @Test
    fun hasCrossedTouchSlop() {
        assertEquals(false, HomeDragMath.hasCrossedTouchSlop(5f, 8f))
        assertEquals(true, HomeDragMath.hasCrossedTouchSlop(-9f, 8f))
        assertEquals(false, HomeDragMath.hasCrossedTouchSlop(Float.NaN, 8f))
    }
}
