package yos.music.player.data.repositories

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
 * 听歌时长心跳上报（对照 md3Music listening_grade_service.dart 移植）。
 *
 * 机制：由 YosPlaybackService 每 [HEARTBEAT_SEC] 秒喂一次 [onHeartbeat]；
 * 仅当「开关开 + 已登录酷狗 + 正在播在线歌曲」时累计秒数，攒满 [UPLOAD_THRESHOLD_SEC]
 * 调 /user/grade/info 按 diff_sec 记账一次。本地基准（d_sec）必须 ≥ 服务器值，
 * 首次上报前先查询服务器抬基准；连续 [MAX_CONSECUTIVE_FAILS] 次失败则查服务器
 * 重新对账并丢弃 pending。基准按 userid 隔离持久化在 MMKV（kugou_auth 命名空间）。
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
    private const val MAX_CONSECUTIVE_FAILS = 3
    private const val MMKV_AUTH = "kugou_auth"

    /** 服务侧播放器回调推送的「正在在线播放」镜像（替代 IO 线程读 Compose 快照态）。 */
    @Volatile
    private var onlinePlaying = false

    /** 本次上报增量（内存态；每拍落盘，进程被杀最多丢最后一个心跳的零头）。 */
    private var pendingDiffSec = 0L

    /** pending 归属账号：增量绝不跨账号记账。换账号在累计侧重置，flush 只减自己的账。 */
    private var pendingUserid: String? = null

    /** 已与服务器对过账的本地累计秒数基准；null = 尚未同步过。 */
    private val syncedDsec = AtomicReference<Long?>(null)
    private val syncedUserid = AtomicReference<String?>(null)

    /** 最近一次服务器返回的累计听歌秒数（未上报时长展示用）。 */
    @Volatile
    private var serverDsec = 0L

    private var consecutiveFails = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reportJob: Job? = null

    /** 服务侧推送：当前是否正在播放在线歌曲（对齐 md3Music setListeningOnline）。 */
    fun setListeningOnline(value: Boolean) {
        onlinePlaying = value
    }

    /** 未上报（服务器未记账）的听歌时长（秒）：本地基准 - 服务器累计值。 */
    val unreportedSeconds: Int
        get() {
            if (serverDsec <= 0) return 0
            val d = (syncedDsec.get() ?: 0L) - serverDsec
            return if (d > 0) d.toInt() else 0
        }

    /** 查询到服务器 d_sec 后调用：记录服务器值，本地基准只升不降，避免上报被拒。 */
    fun resyncFromServer(serverDsecValue: Long) {
        if (serverDsecValue <= 0) return
        serverDsec = serverDsecValue
        val cur = syncedDsec.get()
        if (cur == null || serverDsecValue > cur) {
            syncedDsec.set(serverDsecValue)
            val uid = syncedUserid.get() ?: KugouApiService.userid
            if (uid != null && uid.isNotEmpty()) {
                syncedUserid.set(uid)
                persistState(uid)
            }
        }
        YosDiagnostics.log("GRADE_RESYNC", "serverDsec" to serverDsecValue, "base" to syncedDsec.get())
    }

    /**
     * 心跳入口。门禁不满足时不累计直接返回（暂停/本地歌/关开关/未登录都不计时）。
     * 在线播放态读 [setListeningOnline] 推送的镜像。
     */
    @Synchronized
    fun onHeartbeat(intervalSec: Int) {
        if (!SettingsLibrary.UploadListeningDuration) return
        if (!onlinePlaying) return
        if (!KugouApiService.isLoggedIn()) return
        val userid = KugouApiService.userid ?: return

        // pending 只归当前账号：检测到换人立即切换归属，旧账号攒的增量绝不带给新账号。
        // 换账号时回读该账号已落盘的 pending（对齐 md3Music _load），再续加本拍。
        if (pendingUserid != userid) {
            pendingUserid = userid
            syncedUserid.set(userid)
            val saved = readLong("grade_synced_$userid")
            syncedDsec.set(if (saved > 0) saved else null)
            serverDsec = readLong("grade_server_$userid").coerceAtLeast(0L)
            pendingDiffSec = readLong("grade_pending_$userid").coerceAtLeast(0L) + intervalSec
        } else {
            pendingDiffSec += intervalSec
        }
        // 每拍落盘：进程被杀最多丢最后一个心跳的零头（对齐 md3Music _save）
        persistState(userid)
        YosDiagnostics.log(
            "GRADE_TICK",
            "online" to onlinePlaying,
            "pending" to pendingDiffSec,
            "synced" to syncedDsec.get(),
            "uid" to userid
        )
        if (pendingDiffSec < UPLOAD_THRESHOLD_SEC) {
            Log.d(TAG, "accumulate pending=$pendingDiffSec")
            return
        }
        // 上一轮还在路上：增量继续攒，下个心跳再冲
        if (reportJob?.isActive == true) {
            Log.d(TAG, "report in flight, pending=$pendingDiffSec")
            return
        }
        reportJob = scope.launch { flush() }
    }

    /** 消费 pending 并上报一次；成功刷新基准，失败计数，连败对账。 */
    private suspend fun flush() {
        val userid = KugouApiService.userid ?: return
        val diff = synchronized(this) {
            // 归属不符不报（换人后旧 flush 不该动新账号的账）
            if (pendingDiffSec < UPLOAD_THRESHOLD_SEC || pendingUserid != userid) return
            pendingDiffSec
        }

        // 基准恢复只管 base/失败计数，不动 pending（归属已在累计侧守住）
        if (syncedUserid.get() != userid) {
            val saved = readLong("grade_synced_$userid")
            syncedDsec.set(if (saved > 0) saved else null)
            syncedUserid.set(userid)
            synchronized(this) { consecutiveFails = 0 }
            Log.d(TAG, "account switched uid=$userid base=${syncedDsec.get()}")
        }

        var base = syncedDsec.get()
        if (base == null) {
            // 首次同步：查询服务器取累计值抬基准（服务器要求 d_sec ≥ 服务器值）。
            // 查询失败**不再放弃上报**（对齐 md3Music _report）：退化为 base=0 照常发，
            // 若被服务器拒绝则靠连败 3 次的重同步自愈，而不是永远不发。
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
                YosDiagnostics.log(
                    "GRADE_REPORT",
                    "ok" to ok,
                    "d_sec" to (base + diff),
                    "diff" to diff,
                    "base" to base,
                    "status" to status,
                    "err" to errorCode
                )
                if (!ok) {
                    onUploadFailure(userid, diff, "status=$status error_code=$errorCode")
                    return@fold
                }
                // 基准单调不减：服务器回的 d_sec 可能是更新延迟前的旧值，直接采信会
                // 拉低 base，下轮上报 d_sec < 服务器真实值会被拒。与 base+diff 取大。
                val serverValue = json.optJSONObject("data")?.optLong("d_sec", 0L) ?: 0L
                if (serverValue > 0) serverDsec = serverValue
                val newBase = maxOf(serverValue, base + diff)
                syncedDsec.set(newBase)
                persistState(userid)
                synchronized(this) {
                    // 归属变了就不扣新账号的账（快照 diff 是旧账号的）
                    if (pendingUserid == userid) pendingDiffSec -= diff
                    consecutiveFails = 0
                }
                Log.d(TAG, "reported diff=$diff base=$newBase (server=$serverValue)")
            },
            onFailure = { e ->
                YosDiagnostics.log(
                    "GRADE_REPORT",
                    "ok" to false,
                    "diff" to diff,
                    "base" to base,
                    "why" to Log.getStackTraceString(e).take(160)
                )
                onUploadFailure(userid, diff, Log.getStackTraceString(e).take(200))
            }
        )
    }

    private suspend fun onUploadFailure(userid: String, diff: Long, why: String) {
        val fails = synchronized(this) { ++consecutiveFails }
        Log.w(TAG, "report failed #$fails (pending=$diff): $why")
        if (fails < MAX_CONSECUTIVE_FAILS) return
        // 连败 3 次：查服务器重新对账，丢弃 pending（宁可少记不虚记）。
        // 只丢仍归该账号的 pending，别把换人后新账号攒的一并清了。
        val serverValue = queryServerDsec()
        synchronized(this) {
            consecutiveFails = 0
            if (pendingUserid == userid) pendingDiffSec = 0
        }
        if (serverValue != null) {
            syncedDsec.set(serverValue)
            serverDsec = serverValue
            persistState(userid)
            Log.w(TAG, "resynced from server d_sec=$serverValue, pending dropped")
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
        YosDiagnostics.log("GRADE_QUERY", "status" to status, "err" to errorCode)
        if (status != 1 || errorCode != 0) return null
        val value = json.optJSONObject("data")?.optLong("d_sec", 0L) ?: 0L
        if (value > 0) serverDsec = value
        return value
    }

    // ---------------------------------------------------------------- 持久化（按 userid 隔离）

    private fun readLong(key: String): Long =
        runCatching { MMKV.mmkvWithID(MMKV_AUTH).decodeLong(key, -1L) }.getOrDefault(-1L)

    private fun persistState(userid: String) {
        runCatching {
            val store = MMKV.mmkvWithID(MMKV_AUTH)
            syncedDsec.get()?.let { store.encode("grade_synced_$userid", it) }
            store.encode("grade_pending_$userid", pendingDiffSec)
            store.encode("grade_server_$userid", serverDsec)
        }
    }
}
