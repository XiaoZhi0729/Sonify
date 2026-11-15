package yos.music.player.ui.widgets.mochi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.library.buildAlphabetIndexedItems
import yos.music.player.ui.pages.library.sortAndIndexLibraryItems

class MochiIndexBarTest {

    @Test
    fun indexAt_clampsAndMapsEvenly() {
        assertEquals(0, IndexBarGeometry.indexAt(-10f, 100f, 5))
        assertEquals(0, IndexBarGeometry.indexAt(0f, 100f, 5))
        assertEquals(2, IndexBarGeometry.indexAt(50f, 100f, 5))
        assertEquals(4, IndexBarGeometry.indexAt(99.9f, 100f, 5))
        assertEquals(4, IndexBarGeometry.indexAt(200f, 100f, 5))
        assertEquals(0, IndexBarGeometry.indexAt(10f, 0f, 5))
        assertEquals(0, IndexBarGeometry.indexAt(10f, 100f, 0))
    }

    @Test
    fun centerOf_returnsSlotCenter() {
        assertEquals(10f, IndexBarGeometry.centerOf(0, 100f, 5), 0.01f)
        assertEquals(50f, IndexBarGeometry.centerOf(2, 100f, 5), 0.01f)
        assertEquals(90f, IndexBarGeometry.centerOf(4, 100f, 5), 0.01f)
        assertEquals(0f, IndexBarGeometry.centerOf(0, 100f, 0), 0.01f)
    }

    @Test
    fun initialLetterOf_handlesEnglishAndLeadingPunctuation() {
        assertEquals("A", AlphabetIndex.initialLetterOf("Abbey Road"))
        assertEquals("Z", AlphabetIndex.initialLetterOf("zootopia"))
        assertEquals("F", AlphabetIndex.initialLetterOf("《范特西》"))
        assertEquals("A", AlphabetIndex.initialLetterOf("「アイドル」"))
        assertEquals("B", AlphabetIndex.initialLetterOf("  (Best of) "))
        assertEquals("#", AlphabetIndex.initialLetterOf("1989"))
        assertEquals("#", AlphabetIndex.initialLetterOf("#tag"))
        assertEquals("#", AlphabetIndex.initialLetterOf(""))
        assertEquals("#", AlphabetIndex.initialLetterOf("   "))
    }

    @Test
    fun initialLetterOf_handlesAccentedLatin() {
        assertEquals("E", AlphabetIndex.initialLetterOf("Édith Piaf"))
        assertEquals("U", AlphabetIndex.initialLetterOf("Über"))
        assertEquals("O", AlphabetIndex.initialLetterOf("Øresund"))
        assertEquals("L", AlphabetIndex.initialLetterOf("Łódź"))
        assertEquals("A", AlphabetIndex.initialLetterOf("Æther"))
        assertEquals("S", AlphabetIndex.initialLetterOf("ßeta"))
    }

    @Test
    fun initialLetterOf_handlesChineseAndPolyphones() {
        assertEquals("Z", AlphabetIndex.initialLetterOf("周杰伦"))
        assertEquals("S", AlphabetIndex.initialLetterOf("单依纯"))
        assertEquals("C", AlphabetIndex.initialLetterOf("重庆森林"))
        assertEquals("Y", AlphabetIndex.initialLetterOf("乐队"))
        assertEquals("X", AlphabetIndex.initialLetterOf("厦门"))
    }

    @Test
    fun initialLetterOf_handlesJapaneseKanaAndKanjiFallback() {
        assertEquals("A", AlphabetIndex.initialLetterOf("アイドル"))
        assertEquals("H", AlphabetIndex.initialLetterOf("ひこうき雲"))
        assertEquals("N", AlphabetIndex.initialLetterOf("凪"))
        assertEquals("S", AlphabetIndex.initialLetterOf("桜"))
    }

    @Test
    fun initialLetterOf_handlesKoreanHangul() {
        assertEquals("S", AlphabetIndex.initialLetterOf("사랑"))
        assertEquals("A", AlphabetIndex.initialLetterOf("아이유"))
    }

    @Test
    fun build_ascendingAndDescendingPreserveContiguousGroups() {
        val items = listOf("周杰伦", "1989", "Abbey Road", "アイドル", "「ビター」", "Édith")

        val asc = AlphabetIndex.build(items, descending = false) { it }
        assertEquals(listOf("A", "B", "E", "Z", "#"), asc.labels)
        assertEquals(
            asc.labels,
            asc.firstItemIndices.map { AlphabetIndex.initialLetterOf(asc.sortedItems[it]) },
        )
        assertEquals("#", AlphabetIndex.initialLetterOf(asc.sortedItems.last()))

        val desc = AlphabetIndex.build(items, descending = true) { it }
        assertEquals(listOf("#", "Z", "E", "B", "A"), desc.labels)
        assertEquals(
            desc.labels,
            desc.firstItemIndices.map { AlphabetIndex.initialLetterOf(desc.sortedItems[it]) },
        )
        assertTrue(desc.firstItemIndices.zipWithNext().all { (a, b) -> a < b })
    }

    @Test
    fun sortAndIndexLibraryItems_hidesLabelsOutsideNameModeOrWhenSearchingOrTooFewGroups() {
        val items = listOf("Alpha" to 5, "Beta" to 10, "Gamma" to 2)

        val defaultMode = sortAndIndexLibraryItems(
            items = items,
            sortOrdinal = SettingsLibrary.LibraryListSortEnum.DEFAULT.ordinal,
            descending = false,
            isSearching = false,
            nameSelector = { it.first },
            songCountSelector = { it.second },
        )
        assertEquals(items, defaultMode.sortedItems)
        assertTrue(defaultMode.labels.isEmpty())

        val songCountDesc = sortAndIndexLibraryItems(
            items = items,
            sortOrdinal = SettingsLibrary.LibraryListSortEnum.SONG_COUNT.ordinal,
            descending = true,
            isSearching = false,
            nameSelector = { it.first },
            songCountSelector = { it.second },
        )
        assertEquals(listOf("Beta" to 10, "Alpha" to 5, "Gamma" to 2), songCountDesc.sortedItems)
        assertTrue(songCountDesc.labels.isEmpty())

        val searching = sortAndIndexLibraryItems(
            items = items,
            sortOrdinal = SettingsLibrary.LibraryListSortEnum.NAME.ordinal,
            descending = false,
            isSearching = true,
            nameSelector = { it.first },
            songCountSelector = { it.second },
        )
        assertTrue(searching.labels.isEmpty())

        val singleGroup = sortAndIndexLibraryItems(
            items = listOf("Alpha" to 1, "Apple" to 2),
            sortOrdinal = SettingsLibrary.LibraryListSortEnum.NAME.ordinal,
            descending = false,
            isSearching = false,
            nameSelector = { it.first },
            songCountSelector = { it.second },
        )
        assertTrue(singleGroup.labels.isEmpty())
    }

    @Test
    fun buildAlphabetIndexedItems_supportsTitleAndArtistKeys() {
        val tracks = listOf(
            "Zebra" to "周杰伦",
            "Alpha" to "Aimer",
            "桜" to "米津玄師",
        )
        val byTitle = buildAlphabetIndexedItems(
            items = tracks,
            descending = false,
            isSearching = false,
            keySelector = { it.first },
        )
        assertEquals(listOf("A", "S", "Z"), byTitle.labels)
        assertEquals(listOf(0, 1, 2), byTitle.firstItemIndices)
        assertEquals("Alpha", byTitle.sortedItems[0].first)

        val byArtistDesc = buildAlphabetIndexedItems(
            items = tracks,
            descending = true,
            isSearching = false,
            keySelector = { it.second },
        )
        assertEquals(listOf("Z", "M", "A"), byArtistDesc.labels)
        assertEquals("周杰伦", byArtistDesc.sortedItems[0].second)

        val searching = buildAlphabetIndexedItems(
            items = tracks,
            descending = false,
            isSearching = true,
            keySelector = { it.first },
        )
        assertTrue(searching.labels.isEmpty())
        assertEquals(3, searching.sortedItems.size)
    }
}
