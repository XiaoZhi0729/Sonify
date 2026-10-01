package yos.music.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import yos.music.player.ui.widgets.basic.YosWrapper

private val DarkColorScheme = darkColorScheme(
    primary = primaryDark,
    background = backgroundDark,
    onBackground = background,
    surface = backgroundDark,
    onSurface = background,
    secondary = settingBackDark,
    onSecondary = settingContainerBackDark
)

private val LightColorScheme = lightColorScheme(
    primary = primary,
    background = background,
    onBackground = backgroundDark,
    surface = background,
    onSurface = backgroundDark,
    secondary = settingBack,
    onSecondary = settingContainerBack
)

@Composable
fun YosMusicTheme(
    darkTheme: Boolean = isFlamingoInDarkMode(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }

        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val view = LocalView.current
    if (!view.isInEditMode) {
        YosWrapper {
            SideEffect {
                val window = (view.context as Activity).window
                window.statusBarColor = Color.Transparent.toArgb()
                WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars =
                    !darkTheme
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}