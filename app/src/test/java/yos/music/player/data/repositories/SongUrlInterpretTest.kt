package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/song/url`(|/new) 响应判读的固化用例。
 *
 * 这张表是被真机事故换来的：历史实现把"HTTP 200 但没有 url"整体当成"确凿拒播"
 * （写进可播性表 → 列表置灰 24 小时 → 点击被拦），于是登录态一失效，
 * 连"肯定能播"的歌也被标成"暂无音源"。所以下面的核心断言是：
 * **认不出的空响应必须是 TierUnavailable，绝不能是 Blocked。**
 */
class SongUrlInterpretTest {

    private val standard = KugouQuality.STANDARD
    private val high = KugouQuality.HIGH

    @Test
    fun urlAsStringReturnsOkWithStampedQuality() {
        val outcome = interpretSongUrlBody(
            mapOf(
                "status" to 1,
                "data" to mapOf("url" to "http://cdn/x.mp3", "quality" to "320", "priv_status" to 1)
            ),
            requestedQuality = standard
        )
        assertEquals(SongUrlOutcome.Ok("http://cdn/x.mp3", KugouQuality.HIGH), outcome)
    }

    @Test
    fun urlAsArrayTakesFirstNonEmpty() {
        val outcome = interpretSongUrlBody(
            mapOf(
                "data" to mapOf(
                    "url" to listOf("", "http://cdn/x.flac"),
                    "quality" to "flac"
                )
            ),
            requestedQuality = standard
        )
        assertEquals(SongUrlOutcome.Ok("http://cdn/x.flac", KugouQuality.LOSSLESS), outcome)
    }

    /** priv_url 实测把 data 直接返回成数组（真机：匿名请求 body 形如 `{"data":[{...}]}`）。 */
    @Test
    fun dataAsArrayIsReadFromFirstElement() {
        val outcome = interpretSongUrlBody(
            mapOf(
                "data" to listOf(
                    mapOf("url" to "http://cdn/arr.mp3", "quality" to "128", "priv_status" to 1)
                )
            ),
            requestedQuality = standard
        )
        assertEquals(SongUrlOutcome.Ok("http://cdn/arr.mp3", KugouQuality.STANDARD), outcome)
    }

    @Test
    fun urlElementAsObjectIsRead() {
        val outcome = interpretSongUrlBody(
            mapOf("data" to mapOf("url" to listOf(mapOf("url" to "http://cdn/obj.mp3")))),
            requestedQuality = standard
        )
        assertEquals(SongUrlOutcome.Ok("http://cdn/obj.mp3", null), outcome)
    }

    /** 非标准档：需要购买只是"这一档拿不到"，交降档链继续（对齐 Dart 的 buy 降档语义）。 */
    @Test
    fun buyAtNonStandardTierIsTierUnavailable() {
        val outcome = interpretSongUrlBody(
            mapOf("data" to mapOf("status" to 2, "fail_process" to listOf("pkg", "buy"))),
            requestedQuality = high
        )
        assertEquals(SongUrlOutcome.TierUnavailable, outcome)
    }

    /** 标准档也要买 = 确凿拒播（最低档都被付费拦住，整曲不可播）。 */
    @Test
    fun buyAtStandardTierIsBlocked() {
        val outcome = interpretSongUrlBody(
            mapOf("data" to mapOf("status" to 2, "fail_process" to listOf("buy"))),
            requestedQuality = standard
        )
        assertTrue(outcome is SongUrlOutcome.Blocked)
        assertEquals("暂无可用音源（付费或权限限制）", (outcome as SongUrlOutcome.Blocked).reason)
    }

    /** 真机证据：`status=3 priv_status=0 pay_block_tpl=1 cpy_map=2`（2026-10-06 设备日志）。 */
    @Test
    fun status3WithCpyMapAndPayBlockIsBlocked() {
        val outcome = interpretSongUrlBody(
            mapOf(
                "status" to 3,
                "priv_status" to 0,
                "trans_param" to mapOf("pay_block_tpl" to 1, "cpy_map" to "2")
            ),
            requestedQuality = standard
        )
        assertTrue(outcome is SongUrlOutcome.Blocked)
        assertEquals("暂无可用音源（版权与付费限制）", (outcome as SongUrlOutcome.Blocked).reason)
        assertTrue(outcome.detail.contains("status=3"))
    }

    @Test
    fun status3WithOnlyCpyMapIsCopyrightBlocked() {
        val outcome = interpretSongUrlBody(
            mapOf("status" to 3, "trans_param" to mapOf("cpy_map" to "2")),
            requestedQuality = standard
        )
        assertEquals("暂无可用音源（版权限制）", (outcome as SongUrlOutcome.Blocked).reason)
    }

    /**
     * 事故主因：登录态失效被包在 HTTP 200 里返回。
     * 真机取证（2026-10-14，带 App 自己的凭据）：`{"status":2,"error_code":20017,"priv_status":0}`。
     */
    @Test
    fun status2WithTokenErrorCodeIsAccountInvalidNotBlocked() {
        val outcome = interpretSongUrlBody(
            mapOf(
                "status" to 2,
                "priv_status" to 0,
                "error_code" to 20017,
                "auth_through" to emptyList<String>()
            ),
            requestedQuality = standard
        )
        assertEquals(SongUrlOutcome.AccountInvalid, outcome)
    }

    @Test
    fun tokenApiErrorMessageIsAccountInvalid() {
        val outcome = interpretSongUrlBody(
            mapOf("status" to 0, "error_code" to 20017, "message" to "token api error."),
            requestedQuality = standard
        )
        assertEquals(SongUrlOutcome.AccountInvalid, outcome)
    }

    @Test
    fun needVerifyIsAccountInvalid() {
        val outcome = interpretSongUrlBody(
            mapOf("errcode" to 20028, "status" to 0),
            requestedQuality = standard
        )
        assertEquals(SongUrlOutcome.AccountInvalid, outcome)
    }

    /** 最关键的回归防线：认不出的空响应不产生任何"不可播"结论。 */
    @Test
    fun unrecognizedEmptyBodyIsTierUnavailableNotBlocked() {
        val samples = listOf(
            emptyMap<Any, Any>(),
            mapOf("status" to 2, "priv_status" to 0),
            mapOf("status" to 2, "error_code" to 0),
            mapOf("status" to -1, "data" to mapOf("status" to 2)),
            mapOf("error_code" to 31863, "errmsg" to "no free part info"),
        )
        samples.forEach { body ->
            assertEquals(
                "body=$body 不应被判成拒播",
                SongUrlOutcome.TierUnavailable,
                interpretSongUrlBody(body, standard)
            )
        }
    }

    /** 上游没认会员时返回的是试听片段：非标准档不接受，标准档可用。 */
    @Test
    fun privStatusZeroTrialIsRejectedAtNonStandardTierOnly() {
        val body = mapOf(
            "data" to mapOf("url" to "http://cdn/trial.mp3", "priv_status" to 0, "quality" to "128")
        )
        assertEquals(SongUrlOutcome.TierUnavailable, interpretSongUrlBody(body, high))
        assertEquals(
            SongUrlOutcome.Ok("http://cdn/trial.mp3", KugouQuality.STANDARD),
            interpretSongUrlBody(body, standard)
        )
    }

    /** 付费体常常平铺在顶层（无 data 包裹）——真机 url_512.json 就是这种形态。 */
    @Test
    fun flatTopLevelRejectionBodyIsRead() {
        val outcome = interpretSongUrlBody(
            mapOf(
                "priv_status" to 0,
                "status" to 2,
                "fail_process" to listOf("pkg", "buy"),
                "trans_param" to mapOf("pay_block_tpl" to 1, "cpy_map" to "2")
            ),
            requestedQuality = standard
        )
        assertTrue(outcome is SongUrlOutcome.Blocked)
    }
}
