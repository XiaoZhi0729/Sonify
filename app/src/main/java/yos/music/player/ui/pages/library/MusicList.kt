package yos.music.player.ui.pages.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.SongAddToPlaylistButton

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun /*LazyItemScope.*/MusicList(
    music: YosMediaItem,
    horizontalPadding: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
    itemClick: () -> Unit
) {
    /*rememberSaveable(stateSaver = object : Saver<String?, Any> {
    override fun restore(value: Any): String? {
        return value as String?
    }

    override fun SaverScope.save(value: String?): Any {
        return value ?: ""
    }
}) {
    mutableStateOf(null)
}

LaunchedEffect(Unit) {
    val path = context.filesDir.absolutePath + "/${music().ID}.png"
    thumb.value = path
}*/

    /*val background = Color(Color.Transparent.toArgb())
    val originalColor = rememberSaveable(music()) {
        mutableStateOf(0)
    }
    val duration = rememberSaveable(music()) {
        mutableStateOf("00:00")
    }
    if (duration.value == "00:00") {
        YosWrapper {
            LaunchedEffect(Unit) {
                val time = MusicScanner(context).timeConversion(music().duration)
                duration.value = time.min + ":" + time.sec
            }
        }
    }
    if (originalColor.value == 0) {
        if (thumb != null && music() == musicPlaying.value) {
            val thumb = context.filesDir.absolutePath + "/${music().ID}.jpg"
            val bitmap = if (File(thumb).exists()) BitmapFactory.decodeFile(thumb) else null
            if (bitmap != null) {
                val builder = Palette.from(bitmap)
                val palette = builder.generate()
                originalColor.value =
                    Color(palette.getLightVibrantColor(background.toArgb())).toArgb()
                if (originalColor.value == background.toArgb()) {
                    originalColor.value =
                        Color(palette.getVibrantColor(background.toArgb())).toArgb()
                }
                if (originalColor.value == background.toArgb()) {
                    originalColor.value =
                        Color(palette.getLightMutedColor(background.toArgb())).toArgb()
                }
                if (originalColor.value == background.toArgb()) {
                    originalColor.value =
                        Color(palette.getMutedColor(background.toArgb())).toArgb()
                }
                if (originalColor.value == background.toArgb()) {
                    originalColor.value =
                        Color(palette.getDominantColor(background.toArgb())).toArgb()
                }
            }
        }
    }
    val color by animateColorAsState(
        targetValue = if ((music() == musicPlaying.value)) Color(originalColor.value) else Color(
            MaterialTheme.colorScheme.background.toArgb()
        )
    )
    val alpha by animateFloatAsState(if (music() == musicPlaying.value) 0.3F else 1F)*/
    // 不做点击前的可播性预测：列表接口不下发可用的版权字段（实测 Privilege/FailProcess
    // 与能否拿到音源零相关），主动探测又会被本地负缓存、档位与账号凭据污染，判错一次就是
    // "能播却灰"。真实不可播的歌交给播放失败链路（跳歌 + 提示），见 skipPastUnplayable。
    // 行点击与行尾菜单的「播放」都直接进播放，不做前置拦截。
    val playAction: () -> Unit = itemClick
    Row(
        modifier = Modifier
            /*.animateItem(fadeInSpec = null, fadeOutSpec = null)*/
            .height(64.dp)
            .fillMaxWidth()
            .then(
                if (onLongClick != null) {
                    Modifier.combinedClickable(onClick = itemClick, onLongClick = onLongClick)
                } else {
                    Modifier.clickable(onClick = itemClick)
                }
            )
            .padding(horizontal = horizontalPadding),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShadowImageWithCache(
            dataLambda = { music.thumb },
            contentDescription = null,
            modifier = Modifier.size(52.dp),
            cornerRadius = 3.5.dp,
            shadowAlpha = 0f,
            imageQuality = ImageQuality.LOW
        )

        Column(
            Modifier
                .weight(1f)
                .padding(start = 16.dp)
        ) {
            Text(
                text = music.title ?: defaultTitle,
                modifier = Modifier.padding(bottom = 1.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                lineHeight = 16.sp,
            )

            Text(
                text = music.artistsName ?: defaultArtistsName,
                // 用颜色 alpha 代替 Modifier.alpha(0.5f)：后者会给每行文字套一个离屏图层，
                // 逐帧重绘时每行多一次 saveLayer（trace: "alpha caused saveLayer *x57"）。
                color = LocalContentColor.current.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                lineHeight = 13.sp,
            )
        }

        // 在线歌曲行尾三竖点（本地歌曲无酷狗 hash，组件内部直接不渲染）
        SongAddToPlaylistButton(music = music, onPlay = playAction, onDelete = onLongClick)
    }
}
