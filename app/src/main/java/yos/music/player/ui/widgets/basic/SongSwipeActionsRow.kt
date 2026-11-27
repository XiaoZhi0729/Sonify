package yos.music.player.ui.widgets.basic

import android.view.View
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.mochi.LocalMochiToast
import yos.music.player.ui.widgets.mochi.MochiHapticProfile
import yos.music.player.ui.widgets.mochi.MochiMotions
import yos.music.player.ui.widgets.mochi.lerp
import yos.music.player.ui.widgets.mochi.rememberMochiHaptics
import yos.music.player.ui.widgets.mochi.rubberBand
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * 全局单行互斥协调器：整个列表体系同一时间最多只允许一行处于滑开状态。
 */
internal object SongSwipeCoordinator {
    @Volatile
    private var activeCloser: (() -> Unit)? = null

    @Volatile
    var hasOpenRow: Boolean = false
        private set

    fun opened(closer: () -> Unit) {
        val prev = activeCloser
        if (prev != null && prev !== closer) {
            prev.invoke()
        }
        activeCloser = closer
        hasOpenRow = true
    }

    fun closed(closer: () -> Unit) {
        if (activeCloser === closer) {
            activeCloser = null
            hasOpenRow = false
        }
    }

    fun closeAll(): Boolean {
        val current = activeCloser ?: return false
        activeCloser = null
        hasOpenRow = false
        current.invoke()
        return true
    }
}

private val RightSlotWidth = 74.dp
private val LeftSlotWidth = 84.dp
private val EdgeGuardDp = 24.dp
private val FlingVelocityDp = 240.dp
private val FullSwipeFlingVelocityDp = 900.dp
private const val EnterFullFraction = 0.46f
private const val ExitFullFraction = 0.43f

/**
 * 快捷包装器：把歌曲行的右滑（「下一首播放」+「最后播放」）与左滑（「移除」）统一接到
 * [MediaController] 与 Mochi 顶部胶囊提示（[LocalMochiToast]）。
 */
@Composable
fun SongSwipeableWrapper(
    music: YosMediaItem,
    enableSwipeRight: Boolean = true,
    enableSwipeLeft: Boolean = false,
    onSwipeRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val toast = LocalMochiToast.current
    val isDark = isFlamingoInDarkMode()
    val nextColor = if (isDark) Color(0xFF7357FF) else Color(0xFF6852F5)
    val lastColor = if (isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    val songTitle = music.title ?: defaultTitle

    SongSwipeActionsRow(
        enableSwipeRight = enableSwipeRight,
        enableSwipeLeft = enableSwipeLeft && onSwipeRemove != null,
        onPlayNext = {
            scope.launch {
                val (outcome, undoAction) = MediaController.playNextWithUndo(music, toggleIfAlreadyNext = false)
                val titleRes = when (outcome) {
                    MediaController.PlayNextOutcome.Added -> R.string.swipe_toast_play_next
                    MediaController.PlayNextOutcome.Cancelled -> R.string.song_menu_play_next_cancelled
                    MediaController.PlayNextOutcome.StartedPlayback -> R.string.song_menu_play_next_started
                    MediaController.PlayNextOutcome.Failed -> R.string.song_menu_play_next_failed
                }
                val title = context.getString(titleRes)
                if (toast != null) {
                    toast.show(
                        title = title,
                        subtitle = if (outcome == MediaController.PlayNextOutcome.Failed) null else songTitle,
                        icon = { _ ->
                            Icon(
                                painter = painterResource(R.drawable.ic_swipe_play_next),
                                contentDescription = null,
                                tint = nextColor,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        durationMillis = if (undoAction != null) 3200L else 2200L,
                        actionLabel = if (undoAction != null) context.getString(R.string.swipe_action_undo) else null,
                        onAction = if (undoAction != null) {
                            {
                                scope.launch { MediaController.undoQueueAction(undoAction) }
                                toast.show(
                                    title = context.getString(R.string.swipe_toast_undone),
                                    subtitle = songTitle,
                                )
                            }
                        } else null,
                    )
                }
            }
        },
        onPlayLast = {
            scope.launch {
                val (outcome, undoAction) = MediaController.playLastWithUndo(music)
                val titleRes = when (outcome) {
                    MediaController.PlayLastOutcome.Added -> R.string.swipe_toast_play_last
                    MediaController.PlayLastOutcome.StartedPlayback -> R.string.song_menu_play_next_started
                    MediaController.PlayLastOutcome.Failed -> R.string.song_menu_play_next_failed
                }
                val title = context.getString(titleRes)
                if (toast != null) {
                    toast.show(
                        title = title,
                        subtitle = if (outcome == MediaController.PlayLastOutcome.Failed) null else songTitle,
                        icon = { _ ->
                            Icon(
                                painter = painterResource(R.drawable.ic_swipe_play_last),
                                contentDescription = null,
                                tint = lastColor,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        durationMillis = if (undoAction != null) 3200L else 2200L,
                        actionLabel = if (undoAction != null) context.getString(R.string.swipe_action_undo) else null,
                        onAction = if (undoAction != null) {
                            {
                                scope.launch { MediaController.undoQueueAction(undoAction) }
                                toast.show(
                                    title = context.getString(R.string.swipe_toast_undone),
                                    subtitle = songTitle,
                                )
                            }
                        } else null,
                    )
                }
            }
        },
        onRemove = onSwipeRemove,
        modifier = modifier,
        content = content,
    )
}

/**
 * Apple Music 风格歌曲行两段式左右滑动组件：
 * - 右滑（[enableSwipeRight]）：
 *   1) 轻滑松手停在半开，并排露出紫色「下一首播放」与橙色「最后播放」；
 *   2) 拉过约 50% 临界线后紫色顺滑铺满整个滑开区域（挤掉橙色），松手直接触发；
 *   3) 若未松手又退回 47% 以内，自然恢复双按钮并排状态。
 * - 左滑（[enableSwipeLeft]）：
 *   1) 轻滑松手停在半开，露出红色「移除」按钮，点击触发；
 *   2) 拉过约 50% 临界线松手直接移除，并伴随滑出 + 高度平滑折叠动画让下方歌曲自然顶上。
 * - 跨越（进入或退回）50% 临界线时触发轻震动 + 按钮图标跳动反馈。
 * - 四项防误触规则：同一时间最多展开一行、点行体优先收起不误触播放、列表滚动自动收起、
 *   横竖方向干脆锁定且在屏幕左右 24dp 边缘让出系统侧滑返回。
 */
@Composable
fun SongSwipeActionsRow(
    enableSwipeRight: Boolean = true,
    enableSwipeLeft: Boolean = false,
    onPlayNext: (() -> Unit)? = null,
    onPlayLast: (() -> Unit)? = null,
    onRemove: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val canRight = enableSwipeRight && (onPlayNext != null || onPlayLast != null)
    val canLeft = enableSwipeLeft && onRemove != null

    val density = LocalDensity.current
    val view: View = LocalView.current
    val scope = rememberCoroutineScope()
    val haptics = rememberMochiHaptics(MochiHapticProfile.IosLike)
    val isDark = isFlamingoInDarkMode()

    val currentCanRight by rememberUpdatedState(canRight)
    val currentCanLeft by rememberUpdatedState(canLeft)
    val currentPlayNext by rememberUpdatedState(onPlayNext)
    val currentPlayLast by rememberUpdatedState(onPlayLast)
    val currentRemove by rememberUpdatedState(onRemove)

    val offset = remember { Animatable(0f) }
    val armProgress = remember { Animatable(0f) }
    val collapse = remember { Animatable(1f) }
    val iconPulse = remember { Animatable(0f) }

    var rowWidth by remember { mutableIntStateOf(0) }
    var rowWindowLeft by remember { mutableFloatStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf(false) }
    var committingRight by remember { mutableStateOf(false) }
    var pulsedSide by remember { mutableIntStateOf(0) } // 1 = right-swipe(next), -1 = left-swipe(remove)

    val rightSlotPx = with(density) { RightSlotWidth.toPx() }
    val rightRevealPx = rightSlotPx * 2f
    val leftRevealPx = with(density) { LeftSlotWidth.toPx() }
    val edgeGuardPx = with(density) { EdgeGuardDp.toPx() }
    val flingVelocityPx = with(density) { FlingVelocityDp.toPx() }
    val fullSwipeFlingPx = with(density) { FullSwipeFlingVelocityDp.toPx() }

    val settleSpring = remember { MochiMotions.responseSpring(0.34f, 0.80f) }
    val closeSpring = remember { MochiMotions.responseSpring(0.30f, 0.88f) }
    val morphSpring = remember { MochiMotions.responseSpring(0.24f, 0.80f) }

    val closeRef = remember { arrayOfNulls<() -> Unit>(1) }
    val closeSelf: () -> Unit = remember { { closeRef[0]?.invoke() } }
    val animJobRef = remember { arrayOfNulls<Job>(1) }

    fun close(initialVelocity: Float = 0f) {
        if (removing) return
        opened = false
        armed = false
        SongSwipeCoordinator.closed(closeSelf)
        animJobRef[0]?.cancel()
        scope.launch { armProgress.animateTo(0f, morphSpring) }
        animJobRef[0] = scope.launch {
            offset.animateTo(0f, closeSpring, initialVelocity = initialVelocity)
        }
    }
    closeRef[0] = { close() }

    DisposableEffect(Unit) {
        onDispose { SongSwipeCoordinator.closed(closeSelf) }
    }

    // 开关关闭时，若该侧正处于滑开状态，立即顺滑收回
    LaunchedEffect(canRight, canLeft) {
        if ((offset.value > 0.5f && !canRight) || (offset.value < -0.5f && !canLeft)) {
            close()
        }
    }

    fun bounceIcon(side: Int) {
        pulsedSide = side
        scope.launch {
            iconPulse.snapTo(0f)
            iconPulse.animateTo(1f, MochiMotions.responseSpring(0.11f, 0.82f))
            iconPulse.animateTo(0f, MochiMotions.responseSpring(0.26f, 0.65f))
        }
    }

    fun triggerPlayNextSweep(initialVelocity: Float = 0f) {
        if (removing || committingRight) return
        committingRight = true
        opened = false
        armed = false
        SongSwipeCoordinator.closed(closeSelf)
        haptics.confirm()
        animJobRef[0]?.cancel()
        val sweepTargetX = rowWidth.toFloat().coerceAtLeast(1f)
        animJobRef[0] = scope.launch {
            launch { armProgress.animateTo(1f, morphSpring) }
            offset.animateTo(
                targetValue = sweepTargetX,
                animationSpec = spring(
                    dampingRatio = 1f,
                    stiffness = MochiMotions.stiffnessFor(0.16f),
                    visibilityThreshold = 20f,
                ),
                initialVelocity = initialVelocity.coerceAtLeast(1200f),
            )
            currentPlayNext?.invoke()
            // 到达屏幕边缘后不停留，立刻弹性滑回原位
            offset.animateTo(
                targetValue = 0f,
                animationSpec = MochiMotions.responseSpring(0.32f, 0.86f),
            )
            armProgress.snapTo(0f)
            committingRight = false
        }
    }

    fun triggerRemove(initialVelocity: Float = 0f) {
        if (removing || committingRight) return
        val removeCb = currentRemove ?: run {
            close()
            return
        }
        removing = true
        opened = false
        armed = false
        SongSwipeCoordinator.closed(closeSelf)
        haptics.confirm()
        animJobRef[0]?.cancel()
        val targetX = -(rowWidth.toFloat().coerceAtLeast(1f))
        animJobRef[0] = scope.launch {
            launch { armProgress.animateTo(1f, morphSpring) }
            val slideJob = launch {
                offset.animateTo(
                    targetValue = targetX,
                    animationSpec = MochiMotions.responseSpring(0.26f, 1f),
                    initialVelocity = initialVelocity,
                )
            }
            delay(85)
            collapse.animateTo(0f, MochiMotions.responseSpring(0.30f, 1f))
            slideJob.join()
            removeCb()
        }
    }

    val showActions by remember { derivedStateOf { abs(offset.value) > 0.25f || removing || committingRight } }

    Layout(
        modifier = modifier
            .onGloballyPositioned { coords ->
                rowWindowLeft = coords.positionInWindow().x
            }
            .clipToBounds()
            .pointerInput(rightRevealPx, leftRevealPx, edgeGuardPx, flingVelocityPx) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (removing || committingRight) return@awaitEachGesture

                    // 规则 4a：屏幕左右 24dp 边缘防冲突区，放行系统侧滑返回手势
                    val windowWidth = view.width.toFloat().coerceAtLeast(1f)
                    val downWindowX = rowWindowLeft + down.position.x
                    if (downWindowX < edgeGuardPx || downWindowX > windowWidth - edgeGuardPx) {
                        return@awaitEachGesture
                    }

                    // 点在已露出的彩色按钮区域上时直接放行给按钮本身的 clickable
                    val curOffsetAtDown = offset.value
                    val wAtDown = rowWidth.toFloat().coerceAtLeast(1f)
                    if ((curOffsetAtDown > 0.5f && down.position.x < curOffsetAtDown) ||
                        (curOffsetAtDown < -0.5f && down.position.x > wAtDown + curOffsetAtDown)
                    ) {
                        return@awaitEachGesture
                    }

                    val hadOtherOpen = SongSwipeCoordinator.hasOpenRow && !opened
                    val wasThisOpen = opened

                    if (!currentCanRight && !currentCanLeft && !hadOtherOpen && !wasThisOpen) {
                        return@awaitEachGesture
                    }

                    val touchSlop = viewConfiguration.touchSlop
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)

                    var lockedAxis = 0 // 0=pending, 1=horizontal, -1=vertical
                    var anyMoved = false
                    var startOffset = curOffsetAtDown
                    var dragOriginX = down.position.x
                    var lastClampedX = curOffsetAtDown

                    fun applyDrag(r: Float, w: Float) {
                        val maxR = if (currentCanRight) w * 0.88f else 0f
                        val maxL = if (currentCanLeft) -w * 0.88f else 0f
                        val clamped = when {
                            r > 0f && !currentCanRight -> rubberBand(r, w, 40.dp.toPx())
                            r < 0f && !currentCanLeft -> -rubberBand(-r, w, 40.dp.toPx())
                            r > maxR -> maxR + rubberBand(r - maxR, w * 0.45f, 44.dp.toPx())
                            r < maxL -> maxL - rubberBand(maxL - r, w * 0.45f, 44.dp.toPx())
                            else -> r
                        }
                        lastClampedX = clamped
                        scope.launch(start = CoroutineStart.UNDISPATCHED) { offset.snapTo(clamped) }

                        // 50% 临界线带 3% 滞回（47% 退回），进入和退回都触发震动与图标跳动
                        val enterPx = w * EnterFullFraction
                        val exitPx = w * ExitFullFraction
                        val nextArmed = when {
                            clamped > 0f && currentCanRight -> if (armed) clamped >= exitPx else clamped >= enterPx
                            clamped < 0f && currentCanLeft -> if (armed) clamped <= -exitPx else clamped <= -enterPx
                            else -> false
                        }
                        if (nextArmed != armed) {
                            armed = nextArmed
                            val side = if (clamped >= 0f) 1 else -1
                            haptics.threshold()
                            bounceIcon(side)
                            scope.launch {
                                armProgress.animateTo(if (nextArmed) 1f else 0f, morphSpring)
                            }
                        }
                    }

                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break

                        if (!change.pressed) {
                            if (lockedAxis == 1) {
                                // 拖拽结束：吃掉 up 防止抬手误触行点击，并按位置/速度弹簧结算
                                change.consume()
                                val vx = tracker.calculateVelocity().x.coerceIn(-4500f, 4500f)
                                val curX = lastClampedX
                                val w = rowWidth.toFloat().coerceAtLeast(1f)
                                val projected = curX + vx * 0.14f
                                val fullRightByVelocity = currentCanRight && curX >= rightRevealPx * 0.78f &&
                                    (vx >= fullSwipeFlingPx || (vx > 200f && projected >= w * 0.56f))
                                val fullLeftByVelocity = currentCanLeft && curX <= -leftRevealPx * 1.10f &&
                                    (vx <= -fullSwipeFlingPx || (vx < -200f && projected <= -w * 0.56f))
                                if (currentCanRight && curX > 0f && (armed || fullRightByVelocity)) {
                                    triggerPlayNextSweep(vx)
                                } else if (currentCanLeft && curX < 0f && (armed || fullLeftByVelocity)) {
                                    triggerRemove(vx)
                                } else {
                                    val isFling = abs(vx) >= flingVelocityPx
                                    val openRight = currentCanRight && curX > 0f &&
                                        (if (isFling) vx > 0f else projected > rightRevealPx * 0.45f)
                                    val openLeft = currentCanLeft && curX < 0f &&
                                        (if (isFling) vx < 0f else projected < -leftRevealPx * 0.45f)
                                    when {
                                        openRight -> {
                                            armed = false
                                            animJobRef[0]?.cancel()
                                            scope.launch { armProgress.animateTo(0f, morphSpring) }
                                            animJobRef[0] = scope.launch {
                                                offset.animateTo(rightRevealPx, settleSpring, initialVelocity = vx)
                                            }
                                        }
                                        openLeft -> {
                                            armed = false
                                            animJobRef[0]?.cancel()
                                            scope.launch { armProgress.animateTo(0f, morphSpring) }
                                            animJobRef[0] = scope.launch {
                                                offset.animateTo(-leftRevealPx, settleSpring, initialVelocity = vx)
                                            }
                                        }
                                        else -> close(vx)
                                    }
                                }
                            } else if (!anyMoved && (wasThisOpen || hadOtherOpen)) {
                                // 规则 2：任何行处于展开态时，轻点歌曲本体只收起，不触发播放
                                change.consume()
                                SongSwipeCoordinator.closeAll()
                            }
                            break
                        }

                        val moved = change.position != change.previousPosition
                        if (moved) {
                            tracker.addPosition(change.uptimeMillis, change.position)
                        }

                        val dx = change.position.x - down.position.x
                        val dy = change.position.y - down.position.y
                        val dist = hypot(dx, dy)
                        if (dist > touchSlop) anyMoved = true

                        if (lockedAxis == 0) {
                            if (dist >= touchSlop) {
                                // 规则 4b：干脆的横竖方向锁（横向明显占优才锁定水平滑动）
                                if (abs(dx) > abs(dy) * 1.25f) {
                                    val curX = offset.value
                                    val intendingRight = dx > 0f
                                    val allowed = if (intendingRight) {
                                        currentCanRight || curX < -1f
                                    } else {
                                        currentCanLeft || curX > 1f
                                    }
                                    if (allowed) {
                                        lockedAxis = 1
                                        animJobRef[0]?.cancel()
                                        startOffset = offset.value
                                        dragOriginX = down.position.x + sign(dx) * touchSlop
                                        SongSwipeCoordinator.opened(closeSelf)
                                        opened = true
                                        applyDrag(
                                            startOffset + (change.position.x - dragOriginX),
                                            rowWidth.toFloat().coerceAtLeast(1f),
                                        )
                                        change.consume()
                                    } else {
                                        lockedAxis = -1
                                        break
                                    }
                                } else {
                                    // 规则 3：竖向滚动意图，立即平滑收起已展开行并放行列表滚动
                                    lockedAxis = -1
                                    SongSwipeCoordinator.closeAll()
                                    break
                                }
                            }
                        } else if (lockedAxis == 1) {
                            if (moved) {
                                applyDrag(
                                    startOffset + (change.position.x - dragOriginX),
                                    rowWidth.toFloat().coerceAtLeast(1f),
                                )
                            }
                            change.consume()
                        }
                    }
                }
            },
        content = {
            // 槽位 0 固定存在（永远不增删 Layout 顶层子节点，避免节点错位导致手势中断）
            Box(modifier = Modifier.fillMaxSize()) {
                if (showActions) {
                    SwipeActionBackdrop(
                        offsetPx = { offset.value },
                        armProgress = { armProgress.value },
                        iconScaleFor = { side ->
                            if (pulsedSide == side) 1f + 0.28f * iconPulse.value.coerceIn(0f, 1f) else 1f
                        },
                        fadeOutScale = { collapse.value.coerceIn(0f, 1f) },
                        isDark = isDark,
                        onClickNext = {
                            haptics.confirm()
                            close()
                            currentPlayNext?.invoke()
                        },
                        onClickLast = {
                            haptics.confirm()
                            close()
                            currentPlayLast?.invoke()
                        },
                        onClickRemove = {
                            triggerRemove()
                        },
                    )
                }
            }

            // 槽位 1 固定为前景歌曲内容盒（无额外底色或 saveLayer，完全保持原样透明与性能）
            Box(
                modifier = Modifier.graphicsLayer { translationX = offset.value },
            ) {
                content()
            }
        },
    ) { measurables, constraints ->
        val body = measurables[1].measure(constraints.copy(minHeight = 0))
        val w = body.width.coerceIn(constraints.minWidth, constraints.maxWidth)
        rowWidth = w
        val h = (body.height * collapse.value.coerceIn(0f, 1f)).roundToInt().coerceAtLeast(0)
        val actionPlaceable = measurables[0].measure(Constraints.fixed(w, body.height))
        layout(w, h) {
            actionPlaceable.place(0, 0)
            body.place(0, 0)
        }
    }
}

@Composable
private fun SwipeActionBackdrop(
    offsetPx: () -> Float,
    armProgress: () -> Float,
    iconScaleFor: (Int) -> Float,
    fadeOutScale: () -> Float,
    isDark: Boolean,
    onClickNext: () -> Unit,
    onClickLast: () -> Unit,
    onClickRemove: () -> Unit,
) {
    val nextBg = if (isDark) Color(0xFF7357FF) else Color(0xFF6852F5)
    val lastBg = if (isDark) Color(0xFFFF9F0A) else Color(0xFFFF9500)
    val removeBg = if (isDark) Color(0xFFFF453A) else Color(0xFFFF3B30)

    val nextLabel = stringResource(R.string.swipe_action_play_next)
    val lastLabel = stringResource(R.string.swipe_action_play_last)
    val removeLabel = stringResource(R.string.swipe_action_remove)

    Layout(
        modifier = Modifier.fillMaxSize(),
        content = {
            // 0: 紫色「下一首播放」
            Box(
                modifier = Modifier
                    .background(nextBg)
                    .clipToBounds()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClickNext,
                    ),
            ) {
                ActionLabelColumn(
                    iconRes = R.drawable.ic_swipe_play_next,
                    label = nextLabel,
                    iconScale = { iconScaleFor(1) },
                    alpha = { (offsetPx() / 28f).coerceIn(0f, 1f) },
                    modifier = Modifier
                        .width(RightSlotWidth)
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            val x = offsetPx().coerceAtLeast(0f)
                            val arm = armProgress().coerceIn(0f, 1f)
                            val share = lerp(0.5f, 1f, arm)
                            val slotW = x * share
                            val naturalPx = RightSlotWidth.toPx()
                            val centerOffset = (slotW - naturalPx) / 2f
                            val edgeOffset = (slotW - naturalPx - 10.dp.toPx()).coerceAtLeast(0f)
                            translationX = lerp(centerOffset, edgeOffset, arm)
                        },
                )
            }

            // 1: 橙色「最后播放」
            Box(
                modifier = Modifier
                    .background(lastBg)
                    .clipToBounds()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClickLast,
                    ),
            ) {
                ActionLabelColumn(
                    iconRes = R.drawable.ic_swipe_play_last,
                    label = lastLabel,
                    iconScale = {
                        val arm = armProgress().coerceIn(0f, 1f)
                        val morphFade = (1f - arm / 0.65f).coerceIn(0f, 1f)
                        0.88f + 0.12f * morphFade
                    },
                    alpha = {
                        val x = offsetPx().coerceAtLeast(0f)
                        val arm = armProgress().coerceIn(0f, 1f)
                        val revealAlpha = (x / 28f).coerceIn(0f, 1f)
                        val morphFade = (1f - arm / 0.65f).coerceIn(0f, 1f)
                        revealAlpha * morphFade
                    },
                    modifier = Modifier
                        .width(RightSlotWidth)
                        .align(Alignment.Center),
                )
            }

            // 2: 红色「移除」
            Box(
                modifier = Modifier
                    .background(removeBg)
                    .clipToBounds()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onClickRemove,
                    ),
            ) {
                ActionLabelColumn(
                    iconRes = R.drawable.ic_song_menu_trash,
                    label = removeLabel,
                    iconScale = { iconScaleFor(-1) * (0.55f + 0.45f * fadeOutScale()) },
                    alpha = {
                        val x = (-offsetPx()).coerceAtLeast(0f)
                        (x / 24f).coerceIn(0f, 1f) * fadeOutScale()
                    },
                    modifier = Modifier
                        .width(LeftSlotWidth)
                        .align(Alignment.CenterStart)
                        .graphicsLayer {
                            val x = (-offsetPx()).coerceAtLeast(0f)
                            val arm = armProgress().coerceIn(0f, 1f)
                            val naturalPx = LeftSlotWidth.toPx()
                            val centerOffset = (x - naturalPx) / 2f
                            val edgeOffset = 10.dp.toPx().coerceAtMost((x - naturalPx).coerceAtLeast(0f))
                            translationX = lerp(centerOffset, edgeOffset, arm)
                        },
                )
            }
        },
    ) { measurables, constraints ->
        val w = constraints.maxWidth
        val h = constraints.maxHeight
        val x = offsetPx()
        val arm = armProgress().coerceIn(0f, 1f)

        if (x > 0f) {
            // 严丝合缝贴合右滑前景行左缘，不向透明行下方透色
            val revealed = x.roundToInt().coerceIn(0, w)
            val share = lerp(0.5f, 1f, arm)
            val nextW = (revealed * share).roundToInt().coerceIn(0, revealed)
            val lastW = (revealed - nextW).coerceAtLeast(0)
            val pNext = measurables[0].measure(Constraints.fixed(nextW, h))
            val pLast = measurables[1].measure(Constraints.fixed(lastW, h))
            layout(w, h) {
                pNext.place(0, 0)
                if (lastW > 0) pLast.place(nextW, 0)
            }
        } else if (x < 0f) {
            val revealed = (-x).roundToInt().coerceIn(0, w)
            val pRem = measurables[2].measure(Constraints.fixed(revealed, h))
            layout(w, h) {
                pRem.place(w - revealed, 0)
            }
        } else {
            layout(w, h) {}
        }
    }
}

@Composable
private fun ActionLabelColumn(
    iconRes: Int,
    label: String,
    iconScale: () -> Float,
    alpha: () -> Float,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.graphicsLayer { this.alpha = alpha() },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = Color.White,
            modifier = Modifier
                .size(22.dp)
                .graphicsLayer {
                    val s = iconScale()
                    scaleX = s
                    scaleY = s
                },
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            color = Color.White,
            fontSize = 11.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Clip,
        )
    }
}
