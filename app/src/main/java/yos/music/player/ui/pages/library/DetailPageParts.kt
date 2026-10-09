package yos.music.player.ui.pages.library

import androidx.compose.foundation.clickable
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import yos.music.player.ui.widgets.basic.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.enterCoverCorner
import yos.music.player.ui.widgets.basic.isBlocked
import yos.music.player.ui.widgets.basic.rememberSongAvailability
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.showSongUnavailableToast
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.effects.ShadowType

/**
 * 详情页通用头部（Apple Music 式，仅平板 ≥600dp 使用；手机保持各页旧版居中布局）：
 * 左上大封面（随屏宽 28%，钳制 130–300dp），右侧标题/附加行/数量/标签，
 * 弹性空隙后是简介（可空不显示）与 随机/播放 按钮沉底（与封面下缘对齐）。
 *
 * [extraLines] 插在标题与数量之间（如专辑歌手/发行日期），样式由调用方自定；
 * [countText] 已由调用方格式化（含"首"等单位）；[intro] 空白时不显示。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun DetailPageHeader(
    title: String,
    coverData: () -> Any?,
    tag: String,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
    extraLines: (@Composable () -> Unit)? = null,
    coverSharedElementKey: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    countText: String? = null,
    intro: String? = null
) {
    BoxWithConstraints(modifier) {
        // 封面随屏宽自适应；窄平板钳到下限 130dp，宽平板钳到上限 300dp
        val coverSize = (maxWidth * 0.28f).coerceIn(130.dp, 300.dp)

        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = 9.5.dp)
                .padding(horizontal = 18.dp)
                // 底距按封面动态计算：Medium 阴影底缘 ≈ 封面×1.05 + 28dp 模糊，
                // 保证歌曲列表起始位置在封面阴影之下，不被阴影遮挡
                .padding(bottom = coverSize * 0.06f + 30.dp)
                .statusBarsPadding()
        ) {
            // IntrinsicSize.Min：右栏高度跟随封面，按钮可沉到头部底部
            Row(
                modifier = Modifier.height(IntrinsicSize.Min),
                verticalAlignment = Alignment.Top
            ) {
                // 大封面：URL 为空时组件内部显示占位图
                val coverModifier = Modifier
                    .size(coverSize)
                    .then(
                        if (sharedTransitionScope != null && animatedVisibilityScope != null && coverSharedElementKey != null) {
                            with(sharedTransitionScope) {
                                Modifier.sharedElement(
                                    sharedContentState = rememberSharedContentState(coverSharedElementKey),
                                    animatedVisibilityScope = animatedVisibilityScope
                                )
                            }
                        } else Modifier
                    )
                Box(coverModifier) {
                    ShadowImage(
                        modifier = Modifier.fillMaxSize(),
                        dataLambda = coverData,
                        contentDescription = null,
                        // 转场中圆角从来源封面值渐变到 7dp，静止恒为 7dp
                        cornerRadius = enterCoverCorner(animatedVisibilityScope, 7.dp),
                        imageQuality = ImageQuality.RAW,
                        shadowType = ShadowType.Medium
                    )
                }

                Spacer(modifier = Modifier.width(15.dp))

                // 封面右侧：上方标题/附加行/数量/标签，底部 简介+随机/播放按钮
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(top = 4.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 20.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )

                    extraLines?.invoke()

                    if (countText != null) {
                        Text(
                            text = countText,
                            fontSize = 17.5.sp,
                            lineHeight = 23.5.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(top = 2.dp)
                        )
                    }

                    Text(
                        text = tag,
                        fontSize = 11.5.sp,
                        modifier = Modifier
                            .alpha(0.4f)
                            .padding(top = 2.dp)
                    )

                    // 弹性空隙：把简介与按钮推到头部底部（与封面下缘对齐）
                    Spacer(Modifier.weight(1f))

                    // 歌单/专辑简介：紧贴播放按钮上方；无简介不显示
                    if (!intro.isNullOrBlank()) {
                        Text(
                            text = intro,
                            modifier = Modifier
                                .alpha(0.5f)
                                .padding(bottom = 10.dp),
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            fontSize = 13.sp,
                            lineHeight = 18.sp
                        )
                    }

                    YosWrapper {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(bottom = 4.dp)
                        ) {
                            // 随机：全部歌曲进队列 + 原生 shuffle，从随机位置开始
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_shuffle),
                                label = stringResource(id = R.string.normal_button_shuffle),
                                modifier = Modifier.weight(1f)
                            ) {
                                onShuffle()
                            }
                            Spacer(modifier = Modifier.width(15.dp))
                            // 播放：全部歌曲进队列，从第一首开始
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_play),
                                label = stringResource(id = R.string.normal_button_play),
                                modifier = Modifier.weight(1f)
                            ) {
                                onPlayAll()
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 宽屏（≥600dp）歌曲行（对齐 Apple Music 平板布局）：
 * 左半为 封面+歌名（行左右 padding 对称，50% 分界正好是屏幕 X 轴正中央），
 * 歌手名自屏幕中央起左对齐，时长贴最右（随行 padding 距屏幕边缘 22dp）。
 * 视觉规范对齐共享 MusicList（64dp 行高 / 52dp 封面 / 16sp 歌名 / 13sp 歌手）。
 */
@Composable
fun DetailSongRowWide(
    music: YosMediaItem,
    itemClick: () -> Unit
) {
    // 不可播（无版权/付费限制等）置灰：点击只提示原因，不进播放、不切歌
    val availability = rememberSongAvailability(music)
    val blocked = availability.isBlocked
    val context = LocalContext.current
    Row(
        modifier = Modifier
            .height(64.dp)
            .fillMaxWidth()
            .alpha(if (blocked) 0.4f else 1f)
            .clickable {
                if (blocked) showSongUnavailableToast(context, music, availability.reason)
                else itemClick()
            }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左半：封面 + 歌名
        Row(
            modifier = Modifier.fillMaxWidth(0.5f),
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

            Text(
                text = music.title ?: defaultTitle,
                modifier = Modifier.padding(start = 16.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 16.sp,
                lineHeight = 16.sp
            )
        }

        // 歌手列：自屏幕正中央起左对齐
        Box(Modifier.weight(1f)) {
            Text(
                text = music.artistsName ?: defaultArtistsName,
                modifier = Modifier.alpha(0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontSize = 13.sp,
                lineHeight = 13.sp
            )
        }

        // 时长：最右；无时长数据时不占位
        if (music.duration > 0) {
            Text(
                text = formatDurationMs(music.duration),
                modifier = Modifier
                    .alpha(0.5f)
                    .padding(start = 12.dp),
                fontSize = 13.sp,
                lineHeight = 13.sp
            )
        }
    }
}

/**
 * 毫秒时长 → m:ss（分钟不补零、秒补零）；≥1 小时 → h:mm:ss。
 */
fun formatDurationMs(durationMs: Long): String {
    val totalSeconds = durationMs / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
