package yos.music.player.ui.widgets.liquid

import androidx.compose.foundation.shape.AbsoluteRoundedCornerShape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.fastCoerceAtMost
import com.kyant.backdrop.RuntimeShaderCacheImpl
import com.kyant.backdrop.internal.clipOutline
import com.kyant.backdrop.internal.recordLayer
import com.kyant.backdrop.internal.setRuntimeShader
import com.kyant.backdrop.isRuntimeShaderSupported
import com.kyant.shapes.RoundedRectangularShape
import org.intellij.lang.annotations.Language
import kotlin.math.ceil

private val Ios27EdgeAlwaysEnabled: () -> Boolean = { true }

/**
 * 1:1 复刻 iOS 27 液态玻璃双层立体描边：
 * 1. 内层 [BlendMode.Plus]（`plusLighter`）羽化内倒角高光：严格内缩在最外圈黑色描边以内，
 *    上缘 +0.23、下缘 +0.15，左右渐弱至 0，在白/棕/深绿等任意背景上都表现为通透加亮而非死白；
 * 2. 外层 `plusDarker` 近似暗环（`SrcOver` 黑 + `ColorBurn` 灰）：沿轮廓整圈连续，
 *    左右最深、上下保留 `baseline = 0.40`，在浅色与深色背景上均具清晰深色边线。
 */
fun Modifier.ios27GlassEdge(
    shape: Shape,
    enabled: () -> Boolean = Ios27EdgeAlwaysEnabled,
    drawSurfaceShading: Boolean = true,
): Modifier = ios27GlassEdge(
    shape = { shape },
    enabled = enabled,
    drawSurfaceShading = drawSurfaceShading,
)

/** [ios27GlassEdge] 的 lambda 形状重载（轮廓需在绘制期求值时用，如随展开进度变形）。 */
fun Modifier.ios27GlassEdge(
    shape: () -> Shape,
    enabled: () -> Boolean = Ios27EdgeAlwaysEnabled,
    drawSurfaceShading: Boolean = true,
): Modifier = this.then(Ios27GlassEdgeElement(shape, enabled, drawSurfaceShading))

private class Ios27GlassEdgeElement(
    val shapeProvider: () -> Shape,
    val enabled: () -> Boolean,
    val drawSurfaceShading: Boolean,
) : ModifierNodeElement<Ios27GlassEdgeNode>() {

    override fun create(): Ios27GlassEdgeNode =
        Ios27GlassEdgeNode(shapeProvider, enabled, drawSurfaceShading)

    override fun update(node: Ios27GlassEdgeNode) {
        node.shapeProvider = shapeProvider
        node.enabled = enabled
        node.drawSurfaceShading = drawSurfaceShading
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "ios27GlassEdge"
        properties["shapeProvider"] = shapeProvider
        properties["enabled"] = enabled
        properties["drawSurfaceShading"] = drawSurfaceShading
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Ios27GlassEdgeElement) return false
        if (shapeProvider != other.shapeProvider) return false
        if (enabled != other.enabled) return false
        if (drawSurfaceShading != other.drawSurfaceShading) return false
        return true
    }

    override fun hashCode(): Int {
        var result = shapeProvider.hashCode()
        result = 31 * result + enabled.hashCode()
        result = 31 * result + drawSurfaceShading.hashCode()
        return result
    }
}

private class Ios27GlassEdgeNode(
    var shapeProvider: () -> Shape,
    var enabled: () -> Boolean,
    var drawSurfaceShading: Boolean,
) : DrawModifierNode, Modifier.Node() {

    override val shouldAutoInvalidate: Boolean = false

    private val runtimeShaderCache = RuntimeShaderCacheImpl()
    private var bevelLayer: GraphicsLayer? = null
    private var clipPath: Path? = null

    private val bevelPaint = Paint().apply {
        style = PaintingStyle.Stroke
        blendMode = BlendMode.Plus
    }
    private val clearPaint = Paint().apply {
        style = PaintingStyle.Stroke
        color = Color.Transparent
        blendMode = BlendMode.Clear
    }
    private val rimSrcOverPaint = Paint().apply {
        style = PaintingStyle.Stroke
        blendMode = BlendMode.SrcOver
    }
    private val rimColorBurnPaint = Paint().apply {
        style = PaintingStyle.Stroke
        blendMode = BlendMode.ColorBurn
    }

    private var prevOutline: Outline? = null
    private var prevDensity = Float.NaN
    private var prevLayoutDirection: LayoutDirection? = null
    private var hasRecorded = false

    override fun ContentDrawScope.draw() {
        if (!enabled() || size.width <= 0f || size.height <= 0f) {
            drawContent()
            return
        }

        val currentShape = shapeProvider()
        val outline = currentShape.createOutline(size, layoutDirection, this)
        val localClipPath = if (outline is Outline.Rounded) {
            clipPath ?: Path().also { clipPath = it }
        } else {
            null
        }

        // 先铺上轻薄的纵向立体明暗面，再画子内容（图标/文字），避免压暗前景内容
        if (drawSurfaceShading) {
            val canvas = drawContext.canvas
            canvas.save()
            canvas.clipOutline(outline, localClipPath)
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Black.copy(alpha = 0.040f),
                    1f to Color.Black.copy(alpha = 0.015f),
                    startY = 0f,
                    endY = size.height,
                )
            )
            canvas.restore()
        }

        drawContent()

        val minHalfDim = size.minDimension / 2f
        val rimHalfPx = 0.36f.dp.toPx().coerceAtLeast(1f).fastCoerceAtMost(minHalfDim)
        val useShader = isRuntimeShaderSupported()
        val cornerRadii = if (useShader) getEdgeCornerRadii(currentShape) else null

        // 第 1 层：内缩在黑边以内的 plusLighter 羽化高光倒角
        val layer = bevelLayer
        if (layer != null) {
            layer.compositingStrategy = CompositingStrategy.Offscreen
            layer.blendMode = BlendMode.Plus

            val canReuse = hasRecorded &&
                outline !is Outline.Generic &&
                prevOutline == outline &&
                prevDensity == density &&
                prevLayoutDirection == layoutDirection

            if (!canReuse) {
                val safeSize = IntSize(
                    ceil(size.width).toInt() + 2,
                    ceil(size.height).toInt() + 2,
                )
                val s1 = (rimHalfPx + 0.58f.dp.toPx()).fastCoerceAtMost(minHalfDim)
                val s2 = (rimHalfPx + 0.34f.dp.toPx()).fastCoerceAtMost(minHalfDim)
                val s3 = (rimHalfPx + 0.16f.dp.toPx()).fastCoerceAtMost(minHalfDim)

                if (useShader && cornerRadii != null) {
                    val bevelShader = runtimeShaderCache.obtainRuntimeShader(
                        "Ios27Bevel",
                        Ios27BevelShaderString,
                    ).apply {
                        setFloatUniform("size", size.width, size.height)
                        setFloatUniform("cornerRadii", cornerRadii)
                        setFloatUniform("topIntensity", 0.23f)
                        setFloatUniform("bottomIntensity", 0.15f)
                    }
                    bevelPaint.setRuntimeShader(bevelShader)
                } else {
                    bevelPaint.setRuntimeShader(null)
                }

                val recorded = recordLayer(this@Ios27GlassEdgeNode, layer, safeSize) {
                    translate(1f, 1f) {
                        val canvas = drawContext.canvas
                        canvas.save()
                        canvas.clipOutline(outline, localClipPath)

                        bevelPaint.strokeWidth = s1 * 2f
                        bevelPaint.color = Color.White.copy(alpha = if (useShader) 0.30f else 0.045f)
                        canvas.drawOutline(outline, bevelPaint)

                        bevelPaint.strokeWidth = s2 * 2f
                        bevelPaint.color = Color.White.copy(alpha = if (useShader) 0.35f else 0.055f)
                        canvas.drawOutline(outline, bevelPaint)

                        bevelPaint.strokeWidth = s3 * 2f
                        bevelPaint.color = Color.White.copy(alpha = if (useShader) 0.35f else 0.060f)
                        canvas.drawOutline(outline, bevelPaint)

                        // 清空最外圈 [0..rimHalfPx]，确保内倒角白光绝不覆盖外圈暗环
                        clearPaint.strokeWidth = rimHalfPx * 2f
                        canvas.drawOutline(outline, clearPaint)

                        canvas.restore()
                    }
                }

                if (recorded) {
                    prevOutline = outline
                    prevDensity = density
                    prevLayoutDirection = layoutDirection
                    hasRecorded = true
                } else {
                    hasRecorded = false
                }
            }

            if (hasRecorded) {
                translate(-1f, -1f) {
                    drawLayer(layer)
                }
            }
        }

        // 第 2 层：最外圈 plusDarker 暗环（SrcOver 黑 0.18 + ColorBurn 灰 0.70 @ 0.95）
        rimSrcOverPaint.strokeWidth = rimHalfPx * 2f
        rimSrcOverPaint.color = Color.Black.copy(alpha = 0.18f)
        if (useShader && cornerRadii != null) {
            val srcOverShader = runtimeShaderCache.obtainRuntimeShader(
                "Ios27RimSrcOver",
                Ios27RimShaderString,
            ).apply {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("cornerRadii", cornerRadii)
                setColorUniform("color", Color.Black)
                setFloatUniform("baseline", 0.40f)
            }
            rimSrcOverPaint.setRuntimeShader(srcOverShader)
        } else {
            rimSrcOverPaint.setRuntimeShader(null)
        }

        rimColorBurnPaint.strokeWidth = rimHalfPx * 2f
        if (useShader && cornerRadii != null) {
            rimColorBurnPaint.color = Color(0.70f, 0.70f, 0.70f, 0.95f)
            val colorBurnShader = runtimeShaderCache.obtainRuntimeShader(
                "Ios27RimColorBurn",
                Ios27RimShaderString,
            ).apply {
                setFloatUniform("size", size.width, size.height)
                setFloatUniform("cornerRadii", cornerRadii)
                setColorUniform("color", Color(0.70f, 0.70f, 0.70f, 1.0f))
                setFloatUniform("baseline", 0.40f)
            }
            rimColorBurnPaint.setRuntimeShader(colorBurnShader)
        } else {
            rimColorBurnPaint.color = Color(0.70f, 0.70f, 0.70f, 0.65f)
            rimColorBurnPaint.setRuntimeShader(null)
        }

        val canvas = drawContext.canvas
        canvas.save()
        canvas.clipOutline(outline, localClipPath)
        canvas.drawOutline(outline, rimSrcOverPaint)
        canvas.drawOutline(outline, rimColorBurnPaint)
        canvas.restore()
    }

    override fun onAttach() {
        bevelLayer = requireGraphicsContext().createGraphicsLayer().apply {
            compositingStrategy = CompositingStrategy.Offscreen
        }
    }

    override fun onDetach() {
        bevelLayer?.let { layer ->
            requireGraphicsContext().releaseGraphicsLayer(layer)
            bevelLayer = null
        }
        clipPath = null
        runtimeShaderCache.clear()
        prevOutline = null
        hasRecorded = false
    }
}

private fun DrawScope.getEdgeCornerRadii(shape: Shape): FloatArray {
    val size = size
    val maxRadius = size.minDimension / 2f
    return when (shape) {
        is RoundedRectangularShape -> {
            val radii = shape.cornerRadii(size, layoutDirection, this)
            floatArrayOf(
                radii[0].fastCoerceAtMost(maxRadius),
                radii[1].fastCoerceAtMost(maxRadius),
                radii[2].fastCoerceAtMost(maxRadius),
                radii[3].fastCoerceAtMost(maxRadius),
            )
        }
        is AbsoluteRoundedCornerShape -> {
            floatArrayOf(
                shape.topStart.toPx(size, this).fastCoerceAtMost(maxRadius),
                shape.topEnd.toPx(size, this).fastCoerceAtMost(maxRadius),
                shape.bottomEnd.toPx(size, this).fastCoerceAtMost(maxRadius),
                shape.bottomStart.toPx(size, this).fastCoerceAtMost(maxRadius),
            )
        }
        is CornerBasedShape -> {
            val isLtr = layoutDirection == LayoutDirection.Ltr
            val topLeft = if (isLtr) shape.topStart.toPx(size, this) else shape.topEnd.toPx(size, this)
            val topRight = if (isLtr) shape.topEnd.toPx(size, this) else shape.topStart.toPx(size, this)
            val bottomRight = if (isLtr) shape.bottomEnd.toPx(size, this) else shape.bottomStart.toPx(size, this)
            val bottomLeft = if (isLtr) shape.bottomStart.toPx(size, this) else shape.bottomEnd.toPx(size, this)
            floatArrayOf(
                topLeft.fastCoerceAtMost(maxRadius),
                topRight.fastCoerceAtMost(maxRadius),
                bottomRight.fastCoerceAtMost(maxRadius),
                bottomLeft.fastCoerceAtMost(maxRadius),
            )
        }
        else -> FloatArray(4) { maxRadius }
    }
}

@Language("AGSL")
private const val Ios27BevelShaderString = """
uniform float2 size;
uniform float4 cornerRadii;
uniform float topIntensity;
uniform float bottomIntensity;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

    float vertMask = smoothstep(0.42, 0.92, abs(grad.y));
    float tbGain = mix(topIntensity, bottomIntensity, smoothstep(-0.3, 0.3, grad.y));
    float intensity = vertMask * tbGain;
    return half4(intensity, intensity, intensity, intensity);
}"""

@Language("AGSL")
private const val Ios27RimShaderString = """
uniform float2 size;
uniform float4 cornerRadii;
layout(color) uniform half4 color;
uniform float baseline;

float radiusAt(float2 coord, float4 radii) {
    if (coord.x >= 0.0) {
        if (coord.y <= 0.0) return radii.y;
        else return radii.z;
    } else {
        if (coord.y <= 0.0) return radii.x;
        else return radii.w;
    }
}

float2 gradSdRoundedRect(float2 coord, float2 halfSize, float radius) {
    float2 cornerCoord = abs(coord) - (halfSize - float2(radius));
    if (cornerCoord.x >= 0.0 || cornerCoord.y >= 0.0) {
        return sign(coord) * normalize(max(cornerCoord, 0.0));
    } else {
        float gradX = step(cornerCoord.y, cornerCoord.x);
        return sign(coord) * float2(gradX, 1.0 - gradX);
    }
}

half4 main(float2 coord) {
    float2 halfSize = size * 0.5;
    float2 centeredCoord = coord - halfSize;
    float radius = radiusAt(centeredCoord, cornerRadii);

    float gradRadius = min(radius * 1.5, min(halfSize.x, halfSize.y));
    float2 grad = gradSdRoundedRect(centeredCoord, halfSize, gradRadius);

    float horizMask = smoothstep(0.55, 0.88, abs(grad.x));
    float intensity = baseline + (1.0 - baseline) * horizMask;
    return color * intensity;
}"""
