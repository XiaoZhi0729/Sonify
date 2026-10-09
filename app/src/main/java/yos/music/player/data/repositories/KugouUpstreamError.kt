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

    /** 响应体里的明文标记（与 error_code 二选一命中即可，服务端两处都会带）。 */
    private const val AUTH_INVALID_MARKER = "illegal key"

    /**
     * 是不是鉴权失效。只看非 2xx 的响应体：2xx 时同样的字段形状可能是别的意思，
     * 不猜。响应体空格不定（`{"error_code": 31833}` 也会出现），先压掉空格再匹配。
     */
    fun isAuthInvalid(httpCode: Int, body: String?): Boolean {
        if (httpCode in 200..299) return false
        if (body.isNullOrBlank()) return false
        val compact = body.replace(" ", "")
        return compact.contains("\"error_code\":$AUTH_INVALID_CODE") ||
            body.contains(AUTH_INVALID_MARKER, ignoreCase = true)
    }

    /** 沿因果链找鉴权失效：包装层（DataSource/Loader）会把它裹在 IOException 里。 */
    fun hasAuthInvalidCause(error: Throwable?): Boolean =
        generateSequence(error) { it.cause }.any { it is KugouAuthInvalidException }
}
