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
        assertEquals(1f + (LyricHdr.MaxBoost - 1f) * 0.6f, LyricHdr.targetBoostFor(0.6f), 0.0001f)
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
    fun hdrLiftScalesAllChannelsUniformlyToPreserveChromaticity() {
        val coverTint = Color(1f, 0.2f, 0.1f, 0.8f)
        val mixed = Color(
            red = 0.7f + 0.3f * coverTint.red,
            green = 0.7f + 0.3f * coverTint.green,
            blue = 0.7f + 0.3f * coverTint.blue,
            alpha = 0.8f,
        )
        val linearMixed = mixed.convert(ColorSpaces.LinearExtendedSrgb)
        val gain = 2.5f
        val lifted = mixed.hdrLift(gain)

        assertEquals(ColorSpaces.LinearExtendedSrgb, lifted.colorSpace)
        assertEquals(0.8f, lifted.alpha, 0.001f)
        // 三通道在线性光下等比放大，严格保持色度不变
        assertEquals(linearMixed.red * gain, lifted.red, 0.001f)
        assertEquals(linearMixed.green * gain, lifted.green, 0.001f)
        assertEquals(linearMixed.blue * gain, lifted.blue, 0.001f)
    }

    @Test
    fun resolveBoostCapsByMeasuredHeadroomWithMargin() {
        val target = LyricHdr.targetBoostFor(0.6f)
        // 未报告有效余量（<= 1.05）时按 target 唤醒 HDR 管线
        assertEquals(target, LyricHdr.resolveBoost(0.6f, 1f), 0.0001f)
        // 已报告较低余量时按 headroom * 0.92f 封顶，避免高光漂白
        assertEquals(1.5f * 0.92f, LyricHdr.resolveBoost(0.6f, 1.5f), 0.0001f)
        // 余量充足时仍按用户设置的 target 输出
        assertEquals(target, LyricHdr.resolveBoost(0.6f, 3.0f), 0.0001f)
    }
}
