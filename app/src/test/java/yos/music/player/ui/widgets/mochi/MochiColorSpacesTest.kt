package yos.music.player.ui.widgets.mochi

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.ui.theme.AppAccentColors
import kotlin.math.abs

class MochiColorSpacesTest {
    @Test
    fun srgbRoundTripsThroughLab() {
        val out = FloatArray(3)
        for (c in listOf(
            floatArrayOf(1f, 0f, 0f),
            floatArrayOf(0.2f, 0.6f, 0.9f),
            floatArrayOf(0.5f, 0.5f, 0.5f),
            floatArrayOf(1f, 1f, 1f),
        )) {
            val lab = MochiColorSpaces.srgbToLab(c[0], c[1], c[2])
            MochiColorSpaces.labToSrgb(lab[0], lab[1], lab[2], out)
            for (i in 0..2) assertEquals(c[i], out[i], 2e-3f)
        }
    }

    @Test
    fun outOfGamutLchIsMappedByChromaNotClipped() {
        val out = FloatArray(3)
        for (l in listOf(5.0, 30.0, 55.0, 80.0, 97.0)) {
            for (h in 0 until 360 step 15) {
                MochiColorSpaces.lchToSrgb(l, 150.0, h.toDouble(), out)
                for (v in out) assertTrue("L=$l h=$h v=$v", v in 0f..1f)
                val back = MochiColorSpaces.srgbToLch(out[0], out[1], out[2])
                assertEquals("L=$l h=$h", l, back[0], 0.6)
                if (back[1] > 8.0) {
                    val dh = abs(((back[2] - h + 540.0) % 360.0) - 180.0)
                    assertTrue("L=$l h=$h got ${back[2]}", dh < 3.0)
                }
            }
        }
    }

    @Test
    fun inGamutLchIsUntouched() {
        val out = FloatArray(3)
        MochiColorSpaces.lchToSrgb(60.0, 20.0, 200.0, out)
        val back = MochiColorSpaces.srgbToLch(out[0], out[1], out[2])
        assertEquals(60.0, back[0], 0.3)
        assertEquals(20.0, back[1], 0.3)
        assertEquals(200.0, back[2], 1.0)
    }

    @Test
    fun spectrumModelsLocateWhatTheyProduce() {
        for (m in SpectrumModel.entries) {
            val x = if (m == SpectrumModel.Lab) 0.45f else 0.3f
            val c = Color(m.argb(x, 0.45f, 0.4f))
            val p = m.locate(c)
            assertEquals("$m x", x, p[0], 0.02f)
            assertEquals("$m y", 0.45f, p[1], 0.02f)
        }
    }

    @Test
    fun allApplePresetsAndExtremeColorsLocateAndRenderSafelyInEverySpectrumModel() {
        val candidates = buildList {
            for (preset in AppAccentColors.Presets) {
                add(preset.light)
                add(preset.dark)
            }
            add(Color(0xFF000000))
            add(Color(0xFFFFFFFF))
            add(Color(0xFFFF0000))
            add(Color(0xFF00FF00))
            add(Color(0xFF0000FF))
            add(Color(0xFFFFFF00))
            add(Color(0xFF00FFFF))
            add(Color(0xFFFF00FF))
            add(Color(0xFFFF3B30))
            add(Color(0xFFFF453A))
            add(Color(0xFFFE0100))
            add(Color(0xFFFF0101))
        }
        for (color in candidates) {
            for (model in SpectrumModel.entries) {
                val pos = model.locate(color)
                assertEquals(3, pos.size)
                for (i in 0..2) {
                    assertTrue("$model $color axis[$i]=${pos[i]}", pos[i].isFinite() && pos[i] in 0f..1f)
                }
                val pixels = model.render(pos[2], 16, 16) { true }
                assertNotNull("$model $color rendered null", pixels)
                assertEquals(256, pixels!!.size)
            }
        }
    }

    @Test
    fun hslToArgbClampsOutOfRangeAndNonFiniteInputs() {
        val fromCrash = Color(hslToArgb(1.875f, 1.0000001f, 0.99375f))
        assertEquals(Color.hsl(1.875f, 1f, 0.99375f), fromCrash)

        val clampedNeg = Color(hslToArgb(-1e-7f, -0.01f, 1.01f))
        assertEquals(Color.White, clampedNeg)

        val nonFinite = Color(hslToArgb(Float.NaN, Float.NaN, Float.NaN))
        assertEquals(Color.Black, nonFinite)
    }
}
