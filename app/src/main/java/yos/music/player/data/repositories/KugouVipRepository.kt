package yos.music.player.data.repositories

import android.util.Log
import com.funny.data_saver.core.mutableDataSaverStateOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import yos.music.player.data.SettingsSaver
import yos.music.player.data.objects.KugouBusiVip
import yos.music.player.data.objects.KugouVipDetail
import yos.music.player.data.objects.KugouVipState
import yos.music.player.data.objects.KugouYouthDayRecord
import yos.music.player.data.objects.currentMonthKey
import yos.music.player.native.KugouApiService
import java.util.Calendar
import java.util.Locale

/**
 * 酷狗 VIP / 签到业务仓库（对齐 md3Music kugou_provider.dart L1367-1830 的签到子集）。
 *
 * 双签到语义（与 EchoMusic/kgcheckin 一致）：
 *   1. /youth/day/vip（receive_day）  → 领 1 天畅听 VIP（tvip）
 *   2. /youth/day/vip/upgrade        → 升级为 1 天概念版 VIP（svip）
 * 只有两步都明确成功才提示"签到成功（概念版会员）"；upgrade 失败必须如实上报，
 * 不允许静默吞掉（md3Music 曾因此出现假成功 bug，见 provider 注释 L1452-1458）。
 *
 * 全部接口走 Rust 本地 server（method 无关、query 合并 body），签到类请求
 * 带 x-apicache-bypass 绕过 2 分钟 apicache，保证验证码重试不被旧响应污染。
 */
object KugouVipRepository {

    private const val TAG = "KugouVipRepository"

    /** 自动领取 VIP 开关（App 启动自动签到；默认开启，对齐 md3Music 设置项）。 */
    var AutoReceiveVip by mutableDataSaverStateOf(
        dataSaverInterface = SettingsSaver,
        key = "settings_kugou_auto_receive_vip",
        initialValue = true
    )

    private val api get() = KugouApiService.getInstance()

    // ==================== 查询 ====================

    /**
     * 登录成功后重建 VIP/签到状态（由登录页在扫码确认成功后调用）：
     * 拉 VIP 详情与当月打卡记录恢复服务端派生态；自动领取开启时顺便补签
     * （服务端 131001=今日已领取会把本地标记回写）。登出只保留本地标记
     * （按账号隔离的 MMKV key），服务端派生态全靠本函数恢复。
     */
    suspend fun onLoggedIn() {
        runCatching { refreshVipDetail() }
        runCatching { loadMonthRecord(currentMonthKey()) }
        if (AutoReceiveVip) runCatching { autoSignInIfNeeded() }
    }

    /** 拉取并解析 VIP 详情，写入 [KugouVipState.vipDetail]。失败返回 null（保留旧值）。 */
    suspend fun refreshVipDetail(): KugouVipDetail? = withContext(Dispatchers.IO) {
        val detail = api.getVipDetail().getOrNull()?.let { parseVipDetail(it) }
        if (detail != null) KugouVipState.vipDetail = detail
        detail
    }

    /**
     * 拉取指定月份（yyyy-MM）打卡记录，写入 [KugouVipState]。
     * 必须显式传 month——缺省时接口返回最早月份（md3Music 已知坑）。
     */
    suspend fun loadMonthRecord(month: String): List<KugouYouthDayRecord> =
        withContext(Dispatchers.IO) {
            val list = api.youthMonthVipRecord(month).getOrNull()?.let { parseMonthRecords(it) } ?: emptyList()
            KugouVipState.monthRecords = list
            KugouVipState.monthRecordsFor = month
            list
        }

    // ==================== 手动签到（严格双签到） ====================

    /**
     * 手动签到：始终发请求不做本地已签拦截（服务端会正确返回"今日已领取"）。
     * 返回 (success, message)。20028 且带 ssaCode 时先走滑块验证再重试一次
     * （claim 可能已成功，重试命中 131001 不阻断）。
     */
    suspend fun manualSignIn(): Pair<Boolean, String> {
        if (KugouVipState.signInRunning) return false to "请求进行中"
        if (!KugouApiService.isLoggedIn()) return false to "请先登录"
        KugouVipState.signInRunning = true
        try {
            val receiveDay = resolveReceiveDay()
            Log.d(TAG, "manualSignIn receiveDay=$receiveDay")
            var verifyAttempted = false
            while (true) {
                val (ok, msg, ssaCode) = signInOnce(receiveDay)
                if (ok) return true to msg
                if (ssaCode != null && !verifyAttempted) {
                    verifyAttempted = true
                    val verified = handleVerifyCaptcha(ssaCode)
                    if (!verified) return false to "签到需要安全验证，验证未完成"
                    continue
                }
                return false to msg
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "manualSignIn exception", e)
            return false to friendlyNetworkError(e)
        } finally {
            KugouVipState.signInRunning = false
        }
    }

    /**
     * 单次签到流程：领取畅听 VIP（claim）→ 升级概念版（upgrade）。
     * 返回 (success, message, ssaCode)；遇 20028 且响应带 ssaCode 时 message 为空、
     * 由外层走验证码后重试。
     */
    private suspend fun signInOnce(receiveDay: String): Triple<Boolean, String, String?> {
        // 1. 领取畅听 VIP（基础签到）；必传 receive_day，否则后端可能判定无效签到
        val claim = api.youthDayVip(receiveDay).getOrNull()
            ?: return Triple(false, "签到请求无响应，请稍后重试", null)

        val claimStatus = claim.optInt("status", -1)
        val claimErrorCode = claim.optInt("error_code", Int.MIN_VALUE)
        val claimErrorMsg = claim.optString("error_msg", "") + claim.optString("msg", "")
        val claimSsaCode = claim.optString("ssaCode", "")

        // status=1 成功，或 error_code=0 也视为成功；131001=今日已领取，放行 upgrade
        val claimOk = claimStatus == 1 || claimErrorCode == 0
        val claimAlreadyDone = claimErrorCode == 131001
        if (!claimOk && !claimAlreadyDone) {
            if (claimErrorCode == 20028 && claimSsaCode.isNotEmpty()) {
                return Triple(false, "", claimSsaCode)
            }
            if (claimErrorMsg.isNotEmpty()) {
                return Triple(false, ensureChineseOrFallback(claimErrorMsg), null)
            }
            return Triple(false, mapYouthVipError(claimErrorCode), null)
        }

        // 2. 升级为概念版 —— 必须严格判断，不能静默吞失败
        val upgrade = api.youthDayVipUpgrade().getOrNull()
        val upgradeStatus = upgrade?.optInt("status", -1) ?: -1
        val upgradeErrorCode = upgrade?.optInt("error_code", Int.MIN_VALUE) ?: Int.MIN_VALUE
        val upgradeMsg = upgrade?.let { it.optString("error_msg", "") + it.optString("msg", "") } ?: ""
        val upgradeSsaCode = upgrade?.optString("ssaCode", "") ?: ""

        // "已升级过/今日已升级"等属于正常状态（之前已成功升级），视为本次签到仍成功
        val upgradeAlreadyDone = upgradeErrorCode == 20030 ||
            upgradeErrorCode == 131001 ||
            containsUpgradeDoneHint(upgradeMsg)
        val upgradeOk = upgrade != null &&
            (upgradeStatus == 1 || upgradeErrorCode == 0 || upgradeAlreadyDone)

        if (!upgradeOk) {
            // 第一步已成功（用户已领到畅听 VIP），文案要如实说明
            if (upgradeErrorCode == 20028 && upgradeSsaCode.isNotEmpty()) {
                return Triple(false, "", upgradeSsaCode)
            }
            val tail = if (upgradeMsg.isNotEmpty()) ensureChineseOrFallback(upgradeMsg)
            else mapYouthVipError(upgradeErrorCode)
            return Triple(false, "畅听VIP已领取，但升级概念版失败：$tail", null)
        }

        // 两步都成功 —— 标记今天已签并刷新信息
        try {
            refreshVipDetail()
        } catch (e: Exception) {
            Log.e(TAG, "signInOnce refreshVipDetail", e)
        }
        try {
            loadMonthRecord(currentMonthKey())
        } catch (e: Exception) {
            Log.e(TAG, "signInOnce loadMonthRecord", e)
        }
        KugouVipState.markSignedToday()
        KugouVipState.todayUpgradedToConcept = true
        return Triple(true, "签到成功（概念版会员）", null)
    }

    // ==================== 自动签到（宽松判定） ====================

    /**
     * 启动自动签到：开关开启 + 本地今天未签才执行（本地已签则完全不拉起 server）。
     * 宽松版：claim 通过（或 131001 已领取）即标记当天已签，upgrade 失败不阻断标记。
     */
    suspend fun autoSignInIfNeeded() {
        if (!KugouApiService.isLoggedIn()) return
        if (!AutoReceiveVip) return
        if (KugouVipState.isSignedToday()) return
        try {
            val receiveDay = resolveReceiveDay()
            Log.d(TAG, "autoSignIn receiveDay=$receiveDay")

            val claim = api.youthDayVip(receiveDay).getOrNull()
            val claimOk = claim != null &&
                (claim.optInt("status", -1) == 1 || claim.optInt("error_code", Int.MIN_VALUE) == 0)
            // 131001 = 今日已领取，也视为成功
            val alreadyClaimed = claim?.optInt("error_code", Int.MIN_VALUE) == 131001
            if (!claimOk && !alreadyClaimed) {
                Log.d(TAG, "autoSignIn claim failed: $claim")
                return
            }

            val upgrade = api.youthDayVipUpgrade().getOrNull()
            val upgradeOk = upgrade != null && (
                upgrade.optInt("status", -1) == 1 ||
                    upgrade.optInt("error_code", Int.MIN_VALUE) == 0 ||
                    upgrade.optInt("error_code", Int.MIN_VALUE) == 20030 ||
                    upgrade.optInt("error_code", Int.MIN_VALUE) == 131001
                )
            if (upgradeOk) KugouVipState.todayUpgradedToConcept = true

            KugouVipState.markSignedToday()
            runCatching { refreshVipDetail() }
            runCatching { loadMonthRecord(currentMonthKey()) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "autoSignInIfNeeded exception", e)
        }
    }

    // ==================== 听歌领取 / 广告领取 ====================

    /** 听歌上报领取 VIP。error_code 130012（今日已领取）视为成功。 */
    suspend fun listenSongClaim(): Pair<Boolean, String> {
        if (KugouVipState.listenRunning) return false to "请求进行中"
        if (!KugouApiService.isLoggedIn()) return false to "请先登录"
        KugouVipState.listenRunning = true
        try {
            val resp = api.youthListenSong().getOrNull()
                ?: return false to "请求无响应，请稍后重试"
            if (resp.optInt("status", -1) == 1) return true to "听歌领取成功"
            val code = resp.optInt("error_code", Int.MIN_VALUE)
            if (code == 130012) return true to "今日已领取"
            return false to mapListenAdError(code)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false to friendlyNetworkError(e)
        } finally {
            KugouVipState.listenRunning = false
        }
    }

    /**
     * 广告播放上报领取 VIP：最多 8 次，成功且非最后一次间隔 30 秒，
     * error_code 30002（次数已用光）提前正常停止（对齐 kgcheckin main.js）。
     * 挂调用方协程（签到页 scope），离开页面即取消，不后台空转。
     */
    suspend fun claimAdVip(): Pair<Boolean, String> {
        if (KugouVipState.adRunning) return false to "请求进行中"
        if (!KugouApiService.isLoggedIn()) return false to "请先登录"
        KugouVipState.adRunning = true
        var success = 0
        try {
            for (i in 1..8) {
                val resp = api.youthAdVip().getOrNull()
                val status = resp?.optInt("status", -1) ?: -1
                val code = resp?.optInt("error_code", Int.MIN_VALUE) ?: Int.MIN_VALUE
                when {
                    // 与 Dart parseAdClaimOutcome 一致：status==1 成功；30002 次数用光；其余失败
                    status == 1 -> {
                        success++
                        if (i != 8) delay(30_000)
                    }
                    code == 30002 -> return true to if (success > 0) {
                        "广告领取 $success/8 次，今日次数已用光"
                    } else "今日广告次数已用光"
                    else -> return false to if (success > 0) {
                        "领取 $success/8 次后失败：${mapListenAdError(code)}"
                    } else "领取失败：${mapListenAdError(code)}"
                }
            }
            return true to "广告领取 $success/8 次"
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return false to if (success > 0) {
                "领取 $success/8 次后中断：${friendlyNetworkError(e)}"
            } else friendlyNetworkError(e)
        } finally {
            KugouVipState.adRunning = false
        }
    }

    // ==================== 20028 二次验证（腾讯滑块） ====================

    /**
     * 20028 处理：获取验证码格式（v_type/txappid）→ 交由 UI 弹腾讯滑块 → 提交验证。
     * 返回 true 表示验证通过（可重试原请求）；false 表示取消/失败/类型不支持。
     * 目前仅支持 v_type=23（腾讯滑块），其他类型对齐 md3Music 暂不支持。
     */
    private suspend fun handleVerifyCaptcha(eventid: String): Boolean {
        val info = api.getVerifyInfo(eventid).getOrNull() ?: return false
        val data = info.optJSONObject("data") ?: return false
        val vType = data.optInt("v_type", 23)
        val txappid = data.optString("txappid", "")
        if (txappid.isEmpty()) return false
        if (vType != 23) {
            Log.w(TAG, "unsupported verify v_type=$vType")
            return false
        }

        val verifycode = KugouVipState.awaitCaptcha(
            KugouVipState.PendingCaptcha(eventid = eventid, vType = vType, txappid = txappid)
        ) ?: return false

        val result = api.verifyUserInfo(eventid, vType, verifycode).getOrNull() ?: return false
        return result.optInt("status", -1) == 1 || result.optInt("error_code", Int.MIN_VALUE) == 0
    }

    // ==================== 解析 ====================

    /** /user/vip/detail → [KugouVipDetail]（结构 data.is_vip + data.busi_vip[]）。 */
    fun parseVipDetail(json: JSONObject): KugouVipDetail? = runCatching {
        val data = json.optJSONObject("data") ?: json

        val busiList = mutableListOf<KugouBusiVip>()
        var isVip = data.optInt("is_vip", 0) == 1
        var latestEnd: String? = null
        var conceptEnd: String? = null
        var listenEnd: String? = null

        val rawBusi = data.optJSONArray("busi_vip")
        if (rawBusi != null) {
            for (i in 0 until rawBusi.length()) {
                val b = rawBusi.optJSONObject(i) ?: continue
                val productType = b.optString("product_type", "")
                val bIsVip = b.optInt("is_vip", 0) == 1
                val endRaw = b.optString("vip_end_time", "")
                busiList += KugouBusiVip(productType, bIsVip, endRaw.ifEmpty { null })
                if (!bIsVip) continue
                isVip = true
                // vip_end_time 形如 "2026-09-13 23:59:59"，取日期部分 yyyy-MM-dd
                val endDay = endRaw.takeIf { it.length >= 10 }?.substring(0, 10)
                if (endDay != null) {
                    if (latestEnd == null || endDay > latestEnd) latestEnd = endDay
                    when (productType) {
                        "svip" -> conceptEnd = endDay
                        "tvip" -> listenEnd = endDay
                    }
                }
            }
        }
        KugouVipDetail(
            nickname = data.optString("nickname", "").ifEmpty { null },
            isVip = isVip,
            expireTime = latestEnd,
            conceptExpireTime = conceptEnd,
            listenExpireTime = listenEnd,
            busiVipList = busiList
        )
    }.getOrNull()

    /**
     * /youth/month/vip/record → 记录列表。
     * 结构兼容 data.list / data.record_list / 根级 list 三种位置（对齐 md3Music）。
     */
    fun parseMonthRecords(json: JSONObject): List<KugouYouthDayRecord> = runCatching {
        val data = json.optJSONObject("data")
        val arr = data?.optJSONArray("list")
            ?: data?.optJSONArray("record_list")
            ?: json.optJSONArray("list")
        buildList {
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val item = arr.optJSONObject(i) ?: continue
                    add(
                        KugouYouthDayRecord(
                            day = item.optString("day", "").ifEmpty { null },
                            vipType = item.optString("vip_type", "").ifEmpty { null },
                            receiveVip = if (item.has("receive_vip")) item.optInt("receive_vip", 0) else null
                        )
                    )
                }
            }
        }
    }.getOrDefault(emptyList())

    // ==================== 工具 ====================

    /**
     * 签到日 receive_day：优先 /server/now 服务器时间（秒），失败降级本地时间。
     * 对齐 md3Music：ts * 1000 → DateTime → 本地时区 yyyy-MM-dd。
     */
    private suspend fun resolveReceiveDay(): String {
        val ts = api.getServerNow().getOrNull()?.let { json ->
            val dataTs = json.optJSONObject("data")?.optLong("timestamp", -1L) ?: -1L
            val rootTs = json.optLong("timestamp", -1L)
            listOf(dataTs, rootTs).firstOrNull { it > 0 }
        } ?: (System.currentTimeMillis() / 1000)

        val cal = Calendar.getInstance().apply { timeInMillis = ts * 1000 }
        return String.format(
            Locale.US, "%04d-%02d-%02d",
            cal.get(Calendar.YEAR), cal.get(Calendar.MONTH) + 1, cal.get(Calendar.DAY_OF_MONTH)
        )
    }

    /** 优先返回中文 errorMsg；不含中文（视为英文不可友好展示）则用通用兜底。 */
    private fun ensureChineseOrFallback(errorMsg: String): String =
        if (containsChinese(errorMsg)) errorMsg else "签到失败，请稍后重试"

    private fun containsChinese(s: String): Boolean =
        s.any { it in '\u4e00'..'\u9fa5' }

    /** 网络层/系统异常 → 友好中文提示（对齐 md3Music _friendlyNetworkError）。 */
    private fun friendlyNetworkError(e: Exception): String {
        val raw = e.message ?: e.toString()
        return when {
            listOf("SocketException", "Connection refused", "Failed host lookup", "Network is unreachable")
                .any { raw.contains(it, ignoreCase = true) } -> "网络连接失败，请检查网络后重试"
            raw.contains("timeout", ignoreCase = true) -> "网络请求超时，请稍后重试"
            listOf("HandshakeException", "CertificateException", "CERTIFICATE").any { raw.contains(it) } ->
                "安全证书校验失败，请检查网络环境"
            raw.contains("JSONException") || raw.contains("FormatException") -> "服务器返回数据格式异常，请稍后重试"
            raw.contains("HttpException") || raw.contains("HTTP ") -> "服务器异常，请稍后重试"
            else -> "签到失败，请稍后重试"
        }
    }

    /** 升级接口返回文案中出现"已升级/已领取/already"等视为已升级过（对齐 _containsUpgradeDoneHint）。 */
    private fun containsUpgradeDoneHint(msg: String): Boolean =
        msg.contains("已升级") || msg.contains("已领取") || msg.contains("已签到") ||
            msg.contains("升级过") || msg.contains("已领取过") || msg.contains("已是") ||
            msg.lowercase(Locale.US).contains("already")

    /** youth 签到错误码 → 可读中文（对齐 _mapYouthVipError）。 */
    private fun mapYouthVipError(errorCode: Int): String = when (errorCode) {
        20006 -> "签名错误，请重新登录后重试"
        20010 -> "参数错误（领取日期格式有误）"
        20018 -> "登录已过期，请重新登录"
        20028 -> "酷狗拒绝领取：账号可能不符合资格，或该功能已停用"
        20030 -> "已升级过概念版，无需重复领取"
        20033, 131001 -> "今日已签到，无需重复领取"
        20034 -> "领取失败：领取次数已达上限"
        else -> "签到失败，请稍后重试（错误码：${if (errorCode == Int.MIN_VALUE) "未知" else errorCode.toString()}）"
    }

    /** 听歌/广告领取错误码 → 可读中文（对齐 _mapListenAdError）。 */
    private fun mapListenAdError(errorCode: Int): String = when (errorCode) {
        130012 -> "今日已领取"
        30002 -> "今日广告次数已用光"
        20006 -> "签名错误，请重新登录后重试"
        20010 -> "参数错误"
        20018 -> "登录已过期，请重新登录"
        20028 -> "酷狗拒绝领取：账号可能不符合资格，或该功能已停用"
        else -> "领取失败，请稍后重试（错误码：${if (errorCode == Int.MIN_VALUE) "未知" else errorCode.toString()}）"
    }
}
