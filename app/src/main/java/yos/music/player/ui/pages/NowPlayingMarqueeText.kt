package yos.music.player.ui.pages

import androidx.compose.foundation.MarqueeSpacing
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import yos.music.player.ui.widgets.effects.overlayEffect

/**
 * 播放页歌名/歌手跑马灯文本：文本未超出可用宽度时走零开销普通 Text；
 * 超出时启用平滑横向滚动，并在左右边缘用 `BlendMode.DstOut` 绘制渐隐过渡，
 * 左侧向外延伸 [leftFadeWidth]（在父级原有左边距内留白淡出），避免静止首字被削且无硬切边界。
 */
@Composable
internal fun FadingMarqueeText(
    text: String,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    marqueeEnabled: Boolean = true,
    leftFadeWidth: Dp = 14.dp,
    rightFadeWidth: Dp = 20.dp,
    useOverlayEffect: Boolean = false,
    textModifier: Modifier = Modifier,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    FadingMarqueeText(
        text = AnnotatedString(text),
        fontSize = fontSize,
        modifier = modifier,
        color = color,
        fontWeight = fontWeight,
        lineHeight = lineHeight,
        marqueeEnabled = marqueeEnabled,
        leftFadeWidth = leftFadeWidth,
        rightFadeWidth = rightFadeWidth,
        useOverlayEffect = useOverlayEffect,
        textModifier = textModifier,
        onTextLayout = onTextLayout,
    )
}

@Composable
internal fun FadingMarqueeText(
    text: AnnotatedString,
    fontSize: TextUnit,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontWeight: FontWeight? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    marqueeEnabled: Boolean = true,
    leftFadeWidth: Dp = 14.dp,
    rightFadeWidth: Dp = 20.dp,
    useOverlayEffect: Boolean = false,
    textModifier: Modifier = Modifier,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseStyle = LocalTextStyle.current
    val mergedStyle = remember(baseStyle, fontSize, fontWeight, lineHeight) {
        baseStyle.merge(
            TextStyle(
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = lineHeight,
            )
        )
    }
    val measuredSize = remember(text, mergedStyle, textMeasurer) {
        runCatching {
            textMeasurer.measure(
                text = text,
                style = mergedStyle,
                maxLines = 1,
                softWrap = false,
            ).size
        }.getOrNull()
    }

    var availableWidthPx by remember { mutableIntStateOf(0) }
    val overflow = availableWidthPx > 0 && (measuredSize?.width ?: 0) > availableWidthPx + 1
    val minHeightDp = remember(measuredSize, density) {
        measuredSize?.height?.let { with(density) { it.toDp() } }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .then(if (minHeightDp != null) Modifier.heightIn(min = minHeightDp) else Modifier)
            .onSizeChanged { availableWidthPx = it.width }
    ) {
        if (!overflow) {
            Text(
                text = text,
                fontSize = fontSize,
                fontWeight = fontWeight,
                lineHeight = lineHeight,
                color = color,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Clip,
                modifier = Modifier
                    .then(if (useOverlayEffect) Modifier.overlayEffect() else Modifier)
                    .then(textModifier),
                onTextLayout = onTextLayout ?: {},
            )
        } else {
            val leftFadePx = with(density) { leftFadeWidth.toPx() }
                .coerceAtMost(availableWidthPx * 0.2f)
            val rightFadePx = with(density) { rightFadeWidth.toPx() }
                .coerceAtMost(availableWidthPx * 0.2f)
            val bleedPx = leftFadePx.roundToInt()
            val bleedDp = with(density) { bleedPx.toDp() }

            Box(
                modifier = Modifier
                    .layout { measurable, constraints ->
                        val expandedMax = if (constraints.hasBoundedWidth) {
                            constraints.maxWidth + bleedPx
                        } else {
                            constraints.maxWidth
                        }
                        val expandedMin = (constraints.minWidth + bleedPx).coerceAtMost(expandedMax)
                        val placeable = measurable.measure(
                            constraints.copy(minWidth = expandedMin, maxWidth = expandedMax)
                        )
                        layout((placeable.width - bleedPx).coerceAtLeast(0), placeable.height) {
                            placeable.placeRelative(x = -bleedPx, y = 0)
                        }
                    }
                    .then(
                        if (useOverlayEffect) {
                            // overlayEffect 自带 Offscreen + BlendMode.Plus，不能再外包 SrcOver Offscreen 层
                            Modifier.overlayEffect()
                        } else {
                            Modifier.graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                        }
                    )
                    .drawWithContent {
                        drawContent()
                        if (leftFadePx > 0.5f && size.width > leftFadePx) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Black,
                                    1f to Color.Transparent,
                                    startX = 0f,
                                    endX = leftFadePx,
                                ),
                                topLeft = Offset.Zero,
                                size = Size(leftFadePx, size.height),
                                blendMode = BlendMode.DstOut,
                            )
                        }
                        if (rightFadePx > 0.5f && size.width > rightFadePx) {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    0f to Color.Transparent,
                                    1f to Color.Black,
                                    startX = size.width - rightFadePx,
                                    endX = size.width,
                                ),
                                topLeft = Offset(size.width - rightFadePx, 0f),
                                size = Size(rightFadePx, size.height),
                                blendMode = BlendMode.DstOut,
                            )
                        }
                    }
            ) {
                Text(
                    text = text,
                    fontSize = fontSize,
                    fontWeight = fontWeight,
                    lineHeight = lineHeight,
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .fillMaxWidth()
                        .basicMarquee(
                            iterations = if (marqueeEnabled) Int.MAX_VALUE else 0,
                            repeatDelayMillis = 2000,
                            initialDelayMillis = 1500,
                            spacing = MarqueeSpacing(40.dp),
                            velocity = 34.dp,
                        )
                        .padding(start = bleedDp)
                        .then(textModifier),
                    onTextLayout = onTextLayout ?: {},
                )
            }
        }
    }
}
