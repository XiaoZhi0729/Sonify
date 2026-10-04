/*
 * Adapted from Cresto GlasenseHighlight/GlasenseDialog and Glasense DimIndication,
 * Apache-2.0. Modified: local names, explicit indication content color.
 * See assets/licenses/cresto-NOTICE.txt.
 */
package yos.music.player.ui.widgets.basic

import android.graphics.BlurMaskFilter
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

internal fun Modifier.crestoHighlight(shape: Shape): Modifier = drawWithCache {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = outline.asPath()
    val brush = Brush.verticalGradient(
        0f to Color.White.copy(alpha = 0.2f),
        1f to Color.White.copy(alpha = 0.02f)
    )
    onDrawWithContent {
        drawContent()
        clipPath(path) {
            drawOutline(outline, brush, style = Stroke(3.dp.toPx()), blendMode = BlendMode.Plus)
        }
    }
}

internal fun Modifier.crestoDialogShadow(shape: Shape, dark: Boolean, alpha: () -> Float): Modifier = drawWithCache {
    val path = shape.createOutline(size, layoutDirection, this).asPath().asAndroidPath()
    val paint = Paint().nativePaint.apply {
        isAntiAlias = true
        maskFilter = BlurMaskFilter(32.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
    }
    val baseAlpha = if (dark) 0.2f else 0.1f
    onDrawWithContent {
        paint.color = Color.Black.copy(alpha = baseAlpha * alpha()).toArgb()
        drawIntoCanvas { canvas ->
            canvas.save()
            canvas.translate(0f, 16.dp.toPx())
            canvas.nativeCanvas.drawPath(path, paint)
            canvas.restore()
        }
        drawContent()
    }
}

private fun Outline.asPath(): Path = when (this) {
    is Outline.Generic -> path
    is Outline.Rounded -> Path().apply { addRoundRect(roundRect) }
    is Outline.Rectangle -> Path().apply { addRect(rect) }
}

internal data class CrestoDimIndication(val color: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = DimNode(interactionSource, color)
}

private class DimNode(private val source: InteractionSource, private val color: Color) : Modifier.Node(), DrawModifierNode {
    private val alpha = Animatable(0f)
    override fun onAttach() {
        coroutineScope.launch {
            source.interactions.collectLatest { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> alpha.animateTo(0.1f, tween(150))
                    is PressInteraction.Release -> {
                        if (alpha.value < 0.05f) alpha.animateTo(0.05f, tween(100))
                        alpha.animateTo(0f, tween(200))
                    }
                    is PressInteraction.Cancel -> alpha.animateTo(0f, tween(200))
                }
            }
        }
    }
    override fun ContentDrawScope.draw() {
        drawContent()
        if (alpha.value > 0f) drawRect(color.copy(alpha = alpha.value))
    }
}
