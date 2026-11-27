// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.TargetBasedAnimation
import androidx.compose.animation.core.VectorConverter
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import yos.music.player.ui.widgets.liquid.claimHorizontalDragGestures
import yos.music.player.ui.widgets.liquid.claimVerticalDragGestures
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** 同层级页面过渡的纯几何与手势判定辅助（供单测与运行期共用）。 */
internal object MochiPeerTransitionMath {
    fun scaleForCamera(
        camera: Float,
        origin: Float,
        goal: Float,
        minScale: Float,
        shrinkDistance: Float,
    ): Float {
        val d = min(abs(camera - origin), abs(camera - goal))
        return 1f - (1f - minScale) * smoothstep(0f, shrinkDistance, d)
    }

    fun retargetOrigin(
        camera: Float,
        currentOrigin: Float,
        currentGoal: Float,
        newGoal: Float,
    ): Float {
        val d = min(abs(camera - currentOrigin), abs(camera - currentGoal))
        val dir = if (newGoal >= camera) 1f else -1f
        return camera - dir * d
    }

    fun shouldCommitSwipe(
        offset: Float,
        velocityPagesPerSec: Float,
        hasNeighbor: Boolean,
        flingThreshold: Float = FlingPagesPerSecond,
    ): Boolean = hasNeighbor && (
        abs(offset) > 0.5f ||
            (abs(velocityPagesPerSec) > flingThreshold && sign(velocityPagesPerSec) == sign(offset))
        )

    fun resolveTargetSlot(
        camera: Float,
        startAnchorSlot: Int,
        gestureDelta: Float,
        velocityPagesPerSec: Float,
        minSlot: Int,
        maxSlot: Int,
        flingThreshold: Float = FlingPagesPerSecond,
    ): Int {
        if (minSlot >= maxSlot) return minSlot
        val isFling = abs(velocityPagesPerSec) > flingThreshold &&
            gestureDelta != 0f &&
            sign(velocityPagesPerSec) == sign(gestureDelta)
        val raw = if (isFling) {
            if (velocityPagesPerSec > 0f) {
                maxOf(floor(camera).toInt() + 1, startAnchorSlot + 1)
            } else {
                minOf(ceil(camera).toInt() - 1, startAnchorSlot - 1)
            }
        } else {
            camera.roundToInt()
        }
        return raw.coerceIn(minSlot, maxSlot)
    }
}

/** 同层级页面过渡的样子 */
enum class MochiPeerTransitionStyle {
    /** 两页并排一起滑过去，途中缩小、中间一条窄缝 */
    Slide,

    /** 原地景深：前一页往后退（缩小）淡出，后一页从后面往前（放大到位）淡入 */
    Depth,
}

/**
 * 同层级页面过渡的手感。
 *
 * @param style 并排滑动还是原地景深淡入淡出
 * @param minScale [MochiPeerTransitionStyle.Slide]：途中两页缩到多小；[MochiPeerTransitionStyle.Depth]：退到最远时的大小
 * @param gap 两页之间那条缝的宽度（按缩小后的页面算，只用于 Slide）
 * @param shrinkDistance 离起点 / 终点多远（单位是「一页」）时缩到最小：小 = 一推就收小、快到位才放大（只用于 Slide）
 * @param spring 切换进度的弹簧
 */
@Immutable
data class MochiPeerTransitionSpec(
    val minScale: Float = 0.92f,
    val gap: Dp = 8.dp,
    val shrinkDistance: Float = 0.35f,
    val spring: AnimationSpec<Float> = MochiMotions.responseSpring(0.5f, 0.88f),
    val style: MochiPeerTransitionStyle = MochiPeerTransitionStyle.Slide,
) {
    companion object {
        /** 景深淡入淡出的默认手感 */
        val Depth: MochiPeerTransitionSpec = MochiPeerTransitionSpec(
            minScale = 0.94f,
            spring = MochiMotions.responseSpring(0.42f, 1f),
            style = MochiPeerTransitionStyle.Depth,
        )
    }
}

/**
 * 源自 mochi-compose 的同层级页面过渡 [MochiPeerTransition]：
 * 支持水平或垂直方向（[orientation]），两页并排一起滑过去，途中缩小、中间一条窄缝，
 * 快到位时新页放大铺满；支持跟手拖拽与速度接管，并可选两端渐变遮罩（[edgeFadeExtent]）。
 */
@Composable
fun <T : Any> MochiPeerTransition(
    targetState: T,
    modifier: Modifier = Modifier,
    pages: List<T>? = null,
    onPageChange: (T) -> Unit = {},
    direction: (from: T, to: T) -> Int = { from, to ->
        if (pages != null) pages.indexOf(to).compareTo(pages.indexOf(from)) else 1
    },
    orientation: Orientation = Orientation.Horizontal,
    background: Color = Color.Transparent,
    backdrop: Color = Color.Transparent,
    cornerRadius: Dp = 24.dp,
    edgeFadeExtent: Dp = 0.dp,
    spec: MochiPeerTransitionSpec = MochiPeerTransitionSpec(),
    retainPages: Boolean = false,
    content: @Composable (T) -> Unit,
) {
    val retained = remember { LinkedHashSet<T>() }
    val holder = rememberSaveableStateHolder()
    val scope = rememberCoroutineScope()
    val framePeriod = rememberFramePeriodNanos()
    val currentDirection by rememberUpdatedState(direction)
    val currentPages by rememberUpdatedState(pages)
    val currentOnPageChange by rememberUpdatedState(onPageChange)
    val currentTarget by rememberUpdatedState(targetState)
    val depth = spec.style == MochiPeerTransitionStyle.Depth

    val layers = remember { mutableStateListOf(PeerLayer(targetState, 0)) }
    val camera = remember { Animatable(0f) }
    var origin by remember { mutableFloatStateOf(0f) }
    var goal by remember { mutableFloatStateOf(0f) }
    var handoffVelocity by remember { mutableFloatStateOf(0f) }
    var cameraVelocity by remember { mutableFloatStateOf(0f) }
    var warming by remember { mutableStateOf<Any?>(null) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var animJob by remember { mutableStateOf<Job?>(null) }
    var isDragging by remember { mutableStateOf(false) }

    suspend fun glide(to: Float, v: Float) {
        camera.animateToSteady(to, spec.spring, v, framePeriod) { cameraVelocity = it }
    }

    fun retarget(newGoal: Float) {
        origin = MochiPeerTransitionMath.retargetOrigin(camera.value, origin, goal, newGoal)
        goal = newGoal
    }

    fun settle(target: PeerLayer<T>) {
        layers.removeAll { it !== target }
        origin = target.slot.toFloat()
        goal = origin
    }

    suspend fun depthTo(target: PeerLayer<T>, handoff: Float, warm: Boolean = false) {
        coroutineScope {
            fun go(l: PeerLayer<T>) {
                val to = if (l === target) 1f else 0f
                val v0 = if (handoff != 0f) (if (l === target) handoff else -handoff) else l.velocity
                if (l.vis.value != to) {
                    launch { l.vis.animateToSteady(to, spec.spring, v0, framePeriod) { l.velocity = it } }
                }
            }
            for (l in layers.toList()) if (l !== target) go(l)
            if (warm) {
                warming = target.key
                try {
                    awaitFrameSettle(framePeriod)
                } finally {
                    warming = null
                }
            }
            go(target)
        }
        if (!isDragging && target.key == currentTarget && target.vis.value == 1f) {
            layers.removeAll { it !== target }
        }
    }

    LaunchedEffect(targetState) {
        if (depth) {
            val existing = layers.firstOrNull { it.key == targetState }
            val target = existing ?: PeerLayer(targetState, 0, visible = 0f).also { layers.add(it) }
            if (layers.last() !== target) {
                layers.remove(target)
                layers.add(target)
            }
            val fresh = existing == null && handoffVelocity == 0f
            if (fresh && layers.size == 2) {
                warming = targetState
                try {
                    awaitFrameSettle(framePeriod)
                } finally {
                    warming = null
                }
            }
            val v = handoffVelocity
            handoffVelocity = 0f
            animJob?.cancel()
            animJob = scope.launch {
                depthTo(target, v, warm = fresh && layers.size > 2)
            }
            return@LaunchedEffect
        }

        val existing = layers.firstOrNull { it.key == targetState }
        val target = existing ?: run {
            val front = layers.minBy { abs(it.slot - goal) }
            val dir = if (currentDirection(front.key, targetState) < 0) -1 else 1
            val slot = if (dir > 0) layers.maxOf { it.slot } + 1 else layers.minOf { it.slot } - 1
            PeerLayer(targetState, slot).also { layers.add(it) }
        }
        if (existing == null && layers.size == 2 && handoffVelocity == 0f) {
            warming = targetState
            try {
                awaitFrameSettle(framePeriod)
            } finally {
                warming = null
            }
        }
        val v = if (handoffVelocity != 0f) handoffVelocity else cameraVelocity
        handoffVelocity = 0f
        animJob?.cancel()
        animJob = scope.launch {
            if (abs(camera.value - target.slot) > 0.0005f || layers.size > 1) {
                retarget(target.slot.toFloat())
                glide(target.slot.toFloat(), v)
            }
            if (!isDragging && target.key == currentTarget) {
                settle(target)
            }
        }
    }

    fun scale(): Float =
        MochiPeerTransitionMath.scaleForCamera(camera.value, origin, goal, spec.minScale, spec.shrinkDistance)

    fun pitch(extent: Float, density: Float): Float = extent * scale() + spec.gap.value * density

    fun pitchOf(extent: Float, density: Float): Float = if (depth) extent else pitch(extent, density)

    val swipe = if (pages == null) {
        Modifier
    } else {
        Modifier.pointerInput(orientation) {
            val tracker = VelocityTracker()
            var pointerPos = Offset.Zero
            var accumAxis = 0f
            var didDrag = false
            var dragCurrent: PeerLayer<T>? = null
            var dragIndex = -1
            var neighbor: PeerLayer<T>? = null
            var baseSlot = 0
            var baseIndex = -1
            var startAnchorSlot = 0
            var startCamera = 0f
            var rawTrack = 0f
            var minTrackSlot = 0
            var maxTrackSlot = 0

            fun extentPx(): Float {
                val raw = if (orientation == Orientation.Vertical) {
                    viewportSize.height.toFloat()
                } else {
                    viewportSize.width.toFloat()
                }
                return raw.coerceAtLeast(1f)
            }

            fun ensureSlideLayers(all: List<T>, targetCam: Float) {
                val lo = floor(targetCam).toInt()
                val hi = ceil(targetCam).toInt()
                layers.removeAll { it.slot < lo - 1 || it.slot > hi + 1 }
                for (s in lo..hi) {
                    val pageIdx = baseIndex + (s - baseSlot)
                    if (pageIdx in all.indices) {
                        val pageKey = all[pageIdx]
                        if (layers.none { it.key == pageKey }) {
                            layers.add(PeerLayer(pageKey, s, visible = 1f))
                        }
                    }
                }
            }

            fun followDepth(all: List<T>, current: PeerLayer<T>, index: Int) {
                val dir = if (accumAxis < 0f) 1 else -1
                val next = all.getOrNull(index + dir)
                if (next != null && neighbor?.key != next) {
                    neighbor?.let { layers.remove(it) }
                    neighbor = PeerLayer(next, current.slot + dir, visible = 0f).also {
                        layers.add(it)
                    }
                } else if (next == null && neighbor != null) {
                    neighbor?.let { layers.remove(it) }
                    neighbor = null
                }
                goal = (current.slot + if (next != null) dir else 0).toFloat()
                val p = pitchOf(extentPx(), density)
                val raw = -accumAxis / p
                val pos = if (next == null) rubberBand(raw, 1f, 0.12f) else raw.coerceIn(-1f, 1f)
                val n = neighbor
                scope.launch(start = CoroutineStart.UNDISPATCHED) { current.vis.snapTo(1f - abs(pos)) }
                if (n != null) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) { n.vis.snapTo(abs(pos)) }
                }
            }

            fun handleStart(downPos: Offset) {
                didDrag = false
                accumAxis = 0f
                dragCurrent = null
                dragIndex = -1
                neighbor = null
                pointerPos = downPos
                tracker.resetTracking()
                tracker.addPosition(SystemClock.uptimeMillis(), pointerPos)
            }

            fun handleDrag(delta: Offset) {
                val all = currentPages ?: return
                if (!didDrag) {
                    if (depth) {
                        val current = layers.maxByOrNull { it.vis.value } ?: return
                        val index = all.indexOf(current.key)
                        if (index < 0) return
                        animJob?.cancel()
                        animJob = null
                        isDragging = true
                        didDrag = true
                        dragCurrent = current
                        dragIndex = index
                        origin = current.slot.toFloat()
                    } else {
                        val baseLayer = layers.minByOrNull { abs(it.slot - camera.value) } ?: return
                        val index = all.indexOf(baseLayer.key)
                        if (index < 0) return
                        animJob?.cancel()
                        animJob = null
                        isDragging = true
                        didDrag = true
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { camera.stop() }
                        cameraVelocity = 0f
                        baseSlot = baseLayer.slot
                        baseIndex = index
                        startAnchorSlot = layers.firstOrNull { it.key == currentTarget }?.slot ?: baseSlot
                        startCamera = camera.value
                        rawTrack = startCamera
                        minTrackSlot = baseSlot - baseIndex
                        maxTrackSlot = baseSlot + (all.lastIndex - baseIndex)
                        layers.removeAll { layer ->
                            layer !== baseLayer &&
                                all.getOrNull(baseIndex + (layer.slot - baseSlot)) != layer.key
                        }
                    }
                }
                pointerPos += delta
                tracker.addPosition(SystemClock.uptimeMillis(), pointerPos)
                val deltaAxis = if (orientation == Orientation.Vertical) delta.y else delta.x
                accumAxis += deltaAxis

                if (depth) {
                    val current = dragCurrent ?: return
                    followDepth(all, current, dragIndex)
                    return
                }

                val p = pitchOf(extentPx(), density)
                val deltaCam = -deltaAxis / p
                rawTrack += deltaCam
                val targetCam = when {
                    rawTrack < minTrackSlot -> minTrackSlot + rubberBand(rawTrack - minTrackSlot, 1f, 0.12f)
                    rawTrack > maxTrackSlot -> maxTrackSlot + rubberBand(rawTrack - maxTrackSlot, 1f, 0.12f)
                    else -> rawTrack
                }
                ensureSlideLayers(all, targetCam)
                val newGoal = when {
                    deltaCam > 0f -> (floor(targetCam).toInt() + 1).coerceIn(minTrackSlot, maxTrackSlot).toFloat()
                    deltaCam < 0f -> (ceil(targetCam).toInt() - 1).coerceIn(minTrackSlot, maxTrackSlot).toFloat()
                    else -> goal
                }
                if (newGoal != goal) {
                    retarget(newGoal)
                }
                scope.launch(start = CoroutineStart.UNDISPATCHED) { camera.snapTo(targetCam) }
            }

            fun handleEnd(cancelled: Boolean) {
                if (!didDrag) return
                isDragging = false
                if (depth) {
                    val current = dragCurrent ?: return
                    val n = neighbor
                    if (cancelled) {
                        animJob?.cancel()
                        animJob = scope.launch {
                            current.velocity = 0f
                            n?.velocity = 0f
                            depthTo(current, 0f)
                        }
                        return
                    }
                    val velocity = tracker.calculateVelocity()
                    val vPx = if (orientation == Orientation.Vertical) velocity.y else velocity.x
                    val v = (-vPx / pitchOf(extentPx(), density)).coerceIn(-12f, 12f)
                    val offset = (n?.vis?.value ?: (1f - current.vis.value)) * (if (accumAxis < 0f) 1f else -1f)
                    val go = MochiPeerTransitionMath.shouldCommitSwipe(offset, v, n != null)
                    if (go && n != null) {
                        handoffVelocity = v * sign(offset)
                        currentOnPageChange(n.key)
                    } else {
                        animJob?.cancel()
                        animJob = scope.launch {
                            current.velocity = -v * sign(offset)
                            n?.velocity = v * sign(offset)
                            depthTo(current, 0f)
                        }
                    }
                    return
                }

                val all = currentPages ?: return
                val velocity = tracker.calculateVelocity()
                val vPx = if (orientation == Orientation.Vertical) velocity.y else velocity.x
                val v = (-vPx / pitchOf(extentPx(), density)).coerceIn(-12f, 12f)
                val endV = if (cancelled) 0f else v
                val targetSlot = if (cancelled) {
                    camera.value.roundToInt().coerceIn(minTrackSlot, maxTrackSlot)
                } else {
                    MochiPeerTransitionMath.resolveTargetSlot(
                        camera = camera.value,
                        startAnchorSlot = startAnchorSlot,
                        gestureDelta = rawTrack - startCamera,
                        velocityPagesPerSec = v,
                        minSlot = minTrackSlot,
                        maxSlot = maxTrackSlot,
                    )
                }
                val targetPageIdx = (baseIndex + (targetSlot - baseSlot)).coerceIn(0, all.lastIndex)
                val targetKey = all[targetPageIdx]
                val targetLayer = layers.firstOrNull { it.key == targetKey }
                    ?: PeerLayer(targetKey, targetSlot, visible = 1f).also { layers.add(it) }

                animJob?.cancel()
                if (targetKey != currentTarget) {
                    handoffVelocity = endV
                    currentOnPageChange(targetKey)
                } else {
                    animJob = scope.launch {
                        retarget(targetSlot.toFloat())
                        glide(targetSlot.toFloat(), endV)
                        if (!isDragging && currentTarget == targetKey) {
                            settle(targetLayer)
                        }
                    }
                }
            }

            if (orientation == Orientation.Vertical) {
                claimVerticalDragGestures(
                    canDrag = { dy ->
                        val all = currentPages
                        if (all == null || all.size <= 1) {
                            false
                        } else {
                            val idx = all.indexOf(currentTarget).takeIf { it >= 0 }
                                ?: layers.minByOrNull { abs(it.slot - camera.value) }?.let { all.indexOf(it.key) }
                                ?: -1
                            when {
                                idx < 0 -> false
                                dy < 0f -> idx < all.lastIndex || (!depth && layers.any { it.slot > camera.value + 0.01f })
                                dy > 0f -> idx > 0 || (!depth && layers.any { it.slot < camera.value - 0.01f })
                                else -> false
                            }
                        }
                    },
                    onStart = { down -> handleStart(down.position) },
                    onDrag = { delta -> handleDrag(delta) },
                    onEnd = { handleEnd(cancelled = false) },
                    onCancel = { handleEnd(cancelled = true) },
                )
            } else {
                claimHorizontalDragGestures(
                    onStart = { down -> handleStart(down.position) },
                    onDrag = { delta -> handleDrag(delta) },
                    onEnd = { handleEnd(cancelled = false) },
                    onCancel = { handleEnd(cancelled = true) },
                )
            }
        }
    }

    val fadeModifier = if (edgeFadeExtent > 0.dp) {
        val fadeEdges = if (orientation == Orientation.Vertical) {
            MochiFadeEdges.vertical(edgeFadeExtent)
        } else {
            MochiFadeEdges.horizontal(edgeFadeExtent)
        }
        Modifier.mochiFadeMask(
            edges = fadeEdges,
            amount = MochiFadeAmount {
                val dist = if (depth) {
                    layers.minOfOrNull { 1f - it.vis.value } ?: 0f
                } else {
                    layers.minOfOrNull { abs(camera.value - it.slot) } ?: 0f
                }
                smoothstep(0f, 0.03f, dist)
            },
        )
    } else {
        Modifier
    }

    Box(
        modifier
            .onSizeChanged { viewportSize = it }
            .then(swipe)
            .then(fadeModifier)
            .drawBehind {
                if (depth) {
                    if (layers.size > 1 && background.alpha > 0f) drawRect(background)
                } else if (backdrop.alpha > 0f && scale() < 0.999f) {
                    drawRect(backdrop)
                }
            },
    ) {
        if (!retainPages) retained.retainAll(layers.map { it.key }.toSet())
        layers.forEach { retained.add(it.key) }
        val shown = retained.toList()
        Layout(
            content = {
                for (k in shown) {
                    key(k) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val layer = layers.firstOrNull { it.key == k } ?: return@graphicsLayer
                                    val moving = layers.size > 1 ||
                                        (if (depth) layer.vis.value < 0.9995f else abs(camera.value - layer.slot) > 0.0005f)
                                    compositingStrategy = if (moving) CompositingStrategy.Offscreen else CompositingStrategy.Auto
                                    if (depth) {
                                        val v = layer.vis.value.coerceIn(0f, 1f)
                                        val s = spec.minScale + (1f - spec.minScale) * v
                                        scaleX = s
                                        scaleY = s
                                        alpha = smoothstep(DepthFadeStart, 1f, v)
                                            .coerceAtLeast(if (warming == k) WarmAlpha else 0f)
                                        translationX = 0f
                                        translationY = 0f
                                        clip = false
                                        return@graphicsLayer
                                    }
                                    val s = scale()
                                    if (orientation == Orientation.Vertical) {
                                        translationX = 0f
                                        translationY = (layer.slot - camera.value) * (size.height * s + spec.gap.toPx())
                                    } else {
                                        translationX = (layer.slot - camera.value) * (size.width * s + spec.gap.toPx())
                                        translationY = 0f
                                    }
                                    scaleX = s
                                    scaleY = s
                                    val round = ((1f - s) / (1f - spec.minScale)).coerceIn(0f, 1f)
                                    if (background.alpha > 0f && round > 0.001f) {
                                        clip = true
                                        val r = cornerRadius.toPx().coerceAtLeast(MinCorner.toPx()) * round
                                        shape = G2RectClipShape4(Rect(Offset.Zero, size), r, r, r, r)
                                    } else {
                                        clip = false
                                    }
                                }
                                .drawWithContent {
                                    if (background.alpha > 0f) drawRect(background)
                                    drawContent()
                                },
                        ) {
                            holder.SaveableStateProvider(k) { content(k) }
                        }
                    }
                }
            },
        ) { measurables, constraints ->
            val placeables = measurables.map { it.measure(constraints) }
            layout(constraints.maxWidth, constraints.maxHeight) {
                for (layer in layers) {
                    val i = shown.indexOf(layer.key)
                    if (i >= 0) placeables[i].place(0, 0)
                }
            }
        }
    }
}

private class PeerLayer<T>(val key: T, val slot: Int, visible: Float = 1f) {
    val vis = Animatable(visible)
    var velocity = 0f
}

private suspend fun awaitFrameSettle(framePeriodNanos: Long) {
    val steady = framePeriodNanos * 3 / 2
    val start = withFrameNanos { it }
    var last = start
    var frames = 0
    while (true) {
        val t = withFrameNanos { it }
        frames++
        val dt = t - last
        last = t
        if (frames >= MinFrames && (dt <= steady || t - start >= MaxSettleNanos)) return
    }
}

private suspend fun Animatable<Float, AnimationVector1D>.animateToSteady(
    target: Float,
    spec: AnimationSpec<Float>,
    initialVelocity: Float,
    framePeriodNanos: Long,
    onVelocity: (Float) -> Unit,
) {
    val anim = TargetBasedAnimation(spec, Float.VectorConverter, value, target, initialVelocity)
    val maxStep = framePeriodNanos * MaxStepFrames
    var play = 0L
    var last = withFrameNanos { it }
    while (true) {
        val t = withFrameNanos { it }
        play += (t - last).coerceIn(0L, maxStep)
        last = t
        if (anim.isFinishedFromNanos(play)) {
            snapTo(target)
            onVelocity(0f)
            return
        }
        snapTo(anim.getValueFromNanos(play))
        onVelocity(anim.getVelocityVectorFromNanos(play).value)
    }
}

@Composable
private fun rememberFramePeriodNanos(): Long {
    val view = LocalView.current
    return remember(view) {
        @Suppress("DEPRECATION")
        val hz = view.display?.refreshRate ?: 60f
        (1_000_000_000.0 / hz.coerceIn(30f, 240f)).toLong()
    }
}

private val MinCorner = 24.dp
private const val DepthFadeStart = 0.35f
private const val WarmAlpha = 1f / 255f
private const val FlingPagesPerSecond = 1.2f
private const val MaxStepFrames = 2L
private const val MinFrames = 2
private const val MaxSettleNanos = 160_000_000L
