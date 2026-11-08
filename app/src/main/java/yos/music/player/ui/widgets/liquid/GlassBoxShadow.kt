package yos.music.player.ui.widgets.liquid

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.DefaultCameraDistance
import androidx.compose.ui.graphics.DefaultShadowColor
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/** 玻璃外描边的全局不透明度（与 MeiloX IosAlertSurface 同值）。 */
internal const val GlassBoxShadowAlpha = 0.3f

/**
 * 玻璃实体外描边：侧面暗影 + 清晰外轮廓，两层都用 [BlendMode.Clear] 挖掉形状内部，
 * 只留玻璃外侧的一圈。
 *
 * 必须作为**独立节点**挂在 drawBackdrop 之外：在 onDrawSurface / onDrawFront 里画描边
 * 会被 backdrop 的 clipped layer 裁进内部，圆角/胶囊左右边缘会出现 pale fringe，
 * 而且只剩内半圈，做不出「玻璃有厚度」的立体感。
 *
 * 两层离屏缓冲只在尺寸/形状/alpha/density/fontScale/layoutDirection 变化时重录，
 * 避免每个动画帧栅格化两个屏幕尺寸的层。
 */
internal fun Modifier.glassBoxShadow(
    shape: () -> Shape,
    alpha: Float = GlassBoxShadowAlpha,
    layerBlock: (GraphicsLayerScope.() -> Unit)? = null,
): Modifier = then(GlassBoxShadowElement(shape, alpha, layerBlock))

private class GlassBoxShadowElement(
    private val shape: () -> Shape,
    private val alpha: Float,
    private val layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : ModifierNodeElement<GlassBoxShadowNode>() {

    override fun create(): GlassBoxShadowNode = GlassBoxShadowNode(shape, alpha, layerBlock)

    override fun update(node: GlassBoxShadowNode) {
        node.shape = shape
        node.alpha = alpha
        node.layerBlock = layerBlock
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "glassBoxShadow"
        properties["shape"] = shape
        properties["alpha"] = alpha
        properties["layerBlock"] = layerBlock
    }

    override fun equals(other: Any?): Boolean =
        other is GlassBoxShadowElement &&
            shape == other.shape &&
            alpha == other.alpha &&
            layerBlock == other.layerBlock

    override fun hashCode(): Int {
        var result = shape.hashCode()
        result = 31 * result + alpha.hashCode()
        result = 31 * result + (layerBlock?.hashCode() ?: 0)
        return result
    }
}

private class GlassBoxShadowNode(
    var shape: () -> Shape,
    var alpha: Float,
    var layerBlock: (GraphicsLayerScope.() -> Unit)?,
) : DrawModifierNode, Modifier.Node() {

    private var sideLayer: GraphicsLayer? = null
    private var outlineLayer: GraphicsLayer? = null
    private val layerScope = GlassLayerBlockScope()
    private var recordedSize = Size.Unspecified
    private var recordedShape: Shape? = null
    private var recordedAlpha = Float.NaN
    private var recordedDensity = Float.NaN
    private var recordedFontScale = Float.NaN
    private var recordedLayoutDirection: LayoutDirection? = null

    private val sidePaint = Paint().apply {
        style = PaintingStyle.Fill
        isAntiAlias = true
    }
    private val outlinePaint = Paint().apply {
        style = PaintingStyle.Stroke
        isAntiAlias = true
    }
    private val clearPaint = Paint().apply {
        style = PaintingStyle.Fill
        blendMode = BlendMode.Clear
        isAntiAlias = true
    }

    override fun ContentDrawScope.draw() {
        val sideLayer = sideLayer ?: return drawContent()
        val outlineLayer = outlineLayer ?: return drawContent()
        val shadowAlpha = alpha.coerceIn(0f, 1f)
        if (shadowAlpha <= 0f || size.minDimension <= 0f) return drawContent()

        val sideOffset = 0.7.dp.toPx()
        val outlineWidth = 0.65.dp.toPx()
        val padding = ceil(sideOffset + outlineWidth / 2f + 1f).toInt()
        val layerSize = IntSize(
            ceil(size.width).toInt() + padding * 2,
            ceil(size.height).toInt() + padding * 2,
        )
        val currentShape = shape()
        if (recordedSize != size || recordedShape != currentShape || recordedAlpha != shadowAlpha ||
            recordedDensity != density || recordedFontScale != fontScale ||
            recordedLayoutDirection != layoutDirection
        ) {
            val outline = currentShape.createOutline(size, layoutDirection, this)

            sidePaint.color = Color(0xFF3F3F3F).copy(alpha = shadowAlpha)
            outlinePaint.color = Color(0xFF6E6E6E).copy(alpha = shadowAlpha)
            outlinePaint.strokeWidth = outlineWidth

            // 侧面暗影是把形状沿 X 外扩的副本，高度严格等于元素高度，
            // 所以不会在上下边留下暗线；挖掉原形状后只剩左右两条侧面胶囊。
            sideLayer.record(layerSize) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawContext.canvas.save()
                    drawContext.canvas.translate(size.width / 2f, 0f)
                    drawContext.canvas.scale(
                        (size.width + sideOffset * 2f) / size.width,
                        1f,
                    )
                    drawContext.canvas.translate(-size.width / 2f, 0f)
                    drawContext.canvas.drawOutline(outline, sidePaint)
                    drawContext.canvas.restore()
                    drawContext.canvas.drawOutline(outline, clearPaint)
                }
            }

            outlineLayer.record(layerSize) {
                translate(padding.toFloat(), padding.toFloat()) {
                    drawContext.canvas.drawOutline(outline, outlinePaint)

                    // 只保留轮廓落在玻璃外侧的那一半：外描边而非内描边。
                    drawContext.canvas.drawOutline(outline, clearPaint)
                }
            }

            // 弹窗在窗口空间移动时本地 outline 不变，复用两张外描边纹理即可，
            // 不必每个动画帧清屏重栅格化两个屏幕尺寸缓冲。
            recordedSize = size
            recordedShape = currentShape
            recordedAlpha = shadowAlpha
            recordedDensity = density
            recordedFontScale = fontScale
            recordedLayoutDirection = layoutDirection
        }

        // 外描边层与 drawBackdrop 的 graphicsLayer 是两棵树，必须把跟手形变的
        // 全部变换同步过来，否则按压时边缘会与玻璃脱开。
        layerScope.reset(this, size)
        layerBlock?.invoke(layerScope)
        val pivotFraction = layerScope.transformOrigin
        val pivotOffset = androidx.compose.ui.geometry.Offset(
            pivotFraction.pivotFractionX * layerSize.width,
            pivotFraction.pivotFractionY * layerSize.height,
        )
        for (layer in listOf(sideLayer, outlineLayer)) {
            layer.alpha = layerScope.alpha
            layer.scaleX = layerScope.scaleX
            layer.scaleY = layerScope.scaleY
            layer.translationX = layerScope.translationX
            layer.translationY = layerScope.translationY
            layer.rotationX = layerScope.rotationX
            layer.rotationY = layerScope.rotationY
            layer.rotationZ = layerScope.rotationZ
            layer.cameraDistance = layerScope.cameraDistance
            layer.pivotOffset = pivotOffset
        }

        // 侧面暗影在最下、玻璃主体居中、清晰外轮廓在最上；三层都是 external-only，
        // 不会与玻璃内部的高光互相干扰。
        translate(-padding.toFloat(), -padding.toFloat()) {
            drawLayer(sideLayer)
        }
        drawContent()
        translate(-padding.toFloat(), -padding.toFloat()) {
            drawLayer(outlineLayer)
        }
    }

    override fun onAttach() {
        recordedShape = null
        sideLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        }
        outlineLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = androidx.compose.ui.graphics.layer.CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        sideLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        outlineLayer?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        sideLayer = null
        outlineLayer = null
    }
}

/** 在真实 graphicsLayer 之外求值同一份 layerBlock 的适配器。 */
internal class GlassLayerBlockScope : GraphicsLayerScope {
    private var densityValue: Density = Density(1f)

    override val density: Float get() = densityValue.density
    override val fontScale: Float get() = densityValue.fontScale

    override var size: Size = Size.Zero
    override var scaleX: Float = 1f
    override var scaleY: Float = 1f
    override var alpha: Float = 1f
    override var translationX: Float = 0f
    override var translationY: Float = 0f
    override var shadowElevation: Float = 0f
    override var ambientShadowColor: Color = DefaultShadowColor
    override var spotShadowColor: Color = DefaultShadowColor
    override var rotationX: Float = 0f
    override var rotationY: Float = 0f
    override var rotationZ: Float = 0f
    override var cameraDistance: Float = DefaultCameraDistance
    override var transformOrigin: TransformOrigin = TransformOrigin.Center
    override var shape: Shape = RectangleShape
    override var clip: Boolean = false
    override var renderEffect: RenderEffect? = null
    override var blendMode: BlendMode = BlendMode.SrcOver
    override var colorFilter: ColorFilter? = null
    override var compositingStrategy: androidx.compose.ui.graphics.CompositingStrategy =
        androidx.compose.ui.graphics.CompositingStrategy.Auto

    fun reset(drawScope: Density, newSize: Size) {
        densityValue = drawScope
        size = newSize
        scaleX = 1f
        scaleY = 1f
        alpha = 1f
        translationX = 0f
        translationY = 0f
        shadowElevation = 0f
        ambientShadowColor = DefaultShadowColor
        spotShadowColor = DefaultShadowColor
        rotationX = 0f
        rotationY = 0f
        rotationZ = 0f
        cameraDistance = DefaultCameraDistance
        transformOrigin = TransformOrigin.Center
        shape = RectangleShape
        clip = false
        renderEffect = null
        blendMode = BlendMode.SrcOver
        colorFilter = null
        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Auto
    }
}
