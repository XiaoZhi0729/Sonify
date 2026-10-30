package yos.music.player.data.vocalsep

import kotlin.math.cos
import kotlin.math.sin

/**
 * 实时四轨分离引擎：STFT 前端 + 模型推理 + 加权重叠相加。
 *
 * 模型契约见 [MssModel]。音频前端（实测锁定，不是猜的）：
 * 44100 Hz、2048 点 STFT、hop 512、周期 Hann、只取前 1024 个 bin。
 *
 * 线程约定：**每个实例绑定一份 [MssModel]，不可跨线程共享**——TFLite 的 Interpreter
 * 不是线程安全的，而交叉淡化期间主副两台播放器会同时出声，因此每台播放器各持一份。
 *
 * 算法逻辑与参考实现逐行对齐（已实测 corr 0.999998 / SNR 54.8 dB）。改任何常量、
 * 布局或尾量处理都会退化成噪声，见各处注释。
 */
class MssEngine(private val model: MssModel) {

    companion object {
        const val SR = 44100
        const val NFFT = 2048
        const val HOP = 512
        const val BINS = 1024

        /** 每次推理覆盖 8 帧 × 512 = 4096 样本 ≈ 93 ms。 */
        const val FRAMES = 8
        const val CHUNK = FRAMES * HOP

        /** 8 帧从 -PAD 开始，需要看到 CHUNK+512 处，故每次推理要 512 样本前瞻。 */
        private const val PAD = NFFT / 2
        private const val LOOK = HOP

        /** 每次推理**消费**的样本数（4096 + 512 前瞻）；少于这个数不能出块。 */
        private const val WIN = CHUNK + LOOK

        private const val TAIL = NFFT - HOP
        private const val OLA = PAD + CHUNK + TAIL

        /** 已完成块的环形队列深度：一次调用要连跑两次推理时不至于丢块。 */
        private const val RING = 8
    }

    private val win = FloatArray(NFFT)
    private val cosT = FloatArray(NFFT / 2)
    private val sinT = FloatArray(NFFT / 2)
    private val rev = IntArray(NFFT)

    /** 模型输入频谱（平面布局，见 [MssModel]）。 */
    private val spectrumIn = FloatArray(TfliteMssModel.SPECTRUM_IN_SIZE)

    /** 模型输出频谱（平面布局，见 [MssModel]）。 */
    private val spectrumOut = FloatArray(TfliteMssModel.SPECTRUM_OUT_SIZE)

    /** 28 路流式状态，形状由模型给出。 */
    private val states: Array<FloatArray> = Array(model.stateSizes.size) { i ->
        FloatArray(model.stateSizes[i])
    }

    /** 上一块最后 PAD 个样本（交错立体声），供下一块的居中 STFT 使用。 */
    private val carry = FloatArray(2 * PAD)

    /**
     * OLA 尾量按 `[L ……][R ……]` **两段**存，不是交错。
     * 把整个 2*TAIL 拷进左声道会把右声道尾量折进左声道且偏移错位，
     * 表现为每块前 35 ms 糊/串声（实测 SNR 从 157 dB 掉到 34.9 dB）。
     */
    private val tailV = FloatArray(2 * TAIL)
    private val tailA = FloatArray(2 * TAIL)
    private val tailW = FloatArray(TAIL)

    /** carry + 本块输入，长度 2*(PAD + WIN)。 */
    private val buf = FloatArray(2 * (PAD + WIN))
    private val inQ = FloatArray(2 * (WIN + 4 * CHUNK))
    private var inLen = 0

    private val vocRing = Array(RING) { FloatArray(2 * CHUNK) }
    private val accRing = Array(RING) { FloatArray(2 * CHUNK) }
    private var ringW = 0
    private var ringR = 0
    private var ringN = 0
    private var ringOff = 0

    private val re = FloatArray(NFFT)
    private val im = FloatArray(NFFT)

    private val vL = FloatArray(OLA)
    private val vR = FloatArray(OLA)
    private val aL = FloatArray(OLA)
    private val aR = FloatArray(OLA)
    private val wsum = FloatArray(OLA)

    init {
        for (i in 0 until NFFT) win[i] = (0.5 - 0.5 * cos(2 * Math.PI * i / NFFT)).toFloat()
        for (i in 0 until NFFT / 2) {
            cosT[i] = cos(-2 * Math.PI * i / NFFT).toFloat()
            sinT[i] = sin(-2 * Math.PI * i / NFFT).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(NFFT)
        for (i in 0 until NFFT) rev[i] = Integer.reverse(i) ushr (32 - bits)
    }

    /** 清空全部流式状态。换曲 / seek / 重新配置时调用。 */
    fun reset() {
        for (s in states) java.util.Arrays.fill(s, 0f)
        java.util.Arrays.fill(carry, 0f)
        java.util.Arrays.fill(tailV, 0f)
        java.util.Arrays.fill(tailA, 0f)
        java.util.Arrays.fill(tailW, 0f)
        inLen = 0
        ringW = 0
        ringR = 0
        ringN = 0
        ringOff = 0
    }

    /**
     * 送入 [frames] 帧交错立体声，返回 `{人声, 伴奏}`，各 2*frames 个 float。
     *
     * 输出比输入**晚一块**（约 93 ms）：每次推理需要 512 样本前瞻，因此首次调用合法返回静音。
     * 判据不要拿第 k 块输出比第 k 块输入。
     */
    fun process(interleaved: FloatArray, frames: Int): Array<FloatArray> {
        val n = frames * 2
        // 队列溢出时丢最旧的（正常路径不会触发，推理超时会）
        if (n > inQ.size - inLen) {
            val drop = n - (inQ.size - inLen)
            System.arraycopy(inQ, drop, inQ, 0, inLen - drop)
            inLen -= drop
        }
        System.arraycopy(interleaved, 0, inQ, inLen, n)
        inLen += n

        while (inLen >= 2 * WIN && ringN < RING) {
            runBlock()
            // 只消费 CHUNK，剩下的 LOOK 样本留作下一次的前瞻
            System.arraycopy(inQ, 2 * CHUNK, inQ, 0, inLen - 2 * CHUNK)
            inLen -= 2 * CHUNK
        }

        // 必须输出恰好 frames 帧：块还没攒够时补静音
        val voc = FloatArray(n)
        val acc = FloatArray(n)
        var got = 0
        while (got < n && ringN > 0) {
            val v = vocRing[ringR]
            val a = accRing[ringR]
            val take = minOf(2 * CHUNK - ringOff, n - got)
            System.arraycopy(v, ringOff, voc, got, take)
            System.arraycopy(a, ringOff, acc, got, take)
            ringOff += take
            got += take
            if (ringOff >= 2 * CHUNK) {
                ringOff = 0
                ringR = (ringR + 1) % RING
                ringN--
            }
        }
        return arrayOf(voc, acc)
    }

    /** 对 inQ[0, WIN) 跑一次推理，把 CHUNK 样本写进环形队列。 */
    private fun runBlock() {
        java.util.Arrays.fill(buf, 0f)
        System.arraycopy(carry, 0, buf, 0, carry.size)
        System.arraycopy(inQ, 0, buf, carry.size, WIN * 2)

        // ---- 正向 STFT ----
        // [1,8,4,1024] 是**四个平面**：L_re[1024] / L_im[1024] / R_re[1024] / R_im[1024]。
        // 写成 (re,im) 逐对交错会让每个 1024 长的平面在 bin 512 处被劈开，模型看到的是
        // 乱序频谱、四轨相加退化成噪声（实测 corr -0.02 对 1.00）。这就是"声音碎成一片"。
        java.util.Arrays.fill(spectrumIn, 0f)
        for (t in 0 until FRAMES) {
            for (c in 0 until 2) {
                val start = t * HOP
                for (i in 0 until NFFT) {
                    re[i] = buf[2 * (start + i) + c] * win[i]
                    im[i] = 0f
                }
                fft(re, im, inverse = false)
                val base = t * 4 * BINS + c * 2 * BINS
                for (f in 0 until BINS) {
                    spectrumIn[base + f] = re[f]             // *_re 平面
                    spectrumIn[base + BINS + f] = im[f]      // *_im 平面
                }
            }
        }

        // ---- 推理 ----
        java.util.Arrays.fill(spectrumOut, 0f)
        model.run(spectrumIn, states, spectrumOut)

        // ---- 逆变换 + 重叠相加 ----
        java.util.Arrays.fill(vL, 0f)
        java.util.Arrays.fill(vR, 0f)
        java.util.Arrays.fill(aL, 0f)
        java.util.Arrays.fill(aR, 0f)
        java.util.Arrays.fill(wsum, 0f)
        // 尾量两段分别播种到左右声道，偏移是 PAD 而不是 0
        System.arraycopy(tailV, 0, vL, PAD, TAIL)
        System.arraycopy(tailV, TAIL, vR, PAD, TAIL)
        System.arraycopy(tailA, 0, aL, PAD, TAIL)
        System.arraycopy(tailA, TAIL, aR, PAD, TAIL)
        System.arraycopy(tailW, 0, wsum, PAD, tailW.size)

        for (t in 0 until FRAMES) {
            val start = t * HOP
            for (i in 0 until NFFT) {
                if (start + i < OLA) wsum[start + i] += win[i] * win[i]
            }
            for (c in 0 until 2) {
                val dstV = if (c == 0) vL else vR
                val dstA = if (c == 0) aL else aR
                for (stem in 0 until 2) {
                    java.util.Arrays.fill(re, 0f)
                    java.util.Arrays.fill(im, 0f)
                    for (f in 0 until BINS) {
                        val p = (t * 16 + c * 2) * BINS + f
                        if (stem == 0) {
                            // re 与 im 相隔 BINS 个 float（是平面，不是相邻）
                            re[f] = spectrumOut[p]
                            im[f] = spectrumOut[p + BINS]
                        } else {
                            // 伴奏 = 贝斯 + 鼓 + 其他（轨 1..3）逐 bin 求和
                            var sr = 0f
                            var si = 0f
                            for (s in 1 until 4) {
                                sr += spectrumOut[p + s * 4 * BINS]
                                si += spectrumOut[p + s * 4 * BINS + BINS]
                            }
                            re[f] = sr
                            im[f] = si
                        }
                    }
                    // 共轭对称重建
                    for (f in 1 until BINS) {
                        re[NFFT - f] = re[f]
                        im[NFFT - f] = -im[f]
                    }
                    fft(re, im, inverse = true)
                    for (i in 0 until NFFT) {
                        if (start + i < OLA) {
                            val v = re[i] * win[i]
                            if (stem == 0) dstV[start + i] += v else dstA[start + i] += v
                        }
                    }
                }
            }
        }

        // ---- 取 CHUNK 样本（窗和归一化）----
        val voc = FloatArray(2 * CHUNK)
        val acc = FloatArray(2 * CHUNK)
        for (i in 0 until CHUNK) {
            val p = PAD + i
            val w = maxOf(wsum[p], 1e-8f)
            voc[2 * i] = vL[p] / w
            voc[2 * i + 1] = vR[p] / w
            acc[2 * i] = aL[p] / w
            acc[2 * i + 1] = aR[p] / w
        }

        // ---- 搬运状态：carry 取 buf 中本块最后 PAD 个样本 ----
        System.arraycopy(buf, 2 * CHUNK, carry, 0, carry.size)
        System.arraycopy(vL, PAD + CHUNK, tailV, 0, TAIL)
        System.arraycopy(vR, PAD + CHUNK, tailV, TAIL, TAIL)
        System.arraycopy(aL, PAD + CHUNK, tailA, 0, TAIL)
        System.arraycopy(aR, PAD + CHUNK, tailA, TAIL, TAIL)
        System.arraycopy(wsum, PAD + CHUNK, tailW, 0, TAIL)

        if (ringN < RING) {
            System.arraycopy(voc, 0, vocRing[ringW], 0, 2 * CHUNK)
            System.arraycopy(acc, 0, accRing[ringW], 0, 2 * CHUNK)
            ringW = (ringW + 1) % RING
            ringN++
        }
    }

    /** 原地基 2 FFT；[inverse] 时带 1/N 归一化。 */
    private fun fft(re: FloatArray, im: FloatArray, inverse: Boolean) {
        for (i in 0 until NFFT) {
            val j = rev[i]
            if (j > i) {
                var t = re[i]; re[i] = re[j]; re[j] = t
                t = im[i]; im[i] = im[j]; im[j] = t
            }
        }
        var len = 2
        while (len <= NFFT) {
            val half = len shr 1
            val step = NFFT / len
            var i = 0
            while (i < NFFT) {
                for (k in 0 until half) {
                    val wr = cosT[k * step]
                    val wi = if (inverse) -sinT[k * step] else sinT[k * step]
                    val a = i + k
                    val b = a + half
                    val xr = re[b] * wr - im[b] * wi
                    val xi = re[b] * wi + im[b] * wr
                    re[b] = re[a] - xr
                    im[b] = im[a] - xi
                    re[a] += xr
                    im[a] += xi
                }
                i += len
            }
            len = len shl 1
        }
        if (inverse) {
            val s = 1f / NFFT
            for (i in 0 until NFFT) {
                re[i] *= s
                im[i] *= s
            }
        }
    }
}
