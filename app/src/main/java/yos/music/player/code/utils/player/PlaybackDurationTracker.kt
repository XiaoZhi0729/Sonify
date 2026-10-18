package yos.music.player.code.utils.player

/**
 * CSCC 播放时长累计器（`/user/listen/report` 的 `duration` 语义，对照 md3Music
 * playback_duration_tracker.dart 移植）。
 *
 * 契约：
 * - 只累计**真正出声的墙钟时间**：暂停期间不累计；
 * - **拖动进度不产生额外时长**：按墙钟计时而非 position 差值，seek 天然不额加，
 *   [onSeek] 先结算再起表，避免把 seek 执行耗时算进去；
 * - 一首歌的「一段播放」= start 到 end；切歌视作前一首 end。
 *
 * 纯逻辑（无 Android 依赖），时间源可注入，便于单测。
 */
class PlaybackDurationTracker(private val nowMillis: () -> Long = System::currentTimeMillis) {

    private var songId: String? = null
    private var mixsongid: String? = null
    private var accumulatedMs = 0L

    /** 最近一次进入「播放中」的时间戳（null = 暂停中/未开始）。 */
    private var runningSinceMs: Long? = null

    /** 当前是否有正在计时的播放段。 */
    val isTracking: Boolean get() = songId != null

    /**
     * 开始一段播放。
     * @param playing 该曲此刻是否真的在出声。缓冲/暂停切歌时必须传 false，
     *   否则会把静默等待算进 duration（真机首测 duration 远超实际听歌即此因）。
     */
    fun start(songId: String, mixsongid: String, playing: Boolean = true) {
        this.songId = songId
        this.mixsongid = mixsongid
        accumulatedMs = 0L
        runningSinceMs = if (playing) nowMillis() else null
    }

    /** 播放/暂停状态变化；幂等。 */
    fun setPlaying(playing: Boolean) {
        if (!isTracking) return
        if (playing) {
            if (runningSinceMs == null) runningSinceMs = nowMillis()
        } else {
            val since = runningSinceMs ?: return
            accumulatedMs += nowMillis() - since
            runningSinceMs = null
        }
    }

    /** 拖动进度：先结算已跑的一段再重新起表（不额加 seek 耗时）。 */
    fun onSeek() {
        if (!isTracking) return
        val since = runningSinceMs ?: return
        val now = nowMillis()
        accumulatedMs += now - since
        runningSinceMs = now
    }

    /** 结束当前段并返回结果；无进行中的段时返回 null。 */
    fun end(reason: PlaybackEndReason = PlaybackEndReason.COMPLETED): PlaybackSegment? =
        take(reason)

    /** 结账当前段（供「先取段、后另起一段」的切歌路径使用，语义同 [end]）。 */
    fun snapshot(reason: PlaybackEndReason = PlaybackEndReason.SWITCHED): PlaybackSegment? =
        take(reason)

    private fun take(reason: PlaybackEndReason): PlaybackSegment? {
        val song = songId ?: return null
        val mix = mixsongid ?: return null
        runningSinceMs?.let { accumulatedMs += nowMillis() - it }
        runningSinceMs = null
        val segment = PlaybackSegment(song, mix, accumulatedMs, reason)
        songId = null
        mixsongid = null
        accumulatedMs = 0L
        return segment
    }

    /** 主动放弃当前段（不上报），例如关闭开关或退出登录。 */
    fun discard() {
        songId = null
        mixsongid = null
        accumulatedMs = 0L
        runningSinceMs = null
    }
}

/** 播放段结束原因 → CSCC `state` 取值（对照 md3Music PlaybackEndReason）。 */
enum class PlaybackEndReason(val state: String) {
    COMPLETED("完整播放"),
    SWITCHED("切换下一首"),
    STOPPED("手动停止")
}

/** 一次播放段的结果。[durationMs] 为已扣除暂停与拖动的实际播放毫秒数。 */
data class PlaybackSegment(
    val songId: String,
    val mixsongid: String,
    val durationMs: Long,
    val reason: PlaybackEndReason
) {
    fun reachesThreshold(minMs: Long): Boolean = durationMs >= minMs
}
