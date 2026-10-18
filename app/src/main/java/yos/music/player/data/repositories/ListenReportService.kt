package yos.music.player.data.repositories

import android.os.Build
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.code.utils.player.PlaybackDurationTracker
import yos.music.player.code.utils.player.PlaybackEndReason
import yos.music.player.code.utils.player.PlaybackSegment
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.native.KugouApiService

/**
 * CSCC 真实播放事件上报（`/user/listen/report`，对照 md3Music listen_report_service.dart 移植）。
 *
 * 与 [ListeningGradeTracker] 的分工：
 * - 本服务：**真实播放事件**（`start`/`end`），由播放器回调在播放状态边沿驱动，
 *   携带扣除暂停/拖动后的实际播放毫秒数 → 对应官方「真实播放统计」记账。
 * - [ListeningGradeTracker]：仍负责 30s 心跳式 `d_sec`/`diff_sec` 差量上报。
 *
 * 设计要点（对照 md3Music，勿改）：
 * 1. **不重试**：上游超时也可能已记账，重试会重复上报，故只发一次；
 * 2. start/end 配对由 [PlaybackDurationTracker] 保证（切歌时先补发前一首 end）；
 * 3. 过短播放（< [MIN_REPORTABLE_MS]）只发 start 不发 end，避免噪声；
 * 4. 开关复用 `UploadListeningDuration`，每次上报前重读 → 设置页切换即时生效；
 * 5. end 出口校验本段所属账号，防收听途中切号把时长记到新账号。
 */
object ListenReportService {

    private const val TAG = "CSCC"
    private const val MIN_REPORTABLE_MS = 15_000L

    val tracker = PlaybackDurationTracker()

    /** 当前计时段所属账号（onSongStarted 时记下，end 出口校验）。 */
    private var segmentUserid: String? = null

    private val scope = CoroutineScope(Dispatchers.IO)

    /** 设备参数：同一次播放的 start/end 必须一致（Rust 会话缓存 key 含机型，漂移会重建会话）。 */
    private val deviceModel: String by lazy {
        val m = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        m.ifEmpty { "Android" }
    }
    private val systemVersion: String by lazy { Build.VERSION.RELEASE.orEmpty() }

    /** 歌曲开始播放（切歌时调用）。playing 须如实传当前是否真出声。 */
    fun onSongStarted(songId: String, mixsongid: String, playing: Boolean) {
        if (!enabled()) return
        if (!KugouApiService.isLoggedIn()) return
        // 顺序：先结账并上报上一段 end，再开启新段并上报 start（不可颠倒，
        // 否则期间到达的 onSongEnded 会拿到错误的段）。
        tracker.snapshot(PlaybackEndReason.SWITCHED)?.let { sendEnd(it) }
        tracker.start(songId = songId, mixsongid = mixsongid, playing = playing)
        segmentUserid = KugouApiService.userid
        sendStart(mixsongid)
    }

    /** 播放/暂停状态变化。 */
    fun onPlayingChanged(playing: Boolean) {
        tracker.setPlaying(playing)
    }

    /** 用户拖动进度条。 */
    fun onSeek() {
        tracker.onSeek()
    }

    /** 歌曲结束/被停止：上报 end。 */
    fun onSongEnded(reason: PlaybackEndReason = PlaybackEndReason.COMPLETED) {
        val segment = tracker.end(reason) ?: return
        if (!enabled()) return
        if (!KugouApiService.isLoggedIn()) return
        sendEnd(segment)
    }

    /** 关闭开关/退出登录时丢弃当前段，不上报。 */
    fun discard() {
        tracker.discard()
        segmentUserid = null
    }

    private fun sendStart(mixsongid: String) {
        scope.launch {
            KugouApiService.getInstance().reportListenEvent(
                event = "start",
                mixsongid = mixsongid,
                deviceModel = deviceModel,
                systemVersion = systemVersion
            ).fold(
                onSuccess = { YosDiagnostics.log("CSCC_START", "mixsongid" to mixsongid, "resp" to it.toString().take(120)) },
                onFailure = { YosDiagnostics.log("CSCC_START", "mixsongid" to mixsongid, "ok" to false, "why" to (it.message ?: "")) }
            )
            Log.d(TAG, "start sent mixsongid=$mixsongid")
        }
    }

    private fun sendEnd(segment: PlaybackSegment) {
        // 账号校验：本段开始时所属账号与当前不一致 → 丢弃，避免把时长记到错误账号
        val segmentUid = segmentUserid
        val currentUid = KugouApiService.userid
        if (segmentUid != null && segmentUid != currentUid) {
            YosDiagnostics.log("CSCC_END", "drop" to "account_changed", "seg" to segmentUid, "now" to currentUid)
            return
        }
        if (!segment.reachesThreshold(MIN_REPORTABLE_MS)) {
            YosDiagnostics.log("CSCC_END", "skip" to "too_short", "ms" to segment.durationMs)
            return
        }
        scope.launch {
            KugouApiService.getInstance().reportListenEvent(
                event = "end",
                mixsongid = segment.mixsongid,
                duration = segment.durationMs,
                state = segment.reason.state,
                deviceModel = deviceModel,
                systemVersion = systemVersion
            ).fold(
                onSuccess = {
                    YosDiagnostics.log(
                        "CSCC_END",
                        "mixsongid" to segment.mixsongid,
                        "ms" to segment.durationMs,
                        "resp" to it.toString().take(120)
                    )
                },
                onFailure = {
                    YosDiagnostics.log(
                        "CSCC_END",
                        "mixsongid" to segment.mixsongid,
                        "ms" to segment.durationMs,
                        "ok" to false,
                        "why" to (it.message ?: "")
                    )
                }
            )
            Log.d(TAG, "end sent ${segment.mixsongid} ${segment.durationMs}ms")
        }
    }

    /** 开关是否开启（默认 true，本工程口径）；每次上报前重读，设置页切换即时生效。 */
    private fun enabled(): Boolean = SettingsLibrary.UploadListeningDuration
}
