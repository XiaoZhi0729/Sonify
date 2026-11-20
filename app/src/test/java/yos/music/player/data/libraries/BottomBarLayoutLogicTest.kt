package yos.music.player.data.libraries

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 底栏自定义布局纯逻辑单元测试。
 *
 * 与 [HomeLayoutLogicTest] 一样刻意硬编码 4 个底栏标签（home / favorites / library / search）
 * 以及最小可见数 2 的不变量，确保任何默认值或顺序变动都能被测试即时捕获。
 */
class BottomBarLayoutLogicTest {

    private fun ids(list: List<BottomBarTabConfig>) = list.map { it.id }
    private fun visibleIds(list: List<BottomBarTabConfig>) = list.filter { it.visible }.map { it.id }
    private fun hiddenIds(list: List<BottomBarTabConfig>) = list.filterNot { it.visible }.map { it.id }

    @Test
    fun defaultIsAllVisibleInSourceOrder() {
        val d = BottomBarLayoutLogic.default()
        assertEquals(listOf("home", "favorites", "library", "search"), ids(d))
        assertTrue(d.all { it.visible })
        assertTrue(BottomBarLayoutLogic.isDefault(d, null))
        assertTrue(BottomBarLayoutLogic.isDefault(d, ""))
    }

    @Test
    fun normalizeDropsUnknownDedupesAndAppendsMissing() {
        val raw = listOf(
            BottomBarTabConfig("unknown_tab", true),
            BottomBarTabConfig("search", true),
            BottomBarTabConfig("search", false) // duplicate
        )
        val n = BottomBarLayoutLogic.normalize(raw)
        assertEquals(4, n.size)
        assertTrue(n.none { it.id == "unknown_tab" })
        assertEquals(1, n.count { it.id == "search" })
        assertTrue(n.first { it.id == "search" }.visible) // keep first
        assertEquals(listOf("search", "home", "favorites", "library"), visibleIds(n))
    }

    @Test
    fun normalizeEmptyReturnsDefault() {
        assertEquals(BottomBarLayoutLogic.default(), BottomBarLayoutLogic.normalize(emptyList()))
    }

    @Test
    fun normalizeGroupsVisibleBeforeHidden() {
        val raw = listOf(
            BottomBarTabConfig("search", false),
            BottomBarTabConfig("library", true),
            BottomBarTabConfig("favorites", false),
            BottomBarTabConfig("home", true)
        )
        val n = BottomBarLayoutLogic.normalize(raw)
        assertEquals(listOf("library", "home"), visibleIds(n))
        assertEquals(listOf("search", "favorites"), hiddenIds(n))
    }

    @Test
    fun normalizeEnforcesMinimumTwoVisibleTabs() {
        val allHidden = listOf(
            BottomBarTabConfig("home", false),
            BottomBarTabConfig("favorites", false),
            BottomBarTabConfig("library", false),
            BottomBarTabConfig("search", false)
        )
        val n1 = BottomBarLayoutLogic.normalize(allHidden)
        assertEquals(listOf("home", "favorites"), visibleIds(n1))
        assertEquals(listOf("library", "search"), hiddenIds(n1))

        val onlyOneVisible = listOf(
            BottomBarTabConfig("home", false),
            BottomBarTabConfig("favorites", false),
            BottomBarTabConfig("library", true),
            BottomBarTabConfig("search", false)
        )
        val n2 = BottomBarLayoutLogic.normalize(onlyOneVisible)
        assertEquals(listOf("library", "home"), visibleIds(n2))
        assertEquals(listOf("favorites", "search"), hiddenIds(n2))
    }

    @Test
    fun toggleHideMovesToHiddenEndAndBlocksAtMinTwo() {
        val d = BottomBarLayoutLogic.default()
        assertTrue(BottomBarLayoutLogic.canHide(d, BottomBarTabId.Search))

        val s1 = BottomBarLayoutLogic.toggle(d, BottomBarTabId.Search)
        assertEquals(listOf("home", "favorites", "library"), visibleIds(s1))
        assertEquals(listOf("search"), hiddenIds(s1))

        val s2 = BottomBarLayoutLogic.toggle(s1, BottomBarTabId.Favorites)
        assertEquals(listOf("home", "library"), visibleIds(s2))
        assertEquals(listOf("search", "favorites"), hiddenIds(s2))

        // At 2 visible tabs, hiding any remaining visible tab must be rejected
        assertFalse(BottomBarLayoutLogic.canHide(s2, BottomBarTabId.Home))
        assertFalse(BottomBarLayoutLogic.canHide(s2, BottomBarTabId.Library))
        assertEquals(s2, BottomBarLayoutLogic.toggle(s2, BottomBarTabId.Home))
        assertEquals(s2, BottomBarLayoutLogic.toggle(s2, BottomBarTabId.Library))
    }

    @Test
    fun toggleShowAppendsToVisibleEnd() {
        val d = BottomBarLayoutLogic.default()
        val hidden = BottomBarLayoutLogic.toggle(d, BottomBarTabId.Home)
        assertEquals(listOf("favorites", "library", "search"), visibleIds(hidden))

        val restored = BottomBarLayoutLogic.toggle(hidden, BottomBarTabId.Home)
        assertEquals(listOf("favorites", "library", "search", "home"), visibleIds(restored))
        assertTrue(hiddenIds(restored).isEmpty())
    }

    @Test
    fun reorderVisibleMovesWithinVisibleGroupOnly() {
        val d = BottomBarLayoutLogic.default()
        val moved = BottomBarLayoutLogic.reorderVisible(d, 0, 2)
        assertEquals(listOf("favorites", "library", "home", "search"), visibleIds(moved))
        assertEquals(d, BottomBarLayoutLogic.reorderVisible(d, 0, 99))
        assertEquals(d, BottomBarLayoutLogic.reorderVisible(d, 1, 1))
    }

    @Test
    fun dragCommitReordersAndCrossesGroups() {
        val start = BottomBarLayoutLogic.toggle(BottomBarLayoutLogic.default(), BottomBarTabId.Search)
        // [home(V), favorites(V), library(V), search(H)]
        // Drag library (2) into hidden zone (3)
        val hiddenLib = BottomBarLayoutLogic.dragCommit(start, 2, 3)
        assertEquals(listOf("home", "favorites"), visibleIds(hiddenLib))
        assertEquals(listOf("search", "library"), hiddenIds(hiddenLib))

        // Drag search (2, hidden) back to visible index 0
        val shownSearch = BottomBarLayoutLogic.dragCommit(hiddenLib, 2, 0)
        assertEquals(listOf("search", "home", "favorites"), visibleIds(shownSearch))
        assertEquals(listOf("library"), hiddenIds(shownSearch))
    }

    @Test
    fun dragCommitPreventsDroppingBelowMinTwoVisible() {
        val twoVisible = BottomBarLayoutLogic.default()
            .let { BottomBarLayoutLogic.toggle(it, BottomBarTabId.Library) }
            .let { BottomBarLayoutLogic.toggle(it, BottomBarTabId.Search) }
        // [home(V), favorites(V), library(H), search(H)]
        val attempt = BottomBarLayoutLogic.dragCommit(twoVisible, 0, 3)
        assertEquals(listOf("favorites", "home"), visibleIds(attempt))
        assertEquals(listOf("library", "search"), hiddenIds(attempt))
    }

    @Test
    fun resolveLaunchTabDefaultsToFirstVisibleAndFallsBackWhenHidden() {
        val reordered = BottomBarLayoutLogic.reorderVisible(BottomBarLayoutLogic.default(), 2, 0)
        // [library(V), home(V), favorites(V), search(V)]
        assertEquals(
            BottomBarTabId.Library,
            BottomBarLayoutLogic.resolveLaunchTab(reordered, null)
        )
        assertEquals(
            BottomBarTabId.Search,
            BottomBarLayoutLogic.resolveLaunchTab(reordered, "search")
        )

        val searchHidden = BottomBarLayoutLogic.toggle(reordered, BottomBarTabId.Search)
        assertEquals(
            BottomBarTabId.Library,
            BottomBarLayoutLogic.resolveLaunchTab(searchHidden, "search")
        )
    }

    @Test
    fun sanitizeLaunchTabKeyClearsHiddenOrUnknownTab() {
        val d = BottomBarLayoutLogic.default()
        assertEquals("favorites", BottomBarLayoutLogic.sanitizeLaunchTabKey(d, "favorites"))
        assertNull(BottomBarLayoutLogic.sanitizeLaunchTabKey(d, ""))
        assertNull(BottomBarLayoutLogic.sanitizeLaunchTabKey(d, "bogus"))

        val favHidden = BottomBarLayoutLogic.toggle(d, BottomBarTabId.Favorites)
        assertNull(BottomBarLayoutLogic.sanitizeLaunchTabKey(favHidden, "favorites"))
    }
}
