package yos.music.player.ui.theme

import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class ScreenCornerRadiusStateTest {
    @get:Rule val compose = createComposeRule()

    @Test fun rememberedTransitionReadsUpdatedRadius() {
        val radius = mutableStateOf(30.dp)
        compose.setContent {
            val latest = rememberUpdatedState(radius.value)
            val transitionRadius = remember { derivedStateOf { latest.value } }
            Text(transitionRadius.value.value.toString(), modifier = Modifier.testTag("radius"))
        }
        compose.onNodeWithTag("radius").assertTextEquals("30.0")
        compose.runOnIdle { radius.value = 48.dp }
        compose.onNodeWithTag("radius").assertTextEquals("48.0")
        compose.runOnIdle { radius.value = 0.dp }
        compose.onNodeWithTag("radius").assertTextEquals("0.0")
    }
}
