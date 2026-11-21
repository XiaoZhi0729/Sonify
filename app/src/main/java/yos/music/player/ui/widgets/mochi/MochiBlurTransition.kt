// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy as AlphaStrategy
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

fun <T> mochiSpring(
    durationMillis: Int = 500,
    bounce: Double = 0.0,
    visibilityThreshold: T? = null,
): SpringSpec<T> {
    require(durationMillis > 0) { "durationMillis must be positive" }
    val b = bounce.coerceIn(-0.99999, 1.0)
    val d = durationMillis / 1000.0
    val stiffness = (2.0 * PI / d).let { it * it }.toFloat()
    val damping = if (b >= 0) (1.0 - b).toFloat() else (1.0 / (1.0 + b)).toFloat()
    return spring(dampingRatio = damping, stiffness = stiffness, visibilityThreshold = visibilityThreshold)
}

object MochiSprings {
    fun <T> smooth(durationMillis: Int = 500, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.0 + extraBounce, visibilityThreshold)

    fun <T> crisp(durationMillis: Int = 300, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.1 + extraBounce, visibilityThreshold)

    fun <T> snappy(durationMillis: Int = 500, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.15 + extraBounce, visibilityThreshold)

    fun <T> bouncy(durationMillis: Int = 500, extraBounce: Double = 0.0, visibilityThreshold: T? = null): SpringSpec<T> =
        mochiSpring(durationMillis, 0.3 + extraBounce, visibilityThreshold)
}

/**
 * 源自 mochi-compose 的模糊替换参数：旧内容边模糊、缩小、淡出，新内容从糊、小、透明里清晰起来。
 */
@Immutable
data class MochiBlurTransition(
    val blur: Dp = 8.dp,
    val scale: Float = 0.9f,
    val slide: Float = 0f,
    val enter: AnimationSpec<Float> = ContentSpring,
    val exit: AnimationSpec<Float> = ContentSpring,
)

internal val ContentSpring: AnimationSpec<Float> =
    MochiSprings.snappy(300, visibilityThreshold = 0.001f)

internal val CounterSpring: AnimationSpec<Float> =
    spring(dampingRatio = 0.58f, stiffness = 380f, visibilityThreshold = 0.001f)

object MochiBlurTransitions {
    val Replace: MochiBlurTransition = MochiBlurTransition()
    val Appear: MochiBlurTransition = MochiBlurTransition(blur = 10.dp, scale = 0.8f)
    val Counter: MochiBlurTransition = MochiBlurTransition(
        blur = 5.dp,
        scale = 1f,
        slide = 0.55f,
        enter = CounterSpring,
        exit = ContentSpring,
    )
}

private class BlurSlot<T>(val key: T, initial: Float) {
    val p = Animatable(initial)
    var sign by mutableFloatStateOf(0f)
    var job: Job? = null
}

/**
 * 源自 mochi-compose 的 [MochiBlurCrossfade]：目标状态变化时，旧内容模糊缩小淡出，新内容模糊放大淡入，在原位交叉替换。
 */
@Composable
fun <T> MochiBlurCrossfade(
    targetState: T,
    modifier: Modifier = Modifier,
    transition: MochiBlurTransition = MochiBlurTransitions.Replace,
    direction: Int = 0,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable (T) -> Unit,
) {
    val slots = remember { mutableStateListOf(BlurSlot(targetState, 1f)) }
    val scope = rememberCoroutineScope()
    val dir by rememberUpdatedState(direction)
    val spec by rememberUpdatedState(transition)
    LaunchedEffect(targetState) {
        val d = dir.coerceIn(-1, 1).toFloat()
        for (s in slots.toList()) {
            if (s.key == targetState) continue
            s.sign = -d
            s.job?.cancel()
            s.job = scope.launch {
                s.p.animateTo(0f, spec.exit) { if (value < 0.01f) slots.remove(s) }
                slots.remove(s)
            }
        }
        val s = slots.firstOrNull { it.key == targetState } ?: BlurSlot(targetState, 0f).also {
            it.sign = d
            slots.add(it)
        }
        s.job?.cancel()
        s.job = scope.launch { s.p.animateTo(1f, spec.enter) }
    }

    Layout(
        content = {
            for (s in slots) {
                key(s) {
                    BlurLayer(s, transition) { content(s.key) }
                }
            }
        },
        modifier = modifier,
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val placeables = measurables.map { it.measure(loose) }
        val w: Int
        val h: Int
        if (placeables.all { it.width == placeables[0].width && it.height == placeables[0].height }) {
            w = placeables.firstOrNull()?.width ?: 0
            h = placeables.firstOrNull()?.height ?: 0
        } else {
            var sw = 0f
            var sh = 0f
            var sp = 0f
            placeables.forEachIndexed { i, pl ->
                val p = slots.getOrNull(i)?.p?.value?.coerceIn(0f, 1f) ?: 0f
                sw += pl.width * p
                sh += pl.height * p
                sp += p
            }
            w = if (sp > 0.001f) (sw / sp).roundToInt() else 0
            h = if (sp > 0.001f) (sh / sp).roundToInt() else 0
        }
        val lw = w.coerceIn(constraints.minWidth, constraints.maxWidth)
        val lh = h.coerceIn(constraints.minHeight, constraints.maxHeight)
        layout(lw, lh) {
            placeables.forEach { pl ->
                val o = contentAlignment.align(IntSize(pl.width, pl.height), IntSize(lw, lh), layoutDirection)
                pl.place(o)
            }
        }
    }
}

@Composable
fun MochiBlurVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    transition: MochiBlurTransition = MochiBlurTransitions.Appear,
    contentAlignment: Alignment = Alignment.Center,
    content: @Composable () -> Unit,
) {
    MochiBlurCrossfade(visible, modifier, transition, contentAlignment = contentAlignment) { if (it) content() }
}

/**
 * 源自 mochi-compose 的 [MochiBlurText]：文字换掉时带模糊与微缩放的弹簧交叉替换，同文字不重播。
 */
@Composable
fun MochiBlurText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    style: TextStyle = LocalTextStyle.current,
    maxLines: Int = Int.MAX_VALUE,
    overflow: TextOverflow = TextOverflow.Clip,
    transition: MochiBlurTransition = MochiBlurTransitions.Replace,
    contentAlignment: Alignment = Alignment.CenterStart,
) {
    MochiBlurCrossfade(text, modifier, transition, contentAlignment = contentAlignment) {
        Text(
            text = it,
            color = color,
            style = style,
            maxLines = maxLines,
            overflow = overflow,
        )
    }
}

private fun <T> layerBlur(s: BlurSlot<T>, transition: MochiBlurTransition): Dp {
    val p = s.p.value.coerceIn(0f, 1f)
    val hidden = 1f - p
    return if (p * p * (3f - 2f * p) < 0.02f) 0.dp else transition.blur * hidden
}

@Composable
private fun <T> BlurLayer(s: BlurSlot<T>, transition: MochiBlurTransition, content: @Composable () -> Unit) {
    Layout(
        content = content,
        modifier = Modifier
            .graphicsLayer {
                val p = s.p.value.coerceIn(0f, 1f)
                val a = p * p * (3f - 2f * p)
                mochiBlurAlpha(a, layerBlur(s, transition))
                val raw = s.p.value
                val k = transition.scale + (1f - transition.scale) * raw
                scaleX = k
                scaleY = k
                translationY = s.sign * (1f - raw) * transition.slide * size.height
            }
            .mochiBlur { layerBlur(s, transition) },
    ) { measurables, constraints ->
        val pls = measurables.map { it.measure(constraints) }
        val w = pls.maxOfOrNull { it.width } ?: 0
        val h = pls.maxOfOrNull { it.height } ?: 0
        layout(w, h) { pls.forEach { it.place(0, 0) } }
    }
}

fun Modifier.mochiBlur(continuous: Boolean = false, radius: () -> Dp): Modifier =
    this then CachedBlurElement(radius, continuous) then Modifier.graphicsLayer()

private class CachedBlurElement(val radius: () -> Dp, val continuous: Boolean) : ModifierNodeElement<CachedBlurNode>() {
    override fun create(): CachedBlurNode = CachedBlurNode(radius, continuous)

    override fun update(node: CachedBlurNode) {
        node.radius = radius
        node.continuous = continuous
    }

    override fun hashCode(): Int = radius.hashCode() * 31 + continuous.hashCode()

    override fun equals(other: Any?): Boolean =
        other is CachedBlurElement && other.radius === radius && other.continuous == continuous

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiBlur"
        properties["continuous"] = continuous
    }
}

private data class BlurPlan(val radius: Float, val k: Int, val low: Float)

private class CachedBlurNode(radius: () -> Dp, var continuous: Boolean) :
    Modifier.Node(),
    DrawModifierNode {
    var radius: () -> Dp = radius
    private var plan: State<BlurPlan?>? = null
    private var inner: GraphicsLayer? = null
    private var outer: GraphicsLayer? = null
    private var effect: BlurEffect? = null
    private var effectPlan: BlurPlan? = null

    private fun planState(density: Density): State<BlurPlan?> = plan ?: derivedStateOf {
        val px = with(density) { this@CachedBlurNode.radius().toPx() }
        if (continuous) continuousPlan(px) else steppedPlan(px)
    }.also { plan = it }

    override fun ContentDrawScope.draw() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            release()
            drawContent()
            return
        }
        val p = planState(requireDensity()).value
        if (p == null || size.width < 1f || size.height < 1f) {
            release()
            drawContent()
            return
        }
        drawBlurred(p)
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun ContentDrawScope.drawBlurred(p: BlurPlan) {
        val ctx = requireGraphicsContext()
        val i = inner ?: ctx.createGraphicsLayer().also { inner = it }
        val e = if (p == effectPlan) {
            effect!!
        } else {
            BlurEffect(p.low, p.low, TileMode.Decal).also {
                effect = it
                effectPlan = p
            }
        }
        val k = p.k
        i.renderEffect = e
        i.record(IntSize(ceil(size.width / k).toInt(), ceil(size.height / k).toInt())) {
            if (k == 1) {
                this@drawBlurred.drawContent()
            } else {
                scale(1f / k, pivot = Offset.Zero) { this@drawBlurred.drawContent() }
            }
        }
        if (continuous) {
            releaseCache()
            if (k == 1) drawLayer(i) else scale(k.toFloat(), pivot = Offset.Zero) { drawLayer(i) }
            return
        }
        val o = outer ?: ctx.createGraphicsLayer().also {
            it.compositingStrategy = CompositingStrategy.Offscreen
            outer = it
        }
        val pad = ceil(3f * blurSigma(p.radius) / k).roundToInt() + 1
        val w = ceil(size.width / k).toInt()
        val h = ceil(size.height / k).toInt()
        o.record(IntSize(w + pad * 2, h + pad * 2)) {
            translate(pad.toFloat(), pad.toFloat()) { drawLayer(i) }
        }
        withTransform({
            translate(-pad.toFloat() * k, -pad.toFloat() * k)
            if (k != 1) scale(k.toFloat(), k.toFloat(), pivot = Offset.Zero)
        }) { drawLayer(o) }
    }

    private fun releaseCache() {
        val o = outer ?: return
        if (isAttached) requireGraphicsContext().releaseGraphicsLayer(o)
        outer = null
    }

    private fun release() {
        val ctx = if (isAttached) requireGraphicsContext() else return
        inner?.let { ctx.releaseGraphicsLayer(it) }
        outer?.let { ctx.releaseGraphicsLayer(it) }
        inner = null
        outer = null
    }

    override fun onDetach() {
        release()
        plan = null
        effect = null
        effectPlan = null
    }
}

private const val StepRatio = 1.5f
private val LnStep = ln(StepRatio)
private const val MaxBlurLevel = 10

private fun steppedPlan(px: Float): BlurPlan? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    if (px < 0.75f) return null
    val level = (ln(px) / LnStep).roundToInt().coerceIn(0, MaxBlurLevel)
    val r = StepRatio.pow(level)
    val k = blurDownsample(r)
    return BlurPlan(r, k, lowRadius(r, k))
}

private fun continuousPlan(px: Float): BlurPlan? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    if (px < 0.75f) return null
    val k = blurDownsample(px)
    val low = (lowRadius(px, k) * 4f).roundToInt() / 4f
    return BlurPlan(px, k, low)
}

private fun blurSigma(r: Float): Float = 0.57735f * r + 0.5f

private fun lowRadius(r: Float, k: Int): Float {
    if (k == 1) return r
    val sigma = blurSigma(r)
    val interp = k / 2.45f
    val low = sqrt((sigma * sigma - interp * interp).coerceAtLeast(0f)) / k
    return ((low - 0.5f) / 0.57735f).coerceAtLeast(0.5f)
}

private fun blurDownsample(r: Float): Int {
    val sigma = blurSigma(r)
    return when {
        sigma >= 10f -> 8
        sigma >= 5f -> 4
        sigma >= 2.5f -> 2
        else -> 1
    }
}

fun GraphicsLayerScope.mochiBlurAlpha(alpha: Float, blur: Dp, continuous: Boolean = false) {
    this.alpha = alpha
    val px = blur.toPx()
    val active = if (continuous) continuousPlan(px) != null else steppedPlan(px) != null
    compositingStrategy = if (active && alpha < 1f) AlphaStrategy.ModulateAlpha else AlphaStrategy.Auto
}
