/*
 * Cresto / Glasense dialog tokens, Apache-2.0; Tailwind color tokens, MIT.
 * Modified for Sonify: pure style selection shared by the renderer and tests.
 * See assets/licenses/cresto-NOTICE.txt and cresto-THIRD_PARTY_NOTICES.md.
 */
package yos.music.player.ui.widgets.basic

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Immutable
internal data class CrestoDialogStyle(val liquid: Boolean, val dark: Boolean) {
    val cornerRadius = if (liquid) 36.dp else 24.dp
    val blurRadius = if (liquid) 16.dp else 32.dp
    val contentColor = if (dark) Color.White else Color.Black
    val surfaceColor = if (dark) Color(0xFF1B1C1D) else Color.White
    val primaryColor = Color(0xFF2B7FFF)
    val destructiveColor = Color(0xFFFB2C36)

    fun buttonColors(primary: Boolean, destructive: Boolean, enabled: Boolean): Pair<Color, Color> =
        when {
            !enabled && primary -> contentColor.copy(alpha = 0.1f) to contentColor.copy(alpha = 0.3f)
            !enabled -> contentColor.copy(alpha = 0.025f) to contentColor.copy(alpha = 0.25f)
            primary -> (if (destructive) destructiveColor else primaryColor) to Color.White
            else -> contentColor.copy(alpha = 0.05f) to (if (destructive) destructiveColor else contentColor)
        }
}
