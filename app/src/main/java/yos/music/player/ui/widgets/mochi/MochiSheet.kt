// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.RoundedCorner
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.verticalDrag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCompositionContext
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.SystemScreenCornerSample
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.manualScreenCornerRadiusDp
import kotlin.math.abs
import kotlin.math.roundToInt

/** 底部面板停靠的高度 */
enum class MochiSheetDetent {
    /** 半屏：面板悬浮，四周留边；底部圆角与屏幕同心，顶部圆角上限为 48dp */
    Medium,

    /** 全屏：贴边铺满到状态栏下面 */
    Large,
}

data class MochiScreenCorners(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    companion object {
        val None: MochiScreenCorners = MochiScreenCorners(0f, 0f, 0f, 0f)
    }
}

internal data class SheetFrame(val x: Int, val y: Int, val width: Int, val height: Int)

internal fun sheetSurfaceHeight(frame: SheetFrame, fixedHeight: Int, fillsBottom: Boolean): Int =
    if (fillsBottom) frame.height else minOf(frame.height, fixedHeight)

internal fun sheetFrame(windowWidth: Int, windowHeight: Int, top: Float, largeness: Float, inset: Float): SheetFrame {
    val gap = (inset * (1f - largeness.coerceIn(0f, 1f))).roundToInt().coerceIn(0, windowWidth / 2)
    val y = if (top.isNaN()) windowHeight else top.roundToInt().coerceAtMost(windowHeight)
    return SheetFrame(gap, y, (windowWidth - 2 * gap).coerceAtLeast(0), (windowHeight - gap - y).coerceAtLeast(0))
}

internal fun sheetCornerRadii(screen: MochiScreenCorners, gap: Float, topMaximum: Float, fallback: Float): MochiScreenCorners {
    fun top(radius: Float): Float = (maxOf(radius, fallback) - gap).coerceIn(0f, topMaximum)
    fun bottom(radius: Float): Float = (maxOf(radius, fallback) - gap).coerceAtLeast(0f)
    return MochiScreenCorners(
        top(screen.topLeft),
        top(screen.topRight),
        bottom(screen.bottomRight),
        bottom(screen.bottomLeft),
    )
}

@Stable
class MochiSheetState internal constructor(
    internal val detents: List<MochiSheetDetent>,
    internal val initial: MochiSheetDetent,
    mediumFraction: Float = 0.5f,
    internal val fitContent: Boolean = false,
) {
    internal val mediumFraction: Float = mediumFraction.coerceIn(0.25f, 0.9f)
    internal var requested by mutableStateOf(false)
    internal var composed by mutableStateOf(false)
    internal val top = Animatable(Float.NaN)
    internal var dragging by mutableStateOf(false)
    internal var bottomFilled by mutableStateOf(false)
    internal var height by mutableFloatStateOf(0f)
    internal var width by mutableIntStateOf(0)
    internal var largeTop = 0f
    internal var screenCorners: MochiScreenCorners = MochiScreenCorners.None
    internal var statusTop = 0f
    internal var measuredContentHeight by mutableFloatStateOf(0f)
    internal var floatInsetPx by mutableFloatStateOf(0f)

    internal val mediumTop: Float
        get() {
            if (fitContent && measuredContentHeight > 0f && height > 0f) {
                val bottomGap = if (MochiSheetDetent.Large in detents) 0f else floatInsetPx
                return (height - bottomGap - measuredContentHeight).coerceIn(largeTop, height)
            }
            return height * (1f - mediumFraction)
        }
    internal fun topOf(d: MochiSheetDetent): Float = if (d == MochiSheetDetent.Large) largeTop else mediumTop

    internal val presence: Float
        get() {
            val t = top.value
            if (t.isNaN() || height <= 0f) return 0f
            val low = detents.maxOf { topOf(it) }
            return ((height - t) / (height - low).coerceAtLeast(1f)).coerceIn(0f, 1f)
        }

    internal val largeness: Float
        get() {
            val t = top.value
            if (t.isNaN() || height <= 0f || MochiSheetDetent.Large !in detents) return 0f
            if (MochiSheetDetent.Medium !in detents) return presence
            return 1f - smoothstep(largeTop, mediumTop, t)
        }

    internal val fillsBottom: Boolean
        get() = bottomFilled && requested

    val isVisible: Boolean get() = requested

    fun show() {
        requested = true
    }

    fun hide() {
        bottomFilled = false
        requested = false
    }
}

@Composable
fun rememberMochiSheetState(
    detents: List<MochiSheetDetent> = listOf(MochiSheetDetent.Medium, MochiSheetDetent.Large),
    initial: MochiSheetDetent = detents.first(),
    mediumFraction: Float = 0.5f,
    fitContent: Boolean = false,
): MochiSheetState = remember {
    MochiSheetState(detents.ifEmpty { listOf(MochiSheetDetent.Large) }, initial, mediumFraction, fitContent)
}

/**
 * 源自 mochi-compose 的 iOS 风格底部面板：
 * - 半屏时悬浮：左右和底部留 8dp，底部圆角与屏幕同心，顶部圆角上限 48dp
 * - 全屏时贴边铺满到状态栏下面
 * - 支持上下拖拽停靠各档或甩动关闭
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MochiSheet(
    state: MochiSheetState,
    modifier: Modifier = Modifier,
    onDismiss: () -> Unit = {},
    color: Color = if (isFlamingoInDarkMode()) Color(0xFF1C1C1E) else Color(0xFFF7F7FA),
    grabber: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val currentOnDismiss by rememberUpdatedState(onDismiss)
    LaunchedEffect(state) {
        snapshotFlow { state.requested }.collect { if (it) state.composed = true }
    }
    if (!state.composed) return

    InWindowOverlay(leaving = { !state.requested }) {
        val density = LocalDensity.current
        val scope = rememberCoroutineScope()
        val corners = rememberMochiScreenCorners()
        val statusTop = WindowInsets.statusBars.getTop(density)
        val spring = MochiMotions.responseSpring(0.42f, 0.88f)
        val inset = with(density) { FloatInset.toPx() }
        val fallback = with(density) { 32.dp.toPx() }
        val topMaximum = with(density) { SheetTopCornerMaximum.toPx() }
        state.screenCorners = corners
        state.statusTop = statusTop.toFloat()
        state.largeTop = statusTop + with(density) { 12.dp.toPx() }
        state.floatInsetPx = inset
        val radii = remember(corners, inset, topMaximum, fallback) {
            {
                val gap = (inset * (1f - state.largeness)).roundToInt().toFloat()
                sheetCornerRadii(corners, gap, topMaximum, fallback)
            }
        }
        val corner = remember(radii) { { radii().let { maxOf(it.topLeft, it.topRight) } } }
        val framePeriod = rememberFramePeriodNanos()
        val isDark = isFlamingoInDarkMode()
        val grabberColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
        val placedY = remember { floatArrayOf(0f) }
        val view = LocalView.current
        val haptics = remember(view) { MochiHaptics(view, MochiHapticProfile.IosLike) }

        LaunchedEffect(state) {
            snapshotFlow {
                state.height > 0f && (!state.fitContent || state.measuredContentHeight > 0f)
            }.first { it }
            snapshotFlow { state.requested }.collectLatest { on ->
                if (on) {
                    if (state.top.value.isNaN()) {
                        state.top.snapTo(state.height)
                        awaitFrameSettle(framePeriod)
                    }
                    val settled = interruptible { state.top.animateTo(state.topOf(state.initial), spring) }
                    if (settled && state.requested && state.initial == MochiSheetDetent.Large) state.bottomFilled = true
                } else {
                    if (!state.top.value.isNaN()) {
                        var done = false
                        while (!state.requested && !done) {
                            done = interruptible { state.top.animateTo(state.height, MochiMotions.responseSpring(0.34f, 1f)) }
                        }
                        if (!done || state.requested) return@collectLatest
                    }
                    state.top.snapTo(Float.NaN)
                    state.bottomFilled = false
                    state.measuredContentHeight = 0f
                    state.composed = false
                    currentOnDismiss()
                }
            }
        }

        BackHandler(enabled = state.requested) {
            state.hide()
        }

        val scrim = Color.Black.copy(alpha = if (isDark) 0.56f else 0.36f)
        Box(
            Modifier
                .fillMaxSize()
                .onSizeChanged {
                    state.height = it.height.toFloat()
                    state.width = it.width
                }
                .drawBehind {
                    val a = 0.7f * state.presence + 0.3f * state.largeness
                    if (a > 0f) drawRect(scrim, alpha = a.coerceAtMost(1f))
                }
                .pointerInput(state) { detectTapGestures { state.hide() } },
        ) {
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(
                        modifier
                            .onGloballyPositioned { placedY[0] = it.positionInWindow().y }
                            .graphicsLayer {
                                val r = radii()
                                val fill = state.fillsBottom
                                shape = G2RectClipShape4(
                                    Rect(Offset.Zero, size),
                                    r.topLeft,
                                    r.topRight,
                                    if (fill) 0f else r.bottomRight,
                                    if (fill) 0f else r.bottomLeft,
                                )
                                clip = true
                                shadowElevation = 0f
                            }
                            .background(color)
                            .pointerInput(state) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    if (!state.requested || state.top.value.isNaN()) return@awaitEachGesture
                                    val tracker = VelocityTracker()
                                    fun screen(p: Offset) = Offset(p.x, p.y + placedY[0])
                                    tracker.addPosition(down.uptimeMillis, screen(down.position))
                                    var raw = state.top.value
                                    val dragStartTop = raw
                                    if (MochiSheetDetent.Large in state.detents && state.largeness >= 0.999f) {
                                        state.bottomFilled = true
                                    }
                                    val drag = awaitVerticalTouchSlopOrCancellation(down.id) { c, over ->
                                        c.consume()
                                        raw += over
                                    } ?: return@awaitEachGesture
                                    state.dragging = true
                                    try {
                                        val highest = state.detents.minOf { state.topOf(it) }
                                        fun apply() {
                                            if (raw > maxOf(state.largeTop, dragStartTop) + 0.5f) state.bottomFilled = false
                                            val shown = if (raw < highest) {
                                                highest - rubberBand(highest - raw, state.height, 48.dp.toPx())
                                            } else {
                                                raw
                                            }
                                            scope.launch { state.top.snapTo(shown) }
                                        }
                                        apply()
                                        var lastY = screen(drag.position).y
                                        verticalDrag(drag.id) { c ->
                                            val now = screen(c.position)
                                            raw += now.y - lastY
                                            lastY = now.y
                                            tracker.addPosition(c.uptimeMillis, now)
                                            apply()
                                            c.consume()
                                        }
                                        val v = tracker.calculateVelocity().y
                                        val projected = state.top.value + v * 0.22f
                                        val targets = state.detents.map { state.topOf(it) } + state.height
                                        val target = targets.minBy { abs(it - projected) }
                                        val from = state.detents.map { state.topOf(it) }.minBy { abs(it - dragStartTop) }
                                        if (target != state.height && target != from) haptics.tick()
                                        if (target == state.height) {
                                            state.hide()
                                        } else {
                                            val expandingToLarge = MochiSheetDetent.Large in state.detents && target == state.largeTop
                                            scope.launch {
                                                val settled = interruptible { state.top.animateTo(target, spring, initialVelocity = v) }
                                                if (settled && state.requested && expandingToLarge) {
                                                    state.bottomFilled = true
                                                }
                                            }
                                        }
                                    } finally {
                                        state.dragging = false
                                    }
                                }
                            }
                            .pointerInput(Unit) { detectTapGestures { } }
                            .drawWithContent {
                                drawContent()
                                if (grabber) {
                                    val w = 36.dp.toPx()
                                    val h = 5.dp.toPx()
                                    drawRoundRect(
                                        grabberColor,
                                        topLeft = Offset((size.width - w) / 2f, 6.dp.toPx()),
                                        size = Size(w, h),
                                        cornerRadius = CornerRadius(h / 2f),
                                    )
                                }
                            },
                    ) {
                        Column(
                            Modifier
                                .layout { m, c ->
                                    val bottomGap = if (MochiSheetDetent.Large in state.detents) 0f else inset
                                    if (state.fitContent) {
                                        val maxH = (state.height - state.largeTop - bottomGap).coerceAtLeast(0f).roundToInt()
                                        val p = m.measure(
                                            Constraints(
                                                minWidth = c.maxWidth,
                                                maxWidth = c.maxWidth,
                                                minHeight = 0,
                                                maxHeight = maxH,
                                            )
                                        )
                                        if (maxH > 0) {
                                            state.measuredContentHeight = p.height.toFloat()
                                        }
                                        layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
                                    } else {
                                        val highest = state.detents.minOf { state.topOf(it) }
                                        val h = (state.height - highest - bottomGap).coerceAtLeast(0f).roundToInt()
                                        val p = m.measure(Constraints.fixed(c.maxWidth, h))
                                        layout(c.maxWidth, c.maxHeight) { p.place(0, 0) }
                                    }
                                }
                                .windowInsetsPadding(WindowInsets.navigationBarsIgnoringVisibility.only(WindowInsetsSides.Bottom)),
                        ) {
                            CompositionLocalProvider(
                                LocalMochiSheetCorner provides corner,
                            ) { this@Column.content() }
                        }
                    }
                },
            ) { ms, c ->
                val frame = sheetFrame(c.maxWidth, c.maxHeight, state.top.value, state.largeness, inset)
                val highest = state.detents.minOf { state.topOf(it) }
                val movingHeight = (state.height - highest).coerceAtLeast(0f).roundToInt()
                val h = sheetSurfaceHeight(frame, movingHeight, state.fillsBottom)
                val p = ms.single().measure(Constraints.fixed(frame.width, h))
                layout(c.maxWidth, c.maxHeight) { p.place(frame.x, frame.y) }
            }
        }
    }
}

private suspend fun interruptible(block: suspend () -> Unit): Boolean = try {
    block()
    true
} catch (c: CancellationException) {
    currentCoroutineContext().ensureActive()
    false
}

@Composable
internal fun InWindowOverlay(leaving: () -> Boolean = { false }, content: @Composable () -> Unit) {
    val hostView = LocalView.current
    val parent = rememberCompositionContext()
    val currentContent by rememberUpdatedState(content)
    val currentLeaving by rememberUpdatedState(leaving)
    val overlay = remember(hostView) {
        OverlayFrame(hostView.context) { currentLeaving() }.apply {
            addView(
                ComposeView(hostView.context).apply {
                    setParentCompositionContext(parent)
                    setContent { currentContent() }
                },
                ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT),
            )
        }
    }
    DisposableEffect(overlay) {
        val decor = hostView.rootView as ViewGroup
        decor.addView(overlay, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        onDispose {
            if (overlay.hasFocus()) {
                val old = decor.descendantFocusability
                decor.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                decor.removeView(overlay)
                decor.post { decor.descendantFocusability = old }
            } else {
                decor.removeView(overlay)
            }
            (overlay.getChildAt(0) as ComposeView).disposeComposition()
        }
    }
}

private class OverlayFrame(context: Context, private val leaving: () -> Boolean) : FrameLayout(context) {
    private var passing = false

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) passing = leaving()
        return if (passing) false else super.dispatchTouchEvent(ev)
    }
}

@Composable
private fun rememberMochiScreenCorners(): MochiScreenCorners {
    val view = LocalView.current
    val density = LocalDensity.current
    val orientation = LocalConfiguration.current.orientation
    val manualCornerPx = with(density) { manualScreenCornerRadiusDp(SettingsLibrary.ScreenCorner).toPx() }
    val reportsSquare = SystemScreenCornerSample.reportsSquare
    var corners by remember(view, orientation, manualCornerPx, reportsSquare) {
        mutableStateOf(view.readScreenCorners(manualCornerPx, reportsSquare))
    }
    LaunchedEffect(view, orientation, manualCornerPx, reportsSquare) {
        repeat(10) {
            val c = view.readScreenCorners(manualCornerPx, reportsSquare)
            if (c != null) {
                corners = c
                return@LaunchedEffect
            }
            withFrameNanos { }
        }
    }
    return corners ?: MochiScreenCorners(manualCornerPx, manualCornerPx, manualCornerPx, manualCornerPx)
}

private fun View.readScreenCorners(manualCornerPx: Float, reportsSquare: Boolean): MochiScreenCorners? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || reportsSquare) {
        return MochiScreenCorners(manualCornerPx, manualCornerPx, manualCornerPx, manualCornerPx)
    }
    val insets = rootWindowInsets ?: return null
    fun r(position: Int): Float = insets.getRoundedCorner(position)?.radius?.toFloat() ?: 0f
    val tl = r(RoundedCorner.POSITION_TOP_LEFT)
    val tr = r(RoundedCorner.POSITION_TOP_RIGHT)
    val br = r(RoundedCorner.POSITION_BOTTOM_RIGHT)
    val bl = r(RoundedCorner.POSITION_BOTTOM_LEFT)
    if (tl <= 0f && tr <= 0f && br <= 0f && bl <= 0f) {
        return MochiScreenCorners(manualCornerPx, manualCornerPx, manualCornerPx, manualCornerPx)
    }
    return MochiScreenCorners(tl, tr, br, bl)
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

@Composable
private fun rememberFramePeriodNanos(): Long {
    val view = LocalView.current
    return remember(view) {
        @Suppress("DEPRECATION")
        val hz = view.display?.refreshRate ?: 60f
        (1_000_000_000.0 / hz.coerceIn(30f, 240f)).toLong()
    }
}

private const val MinFrames = 2
private const val MaxSettleNanos = 160_000_000L
private val FloatInset: Dp = 8.dp
private val SheetTopCornerMaximum: Dp = 48.dp
private val SheetBarItemSize: Dp = 44.dp

internal val LocalMochiSheetCorner = staticCompositionLocalOf<(() -> Float)?> { null }

private fun Density.sheetHeaderInset(corner: (() -> Float)?): Int {
    val item = SheetBarItemSize.roundToPx()
    val r = corner?.invoke() ?: 32.dp.toPx()
    return (r - item / 2f).roundToInt().coerceAtLeast(12.dp.roundToPx())
}

@Composable
fun MochiSheetContent(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.spacedBy(16.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val corner = LocalMochiSheetCorner.current
    Column(
        modifier
            .fillMaxWidth()
            .layout { m, c ->
                val inset = sheetHeaderInset(corner)
                val w = (c.maxWidth - inset * 2).coerceAtLeast(0)
                val p = m.measure(c.copy(minWidth = w, maxWidth = w))
                layout(c.maxWidth, p.height + inset * 2) { p.place(inset, inset) }
            },
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

@Composable
fun MochiSheetHeader(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: (@Composable () -> Unit)? = null,
) {
    val corner = LocalMochiSheetCorner.current
    val navTitleStyle = androidx.compose.ui.text.TextStyle(
        textMotion = TextMotion.Animated,
        fontSize = 17.sp,
        lineHeight = 22.sp,
        fontWeight = FontWeight.SemiBold,
        letterSpacing = 0.sp,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    Layout(
        modifier = modifier.fillMaxWidth(),
        content = {
            Box { navigationIcon?.invoke() }
            Text(
                text = title,
                style = navTitleStyle,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box { actions?.invoke() }
        },
    ) { ms, c ->
        val item = SheetBarItemSize.roundToPx()
        val inset = sheetHeaderInset(corner)
        val loose = c.copy(minWidth = 0, minHeight = 0)
        val nav = ms[0].measure(loose)
        val act = ms[2].measure(loose)
        val side = maxOf(nav.width, act.width)
        val titleMax = (c.maxWidth - 2 * (inset + side + 8.dp.roundToPx())).coerceAtLeast(0)
        val t = ms[1].measure(loose.copy(maxWidth = titleMax))
        val rowH = maxOf(item, nav.height, act.height, t.height)
        layout(c.maxWidth, inset + rowH) {
            nav.place(inset, inset + (rowH - nav.height) / 2)
            act.place(c.maxWidth - inset - act.width, inset + (rowH - act.height) / 2)
            t.place((c.maxWidth - t.width) / 2, inset + (rowH - t.height) / 2)
        }
    }
}

@Composable
internal fun MochiSheetCloseButton(
    onClick: () -> Unit,
    contentDescription: String,
    modifier: Modifier = Modifier,
) {
    val isDark = isFlamingoInDarkMode()
    val container = if (isDark) Color(0xFF2C2C2E) else Color.White
    val tint = MaterialTheme.colorScheme.onSurface
    Box(
        modifier = modifier
            .size(SheetBarItemSize)
            .mochiPressDeform()
            .clip(CircleShape)
            .background(container)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            )
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        MochiCloseGlyph(tint = tint, size = 14.dp)
    }
}

@Composable
internal fun MochiCloseGlyph(
    tint: Color,
    modifier: Modifier = Modifier,
    size: Dp = 14.dp,
) {
    Canvas(modifier.size(size)) {
        val w = 2.dp.toPx()
        val a = w / 2f
        val b = this.size.width - w / 2f
        drawLine(tint, Offset(a, a), Offset(b, b), w, StrokeCap.Round)
        drawLine(tint, Offset(b, a), Offset(a, b), w, StrokeCap.Round)
    }
}
