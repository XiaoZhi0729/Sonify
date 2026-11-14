package yos.music.player.ui.widgets.liquid

import androidx.compose.runtime.AbstractApplier
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Composition
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MonotonicFrameClock
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.key
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import com.funny.data_saver.core.DataSaverInterface
import com.funny.data_saver.core.DataSaverMutableState
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Test
import yos.music.player.ui.onboarding.ControlCard
import yos.music.player.ui.onboarding.OnboardingColors

class SliderSwitchRecompositionTest {

    private fun testColors() = OnboardingColors(
        dark = false,
        bgTop = Color.White,
        bgBottom = Color.White,
        ink = Color.Black,
        ink2 = Color.Gray,
        ink3 = Color.LightGray,
        hairline = Color.LightGray,
        card = Color.White,
        track = Color.LightGray,
        indicator = Color.White,
        brand = Color.Red,
        onBrand = Color.White,
        scrim = Color.Black,
        accentSoft = Color.Red,
        cardStroke = Color.Gray,
        success = Color.Green,
        stageBase = Color.Black,
        auraStrong = 0.13f,
        auraWeak = 0.055f,
    )

    private fun stubViewConfig() = object : ViewConfiguration {
        override val longPressTimeoutMillis: Long = 400L
        override val doubleTapTimeoutMillis: Long = 300L
        override val doubleTapMinTimeMillis: Long = 40L
        override val touchSlop: Float = 8f
        override val minimumTouchTargetSize: DpSize = DpSize(48.dp, 48.dp)
    }

    @Test
    fun controlCardAndKeyedSliderSlotRecomposeImmediatelyOnToggle() = runBlocking(object : MonotonicFrameClock {
        override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
            yield()
            return onFrame(System.nanoTime())
        }
    }) {
        val recomposer = Recomposer(coroutineContext)
        val runner = launch { recomposer.runRecomposeAndApplyChanges() }

        val inMemorySaver = object : DataSaverInterface() {
            private val map = mutableMapOf<String, Any?>()
            override fun <T> saveData(key: String, data: T) {
                map[key] = data
            }

            @Suppress("UNCHECKED_CAST")
            override fun <T> readData(key: String, default: T): T = (map[key] as? T) ?: default
            override fun remove(key: String) {
                map.remove(key)
            }

            override fun contains(key: String): Boolean = map.containsKey(key)
        }
        val barBlurState = DataSaverMutableState(inMemorySaver, "bar_blur_effect", true, autoSave = true)

        var activeSlot = ""
        val lifecycleEvents = mutableListOf<String>()
        val colors = testColors()

        val trackingApplier = object : AbstractApplier<Any>(Unit) {
            override fun insertTopDown(index: Int, instance: Any) = Unit
            override fun insertBottomUp(index: Int, instance: Any) = Unit
            override fun remove(index: Int, count: Int) = Unit
            override fun move(from: Int, to: Int, count: Int) = Unit
            override fun onClear() = Unit
        }
        val realComposition = Composition(trackingApplier, recomposer)

        @Composable
        fun probeSliderSlot(glassOn: Boolean) {
            key(glassOn) {
                if (glassOn) {
                    activeSlot = "liquid"
                    DisposableEffect(Unit) {
                        lifecycleEvents += "enter:liquid"
                        onDispose { lifecycleEvents += "dispose:liquid" }
                    }
                } else {
                    activeSlot = "mochi"
                    DisposableEffect(Unit) {
                        lifecycleEvents += "enter:mochi"
                        onDispose { lifecycleEvents += "dispose:mochi" }
                    }
                }
            }
        }

        @Composable
        fun probeScreen() {
            val liquid = barBlurState.value
            ControlCard(colors) {
                probeSliderSlot(glassOn = liquid)
            }
        }

        realComposition.setContent {
            CompositionLocalProvider(
                LocalDensity provides Density(1f),
                LocalLayoutDirection provides LayoutDirection.Ltr,
                LocalViewConfiguration provides stubViewConfig(),
            ) {
                probeScreen()
            }
        }
        try {
            recomposer.awaitIdle()
            assertEquals("liquid", activeSlot)
            assertEquals(listOf("enter:liquid"), lifecycleEvents)

            barBlurState.value = false
            Snapshot.sendApplyNotifications()
            yield()
            recomposer.awaitIdle()

            assertEquals("mochi", activeSlot)
            assertEquals(
                listOf("enter:liquid", "dispose:liquid", "enter:mochi"),
                lifecycleEvents
            )

            barBlurState.value = true
            Snapshot.sendApplyNotifications()
            yield()
            recomposer.awaitIdle()

            assertEquals("liquid", activeSlot)
            assertEquals(
                listOf("enter:liquid", "dispose:liquid", "enter:mochi", "dispose:mochi", "enter:liquid"),
                lifecycleEvents
            )
        } finally {
            realComposition.dispose()
            runner.cancel()
            recomposer.close()
        }
    }
}
