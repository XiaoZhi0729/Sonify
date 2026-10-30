package com.kyant.backdrop.backdrops

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.Density
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.internal.InverseLayerScope

private val DefaultOnDraw: ContentDrawScope.() -> Unit = { drawContent() }

/**
 * @param blitRecordedContent 录制完成后直接把录制层画上屏（不再第二次 [drawContent]），
 *   前提是 onDraw 的产出一一对应节点该显示的像素（如「种子底色 + 内容」正好等于
 *   「页面底色 + 内容」）。默认 false 保持源库行为：onDraw 可能只为采样额外加了背景，
 *   上屏必须退回 drawContent() 才不丢内容。
 */
@Composable
fun rememberLayerBackdrop(
    graphicsLayer: GraphicsLayer = rememberGraphicsLayer(),
    blitRecordedContent: Boolean = false,
    onDraw: ContentDrawScope.() -> Unit = DefaultOnDraw
): LayerBackdrop {
    return remember(graphicsLayer, onDraw, blitRecordedContent) {
        LayerBackdrop(graphicsLayer, onDraw, blitRecordedContent)
    }
}

@Stable
class LayerBackdrop internal constructor(
    val graphicsLayer: GraphicsLayer,
    internal val onDraw: ContentDrawScope.() -> Unit,
    blitRecordedContent: Boolean = false
) : Backdrop {

    /**
     * 录制后能否直接把图层画上屏，使内容树只走一遍。
     * 为 true 的条件是「onDraw 的产出即节点应显示的像素」：默认 onDraw 天然满足，
     * 或调用方显式声明 [blitRecordedContent]。为 false 时 onDraw 可能额外画了背景等，
     * 上屏仍 drawContent()（内容会走两遍，但保证像素一致）。
     */
    internal val contentOnlyCapture: Boolean =
        blitRecordedContent || onDraw === DefaultOnDraw

    override val isCoordinatesDependent: Boolean = true

    override var contentVersion: Int = 0
        internal set

    internal var layerCoordinates: LayoutCoordinates? by mutableStateOf(null)

    private var inverseLayerScope: InverseLayerScope? = null

    override fun DrawScope.drawBackdrop(
        density: Density,
        coordinates: LayoutCoordinates?,
        layerBlock: (GraphicsLayerScope.() -> Unit)?
    ) {
        val coordinates = coordinates ?: return
        val layerCoordinates = layerCoordinates ?: return
        withTransform({
            if (layerBlock != null) {
                with(obtainInverseLayerScope()) { inverseTransform(density, layerBlock) }
            }
            val offset =
                try {
                    layerCoordinates.localPositionOf(coordinates)
                } catch (_: Exception) {
                    // TODO: outer transformations lead to wrong position calculation
                    coordinates.positionInWindow() - layerCoordinates.positionInWindow()
                }
            translate(-offset.x, -offset.y)
        }) {
            drawLayer(graphicsLayer)
        }
    }

    private fun obtainInverseLayerScope(): InverseLayerScope {
        return inverseLayerScope?.apply { reset() }
            ?: InverseLayerScope().also { inverseLayerScope = it }
    }
}
