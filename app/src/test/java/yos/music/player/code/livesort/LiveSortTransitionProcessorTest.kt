package yos.music.player.code.livesort

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class LiveSortTransitionProcessorTest {

    private fun pcm16(samples: ShortArray): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(samples.size * 2).order(ByteOrder.nativeOrder())
        buf.asShortBuffer().put(samples)
        buf.position(0)
        return buf
    }

    @Test
    fun `passthrough mode is bit exact for 16 bit pcm`() {
        val processor = LiveSortTransitionProcessor()
        processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        assertTrue(processor.isActive)

        val input = shortArrayOf(12, -34, 567, -890, 12345, -23456, 1, -1)
        processor.queueInput(pcm16(input))
        val out = processor.getOutput()
        assertEquals(input.size * 2, out.remaining())

        val got = ShortArray(input.size)
        out.asShortBuffer().get(got)
        assertArrayEquals(input, got)
    }

    @Test
    fun `mono input marks processor inactive without throwing`() {
        val processor = LiveSortTransitionProcessor()
        processor.configure(AudioFormat(44_100, 1, C.ENCODING_PCM_16BIT))
        assertFalse(processor.isActive)
    }

    @Test
    fun `underwater tone filter attenuates high frequencies while keeping bass and finite output`() {
        val processor = LiveSortTransitionProcessor()
        val sr = 44_100
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.setEffects(depth = 0.9f, reverb = 0.25f)

        val frames = 4096
        val highFreqSamples = ShortArray(frames * 2) { idx ->
            val frame = idx / 2
            (16000.0 * sin(2.0 * PI * 8000.0 * frame / sr)).toInt().toShort()
        }

        // 连续送多块让 180ms 一阶平滑达到稳态目标深度
        var lastOut = ShortArray(0)
        repeat(12) {
            processor.queueInput(pcm16(highFreqSamples))
            val out = processor.getOutput()
            assertEquals(frames * 4, out.remaining())
            lastOut = ShortArray(frames * 2)
            out.asShortBuffer().get(lastOut)
        }

        val inputPeak = highFreqSamples.maxOf { abs(it.toInt()) }
        val tailPeak = lastOut.drop(frames).maxOf { abs(it.toInt()) }
        assertTrue(
            "high frequency energy should be strongly attenuated by underwater filter: in=$inputPeak out=$tailPeak",
            tailPeak < inputPeak / 3,
        )
    }

    @Test
    fun `sample frame count is strictly conserved during dsp transition`() {
        val processor = LiveSortTransitionProcessor()
        processor.configure(AudioFormat(48_000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush()
        processor.setEffects(depth = 0.6f, reverb = 0.3f)

        val frames = 1024
        val buf = ByteBuffer.allocateDirect(frames * 8).order(ByteOrder.nativeOrder())
        val fb = buf.asFloatBuffer()
        for (i in 0 until frames * 2) {
            fb.put(0.25f * sin(i * 0.05f))
        }
        buf.position(0)
        processor.queueInput(buf)
        val out = processor.getOutput()
        assertEquals(frames * 8, out.remaining())

        val outFloats = FloatArray(frames * 2)
        out.asFloatBuffer().get(outFloats)
        assertTrue(outFloats.all { it.isFinite() && abs(it) <= 1f })
    }

    @Test
    fun `resetting effects returns processor to bit exact passthrough`() {
        val processor = LiveSortTransitionProcessor()
        processor.configure(AudioFormat(44_100, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.setEffects(depth = 0.8f, reverb = 0.3f)

        val warm = ShortArray(512 * 2) { 1000 }
        processor.queueInput(pcm16(warm))
        processor.getOutput()

        processor.resetEffects(immediate = true)
        val probe = shortArrayOf(3, -5, 11, -17, 101, -202, 303, -404)
        processor.queueInput(pcm16(probe))
        val out = processor.getOutput()
        val got = ShortArray(probe.size)
        out.asShortBuffer().get(got)
        assertArrayEquals(probe, got)
    }

    @Test
    fun `primed processor filters the very first buffer without leaking dry audio`() {
        val processor = LiveSortTransitionProcessor()
        val sr = 44_100
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.primeEffects(depth = 0.85f, reverb = 0.35f)

        val frames = 1024
        val highFreq = ShortArray(frames * 2) { idx ->
            val frame = idx / 2
            (18000.0 * sin(2.0 * PI * 9000.0 * frame / sr)).toInt().toShort()
        }
        processor.queueInput(pcm16(highFreq))
        val out = processor.getOutput()
        val got = ShortArray(frames * 2)
        out.asShortBuffer().get(got)
        val tailPeak = got.drop(frames).maxOf { abs(it.toInt()) }
        assertTrue("primed processor must immediately attenuate 9kHz tone on first buffer: peak=$tailPeak", tailPeak < 4000)
    }

    @Test
    fun `spatial reverb produces audible stereo tail after active signal stops`() {
        val processor = LiveSortTransitionProcessor()
        val sr = 44_100
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.primeEffects(depth = 0.7f, reverb = 0.38f)

        val frames = 2048
        val burst = ShortArray(frames * 2) { idx ->
            val frame = idx / 2
            if (frame < 512) (14000.0 * sin(2.0 * PI * 800.0 * frame / sr)).toInt().toShort() else 0
        }
        processor.queueInput(pcm16(burst))
        processor.getOutput()

        // Feed pure silence and verify stereo reverb tail continues resonating
        val silence = ShortArray(frames * 2)
        processor.queueInput(pcm16(silence))
        val tailBuf = processor.getOutput()
        val tailSamples = ShortArray(frames * 2)
        tailBuf.asShortBuffer().get(tailSamples)
        val tailPeak = tailSamples.maxOf { abs(it.toInt()) }
        assertTrue("spatial reverb should emit subtle diffused tail (>40) into silence, got $tailPeak", tailPeak > 40)
    }

    @Test
    fun `vocal band stays clear during mid transition depth and reverb`() {
        val processor = LiveSortTransitionProcessor()
        val sr = 44_100
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.primeEffects(depth = 0.5f, reverb = 0.12f)

        val frames = 2048
        val vocalTone = ShortArray(frames * 2) { idx ->
            val frame = idx / 2
            (14000.0 * sin(2.0 * PI * 1000.0 * frame / sr)).toInt().toShort()
        }
        var lastOut = ShortArray(0)
        repeat(4) {
            processor.queueInput(pcm16(vocalTone))
            val out = processor.getOutput()
            lastOut = ShortArray(frames * 2)
            out.asShortBuffer().get(lastOut)
        }
        val inPeak = vocalTone.maxOf { abs(it.toInt()) }
        val outPeak = lastOut.drop(frames).maxOf { abs(it.toInt()) }
        assertTrue(
            "1kHz vocal band must remain clear (>70% amplitude) during mid transition: in=$inPeak out=$outPeak",
            outPeak > inPeak * 0.7f,
        )
    }

    @Test
    fun `full scale bass plus treble does not clip or distort during max depth and reverb`() {
        val processor = LiveSortTransitionProcessor()
        val sr = 44_100
        processor.configure(AudioFormat(sr, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        processor.primeEffects(depth = 1.0f, reverb = 0.24f)

        val frames = 2048
        var globalMaxPeak = 0
        var flatTopCount = 0
        var lastBlock = ShortArray(0)
        var lastInBlock = ShortArray(0)

        for (block in 0 until 12) {
            val baseFrame = block * frames
            val input = ShortArray(frames * 2) { idx ->
                val f = baseFrame + idx / 2
                val mix = 0.45 * sin(2.0 * PI * 80.0 * f / sr) +
                    0.30 * sin(2.0 * PI * 440.0 * f / sr) +
                    0.20 * sin(2.0 * PI * 4000.0 * f / sr)
                (mix * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
            }
            lastInBlock = input
            processor.queueInput(pcm16(input))
            val outBuf = processor.getOutput()
            assertEquals(frames * 4, outBuf.remaining())

            val out = ShortArray(frames * 2)
            outBuf.asShortBuffer().get(out)
            lastBlock = out

            var prevL = Short.MIN_VALUE
            for (i in 0 until frames) {
                val l = out[i * 2]
                val r = out[i * 2 + 1]
                val absL = abs(l.toInt())
                val absR = abs(r.toInt())
                if (absL > globalMaxPeak) globalMaxPeak = absL
                if (absR > globalMaxPeak) globalMaxPeak = absR
                if (absL > 31500 && l == prevL) {
                    flatTopCount++
                }
                prevL = l
            }
        }

        assertTrue("output peak ($globalMaxPeak) must stay strictly below 32100 without clipping", globalMaxPeak < 32100)
        assertEquals("flat-top clipping should never occur, count=$flatTopCount", 0, flatTopCount)

        // Verify steady-state warmth stays bounded near unity without comb filter energy runaway
        val inEnergy = lastInBlock.sumOf { it.toDouble() * it.toDouble() } / lastInBlock.size
        val outEnergy = lastBlock.sumOf { it.toDouble() * it.toDouble() } / lastBlock.size
        assertTrue("output should stay cleanly audible: peak=$globalMaxPeak outEnergy=$outEnergy", globalMaxPeak > 3000 && outEnergy > 1e6)
        assertTrue("steady-state energy must remain bounded without comb runaway: in=$inEnergy out=$outEnergy", outEnergy < inEnergy * 1.15)
    }
}
