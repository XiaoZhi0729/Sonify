package yos.music.player.data.repositories

import java.io.IOException

/**
 * 上游**鉴权失效**（不是某一首曲目的问题，也不是网络问题）：
 * `/song/url` 返回 HTTP 502 + `{"error_code":31833,"error":"illegal key"}`，
 * 实测对应未登录/登录态过期/sign_key 计算错（Rust 侧 userid 参与签名，见
 * [yos.music.player.native.KugouApiService] 的 `/song/url` 注释）。
 *
 * 它必须与另外两类失败分开，历史故障就是被混成一类才放大成"整晚静默停播"：
 *  1. 与版权/付费拦截（[KugouRepository.KugouPlayBlockedException]）不同——拦截是**这首歌**
 *     拿不到，跳下一首是对的；鉴权失效是**整个账号**都拿不到，跳多少首都一样。
 *  2. 与网络失败（普通 [IOException]）不同——网络失败值得让 ExoPlayer 退避重试一次，
 *     鉴权失效重试只是把 1 秒一次的 502 刷成刷屏（真机一次爆发：22 个 hash、98 条
 *     RESOLVE_FAIL、每首 3.4 秒才认输）。
 *
 * 独立成顶层类（而不是嵌在 `object KugouRepository` 里）：`KugouApiService` 要构造它，
 * 而两个 `object` 互相在 `<clinit>` 里碰对方会踩初始化环。
 */
class KugouAuthInvalidException(detail: String) :
    IOException("鉴权失效 illegal_key: $detail")

/**
 * 上游错误体的识别。纯函数、无 Android 依赖，便于单测。
 */
object KugouUpstreamError {

    /** 酷狗侧鉴权/签名失效的错误码（实测固定值）。 */
    const val AUTH_INVALID_CODE = 31833

    /**
     * 风控要求"本次请求需要验证"的业务码（实测固定 20028）。
     * 注意字段名是 **errcode**（不是 error_code）：设备身份/sign_key 失效时上游返回
     * `{"errcode":20028,"status":0,"error":"本次请求需要验证"}`。与 illegal_key 同类——
     * 整账号都拿不到 URL，逐首跳只是把同一个 502 刷成屏。
     */
    const val NEED_VERIFY_CODE = 20028

    /** 响应体里的明文标记（与 error_code 二选一命中即可，服务端两处都会带）。 */
    private const val AUTH_INVALID_MARKER = "illegal key"

    /** 需要安全验证的明文（与 errcode 二选一命中即可）。 */
    private const val NEED_VERIFY_MARKER = "本次请求需要验证"

    /**
     * 精选歌单 listfile 不存在/键无效的业务码。2026-10-13 真机：
     * `HTTP 502 + {"error_code":20010,"errmsg":"get other listfile fail","data":{},"status":0}`，
     * 由 `/playlist/track/all`（上游 `/pubsongs/v2/get_other_list_file_nofilt`）在
     * `global_collection_id` 传成数字 specialid 时返回——同族还有 20017。
     *
     * **这是确定性错误**（该键不存在），不是抖动：按 HTTP 状态重试只会把同一个 502 刷 3 遍。
     *
     * 注意：`20010` 在 [KugouVipRepository] 的签到链里另有含义（领取日期参数错误），
     * 故 [isListFileMissing] 只应在本接口链路调用，勿全局复用。
     */
    const val LIST_FILE_MISSING_CODE = 20010

    /** 20010 的明文标记（与 error_code 二选一命中即可；上游两处都会带）。 */
    private const val LIST_FILE_MISSING_MARKER = "get other listfile fail"

    /**
     * 是不是"需要安全验证"（风控 20028）。与 [isAuthInvalid] 分开暴露：调用方需要
     * 先做"重注册设备 + 重试一次"的自愈（对齐 md3Music kugou_api_client.dart:1112-1126），
     * 重试仍失败才当账号级失效处理。不区分 2xx/非 2xx——上游两种 HTTP 形态都会带这个体。
     */
    fun isNeedVerify(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        val compact = body.replace(" ", "")
        return compact.contains("\"errcode\":$NEED_VERIFY_CODE") ||
            body.contains(NEED_VERIFY_MARKER)
    }

    /**
     * 与 [isNeedVerify] 同义，但入参是 `toPlainAny` 展开后的纯结构（song/url 判读用）：
     * 展开后 `"errcode":20028` 会变成 `errcode=20028`，故匹配形态放宽。
     */
    fun isNeedVerifyPlain(root: Map<*, *>): Boolean {
        val flat = flattenForMatch(root)
        return flat.contains("errcode=$NEED_VERIFY_CODE") ||
            flat.contains("\"errcode\":$NEED_VERIFY_CODE") ||
            flat.contains(NEED_VERIFY_MARKER)
    }

    private fun flattenForMatch(root: Map<*, *>): String = buildString {
        root.forEach { (k, v) ->
            append(k).append('=')
            when (v) {
                is Map<*, *> -> append(flattenForMatch(v))
                is List<*> -> v.forEach { append(it).append(',') }
                else -> append(v)
            }
            append(';')
        }
    }

    /**
     * song/url 链路实测的「上游不接受这份凭据」业务码。**只在 song/url(|/new) 判读时使用。**
     *
     * - `20017`：priv_url 同时回明文 `"message":"token api error."`；v5/url 回
     *   `{"status":2,"error_code":20017,"priv_status":0}` 且**没有 url 字段**。
     *   2026-10-14 真机取证：同一份 App 凭据连"肯定能播"的歌都拿到这个响应，
     *   而 Anonymous 请求走的是另一条错误（31833 illegal key）——即凭据被读到了、
     *   只是不被接受（登录态过期）。
     * - `20018`：登录已过期（Dart 侧据此刷新 token；见 KugouVipRepository 文案表）。
     *
     * 20017 在**歌单链**里另有「参数错误」含义（见 [LIST_FILE_MISSING_CODE] 注释），
     * 因此本判定严禁全局复用。
     */
    private val SONG_URL_TOKEN_CODES = setOf(20017, 20018, 20019, 20020)

    /** priv_url 的凭据失效明文标记。 */
    private const val TOKEN_ERROR_MARKER = "token api error"

    /**
     * song/url 响应体是不是「凭据不被接受」（账号级问题，**不是这首歌没音源**）。
     *
     * 与 [isAuthInvalid] 分开：后者看 HTTP 状态码、面向 502/illegal key；本判定只看
     * **业务体**——上游把登录态失效包在 HTTP 200 里返回，正是历史误判成"确凿拒播"
     * 并置灰 24 小时的来源。入参是 `toPlainAny` 展开后的纯结构，便于单测。
     */
    fun isSongUrlTokenInvalid(root: Map<*, *>): Boolean {
        val data = (root["data"] as? Map<*, *>) ?: root
        val msg = listOf(data["message"], data["errmsg"], root["message"], root["errmsg"])
            .firstNotNullOfOrNull { it as? String }
            .orEmpty()
        if (msg.contains(TOKEN_ERROR_MARKER, ignoreCase = true)) return true
        val code = numOf(data["error_code"]) ?: numOf(root["error_code"]) ?: return false
        return code in SONG_URL_TOKEN_CODES
    }

    private fun numOf(value: Any?): Int? = when (value) {
        is Number -> value.toInt()
        is String -> value.trim().toIntOrNull()
        else -> null
    }

    /**
     * 是不是鉴权失效。只看非 2xx 的响应体：2xx 时同样的字段形状可能是别的意思，
     * 不猜。响应体空格不定（`{"error_code": 31833}` 也会出现），先压掉空格再匹配。
     * 20028（需要验证）同样算账号级失效——重试过后仍失败就不该再逐首跳。
     */
    fun isAuthInvalid(httpCode: Int, body: String?): Boolean {
        if (httpCode in 200..299) return false
        if (body.isNullOrBlank()) return false
        val compact = body.replace(" ", "")
        return compact.contains("\"error_code\":$AUTH_INVALID_CODE") ||
            body.contains(AUTH_INVALID_MARKER, ignoreCase = true) ||
            isNeedVerify(body)
    }

    /** 沿因果链找鉴权失效：包装层（DataSource/Loader）会把它裹在 IOException 里。 */
    fun hasAuthInvalidCause(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { it is KugouAuthInvalidException }

    /**
     * 是不是「精选歌单 listfile 查无此物」（20010 `get other listfile fail`）。
     * 上游把该错误包在 HTTP 502 里、`data:{}` 为空对象——是**确定性**失败，
     * 重试无意义，应直接提示而不是退避重试 3 次再透传 200 字原始 JSON。
     */
    fun isListFileMissing(body: String?): Boolean {
        if (body.isNullOrBlank()) return false
        return body.replace(" ", "").contains("\"error_code\":$LIST_FILE_MISSING_CODE") ||
            body.contains(LIST_FILE_MISSING_MARKER)
    }
}
