package yos.music.player.ui.widgets.liquid

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.swipeRight
import androidx.navigation.compose.rememberNavController
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.onboarding.GlassSettingsScreen
import yos.music.player.ui.onboarding.onboardingColors
import yos.music.player.ui.pages.settings.performance.userinterface.GlassEffectSetting
import yos.music.player.ui.theme.YosMusicTheme

class GlassBlurSliderSwitchTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun glassBlurSliderControl_switchesBetweenLiquidAndMochi_bothDirections() {
        val glassOn = mutableStateOf(true)
        val value = mutableFloatStateOf(1.0f)

        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                Box(Modifier.fillMaxWidth()) {
                    GlassBlurSliderControl(
                        value = { value.floatValue },
                        onValueChange = { value.floatValue = it },
                        glassOn = glassOn.value,
                    )
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("liquid_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

        compose.runOnIdle { glassOn.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("mochi_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)

        compose.runOnIdle { glassOn.value = true }
        compose.waitForIdle()
        compose.onNodeWithTag("liquid_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

        compose.runOnIdle { glassOn.value = false }
        compose.waitForIdle()
        compose.onNodeWithTag("mochi_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)
    }

    @Test
    fun glassTuningPanel_clickingMaterialTabs_switchesSliderBothDirections() {
        val liquid = mutableStateOf(true)
        val liquidScale = mutableFloatStateOf(1.0f)
        val frostedScale = mutableFloatStateOf(1.0f)

        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                Box(Modifier.fillMaxSize()) {
                    GlassTuningPanel(
                        scale = if (liquid.value) liquidScale.floatValue else frostedScale.floatValue,
                        onScaleChange = { v ->
                            if (liquid.value) liquidScale.floatValue = v else frostedScale.floatValue = v
                        },
                        liquid = liquid.value,
                        onLiquidChange = { liquid.value = it },
                        liquidScale = liquidScale.floatValue,
                        frostedScale = frostedScale.floatValue,
                    )
                }
            }
        }

        compose.waitForIdle()
        compose.onNodeWithTag("liquid_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

        compose.onNodeWithText("混色模糊").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mochi_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)

        compose.onNodeWithText("液态玻璃").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("liquid_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

        compose.onNodeWithText("混色模糊").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("mochi_slider").assertExists().assertIsDisplayed()
        compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)
    }

    @Test
    fun onboardingGlassSettingsScreen_clickingMaterialTabs_switchesSliderBothDirections() {
        val originalBlur = SettingsLibrary.BarBlurEffect
        val originalLiquidScale = SettingsLibrary.GlassBlurScale
        val originalFrostedScale = SettingsLibrary.FrostedBlurScale
        try {
            SettingsLibrary.BarBlurEffect = true
            compose.setContent {
                YosMusicTheme(darkTheme = false) {
                    GlassSettingsScreen(colors = onboardingColors())
                }
            }

            compose.waitForIdle()
            compose.onNodeWithTag("liquid_slider").assertExists()
            compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

            compose.onNodeWithText("混色模糊").performScrollTo().performClick()
            compose.waitForIdle()
            assertEquals("BarBlurEffect should become false after clicking 混色模糊", false, SettingsLibrary.BarBlurEffect)
            val mochiCount = compose.onAllNodesWithTag("mochi_slider").fetchSemanticsNodes().size
            val liquidCount = compose.onAllNodesWithTag("liquid_slider").fetchSemanticsNodes().size
            assertEquals(
                "Expected mochi=1, liquid=0 after onboarding switch. Tree:\n${compose.onRoot(useUnmergedTree = true).printToString()}",
                "mochi=1,liquid=0",
                "mochi=$mochiCount,liquid=$liquidCount"
            )
            compose.onNodeWithTag("mochi_slider").assertExists()
            compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)

            compose.onNodeWithTag("mochi_slider").performScrollTo().performTouchInput { swipeRight() }
            compose.waitForIdle()

            compose.onNodeWithText("液态玻璃").performScrollTo().performClick()
            compose.waitForIdle()
            compose.onNodeWithTag("liquid_slider").assertExists()
            compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)
        } finally {
            SettingsLibrary.BarBlurEffect = originalBlur
            SettingsLibrary.GlassBlurScale = originalLiquidScale
            SettingsLibrary.FrostedBlurScale = originalFrostedScale
        }
    }

    @Test
    fun yosSlider_switchesBetweenLiquidAndMochi_bothDirections() {
        val originalBlur = SettingsLibrary.BarBlurEffect
        val value = mutableFloatStateOf(0.5f)
        try {
            SettingsLibrary.BarBlurEffect = true
            compose.setContent {
                YosMusicTheme(darkTheme = false) {
                    Box(Modifier.fillMaxWidth()) {
                        YosSlider(
                            value = { value.floatValue },
                            onValueChange = { value.floatValue = it },
                            valueRange = 0f..1f,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            compose.waitForIdle()
            compose.onNodeWithTag("liquid_slider").assertExists().assertIsDisplayed()
            compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

            compose.runOnIdle { SettingsLibrary.BarBlurEffect = false }
            compose.waitForIdle()
            compose.onNodeWithTag("mochi_slider").assertExists().assertIsDisplayed()
            compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)

            compose.runOnIdle { SettingsLibrary.BarBlurEffect = true }
            compose.waitForIdle()
            compose.onNodeWithTag("liquid_slider").assertExists().assertIsDisplayed()
            compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

            compose.runOnIdle { SettingsLibrary.BarBlurEffect = false }
            compose.waitForIdle()
            compose.onNodeWithTag("mochi_slider").assertExists().assertIsDisplayed()
            compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)
        } finally {
            SettingsLibrary.BarBlurEffect = originalBlur
        }
    }

    @Test
    fun glassEffectSetting_barBlurEffectChange_switchesSliderBothDirections() {
        val originalBlur = SettingsLibrary.BarBlurEffect
        try {
            SettingsLibrary.BarBlurEffect = true
            compose.setContent {
                YosMusicTheme(darkTheme = false) {
                    GlassEffectSetting(navController = rememberNavController())
                }
            }

            compose.waitForIdle()
            compose.onNodeWithTag("liquid_slider").assertExists()
            compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)

            compose.runOnIdle { SettingsLibrary.BarBlurEffect = false }
            compose.waitForIdle()
            val mochiCount = compose.onAllNodesWithTag("mochi_slider").fetchSemanticsNodes().size
            val liquidCount = compose.onAllNodesWithTag("liquid_slider").fetchSemanticsNodes().size
            assertEquals(
                "Expected mochi=1, liquid=0 after GlassEffectSetting switch. Tree:\n${compose.onRoot(useUnmergedTree = true).printToString()}",
                "mochi=1,liquid=0",
                "mochi=$mochiCount,liquid=$liquidCount"
            )
            compose.onNodeWithTag("mochi_slider").assertExists()
            compose.onAllNodesWithTag("liquid_slider").assertCountEquals(0)

            compose.runOnIdle { SettingsLibrary.BarBlurEffect = true }
            compose.waitForIdle()
            compose.onNodeWithTag("liquid_slider").assertExists()
            compose.onAllNodesWithTag("mochi_slider").assertCountEquals(0)
        } finally {
            SettingsLibrary.BarBlurEffect = originalBlur
        }
    }
}
