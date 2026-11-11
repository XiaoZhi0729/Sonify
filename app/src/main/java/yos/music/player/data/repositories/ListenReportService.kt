package yos.music.player.data.repositories

import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
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
 *
 * **本工程修正（勿回退）**：
 * 6. **start 延迟到真出声**：切歌即发 start 会给「缓冲失败/被跳过」的歌留一个永远
 *    配不上 end 的孤儿 start（真机日志里 144 次 start 只对应 37 次 end）。现在
 *    [onSongStarted] 在 playing=false 时只记账不发请求，等 [onPlayingChanged] 首次
 *    playing=true 再发。
 * 7. **事件严格有序**：所有请求经 [queue] 串成 FIFO，由单消费者逐条 await。
 *    原来是各自 launch，真机日志实锤新歌 start 抢在上一首 end 之前到达
 *    （服务器回 `duplicate:true`，今天 10 次 / 昨天 46 次）。
 * 8. `duplicate:true` 只记日志，不重发（服务器侧已判过重）。
 *
 * ## 实测的记账规则（2026-11-08 真机对照，勿再凭注释猜）
 *
 * `/user/grade/info` 的 `diff_sec` **完全不记账**：连发 10 次均为 `status=1 err=0`，
 * 服务器 `d_sec` 一动不动（4385943 恒定）。所以心跳差量上报对时长**没有作用**，
 * 它的价值仅在于「本地记账 + 设置页展示」。
 *
 * CSCC 的 `end.duration` 是**唯一生效的时长信号**，但有两个实测特性：
 *
 * 1. **延迟约 14 分钟**才反映到 `d_sec`（15:05:21 发出 → 15:19:56 可见）。
 *    中途刷新看到数字不动属于正常，不代表失败。
 * 2. 只有 start 未被判 `duplicate` 的那一段会被计入：
 *
 * | mxid      | duration | start 响应                  | 服务器 d_sec |
 * |-----------|----------|-----------------------------|--------------|
 * | 770092340 | 169829   | `{"status":1,"errcode":0}`  | **+169 秒**  |
 * | 526439146 | 180673   | `{...,"duplicate":true}`    | **0**        |
 *
 * 而 `duplicate` **不是**「今天播过」的意思——同一天反复播放大量歌曲都拿到干净
 * start；它出现在**刚结束就立刻重播同一首**的情形（12:59 的 403520109 前面
 * 12:58:39 刚播完 445570650，形成 1 秒一跳的连续切歌）。
 *
 * 结论：「听歌时长不涨」经常不是上报失败，而是这段被判重了。客户端能做的是保证
 * 每段都有干净的 start→end 配对；是否计入由服务器判定。
 */
object ListenReportService {

    private const val TAG = "CSCC"
    private const val MIN_REPORTABLE_MS = 15_000L

    /**
     * start 发出前的静默期（毫秒）：实测「紧贴着上一首 end 发出的 start」会被服务器
     * 判 `duplicate`，而判 dup 那一段的 end.duration 完全不入账。
     *
     * 数据（2026-11-08）：切歌瞬间 end→start 只隔 25ms 的两段共 435 秒全丢；
     * 隔了一段时间才发 start 的段 186 秒全部到账。
     *
     * 只延迟 start，不延迟 end——end 越早发越好（它才是记账信号）。
     */
    private const val START_GUARD_MS = 1_500L

    val tracker = PlaybackDurationTracker()

    /** 当前计时段所属账号（onSongStarted 时记下，end 出口校验）。 */
    private var segmentUserid: String? = null

    /** 本段 start 是否已经发出（未出声的歌不发 start，也就不发 end）。 */
    private var startSent = false

    /** 本段所属 mixsongid（start 延迟发送时要用）。 */
    private var pendingStartMxid: String? = null

    /**
     * 本段的 start 是否被服务器判 duplicate。
     *
     * 实测判 dup 的段不会记账，但这里**不据此跳过 end**（只落日志）：客户端不替
     * 服务器决定记账规则，上游规则一变就要自动生效。计数用于验证 [START_GUARD_MS]
     * 是否真的降了判重率。
     */
    private var segmentStartDuplicated = false

    /** 段的单调序号：start 的响应异步回来时用来判断「是不是还在同一段」。 */
    private var segmentSeq = 0L

    /**
     * 事件严格串行：生产者（播放器回调，主线程）只 [Channel.trySend] 入队，
     * 单消费者按入队顺序逐条发完再取下一条。
     *
     * 为什么不能只靠 launch + 单线程调度器：`reportListenEvent` 内部会
     * `withContext(Dispatchers.IO)`，协程被切回线程池，相邻两条请求仍可能乱序。
     * 只有「上一条 await 完再取下一条」的消费者循环能真正保证到达顺序。
     */
    private val queue = Channel<Event>(Channel.UNLIMITED)

    private val worker = CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
        for (event in queue) {
            // start 前静默：让「上一首 end」把窗口让开，避免被判 duplicate。
            // 用「目标时刻」而不是无条件 delay：延迟发生在消费侧，若无条件等 1.5s，
            // 紧跟在后面的 end 也会被一起拖慢（end 越早到越好）。这里只等到
            // 「上一个事件真正发完 + START_GUARD_MS」这个绝对时刻。
            val wait = event.notBeforeMs - SystemClock.elapsedRealtime()
            if (wait > 0) runCatching { delay(wait) }
            runCatching { sendEvent(event) }
            lastSentAtMs = SystemClock.elapsedRealtime()
            // 判重率统计：起终点都是 CSCC 事件，验收时直接看这行的 dup/total 即可
            // 判断 START_GUARD_MS 是否真的降了判重（改前实测约 8/58 ≈ 14%）。
            if (event.kind == "start") {
                startCount++
                if (lastStartDuplicated) dupStartCount++
                YosDiagnostics.log(
                    "CSCC_GUARD",
                    "waited_ms" to wait.coerceAtLeast(0L),
                    "dup" to lastStartDuplicated,
                    "dup_rate" to "${dupStartCount}/${startCount}"
                )
            }
        }
    }

    /**
     * 待发事件。[durationMs]/[state] 仅 end 用；[seq] 用于回填异步响应。
     * [notBeforeMs] 是 [SystemClock.elapsedRealtime] 口径的最早可发时刻
     * （只对 start 设；end 恒为 0 = 立刻发）。
     */
    private data class Event(
        val kind: String,
        val mixsongid: String,
        val durationMs: Long = 0L,
        val state: String? = null,
        val seq: Long = 0L,
        val notBeforeMs: Long = 0L
    )

    /** 上一个事件发完的时刻（elapsedRealtime），用于给下一个 start 留静默期。 */
    @Volatile
    private var lastSentAtMs = 0L

    /** 判重率统计（进程内累计，仅日志用）：最后一个 start 是否被判重 + 计数器。 */
    @Volatile
    private var lastStartDuplicated = false
    private var startCount = 0
    private var dupStartCount = 0

    /** 设备参数：同一次播放的 start/end 必须一致（Rust 会话缓存 key 含机型，漂移会重建会话）。 */
    private val deviceModel: String by lazy {
        val m = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
        m.ifEmpty { "Android" }
    }
    private val systemVersion: String by lazy { Build.VERSION.RELEASE.orEmpty() }

    /** 歌曲开始播放（切歌时调用）。playing 须如实传当前是否真出声。 */
    @Synchronized
    fun onSongStarted(songId: String, mixsongid: String, playing: Boolean) {
        // 顺序：先结账并上报上一段 end，再开启新段（不可颠倒，
        // 否则期间到达的 onSongEnded 会拿到错误的段）。
        // 上一段的结束原因由播放器回调决定：自然播完时 STATE_ENDED 已经结账（state=
        // 完整播放），这里快照到的是 null；用户主动切歌才落到 SWITCHED。
        tracker.snapshot(PlaybackEndReason.SWITCHED)?.let { sendEnd(it, wasStarted = startSent) }
        segmentUserid = null
        startSent = false
        pendingStartMxid = null
        segmentStartDuplicated = false
        segmentSeq++
        // 开关关着不建段：否则关开关期间播放的时长会在打开后被一并上报
        if (!enabled()) {
            tracker.discard()
            return
        }
        tracker.start(songId = songId, mixsongid = mixsongid, playing = playing)
        segmentUserid = KugouApiService.userid
        pendingStartMxid = mixsongid
        if (playing) sendStartIfNeeded()
    }

    /**
     * 播放/暂停状态变化。
     *
     * @param songId/@param mixsongid 当前在线歌（MediaController 传入）。仅用于**补建段**：
     *   队列最后一首自然播完后 STATE_ENDED 已结账，用户再点播放时 media3 不会再派发
     *   media item transition，没有这个兜底这一段就完全不被计时。
     */
    @Synchronized
    fun onPlayingChanged(
        playing: Boolean,
        songId: String? = null,
        mixsongid: String? = null
    ) {
        // 开关关着就不计时：否则关开关期间听的歌会在打开后被算进 duration
        if (!enabled()) {
            if (tracker.isTracking) discard()
            return
        }
        // 补建段：不在跟踪中却真的出声了（ENDED 后重播/暂停恢复但段已结账）
        if (playing && !tracker.isTracking && songId != null && mixsongid != null) {
            tracker.start(songId = songId, mixsongid = mixsongid, playing = true)
            segmentUserid = KugouApiService.userid
            startSent = false
            pendingStartMxid = mixsongid
            segmentStartDuplicated = false
            segmentSeq++
            YosDiagnostics.log("CSCC_SEGMENT", "from" to "resume", "mixsongid" to mixsongid)
        }
        tracker.setPlaying(playing)
        // 真正出声才发 start：缓冲期就发会让「后来没播成」的歌留下孤儿 start
        if (playing) sendStartIfNeeded()
    }

    /** 用户拖动进度条。 */
    fun onSeek() {
        tracker.onSeek()
    }

    /** 歌曲结束/被停止：上报 end。 */
    @Synchronized
    fun onSongEnded(reason: PlaybackEndReason = PlaybackEndReason.COMPLETED) {
        val segment = tracker.end(reason) ?: return
        sendEnd(segment, wasStarted = startSent)
        startSent = false
        pendingStartMxid = null
    }

    /** 关闭开关/退出登录时丢弃当前段，不上报。 */
    @Synchronized
    fun discard() {
        tracker.discard()
        segmentUserid = null
        startSent = false
        pendingStartMxid = null
    }

    /** [startSent] 为 false 且有待发 mixsongid 时才发；发过就置位，幂等。 */
    private fun sendStartIfNeeded() {
        if (startSent) return
        val mxid = pendingStartMxid ?: return
        if (!enabled()) return
        if (!KugouApiService.isLoggedIn()) return
        startSent = true
        // 若队列里刚发过东西（典型就是上一首的 end），start 顺延到「它发完 + 静默期」。
        // 队列空时 lastSentAtMs 是很久以前，notBeforeMs 落在过去 → 立即发。
        val notBefore = lastSentAtMs + START_GUARD_MS
        queue.trySend(
            Event(kind = "start", mixsongid = mxid, seq = segmentSeq, notBeforeMs = notBefore)
        )
    }

    /**
     * 入队一条 end。
     *
     * @param wasStarted 本段是否真的发过 start。没发过 start 就不发 end——
     *   服务器没收到开始事件的 end 是无主事件，只会污染统计。
     */
    private fun sendEnd(segment: PlaybackSegment, wasStarted: Boolean) {
        // 账号校验：本段开始时所属账号与当前不一致 → 丢弃，避免把时长记到错误账号
        val segmentUid = segmentUserid
        val currentUid = KugouApiService.userid
        if (segmentUid != null && segmentUid != currentUid) {
            YosDiagnostics.log("CSCC_END", "drop" to "account_changed", "seg" to segmentUid, "now" to currentUid)
            return
        }
        if (!wasStarted) {
            YosDiagnostics.log("CSCC_END", "skip" to "no_start", "ms" to segment.durationMs)
            return
        }
        // 这一段对应的 start 被判 duplicate 时，服务器不会把它的 duration 记账
        //（实测：dup 段 181s+254s 全丢，干净段 170s+16s 全到账）。发了也是无效请求，
        // 但**仍然照发**——理由见类注释：客户端不替服务器做记账决定，且日后上游
        // 规则变了要能自动生效，而不是靠一个本地开关挡着。
        if (segmentStartDuplicated) {
            YosDiagnostics.log(
                "CSCC_END",
                "note" to "start_was_duplicate",
                "mixsongid" to segment.mixsongid,
                "ms" to segment.durationMs
            )
        }
        if (!segment.reachesThreshold(MIN_REPORTABLE_MS)) {
            // 过短：刻意不发 end（上游口径如此），start 已经发过，属已知的不配对形态
            YosDiagnostics.log("CSCC_END", "skip" to "too_short", "ms" to segment.durationMs)
            return
        }
        if (!enabled()) return
        queue.trySend(
            Event(
                kind = "end",
                mixsongid = segment.mixsongid,
                durationMs = segment.durationMs,
                state = segment.reason.state,
                seq = segmentSeq
            )
        )
    }

    /** 单消费者：发一条、等它回来、再发下一条。 */
    private suspend fun sendEvent(event: Event) {
        if (!enabled()) return
        if (!KugouApiService.isLoggedIn()) return
        val isEnd = event.kind == "end"
        KugouApiService.getInstance().reportListenEvent(
            event = event.kind,
            mixsongid = event.mixsongid,
            duration = if (isEnd) event.durationMs else null,
            state = event.state,
            deviceModel = deviceModel,
            systemVersion = systemVersion
        ).fold(
            onSuccess = { resp ->
                val tag = if (isEnd) "CSCC_END" else "CSCC_START"
                val respText = resp.toString().take(160)
                if (isEnd) {
                    YosDiagnostics.log(
                        tag,
                        "mixsongid" to event.mixsongid,
                        "ms" to event.durationMs,
                        "resp" to respText
                    )
                } else {
                    YosDiagnostics.log(
                        tag,
                        "mixsongid" to event.mixsongid,
                        "dup" to resp.optBoolean("duplicate", false),
                        "resp" to respText
                    )
                    // 判重是「这一段」的属性：只有响应对应的还是当前段才回填，
                    // 否则会把判重结果算到下一首头上（切歌窗口里 start 响应常晚到）。
                    synchronized(this@ListenReportService) {
                        if (event.seq == segmentSeq) {
                            segmentStartDuplicated = resp.optBoolean("duplicate", false)
                        }
                        lastStartDuplicated = resp.optBoolean("duplicate", false)
                    }
                }
                if (isEnd && resp.optBoolean("duplicate", false)) {
                    // 服务器判定重复（同曲 end 已在窗口内记过）：只记日志，不重发
                    Log.d(TAG, "end duplicated by server mxid=${event.mixsongid}")
                }
            },
            onFailure = { e ->
                val tag = if (isEnd) "CSCC_END" else "CSCC_START"
                YosDiagnostics.log(
                    tag,
                    "mixsongid" to event.mixsongid,
                    "ok" to false,
                    "why" to (e.message ?: e.toString())
                )
            }
        )
    }

    /** 开关是否开启（默认 true，本工程口径）；每次上报前重读，设置页切换即时生效。 */
    private fun enabled(): Boolean = SettingsLibrary.UploadListeningDuration
}
