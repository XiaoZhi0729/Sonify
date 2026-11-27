// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 页面指示器的几何。[position] 是带小数的当前页（翻到一半 = 2.5）。
 * 页数多于 [maxVisible] 时只露出一个窗口，窗口跟着 position 平滑移动；窗口两端（不是真正首末页）的点缩小。
 */
internal object PageControlGeometry {
    /** 第 i 个点的宽度：离当前页越近越接近 [activeWidth]，翻页时宽度在相邻两点之间连续转移 */
    fun dotWidth(i: Int, position: Float, dot: Float, activeWidth: Float): Float {
        val t = (1f - abs(i - position)).coerceIn(0f, 1f)
        return dot + (activeWidth - dot) * t
    }

    /** 窗口起点（第几个点，带小数） */
    fun windowStart(position: Float, count: Int, maxVisible: Int): Float {
        if (count <= maxVisible) return 0f
        return (position - (maxVisible - 1) / 2f).coerceIn(0f, (count - maxVisible).toFloat())
    }

    /** 第 i 个点的缩放：窗口内 1，窗口边上的缩到 [EdgeScale]，窗口外 0（真正的首末页不缩） */
    fun dotScale(i: Int, position: Float, count: Int, maxVisible: Int): Float {
        if (count <= maxVisible) return 1f
        val start = windowStart(position, count, maxVisible)
        val rel = i - start
        val edge = minOf(rel, (maxVisible - 1) - rel)
        val atRealEnd = (i == 0 && rel <= 0.5f) || (i == count - 1 && (maxVisible - 1) - rel <= 0.5f)
        return when {
            edge >= 1f -> 1f
            edge >= 0f -> if (atRealEnd) 1f else EdgeScale + (1f - EdgeScale) * edge
            edge > -1f -> EdgeScale * (1f + edge)
            else -> 0f
        }
    }

    /** 拖动选页：手指的横坐标（相对指示器中心，px）换成第几页 */
    fun indexAt(xFromCenter: Float, position: Float, count: Int, maxVisible: Int, pitch: Float): Int {
        if (count <= 0 || pitch <= 0f) return 0
        val visible = minOf(count, maxVisible)
        val start = windowStart(position, count, maxVisible)
        val rel = xFromCenter / pitch + (visible - 1) / 2f
        return floor(start + rel + 0.5f).toInt().coerceIn(0, count - 1)
    }

    const val EdgeScale = 0.55f
}

/** 页面指示器的颜色 */
@Immutable
data class MochiPageControlColors(
    val active: Color,
    val inactive: Color,
    /** 按住拖动时出现的底衬 */
    val track: Color,
)

/** 页面指示器的默认值 */
object MochiPageControlDefaults {
    /** 点的直径 */
    val DotSize: Dp = 8.dp

    /** 当前页拉长后的宽度 */
    val ActiveWidth: Dp = 20.dp

    /** 相邻两点中心的距离 */
    val Pitch: Dp = 18.dp

    /** 整条的高度（也是按住拖动的热区高度） */
    val Height: Dp = 28.dp

    /** 最多露出几个点 */
    const val MaxVisible: Int = 7

    @Composable
    fun colors(
        active: Color = MaterialTheme.colorScheme.onSurface,
        inactive: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f),
        track: Color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (isFlamingoInDarkMode()) 0.14f else 0.08f
        ),
    ): MochiPageControlColors = remember(active, inactive, track) {
        MochiPageControlColors(active, inactive, track)
    }
}

/**
 * 源自 mochi-compose 的页面指示器（iOS 的 UIPageControl）：
 * - 当前页的点拉长成 G2 平滑胶囊，翻页时长度在相邻两点之间连续转移（跟着 [position] 走，不跳）
 * - 点左半边 / 右半边 = 上一页 / 下一页
 * - 按住后左右拖动快速翻页（底衬出现、整条轻微放大），每换一页轻震一下
 * - 页数超过 [maxVisible] 时只露出一段，两端的点缩小
 */
@Composable
fun MochiPageControl(
    pageCount: Int,
    position: () -> Float,
    onPageSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    maxVisible: Int = MochiPageControlDefaults.MaxVisible,
    dotSize: Dp = MochiPageControlDefaults.DotSize,
    activeWidth: Dp = MochiPageControlDefaults.ActiveWidth,
    pitch: Dp = MochiPageControlDefaults.Pitch,
    height: Dp = MochiPageControlDefaults.Height,
    colors: MochiPageControlColors = MochiPageControlDefaults.colors(),
) {
    val view = LocalView.current
    val haptics = remember(view) { MochiHaptics(view, MochiHapticProfile.IosLike) }
    val select by rememberUpdatedState(onPageSelect)
    val scrub = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val visible = minOf(pageCount, maxVisible).coerceAtLeast(1)
    val scratch = remember { Path() }
    val width = pitch * visible + 16.dp
    Canvas(
        modifier
            .width(width)
            .height(height)
            .semantics { contentDescription = "第 ${position().toInt() + 1} 页，共 $pageCount 页" }
            .graphicsLayer {
                val s = 1f + 0.08f * scrub.value
                scaleX = s
                scaleY = s
            }
            .pointerInput(pageCount, maxVisible, pitch) {
                val pitchPx = pitch.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    var up: Boolean? = null
                    withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis.coerceAtMost(300L)) {
                        while (up == null) {
                            val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                            if (c == null || !c.pressed) {
                                up = true
                            } else if ((c.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                up = false
                            }
                        }
                    }
                    if (up == true) {
                        val cur = position().roundToInt()
                        val next = if (down.position.x < size.width / 2f) cur - 1 else cur + 1
                        if (next in 0 until pageCount) {
                            haptics.tick()
                            select(next)
                        }
                        return@awaitEachGesture
                    }
                    if (up == false) return@awaitEachGesture
                    scope.launch { scrub.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 600f)) }
                    haptics.confirm()
                    var last = position().roundToInt()
                    while (true) {
                        val e = awaitPointerEvent()
                        val c = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!c.pressed) break
                        c.consume()
                        val i = PageControlGeometry.indexAt(
                            c.position.x - size.width / 2f,
                            position(),
                            pageCount,
                            maxVisible,
                            pitchPx,
                        )
                        if (i != last) {
                            last = i
                            haptics.tick()
                            select(i)
                        }
                    }
                    scope.launch { scrub.animateTo(0f, spring(dampingRatio = 1f, stiffness = 600f)) }
                }
            },
    ) {
        if (pageCount <= 0) return@Canvas
        val pos = position().coerceIn(0f, (pageCount - 1).coerceAtLeast(0).toFloat())
        val dot = dotSize.toPx()
        val active = activeWidth.toPx()
        val pitchPx = pitch.toPx()
        val sc = scrub.value
        if (sc > 0f) {
            drawG2RoundRect(
                scratch,
                Rect(Offset.Zero, size),
                size.height / 2f,
                colors.track,
                alpha = sc.coerceIn(0f, 1f),
            )
        }
        val start = PageControlGeometry.windowStart(pos, pageCount, maxVisible)
        val cx = size.width / 2f
        val cy = size.height / 2f
        val first = (start - 1f).toInt().coerceAtLeast(0)
        val last = (start + visible + 1f).toInt().coerceAtMost(pageCount - 1)
        for (i in first..last) {
            val s = PageControlGeometry.dotScale(i, pos, pageCount, maxVisible)
            if (s <= 0f) continue
            val w = PageControlGeometry.dotWidth(i, pos, dot, active) * s
            val h = dot * s
            val x = cx + (i - start - (visible - 1) / 2f) * pitchPx
            val t = (1f - abs(i - pos)).coerceIn(0f, 1f)
            val color = lerp(colors.inactive, colors.active, t)
            drawG2RoundRect(scratch, Rect(x - w / 2f, cy - h / 2f, x + w / 2f, cy + h / 2f), h / 2f, color)
        }
    }
}

/** 直接接 [PagerState]：点按 / 拖动选页时让 pager 翻过去 */
@Composable
fun MochiPageControl(
    state: PagerState,
    modifier: Modifier = Modifier,
    maxVisible: Int = MochiPageControlDefaults.MaxVisible,
    dotSize: Dp = MochiPageControlDefaults.DotSize,
    activeWidth: Dp = MochiPageControlDefaults.ActiveWidth,
    pitch: Dp = MochiPageControlDefaults.Pitch,
    height: Dp = MochiPageControlDefaults.Height,
    colors: MochiPageControlColors = MochiPageControlDefaults.colors(),
) {
    val scope = rememberCoroutineScope()
    MochiPageControl(
        pageCount = state.pageCount,
        position = { state.currentPage + state.currentPageOffsetFraction },
        onPageSelect = { scope.launch { coroutineScope { state.animateScrollToPage(it) } } },
        modifier = modifier,
        maxVisible = maxVisible,
        dotSize = dotSize,
        activeWidth = activeWidth,
        pitch = pitch,
        height = height,
        colors = colors,
    )
}
