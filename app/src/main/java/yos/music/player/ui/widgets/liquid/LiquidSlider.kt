package yos.music.player.ui.widgets.liquid

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceIn
import androidx.compose.ui.util.fastRoundToInt
import androidx.compose.ui.util.lerp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberBackdrop
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import kotlinx.coroutines.flow.collectLatest
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.mochi.MochiSlider

/**
 * 液态玻璃滑动条（逐行移植自 AndroidLiquidGlass 1.0.5 catalog 的 `LiquidSlider`）。
 *
 * 轨道是胶囊 + 进度填充，拇指是一颗 40×24dp 的玻璃胶囊：静息时 blur 8dp，
 * 按下/拖动时 blur→0、lens 折射 + 环境高光 + 内阴影一起上，随拖动速度轻微拉伸；
 * 点按轨道任意位置会 animateToValue 并回调。
 *
 * 差异：暗色判定改用 [isFlamingoInDarkMode]；`visibilityThreshold` 给了默认值。
 *
 * [backdrop] 必须是"不会包含自身"的采样源 —— 本页在 `Title` 的录制子树内，
 * 传页面 backdrop 会自引用递归（SIGSEGV），设置页内按 LiquidSwitch 的先例传画布色兜底。
 */
@Composable
fun LiquidSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    backdrop: Backdrop,
    modifier: Modifier = Modifier,
    visibilityThreshold: Float = 0.001f
) {
    val isLightTheme = !isFlamingoInDarkMode()
    val accentColor =
        if (isLightTheme) Color(0xFF0088FF)
        else Color(0xFF0091FF)
    val trackColor =
        if (isLightTheme) Color(0xFF787878).copy(0.2f)
        else Color(0xFF787880).copy(0.36f)

    val trackBackdrop = rememberLayerBackdrop()
    var trackWidth by remember { mutableIntStateOf(0) }

    Box(
        modifier
            .fillMaxWidth()
            .onSizeChanged { trackWidth = it.width }
            .testTag("liquid_slider"),
        contentAlignment = Alignment.CenterStart
    ) {
        val isLtr = LocalLayoutDirection.current == LayoutDirection.Ltr
        val animationScope = rememberCoroutineScope()
        val currentValue by rememberUpdatedState(value)
        val currentOnValueChange by rememberUpdatedState(onValueChange)
        var didDrag by remember { mutableStateOf(false) }
        var isInteracting by remember { mutableStateOf(false) }
        var downX by remember { mutableFloatStateOf(0f) }

        fun xToValue(x: Float): Float {
            val safeWidth = trackWidth.coerceAtLeast(1)
            val p = (x / safeWidth).coerceIn(0f, 1f)
            val span = valueRange.endInclusive - valueRange.start
            return (if (isLtr) valueRange.start + span * p else valueRange.endInclusive - span * p)
                .coerceIn(valueRange)
        }

        val dampedDragAnimation = remember(animationScope) {
            DampedDragAnimation(
                animationScope = animationScope,
                initialValue = currentValue(),
                valueRange = valueRange,
                visibilityThreshold = visibilityThreshold,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStopped = {
                    val next = if (didDrag) targetValue else xToValue(downX)
                    if (!didDrag) {
                        updateValue(next)
                    }
                    currentOnValueChange(next)
                    didDrag = false
                    isInteracting = false
                },
                onDragCancelled = {
                    if (didDrag) {
                        currentOnValueChange(targetValue)
                    }
                    didDrag = false
                    isInteracting = false
                },
                onDrag = { _, dragAmount ->
                    if (!didDrag) {
                        didDrag = dragAmount.x != 0f
                    }
                    val safeWidth = trackWidth.coerceAtLeast(1)
                    val delta = (valueRange.endInclusive - valueRange.start) * (dragAmount.x / safeWidth)
                    val next = (if (isLtr) targetValue + delta else targetValue - delta).coerceIn(valueRange)
                    dragTo(next)
                    currentOnValueChange(next)
                },
                onDragStarted = { position ->
                    // 记录落点但不立即跳转：拖动时 1:1 跟手，未越过 slop 抬手走点按跳转，
                    // 纵向滚动抢占（onDragCancelled）则不误改数值。
                    isInteracting = true
                    didDrag = false
                    downX = position.x
                },
                // 页面是 LazyColumn：只认领横向拖动，纵向滑动留给页面滚动
                horizontalClaim = true
            )
        }
        LaunchedEffect(dampedDragAnimation) {
            snapshotFlow { currentValue() }
                .collectLatest { v ->
                    if (!isInteracting && dampedDragAnimation.targetValue != v) {
                        dampedDragAnimation.updateValue(v)
                    }
                }
        }

        Box(
            Modifier.layerBackdrop(trackBackdrop),
            contentAlignment = Alignment.CenterStart,
        ) {
            // 轨道（纯绘制）：trackBackdrop 仅包住 6dp 轨道本身，与 demo 对齐；
            // 44dp 全行命中区放在下面同级节点上。
            Box(
                Modifier
                    .clip(Capsule)
                    .background(trackColor)
                    .height(6f.dp)
                    .fillMaxWidth()
            )

            Box(
                Modifier
                    .clip(Capsule)
                    .background(accentColor)
                    .height(6f.dp)
                    .layout { measurable, constraints ->
                        val placeable = measurable.measure(constraints)
                        val width = (constraints.maxWidth * dampedDragAnimation.progress).fastRoundToInt()
                        layout(width, placeable.height) {
                            placeable.place(0, 0)
                        }
                    }
            )
        }

        // 44dp 全行透明命中区（与视觉 6dp 轨道垂直居中对齐），不撑高 trackBackdrop。
        Box(
            Modifier
                .fillMaxWidth()
                .height(44.dp)
                .then(dampedDragAnimation.modifier)
        )

        Box(
            Modifier
                .graphicsLayer {
                    val width = trackWidth
                    translationX = if (width <= 0) {
                        0f
                    } else {
                        (-size.width / 2f + width * dampedDragAnimation.progress)
                            .fastCoerceIn(-size.width / 4f, width - size.width * 3f / 4f) * if (isLtr) 1f else -1f
                    }
                }
                // ★拇指上**不再挂手势**：手势在下面整行的 44dp 命中层上。
                //   两处都挂的话是同一个 pointerInput 双份循环，互相抢事件
                //   （按下时谁先进入 awaitEachGesture 谁赢，另一个永远收不到抬手）。
                .drawBackdrop(
                    backdrop = rememberCombinedBackdrop(
                        backdrop,
                        rememberBackdrop(trackBackdrop) { drawBackdrop ->
                            val progress = dampedDragAnimation.pressProgress
                            val scaleX = lerp(2f / 3f, 1f, progress)
                            val scaleY = lerp(0f, 1f, progress)
                            scale(scaleX, scaleY) {
                                drawBackdrop()
                            }
                        }
                    ),
                    shape = { Capsule },
                    effects = {
                        val progress = dampedDragAnimation.pressProgress
                        blur(8f.dp.toPx() * (1f - progress))
                        lens(
                            10f.dp.toPx() * progress,
                            14f.dp.toPx() * progress,
                            chromaticAberration = true
                        )
                    },
                    highlight = {
                        val progress = dampedDragAnimation.pressProgress
                        Highlight.Ambient.copy(
                            width = Highlight.Ambient.width / 1.5f,
                            blurRadius = Highlight.Ambient.blurRadius / 1.5f,
                            alpha = progress
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 4f.dp,
                            color = Color.Black.copy(alpha = 0.05f)
                        )
                    },
                    innerShadow = {
                        val progress = dampedDragAnimation.pressProgress
                        InnerShadow(
                            radius = 4f.dp * progress,
                            alpha = progress
                        )
                    },
                    layerBlock = {
                        scaleX = dampedDragAnimation.scaleX
                        scaleY = dampedDragAnimation.scaleY
                        val velocity = dampedDragAnimation.velocity / 10f
                        scaleX /= 1f - (velocity * 0.75f).fastCoerceIn(-0.2f, 0.2f)
                        scaleY *= 1f - (velocity * 0.25f).fastCoerceIn(-0.2f, 0.2f)
                    },
                    onDrawSurface = {
                        val progress = dampedDragAnimation.pressProgress
                        drawRect(Color.White.copy(alpha = 1f - progress))
                    }
                )
                .size(40f.dp, 24f.dp)
        )
    }
}

/**
 * 统一滑块入口（与 [YosSwitch] 同一切换规则）：
 * 开启液态玻璃（[SettingsLibrary.BarBlurEffect] == true）时使用 [LiquidSlider]，
 * 否则使用 mochi-compose 的 [MochiSlider]。
 */
@Composable
fun YosSlider(
    value: () -> Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    contentBackdrop: Backdrop? = null,
    steps: Int = 0,
    origin: Float? = null,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val glassOn = SettingsLibrary.BarBlurEffect
    if (glassOn) {
        val cardColor = MaterialTheme.colorScheme.onSecondary
        val backdrop = contentBackdrop ?: rememberCanvasBackdrop {
            drawRect(cardColor.copy(alpha = 0.08f))
        }
        LiquidSlider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            backdrop = backdrop,
            modifier = modifier,
        )
    } else {
        MochiSlider(
            value = value(),
            onValueChange = onValueChange,
            modifier = modifier,
            valueRange = valueRange,
            steps = steps,
            origin = origin,
            onValueChangeFinished = onValueChangeFinished,
        )
    }
}
