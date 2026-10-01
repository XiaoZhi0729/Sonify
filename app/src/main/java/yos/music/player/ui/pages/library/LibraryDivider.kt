package yos.music.player.ui.pages.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import yos.music.player.ui.theme.withNight

/**
 * 资料库的横向分割线
 */
@Composable
fun LibraryDivider(modifier: Modifier = Modifier) =
    Spacer(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 60.dp, end = 16.dp)
            .alpha(0.15f)
            .height(0.5.dp)
            .background(Color.Black withNight Color.White)
    )