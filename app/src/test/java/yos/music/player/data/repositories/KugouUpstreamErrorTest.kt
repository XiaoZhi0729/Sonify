package yos.music.player.data.repositories

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * 鉴权失效识别。这几条断言全部照抄真机响应体（PKT110 用户报告 09:57 段），
 * 目的就是把"illegal_key 必须是独立一类"钉住：历史上它被当成普通 IOException，
 * 于是既享受了不该有的退避重试，又被逐首写进负缓存，一次爆发刷出 98 条 RESOLVE_FAIL。
 */
class KugouUpstreamErrorTest {

    /** 真机原文（未压缩）：未登录/登录态失效时 /song/url 的响应。 */
    private val illegalKeyBody =
        """{"error_code":31833,"trans_param":{"display":0,"display_rate":0},""" +
            """"auth_through":[],"status":0,"error":"illegal key"}"""

    @Test
    fun detectsTheRealIllegalKeyBody() {
        assertTrue(KugouUpstreamError.isAuthInvalid(502, illegalKeyBody))
    }

    @Test
    fun detectsEitherMarkerAlone() {
        // 只有错误码（有的版本不带 error 明文）
        assertTrue(KugouUpstreamError.isAuthInvalid(502, """{"error_code":31833}"""))
        // 只有明文；以及带空格的序列化写法
        assertTrue(KugouUpstreamError.isAuthInvalid(502, """{"error":"ILLEGAL KEY"}"""))
        assertTrue(KugouUpstreamError.isAuthInvalid(502, """{"error_code": 31833}"""))
    }

    @Test
    fun doesNotPretendOtherFailuresAreAuth() {
        // 版权/付费拦截：这一首的问题，跳下一首才对
        assertFalse(
            KugouUpstreamError.isAuthInvalid(
                200,
                """{"status":3,"priv_status":0,"trans_param":{"pay_block_tpl":1,"cpy_map":"2"},"error_code":0}"""
            )
        )
        // 上游 5xx 但没有鉴权字段：仍然是网络/服务端抖动
        assertFalse(KugouUpstreamError.isAuthInvalid(500, "Gateway Timeout"))
        // 空体/缺体：不猜
        assertFalse(KugouUpstreamError.isAuthInvalid(502, ""))
        assertFalse(KugouUpstreamError.isAuthInvalid(502, null))
    }

    @Test
    fun twoHundredIsNeverAuthFailure() {
        // 2xx 同名字段可能是业务自己的返回结构，不能凭字段名判鉴权
        assertFalse(KugouUpstreamError.isAuthInvalid(200, illegalKeyBody))
    }

    /** 真机原文：风控"本次请求需要验证"（HTTP 502，注意字段名是 errcode 不是 error_code）。 */
    private val needVerifyBody =
        """{"errcode":20028,"status":0,"error":"本次请求需要验证","edt":"hLnL98Qpft3bYZWh"}"""

    @Test
    fun detectsTheRealNeedVerifyBody() {
        // 502 + 真机体：既算"需要验证"，也算账号级鉴权失效（重试后仍失败 → 挂起而非跳歌）
        assertTrue(KugouUpstreamError.isNeedVerify(needVerifyBody))
        assertTrue(KugouUpstreamError.isAuthInvalid(502, needVerifyBody))
        // 字段/明文二选一，带空格也能认
        assertTrue(KugouUpstreamError.isNeedVerify("""{"errcode": 20028}"""))
        assertTrue(KugouUpstreamError.isNeedVerify("""{"error":"本次请求需要验证"}"""))
    }

    @Test
    fun needVerifyDoesNotSwallowOtherFailures() {
        // 版权/付费拦截不是"需要验证"
        assertFalse(KugouUpstreamError.isNeedVerify("""{"status":3,"priv_status":0,"error_code":0}"""))
        assertFalse(KugouUpstreamError.isNeedVerify("Gateway Timeout"))
        assertFalse(KugouUpstreamError.isNeedVerify(""))
        assertFalse(KugouUpstreamError.isNeedVerify(null))
        // isNeedVerify 不看状态码：HTTP 200 形态（MD3 常态）也能认（resolvePlayUrl 的兜底）
        assertTrue(KugouUpstreamError.isNeedVerify("""{"errcode":20028,"status":1}"""))
    }

    @Test
    fun findsTheMarkerThroughWrapperLayers() {
        // DataSource/Loader 会一层层往外裹，分类必须能沿因果链认出来
        val wrapped = IOException(
            "在线歌曲 URL 解析失败",
            IOException("外层包装", KugouAuthInvalidException("整账号鉴权失效"))
        )
        assertTrue(KugouUpstreamError.hasAuthInvalidCause(wrapped))
        assertTrue(KugouUpstreamError.hasAuthInvalidCause(KugouAuthInvalidException("raw")))
        assertFalse(KugouUpstreamError.hasAuthInvalidCause(IOException("plain")))
        assertFalse(KugouUpstreamError.hasAuthInvalidCause(null))
    }

    /** 真机原文：精选歌单 gcid 无效时 /playlist/track/all 的响应（HTTP 502）。 */
    private val listFileMissingBody =
        """{"error_code":20010,"errmsg":"get other listfile fail","data":{},"status":0}"""

    @Test
    fun detectsListFileMissing() {
        assertTrue(KugouUpstreamError.isListFileMissing(listFileMissingBody))
        // 只有错误码 / 只有明文 / 带空格的序列化都能认
        assertTrue(KugouUpstreamError.isListFileMissing("""{"error_code":20010}"""))
        assertTrue(KugouUpstreamError.isListFileMissing("""{"errmsg":"get other listfile fail"}"""))
        assertTrue(KugouUpstreamError.isListFileMissing("""{"error_code": 20010}"""))
    }

    @Test
    fun listFileMissingDoesNotSwallowOtherFailures() {
        // 鉴权失效不是 listfile 缺失
        assertFalse(KugouUpstreamError.isListFileMissing(illegalKeyBody))
        assertFalse(KugouUpstreamError.isListFileMissing(needVerifyBody))
        // 同族的 20017（specialid 当 listid）是另一个错误码，不在此列
        assertFalse(KugouUpstreamError.isListFileMissing("""{"error_code":20017}"""))
        // 空体/缺体：不猜
        assertFalse(KugouUpstreamError.isListFileMissing(""))
        assertFalse(KugouUpstreamError.isListFileMissing(null))
    }
}
