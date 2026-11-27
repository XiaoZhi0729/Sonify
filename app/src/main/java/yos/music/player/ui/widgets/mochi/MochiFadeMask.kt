// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * 按 smoothstep 走的渐变：两端的斜率都是 0，起点和终点接得上纯色和透明，
 * 看不到线性渐变在开始、结束处那两条「折线」。
 */
object MochiSmoothGradient {
    /** 拆成多少段色标：16 段时每段的折角已经看不出来 */
    const val Stops: Int = 16

    /** S 曲线：0..1 → 0..1，两端斜率为 0 */
    fun smoothstep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }

    /** 从 [from] 到 [to] 的色标（位置 0..1） */
    fun stops(from: Color, to: Color, count: Int = Stops): Array<Pair<Float, Color>> = Array(count + 1) { i ->
        val t = i / count.toFloat()
        t to lerp(from, to, smoothstep(t))
    }

    /** 竖直方向：[startY] 处是 [from]，[endY] 处是 [to] */
    fun vertical(from: Color, to: Color, startY: Float, endY: Float): Brush =
        Brush.verticalGradient(*stops(from, to), startY = startY, endY = endY)

    /** 水平方向：[startX] 处是 [from]，[endX] 处是 [to] */
    fun horizontal(from: Color, to: Color, startX: Float, endX: Float): Brush =
        Brush.horizontalGradient(*stops(from, to), startX = startX, endX = endX)
}

/**
 * 渐变遮罩每条边的长度（0 = 这条边不遮）。start / end 按阅读方向，RTL 下自动对调。
 */
@Immutable
data class MochiFadeEdges(
    val top: Dp = 0.dp,
    val bottom: Dp = 0.dp,
    val start: Dp = 0.dp,
    val end: Dp = 0.dp,
) {
    companion object {
        /** 上下两边 */
        fun vertical(extent: Dp = MochiFadeMaskDefaults.Extent): MochiFadeEdges =
            MochiFadeEdges(top = extent, bottom = extent)

        /** 左右两边 */
        fun horizontal(extent: Dp = MochiFadeMaskDefaults.Extent): MochiFadeEdges =
            MochiFadeEdges(start = extent, end = extent)

        /** 四边 */
        fun all(extent: Dp = MochiFadeMaskDefaults.Extent): MochiFadeEdges =
            MochiFadeEdges(extent, extent, extent, extent)
    }
}

/** 每条边此刻遮多深（0..1，在绘制阶段读，可以每帧变，不会触发重组） */
fun interface MochiFadeAmount {
    fun amount(edge: MochiFadeEdge): Float
}

/** 哪一条边 */
enum class MochiFadeEdge { Top, Bottom, Start, End }

object MochiFadeMaskDefaults {
    /** 默认渐隐长度 */
    val Extent: Dp = 24.dp

    /** 四边都按满强度遮 */
    val Full: MochiFadeAmount = MochiFadeAmount { 1f }
}

/**
 * 通用的容器内渐变遮罩：把这个区域自己画出来的内容在指定的边上渐隐成透明（不是盖一层底色），
 * 所以放在任何底上（卡片、图片、渐变、毛玻璃）都对，形状、圆角、阴影都不受影响。
 */
fun Modifier.mochiFadeMask(
    edges: MochiFadeEdges = MochiFadeEdges.vertical(),
    amount: MochiFadeAmount = MochiFadeMaskDefaults.Full,
): Modifier = this
    .graphicsLayer {
        (amount as? ScrollFadeAmount)?.density = density
        val active = (edges.top.value > 0f && amount.amount(MochiFadeEdge.Top) > 0f) ||
            (edges.bottom.value > 0f && amount.amount(MochiFadeEdge.Bottom) > 0f) ||
            (edges.start.value > 0f && amount.amount(MochiFadeEdge.Start) > 0f) ||
            (edges.end.value > 0f && amount.amount(MochiFadeEdge.End) > 0f)
        compositingStrategy = if (active) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        (amount as? ScrollFadeAmount)?.density = density
        val rtl = layoutDirection == LayoutDirection.Rtl
        fade(MochiFadeEdge.Top, edges.top.toPx(), amount.amount(MochiFadeEdge.Top))
        fade(MochiFadeEdge.Bottom, edges.bottom.toPx(), amount.amount(MochiFadeEdge.Bottom))
        val startPx = edges.start.toPx()
        val endPx = edges.end.toPx()
        val startAmount = amount.amount(MochiFadeEdge.Start)
        val endAmount = amount.amount(MochiFadeEdge.End)
        fadeSide(left = true, px = if (rtl) endPx else startPx, a = if (rtl) endAmount else startAmount)
        fadeSide(left = false, px = if (rtl) startPx else endPx, a = if (rtl) startAmount else endAmount)
    }

/**
 * 可滚动区域的渐变遮罩：还能往那边滚，那一边就渐隐；滚到头，那一边就是实的。
 */
fun Modifier.mochiScrollFadeMask(
    state: ScrollState,
    extent: Dp = MochiFadeMaskDefaults.Extent,
    vertical: Boolean = true,
): Modifier = mochiFadeMask(
    if (vertical) MochiFadeEdges.vertical(extent) else MochiFadeEdges.horizontal(extent),
    ScrollFadeAmount(extent.value, vertical, { state.value.toFloat() }, { (state.maxValue - state.value).toFloat() }),
)

/** [androidx.compose.foundation.lazy.LazyColumn] / [androidx.compose.foundation.lazy.LazyRow] 的渐变遮罩 */
fun Modifier.mochiScrollFadeMask(
    state: LazyListState,
    extent: Dp = MochiFadeMaskDefaults.Extent,
    vertical: Boolean = true,
): Modifier = mochiFadeMask(
    if (vertical) MochiFadeEdges.vertical(extent) else MochiFadeEdges.horizontal(extent),
    ScrollFadeAmount(
        extent.value,
        vertical,
        { if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat() },
        {
            lazyRemaining(
                state.layoutInfo.visibleItemsInfo.lastOrNull()?.let {
                    it.index to (it.offset + it.size)
                },
                state.layoutInfo.totalItemsCount,
                state.layoutInfo.viewportEndOffset,
            )
        },
    ),
)

/** [androidx.compose.foundation.lazy.grid.LazyVerticalGrid] 的渐变遮罩（上下两边） */
fun Modifier.mochiScrollFadeMask(
    state: LazyGridState,
    extent: Dp = MochiFadeMaskDefaults.Extent,
): Modifier = mochiFadeMask(
    MochiFadeEdges.vertical(extent),
    ScrollFadeAmount(
        extent.value,
        true,
        { if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat() },
        {
            val last = state.layoutInfo.visibleItemsInfo.lastOrNull()
            lazyRemaining(
                last?.let { it.index to (it.offset.y + it.size.height) },
                state.layoutInfo.totalItemsCount,
                state.layoutInfo.viewportEndOffset,
            )
        },
    ),
)

private fun lazyRemaining(last: Pair<Int, Int>?, total: Int, viewportEnd: Int): Float {
    if (last == null) return 0f
    if (last.first < total - 1) return Float.MAX_VALUE
    return (last.second - viewportEnd).coerceAtLeast(0).toFloat()
}

private class ScrollFadeAmount(
    private val extentDp: Float,
    private val vertical: Boolean,
    private val before: () -> Float,
    private val after: () -> Float,
) : MochiFadeAmount {
    var density = 1f

    override fun amount(edge: MochiFadeEdge): Float {
        val ramp = (extentDp * density * RampFraction).coerceAtLeast(1f)
        fun of(px: Float) = MochiSmoothGradient.smoothstep(px / ramp)
        return when (edge) {
            MochiFadeEdge.Top -> if (vertical) of(before()) else 0f
            MochiFadeEdge.Bottom -> if (vertical) of(after()) else 0f
            MochiFadeEdge.Start -> if (!vertical) of(before()) else 0f
            MochiFadeEdge.End -> if (!vertical) of(after()) else 0f
        }
    }
}

private fun DrawScope.fade(edge: MochiFadeEdge, px: Float, a: Float) {
    if (px <= 0f || a <= 0f) return
    val e = px.coerceAtMost(size.height / 2f)
    if (edge == MochiFadeEdge.Top) {
        drawRect(
            eased(a, vertical = true, from = 0f, to = e, solidFirst = true),
            Offset.Zero,
            Size(size.width, e),
            blendMode = BlendMode.DstOut,
        )
    } else {
        drawRect(
            eased(a, vertical = true, from = size.height - e, to = size.height, solidFirst = false),
            Offset(0f, size.height - e),
            Size(size.width, e),
            blendMode = BlendMode.DstOut,
        )
    }
}

private fun DrawScope.fadeSide(left: Boolean, px: Float, a: Float) {
    if (px <= 0f || a <= 0f) return
    val e = px.coerceAtMost(size.width / 2f)
    if (left) {
        drawRect(
            eased(a, vertical = false, from = 0f, to = e, solidFirst = true),
            Offset.Zero,
            Size(e, size.height),
            blendMode = BlendMode.DstOut,
        )
    } else {
        drawRect(
            eased(a, vertical = false, from = size.width - e, to = size.width, solidFirst = false),
            Offset(size.width - e, 0f),
            Size(e, size.height),
            blendMode = BlendMode.DstOut,
        )
    }
}

/**
 * 擦除用的渐变（DstOut：越黑擦得越多）。按 smoothstep 取色标，两端平滑衔接。
 */
private fun eased(a: Float, vertical: Boolean, from: Float, to: Float, solidFirst: Boolean): Brush {
    val stops = Array(Stops + 1) { i ->
        val t = i / Stops.toFloat()
        val k = 1f - MochiSmoothGradient.smoothstep(t)
        val pos = if (solidFirst) t else 1f - t
        pos to Color.Black.copy(alpha = a * k)
    }.sortedBy { it.first }.toTypedArray()
    return if (vertical) {
        Brush.verticalGradient(*stops, startY = from, endY = to)
    } else {
        Brush.horizontalGradient(*stops, startX = from, endX = to)
    }
}

private const val Stops = MochiSmoothGradient.Stops
private const val RampFraction = 0.25f
