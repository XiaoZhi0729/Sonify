package yos.music.player.ui.widgets.effects

import android.graphics.BlurMaskFilter
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ShadowType(val blur: Dp, val offsetY: Float, val offsetX: Float, val areaWeight: Float) {
    Large(24.dp, 0.08f, 0f, 0.94f),
    Medium(28.dp, 0.08f, 0f, 0.94f),
    Small(24.dp, 0.11f, 0f, 0.94f)
}

@Composable
fun Modifier.dropShadow(
    shape: Shape,
    shadowAlpha: Float,
    shadowType: ShadowType,
    overlay: Boolean = false
): Modifier = if (shadowAlpha == 0f) this else this.drawWithCache {
    val color = Color(0xFF000000).copy(alpha = shadowAlpha)

    val height = size.height
    val width = size.width

    val shadowSize = Size(width * shadowType.areaWeight, height * shadowType.areaWeight)
    val shadowOutline = shape.createOutline(shadowSize, layoutDirection, this)

    val paint = Paint().apply {
        this.color = color
        if (overlay) {
            this.blendMode = BlendMode.Overlay
        }
    }

    val blurPx = shadowType.blur.toPx()

    val offsetX = shadowType.offsetX * width + (width * (1f - shadowType.areaWeight)) / 2
    val offsetY = shadowType.offsetY * height + (height * (1f - shadowType.areaWeight)) / 2

    onDrawBehind {
        if (blurPx > 0) {
            paint.asFrameworkPaint().apply {
                maskFilter = BlurMaskFilter(blurPx, BlurMaskFilter.Blur.NORMAL)
            }
        }

            val canvas = this.drawContext.canvas
        canvas.save()
            canvas.translate(
                offsetX,
                offsetY
            )
            canvas.drawOutline(shadowOutline, paint)
        canvas.restore()

    }
}