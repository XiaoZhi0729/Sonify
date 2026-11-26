package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `/user/detail` 解析单测。
 *
 * 走 Map 入参（`toPlainAny` 之后的结构）：org.json 在 JVM 单测里是 android.jar 桩，
 * 不能直接构造 JSONObject —— 与既有 [KugouRecommendPlaylistTest] / [KugouSpecialPlaylistParseTest]
 * 同款约定。
 *
 * 真机实测（md3Music 参考实现）：上游 `get_my_info` 返回
 * `{ status:1, error_code:0, data:{ nickname, pic, userid, level, ... } }`，
 * 未登录时 `error_code=20010` 且 data 为空。
 */
class KugouProfileParseTest {

    private fun parse(data: Map<String, Any?>): Map<String, Any?> =
        mapOf("status" to 1, "error_code" to 0, "data" to data)

    private fun detail(data: Map<String, Any?>) =
        parseKugouUserDetail(parse(data))

    // ---------- 头像字段优先级 ----------

    @Test
    fun avatarPrefersAvatarThenImgThenPic() {
        // 对齐上游 Dart KugouUserDetail.fromJson：data['avatar'] ?? data['img'] ?? data['pic']
        val d = detail(
            mapOf(
                "nickname" to "n",
                "avatar" to "https://imge.kugou.com/kugouicon/165/a.jpg",
                "img" to "https://imge.kugou.com/kugouicon/165/b.jpg",
                "pic" to "https://imge.kugou.com/kugouicon/165/c.jpg"
            )
        )
        assertEquals("https://imge.kugou.com/kugouicon/165/a.jpg", d?.avatarUrl)
    }

    @Test
    fun avatarFallsBackToImgThenPic() {
        assertEquals(
            "https://imge.kugou.com/kugouicon/165/b.jpg",
            detail(mapOf("nickname" to "n", "img" to "https://imge.kugou.com/kugouicon/165/b.jpg", "pic" to "https://imge.kugou.com/kugouicon/165/c.jpg"))?.avatarUrl
        )
        assertEquals(
            "https://imge.kugou.com/kugouicon/165/c.jpg",
            detail(mapOf("nickname" to "n", "pic" to "https://imge.kugou.com/kugouicon/165/c.jpg"))?.avatarUrl
        )
    }

    @Test
    fun avatarAcceptsUserPicFallback() {
        assertEquals(
            "https://imge.kugou.com/kugouicon/165/u.jpg",
            detail(mapOf("nickname" to "n", "user_pic" to "https://imge.kugou.com/kugouicon/165/u.jpg"))?.avatarUrl
        )
    }

    @Test
    fun missingAvatarKeepsNicknameAndNullUrl() {
        // 没有头像但有昵称 = 合法资料；UI 据此回落字母块
        val d = detail(mapOf("nickname" to "小明"))
        assertEquals("小明", d?.nickname)
        assertNull(d?.avatarUrl)
    }

    // ---------- URL 归一化 ----------

    @Test
    fun replacesSizePlaceholderWith400() {
        assertEquals(
            "https://imge.kugou.com/kugouicon/400/20260101/a.jpg",
            detail(mapOf("nickname" to "n", "pic" to "https://imge.kugou.com/kugouicon/{size}/20260101/a.jpg"))?.avatarUrl
        )
    }

    @Test
    fun upgradesCleartextHttpToHttps() {
        assertEquals(
            "https://imge.kugou.com/kugouicon/165/a.jpg",
            detail(mapOf("nickname" to "n", "pic" to "http://imge.kugou.com/kugouicon/165/a.jpg"))?.avatarUrl
        )
    }

    @Test
    fun blankAvatarBecomesNull() {
        assertNull(detail(mapOf("nickname" to "n", "pic" to ""))?.avatarUrl)
        assertNull(detail(mapOf("nickname" to "n", "pic" to "   "))?.avatarUrl)
    }

    // ---------- 昵称字段回退 ----------

    @Test
    fun nicknameFallsBackToUsernameThenName() {
        assertEquals("u", detail(mapOf("username" to "u", "pic" to "http://a/x.jpg"))?.nickname)
        assertEquals("n", detail(mapOf("name" to "n", "pic" to "http://a/x.jpg"))?.nickname)
        assertEquals("nick", detail(mapOf("nickname" to "nick", "username" to "u", "name" to "n", "pic" to "http://a/x.jpg"))?.nickname)
    }

    // ---------- userid / level ----------

    @Test
    fun readsNumericUseridAsString() {
        assertEquals("12345678", detail(mapOf("nickname" to "n", "userid" to 12345678, "pic" to "http://a/x.jpg"))?.userid)
    }

    @Test
    fun readsLevelAsInt() {
        assertEquals(23, detail(mapOf("nickname" to "n", "pic" to "http://a/x.jpg", "level" to 23))?.level)
        assertEquals(23, detail(mapOf("nickname" to "n", "pic" to "http://a/x.jpg", "level" to "23"))?.level)
        assertNull(detail(mapOf("nickname" to "n", "pic" to "http://a/x.jpg"))?.level)
    }

    // ---------- 业务错误 ----------

    @Test
    fun unauthenticatedErrorCodeReturnsNull() {
        // 20010 = 未登录。绝不能当成"空资料"，否则会把上一账号的缓存头像抹掉
        assertNull(parseKugouUserDetail(mapOf("status" to 0, "error_code" to 20010, "data" to mapOf<String, Any?>())))
    }

    @Test
    fun nonOkStatusReturnsNull() {
        assertNull(parseKugouUserDetail(mapOf("status" to 0, "error_code" to 0, "data" to mapOf("nickname" to "n"))))
    }

    @Test
    fun businessOkWithStatusCode1Parses() {
        // status=1 + error_code=0 是正常响应
        val d = parseKugouUserDetail(
            mapOf("status" to 1, "error_code" to 0, "data" to mapOf("nickname" to "n", "pic" to "http://a/x.jpg"))
        )
        assertEquals("n", d?.nickname)
    }

    @Test
    fun absentStatusAndErrorCodeAreAccepted() {
        // 有些响应不带 status/error_code，直接是 data
        val d = parseKugouUserDetail(mapOf("data" to mapOf("nickname" to "n", "pic" to "http://a/x.jpg")))
        assertEquals("n", d?.nickname)
    }

    // ---------- 空资料 ----------

    @Test
    fun emptyDataReturnsNull() {
        // 既无昵称也无头像 = 不是有效资料
        assertNull(parseKugouUserDetail(mapOf("status" to 1, "error_code" to 0, "data" to mapOf<String, Any?>())))
    }

    @Test
    fun toleratesRootLevelFields() {
        // 少数上游分支把字段平铺在根级
        val d = parseKugouUserDetail(mapOf("nickname" to "n", "pic" to "http://a/x.jpg"))
        assertEquals("n", d?.nickname)
        assertEquals("https://a/x.jpg", d?.avatarUrl)
    }
}
