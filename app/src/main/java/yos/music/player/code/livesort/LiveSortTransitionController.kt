package yos.music.player.code.livesort

import androidx.media3.common.util.UnstableApi
import yos.music.player.code.vocalsep.VocalSeparationController
import java.util.concurrent.ConcurrentHashMap

/**
 * LiveSort 智能过渡处理器运行时中枢：按播放器身份（[PLAYER_PRIMARY] / [PLAYER_SECONDARY]）
 * 管理主、副两路独立的 [LiveSortTransitionProcessor]，避免并发线程污染滤波器与混响状态。
 */
@UnstableApi
object LiveSortTransitionController {
    const val PLAYER_PRIMARY = VocalSeparationController.PLAYER_PRIMARY
    const val PLAYER_SECONDARY = VocalSeparationController.PLAYER_SECONDARY

    private val processors = ConcurrentHashMap<String, LiveSortTransitionProcessor>()

    @Volatile
    private var lastBoundPrimaryMediaId: String? = null

    @Volatile
    private var lastBoundPrimaryDurationMs: Long = 0L

    @Volatile
    private var lastStreamObserveEnabled: Boolean = false

    fun attach(playerKey: String, processor: LiveSortTransitionProcessor) {
        processor.resetEffects(immediate = true)
        if (playerKey == PLAYER_PRIMARY) {
            processor.bindTrack(
                lastBoundPrimaryMediaId,
                lastBoundPrimaryDurationMs,
                lastStreamObserveEnabled,
            )
        }
        processors[playerKey] = processor
    }

    fun updateOutgoingEffects(depth: Float, reverb: Float) {
        processors[PLAYER_PRIMARY]?.setEffects(depth, reverb)
    }

    fun updateIncomingEffects(depth: Float, reverb: Float) {
        processors[PLAYER_SECONDARY]?.setEffects(depth, reverb)
    }

    /**
     * 在副播放器 `prepare()` 解码第一帧之前直接预置初始音色与空间混响，防止首段预缓冲干声漏出。
     */
    fun primeIncomingEffects(depth: Float, reverb: Float) {
        processors[PLAYER_SECONDARY]?.primeEffects(depth, reverb)
    }

    /**
     * 扶正后继续对已升格为主播放器的音频流释放剩余下潜滤波与混响尾段。
     */
    fun updatePromotedEffects(depth: Float, reverb: Float) {
        processors[PLAYER_PRIMARY]?.setEffects(depth, reverb)
    }

    fun resetAllEffects(immediate: Boolean = false) {
        processors.values.forEach { it.resetEffects(immediate = immediate) }
    }

    fun promoteSecondaryToPrimary() {
        val sec = processors.remove(PLAYER_SECONDARY)
        processors.remove(PLAYER_PRIMARY)?.resetEffects(immediate = true)
        if (sec != null) {
            processors[PLAYER_PRIMARY] = sec
        }
    }

    fun bindTrack(
        playerKey: String,
        mediaId: String?,
        durationMs: Long,
        observeStream: Boolean,
    ) {
        if (playerKey == PLAYER_PRIMARY) {
            lastBoundPrimaryMediaId = mediaId
            lastBoundPrimaryDurationMs = durationMs
            lastStreamObserveEnabled = observeStream
        }
        processors[playerKey]?.bindTrack(mediaId, durationMs, observeStream)
    }

    fun setStreamObserveEnabled(enabled: Boolean) {
        lastStreamObserveEnabled = enabled
        processors.values.forEach { it.setStreamObserveEnabled(enabled) }
    }

    fun release() {
        processors.values.forEach { it.resetEffects(immediate = true) }
        processors.clear()
        lastBoundPrimaryMediaId = null
        lastBoundPrimaryDurationMs = 0L
        lastStreamObserveEnabled = false
    }
}
