package yos.music.player.code.vocalsep

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.vocalsep.MssEngine
import java.nio.ByteBuffer

/**
 * 把实时人声分离挂进 Media3 音频链的 [AudioProcessor]。
 *
 * 链路位置：解码器 → **本处理器** → 静音跳过 → Sonic(倍速) → AudioTrack。
 * 倍速与静音跳过都排在它之后，不会绕过本处理器。本处理器**不改变输出格式**
 * （采样率/声道/编码全同输入），避免触发 AudioSink 重配置与 AudioTrack 重建。
 *
 * ## 为什么不保证「一次 queueInput 换等量输出」
 * [MssEngine] 只在攒够一整块（CHUNK 帧）时才吐一整块，所以输入是平滑的、输出是**成块**的。
 * 若强行让每次调用 1:1 出同样多帧，缺口只能补静音，结果是每块插一次断点
 * （实测 SNR 从 130+ dB 掉到 −3 dB）。
 *
 * 正确做法是 Media3 的常规形态（`SonicAudioProcessor` 同样如此）：
 * 输入照收、能出多少出多少，出不来就返回空缓冲，让 sink 等下一次。
 * 代价是整条流的最前面会有约 104 ms 的启动填充（引擎要吃满 4608 帧才出第一块），
 * 这就是算法的固有延迟；稳态下仍然 1 进 1 出，时长不会漂。
 *
 * ## 为什么每台播放器一份
 * TFLite 的 `Interpreter` 不是线程安全的，而交叉淡化期间主副两台播放器会**同时出声**。
 * 共享一份会并发调用同一个解释器。因此引擎由 [engineProvider] 注入，
 * 由 [VocalSeparationController] 按播放器身份各发一份。
 *
 * ## 为什么 `isActive` 不是恒 true
 * 只有「立体声 + 16bit/float」才 active。其余格式返回 false，音频链会把本处理器
 * 整体排除，等价零成本直通。**不能抛 UnhandledAudioFormatException**：sink 会把它
 * 包成 ConfigurationException，整首歌直接播不出来。
 *
 * ## 干湿对齐
 * 引擎吐出的那块对应**上一块**输入，所以混音时的干声取 [dryPrev]（上一块的原始内容）。
 * 两者始终是同一段内容的不同版本，混合等价于"原声 ↔ 分离"的交叉淡化，不是回声。
 *
 * ## 占比的含义
 * [setRatio] 收的是**原声含量**：0 = 只剩伴奏，1 = 完全是原声（等价于没开）。
 * 拉满时 wet 恒等于 [dryPrev]，与关闭态逐样本一致，只差一整块（93 ms）的固有延迟，
 * 所以"拉满 = 关闭"在听感上是无缝的。
 */
@UnstableApi
class VocalSeparationProcessor(
    private val engineProvider: () -> MssEngine?,
    private val onUnavailable: (String) -> Unit = {},
) : BaseAudioProcessor() {

    companion object {
        /** 开关/占比淡变时长。太短会爆音，太长会被听成"渐变生效"。 */
        private const val RAMP_MS = 120f

        /** 连续含 NaN 的块数上限，超过即判定引擎异常并退回直通。 */
        private const val MAX_NAN_BLOCKS = 8

        /** 单块推理异常最多跳过多少块，超过则放弃。 */
        private const val MAX_ERROR_BLOCKS = 16

        /** 输出 FIFO 容量（帧）：够缓冲 8 块，正常路径远用不满。 */
        private const val FIFO_FRAMES = 8 * MssEngine.CHUNK

        /** 块级证据最多记几条（拖动进度条会反复 flush，不设上限会刷屏）。 */
        private const val MAX_BLOCK_LOGS = 6
    }

    /** 块级 RMS（只看能量，不做判定）：日志里用来说明"这一块到底有没有音频"。 */
    private fun rms(a: FloatArray): Float {
        var s = 0.0
        for (v in a) s += (v.toDouble() * v)
        return kotlin.math.sqrt(s / a.size).toFloat()
    }

    /** 编码名：日志里写数字没人愿意去查 C 常量表。 */
    private fun encodingName(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_8BIT -> "u8"
        C.ENCODING_PCM_16BIT -> "s16"
        C.ENCODING_PCM_24BIT -> "s24"
        C.ENCODING_PCM_32BIT -> "s32"
        C.ENCODING_PCM_FLOAT -> "f32"
        else -> "?$encoding"
    }

    // ---- 控制层写入（volatile：写点在主线程，读点在播放线程）----
    @Volatile
    private var targetEnabled = false

    /** 目标原声含量 0..1（0 = 纯伴奏，1 = 原声 = 等同关闭）。 */
    @Volatile
    private var targetRatio = 1f

    /** 生效中的占比，向 [targetRatio] 逐块逼近。 */
    private var rampedRatio = 1f

    /** 生效中的开关度 0..1，向 [targetEnabled] 逐块逼近。 */
    private var rampedMix = 0f

    /** 引擎已判定不可用（模型缺失 / 连续 NaN / 连续异常）。true 后永久直通。 */
    @Volatile
    private var failed = false

    /** 当前输入格式是否被支持；false 时 [isActive] 返回 false（等价直通）。 */
    @Volatile
    private var formatSupported = false

    private var engine: MssEngine? = null
    private var engineResolved = false

    private var sampleRate = 0
    private var encoding = 0
    private var bytesPerSample = 2

    /** 44.1k 域的输入累积缓冲（交错 float），攒够一整块才送引擎。 */
    private var pending = FloatArray(0)
    private var pendingFrames = 0

    /**
     * 上一整块的干声（44.1k 域）。初值全零：开启后第一块引擎只吃不出、返回静音，
     * 此时若干声分量取本块内容，会把静音换成原声、随后再放一遍分离结果。
     */
    private var dryPrev = FloatArray(0)

    /** 引擎输出（44.1k 域）的 FIFO：引擎成块吐，sink 按自己的节奏取。 */
    private val fifo = FloatArray(FIFO_FRAMES * 2)
    private var fifoHead = 0
    private var fifoFrames = 0

    private var toEngine: HermiteResampler? = null
    private var fromEngine: HermiteResampler? = null

    /** 输出侧重采样器跨块保留的尾帧数：每次只能推 FIFO 里已有的内容。 */
    private var nanBlocks = 0
    private var errorBlocks = 0

    /** 首块分离结果是否已记录（只记一次：每块都记会把日志刷满且无新信息）。 */
    private var blocksLogged = false

    /** 已记过的块级证据条数上限：拖动进度条会频繁 flush，不设上限日志会被刷屏。 */
    private var blockLogCount = 0

    /** [onConfigure] 调用次数（sink 层回查用，见 [configureCount]）。 */
    @Volatile
    private var configureCountValue = 0

    /** 是否已记录"音频真的流进本处理器"（只记一次）。 */
    private var inputSeen = false

    fun setEnabled(enabled: Boolean) {
        targetEnabled = enabled
        // 开关是用户唯一的动作，必须落盘：日志里"点了开但没 VSEP_CONF"这类结论全靠它定位。
        YosDiagnostics.log("VSEP_SET", "on" to enabled, "r" to targetRatio, "usable" to !failed)
    }

    fun setRatio(ratio: Float) {
        targetRatio = ratio.coerceIn(0f, 1f)
    }

    fun currentEnabled(): Boolean = targetEnabled

    fun currentRatio(): Float = targetRatio

    /** 引擎是否仍然可用（模型存在且没被判故障）。 */
    fun isUsable(): Boolean = !failed

    /**
     * [onConfigure] 被调用过几次。
     *
     * 供 sink 层回查"处理器到底有没有进音频管线"：处理器自己不执行时无法自救，
     * 只有从管线入口往回看才能区分"配置了但没数据"与"压根没配置"。
     */
    fun configureCount(): Int = configureCountValue

    /**
     * 换曲 / seek 后清引擎状态。
     *
     * 只清引擎与缓冲，**不动开关**：seek 不该把用户的选择关掉。
     */
    fun resetEngineState() {
        engine?.reset()
        toEngine?.reset()
        fromEngine?.reset()
        clearBuffers()
        nanBlocks = 0
        errorBlocks = 0
    }

    // ---------------------------------------------------------------- 配置

    override fun onConfigure(inputFormat: AudioFormat): AudioFormat {
        configureCountValue++
        val supported = inputFormat.channelCount == 2 &&
            (inputFormat.encoding == C.ENCODING_PCM_16BIT ||
                inputFormat.encoding == C.ENCODING_PCM_FLOAT)
        formatSupported = supported
        // 本方法只在处理器真的被装进音频管线时才会被调用。因此这条日志同时回答了两个问题：
        // "sink 有没有采纳处理器"（无此行 = 管线里根本没有它）与"进来的格式是什么"。
        YosDiagnostics.log(
            "VSEP_CONF",
            "ch" to inputFormat.channelCount,
            "sr" to inputFormat.sampleRate,
            "enc" to encodingName(inputFormat.encoding),
            "ok" to supported,
            "enabled" to targetEnabled
        )
        if (!supported) {
            // 返回输入格式即可；isActive()==false 时 BaseAudioProcessor.configure 对外
            // 返回 NOT_SET，音频链不会采纳它
            return inputFormat
        }

        sampleRate = inputFormat.sampleRate
        encoding = inputFormat.encoding
        bytesPerSample = if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2

        // 累积缓冲固定按引擎一块（44.1k 域的 CHUNK 帧）：引擎只认整块
        pending = FloatArray(MssEngine.CHUNK * 2)
        dryPrev = FloatArray(MssEngine.CHUNK * 2)
        clearBuffers()
        toEngine = if (sampleRate == MssEngine.SR) null else HermiteResampler(sampleRate, MssEngine.SR)
        fromEngine = if (sampleRate == MssEngine.SR) null else HermiteResampler(MssEngine.SR, sampleRate)
        // 输出格式 = 输入格式：不改变任何一项，避免 sink 重配置
        return inputFormat
    }

    override fun isActive(): Boolean = formatSupported

    override fun onFlush() {
        engine?.reset()
        toEngine?.reset()
        fromEngine?.reset()
        clearBuffers()
        nanBlocks = 0
        errorBlocks = 0
        // 首块证据按"每段音频"重来：seek/换曲后需要重新确认这一段也真的跑起来了
        blocksLogged = false
        // 开关态保持：flush 只发生在 seek/重配，不代表用户关掉了功能
    }

    override fun onReset() {
        engine?.reset()
        engine = null
        engineResolved = false
        toEngine?.reset()
        fromEngine?.reset()
        pending = FloatArray(0)
        dryPrev = FloatArray(0)
        clearBuffers()
        nanBlocks = 0
        errorBlocks = 0
        rampedMix = 0f
        rampedRatio = targetRatio
    }

    private fun clearBuffers() {
        pendingFrames = 0
        if (dryPrev.isNotEmpty()) java.util.Arrays.fill(dryPrev, 0f)
        fifoHead = 0
        fifoFrames = 0
    }

    // ---------------------------------------------------------------- 处理

    override fun queueInput(inputBuffer: ByteBuffer) {
        val inputBytes = inputBuffer.remaining()
        if (inputBytes <= 0) return
        // 一次性证据：音频数据真的流过本处理器。没有这行就说明处理器压根没在管线里
        // （例如 sink 走了 float 分支把自定义处理器整个丢掉），与"跑起来但没效果"是两回事。
        if (!inputSeen) {
            inputSeen = true
            YosDiagnostics.log(
                "VSEP_IN",
                "bytes" to inputBytes,
                "sr" to sampleRate,
                "enc" to encodingName(encoding),
                "enabled" to targetEnabled,
                "mix" to rampedMix
            )
        }
        val frameBytes = bytesPerSample * 2
        val frames = inputBytes / frameBytes
        val tailBytes = inputBytes - frames * frameBytes

        // 关闭且已淡出完毕：**按字节**直通。
        // 不能走"解码成 float 再写回"的路子：int16 → float → int16 有量化损失
        // （实测 1 会变成 0），关闭态必须比特级无改动。
        if (rampedMix <= 0f && !targetEnabled) {
            clearBuffers()
            val out = replaceOutputBuffer(inputBytes)
            out.put(inputBuffer)
            out.flip()
            return
        }

        if (frames == 0) {
            // 不足一帧的残字节：原样搬（解码器输出总是帧对齐，正常不会走到）
            val out = replaceOutputBuffer(inputBytes)
            out.put(inputBuffer)
            out.flip()
            return
        }

        val source = FloatArray(frames * 2)
        readFloats(inputBuffer, source, frames)
        inputBuffer.position(inputBuffer.position() + frames * frameBytes)
        if (tailBytes > 0) inputBuffer.position(inputBuffer.position() + tailBytes)

        separateIntoFifo(source, frames)
        emitFifo()
    }

    /**
     * 把输入送进引擎，结果堆进 [fifo]；本方法**不产出**输出缓冲。
     *
     * 尾部不足一块的帧留在 [pending] 里等下一次调用，不丢样本。
     */
    private fun separateIntoFifo(source: FloatArray, frames: Int) {
        val eng = resolveEngine()
        if (eng == null || failed) {
            // 引擎不可用：退回直通。这里直接当"原声块"推进 FIFO，
            // 保证流不断（rampedMix 归零后下一次调用就走纯直通分支）
            rampedMix = 0f
            pushFifo(source, frames)
            return
        }

        // ---- 输入侧重采样到 44.1k ----
        val atEngineRate = toEngine?.push(source, frames) ?: source
        val engineFrames = atEngineRate.size / 2

        // ---- 攒整块 → 逐块推理 ----
        var srcOff = 0
        while (srcOff < engineFrames) {
            val space = MssEngine.CHUNK - pendingFrames
            val take = minOf(space, engineFrames - srcOff)
            System.arraycopy(
                atEngineRate, srcOff * 2,
                pending, pendingFrames * 2,
                take * 2
            )
            pendingFrames += take
            srcOff += take
            if (pendingFrames == MssEngine.CHUNK) {
                runEngineBlock(eng)
                pendingFrames = 0
            }
        }
    }

    /**
     * 用引擎跑一整块（[MssEngine.CHUNK] 帧，44.1k），混好干声后推进 FIFO。
     *
     * 混音在这里做而不是在别处：只有这里才知道"这一块对应上一块干声"，
     * 干湿对齐是逐块的属性。ramp 也按块步进（块长恒定，步长才有确定含义）。
     */
    private fun runEngineBlock(eng: MssEngine) {
        val block = pending
        stepRamps(MssEngine.CHUNK)

        val stems = try {
            eng.process(block, MssEngine.CHUNK)
        } catch (t: Throwable) {
            // 第一次失败必须留证据：早先这一路径只会累加计数，16 块之后才判故障，
            // 用户只看到"没反应"，事后无从知道 TFLite 在这台机器上抛的是什么。
            if (errorBlocks == 0) {
                YosDiagnostics.log(
                    "VSEP_ERR", "what" to "infer",
                    "type" to t.javaClass.simpleName,
                    "msg" to runCatching { t.message }.getOrNull()
                )
            }
            if (++errorBlocks >= MAX_ERROR_BLOCKS) fail("inference_failed")
            // 失败块直通：把本块干声推进 FIFO，保证内容连续
            pushFifo(block, MssEngine.CHUNK)
            System.arraycopy(block, 0, dryPrev, 0, dryPrev.size)
            return
        }
        errorBlocks = 0

        val vocals = stems[0]
        val accomp = stems[1]

        // 只查有限性，**不做能量比**：引擎输出比输入晚一块，逐块 rms(out)/rms(in)
        // 在真实音乐上必然误报（参考实现实测报过 2.07 并误杀播放）。
        for (i in vocals.indices) {
            if (!vocals[i].isFinite() || !accomp[i].isFinite()) {
                if (++nanBlocks >= MAX_NAN_BLOCKS) fail("non_finite_output")
                pushFifo(block, MssEngine.CHUNK)
                System.arraycopy(block, 0, dryPrev, 0, dryPrev.size)
                return
            }
        }
        nanBlocks = 0

        // 分离结果是不是"真的分了"：只有在这里能量比才有确定含义——两条轨是同一块
        // 输入的产物。若 accomp 与 vocals 的能量和≈干声、且各占一半，说明模型在这台
        // 机器上跑出了近似"恒等拆分"（TFLite 指令集差异的典型表现），那时"开关能点、
        // 听感没变化"就有了实证，不必再猜。只在首块记一次，不污染日志。
        if (!blocksLogged && blockLogCount < MAX_BLOCK_LOGS) {
            blocksLogged = true
            blockLogCount++
            val dRms = rms(block)
            val vRms = rms(vocals)
            val aRms = rms(accomp)
            YosDiagnostics.log(
                "VSEP_BLOCK",
                "dry" to dRms,
                "voc" to vRms,
                "acc" to aRms,
                "dry2" to (dRms * dRms),
                "wet2" to (vRms * vRms + aRms * aRms),
                "r" to rampedRatio,
                "mix" to rampedMix
            )
        }

        // 不能用等功率（sin/cos）：分离出的伴奏与原声**同源高度相关**
        // （dry ≈ vocals + accomp），线性交叉淡化恰好等价于 accomp + r·vocals，
        // 等功率会在中段过冲约 +3 dB。
        val r = rampedRatio
        val mix = rampedMix
        val inv = 1f - mix

        val mixed = FloatArray(MssEngine.CHUNK * 2)
        for (i in mixed.indices) {
            val wet = accomp[i] * (1f - r) + dryPrev[i] * r
            mixed[i] = wet * mix + dryPrev[i] * inv
        }
        pushFifo(mixed, MssEngine.CHUNK)

        // 本块干声留给下一块对齐
        System.arraycopy(block, 0, dryPrev, 0, dryPrev.size)
    }

    /** 把 FIFO 里现有的内容（若有）作为本次输出；没有则留空缓冲让 sink 等下一次。 */
    private fun emitFifo() {
        if (fifoFrames <= 0) return
        val outFrames = if (fromEngine == null) {
            fifoFrames
        } else {
            // 重采样后帧数会变，这里按"本批 FIFO 内容"整批转换，长度以重采样结果为准
            fifoFrames
        }
        val raw = FloatArray(outFrames * 2)
        var written = 0
        while (written < outFrames && fifoFrames > 0) {
            val dst = written * 2
            val src = fifoHead * 2
            raw[dst] = fifo[src]
            raw[dst + 1] = fifo[src + 1]
            fifoHead = (fifoHead + 1) % FIFO_FRAMES
            fifoFrames--
            written++
        }

        val outSamples = fromEngine?.push(raw, outFrames) ?: raw
        val outFramesFinal = outSamples.size / 2
        if (outFramesFinal <= 0) return

        val out = replaceOutputBuffer(outFramesFinal * frameBytes())
        writeFloats(outSamples, outFramesFinal, out)
        out.flip()
    }

    private fun frameBytes(): Int = bytesPerSample * 2

    /** 把 [frames] 帧追加进输出 FIFO（满了就丢最旧的，正常路径不会触发）。 */
    private fun pushFifo(src: FloatArray, frames: Int) {
        if (frames <= 0) return
        val n = minOf(frames, FIFO_FRAMES)
        if (fifoFrames + n > FIFO_FRAMES) {
            val drop = fifoFrames + n - FIFO_FRAMES
            fifoHead = (fifoHead + drop) % FIFO_FRAMES
            fifoFrames -= drop
        }
        var tail = (fifoHead + fifoFrames) % FIFO_FRAMES
        for (i in 0 until n) {
            val srcIndex = i * 2
            val dst = tail * 2
            fifo[dst] = src[srcIndex]
            fifo[dst + 1] = src[srcIndex + 1]
            tail = (tail + 1) % FIFO_FRAMES
        }
        fifoFrames += n
    }

    /** 把 ramp 往目标推进一步，步长按块时长折算，使整段淡变约 [RAMP_MS]。 */
    private fun stepRamps(frames: Int) {
        val ms = frames * 1000f / sampleRate
        val step = if (ms <= 0f) 1f else (ms / RAMP_MS).coerceIn(0f, 1f)

        val targetMix = if (targetEnabled) 1f else 0f
        rampedMix = when {
            rampedMix < targetMix -> minOf(targetMix, rampedMix + step)
            rampedMix > targetMix -> maxOf(targetMix, rampedMix - step)
            else -> targetMix
        }

        val r = targetRatio
        rampedRatio = when {
            rampedRatio < r -> minOf(r, rampedRatio + step)
            rampedRatio > r -> maxOf(r, rampedRatio - step)
            else -> r
        }
    }

    /** 懒取引擎：首次需要时才建；模型缺失则永久标记不可用。 */
    private fun resolveEngine(): MssEngine? {
        if (engineResolved) return engine
        engineResolved = true
        // 这是整条链路最容易"静默落空"的一步：模型缺失/TFLite 加载失败时只会退成直通，
        // 用户看到的现象就是"点了开关但声音没变"。必须把失败原因原样记下来。
        engine = runCatching { engineProvider() }
            .onFailure { t ->
                YosDiagnostics.log(
                    "VSEP_ERR", "what" to "engine",
                    "type" to t.javaClass.simpleName,
                    "msg" to runCatching { t.message }.getOrNull()
                )
            }
            .getOrNull()
        YosDiagnostics.log(
            "VSEP_ENG",
            "ok" to (engine != null),
            "cpus" to Runtime.getRuntime().availableProcessors(),
            "threads" to (Runtime.getRuntime().availableProcessors() / 2).coerceIn(2, 6)
        )
        if (engine == null) {
            failed = true
            targetEnabled = false
            onUnavailable("model_unavailable")
        }
        return engine
    }

    private fun fail(reason: String) {
        if (failed) return
        failed = true
        targetEnabled = false
        YosDiagnostics.log("VSEP_FAIL", "why" to reason)
        onUnavailable(reason)
    }

    // ---------------------------------------------------------------- PCM IO

    private fun readFloats(buffer: ByteBuffer, dst: FloatArray, frames: Int) {
        val dup = buffer.duplicate().order(buffer.order())
        if (encoding == C.ENCODING_PCM_FLOAT) {
            dup.asFloatBuffer().get(dst, 0, frames * 2)
        } else {
            val shorts = ShortArray(frames * 2)
            dup.asShortBuffer().get(shorts, 0, frames * 2)
            for (i in shorts.indices) dst[i] = shorts[i] / 32768f
        }
    }

    /**
     * 写回 [buffer]。必须**手动推进外层 ByteBuffer 的 position**：
     * `asShortBuffer()` / `asFloatBuffer()` 返回的是视图，往视图里 put 不会移动外层
     * ByteBuffer 的位置；不推进的话随后 flip() 会把 limit 归零、输出变空。
     */
    private fun writeFloats(src: FloatArray, frames: Int, buffer: ByteBuffer) {
        val count = minOf(src.size, frames * 2)
        if (encoding == C.ENCODING_PCM_FLOAT) {
            buffer.asFloatBuffer().put(src, 0, count)
            buffer.position(buffer.position() + count * 4)
        } else {
            val shorts = ShortArray(count)
            for (i in 0 until count) {
                val v = src[i]
                shorts[i] = when {
                    v <= -1f -> Short.MIN_VALUE
                    v >= 1f -> Short.MAX_VALUE
                    else -> (v * 32767f).toInt().toShort()
                }
            }
            buffer.asShortBuffer().put(shorts, 0, count)
            buffer.position(buffer.position() + count * 2)
        }
    }

    /**
     * 4 点 Hermite 三次插值重采样器，状态跨块保留（[prev] 3 帧 + 小数相位 [frac]）。
     *
     * 用在两处：把非 44.1k 输入送进引擎的 44.1k 域，以及把结果送回来。
     * 不能换成逐块线性插值——相位在块边界断裂会产生周期性咔哒。
     */
    private class HermiteResampler(srcRate: Int, dstRate: Int) {
        /** 每个输出帧在源上走过的帧数。 */
        private val step = srcRate.toDouble() / dstRate
        private val prev = Array(3) { FloatArray(2) }
        private var frac = 0.0
        private var buf = FloatArray(0)

        fun reset() {
            for (p in prev) {
                p[0] = 0f
                p[1] = 0f
            }
            frac = 0.0
        }

        fun push(src: FloatArray, frames: Int): FloatArray {
            if (frames <= 0) return FloatArray(0)
            val need = (Math.ceil(frames / step).toInt() + 2) * 2
            if (buf.size < need) buf = FloatArray(need)
            var fill = 0
            var p = frac
            while (p < frames) {
                val i = p.toInt()
                val t = (p - i).toFloat()
                for (c in 0 until 2) {
                    buf[fill++] = cubic(
                        at(src, frames, c, i - 1),
                        at(src, frames, c, i),
                        at(src, frames, c, i + 1),
                        at(src, frames, c, i + 2),
                        t
                    )
                }
                p += step
            }
            frac = p - frames
            for (k in 0 until 3) {
                val idx = frames - 3 + k
                if (idx >= 0) {
                    prev[k][0] = src[idx * 2]
                    prev[k][1] = src[idx * 2 + 1]
                }
            }
            return buf.copyOf(fill)
        }

        private fun at(src: FloatArray, frames: Int, c: Int, idx: Int): Float {
            if (idx < 0) {
                val k = idx + 3
                return if (k < 0) 0f else prev[k][c]
            }
            val i = if (idx >= frames) frames - 1 else idx
            return src[i * 2 + c]
        }

        private fun cubic(y0: Float, y1: Float, y2: Float, y3: Float, t: Float): Float =
            0.5f * ((2f * y1) + (-y0 + y2) * t +
                (2f * y0 - 5f * y1 + 4f * y2 - y3) * t * t +
                (-y0 + 3f * y1 - 3f * y2 + y3) * t * t * t)
    }
}
