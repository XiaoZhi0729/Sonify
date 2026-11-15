package yos.music.player.ui.pages.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.ui.theme.headline
import yos.music.player.ui.theme.headlineDark
import yos.music.player.ui.theme.withNight

@Composable
fun ListHeader(content: String, horizontalPadding: Dp = 34.5.dp) = Box(
    modifier = Modifier
        .fillMaxWidth()
        .padding(start = horizontalPadding, end = horizontalPadding, bottom = 6.dp)
) {
    Text(text = content, fontSize = 13.sp, lineHeight = 15.sp, color = headline withNight headlineDark)
}