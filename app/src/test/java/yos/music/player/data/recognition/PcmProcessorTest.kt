package yos.music.player.data.recognition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 识曲 PCM 前处理的纯函数回归测试。
 *
 * 这些参数直接决定酷狗指纹能不能命中，而算法是从参照实现（md3Music
 * recognition_utils.dart）逐行搬过来的，任何"顺手优化"都应该先让这里失败。
 */
class PcmProcessorTest {

    /** 生成 16bit 小端单声道 PCM（正弦波）。 */
    private fun tone(freq: Double, sampleRate: Int, samples: Int, amplitude: Int): ByteArray {
        val out = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * freq * i / sampleRate) * amplitude).roundToInt()
            out[i * 2] = (v and 0xFF).toByte()
            out[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }
        return out
    }

    @Test
    fun `max amplitude reads little endian samples`() {
        val pcm = tone(440.0, 8000, 800, 12000)
        val max = PcmProcessor.computeMaxAmplitude(pcm)
        // 正弦峰值与振幅同量级（允许一个量化误差）
        assertTrue("max=$max", max in 11000..12100)
    }

    @Test
    fun `silence has zero amplitude`() {
        assertEquals(0, PcmProcessor.computeMaxAmplitude(ByteArray(1600)))
    }

    @Test
    fun `downsample keeps duration ratio`() {
        val input = tone(440.0, 44100, 44100, 8000) // 1 秒
        val out = PcmProcessor.downsample(input, 44100, 8000)
        // 1 秒 → 8000 点 → 16000 字节
        assertEquals(16000, out.size)
    }

    @Test
    fun `downsample is identity at same rate`() {
        val input = tone(440.0, 8000, 800, 8000)
        assertTrue(input === PcmProcessor.downsample(input, 8000, 8000))
    }

    /**
     * 回归：8 秒整段的采样数 352800，乘以 8000 会溢出 Int（2.82e9 > 2.147e9），
     * 溢出后输出长度算成 0 → 整段 PCM 变空 → 每段都被判静音，识曲永远不发出请求。
     */
    @Test
    fun `downsample a full eight second segment without int overflow`() {
        val input = ByteArray(44100 * 8 * 2) // 705600 字节 = 8 秒 44.1kHz mono PCM16
        val out = PcmProcessor.downsample(input, 44100, 8000)
        assertEquals(8000 * 8 * 2, out.size) // 128000 字节
    }

    @Test
    fun `downsample preserves a 440Hz tone`() {
        val input = tone(440.0, 44100, 44100, 8000)
        val out = PcmProcessor.downsample(input, 44100, 8000)
        // 降采样后仍是 440Hz：峰值振幅应保留大半（均值窗会轻微衰减高频，440Hz 几乎无损）
        val max = PcmProcessor.computeMaxAmplitude(out)
        assertTrue("max=$max", max > 6000)
    }

    @Test
    fun `normalizeGain lifts peak to target and removes dc`() {
        // 带 1000 直流偏移的小信号
        val samples = 800
        val pcm = ByteArray(samples * 2)
        for (i in 0 until samples) {
            val v = (sin(2 * PI * 220.0 * i / 8000) * 500).roundToInt() + 1000
            pcm[i * 2] = (v and 0xFF).toByte()
            pcm[i * 2 + 1] = ((v shr 8) and 0xFF).toByte()
        }

        val out = PcmProcessor.normalizeGain(pcm, targetAmplitude = 25000)

        // 去 DC：均值应接近 0
        var sum = 0L
        for (i in 0 until samples) sum += PcmProcessor.sampleAt(out, i)
        val mean = sum.toDouble() / samples
        assertTrue("mean=$mean", abs(mean) < 300)

        // 峰值应被拉到目标附近
        val max = PcmProcessor.computeMaxAmplitude(out)
        assertTrue("max=$max", max in 24000..25000)
    }

    @Test
    fun `prepareSegmentPcm flags silence and normalizes audible segments`() {
        val silent = prepareSegmentPcm(ByteArray(44100 * 2 / 2))
        assertTrue(silent.isSilent)

        val audible = prepareSegmentPcm(tone(440.0, 44100, 44100, 2000))
        assertTrue(!audible.isSilent)
        assertEquals(16000, audible.pcm.size)
        assertTrue(PcmProcessor.computeMaxAmplitude(audible.pcm) >= RecognitionConfig.TARGET_AMPLITUDE - 2000)
    }

    /** 8 秒整段（真实识曲链路的长度）：不能因为整数溢出把 PCM 处理成空。 */
    @Test
    fun `prepareSegmentPcm keeps a full eight second segment`() {
        val raw = tone(440.0, 44100, 44100 * RecognitionConfig.SEGMENT_SECONDS, 3000)
        val segment = prepareSegmentPcm(raw)
        assertTrue(!segment.isSilent)
        assertEquals(RecognitionConfig.TARGET_SAMPLE_RATE * RecognitionConfig.SEGMENT_SECONDS * 2, segment.pcm.size)
        assertTrue(segment.maxAmplitude > RecognitionConfig.SILENCE_THRESHOLD)
    }
}
