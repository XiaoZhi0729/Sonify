package yos.music.player.code.hdr

import android.os.Build
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.colorspace.ColorSpaces

// HDR 画法的两段基础实现改写自 mochi-compose（Apache-2.0）：
// mochi-compose/src/main/java/ui/feice/mochi/theme/MochiHdr.kt（hdrLift）
// mochi-compose/src/main/java/ui/feice/mochi/effect/hdr/HdrBrush.kt（hdrSolidBrush）
//
// 关键约束（照抄上游）：
// - 提亮必须在线性光里做。sRGB 编码值直接乘 2，实际亮度约是 4.6 倍，会瞬间顶到屏幕上限。
// - 只放大颜色里「彩色」的部分：最亮的通道到目标倍数，最暗的通道留在 SDR，浅色光不会变成白光。
//   接近白/灰的颜色（饱和度低）退回整体乘倍数——白字照样变亮、不会染上色。

private val LinearExt by lazy {
    android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB)
}

private fun useHdr(boost: Float) = boost > 1.001f && Build.VERSION.SDK_INT >= 29

/**
 * 同一种颜色在线性光下亮 [gain] 倍，结果在 LinearExtendedSrgb（通道值可超过 1）。
 * [gain] ≤ 1 时原样返回。
 */
internal fun Color.hdrLift(gain: Float): Color {
    if (gain <= 1f) return this
    val l = convert(ColorSpaces.LinearExtendedSrgb)
    val g = gain.coerceAtMost(7f)
    val hi = maxOf(l.red, l.green, l.blue)
    val lo = minOf(l.red, l.green, l.blue).coerceAtLeast(0f)
    if (hi <= 0f) return l
    // 彩色部分的放大倍数：让最亮的通道到 hi·g，最小的通道不动
    val chroma = if (hi - lo > 1e-4f) (hi * g - lo) / (hi - lo) else g
    // 饱和度低（接近白、灰）时平滑地退回整体乘 g
    val t = ((hi - lo) / hi / 0.3f).coerceIn(0f, 1f)
    val w = t * t * (3f - 2f * t)
    fun ch(v: Float) = (v * g) * (1f - w) + (lo + (v - lo) * chroma) * w
    return Color(
        red = ch(l.red).coerceIn(-0.5f, 7.4f),
        green = ch(l.green).coerceIn(-0.5f, 7.4f),
        blue = ch(l.blue).coerceIn(-0.5f, 7.4f),
        alpha = l.alpha,
        colorSpace = ColorSpaces.LinearExtendedSrgb,
    )
}

private fun Color.hdrLong(boost: Float): Long {
    val c = hdrLift(boost.coerceAtLeast(1f))
    return android.graphics.Color.pack(c.red, c.green, c.blue, c.alpha, LinearExt)
}

private class FixedShaderBrush(private val shader: Shader) : ShaderBrush() {
    override fun createShader(size: Size): Shader = shader
}

/**
 * 纯色画刷，HDR 下亮 [boost] 倍（超过 SDR 白）。
 *
 * Compose 的 `drawRect(color)` / `drawText(layout, color)` 会把颜色截到 8 位，
 * 画不出 HDR；扩展范围的颜色只能经 shader 进画布，所以这里用两个同色色标造一个
 * 恒定的 LinearGradient 代替纯色。
 *
 * [boost] ≤ 1（或 API < 29）时退回 [SolidColor]，SDR 下和原来逐像素一致。
 */
internal fun hdrSolidBrush(color: Color, boost: Float): Brush {
    if (!useHdr(boost)) return SolidColor(color)
    val c = color.hdrLong(boost)
    return FixedShaderBrush(
        android.graphics.LinearGradient(
            0f, 0f, 1f, 0f,
            longArrayOf(c, c), null,
            android.graphics.Shader.TileMode.CLAMP,
        )
    )
}
