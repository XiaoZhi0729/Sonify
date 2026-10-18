package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 搜索页歌单结果解析（`/search/special`）单测。
 *
 * 背景（真机实测 2026-10-13）：酷狗 `global_collection_id` 是**非数字**集合串，
 * 形态为 `collection_3_<uid>_<listid>_<ver>`（样本见 `_diag_log2.txt` 的 `/user/playlist` 原文）。
 * 搜索页模型曾把它声明为 `Long?` 并用 `optLong` 解析 —— 对不可解析字符串 `optLong` 退回 0，
 * 于是 gcid 恒为 null，调用点落到数字 `specialid` 兜底；详情页拿 specialid 去请求
 * `/playlist/track/all`（上游 `get_other_list_file_nofilt`）→ 查不到该 listfile →
 * HTTP 502 `{"error_code":20010,"errmsg":"get other listfile fail","data":{}}`。
 *
 * 本测试把「gcid 必须按字符串原样保留」钉住，防回归。
 * 走 [parseSpecialPlaylist]（Map 入参）：org.json 在 JVM 单测里是 android.jar 桩，
 * 不能直接构造 JSONObject——与既有 [KugouRecommendPlaylistTest] 同款约定。
 */
class KugouSpecialPlaylistParseTest {

    private fun parse(fields: Map<String, Any?>): KugouPlaylistBrief =
        parseSpecialPlaylist(fields) { it }!!

    /** 真机形态：collection_ 前缀的集合串，绝不能被解析成数字/null。 */
    @Test
    fun keepsCollectionStringGcidVerbatim() {
        val p = parse(
            mapOf(
                "specialid" to 123,
                "specialname" to "夏日私藏",
                "global_collection_id" to "collection_3_2167347319_9_0",
                "songcount" to 50
            )
        )
        assertEquals("collection_3_2167347319_9_0", p.globalCollectionId)
        assertEquals(123L, p.specialId)
        assertEquals("夏日私藏", p.name)
        assertEquals(50, p.songCount)
    }

    /** 上游偶把数字序列化成 JSON number，也要能兜住（toString 后仍是同一个 key）。 */
    @Test
    fun acceptsNumericGcidAsString() {
        assertEquals(
            "88888888",
            parse(mapOf("specialid" to 7, "specialname" to "N", "global_collection_id" to 88888888)).globalCollectionId
        )
    }

    /** 缺 gcid 时为 null（而不是崩 / 也不是 "0"）。 */
    @Test
    fun missingGcidIsNull() {
        assertNull(parse(mapOf("specialid" to 7, "specialname" to "NoGcid")).globalCollectionId)
    }

    /** gid 兜底键（与 global_collection_id 同源异名）。 */
    @Test
    fun fallsBackToGidKey() {
        assertEquals(
            "collection_3_1_2_0",
            parse(mapOf("specialid" to 7, "specialname" to "G", "gid" to "collection_3_1_2_0")).globalCollectionId
        )
    }

    /** specialid 缺失/为 0 → 整条丢弃（无法定位歌单）。 */
    @Test
    fun dropsEntriesWithoutSpecialId() {
        assertNull(parseSpecialPlaylist(mapOf("specialname" to "bad"), { it }))
        assertNull(parseSpecialPlaylist(mapOf("specialid" to 0, "specialname" to "zero"), { it }))
    }

    /** 名称/封面/歌曲数照常解析（顺带钉住兜底键链与数字/字符串兼容）。 */
    @Test
    fun parsesNameCoverAndCountFallbacks() {
        val p = parse(
            mapOf(
                "specialid" to "42",
                "name" to "FallbackName",
                "sizable_cover" to "http://c1.kgimg.com/{size}/a.jpg",
                "song_count" to "13"
            )
        )
        assertEquals(42L, p.specialId)
        assertEquals("FallbackName", p.name)
        assertEquals("http://c1.kgimg.com/{size}/a.jpg", p.cover) // resolver 透传，替换在 resolveArtworkUrl
        assertEquals(13, p.songCount)
    }
}
