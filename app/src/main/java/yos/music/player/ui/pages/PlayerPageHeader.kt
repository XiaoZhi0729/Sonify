package yos.music.player.ui.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.ui.widgets.effects.overlayEffect

/** 头部行距顶部的内边距：手机沿用原标定值。 */
private val HeaderTopPadding = 10.dp

/**
 * 平板竖屏的顶部内边距。
 *
 * 平板屏高接近手机的 1.4 倍，10dp 在 PlayingBar 小封面下方显得几乎贴着，标题像被顶到
 * 封面上沿；这里单独加一档，与手机区分。
 */
private val HeaderTopPaddingTabletPortrait = 24.dp

/**
 * 播放页二级页（播放列表 / 评论）共用的头部行几何。
 *
 * 两页的标题位置、字重、右侧元素对齐必须同源，否则任何一边单独调整都会漂；
 * 这里只固定行高、内外边距与垂直居中，内容由调用方填。
 */
@Composable
internal fun PlayerPageHeaderRow(content: @Composable RowScope.() -> Unit) {
    val configuration = LocalConfiguration.current
    val topPadding = if (
        configuration.screenWidthDp >= 600 &&
        configuration.screenHeightDp > configuration.screenWidthDp
    ) {
        HeaderTopPaddingTabletPortrait
    } else {
        HeaderTopPadding
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 30.dp)
            .padding(top = topPadding)
            .height(65.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content
    )
}

/** 头部主标题 + 副标题，字号/字重与「播放列表」页完全一致。 */
@Composable
internal fun RowScope.PlayerPageHeaderText(title: String, subtitle: String?) {
    Column(modifier = Modifier.weight(1f)) {
        Text(
            text = title,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Medium
        )
        if (subtitle != null) {
            Text(
                text = subtitle,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .overlayEffect()
                    .alpha(0.35f)
            )
        }
    }
}
