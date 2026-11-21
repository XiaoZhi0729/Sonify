package yos.music.player.code.livesort

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.code.utils.player.LiveSortAudioAnalyzer
import yos.music.player.code.utils.player.LiveSortFeatureStore
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * LiveSort 智能过渡实时音频处理器（Media3 [BaseAudioProcessor]）。
 *
 * 挂载在 `DefaultAudioSink` 管线中（位于人声分离处理器之后），在智能过渡重叠与释放期间
 * 对主/副两路音频分别施加：
 * 1. **下潜滤波级联（Underwater Tone EQ）**：200Hz LowShelf（+3.2dB·depth）、
 *    1600Hz HighShelf（-18dB·depth）、动态 Butterworth LowPass（18kHz → 720Hz, Q=0.7071）
 *    与干声增益平衡，让淡出/淡入人声柔和下潜的同时避免低频过载。
 * 2. **立体声空间混响（Stereo Reverb）**：180Hz 输入高通低切 + 预延迟/早期反射 +
 *    8 路增益归一化阻尼梳状滤波 + 4 串联全通扩散 + 10.4kHz 输出低通 + 立体声包络峰值限幅。
 * 3. **非过渡期 100% 比特级直通**：当 `depth` 与 `reverb` 均为 0 且平滑归零时，
 *    直接按字节原样拷贝 `ByteBuffer`，零量化损失、零额外延迟。
 * 4. **实时流式首尾特征采样**：对尚无离线尾部分析的在线流歌曲，用只读视图零拷贝
 *    采样前 15s 与滚动 40s 尾部 RMS，确保在线首次播放也能自适应响度与切入点。
 */
@UnstableApi
class LiveSortTransitionProcessor : BaseAudioProcessor() {

    companion object {
        private const val EPS = 1e-5f
        private const val PARAM_SMOOTH_MS = 180f
        private const val SUB_BLOCK_FRAMES = 32
        private const val MAX_REVERB_WET = 0.24f
        private const val REVERB_RT60_SEC = 1.15f
        private const val REVERB_HP_HZ = 280.0
        private const val REVERB_DAMP_HZ = 4500f
        private const val REVERB_OUT_LP_HZ = 6500f
        private const val REVERB_PREDELAY_MS = 6.0
        private const val LIMITER_CEILING = 0.95f
        private const val LIMITER_RELEASE_MS = 120.0
        private const val TAIL_RMS_RING_CAPACITY = 1800 // ~41.8s @ 22050Hz / 512 hop
    }

    @Volatile
    private var targetDepth = 0f

    @Volatile
    private var targetReverb = 0f

    @Volatile
    private var pendingPrime = false

    @Volatile
    private var pendingImmediateReset = false

    @Volatile
    private var boundMediaId: String? = null

    @Volatile
    private var boundDurationSec = 0f

    @Volatile
    private var streamObserveEnabled = false

    private var currentDepth = 0f
    private var currentReverb = 0f
    private var lastCoeffDepth = -1f
    private var limiterEnv = 0f
    private var limiterReleaseCoeff = 0.9998f

    private var formatSupported = false
    private var sampleRate = 44_100
    private var encoding = C.ENCODING_PCM_16BIT
    private var bytesPerSample = 2

    // 预分配浮点工作缓冲（按需扩容，稳态零 GC）
    private var workInterleaved = FloatArray(0)

    // 立体声下潜滤波器（L / R 独立状态）
    private val lowShelfL = BiquadFilter()
    private val lowShelfR = BiquadFilter()
    private val highShelfL = BiquadFilter()
    private val highShelfR = BiquadFilter()
    private val lowPassL = BiquadFilter()
    private val lowPassR = BiquadFilter()
    private var dryGain = 1f

    // 立体声空间混响器（L / R 独立延迟线）
    private val reverbEngine = StereoReverbEngine()

    // 流式首尾特征采样状态
    private val streamObserver = StreamingFeatureObserver()

    fun setEffects(depth: Float, reverb: Float) {
        val safeDepth = if (depth.isFinite()) depth.coerceIn(0f, 1f) else 0f
        val safeReverb = if (reverb.isFinite()) reverb.coerceIn(0f, MAX_REVERB_WET) else 0f
        pendingImmediateReset = false
        targetDepth = safeDepth
        targetReverb = safeReverb
    }

    /**
     * 预置初始过渡音效（在副播放器 `prepare()` 解码首帧前调用，避免前 1~2 秒预缓冲干声漏出）。
     */
    fun primeEffects(depth: Float, reverb: Float) {
        val safeDepth = if (depth.isFinite()) depth.coerceIn(0f, 1f) else 0f
        val safeReverb = if (reverb.isFinite()) reverb.coerceIn(0f, MAX_REVERB_WET) else 0f
        pendingImmediateReset = false
        targetDepth = safeDepth
        targetReverb = safeReverb
        currentDepth = safeDepth
        currentReverb = safeReverb
        lastCoeffDepth = -1f
        pendingPrime = true
    }

    fun resetEffects(immediate: Boolean = false) {
        targetDepth = 0f
        targetReverb = 0f
        pendingPrime = false
        if (immediate) {
            currentDepth = 0f
            currentReverb = 0f
            lastCoeffDepth = -1f
            dryGain = 1f
            pendingImmediateReset = true
            resetFilterStates()
        }
    }

    fun bindTrack(mediaId: String?, durationMs: Long, observeStream: Boolean) {
        val prev = boundMediaId
        boundMediaId = mediaId
        boundDurationSec = if (durationMs > 0L) durationMs / 1000f else 0f
        streamObserveEnabled = observeStream
        if (prev != mediaId) {
            streamObserver.reset(mediaId, boundDurationSec, sampleRate)
        }
    }

    fun setStreamObserveEnabled(enabled: Boolean) {
        streamObserveEnabled = enabled
    }

    override fun onConfigure(inputFormat: AudioFormat): AudioFormat {
        val supported = inputFormat.channelCount == 2 &&
            (inputFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputFormat.encoding == C.ENCODING_PCM_FLOAT)
        formatSupported = supported
        if (!supported) {
            return inputFormat
        }
        sampleRate = inputFormat.sampleRate.coerceIn(8_000, 192_000)
        encoding = inputFormat.encoding
        bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
        limiterReleaseCoeff = exp(-1.0 / (sampleRate.toDouble() * LIMITER_RELEASE_MS / 1000.0))
            .toFloat()
            .coerceIn(0.9f, 0.99999f)
        reverbEngine.configure(sampleRate)
        lastCoeffDepth = -1f
        resetFilterStates()
        streamObserver.configureSampleRate(sampleRate)
        return inputFormat
    }

    override fun isActive(): Boolean = formatSupported

    override fun onFlush() {
        resetFilterStates()
        // seek / 重配后清空实时观察缓存以免把跳转前后拼接在一起
        streamObserver.onSeekOrFlush()
    }

    override fun onReset() {
        targetDepth = 0f
        targetReverb = 0f
        currentDepth = 0f
        currentReverb = 0f
        lastCoeffDepth = -1f
        dryGain = 1f
        limiterEnv = 0f
        workInterleaved = FloatArray(0)
        resetFilterStates()
        reverbEngine.release()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputBytes = inputBuffer.remaining()
        if (inputBytes <= 0) return

        val frameBytes = bytesPerSample * 2
        val frames = inputBytes / frameBytes

        // 只读视图流式采集首尾特征（不改变 inputBuffer.position）
        if (streamObserveEnabled && frames > 0 && boundMediaId != null) {
            streamObserver.observe(inputBuffer, frames, bytesPerSample, encoding)
        }

        if (pendingImmediateReset) {
            pendingImmediateReset = false
            currentDepth = 0f
            currentReverb = 0f
            lastCoeffDepth = -1f
            dryGain = 1f
            resetFilterStates()
        }

        if (pendingPrime) {
            pendingPrime = false
            currentDepth = targetDepth
            currentReverb = targetReverb
            lastCoeffDepth = -1f
        }

        // 非过渡态且混响尾音已自然归零：100% 比特级原样直通
        if (targetDepth <= EPS && targetReverb <= EPS &&
            currentDepth <= EPS && currentReverb <= EPS &&
            !reverbEngine.hasTailEnergy
        ) {
            currentDepth = 0f
            currentReverb = 0f
            val out = replaceOutputBuffer(inputBytes)
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (frames == 0) {
            val out = replaceOutputBuffer(inputBytes)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val sampleCount = frames * 2
        if (workInterleaved.size < sampleCount) {
            workInterleaved = FloatArray(sampleCount)
        }
        readInterleavedFloats(inputBuffer, workInterleaved, frames)
        val tailBytes = inputBytes - frames * frameBytes
        if (tailBytes > 0) {
            inputBuffer.position(inputBuffer.position() + tailBytes)
        }

        processDsp(workInterleaved, frames)

        val out = replaceOutputBuffer(frames * frameBytes)
        writeInterleavedFloats(workInterleaved, frames, out)
        out.flip()
    }

    private fun processDsp(interleaved: FloatArray, frames: Int) {
        val sr = sampleRate.toDouble()
        var frameOffset = 0

        while (frameOffset < frames) {
            val subFrames = min(SUB_BLOCK_FRAMES, frames - frameOffset)
            val subBlockMs = (subFrames * 1000.0) / sr
            val alpha = if (PARAM_SMOOTH_MS <= 0f) {
                1f
            } else {
                (1.0 - exp(-subBlockMs / PARAM_SMOOTH_MS.toDouble())).toFloat().coerceIn(0.001f, 1f)
            }

            val startWet = currentReverb.coerceIn(0f, MAX_REVERB_WET)
            val tDepth = targetDepth
            val tReverb = targetReverb
            currentDepth += (tDepth - currentDepth) * alpha
            currentReverb += (tReverb - currentReverb) * alpha
            if (abs(currentDepth - tDepth) < 1e-4f) currentDepth = tDepth
            if (abs(currentReverb - tReverb) < 1e-4f) currentReverb = tReverb

            val applyTone = currentDepth > EPS
            val endWet = currentReverb.coerceIn(0f, MAX_REVERB_WET)
            val wetActive = startWet > EPS || endWet > EPS
            if (!wetActive && reverbEngine.hasTailEnergy) {
                reverbEngine.resetState()
            }

            if (applyTone && abs(currentDepth - lastCoeffDepth) > 5e-4f) {
                updateToneCoefficients(currentDepth)
                lastCoeffDepth = currentDepth
            }

            val invSub = if (subFrames > 1) 1f / (subFrames - 1) else 0f

            for (s in 0 until subFrames) {
                val f = frameOffset + s
                val idxL = f * 2
                val idxR = idxL + 1
                val rawL = interleaved[idxL]
                val rawR = interleaved[idxR]

                var dryL = rawL
                var dryR = rawR

                if (applyTone) {
                    val shelfL = highShelfL.process(lowShelfL.process(rawL))
                    val shelfR = highShelfR.process(lowShelfR.process(rawR))

                    dryL = lowPassL.process(shelfL) * dryGain
                    dryR = lowPassR.process(shelfR) * dryGain
                }

                val outL: Float
                val outR: Float
                if (wetActive) {
                    val sampleWet = if (subFrames > 1) {
                        startWet + (endWet - startWet) * (s * invSub)
                    } else {
                        endWet
                    }
                    val drySend = 1f - sampleWet * 0.20f
                    val wetSend = sampleWet * 0.42f
                    reverbEngine.processStereo(dryL, dryR)
                    outL = dryL * drySend + reverbEngine.outLeft * wetSend
                    outR = dryR * drySend + reverbEngine.outRight * wetSend
                } else {
                    outL = dryL
                    outR = dryR
                }

                // 立体声联动包络峰值限幅：平滑压限峰值至 0.95 以内，防止叠音和低频削波炸音
                val safeL = if (outL.isFinite()) outL else 0f
                val safeR = if (outR.isFinite()) outR else 0f
                val peak = max(abs(safeL), abs(safeR))
                limiterEnv = if (peak > limiterEnv) {
                    peak
                } else {
                    max(peak, limiterEnv * limiterReleaseCoeff)
                }
                val limGain = if (limiterEnv > LIMITER_CEILING) LIMITER_CEILING / limiterEnv else 1f
                interleaved[idxL] = (safeL * limGain).coerceIn(-0.999f, 0.999f)
                interleaved[idxR] = (safeR * limGain).coerceIn(-0.999f, 0.999f)
            }

            frameOffset += subFrames
        }
    }

    private fun updateToneCoefficients(depth: Float) {
        val d = depth.coerceIn(0f, 1f)
        val sr = sampleRate.toFloat()
        lowShelfL.configureLowShelf(200f, d * 2.0f, sr)
        lowShelfR.copyCoefficientsFrom(lowShelfL)

        highShelfL.configureHighShelf(3200f, -d * 7.5f, sr)
        highShelfR.copyCoefficientsFrom(highShelfL)

        // 对齐 LiveSort 原版线性低通扫频：Butterworth Q=0.7071，保留人声主体通透清晰感
        val maxCutoff = min(18_000f, sr * 0.45f)
        val minCutoff = 2_400f
        val cutoff = (maxCutoff - d * (maxCutoff - minCutoff)).coerceIn(minCutoff, maxCutoff)
        lowPassL.configureLowPass(cutoff, 0.7071f, sr)
        lowPassR.copyCoefficientsFrom(lowPassL)

        dryGain = 1f - d * 0.08f
    }

    private fun resetFilterStates() {
        lowShelfL.resetState()
        lowShelfR.resetState()
        highShelfL.resetState()
        highShelfR.resetState()
        lowPassL.resetState()
        lowPassR.resetState()
        reverbEngine.resetState()
        limiterEnv = 0f
    }

    private fun readInterleavedFloats(src: ByteBuffer, dst: FloatArray, frames: Int) {
        val count = frames * 2
        if (encoding == C.ENCODING_PCM_FLOAT) {
            val fb = src.asFloatBuffer()
            fb.get(dst, 0, count)
            src.position(src.position() + count * 4)
        } else {
            val sb = src.asShortBuffer()
            for (i in 0 until count) {
                dst[i] = sb.get(i) / 32768f
            }
            src.position(src.position() + count * 2)
        }
    }

    private fun writeInterleavedFloats(src: FloatArray, frames: Int, dst: ByteBuffer) {
        val count = frames * 2
        if (encoding == C.ENCODING_PCM_FLOAT) {
            for (i in 0 until count) {
                dst.putFloat(src[i])
            }
        } else {
            for (i in 0 until count) {
                val sample = (src[i] * 32767f).toInt().coerceIn(-32768, 32767)
                dst.putShort(sample.toShort())
            }
        }
    }

    // ----------------------------------------------------------------
    // 二阶 Biquad 滤波器（Direct Form I：参数连续扫频无状态阶跃噪声）
    // ----------------------------------------------------------------

    private class BiquadFilter {
        private var b0 = 1f
        private var b1 = 0f
        private var b2 = 0f
        private var a1 = 0f
        private var a2 = 0f

        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun copyCoefficientsFrom(other: BiquadFilter) {
            b0 = other.b0
            b1 = other.b1
            b2 = other.b2
            a1 = other.a1
            a2 = other.a2
        }

        fun resetState() {
            x1 = 0f
            x2 = 0f
            y1 = 0f
            y2 = 0f
        }

        fun configureLowPass(cutoffHz: Float, q: Float, sampleRateHz: Float) {
            val sr = sampleRateHz.coerceIn(8_000f, 192_000f)
            val fc = cutoffHz.coerceIn(20f, sr * 0.45f)
            val safeQ = q.coerceIn(0.1f, 10f)
            val w0 = 2.0 * PI * fc / sr
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 / (2.0 * safeQ)
            val a0 = 1.0 + alpha
            b0 = (((1.0 - cosW0) / 2.0) / a0).toFloat()
            b1 = ((1.0 - cosW0) / a0).toFloat()
            b2 = b0
            a1 = ((-2.0 * cosW0) / a0).toFloat()
            a2 = ((1.0 - alpha) / a0).toFloat()
        }

        fun configureLowShelf(freqHz: Float, gainDb: Float, sampleRateHz: Float) {
            val sr = sampleRateHz.coerceIn(8_000f, 192_000f)
            val fc = freqHz.coerceIn(20f, sr * 0.45f)
            val amp = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * fc / sr
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            // Slope S = 1.0 -> alpha = sin(w0)/2 * sqrt(2)
            val alpha = sinW0 * 0.5 * sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(amp) * alpha

            val a0 = (amp + 1.0) + (amp - 1.0) * cosW0 + twoSqrtAAlpha
            b0 = ((amp * ((amp + 1.0) - (amp - 1.0) * cosW0 + twoSqrtAAlpha)) / a0).toFloat()
            b1 = ((2.0 * amp * ((amp - 1.0) - (amp + 1.0) * cosW0)) / a0).toFloat()
            b2 = ((amp * ((amp + 1.0) - (amp - 1.0) * cosW0 - twoSqrtAAlpha)) / a0).toFloat()
            a1 = ((-2.0 * ((amp - 1.0) + (amp + 1.0) * cosW0)) / a0).toFloat()
            a2 = (((amp + 1.0) + (amp - 1.0) * cosW0 - twoSqrtAAlpha) / a0).toFloat()
        }

        fun configureHighShelf(freqHz: Float, gainDb: Float, sampleRateHz: Float) {
            val sr = sampleRateHz.coerceIn(8_000f, 192_000f)
            val fc = freqHz.coerceIn(20f, sr * 0.45f)
            val amp = 10.0.pow(gainDb / 40.0)
            val w0 = 2.0 * PI * fc / sr
            val cosW0 = cos(w0)
            val sinW0 = sin(w0)
            val alpha = sinW0 * 0.5 * sqrt(2.0)
            val twoSqrtAAlpha = 2.0 * sqrt(amp) * alpha

            val a0 = (amp + 1.0) - (amp - 1.0) * cosW0 + twoSqrtAAlpha
            b0 = ((amp * ((amp + 1.0) + (amp - 1.0) * cosW0 + twoSqrtAAlpha)) / a0).toFloat()
            b1 = ((-2.0 * amp * ((amp - 1.0) + (amp + 1.0) * cosW0)) / a0).toFloat()
            b2 = ((amp * ((amp + 1.0) + (amp - 1.0) * cosW0 - twoSqrtAAlpha)) / a0).toFloat()
            a1 = ((2.0 * ((amp - 1.0) - (amp + 1.0) * cosW0)) / a0).toFloat()
            a2 = (((amp + 1.0) - (amp - 1.0) * cosW0 - twoSqrtAAlpha) / a0).toFloat()
        }

        fun process(input: Float): Float {
            val out = b0 * input + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1
            x1 = input
            if (!out.isFinite()) {
                x1 = 0f
                x2 = 0f
                y1 = 0f
                y2 = 0f
                return 0f
            }
            y2 = y1
            y1 = out
            return out
        }
    }

    // ----------------------------------------------------------------
    // 轻薄空气扩散混响网络（280Hz HPF + 6ms PreDelay + 2-PreAllPass + 8-Comb + 4-Allpass）
    // ----------------------------------------------------------------

    private class StereoReverbEngine {
        private var sr = 44_100
        private var hpAlpha = 0.96f
        private var hpPrevInL = 0f
        private var hpPrevInR = 0f
        private var hpStateL = 0f
        private var hpStateR = 0f
        private var preDelayL = FloatArray(0)
        private var preDelayR = FloatArray(0)
        private var preDelayIdx = 0
        private var preDiffusersL = emptyArray<AllPass>()
        private var preDiffusersR = emptyArray<AllPass>()
        private var combsL = emptyArray<DampedComb>()
        private var combsR = emptyArray<DampedComb>()
        private var allpassesL = emptyArray<AllPass>()
        private var allpassesR = emptyArray<AllPass>()
        private var outLpAlpha = 0.5f
        private var outLpStateL = 0f
        private var outLpStateR = 0f

        var outLeft = 0f
            private set
        var outRight = 0f
            private set
        var hasTailEnergy = false
            private set

        fun configure(sampleRateHz: Int) {
            sr = sampleRateHz.coerceIn(8_000, 192_000)
            val hpRc = 1.0 / (2.0 * PI * REVERB_HP_HZ)
            val dt = 1.0 / sr.toDouble()
            hpAlpha = (hpRc / (hpRc + dt)).toFloat().coerceIn(0.5f, 0.999f)

            val scale = sr / 44_100.0
            val preApBase = intArrayOf(113, 179)
            val combBase = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
            val apBase = intArrayOf(225, 341, 441, 556)
            val preApSpread = (17 * scale).toInt().coerceAtLeast(1)
            val combSpread = (29 * scale).toInt().coerceAtLeast(1)
            val apSpread = (23 * scale).toInt().coerceAtLeast(1)

            val preDelayLen = ((REVERB_PREDELAY_MS / 1000.0) * sr).toInt().coerceAtLeast(32)
            preDelayL = FloatArray(preDelayLen)
            preDelayR = FloatArray(preDelayLen)
            preDelayIdx = 0

            val dampFc = min(REVERB_DAMP_HZ.toDouble(), sr * 0.22)
            val dampAlpha = onePoleAlpha(dampFc, sr.toDouble())
            outLpAlpha = onePoleAlpha(min(REVERB_OUT_LP_HZ.toDouble(), sr * 0.24), sr.toDouble())

            preDiffusersL = Array(preApBase.size) { i ->
                AllPass((preApBase[i] * scale).toInt().coerceAtLeast(16), 0.55f)
            }
            preDiffusersR = Array(preApBase.size) { i ->
                AllPass((preApBase[i] * scale).toInt().coerceAtLeast(16) + preApSpread, 0.55f)
            }
            combsL = Array(combBase.size) { i ->
                val len = (combBase[i] * scale).toInt().coerceAtLeast(32)
                DampedComb(len, rt60Feedback(len, sr, REVERB_RT60_SEC), dampAlpha)
            }
            combsR = Array(combBase.size) { i ->
                val len = (combBase[i] * scale).toInt().coerceAtLeast(32) + combSpread
                DampedComb(len, rt60Feedback(len, sr, REVERB_RT60_SEC), dampAlpha)
            }
            allpassesL = Array(apBase.size) { i ->
                AllPass((apBase[i] * scale).toInt().coerceAtLeast(16), 0.5f)
            }
            allpassesR = Array(apBase.size) { i ->
                AllPass((apBase[i] * scale).toInt().coerceAtLeast(16) + apSpread, 0.5f)
            }
            resetState()
        }

        fun processStereo(inL: Float, inR: Float) {
            if (combsL.isEmpty() || preDelayL.isEmpty()) {
                outLeft = 0f
                outRight = 0f
                return
            }

            // 保留立体声分离度的同时做适量跨声道融汇
            val srcL = inL * 0.78f + inR * 0.22f
            val srcR = inR * 0.78f + inL * 0.22f

            // 280Hz 一阶高通低切，防止次低频进入混响网络产生浑浊轰鸣
            hpStateL = hpAlpha * (hpStateL + srcL - hpPrevInL)
            hpStateR = hpAlpha * (hpStateR + srcR - hpPrevInR)
            hpPrevInL = srcL
            hpPrevInR = srcR
            if (!hpStateL.isFinite()) hpStateL = 0f
            if (!hpStateR.isFinite()) hpStateR = 0f
            val revInL = hpStateL
            val revInR = hpStateR

            val pLen = preDelayL.size
            val delayedL = preDelayL[preDelayIdx]
            val delayedR = preDelayR[preDelayIdx]
            preDelayL[preDelayIdx] = revInL
            preDelayR[preDelayIdx] = revInR
            preDelayIdx++
            if (preDelayIdx >= pLen) preDelayIdx = 0

            // 前置双全通扩散打散瞬态冲击，消除梳状滤波器产生的空腔/浴室金属共振声
            var diffL = delayedL
            var diffR = delayedR
            for (i in preDiffusersL.indices) {
                diffL = preDiffusersL[i].process(diffL)
                diffR = preDiffusersR[i].process(diffR)
            }

            var sumL = 0f
            var sumR = 0f
            for (i in combsL.indices) {
                sumL += combsL[i].process(diffL)
                sumR += combsR[i].process(diffR)
            }
            // 8 路归一化 comb 轻量求和 (~0.34)，形成轻薄自然的空间雾化空气感
            var apL = sumL * 0.34f
            var apR = sumR * 0.34f
            for (i in allpassesL.indices) {
                apL = allpassesL[i].process(apL)
                apR = allpassesR[i].process(apR)
            }
            outLpStateL += outLpAlpha * (apL - outLpStateL)
            outLpStateR += outLpAlpha * (apR - outLpStateR)
            if (!outLpStateL.isFinite()) outLpStateL = 0f
            if (!outLpStateR.isFinite()) outLpStateR = 0f
            outLeft = outLpStateL
            outRight = outLpStateR
            hasTailEnergy = abs(outLeft) > 5e-5f || abs(outRight) > 5e-5f
        }

        fun resetState() {
            hpPrevInL = 0f
            hpPrevInR = 0f
            hpStateL = 0f
            hpStateR = 0f
            if (preDelayL.isNotEmpty()) preDelayL.fill(0f)
            if (preDelayR.isNotEmpty()) preDelayR.fill(0f)
            preDelayIdx = 0
            preDiffusersL.forEach { it.reset() }
            preDiffusersR.forEach { it.reset() }
            combsL.forEach { it.reset() }
            combsR.forEach { it.reset() }
            allpassesL.forEach { it.reset() }
            allpassesR.forEach { it.reset() }
            outLpStateL = 0f
            outLpStateR = 0f
            outLeft = 0f
            outRight = 0f
            hasTailEnergy = false
        }

        fun release() {
            hpPrevInL = 0f
            hpPrevInR = 0f
            hpStateL = 0f
            hpStateR = 0f
            preDelayL = FloatArray(0)
            preDelayR = FloatArray(0)
            preDelayIdx = 0
            preDiffusersL = emptyArray()
            preDiffusersR = emptyArray()
            combsL = emptyArray()
            combsR = emptyArray()
            allpassesL = emptyArray()
            allpassesR = emptyArray()
            outLpStateL = 0f
            outLpStateR = 0f
            hasTailEnergy = false
        }

        private fun rt60Feedback(delaySamples: Int, sampleRateHz: Int, rt60Sec: Float): Float {
            val delaySec = delaySamples.toDouble() / sampleRateHz.toDouble()
            return 10.0.pow((-3.0 * delaySec) / rt60Sec.toDouble()).toFloat().coerceIn(0.4f, 0.94f)
        }

        private fun onePoleAlpha(cutoffHz: Double, sampleRateHz: Double): Float {
            val dt = 1.0 / sampleRateHz.coerceAtLeast(1.0)
            val rc = 1.0 / (2.0 * PI * cutoffHz.coerceIn(100.0, sampleRateHz * 0.45))
            return (dt / (rc + dt)).toFloat().coerceIn(0.01f, 0.99f)
        }

        private class DampedComb(
            size: Int,
            private val feedback: Float,
            private val dampAlpha: Float,
        ) {
            private val buffer = FloatArray(size)
            private val inputScale = (1f - feedback).coerceAtLeast(0.05f)
            private var index = 0
            private var filterStore = 0f

            fun process(input: Float): Float {
                val output = buffer[index]
                filterStore += dampAlpha * (output - filterStore)
                if (!filterStore.isFinite()) filterStore = 0f
                val next = input * inputScale + filterStore * feedback
                buffer[index] = if (next.isFinite()) next else 0f
                index++
                if (index >= buffer.size) index = 0
                return output
            }

            fun reset() {
                buffer.fill(0f)
                index = 0
                filterStore = 0f
            }
        }

        private class AllPass(
            size: Int,
            private val feedback: Float,
        ) {
            private val buffer = FloatArray(size)
            private var index = 0

            fun process(input: Float): Float {
                val bufOut = buffer[index]
                val out = -input + bufOut
                val next = input + bufOut * feedback
                buffer[index] = if (next.isFinite()) next else 0f
                index++
                if (index >= buffer.size) index = 0
                return if (out.isFinite()) out else 0f
            }

            fun reset() {
                buffer.fill(0f)
                index = 0
            }
        }
    }

    // ----------------------------------------------------------------
    // 流式首尾特征观察器（零拷贝只读视图，为在线首播歌曲补充动态响度与尾段特征）
    // ----------------------------------------------------------------

    private class StreamingFeatureObserver {
        private var currentMediaId: String? = null
        private var currentDurationSec = 0f
        private var inputSr = 44_100
        private var resampleStep = 2.0
        private var resampleAccum = 0.0

        private val targetStartSamples = LiveSortAudioAnalyzer.ANALYSIS_SAMPLE_RATE * 15
        private var startBuffer: FloatArray? = null
        private var startCount = 0
        private var startDone = false

        // 实时帧级 RMS 滚动环（供尾段分析）
        private val hopTarget = 512
        private var hopSqSum = 0.0
        private var hopSamples = 0
        private val tailRmsRing = FloatArray(TAIL_RMS_RING_CAPACITY)
        private var ringWrite = 0
        private var ringCount = 0
        private var hopsSinceTailPublish = 0

        fun configureSampleRate(sr: Int) {
            inputSr = sr.coerceAtLeast(8_000)
            resampleStep = inputSr.toDouble() / LiveSortAudioAnalyzer.ANALYSIS_SAMPLE_RATE.toDouble()
        }

        fun reset(mediaId: String?, durationSec: Float, sr: Int) {
            currentMediaId = mediaId
            currentDurationSec = durationSec
            configureSampleRate(sr)
            resampleAccum = 0.0
            startDone = false
            startCount = 0
            startBuffer = if (mediaId != null &&
                LiveSortFeatureStore.get(mediaId)?.hasTailAnalysis != true
            ) {
                FloatArray(targetStartSamples)
            } else {
                null
            }
            hopSqSum = 0.0
            hopSamples = 0
            ringWrite = 0
            ringCount = 0
            hopsSinceTailPublish = 0
        }

        fun onSeekOrFlush() {
            // Seek 后不再污染开头 15s 统计，只保留滑窗重置
            startBuffer = null
            startDone = true
            hopSqSum = 0.0
            hopSamples = 0
            ringWrite = 0
            ringCount = 0
            hopsSinceTailPublish = 0
        }

        fun observe(src: ByteBuffer, frames: Int, bytesPerSample: Int, encoding: Int) {
            val id = currentMediaId ?: return
            val existing = LiveSortFeatureStore.get(id)
            if (existing?.hasTailAnalysis == true) {
                startBuffer = null
                startDone = true
                return
            }

            val dup = src.duplicate().order(src.order())
            val isFloat = encoding == C.ENCODING_PCM_FLOAT
            val sb = if (!isFloat) dup.asShortBuffer() else null
            val fb = if (isFloat) dup.asFloatBuffer() else null

            for (f in 0 until frames) {
                val base = f * 2
                val mono = if (isFloat) {
                    (fb!!.get(base) + fb.get(base + 1)) * 0.5f
                } else {
                    (sb!!.get(base) + sb.get(base + 1)) / 65536f
                }

                if (resampleAccum <= 0.0) {
                    onDownsampledSample(id, mono)
                    resampleAccum += resampleStep
                }
                resampleAccum -= 1.0
            }
        }

        private fun onDownsampledSample(mediaId: String, mono: Float) {
            if (!startDone) {
                val buf = startBuffer
                if (buf != null && startCount < buf.size) {
                    buf[startCount++] = mono
                    if (startCount >= buf.size) {
                        publishStartFeatures(mediaId, buf, startCount)
                        startBuffer = null
                        startDone = true
                    }
                }
            }

            hopSqSum += mono.toDouble() * mono.toDouble()
            hopSamples++
            if (hopSamples >= hopTarget) {
                val rms = sqrt(hopSqSum / hopTarget).toFloat()
                hopSqSum = 0.0
                hopSamples = 0
                tailRmsRing[ringWrite] = rms
                ringWrite = (ringWrite + 1) % TAIL_RMS_RING_CAPACITY
                ringCount = min(ringCount + 1, TAIL_RMS_RING_CAPACITY)

                hopsSinceTailPublish++
                // 约每 1 秒（43 hop @ 22050Hz）刷新一次曲尾滚动特征
                if (hopsSinceTailPublish >= 43 && ringCount >= 43) {
                    hopsSinceTailPublish = 0
                    publishTailFeatures(mediaId)
                }
            }
        }

        private fun publishStartFeatures(mediaId: String, buf: FloatArray, len: Int) {
            val sr = LiveSortAudioAnalyzer.ANALYSIS_SAMPLE_RATE
            val startBpm = LiveSortAudioAnalyzer.estimateBpmFromSamples(buf, sr, len)
            val startEnergy = LiveSortAudioAnalyzer.computeEnergyFromSamples(buf, len)
            val start10sLen = min(len, sr * 10)
            val start10sEnergy = LiveSortAudioAnalyzer.computeEnergyFromSamples(buf, start10sLen)
            val brightness = LiveSortAudioAnalyzer.computeBrightnessFromSamples(buf, sr, len)
            LiveSortFeatureStore.updateStreamingStartFeatures(
                mediaId = mediaId,
                durationSec = currentDurationSec,
                startBpm = startBpm,
                startEnergy = startEnergy,
                start10sEnergy = start10sEnergy,
                brightness = brightness,
            )
            YosDiagnostics.log(
                "LIVESORT_STREAM_START",
                "id" to mediaId,
                "bpm" to "%.1f".format(startBpm),
                "e10" to "%.4f".format(start10sEnergy),
            )
        }

        private fun publishTailFeatures(mediaId: String) {
            val count = ringCount
            val ordered = FloatArray(count)
            val startIdx = if (count < TAIL_RMS_RING_CAPACITY) 0 else ringWrite
            for (i in 0 until count) {
                ordered[i] = tailRmsRing[(startIdx + i) % TAIL_RMS_RING_CAPACITY]
            }
            val sr = LiveSortAudioAnalyzer.ANALYSIS_SAMPLE_RATE
            val invalidTailSec = LiveSortAudioAnalyzer.estimateInvalidTailSecFromRmsSeries(
                rmsSeries = ordered,
                sampleRate = sr,
                hopSize = hopTarget,
            )
            val end15Frames = min(count, ((15f * sr) / hopTarget).toInt())
            val end10Frames = min(count, ((10f * sr) / hopTarget).toInt())
            val invalidFrames = ((invalidTailSec * sr) / hopTarget).toInt().coerceIn(0, count)

            var sum15 = 0.0
            for (i in count - end15Frames until count) sum15 += ordered[i]
            val endEnergy = (sum15 / max(1, end15Frames)).toFloat()

            var sum10 = 0.0
            for (i in count - end10Frames until count) sum10 += ordered[i]
            val end10Energy = (sum10 / max(1, end10Frames)).toFloat()

            val dynEnd = (count - invalidFrames).coerceAtLeast(1)
            val dynStart = (dynEnd - end10Frames).coerceAtLeast(0)
            var sumDyn = 0.0
            for (i in dynStart until dynEnd) sumDyn += ordered[i]
            val endDynEnergy = (sumDyn / max(1, dynEnd - dynStart)).toFloat()

            val tailWindowFrames = end15Frames
            var peak15 = 0f
            for (i in count - tailWindowFrames until count) {
                if (ordered[i] > peak15) peak15 = ordered[i]
            }
            val activeThresh = max(peak15 * 0.2f, 1e-4f)
            var lastActiveRel = -1
            for (i in 0 until tailWindowFrames) {
                if (ordered[count - tailWindowFrames + i] >= activeThresh) lastActiveRel = i
            }
            val endLenSec = (tailWindowFrames * hopTarget).toFloat() / sr
            val lastActiveSec = if (lastActiveRel >= 0) (lastActiveRel * hopTarget).toFloat() / sr else 0f
            val tailSilenceSec = max(0f, endLenSec - lastActiveSec)
            val endActivityRatio = if (endLenSec > 0f) {
                ((endLenSec - tailSilenceSec) / endLenSec).coerceIn(0f, 1f)
            } else {
                1f
            }

            LiveSortFeatureStore.updateStreamingEndFeatures(
                mediaId = mediaId,
                endBpm = 0f,
                endEnergy = endEnergy,
                end10sEnergy = end10Energy,
                endDynamicEnergy = endDynEnergy,
                tailSilenceSec = tailSilenceSec,
                invalidTailSec = invalidTailSec,
                endActivityRatio = endActivityRatio,
            )
        }
    }
}
