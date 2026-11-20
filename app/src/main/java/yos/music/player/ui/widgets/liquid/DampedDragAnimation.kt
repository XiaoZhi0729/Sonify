package yos.music.player.ui.widgets.liquid

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationConstants
import androidx.compose.animation.core.FloatSpringSpec
import androidx.compose.animation.core.spring
import androidx.compose.foundation.MutatorMutex
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.time.Clock

class DampedDragAnimation(
    private val animationScope: CoroutineScope,
    val initialValue: Float,
    val valueRange: ClosedRange<Float>,
    val visibilityThreshold: Float,
    val initialScale: Float,
    val pressedScale: Float,
    val onDragStarted: DampedDragAnimation.(position: Offset) -> Unit,
    val onDragStopped: DampedDragAnimation.() -> Unit,
    val onDrag: DampedDragAnimation.(size: IntSize, dragAmount: Offset) -> Unit,
    // 独占拖动：越过 touchSlop 后消费指针，父级滚动容器不再跟着滚（开关用）。
    private val exclusiveDrag: Boolean = false,
    // 只认领横向拖动（滚动容器里的横向滑条用）：横向越过 touchSlop 且占优才消费指针，
    // 纵向为主的滑动原样留给父级滚动容器。优先于 exclusiveDrag。
    private val horizontalClaim: Boolean = false,
    // 拖动被外部抢占而中止时的回调；默认与 onDragStopped 同语义（保持旧行为）。
    val onDragCancelled: DampedDragAnimation.() -> Unit = onDragStopped,
) {

    private val valueAnimationSpec =
        spring(1f, 1000f, visibilityThreshold)
    private val valueFloatSpringSpec =
        FloatSpringSpec(1f, 1000f, visibilityThreshold)
    private val velocityFloatSpringSpec =
        FloatSpringSpec(0.5f, 300f, visibilityThreshold * 10f)
    private val velocityZeroFloatSpringSpec =
        FloatSpringSpec(0.8f, 350f, minOf(visibilityThreshold * 2f, 0.01f))
    private val pressProgressAnimationSpec =
        spring(1f, 1000f, 0.001f)
    private val scaleXAnimationSpec =
        spring(0.6f, 250f, 0.001f)
    private val scaleYAnimationSpec =
        spring(0.7f, 250f, 0.001f)

    private val valueAnimation =
        Animatable(initialValue, visibilityThreshold)
    private val velocityAnimation =
        Animatable(0f, minOf(visibilityThreshold * 2f, 0.01f))
    private val pressProgressAnimation =
        Animatable(0f, 0.001f)
    private val scaleXAnimation =
        Animatable(initialScale, 0.001f)
    private val scaleYAnimation =
        Animatable(initialScale, 0.001f)

    private val mutatorMutex = MutatorMutex()

    private val velocityTracker = VelocityTracker()

    private var isHeld = false
    private var pressJob: Job? = null
    private var releaseJob: Job? = null
    private var valueJob: Job? = null
    private var velocityJob: Job? = null
    private var velocityIdleResetJob: Job? = null
    // 同步跟踪最新目标值：Animatable.targetValue 要等到 launch 的协程调度进入 animateTo/snapTo
    // 才会更新；连续事件在同一帧内取消旧 valueJob 时若读异步值会丢失前面所有增量。
    private var pendingTargetValue = initialValue
    private var valueSpringActive = false
    private var currentValueVelocity = 0f
    private var pendingVelocityTarget = 0f
    private var activeVelocitySpec = velocityFloatSpringSpec
    private var velocitySpringActive = false
    private var currentVelocityVelocity = 0f

    val value: Float get() = valueAnimation.value
    val progress: Float get() = (value - valueRange.start) / (valueRange.endInclusive - valueRange.start)
    val targetValue: Float get() = pendingTargetValue
    val pressProgress: Float get() = pressProgressAnimation.value
    val scaleX: Float get() = scaleXAnimation.value
    val scaleY: Float get() = scaleYAnimation.value
    val velocity: Float get() = velocityAnimation.value

    val modifier: Modifier = Modifier.pointerInput(Unit) {
        if (horizontalClaim) {
            claimHorizontalDragGestures(
                onStart = { down ->
                    onDragStarted(down.position)
                    press()
                },
                onEnd = {
                    onDragStopped()
                    release()
                },
                onCancel = {
                    onDragCancelled()
                    release()
                }
            ) { dragAmount ->
                onDrag(size, dragAmount)
            }
        } else if (exclusiveDrag) {
            claimDragGestures(
                onStart = { down ->
                    onDragStarted(down.position)
                    press()
                },
                onEnd = {
                    onDragStopped()
                    release()
                },
                onCancel = {
                    onDragCancelled()
                    release()
                }
            ) { dragAmount ->
                onDrag(size, dragAmount)
            }
        } else {
            inspectDragGestures(
                onDragStart = { down ->
                    onDragStarted(down.position)
                    press()
                },
                onDragEnd = {
                    onDragStopped()
                    release()
                },
                onDragCancel = {
                    onDragStopped()
                    release()
                }
            ) { change, dragAmount ->
                onDrag(size, dragAmount)
            }
        }
    }

    fun press() {
        isHeld = true
        releaseJob?.cancel()
        releaseJob = null
        velocityIdleResetJob?.cancel()
        velocityIdleResetJob = null
        velocityTracker.resetTracking()
        pressJob?.cancel()
        pressJob = animationScope.launch {
            launch { pressProgressAnimation.animateTo(1f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(pressedScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(pressedScale, scaleYAnimationSpec) }
        }
    }

    fun release() {
        isHeld = false
        velocityIdleResetJob?.cancel()
        velocityIdleResetJob = null
        velocityTracker.resetTracking()
        releaseJob?.cancel()
        releaseJob = animationScope.launch {
            withFrameNanos {}
            if (value != targetValue) {
                val threshold = (valueRange.endInclusive - valueRange.start) * 0.025f
                snapshotFlow { valueAnimation.value }
                    .filter { abs(it - pendingTargetValue) < threshold }
                    .first()
            }
            launch { pressProgressAnimation.animateTo(0f, pressProgressAnimationSpec) }
            launch { scaleXAnimation.animateTo(initialScale, scaleXAnimationSpec) }
            launch { scaleYAnimation.animateTo(initialScale, scaleYAnimationSpec) }
            resetVelocity()
        }
    }

    fun updateValue(value: Float) {
        val target = value.coerceIn(valueRange)
        pendingTargetValue = target
        if (valueSpringActive && valueJob?.isActive == true) {
            return
        }
        valueJob?.cancel()
        valueSpringActive = true
        valueJob = animationScope.launch {
            try {
                runContinuousSpring(
                    animatable = valueAnimation,
                    getTarget = { pendingTargetValue },
                    getSpec = { valueFloatSpringSpec },
                    getCurrentVelocity = { currentValueVelocity },
                    setCurrentVelocity = { currentValueVelocity = it },
                    onStep = { updateVelocity() },
                )
                resetVelocity()
            } finally {
                valueSpringActive = false
            }
        }
    }

    /** 拖动中 1:1 跟手刷新目标值与瞬时速度，避免弹簧追赶带来的拖滞与松手停顿 */
    fun dragTo(value: Float) {
        releaseJob?.cancel()
        releaseJob = null
        val target = value.coerceIn(valueRange)
        pendingTargetValue = target
        valueSpringActive = false
        currentValueVelocity = 0f
        valueJob?.cancel()
        valueJob = animationScope.launch {
            valueAnimation.snapTo(target)
            updateVelocity()
        }
        velocityIdleResetJob?.cancel()
        velocityIdleResetJob = animationScope.launch {
            delay(80L)
            resetVelocity()
        }
    }

    fun animateToValue(value: Float) {
        val target = value.coerceIn(valueRange)
        pendingTargetValue = target
        valueSpringActive = false
        currentValueVelocity = 0f
        valueJob?.cancel()
        valueJob = animationScope.launch {
            mutatorMutex.mutate {
                press()
                launch { valueAnimation.animateTo(target, valueAnimationSpec) }
                resetVelocity()
                release()
            }
        }
    }

    private fun updateVelocity() {
        velocityTracker.addPosition(
            Clock.System.now().toEpochMilliseconds(),
            Offset(value, 0f)
        )
        val targetVelocity = velocityTracker.calculateVelocity().x / (valueRange.endInclusive - valueRange.start)
        animateVelocityTo(targetVelocity, velocityFloatSpringSpec)
    }

    private fun resetVelocity() {
        velocityTracker.resetTracking()
        velocityIdleResetJob?.cancel()
        velocityIdleResetJob = null
        if (!velocitySpringActive && velocityAnimation.value == 0f && pendingVelocityTarget == 0f) {
            return
        }
        animateVelocityTo(0f, velocityZeroFloatSpringSpec)
    }

    private fun animateVelocityTo(target: Float, spec: FloatSpringSpec) {
        pendingVelocityTarget = target
        activeVelocitySpec = spec
        if (velocitySpringActive && velocityJob?.isActive == true) {
            return
        }
        velocityJob?.cancel()
        velocitySpringActive = true
        velocityJob = animationScope.launch {
            try {
                runContinuousSpring(
                    animatable = velocityAnimation,
                    getTarget = { pendingVelocityTarget },
                    getSpec = { activeVelocitySpec },
                    getCurrentVelocity = { currentVelocityVelocity },
                    setCurrentVelocity = { currentVelocityVelocity = it },
                )
            } finally {
                velocitySpringActive = false
            }
        }
    }

    private data class SpringStepResult(
        val value: Float,
        val finished: Boolean,
        val target: Float,
        val spec: FloatSpringSpec,
    )

    private suspend fun runContinuousSpring(
        animatable: Animatable<Float, *>,
        getTarget: () -> Float,
        getSpec: () -> FloatSpringSpec,
        getCurrentVelocity: () -> Float,
        setCurrentVelocity: (Float) -> Unit,
        onStep: () -> Unit = {},
    ) {
        var segmentStartTimeNanos = AnimationConstants.UnspecifiedTime
        var lastFrameNanos = AnimationConstants.UnspecifiedTime
        var segmentStartValue = animatable.value
        var segmentStartVelocity = getCurrentVelocity()
        var segmentTarget = getTarget()
        var segmentSpec = getSpec()
        var segmentDurationNanos = segmentSpec.getDurationNanos(
            segmentStartValue, segmentTarget, segmentStartVelocity
        )

        while (true) {
            val step = withFrameNanos { frameTimeNanos ->
                val latestTarget = getTarget()
                val latestSpec = getSpec()
                if (segmentStartTimeNanos == AnimationConstants.UnspecifiedTime) {
                    segmentStartTimeNanos = frameTimeNanos
                    segmentTarget = latestTarget
                    segmentSpec = latestSpec
                    segmentStartValue = animatable.value
                    segmentStartVelocity = getCurrentVelocity()
                    segmentDurationNanos = segmentSpec.getDurationNanos(
                        segmentStartValue, segmentTarget, segmentStartVelocity
                    )
                } else if (segmentTarget != latestTarget || segmentSpec !== latestSpec) {
                    segmentStartValue = animatable.value
                    segmentStartVelocity = getCurrentVelocity()
                    segmentTarget = latestTarget
                    segmentSpec = latestSpec
                    segmentStartTimeNanos = lastFrameNanos
                    segmentDurationNanos = segmentSpec.getDurationNanos(
                        segmentStartValue, segmentTarget, segmentStartVelocity
                    )
                }
                lastFrameNanos = frameTimeNanos
                val playTimeNanos = (frameTimeNanos - segmentStartTimeNanos).coerceAtLeast(0L)
                val isFinished = playTimeNanos >= segmentDurationNanos
                val nextValue = if (isFinished) {
                    setCurrentVelocity(0f)
                    segmentTarget
                } else {
                    setCurrentVelocity(
                        segmentSpec.getVelocityFromNanos(
                            playTimeNanos, segmentStartValue, segmentTarget, segmentStartVelocity
                        )
                    )
                    segmentSpec.getValueFromNanos(
                        playTimeNanos, segmentStartValue, segmentTarget, segmentStartVelocity
                    )
                }
                SpringStepResult(nextValue, isFinished, segmentTarget, segmentSpec)
            }
            animatable.snapTo(step.value)
            onStep()
            if (step.finished && step.target == getTarget() && step.spec === getSpec()) {
                break
            }
        }
    }
}
