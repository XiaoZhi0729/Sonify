package yos.music.player.ui.widgets.basic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdaptiveBackdropLuminanceFormulaTest {
    @Test
    fun signedLuminanceIsSquaredAndSignPreserved() {
        assertEquals(0f, backdropSignedLuminance(0.5f), 0.00001f)
        // 0.75 → +0.5，平方保号得 +0.25
        assertEquals(0.25f, backdropSignedLuminance(0.75f), 0.00001f)
        // 0.25 → -0.5，平方保号得 -0.25
        assertEquals(-0.25f, backdropSignedLuminance(0.25f), 0.00001f)
        // 亮侧封顶：纯白背景不再压成 contrast=0 的白平板
        assertEquals(BrightLuminanceCap, backdropSignedLuminance(1f), 0.00001f)
        assertEquals(-1f, backdropSignedLuminance(0f), 0.00001f)
        // 封顶边界：raw l=0.6 → 采样 m≈0.8873 恰好等于 cap
        assertEquals(BrightLuminanceCap, backdropSignedLuminance(0.8873f), 0.001f)
    }

    @Test
    fun brightnessMatchesAdaptiveLuminanceDemoMapping() {
        assertEquals(0.1f, adaptiveBrightness(0f), 0.00001f)
        assertEquals(0.5f, adaptiveBrightness(1f), 0.00001f)
        assertEquals(-0.2f, adaptiveBrightness(-1f), 0.00001f)
        assertEquals(0.1f + 0.4f * 0.25f, adaptiveBrightness(0.25f), 0.00001f)
        assertEquals(0.1f + 0.3f * -0.25f, adaptiveBrightness(-0.25f), 0.00001f)
    }

    private fun colorControlsNeutralOutput(x: Float, l: Float): Float =
        adaptiveContrast(l) * x + adaptiveBrightness(l) +
                (1f - adaptiveContrast(l)) / 2f

    @Test
    fun effectiveColorControlsOutputDocumentsOriginalBrightSideMapping() {
        assertEquals(1.1f, colorControlsNeutralOutput(1f, 0f), 0.00001f)
        assertEquals(1.075f, colorControlsNeutralOutput(1f, 0.25f), 0.00001f)
        assertEquals(1.05f, colorControlsNeutralOutput(1f, 0.5f), 0.00001f)
        assertEquals(1.01f, colorControlsNeutralOutput(1f, 0.9f), 0.00001f)
        assertEquals(1f, colorControlsNeutralOutput(1f, 1f), 0.00001f)
    }

    @Test
    fun contrastOnlyFallsOnTheBrightSide() {
        assertEquals(1f, adaptiveContrast(0f), 0.00001f)
        assertEquals(0f, adaptiveContrast(1f), 0.00001f)
        assertEquals(1f, adaptiveContrast(-1f), 0.00001f)
        assertEquals(0.75f, adaptiveContrast(0.25f), 0.00001f)
    }

    @Test
    fun surfaceVeilAlphaFadesOutOnDarkBackdrops() {
        assertEquals(0.10f, adaptiveSurfaceVeilAlpha(0f), 0.00001f)
        assertEquals(0.30f, adaptiveSurfaceVeilAlpha(0.5f), 0.00001f)
        assertEquals(0.50f, adaptiveSurfaceVeilAlpha(1f), 0.00001f)
        // 越界钳制到 [0,1]
        assertEquals(0.10f, adaptiveSurfaceVeilAlpha(-0.3f), 0.00001f)
        assertEquals(0.50f, adaptiveSurfaceVeilAlpha(1.7f), 0.00001f)
    }

    @Test
    fun blurScalesUpOnBrightAndGentlerDownOnDark() {
        assertEquals(8f, adaptiveBlurPx(8f, 0f), 0.00001f)
        // 亮侧 ×(1+l)：8+8l 形式
        assertEquals(16f, adaptiveBlurPx(8f, 1f), 0.00001f)
        // 暗侧 ×(1+0.75l)：8+6l 形式
        assertEquals(8f + 6f * -1f, adaptiveBlurPx(8f, -1f), 0.00001f)
        // 任意基底半径通用：24dp 弹层面板
        assertEquals(24f * 0.25f, adaptiveBlurPx(24f, -1f), 0.00001f)
        assertTrue(adaptiveBlurPx(24f, 0.5f) > 24f)
        assertTrue(adaptiveBlurPx(24f, -0.5f) < 24f)
    }
}
