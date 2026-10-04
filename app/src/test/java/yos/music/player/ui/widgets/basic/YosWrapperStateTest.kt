package yos.music.player.ui.widgets.basic

import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.LocalSaveableStateRegistry
import androidx.compose.runtime.saveable.SaveableStateRegistry
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.snapshots.Snapshot
import kotlinx.coroutines.yield
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class YosWrapperStateTest {
    @Test
    fun recreatedContentRestoresListAndGridPositions() = runBlocking {
        suspend fun render(saved: Map<String, List<Any?>>?, initialIndex: Int): Pair<Map<String, List<Any?>>, Pair<Int, Int>> {
            val recomposer = Recomposer(coroutineContext)
            val composition = Composition(object : AbstractApplier<Unit>(Unit) {
                override fun insertTopDown(index: Int, instance: Unit) = Unit
                override fun insertBottomUp(index: Int, instance: Unit) = Unit
                override fun remove(index: Int, count: Int) = Unit
                override fun move(from: Int, to: Int, count: Int) = Unit
                override fun onClear() = Unit
            }, recomposer)
            val registry = SaveableStateRegistry(saved) { true }
            var indices = -1 to -1
            composition.setContent {
                CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                    YosWrapper {
                        val list = rememberLazyListState(initialIndex, 17)
                        val grid = rememberLazyGridState(initialIndex, 23)
                        indices = list.firstVisibleItemIndex to grid.firstVisibleItemIndex
                    }
                }
            }
            val result = registry.performSave() to indices
            composition.dispose()
            recomposer.close()
            return result
        }
        val original = render(null, 30)
        assertEquals(30 to 30, original.second)
        val restored = render(original.first, 0)
        assertEquals(30 to 30, restored.second)
    }

    @Test
    fun contentChangeKeepsListAndGridPositions() = runBlocking(object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            return onFrame(System.nanoTime())
        }
    }) {
        val recomposer = Recomposer(coroutineContext)
        val composition = Composition(object : AbstractApplier<Unit>(Unit) {
            override fun insertTopDown(index: Int, instance: Unit) = Unit
            override fun insertBottomUp(index: Int, instance: Unit) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }, recomposer)
        val runner = launch { recomposer.runRecomposeAndApplyChanges() }
        val registry = SaveableStateRegistry(null) { true }
        val destination = mutableStateOf("list")
        var listIndex = -1
        var gridIndex = -1
        var listOffset = -1
        var gridOffset = -1
        composition.setContent {
            CompositionLocalProvider(LocalSaveableStateRegistry provides registry) {
                val route = destination.value
                val content: @Composable () -> Unit = {
                    val list = rememberLazyListState(if (route == "list") 30 else 0, 17)
                    val grid = rememberLazyGridState(if (route == "list") 20 else 0, 23)
                    listIndex = list.firstVisibleItemIndex
                    gridIndex = grid.firstVisibleItemIndex
                    listOffset = list.firstVisibleItemScrollOffset
                    gridOffset = grid.firstVisibleItemScrollOffset
                }
                YosWrapper(content)
            }
        }
        try {
            recomposer.awaitIdle()
            assertEquals(30, listIndex)
            assertEquals(20, gridIndex)
            destination.value = "detail"
            Snapshot.sendApplyNotifications()
            yield()
            recomposer.awaitIdle()
            assertEquals(30, listIndex)
            assertEquals(20, gridIndex)
            destination.value = "list"
            Snapshot.sendApplyNotifications()
            yield()
            recomposer.awaitIdle()
            assertEquals(30, listIndex)
            assertEquals(17, listOffset)
            assertEquals(20, gridIndex)
            assertEquals(23, gridOffset)
        } finally {
            composition.dispose()
            recomposer.close()
            runner.join()
        }
    }
}
