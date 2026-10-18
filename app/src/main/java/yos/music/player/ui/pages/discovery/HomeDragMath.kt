package yos.music.player.ui.pages.discovery

import yos.music.player.ui.pages.QueueDragMath

/**
 * 主页编辑态拖拽几何（变高板块版）。
 *
 * 与队列编辑（QueueDragMath）同源思路：冻结几何 + 边缘自动滚动 + 让位位移。
 * 差别：① 队列行等高（单一 itemHeightPx），主页板块高度差异大 → 落点按每个可见项自身的
 * offset/size 计算；② 坐标统一采用「屏幕坐标（视口顶 = 0）」，由 [draggedTranslationY]
 * 换算成相对槽位的 translationY，避免内容坐标与视口坐标混用。
 */
internal data class HomeDragGeometry(
    val fromIndex: Int,
    /** 被拖块原位槽顶（屏幕坐标，视口顶 = 0）。 */
    val originTopPx: Float,
    /** 被拖块高度（px）：让位位移量 = 该值。 */
    val draggedHeightPx: Int,
    val fingerOffsetPx: Float = 0f,
    val consumedScrollPx: Float = 0f
) {
    /** 被拖块期望屏幕顶边 = 原位 + 手指位移（列表自滚时手指不动 → 该值不变）。 */
    val draggedTopPx: Float get() = originTopPx + fingerOffsetPx

    /** 当前槽位屏幕顶边（列表自滚 consumed 后槽位上移）。 */
    val untransformedTopPx: Float get() = originTopPx - consumedScrollPx

    fun afterFingerMove(deltaPx: Float) = copy(fingerOffsetPx = fingerOffsetPx + deltaPx)
    fun afterScroll(consumedPx: Float) = copy(consumedScrollPx = consumedScrollPx + consumedPx)
}

internal object HomeDragMath {

    fun hasCrossedTouchSlop(fingerOffsetPx: Float, touchSlopPx: Float): Boolean =
        fingerOffsetPx.isFinite() && kotlin.math.abs(fingerOffsetPx) > touchSlopPx

    /**
     * 变高落点：[itemCentersPx] 为可见板块的 (列表下标, 屏幕中心 y) 对，按列表下标升序。
     * 被拖块中心高于某槽位中心 → 目标至少在该槽位之后。返回目标下标（0..[maxIndex]）。
     */
    fun targetIndex(
        itemCentersPx: List<Pair<Int, Float>>,
        draggedCenterPx: Float,
        maxIndex: Int
    ): Int {
        if (itemCentersPx.isEmpty()) return 0
        var target = itemCentersPx.first().first
        for ((idx, center) in itemCentersPx) {
            if (draggedCenterPx.isFinite() && draggedCenterPx > center) target = idx + 1 else break
        }
        return target.coerceIn(0, maxIndex.coerceAtLeast(0))
    }

    /**
     * 非被拖项的让位位移（px）：目标在下方则区间 (from, target] 向上让一格；反之区间 [target, from) 向下让一格。
     * 让位量 = 被拖块高度（抽出式：原槽位被被拖块自身占据，其余项错开一格）。
     */
    fun displacementFor(
        index: Int,
        fromIndex: Int,
        targetIndex: Int,
        draggedHeightPx: Int
    ): Float = when {
        targetIndex > fromIndex && index > fromIndex && index <= targetIndex ->
            -draggedHeightPx.toFloat()
        targetIndex < fromIndex && index >= targetIndex && index < fromIndex ->
            draggedHeightPx.toFloat()
        else -> 0f
    }

    /** 越界阻尼（rubber-band）：超出 [min,max] 的部分按 [factor] 衰减，产生"拖不动"的阻力感。 */
    fun rubberBand(value: Float, min: Float, max: Float, factor: Float = 0.3f): Float = when {
        value < min -> min - (min - value) * factor
        value > max -> max + (value - max) * factor
        else -> value
    }

    /**
     * 被拖块相对其槽位的 translationY：期望屏幕顶边 [draggedTopPx]（阻尼后）减去当前槽位屏幕顶边。
     * 列表自滚时 [consumedScrollPx] 已并入 [untransformedTopPx]，故被拖块视觉上始终跟随手指。
     */
    fun draggedTranslationY(
        draggedTopPx: Float,
        untransformedTopPx: Float,
        draggedHeightPx: Int,
        viewportHeightPx: Int
    ): Float {
        if (draggedHeightPx <= 0 || viewportHeightPx <= 0 ||
            !draggedTopPx.isFinite() || !untransformedTopPx.isFinite()
        ) return 0f
        val maxTop = (viewportHeightPx - draggedHeightPx).toFloat().coerceAtLeast(0f)
        val damped = rubberBand(draggedTopPx, 0f, maxTop)
        return damped - untransformedTopPx
    }

    /** 边缘自动滚动：直接复用队列实现（与行高无关）。 */
    fun edgeScrollDelta(
        draggedCenterPx: Float,
        viewportStartPx: Float,
        viewportEndPx: Float,
        edgeThresholdPx: Float,
        maxDeltaPx: Float,
        canScrollBackward: Boolean,
        canScrollForward: Boolean
    ): Float = QueueDragMath.edgeScrollDelta(
        draggedCenterPx = draggedCenterPx,
        viewportStartPx = viewportStartPx,
        viewportEndPx = viewportEndPx,
        edgeThresholdPx = edgeThresholdPx,
        maxDeltaPx = maxDeltaPx,
        canScrollBackward = canScrollBackward,
        canScrollForward = canScrollForward
    )
}
