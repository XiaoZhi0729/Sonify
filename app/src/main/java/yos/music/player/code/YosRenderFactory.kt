package yos.music.player.code

import android.content.Context
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import yos.music.player.code.vocalsep.VocalSeparationController
import yos.music.player.code.vocalsep.VocalSeparationProcessor

/**
 * 渲染器工厂：在默认链路前面插一路 FFmpeg 软解，并把自定义 AudioProcessor
 * （人声分离）装进 AudioSink。
 *
 * ## 两处必须一起改（只改一处等于没接上）
 * 1. 覆写 [buildAudioSink]：用 `setAudioProcessors` 把处理器装进 sink。
 * 2. 给 [FfmpegAudioRenderer] **传同一个 sink**。Codec 设为 "Auto" 时
 *    [DefaultRenderersFactory] 用 `EXTENSION_RENDERER_MODE_PREFER`，FFmpeg 会被优先选中；
 *    无参构造的 `FfmpegAudioRenderer()` 会**自建一个 sink**，绕过上面那处，
 *    结果是"接上了但听不到效果"——这是本功能最容易踩的坑。
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
    ): AudioSink = DefaultAudioSink.Builder(context)
        .setEnableFloatOutput(enableFloatOutput)
        .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
        // 处理器排在静音跳过与 Sonic(倍速) 之前：倍速不会绕过分离
        .setAudioProcessors(arrayOf(vocalSeparationProcessor))
        .build()

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
        // 必须传 audioSink：无参构造会自建 sink，把处理器整条绕过（见类注释）
        out.add(FfmpegAudioRenderer(eventHandler, eventListener, audioSink))
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
    }
}
