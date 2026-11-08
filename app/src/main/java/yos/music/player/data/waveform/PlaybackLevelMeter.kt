package yos.music.player.data.waveform

import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.min

/**
 * 播放链路低音律动分析器。
 *
 * ## 输出端逐式照搬上游
 * 输出端的全部环节（基线跟随、4 帧加权斜坡、3 帧中值确认、双时间常数释放）取自
 * Pear Wall 的 `classic/src/lib.rs:186-243`，常量一字未改 —— 那部分是这套观感的来源。
 *
 * ## 输入端做了简化
 * 上游用 `android.media.audiofx.Visualizer(0)` 抓全局混音：8-bit、20Hz、1024 样本、
 * **没有时间戳**。为了在这么差的数据上稳住，它额外挂了 5 频带 FFT、波形/FFT 双分支融合、
 * `sharp_attack`、`harmonic_confidence`、`rejection` 一整套抗噪环节（`lib.rs` 三分之二的代码）。
 *
 * Sonify 能直接拿到交给 AudioTrack 前的完整 16-bit PCM，噪声水平完全不同，所以这里只保留
 * 上游的「波形分支」那一路：二阶低通 160Hz → 逐 hop RMS → dB。5 频带 FFT 整个不要。
 *
 * ## 线程
 * 全部在调用方线程（ExoPlayer 播放线程）同步执行，**不新开线程**。每个 hop 只有
 * 1024 次乘加，相对 23ms 的音频预算可忽略。因此这里所有缓冲都预分配，
 * [feed] 内不产生任何新对象 —— 在播放线程触发 GC 才是真正的代价。
 */
class PlaybackLevelMeter {

    /** 每个 hop 的帧数。1024 帧 @44.1kHz ≈ 23ms，与上游的采集窗口同量级。 */
    private val hopFrames = HOP_FRAMES

    /** 当前 hop 已累计的「双声道样本」个数（单声道也按一份算）。 */
    private var hopFilled = 0

    /** 当前 hop 的平方和（累加两个声道）。 */
    private var hopSquareSum = 0.0

    private var sampleRate = 0
    private var channelCount = 0
    private var encoding = -1

    // ---- 低通 ----
    private val lowPass = BiquadLowPass()
    private var lowPassReady = false

    // ---- 输出端状态（全部对应 lib.rs 的字段）----
    private var baselineDb = SILENCE_DB
    private var baselineReady = false
    private var previousHopNanos = 0L
    private var sawFirstHop = false

    /** 4 帧加权斜坡：写入指针。 */
    private var recentWrite = 0
    private val recent = FloatArray(4)

    /** 带包络的瞬态强度（上游 transient_response 的角色）。 */
    private var transient = 0f

    /** 3 帧中值确认历史。 */
    private val responseHistory = FloatArray(3)
    private var historyWrite = 0
    private var historyCount = 0

    private var target = 0f
    private var power = 0f

    /** 最近一次算出的 0..1 律动值。 */
    val value: Float get() = power

    /**
     * 配置输入格式。格式变化时重置全部状态 —— 切换音源（不同采样率/声道数）
     * 时若沿用旧滤波器系数，低频响应会偏。
     */
    fun configure(sampleRateHz: Int, channelCount: Int, pcmEncoding: Int) {
        if (sampleRateHz == sampleRate && channelCount == this.channelCount &&
            pcmEncoding == encoding
        ) {
            return
        }
        sampleRate = sampleRateHz
        this.channelCount = channelCount.coerceAtLeast(1)
        encoding = pcmEncoding
        hopFilled = 0
        hopSquareSum = 0.0
        lowPass.configure(LOW_CUTOFF_HZ, sampleRate.toFloat())
        lowPassReady = true
        baselineDb = SILENCE_DB
        baselineReady = false
        previousHopNanos = 0L
        sawFirstHop = false
        recentWrite = 0
        recent.fill(0f)
        responseHistory.fill(0f)
        historyWrite = 0
        historyCount = 0
        transient = 0f
        target = 0f
        power = 0f
    }

    /** 输入格式是否可被本分析器处理。 */
    fun supports(pcmEncoding: Int): Boolean =
        pcmEncoding == ENCODING_PCM_16BIT || pcmEncoding == ENCODING_PCM_FLOAT

    /**
     * 喂入一块 PCM。
     *
     * [samples] 是已经归一化到 [-1, 1] 的**交错**样本（L,R,L,R…）。调用方负责解码，
     * 这里只管分析与算法状态。
     *
     * 返回 true 表示这一批里至少产出了一个新 hop（即 [value] 有更新）。
     */
    fun feed(samples: FloatArray, count: Int, nowNanos: Long): Boolean {
        if (!lowPassReady || count <= 0) return false
        var produced = false
        var i = 0
        while (i < count) {
            // 一个「样本组」（frame）里的所有声道累加到一起：能量上等价于单声道混音
            var frameSum = 0f
            for (c in 0 until channelCount) {
                if (i >= count) break
                frameSum += lowPass.process(samples[i])
                i++
            }
            hopSquareSum += (frameSum * frameSum).toDouble()
            hopFilled++
            if (hopFilled >= hopFrames) {
                emitHop(nowNanos)
                hopFilled = 0
                hopSquareSum = 0.0
                produced = true
            }
        }
        return produced
    }

    /** 一个 hop 结束：RMS → dB → 输出端全链路。 */
    private fun emitHop(nowNanos: Long) {
        val channels = channelCount.coerceAtLeast(1)
        val frames = hopFilled.coerceAtLeast(1)
        // 归一化口径：能量取每帧每声道均值，换采样率/声道数时不漂
        val meanSquare = hopSquareSum / (frames.toDouble() * channels)
        val db = (10.0 * log10(meanSquare.coerceAtLeast(1e-12))).toFloat()

        val elapsed = elapsedSeconds(nowNanos)
        previousHopNanos = nowNanos
        sawFirstHop = true

        // 首帧只建立基线，不产出（对应 lib.rs:178-184）
        if (!baselineReady) {
            baselineDb = (db - BASELINE_HEADROOM_DB).coerceAtLeast(SILENCE_DB)
            baselineReady = true
            return
        }

        val rise = (db - baselineDb).coerceAtLeast(0f)
        baselineDb = followBaseline(baselineDb, db, BASELINE_ATTACK_S, BASELINE_RELEASE_S, elapsed)

        val level = smoothRange(db, LEVEL_FLOOR_DB, LEVEL_CEIL_DB)
        val transientTarget = smoothRange(rise, TRANSIENT_FLOOR_DB, TRANSIENT_CEIL_DB)
        // ★ 必须给瞬态加包络。
        //
        // `transientTarget` 是「单个 hop（23ms）内的 dB 跃升」推导出来的**阶跃**量：
        // 一个鼓点就能让它在一跳之内从 0 跳到 1。上游没有这个问题——它的
        // `transient_response` 来自逐采样包络（攻击 0.006s / 释放 0.045s）再乘
        // 全波形 max，天然是连续的。简化输入把这层平滑丢了，如果直接拿去用，
        // 画面会在 23ms 内"啪"地顶到位、再用几百毫秒慢慢落回去——一快一慢的不对称
        // 就是观感上"闪了一下"的来源。这里补一阶包络把上升拉软。
        val transientTau = if (transientTarget > transient) {
            TRANSIENT_ATTACK_S
        } else {
            TRANSIENT_RELEASE_S
        }
        transient += (transientTarget - transient) * (1f - decay(elapsed, transientTau))
        val unprocessed = level * (TRANSIENT_BASE + (1f - TRANSIENT_BASE) * transient)
        // 上游的 immediate 依赖 sharp_attack（FFT 分支）；简化输入后改用瞬态强度直判。
        val immediate = transient >= IMMEDIATE_TRANSIENT

        // 3 帧中值确认 —— 逐式照搬 lib.rs:226-243
        responseHistory[historyWrite] = unprocessed
        historyWrite = (historyWrite + 1) % 3
        historyCount = min(historyCount + 1, 3)
        val confirmed = if (immediate) {
            unprocessed
        } else when (historyCount) {
            1 -> 0f
            2 -> min(responseHistory[0], responseHistory[1])
            else -> {
                val a = responseHistory[0]
                val b = responseHistory[1]
                val c = responseHistory[2]
                a + b + c - min(min(a, b), c) - maxOf(a, maxOf(b, c))
            }
        }

        // 4 帧加权斜坡 —— lib.rs:213-219，权重 [0.1,0.2,0.3,0.4]
        recent[recentWrite] = confirmed
        recentWrite = (recentWrite + 1) % 4
        var weighted = 0f
        for (k in 0 until 4) {
            weighted += recent[(recentWrite + k) % 4] * RAMP_WEIGHTS[k]
        }

        target = maxOf(weighted, target * decay(elapsed, TARGET_RELEASE_S))
        power += (target - power) * (1f - decay(elapsed, POWER_FOLLOW_S))
        // 上游 lib.rs:221-223：`power.max(transient_response * level)`。
        // ★ 这个 `* level` 不能漏 —— level 是 -50..-18dB 映射来的电平门限，
        //   漏掉就等于让瞬态未经缩放直接顶满输出，轻音也会把视觉推到头。
        power = maxOf(power, transient * level).coerceIn(0f, 1f)
    }

    /** 相邻 hop 的时间差，钳到上游的 [0.001, 0.25] 秒。 */
    private fun elapsedSeconds(nowNanos: Long): Float {
        if (!sawFirstHop || previousHopNanos == 0L) return DEFAULT_REPORT_SECONDS
        val delta = nowNanos - previousHopNanos
        if (delta <= 0L) return DEFAULT_REPORT_SECONDS
        return (delta / 1_000_000_000.0).toFloat()
            .coerceIn(MIN_REPORT_SECONDS, MAX_REPORT_SECONDS)
    }

    companion object {
        /**
         * 每个 hop 的帧数。2048 帧 ≈ 46ms @44.1kHz。
         *
         * 原先是 1024（23ms），但渲染侧是 30fps（33ms）——分析比画面快，采样率不对齐时
         * 同一拍会被读到一高一低两个值，观感就是"抖"。46ms 比一帧略长，配合下面的
         * 包络与斜坡，输出在相邻两帧之间保持单调，不会被读成闪。
         */
        const val HOP_FRAMES = 2048

        /** 低音低通截止。上游分 25 / 190 / 760Hz 三个再相减；简化输入后单级 160Hz 覆盖同一区间。 */
        const val LOW_CUTOFF_HZ = 160f

        const val ENCODING_PCM_16BIT = 2
        const val ENCODING_PCM_FLOAT = 4

        private const val SILENCE_DB = -72f
        private const val DEFAULT_REPORT_SECONDS = 0.05f
        private const val MIN_REPORT_SECONDS = 0.001f
        private const val MAX_REPORT_SECONDS = 0.25f

        /** 首帧基线取当前值往下留多少 dB 余量（对应 lib.rs:179 的 7.0）。 */
        private const val BASELINE_HEADROOM_DB = 7f

        /** 基线跟随：上升慢、下降快（lib.rs:203-205）。 */
        private const val BASELINE_ATTACK_S = 1.1f
        private const val BASELINE_RELEASE_S = 0.16f

        /** 电平门限（lib.rs:207-211）。 */
        private const val LEVEL_FLOOR_DB = -50f
        private const val LEVEL_CEIL_DB = -18f
        private const val TRANSIENT_FLOOR_DB = 1.2f
        private const val TRANSIENT_CEIL_DB = 7f

        /** 静音时保留的底噪系数（lib.rs:209 的 0.1）。 */
        private const val TRANSIENT_BASE = 0.1f

        /** 直通中值滤波所需瞬态强度（上游 0.32，配合 sharp_attack 的 0.72）。 */
        private const val IMMEDIATE_TRANSIENT = 0.32f

        /**
         * 瞬态包络的时间常数。
         *
         * 上游是逐采样包络（0.006s / 0.045s），作用在 1024 点波形上。这里是 hop 级
         * （23ms 一格），按同样的观感换算：攻击不能比一个 hop 还短，否则又变回阶跃；
         * 释放给到 120ms，让鼓点的"鼓起来"有形状，而不是立即瘪回去。
         */
        private const val TRANSIENT_ATTACK_S = 0.09f
        private const val TRANSIENT_RELEASE_S = 0.12f

        /** 4 帧加权斜坡，最旧→最新（lib.rs:215）。 */
        private val RAMP_WEIGHTS = floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)

        private const val TARGET_RELEASE_S = 1.0f
        private const val POWER_FOLLOW_S = 0.07f

        /** `exp(-elapsed / tau)`，与上游 `decay()` 同义。 */
        private fun decay(elapsed: Float, timeConstant: Float): Float =
            exp(-elapsed / timeConstant)

        /** 一阶基线跟随，结果不低于静音门限（lib.rs:261-264）。 */
        private fun followBaseline(
            current: Float,
            target: Float,
            attack: Float,
            release: Float,
            elapsed: Float,
        ): Float {
            val timeConstant = if (target > current) attack else release
            return (current + (target - current) * (1f - decay(elapsed, timeConstant)))
                .coerceAtLeast(SILENCE_DB)
        }

        /** smoothstep（lib.rs:290-293）。 */
        private fun smoothRange(value: Float, floor: Float, ceiling: Float): Float {
            val x = ((value - floor) / (ceiling - floor)).coerceIn(0f, 1f)
            return x * x * (3f - 2f * x)
        }
    }
}

/**
 * RBJ cookbook 二阶 Butterworth 低通。
 *
 * 系数公式与上游 `biquad 0.5.0` 的 `Coefficients::from_params(Type::LowPass, …)`
 * 逐式一致；Kotlin 侧同款实现见 Pear Wall 的 `GlobalAudioMeter.kt:435-446`。
 * 这里额外保留上游的 cutoff 钳制（`min(fs*0.45).max(1.0)`），
 * 避免异常采样率把系数算到不稳定区。
 */
private class BiquadLowPass {
    private var b0 = 1f
    private var b1 = 0f
    private var b2 = 0f
    private var a1 = 0f
    private var a2 = 0f

    // Transposed Direct Form II 状态
    private var z1 = 0f
    private var z2 = 0f

    fun configure(cutoffHz: Float, sampleRateHz: Float) {
        val safeRate = if (sampleRateHz.isFinite()) {
            sampleRateHz.coerceIn(2_000f, 192_000f)
        } else {
            48_000f
        }
        val safeCutoff = cutoffHz.coerceAtMost(safeRate * 0.45f).coerceAtLeast(1f)
        val w0 = 2.0 * Math.PI * safeCutoff / safeRate
        val cosW0 = kotlin.math.cos(w0).toFloat()
        val alpha = (kotlin.math.sin(w0) / (2.0 * Q_BUTTERWORTH)).toFloat()
        val a0 = 1f + alpha
        b0 = ((1f - cosW0) / 2f) / a0
        b1 = (1f - cosW0) / a0
        b2 = b0
        a1 = (-2f * cosW0) / a0
        a2 = (1f - alpha) / a0
        z1 = 0f
        z2 = 0f
    }

    fun process(input: Float): Float {
        val out = b0 * input + z1
        z1 = b1 * input - a1 * out + z2
        z2 = b2 * input - a2 * out
        // 数值异常时清状态，避免 NaN 永久粘住整条链路
        if (!out.isFinite()) {
            z1 = 0f
            z2 = 0f
            return 0f
        }
        return out
    }

    private companion object {
        const val Q_BUTTERWORTH = 0.7071067811865476
    }
}
