package yos.music.player.ui.pages

/** Geometry shared by queue drag rendering, auto-scroll and final reordering. */
internal data class QueueDragGeometryState(
    val fromIndex: Int,
    val originTopPx: Float,
    val fingerOffsetPx: Float = 0f,
    val consumedScrollPx: Float = 0f
) {
    val logicalOffsetPx: Float
        get() = fingerOffsetPx + consumedScrollPx

    val untransformedTopPx: Float
        get() = originTopPx - consumedScrollPx

    val draggedTopPx: Float
        get() = untransformedTopPx + logicalOffsetPx

    fun afterFingerMove(deltaPx: Float) = copy(
        fingerOffsetPx = fingerOffsetPx + deltaPx
    )

    fun afterScroll(consumedPx: Float) = copy(
        consumedScrollPx = consumedScrollPx + consumedPx
    )
}

internal object QueueDragMath {
    fun hasCrossedTouchSlop(fingerOffsetPx: Float, touchSlopPx: Float): Boolean =
        fingerOffsetPx.isFinite() && kotlin.math.abs(fingerOffsetPx) > touchSlopPx

    fun targetIndex(
        fromIndex: Int,
        logicalOffsetPx: Float,
        itemHeightPx: Int,
        itemCount: Int
    ): Int? {
        if (itemCount <= 0 || itemHeightPx <= 0 || !logicalOffsetPx.isFinite()) return null
        val delta = roundHalfAwayFromZero(logicalOffsetPx / itemHeightPx.toFloat())
        return (fromIndex + delta).coerceIn(0, itemCount - 1)
    }

    fun edgeScrollDelta(
        draggedCenterPx: Float,
        viewportStartPx: Float,
        viewportEndPx: Float,
        edgeThresholdPx: Float,
        maxDeltaPx: Float,
        canScrollBackward: Boolean,
        canScrollForward: Boolean
    ): Float {
        val maxDelta = maxDeltaPx.coerceAtLeast(0f)
        if (!draggedCenterPx.isFinite() || !viewportStartPx.isFinite() ||
            !viewportEndPx.isFinite() || edgeThresholdPx <= 0f || maxDelta == 0f ||
            viewportEndPx <= viewportStartPx
        ) return 0f

        val topEdge = viewportStartPx + edgeThresholdPx
        val bottomEdge = viewportEndPx - edgeThresholdPx
        return when {
            canScrollBackward && draggedCenterPx < topEdge -> {
                -(((topEdge - draggedCenterPx) / edgeThresholdPx) * maxDelta)
                    .coerceIn(0f, maxDelta)
            }
            canScrollForward && draggedCenterPx > bottomEdge -> {
                (((draggedCenterPx - bottomEdge) / edgeThresholdPx) * maxDelta)
                    .coerceIn(0f, maxDelta)
            }
            else -> 0f
        }
    }

    fun clampDraggedTop(
        draggedTopPx: Float,
        itemHeightPx: Int,
        viewportStartPx: Int,
        viewportEndPx: Int
    ): Float {
        if (!draggedTopPx.isFinite() || itemHeightPx <= 0 || viewportEndPx <= viewportStartPx) {
            return draggedTopPx
        }
        val maxTop = (viewportEndPx - itemHeightPx).coerceAtLeast(viewportStartPx)
        return draggedTopPx.coerceIn(viewportStartPx.toFloat(), maxTop.toFloat())
    }

    /**
     * 落点/让位使用的位移：以浮层实际显示位置（钳制在可视区内）相对源行当前槽位计算，
     * 手指伸出可视区外的部分不计入目标行数。边缘自动滚动仍按手指位置触发。
     */
    fun displayedDisplacementPx(
        draggedTopPx: Float,
        untransformedTopPx: Float,
        itemHeightPx: Int,
        viewportStartPx: Int,
        viewportEndPx: Int
    ): Float {
        if (itemHeightPx <= 0 || viewportEndPx <= viewportStartPx ||
            !draggedTopPx.isFinite() || !untransformedTopPx.isFinite()
        ) return 0f
        val displayedTop = clampDraggedTop(draggedTopPx, itemHeightPx, viewportStartPx, viewportEndPx)
        return displayedTop - untransformedTopPx
    }

    private fun roundHalfAwayFromZero(value: Float): Int =
        if (value >= 0f) kotlin.math.floor(value + 0.5f).toInt()
        else kotlin.math.ceil(value - 0.5f).toInt()
}
