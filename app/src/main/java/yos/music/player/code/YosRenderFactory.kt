package yos.music.player.code

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.code.vocalsep.VocalSeparationController
import yos.music.player.code.vocalsep.VocalSeparationProcessor

/**
 * 渲染器工厂：把自定义 AudioProcessor（人声分离）装进 AudioSink，
 * 并按“音频解码器”档位决定是否/如何插入 FFmpeg 软解。
 *
 * ## 一处必须做对
 * 覆写 [buildAudioSink] 用 `setAudioProcessors` 把处理器装进 sink。
 *
 * ## FFmpeg 软解不由我们手build
 * [DefaultRenderersFactory] 在 ON / PREFER 两档会自己反射构造 FfmpegAudioRenderer
 * 并传入本 sink，所以处理器照样生效；详见 [buildAudioRenderers] 的注释。
 *
 * ## 为什么每个工厂各持一份处理器
 * 交叉淡化期间主副两台播放器同时出声，而 TFLite 的 Interpreter 不是线程安全的。
 * 每台播放器由 [playerKey] 标识，各自注册到 [VocalSeparationController]，
 * 拿到独立的处理器与独立的引擎。
 */
@OptIn(UnstableApi::class)
class YosRenderFactory(
    context: Context,
    private val playerKey: String = VocalSeparationController.PLAYER_PRIMARY,
) : DefaultRenderersFactory(context) {

    /**
     * 本工厂（即本播放器）专用的处理器。懒建：只有 sink 真的被装配时才需要，
     * 而引擎本身要等首次启用才加载。
     */
    private val vocalSeparationProcessor: VocalSeparationProcessor by lazy {
        VocalSeparationProcessor(
            engineProvider = VocalSeparationController.engineProviderFor(playerKey),
            onUnavailable = { reason ->
                yos.music.player.data.objects.VocalSeparationState.onEngineFailed(reason)
            }
        ).also { VocalSeparationController.attach(playerKey, it) }
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean,
    ): AudioSink {
        // ## 为什么必须无条件关掉 float 输出（2026-11-01 实测确证，不是推测）
        //
        // media3 1.4.0 的 DefaultAudioSink.configure 里，自定义 AudioProcessor 只在**整数**分支
        // 被加入管线：
        //     if (shouldUseFloatOutput(pcmEncoding)) { 只加 toFloatPcmAvailableAudioProcessors }
        //     else { toInt16 + channelMapping + trimming + audioProcessorChain.getAudioProcessors() }
        // 于是只要 sink 走 float 分支，人声分离处理器就被**整条丢弃且不报错**：用户点开开关、
        // 日志里连一次 configure 都没有（实测 8 Elite Gen 5 那台机器的报告即为此形态）。
        //
        // 这个开关在本工程收益也是零：float 分支要求输入是 24/32-bit 或 float PCM
        // （Util.isEncodingHighResolutionPcm），而解码输出日常都是 16-bit，分支根本不触发——
        // 用户看到的现象就是"开了也没用"。一旦真遇到高解析 PCM 音源，它立刻变成"人声分离失效"。
        // 收益为零、触发即破坏，所以这里不采纳该设置（UI 侧同步停用，见 ExoPlayerSettings）。
        //
        // **不要**把它改回 `enableFloatOutput`：复位后本机看似正常（16-bit 不进 float 分支），
        // 只有反馈者那种会解出高解析 PCM 的机器才会坏，属于最难查的一类回归。
        val base = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            // 处理器排在静音跳过与 Sonic(倍速) 之前：倍速不会绕过分离
            .setAudioProcessors(arrayOf(vocalSeparationProcessor))
            .build()
        YosDiagnostics.log(
            "VSEP_SINK",
            "pkey" to playerKey,
            // float_req=上游请求（设置里开着就是 true），forced=实际采纳值
            "float_req" to enableFloatOutput,
            "forced" to false,
            "procs" to 1
        )
        return ProbingAudioSink(base, vocalSeparationProcessor, playerKey)
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        // ## 这里刻意**不**自己 new FfmpegAudioRenderer（2026-11-01 字节码核对）
        //
        // 旧实现在 super 之前 add 了一个手build的 FfmpegAudioRenderer，本意是"让软解优先"，
        // 但它使 "音频解码器" 这个三档设置整条失效：
        //   MappingTrackSelector.findRenderer 只在 maxSupport **严格大于** 当前最优时换渲染器
        //   （平局保留先出现的），而 FFmpeg 与 MediaCodec 对 MP3/AAC/FLAC 等都报同样的支持
        //   等级。手build 那个永远排第 0 位，于是 Auto/FFmpeg 两档只多出一个重复实例、
        //   彼此无差别，System 档也只是少了 super 追加的那个——硬件解码从未被选中过。
        //
        // super 在 ON / PREFER 两档本来就会用反射构造 FfmpegAudioRenderer 并**传入本 sink**
        // （DefaultRenderersFactory 里走的正是 (Handler, AudioRendererEventListener, AudioSink)
        // 这个构造器），所以人声分离处理器照样在管线上；只有 OFF（System 档）才完全不装软解。
        // 交给它之后三档各有明确含义（映射见 MediaController.renderFactory），本处不再插手：
        //   Auto   → ON     → 硬件在最前，FFmpeg 兜底
        //   FFmpeg → PREFER → FFmpeg 在最前，软解优先（排查硬件解码怪声/无声时用）
        //   System → OFF    → 只有硬件，完全不装软解
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        // 记下"谁拿着哪个 sink"：若实际出声的渲染器没拿我们这个 sink，
        // 处理器永远收不到 configure——这条能当场把"两套音频通路"的问题暴露出来。
        YosDiagnostics.log(
            "VSEP_REND",
            "pkey" to playerKey,
            "sink" to System.identityHashCode(audioSink),
            "mode" to extensionRendererMode,
            "renderers" to out.joinToString(",") { it.javaClass.simpleName }
        )
    }

    /**
     * 探测用 sink：把 ExoPlayer 真正交给音频设备的**输入格式**记下来，
     * 并在 `configure` 返回后回查处理器有没有被管线 configure。
     *
     * 这是唯一能回答"处理器到底在不在管线里"的位置——处理器自己的 `onConfigure`
     * 不执行时，它自己无法报告任何东西。
     */
    @UnstableApi
    private class ProbingAudioSink(
        delegate: AudioSink,
        private val processor: VocalSeparationProcessor,
        private val playerKey: String,
    ) : ForwardingAudioSink(delegate) {

        private var logged = false
        private var bufferLogged = false

        override fun configure(
            inputFormat: Format,
            specifiedBufferSize: Int,
            outputChannels: IntArray?
        ) {
            val before = processor.configureCount()
            super.configure(inputFormat, specifiedBufferSize, outputChannels)
            val after = processor.configureCount()
            YosDiagnostics.log(
                "VSEP_FMT",
                "pkey" to playerKey,
                "mime" to inputFormat.sampleMimeType,
                "pcm" to pcmName(inputFormat.pcmEncoding),
                "ch" to inputFormat.channelCount,
                "sr" to inputFormat.sampleRate,
                // confd=1 表示管线真的叫过处理器；confd=0 表示它被排除在管线外
                "confd" to (after - before),
                "total_confd" to after
            )
            logged = true
        }

        override fun handleBuffer(
            buffer: java.nio.ByteBuffer,
            presentationTimeUs: Long,
            encodedAccessUnitCount: Int
        ): Boolean {
            if (!bufferLogged) {
                bufferLogged = true
                YosDiagnostics.log(
                    "VSEP_BUF",
                    "pkey" to playerKey,
                    "bytes" to buffer.remaining(),
                    "confd" to processor.configureCount()
                )
            }
            return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)
        }

        /** 位深名字：日志里写数字没人愿意去查 C 常量表。 */
        private fun pcmName(encoding: Int): String = when (encoding) {
            C.ENCODING_PCM_8BIT -> "u8"
            C.ENCODING_PCM_16BIT -> "s16"
            C.ENCODING_PCM_24BIT -> "s24"
            C.ENCODING_PCM_32BIT -> "s32"
            C.ENCODING_PCM_FLOAT -> "f32"
            C.ENCODING_INVALID -> "invalid"
            else -> "?$encoding"
        }
    }
}
