package yos.music.player.ui.pages.library.artists

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.pages.discovery.NewAlbumCard
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.preloadRawCover
import yos.music.player.ui.widgets.effects.ShadowType
import yos.music.player.ui.widgets.effects.dropShadow
import yos.music.player.ui.widgets.effects.overlayEffect
import java.text.SimpleDateFormat
import java.util.Locale

internal data class ArtistPagingSnapshot(
    val current: Int,
    val count: Int,
    val page: Int,
    val loading: Boolean,
    val error: String?,
    val endReached: Boolean
)

@Composable
internal fun ArtistSectionHeader(title: String, onClick: (() -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 24.sp)
        if (onClick != null) {
            Spacer(Modifier.width(4.dp))
            Icon(
                painterResource(R.drawable.ic_chevron_right), contentDescription = null,
                modifier = Modifier.size(24.dp).alpha(0.35f),
                tint = LocalContentColor.current
            )
        }
    }
}

@Composable
internal fun ArtistSectionError(error: String, busy: Boolean = false, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 10.dp)) {
        Text(error, fontSize = 15.sp, modifier = Modifier.alpha(0.65f))
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.artist_detail_retry),
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(enabled = !busy, role = Role.Button, onClick = onRetry)
                .padding(vertical = 6.dp).alpha(if (busy) 0.45f else 1f)
        )
    }
}

@Composable
private fun ArtistEmptyStatus(text: String) {
    Text(text, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 22.dp, vertical = 12.dp).alpha(0.6f))
}

/** 列表页分页错误行（艺人热门/全部歌曲与专辑大全页共用）：整块文案 + 可点重试。 */
@Composable
internal fun ArtistSongsError(
    message: String,
    onRetry: () -> Unit,
    compact: Boolean = false
) {
    Column(Modifier.padding(horizontal = 22.dp, vertical = if (compact) 8.dp else 10.dp)) {
        Text(
            text = message,
            fontSize = if (compact) 14.sp else 18.sp,
            modifier = Modifier.alpha(0.6f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.load_more_failed),
            fontSize = 16.sp,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable(onClick = onRetry)
        )
    }
}

/** 列表页翻页页脚：居中小号“加载中”文案。 */
@Composable
internal fun ArtistListFooterLoading() {
    Text(
        text = stringResource(R.string.online_playlists_loading),
        fontSize = 13.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp)
            .wrapContentWidth(Alignment.CenterHorizontally)
            .alpha(0.5f)
    )
}

@Composable
internal fun ArtistSongSection(
    songs: List<KugouNewSong>,
    loading: Boolean,
    error: String?,
    pagerState: PagerState,
    onRetry: () -> Unit,
    onPlayAt: (Int) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        when {
            songs.isNotEmpty() -> SongsPagerSection(songs, pagerState, onPlayAt)
            loading -> ArtistSongsSkeleton()
            error == null -> ArtistEmptyStatus(stringResource(R.string.artist_detail_empty))
        }
        // Appended-page failures remain visible without hiding already loaded songs.
        if (error != null) ArtistSectionError(error, loading, onRetry)
        if (loading && songs.isNotEmpty()) {
            Text(
                stringResource(R.string.online_playlists_loading), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.CenterHorizontally).padding(vertical = 12.dp).alpha(0.5f)
            )
        }
    }
}

@Composable
internal fun SongsPagerSection(
    songs: List<KugouNewSong>,
    pagerState: PagerState,
    onPlayAt: (Int) -> Unit
) {
    val displaySongs = remember(songs) { songs.map { KugouRepository.toDisplayMediaItem(it) } }
    val pageKeys = remember(songs) {
        songs.chunked(4).mapIndexed { index, column -> "artist-page-$index-${column.first().hash}" }
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val startInset = 20.dp
        val endInset = 39.dp
        val pageWidth = (maxWidth - startInset - endInset).coerceAtLeast(1.dp)
        HorizontalPager(
            state = pagerState,
            flingBehavior = PagerDefaults.flingBehavior(
                state = pagerState,
                pagerSnapDistance = PagerSnapDistance.atMost(Int.MAX_VALUE)
            ),
            pageSize = PageSize.Fixed(pageWidth),
            contentPadding = PaddingValues(start = startInset, end = endInset),
            verticalAlignment = Alignment.Top,
            key = { page -> pageKeys.getOrElse(page) { "artist-page-$page-empty" } },
            beyondViewportPageCount = 1
        ) { page ->
            Column(Modifier.fillMaxWidth()) {
                repeat(4) { row ->
                    val index = page * 4 + row
                    val item = displaySongs.getOrNull(index) ?: return@repeat
                    MusicList(item, horizontalPadding = 0.dp) { onPlayAt(index) }
                    if (row < 3 && index + 1 < songs.size) {
                        OnlineListItemDivider(startPadding = 66.dp, endPadding = 16.dp)
                    }
                }
            }
        }
    }
}

/** Shared, static placeholder: four 64dp rows with 52dp artwork. */
@Composable
internal fun ArtistSongsSkeleton() {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
        repeat(4) {
            Row(Modifier.fillMaxWidth().height(64.dp), verticalAlignment = Alignment.CenterVertically) {
                ArtistSkeletonBlock(Modifier.size(52.dp))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    ArtistSkeletonBlock(Modifier.fillMaxWidth(0.65f).height(13.dp))
                    ArtistSkeletonBlock(Modifier.fillMaxWidth(0.42f).height(11.dp))
                }
            }
        }
    }
}

@Composable
internal fun ArtistHeaderSkeleton() {
    Column(
        Modifier.fillMaxWidth().padding(bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ArtistSkeletonBlock(Modifier.width(150.dp).height(14.dp))
        ArtistSkeletonBlock(Modifier.width(108.dp).height(10.dp))
    }
}

@Composable
private fun ArtistSkeletonBlock(modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(LocalContentColor.current.copy(alpha = 0.09f)))
}

@Composable
internal fun ArtistAlbumsSection(
    albums: List<KugouNewAlbum>,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpen: (KugouNewAlbum) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    Column(Modifier.fillMaxWidth()) {
        when {
            albums.isNotEmpty() -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(albums, key = { index, album -> "artist-album-$index-${album.albumId}" }) { _, album ->
                    NewAlbumCard(
                        album = album,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope
                    ) { onOpen(album) }
                }
            }
            loading -> LazyRow(
                contentPadding = PaddingValues(horizontal = 20.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(3) {
                    Column(verticalArrangement = Arrangement.spacedBy(9.dp)) {
                        ArtistSkeletonBlock(Modifier.size(150.dp))
                        ArtistSkeletonBlock(Modifier.width(110.dp).height(12.dp))
                        ArtistSkeletonBlock(Modifier.width(80.dp).height(10.dp))
                    }
                }
            }
            error == null -> ArtistEmptyStatus(stringResource(R.string.artist_detail_albums_empty))
        }
        if (error != null) ArtistSectionError(error, loading, onRetry)
    }
}

/**
 * 推荐专辑卡片（艺人页头部下方，Apple Music 式）：半透明白圆角容器 + 顶部亮、底部收的
 * 白色渐变描边；左侧封面带投影，右侧自上而下为发行日期 / 专辑名 / 歌曲数。
 * 日期与歌曲数沿用专辑详情页「N 首」的发亮 primary 色；数据取艺人最新发布的专辑。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun ArtistLatestAlbumCard(
    album: KugouNewAlbum,
    songCount: Int,
    underlayColor: Color,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onOpen: (KugouNewAlbum) -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    // 同心圆角规则（用户定版）：外圆角 = 内圆角 + 内边距 —— 封面 10dp + 垂直内边距 12dp = 22dp
    val shape = YosRoundedCornerShape(22.dp)
    // 次级文字色（日期/歌曲数）：随取色明暗分流——暗卡 = 白 + Plus 加色微光
    // （播放页歌手名同款质感），亮卡 = 深色弱化；原先的 White 35% 定版在浅色
    // 取色底上会淡到近乎隐形（普通混合的白被亮底稀释，加色更会直接饱和融底）。
    // 阈值与 ArtistArtworkColors.content 的明暗判定一致（0.179）。
    val lightCard = underlayColor.luminance() > 0.179f
    val secondaryText = if (lightCard) Color(0xFF161616).copy(alpha = 0.55f)
                        else Color.White.copy(alpha = 0.45f)
    val secondaryGlow = Modifier.then(if (lightCard) Modifier else Modifier.overlayEffect())
    val context = LocalContext.current
    val prefetchScope = rememberCoroutineScope()
    val dateText = remember(album.publishTime) {
        album.publishTime?.trim()?.takeIf { it.isNotEmpty() }?.let { raw ->
            runCatching {
                val parsed = SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(raw.take(10))
                parsed?.let { java.text.DateFormat.getDateInstance(java.text.DateFormat.DEFAULT, Locale.getDefault()).format(it) }
            }.getOrNull() ?: raw
        }
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 6.dp)
            .dropShadow(shape, 0.16f, ShadowType.Small)
            .clip(shape)
            // 不透明垫底：卡片悬在纯色页面上，垫上同色后视觉与半透明完全一致，
            // 却能把 dropShadow 挡在形状之外——阴影只出现在矩形外，不再透底发黑
            .background(underlayColor)
            .background(Color.White.copy(alpha = if (isDark) 0.12f else 0.22f))
            .border(
                width = 0.5.dp,
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.42f), Color.White.copy(alpha = 0.15f))
                ),
                shape = shape
            )
            .clickable(role = Role.Button) {
                // 来源封面圆角 10dp：目标端封面转场时从这里渐变到自身 7dp
                SharedCoverStyle.lastSourceCorner = 10.dp
                // 本卡用独立来源 key：与下方专辑横排的 album/online/{id} 区分开，
                // 两处同专辑同屏时不再争抢；详情页按此 key 与本卡封面配对
                OnlineAlbumObject.setSharedCoverKey(album.albumId, "album/latest/${album.albumId}")
                prefetchScope.launch {
                    preloadRawCover(context, album.coverUrl)
                    onOpen(album)
                }
            }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面外壳承载 sharedElement：进入专辑详情时封面连续放大
        val coverModifier = Modifier
            .size(92.dp)
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState("album/latest/${album.albumId}"),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            )
        Box(coverModifier) {
            ShadowImage(
                modifier = Modifier.fillMaxSize(),
                dataLambda = { album.coverUrl.takeIf { it.isNotEmpty() } },
                contentDescription = album.name,
                cornerRadius = 10.dp,
                imageQuality = ImageQuality.HIGH,
                shadowType = ShadowType.Medium
            )
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            dateText?.let {
                Text(it, fontSize = 12.5.sp, lineHeight = 16.sp, color = secondaryText, modifier = secondaryGlow)
            }
            Text(
                album.name,
                fontSize = 17.sp,
                lineHeight = 22.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (songCount > 0) {
                Text(
                    stringResource(R.string.artist_detail_songs, songCount),
                    fontSize = 12.5.sp,
                    lineHeight = 16.sp,
                    color = secondaryText,
                    modifier = secondaryGlow
                )
            }
        }
    }
}
