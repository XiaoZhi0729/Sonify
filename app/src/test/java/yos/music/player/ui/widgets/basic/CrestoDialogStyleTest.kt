package yos.music.player.ui.widgets.basic

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Test

class CrestoDialogStyleTest {
    @Test
    fun destructiveButtonMatchesBothThemesAndMaterials() {
        for (dark in listOf(false, true)) {
            for (liquid in listOf(false, true)) {
                val style = CrestoDialogStyle(liquid, dark)
                val (background, text) = style.buttonColors(primary = true, destructive = true, enabled = true)
                assertEquals(Color(0xFFFB2C36), background)
                assertEquals(Color.White, text)
            }
        }
    }

    @Test
    fun normalPrimaryMatchesOriginalBlueAndWhite() {
        for (dark in listOf(false, true)) {
            val colors = CrestoDialogStyle(false, dark).buttonColors(true, false, true)
            assertEquals(Color(0xFF2B7FFF) to Color.White, colors)
        }
    }

    @Test
    fun cancelKeepsFivePercentBackgroundAndOpaqueText() {
        for (dark in listOf(false, true)) {
            val content = if (dark) Color.White else Color.Black
            val colors = CrestoDialogStyle(false, dark).buttonColors(false, false, true)
            assertEquals(content.copy(alpha = 0.05f) to content, colors)
        }
    }

    @Test
    fun disabledColorsDoNotFadeTheWholeButton() {
        for (dark in listOf(false, true)) {
            val style = CrestoDialogStyle(false, dark)
            val content = style.contentColor
            assertEquals(content.copy(alpha = 0.1f) to content.copy(alpha = 0.3f), style.buttonColors(true, false, false))
            assertEquals(content.copy(alpha = 0.025f) to content.copy(alpha = 0.25f), style.buttonColors(false, false, false))
        }
    }

    @Test
    fun materialBranchesMatchOriginalMetrics() {
        val normal = CrestoDialogStyle(false, false)
        val liquid = CrestoDialogStyle(true, false)
        assertEquals(24.dp, normal.cornerRadius)
        assertEquals(32.dp, normal.blurRadius)
        assertEquals(36.dp, liquid.cornerRadius)
        assertEquals(16.dp, liquid.blurRadius)
        assertEquals(Color.White, normal.surfaceColor)
        assertEquals(Color(0xFF1B1C1D), CrestoDialogStyle(true, true).surfaceColor)
    }
}
