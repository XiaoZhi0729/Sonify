package com.kyant.backdrop

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

// 离屏模糊缓冲的降采样比例。模糊/折射在低分辨率缓冲上运算后放大回原尺寸，
// 可显著降低 GPU 采样成本（成本按 1/scale² 下降）。0.42f 为固定降采样。
internal const val DOWNSAMPLE_SCALE = 0.42f

sealed interface BackdropEffectScope : Density, RuntimeShaderCache {

    val size: Size

    /**
     * 当前离屏模糊缓冲的降采样比例。
     *
     * 默认 0.38f（约 14.4% 像素面积），可在 effects 块内按需覆盖（如顶栏渐进模糊设为 1f 以保持 0 模糊尾部锐利）。
     */
    var downsampleScale: Float

    val layoutDirection: LayoutDirection

    val shape: Shape

    var padding: Float

    var renderEffect: RenderEffect?

    /** lens() 产生的 RuntimeShader，供 ImageShader 直采折射路径复用 */
    var lensShader: RuntimeShader?

    /** 全分辨率直采用的折射参数（屏幕像素） */
    var lensRefractionHeight: Float
    var lensRefractionAmount: Float
    var lensCornerRadii: FloatArray?
}

internal abstract class BackdropEffectScopeImpl : BackdropEffectScope, RuntimeShaderCache {

    override var density: Float = 1f
    override var fontScale: Float = 1f
    override var size: Size = Size.Unspecified
    override var layoutDirection: LayoutDirection = LayoutDirection.Ltr
    override var padding: Float = 0f
    override var renderEffect: RenderEffect? = null
    override var lensShader: RuntimeShader? = null
    override var lensRefractionHeight: Float = 0f
    override var lensRefractionAmount: Float = 0f
    override var lensCornerRadii: FloatArray? = null
    override var downsampleScale: Float = 0.38f

    private val runtimeShaderCache = RuntimeShaderCacheImpl()

    override fun obtainRuntimeShader(key: String, string: String): RuntimeShader {
        return runtimeShaderCache.obtainRuntimeShader(key, string)
    }

    fun update(scope: DrawScope): Boolean {
        val newDensity = scope.density
        val newFontScale = scope.fontScale
        val newSize = scope.size
        val newLayoutDirection = scope.layoutDirection

        val changed = newDensity != density ||
                newFontScale != fontScale ||
                newSize != size ||
                newLayoutDirection != layoutDirection

        if (changed) {
            density = newDensity
            fontScale = newFontScale
            size = newSize
            layoutDirection = newLayoutDirection
        }

        return changed
    }

    fun apply(effects: BackdropEffectScope.() -> Unit) {
        downsampleScale = 0.38f
        padding = 0f
        renderEffect = null
        lensShader = null
        lensRefractionHeight = 0f
        lensRefractionAmount = 0f
        lensCornerRadii = null
        effects()
    }

    fun reset() {
        downsampleScale = 0.38f
        density = 1f
        fontScale = 1f
        size = Size.Unspecified
        layoutDirection = LayoutDirection.Ltr
        padding = 0f
        renderEffect = null
        lensShader = null
        lensRefractionHeight = 0f
        lensRefractionAmount = 0f
        lensCornerRadii = null
        runtimeShaderCache.clear()
    }
}
