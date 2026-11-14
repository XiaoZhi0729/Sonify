// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 分段选择器配色 */
@Immutable
data class MochiSegmentedColors(
    val track: Color,
    val thumb: Color,
    val selectedContent: Color,
    val content: Color,
    val shadow: Color,
)

/** 分段选择器手感与尺寸参数 */
@Immutable
data class MochiSegmentedBehavior(
    val height: Dp = 36.dp,
    val segmentPadding: Dp = 14.dp,
    val gel: Float = 0.6f,
    val trail: Float = 0.55f,
    val pressScale: Float = 1.14f,
    val hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
)

object MochiSegmentedDefaults {
    @Composable
    fun colors(
        track: Color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (isFlamingoInDarkMode()) 0.24f else 0.12f,
        ),
        thumb: Color = if (isFlamingoInDarkMode()) {
            MaterialTheme.colorScheme.surfaceVariant
        } else {
            Color.White
        },
        selectedContent: Color = MaterialTheme.colorScheme.onSurface,
        content: Color = MaterialTheme.colorScheme.onSurface,
        shadow: Color = Color.Black,
    ): MochiSegmentedColors = remember(track, thumb, selectedContent, content, shadow) {
        MochiSegmentedColors(track, thumb, selectedContent, content, shadow)
    }

    fun behavior(
        height: Dp = 36.dp,
        segmentPadding: Dp = 14.dp,
        gel: Float = 0.6f,
        trail: Float = 0.55f,
        pressScale: Float = 1.14f,
        hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
    ): MochiSegmentedBehavior = MochiSegmentedBehavior(
        height = height,
        segmentPadding = segmentPadding,
        gel = gel,
        trail = trail,
        pressScale = pressScale,
        hapticProfile = hapticProfile,
    )
}

/**
 * 源自 mochi-compose 的 iOS 26 风格分段选择器（非液态玻璃场景）。
 */
@Composable
fun MochiSegmentedControl(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    items: List<String>,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: MochiSegmentedColors = MochiSegmentedDefaults.colors(),
    behavior: MochiSegmentedBehavior = MochiSegmentedDefaults.behavior(),
) {
    val n = items.size
    if (n == 0) return
    val view = LocalView.current
    val density = LocalDensity.current
    val haptics = remember(view, behavior.hapticProfile) { MochiHaptics(view, behavior.hapticProfile) }
    val state = remember { TabBarState(selectedIndex.toFloat()) }
    val current by rememberUpdatedState(selectedIndex.coerceIn(0, n - 1))
    val currentOnSelect by rememberUpdatedState(onSelect)
    val glow = rememberMochiGlowState()
    val trackGlow = rememberMochiGlowState()
    val thumbPath = remember { G2PathCache() }
    val trackPath = remember { G2PathCache() }
    val shadowPath = remember { G2PathCache() }
    val lift = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    val dims = remember { SegmentDims() }
    val padPx = with(density) { Pad.toPx() }
    val hPx = with(density) { behavior.height.toPx() }
    val maxShift = with(density) { Offset(4.dp.toPx(), 2.dp.toPx()) }
    val shadowY = with(density) { 1.dp.toPx() }
    val alpha = if (enabled) 1f else MochiDisabledAlpha

    LaunchedEffect(state, n, dims.width, hPx) {
        if (dims.width > 0f) state.run(n, { current }, haptics, Size(dims.width, hPx), maxShift)
    }

    fun slot(x: Float): Rect = Rect(padPx + x * dims.seg, padPx, padPx + (x + 1f) * dims.seg, hPx - padPx)

    fun geometry(): Pair<Rect, Rect> {
        val lv = lift.value
        val sy = 1f + (lv - 1f) * 0.6f
        val ind = if (behavior.trail > 0f) {
            val back = lerp(state.pos, state.tail, behavior.trail)
            val head = slot(state.pos)
            val tail = slot(back)
            val span = abs(state.pos - back)
            val thin = 1f - 0.16f * smoothstep(0f, 1.2f, span) * behavior.gel / 0.6f
            Rect(min(head.left, tail.left), head.top, max(head.right, tail.right), head.bottom)
                .scaleAround(lv, thin.coerceIn(0.7f, 1f) * sy)
                .translate(state.offset)
        } else {
            val s = jellyStretch(state.vel) * behavior.gel
            slot(state.pos).scaleAround((1f + s) * lv, jellyCrossScale(s) * sy).translate(state.offset)
        }
        val base = Rect(0f, 0f, dims.width, hPx).translate(state.offset)
        val track = Rect(min(base.left, ind.left - padPx), base.top, max(base.right, ind.right + padPx), base.bottom)
        return ind to track
    }

    Layout(
        modifier = modifier
            .graphicsLayer { this.alpha = alpha }
            .drawBehind {
                if (dims.width <= 0f) return@drawBehind
                val (ind, track) = geometry()
                val tp = trackPath.get(track, track.height / 2f, 1f)
                drawPath(tp, colors.track)
                drawGlow(trackGlow, Outline.Generic(tp), trackGlow.center, intensityScale = 0.5f)
                val sp = shadowPath.get(ind, ind.height / 2f, 1f)
                translate(top = shadowY) { drawPath(sp, colors.shadow.copy(alpha = 0.10f)) }
                val p = thumbPath.get(ind, ind.height / 2f, 1f)
                drawPath(p, colors.thumb)
                drawGlow(glow, Outline.Generic(p), glow.center)
            }
            .pointerInput(enabled, n) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (dims.seg <= 0f) return@awaitEachGesture
                    fun indexAt(x: Float) = (x - padPx - dims.seg / 2f) / dims.seg
                    val at = indexAt(down.position.x)
                    val onThumb = abs(at - state.pos) < 0.5f
                    val trackR = MochiGlowDefaults.radius(Size(dims.width, hPx), this)
                    scope.launch { trackGlow.press(down.position, trackR) }
                    if (onThumb) {
                        state.finger = at
                        state.drag = Offset.Zero
                        state.held = true
                        scope.launch {
                            glow.press(
                                down.position,
                                MochiGlowDefaults.radius(Size(dims.seg, hPx), this@pointerInput),
                            )
                        }
                        scope.launch {
                            lift.animateTo(behavior.pressScale, MochiMotions.responseSpring(0.3f, 0.6f))
                        }
                    } else {
                        dims.pressed = at.roundToInt().coerceIn(0, n - 1)
                    }
                    var released = false
                    var last = down.position
                    while (true) {
                        val e = awaitPointerEvent()
                        val ch = e.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            released = true
                            break
                        }
                        last = ch.position
                        if (onThumb) {
                            state.finger = indexAt(ch.position.x)
                            state.drag = ch.position - down.position
                            glow.moveTo(ch.position)
                        }
                        trackGlow.moveTo(ch.position)
                        ch.consume()
                    }
                    trackGlow.markReleased()
                    scope.launch { trackGlow.release() }
                    dims.pressed = -1
                    if (onThumb) {
                        val target = state.finger.roundToInt().coerceIn(0, n - 1)
                        state.release(if (released) target else null)
                        glow.markReleased()
                        scope.launch { glow.release() }
                        scope.launch { lift.animateTo(1f, MochiMotions.responseSpring(0.38f, 0.62f)) }
                        if (released && target != current) currentOnSelect(target)
                    } else if (released) {
                        val inside = last.y in -hPx * 0.5f..hPx * 1.5f && last.x in 0f..dims.width
                        val target = indexAt(last.x).roundToInt().coerceIn(0, n - 1)
                        if (inside && target != current) {
                            haptics.tick()
                            currentOnSelect(target)
                        }
                    }
                }
            },
        content = {
            items.forEachIndexed { i, label ->
                Box(
                    Modifier
                        .graphicsLayer {
                            translationX = state.offset.x
                            translationY = state.offset.y
                            this.alpha = if (dims.pressed == i && i != current) 0.55f else 1f
                        }
                        .semantics(mergeDescendants = true) {
                            role = Role.Tab
                            selected = i == selectedIndex
                            onClick {
                                currentOnSelect(i)
                                true
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        label,
                        style = SegmentLabelStyle,
                        color = colors.content,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            this.alpha = 1f - (1f - abs(state.pos - i)).coerceIn(0f, 1f)
                        },
                    )
                    Text(
                        label,
                        style = SegmentLabelStyle.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.selectedContent,
                        maxLines = 1,
                        modifier = Modifier.graphicsLayer {
                            this.alpha = (1f - abs(state.pos - i)).coerceIn(0f, 1f)
                        },
                    )
                }
            }
        },
    ) { measurables, c ->
        val h = hPx.roundToInt()
        val segPad = behavior.segmentPadding.roundToPx()
        val loose = Constraints(maxHeight = h)
        val natural = measurables.maxOf { it.maxIntrinsicWidth(h) } + segPad * 2
        val pad = padPx.roundToInt()
        val w = if (c.hasFixedWidth) {
            c.maxWidth
        } else {
            val want = natural * n + pad * 2
            if (c.hasBoundedWidth) want.coerceIn(c.minWidth, c.maxWidth) else want
        }
        val seg = (w - pad * 2).toFloat() / n
        dims.width = w.toFloat()
        dims.seg = seg
        val ps = measurables.map { it.measure(loose.copy(maxWidth = seg.roundToInt().coerceAtLeast(0))) }
        layout(w, h) {
            ps.forEachIndexed { i, p ->
                val cx = pad + seg * (i + 0.5f)
                p.place((cx - p.width / 2f).roundToInt(), (h - p.height) / 2)
            }
        }
    }
}

private val Pad = 2.dp

private val SegmentLabelStyle = TextStyle(
    textMotion = TextMotion.Animated,
    fontSize = 13.sp,
    lineHeight = 18.sp,
    fontWeight = FontWeight.Normal,
    letterSpacing = 0.sp,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

@Stable
private class SegmentDims {
    var width by mutableFloatStateOf(0f)
    var seg by mutableFloatStateOf(0f)
    var pressed by mutableIntStateOf(-1)
}
