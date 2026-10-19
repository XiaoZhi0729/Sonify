package yos.music.player.ui.widgets.basic

import android.os.Build
import android.view.inspector.WindowInspector
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.IntRect
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import yos.music.player.ui.theme.YosMusicTheme

class LiquidDropdownLifecycleTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun closedFallbackDoesNotCreatePopupAndExitRemovesWindow() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val expanded = mutableStateOf(false)
        var dismissed = 0
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                CompositionLocalProvider(LocalTitleOverlayHost provides null) {
                    LiquidDropdownLayout(
                        expanded = expanded.value,
                        anchorBounds = IntRect(40, 100, 80, 140),
                        onDismissRequest = { expanded.value = false },
                        onDismissFinished = { dismissed++ },
                    ) { Text("Song actions") }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, popupWindowCount())
            assertEquals(0, dismissed)
        }
        compose.runOnIdle { expanded.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, popupWindowCount()) }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle {
            assertEquals(1, popupWindowCount())
            assertEquals(0, dismissed)
        }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, popupWindowCount())
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun manyClosedFallbackMenusDoNotAllocateWindows() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        val expandedMenu = mutableStateOf<Int?>(null)
        var dismissed = 0
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                CompositionLocalProvider(LocalTitleOverlayHost provides null) {
                    repeat(64) { index ->
                        LiquidDropdownLayout(
                            expanded = expandedMenu.value == index,
                            anchorBounds = IntRect(40, 100, 80, 140),
                            onDismissRequest = { expandedMenu.value = null },
                            onDismissFinished = { dismissed++ },
                        ) { Text("Song actions $index") }
                    }
                }
            }
        }
        compose.runOnIdle {
            assertEquals(0, popupWindowCount())
            assertEquals(0, dismissed)
        }
        listOf(0, 63, 0).forEachIndexed { cycle, index ->
            compose.runOnIdle { expandedMenu.value = index }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(1, popupWindowCount()) }
            compose.runOnIdle { expandedMenu.value = null }
            compose.waitForIdle()
            compose.runOnIdle {
                assertEquals(0, popupWindowCount())
                assertEquals(cycle + 1, dismissed)
            }
        }
    }

    @Test
    fun hostSlotIsRetainedForExitAndReopeningCancelsDismiss() {
        val expanded = mutableStateOf(false)
        val host = TitleOverlayHost()
        var dismissed = 0
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                CompositionLocalProvider(LocalTitleOverlayHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        LiquidDropdownLayout(
                            expanded = expanded.value,
                            anchorBounds = IntRect(40, 100, 80, 140),
                            onDismissRequest = { expanded.value = false },
                            onDismissFinished = { dismissed++ },
                        ) { Text("Song actions") }
                        TitleOverlayHostSlot(host)
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(0, host.slots.size) }
        compose.runOnIdle { expanded.value = true }
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(1, host.slots.size) }

        compose.mainClock.autoAdvance = false
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeBy(80)
        compose.runOnIdle { assertEquals(1, host.slots.size) }
        compose.runOnIdle { expanded.value = true }
        compose.mainClock.advanceTimeBy(3000)
        compose.runOnIdle {
            assertEquals(1, host.slots.size)
            assertEquals(0, dismissed)
        }
        compose.runOnIdle { expanded.value = false }
        compose.mainClock.advanceTimeBy(3000)
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, host.slots.size)
            assertEquals(1, dismissed)
        }
    }

    @Test
    fun closingBeforeHostBodyFirstComposesStillRemovesSlot() {
        val expanded = mutableStateOf(true)
        val renderHost = mutableStateOf(false)
        val host = TitleOverlayHost()
        var dismissed = 0
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                CompositionLocalProvider(LocalTitleOverlayHost provides host) {
                    Box(Modifier.fillMaxSize()) {
                        LiquidDropdownLayout(
                            expanded = expanded.value,
                            anchorBounds = IntRect(40, 100, 80, 140),
                            onDismissRequest = { expanded.value = false },
                            onDismissFinished = { dismissed++ },
                        ) { Text("Song actions") }
                        if (renderHost.value) TitleOverlayHostSlot(host)
                    }
                }
            }
        }
        compose.runOnIdle { assertEquals(1, host.slots.size) }
        compose.runOnIdle { expanded.value = false }
        compose.waitForIdle()
        compose.runOnIdle { renderHost.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(0, host.slots.size)
            assertEquals(1, dismissed)
        }
    }

    private fun popupWindowCount(): Int = WindowInspector.getGlobalWindowViews().count {
        it.javaClass.name == "androidx.compose.ui.window.PopupLayout"
    }
}
