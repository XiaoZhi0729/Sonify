package yos.music.player.code.vocalsep

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.data.vocalsep.MssEngine
import yos.music.player.data.vocalsep.MssModel
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 处理器的 PCM 契约自检。
 *
 * 重点验证四件会直接导致"没声音 / 时长漂移 / 单声道全废"的事：
 * 1. 样本数守恒（输出帧数恒等于输入帧数）——不守恒会让 sink 的时间轴漂移
 * 2. 关闭态是**逐样本**直通（零延迟、零改动）
 * 3. 单声道不抛异常而是退成 inactive（抛异常会让整首歌播不出来）
 * 4. 16-bit 与 float 两种编码都能走通
 */
class VocalSeparationProcessorTest {

    /** 恒等模型：人声=输入频谱、其余填零，于是人声+伴奏 重建输入。 */
    private class IdentityModel : MssModel {
        override val stateSizes = IntArray(28) { 1 }
        override fun run(spectrumIn: FloatArray, states: Array<FloatArray>, spectrumOut: FloatArray) {
            java.util.Arrays.fill(spectrumOut, 0f)
            for (t in 0 until MssEngine.FRAMES) {
                for (c in 0 until 2) {
                    val src = t * 4 * MssEngine.BINS + c * 2 * MssEngine.BINS
                    val dst = t * 16 * MssEngine.BINS + c * 2 * MssEngine.BINS
                    System.arraycopy(spectrumIn, src, spectrumOut, dst, MssEngine.BINS * 2)
                }
            }
        }
        override fun close() = Unit
    }

    private fun newProcessor(): VocalSeparationProcessor =
        VocalSeparationProcessor(engineProvider = { MssEngine(IdentityModel()) })

    private fun pcm16(vararg values: Short): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(values.size * 2).order(ByteOrder.nativeOrder())
        buf.asShortBuffer().put(values)
        buf.position(0)
        return buf
    }

    private fun pcmFloat(vararg values: Float): ByteBuffer {
        val buf = ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
        buf.asFloatBuffer().put(values)
        buf.position(0)
        return buf
    }

    @Test
    fun `disabled path is a bit exact pass through`() {
        val p = newProcessor()
        val format = AudioFormat(MssEngine.SR, 2, C.ENCODING_PCM_16BIT)
        p.configure(format)
        p.flush()
        assertTrue(p.isActive)

        val samples = shortArrayOf(1, -2, 3, -4, 5, -6, 7, -8)
        p.queueInput(pcm16(*samples))
        val out = p.getOutput()
        assertEquals("byte count must be preserved", samples.size * 2, out.remaining())
        val got = ShortArray(samples.size)
        out.asShortBuffer().get(got)
        assertArrayEqualsShort(samples, got)
    }

    /**
     * 开启态的样本守恒：**累计**输出帧数 == 累计输入帧数。
     *
     * 不能按单次调用断言 1:1——引擎成块产出（攒满 4096 才吐 4096），
     * 中途几次调用会返回空缓冲，这是 Media3 处理器的正常形态（Sonic 也如此）。
     * 稳态下整条流的时长必须不漂，这才是要保的性质。
     */
    @Test
    fun `sample count is conserved while enabled for 16 bit`() {
        val p = newProcessor()
        p.configure(AudioFormat(MssEngine.SR, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.setEnabled(true)

        var inFrames = 0
        var outFrames = 0
        for (round in 0 until 20) {
            val frames = 1024
            val samples = ShortArray(frames * 2) { (it % 100).toShort() }
            p.queueInput(pcm16(*samples))
            inFrames += frames
            outFrames += p.getOutput().remaining() / 4
        }
        // 允许差一块（引擎预热期），但不得越滚越大
        assertTrue(
            "cumulative frames must stay within one block: in=$inFrames out=$outFrames",
            inFrames - outFrames in 0..MssEngine.CHUNK
        )
    }

    @Test
    fun `sample count is conserved while enabled for float`() {
        val p = newProcessor()
        p.configure(AudioFormat(MssEngine.SR, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        p.setEnabled(true)

        var inFrames = 0
        var outFrames = 0
        for (round in 0 until 20) {
            val frames = 1000
            val samples = FloatArray(frames * 2) { (it % 50) / 100f }
            p.queueInput(pcmFloat(*samples))
            inFrames += frames
            outFrames += p.getOutput().remaining() / 8
        }
        assertTrue(
            "cumulative frames must stay within one block: in=$inFrames out=$outFrames",
            inFrames - outFrames in 0..MssEngine.CHUNK
        )
    }

    @Test
    fun `non block aligned input still yields the same total frame count`() {
        val p = newProcessor()
        p.configure(AudioFormat(MssEngine.SR, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.setEnabled(true)

        var inFrames = 0
        var outFrames = 0
        // 刻意用各种非 4096 整数倍的帧数
        for (frames in intArrayOf(1, 7, 100, 4095, 4097, 5000, 1024, 1024, 1024, 1024)) {
            val samples = ShortArray(frames * 2) { (it % 80).toShort() }
            p.queueInput(pcm16(*samples))
            inFrames += frames
            outFrames += p.getOutput().remaining() / 4
        }
        assertTrue(
            "cumulative frames must stay within one block: in=$inFrames out=$outFrames",
            inFrames - outFrames in 0..MssEngine.CHUNK
        )
    }

    @Test
    fun `mono is rejected as inactive instead of throwing`() {
        val p = newProcessor()
        // 不能抛异常：DefaultAudioSink 会把 UnhandledAudioFormatException 包成
        // ConfigurationException，整首歌直接播不出来
        val result = p.configure(AudioFormat(MssEngine.SR, 1, C.ENCODING_PCM_16BIT))
        assertFalse("mono must not be active", p.isActive)
        // BaseAudioProcessor.configure 在 inactive 时对外返回 NOT_SET（pipeline 据此跳过本处理器）
        assertEquals("inactive processor must report NOT_SET", -1, result.channelCount)
    }

    @Test
    fun `non 44k sample rate keeps cumulative frame count`() {
        val p = newProcessor()
        p.configure(AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.setEnabled(true)

        fun runRounds(rounds: Int): Int {
            var outFrames = 0
            repeat(rounds) {
                val frames = 1024
                val samples = ShortArray(frames * 2) { (it % 90).toShort() }
                p.queueInput(pcm16(*samples))
                outFrames += p.getOutput().remaining() / 4
            }
            return outFrames
        }

        // 先跑 20 轮拿基准，再跑 40 轮：差额必须**不随轮数增长**（有界，不是漂移）
        val roundsA = 20
        val outA = runRounds(roundsA)
        val inA = roundsA * 1024
        val deficitA = inA - outA

        val roundsB = 40
        val outB = runRounds(roundsB)
        val inB = roundsB * 1024
        val deficitB = inB - outB

        // 有界：最多差一块引擎块（在 48k 输出域里放大）
        val bound = (MssEngine.CHUNK.toLong() * 48000 / MssEngine.SR).toInt() + 256
        assertTrue(
            "48k deficit must be bounded: after ${roundsA} rounds=$deficitA, after $roundsB rounds=$deficitB, bound=$bound",
            deficitA in 0..bound && deficitB in 0..bound
        )
        // 有界即不漂：多跑 20 轮，差额不得继续累加
        assertTrue(
            "48k deficit must not grow with playback length: $deficitA -> $deficitB",
            deficitB - deficitA <= bound
        )
    }

    @Test
    fun `reset engine state keeps the switch on`() {
        val p = newProcessor()
        p.configure(AudioFormat(MssEngine.SR, 2, C.ENCODING_PCM_16BIT))
        p.flush()
        p.setEnabled(true)
        assertTrue(p.currentEnabled())

        p.resetEngineState()
        assertTrue("seek must not turn the feature off", p.currentEnabled())
    }

    /**
     * 端到端内容校验（最关键的一条）：开启状态下，恒等模型应当让输出**重建出输入**，
     * 延迟恰好是**一整块**（[MssEngine.CHUNK] 帧 = 93 ms）。
     *
     * 这条用例专门抓"块对齐"类的错误。历史教训：如果强行让每次 `queueInput`
     * 都等量输出（不足补静音），引擎的成块产出与平滑消费之差会变成每块一次的断点，
     * SNR 从 130+ dB 掉到 −3 dB——而样本总数依然守恒，只查长度的用例发现不了。
     */
    @Test
    fun `identity model reconstructs the signal through the processor`() {
        val p = newProcessor()
        p.configure(AudioFormat(MssEngine.SR, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        p.setRatio(1f)
        p.setEnabled(true)

        val totalFrames = MssEngine.CHUNK * 12
        // 左右不同频率，顺带验证声道不串
        val input = FloatArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            input[2 * i] = (0.4 * kotlin.math.sin(2 * Math.PI * 440.0 * i / MssEngine.SR)).toFloat()
            input[2 * i + 1] = (0.4 * kotlin.math.sin(2 * Math.PI * 660.0 * i / MssEngine.SR)).toFloat()
        }

        // 按 media3 的真实形态取输出：每次能拿多少拿多少，拿不到就下次再取。
        // 刻意用"非块长"的输入尺寸，模拟解码器不规则输出。
        val collected = ArrayList<Float>(totalFrames * 2)
        val callSizes = intArrayOf(1000, 4096, 777, 5000, 2000, 3000)
        var srcPos = 0
        var callIndex = 0
        while (srcPos < totalFrames) {
            val want = callSizes[callIndex % callSizes.size]
            val take = minOf(want, totalFrames - srcPos)
            val chunk = input.copyOfRange(srcPos * 2, (srcPos + take) * 2)
            p.queueInput(pcmFloat(*chunk))
            val out = p.getOutput()
            val got = FloatArray(out.remaining() / 4)
            out.asFloatBuffer().get(got)
            for (v in got) collected.add(v)
            srcPos += take
            callIndex++
        }
        val output = collected.toFloatArray()

        assertEquals(
            "total frames out must equal total frames in",
            totalFrames * 2,
            output.size
        )

        // 延迟 = 一整块；跳过开头 4 块（淡入未完成）
        val lag = MssEngine.CHUNK
        val skip = MssEngine.CHUNK * 6
        var signal = 0.0
        var noise = 0.0
        for (i in skip until totalFrames) {
            for (c in 0 until 2) {
                val expected = input[(i - lag) * 2 + c]
                val actual = output[i * 2 + c]
                signal += expected.toDouble() * expected
                val err = (actual - expected).toDouble()
                noise += err * err
            }
        }
        val snrDb = 10 * kotlin.math.log10(signal / maxOf(noise, 1e-30))
        assertTrue(
            "processor must reconstruct the signal (identity model), SNR was $snrDb dB",
            snrDb > 100.0
        )
    }

    private fun assertArrayEqualsShort(expected: ShortArray, actual: ShortArray) {
        assertEquals("length", expected.size, actual.size)
        for (i in expected.indices) {
            assertEquals("index $i", expected[i], actual[i])
        }
    }

    // ------------------------------------------------------------ 混音律

    /**
     * 跑 [blocks] 块正弦并收集输出。返回 (输入, 输出)。
     *
     * 调用方按 `MssEngine.CHUNK` 整数块喂，保证每次 queueInput 正好凑满一块，
     * 输出节奏与输入一一对应，便于按整块做内容断言。
     */
    private fun runBlocks(ratio: Float, blocks: Int): Pair<FloatArray, FloatArray> {
        val p = newProcessor()
        p.configure(AudioFormat(MssEngine.SR, 2, C.ENCODING_PCM_FLOAT))
        p.flush()
        p.setRatio(ratio)
        p.setEnabled(true)

        val totalFrames = MssEngine.CHUNK * blocks
        val input = FloatArray(totalFrames * 2)
        for (i in 0 until totalFrames) {
            input[2 * i] = (0.4 * kotlin.math.sin(2 * Math.PI * 440.0 * i / MssEngine.SR)).toFloat()
            input[2 * i + 1] = (0.4 * kotlin.math.sin(2 * Math.PI * 660.0 * i / MssEngine.SR)).toFloat()
        }

        val collected = ArrayList<Float>(totalFrames * 2)
        var srcPos = 0
        while (srcPos < totalFrames) {
            val take = minOf(MssEngine.CHUNK, totalFrames - srcPos)
            val chunk = input.copyOfRange(srcPos * 2, (srcPos + take) * 2)
            p.queueInput(pcmFloat(*chunk))
            val out = p.getOutput()
            val got = FloatArray(out.remaining() / 4)
            out.asFloatBuffer().get(got)
            for (v in got) collected.add(v)
            srcPos += take
        }
        return input to collected.toFloatArray()
    }

    /**
     * 最下方 0% = **纯伴奏**，不是纯人声。
     *
     * 恒等模型的伴奏分轨全零（它把人声分轨填成输入频谱、其余留零），
     * 所以 0% 的输出必须接近静音。旧版等功率律在 0% 给出的是纯人声（= 完整信号），
     * 这条用例专门抓"方向反了"。
     */
    @Test
    fun `ratio zero outputs the accompaniment stem`() {
        val blocks = 12
        val (_, output) = runBlocks(ratio = 0f, blocks = blocks)

        // 跳过开头 6 块：开关淡变（120ms）与占比淡变都还没走完
        val skip = MssEngine.CHUNK * 6
        var peak = 0f
        for (i in skip until output.size) {
            val a = kotlin.math.abs(output[i])
            if (a > peak) peak = a
        }
        assertTrue("ratio=0 must be the accompaniment stem (all zero here), peak was $peak", peak < 1e-4f)
    }

    /**
     * 50% = 原声与伴奏的线性对半，**不是**等功率混合。
     *
     * 恒等模型下伴奏分轨为零，所以输出必须恰为 `0.5 × 上一块干声`。
     * 若有人把权重改回 sin/cos，这里会读到 0.707 而不是 0.5，直接被抓住。
     */
    @Test
    fun `half ratio is a linear blend with the dry signal`() {
        val blocks = 12
        val (input, output) = runBlocks(ratio = 0.5f, blocks = blocks)

        val lag = MssEngine.CHUNK
        val skip = MssEngine.CHUNK * 6
        var maxErr = 0f
        val totalFrames = input.size / 2
        for (i in skip until totalFrames) {
            for (c in 0 until 2) {
                val expected = 0.5f * input[(i - lag) * 2 + c]
                val err = kotlin.math.abs(output[i * 2 + c] - expected)
                if (err > maxErr) maxErr = err
            }
        }
        assertTrue("ratio=0.5 must be exactly half of dry (linear law), max err was $maxErr", maxErr < 1e-5f)
    }
}
