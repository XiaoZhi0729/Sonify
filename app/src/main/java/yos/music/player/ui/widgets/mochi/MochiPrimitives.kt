// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.kyant.capsule.Continuity
import com.kyant.capsule.continuities.G1Continuity
import com.kyant.capsule.path.toPath
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

internal const val MochiDisabledAlpha: Float = 0.4f

internal fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

internal fun smoothstep(e0: Float, e1: Float, x: Float): Float {
    val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

internal object MochiMotions {
    fun stiffnessFor(response: Float): Float = (2f * PI.toFloat() / response).pow(2)

    fun responseSpring(response: Float, damping: Float): AnimationSpec<Float> =
        spring(dampingRatio = damping, stiffness = stiffnessFor(response), visibilityThreshold = 0.0005f)
}

internal fun rubberBand(overshoot: Float, dimension: Float, max: Float): Float {
    if (max <= 0f || dimension <= 0f || overshoot == 0f) return 0f
    val f = (1f - 1f / (abs(overshoot) * 0.55f / dimension + 1f)) * dimension
    return sign(overshoot) * f / (1f + f / max)
}

@Stable
internal class MochiFollowSpring(var response: Float, var dampingRatio: Float) {
    var value: Offset = Offset.Zero
        private set
    var velocity: Offset = Offset.Zero
        private set
    private var lastNanos = 0L

    fun snapTo(v: Offset, resetClock: Boolean = true) {
        value = v
        velocity = Offset.Zero
        if (resetClock) lastNanos = 0L
    }

    fun step(target: Offset, dt: Float): Offset {
        val omega = (2f * PI.toFloat()) / response
        val zeta = dampingRatio
        var x = value
        var v = velocity
        val steps = (dt / (1f / 240f)).toInt().coerceAtLeast(1)
        val h = dt / steps
        repeat(steps) {
            val a = (target - x) * (omega * omega) - v * (2f * zeta * omega)
            v += a * h
            x += v * h
        }
        value = x
        velocity = v
        return x
    }

    suspend fun awaitFrameDelta(): Float = withFrameNanos { now ->
        val dt = if (lastNanos == 0L) 1f / 120f else ((now - lastNanos) / 1e9f).coerceIn(0f, 1f / 20f)
        lastNanos = now
        dt
    }

    suspend fun stepFrame(target: Offset): Offset = step(target, awaitFrameDelta())
}

internal fun jellyStretch(velocity: Float): Float = (velocity * 0.045f).coerceIn(-0.10f, 0.16f)

internal fun jellyCrossScale(stretch: Float): Float = 1f / (1f + stretch * 0.6f)

enum class MochiHapticProfile { IosLike, Rich, Off }

internal class MochiHaptics(private val view: View, private val profile: MochiHapticProfile) {
    private val vibrator: Vibrator? = view.context.vibratorOrNull()
    private val canCompose: Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            vibrator?.hasVibrator() == true &&
            view.context.checkSelfPermission(Manifest.permission.VIBRATE) == PackageManager.PERMISSION_GRANTED
    private val lowTickOk = canCompose && vibrator.supports(VibrationEffect.Composition.PRIMITIVE_LOW_TICK)
    private val tickOk = canCompose && vibrator.supports(VibrationEffect.Composition.PRIMITIVE_TICK)
    private val clickOk = canCompose && vibrator.supports(VibrationEffect.Composition.PRIMITIVE_CLICK)
    private var lastTickAt = 0L

    fun tick() {
        if (profile == MochiHapticProfile.Off) return
        val now = SystemClock.uptimeMillis()
        if (now - lastTickAt < TickGapMillis) return
        lastTickAt = now
        val played = primitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.7f, tickOk) ||
            primitive(VibrationEffect.Composition.PRIMITIVE_LOW_TICK, 1f, lowTickOk)
        if (!played) {
            perform(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    HapticFeedbackConstants.SEGMENT_TICK
                } else {
                    HapticFeedbackConstants.CLOCK_TICK
                },
            )
        }
    }

    /** 确认一个选择（开关拨动、松手选中）：一下轻 click。IosLike 和 Rich 都震 */
    fun confirm() {
        if (profile == MochiHapticProfile.Off) return
        if (!primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.55f, clickOk)) {
            perform(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    HapticFeedbackConstants.CONFIRM
                } else {
                    HapticFeedbackConstants.VIRTUAL_KEY
                },
            )
        }
    }

    /** 越过阈值（下拉刷新触发）：一下轻 click */
    fun threshold() {
        if (profile == MochiHapticProfile.Off) return
        if (!primitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.6f, clickOk)) {
            perform(
                when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
                        HapticFeedbackConstants.GESTURE_THRESHOLD_ACTIVATE
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> HapticFeedbackConstants.CONFIRM
                    else -> HapticFeedbackConstants.VIRTUAL_KEY
                },
            )
        }
    }

    private fun primitive(id: Int, scale: Float, supported: Boolean): Boolean {
        if (!supported || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        val v = vibrator ?: return false
        return try {
            val effect = VibrationEffect.startComposition().addPrimitive(id, scale).compose()
            v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            true
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun perform(constant: Int) {
        @Suppress("DEPRECATION")
        view.performHapticFeedback(constant, HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING)
    }
}

private const val TickGapMillis = 24L

private fun Context.vibratorOrNull(): Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    getSystemService(VibratorManager::class.java)?.defaultVibrator
} else {
    @Suppress("DEPRECATION")
    getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
}

private fun Vibrator?.supports(primitive: Int): Boolean {
    if (this == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return false
    return try {
        areAllPrimitivesSupported(primitive)
    } catch (_: RuntimeException) {
        false
    }
}

internal fun continuityFor(smoothing: Float): Continuity =
    if (smoothing <= 0f) G1Continuity else Continuity.Default

internal fun Path.addG2RoundRect(rect: Rect, radius: Float, smoothing: Float) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val r = radius.coerceIn(0f, min(rect.width, rect.height) / 2f).toDouble()
    val p = continuityFor(smoothing)
        .createRoundedRectanglePathSegments(rect.width.toDouble(), rect.height.toDouble(), r, r, r, r)
        .toPath()
    addPath(p, rect.topLeft)
}

internal fun Path.addG2RoundRect(
    rect: Rect,
    topLeft: Float,
    topRight: Float,
    bottomRight: Float,
    bottomLeft: Float,
) {
    if (rect.width <= 0f || rect.height <= 0f) return
    val half = min(rect.width, rect.height) / 2f
    val p = Continuity.Default
        .createRoundedRectanglePathSegments(
            rect.width.toDouble(),
            rect.height.toDouble(),
            topLeft.coerceIn(0f, half).toDouble(),
            topRight.coerceIn(0f, half).toDouble(),
            bottomRight.coerceIn(0f, half).toDouble(),
            bottomLeft.coerceIn(0f, half).toDouble(),
        )
        .toPath()
    addPath(p, rect.topLeft)
}

internal fun DrawScope.drawG2RoundRect(
    scratch: Path,
    rect: Rect,
    radius: Float,
    color: Color,
    alpha: Float = 1f,
    style: DrawStyle = Fill,
) {
    if (rect.width <= 0f || rect.height <= 0f) return
    scratch.reset()
    scratch.addG2RoundRect(rect, radius, 1f)
    drawPath(scratch, color, alpha = alpha, style = style)
}

internal class G2RectClipShape4(
    private val rect: Rect,
    private val topLeft: Float,
    private val topRight: Float,
    private val bottomRight: Float,
    private val bottomLeft: Float,
) : Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: LayoutDirection,
        density: Density,
    ): Outline = Outline.Generic(
        Path().apply { addG2RoundRect(rect, topLeft, topRight, bottomRight, bottomLeft) },
    )

    override fun equals(other: Any?): Boolean = other is G2RectClipShape4 &&
        other.rect == rect &&
        other.topLeft == topLeft &&
        other.topRight == topRight &&
        other.bottomRight == bottomRight &&
        other.bottomLeft == bottomLeft

    override fun hashCode(): Int {
        var h = rect.hashCode()
        h = h * 31 + topLeft.hashCode()
        h = h * 31 + topRight.hashCode()
        h = h * 31 + bottomRight.hashCode()
        return h * 31 + bottomLeft.hashCode()
    }
}

internal class G2PathCache {
    private val local = Path()
    private val placed = Path()
    internal val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private var w = -1f
    private var h = -1f
    private var r = -1f
    private var s = -1f
    private var x = Float.NaN
    private var y = Float.NaN

    fun get(rect: Rect, radius: Float, smoothing: Float): Path {
        if (rect.width != w || rect.height != h || radius != r || smoothing != s) {
            w = rect.width
            h = rect.height
            r = radius
            s = smoothing
            local.rewind()
            local.addG2RoundRect(Rect(0f, 0f, rect.width, rect.height), radius, smoothing)
            x = Float.NaN
        }
        if (rect.left != x || rect.top != y) {
            x = rect.left
            y = rect.top
            placed.rewind()
            placed.addPath(local, rect.topLeft)
        }
        return placed
    }
}

internal const val ControlSmoothing = 1f
private const val FallbackShadowRings = 4
private val SoftShadowsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P

internal fun DrawScope.drawG2Capsule(cache: G2PathCache, rect: Rect, color: Color, alpha: Float = 1f) {
    if (rect.width <= 0f || rect.height <= 0f) return
    drawPath(cache.get(rect, minOf(rect.width, rect.height) / 2f, ControlSmoothing), color, alpha = alpha)
}

internal fun DrawScope.drawPillThumb(
    rect: Rect,
    color: Color,
    shadow: Color,
    lift: Float,
    glow: MochiGlowState?,
    path: G2PathCache,
    glowOrigin: Offset = Offset.Zero,
) {
    val g2 = path.get(rect, minOf(rect.width, rect.height) / 2f, ControlSmoothing)
    if (SoftShadowsSupported && shadow.alpha > 0f) {
        val blur = (2.5f + 5f * lift).dp.toPx()
        val dy = (1f + 2f * lift).dp.toPx()
        drawIntoCanvas { c ->
            val p = path.shadowPaint.apply {
                this.color = color.toArgb()
                setShadowLayer(blur, 0f, dy, shadow.copy(alpha = shadow.alpha * (0.6f + 0.4f * lift)).toArgb())
            }
            c.nativeCanvas.drawPath(g2.asAndroidPath(), p)
        }
    } else {
        if (shadow.alpha > 0f) {
            val blur = (2.5f + 5f * lift).dp.toPx()
            val dy = (1f + 2f * lift).dp.toPx()
            val a = shadow.alpha * (0.6f + 0.4f * lift)
            for (k in FallbackShadowRings downTo 1) {
                val grow = blur * k / FallbackShadowRings
                val r = rect.inflate(grow).translate(0f, dy)
                val radius = minOf(r.width, r.height) / 2f
                drawRoundRect(
                    shadow.copy(alpha = 1f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(radius),
                    alpha = a / FallbackShadowRings * (1f - (k - 1f) / FallbackShadowRings),
                )
            }
        }
        drawPath(g2, color)
    }
    if (glow != null && glow.intensity > 0.01f) {
        drawGlow(glow, Outline.Generic(g2), glow.center - glowOrigin)
    }
}

@Immutable
internal data class MochiGlowSpec(
    val color: Color,
    val followResponse: Float = 0.32f,
    val followDamping: Float = 0.78f,
)

internal object MochiGlowDefaults {
    @Composable
    fun spec(color: Color = Color.White.copy(alpha = if (isFlamingoInDarkMode()) 0.22f else 0.45f)): MochiGlowSpec =
        remember(color) { MochiGlowSpec(color) }

    fun radius(size: Size, density: Density): Float = with(density) {
        val short = min(size.width, size.height)
        val long = max(size.width, size.height)
        (short * 1.2f + long * 0.15f).coerceIn(MinRadius.toPx(), MaxRadius.toPx())
    }

    val MinRadius: Dp = 40.dp
    val MaxRadius: Dp = 160.dp
}

@Stable
internal class MochiGlowState(spec: MochiGlowSpec) {
    var spec: MochiGlowSpec by mutableStateOf(spec)

    private val intensityAnim = Animatable(0f)
    private val radiusAnim = Animatable(0f)
    private var targetState by mutableStateOf(Offset.Zero)

    val intensity: Float get() = intensityAnim.value
    val radius: Float get() = radiusAnim.value
    var center: Offset by mutableStateOf(Offset.Zero)
        private set
    var held: Boolean by mutableStateOf(false)
        private set

    fun moveTo(p: Offset) {
        targetState = p
    }

    suspend fun press(at: Offset? = null, radius: Float, intensity: Float = 1f) {
        if (at != null) moveTo(at)
        held = true
        coroutineScope {
            radiusAnim.snapTo(radius * 0.35f)
            launch { radiusAnim.animateTo(radius, MochiMotions.responseSpring(0.6f, 1f)) }
            launch { intensityAnim.animateTo(intensity, MochiMotions.responseSpring(0.2f, 1f)) }
        }
    }

    fun markReleased() {
        held = false
    }

    suspend fun release() {
        held = false
        intensityAnim.animateTo(0f, MochiMotions.responseSpring(0.5f, 1f))
    }

    suspend fun runFollow() {
        val spring = MochiFollowSpring(spec.followResponse, spec.followDamping)
        while (true) {
            if (intensityAnim.value <= 0.001f) {
                snapshotFlow { intensityAnim.value }.first { it > 0.001f }
                spring.snapTo(targetState)
                center = targetState
            }
            spring.response = spec.followResponse
            spring.dampingRatio = spec.followDamping
            center = spring.stepFrame(targetState)
        }
    }
}

@Composable
internal fun rememberMochiGlowState(spec: MochiGlowSpec = MochiGlowDefaults.spec()): MochiGlowState {
    val state = remember { MochiGlowState(spec) }
    state.spec = spec
    LaunchedEffect(state) { state.runFollow() }
    return state
}

private const val GlowPlusWeight = 0.28f
private val BodyStops = FloatArray(12) { it * 0.16f }
    .let { rs -> rs to FloatArray(12) { 0.85f * exp(-rs[it] * rs[it] * rs[it] * 1.6f) } }

internal fun DrawScope.drawGlow(
    state: MochiGlowState,
    outline: Outline,
    center: Offset,
    intensityScale: Float = 1f,
) {
    val a = state.intensity * intensityScale
    if (a <= 0.01f) return
    val color = state.spec.color
    val gr = state.radius.coerceAtLeast(1f)
    val (rs, vs) = BodyStops
    val end = rs.last()
    val alpha = color.alpha * a * GlowPlusWeight
    val pairs = Array(rs.size) { i ->
        val v = if (i == rs.lastIndex) 0f else vs[i]
        rs[i] / end to color.copy(alpha = (alpha * v).coerceIn(0f, 1f))
    }
    drawOutline(
        outline = outline,
        brush = Brush.radialGradient(
            colorStops = pairs,
            center = center,
            radius = (gr * end).coerceAtLeast(1f),
        ),
        blendMode = BlendMode.Plus,
    )
}

@Stable
internal class TabBarState(initial: Float) {
    var pos by mutableFloatStateOf(initial)
        private set
    var vel by mutableFloatStateOf(0f)
        private set
    var tail by mutableFloatStateOf(initial)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set
    var held by mutableStateOf(false)
    var finger by mutableFloatStateOf(initial)
    var drag: Offset = Offset.Zero

    private var settle: Int? = null
    private var settleFrames = 0

    fun release(target: Int?) {
        held = false
        drag = Offset.Zero
        settle = target
        settleFrames = 6
    }

    private fun target(n: Int, selected: Int): Float {
        if (held) {
            val c = finger.coerceIn(0f, n - 1f)
            return c + rubberBand(finger - c, 1f, 0.35f)
        }
        val s = settle
        if (s != null) {
            if (s == selected || settleFrames-- <= 0) settle = null else return s.toFloat()
        }
        return selected.toFloat()
    }

    private fun atRest(t: Float, a: MochiFollowSpring, b: MochiFollowSpring, o: MochiFollowSpring): Boolean = !held &&
        settle == null &&
        abs(pos - t) < 0.001f &&
        abs(a.velocity.x) < 0.01f &&
        abs(tail - t) < 0.001f &&
        abs(b.velocity.x) < 0.01f &&
        o.value.getDistance() < 0.1f &&
        o.velocity.getDistance() < 1f

    suspend fun run(n: Int, selected: () -> Int, haptics: MochiHaptics, bar: Size, maxShift: Offset) {
        val spring = MochiFollowSpring(0.42f, 0.72f)
        val tailSpring = MochiFollowSpring(0.24f, 0.9f)
        val shift = MochiFollowSpring(0.3f, 0.62f)
        spring.snapTo(Offset(pos, 0f))
        tailSpring.snapTo(Offset(tail, 0f))
        shift.snapTo(offset)
        var nearest = selected()
        while (true) {
            val t = target(n, selected())
            if (atRest(t, spring, tailSpring, shift)) {
                pos = t
                tail = t
                vel = 0f
                offset = Offset.Zero
                spring.snapTo(Offset(t, 0f))
                tailSpring.snapTo(Offset(t, 0f))
                shift.snapTo(Offset.Zero)
                snapshotFlow { held to selected() }.first { (h, s) -> h || s.toFloat() != pos }
                continue
            }
            val dt = spring.awaitFrameDelta()
            spring.response = if (held) 0.22f else 0.42f
            spring.dampingRatio = if (held) 0.86f else 0.72f
            pos = spring.step(Offset(t, 0f), dt).x
            vel = spring.velocity.x
            tailSpring.response = if (held) 0.16f else 0.24f
            tail = tailSpring.step(Offset(pos, 0f), dt).x
            val d = drag
            val shiftTarget = if (held) {
                Offset(rubberBand(d.x, bar.width, maxShift.x), rubberBand(d.y, bar.height * 4f, maxShift.y))
            } else {
                Offset.Zero
            }
            offset = shift.step(shiftTarget, dt)
            val k = pos.roundToInt().coerceIn(0, n - 1)
            if (held && k != nearest) haptics.tick()
            nearest = k
        }
    }
}

internal fun Rect.scaleAround(sx: Float, sy: Float): Rect {
    val c = center
    val hw = width * sx / 2f
    val hh = height * sy / 2f
    return Rect(c.x - hw, c.y - hh, c.x + hw, c.y + hh)
}
