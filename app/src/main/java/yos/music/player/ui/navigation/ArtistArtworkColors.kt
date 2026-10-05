package yos.music.player.ui.navigation

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/** Raw artwork color and gentle RGB variants; no theme-driven HSV clipping. */
@Immutable
data class ArtistArtworkColors(
    val accent: Color,
    val bright: Color,
    val dark: Color
) {
    val content: Color
        get() = if (accent.luminance() > 0.179f) Color(0xFF161616) else Color.White

    companion object {
        fun fromRgb(rgb: Int?, isDark: Boolean): ArtistArtworkColors {
            val accent = rgb?.let(::Color)
                ?: if (isDark) Color(0xFF1B1B1D) else Color(0xFFF2F2F4)
            return ArtistArtworkColors(
                accent = accent,
                bright = lerp(accent, Color.White, 0.16f),
                dark = lerp(accent, Color.Black, 0.16f)
            )
        }
    }
}
