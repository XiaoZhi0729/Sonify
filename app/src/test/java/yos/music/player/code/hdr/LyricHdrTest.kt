package yos.music.player.code.hdr

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricHdrTest {

    @Test
    fun targetBoostForClampsAndScalesMonotonically() {
        assertEquals(1f, LyricHdr.targetBoostFor(-0.5f), 0.0001f)
        assertEquals(1f, LyricHdr.targetBoostFor(0f), 0.0001f)
        assertEquals(1f, LyricHdr.targetBoostFor(0.0005f), 0.0001f)
        assertEquals(2.2f, LyricHdr.targetBoostFor(0.6f), 0.0001f)
        assertEquals(LyricHdr.MaxBoost, LyricHdr.targetBoostFor(1f), 0.0001f)
        assertEquals(LyricHdr.MaxBoost, LyricHdr.targetBoostFor(2f), 0.0001f)

        val steps = listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f)
        for (i in 0 until steps.lastIndex) {
            val prev = LyricHdr.targetBoostFor(steps[i])
            val next = LyricHdr.targetBoostFor(steps[i + 1])
            assertTrue("Expected $prev < $next", prev < next)
        }
    }

    @Test
    fun hdrLiftReturnsOriginalWhenGainAtMostOne() {
        val sample = Color(0.75f, 0.35f, 0.2f, 0.65f)
        assertEquals(sample, sample.hdrLift(1f))
        assertEquals(sample, sample.hdrLift(0.5f))
    }

    @Test
    fun hdrLiftScalesWhiteUniformlyInLinearExtendedSrgb() {
        val lifted = Color.White.hdrLift(2.5f)
        assertEquals(ColorSpaces.LinearExtendedSrgb, lifted.colorSpace)
        assertEquals(2.5f, lifted.red, 0.001f)
        assertEquals(2.5f, lifted.green, 0.001f)
        assertEquals(2.5f, lifted.blue, 0.001f)
        assertEquals(1f, lifted.alpha, 0.001f)
    }

    @Test
    fun hdrLiftPreservesChromaShadowAndAlphaOnSaturatedColor() {
        val orange = Color(1f, 0.2f, 0.1f, 0.8f)
        val linearOrange = orange.convert(ColorSpaces.LinearExtendedSrgb)
        val gain = 2.5f
        val lifted = orange.hdrLift(gain)

        assertEquals(ColorSpaces.LinearExtendedSrgb, lifted.colorSpace)
        assertEquals(0.8f, lifted.alpha, 0.001f)
        // 最高通道按 gain 抬升，最低通道保留在原始 SDR 线性亮度附近不泛白
        val expectedMax = maxOf(linearOrange.red, linearOrange.green, linearOrange.blue) * gain
        val expectedMin = minOf(linearOrange.red, linearOrange.green, linearOrange.blue)
        val actualMax = maxOf(lifted.red, lifted.green, lifted.blue)
        val actualMin = minOf(lifted.red, lifted.green, lifted.blue)
        assertEquals(expectedMax, actualMax, 0.001f)
        assertEquals(expectedMin, actualMin, 0.001f)
    }
}
