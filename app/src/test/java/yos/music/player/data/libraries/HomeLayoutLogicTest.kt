package yos.music.player.data.libraries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeLayoutLogicTest {

    private fun ids(list: List<HomeSectionConfig>) = list.map { it.id }
    private fun visibleIds(list: List<HomeSectionConfig>) = list.filter { it.visible }.map { it.id }
    private fun hiddenIds(list: List<HomeSectionConfig>) = list.filterNot { it.visible }.map { it.id }

    @Test
    fun defaultIsAllVisibleInSourceOrder() {
        val d = HomeLayoutLogic.default()
        assertEquals(
            listOf(
                "recently_played", "everyday_recommend", "recommend_playlists",
                "new_songs", "new_albums", "rank"
            ),
            ids(d)
        )
        assertTrue(d.all { it.visible })
    }

    @Test
    fun normalizeDropsUnknownAndDedupes() {
        val raw = listOf(
            HomeSectionConfig("bogus", true),
            HomeSectionConfig("rank", true),
            HomeSectionConfig("rank", false) // duplicate id
        )
        val n = HomeLayoutLogic.normalize(raw)
        assertEquals(6, n.size)
        assertTrue(n.none { it.id == "bogus" })
        assertEquals(1, n.count { it.id == "rank" })
        assertTrue(n.first { it.id == "rank" }.visible) // 保首个
    }

    @Test
    fun normalizeAppendsMissingVisible() {
        val raw = listOf(HomeSectionConfig("rank", false))
        val n = HomeLayoutLogic.normalize(raw)
        assertEquals(6, n.size)
        assertEquals(listOf("rank"), hiddenIds(n))
        assertEquals(5, visibleIds(n).size)
    }

    @Test
    fun normalizeGroupsVisibleFirstThenHidden() {
        val raw = listOf(
            HomeSectionConfig("rank", false),
            HomeSectionConfig("recently_played", true),
            HomeSectionConfig("new_songs", false)
        )
        val n = HomeLayoutLogic.normalize(raw)
        assertEquals(
            listOf("recently_played", "everyday_recommend", "recommend_playlists", "new_albums"),
            visibleIds(n)
        )
        assertEquals(listOf("rank", "new_songs"), hiddenIds(n))
    }

    @Test
    fun normalizeEmptyReturnsDefault() {
        assertEquals(HomeLayoutLogic.default(), HomeLayoutLogic.normalize(emptyList()))
    }

    @Test
    fun toggleHideMovesToHiddenEnd() {
        val d = HomeLayoutLogic.default()
        val hidden = HomeLayoutLogic.toggle(d, HomeSectionId.NewSongs)
        assertEquals(listOf("new_songs"), hiddenIds(hidden))
        assertEquals(5, visibleIds(hidden).size)
    }

    @Test
    fun toggleShowAppendsToVisibleEnd() {
        val d = HomeLayoutLogic.default()
        val hidden = HomeLayoutLogic.toggle(d, HomeSectionId.NewSongs)     // hide
        val shown = HomeLayoutLogic.toggle(hidden, HomeSectionId.NewSongs) // show
        assertEquals(6, visibleIds(shown).size)
        assertEquals("new_songs", visibleIds(shown).last())                // 追加到显示组末尾
    }

    @Test
    fun reorderVisibleMovesWithinVisibleGroupOnly() {
        val d = HomeLayoutLogic.default()
        val moved = HomeLayoutLogic.reorderVisible(d, 0, 2)
        assertEquals(
            listOf(
                "everyday_recommend", "recommend_playlists", "recently_played",
                "new_songs", "new_albums", "rank"
            ),
            visibleIds(moved)
        )
    }

    @Test
    fun reorderVisibleIgnoresOutOfRange() {
        val d = HomeLayoutLogic.default()
        assertEquals(d, HomeLayoutLogic.reorderVisible(d, 0, 99))
        assertEquals(d, HomeLayoutLogic.reorderVisible(d, 3, 3))
    }

    @Test
    fun dragShownIntoHiddenRegionHides() {
        val l = HomeLayoutLogic.default().let {
            HomeLayoutLogic.toggle(HomeLayoutLogic.toggle(it, HomeSectionId.NewAlbums), HomeSectionId.Rank)
        }
        // visible=[recently,everyday,recommend,new_songs] hidden=[new_albums,rank]
        val r = HomeLayoutLogic.dragCommit(l, 0, 5) // 把首项拖到末位（隐藏区）
        assertEquals(6, r.size)
        assertEquals("recently_played", hiddenIds(r).last())
        // 不变量：可见组在前
        assertEquals(visibleIds(r), r.takeWhile { it.visible }.map { it.id })
    }

    @Test
    fun dragHiddenIntoShownRegionShows() {
        val l = HomeLayoutLogic.default().let {
            HomeLayoutLogic.toggle(HomeLayoutLogic.toggle(it, HomeSectionId.NewAlbums), HomeSectionId.Rank)
        }
        val r = HomeLayoutLogic.dragCommit(l, 5, 0) // 把末位（隐藏）拖到首位（显示区）
        assertEquals("rank", r.first().id)
        assertTrue(r.first().visible)
        assertEquals(5, visibleIds(r).size)
    }

    @Test
    fun dragWithinShownReorders() {
        val d = HomeLayoutLogic.default()
        val r = HomeLayoutLogic.dragCommit(d, 0, 2)
        assertEquals(
            listOf(
                "everyday_recommend", "recommend_playlists", "recently_played",
                "new_songs", "new_albums", "rank"
            ),
            visibleIds(r)
        )
    }
}
