// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.text
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 源自 mochi-compose 的计数器样式数字：每一位是一个 0–9 循环的滚轮，数字变化时带模糊与回弹滚动。
 */
@Composable
fun MochiCounterText(
    value: Long,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    transition: MochiBlurTransition = MochiBlurTransitions.Counter,
    format: (Long) -> String = { it.toString() },
) {
    val last = remember { LongRef(value) }
    val direction = remember(value) {
        val d = abs(value).compareTo(abs(last.v))
        last.v = value
        d
    }
    MochiCounterText(format(value), direction, modifier, color, style, transition)
}

@Composable
fun MochiCounterText(
    text: String,
    direction: Int,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    transition: MochiBlurTransition = MochiBlurTransitions.Counter,
) {
    val digits = remember(style) {
        style.merge(TextStyle(fontFeatureSettings = "tnum", textMotion = style.textMotion ?: TextMotion.Animated))
    }
    val ink = when {
        color != Color.Unspecified -> color
        digits.color != Color.Unspecified -> digits.color
        else -> LocalContentColor.current
    }
    val glyphs = rememberDigitGlyphs(digits)
    val slots = remember { IntRef(0) }
    slots.v = maxOf(slots.v, text.length)
    val first = remember { BoolRef(true) }
    SideEffect { first.v = false }
    Row(modifier.clearAndSetSemantics { this.text = AnnotatedString(text) }, verticalAlignment = Alignment.CenterVertically) {
        for (i in slots.v - 1 downTo 0) {
            val ch = text.getOrNull(text.length - 1 - i)
            val isDigit = ch == null || ch in '0'..'9'
            key(i) {
                MochiBlurCrossfade(if (isDigit) WheelSlot else ch, transition = transition, direction = direction) { c ->
                    if (c === WheelSlot) {
                        CounterWheel(if (isDigit) ch?.minus('0') else null, direction, born = !first.v, glyphs, ink)
                    } else {
                        Text(c.toString(), color = ink, style = digits, maxLines = 1)
                    }
                }
            }
        }
    }
}

private object WheelSlot
private class LongRef(var v: Long)
private class IntRef(var v: Int)
private class BoolRef(var v: Boolean)

private val WheelRadiansPerCell: Float = (PI / 4).toFloat()
private val WheelRadiusCells: Float = 1f / WheelRadiansPerCell
private val HalfPi: Float = (PI / 2).toFloat()
private const val WheelPitchPerInk = 0.8f
private const val WheelZoomAmount = 0.4f
private const val WheelSinkAmount = 0.5f
private const val WheelFadeStartCells = 0.5f
private const val WheelFadeEndCells = 0.85f
private const val CenterLiftCells = 0.5f
private val VisibleTurnRadians: Float = asin((WheelFadeEndCells / WheelRadiusCells).coerceAtMost(1f))
private val TurnSides = intArrayOf(-1, 1)
private val CenterTurn = intArrayOf(0)
private const val MotionBlurVelocity = 12f
private const val SingleStepPeakVelocity = 7f
private val MaxBlurFraction: Float = smoothstep(0f, MotionBlurVelocity, SingleStepPeakVelocity)
private const val ZoomFullVelocity = 30f
private const val MotionFade = 0.45f
private const val RetireShift = 2f
private val DigitSlideSpring = spring<Float>(dampingRatio = 1f, stiffness = 1200f, visibilityThreshold = 0.01f)

internal class DigitGlyphs(
    val layouts: Array<TextLayoutResult>,
    val width: Int,
    val height: Int,
    val inkCenter: Float,
    val inkHalf: Float,
    val pitch: Float,
)

@Composable
internal fun rememberDigitGlyphs(style: TextStyle): DigitGlyphs {
    val measurer = rememberTextMeasurer(cacheSize = 0)
    return remember(style, measurer) {
        val layouts = Array(10) { measurer.measure(it.toString(), style, maxLines = 1) }
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        for (l in layouts) {
            val b = l.getPathForRange(0, 1).getBounds()
            if (b.height > 0f) {
                top = minOf(top, b.top)
                bottom = maxOf(bottom, b.bottom)
            }
        }
        val height = layouts[0].size.height
        if (top > bottom) {
            top = height * 0.25f
            bottom = height * 0.75f
        }
        DigitGlyphs(
            layouts = layouts,
            width = layouts.maxOf { it.size.width },
            height = height,
            inkCenter = (top + bottom) / 2f,
            inkHalf = (bottom - top) / 2f,
            pitch = (bottom - top) * WheelPitchPerInk,
        )
    }
}

private class WheelTrack(var digit: Int) {
    var index = 0

    fun push(d: Int?, direction: Int): Int {
        if (d == null || d == digit) return index
        index += when {
            direction > 0 -> (d - digit + 10) % 10
            direction < 0 -> -((digit - d + 10) % 10)
            else -> ((d - digit + 15) % 10) - 5
        }
        digit = d
        return index
    }

    fun digitAt(turn: Int): Int = ((digit + turn - index) % 10 + 10) % 10
}

@Composable
internal fun CounterWheel(digit: Int?, direction: Int, born: Boolean, glyphs: DigitGlyphs, color: Color) {
    val track = remember { WheelTrack(digit ?: 0) }
    val target = track.push(digit, direction)
    val absent = digit == null
    val roll = remember { Animatable(target.toFloat()) }
    val shift = remember { Animatable(if (born || absent) 1f else 0f) }
    val presence = remember { Animatable(if (born || absent) 0f else 1f) }
    val approachDir = remember { mutableIntStateOf(1) }
    val approachTarget = remember { mutableIntStateOf(target) }
    SideEffect {
        if (approachTarget.intValue != target) {
            approachTarget.intValue = target
            approachDir.intValue = if (target - roll.value >= 0f) 1 else -1
        }
    }
    val retiring by rememberUpdatedState(absent)
    val hold = rememberMotionHold(
        position = { roll.value + shift.value },
        speed = { abs(roll.velocity) + abs(shift.velocity) },
    )

    LaunchedEffectTarget(target.toFloat()) { t ->
        if (abs(shift.value) > WheelFadeEndCells) roll.snapTo(t)
        if ((t - roll.value) * roll.velocity < 0f) hold.freeze(abs(roll.velocity) + abs(shift.velocity))
        roll.animateTo(t, retargetSpring(t - roll.value, roll.velocity))
    }
    LaunchedEffectTarget(if (absent) RetireShift else 0f) { t ->
        if ((t - shift.value) * shift.velocity < 0f) hold.freeze(abs(roll.velocity) + abs(shift.velocity))
        shift.animateTo(t, retargetSpring(t - shift.value, shift.velocity))
    }
    LaunchedEffectTarget(if (absent) 0f else 1f) { presence.animateTo(it, DigitSlideSpring) }

    val density = LocalDensity.current
    val w = glyphs.width
    val h = glyphs.height
    val pitch = glyphs.pitch
    val maxBlur = w / 4f
    val reach = 2f * WheelFadeEndCells * pitch + glyphs.inkHalf + maxBlur
    val padY = ceil(maxOf(reach - glyphs.inkCenter, glyphs.inkCenter + reach - h, 0f)).toInt()
    val padX = ceil(maxBlur).toInt()

    fun speed(): Float {
        roll.value
        shift.value
        val v = abs(roll.velocity) + abs(shift.velocity)
        return if (v.isFinite()) maxOf(v, hold.speed) else hold.speed
    }

    Spacer(
        Modifier
            .layout { m, _ ->
                val p = m.measure(Constraints.fixed(w + padX * 2, h + padY * 2))
                val slot = (w * presence.value.coerceAtLeast(0f)).roundToInt()
                layout(slot, h) { p.place((slot - w) / 2 - padX, -padY) }
            }
            .mochiBlur(continuous = true) {
                val f = smoothstep(0f, MotionBlurVelocity, speed()).coerceAtMost(MaxBlurFraction)
                with(density) { (f * maxBlur).toDp() }
            }
            .drawBehind {
                val shiftCells = shift.value
                if (abs(shiftCells) >= WheelFadeEndCells) return@drawBehind
                val position = roll.value
                val s = speed()
                val zoom = 1f - WheelZoomAmount * (s / ZoomFullVelocity).coerceIn(0f, 1f)
                val alphaScale = 1f - MotionFade * smoothstep(0f, MotionBlurVelocity, s)
                val shiftFade = 1f - smoothstep(WheelFadeStartCells, WheelFadeEndCells, abs(shiftCells))
                val cx = padX + w / 2f
                val cy = padY + glyphs.inkCenter
                val travel = approachDir.intValue
                val passedTarget = (position - target) * travel > 0
                val base = floor(position).toInt()
                val turns = ceil(VisibleTurnRadians / (WheelRadiansPerCell * zoom)).toInt().coerceIn(1, 8)
                for (pass in 0..1) {
                    for (d in turns downTo 0) {
                        for (side in if (d == 0) CenterTurn else TurnSides) {
                            val turn = base + side * d
                            if (passedTarget && !retiring && (turn - target) * travel > 0) continue
                            val u = turn - position
                            val angle = u * WheelRadiansPerCell * zoom
                            if (angle <= -HalfPi || angle >= HalfPi) continue
                            val arc = WheelRadiusCells * sin(angle)
                            if (abs(arc) >= WheelFadeEndCells) continue
                            val screen = arc + shiftCells
                            if ((abs(screen) < CenterLiftCells) != (pass == 1)) continue
                            val rollFade = 1f - smoothstep(WheelFadeStartCells, WheelFadeEndCells, abs(arc))
                            val alpha = rollFade * shiftFade * alphaScale
                            if (alpha <= 0.01f) continue
                            val sink = (1f - WheelSinkAmount * smoothstep(0f, 1f, abs(u))).coerceAtLeast(0.1f)
                            withTransform({
                                translate(cx, cy + screen * pitch)
                                scale(zoom * sink, zoom * sink * cos(angle), pivot = Offset.Zero)
                            }) {
                                drawText(
                                    glyphs.layouts[track.digitAt(turn)],
                                    color = color,
                                    topLeft = Offset(-w / 2f, -glyphs.inkCenter),
                                    alpha = alpha,
                                )
                            }
                        }
                    }
                }
            },
    )
}

private const val OvershootCells = 0.0948f
private const val PeakVelocityAnchor = 7.05f
private const val PeakVelocityExponent = 0.75f
private const val MinDistance = 0.35f
private const val MinDamping = 0.40f
private const val MaxDamping = 0.93f
private const val MinStiffness = 8f
private const val MaxStiffness = 8000f
private const val SolverSteps = 32
private const val VisibilityThreshold = 0.002f
private val TwoPi = (2.0 * PI).toFloat()

private fun retargetSpring(remaining: Float, velocity: Float): SpringSpec<Float> {
    val distance = abs(remaining).coerceAtLeast(MinDistance)
    val toward = if (remaining >= 0f) velocity else -velocity
    val targetPeak = PeakVelocityAnchor * distance.pow(PeakVelocityExponent)
    val damping = dampingForDistance(distance)
    val base = stiffnessForPeak(distance, damping, targetPeak)
    val carry = (toward / targetPeak).coerceAtLeast(0f)
    val stiffness = if (carry > 1f) (base * carry * carry).coerceAtMost(MaxStiffness) else base
    return spring(
        dampingRatio = dampingForOvershoot(distance, toward, stiffness),
        stiffness = stiffness,
        visibilityThreshold = VisibilityThreshold,
    )
}

private class Phase(val omega: Float, val omegaD: Float, val radius: Float, val phi: Float)

private fun phase(distance: Float, damping: Float, stiffness: Float, velocity: Float): Phase {
    val omega = sqrt(stiffness)
    val omegaD = omega * sqrt((1f - damping * damping).coerceAtLeast(1e-4f))
    val x0 = -distance
    val b = (velocity + damping * omega * x0) / omegaD
    var phi = atan2(b, x0)
    if (phi > 0f) phi -= TwoPi
    return Phase(omega, omegaD, sqrt(x0 * x0 + b * b), phi)
}

private fun dampingForDistance(distance: Float): Float {
    val c = ln(distance / OvershootCells) / PI.toFloat()
    return (c / sqrt(1f + c * c)).coerceIn(MinDamping, 0.92f)
}

private fun peakSpeedFromRest(distance: Float, damping: Float, stiffness: Float): Float {
    val p = phase(distance, damping, stiffness, 0f)
    val decay = damping * p.omega
    val amplitude = p.radius * p.omega * sqrt((1f - damping * damping).coerceAtLeast(0f))
    val psi = atan2(p.omegaD, decay)
    val turn = -atan(decay / p.omegaD)
    var best = 0f
    for (m in 0..5) {
        val t = (p.phi + psi + turn + m * PI.toFloat()) / p.omegaD
        if (t < 0f) continue
        val speed = amplitude * exp(-decay * t)
        if (speed > best) best = speed
    }
    return best
}

private fun stiffnessForPeak(distance: Float, damping: Float, target: Float): Float {
    var lo = MinStiffness
    var hi = MaxStiffness
    repeat(SolverSteps) {
        val mid = sqrt(lo * hi)
        if (peakSpeedFromRest(distance, damping, mid) < target) lo = mid else hi = mid
    }
    return sqrt(lo * hi)
}

private fun overshoot(distance: Float, velocity: Float, damping: Float, stiffness: Float): Float {
    val p = phase(distance, damping, stiffness, velocity)
    val decay = damping * p.omega
    val amplitude = p.radius * sqrt((1f - damping * damping).coerceAtLeast(0f))
    val turn = -atan(decay / p.omegaD)
    val period = TwoPi / p.omegaD
    var best = 0f
    for (m in 0..5) {
        var t = (turn + p.phi + m * TwoPi) / p.omegaD
        while (t <= 0f) t += period
        val x = amplitude * exp(-decay * t)
        if (x > best) best = x
    }
    return best
}

private fun dampingForOvershoot(distance: Float, velocity: Float, stiffness: Float): Float {
    var lo = 0.02f
    var hi = 0.99f
    repeat(SolverSteps) {
        val mid = (lo + hi) / 2f
        if (overshoot(distance, velocity, mid, stiffness) > OvershootCells) lo = mid else hi = mid
    }
    return ((lo + hi) / 2f).coerceIn(MinDamping, MaxDamping)
}

private const val ReleaseSeconds = 0.2f
private const val IdleSpeed = 0.5f
private const val MaxFrameSeconds = 0.05f

private class MotionHold {
    var speed by mutableFloatStateOf(0f)
        private set

    fun freeze(atSpeed: Float) {
        if (atSpeed > speed) speed = atSpeed
    }

    fun clear() {
        speed = 0f
    }

    fun decay(seconds: Float) {
        speed *= exp(-seconds / ReleaseSeconds)
    }
}

@Composable
private fun rememberMotionHold(position: () -> Float, speed: () -> Float): MotionHold {
    val hold = remember { MotionHold() }
    val latestPosition by rememberUpdatedState(position)
    val latestSpeed by rememberUpdatedState(speed)
    LaunchedEffect(hold) {
        while (true) {
            snapshotFlow { latestPosition() }.drop(1).first()
            var last = 0L
            while (true) {
                val now = withFrameNanos { it }
                val seconds = if (last == 0L) 0f else ((now - last) / 1e9f).coerceAtMost(MaxFrameSeconds)
                last = now
                val current = latestSpeed().let { if (it.isFinite()) it else 0f }
                if (current >= hold.speed) hold.clear() else hold.decay(seconds)
                if (current <= IdleSpeed && hold.speed <= IdleSpeed) {
                    hold.clear()
                    break
                }
            }
        }
    }
    return hold
}

@Composable
private fun LaunchedEffectTarget(target: Float, onTarget: suspend (Float) -> Unit) {
    val latestTarget by rememberUpdatedState(target)
    val latestOnTarget by rememberUpdatedState(onTarget)
    LaunchedEffect(Unit) {
        val scope = CoroutineScope(coroutineContext)
        snapshotFlow { latestTarget }.collect { value -> scope.launch { latestOnTarget(value) } }
    }
}
