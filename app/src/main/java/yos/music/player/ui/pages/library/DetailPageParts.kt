package yos.music.player.ui.pages.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import yos.music.player.ui.widgets.basic.SongAddToPlaylistButton
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsList
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultArtists
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.pages.library.artists.ArtistCollectButton
import yos.music.player.ui.pages.library.artists.ArtistRoundActionButton
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.enterCoverCorner
import yos.music.player.ui.widgets.basic.isBlocked
import yos.music.player.ui.widgets.basic.rememberSongAvailability
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.handleBlockedClick
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.effects.ShadowType
import yos.music.player.ui.widgets.effects.overlayEffect

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
    intro: String? = null,
    // 非 null 时数量行与两个动作按钮改用该色（页面底色不是主题色的场景，
    // 如在线专辑详情的封面色底）；默认 null 保持原观感（主题红）。
    accentColor: Color? = null,
    // 收藏开关（可选）：非 null 时在两个动作按钮右侧追加「+ / ✓」圆钮。
    // 目前只有在线专辑详情页传入；其余调用点不传，观感与布局零变化。
    collected: Boolean? = null,
    onToggleCollect: (() -> Unit)? = null,
    // 通用尾部动作圆钮（可选）：非 null 时**优先于** collected 渲染同一位置的 42dp 圆钮。
    // 目前只有在线歌单详情页传入（「添加歌曲」）；图标/无障碍文案由调用方给。
    trailingIcon: Int? = null,
    trailingIconDescription: String? = null,
    trailingEnabled: Boolean = true,
    onTrailingClick: (() -> Unit)? = null,
    collectEnabled: Boolean = true
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
                            color = accentColor ?: MaterialTheme.colorScheme.primary,
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
                                modifier = Modifier.weight(1f),
                                contentColor = accentColor,
                                containerColor = accentColor?.copy(alpha = 0.18f)
                            ) {
                                onShuffle()
                            }
                            Spacer(modifier = Modifier.width(15.dp))
                            // 播放：全部歌曲进队列，从第一首开始
                            NormalButton(
                                icon = painterResource(id = R.drawable.button_icon_play),
                                label = stringResource(id = R.string.normal_button_play),
                                modifier = Modifier.weight(1f),
                                contentColor = accentColor,
                                containerColor = accentColor?.copy(alpha = 0.18f)
                            ) {
                                onPlayAll()
                            }
                            // 尾部动作圆钮：固定 42dp，不参与 weight，避免挤压两个等宽按钮。
                            // 通用 trailingIcon 优先（歌单「添加歌曲」），否则走收藏开关。
                            if (trailingIcon != null && onTrailingClick != null) {
                                Spacer(modifier = Modifier.width(12.dp))
                                ArtistRoundActionButton(
                                    icon = trailingIcon,
                                    contentDescription = trailingIconDescription,
                                    iconTint = accentColor ?: Color.Unspecified,
                                    highlighted = false,
                                    iconSize = 22.dp,
                                    enabled = trailingEnabled,
                                    containerColor = accentColor?.copy(alpha = 0.18f)
                                ) {
                                    onTrailingClick()
                                }
                            } else if (collected != null && onToggleCollect != null) {
                                Spacer(modifier = Modifier.width(12.dp))
                                // 加号 ↔ 勾号 互相形变（不是换图）
                                ArtistCollectButton(
                                    collected = collected,
                                    iconSize = 22.dp,
                                    iconTint = accentColor ?: Color.Unspecified,
                                    containerColor = accentColor?.copy(alpha = 0.18f),
                                    enabled = collectEnabled
                                ) {
                                    onToggleCollect()
                                }
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
    onDelete: (() -> Unit)? = null,
    itemClick: () -> Unit
) {
    // 不可播（无版权/付费限制等）置灰：点击**先复核再决定**，复核通过仍可正常播放
    val availability = rememberSongAvailability(music)
    val blocked = availability.isBlocked
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 行点击与菜单「播放」共用：不可播歌曲先复核再决定是否播放
    val playAction: () -> Unit = {
        if (blocked) scope.launch { handleBlockedClick(context, music) { itemClick() } }
        else itemClick()
    }
    Row(
        modifier = Modifier
            .height(64.dp)
            .fillMaxWidth()
            .alpha(if (blocked) 0.4f else 1f)
            .clickable(onClick = playAction)
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

        // 在线歌曲行尾三竖点（本地歌曲无酷狗 hash，组件内部直接不渲染）
        SongAddToPlaylistButton(music = music, onPlay = playAction, onDelete = onDelete)
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

/**
 * 专辑歌曲行（窄屏 <600dp，无封面）：行首为曲目序号，视觉对齐本地专辑页 AlbumSongsItem
 * （24dp 序号框 + 歌名 + 仅当与专辑歌手不同时才显示的歌手行；无固定行高）。
 *
 * 不可播置灰 + 点击「先复核再决定」（与共享 [MusicList] 一致）；本地歌曲 kugouHash() 为
 * null → stateOf/ensureChecked 均为 no-op，故本行对本地专辑零影响。
 */
@Composable
fun AlbumTrackRow(
    music: YosMediaItem,
    trackNumber: Int?,
    mainArtists: List<String>,
    modifier: Modifier = Modifier,
    itemClick: () -> Unit
) {
    val availability = rememberSongAvailability(music)
    val blocked = availability.isBlocked
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 行点击与菜单「播放」共用：不可播歌曲先复核再决定是否播放
    val playAction: () -> Unit = {
        if (blocked) scope.launch { handleBlockedClick(context, music) { itemClick() } }
        else itemClick()
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (blocked) 0.4f else 1f)
            .clickable(onClick = playAction)
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            contentAlignment = Alignment.TopCenter, modifier = Modifier
                .width(24.dp)
                .fillMaxHeight()
        ) {
            Text(
                text = "${trackNumber ?: "-"}",
                fontSize = 16.sp,
                modifier = Modifier.alpha(0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(modifier = Modifier.width(10.dp))

        Column(
            Modifier
                .weight(1f)
                .padding(vertical = 10.dp)
        ) {
            Text(
                text = music.title ?: defaultTitle,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 20.sp
            )
            YosWrapper {
                val needShowArtists = remember(music) {
                    derivedStateOf {
                        !mainArtists.containsAll(music.artistsList ?: defaultArtists)
                    }
                }
                if (needShowArtists.value) {
                    Text(
                        text = music.artistsName ?: defaultArtistsName,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 16.sp,
                        modifier = Modifier.alpha(0.4f)
                    )
                }
            }
        }

        // 在线歌曲行尾三竖点（本地歌曲无酷狗 hash，组件内部直接不渲染）
        SongAddToPlaylistButton(music = music, onPlay = playAction)
    }
}

/**
 * 专辑歌曲行（宽屏 ≥600dp，无封面）：由 [DetailSongRowWide] 派生，把 52dp 封面列换成
 * 曲目序号列，其余不变（歌名占左半 / 歌手自屏幕中央起左对齐 / 时长贴最右）。
 * 同样保留不可播置灰 + 点击复核。
 */
@Composable
fun AlbumTrackRowWide(
    music: YosMediaItem,
    trackNumber: Int?,
    itemClick: () -> Unit
) {
    val availability = rememberSongAvailability(music)
    val blocked = availability.isBlocked
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 行点击与菜单「播放」共用：不可播歌曲先复核再决定是否播放
    val playAction: () -> Unit = {
        if (blocked) scope.launch { handleBlockedClick(context, music) { itemClick() } }
        else itemClick()
    }
    Row(
        modifier = Modifier
            .height(64.dp)
            .fillMaxWidth()
            .alpha(if (blocked) 0.4f else 1f)
            .clickable(onClick = playAction)
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左半：序号 + 歌名
        Row(
            modifier = Modifier.fillMaxWidth(0.5f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.width(24.dp)
            ) {
                Text(
                    text = "${trackNumber ?: "-"}",
                    fontSize = 16.sp,
                    modifier = Modifier.alpha(0.4f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

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

        // 在线歌曲行尾三竖点（本地歌曲无酷狗 hash，组件内部直接不渲染）
        SongAddToPlaylistButton(music = music, onPlay = playAction)
    }
}

private data class CollapsedIntro(val text: AnnotatedString, val hasMore: Boolean)

/**
 * 详情页简介（常驻，位于操作行下方）：专辑详情 / 在线歌单详情共用。
 *
 * 排版与歌手页简介正文同源：14sp / 22sp 行高；浅底 `#161616 @0.55`、深底 `白 @0.45`，
 * 深底再叠一层 Plus 加色微光（同歌手页）。
 *
 * 折叠规则：超过两行时截断到第二行末尾，接「…」+「更多」；「更多」用满不透明度，
 * 与半透明正文形成对比，表明可点。点击「更多」展开全文，展开后正文末尾接「收起」，
 * 点击收起回到两行。
 */
@Composable
fun DetailIntro(
    text: String,
    lightBackdrop: Boolean,
    modifier: Modifier = Modifier
) {
    var expanded by remember(text) { mutableStateOf(false) }
    val bodyColor = if (lightBackdrop) Color(0xFF161616).copy(alpha = 0.55f)
                    else Color.White.copy(alpha = 0.45f)
    val moreColor = if (lightBackdrop) Color(0xFF161616) else Color.White
    val style = remember(bodyColor) {
        TextStyle(fontSize = 14.sp, lineHeight = 22.sp, color = bodyColor)
    }
    val moreLabel = stringResource(id = R.string.online_album_intro_more)
    val lessLabel = stringResource(id = R.string.online_album_intro_less)
    val measurer = rememberTextMeasurer()

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val widthPx = constraints.maxWidth
        val collapsed = remember(text, widthPx, style, moreColor, moreLabel) {
            collapseIntro(text, measurer, style, widthPx, moreColor, moreLabel)
        }
        // 展开态：全文 + 末尾「收起」，两者同色（满不透明度）以区别于正文
        val expandedText = remember(text, moreColor, lessLabel) {
            buildAnnotatedString {
                append(text)
                append(" ")
                withStyle(SpanStyle(color = moreColor)) { append(lessLabel) }
            }
        }
        val shown = if (expanded) expandedText else collapsed.text
        val tailLabel = if (expanded) lessLabel else moreLabel
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        // 只有确实存在尾部控件（折叠态被截断 / 展开态有「收起」）时才挂点击，
        // 命中判定落在末尾的「更多」/「收起」区间
        val tailTap = if (expanded || collapsed.hasMore) {
            Modifier.pointerInput(shown, expanded) {
                detectTapGestures { position ->
                    val result = layout ?: return@detectTapGestures
                    val offset = result.getOffsetForPosition(position)
                    if (offset >= shown.length - tailLabel.length) expanded = !expanded
                }
            }
        } else Modifier

        Text(
            text = shown,
            style = style,
            maxLines = if (expanded) Int.MAX_VALUE else 2,
            overflow = TextOverflow.Clip,
            onTextLayout = { layout = it },
            modifier = Modifier
                .fillMaxWidth()
                .then(if (lightBackdrop) Modifier else Modifier.overlayEffect())
                .then(tailTap)
        )
    }
}

/**
 * 把简介压到两行 + 末尾「…更多」。
 *
 * 用 [TextMeasurer] 对候选串实测（而不是按字数估算），二分找最长可容纳的前缀；
 * 只有全文本身就超过两行时才加「更多」。
 */
private fun collapseIntro(
    text: String,
    measurer: TextMeasurer,
    style: TextStyle,
    widthPx: Int,
    moreColor: Color,
    moreLabel: String
): CollapsedIntro {
    val constraints = Constraints(maxWidth = widthPx)
    fun fits(candidate: String): Boolean = !measurer.measure(
        text = AnnotatedString(candidate),
        style = style,
        maxLines = 2,
        overflow = TextOverflow.Clip,
        constraints = constraints
    ).hasVisualOverflow

    if (fits(text)) return CollapsedIntro(AnnotatedString(text), false)

    var low = 0
    var high = text.length
    while (low < high) {
        val mid = (low + high + 1) / 2
        if (fits(text.take(mid).trimEnd() + "…" + moreLabel)) low = mid else high = mid - 1
    }
    val built = buildAnnotatedString {
        append(text.take(low).trimEnd())
        append("…")
        withStyle(SpanStyle(color = moreColor)) { append(moreLabel) }
    }
    return CollapsedIntro(built, true)
}
