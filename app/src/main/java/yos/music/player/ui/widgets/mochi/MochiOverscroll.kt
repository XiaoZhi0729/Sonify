package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import com.cormor.overscroll.core.overScrollHorizontal
import kotlin.math.abs
import kotlin.math.sign

/**
 * 水平滑动列表 / Pager 的 MOCHI 越界弹性回弹（替代系统原生拉伸）。
 * 默认不向父级竖向列表透传水平嵌套滚动，避免与纵向滚动/下拉刷新互相干扰。
 */
fun Modifier.mochiHorizontalOverscroll(
    nestedScrollToParent: Boolean = false,
): Modifier = overScrollHorizontal(nestedScrollToParent = nestedScrollToParent)

internal fun cannotScrollInDirection(
    canScrollForward: Boolean,
    canScrollBackward: Boolean,
    velocity: Float,
): Boolean = (velocity > 0f && !canScrollForward) || (velocity < 0f && !canScrollBackward)

internal fun coerceScrollValueToTarget(value: Float, target: Float): Float = when {
    target == 0f -> 0f
    target > 0f -> value.coerceAtMost(target)
    else -> value.coerceAtLeast(target)
}

internal fun calculateLazyListBoundaryDistancePx(
    velocity: Float,
    totalItemsCount: Int,
    viewportEndOffset: Int,
    afterContentPadding: Int,
    firstItemOffset: Int?,
    lastItemOffset: Int?,
    lastItemSize: Int?,
): Float? {
    if (totalItemsCount <= 0 || velocity == 0f) return null
    return when {
        velocity < 0f -> firstItemOffset?.let { (-it).coerceAtLeast(0).toFloat() }
        lastItemOffset != null && lastItemSize != null ->
            ((lastItemOffset + lastItemSize + afterContentPadding) - viewportEndOffset)
                .coerceAtLeast(0)
                .toFloat()

        else -> null
    }
}

internal fun calculatePagerMaxScrollOffsetPx(
    pageCount: Int,
    pageSize: Int,
    pageSpacing: Int,
    beforeContentPadding: Int,
    afterContentPadding: Int,
    viewportMainAxisSize: Int,
): Long {
    val pageStep = pageSize + pageSpacing
    if (pageCount <= 0 || pageStep <= 0) return 0L
    val totalContent =
        pageCount.toLong() * pageStep + beforeContentPadding + afterContentPadding - pageSpacing
    return (totalContent - viewportMainAxisSize).coerceAtLeast(0L)
}

internal fun calculatePagerBoundaryDistancePx(
    velocity: Float,
    pageCount: Int,
    pageSize: Int,
    pageSpacing: Int,
    beforeContentPadding: Int,
    afterContentPadding: Int,
    viewportMainAxisSize: Int,
    visiblePageIndex: Int,
    visiblePageOffset: Int,
): Float? {
    val pageStep = pageSize + pageSpacing
    if (pageCount <= 0 || pageStep <= 0 || velocity == 0f) return null
    val maxScroll = calculatePagerMaxScrollOffsetPx(
        pageCount = pageCount,
        pageSize = pageSize,
        pageSpacing = pageSpacing,
        beforeContentPadding = beforeContentPadding,
        afterContentPadding = afterContentPadding,
        viewportMainAxisSize = viewportMainAxisSize,
    )
    val currentScroll =
        (visiblePageIndex.toLong() * pageStep - visiblePageOffset.toLong()).coerceIn(0L, maxScroll)
    return if (velocity < 0f) {
        currentScroll.toFloat()
    } else {
        (maxScroll - currentScroll).coerceAtLeast(0L).toFloat()
    }
}

internal fun shouldDecayIntoBoundary(decayOffset: Float, distanceToBoundaryPx: Float?): Boolean =
    distanceToBoundaryPx != null && abs(decayOffset) > distanceToBoundaryPx

private fun ScrollScope.safeScrollBy(delta: Float): Float? = try {
    scrollBy(delta)
} catch (_: IllegalStateException) {
    // 布局中途（如 Placement happened before lookahead）可能抛出 IllegalStateException，安全退出动画。
    null
}

internal suspend fun ScrollScope.performMochiBoundaryDecay(
    initialVelocity: Float,
    decayAnimationSpec: DecayAnimationSpec<Float>,
    canScrollForward: () -> Boolean,
    canScrollBackward: () -> Boolean,
    onProgress: (consumedValue: Float) -> Unit = {},
): Pair<Boolean, Float> {
    var lastValue = 0f
    var velocityLeft = initialVelocity
    var hitBoundary = false
    AnimationState(
        initialValue = 0f,
        initialVelocity = initialVelocity,
    ).animateDecay(decayAnimationSpec) {
        val delta = value - lastValue
        val consumed = safeScrollBy(delta)
        if (consumed == null) {
            velocityLeft = this.velocity
            cancelAnimation()
            return@animateDecay
        }
        lastValue = value
        velocityLeft = this.velocity
        onProgress(value)
        if (abs(delta - consumed) > 0.5f ||
            cannotScrollInDirection(canScrollForward(), canScrollBackward(), velocityLeft)
        ) {
            hitBoundary = true
            cancelAnimation()
        }
    }
    return hitBoundary to velocityLeft
}

/**
 * 为横向吸附 [androidx.compose.foundation.lazy.LazyRow] 提供适配 MOCHI 越界弹性回弹的 [TargetedFlingBehavior]。
 *
 * 原生 [androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior] 在吸附阶段到达首/尾时会把
 * `remainingOffset` 归零并返回 `0f` 剩余速度，导致外层 `overScrollHorizontal` 收不到撞边动能而无法回弹。
 * 这里保留正常卡片间的惯性+磁性吸附，并在预测惯性将越过首/尾边界时直接减速撞边、把剩余速度还给 MOCHI 弹簧。
 */
@Composable
fun rememberMochiSnapFlingBehavior(
    lazyListState: LazyListState,
    snapPosition: SnapPosition = SnapPosition.Start,
    decayAnimationSpec: DecayAnimationSpec<Float> = rememberSplineBasedDecay(),
    snapAnimationSpec: AnimationSpec<Float> = spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = Int.VisibilityThreshold.toFloat(),
    ),
): TargetedFlingBehavior {
    val density = LocalDensity.current
    val snapLayoutInfoProvider = remember(lazyListState, snapPosition) {
        SnapLayoutInfoProvider(lazyListState, snapPosition)
    }
    return remember(lazyListState, snapLayoutInfoProvider, decayAnimationSpec, snapAnimationSpec, density) {
        MochiLazyListSnapFlingBehavior(
            lazyListState = lazyListState,
            snapLayoutInfoProvider = snapLayoutInfoProvider,
            decayAnimationSpec = decayAnimationSpec,
            snapAnimationSpec = snapAnimationSpec,
        )
    }
}

internal class MochiLazyListSnapFlingBehavior(
    private val lazyListState: LazyListState,
    private val snapLayoutInfoProvider: SnapLayoutInfoProvider,
    private val decayAnimationSpec: DecayAnimationSpec<Float>,
    private val snapAnimationSpec: AnimationSpec<Float>,
) : TargetedFlingBehavior {

    private fun boundaryDistancePx(velocity: Float): Float? {
        val layoutInfo = lazyListState.layoutInfo
        val totalCount = layoutInfo.totalItemsCount
        val visibleItems = layoutInfo.visibleItemsInfo
        val firstItem = visibleItems.firstOrNull { it.index == 0 }
        val lastItem = visibleItems.lastOrNull { it.index == totalCount - 1 }
        return calculateLazyListBoundaryDistancePx(
            velocity = velocity,
            totalItemsCount = totalCount,
            viewportEndOffset = layoutInfo.viewportEndOffset,
            afterContentPadding = layoutInfo.afterContentPadding,
            firstItemOffset = firstItem?.offset,
            lastItemOffset = lastItem?.offset,
            lastItemSize = lastItem?.size,
        )
    }

    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        if (cannotScrollInDirection(lazyListState.canScrollForward, lazyListState.canScrollBackward, initialVelocity)) {
            onRemainingDistanceUpdated(0f)
            return initialVelocity
        }

        val decayOffset = decayAnimationSpec.calculateTargetValue(0f, initialVelocity)
        val approachOffset =
            abs(snapLayoutInfoProvider.calculateApproachOffset(initialVelocity, decayOffset)) *
                sign(initialVelocity)
        var continuingToBoundary =
            shouldDecayIntoBoundary(decayOffset, boundaryDistancePx(initialVelocity))

        var phase2Velocity = initialVelocity
        if (abs(initialVelocity) > 1f && (abs(approachOffset) > 0f || continuingToBoundary)) {
            var lastValue = 0f
            var velocityLeft = initialVelocity
            var hitBoundary = false
            AnimationState(
                initialValue = 0f,
                initialVelocity = initialVelocity,
            ).animateDecay(decayAnimationSpec) {
                if (!continuingToBoundary && abs(value) >= abs(approachOffset)) {
                    val remDecay = decayAnimationSpec.calculateTargetValue(0f, this.velocity)
                    if (shouldDecayIntoBoundary(remDecay, boundaryDistancePx(this.velocity))) {
                        continuingToBoundary = true
                    }
                }

                if (!continuingToBoundary && abs(value) >= abs(approachOffset)) {
                    val targetVal = coerceScrollValueToTarget(value, approachOffset)
                    val delta = targetVal - lastValue
                    val consumed = safeScrollBy(delta)
                    if (consumed == null) {
                        velocityLeft = this.velocity
                        cancelAnimation()
                        return@animateDecay
                    }
                    lastValue = targetVal
                    velocityLeft = this.velocity
                    if (abs(delta - consumed) > 0.5f ||
                        cannotScrollInDirection(
                            lazyListState.canScrollForward,
                            lazyListState.canScrollBackward,
                            velocityLeft,
                        )
                    ) {
                        hitBoundary = true
                    }
                    cancelAnimation()
                } else {
                    val delta = value - lastValue
                    val consumed = safeScrollBy(delta)
                    if (consumed == null) {
                        velocityLeft = this.velocity
                        cancelAnimation()
                        return@animateDecay
                    }
                    lastValue = value
                    velocityLeft = this.velocity
                    onRemainingDistanceUpdated(
                        if (continuingToBoundary) 0f else (approachOffset - value),
                    )
                    if (abs(delta - consumed) > 0.5f ||
                        cannotScrollInDirection(
                            lazyListState.canScrollForward,
                            lazyListState.canScrollBackward,
                            velocityLeft,
                        )
                    ) {
                        hitBoundary = true
                        cancelAnimation()
                    }
                }
            }
            phase2Velocity = velocityLeft
            if (hitBoundary &&
                cannotScrollInDirection(
                    lazyListState.canScrollForward,
                    lazyListState.canScrollBackward,
                    velocityLeft,
                )
            ) {
                onRemainingDistanceUpdated(0f)
                return velocityLeft
            }
        }

        val snapOffset = snapLayoutInfoProvider.calculateSnapOffset(phase2Velocity)
        onRemainingDistanceUpdated(snapOffset)
        if (snapOffset == 0f) {
            onRemainingDistanceUpdated(0f)
            return if (cannotScrollInDirection(
                    lazyListState.canScrollForward,
                    lazyListState.canScrollBackward,
                    phase2Velocity,
                )
            ) {
                phase2Velocity
            } else {
                0f
            }
        }

        var lastSnapValue = 0f
        var snapVelocityLeft = phase2Velocity
        var snapHitBoundary = false
        AnimationState(
            initialValue = 0f,
            initialVelocity = phase2Velocity,
        ).animateTo(
            targetValue = snapOffset,
            animationSpec = snapAnimationSpec,
            sequentialAnimation = phase2Velocity != 0f,
        ) {
            val coerced = coerceScrollValueToTarget(value, snapOffset)
            val delta = coerced - lastSnapValue
            val consumed = safeScrollBy(delta)
            if (consumed == null) {
                snapVelocityLeft = this.velocity
                cancelAnimation()
                return@animateTo
            }
            lastSnapValue = coerced
            snapVelocityLeft = this.velocity
            onRemainingDistanceUpdated(snapOffset - coerced)
            if (abs(delta - consumed) > 0.5f ||
                cannotScrollInDirection(
                    lazyListState.canScrollForward,
                    lazyListState.canScrollBackward,
                    snapVelocityLeft,
                )
            ) {
                snapHitBoundary = true
                cancelAnimation()
            } else if (coerced != value) {
                cancelAnimation()
            }
        }
        onRemainingDistanceUpdated(0f)
        return if (snapHitBoundary &&
            cannotScrollInDirection(
                lazyListState.canScrollForward,
                lazyListState.canScrollBackward,
                snapVelocityLeft,
            )
        ) {
            snapVelocityLeft
        } else {
            0f
        }
    }
}

/**
 * 为 [androidx.compose.foundation.pager.HorizontalPager] 提供适配 MOCHI 越界弹性回弹的 [TargetedFlingBehavior]。
 *
 * 中间翻页完全委托给 [PagerDefaults.flingBehavior] 保持原生分页吸附手感；当惯性距离足以冲到首/尾边缘时，
 * 直接完成惯性滑行并将撞边剩余速度交给外层 `overScrollHorizontal` 触发 MOCHI 回弹。
 */
@Composable
fun rememberMochiPagerFlingBehavior(
    state: PagerState,
    pagerSnapDistance: PagerSnapDistance = PagerSnapDistance.atMost(1),
    decayAnimationSpec: DecayAnimationSpec<Float> = rememberSplineBasedDecay(),
    snapAnimationSpec: AnimationSpec<Float> = spring(
        stiffness = Spring.StiffnessMediumLow,
        visibilityThreshold = Int.VisibilityThreshold.toFloat(),
    ),
    snapPositionalThreshold: Float = 0.5f,
): TargetedFlingBehavior {
    val delegate = PagerDefaults.flingBehavior(
        state = state,
        pagerSnapDistance = pagerSnapDistance,
        decayAnimationSpec = decayAnimationSpec,
        snapAnimationSpec = snapAnimationSpec,
        snapPositionalThreshold = snapPositionalThreshold,
    )
    return remember(state, pagerSnapDistance, decayAnimationSpec, delegate) {
        MochiPagerSnapFlingBehavior(
            state = state,
            pagerSnapDistance = pagerSnapDistance,
            decayAnimationSpec = decayAnimationSpec,
            delegate = delegate,
        )
    }
}

internal class MochiPagerSnapFlingBehavior(
    private val state: PagerState,
    private val pagerSnapDistance: PagerSnapDistance,
    private val decayAnimationSpec: DecayAnimationSpec<Float>,
    private val delegate: TargetedFlingBehavior,
) : TargetedFlingBehavior {

    override suspend fun ScrollScope.performFling(
        initialVelocity: Float,
        onRemainingDistanceUpdated: (Float) -> Unit,
    ): Float {
        if (cannotScrollInDirection(state.canScrollForward, state.canScrollBackward, initialVelocity)) {
            onRemainingDistanceUpdated(0f)
            return initialVelocity
        }

        val layoutInfo = state.layoutInfo
        val firstVisible = layoutInfo.visiblePagesInfo.firstOrNull()
        val pageStep = layoutInfo.pageSize + layoutInfo.pageSpacing
        if (firstVisible != null && pageStep > 0 && state.pageCount > 0 && abs(initialVelocity) > 1f) {
            val decayOffset = decayAnimationSpec.calculateTargetValue(0f, initialVelocity)
            val viewportMainAxis = if (layoutInfo.orientation == Orientation.Horizontal) {
                layoutInfo.viewportSize.width
            } else {
                layoutInfo.viewportSize.height
            }
            val distanceToBoundaryPx = calculatePagerBoundaryDistancePx(
                velocity = initialVelocity,
                pageCount = state.pageCount,
                pageSize = layoutInfo.pageSize,
                pageSpacing = layoutInfo.pageSpacing,
                beforeContentPadding = layoutInfo.beforeContentPadding,
                afterContentPadding = layoutInfo.afterContentPadding,
                viewportMainAxisSize = viewportMainAxis,
                visiblePageIndex = firstVisible.index,
                visiblePageOffset = firstVisible.offset,
            )
            val maxScroll = calculatePagerMaxScrollOffsetPx(
                pageCount = state.pageCount,
                pageSize = layoutInfo.pageSize,
                pageSpacing = layoutInfo.pageSpacing,
                beforeContentPadding = layoutInfo.beforeContentPadding,
                afterContentPadding = layoutInfo.afterContentPadding,
                viewportMainAxisSize = viewportMainAxis,
            )
            val maxScrollPage = (maxScroll / pageStep).toInt()
            val naturalDeltaPages = (decayOffset / pageStep).toInt()
            val naturalTarget =
                (state.currentPage + naturalDeltaPages).coerceIn(0, (state.pageCount - 1).coerceAtLeast(0))
            val allowedTarget = pagerSnapDistance.calculateTargetPage(
                startPage = state.currentPage,
                suggestedTargetPage = naturalTarget,
                velocity = initialVelocity,
                pageSize = layoutInfo.pageSize,
                pageSpacing = layoutInfo.pageSpacing,
            )
            val reachesBoundary =
                (initialVelocity < 0f && allowedTarget <= 0) ||
                    (initialVelocity > 0f && allowedTarget >= maxScrollPage)

            if (reachesBoundary && shouldDecayIntoBoundary(decayOffset, distanceToBoundaryPx)) {
                val (hitBoundary, velocityLeft) = performMochiBoundaryDecay(
                    initialVelocity = initialVelocity,
                    decayAnimationSpec = decayAnimationSpec,
                    canScrollForward = { state.canScrollForward },
                    canScrollBackward = { state.canScrollBackward },
                ) { consumedValue ->
                    onRemainingDistanceUpdated(
                        coerceScrollValueToTarget(decayOffset - consumedValue, decayOffset),
                    )
                }
                if (hitBoundary &&
                    cannotScrollInDirection(state.canScrollForward, state.canScrollBackward, velocityLeft)
                ) {
                    onRemainingDistanceUpdated(0f)
                    return velocityLeft
                }
                return with(delegate) {
                    performFling(velocityLeft, onRemainingDistanceUpdated)
                }
            }
        }

        return with(delegate) {
            performFling(initialVelocity, onRemainingDistanceUpdated)
        }
    }
}
