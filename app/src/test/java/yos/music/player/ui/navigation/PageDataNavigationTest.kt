package yos.music.player.ui.navigation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.ui.graphics.Color
import yos.music.player.data.objects.ArtistSongsObject
import yos.music.player.data.repositories.KugouArtistDetailData
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PageDataNavigationTest {
    @Test
    fun artistIdentityAndArtworkAreAvailableOnFirstReturningComposition() = runBlocking {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val artist = KugouArtistDetailData("return-test", "Artist", "https://example.com/art.jpg", null, 30, 4, false)
        suspend fun render(load: Boolean): Triple<String, String?, Color?> {
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            var result = Triple("", null as String?, null as Color?)
            composition.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides owner) {
                    val id = rememberPageData("artist.resolvedId") { "" }
                    val detail = rememberPageData<KugouArtistDetailData?>("artist.detail") { null }
                    if (load) {
                        id.value = artist.artistId
                        detail.value = artist
                        ArtistSongsObject.forArtist(id.value).paletteColor.value = 0xFF182536.toInt()
                    }
                    result = Triple(id.value, detail.value?.avatarUrl,
                        id.value.takeIf { it.isNotEmpty() }?.let {
                            ArtistSongsObject.forArtist(it).paletteColor.value?.let(::Color)
                        })
                }
            }
            composition.dispose()
            recomposer.close()
            return result
        }
        try {
            val first = render(true)
            assertEquals(first, render(false))
            assertEquals(artist.artistId, first.first)
            assertEquals(artist.avatarUrl, first.second)
            assertEquals(Color(0xFF182536), first.third)
        } finally {
            owner.viewModelStore.clear()
        }
    }

    @Test
    fun returningToEntryKeepsRowsAndPageButNewEntryStartsFresh() = runBlocking {
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        val otherOwner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        suspend fun render(entry: ViewModelStoreOwner, append: Boolean): Pair<List<Int>, Int> {
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            var result = emptyList<Int>() to -1
            composition.setContent {
                CompositionLocalProvider(LocalViewModelStoreOwner provides entry) {
                    val rows = rememberPageData("rows") { (0 until 20).toList() }
                    val page = rememberPageData("page") { 2 }
                    if (append) {
                        rows.value = (0 until 80).toList()
                        page.value = 5
                    }
                    result = rows.value to page.value
                }
            }
            composition.dispose()
            recomposer.close()
            return result
        }
        try {
            assertEquals(80, render(owner, true).first.size)
            val restored = render(owner, false)
            assertEquals(80, restored.first.size)
            assertEquals(5, restored.second)
            val fresh = render(otherOwner, false)
            assertEquals(20, fresh.first.size)
            assertEquals(2, fresh.second)
        } finally {
            owner.viewModelStore.clear()
            otherOwner.viewModelStore.clear()
        }
    }
}
