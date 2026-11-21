// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import me.tatarka.google.material.hct.Hct
import kotlin.math.atan2
import kotlin.math.cbrt
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin

/**
 * 源自 mochi-compose 的色彩空间换算：sRGB ↔ CIE Lab / LCH（D65），HCT 走 material-color-utilities。
 *
 * Lab / LCH 里很多组合在 sRGB 里显示不出来：保持明度和色相、把彩度降到刚好能显示为止（CSS Color 4 色域映射），
 * 不按分量截断，平面上不会出现色相跑偏的色带。
 */
internal object MochiColorSpaces {
    fun argb(r: Float, g: Float, b: Float): Int {
        fun ch(v: Float) = (v * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    private fun toLinear(c: Double): Double =
        if (c <= 0.04045) c / 12.92 else ((c + 0.055) / 1.055).pow(2.4)

    private fun fromLinear(c: Double): Double =
        if (c <= 0.0031308) c * 12.92 else 1.055 * c.pow(1.0 / 2.4) - 0.055

    private const val Xn = 0.95047
    private const val Yn = 1.0
    private const val Zn = 1.08883
    private const val Eps = 216.0 / 24389.0
    private const val Kappa = 24389.0 / 27.0

    private fun f(t: Double): Double = if (t > Eps) cbrt(t) else (Kappa * t + 16.0) / 116.0

    private fun fInv(t: Double): Double {
        val t3 = t * t * t
        return if (t3 > Eps) t3 else (116.0 * t - 16.0) / Kappa
    }

    fun srgbToLab(r: Float, g: Float, b: Float): DoubleArray {
        val lr = toLinear(r.toDouble())
        val lg = toLinear(g.toDouble())
        val lb = toLinear(b.toDouble())
        val x = 0.4124564 * lr + 0.3575761 * lg + 0.1804375 * lb
        val y = 0.2126729 * lr + 0.7151522 * lg + 0.0721750 * lb
        val z = 0.0193339 * lr + 0.1191920 * lg + 0.9503041 * lb
        val fx = f(x / Xn)
        val fy = f(y / Yn)
        val fz = f(z / Zn)
        return doubleArrayOf(116.0 * fy - 16.0, 500.0 * (fx - fy), 200.0 * (fy - fz))
    }

    private fun labToLinear(l: Double, a: Double, b: Double, out: DoubleArray) {
        val fy = (l + 16.0) / 116.0
        val fx = fy + a / 500.0
        val fz = fy - b / 200.0
        val x = Xn * fInv(fx)
        val y = Yn * (if (l > Kappa * Eps) fy * fy * fy else l / Kappa)
        val z = Zn * fInv(fz)
        out[0] = 3.2404542 * x - 1.5371385 * y - 0.4985314 * z
        out[1] = -0.9692660 * x + 1.8760108 * y + 0.0415560 * z
        out[2] = 0.0556434 * x - 0.2040259 * y + 1.0572252 * z
    }

    private fun inGamut(lin: DoubleArray): Boolean = lin.all { it >= 0.0 && it <= 1.0 }

    fun lchToSrgb(l: Double, c: Double, hDeg: Double, out: FloatArray) {
        val safeL = l.coerceIn(0.0, 100.0)
        val safeC = c.coerceAtLeast(0.0)
        val lin = DoubleArray(3)
        val h = Math.toRadians(hDeg)
        val ca = cos(h)
        val sa = sin(h)
        labToLinear(safeL, safeC * ca, safeC * sa, lin)
        if (!inGamut(lin)) {
            var lo = 0.0
            var hi = safeC
            repeat(18) {
                val mid = (lo + hi) / 2.0
                labToLinear(safeL, mid * ca, mid * sa, lin)
                if (inGamut(lin)) lo = mid else hi = mid
            }
            labToLinear(safeL, lo * ca, lo * sa, lin)
            if (!inGamut(lin)) {
                val y = if (safeL > Kappa * Eps) ((safeL + 16.0) / 116.0).pow(3) else safeL / Kappa
                lin[0] = y
                lin[1] = y
                lin[2] = y
            }
        }
        for (i in 0..2) out[i] = fromLinear(lin[i].coerceIn(0.0, 1.0)).toFloat().coerceIn(0f, 1f)
    }

    fun labToSrgb(l: Double, a: Double, b: Double, out: FloatArray) {
        lchToSrgb(l, hypot(a, b), Math.toDegrees(atan2(b, a)), out)
    }

    fun srgbToLch(r: Float, g: Float, b: Float): DoubleArray {
        val lab = srgbToLab(r, g, b)
        var h = Math.toDegrees(atan2(lab[2], lab[1]))
        if (h < 0) h += 360.0
        return doubleArrayOf(lab[0], hypot(lab[1], lab[2]), h)
    }

    fun hctToArgb(hue: Double, chroma: Double, tone: Double): Int = Hct.from(hue, chroma, tone).toInt()

    fun argbToHct(argb: Int): DoubleArray {
        val h = Hct.fromInt(argb)
        return doubleArrayOf(h.hue, h.chroma, h.tone)
    }
}
