package yos.music.player.data.libraries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 资料库板块布局的纯逻辑测试。
 */
class LibraryLayoutLogicTest {

    private fun ids(list: List<LibrarySectionConfig>) = list.map { it.id }
    private fun visibleIds(list: List<LibrarySectionConfig>) = list.filter { it.visible }.map { it.id }
    private fun hiddenIds(list: List<LibrarySectionConfig>) = list.filterNot { it.visible }.map { it.id }

    @Test
    fun defaultIsAllVisibleInSourceOrder() {
        val d = LibraryLayoutLogic.default()
        assertEquals(
            listOf("hero_widgets", "recent_played", "followed_artists", "collections"),
            ids(d)
        )
        assertTrue(d.all { it.visible })
    }

    @Test
    fun normalizeDropsUnknownAndDedupes() {
        val raw = listOf(
            LibrarySectionConfig("bogus", true),
            LibrarySectionConfig("collections", true),
            LibrarySectionConfig("collections", false) // duplicate id
        )
        val n = LibraryLayoutLogic.normalize(raw)
        assertEquals(4, n.size)
        assertTrue(n.none { it.id == "bogus" })
        assertEquals(1, n.count { it.id == "collections" })
        assertTrue(n.first { it.id == "collections" }.visible) // 保首个
    }

    @Test
    fun normalizeAppendsMissingVisible() {
        val raw = listOf(LibrarySectionConfig("collections", false))
        val n = LibraryLayoutLogic.normalize(raw)
        assertEquals(4, n.size)
        assertEquals(listOf("collections"), hiddenIds(n))
        assertEquals(3, visibleIds(n).size)
    }

    @Test
    fun normalizeGroupsVisibleFirstThenHidden() {
        val raw = listOf(
            LibrarySectionConfig("collections", false),
            LibrarySectionConfig("hero_widgets", true),
            LibrarySectionConfig("recent_played", false)
        )
        val n = LibraryLayoutLogic.normalize(raw)
        assertEquals(
            listOf("hero_widgets", "followed_artists"),
            visibleIds(n)
        )
        assertEquals(listOf("collections", "recent_played"), hiddenIds(n))
    }

    @Test
    fun normalizeEmptyReturnsDefault() {
        assertEquals(LibraryLayoutLogic.default(), LibraryLayoutLogic.normalize(emptyList()))
    }

    @Test
    fun toggleHideMovesToHiddenEnd() {
        val d = LibraryLayoutLogic.default()
        val hidden = LibraryLayoutLogic.toggle(d, LibrarySectionId.RecentPlayed)
        assertEquals(listOf("recent_played"), hiddenIds(hidden))
        assertEquals(3, visibleIds(hidden).size)
    }

    @Test
    fun toggleShowAppendsToVisibleEnd() {
        val d = LibraryLayoutLogic.default()
        val hidden = LibraryLayoutLogic.toggle(d, LibrarySectionId.RecentPlayed)
        val shown = LibraryLayoutLogic.toggle(hidden, LibrarySectionId.RecentPlayed)
        assertEquals(4, visibleIds(shown).size)
        assertEquals("recent_played", visibleIds(shown).last())
    }

    @Test
    fun reorderVisibleMovesWithinVisibleGroupOnly() {
        val d = LibraryLayoutLogic.default()
        val moved = LibraryLayoutLogic.reorderVisible(d, 0, 2)
        assertEquals(
            listOf("recent_played", "followed_artists", "hero_widgets", "collections"),
            visibleIds(moved)
        )
    }

    @Test
    fun reorderVisibleIgnoresOutOfRange() {
        val d = LibraryLayoutLogic.default()
        assertEquals(d, LibraryLayoutLogic.reorderVisible(d, 0, 99))
        assertEquals(d, LibraryLayoutLogic.reorderVisible(d, 2, 2))
    }

    @Test
    fun dragShownIntoHiddenRegionHides() {
        val l = LibraryLayoutLogic.default().let {
            LibraryLayoutLogic.toggle(
                LibraryLayoutLogic.toggle(it, LibrarySectionId.FollowedArtists),
                LibrarySectionId.Collections
            )
        }
        // visible=[hero_widgets, recent_played] hidden=[followed_artists, collections]
        val r = LibraryLayoutLogic.dragCommit(l, 0, 3)
        assertEquals(4, r.size)
        assertEquals("hero_widgets", hiddenIds(r).last())
        assertEquals(visibleIds(r), r.takeWhile { it.visible }.map { it.id })
    }

    @Test
    fun dragHiddenIntoShownRegionShows() {
        val l = LibraryLayoutLogic.default().let {
            LibraryLayoutLogic.toggle(
                LibraryLayoutLogic.toggle(it, LibrarySectionId.FollowedArtists),
                LibrarySectionId.Collections
            )
        }
        val r = LibraryLayoutLogic.dragCommit(l, 3, 0)
        assertEquals("collections", r.first().id)
        assertTrue(r.first().visible)
        assertEquals(3, visibleIds(r).size)
    }

    @Test
    fun dragWithinShownReorders() {
        val d = LibraryLayoutLogic.default()
        val r = LibraryLayoutLogic.dragCommit(d, 0, 2)
        assertEquals(
            listOf("recent_played", "followed_artists", "hero_widgets", "collections"),
            visibleIds(r)
        )
    }
}
