package yos.music.player.ui.pages.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import yos.music.player.ui.theme.headline
import yos.music.player.ui.theme.headlineDark
import yos.music.player.ui.theme.withNight

/**
 * 设置项目的横向分割线
 */
@Composable
fun Divider(modifier: Modifier = Modifier) =
    Spacer(
        modifier
            .fillMaxWidth()
            .padding(start = 15.dp, end = 16.dp)
            .height(0.3.dp)
            .alpha(0.2f)
            .background(color = headline withNight headlineDark)
    )

/**
 * 设置区块的填充，一般在 ListHeader 上面
 */
@Composable
fun GroupSpacer(modifier: Modifier = Modifier) =
    Spacer(
        modifier
            .height(18.dp)
    )

/**
 * 设置区块的填充，一般在 ListHeader 上面
 */
@Composable
fun GroupSpacerMedium(modifier: Modifier = Modifier) =
    Spacer(
        modifier
            .height(14.dp)
    )