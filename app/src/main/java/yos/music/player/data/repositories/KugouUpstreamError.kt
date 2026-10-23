package yos.music.player.data.repositories

import java.io.IOException

/** 仅在恢复流程完成、仍已确认凭据失效时使用；单次上游错误体不能确证。 */
class KugouAuthInvalidException(detail: String) :
    IOException("鉴权已确认失效: $detail")

/** [eventId] 是上游 20028 附带的二次验证凭据；[edt] 是另一种一次性凭据（真机 /song/url 常见）。 */
class KugouVerificationRequiredException(
    detail: String,
    val eventId: String? = null,
    val edt: String? = null
) : IOException("请求需要安全验证: $detail")

class KugouSignatureRejectedException(detail: String) :
    IOException("请求签名被拒绝: $detail")

class KugouTokenRejectedException(detail: String) :
    IOException("请求 token 被拒绝: $detail")

/** 单次响应能提供的证据，不代表恢复后确认的登录态失效。 */
enum class KugouUpstreamErrorType {
    VerificationRequired,
    SignatureRejected,
    TokenRejected
}

/** 上游错误体的识别。纯函数、无 Android/JSON 依赖。 */
object KugouUpstreamError {

    /** 旧常量名保留兼容；31833 表示签名被拒绝，不能确证 token 过期。 */
    const val AUTH_INVALID_CODE = 31833
    const val NEED_VERIFY_CODE = 20028

    /** 仅限精选歌单 listfile 接口；其他接口的同码可能另有含义。 */
    const val LIST_FILE_MISSING_CODE = 20010
    private const val LIST_FILE_MISSING_MARKER = "get other listfile fail"
    private const val SIGNATURE_REJECTED_MARKER = "illegal key"
    private const val NEED_VERIFY_MARKER = "本次请求需要验证"
    private const val TOKEN_ERROR_MARKER = "token api error"
    private val SONG_URL_TOKEN_CODES = setOf(20017, 20018, 20019, 20020)
    private val codeFields = listOf("error_code", "errcode")
    private val messageFields = listOf("error", "message", "errmsg")

    /**
     * 接收 JSON 展开的纯 Map，检查顶层与 data 对象/数组首项；错误码接受数字或字符串。
     * 20017–20020 在歌单等接口可能表示参数错误，因此只有 songUrlContext=true 才识别
     * TokenRejected（包括 token api error 明文）。验证优先于签名，签名优先于 token。
     * 返回 null 表示未识别；任何返回值都不是恢复后确证的鉴权失效。
     */
    fun classify(root: Map<*, *>, songUrlContext: Boolean = false): KugouUpstreamErrorType? {
        val data = when (val raw = root["data"]) {
            is Map<*, *> -> raw
            is List<*> -> raw.firstOrNull() as? Map<*, *>
            else -> null
        }
        val nodes = listOfNotNull(root, data)
        val codes = nodes.flatMap { node -> codeFields.mapNotNull { numOf(node[it]) } }
        val messages = nodes.flatMap { node -> messageFields.mapNotNull { node[it] as? String } }
        return when {
            NEED_VERIFY_CODE in codes || messages.any { it.contains(NEED_VERIFY_MARKER) } ->
                KugouUpstreamErrorType.VerificationRequired
            AUTH_INVALID_CODE in codes || messages.any { it.contains(SIGNATURE_REJECTED_MARKER, ignoreCase = true) } ->
                KugouUpstreamErrorType.SignatureRejected
            songUrlContext && (codes.any { it in SONG_URL_TOKEN_CODES } ||
                messages.any { it.contains(TOKEN_ERROR_MARKER, ignoreCase = true) }) ->
                KugouUpstreamErrorType.TokenRejected
            else -> null
        }
    }

    /** 兼容原始响应体入口；HTTP 200 与非 2xx 均可能要求验证。 */
    fun isNeedVerify(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return NEED_VERIFY_BODY_CODE.containsMatchIn(body) || body.contains(NEED_VERIFY_MARKER)
    }

    private val NEED_VERIFY_BODY_CODE = Regex("\"(?:errcode|error_code)\"\\s*:\\s*(?:\"20028\"|20028)(?=\\s*[,}])")

    fun isNeedVerifyPlain(root: Map<*, *>): Boolean =
        classify(root) == KugouUpstreamErrorType.VerificationRequired

    /** 旧名字保留兼容：仅表示 song/url 拒绝 token，不确证登录态过期。 */
    fun isSongUrlTokenInvalid(root: Map<*, *>): Boolean =
        classify(root, songUrlContext = true) == KugouUpstreamErrorType.TokenRejected

    private fun numOf(value: Any?): Int? = when (value) {
        is Number -> value.toInt().takeIf { it.toDouble() == value.toDouble() }
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    /** 响应体里的 error_code（顶层或 data 层首个可识别值），只用于诊断与恢复判定。 */
    fun errorCodeOf(root: Map<*, *>): Int? {
        val nodes = listOfNotNull(root, dataNodeOf(root))
        return nodes.flatMap { node -> codeFields.mapNotNull { numOf(node[it]) } }.firstOrNull()
    }

    /**
     * 20028 响应携带的二次验证凭据（酷狗 `eventid`，Dart 侧叫 ssaCode）。
     * 只认明确字段名，不做模糊匹配——认不出就当没有，也绝不拿别的字段冒充验证入口。
     */
    fun verificationEventId(root: Map<*, *>): String? {
        val keys = listOf("eventid", "event_id", "ssaCode", "ssa_code")
        return listOfNotNull(root, dataNodeOf(root)).firstNotNullOfOrNull { node ->
            keys.firstNotNullOfOrNull { key ->
                (node[key] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            }
        }
    }

    private fun dataNodeOf(root: Map<*, *>): Map<*, *>? = when (val raw = root["data"]) {
        is Map<*, *> -> raw
        is List<*> -> raw.firstOrNull() as? Map<*, *>
        else -> null
    }

    /**
     * 20028 响应里的 `edt`（真机 /song/url 常见形态：只有 edt、没有 eventid）。
     *
     * 它原本是请求签名用的一次性凭据，能不能当 eventid 喂给 /get/verify/info 属于**未验证的猜测**，
     * 所以这里只把它当作"可以试一试的备选"，由上层决定要不要试、试失败就降级。
     */
    fun verificationEdt(root: Map<*, *>): String? =
        listOfNotNull(root, dataNodeOf(root)).firstNotNullOfOrNull { node ->
            (node["edt"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        }

    /**
     * 兼容旧调用签名，但单个 HTTP 状态/响应体不足以确证鉴权失效。
     * 调用方应改用 classify 执行相应恢复，恢复后确认失效才构造 KugouAuthInvalidException。
     */
    @Suppress("UNUSED_PARAMETER")
    fun isAuthInvalid(httpCode: Int, body: String?): Boolean = false

    /** 沿因果链只识别恢复后已确认的失效，不混入请求验证/签名/token 拒绝。 */
    fun hasAuthInvalidCause(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { it is KugouAuthInvalidException }

    /** 精选歌单 listfile 查无此物，独立于账号/请求错误。 */
    fun isListFileMissing(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return body.replace(" ", "").contains("\"error_code\":$LIST_FILE_MISSING_CODE") ||
            body.contains(LIST_FILE_MISSING_MARKER)
    }
}
