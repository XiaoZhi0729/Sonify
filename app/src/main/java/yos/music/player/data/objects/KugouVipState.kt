package yos.music.player.data.objects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.coroutines.resume

/**
 * 酷狗 VIP / 签到全局可观察状态（对齐 md3Music KugouProvider 的签到子集）。
 *
 * 分层与 [KugouAccountState] 相同：本对象只是可观察镜像，
 * 真实业务与网络在 KugouVipRepository，MMKV 持久化 key 挂在 kugou_auth 实例；
 * 已签日期 key 按账号隔离（登出保留，重登恢复），服务端派生态（VIP 详情/
 * 打卡记录/待验证码）在登出时清空、登录后由 KugouVipRepository.onLoggedIn 重建。
 *
 * 线程约定：mutableStateOf 写入只发生在主线程调用点（UI 协程/应用启动协程），
 * 与项目内其它全局 object 一致，不加额外锁。
 */
object KugouVipState {

    // ---------- MMKV 持久化（本地已签日期兜底，跨重启保证"今天已签"判定） ----------
    // key 按账号隔离（_userid 后缀）：登出不清数据，重登同一账号立即恢复"今日已签"
    // 显示，无需等网络；不同账号登录各自读写自己的 key，互不污染（对齐 md3Music
    // 按账号隔离的 _signedDaysKey 设计）。
    private const val K_SIGNED_DAYS = "kugou_youth_signed_days"

    private val store: MMKV? by lazy {
        runCatching { MMKV.mmkvWithID("kugou_auth") }.getOrNull()
    }

    /** 当前账号的已签日期 MMKV key；账号未知时落到 _anon（不可签到的空命名空间）。 */
    private var signedDaysKey: String = "${K_SIGNED_DAYS}_anon"

    /** 本地已签日期集合（yyyy-MM-dd）。服务端打卡记录不及时时的日历兜底与自动签到防重。 */
    var localSignedDays: Set<String> by mutableStateOf(loadSignedDays())
        private set

    private fun loadSignedDays(): Set<String> =
        store?.decodeStringSet(signedDaysKey, emptySet()) ?: emptySet()

    /** 标记今天已签（幂等；只有新增时才写 MMKV）。 */
    fun markSignedToday(todayKey: String = todayLocal()) {
        val next = localSignedDays + todayKey
        if (next != localSignedDays) {
            localSignedDays = next
            store?.encode(signedDaysKey, next)
        }
    }

    fun isSignedToday(todayKey: String = todayLocal()): Boolean = todayKey in localSignedDays

    /**
     * 由 KugouApiService 在登录态写入点调用（restoreAuth/applyLogin→userid，clearLogin→null）：
     * 切换 MMKV key 并重载对应账号的本地已签标记。
     * 修复"登出→重登同一账号显示今日未签到"：标记按账号保留，重登即恢复。
     */
    fun onAccountResolved(userid: String?) {
        signedDaysKey = if (userid != null) "${K_SIGNED_DAYS}_$userid" else "${K_SIGNED_DAYS}_anon"
        localSignedDays = loadSignedDays()
        Log.d("KugouVipState", "onAccountResolved uid=$userid days=${localSignedDays.size}")
    }

    /**
     * 退出登录：重置服务端派生态与待验证码；本地已签日期按账号保留在 MMKV
     * （不删除——重登同一账号靠它立即恢复显示），仅把当前命名空间切回匿名。
     */
    fun clear() {
        vipDetail = null
        monthRecords = emptyList()
        monthRecordsFor = null
        todayUpgradedToConcept = false
        pendingCaptcha = null
        onAccountResolved(null)
    }

    // ---------- 服务端拉取的可观察数据 ----------
    var vipDetail: KugouVipDetail? by mutableStateOf(null)

    /** 当月（或当前查看月）的打卡记录，只含概念版(svip)记录，对齐 md3Music 日历口径。 */
    var monthRecords: List<KugouYouthDayRecord> by mutableStateOf(emptyList())

    /** monthRecords 所属月份（yyyy-MM），null=尚未加载。 */
    var monthRecordsFor: String? by mutableStateOf(null)

    /** 今天是否已成功升级概念版（内存态；跨重启由 localSignedDays 兜底判定）。 */
    var todayUpgradedToConcept: Boolean by mutableStateOf(false)

    /** 今天已签：内存升级成功 或 本地持久化标记 或 服务端当月记录含今天（供入口描述与日历口径一致）。 */
    fun isTodaySigned(): Boolean {
        if (todayUpgradedToConcept || isSignedToday()) return true
        if (monthRecordsFor == currentMonthKey()) {
            val today = todayLocal()
            return monthRecords.any { it.vipType == "svip" && it.day == today }
        }
        return false
    }

    // ---------- 操作进行中标志（按钮禁用态） ----------
    var signInRunning: Boolean by mutableStateOf(false)
    var listenRunning: Boolean by mutableStateOf(false)
    var adRunning: Boolean by mutableStateOf(false)

    // ---------- 20028 二次验证（腾讯滑块） ----------
    /**
     * 待处理验证码请求；非 null 时签到页应弹出 WebView 滑块弹窗。
     * 对齐 md3Music VerifyCaptchaRequest + Completer 模式，
     * Kotlin 侧用 suspendCancellableCoroutine 等待 UI 回填。
     */
    data class PendingCaptcha(val eventid: String, val vType: Int, val txappid: String)

    var pendingCaptcha: PendingCaptcha? by mutableStateOf(null)
        private set

    private var captchaContinuation: kotlinx.coroutines.CancellableContinuation<String?>? = null

    /**
     * 挂起等待 UI 完成滑块验证，返回 verifycode；取消/超时(3 分钟)/失败返回 null。
     * UI 侧监听 [pendingCaptcha] 弹窗，完成后调 [completeCaptcha] / [cancelCaptcha]。
     */
    suspend fun awaitCaptcha(request: PendingCaptcha): String? =
        withTimeoutOrNull(3 * 60 * 1000L) {
            suspendCancellableCoroutine { cont ->
                captchaContinuation = cont
                pendingCaptcha = request
                cont.invokeOnCancellation {
                    if (captchaContinuation === cont) {
                        captchaContinuation = null
                        pendingCaptcha = null
                    }
                }
            }
        }.also {
            captchaContinuation = null
            pendingCaptcha = null
        }

    /** UI 回调：滑块验证通过，回填 verifycode。 */
    fun completeCaptcha(verifycode: String) {
        val cont = captchaContinuation ?: return
        captchaContinuation = null
        pendingCaptcha = null
        if (cont.isActive) cont.resume(verifycode)
    }

    /** UI 回调：用户取消 / WebView 加载失败。 */
    fun cancelCaptcha() {
        val cont = captchaContinuation ?: return
        captchaContinuation = null
        pendingCaptcha = null
        if (cont.isActive) cont.resume(null)
    }
}

/** 本地日期 key（yyyy-MM-dd，跟随设备时区；服务器时间仅用于取号当天的 receive_day）。 */
fun todayLocal(): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

/** 当前月份 key（yyyy-MM）。 */
fun currentMonthKey(): String =
    SimpleDateFormat("yyyy-MM", Locale.US).format(Date())

/** /user/vip/detail 解析结果（对齐 md3Music KugouUserVipDetail）。 */
data class KugouVipDetail(
    val nickname: String?,
    val isVip: Boolean,
    /** 所有已开通 VIP 中最晚到期日（yyyy-MM-dd）。 */
    val expireTime: String?,
    /** 概念版(svip)到期日（yyyy-MM-dd）。 */
    val conceptExpireTime: String?,
    /** 畅听(tvip)到期日（yyyy-MM-dd）。 */
    val listenExpireTime: String?,
    /** 原始 busi_vip 原始字段（product_type/is_vip/vip_end_time），供 UI 细分。 */
    val busiVipList: List<KugouBusiVip>
)

/** busi_vip 单项。 */
data class KugouBusiVip(
    val productType: String?,
    val isVip: Boolean,
    val endTime: String?
)

/** /youth/month/vip/record 打卡记录单项：{"day":"2026-06-07","receive_vip":1,"vip_type":"svip"}。 */
data class KugouYouthDayRecord(
    /** 记录日期（yyyy-MM-dd，解析失败为 null）。 */
    val day: String?,
    /** svip=概念版 / tvip=畅听。 */
    val vipType: String?,
    val receiveVip: Int?
)
