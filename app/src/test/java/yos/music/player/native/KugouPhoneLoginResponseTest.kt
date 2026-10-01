package yos.music.player.native

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * /login/cellphone 响应解析的纯 JVM 测试（入参为 toPlainAny 展开后的 stdlib 结构）。
 * 字段兼容集对齐参考项目 md3Music kugou_provider.dart / KugouLoginAccount.fromJson。
 */
class KugouPhoneLoginResponseTest {

    @Test
    fun successResponseExtractsCredentialsAndNormalizesNumericUserid() {
        val result = parsePhoneLoginResponse(
            mapOf(
                "status" to 1L,
                "data" to mapOf(
                    "token" to "abc-token",
                    "userid" to 1234567890,
                    "vip_token" to "vip-xyz"
                )
            )
        )

        val success = result as PhoneLoginResult.Success
        assertEquals("abc-token", success.token)
        assertEquals("1234567890", success.userid)
        assertEquals("vip-xyz", success.vipToken)
    }

    @Test
    fun successWithoutVipTokenYieldsEmptyVipToken() {
        val result = parsePhoneLoginResponse(
            mapOf(
                "status" to 1,
                "data" to mapOf("token" to "t", "userid" to "u")
            )
        )

        assertTrue(result is PhoneLoginResult.Success)
        assertEquals("", (result as PhoneLoginResult.Success).vipToken)
    }

    @Test
    fun successMissingTokenFallsBackToFailed() {
        val result = parsePhoneLoginResponse(
            mapOf("status" to 1, "data" to mapOf("userid" to "u"))
        )

        assertTrue(result is PhoneLoginResult.Failed)
    }

    @Test
    fun multiAccountParsesInfoListWithFieldAliases() {
        val result = parsePhoneLoginResponse(
            mapOf(
                "status" to 0,
                "error_code" to 34175,
                "data" to mapOf(
                    "info_list" to listOf(
                        mapOf("userid" to 10001L, "nickname" to "甲"),
                        mapOf("user_id" to "10002", "user_name" to "乙", "pic" to "http://a/1.jpg")
                    )
                )
            )
        )

        val choose = result as PhoneLoginResult.NeedChooseAccount
        assertEquals(2, choose.candidates.size)
        assertEquals("10001", choose.candidates[0].userid)
        assertEquals("甲", choose.candidates[0].nickname)
        assertEquals("10002", choose.candidates[1].userid)
        assertEquals("乙", choose.candidates[1].nickname)
        assertEquals("http://a/1.jpg", choose.candidates[1].avatar)
    }

    @Test
    fun multiAccountCandidateListFallsBackThroughLegacyFieldNames() {
        val candidates = listOf(mapOf("userid" to "1", "name" to "n"))
        for (key in listOf("info_list", "user_list", "userList", "lists", "list")) {
            val result = parsePhoneLoginResponse(
                mapOf(
                    "status" to 0,
                    "error_code" to 34175,
                    "data" to mapOf(key to candidates)
                )
            )
            assertTrue("field name $key should be recognized", result is PhoneLoginResult.NeedChooseAccount)
        }
    }

    @Test
    fun multiAccountWithoutUsableCandidatesDegradesToFailed() {
        val result = parsePhoneLoginResponse(
            mapOf(
                "status" to 0,
                "error_code" to 34175,
                "error_msg" to "multi",
                "data" to mapOf("info_list" to listOf(mapOf("nickname" to "无id")))
            )
        )

        val failed = result as PhoneLoginResult.Failed
        assertEquals(34175, failed.errorCode)
        assertEquals("multi", failed.errorMsg)
    }

    @Test
    fun failedResponseCarriesUpstreamErrorCodeAndMessage() {
        val result = parsePhoneLoginResponse(
            mapOf(
                "status" to 0,
                "error_code" to 30790,
                "error_msg" to "验证码错误"
            )
        )

        val failed = result as PhoneLoginResult.Failed
        assertEquals(30790, failed.errorCode)
        assertEquals("验证码错误", failed.errorMsg)
    }

    @Test
    fun failedResponseFallsBackToErrorFieldAndMissingMessageIsNull() {
        val withError = parsePhoneLoginResponse(
            mapOf("status" to 0, "error_code" to 1, "error" to "illegal key")
        ) as PhoneLoginResult.Failed
        assertEquals("illegal key", withError.errorMsg)

        val silent = parsePhoneLoginResponse(mapOf("status" to 0)) as PhoneLoginResult.Failed
        assertEquals(0, silent.errorCode)
        assertNull(silent.errorMsg)
    }

    @Test
    fun emptyCandidateUseridIsDropped() {
        val candidate = parseLoginAccountCandidate(mapOf("userid" to "  ", "nickname" to "x"))
        assertNull(candidate)

        val numeric = parseLoginAccountCandidate(mapOf("id" to 42))
        assertEquals("42", numeric?.userid)
        assertNull(numeric?.nickname)
        assertNull(numeric?.avatar)
    }
}
