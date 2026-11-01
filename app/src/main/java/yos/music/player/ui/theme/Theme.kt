package yos.music.player.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.text.selection.LocalTextSelectionColors
import androidx.compose.foundation.text.selection.TextSelectionColors
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.SonifyDialogHost

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

// 光标/选择手柄由 Compose 画在独立 Popup 窗口里，层级永远压过页面内容，
// 而 MaterialTheme 默认把 handleColor 设为 colorScheme.primary（本工程的红色），
// 导致输入框里出现一块盖住一切的红色水滴。这里统一改成透明，手柄不再绘制；
// 拖拽选择手势本身不受影响。选中文字的高亮底色也一并去掉 primary 的染色。
private val TransparentSelectionColors = TextSelectionColors(
    handleColor = Color.Transparent,
    backgroundColor = Color(0x33808080)
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
        // 必须挂在 MaterialTheme 内部：MaterialTheme 自身会 provide
        // LocalTextSelectionColors（handleColor = colorScheme.primary），
        // 写在外部会被它覆盖回红色。
        content = {
            CompositionLocalProvider(
                LocalTextSelectionColors provides TransparentSelectionColors
            ) {
                SonifyDialogHost(content)
            }
        }
    )
}