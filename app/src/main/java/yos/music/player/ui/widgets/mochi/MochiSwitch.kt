package yos.music.player.ui.widgets.mochi

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.mochi.lerp as mochiLerp

/** 开关配色 */
@Immutable
data class MochiSwitchColors(
    val checkedTrack: Color,
    val uncheckedTrack: Color,
    val thumb: Color,
    val thumbShadow: Color,
)

object MochiSwitchDefaults {
    /** iOS 的系统绿 */
    val IosGreen: Color = Color(0xFF34C759)

    @Composable
    fun colors(
        checkedTrack: Color = MaterialTheme.colorScheme.primary,
        uncheckedTrack: Color = MaterialTheme.colorScheme.onSurface.copy(
            alpha = if (isFlamingoInDarkMode()) 0.24f else 0.12f,
        ),
        thumb: Color = Color.White,
        thumbShadow: Color = Color.Black.copy(alpha = 0.22f),
    ): MochiSwitchColors = remember(checkedTrack, uncheckedTrack, thumb, thumbShadow) {
        MochiSwitchColors(checkedTrack, uncheckedTrack, thumb, thumbShadow)
    }
}

/**
 * 源自 `mochi-compose` 的开关（iOS 26 的样子和手感，不做玻璃）：64×28 的轨道 + 38×24 的胶囊拇指。
 *
 * - 点一下：拇指带速度滑过去，沿运动方向拉长、到位压扁回弹，轨道颜色随拇指位置连续过渡
 * - 按住：拇指放大到溢出轨道、投影变深并发光；左右拖动跟手，拖出两端被橡皮筋拉住，过中线轻震；松手按位置决定开关
 * - 状态改变时有确认触感
 */
@Composable
fun MochiSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    switchHeight: Dp = 28.dp,
    enabled: Boolean = true,
    colors: MochiSwitchColors = MochiSwitchDefaults.colors(),
    gel: Float = 0.6f,
    hapticProfile: MochiHapticProfile = MochiHapticProfile.IosLike,
) {
    val view = LocalView.current
    val density = LocalDensity.current
    val haptics = remember(view, hapticProfile) { MochiHaptics(view, hapticProfile) }
    val state = remember { SwitchState(if (checked) 1f else 0f) }
    val currentChecked by rememberUpdatedState(checked)
    val currentOnChange by rememberUpdatedState(onCheckedChange)
    val glow = rememberMochiGlowState()
    val scope = rememberCoroutineScope()
    val thumbPath = remember { G2PathCache() }
    val trackPath = remember { G2PathCache() }
    val interactive = enabled && onCheckedChange != null

    LaunchedEffect(state) { state.run({ currentChecked }, haptics) }

    val scale = switchHeight / 28.dp
    val trackW = 64.dp * scale
    val trackH = switchHeight

    with(density) {
        val inset = (2.dp * scale).toPx()
        val thumbH = (trackH.toPx() - inset * 2f).coerceAtLeast(1f)
        val thumbW = (38.dp * scale).toPx()
        val travel = (trackW.toPx() - inset * 2f - thumbW).coerceAtLeast(1f)
        Box(
            modifier
                .size(trackW, trackH)
                .semantics {
                    role = Role.Switch
                    toggleableState = ToggleableState(checked)
                    if (interactive) {
                        onClick {
                            haptics.confirm()
                            currentOnChange?.invoke(!currentChecked)
                            true
                        }
                    }
                }
                .pointerInput(interactive) {
                    if (!interactive) return@pointerInput
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        state.held = true
                        state.dragStart = state.pos
                        state.drag = null
                        scope.launch {
                            glow.press(
                                down.position,
                                MochiGlowDefaults.radius(Size(thumbW, thumbH), this@pointerInput),
                            )
                        }
                        var dx = 0f
                        var released = false
                        while (true) {
                            val e = awaitPointerEvent()
                            val c = e.changes.firstOrNull { it.id == down.id } ?: break
                            dx += c.positionChange().x
                            glow.moveTo(c.position)
                            if (state.drag == null && abs(dx) > viewConfiguration.touchSlop) state.drag = 0f
                            if (state.drag != null) {
                                state.drag = dx / travel
                                c.consume()
                            }
                            if (!c.pressed) {
                                released = true
                                break
                            }
                        }
                        val dragged = state.drag != null
                        val target = if (dragged) state.pos > 0.5f else !currentChecked
                        state.held = false
                        state.drag = null
                        glow.markReleased()
                        scope.launch { glow.release() }
                        if (released) {
                            state.settle(target)
                            if (target != currentChecked) {
                                if (!dragged && hapticProfile != MochiHapticProfile.Rich) haptics.tick()
                                haptics.confirm()
                                currentOnChange?.invoke(target)
                            }
                        }
                    }
                }
                .drawBehind {
                    val alpha = if (enabled) 1f else MochiDisabledAlpha
                    val p = state.pos
                    val pc = p.coerceIn(0f, 1f)
                    val track = lerp(colors.uncheckedTrack, colors.checkedTrack, pc)
                    drawG2Capsule(trackPath, Rect(Offset.Zero, size), track, alpha)
                    // 拇指：按下放大到溢出轨道（偏宽），速度大时沿运动方向拉长
                    val k = state.press
                    val s = jellyStretch(state.vel * travel / thumbW) * gel
                    val w = thumbW * mochiLerp(1f, 1.22f, k) * (1f + abs(s))
                    val h = thumbH * mochiLerp(1f, 1.34f, k) * jellyCrossScale(abs(s))
                    val cx = inset + thumbW / 2f + p * travel
                    val cy = size.height / 2f
                    val rect = Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
                    drawPillThumb(
                        rect = rect,
                        color = colors.thumb.copy(alpha = colors.thumb.alpha * alpha),
                        shadow = colors.thumbShadow,
                        lift = k,
                        glow = glow,
                        path = thumbPath,
                    )
                },
        )
    }
}

/** 拇指位置（0 = 关，1 = 开，拖出两端可以略超出）和按压程度 */
@Stable
private class SwitchState(initial: Float) {
    var pos by mutableFloatStateOf(initial)
        private set
    var vel by mutableFloatStateOf(0f)
        private set
    var press by mutableFloatStateOf(0f)
        private set
    var held by mutableStateOf(false)

    /** 拖动量（以「拇指行程」为单位）；null = 没在拖 */
    var drag: Float? = null
    var dragStart = 0f
    private var pending: Boolean? = null
    private var pendingFrames = 0

    fun settle(target: Boolean) {
        pending = target
        pendingFrames = 8
    }

    private fun target(checked: Boolean): Float {
        val d = drag
        if (held && d != null) {
            val raw = dragStart + d
            val c = raw.coerceIn(0f, 1f)
            // 拖出两端：被橡皮筋拉住
            return c + rubberBand(raw - c, 1f, 0.12f)
        }
        if (held) return pos
        val p = pending
        if (p != null) {
            if (p == checked || pendingFrames-- <= 0) pending = null else return if (p) 1f else 0f
        }
        return if (checked) 1f else 0f
    }

    suspend fun run(checked: () -> Boolean, haptics: MochiHaptics) {
        val spring = MochiFollowSpring(0.36f, 0.68f)
        val pressSpring = MochiFollowSpring(0.24f, 0.7f)
        spring.snapTo(Offset(pos, 0f))
        var side = pos > 0.5f
        while (true) {
            val t = target(checked())
            if (!held && pending == null && abs(pos - t) < 0.0005f && abs(spring.velocity.x) < 0.005f && press < 0.001f &&
                abs(pressSpring.velocity.x) < 0.01f
            ) {
                pos = t
                vel = 0f
                press = 0f
                spring.snapTo(Offset(t, 0f))
                pressSpring.snapTo(Offset.Zero)
                snapshotFlow { held to checked() }.first { (h, c) -> h || (if (c) 1f else 0f) != pos }
                continue
            }
            val dt = spring.awaitFrameDelta()
            // 拖动时跟得紧；松手后带速度滑过去、有一点回弹
            spring.response = if (held && drag != null) 0.12f else 0.36f
            spring.dampingRatio = if (held && drag != null) 1f else 0.68f
            pos = spring.step(Offset(t, 0f), dt).x
            vel = spring.velocity.x
            press = pressSpring.step(Offset(if (held) 1f else 0f, 0f), dt).x.coerceAtLeast(0f)
            val now = pos > 0.5f
            if (held && drag != null && now != side) haptics.tick()
            side = now
        }
    }
}
