package yos.music.player.ui.navigation

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.compose.LocalViewModelStoreOwner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class PageDataNavigationTest {
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
