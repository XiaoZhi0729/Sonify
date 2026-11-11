package yos.music.player.data.repositories

import android.os.SystemClock
import android.util.Log
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.native.KugouApiService
import java.util.concurrent.atomic.AtomicReference

/**
 * 听歌时长心跳上报（对照 md3Music listening_grade_service.dart 移植，计时口径已修正）。
 *
 * 机制：由 YosPlaybackService 每 [HEARTBEAT_SEC] 秒喂一次 [onHeartbeat]；
 * 仅当「开关开 + 已登录酷狗 + 正在播在线歌曲」时累计**真实出声的墙钟毫秒**，
 * 攒满 [UPLOAD_THRESHOLD_MS] 调 /user/grade/info 按 diff_sec 记账一次。
 *
 * **与 md3Music 的差异（本工程修正，勿回退）**：
 * 1. md3 在心跳瞬间判 `onlinePlaying` 就硬加一整拍 30s——播放 25s 就暂停、1s 短试听
 *    都会按整拍记或归零，单次收听误差可达 ±30s。这里改为**墙钟计时**：由
 *    [setListeningOnline] 在播放/暂停边沿打点，心跳结算「上拍到现在真正在播的毫秒」，
 *    单拍上限 [intervalSec]×2（防进程冻结后一次性爆记）。
 * 2. 基准不再只升不降。历史上 `syncedDsec = max(server, base+diff)` 让「服务器没记账
 *    的那部分」永久固化在本地（实测本地比服务器高 6121s，设置页「未上报」永远清不掉）。
 *    现在：**无 pending 时基准对齐服务器实测值（可降）**，有 pending 时才只升不降。
 * 3. 连败 3 次不再无条件丢 pending：先查服务器，若它其实已经记上了（只是回包慢/超时），
 *    消费增量而不是丢弃；确认服务器不记账才丢，且连续 3 轮对账都不记账才放弃。
 *
 * 基准按 userid 隔离持久化在 MMKV（kugou_auth 命名空间）。
 *
 * 「正在在线播放」由服务侧的播放器回调经 [setListeningOnline] 推送（对齐 md3Music
 * PlayerProvider），心跳只读该镜像，不在 IO 线程直读 Compose 快照态。
 *
 * 全程 best-effort：任何失败只影响本次上报，绝不向上抛、绝不影响播放。
 * 关键节点同时落 [YosDiagnostics]（release 也保留），便于定位「卡在哪一环」。
 */
object ListeningGradeTracker {

    private const val TAG = "GRADE_UPLOAD"
    private const val HEARTBEAT_SEC = 30
    private const val UPLOAD_THRESHOLD_SEC = 60L
    private const val UPLOAD_THRESHOLD_MS = UPLOAD_THRESHOLD_SEC * 1000L

    /** 单次上报增量上限：pending 攒过 30 分钟就不再加（防长时间离线后一次性虚记）。 */
    private const val MAX_PENDING_MS = GradeAccounting.MAX_PENDING_MS

    /** 连续失败几拍后去服务器对账（只查，不再无条件丢 pending）。 */
    private const val MAX_CONSECUTIVE_FAILS = 3

    /** 对账几轮仍确认服务器不记账 → 放弃 pending（宁可少记不虚记）。 */
    private const val MAX_UNCREDITED_RECONCILES = 3

    private const val CREDIT_TOLERANCE_SEC = GradeAccounting.CREDIT_TOLERANCE_SEC

    /**
     * 连续几次「status=1 但服务器值不涨」后，本地基准改为跟随服务器（可降）。
     * 判定在 [GradeAccounting.nextBase]，这里只做计数。
     */
    private const val UNCREDITED_BASE_RESET = GradeAccounting.UNCREDITED_BASE_RESET

    private const val MMKV_AUTH = "kugou_auth"

    /** pending 持久化键换了单位（秒→毫秒），必须换 key，否则读到旧秒值会把时长放大 1000 倍。 */
    private const val KEY_PENDING_MS = "grade_pending_ms2"
    private const val KEY_SYNCED = "grade_synced"
    private const val KEY_SERVER = "grade_server"

    /** 服务侧播放器回调推送的「正在在线播放」镜像（替代 IO 线程读 Compose 快照态）。 */
    @Volatile
    private var onlinePlaying = false

    /** 上拍结算后本拍已累计的出声毫秒（未并入 pending 前的当前窗口）。 */
    private var playedAccumMs = 0L

    /** 当前这段「在播」的起算时刻（[SystemClock.elapsedRealtime]）；0 = 当前不在播。 */
    private var playingSinceElapsed = 0L

    /** 本次上报增量（毫秒；每拍落盘，进程被杀最多丢最后一个心跳的零头）。 */
    private var pendingDiffMs = 0L

    /** pending 归属账号：增量绝不跨账号记账。换账号在累计侧重置，flush 只减自己的账。 */
    private var pendingUserid: String? = null

    /** 已与服务器对过账的本地累计秒数基准；null = 尚未同步过。 */
    private val syncedDsec = AtomicReference<Long?>(null)
    private val syncedUserid = AtomicReference<String?>(null)

    /** 最近一次服务器返回的累计听歌秒数（未上报时长展示用）。 */
    @Volatile
    private var serverDsec = 0L

    private var consecutiveFails = 0

    /** 连续「上报成功但服务器值没动」的次数：>0 说明该账号不吃 diff_sec 记账。 */
    private var uncreditedReports = 0

    /** 对账轮次：服务器始终不记账时用它兜底放弃 pending，避免无限重试。 */
    private var uncreditedReconciles = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reportJob: Job? = null

    /**
     * 服务侧推送：当前是否正在播放在线歌曲（对齐 md3Music setListeningOnline）。
     *
     * **同时是计时开关**：进入 true 开始计墙钟，false 结算并停表。必须如实传入
     * （缓冲中/被焦点抑制都算 false），否则静默等待会被算进时长。
     */
    @Synchronized
    fun setListeningOnline(value: Boolean) {
        onlinePlaying = value
        val now = SystemClock.elapsedRealtime()
        if (value) {
            if (playingSinceElapsed == 0L) playingSinceElapsed = now
        } else {
            if (playingSinceElapsed != 0L) {
                playedAccumMs += (now - playingSinceElapsed).coerceAtLeast(0L)
                playingSinceElapsed = 0L
            }
        }
    }

    /** 未上报（服务器未记账）的听歌时长（秒）：本地基准 - 服务器累计值。 */
    val unreportedSeconds: Int
        get() {
            if (serverDsec <= 0) return 0
            val d = (syncedDsec.get() ?: 0L) - serverDsec
            return if (d > 0) d.toInt() else 0
        }

    /**
     * 查询到服务器 d_sec 后调用（设置页刷新 / 连败对账）。
     *
     * **无 pending 时双向对齐服务器值**：这是清掉「本地虚高」的唯一出口——旧实现只升不降，
     * 服务器没记账的部分就永久留在本地，表现为「未上报」恒显示一大截且永不消失。
     * 有 pending 时仍只升不降，免得正在攒的增量被旧值抹掉。
     */
    @Synchronized
    fun resyncFromServer(serverDsecValue: Long) {
        // 0 不采信：data.d_sec 缺失/解析失败也长得像 0，拿它当基准会把 d_sec 压到 diff，
        // 上游必然拒收（下一次上报就进失败循环）。只有真读到正数才对齐。
        if (serverDsecValue <= 0) {
            YosDiagnostics.log("GRADE_RESYNC", "skip" to "non_positive", "serverDsec" to serverDsecValue)
            return
        }
        serverDsec = serverDsecValue
        val cur = syncedDsec.get()
        val hasPending = pendingDiffMs >= 1000L
        val shouldApply = when {
            cur == null -> true
            hasPending -> serverDsecValue > cur
            else -> serverDsecValue != cur
        }
        if (shouldApply) {
            syncedDsec.set(serverDsecValue)
            val uid = syncedUserid.get() ?: KugouApiService.userid
            if (uid != null && uid.isNotEmpty()) {
                syncedUserid.set(uid)
                persistState(uid)
            }
        }
        YosDiagnostics.log(
            "GRADE_RESYNC",
            "serverDsec" to serverDsecValue,
            "base" to syncedDsec.get(),
            "pending_ms" to pendingDiffMs
        )
    }

    /**
     * 心跳入口。先结算本拍的出声毫秒（无论门禁是否通过都结算，避免关闭开关期间攒下的
     * 时间在重新打开后被一次性记上），再判定门禁。
     * 在线播放态读 [setListeningOnline] 推送的镜像。
     */
    @Synchronized
    fun onHeartbeat(intervalSec: Int) {
        val playedMs = drainPlayedMs(intervalSec)
        if (!SettingsLibrary.UploadListeningDuration) return
        if (!onlinePlaying) return
        if (!KugouApiService.isLoggedIn()) return
        val userid = KugouApiService.userid ?: return

        // pending 只归当前账号：检测到换人立即切换归属，旧账号攒的增量绝不带给新账号。
        // 换账号时回读该账号已落盘的 pending（对齐 md3Music _load），再续加本拍。
        if (pendingUserid != userid) {
            pendingUserid = userid
            syncedUserid.set(userid)
            val savedBase = readLong("${KEY_SYNCED}_$userid")
            syncedDsec.set(if (savedBase > 0) savedBase else null)
            serverDsec = readLong("${KEY_SERVER}_$userid").coerceAtLeast(0L)
            pendingDiffMs = readLong("${KEY_PENDING_MS}_$userid").coerceAtLeast(0L) + playedMs
        } else {
            pendingDiffMs += playedMs
        }
        if (pendingDiffMs > MAX_PENDING_MS) {
            YosDiagnostics.log("GRADE_PENDING_CAP", "was" to pendingDiffMs, "cap" to MAX_PENDING_MS)
            pendingDiffMs = MAX_PENDING_MS
        }
        // 每拍落盘：进程被杀最多丢最后一个心跳的零头（对齐 md3Music _save）
        persistState(userid)
        YosDiagnostics.log(
            "GRADE_TICK",
            "online" to onlinePlaying,
            "played_ms" to playedMs,
            "pending_s" to (pendingDiffMs / 1000),
            "synced" to syncedDsec.get(),
            "uid" to userid
        )
        if (pendingDiffMs < UPLOAD_THRESHOLD_MS) {
            Log.d(TAG, "accumulate pending=${pendingDiffMs}ms")
            return
        }
        // 上一轮还在路上：增量继续攒，下个心跳再冲
        if (reportJob?.isActive == true) {
            Log.d(TAG, "report in flight, pending=${pendingDiffMs}ms")
            return
        }
        reportJob = scope.launch { flush() }
    }

    /**
     * 结算本拍真正出声的毫秒并重新起表。
     *
     * 用墙钟而不是「tick 时是否在播」：md3 的整拍记账对短段落（试听、暂停在拍中间）
     * 要么整拍记上要么整拍归零，误差可达一整拍。墙钟口径下暂停多久就少记多久。
     */
    private fun drainPlayedMs(intervalSec: Int): Long {
        val now = SystemClock.elapsedRealtime()
        var played = playedAccumMs
        if (playingSinceElapsed != 0L) {
            played += (now - playingSinceElapsed).coerceAtLeast(0L)
            playingSinceElapsed = now
        }
        playedAccumMs = 0L
        // 正常一拍只有 intervalSec 的量；超了说明心跳循环被冻结/挂起，多余部分不可信
        val capped = GradeAccounting.capPlayedMs(played, intervalSec)
        if (capped != played) {
            YosDiagnostics.log("GRADE_CLOCK_CAP", "played_ms" to played, "cap_ms" to capped)
        }
        return capped
    }

    /** 消费 pending 并上报一次；成功刷新基准，失败计数，连败对账。 */
    private suspend fun flush() {
        val userid = KugouApiService.userid ?: return
        // 整秒上报：服务器 d_sec/diff_sec 都是秒，毫秒余数留在 pending 里下轮累计，
        // 不做四舍五入（多报 1 秒比少报 1 秒更容易被上游判为虚记）。
        val diff = synchronized(this) {
            // 归属不符不报（换人后旧 flush 不该动新账号的账）
            if (pendingDiffMs < UPLOAD_THRESHOLD_MS || pendingUserid != userid) return
            pendingDiffMs / 1000L
        }

        // 基准恢复只管 base/失败计数，不动 pending（归属已在累计侧守住）
        if (syncedUserid.get() != userid) {
            val savedBase = readLong("${KEY_SYNCED}_$userid")
            syncedDsec.set(if (savedBase > 0) savedBase else null)
            syncedUserid.set(userid)
            synchronized(this) { consecutiveFails = 0 }
            Log.d(TAG, "account switched uid=$userid base=${syncedDsec.get()}")
        }

        var base = syncedDsec.get()
        if (base == null) {
            // 首次同步：查询服务器取累计值抬基准（服务器要求 d_sec ≥ 服务器值）。
            // 查询失败**不再放弃上报**（对齐 md3Music _report）：退化为 base=0 照常发，
            // 若被服务器拒绝则靠连败对账自愈，而不是永远不发。
            val queried = queryServerDsec()
            if (queried != null) {
                base = queried
                syncedDsec.set(queried)
                persistState(userid)
                YosDiagnostics.log("GRADE_BASE", "from" to "query", "d_sec" to queried)
                Log.d(TAG, "baseline synced from server d_sec=$queried")
            } else {
                base = 0L
                YosDiagnostics.log("GRADE_BASE", "from" to "fallback0", "d_sec" to 0L)
                Log.w(TAG, "baseline query failed, report with base=0 (pending=$diff)")
            }
        }

        val api = KugouApiService.getInstance()
        api.getUserGradeInfo(dSec = base + diff, diffSec = diff).fold(
            onSuccess = { json ->
                val status = json.optInt("status")
                val errorCode = json.optInt("error_code")
                val ok = status == 1 && errorCode == 0
                if (!ok) {
                    YosDiagnostics.log(
                        "GRADE_REPORT",
                        "ok" to false,
                        "d_sec" to (base + diff),
                        "diff" to diff,
                        "base" to base,
                        "status" to status,
                        "err" to errorCode
                    )
                    onUploadFailure(userid, diff, "status=$status error_code=$errorCode")
                    return@fold
                }
                val serverValue = json.optJSONObject("data")?.optLong("d_sec", 0L) ?: 0L
                if (serverValue > 0) serverDsec = serverValue
                // 记账判定与基准推进都在 [GradeAccounting]（与单测共用，避免两处各写一份）：
                // 服务器持续不涨时基准要能下调，否则本地虚高会让「未上报」永远清不掉。
                val credited = GradeAccounting.isCredited(serverValue, base + diff)
                val streakBefore = uncreditedReports
                val newBase = GradeAccounting.nextBase(base, diff, serverValue, streakBefore)
                syncedDsec.set(newBase)
                persistState(userid)
                synchronized(this) {
                    // 归属变了就不扣新账号的账（快照 diff 是旧账号的）
                    if (pendingUserid == userid) {
                        pendingDiffMs = (pendingDiffMs - diff * 1000L).coerceAtLeast(0L)
                    }
                    consecutiveFails = 0
                    uncreditedReconciles = 0
                    uncreditedReports = if (credited) 0 else streakBefore + 1
                }
                YosDiagnostics.log(
                    "GRADE_REPORT",
                    "ok" to true,
                    "d_sec" to (base + diff),
                    "diff" to diff,
                    "base" to base,
                    "newBase" to newBase,
                    "srv" to serverValue,
                    "credited" to credited,
                    "uncredited" to uncreditedReports,
                    "status" to status,
                    "err" to errorCode
                )
                // 连续多轮「status=1/error_code=0 但服务器值不动」= 该账号不吃 diff_sec 记账，
                // 只认真实播放（playhistory/CSCC）。发一次全量诊断，省得日后重新翻日志。
                if (uncreditedReports == UNCREDITED_BASE_RESET) {
                    YosDiagnostics.log(
                        "GRADE_NOCRE",
                        "reports" to uncreditedReports,
                        "base" to base,
                        "srv" to serverValue,
                        "newBase" to newBase,
                        "note" to "server_ignores_diff_sec_base_follows_server"
                    )
                }
                Log.d(TAG, "reported diff=$diff base=$newBase (server=$serverValue credited=$credited)")
            },
            onFailure = { e ->
                // 不要用 Log.getStackTraceString：proguard 把 android.util.Log.* 整条剥掉，
                // release 里它恒返回空串，失败原因就永远查不到（YosDiagnostics 存在的意义
                // 正是绕开这个）。这里自己拼类型+消息。
                val why = "(${e.javaClass.simpleName}) ${e.message ?: ""}".take(200)
                YosDiagnostics.log(
                    "GRADE_REPORT",
                    "ok" to false,
                    "diff" to diff,
                    "base" to base,
                    "why" to why
                )
                onUploadFailure(userid, diff, why)
            }
        )
    }

    /**
     * 上报失败处理。连败 [MAX_CONSECUTIVE_FAILS] 次后查服务器对账，**不再无条件丢 pending**：
     * 上游超时/502 常见于「其实已经记账、只是回包没拿到」，无脑丢弃等于把真实时长扔掉。
     *
     * 对账判定：
     * - 服务器值已推进到 base+diff 附近 → 之前的失败其实记上了：消费掉这段增量，对齐基准；
     * - 服务器值没动 → 真的没记：**保留** pending 下拍重试（旧实现这里丢，白丢时长）；
     * - 连续 [MAX_UNCREDITED_RECONCILES] 轮对账都不记账 → 放弃 pending（防无限重试 + 虚记）；
     * - 服务器值领先本地 → 抬基准（否则 d_sec 会被上游拒），pending 保留。
     */
    private suspend fun onUploadFailure(userid: String, diff: Long, why: String) {
        val fails = synchronized(this) { ++consecutiveFails }
        Log.w(TAG, "report failed #$fails (pending=$diff): $why")
        if (fails < MAX_CONSECUTIVE_FAILS) return

        val base = syncedDsec.get() ?: 0L
        val serverValue = queryServerDsec()
        val credited = serverValue != null && GradeAccounting.isCredited(serverValue, base + diff)
        val giveUp = synchronized(this) {
            consecutiveFails = 0
            if (credited) {
                uncreditedReconciles = 0
                if (pendingUserid == userid) {
                    pendingDiffMs = (pendingDiffMs - diff * 1000L).coerceAtLeast(0L)
                }
                false
            } else {
                uncreditedReconciles++
                if (uncreditedReconciles >= MAX_UNCREDITED_RECONCILES) {
                    uncreditedReconciles = 0
                    if (pendingUserid == userid) pendingDiffMs = 0L
                    true
                } else {
                    false
                }
            }
        }
        if (serverValue != null) {
            val newBase = GradeAccounting.baseAfterReconcile(base, diff, serverValue, credited)
            syncedDsec.set(newBase)
            serverDsec = serverValue
            persistState(userid)
        }
        YosDiagnostics.log(
            "GRADE_RECONCILE",
            "base" to base,
            "srv" to (serverValue ?: -1L),
            "diff" to diff,
            "credited" to credited,
            "giveUp" to giveUp,
            "pending_s" to (pendingDiffMs / 1000)
        )
        if (giveUp) {
            Log.w(TAG, "server never credited, pending dropped (base=$base)")
        }
    }

    /**
     * 查询模式取服务器累计秒数。返回 null = 查询失败（下轮重试）；
     * 返回 0 = 合法（全新账号听歌 0 秒），不能与失败混淆。
     */
    private suspend fun queryServerDsec(): Long? {
        val json = KugouApiService.getInstance().getUserGradeInfo().getOrNull() ?: return null
        val status = json.optInt("status")
        val errorCode = json.optInt("error_code")
        val value = json.optJSONObject("data")?.optLong("d_sec", 0L) ?: 0L
        YosDiagnostics.log("GRADE_QUERY", "status" to status, "err" to errorCode, "d_sec" to value)
        if (status != 1 || errorCode != 0) return null
        serverDsec = value
        return value
    }

    // ---------------------------------------------------------------- 持久化（按 userid 隔离）

    private fun readLong(key: String): Long =
        runCatching { MMKV.mmkvWithID(MMKV_AUTH).decodeLong(key, -1L) }.getOrDefault(-1L)

    private fun persistState(userid: String) {
        runCatching {
            val store = MMKV.mmkvWithID(MMKV_AUTH)
            syncedDsec.get()?.let { store.encode("${KEY_SYNCED}_$userid", it) }
            store.encode("${KEY_PENDING_MS}_$userid", pendingDiffMs)
            store.encode("${KEY_SERVER}_$userid", serverDsec)
        }
    }
}
