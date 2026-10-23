package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** 请求错误分类与恢复后确证失效的边界，使用纯 Map 和真机响应形态回归。 */
class KugouUpstreamErrorTest {

    /** 真机原文（未压缩）：未登录/登录态失效时 /song/url 的响应。 */
    private val illegalKeyBody =
        """{"error_code":31833,"trans_param":{"display":0,"display_rate":0},""" +
            """"auth_through":[],"status":0,"error":"illegal key"}"""

    @Test
    fun detectsTheRealIllegalKeyBody() {
        assertFalse(KugouUpstreamError.isAuthInvalid(502, illegalKeyBody))
    }

    @Test
    fun signatureMarkersDoNotConfirmExpiredAuthentication() {
        listOf(
            """{"error_code":31833}""",
            """{"error":"ILLEGAL KEY"}""",
            """{"error_code": 31833}"""
        ).forEach { assertFalse(KugouUpstreamError.isAuthInvalid(502, it)) }
    }

    @Test
    fun classifiesSignatureAtTopLevelAndInDataShapes() {
        val samples = listOf(
            mapOf("error_code" to 31833),
            mapOf("data" to mapOf("error_code" to "31833")),
            mapOf("data" to listOf(mapOf("error" to "ILLEGAL KEY")))
        )
        samples.forEach {
            assertEquals(KugouUpstreamErrorType.SignatureRejected, KugouUpstreamError.classify(it))
        }
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
        // 需要验证不等于恢复后已确认鉴权失效。
        assertTrue(KugouUpstreamError.isNeedVerify(needVerifyBody))
        assertFalse(KugouUpstreamError.isAuthInvalid(502, needVerifyBody))
        // 字段/明文二选一，带空格也能认
        assertTrue(KugouUpstreamError.isNeedVerify("""{"errcode": 20028}"""))
        assertTrue(KugouUpstreamError.isNeedVerify("""{"error":"本次请求需要验证"}"""))
    }

    @Test
    fun verificationClassificationSupportsCodesAndFirstDataItem() {
        listOf(
            mapOf("errcode" to "20028"),
            mapOf("data" to mapOf("error_code" to 20028)),
            mapOf("data" to listOf(mapOf("errcode" to "20028"))),
            mapOf("message" to "本次请求需要验证")
        ).forEach {
            assertEquals(KugouUpstreamErrorType.VerificationRequired, KugouUpstreamError.classify(it))
            assertTrue(KugouUpstreamError.isNeedVerifyPlain(it))
        }
        assertTrue(KugouUpstreamError.isNeedVerify("""{"data":[{"errcode":"20028"}]}"""))
        assertFalse(KugouUpstreamError.isNeedVerify("""{"errcode":200280}"""))
        assertNull(KugouUpstreamError.classify(mapOf("detail" to mapOf("errcode" to 20028))))
        assertNull(KugouUpstreamError.classify(mapOf("data" to listOf(emptyMap<String, Any>(), mapOf("errcode" to 20028)))))
    }

    @Test
    fun tokenRejectionRequiresSongUrlContext() {
        val samples = (20017..20020).flatMap { code ->
            listOf(
                mapOf("error_code" to code),
                mapOf("data" to mapOf("errcode" to code.toString())),
                mapOf("data" to listOf(mapOf("error_code" to code)))
            )
        } + listOf(
            mapOf("error" to "TOKEN API ERROR."),
            mapOf("message" to "other", "errmsg" to "token api error.")
        )
        samples.forEach {
            assertNull(KugouUpstreamError.classify(it))
            assertEquals(KugouUpstreamErrorType.TokenRejected, KugouUpstreamError.classify(it, songUrlContext = true))
            assertTrue(KugouUpstreamError.isSongUrlTokenInvalid(it))
        }
        assertNull(KugouUpstreamError.classify(mapOf("error_code" to 20010), songUrlContext = true))
    }

    @Test
    fun rejectionExceptionsDoNotConfirmAuthenticationInvalidity() {
        listOf(
            KugouVerificationRequiredException("test"),
            KugouSignatureRejectedException("test"),
            KugouTokenRejectedException("test")
        ).forEach {
            assertTrue(it is IOException)
            assertFalse(KugouUpstreamError.hasAuthInvalidCause(IOException("wrapper", it)))
        }
    }

    @Test
    fun readsVerificationEventIdOnlyFromExplicitFields() {
        assertEquals("gz_tx_event_1", KugouUpstreamError.verificationEventId(mapOf("eventid" to "gz_tx_event_1")))
        assertEquals("x", KugouUpstreamError.verificationEventId(mapOf("data" to mapOf("ssaCode" to "x"))))
        assertEquals("y", KugouUpstreamError.verificationEventId(
            mapOf("data" to listOf(mapOf("event_id" to "y")))
        ))
        // 真机 /song/url 的 20028 只有 edt（不是验证入口）：不许拿别的字段冒充
        assertNull(
            KugouUpstreamError.verificationEventId(
                mapOf("errcode" to 20028, "status" to 0, "error" to "本次请求需要验证", "edt" to "hLnL98Qpft3bYZWh")
            )
        )
        assertNull(KugouUpstreamError.verificationEventId(mapOf("eventid" to "   ")))
    }

    @Test
    fun readsTheOneTimeEdtWithoutConfusingItWithEventId() {
        assertEquals("abc", KugouUpstreamError.verificationEdt(mapOf("edt" to "abc")))
        assertEquals("x", KugouUpstreamError.verificationEdt(mapOf("data" to mapOf("edt" to "x"))))
        // 只有 eventid 时不能凭空造出 edt
        assertNull(KugouUpstreamError.verificationEdt(mapOf("eventid" to "gz_tx_event_1")))
        assertNull(KugouUpstreamError.verificationEdt(mapOf("edt" to "   ")))
        assertNull(KugouUpstreamError.verificationEdt(emptyMap<String, Any>()))
    }

    @Test
    fun readsTheUpstreamErrorCodeFromEitherLayer() {
        assertEquals(20028, KugouUpstreamError.errorCodeOf(mapOf("errcode" to "20028")))
        assertEquals(31833, KugouUpstreamError.errorCodeOf(mapOf("data" to mapOf("error_code" to 31833))))
        assertEquals(20018, KugouUpstreamError.errorCodeOf(mapOf("data" to listOf(mapOf("errcode" to 20018)))))
        assertNull(KugouUpstreamError.errorCodeOf(mapOf("status" to 1)))
        // 有数据的层级优先：顶层只放业务字段时不要把 status 当错误码
        assertNull(KugouUpstreamError.errorCodeOf(mapOf("status" to 0, "data" to mapOf("url" to "x"))))
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
