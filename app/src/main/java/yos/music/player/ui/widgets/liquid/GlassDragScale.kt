package yos.music.player.ui.widgets.liquid

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import androidx.compose.ui.util.lerp
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh

/** 按压液态形变的最大位移（与 MeiloX IosAlertSurface 同值）。 */
internal const val GlassDragScaleMaxDp = 4f

/**
 * 跟手液态形变：tanh 位移 + 按手指方向各向异性的缩放。
 *
 * 这是「叠加」语义：在 [GraphicsLayerScope] 已有的 scale/translation 之上再乘、
 * 再加，不能整体覆盖 —— 确认框的入场 spring(1.15→1) 也写在同一个 layerBlock 里，
 * 覆盖会把入场动画顶掉。
 *
 * 与 MeiloX GlassSurface.applyGlassDragScale 的算式逐项一致；仅补了两点防护：
 * - 除以尺寸时统一 coerceAtLeast(1f)，避免极小控件出现除零/Inf；
 * - 静止态（progress=0 且 offset=0）严格恒等，不产生任何多余变换。
 */
internal fun GraphicsLayerScope.applyGlassDragScale(
    pressProgress: Float,
    offset: Offset,
) {
    val progress = pressProgress.coerceIn(0f, 1f)
    if (progress <= 0f && offset == Offset.Zero) return

    val maxDpPx = GlassDragScaleMaxDp.dp.toPx()
    val controlHeight = size.height.coerceAtLeast(1f)
    val width = size.width.coerceAtLeast(1f)
    val maxDimension = size.maxDimension.coerceAtLeast(1f)
    val maxOffset = size.minDimension.coerceAtLeast(1f)

    val baseScale = lerp(1f, 1f + maxDpPx / controlHeight, progress)
    val maxDragScale = maxDpPx / controlHeight
    val angle = atan2(offset.y, offset.x)

    translationX += maxOffset * tanh(0.05f * offset.x / maxOffset)
    translationY += maxOffset * tanh(0.05f * offset.y / maxOffset)

    val extraScaleX = maxDragScale * abs(cos(angle) * offset.x / maxDimension) *
        (width / controlHeight).fastCoerceAtMost(1f)
    val extraScaleY = maxDragScale * abs(sin(angle) * offset.y / maxDimension) *
        (controlHeight / width).fastCoerceAtMost(1f)

    scaleX *= (baseScale + extraScaleX)
    scaleY *= (baseScale + extraScaleY)
}
