package com.kyant.backdrop.effects

import androidx.annotation.FloatRange
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.TileMode
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.DOWNSAMPLE_SCALE
import com.kyant.backdrop.isRenderEffectSupported

fun BackdropEffectScope.blur(
    @FloatRange(from = 0.0) radius: Float,
    edgeTreatment: TileMode = TileMode.Clamp
) {
    if (!isRenderEffectSupported()) return
    if (radius <= 0f) return

    if (edgeTreatment != TileMode.Clamp || renderEffect != null) {
        if (radius > padding) {
            padding = radius
        }
    }

    // 模糊作用在降采样缓冲上，半径需同步 × (DOWNSAMPLE_SCALE * downsampleScale)，
    // 使 2x 放大上屏后的等效视觉扩散范围保持完全一致。
    val scaledRadius = radius * DOWNSAMPLE_SCALE * this.downsampleScale
    renderEffect =
        BlurEffect(
            renderEffect,
            scaledRadius,
            scaledRadius,
            edgeTreatment
        )
}
