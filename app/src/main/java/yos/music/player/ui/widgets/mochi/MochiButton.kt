// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.tanh

enum class MochiButtonStyle { Filled, Tonal, Outlined, Text }

@Immutable
data class MochiButtonColors(
    val container: Color,
    val content: Color,
    val outline: Color,
    val pressedContainer: Color,
    val glow: Color,
    val disabledContainer: Color,
    val disabledContent: Color,
)

@Immutable
data class MochiButtonBehavior(
    val pressScale: Float = 1.08f,
    val follow: Dp = 4.dp,
    val cancelSlop: Dp = 48.dp,
    val maxGrow: Dp = 6.dp,
)

object MochiButtonDefaults {
    val ContentPadding: PaddingValues = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    val Height: Dp = 48.dp

    @Composable
    fun colors(style: MochiButtonStyle = MochiButtonStyle.Filled): MochiButtonColors {
        val cs = MaterialTheme.colorScheme
        val isDark = isFlamingoInDarkMode()
        val glowA = if (isDark) 0.16f else 0.45f
        return remember(style, cs, isDark) {
            when (style) {
                MochiButtonStyle.Filled -> MochiButtonColors(
                    container = cs.primary,
                    content = cs.onPrimary,
                    outline = Color.Transparent,
                    pressedContainer = Color.Transparent,
                    glow = Color.White.copy(alpha = if (isDark) 0.22f else 0.35f),
                    disabledContainer = cs.onSurface.copy(alpha = 0.12f),
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )

                MochiButtonStyle.Tonal -> MochiButtonColors(
                    container = cs.secondaryContainer,
                    content = cs.onSecondaryContainer,
                    outline = Color.Transparent,
                    pressedContainer = Color.Transparent,
                    glow = Color.White.copy(alpha = glowA),
                    disabledContainer = cs.onSurface.copy(alpha = 0.12f),
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )

                MochiButtonStyle.Outlined -> MochiButtonColors(
                    container = Color.Transparent,
                    content = cs.primary,
                    outline = cs.outlineVariant,
                    pressedContainer = cs.primary.copy(alpha = 0.10f),
                    glow = cs.primary.copy(alpha = 0.18f),
                    disabledContainer = Color.Transparent,
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )

                MochiButtonStyle.Text -> MochiButtonColors(
                    container = Color.Transparent,
                    content = cs.primary,
                    outline = Color.Transparent,
                    pressedContainer = cs.primary.copy(alpha = 0.10f),
                    glow = cs.primary.copy(alpha = 0.18f),
                    disabledContainer = Color.Transparent,
                    disabledContent = cs.onSurface.copy(alpha = 0.38f),
                )
            }
        }
    }

    fun behavior(
        pressScale: Float = 1.08f,
        follow: Dp = 4.dp,
        cancelSlop: Dp = 48.dp,
        maxGrow: Dp = 6.dp,
    ): MochiButtonBehavior = MochiButtonBehavior(pressScale, follow, cancelSlop, maxGrow)
}

/**
 * 源自 mochi-compose 的 [MochiButton]：G2 连续曲率胶囊 + 按下弹跳放大（宽按钮每边最多放大 maxGrow）+
 * 手指跟手位移形变 + 跟手径向高光 + 拖出取消点击。
 */
@Composable
fun MochiButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: MochiButtonStyle = MochiButtonStyle.Filled,
    colors: MochiButtonColors = MochiButtonDefaults.colors(style),
    cornerRadius: Dp? = null,
    contentPadding: PaddingValues = MochiButtonDefaults.ContentPadding,
    minHeight: Dp = MochiButtonDefaults.Height,
    behavior: MochiButtonBehavior = MochiButtonDefaults.behavior(),
    content: @Composable RowScope.() -> Unit,
) {
    ButtonCore(
        onClick = onClick,
        enabled = enabled,
        colors = colors,
        cornerRadius = cornerRadius,
        behavior = behavior,
        modifier = modifier.defaultMinSize(minHeight = minHeight),
    ) {
        Row(
            Modifier.padding(contentPadding),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
}

@Composable
private fun ButtonCore(
    onClick: () -> Unit,
    enabled: Boolean,
    colors: MochiButtonColors,
    cornerRadius: Dp?,
    behavior: MochiButtonBehavior,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    val visual = remember { ButtonVisual() }
    val glow = rememberMochiGlowState(remember(colors.glow) { MochiGlowSpec(colors.glow) })
    val scope = rememberCoroutineScope()
    val currentOnClick by rememberUpdatedState(onClick)
    val path = remember { G2PathCache() }
    val radiusPx = cornerRadius?.let { with(density) { it.toPx() } }
    val enabledProgress by animateFloatAsState(
        targetValue = if (enabled) 1f else 0f,
        animationSpec = tween(220),
        label = "mochiButtonEnabled",
    )
    val container = lerpColor(colors.disabledContainer, colors.container, enabledProgress)
    val contentColor = lerpColor(colors.disabledContent, colors.content, enabledProgress)

    visual.radius = radiusPx
    visual.pressScale = behavior.pressScale
    visual.maxGrowPx = if (behavior.maxGrow.isSpecified) with(density) { behavior.maxGrow.toPx() } else Float.POSITIVE_INFINITY
    visual.followPx = with(density) { behavior.follow.toPx() }

    Box(
        modifier
            .semantics {
                role = Role.Button
                if (!enabled) disabled()
                onClick {
                    if (enabled) {
                        currentOnClick()
                        true
                    } else {
                        false
                    }
                }
            }
            .onPlaced { visual.coords = it }
            .pointerInput(enabled, behavior.cancelSlop) {
                if (!enabled) return@pointerInput
                val slop = behavior.cancelSlop.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    visual.finger = down.position
                    visual.held = true
                    visual.start(scope)
                    scope.launch { glow.press(down.position, MochiGlowDefaults.radius(size.toSize(), this@pointerInput)) }
                    var click = false
                    try {
                        while (true) {
                            val e = awaitPointerEvent(PointerEventPass.Main)
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            if (c.isConsumed) break
                            visual.finger = c.position
                            glow.moveTo(c.position)
                            if (!c.pressed) {
                                val p = c.position
                                click = p.x > -slop && p.y > -slop && p.x < size.width + slop && p.y < size.height + slop
                                c.consume()
                                break
                            }
                            val final = awaitPointerEvent(PointerEventPass.Final)
                            if (final.changes.firstOrNull { it.id == down.id }?.isConsumed != false) break
                        }
                    } finally {
                        visual.held = false
                        glow.markReleased()
                        scope.launch { glow.release() }
                    }
                    if (click) currentOnClick()
                }
            }
            .graphicsLayer {
                val s = visual.scale
                val sh = visual.shift
                val w = size.width.coerceAtLeast(1f)
                val h = size.height.coerceAtLeast(1f)
                scaleX = s * (1f + abs(sh.x) / w * 0.6f)
                scaleY = s * (1f + abs(sh.y) / h * 0.6f)
                translationX = sh.x
                translationY = sh.y
            }
            .drawBehind {
                val maxR = min(size.width, size.height) / 2f
                val r = (radiusPx ?: (size.height / 2f)).coerceAtMost(maxR)
                val rect = Rect(Offset.Zero, size)
                // 全胶囊（r 贴近半高）时 G2 超椭圆会在左右两端挤出轻微尖角，改用 G1 真半圆端头
                val smoothing = if (r >= maxR - 0.5f) 0f else ControlSmoothing
                val p = path.get(rect, r, smoothing)
                if (container.alpha > 0f) drawPath(p, container)
                if (enabled && colors.pressedContainer.alpha > 0f) {
                    val k = visual.pressAmount
                    if (k > 0.01f) drawPath(p, colors.pressedContainer, alpha = k)
                }
                if (colors.outline.alpha > 0f) drawPath(p, colors.outline, style = Stroke(1.dp.toPx()))
                drawGlow(glow, Outline.Generic(p), glow.center)
            },
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides contentColor) { content() }
    }
}

@Stable
private class ButtonVisual {
    var scale by mutableFloatStateOf(1f)
        private set
    var shift by mutableStateOf(Offset.Zero)
        private set
    var held = false
    var finger = Offset.Zero
    var coords: LayoutCoordinates? by mutableStateOf(null)
    var radius: Float? = null
    var pressScale = 1.08f
    var maxGrowPx = Float.POSITIVE_INFINITY
    var followPx = 0f

    private val targetScale: Float
        get() {
            val w = coords?.takeIf { it.isAttached }?.size?.width ?: 0
            if (w <= 0 || maxGrowPx.isInfinite()) return pressScale
            return min(pressScale, 1f + 2f * maxGrowPx / w)
        }
    private var loop: Job? = null

    val pressAmount: Float get() = ((scale - 1f) / (targetScale - 1f).coerceAtLeast(1e-3f)).coerceIn(0f, 1f)

    fun start(scope: CoroutineScope) {
        if (loop?.isActive == true) return
        loop = scope.launch {
            val s = MochiFollowSpring(0.26f, 0.6f)
            val o = MochiFollowSpring(0.3f, 0.7f)
            s.snapTo(Offset(scale, 0f))
            o.snapTo(shift)
            while (true) {
                val dt = s.awaitFrameDelta()
                scale = s.step(Offset(if (held) targetScale else 1f, 0f), dt).x
                shift = o.step(shiftTarget(), dt)
                if (!held && abs(scale - 1f) < 0.0005f && abs(s.velocity.x) < 0.01f &&
                    shift.getDistance() < 0.05f && o.velocity.getDistance() < 1f
                ) {
                    scale = 1f
                    shift = Offset.Zero
                    break
                }
            }
        }
    }

    private fun shiftTarget(): Offset {
        val c = coords ?: return Offset.Zero
        if (!held) return Offset.Zero
        val w = c.size.width / 2f
        val h = c.size.height / 2f
        return Offset(
            followPx * tanh((finger.x - w) / w.coerceAtLeast(1f)),
            followPx * tanh((finger.y - h) / h.coerceAtLeast(1f)),
        )
    }
}
