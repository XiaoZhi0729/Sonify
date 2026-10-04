package yos.music.player.ui.widgets.basic

import androidx.compose.runtime.Composable

@Composable
fun YosWrapper(content: @Composable () -> Unit) {
    content()
}
