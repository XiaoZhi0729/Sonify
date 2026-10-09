package yos.music.player.code

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import coil.size.Size
import androidx.annotation.OptIn
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.ForwardingPlayer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.PlaybackException
import androidx.media3.common.Timeline
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.CommandButton
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaController
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import androidx.media3.session.SessionCommand
import androidx.media3.session.SessionResult
import cn.lyric.getter.api.API
import cn.lyric.getter.api.data.ExtraData
import cn.lyric.getter.api.tools.Tools
import com.blankj.utilcode.util.ResourceUtils.getDrawable
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.MainActivity
import yos.music.player.R
import yos.music.player.code.MediaController.mediaControl
import yos.music.player.code.MediaController.mediaSession
import yos.music.player.code.MediaController.musicPlaying
import yos.music.player.code.MediaController.onServiceRunning
import yos.music.player.code.utils.lrc.YosLrcFactory
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.code.utils.lrc.LyricLoadCoordinator
import yos.music.player.code.utils.lrc.LyricEntry
import yos.music.player.code.utils.lrc.LyricInfoSerializer
import yos.music.player.code.MediaController as YosControllerObject
import yos.music.player.code.utils.player.CrossfadeExo
import yos.music.player.code.utils.player.FadeExo
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.player.FadeExo.fadePlay
import yos.music.player.code.utils.player.OnlineFailureKind
import yos.music.player.code.utils.player.OnlineSkipPolicy
import yos.music.player.code.utils.player.OnlineSkipState
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.coverCacheKey
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.MusicLibrary.toMediaItem
import yos.music.player.data.libraries.MusicLibrary.toYosMediaItem
import yos.music.player.data.libraries.PlayListV1
import yos.music.player.data.libraries.PlayStatus
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.uri
import yos.music.player.data.objects.MainViewModelObject
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.repositories.IntentSource
import yos.music.player.data.repositories.KugouAuthInvalidException
import yos.music.player.data.repositories.KugouQuality
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.ListeningGradeTracker
import yos.music.player.data.repositories.SongPlayabilityStore
import yos.music.player.data.repositories.PerSongQualityIntent
import yos.music.player.data.repositories.QualityIntentResolver
import yos.music.player.data.repositories.QualitySwitchPolicy
import yos.music.player.data.repositories.QualityTrace
import yos.music.player.native.KugouApiService

@Stable
object MediaController {
    @Stable
    val mainMusicList: List<YosMediaItem>
        get() = MusicLibrary.songs

    @Stable
    var playingMusicList = mutableStateOf<List<YosMediaItem>?>(null)

    @Stable
    var mediaControl: MediaController? = null

    @Stable
    var musicPlaying = mutableStateOf<YosMediaItem?>(null)

    @Stable
    var mediaSession: MediaSession? = null

    // ---------- 倍速播放 ----------

    /**
     * 设置播放倍速并持久化。mediaControl 的调用须主线程；
     * 服务侧在 onCreate 时应用持久化值，跨进程重启保持用户选择。
     */
    fun applyPlaybackSpeed(speed: Double) {
        SettingsLibrary.PlaybackSpeed = speed
        // 倍速一变，已在途的过渡曲线长度就错了（窗口按倍速折算）：作废，下一次重新起
        CrossfadeExo.abort("speed")
        CoroutineScope(Dispatchers.Main).launch {
            mediaControl?.setPlaybackSpeed(speed.toFloat())
        }
    }

    // ---------- 在线音质 ----------

    /**
     * 应用级 Context：由 Application 与播放服务 onCreate 注入。
     * 切档时要读偏好与网络形态；未注入时（理论上不会：能切档说明已在播放）
     * 退回"只记意图、不强制重解析"，绝不拿 null 当"按 WiFi 档"的借口。
     */
    @Volatile
    var appContext: android.content.Context? = null

    /** 当前在线曲的 hash（本地曲 / 未开播 / 无占位符时 null）。 */
    fun currentOnlineHash(): String? =
        musicPlaying.value?.uri
            ?.takeIf { it.scheme == KugouRepository.PLACEHOLDER_SCHEME }
            ?.lastPathSegment

    /** 上一次切换的协程 Job：新意图取消旧探测（采用最新意图，不排队也不防抖）。 */
    private var qualitySwitchJob: Job? = null

    /**
     * 播放页/更多菜单选档：**只作用于本首**，不触碰设置页的 WiFi/流量偏好（A4/A5）。
     *
     * 历史上这里把 WiFi 与流量两档一并写死：用户以为在改"这一首"，实际永久改掉了
     * 全局分设且不可撤销——两处设置冲突的全部来源。
     *
     * 两条早退保证零无谓中断（A3）：
     *  ① 意图未变 → 不发解析、不重建媒体周期；
     *  ② 新档实际拿到的仍是同一规格 → 只记结论，不重开（用户不该为"没有变化的选择"听到一次卡顿）。
     * 探测失败 → 撤销刚写入的覆盖并保留现场（A2：不跳歌、不留错误的"以后都试高档"）。
     */
    fun requestQualityForCurrentSong(tier: String) {
        val hash = currentOnlineHash() ?: return
        val normalized = KugouQuality.parse(tier) ?: return
        // 不用时间窗口防抖：新意图直接取消旧探测（下面 cancel），幂等且不会错过最后一次点击
        qualitySwitchJob?.cancel()

        if (QualitySwitchPolicy.before(
                target = normalized,
                pending = KugouRepository.pendingTierOf(hash),
                factIntent = KugouRepository.factOf(hash)?.intentTier,
                override = PerSongQualityIntent.overrideOf(hash)
            ) == QualitySwitchPolicy.Before.NOOP_INTENT_UNCHANGED
        ) {
            // 早退 ①：用户点了当前已生效（或正在切过去）的那一档——什么都不做，
            // 理由由菜单里那一行的结论 chip 说明，不需要重新跑一次解析
            QualityTrace.log("SWITCH_NOOP", "hash" to hash, "tier" to normalized, "why" to "intent_unchanged")
            return
        }
        val previousOverride = PerSongQualityIntent.overrideOf(hash)
        val previousPlayingTier = KugouRepository.actualQualityOf(hash)
        PerSongQualityIntent.setOverride(hash, normalized)
        qualitySwitchJob = CoroutineScope(Dispatchers.Main).launch {
            switchTo(
                hash,
                QualityIntentResolver.Decision(normalized, IntentSource.PER_SONG),
                previousPlayingTier
            ) { restored ->
                // 探测失败：回滚本曲覆盖，别让一次失败的选择绑架后续所有播放
                if (restored) {
                    if (previousOverride == null) PerSongQualityIntent.clearOverride(hash)
                    else PerSongQualityIntent.setOverride(hash, previousOverride)
                }
            }
        }
    }

    /**
     * 音质切换的唯一提交路径：先探测（不动现场）→ 有变化才重建媒体周期。
     * [onFailureRollback] 在探测失败时回调（供调用方回滚自己的意图写入）。
     */
    private suspend fun switchTo(
        hash: String,
        decision: QualityIntentResolver.Decision,
        previousPlayingTier: String?,
        onFailureRollback: (Boolean) -> Unit = {}
    ) {
        QualityTrace.userSwitchCount++
        // 显式意图：作废本曲负缓存、丢掉高于目标的缓存（A2），但保留事实与其余 URL
        // 缓存（不闪空、不丢兜底弹药）
        KugouRepository.prepareForExplicitSwitch(hash, decision.tier)
        val fact = withContext(Dispatchers.IO) {
            KugouRepository.probeForSwitch(hash, decision)
        }
        when (QualitySwitchPolicy.after(
            probeFailed = fact == null,
            obtained = fact?.playingTier,
            previousPlaying = previousPlayingTier
        )) {
            // 探测失败：回滚用户刚写的意图，现场不动
            QualitySwitchPolicy.After.ROLLBACK -> onFailureRollback(true)

            // 早退 ②：拿到的还是同一个规格 → 不重开
            QualitySwitchPolicy.After.NO_RELOAD -> {
                KugouRepository.clearPending(hash)
                QualityTrace.log(
                    "SWITCH_NO_RELOAD", "hash" to hash, "tier" to decision.tier,
                    "stamped" to fact?.playingTier
                )
            }

            // 确有变化：恰好一次重开（进度与播放态由提交路径保持）
            QualitySwitchPolicy.After.COMMIT_RELOAD -> {
                QualityTrace.userSwitchCausedReload++
                commitReload()
            }
        }
    }

    /**
     * 设置页偏好变更后的立即生效：仍走同一条"先探测、有变化才重开"的事务路径。
     * 直接返回（本地曲、未开播、无队列）时不做任何事。
     */
    suspend fun reapplyOnlineQuality() {
        val hash = currentOnlineHash() ?: return
        val decision = appContext?.let { QualityIntentResolver.resolve(hash, it) } ?: return
        switchTo(hash, decision, KugouRepository.actualQualityOf(hash))
    }

    // ---------- 睡眠定时器 ----------

    /** 睡眠定时器剩余秒数（0=未启用）；每秒回流的响应式状态，UI 直接订阅。 */
    val sleepTimerRemainingSeconds = mutableIntStateOf(0)

    private var sleepTimerJob: Job? = null

    /** "播完当前曲再暂停"到点后挂的一次性监听；触发或手动取消后即注销。 */
    private var sleepEndListener: Player.Listener? = null

    /**
     * 启动睡眠定时器：到点仅暂停播放（不清队列，对齐上游 md3Music）。
     * 挂 object 而非 Compose/Activity——后台播放时 UI 层协程不可靠；
     * 剩余时间按绝对截止时刻换算（每 250ms 校准，不受单个 delay 漂移影响）；
     * 进程被杀定时自然消失（与上游行为一致）。
     */
    fun startSleepTimer(minutes: Int) {
        YosDiagnostics.log("SLEEP_START", "minutes" to minutes)
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        // 重设定时同时作废上一轮到点后可能仍在等待的"播完再暂停"检查点
        stopSleepEndWatcher()
        if (minutes <= 0) {
            sleepTimerRemainingSeconds.intValue = 0
            return
        }
        val deadlineMs = System.currentTimeMillis() + minutes * 60_000L
        sleepTimerJob = CoroutineScope(Dispatchers.Main).launch {
            while (true) {
                val remainMs = deadlineMs - System.currentTimeMillis()
                if (remainMs <= 0) break
                sleepTimerRemainingSeconds.intValue = ((remainMs + 999) / 1000).toInt()
                delay(250)
            }
            sleepTimerRemainingSeconds.intValue = 0
            sleepTimerJob = null
            onSleepTimerElapsed()
        }
    }

    /**
     * 到点动作。开关关：立即淡出暂停（原行为）。
     * 开关开：不立即暂停，挂一个一次性检查点等当前曲自然播完再淡出暂停；
     * 期间用户手动切歌/清除队列（非自然推进）只作废旧检查点、不再暂停，
     * 保证只在"到达设定时间且未手动取消"的场景下生效，不干扰队列其他操作。
     *
     * 挂上检查点的同时必须挂起平滑过渡：这两条语义直接对立——"播完就停"要的是
     * 这一首结束后的安静，而过渡做的事就是把下一首的前几秒淡入进来放完。不挂闩的话，
     * abort 只能拦当前一轮，150ms 后引擎会重新预告一条新的，现象就是"下一首响了 5 秒才停"。
     */
    private fun onSleepTimerElapsed() {
        YosDiagnostics.log("SLEEP_FIRED", "finishCurrent" to SettingsLibrary.EnableFinishCurrentSongBeforePause)
        val controller = mediaControl ?: return
        if (!SettingsLibrary.EnableFinishCurrentSongBeforePause) {
            runCatching { controller.fadePause() }
            return
        }
        stopSleepEndWatcher()
        // 放在 stopSleepEndWatcher 之后：那里会把上一轮的闩解开，本行重新挂上
        CrossfadeExo.suspend("sleep_finish_current")
        val listener = object : Player.Listener {
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                // 当前曲自然播完：含进下一首（AUTO）与单曲循环播完这一遍（REPEAT），
                // 两者都算"这首听完了"，到点就该停；手动切歌（SEEK/PLAYLIST_CHANGED）
                // 不作暂停，只作废检查点。
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO ||
                    reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT
                ) {
                    runCatching { mediaControl?.fadePause() }
                }
                stopSleepEndWatcher()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                // 单曲/队末自然结束（无下一首可进）
                if (playbackState == Player.STATE_ENDED) {
                    runCatching { mediaControl?.fadePause() }
                    stopSleepEndWatcher()
                }
            }
        }
        sleepEndListener = listener
        runCatching { controller.addListener(listener) }
    }

    /**
     * 注销"播完当前曲再暂停"的一次性监听（触发后、或重设/手动取消定时器时调用）。
     * internal：播放服务在重备（[OnlineSkipState] 的退避重试）前也要作废它——
     * `prepare()` 会产生一次 REPEAT 过渡，不作废就会把刚自愈成功的歌误判成"播完了"而暂停。
     */
    internal fun stopSleepEndWatcher() {
        val listener = sleepEndListener ?: return
        runCatching { mediaControl?.removeListener(listener) }
        sleepEndListener = null
        // 检查点不在了（已触发/被取消）就恢复过渡：下一首自然播完时该淡还是淡
        CrossfadeExo.resume("sleep_watch_cleared")
    }

    fun cancelSleepTimer() {
        YosDiagnostics.log("SLEEP_STOP")
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerRemainingSeconds.intValue = 0
        // 到点后处于"等当前曲播完"的窗口内时，手动取消一并作废该检查点
        stopSleepEndWatcher()
    }

    // ---------- 播放队列编辑 ----------

    /**
     * 队列编辑后的三同步：Media3 timeline（权威顺序）→ playingMusicList 快照 → MMKV 持久化。
     * 快照与 timeline 失位会导致点击切歌索引错位，任何队列变更后必须调用。
     * 持久化条件与 prepare 保持一致（无条件落盘）：本地库为空的设备（如无本地音乐的平板）
     * 也必须持久化队列，否则编辑后重启丢队列。
     */
    private suspend fun syncQueueSnapshot() = withContext(Dispatchers.Main) {
        val controller = mediaControl ?: return@withContext
        val timeline = controller.currentTimeline
        // 发布条目不携带自有 extras（见 MusicLibrary.toMediaItem），timeline 读回的
        // 重建值缺时长等字段：按 mediaId 与编辑前的队列快照合并取完整条目
        val known = playingMusicList.value.orEmpty().associateBy { it.mediaId }
        val newList = if (timeline.isEmpty) emptyList()
        else (0 until timeline.windowCount).map { i ->
            val rebuilt = timeline.getWindow(i, Timeline.Window()).mediaItem.toYosMediaItem()
            known[rebuilt.mediaId] ?: rebuilt
        }
        playingMusicList.value = newList
        MusicLibrary.updatePlayList(PlayListV1(mainMusicList, newList))
    }

    /** 队列内拖拽重排（Media3 原生 moveMediaItem，随机顺序由 Media3 自管不受影响）。 */
    suspend fun moveQueueItem(from: Int, to: Int) = withContext(Dispatchers.Main) {
        val controller = mediaControl ?: return@withContext
        val count = controller.mediaItemCount
        if (from !in 0 until count || to !in 0 until count || from == to) return@withContext
        // 重排会改动"下一首"是谁，副 player 里备好的那首可能就是错的：直接作废，不赌
        CrossfadeExo.abort("queue_move")
        YosDiagnostics.log("SRC", "fn" to "moveQueueItem")
        controller.moveMediaItem(from, to)
        syncQueueSnapshot()
    }

    /**
     * 多选删除（索引自大向小删避免位移）。删除当前播放曲时 Media3 自动切到相邻曲；
     * 删空后播放器进入空闲，快照同步为空列表。
     */
    suspend fun removeQueueItems(indices: Collection<Int>) = withContext(Dispatchers.Main) {
        val controller = mediaControl ?: return@withContext
        // 同上：删到目的那首就会突然换曲，不如退回硬切
        CrossfadeExo.abort("queue_remove")
        YosDiagnostics.log("SRC", "fn" to "removeQueueItems", "n" to indices.size)
        indices
            .filter { it in 0 until controller.mediaItemCount }
            .sortedDescending()
            .forEach { controller.removeMediaItem(it) }
        syncQueueSnapshot()
    }

    /**
     * 队列排序（破坏性重排，对齐上游 sortPlaylist 语义）：当前曲按 mediaId 重新定位并保留进度。
     */
    suspend fun sortQueue(comparator: Comparator<YosMediaItem>, descending: Boolean = false) =
        withContext(Dispatchers.Main) {
            val controller = mediaControl ?: return@withContext
            val current = playingMusicList.value ?: return@withContext
            val currentMediaId = controller.currentMediaItem?.mediaId
            // 排序是整时线重建，副 player 手里那首与新顺序不再对应
            CrossfadeExo.abort("queue_sort")
            YosDiagnostics.log("SRC", "fn" to "sortQueue")
            val sorted = if (descending) current.sortedWith(comparator.reversed())
            else current.sortedWith(comparator)
            val newIndex = sorted.indexOfFirst { it.mediaId == currentMediaId }.coerceAtLeast(0)
            controller.setMediaItems(sorted.map { it.toMediaItem() }, newIndex, controller.currentPosition)
            controller.prepare()
            syncQueueSnapshot()
        }

    // ---------- 手动切歌（唯一入口） ----------

    /**
     * 播放页/迷你条/横屏「上一首」「下一首」的唯一入口。两件事一起解决：
     *
     *  ① 埋点：按下即记 `SRC fn=ui_next`（带当时索引/队列长度/播放态）。此前手动传输命令在日志
     *     里完全隐形（只探得到 session 侧的外部命令），“切歌没反应”只能靠“PREPARE 之外的
     *     reason=2”间接反推，永远分不清“没发出 / 被吞 / 发出后被打回”。
     *  ② 恢复：source error 后播放器停在 IDLE，此时 `seekToNextMediaItem()` 只移动索引、不触发
     *     加载——用户视角就是“按了完全没反应”（真机日志实证：SKIP_LIMIT 锁死后按区间无任何
     *     TRANSITION）。IDLE 下必须补 `prepare()+play()`，与错误跳歌兜底同一套三行。
     *
     * 耳机/通知栏的 NEXT 由 MediaSession 内部直接打到底层 player，进不了本入口，
     * 靠 `TRANSITION` 行区分来源。
     */
    fun userSkipNext() = skipInternal(forward = true)

    /** 见 [userSkipNext]。 */
    fun userSkipPrevious() = skipInternal(forward = false)

    private fun skipInternal(forward: Boolean) {
        val fn = if (forward) "ui_next" else "ui_prev"
        if (Looper.myLooper() == Looper.getMainLooper()) skipOnMain(fn, forward) else
            CoroutineScope(Dispatchers.Main).launch { skipOnMain(fn, forward) }
    }

    /** media3 的 MediaController 禁止跨线程访问（verifyApplicationThread 直接抛），统一回主线程。 */
    private fun skipOnMain(fn: String, forward: Boolean) {
        val controller = mediaControl
        if (controller == null) {
            // 控制器没连上时什么都不做也要留痕：这就是“没发出”那一档
            YosDiagnostics.log("SRC", "fn" to fn, "why" to "no_controller")
            return
        }
        val state = runCatching { controller.playbackState }.getOrDefault(-1)
        YosDiagnostics.log(
            "SRC",
            "fn" to fn,
            "idx" to runCatching { controller.currentMediaItemIndex }.getOrDefault(-1),
            "q" to runCatching { controller.mediaItemCount }.getOrDefault(-1),
            "state" to YosDiagnostics.stateLabel(state)
        )
        // 手动切歌是显式意图：作废上一轮的失败锁存（否则新队列第一首一失败就"达上限"）
        OnlineSkipState.reset(fn)
        val rearm = state == Player.STATE_IDLE
        runCatching {
            if (forward) controller.seekToNextMediaItem() else controller.seekToPreviousMediaItem()
            if (rearm) {
                controller.prepare()
                controller.fadePlay()
            }
        }.onFailure { e ->
            YosDiagnostics.log("SRC_ERR", "fn" to fn, "why" to e.toString())
        }
        // 重备是“不是自然播完”的推进：副 player 里备着的可能正是那首坏曲
        if (rearm) CrossfadeExo.abort("user_skip_rearm")
    }

    fun onServiceRunning() {
        val handler by lazy { Handler(Looper.getMainLooper()) }
        val lyricAPI by lazy { API() }
        var lastLyric: LyricEntry? = null
        val base64 = Tools.drawableToBase64(getDrawable(R.drawable.flamingo_icon_notification)!!)
        var statusBarLyricEnabled: Boolean
        var hooked = false

        val checkHookStatusRunnable = object : Runnable {
            override fun run() {
                hooked = lyricAPI.hasEnable
                SettingsLibrary.StatusBarLyricHooked = hooked
                handler.postDelayed(this, 350)
            }
        }

        val updateLyricsRunnable = object : Runnable {
            override fun run() {
                runCatching {
                    var currentLyricIndex: Int
                    var isPlaying: Boolean?
                    var liveTime: Long

                    handler.post {
                        isPlaying = mediaControl?.isPlaying

                        runCatching {
                            currentLyricIndex = MainViewModelObject.syncLyricIndex.intValue

                            if (isPlaying == true) {
                                liveTime = (mediaControl?.currentPosition ?: 0) +
                                    SettingsLibrary.LyricTimingOffset

                                val lrcEntries = MediaViewModelObject.lrcEntries.value

                                val nextIndex = lrcEntries.indexOfFirst { line ->
                                    line.startTime >= liveTime
                                }

                                val sendLyric = fun() {
                                    try {
                                        MainViewModelObject.syncLyricIndex.intValue =
                                            currentLyricIndex
                                        statusBarLyricEnabled =
                                            SettingsLibrary.StatusBarLyricEnabled


                                        val line = lrcEntries[currentLyricIndex]
                                        if (line == lastLyric) {
                                            return
                                        }

                                        val lyric = StringBuffer("")
                                        line.mainLyric.forEach { char ->
                                            lyric.append(char.second)
                                        }

                                        val lyricResult = lyric.toString()

                                        if (statusBarLyricEnabled && hooked) {
                                            lyricAPI.sendLyric(
                                                lyricResult,
                                                extra = ExtraData().apply {
                                                    customIcon = true
                                                    base64Icon = base64
                                                }
                                            )
                                        }

                                        // YosPlaybackService().sendLyricTicker(lyricResult)

                                        lastLyric = line
                                    } catch (_: Exception) {
                                    }
                                }

                                if (nextIndex != -1) {
                                    if (nextIndex - 1 != currentLyricIndex) {
                                        currentLyricIndex = nextIndex - 1
                                    }
                                    if (currentLyricIndex != -1) {
                                        sendLyric()
                                    }
                                } else if (currentLyricIndex != lrcEntries.size - 1) {
                                    currentLyricIndex = lrcEntries.size - 1
                                    if (currentLyricIndex != -1) {
                                        sendLyric()
                                    }
                                }
                            }
                        }
                    }

                    handler.postDelayed(this, 70)
                }
            }
        }

        CoroutineScope(Dispatchers.IO).launch {
            handler.post(checkHookStatusRunnable)
            handler.post(updateLyricsRunnable)
        }
    }



    suspend fun prepare(
        music: YosMediaItem,
        thisMusicList: List<YosMediaItem>,
        position: Long = 0L,
        shuffleModeEnabled: Boolean = false,
        repeatMode: Int = REPEAT_MODE_ALL,
        play: Boolean = true
    ) {
        println("prepare $music")
        // 到点恢复/用户手动开播都走这里：记下 play 与起始进度，才能把"为什么没声音"
        // 与"根本没去播"区分开（println 在 release 会被剥，这行不会）
        YosDiagnostics.log("PREPARE", "mediaId" to music.mediaId, "play" to play, "pos" to position)
        // 用户主动开播（点任意一首歌、恢复上次播放）：一切在途的平滑过渡作废——本功能
        // 只服务"自然播完自动进下一首"，手动路径一律硬切
        CrossfadeExo.abort("prepare")
        // 用户显式意图同样作废跳歌锁存：不清就会把"上一轮整队列失败"的计数算到新队列上，
        // 新队列第一首一失败就直接落进"达上限"分支（真机：用户重新点歌也救不回来）
        OnlineSkipState.reset("user_prepare")
        if (thisMusicList != playingMusicList.value) {

            var index = 0

            val itemList = thisMusicList.mapIndexed { thisIndex, it ->
                if (it.uri == music.uri) {
                    index = thisIndex
                }

                it.toMediaItem()
            }


            withContext(Dispatchers.Main) {
                mediaControl?.setMediaItems(itemList, index, position)
                mediaControl?.prepare()
            }

            println("prepare 调用切列表")
            if (!play && playingMusicList.value == null) {
                // 必须在 Main 线程写入:IO 线程写 snapshot state 会让主线程
                // measure 中的 LazyColumn 当帧读到新列表,而 key 快照还是旧的(越界崩溃)
                withContext(Dispatchers.Main) { playingMusicList.value = thisMusicList }
                //refresh(music)
                withContext(Dispatchers.Main) {
                    mediaControl?.shuffleModeEnabled = shuffleModeEnabled
                    mediaControl?.repeatMode = repeatMode
                    mediaControl?.let { YosPlaybackService().setCustomButtons(it) }
                }
            } else {
                withContext(Dispatchers.Main) { playingMusicList.value = thisMusicList }
            }

            if (play) {
                withContext(Dispatchers.Main) {
                    mediaControl?.fadePlay()
                }
            }

            // 播放列表切换事件
            println("prepare 尝试保存播放列表")
            if (mainMusicList != null && playingMusicList.value != null) {
                println("prepare 保存播放列表")
                MusicLibrary.updatePlayList(
                    PlayListV1(
                        mainMusicList,
                        playingMusicList.value
                    )
                )
            }

        } else {
            println("prepare 调用非切列表")
            val index = thisMusicList.indexOf(music)
            ensureOnlineLyric(music)
            withContext(Dispatchers.Main) {
                mediaControl?.seekToDefaultPosition(index)
                mediaControl?.fadePlay()
            }
        }
    }

    /**
     * 提交重建：对当前在线歌曲按已记录的意图重新解析并续播（已由 [switchTo] 探测过，
     * 此处命中 URL 缓存、不再发网络请求）。
     * 直接 setMediaItems(同内容列表, 当前索引, 当前进度)：重建媒体周期、强制数据源
     * 重新 open（不会像 seek 那样落在缓冲区内而静默不重开），进度与播放/暂停状态
     * 天然保留、不强制开播。不走 prepare——其列表相等判断是内容比较，else 分支会把
     * 歌拉回 0ms 并无视 play 参数强制 fadePlay（切音质跳歌/归零的历史根因）。
     * 本地歌曲不受音质影响，直接返回。
     */
    private suspend fun commitReload() {
        val current = musicPlaying.value ?: return
        val uri = current.uri ?: return
        if (uri.scheme != KugouRepository.PLACEHOLDER_SCHEME) return
        val list = playingMusicList.value ?: return
        if (list.isEmpty()) return
        val controller = mediaControl ?: return
        // 重建媒体周期会把主 player 拉回重新 open，进度与副 player 手里那段对不上：先退出过渡
        CrossfadeExo.abort("quality_reload")
        YosDiagnostics.log("SRC", "fn" to "commitReload")
        withContext(Dispatchers.Main) {
            val index = controller.currentMediaItemIndex
            val position = controller.currentPosition
            controller.setMediaItems(list.map { it.toMediaItem() }, index, position)
            controller.prepare()
        }
    }

    fun onCase(mediaItem: YosMediaItem) {
        CoroutineScope(Dispatchers.IO).launch {
            refresh(mediaItem)
        }
    }

    /** 最近一次预加载封面的 URI，同一首只入队一次。 */
    private var lastPreloadedCoverThumb: Uri? = null

    /**
     * 下一首封面预加载：当前曲开始播放时就预解码下一首封面进内存缓存，
     * 切歌瞬间 AsyncImage 直接命中，不闪占位图。
     * 请求参数必须与 ShadowImageWithCache 的大封面（RAW）完全一致——
     * 显示侧用了显式 memoryCacheKey，尺寸/精度不同就拿不到同一个缓存键
     * （键统一由 ShadowImage.kt 的 coverCacheKey 生成："$thumb|RAW" 槽）。
     * 下一首走 Player.getNextMediaItemIndex()，与真实播放推进（随机/循环）一致。
     */
    fun preloadNextCover(player: Player, context: Context) {
        try {
            if (player.repeatMode == REPEAT_MODE_ONE) return
            if (!player.hasNextMediaItem()) return
            val nextItem = player.getMediaItemAt(player.getNextMediaItemIndex())
            val thumb = nextItem.mediaMetadata.artworkUri ?: return
            if (thumb == player.currentMediaItem?.mediaMetadata?.artworkUri) return
            if (thumb == lastPreloadedCoverThumb) return
            lastPreloadedCoverThumb = thumb

            val request = ImageRequest.Builder(context)
                .data(thumb)
                .size(Size.ORIGINAL)
                .precision(Precision.EXACT)
                .memoryCacheKey(coverCacheKey(thumb, ImageQuality.RAW))
                .allowHardware(true)
                .build()
            context.imageLoader.enqueue(request)
            println("封面预加载 $thumb")
        } catch (_: Exception) {
        }
    }

    private var refreshJob: CompletableJob? = null

    private val lyricCoordinator = LyricLoadCoordinator()
    private val lyricParseLock = Any()

    private val lyricHandler = Handler(Looper.getMainLooper())
    private const val LYRIC_PUBLISH_MAX_RETRIES = 5
    private const val LYRIC_PUBLISH_RETRY_INTERVAL_MS = 800L

    private fun isOnlineMedia(mediaId: String?): Boolean =
        mediaId?.startsWith("kugou-online-") == true

    /**
     * 歌词帧与切歌元数据提交的最小间隔：OPlus 锁屏的防抖会丢弃同曲紧邻的第二次提交，
     * 缓存命中等场景下歌词就绪可能贴着切歌提交，统一延后再发（对锁定窗口的保守估计）。
     */
    private const val LYRIC_PUBLISH_INITIAL_DELAY_MS = 700L

    /**
     * 把歌词以 LyricInfo 协议写入当前媒体条目的元数据 extras，随 MediaSession 发布给系统，
     * 供 HyperLyric（小米超级岛）与 ColorOS 锁屏歌词等组件读取。
     *
     * 发布统一投递主线程执行（media3 MediaController 禁止跨线程访问）；当前条目尚未就绪
     * （控制器未连上/切歌未落地）时定时重试，歌词先于播放态就绪不再被静默丢弃
     * （对齐 MD3Music 的"推送先于首次元数据发布不得丢弃"兜底）。
     */
    fun publishSuperIslandLyric(mediaId: String?, entries: List<LyricEntry>) {
        if (!SettingsLibrary.SuperIslandLyricEnabled) {
            YosDiagnostics.log("LYRIC_DISABLED", "id" to mediaId)
            return
        }
        if (mediaId == null) return
        YosDiagnostics.log(
            "LYRIC_REQ", "id" to mediaId, "lines" to entries.size,
            "raw" to entries.any { it.mainLyric.size >= 3 },
            "delay" to LYRIC_PUBLISH_INITIAL_DELAY_MS
        )
        requestLyricPublish(mediaId, entries, delayMs = LYRIC_PUBLISH_INITIAL_DELAY_MS)
    }

    /** 无视开关直接移除当前条目的歌词元数据，供关闭开关时清理。 */
    fun removeSuperIslandLyric() {
        YosDiagnostics.log("LYRIC_REMOVE_REQ")
        requestLyricPublish(mediaId = null, entries = null)
    }

    /** 待发布目标：mediaId 为 null 表示清理当前条目；entries 为 null 表示移除歌词键。 */
    private var lyricPublishTarget: Pair<String?, List<LyricEntry>?>? = null
    private var lyricPublishRunnable: Runnable? = null
    private var lyricPublishAttempts = 0

    /**
     * 会话代次（LyricInfo 协议 §5）：仅在真实换曲（mediaId 变化）时递增，同曲重推不递增，
     * 供消费端拒绝同会话过期 payload。
     */
    private var lyricSessionGeneration = 0
    private var lyricGenerationMediaId: String? = null

    private fun lyricGenerationFor(mediaId: String): Int {
        if (mediaId != lyricGenerationMediaId) {
            lyricGenerationMediaId = mediaId
            lyricSessionGeneration++
        }
        return lyricSessionGeneration
    }

    private fun requestLyricPublish(mediaId: String?, entries: List<LyricEntry>?, delayMs: Long = 0L) {
        lyricHandler.post {
            lyricPublishRunnable?.let { lyricHandler.removeCallbacks(it) }
            lyricPublishTarget = mediaId to entries
            lyricPublishAttempts = 0
            if (delayMs > 0L) {
                val run = Runnable { runLyricPublish() }
                lyricPublishRunnable = run
                lyricHandler.postDelayed(run, delayMs)
            } else {
                runLyricPublish()
            }
        }
    }

    private fun runLyricPublish() {
        val target = lyricPublishTarget ?: return
        val (mediaId, entries) = target
        // 歌曲已切走：放弃旧目标（新一轮请求会覆盖 pending），不做无意义重试。
        if (mediaId != null && musicPlaying.value?.mediaId != mediaId) {
            YosDiagnostics.log(
                "LYRIC_STALE", "target" to mediaId, "now" to musicPlaying.value?.mediaId
            )
            lyricPublishTarget = null
            return
        }
        val generation = if (mediaId != null) lyricGenerationFor(mediaId) else 0
        if (lyricPublishAttempts > 0) {
            YosDiagnostics.log("LYRIC_RETRY", "id" to mediaId, "attempt" to lyricPublishAttempts)
        }
        val settled = updateSuperIslandExtra(mediaId, generation) { item ->
            if (entries == null) null else LyricInfoSerializer.encode(item, entries, generation)
        }
        if (settled || lyricPublishAttempts >= LYRIC_PUBLISH_MAX_RETRIES) {
            if (!settled) {
                YosDiagnostics.log("LYRIC_GIVEUP", "id" to mediaId, "attempts" to lyricPublishAttempts)
            }
            lyricPublishTarget = null
            return
        }
        lyricPublishAttempts++
        val run = Runnable { runLyricPublish() }
        lyricPublishRunnable = run
        lyricHandler.postDelayed(run, LYRIC_PUBLISH_RETRY_INTERVAL_MS)
    }

    /**
     * @return true 表示目标已终结（已发布/已清理/内容无变化），false 表示条目未就绪需重试。
     */
    private fun updateSuperIslandExtra(
        mediaId: String?,
        generation: Int,
        encode: (MediaItem) -> String?
    ): Boolean {
        try {
            val controller = mediaControl ?: run {
                YosDiagnostics.log("LYRIC_NOREADY", "why" to "no_controller", "id" to mediaId)
                return false
            }
            val index = controller.currentMediaItemIndex
            if (index == C.INDEX_UNSET) {
                YosDiagnostics.log("LYRIC_NOREADY", "why" to "no_index", "id" to mediaId)
                return false
            }
            val item = controller.getMediaItemAt(index)
            if (mediaId != null && item.mediaId != mediaId) {
                YosDiagnostics.log(
                    "LYRIC_NOREADY", "why" to "mismatch", "id" to mediaId, "item" to item.mediaId
                )
                return false
            }

            val json = encode(item)
            val oldExtras = item.mediaMetadata.extras
            val oldValue = oldExtras?.getString(LyricInfoSerializer.EXTRAS_KEY)
            if (json == null) {
                if (oldValue == null) {
                    YosDiagnostics.log("LYRIC_SKIP", "id" to item.mediaId, "why" to "no_content")
                    return true
                }
            } else if (json == oldValue) {
                YosDiagnostics.log("LYRIC_SKIP", "id" to item.mediaId, "why" to "same")
                return true
            }

            // OPlus 锁屏对同曲紧邻两次元数据提交会丢弃第二次（防抖，MD3Music 真机实证
            // "within debounce period, ignore"），而 media3 的 MediaMetadata.equals 对
            // extras 只比 null 与非 null、不比内容（1.4.0 起如此）——所以每次向锁屏
            // 发布只允许一次 replaceMediaItem，且 extras 必须走 null→非 null 才能触发
            // framework 重发布（发布条目的自有 extras 已剥离，见 MusicLibrary.toMediaItem）。
            // 旧 extras 非空（残留 lyricInfo）时先清空一帧：清理目标到此终结；写入目标
            // 交给重试循环在 800ms 后以 null→非 null 单帧补写，避开防抖窗口。
            if (oldExtras != null) {
                val clearedMetadata = item.mediaMetadata.buildUpon().setExtras(null).build()
                controller.replaceMediaItem(index, item.buildUpon().setMediaMetadata(clearedMetadata).build())
                YosDiagnostics.log(
                    "LYRIC_CLEAR", "id" to item.mediaId, "rewrite" to (json != null),
                    "oldLen" to oldValue?.length
                )
                return json == null
            }

            val updatedMetadata = item.mediaMetadata.buildUpon()
                .setExtras(Bundle().apply { putString(LyricInfoSerializer.EXTRAS_KEY, json) })
                .build()
            controller.replaceMediaItem(index, item.buildUpon().setMediaMetadata(updatedMetadata).build())
            YosDiagnostics.log(
                "LYRIC_ADD", "id" to item.mediaId, "gen" to generation,
                "len" to json?.length, "title" to item.mediaMetadata.title,
                "artist" to item.mediaMetadata.artist
            )
            // 500ms 后回读条目 extras：确认 app 侧链路完整（锁屏仍无词时区分
            // "应用没发"与"系统侧拦截"的关键证据）
            val verifyId = item.mediaId
            lyricHandler.postDelayed({
                runCatching {
                    val c = mediaControl ?: return@postDelayed
                    val cur = c.currentMediaItem ?: return@postDelayed
                    val ex = cur.mediaMetadata.extras
                    val v = ex?.getString(LyricInfoSerializer.EXTRAS_KEY)
                    YosDiagnostics.log(
                        "LYRIC_VERIFY", "id" to cur.mediaId, "match" to (cur.mediaId == verifyId),
                        "ok" to (v != null), "len" to (v?.length ?: 0), "extrasKeys" to ex?.keySet()?.size
                    )
                }
            }, 500)
            return true
        } catch (e: Exception) {
            YosDiagnostics.log("LYRIC_ERR", "id" to mediaId, "why" to e.toString())
            return false
        }
    }

    private fun publishLyricPayload(mediaId: String, payload: LyricLoadCoordinator.Payload) {
        MediaViewModelObject.lrcEntries.value = payload.entries
        MediaViewModelObject.otherSideForLines.clear()
        MediaViewModelObject.otherSideForLines.addAll(payload.otherSideForLines)
        MediaViewModelObject.lyricMediaId.value = mediaId
        MediaViewModelObject.lyricLoading.value = false
        publishSuperIslandLyric(mediaId, payload.entries)
    }

    private fun clearLyricFor(mediaId: String) {
        MediaViewModelObject.lrcEntries.value = emptyList()
        MediaViewModelObject.otherSideForLines.clear()
        MediaViewModelObject.lyricMediaId.value = mediaId
        MediaViewModelObject.lyricLoading.value = true
    }

    /** Loads online lyrics once per mediaId and publishes only the current request. */
    private fun ensureOnlineLyric(music: YosMediaItem) {
        val mediaId = music.mediaId ?: return
        if (!isOnlineMedia(mediaId)) return

        when (val selection = lyricCoordinator.select(mediaId)) {
            is LyricLoadCoordinator.Selection.Cached -> {
                publishLyricPayload(mediaId, selection.payload)
                MainViewModelObject.syncLyricIndex.intValue = -1
            }

            is LyricLoadCoordinator.Selection.InFlight -> {
                if (MediaViewModelObject.lyricMediaId.value != mediaId) {
                    clearLyricFor(mediaId)
                }
            }

            is LyricLoadCoordinator.Selection.Load -> {
                clearLyricFor(mediaId)
                CoroutineScope(Dispatchers.IO).launch {
                    val request = selection.request
                        val result = runCatching {
                            yos.music.player.data.repositories.KugouRepository.getLyrics(
                                mediaId.removePrefix("kugou-online-")
                            ).getOrThrow()
                        }
                        result.onSuccess { lyrics ->
                                    val parsed = synchronized(lyricParseLock) {
                                        val krcResult = lyrics.krc?.let { krc ->
                                            runCatching {
                                                YosLrcFactory().formatKrcEntries(krc, lyrics.translation)
                                            }
                                        }
                                        val krcEntries = krcResult?.getOrNull()
                                        if (krcEntries != null && krcEntries.isNotEmpty()) {
                                            println("在线歌词使用 KRC 逐字 mediaId=$mediaId lines=${krcEntries.size}")
                                            LyricLoadCoordinator.Payload(
                                                entries = krcEntries,
                                                otherSideForLines = MediaViewModelObject.otherSideForLines.toList()
                                            )
                                        } else {
                                            val reason = when {
                                                lyrics.krc == null -> "krc_missing"
                                                krcResult?.exceptionOrNull() != null ->
                                                    "krc_parse_error=${krcResult.exceptionOrNull()?.javaClass?.simpleName}"
                                                else -> "krc_empty"
                                            }
                                            println("在线歌词回退 LRC mediaId=$mediaId reason=$reason")
                                            val lrcEntries = YosLrcFactory().formatLrcEntries(
                                                lyrics.lrc,
                                                lyrics.translation
                                            )
                                            LyricLoadCoordinator.Payload(
                                                entries = lrcEntries,
                                                otherSideForLines = MediaViewModelObject.otherSideForLines.toList()
                                            )
                                        }
                                    }
                            val current = lyricCoordinator.complete(request, parsed)
                            if (current && musicPlaying.value?.mediaId == mediaId) {
                                publishLyricPayload(mediaId, parsed)
                                MainViewModelObject.syncLyricIndex.intValue = -1
                            }
                            println("在线歌词加载完成 mediaId=$mediaId lines=${parsed.entries.size} current=$current")
                        }.onFailure { error ->
                            val current = lyricCoordinator.fail(request)
                            if (current && musicPlaying.value?.mediaId == mediaId) {
                                MediaViewModelObject.lyricLoading.value = false
                            }
                            println("在线歌词加载失败 mediaId=$mediaId current=$current: ${error.message}")
                        }
                }
            }
        }
    }

    private fun refresh(music: YosMediaItem) {
        refreshJob?.cancel()
        refreshJob = Job()

        val scope = CoroutineScope(Dispatchers.IO + refreshJob!!)

        musicPlaying.value = music
        println("prepare 刷新UI状态 $music")

        scope.launch {
            println(musicPlaying.value)
        }

        scope.launch {
            // val bitmap: MutableState<String?> = MediaViewModelObject.bitmap
            // bitmap.value = music.thumb
            MediaViewModelObject.bitmap.value = music.thumb
        }

        scope.launch {
            MainViewModelObject.syncLyricIndex.intValue = -1
        }

        // 在线歌词由协调器加载；切歌时取消旧请求，但成功结果仍按 mediaId 缓存。
        scope.launch {
            ensureOnlineLyric(music)
        }
    }
}

class YosPlaybackService : MediaSessionService() {
    private val notificationID = 1145
    private val channelID = "YosMediaControllerChannelV2"

    // ---------- Crossfade 扶正（promote）状态 ----------
    // currentPlayer 是音频真相（真 ExoPlayer 实例），forwardingPlayer 是 MediaSession
    // 绑定的会话外壳。副 player 扶正时两者整体换引用：音频流不断，只换管理层。
    private var currentPlayer: ExoPlayer? = null
    private var forwardingPlayer: ForwardingPlayer? = null
    private var sessionAudioAttributes: AudioAttributes? = null

    /** 应用侧大监听器：onCreate 里创建并挂到外壳上，扶正时随外壳迁移到新 player。 */
    private var playbackListener: Player.Listener? = null

    /** 播放历史上报去重：服务生命周期内已报过的 mediaId 集合（同一首不重复报）。 */
    private val playHistoryUploaded = mutableSetOf<String>()

    /** 听歌时长心跳循环：随服务生命周期启停（防重建叠加导致计时翻倍）。 */
    private var gradeHeartbeatJob: Job? = null

    private val shuffleMode = "shuffle_mode"
    private val repeatMode = "repeat_mode"

    companion object {
        private const val FLAG_ALWAYS_SHOW_TICKER = 0x1000000
        private const val FLAG_ONLY_UPDATE_TICKER = 0x2000000
    }

    @OptIn(UnstableApi::class)
    private fun setCustomButtons(player: ForwardingPlayer) {
        if (SettingsLibrary.NotificationEnableIcon) {
            val useSmallerIcon = SettingsLibrary.NotificationSmallerIcon

            val shuffleButtonIcon =
                if (player.shuffleModeEnabled) {
                    if (useSmallerIcon) R.drawable.ic_mini_shuffle else R.drawable.ic_shuffle
                } else {
                    if (useSmallerIcon) R.drawable.ic_mini_shuffle_off else R.drawable.ic_shuffle_off
                }
            val shuffleButton = CommandButton.Builder()
                .setIconResId(shuffleButtonIcon)
                .setDisplayName(shuffleMode)
                .setSessionCommand(SessionCommand(shuffleMode, Bundle()))
                .build()

            val repeatButtonIcon =
                when (player.repeatMode) {
                    REPEAT_MODE_ONE -> if (useSmallerIcon) R.drawable.ic_mini_repeat_one else R.drawable.ic_repeat_one
                    REPEAT_MODE_ALL -> if (useSmallerIcon) R.drawable.ic_mini_repeat else R.drawable.ic_repeat
                    else -> if (useSmallerIcon) R.drawable.ic_mini_repeat_off else R.drawable.ic_repeat_off
                }
            val repeatButton = CommandButton.Builder()
                .setIconResId(repeatButtonIcon)
                .setDisplayName(repeatMode)
                .setSessionCommand(SessionCommand(repeatMode, Bundle()))
                .build()

            mediaSession?.setCustomLayout(ImmutableList.of(shuffleButton, repeatButton))
        } else {
            mediaSession?.setCustomLayout(emptyList())
        }
    }

    fun setCustomButtons(player: MediaController) {
        if (SettingsLibrary.NotificationEnableIcon) {
            val useSmallerIcon = SettingsLibrary.NotificationSmallerIcon

            val shuffleButtonIcon =
                if (player.shuffleModeEnabled) {
                    if (useSmallerIcon) R.drawable.ic_mini_shuffle else R.drawable.ic_shuffle
                } else {
                    if (useSmallerIcon) R.drawable.ic_mini_shuffle_off else R.drawable.ic_shuffle_off
                }
            val shuffleButton = CommandButton.Builder()
                .setIconResId(shuffleButtonIcon)
                .setDisplayName(shuffleMode)
                .setSessionCommand(SessionCommand(shuffleMode, Bundle()))
                .build()

            val repeatButtonIcon =
                when (player.repeatMode) {
                    REPEAT_MODE_ONE -> if (useSmallerIcon) R.drawable.ic_mini_repeat_one else R.drawable.ic_repeat_one
                    REPEAT_MODE_ALL -> if (useSmallerIcon) R.drawable.ic_mini_repeat else R.drawable.ic_repeat
                    else -> if (useSmallerIcon) R.drawable.ic_mini_repeat_off else R.drawable.ic_repeat_off
                }
            val repeatButton = CommandButton.Builder()
                .setIconResId(repeatButtonIcon)
                .setDisplayName(repeatMode)
                .setSessionCommand(SessionCommand(repeatMode, Bundle()))
                .build()

            mediaSession?.setCustomLayout(ImmutableList.of(shuffleButton, repeatButton))
        } else {
            mediaSession?.setCustomLayout(emptyList())
        }
    }

    /*fun sendLyricTicker(lyric: String) {
        val notification = NotificationCompat.Builder(this, channelID).apply {
            setTicker(lyric)
            setSmallIcon(R.drawable.flamingo_icon_notification)
        }.build().also {
            it.extras.putInt("ticker_icon", R.drawable.flamingo_icon_notification)
            it.extras.putBoolean("ticker_icon_switch", true)
            it.flags = it.flags.or(FLAG_ALWAYS_SHOW_TICKER).or(FLAG_ONLY_UPDATE_TICKER)
        }

        if (ActivityCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        NotificationManagerCompat.from(this).notify(notificationID, notification)
    }*/

    private var saveJob: Job? = null

    fun saveDataWithDelay() {
        saveJob?.cancel()
        saveJob = CoroutineScope(Dispatchers.IO).launch {
            delay(200)
            withContext(Dispatchers.Main) {
                saveData()
            }
        }
    }

    private fun saveData() {
        println("持久化 尝试保存播放状态")
        if (musicPlaying.value != null && mediaControl != null) {
            println("持久化 保存播放状态")
            MusicLibrary.updatePlayStatus(
                PlayStatus(
                    musicPlaying.value,
                    mediaControl?.currentPosition ?: 0,
                    mediaControl?.shuffleModeEnabled ?: false,
                    mediaControl?.repeatMode ?: REPEAT_MODE_ALL
                )
            )
        }
    }

    @OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        // 音质切换需要读偏好与网络形态：服务创建时注入应用级 Context（幂等）
        yos.music.player.code.MediaController.appContext = applicationContext
        yos.music.player.data.repositories.NetworkObserver.init(applicationContext)
        val audioAttributes: AudioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        sessionAudioAttributes = audioAttributes
        // 主/副 player 共用这一套解码配置：过渡窗口里新曲必须与正在播的这曲走同一条解码
        // 路径，否则"切个歌顺便换了渲染器"会听出音色差
        fun renderFactory(): DefaultRenderersFactory =
            YosRenderFactory(this)
                .setEnableAudioFloatOutput(
                    SettingsLibrary.AudioFloatOutput
                )
                .setEnableDecoderFallback(true)
                .setEnableAudioTrackPlaybackParams(
                    SettingsLibrary.HardwareAudioTrackPlayBackParams
                )
                .setExtensionRendererMode(
                    when (SettingsLibrary.Codec) {
                        "Auto" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                        "System" -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
                        else -> DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON
                    }
                )
        val player = ExoPlayer.Builder(this, renderFactory())
            .setAudioAttributes(
                audioAttributes,
                SettingsLibrary.AudioAttributes
            )
            .setHandleAudioBecomingNoisy(true)
            // 在线歌曲惰性 URL 解析：占位符 URI 在播放前才解析真实 CDN URL（本地歌曲透传不变）
            .setMediaSourceFactory(buildKugouMediaSourceFactory(this))
            .build()
        currentPlayer = player

        // ---------- 歌曲平滑过渡（Crossfade） ----------
        // 副 player 只在过渡窗口里发声，且不申请音频焦点（handleAudioFocus=false：它一旦去
        // 抢焦点，主 player 会被抑制，重叠就变成"两边都没声"），也不接 MediaSession——
        // 通知栏、锁屏、歌词、音质逻辑全部仍以主 player 为准。
        // 淡化结束时不做任何 seek：副 player 携带完整队列克隆被就地扶正（promoteSecondary），
        // 音频流从头到尾同一条 AudioTrack，接缝在音频层面不存在。
        CrossfadeExo.attach(
            player,
            secondaryBuilder = {
                ExoPlayer.Builder(this, renderFactory())
                    .setAudioAttributes(audioAttributes, false)
                    // handleAudioBecomingNoisy 是 builder-only 配置，出生就设 true：过渡期拔
                    // 耳机两个 player 一起暂停，crossfade 正常 abort，行为与单 player 一致
                    .setHandleAudioBecomingNoisy(true)
                    .setMediaSourceFactory(buildKugouMediaSourceFactory(this))
                    .build()
            },
            onPromote = ::promoteSecondary,
        )
        CrossfadeExo.start()

        // 恢复持久化的播放倍速（服务每次创建/进程重启后仍保持用户选择）
        if (SettingsLibrary.PlaybackSpeed != 1.0) {
            player.setPlaybackSpeed(SettingsLibrary.PlaybackSpeed.toFloat())
        }

        forwardingPlayer = makeForwardingPlayer(player)
        // 在线跳歌的计数/退避不在这里：挂在 listener 字段上会在 Crossfade 扶正时被连带迁移，
        // 且"只在 READY 清零"使其在 IDLE 错误态下永远回不了零（详见 OnlineSkipState）
        val listener = object : Player.Listener {

                override fun onTracksChanged(tracks: Tracks) {
                    // 抽到 handleTracksChanged：扶正后副 player 不会再有 tracks 事件，
                    // promote 事务需要手动补发同一套处理
                    currentPlayer?.let { runCatching { handleTracksChanged(it, tracks) } }
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    // 抽到 handleTrackSwitched：扶正后副 player 不会再有 transition 事件，
                    // promote 事务需要手动补发同一套处理
                    handleTrackSwitched(mediaItem, reason)
                    println("更新 $mediaItem")
                    super.onMediaItemTransition(mediaItem, reason)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    super.onPlaybackStateChanged(playbackState)
                    val p = currentPlayer
                    YosDiagnostics.log(
                        "STATE",
                        "s" to YosDiagnostics.stateLabel(playbackState),
                        "pos" to p?.currentPosition,
                        "buf" to p?.bufferedPosition
                    )
                    if (playbackState == Player.STATE_READY) {
                        // 真开播了：上一轮的失败不再相关
                        OnlineSkipState.reset("ready")
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    super.onPlayerError(error)
                    val p = currentPlayer ?: return
                    // 先把完整现场落盘（错误码 + 因果链头 + 媒体），再走原有的跳歌策略：
                    // 下面那些 println 在 release 全部会被 R8 剥掉，当初就是靠它们查不出东西的
                    val errorCause = generateSequence<Throwable>(error) { it.cause }
                        .joinToString("<") { it.javaClass.simpleName }
                    YosDiagnostics.log(
                        "PLAY_ERROR",
                        "code" to error.errorCodeName,
                        "mediaId" to p.currentMediaItem?.mediaId,
                        "chain" to errorCause,
                        "buf" to p.bufferedPosition
                    )
                    // 在线歌曲 URL 解析/加载失败：按失败**类型**分别处理——逐首拦截时
                    // 跳下一首是对的，整账号鉴权失效时跳多少首都一样，应挂起退避而不是数满 10 首。
                    val failedItem = p.currentMediaItem
                    if (failedItem?.mediaId?.startsWith("kugou-online-") == true) {
                        // 错误态下 hasNextMediaItem() 恒 false，改用队列索引判断是否还有下一首
                        val hasNext = if (p.repeatMode == REPEAT_MODE_ALL) {
                            p.mediaItemCount > 1
                        } else {
                            p.currentMediaItemIndex < p.mediaItemCount - 1
                        }
                        val kind = classifyOnlineFailure(error)
                        val reason = userReasonOf(error, kind)
                        // 确凿拒播（版权/付费拦截）：把结论写进可播性表，返回列表时该行置灰。
                        // 鉴权/网络/超时不写——它们不是“这首歌不可播”的证据。
                        if (kind == OnlineFailureKind.BLOCKED) {
                            SongPlayabilityStore.markBlocked(
                                failedItem.mediaId?.removePrefix("kugou-online-"),
                                reason
                            )
                        }
                        when (OnlineSkipState.decide(kind, hasNext, KugouRepository.isAuthInvalidWindow())) {
                            OnlineSkipPolicy.Decision.SKIP_NEXT ->
                                skipPastUnplayable(failedItem, reason)

                            OnlineSkipPolicy.Decision.HOLD_AND_RETRY ->
                                holdForRetry(reason)

                            OnlineSkipPolicy.Decision.STOP ->
                                stopAutoSkip(reason)
                        }
                    }
                }

                /*override fun onIsPlayingChanged(isPlaying: Boolean) {
                    saveData()
                    super.onIsPlayingChanged(isPlaying)
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    saveData()
                    super.onRepeatModeChanged(repeatMode)
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    saveData()
                    super.onShuffleModeEnabledChanged(shuffleModeEnabled)
                }

                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState != Player.STATE_BUFFERING) {
                        saveData()
                    }
                    super.onPlaybackStateChanged(playbackState)
                }*/

                override fun onRepeatModeChanged(repeatMode: Int) {
                    super.onRepeatModeChanged(repeatMode)
                    // 循环/随机变化会让副 player 克隆的队列语义失真：过渡窗口内直接作废
                    CrossfadeExo.abort("repeat_mode")
                    MediaViewModelObject.repeatMode.intValue = repeatMode
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    super.onShuffleModeEnabledChanged(shuffleModeEnabled)
                    CrossfadeExo.abort("shuffle_mode")
                    MediaViewModelObject.shuffleModeEnabled.value = shuffleModeEnabled
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    super.onIsPlayingChanged(isPlaying)
                    MediaViewModelObject.isPlaying.value = isPlaying
                    YosDiagnostics.log(
                        "ISPLAYING",
                        "v" to isPlaying,
                        "state" to YosDiagnostics.stateLabel(currentPlayer?.playbackState ?: Player.STATE_IDLE),
                        "sup" to (currentPlayer?.playbackSuppressionReason ?: -1)
                    )
                }

                /**
                 * 焦点被瞬态抑制时 playWhenReady 仍为 true 但不进声——正是"进度在走却没声音"
                 * 的形态。不记这一条就分不开"被别的 app 抢焦点"与"引擎自杀"。
                 */
                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                    super.onPlaybackSuppressionReasonChanged(playbackSuppressionReason)
                    YosDiagnostics.log("SUPPRESSION", "r" to playbackSuppressionReason)
                }

                override fun onEvents(player: Player, events: Player.Events) {
                    super.onEvents(player, events)

                    if (events.containsAny(
                            Player.EVENT_PLAY_WHEN_READY_CHANGED,
                            Player.EVENT_PLAYBACK_STATE_CHANGED,
                            Player.EVENT_MEDIA_ITEM_TRANSITION,
                            Player.EVENT_REPEAT_MODE_CHANGED,
                            Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED
                        )
                    ) {
                        saveDataWithDelay()
                    }
                }

            }
        playbackListener = listener
        forwardingPlayer!!.addListener(listener)

        // ---------- 听歌时长心跳上报（对照 md3Music） ----------
        // 每 30s 喂一次 ListeningGradeTracker，只有「正在播在线歌」才累计。
        // 刻意不跨线程摸 currentPlayer，改用主线程 Listener 回流的状态镜像
        // （MediaViewModelObject.isPlaying + musicPlaying）判定在线播放态。
        // 循环挂服务生命周期：服务销毁必须停，否则系统回收重建后会叠加第二个
        // 循环，同一秒被计两次、时长翻倍多报。
        gradeHeartbeatJob?.cancel()
        gradeHeartbeatJob = CoroutineScope(Dispatchers.IO).launch {
            while (true) {
                delay(30_000L)
                runCatching {
                    val online =
                        musicPlaying.value?.mediaId?.startsWith("kugou-online-") == true
                    ListeningGradeTracker.onHeartbeat(
                        intervalSec = 30,
                        onlinePlaying = online && MediaViewModelObject.isPlaying.value
                    )
                }
            }
        }

        /*val repeatButton = CommandButton.Builder()
            .setIconResId(android.R.drawable.ic_media_rew)
            .setSessionCommand(SessionCommand(SAVE_TO_FAVORITES, Bundle()))
            .build()*/

        @Suppress("DEPRECATION")
        class YosMediaSessionCallback : MediaSession.Callback {
            // ——诊断探针：外部 controller（通知栏/语音助手/车机/蓝牙耳机）能直接
            // stop/pause/改队列，是"无错静默停播"的头号盲区；全部只记日志不改行为。
            // 与 TLMUT/SRC 探针合用：SVC_CMD 缺席而 TLMUT 在场 → app 内路径；
            // SVC_CMD 在场 → 外部命令，from 直接点名发包应用。
            override fun onConnect(
                session: MediaSession,
                controller: MediaSession.ControllerInfo
            ): MediaSession.ConnectionResult {
                YosDiagnostics.log("SVC_CMD", "cmd" to "connect", "from" to controller.packageName)
                val sessionCommands =
                    MediaSession.ConnectionResult.DEFAULT_SESSION_COMMANDS.buildUpon()
                        .add(SessionCommand(shuffleMode, Bundle.EMPTY))
                        .add(SessionCommand(repeatMode, Bundle.EMPTY))
                        .build()
                return MediaSession.ConnectionResult.AcceptedResultBuilder(session)
                    .setAvailableSessionCommands(sessionCommands)
                    .build()
            }

            override fun onSetMediaItems(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>,
                startIndex: Int,
                startPositionMs: Long
            ): ListenableFuture<MediaSession.MediaItemsWithStartPosition> {
                YosDiagnostics.log(
                    "SVC_CMD", "cmd" to "setMediaItems",
                    "from" to controller.packageName, "n" to mediaItems.size
                )
                return super.onSetMediaItems(session, controller, mediaItems, startIndex, startPositionMs)
            }

            override fun onAddMediaItems(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                mediaItems: List<MediaItem>
            ): ListenableFuture<List<MediaItem>> {
                YosDiagnostics.log(
                    "SVC_CMD", "cmd" to "addMediaItems",
                    "from" to controller.packageName, "n" to mediaItems.size
                )
                return super.onAddMediaItems(session, controller, mediaItems)
            }

            // 注：1.4.0 的 MediaSession.Callback 没有 onPlay/onPause/onStop 钩子，
            // 传输类命令不经过 callback；但它最终必打到底层 player 状态机 →
            // STATE 探针的"IDLE 且无 TLMUT/SRC/PLAY_ERROR 邻近行"形态即可判外源 stop。

            override fun onCustomCommand(
                session: MediaSession,
                controller: MediaSession.ControllerInfo,
                customCommand: SessionCommand,
                args: Bundle
            ): ListenableFuture<SessionResult> {
                val p = currentPlayer
                if (customCommand.customAction == shuffleMode) {
                    if (p != null) p.shuffleModeEnabled = !p.shuffleModeEnabled
                    forwardingPlayer?.let { setCustomButtons(it) }
                } else if (customCommand.customAction == repeatMode && p != null) {
                    when (p.repeatMode) {
                        REPEAT_MODE_OFF -> {
                            p.repeatMode = REPEAT_MODE_ALL
                        }

                        REPEAT_MODE_ALL -> {
                            p.repeatMode = REPEAT_MODE_ONE
                        }

                        else -> {
                            p.repeatMode = REPEAT_MODE_OFF
                        }
                    }
                    forwardingPlayer?.let { setCustomButtons(it) }
                }
                return Futures.immediateFuture(
                    SessionResult(SessionResult.RESULT_SUCCESS)
                )
            }
            /*override fun onMediaButtonEvent(
                session: MediaSession,
                controllerInfo: MediaSession.ControllerInfo,
                intent: Intent
            ): Boolean {
                val keyEvent = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
                if (keyEvent != null) {
                    when (keyEvent.keyCode) {
                        KeyEvent.KEYCODE_MEDIA_PLAY -> {
                            player.fadePlay()
                        }

                        KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                            player.fadePause()
                        }

                        KeyEvent.KEYCODE_MEDIA_NEXT -> {
                            player.seekToNextMediaItem()
                        }

                        KeyEvent.KEYCODE_MEDIA_PREVIOUS -> {
                            player.seekToPreviousMediaItem()
                        }
                    }
                }
                return super.onMediaButtonEvent(session, controllerInfo, intent)
            }*/
        }

        mediaSession =
            MediaSession
                .Builder(this, forwardingPlayer!!)
                .setSessionActivity(
                    PendingIntent.getActivity(
                        this,
                        0,
                        Intent(this, MainActivity::class.java),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    )
                )
                .setShowPlayButtonIfPlaybackIsSuppressed(true)
                .setCallback(YosMediaSessionCallback())
                .build()
        /*
                val mediaButtonReceiver = ComponentName(this, MediaButtonReceiver::class.java)
                val mediaButtonIntent = Intent(Intent.ACTION_MEDIA_BUTTON)
                mediaButtonIntent.component = mediaButtonReceiver
                val pendingIntent = PendingIntent.getBroadcast(this, 0, mediaButtonIntent, PendingIntent.FLAG_UPDATE_CURRENT)
                mediaSession.setMediaButtonReceiver(pendingIntent)
        */

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name = "Sonify Media Control"
            val descriptionText = "Sonify Media Control Notification Channel"
            // 渠道重要性创建后不可改：熄屏播放中断的根因是 OEM 省电冻结，无声（NONE）渠道
            // 会让前台服务存在感极低；V2 渠道用 LOW（不发声但正常展示），并清理旧渠道。
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(channelID, name, importance).apply {
                description = descriptionText
                enableVibration(false)
                vibrationPattern = longArrayOf(0)
                setSound(null, null)
            }
            val notificationManager: NotificationManager =
                ContextCompat.getSystemService(
                    this,
                    NotificationManager::class.java
                ) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            notificationManager.deleteNotificationChannel("YosMediaControllerChannel")
        }

        val notificationProvider =
            DefaultMediaNotificationProvider.Builder(this)
                .setNotificationId(notificationID)
                .setChannelId(channelID)
                .build()

        /*DefaultMediaNotificationProvider(
            this,
            {
                notificationID
            },
            channelID,
            notificationID
        )*/

        notificationProvider.setSmallIcon(R.drawable.flamingo_icon_notification)

        this.setMediaNotificationProvider(notificationProvider)

        setCustomButtons(forwardingPlayer!!)

        YosDiagnostics.log("SVC_CREATE", "player" to player.javaClass.simpleName)
        // 心跳读原始 player（真相），不读 ForwardingPlayer：后者的 isPlaying() 被重写成
        // FadeExo.targetStatus != 0，拿它做心跳会把"谎报的在播"当成事实
        YosDiagnostics.attachPlayback(this, player)

        // ---------- 在线失败的退避重备动作 ----------
        // 锁存（HOLD_AND_RETRY）到点只做一件事：把当前条目重新 prepare。不改索引、不改队列，
        // 所以用户看到的还是同一首歌；上游恢复（真机那次 illegal_key 持续 5 分钟后自愈）即自动续播，
        // 不需要用户杀进程重开或回列表重新点歌。
        OnlineSkipState.attach {
            val fp = forwardingPlayer
            when {
                fp == null -> YosDiagnostics.log("SKIP_REARM_SKIP", "why" to "no_player")
                // 已经自己在播了（用户动过、或上一轮重试已成功）：什么都不做
                fp.playbackState == Player.STATE_READY && fp.isPlaying ->
                    YosDiagnostics.log("SKIP_REARM_SKIP", "why" to "already_playing")

                else -> {
                    CrossfadeExo.abort("skip_rearm")
                    runCatching {
                        fp.prepare()
                        fp.play()
                    }
                }
            }
        }

        onServiceRunning()
    }

    /**
     * 把一次播放失败归到 [OnlineFailureKind]。顺序有意为之：
     *  1. 鉴权失效优先——它是整账号层面的，误判成"网络抖动"就会退避重试、把同一个 502 刷成屏；
     *  2. 取消/中断排在网络之前——切歌时 ExoPlayer 会中断 Loader 线程，那层 IOException 不是
     *     失败证据（KugouResolvingDataSource 对此已专门区分，这里不能又把它算回来）。
     */
    private fun classifyOnlineFailure(error: PlaybackException): OnlineFailureKind {
        val chain = generateSequence<Throwable>(error) { it.cause }.toList()
        return when {
            chain.any { it is KugouAuthInvalidException } -> OnlineFailureKind.AUTH_INVALID
            chain.any { it is KugouRepository.KugouPlayBlockedException } -> OnlineFailureKind.BLOCKED
            chain.any {
                it is InterruptedException ||
                    (it is kotlinx.coroutines.CancellationException &&
                            it !is kotlinx.coroutines.TimeoutCancellationException)
            } -> OnlineFailureKind.OTHER
            chain.any {
                it is java.net.SocketTimeoutException ||
                    it is java.io.InterruptedIOException ||
                    it is kotlinx.coroutines.TimeoutCancellationException
            } -> OnlineFailureKind.TIMEOUT
            chain.any { it is java.io.IOException } -> OnlineFailureKind.NETWORK
            else -> OnlineFailureKind.OTHER
        }
    }

    /** 用户可见原因：鉴权失效必须点名"重新登录"，不能泛化成"播放失败"。 */
    private fun userReasonOf(error: PlaybackException, kind: OnlineFailureKind): String =
        if (kind == OnlineFailureKind.AUTH_INVALID) {
            "登录态失效，请重新登录账号"
        } else {
            generateSequence<Throwable>(error) { it.cause }
                .filterIsInstance<KugouRepository.KugouPlayBlockedException>()
                .firstOrNull()?.userReason ?: "播放失败"
        }

    /** 跳下一首：非阻塞告知歌名与原因；错误态下光 seek 不触发加载，必须 prepare+play。 */
    private fun skipPastUnplayable(failedItem: MediaItem, reason: String) {
        YosDiagnostics.log("SKIP_TO_NEXT", "attempt" to OnlineSkipState.failureCount())
        // 失败跳歌属于"不是自然播完"：副 player 里备着的可能正是这首坏曲
        CrossfadeExo.abort("error_skip")
        val title = failedItem.mediaMetadata.title?.toString()?.takeIf { it.isNotEmpty() }
        val toastMsg =
            if (title != null) "《$title》$reason，已跳到下一首" else "$reason，已跳到下一首"
        Toast.makeText(this, toastMsg, Toast.LENGTH_SHORT).show()
        forwardingPlayer?.seekToNextMediaItem()
        forwardingPlayer?.prepare()
        forwardingPlayer?.play()
    }

    /**
     * 挂起等退避：不改索引、不改队列（重备由 [OnlineSkipState.attach] 注入的回调执行）。
     * 同时作废"播完再暂停"检查点——重备的 prepare() 会产生一次 REPEAT 过渡，而那个检查点
     * 正是按 AUTO/REPEAT 判"播完了"的，不作废就会把刚自愈回来的歌暂停掉。
     */
    private fun holdForRetry(reason: String) {
        runCatching { YosControllerObject.stopSleepEndWatcher() }
        Toast.makeText(this, "连续无法播放：$reason，稍后自动重试", Toast.LENGTH_SHORT).show()
    }

    /**
     * 彻底停住（没有可跳目标，或退避轮次用尽）。事件名沿用 SKIP_LIMIT：它与"静默停一整晚"
     * 是同一个用户可感知现象，历史报告全部靠它检索，改名等于把检索面抹掉。
     */
    private fun stopAutoSkip(reason: String) {
        YosDiagnostics.log(
            "SKIP_LIMIT",
            "attempt" to OnlineSkipState.failureCount(),
            "round" to OnlineSkipState.retryRounds(),
            "why" to reason
        )
        Toast.makeText(
            this,
            "连续多首无法播放（$reason），已停止自动跳过",
            Toast.LENGTH_SHORT
        ).show()
    }

    /**
     * 会话外壳工厂：play/pause 走淡入淡出，isPlaying 用 FadeExo 的目标态平滑系统亮度图标，
     * stop/prepare 挂诊断探针。扶正时对副 player 重建一个同等外壳。
     */
    private fun makeForwardingPlayer(p: ExoPlayer): ForwardingPlayer =
        object : ForwardingPlayer(p) {
            override fun play() {
                p.fadePlay()
            }

            // ——诊断探针（只记录不改行为）——
            // stop()/prepare() 是能把"播到一半、缓冲充足"的播放器直接打成 IDLE 的
            // 仅有两个常规入口（真错误会另有 PLAY_ERROR）。会话外部 controller（通知
            // 栏/语音/车机/耳机）与 app 内音质重建、队列排序最终都落到这里；
            // 归因靠邻近的 SRC/TLMUT 行，缺省即"外源"。排查"无错静默停播"的唯一目击点。
            override fun stop() {
                YosDiagnostics.log("TLMUT", "op" to "stop", "th" to Thread.currentThread().name)
                super.stop()
            }

            override fun prepare() {
                YosDiagnostics.log("TLMUT", "op" to "prepare", "th" to Thread.currentThread().name)
                super.prepare()
            }

            override fun pause() {
                p.fadePause()
            }

            /**
             * 200ms 淡入淡出期间 p.isPlaying() 会先假 false，通知栏/系统亮度图标跟着抖，
             * 所以这里拿 FadeExo 的目标态顶着。
             *
             * 但引擎进了 IDLE（source error、被 stop）就必须报真实态：否则界面一直显示"在播"
             * 而实际无声，用户完全看不出已经停了——真机那次锁死就是靠 STATE_LIE
             * real=false reported=true 连报几十分钟才看出来，而用户只能杀进程重开。
             */
            override fun isPlaying(): Boolean =
                FadeExo.targetStatus != 0 && p.playbackState != Player.STATE_IDLE
        }

    /**
     * Crossfade 扶正事务（由 CrossfadeExo 在 ramp 结束时回调，主线程）：
     * 把仍在发声的副 player 就地升为主 player。音频流全程不断——无 seek、无换音源，
     * 换的只是会话绑定/监听器/焦点，接缝在音频层面不存在。
     *
     * 契约：setPlayer（会话重绑）之前的步骤失败要抛出，CrossfadeExo 会回退硬切；
     * 会话重绑之后绝不抛出（不可回退点），收尾步骤各自 runCatching。
     */
    private fun promoteSecondary(secondary: ExoPlayer) {
        val old = currentPlayer ?: throw IllegalStateException("promote without primary")
        val attrs = sessionAudioAttributes ?: throw IllegalStateException("promote without attrs")

        // ① 摘除旧 player 上的应用监听器：接下来焦点交接会让它暂停，不能再触发 UI 事件
        val listener = playbackListener
        if (listener != null) forwardingPlayer?.removeListener(listener)
        // ② 焦点交接：副 player 预取期不抢焦点，此刻以主 player 身份接管
        //    （旧 player 收到焦点丢失而暂停——它已无人监听、音量为 0、即将释放）
        secondary.setAudioAttributes(attrs, true)
        secondary.volume = 1f
        // ③ 重建会话外壳并迁移监听器/诊断心跳
        val newForwarding = makeForwardingPlayer(secondary)
        if (listener != null) newForwarding.addListener(listener)
        YosDiagnostics.attachPlayback(this, secondary)
        // ④ 会话重绑：从此刻起 controller/通知栏/UI 全部跟随副 player —— 不可回退点
        val session = mediaSession ?: throw IllegalStateException("promote without session")
        session.setPlayer(newForwarding)
        forwardingPlayer = newForwarding
        currentPlayer = secondary
        setCustomButtons(newForwarding)
        // ⑤ 收尾：释放旧 player；补发换曲事件（副 player 本来就在播，不会再有 transition/tracks
        //    通知，歌词/最近播放/音质探测/封面预载必须手动补跑，且顺序与自然换曲一致）
        runCatching { old.release() }
        runCatching { syncUiSnapshot(secondary) }
        runCatching {
            handleTrackSwitched(secondary.currentMediaItem, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
            handleTracksChanged(secondary, secondary.currentTracks)
        }
        YosDiagnostics.log("XFADE_SERVICE_PROMOTE", "idx" to secondary.currentMediaItemIndex)
    }

    /** 扶正后用新 player 的状态快照刷新 UI 全局态，防止迁移窗口内丢增量事件。 */
    private fun syncUiSnapshot(p: ExoPlayer) {
        MediaViewModelObject.isPlaying.value = p.isPlaying
        MediaViewModelObject.repeatMode.intValue = p.repeatMode
        MediaViewModelObject.shuffleModeEnabled.value = p.shuffleModeEnabled
    }

    /**
     * 换曲处理（onMediaItemTransition 的主体）：歌词重置、当前曲状态、最近播放、
     * 封面预载、在线档位探测。扶正时手动补调，与自然换曲走同一份代码。
     */
    private fun handleTrackSwitched(mediaItem: MediaItem?, transitionReason: Int) {
        YosDiagnostics.log(
            "TRANSITION",
            "mediaId" to mediaItem?.mediaId,
            // 直记 media3 原值：本版本的 reason 常量集与旧 ExoPlayer 不一致（没有
            // UNKNOWN/MANUAL），拿记忆里的表去翻译会把 auto/repeat 说成别的
            "reason" to transitionReason
        )
        // 换曲瞬间清掉上一首的实测规格：这两个值是全局态且只在 handleTracksChanged 里
        // 更新，不清就会在切歌窗口里拿旧曲的码率/采样率给新曲判档（闪一下别的值）。
        MediaViewModelObject.bitrate.intValue = 0
        MediaViewModelObject.samplingRate.intValue = 0
        mediaItem?.let { item ->
            // 发布条目不携带自有 extras（见 MusicLibrary.toMediaItem）：当前曲的完整
            // YosMediaItem（时长/日期等）按 mediaId 从队列快照取，快照缺失才退回重建值
            val yosItem = yos.music.player.code.MediaController.playingMusicList.value
                ?.firstOrNull { it.mediaId == item.mediaId }
                ?: item.toYosMediaItem()
            yos.music.player.code.MediaController.onCase(yosItem)
            MusicLibrary.recordRecentlyPlayed(yosItem)

            // 播放历史上报（对照 md3Music _maybeUploadPlayHistory）：在线歌开播即报一次，
            // mxid 取解析时登记的身份表（songIdentityFor），查不到就静默跳过；
            // best-effort，任何失败不影响播放。受「上传听歌时长」开关统一管。
            val switchedMediaId = item.mediaId
            if (switchedMediaId?.startsWith("kugou-online-") == true &&
                !playHistoryUploaded.contains(switchedMediaId) &&
                SettingsLibrary.UploadListeningDuration &&
                KugouApiService.isLoggedIn()
            ) {
                playHistoryUploaded.add(switchedMediaId)
                CoroutineScope(Dispatchers.IO).launch {
                    val hash = switchedMediaId.removePrefix("kugou-online-")
                    val mxid = KugouRepository.songIdentityFor(hash)?.mixsongId ?: 0L
                    if (mxid <= 0) {
                        Log.d("PLAYHIST_UPLOAD", "skip: no mxid for $hash")
                        return@launch
                    }
                    KugouApiService.getInstance()
                        .uploadPlayHistory(mxid, ot = System.currentTimeMillis() / 1000L)
                        .fold(
                            onSuccess = { json ->
                                Log.d(
                                    "PLAYHIST_UPLOAD",
                                    "mxid=$mxid status=${json.optInt("status")} error_code=${json.optInt("error_code")}"
                                )
                            },
                            onFailure = { e ->
                                Log.w("PLAYHIST_UPLOAD", "mxid=$mxid failed: ${e.message}")
                            }
                        )
                }
            }
        }
        currentPlayer?.let { yos.music.player.code.MediaController.preloadNextCover(it, applicationContext) }

        // 播放即探测：换曲后立刻把这首歌的档位阶梯问一轮（单调阶梯，通常一个
        // 请求就定完），结论写进能力表。用户打开面板时就能看到哪些档拿不到，
        // 而不是逐档点下去试探。本地文件无档位概念，跳过。
        mediaItem?.localConfiguration?.uri
            ?.takeIf { it.scheme == KugouRepository.PLACEHOLDER_SCHEME }
            ?.lastPathSegment?.let { probeHash ->
                CoroutineScope(Dispatchers.IO).launch {
                    KugouRepository.probeAllQualities(probeHash)
                }
            }
    }

    /** 轨道处理（onTracksChanged 的主体）：歌词加载 + 实测规格 + 在线档位交叉校验。 */
    private fun handleTracksChanged(p: Player, tracks: Tracks) {
        runCatching {
            if (tracks.isEmpty) return

            val mediaId = p.currentMediaItem?.mediaId
            val path = p.currentMediaItem?.uri

            val thisPath = path?.path

            println("质量分析 内置实现获取")
            var samplingRate = 0
            var bitrate = 0
            var haveJOC = false

            for (i in tracks.groups) {
                for (j in 0 until i.length) {
                    if (!i.isTrackSelected(j)) continue
                    val trackFormat = i.getTrackFormat(j)
                    samplingRate = trackFormat.sampleRate
                    bitrate = trackFormat.bitrate / 1000
                    haveJOC =
                        trackFormat.sampleMimeType?.contains("-joc", ignoreCase = true)
                            ?: false
                    break
                }
            }

            if (!mediaId.orEmpty().startsWith("kugou-online-")) {
                // 本地歌词：同名 .lrc 优先，读不到再取音频内嵌歌词（M4A ©lyr / MP3 USLT / FLAC）。
                // TagLib 解析是磁盘 IO，放 IO 线程；发布前回主线程校验 mediaId 防串歌。
                val durationMs = p.duration.takeIf { it > 0 } ?: 0L
                MediaViewModelObject.lrcEntries.value = emptyList()
                MediaViewModelObject.lyricLoading.value = true
                CoroutineScope(Dispatchers.IO).launch {
                    val lrcContent = runCatching {
                        val fromFile = thisPath?.let { fp ->
                            println("读取本地歌词：${fp.substringBeforeLast(".")}.lrc")
                            AudioMetadataUtils.loadLrcFile(
                                this@YosPlaybackService,
                                "${fp.substringBeforeLast(".")}.lrc"
                            )
                        }
                        val embedded = fromFile?.takeIf { it.isNotBlank() } ?: run {
                            if (thisPath != null) println("未找到同名 .lrc，尝试读取内嵌歌词")
                            thisPath?.let { AudioMetadataUtils.loadEmbeddedLyric(it) }
                        }
                        embedded
                    }.getOrNull().orEmpty()

                    // player.duration 在部分轨道上仍是 UNSET，用 TagLib 读文件时长兜底
                    val totalDurationMs = if (durationMs > 0) durationMs
                    else thisPath?.let { AudioMetadataUtils.getAudioLengthMs(it) } ?: 0L

                    val lrcEntries = YosLrcFactory()
                        .formatLrcEntriesWithFallback(lrcContent, totalDurationMs)

                    withContext(Dispatchers.Main) {
                        if (p.currentMediaItem?.mediaId == mediaId) {
                            MediaViewModelObject.lrcEntries.value = lrcEntries
                            MediaViewModelObject.lyricMediaId.value = mediaId
                            MediaViewModelObject.lyricLoading.value = false
                            YosControllerObject.publishSuperIslandLyric(mediaId, lrcEntries)
                        }
                    }
                }
            }

            if (thisPath != null && !mediaId.orEmpty().startsWith("kugou-online-")) {
                // MediaViewModelObject.isDolby.value = thisPath.endsWith(".m4a")
                // 改为 JOC 判断

                if (samplingRate == 0 || bitrate == 0) {
                    val audioInfo = AudioMetadataUtils.getQualityInfos(thisPath)
                    if (samplingRate == 0) {
                        samplingRate = audioInfo.second
                    } else {
                        bitrate = audioInfo.first
                    }
                }
            }

            MediaViewModelObject.isDolby.value = haveJOC
            MediaViewModelObject.samplingRate.intValue = samplingRate
            MediaViewModelObject.bitrate.intValue = bitrate

            // 在线曲：把解码器观测档作为交叉校验证据记入事实表（只计指标、
            // 不推翻服务端回写值——在线流 bitrate 常为 -1，拿它推翻声明会造大面
            // 积假"未知"）。本地文件不受音质概念影响，不记事实。
            if (mediaId?.startsWith("kugou-online-") == true) {
                KugouRepository.recordObservedTier(
                    mediaId.removePrefix("kugou-online-"),
                    KugouQuality.tierFromSpec(bitrate, samplingRate)
                )
            }

            println("质量分析 采样率：${MediaViewModelObject.samplingRate.intValue}，比特率：${MediaViewModelObject.bitrate.intValue}")
        }
    }

    override fun onDestroy() {
        YosDiagnostics.log("SVC_DESTROY")
        YosDiagnostics.attachPlayback(this, null)
        gradeHeartbeatJob?.cancel()
        gradeHeartbeatJob = null
        // 先拆跳歌退避：它往主线程 Handler 上挂了重备任务，回调里要碰 player，
        // 放在 session 释放之后执行就会打到已 release 的播放器
        OnlineSkipState.detach()
        // 先退过渡：它持有副 player（独立于会话），放在 session 释放前后都会漏，显式收尾
        CrossfadeExo.release()
        mediaSession?.run {
            // player 是会话外壳（ForwardingPlayer），release 透传到当前真 player——
            // 无论是否发生过扶正，释放的都是音频真相那一侧
            player.release()
            release()
            mediaSession = null
        }
        currentPlayer = null
        forwardingPlayer = null
        playbackListener = null
        super.onDestroy()
    }

    override fun onGetSession(
        controllerInfo: MediaSession.ControllerInfo
    ): MediaSession? = mediaSession
}

