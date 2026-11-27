// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp as lerpColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.capsule.Continuity
import com.kyant.capsule.ContinuousCapsule
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.statusBarsPadding
import kotlin.math.abs
import kotlin.math.roundToInt

/** 全局 Mochi 顶部胶囊提示入口（由 MainActivity 根部提供）。 */
val LocalMochiToast = staticCompositionLocalOf<MochiToastState?> { null }

/** Kyant G2 连续曲率胶囊形状，与 mochi-compose 的 `MochiCapsule` 一致。 */
internal val MochiCapsule: Shape = ContinuousCapsule(Continuity.Default)

/** 一条顶部胶囊提示。 */
@Immutable
class MochiToastData internal constructor(
    val title: String,
    val subtitle: String?,
    val icon: (@Composable (tint: Color) -> Unit)?,
    val durationMillis: Long,
    val actionLabel: String?,
    val onAction: (() -> Unit)?,
    val onClick: (() -> Unit)?,
    internal val id: Long,
)

/** 提示状态：在任何地方调用 [show]，由根部的 [MochiToastHost] 显示。 */
@Stable
class MochiToastState internal constructor() {
    internal var current: MochiToastData? by mutableStateOf(null)
    private var seq = 0L

    fun show(
        title: String,
        subtitle: String? = null,
        icon: (@Composable (tint: Color) -> Unit)? = null,
        durationMillis: Long = 2200,
        actionLabel: String? = null,
        onAction: (() -> Unit)? = null,
        onClick: (() -> Unit)? = null,
    ) {
        current = MochiToastData(title, subtitle, icon, durationMillis, actionLabel, onAction, onClick, ++seq)
    }

    fun dismiss() {
        current = null
    }
}

@Composable
fun rememberMochiToastState(): MochiToastState = remember { MochiToastState() }

@Immutable
data class MochiToastColors(
    val container: Color,
    val content: Color,
    val secondary: Color,
    val glow: Color,
    val hairline: Color,
)

object MochiToastDefaults {
    val IslandWidth: Dp = 120.dp
    val IslandHeight: Dp = 36.dp

    @Composable
    fun colors(
        container: Color = if (isFlamingoInDarkMode()) Color(0xFF2C2C2E) else Color(0xFF1C1C1E),
        content: Color = toastContentColor(container, Color.White),
        secondary: Color = content.copy(alpha = 0.80f),
        glow: Color = Color.White.copy(alpha = 0.22f),
        hairline: Color = Color.White.copy(alpha = if (isFlamingoInDarkMode()) 0.14f else 0.08f),
    ): MochiToastColors = remember(container, content, secondary, glow, hairline) {
        MochiToastColors(container, content, secondary, glow, hairline)
    }
}

private fun toastContentColor(container: Color, preferred: Color): Color {
    fun contrast(a: Color, b: Color): Float {
        val x = a.luminance()
        val y = b.luminance()
        return (maxOf(x, y) + 0.05f) / (minOf(x, y) + 0.05f)
    }
    val base = preferred.copy(alpha = 1f)
    if (contrast(base, container) >= 12f) return base
    val extreme = if (contrast(Color.White, container) >= contrast(Color.Black, container)) Color.White else Color.Black
    if (contrast(extreme, container) < 12f) return extreme
    var low = 0f
    var high = 1f
    repeat(16) {
        val mid = (low + high) / 2f
        if (contrast(lerpColor(base, extreme, mid), container) >= 12f) high = mid else low = mid
    }
    return lerpColor(base, extreme, high)
}

/**
 * 源自 mochi-compose 的 iOS 风格顶部胶囊提示（像灵动岛长出来的顶部 G2 平滑圆角胶囊 + 模糊显隐）。
 */
@Composable
fun MochiToastHost(
    state: MochiToastState,
    modifier: Modifier = Modifier,
    colors: MochiToastColors = MochiToastDefaults.colors(),
) {
    var shown by remember { mutableStateOf<MochiToastData?>(null) }
    val presence = remember { Animatable(0f) }
    val departure = remember { Animatable(0f) }
    val drag = remember { Animatable(0f) }
    var held by remember { mutableStateOf(false) }
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)

    LaunchedEffect(state.current) {
        val t = state.current
        if (t != null) {
            shown = t
            haptics.tick()
            coroutineScope {
                launch { drag.animateTo(0f, spring(dampingRatio = 1f, stiffness = 500f, visibilityThreshold = 0.5f)) }
                launch { departure.animateTo(0f, tween(160)) }
                launch { presence.animateTo(1f, MochiMotions.responseSpring(0.4f, 0.9f)) }
            }
            var left = t.durationMillis
            while (left > 0) {
                if (held) {
                    snapshotFlow { held }.first { !it }
                    left = left.coerceAtLeast(800)
                }
                delay(100)
                left -= 100
            }
            if (state.current?.id == t.id) state.current = null
        } else if (shown != null) {
            departure.animateTo(1f, tween(190, easing = FastOutLinearInEasing))
            shown = null
            presence.snapTo(0f)
            departure.snapTo(0f)
            drag.snapTo(0f)
        }
    }

    val toast = shown ?: return
    InWindowOverlay(leaving = { state.current?.id != toast.id }) {
        DisposableEffect(toast.id) { onDispose { held = false } }
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val glow = rememberMochiGlowState(remember(colors.glow) { MochiGlowSpec(colors.glow) })
        val islandW = with(density) { MochiToastDefaults.IslandWidth.toPx() }
        val islandH = with(density) { MochiToastDefaults.IslandHeight.toPx() }
        val contentBlur: () -> Dp = {
            val p = smoothstep(0.10f, 0.78f, presence.value)
            val e = departure.value.coerceIn(0f, 1f)
            8.dp * ((1f - p) + e).coerceIn(0f, 1f)
        }

        Box(
            modifier
                .fillMaxSize()
                .statusBarsPadding()
                .padding(top = 6.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            Layout(
                content = {
                    Row(
                        Modifier
                            .widthIn(max = 368.dp)
                            .height(IntrinsicSize.Min)
                            .padding(
                                end = if (toast.actionLabel != null && toast.onAction != null) 12.dp else 20.dp,
                                top = 10.dp,
                                bottom = 10.dp,
                            )
                            .graphicsLayer {
                                val p = smoothstep(0.10f, 0.65f, presence.value)
                                val e = departure.value.coerceIn(0f, 1f)
                                mochiBlurAlpha(p * (1f - e), contentBlur())
                            }
                            .mochiBlur(radius = contentBlur),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        MochiBlurCrossfade(
                            toast.icon,
                            modifier = Modifier.fillMaxHeight(),
                            contentAlignment = Alignment.Center,
                        ) { icon ->
                            if (icon != null) {
                                Box(
                                    Modifier
                                        .padding(start = 10.dp, end = 12.dp)
                                        .fillMaxHeight()
                                        .defaultMinSize(minHeight = 28.dp)
                                        .aspectRatio(1f, matchHeightConstraintsFirst = true),
                                    contentAlignment = Alignment.Center,
                                ) { icon(colors.content) }
                            } else {
                                Box(Modifier.width(20.dp))
                            }
                        }
                        Column(Modifier.weight(1f, fill = false)) {
                            MochiBlurText(
                                toast.title,
                                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
                                color = colors.content,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            MochiBlurCrossfade(toast.subtitle, contentAlignment = Alignment.CenterStart) { subtitle ->
                                if (subtitle != null) {
                                    Text(
                                        text = subtitle,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = colors.secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                            }
                        }
                        MochiBlurCrossfade(
                            targetState = if (toast.actionLabel != null && toast.onAction != null) {
                                toast.actionLabel to toast.onAction
                            } else null,
                            contentAlignment = Alignment.CenterEnd,
                        ) { action ->
                            if (action != null) {
                                Box(
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .mochiPressDeform(scale = 0.94f, follow = 2.dp)
                                        .clip(MochiCapsule)
                                        .background(colors.content.copy(alpha = 0.16f))
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                        ) {
                                            haptics.confirm()
                                            action.second.invoke()
                                            state.dismiss()
                                        }
                                        .padding(horizontal = 12.dp, vertical = 6.dp),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = action.first,
                                        style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                                        color = colors.content,
                                        maxLines = 1,
                                    )
                                }
                            }
                        }
                    }
                },
                modifier = Modifier
                    .mochiPressDeform(scale = 1.04f, follow = 4.dp)
                    .graphicsLayer {
                        val p = presence.value.coerceIn(0f, 1f)
                        val exit = departure.value.coerceIn(0f, 1f)
                        val scale = lerp(0.98f, 1f, p) * (1f - 0.035f * exit)
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0.5f, 0f)
                        translationY = drag.value - 10.dp.toPx() * (1f - p) - 18.dp.toPx() * exit
                        alpha = smoothstep(0f, 0.2f, p) * (1f - exit)
                        shadowElevation = 14.dp.toPx() * p * (1f - exit)
                        shape = MochiCapsule
                        clip = true
                    }
                    .background(colors.container, MochiCapsule)
                    .border(0.5.dp, colors.hairline, MochiCapsule)
                    .mochiToastPressGlow(glow, MochiCapsule)
                    .pointerInput(toast.id) {
                        val dismissPx = 24.dp.toPx()
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            held = true
                            val downTime = down.uptimeMillis
                            var lastTime = downTime
                            var lastY = down.position.y
                            var dy = 0f
                            var velocityY = 0f
                            var moved = false
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                val stepDy = c.position.y - c.previousPosition.y
                                dy += stepDy
                                val dtSec = ((c.uptimeMillis - lastTime).coerceAtLeast(1L)) / 1000f
                                if (c.uptimeMillis > lastTime) {
                                    velocityY = (c.position.y - lastY) / dtSec
                                    lastY = c.position.y
                                    lastTime = c.uptimeMillis
                                }
                                if (!moved && abs(dy) > viewConfiguration.touchSlop) moved = true
                                val shownDy = if (dy < 0f) dy else rubberBand(dy, size.height.coerceAtLeast(1) * 3f, 14.dp.toPx())
                                scope.launch { drag.snapTo(shownDy) }
                                c.consume()
                                if (!c.pressed) break
                            }
                            held = false
                            if (moved && (dy < -dismissPx || velocityY < -600f)) {
                                state.dismiss()
                            } else if (!moved) {
                                toast.onClick?.invoke()
                                state.dismiss()
                            } else {
                                scope.launch { drag.animateTo(0f, MochiMotions.responseSpring(0.36f, 0.62f), initialVelocity = velocityY) }
                            }
                        }
                    },
            ) { ms, c ->
                val p = ms.first().measure(Constraints(maxWidth = c.maxWidth))
                val k = presence.value.coerceAtLeast(0f)
                val w = lerp(islandW, p.width.toFloat(), k).roundToInt().coerceAtLeast(1)
                val h = lerp(islandH, p.height.toFloat(), k).roundToInt().coerceAtLeast(1)
                layout(w, h) { p.place((w - p.width) / 2, (h - p.height) / 2) }
            }
        }
    }
}

private fun Modifier.mochiToastPressGlow(
    glow: MochiGlowState,
    shape: Shape,
): Modifier = this
    .pointerInput(glow) {
        coroutineScope {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val radius = MochiGlowDefaults.radius(
                    androidx.compose.ui.geometry.Size(size.width.toFloat(), size.height.toFloat()),
                    this,
                )
                val pressJob = launch { glow.press(down.position, radius) }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    glow.moveTo(change.position)
                    if (!change.pressed) break
                }
                pressJob.cancel()
                launch { glow.release() }
            }
        }
    }
    .drawWithContent {
        drawContent()
        if (glow.intensity > 0.01f) {
            val outline = shape.createOutline(size, layoutDirection, this)
            drawGlow(glow, outline, glow.center)
        }
    }
