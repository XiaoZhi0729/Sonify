package yos.music.player.data.libraries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 主页板块布局的纯逻辑测试。
 *
 * ★本文件里的板块总数与顺序是**刻意硬编码**的：`HomeLayoutLogic` 的行为就是"按
 * `HomeSectionId` 的枚举顺序生成默认布局"，所以枚举一变，这些断言必须跟着改。
 * 用 `HomeSectionId.entries.size` 之类的动态写法会让"加板块"这件事在测试里
 * 完全隐形——而那正是最需要被提醒的时刻。
 */
class HomeLayoutLogicTest {

    private fun ids(list: List<HomeSectionConfig>) = list.map { it.id }
    private fun visibleIds(list: List<HomeSectionConfig>) = list.filter { it.visible }.map { it.id }
    private fun hiddenIds(list: List<HomeSectionConfig>) = list.filterNot { it.visible }.map { it.id }

    @Test
    fun defaultIsAllVisibleInSourceOrder() {
        val d = HomeLayoutLogic.default()
        assertEquals(
            listOf(
                "vip_recommend", "personal_fm", "everyday_recommend", "niche_treasure",
                "recommend_playlists", "new_songs", "new_albums", "rank"
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
        assertEquals(8, n.size)
        assertTrue(n.none { it.id == "bogus" })
        assertEquals(1, n.count { it.id == "rank" })
        assertTrue(n.first { it.id == "rank" }.visible) // 保首个
    }

    @Test
    fun normalizeAppendsMissingVisible() {
        val raw = listOf(HomeSectionConfig("rank", false))
        val n = HomeLayoutLogic.normalize(raw)
        assertEquals(8, n.size)
        assertEquals(listOf("rank"), hiddenIds(n))
        assertEquals(7, visibleIds(n).size)
    }

    @Test
    fun normalizeGroupsVisibleFirstThenHidden() {
        val raw = listOf(
            HomeSectionConfig("rank", false),
            HomeSectionConfig("vip_recommend", true),
            HomeSectionConfig("new_songs", false)
        )
        val n = HomeLayoutLogic.normalize(raw)
        assertEquals(
            listOf(
                "vip_recommend", "personal_fm", "everyday_recommend",
                "niche_treasure", "recommend_playlists", "new_albums"
            ),
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
        assertEquals(7, visibleIds(hidden).size)
    }

    @Test
    fun toggleShowAppendsToVisibleEnd() {
        val d = HomeLayoutLogic.default()
        val hidden = HomeLayoutLogic.toggle(d, HomeSectionId.NewSongs)     // hide
        val shown = HomeLayoutLogic.toggle(hidden, HomeSectionId.NewSongs) // show
        assertEquals(8, visibleIds(shown).size)
        assertEquals("new_songs", visibleIds(shown).last())                // 追加到显示组末尾
    }

    @Test
    fun reorderVisibleMovesWithinVisibleGroupOnly() {
        val d = HomeLayoutLogic.default()
        val moved = HomeLayoutLogic.reorderVisible(d, 0, 2)
        assertEquals(
            listOf(
                "personal_fm", "everyday_recommend", "vip_recommend",
                "niche_treasure", "recommend_playlists", "new_songs", "new_albums", "rank"
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
        // visible=[vip_recommend, personal_fm, everyday, niche_treasure, recommend, new_songs] hidden=[new_albums, rank]
        val r = HomeLayoutLogic.dragCommit(l, 0, 7) // 把首项拖到末位（隐藏区）
        assertEquals(8, r.size)
        assertEquals("vip_recommend", hiddenIds(r).last())
        // 不变量：可见组在前
        assertEquals(visibleIds(r), r.takeWhile { it.visible }.map { it.id })
    }

    @Test
    fun dragHiddenIntoShownRegionShows() {
        val l = HomeLayoutLogic.default().let {
            HomeLayoutLogic.toggle(HomeLayoutLogic.toggle(it, HomeSectionId.NewAlbums), HomeSectionId.Rank)
        }
        val r = HomeLayoutLogic.dragCommit(l, 7, 0) // 把末位（隐藏）拖到首位（显示区）
        assertEquals("rank", r.first().id)
        assertTrue(r.first().visible)
        assertEquals(7, visibleIds(r).size)
    }

    @Test
    fun dragWithinShownReorders() {
        val d = HomeLayoutLogic.default()
        val r = HomeLayoutLogic.dragCommit(d, 0, 2)
        assertEquals(
            listOf(
                "personal_fm", "everyday_recommend", "vip_recommend",
                "niche_treasure", "recommend_playlists", "new_songs", "new_albums", "rank"
            ),
            visibleIds(r)
        )
    }

    /**
     * 新加的板块必须在老用户已存布局里被自动补上。
     *
     * 这是 `normalize` 的"补齐缺失"步骤，也是加板块唯一需要保证的兼容性：
     * 老用户的 MMKV 里只有旧版 id，升级后新板块应当自动补齐而不是消失。
     */
    @Test
    fun legacyLayoutGainsMissingSections() {
        val legacy = listOf(
            HomeSectionConfig("recently_played", true),
            HomeSectionConfig("everyday_recommend", true),
            HomeSectionConfig("recommend_playlists", true),
            HomeSectionConfig("new_songs", true),
            HomeSectionConfig("new_albums", true),
            HomeSectionConfig("rank", true)
        )
        val n = HomeLayoutLogic.normalize(legacy)
        assertEquals(8, n.size)
        assertTrue(n.first { it.id == "personal_fm" }.visible)
        assertTrue(n.first { it.id == "niche_treasure" }.visible)
        assertTrue(n.any { it.id == "vip_recommend" })
        // 已存顺序保持：补齐项按枚举顺序追加在已存项之后
        assertEquals(listOf("personal_fm", "niche_treasure"), ids(n).takeLast(2))
    }

    @Test
    fun normalizeMigratesLegacyRecentlyPlayedToVipRecommend() {
        val legacy = listOf(
            HomeSectionConfig("everyday_recommend", true),
            HomeSectionConfig("recently_played", false),
            HomeSectionConfig("vip_recommend", true)
        )
        val n = HomeLayoutLogic.normalize(legacy)
        assertEquals(8, n.size)
        assertTrue(n.none { it.id == "recently_played" })
        assertEquals(1, n.count { it.id == "vip_recommend" })
        assertEquals(listOf("vip_recommend"), hiddenIds(n))
    }
}
