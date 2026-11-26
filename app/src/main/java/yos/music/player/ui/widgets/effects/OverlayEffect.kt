package yos.music.player.ui.widgets.effects

import android.os.Build
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 在 Compose 上实现 Add 的效果。
 *
 * API 29+ 且无需额外扩边（`expand == 0.dp`）时优先使用 `graphicsLayer(Offscreen, BlendMode.Plus)`，
 * 让静止元素直接命中 HWUI RenderNode 硬件层缓存，避免每帧重新分配/录制 `saveLayer`；
 * 需要向外扩边避免圆头/轨道被裁剪（如进度条/音量条）或 API < 29 时，走 `saveLayer` 扩边路径。
 *
 * 注意：如果要使用 .alpha() 设置透明度，则必须在该 Modifier 之后，或者使用 .graphicsLayer { this.alpha = 0.5f } 类此。
 *
 * —— By Yos-X
 */
fun Modifier.overlayEffect(expand: Dp = 0.dp): Modifier {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && expand == 0.dp) {
        return this.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            blendMode = BlendMode.Plus
            clip = false
        }
    }
    return this.drawWithCache {
        val overlayPaint = Paint().apply {
            blendMode = BlendMode.Plus
        }
        val expandPx = expand.toPx()
        val rect = Rect(-expandPx, -expandPx, size.width + expandPx, size.height + expandPx)

        onDrawWithContent {
            val canvas = this.drawContext.canvas

            canvas.saveLayer(rect, overlayPaint)

            drawContent()

            canvas.restore()
        }
    }
}

/**
 * 支持在绘制期动态读取 [alpha] 的 [overlayEffect] 重载，避免动画/滚动期间
 * 因颜色 alpha 变化触发组合重组，同时保证不引入父级离屏缓冲而破坏 `BlendMode.Plus`。
 */
fun Modifier.overlayEffect(
    expand: Dp = 0.dp,
    alpha: () -> Float
): Modifier {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && expand == 0.dp) {
        return this.graphicsLayer {
            compositingStrategy = CompositingStrategy.Offscreen
            blendMode = BlendMode.Plus
            clip = false
            this.alpha = alpha()
        }
    }
    return this.drawWithCache {
        val overlayPaint = Paint().apply {
            blendMode = BlendMode.Plus
        }
        val expandPx = expand.toPx()
        val rect = Rect(-expandPx, -expandPx, size.width + expandPx, size.height + expandPx)

        onDrawWithContent {
            val currentAlpha = alpha()
            if (currentAlpha <= 0f) return@onDrawWithContent
            overlayPaint.alpha = currentAlpha
            val canvas = this.drawContext.canvas

            canvas.saveLayer(rect, overlayPaint)

            drawContent()

            canvas.restore()
        }
    }
}
