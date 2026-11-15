package yos.music.player.ui.pages.settings.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.code.utils.lrc.parseLyricFontWeight

class LyricSettingTest {

    @Test
    fun `parseLyricFontWeight supports legacy names and continuous numeric strings`() {
        assertEquals(100, parseLyricFontWeight("Thin"))
        assertEquals(400, parseLyricFontWeight("Regular"))
        assertEquals(600, parseLyricFontWeight("SemiBold"))
        assertEquals(800, parseLyricFontWeight("ExtraBold"))
        assertEquals(900, parseLyricFontWeight("Black"))
        assertEquals(645, parseLyricFontWeight("645"))
        assertEquals(100, parseLyricFontWeight("10"))
        assertEquals(900, parseLyricFontWeight("1200"))
        assertEquals(800, parseLyricFontWeight("unknown"))
    }

    @Test
    fun `snapToMagneticAnchor snaps near anchor and allows free values outside radius`() {
        // 字重：100..900，每 100 吸附（吸附半径 = 800 * 0.024 = 19.2）
        val (snappedWeight, hitWeight) = snapToMagneticAnchor(612f, 100f..900f, 100f)
        assertTrue(hitWeight)
        assertEquals(600f, snappedWeight, 0.001f)

        val (freeWeight, missWeight) = snapToMagneticAnchor(645f, 100f..900f, 100f)
        assertFalse(missWeight)
        assertEquals(645f, freeWeight, 0.001f)

        // 时间偏移：-600..600，每 150ms 吸附（吸附半径 = 1200 * 0.024 = 28.8）
        val (snappedOffset, hitOffset) = snapToMagneticAnchor(-165f, -600f..600f, 150f)
        assertTrue(hitOffset)
        assertEquals(-150f, snappedOffset, 0.001f)

        val (freeOffset, missOffset) = snapToMagneticAnchor(220f, -600f..600f, 150f)
        assertFalse(missOffset)
        assertEquals(220f, freeOffset, 0.001f)

        // 字号：80..120，每 10% 吸附（吸附半径 = 40 * 0.024 = 0.96）
        val (snappedScale, hitScale) = snapToMagneticAnchor(100.5f, 80f..120f, 10f)
        assertTrue(hitScale)
        assertEquals(100f, snappedScale, 0.001f)

        val (freeScale, missScale) = snapToMagneticAnchor(104f, 80f..120f, 10f)
        assertFalse(missScale)
        assertEquals(104f, freeScale, 0.001f)
    }

    @Test
    fun `sliderAnchorCenterX matches MochiSlider and LiquidSlider thumb center geometry`() {
        val width = 900f
        val density = 3f

        // MochiSlider：38dp 拇指，两端中心内缩 19dp（57px）
        assertEquals(57f, sliderAnchorCenterX(width, 0f, isLiquidGlass = false, density = density), 0.01f)
        assertEquals(450f, sliderAnchorCenterX(width, 0.5f, isLiquidGlass = false, density = density), 0.01f)
        assertEquals(843f, sliderAnchorCenterX(width, 1f, isLiquidGlass = false, density = density), 0.01f)

        // LiquidSlider：40dp 拇指，中心在 width * fraction 并夹持在 [10dp, width - 10dp]（30px..870px）
        assertEquals(30f, sliderAnchorCenterX(width, 0f, isLiquidGlass = true, density = density), 0.01f)
        assertEquals(450f, sliderAnchorCenterX(width, 0.5f, isLiquidGlass = true, density = density), 0.01f)
        assertEquals(870f, sliderAnchorCenterX(width, 1f, isLiquidGlass = true, density = density), 0.01f)
        assertEquals(225f, sliderAnchorCenterX(width, 0.25f, isLiquidGlass = true, density = density), 0.01f)
    }
}
