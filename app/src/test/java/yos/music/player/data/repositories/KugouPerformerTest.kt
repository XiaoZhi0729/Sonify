package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 播放页点歌手名 → 艺人页 的 id 反查链纯函数单测。
 *
 * 背景（真机实测）：酷狗 `singerinfo[]` 自带歌手 id，但解析时曾只取 name、丢掉 id，
 * 导致点歌手名只能按名搜索；而 `/search/artist` 不做繁简/译名归一化
 * （「高橋洋子」搜不到、「高桥洋子」才命中），于是「歌手明明在却报未找到」。
 * 这里锁定「解析 → 按名反查 id」两个纯函数的行为。
 */
class KugouPerformerTest {

    @Test
    fun keepsSinglePerformerNameAndId() {
        val performers = mergePerformers(listOf("高橋洋子" to 12740L))
        assertEquals(1, performers.size)
        assertEquals(KugouPerformer("高橋洋子", 12740L), performers[0])
    }

    @Test
    fun keepsAllPerformersInOrderForMultiArtistSong() {
        val performers = mergePerformers(
            listOf("鷺巣詩郎" to 155821L, "林原めぐみ" to 11507L, "高橋洋子" to 12740L)
        )
        assertEquals(
            listOf("鷺巣詩郎", "林原めぐみ", "高橋洋子"),
            performers.map { it.name }
        )
        assertEquals(listOf(155821L, 11507L, 12740L), performers.map { it.id })
    }

    @Test
    fun skipsBlankNameMissingOrNonPositiveId() {
        val performers = mergePerformers(
            listOf(
                "" to 123L,          // 无名字
                "NoId" to 0L,        // id 缺失/0
                "Ok" to 456L         // 有效
            )
        )
        assertEquals(listOf(KugouPerformer("Ok", 456L)), performers)
    }

    @Test
    fun dropsDuplicateNamesKeepingFirst() {
        val performers = mergePerformers(
            listOf("Conton Candy" to 7160023L, "conton candy" to 999L)
        )
        assertEquals(listOf(KugouPerformer("Conton Candy", 7160023L)), performers)
    }

    @Test
    fun matchIsExactIgnoringCaseAndSurroundingSpace() {
        val performers = mergePerformers(listOf("高橋洋子" to 12740L, "Conton Candy" to 7160023L))
        assertEquals(12740L, matchPerformerId(performers, "高橋洋子"))
        assertEquals(12740L, matchPerformerId(performers, "  高橋洋子 "))
        assertEquals(7160023L, matchPerformerId(performers, "conton candy"))
    }

    @Test
    fun matchReturnsNullForUnknownNameSoCallerFallsBackToSearch() {
        val performers = mergePerformers(listOf("高橋洋子" to 12740L))
        assertNull(matchPerformerId(performers, "高桥洋子"))
        assertNull(matchPerformerId(emptyList(), "高橋洋子"))
    }

    // ---------- 歌曲搜索反查 singerid（兜底 /search/artist 零命中） ----------

    @Test
    fun selectsIdFromFirstSongThatHasAMatch() {
        val first = mergePerformers(listOf("The Marías" to 825924L))
        val second = mergePerformers(listOf("SIENNA SPIRO" to 13110999L))
        assertEquals(13110999L, selectArtistIdForName(listOf(first, second), "SIENNA SPIRO"))
    }

    @Test
    fun selectIsExactIgnoringCaseAndSurroundingSpace() {
        val lists = listOf(mergePerformers(listOf("SIENNA SPIRO" to 13110999L)))
        assertEquals(13110999L, selectArtistIdForName(lists, "sienna spiro"))
        assertEquals(13110999L, selectArtistIdForName(lists, "  SIENNA SPIRO  "))
    }

    @Test
    fun selectPicksTheRightArtistOutOfAMultiArtistSong() {
        // 多歌手：只认点中的那一个，不能退化成「取第一位」
        val lists = listOf(
            mergePerformers(listOf("DJ Dark" to 171261L, "Sienna" to 446220L))
        )
        assertEquals(446220L, selectArtistIdForName(lists, "Sienna"))
        assertEquals(171261L, selectArtistIdForName(lists, "DJ Dark"))
        assertNull(selectArtistIdForName(lists, "Sienna Spiro"))
    }

    @Test
    fun selectReturnsNullWhenNothingMatchesAnywhere() {
        val lists = listOf(
            mergePerformers(listOf("周杰伦" to 3520L)),
            mergePerformers(emptyList())
        )
        assertNull(selectArtistIdForName(lists, "SIENNA SPIRO"))
        assertNull(selectArtistIdForName(emptyList(), "SIENNA SPIRO"))
        assertNull(selectArtistIdForName(lists, "   "))
    }

    @Test
    fun strippedHighlightCollapsesToThePlainNameForLookup() {
        // 搜索上游会给命中关键词注入 <em>；parsePerformerArray 的 nameTransform 负责剥离，
        // 这里锁定「剥离后的名字」与点中的原名落在同一个去重键上（否则反查精确相等会落空）。
        val stripped = mergePerformers(listOf("SIENNA SPIRO" to 13110999L))
        assertEquals(listOf(KugouPerformer("SIENNA SPIRO", 13110999L)), stripped)
        assertEquals(13110999L, selectArtistIdForName(listOf(stripped), "SIENNA SPIRO"))
    }
}
