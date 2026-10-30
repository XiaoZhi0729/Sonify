package yos.music.player.data.vocalsep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 引擎的 STFT / iSTFT / 加权重叠相加自检。
 *
 * 用**恒等模型**（把输入频谱原样当作"人声"、其余三轨填零）驱动引擎：
 * 此时 `人声 + 伴奏` 应当逐样本重建出输入（只差一块延迟）。
 * 这条路径能抓住三个曾经翻过车的错误：
 * 1. 频谱平面布局写成 re/im 交错（corr 会从 1.0 掉到 -0.02）
 * 2. OLA 尾量整块拷进左声道而不是分段（SNR 从 157 dB 掉到 34.9 dB）
 * 3. 每块消费样本数写成 CHUNK 而不是 CHUNK+LOOK（数组越界或错位）
 *
 * 恒等模型只填 stem 0，因此它也顺带验证了"人声=stem0、伴奏=stem1..3 求和"的取值布局。
 */
class MssEngineTest {

    /**
     * 恒等模型：把输入频谱（4 平面）复制到输出的 stem 0（人声），
     * 其余三轨填零，于是 人声+伴奏 恰好等于输入。
     */
    private class IdentityModel : MssModel {
        override val stateSizes = IntArray(28) { 1 }

        override fun run(spectrumIn: FloatArray, states: Array<FloatArray>, spectrumOut: FloatArray) {
            java.util.Arrays.fill(spectrumOut, 0f)
            for (t in 0 until MssEngine.FRAMES) {
                for (c in 0 until 2) {
                    // 输入 base = t*4096 + c*2*1024（c 的 4 个平面里前两个是 L 或 R）
                    val src = t * 4 * MssEngine.BINS + c * 2 * MssEngine.BINS
                    // 输出 base = t*16384 + plane*1024，stem0/ch c 的 re 在 (c*2)，im 在 (c*2+1)
                    val dst = t * 16 * MssEngine.BINS + c * 2 * MssEngine.BINS
                    System.arraycopy(
                        spectrumIn, src,
                        spectrumOut, dst,
                        MssEngine.BINS * 2
                    )
                }
            }
            // 状态原地保留（恒等模型不需要回灌语义，但必须不动数组长度）
            for (s in states) java.util.Arrays.fill(s, 0f)
        }

        override fun close() = Unit
    }

    /** 生成一段立体声测试信号：左右不同频率，确保声道不会串。 */
    private fun testSignal(frames: Int): FloatArray {
        val out = FloatArray(frames * 2)
        for (i in 0 until frames) {
            out[2 * i] = (0.5 * sin(2 * PI * 440.0 * i / MssEngine.SR)).toFloat()
            out[2 * i + 1] = (0.5 * sin(2 * PI * 660.0 * i / MssEngine.SR)).toFloat()
        }
        return out
    }

    @Test
    fun `identity model reconstructs input with one block delay`() {
        val engine = MssEngine(IdentityModel())
        val blocks = 12
        val totalFrames = blocks * MssEngine.CHUNK
        val input = testSignal(totalFrames)

        // 逐块喂，逐块收（与处理器里的调用节奏一致）
        val vocalsOut = FloatArray(totalFrames * 2)
        val accompOut = FloatArray(totalFrames * 2)
        for (b in 0 until blocks) {
            val chunk = input.copyOfRange(
                b * MssEngine.CHUNK * 2,
                (b + 1) * MssEngine.CHUNK * 2
            )
            val result = engine.process(chunk, MssEngine.CHUNK)
            System.arraycopy(result[0], 0, vocalsOut, b * MssEngine.CHUNK * 2, MssEngine.CHUNK * 2)
            System.arraycopy(result[1], 0, accompOut, b * MssEngine.CHUNK * 2, MssEngine.CHUNK * 2)
        }

        // 第一块合法返回静音（要攒够前瞻样本）
        for (i in 0 until MssEngine.CHUNK * 2) {
            assertEquals("first block must be silent", 0f, vocalsOut[i], 0f)
            assertEquals("first block must be silent", 0f, accompOut[i], 0f)
        }

        // 从第二块起，输出应当等于**上一块**的输入（延迟一块）
        val lag = MssEngine.CHUNK * 2
        var signal = 0.0
        var noise = 0.0
        for (b in 1 until blocks) {
            for (i in 0 until MssEngine.CHUNK * 2) {
                val outIndex = b * MssEngine.CHUNK * 2 + i
                val expected = input[outIndex - lag]
                val actual = vocalsOut[outIndex] + accompOut[outIndex]
                signal += expected.toDouble() * expected
                val err = (actual - expected).toDouble()
                noise += err * err
            }
        }
        val snrDb = 10 * kotlin.math.log10(signal / maxOf(noise, 1e-30))
        assertTrue("identity reconstruction SNR must be very high, was $snrDb dB", snrDb > 100.0)
    }

    @Test
    fun `reset clears streaming state so a second pass matches the first`() {
        val engine = MssEngine(IdentityModel())
        val frames = MssEngine.CHUNK * 6
        val input = testSignal(frames)

        fun runPass(): FloatArray {
            val out = FloatArray(frames * 2)
            for (b in 0 until 6) {
                val chunk = input.copyOfRange(b * MssEngine.CHUNK * 2, (b + 1) * MssEngine.CHUNK * 2)
                val r = engine.process(chunk, MssEngine.CHUNK)
                System.arraycopy(r[0], 0, out, b * MssEngine.CHUNK * 2, MssEngine.CHUNK * 2)
            }
            return out
        }

        val first = runPass()
        engine.reset()
        val second = runPass()
        for (i in first.indices) {
            assertEquals("reset must reproduce the first pass exactly", first[i], second[i], 1e-6f)
        }
    }

    @Test
    fun `output length always equals input frames regardless of block boundary`() {
        val engine = MssEngine(IdentityModel())
        // 刻意用非块长整数倍，验证"攒不够一块也照样吐满样本"
        val sizes = intArrayOf(100, 4096, 777, 5000, 1, 4608)
        for (n in sizes) {
            val result = engine.process(testSignal(n), n)
            assertEquals("vocals length for $n frames", n * 2, result[0].size)
            assertEquals("accompaniment length for $n frames", n * 2, result[1].size)
            for (v in result[0]) assertTrue("no NaN expected", v.isFinite())
            for (v in result[1]) assertTrue("no NaN expected", v.isFinite())
        }
    }
}
