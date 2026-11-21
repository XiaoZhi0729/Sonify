// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireDensity
import androidx.compose.ui.node.traverseAncestors
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.tanh

/** 按压形变的默认值 */
object MochiPressDefaults {
    const val Scale: Float = 0.96f
    val Follow: Dp = 3.dp
}

/**
 * 源自 mochi-compose 的按压反馈：按下轻微缩小，并朝手指方向轻微偏移、略微拉长（跟手形变）；松手带一点回弹恢复。
 * 不消费触摸事件，可以和 clickable 叠用。只改图层（不重组、不重新布局）。
 * 在滚动容器里时按下先等一小会儿、滚动接手就收回；快速轻点也补一次按下回弹。
 */
fun Modifier.mochiPressDeform(
    scale: Float = MochiPressDefaults.Scale,
    follow: Dp = MochiPressDefaults.Follow,
): Modifier = this then PressDeformElement(scale, follow)

private data class PressDeformElement(
    val scale: Float,
    val follow: Dp,
) : ModifierNodeElement<PressDeformNode>() {
    override fun create(): PressDeformNode = PressDeformNode(scale, follow)

    override fun update(node: PressDeformNode) {
        node.scale = scale
        node.follow = follow
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "mochiPressDeform"
        properties["scale"] = scale
        properties["follow"] = follow
    }
}

private class PressDeformNode(
    var scale: Float,
    var follow: Dp,
) : DelegatingNode(), LayoutModifierNode {
    private var press by mutableStateOf(Offset(1f, 0f))
    private var shift by mutableStateOf(Offset.Zero)
    private var held = false
    private var finger = Offset.Zero
    private var w = 1f
    private var h = 1f
    private var loop: Job? = null
    private var release: Job? = null

    init {
        delegate(
            SuspendingPointerInputModifierNode {
                val inScroll = isInScrollContainer()
                awaitEachGesture {
                    awaitMochiPress(
                        inScrollContainer = inScroll,
                        onPress = { p ->
                            release?.cancel()
                            finger = p
                            held = true
                            startLoop()
                        },
                        onMove = { finger = it },
                        onRelease = { hold ->
                            release?.cancel()
                            if (hold <= 0L) {
                                held = false
                            } else {
                                release = coroutineScope.launch {
                                    delay(hold)
                                    held = false
                                }
                            }
                        },
                    )
                }
            },
        )
    }

    private fun shiftTarget(): Offset {
        if (!held) return Offset.Zero
        val f = follow.value * requireDensity().density
        val dx = (finger.x - w / 2f) / (w / 2f).coerceAtLeast(1f)
        val dy = (finger.y - h / 2f) / (h / 2f).coerceAtLeast(1f)
        return Offset(f * tanh(dx), f * tanh(dy))
    }

    private fun startLoop() {
        if (loop?.isActive == true) return
        loop = coroutineScope.launch {
            val s = MochiFollowSpring(0.24f, 0.62f)
            val o = MochiFollowSpring(0.3f, 0.7f)
            s.snapTo(press)
            o.snapTo(shift)
            while (true) {
                val dt = s.awaitFrameDelta()
                press = s.step(Offset(if (held) scale else 1f, 0f), dt)
                shift = o.step(shiftTarget(), dt)
                val rest = !held && abs(press.x - 1f) < 0.0005f && abs(s.velocity.x) < 0.01f &&
                    shift.getDistance() < 0.05f && o.velocity.getDistance() < 1f
                if (rest) {
                    press = Offset(1f, 0f)
                    shift = Offset.Zero
                    break
                }
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val p = measurable.measure(constraints)
        w = p.width.toFloat()
        h = p.height.toFloat()
        return layout(p.width, p.height) {
            p.placeWithLayer(0, 0) {
                val k = press.x
                val sh = shift
                val stretchX = 1f + abs(sh.x) / w.coerceAtLeast(1f) * 0.6f
                val stretchY = 1f + abs(sh.y) / h.coerceAtLeast(1f) * 0.6f
                scaleX = k * stretchX
                scaleY = k * stretchY
                translationX = sh.x
                translationY = sh.y
                transformOrigin = TransformOrigin.Center
            }
        }
    }
}

internal suspend fun AwaitPointerEventScope.awaitMochiPress(
    inScrollContainer: Boolean,
    onPress: (Offset) -> Unit,
    onMove: (Offset) -> Unit,
    onRelease: (holdMillis: Long) -> Unit,
) {
    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
    var pressed = false
    var last = down.position
    if (!inScrollContainer) {
        pressed = true
        onPress(down.position)
    }
    while (true) {
        val waited = if (!pressed) {
            val remaining = PressDelayMillis - ((currentEvent.changes.firstOrNull()?.uptimeMillis ?: down.uptimeMillis) - down.uptimeMillis)
            if (remaining <= 0L) {
                pressed = true
                onPress(last)
                null
            } else {
                withTimeoutOrNull(remaining) { awaitPointerEvent(PointerEventPass.Final) } ?: run {
                    pressed = true
                    onPress(last)
                    null
                }
            }
        } else {
            null
        }
        val event = waited ?: awaitPointerEvent(PointerEventPass.Final)
        val c = event.changes.firstOrNull { it.id == down.id } ?: break
        last = c.position
        if (!c.pressed) {
            if (pressed) {
                onRelease(0L)
            } else {
                onPress(last)
                onRelease(TapPulseMillis)
            }
            return
        }
        if (inScrollContainer && c.isConsumed && c.positionChanged()) {
            if (pressed) onRelease(0L)
            return
        }
        if (pressed) onMove(c.position)
    }
    if (pressed) onRelease(0L)
}

internal fun DelegatableNode.isInScrollContainer(): Boolean {
    val key = ScrollContainerKey ?: return false
    var found = false
    traverseAncestors(key) {
        found = true
        false
    }
    return found
}

private val ScrollContainerKey: Any? by lazy {
    runCatching {
        Class.forName("androidx.compose.foundation.gestures.ScrollableContainerNode").getField("TraverseKey").get(null)
    }.getOrNull()
}

private const val PressDelayMillis: Long = 90L
private const val TapPulseMillis: Long = 90L
