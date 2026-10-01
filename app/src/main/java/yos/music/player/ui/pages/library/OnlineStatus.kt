package yos.music.player.ui.pages.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.ui.theme.withNight

/**
 * 在线页面统一状态行（loading / empty / error）。
 *
 * 视觉规范与 MusicList 行距一致（22.dp 水平边距），三态：
 *   - "loading"       小进度圈 + 加载文案
 *   - "empty"         空态文案（对齐原版列表空态规范：18sp / α0.6）
 *   - "error:<msg>"   错误色提示，最多两行
 * 其余状态（含 "ok"）不渲染任何内容。
 */
@Composable
fun OnlineStatusItem(
    status: String,
    loadingText: String,
    emptyText: String,
    modifier: Modifier = Modifier
) {
    if (status != "loading" && status != "empty" && !status.startsWith("error:")) return

    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            status == "loading" -> {
                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                Spacer(Modifier.size(10.dp))
                Text(
                    text = loadingText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            status == "empty" -> Text(
                text = emptyText,
                fontSize = 18.sp,
                modifier = Modifier.alpha(0.6f)
            )

            else -> Text(
                text = status.removePrefix("error:"),
                fontSize = 13.sp,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 歌曲列表行间分割线，对齐 NormalMusic 现有规范
 * （起始 88.dp 与 MusicList 封面右侧对齐，0.5dp、alpha 0.15）。
 * startPadding / endPadding 可按需覆写（如新歌精选 horizontalPadding=0 时传 66.dp）。
 */
@Composable
fun OnlineListItemDivider(
    modifier: Modifier = Modifier,
    startPadding: Dp = 88.dp,
    endPadding: Dp = 16.dp
) =
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = startPadding, end = endPadding)
            .alpha(0.15f)
            .height(0.5.dp)
            .background(Color.Black withNight Color.White)
    )
