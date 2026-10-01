package yos.music.player.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import yos.music.player.data.libraries.SettingsLibrary

val primary = Color(0xFFF54047)
val primaryDark = Color(0xFFE64366)
val headline = Color(0xFF88878e)
val headlineDark = Color(0xFF8e8d93)
val background = Color.White
val backgroundDark = Color.Black

val settingBack = Color(0xfff2f1f6)
val settingBackDark = Color(0xFF000002)
val settingContainerBack = Color.White
val settingContainerBackDark = Color(0xFF1c1c1e)

@Composable
infix fun Color.withNight(nightColor: Color): Color {
    return if (isFlamingoInDarkMode()) nightColor else this
}

@Composable
fun isFlamingoInDarkMode(): Boolean {
    return if (SettingsLibrary.CustomTheme == "Auto") isSystemInDarkTheme() else SettingsLibrary.CustomTheme == "Dark"
}