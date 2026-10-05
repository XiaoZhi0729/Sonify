package yos.music.player.ui.widgets.basic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiquidBackdropLuminanceTest {
    @Test
    fun missingOrTransparentSamplesKeepThePreviousLuminance() {
        assertNull(liquidBackdropLuminance(intArrayOf()))
        assertNull(liquidBackdropLuminance(IntArray(25) { 0x00FFFFFF }))
    }

    @Test
    fun grayBelowThresholdAndRec709ChannelWeightsMatchDemo() {
        assertTrue(liquidBackdropLuminance(intArrayOf(0xFF7F7F7F.toInt()))!! < 0.5f)
        assertEquals(0.2126f, liquidBackdropLuminance(intArrayOf(0xFFFF0000.toInt()))!!, 0.00001f)
        assertEquals(0.7152f, liquidBackdropLuminance(intArrayOf(0xFF00FF00.toInt()))!!, 0.00001f)
        assertEquals(0.0722f, liquidBackdropLuminance(intArrayOf(0xFF0000FF.toInt()))!!, 0.00001f)
    }
    @Test
    fun transparentCornersDoNotDarkenWhiteBackdrop() {
        val pixels = IntArray(25) { 0xFFFFFFFF.toInt() }
        for (index in intArrayOf(0, 4, 20, 24)) pixels[index] = 0
        assertEquals(1f, liquidBackdropLuminance(pixels)!!, 0.00001f)
    }

    @Test
    fun blackAndWhiteStayAtTheExpectedEndpoints() {
        assertEquals(0f, liquidBackdropLuminance(IntArray(25) { 0xFF000000.toInt() })!!, 0.00001f)
        assertEquals(1f, liquidBackdropLuminance(IntArray(25) { 0xFFFFFFFF.toInt() })!!, 0.00001f)
    }

    @Test
    fun middleGrayMatchesDemoAndSelectsDarkForeground() {
        val luminance = liquidBackdropLuminance(IntArray(25) { 0xFF808080.toInt() })!!
        assertEquals(128f / 255f, luminance, 0.00001f)
        assertTrue(luminance > 0.5f)
    }
}
