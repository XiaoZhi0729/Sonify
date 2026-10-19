package yos.music.player.data.repositories

/**
 * `/song/url`（v5/url）与 `/song/url/new`（v6/priv_url）响应体的判读结果。
 *
 * 历史实现把「HTTP 200 但没有 url」一律当成 [Blocked]（确凿拒播），
 * 因此需要区分请求级错误、档位不可用与付费/版权证据，避免把错误写入可播性表。
 */
internal sealed interface SongUrlOutcome {

    /** 拿到可用链接。[quality] 是服务端回写的实际档位（不可识别为 null，不猜）。 */
    data class Ok(val url: String, val quality: String?) : SongUrlOutcome

    /**
     * **这一档**没有音源：降档链继续往下试（[KugouRepository.resolvePlayUrlWithFallback]）。
     * 它不是"整曲被封"，因此**不写可播性表、不置灰**。
     */
    data object TierUnavailable : SongUrlOutcome

    /** 确凿拒播（版权/付费硬拦）：唯一允许置灰的情形。 */
    data class Blocked(val reason: String, val detail: String) : SongUrlOutcome

    /** 请求需要验证，应进入设备/风控恢复，不确证登录态失效。 */
    data object VerificationRequired : SongUrlOutcome

    /** 请求签名被拒绝，应检查/恢复签名与身份。 */
    data object SignatureRejected : SongUrlOutcome

    /** song/url 拒绝 token，应进入凭据恢复，不直接置灰歌曲。 */
    data object TokenRejected : SongUrlOutcome
}

/**
 * 纯函数：把 `/song/url(|/new)` 的响应体判读成 [SongUrlOutcome]。
 *
 * 入参是 `toPlainAny(JSONObject)` 展开后的纯 stdlib 结构（与
 * [yos.music.player.native.parsePhoneLoginResponse] 同一范式），因此可在纯 JVM 单测里覆盖。
 *
 * 判据来源（对齐 md3Music 的 Dart 侧 kugou_api_client.dart:948-1097，并用真机响应校准）：
 *  - `data` 可能是对象、**数组**（priv_url 实测如此），或干脆没有 data 层（拒播体平铺在顶层）；
 *  - `fail_process` 含 `buy` = 该档需要购买/VIP：非标准档只是"这一档拿不到"（降档重试），
 *    标准档还拿不到才算确凿拒播；
 *  - `priv_status == 0` = 上游没认会员，返回的是试听片段，非标准档不接受；
 *  - `status == 3` + `trans_param.cpy_map`/`pay_block_tpl` = 版权/付费硬拦 → [SongUrlOutcome.Blocked]；
 *  - 20028 / 31833 / 20017–20020 分别返回验证、签名、token 拒绝，交给上层恢复；
 *  - **其余一切"没有 url"都只是 [SongUrlOutcome.TierUnavailable]**（不置灰、不落盘）。
 */
internal fun interpretSongUrlBody(root: Map<*, *>, requestedQuality: String): SongUrlOutcome {
    // data 层取用顺序：对象 → 数组首元素（实测 priv_url）→ 没有 data 层时用顶层
    val data: Map<*, *> = when (val d = root["data"]) {
        is Map<*, *> -> d
        is List<*> -> d.firstOrNull() as? Map<*, *> ?: root
        else -> root
    }

    val url = extractUrl(data["url"] ?: data["play_url"])
    val failBuy = failProcessOf(data).contains("buy")
    val privStatus = numOf(data["priv_status"]) ?: numOf(root["priv_status"])
    val status = numOf(data["status"]) ?: numOf(root["status"]) ?: -1

    if (url.isNotEmpty()) {
        // 需要购买 / 上游没认会员（试听片段）：这一档不算数，让降档链继续往下试。
        // 标准档本就是免费档，两条件都不参与判定（对齐 Dart：quality==standard 时跳过 buy 分支）。
        if (requestedQuality != KugouQuality.STANDARD && (failBuy || privStatus == 0)) {
            return SongUrlOutcome.TierUnavailable
        }
        val stamped = strOf(data["quality"]) ?: strOf(root["quality"])
        return SongUrlOutcome.Ok(url, KugouQuality.parse(stamped))
    }

    // 无 url：先识别请求级错误，不据此认定 token 已过期或歌曲不可播。
    when (KugouUpstreamError.classify(root, songUrlContext = true)) {
        KugouUpstreamErrorType.VerificationRequired -> return SongUrlOutcome.VerificationRequired
        KugouUpstreamErrorType.SignatureRejected -> return SongUrlOutcome.SignatureRejected
        KugouUpstreamErrorType.TokenRejected -> return SongUrlOutcome.TokenRejected
        null -> Unit
    }

    val detail = buildString {
        append("status=").append(status)
        append(" priv_status=").append(privStatus ?: -1)
        append(" error_code=").append(numOf(data["error_code"]) ?: numOf(root["error_code"]) ?: 0)
        append(" fail_process=").append(failProcessOf(data))
    }

    // 该档需要购买/VIP：非标准档交给降档链，标准档仍拿不到 = 确凿拒播
    if (failBuy) {
        return if (requestedQuality == KugouQuality.STANDARD) {
            SongUrlOutcome.Blocked("暂无可用音源（付费或权限限制）", detail)
        } else {
            SongUrlOutcome.TierUnavailable
        }
    }

    if (status == 3) {
        val trans = (data["trans_param"] as? Map<*, *>) ?: (root["trans_param"] as? Map<*, *>)
        val payBlockTpl = numOf(trans?.get("pay_block_tpl")) ?: 0
        val cpyMap = numOf(trans?.get("cpy_map")) ?: 0
        val reason = when {
            cpyMap != 0 && payBlockTpl == 1 -> "暂无可用音源（版权与付费限制）"
            cpyMap != 0 -> "暂无可用音源（版权限制）"
            payBlockTpl == 1 -> "暂无可用音源（付费或权限限制）"
            else -> return SongUrlOutcome.TierUnavailable
        }
        return SongUrlOutcome.Blocked(reason, detail)
    }

    // 认不出的空响应：**不是拒播证据**。历史上这里一律判 Blocked，正是误灰的根因——
    // 交给降档链与上层重试，可播性表不产生任何结论。
    return SongUrlOutcome.TierUnavailable
}

/** url 字段可能是字符串、数组（首个非空）或对象（取内部 url）。 */
private fun extractUrl(raw: Any?): String = when (raw) {
    is String -> raw.trim()
    is List<*> -> raw.asSequence().map { extractUrl(it) }.firstOrNull { it.isNotEmpty() } ?: ""
    is Map<*, *> -> extractUrl(raw["url"] ?: raw["play_url"])
    else -> ""
}

/** fail_process 可能是数组（实测 `["pkg","buy"]`）或字符串。 */
private fun failProcessOf(data: Map<*, *>): List<String> = when (val raw = data["fail_process"]) {
    is List<*> -> raw.mapNotNull { it?.toString() }
    is String -> listOf(raw)
    else -> emptyList()
}

private fun numOf(value: Any?): Int? = when (value) {
    is Number -> value.toInt()
    is String -> value.trim().toIntOrNull()
    else -> null
}

private fun strOf(value: Any?): String? = when (value) {
    is String -> value.takeIf { it.isNotBlank() }
    is Number -> value.toString()
    else -> null
}
