// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitHorizontalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.horizontalDrag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 滑块配色 */
@Immutable
data class MochiSliderColors(
    val activeTrack: Color,
    val inactiveTrack: Color,
    val thumb: Color,
    val thumbShadow: Color,
    val tick: Color,
)

object MochiSliderDefaults {
    val ThumbWidth: Dp = 38.dp
    val MaxStretch: Dp = 10.dp

    @Composable
    fun colors(
        activeTrack: Color = MaterialTheme.colorScheme.primary,
        inactiveTrack: Color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (isFlamingoInDarkMode()) 0.24f else 0.12f,
        ),
        thumb: Color = Color.White,
        thumbShadow: Color = Color.Black.copy(alpha = 0.22f),
        tick: Color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    ): MochiSliderColors = remember(activeTrack, inactiveTrack, thumb, thumbShadow, tick) {
        MochiSliderColors(activeTrack, inactiveTrack, thumb, thumbShadow, tick)
    }
}

/**
 * 源自 mochi-compose 的 iOS 26 风格滑块（非液态玻璃时使用）：6dp 细轨道 + 38×24 白色胶囊拇指 + 果冻拉伸 & 两端橡皮筋。
 */
@Composable
fun MochiSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    origin: Float? = null,
    onValueChangeFinished: (() -> Unit)? = null,
    colors: MochiSliderColors = MochiSliderDefaults.colors(),
    gel: Float = 0.6f,
    hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val haptics = remember(view, hapticProfile) { MochiHaptics(view, hapticProfile) }
    val span = (valueRange.endInclusive - valueRange.start).takeIf { it > 0f } ?: 1f
    val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
    val state = remember { SliderState(fraction) }
    val currentFraction by rememberUpdatedState(fraction)
    val currentOnChange by rememberUpdatedState(onValueChange)
    val currentOnFinished by rememberUpdatedState(onValueChangeFinished)
    val range by rememberUpdatedState(valueRange)
    val currentSteps by rememberUpdatedState(steps)
    val originFraction = origin?.let { ((it - valueRange.start) / span).coerceIn(0f, 1f) }
    val currentOrigin by rememberUpdatedState(originFraction)
    val glow = rememberMochiGlowState()
    val scope = rememberCoroutineScope()
    val thumbPath = remember { G2PathCache() }
    val inactivePath = remember { G2PathCache() }
    val activePath = remember { G2PathCache() }

    LaunchedEffect(state) { state.run({ currentFraction }) }

    fun snap(f: Float): Float {
        val n = currentSteps
        if (n <= 0) return f
        val k = n + 1
        return (f * k).roundToInt() / k.toFloat()
    }

    with(density) {
        val thumbW = MochiSliderDefaults.ThumbWidth.toPx()
        val thumbH = 24.dp.toPx()
        val trackH = 6.dp.toPx()
        val maxStretch = MochiSliderDefaults.MaxStretch.toPx()
        Box(
            modifier
                .fillMaxWidth()
                .height(44.dp)
                .testTag("mochi_slider")
                .semantics {
                    progressBarRangeInfo = ProgressBarRangeInfo(value, valueRange, steps)
                    if (enabled) {
                        setProgress { v ->
                            currentOnChange(v.coerceIn(range.start, range.endInclusive))
                            true
                        }
                    }
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    val travel = (size.width - thumbW).coerceAtLeast(1f)
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        state.held = true
                        val r = MochiGlowDefaults.radius(Size(thumbW, thumbH), this@pointerInput)
                        scope.launch { glow.press(down.position, r) }
                        var raw = currentFraction
                        var last = snap(raw)
                        val drag = awaitHorizontalTouchSlopOrCancellation(down.id) { c, over ->
                            c.consume()
                            raw += over / travel
                        }
                        if (drag != null) {
                            state.dragging = true
                            fun apply(newRaw: Float) {
                                raw = newRaw
                                val c = raw.coerceIn(0f, 1f)
                                state.over = rubberBand((raw - c) * travel, size.width.toFloat(), maxStretch)
                                val f = snap(c)
                                if (f != last) {
                                    val hitEnd = (f == 0f || f == 1f) && last != f
                                    val o = currentOrigin
                                    val crossOrigin = o != null && (f == o || (f - o) * (last - o) < 0f)
                                    if (currentSteps > 0 || hitEnd || crossOrigin) haptics.tick()
                                    last = f
                                    currentOnChange(range.start + f * (range.endInclusive - range.start))
                                }
                            }
                            apply(raw)
                            glow.moveTo(drag.position)
                            horizontalDrag(drag.id) { c ->
                                apply(raw + (c.position.x - c.previousPosition.x) / travel)
                                glow.moveTo(c.position)
                                c.consume()
                            }
                        }
                        state.held = false
                        state.dragging = false
                        state.over = 0f
                        glow.markReleased()
                        scope.launch { glow.release() }
                        if (drag != null) currentOnFinished?.invoke()
                    }
                }
                .drawBehind {
                    val alpha = if (enabled) 1f else MochiDisabledAlpha
                    val travel = (size.width - thumbW).coerceAtLeast(1f)
                    val cy = size.height / 2f
                    val st = state.stretch
                    val left = thumbW / 2f + if (st < 0f) st else 0f
                    val right = size.width - thumbW / 2f + if (st > 0f) st else 0f
                    val k = state.press
                    val th = trackH * lerp(1f, 1.35f, k)
                    val f = state.pos
                    val tx = left + (right - left) * f.coerceIn(0f, 1f)
                    val trackL = left - thumbW / 2f
                    val trackR = right + thumbW / 2f
                    drawG2Capsule(inactivePath, Rect(trackL, cy - th / 2f, trackR, cy + th / 2f), colors.inactiveTrack, alpha)
                    if (originFraction == null) {
                        drawG2Capsule(activePath, Rect(trackL, cy - th / 2f, tx + th / 2f, cy + th / 2f), colors.activeTrack, alpha)
                    } else {
                        val ox = left + (right - left) * originFraction
                        val l = min(ox, tx) - th / 2f
                        val r = max(ox, tx) + th / 2f
                        drawG2Capsule(activePath, Rect(l, cy - th / 2f, r, cy + th / 2f), colors.activeTrack, alpha)
                        drawRoundRect(
                            colors.tick,
                            Offset(ox - 1.dp.toPx(), cy - th / 2f - 3.dp.toPx()),
                            Size(2.dp.toPx(), th + 6.dp.toPx()),
                            CornerRadius(1.dp.toPx()),
                            alpha = alpha,
                        )
                    }
                    if (steps > 0) {
                        val n = steps + 1
                        for (i in 0..n) {
                            val x = left + (right - left) * i / n
                            drawCircle(colors.tick, 1.5.dp.toPx(), Offset(x, cy), alpha = alpha)
                        }
                    }
                    val s = jellyStretch(state.vel * travel / thumbW) * gel
                    val squash = (abs(st) / maxStretch).coerceIn(0f, 1f) * 0.12f
                    val w = thumbW * lerp(1f, 1.2f, k) * (1f + abs(s)) * (1f - squash)
                    val h = thumbH * lerp(1f, 1.25f, k) * jellyCrossScale(abs(s)) * (1f + squash * 0.6f)
                    val rect = Rect(tx - w / 2f, cy - h / 2f, tx + w / 2f, cy + h / 2f)
                    drawPillThumb(rect, colors.thumb.copy(alpha = colors.thumb.alpha * alpha), colors.thumbShadow, k, glow, thumbPath)
                },
        )
    }
}

@Stable
private class SliderState(initial: Float) {
    var pos by mutableFloatStateOf(initial)
        private set
    var vel by mutableFloatStateOf(0f)
        private set
    var press by mutableFloatStateOf(0f)
        private set
    var stretch by mutableFloatStateOf(0f)
        private set
    var held by mutableStateOf(false)
    var dragging = false
    var over = 0f

    suspend fun run(fraction: () -> Float) {
        val spring = MochiFollowSpring(0.2f, 0.82f)
        val pressSpring = MochiFollowSpring(0.24f, 0.7f)
        val stretchSpring = MochiFollowSpring(0.26f, 0.62f)
        spring.snapTo(Offset(pos, 0f))
        while (true) {
            val t = fraction()
            if (!held && abs(pos - t) < 0.0002f && abs(spring.velocity.x) < 0.005f && press < 0.001f &&
                abs(stretch) < 0.1f && abs(stretchSpring.velocity.x) < 1f
            ) {
                pos = t
                vel = 0f
                press = 0f
                stretch = 0f
                spring.snapTo(Offset(t, 0f))
                pressSpring.snapTo(Offset.Zero)
                stretchSpring.snapTo(Offset.Zero)
                snapshotFlow { held to fraction() }.first { (h, f) -> h || f != pos }
                continue
            }
            val dt = spring.awaitFrameDelta()
            spring.response = if (dragging) 0.08f else 0.2f
            pos = spring.step(Offset(t, 0f), dt).x
            vel = spring.velocity.x
            press = pressSpring.step(Offset(if (held) 1f else 0f, 0f), dt).x.coerceAtLeast(0f)
            stretch = stretchSpring.step(Offset(if (dragging) over else 0f, 0f), dt).x
        }
    }
}
