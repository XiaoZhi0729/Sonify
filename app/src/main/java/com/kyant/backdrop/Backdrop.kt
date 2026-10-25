package com.kyant.backdrop

import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.unit.Density

interface Backdrop {

    val isCoordinatesDependent: Boolean

    /**
     * **已烘焙**的模糊源层：模糊结果在预渲染时就固化进这张层，
     * 采样侧 drawLayer 时不再有任何模糊运算。
     *
     * 非 null 时实现应当在 [drawBackdrop] 里画它，而不是原始源层。
     */
    val blurredLayer: GraphicsLayer? get() = null

    /**
     * 源内容版本号。
     * 保留仅为兼容：采样链路已回退到源库实现（每帧独立录制），不再用它做跳过判断。
     */
    val contentVersion: Int get() = 0

    fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)? = null
    )
}
