package yos.music.player.data.repositories

import android.util.Log
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
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
 * 全程 best-effort：任何失败只影响本次上报，绝不向上抛、绝不影响播放。
 */
object ListeningGradeTracker {

    private const val TAG = "GRADE_UPLOAD"
    private const val HEARTBEAT_SEC = 30
    private const val UPLOAD_THRESHOLD_SEC = 60L
    private const val MAX_CONSECUTIVE_FAILS = 3
    private const val MMKV_AUTH = "kugou_auth"

    /** 本次上报增量（内存态；进程被杀最多丢一轮未满 60s 的零头，下次查询对账兜回）。 */
    private var pendingDiffSec = 0L

    /** pending 归属账号：增量绝不跨账号记账。换账号在累计侧重置，flush 只减自己的账。 */
    private var pendingUserid: String? = null

    /** 已与服务器对过账的本地累计秒数基准；null = 尚未同步过。 */
    private val syncedDsec = AtomicReference<Long?>(null)
    private val syncedUserid = AtomicReference<String?>(null)
    private var consecutiveFails = 0

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var reportJob: Job? = null

    /**
     * 心跳入口。门禁不满足时不累计直接返回（暂停/本地歌/关开关/未登录都不计时）。
     * @param onlinePlaying 当前是否正在播**在线**歌曲（由服务侧用状态镜像判定后传入）
     */
    @Synchronized
    fun onHeartbeat(intervalSec: Int, onlinePlaying: Boolean) {
        if (!SettingsLibrary.UploadListeningDuration) return
        if (!onlinePlaying) return
        if (!KugouApiService.isLoggedIn()) return
        val userid = KugouApiService.userid ?: return

        // pending 只归当前账号：检测到换人立即重置，旧账号攒的增量绝不带给新账号
        if (pendingUserid != userid) {
            pendingUserid = userid
            pendingDiffSec = intervalSec.toLong()
        } else {
            pendingDiffSec += intervalSec
        }
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

        // 基准恢复只管 base/失败计数，不动 pending（归属已在累计侧守住，
        // 这里清了会跟快照的 diff 脱钩造成幽灵扣款）
        if (syncedUserid.get() != userid) {
            val saved = runCatching {
                MMKV.mmkvWithID(MMKV_AUTH).decodeLong("grade_synced_$userid", -1L)
            }.getOrNull() ?: -1L
            syncedDsec.set(if (saved > 0) saved else null)
            syncedUserid.set(userid)
            synchronized(this) { consecutiveFails = 0 }
            Log.d(TAG, "account switched uid=$userid base=${syncedDsec.get()}")
        }

        var base = syncedDsec.get()
        if (base == null) {
            // 首次同步：查询服务器取累计值抬基准（服务器要求 d_sec ≥ 服务器值）
            base = queryServerDsec()?.also {
                syncedDsec.set(it)
                persistBase(userid, it)
                Log.d(TAG, "baseline synced from server d_sec=$it")
            }
            if (base == null) {
                Log.w(TAG, "baseline query failed, retry next heartbeat (pending=$diff)")
                return
            }
        }

        val api = KugouApiService.getInstance()
        api.getUserGradeInfo(dSec = base + diff, diffSec = diff).fold(
            onSuccess = { json ->
                val ok = json.optInt("status") == 1 && json.optInt("error_code") == 0
                if (!ok) {
                    onUploadFailure(userid, diff, "status=${json.optInt("status")} error_code=${json.optInt("error_code")}")
                    return
                }
                // 基准单调不减：服务器回的 d_sec 可能是更新延迟前的旧值，直接采信会
                // 拉低 base，下轮上报 d_sec < 服务器真实值会被拒。与 base+diff 取大。
                val serverDsec = json.optJSONObject("data")?.optLong("d_sec", 0L) ?: 0L
                val newBase = maxOf(serverDsec, base + diff)
                syncedDsec.set(newBase)
                persistBase(userid, newBase)
                synchronized(this) {
                    // 归属变了就不扣新账号的账（快照 diff 是旧账号的）
                    if (pendingUserid == userid) pendingDiffSec -= diff
                    consecutiveFails = 0
                }
                Log.d(TAG, "reported diff=$diff base=$newBase (server=$serverDsec)")
            },
            onFailure = { e ->
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
        val serverDsec = queryServerDsec()
        synchronized(this) {
            consecutiveFails = 0
            if (pendingUserid == userid) pendingDiffSec = 0
        }
        if (serverDsec != null) {
            syncedDsec.set(serverDsec)
            persistBase(userid, serverDsec)
            Log.w(TAG, "resynced from server d_sec=$serverDsec, pending dropped")
        }
    }

    /**
     * 查询模式取服务器累计秒数。返回 null = 查询失败（下轮重试）；
     * 返回 0 = 合法（全新账号听歌 0 秒），不能与失败混淆，否则新账号基线永远同步不上。
     */
    private suspend fun queryServerDsec(): Long? {
        val json = KugouApiService.getInstance().getUserGradeInfo().getOrNull() ?: return null
        if (json.optInt("status") != 1 || json.optInt("error_code") != 0) return null
        return json.optJSONObject("data")?.optLong("d_sec", 0L) ?: 0L
    }

    private fun persistBase(userid: String, dsec: Long) {
        runCatching { MMKV.mmkvWithID(MMKV_AUTH).encode("grade_synced_$userid", dsec) }
    }
}
