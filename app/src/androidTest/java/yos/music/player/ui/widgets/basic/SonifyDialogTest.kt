package yos.music.player.ui.widgets.basic

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import yos.music.player.ui.theme.YosMusicTheme

class SonifyDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun inputAndPendingStateUpdateAcrossOverlayHost() {
        val name = mutableStateOf("")
        val pending = mutableStateOf(false)
        var submitted = 0
        var dismissed = 0
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                SonifyDialog(
                    title = "Create playlist",
                    onDismissRequest = { dismissed++ },
                    positiveContent = "Create",
                    onPositive = { submitted++; pending.value = true },
                    positiveEnabled = name.value.isNotBlank() && !pending.value,
                    negativeContent = "Cancel",
                    negativeEnabled = !pending.value,
                    dismissEnabled = !pending.value,
                    closeOnPositive = false,
                    content = {
                        SonifyDialogTextField(name.value, { name.value = it }, "Name", Modifier.fillMaxWidth())
                    }
                )
            }
        }
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextInput("Playlist")
        compose.onNodeWithText("Create").assertIsEnabled().performClick()
        compose.onNodeWithText("Create").assertIsNotEnabled()
        compose.onNodeWithText("Cancel").assertIsNotEnabled()
        compose.runOnIdle {
            assertEquals(1, submitted)
            assertEquals(0, dismissed)
            pending.value = false
        }
        compose.onNodeWithText("Create").assertIsEnabled()
    }

    @Test
    fun confirmationIsDeliveredOnceAfterExit() {
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(true)
        var confirmed = 0
        compose.setContent {
            YosMusicTheme(darkTheme = true) {
                if (visible.value) {
                    SonifyDialog(
                        title = "Remove track",
                        onDismissRequest = { visible.value = false },
                        positiveContent = "Remove",
                        onPositive = { confirmed++; visible.value = false },
                        destructive = true
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Remove").performClick()
        compose.runOnIdle { assertEquals(0, confirmed) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, confirmed) }
        compose.onNodeWithText("Remove track").assertDoesNotExist()
    }

    @Test
    fun callerCompositionLocalsArePreserved() {
        val localLabel = androidx.compose.runtime.staticCompositionLocalOf { "Missing caller" }
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                androidx.compose.runtime.CompositionLocalProvider(localLabel provides "Caller label") {
                    SonifyDialog(
                        title = "Context",
                        onDismissRequest = {},
                        positiveContent = "OK",
                        onPositive = {},
                        content = { Text(localLabel.current) }
                    )
                }
            }
        }
        compose.onNodeWithText("Caller label").assertIsDisplayed()
        compose.onNodeWithText("Missing caller").assertDoesNotExist()
    }

    @Test
    fun tertiaryActionIsPinnedAndDeliveredAfterExit() {
        compose.mainClock.autoAdvance = false
        val visible = mutableStateOf(true)
        var ignored = 0
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                if (visible.value) {
                    SonifyDialog(
                        title = "Update available",
                        onDismissRequest = { visible.value = false },
                        positiveContent = "Download",
                        onPositive = {},
                        negativeContent = "Later",
                        tertiaryContent = "Ignore this update",
                        onTertiary = { ignored++; visible.value = false },
                        content = { Column { repeat(80) { Text("Note $it") } } }
                    )
                }
            }
        }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithText("Download").assertIsDisplayed()
        compose.onNodeWithText("Later").assertIsDisplayed()
        compose.onNodeWithText("Note 79").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Ignore this update").assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(0, ignored) }
        compose.mainClock.advanceTimeBy(500)
        compose.runOnIdle { assertEquals(1, ignored) }
        compose.onNodeWithText("Update available").assertDoesNotExist()
    }

    @Test
    fun longBodyScrollsWithoutHidingActions() {
        compose.setContent {
            YosMusicTheme(darkTheme = false) {
                SonifyDialog(
                    title = "Choose account",
                    onDismissRequest = {},
                    positiveContent = "Cancel",
                    onPositive = {},
                    content = { Column { repeat(80) { Text("Account $it") } } }
                )
            }
        }
        compose.onNodeWithText("Cancel").assertIsDisplayed()
        compose.onNodeWithText("Account 79").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Cancel").assertIsDisplayed()
    }
}
