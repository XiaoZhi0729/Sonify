package yos.music.player.ui.widgets.effects

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.dp

/**
 * 在 Compose 上实现 Add 的效果。
 *
 * 注意：如果要使用 .alpha() 设置透明度，则必须在该 Modifier 之后，或者使用 .graphicLayer { this.alpha = 0.5f } 类此。
 *
 * —— By Yos-X
 */
@Composable
fun Modifier.overlayEffect() = this.drawWithCache {
    val overlayPaint = Paint().apply {
        blendMode = BlendMode.Plus
    }
    // saveLayer 边界是硬裁剪框：向四周扩 24dp，
    // 避免切掉超出节点边界的绘制（如进度条 StrokeCap.Round 圆头、M3 Slider 轨道内缩补偿）
    val expand = 24.dp.toPx()
    val rect = Rect(-expand, -expand, size.width + expand, size.height + expand)

    onDrawWithContent {
        val canvas = this.drawContext.canvas

        canvas.saveLayer(rect, overlayPaint)

        drawContent()

        canvas.restore()
    }
}