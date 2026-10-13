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
}
