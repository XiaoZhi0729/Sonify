package yos.music.player.code

import androidx.annotation.OptIn
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [audioCodecSelectorFor] 的单元测试：验证 `Auto` 模式下仅将 `audio/flac` 分流给 FFmpeg，
 * 普通格式原样透传，且非 `Auto` 模式或 FFmpeg 不支持 FLAC 时不拦截系统解码器选择。
 */
@OptIn(UnstableApi::class)
class YosRenderFactoryCodecSelectorTest {

    private class RecordingSelector : MediaCodecSelector {
        data class Call(
            val mimeType: String,
            val requiresSecureDecoder: Boolean,
            val requiresTunnelingDecoder: Boolean,
        )

        val calls = mutableListOf<Call>()

        override fun getDecoderInfos(
            mimeType: String,
            requiresSecureDecoder: Boolean,
            requiresTunnelingDecoder: Boolean,
        ): MutableList<MediaCodecInfo> {
            calls.add(Call(mimeType, requiresSecureDecoder, requiresTunnelingDecoder))
            return mutableListOf()
        }
    }

    @Test
    fun `auto mode blocks system mediacodec for flac and delegates standard formats`() {
        val upstream = RecordingSelector()
        val selector = audioCodecSelectorFor(
            extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            upstream = upstream,
            ffmpegSupportsFlac = { true },
        )

        assertTrue(selector.getDecoderInfos(MimeTypes.AUDIO_FLAC, false, false).isEmpty())
        assertTrue(selector.getDecoderInfos("AUDIO/FLAC", true, true).isEmpty())
        assertEquals(0, upstream.calls.size)

        selector.getDecoderInfos(MimeTypes.AUDIO_MPEG, true, false)
        selector.getDecoderInfos(MimeTypes.AUDIO_AAC, false, true)
        assertEquals(
            listOf(
                RecordingSelector.Call(MimeTypes.AUDIO_MPEG, true, false),
                RecordingSelector.Call(MimeTypes.AUDIO_AAC, false, true),
            ),
            upstream.calls,
        )
    }

    @Test
    fun `auto mode falls back to upstream when ffmpeg does not support flac`() {
        val upstream = RecordingSelector()
        val selector = audioCodecSelectorFor(
            extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON,
            upstream = upstream,
            ffmpegSupportsFlac = { false },
        )

        assertSame(upstream, selector)
        selector.getDecoderInfos(MimeTypes.AUDIO_FLAC, false, false)
        assertEquals(
            listOf(RecordingSelector.Call(MimeTypes.AUDIO_FLAC, false, false)),
            upstream.calls,
        )
    }

    @Test
    fun `system mode returns upstream untouched without querying ffmpeg`() {
        val upstream = RecordingSelector()
        val selector = audioCodecSelectorFor(
            extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF,
            upstream = upstream,
            ffmpegSupportsFlac = {
                throw AssertionError("ffmpegSupportsFlac should not be queried in System mode")
            },
        )

        assertSame(upstream, selector)
    }

    @Test
    fun `ffmpeg prefer mode returns upstream untouched without querying ffmpeg`() {
        val upstream = RecordingSelector()
        val selector = audioCodecSelectorFor(
            extensionRendererMode = DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER,
            upstream = upstream,
            ffmpegSupportsFlac = {
                throw AssertionError("ffmpegSupportsFlac should not be queried in FFmpeg mode")
            },
        )

        assertSame(upstream, selector)
    }
}
