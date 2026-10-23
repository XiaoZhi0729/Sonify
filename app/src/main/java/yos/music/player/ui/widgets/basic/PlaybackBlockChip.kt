package yos.music.player.ui.widgets.basic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.data.objects.PlaybackBlockState

/**
 * 播放被风控拦截时的常驻胶囊（点一下重新打开说明框）。
 *
 * 为什么要有它：说明框是可以关掉的，关掉后如果没有别的入口，用户就只剩"没声音"。
 * 这枚胶囊是纯状态投影——没有拦截时不占任何布局（直接不组合）。
 *
 * 颜色写死白色：播放页内容色恒为白（背景是封面模糊层），与时间行/音质胶囊同一套。
 */
@Composable
fun PlaybackBlockChip(modifier: Modifier = Modifier) {
    if (PlaybackBlockState.block == null) return
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(percent = 50))
            .background(Color.White.copy(alpha = 0.12f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { PlaybackBlockState.requestPrompt() }
            .padding(horizontal = 14.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Spacer(
            Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.85f))
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = stringResource(id = R.string.playback_block_chip),
            fontSize = 12.sp,
            color = Color.White.copy(alpha = 0.85f)
        )
    }
}
