package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.ImageLoader
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import io.github.alexzhirkevich.cupertino.icons.CupertinoIcons
import io.github.alexzhirkevich.cupertino.icons.outlined.PersonCropCircle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultAlbum
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.objects.DiscoveryObject
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.objects.RankObject
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRank
import yos.music.player.data.repositories.KugouRecommendPlaylist
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.UI
import yos.music.player.ui.pages.RecommendCardItem
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.preloadRawCover
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.calculateAdaptiveImageLayout

// Content rails retain the natural decay target; Pager still clamps it to the dataset bounds.
internal val DiscoveryRailSnapDistance = PagerSnapDistance.atMost(Int.MAX_VALUE)

/**
 * 主页（原 Discovery 升级）：单一内容首页，不再维护重复页面。
 *
 * 内容流节奏（从上到下）：
 *   最近播放    —— 单张全宽大卡（复用 RecommendCardItem 视觉语言），无数据则隐藏
 *   精选歌单    —— 横向自适应歌单卡（最小 150dp）
 *   新歌精选    —— 横向分页多列歌曲（每列 4 首，复用 MusicList）
 *   本周新发行  —— 横向自适应专辑卡（最小 150dp）
 *   排行榜      —— 横向自适应榜单卡（最小 150dp）
 *
 * 各 Section 内部 UI / 卡片尺寸 / 横向滑动逻辑 / Header 箭头 / 间距均保持原 Discovery 不变。
 * 数据走现有 DiscoveryObject 状态，切 Tab 返回不重新请求。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun Discovery(
    navController: NavController,
    onOpenSettings: (() -> Unit)? = null,
    onOpenOnlinePlaylist: ((PlaylistSelection) -> Unit)? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val openSettings = onOpenSettings ?: { navController.toUI(UI.Settings.Main) }
    val openOnlinePlaylist = onOpenOnlinePlaylist ?: { selection: PlaylistSelection -> navController.navigate(selection.toRoute()) }
    val rankList = DiscoveryObject.rankList
    val status = DiscoveryObject.status
    val newSongs = DiscoveryObject.newSongs
    val newAlbums = DiscoveryObject.newAlbums
    val newSongsStatus = DiscoveryObject.newSongsStatus
    val newAlbumsStatus = DiscoveryObject.newAlbumsStatus
    val recommendPlaylists = DiscoveryObject.recommendPlaylists
    val recommendPlaylistsStatus = DiscoveryObject.recommendPlaylistsStatus
    val scope = rememberCoroutineScope()

    // 最近播放：收听历史（最近优先，后台切歌实时更新），首页最多展示 10 首
    val recentlyPlayed = MusicLibrary.recentlyPlayed.take(10)

    fun load() {
        if (status.value == "loading") return
        status.value = "loading"
        scope.launch {
            KugouRepository.getRankList()
                .onSuccess { list ->
                    rankList.value = list
                    status.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    status.value = "error:${e.message}"
                }
        }
    }

    fun loadNewSongs() {
        if (newSongsStatus.value == "loading") return
        newSongsStatus.value = "loading"
        scope.launch {
            KugouRepository.getTopSongs()
                .onSuccess { list ->
                    newSongs.value = list
                    newSongsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    newSongsStatus.value = "error:${e.message}"
                }
        }
    }

    fun loadNewAlbums() {
        if (newAlbumsStatus.value == "loading") return
        newAlbumsStatus.value = "loading"
        scope.launch {
            KugouRepository.getTopAlbums()
                .onSuccess { list ->
                    newAlbums.value = list
                    newAlbumsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    newAlbumsStatus.value = "error:${e.message}"
                }
        }
    }

    fun loadRecommendPlaylists() {
        if (recommendPlaylistsStatus.value == "loading") return
        recommendPlaylistsStatus.value = "loading"
        scope.launch {
            KugouRepository.getRecommendPlaylists()
                .onSuccess { list ->
                    recommendPlaylists.value = list
                    recommendPlaylistsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    recommendPlaylistsStatus.value = "error:${e.message}"
                }
        }
    }

    // 新歌点击：整列表进 Media3 队列，从被点击的 index 开始（URL 惰性解析）
    fun playNewSongsAt(index: Int) {
        val songs = newSongs.value
        if (index !in songs.indices) return
        val queue = songs.map { KugouRepository.toQueueMediaItem(it) }
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(queue[index], queue)
        }
    }

    // 首次进入加载；切 Tab 返回时 DiscoveryObject 状态仍在，不重复请求
    LaunchedEffect(Unit) {
        if (status.value == "idle") load()
        // 上次会话残留 loading 态自愈（切页时协程已取消）
        if (status.value == "loading") {
            status.value = if (rankList.value.isEmpty()) "idle" else "ok"
            if (status.value == "idle") load()
        }
        if (newSongsStatus.value == "idle") loadNewSongs()
        if (newAlbumsStatus.value == "idle") loadNewAlbums()
        if (recommendPlaylistsStatus.value == "idle") loadRecommendPlaylists()
    }

    Box(Modifier.fillMaxSize()) {
        Title(
            title = stringResource(id = R.string.page_home_title),
            topRightIcon = Icons.Filled.MoreVert,
            onTopRightIcon = {
                openSettings()
            },
            extraTopPadding = 57.dp
        ) {
        item("TopDivider") {
            // 顶部标题栏下方细分割线（对齐 Apple Music 标题区与内容的细分隔）
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 16.dp)
                    .alpha(0.2f)
                    .height(0.5.dp)
                    .background(Color.Black withNight Color.White)
            )
        }

        // ① 最近播放横向大卡列表（取色模糊背景，无数据则不渲染，无空状态）
        item("RecentlyPlayed") {
            val songs = recentlyPlayed
            if (songs.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp)
                ) {
                    // Header：标题 + 箭头（与 DiscoverySectionHeader 同款视觉），点击进入详情页
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .clickable { navController.toUI(UI.RecentPlayedDetail) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(id = R.string.home_continue_listening),
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            lineHeight = 20.sp
                        )
                        Spacer(Modifier.width(4.dp))
                        Icon(
                            painter = painterResource(id = R.drawable.ic_chevron_right),
                            contentDescription = null,
                            modifier = Modifier
                                .size(24.dp)
                                .alpha(0.35f),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    Spacer(Modifier.height(10.dp))

                    RecentListeningRail(
                        songs = songs,
                        onSongClick = { music, queue ->
                            scope.launch(Dispatchers.IO) {
                                MediaController.prepare(music, queue)
                            }
                        }
                    )
                }
            }
        }

        // ② 精选歌单
        item("RecommendPlaylistSection") {
            if (recommendPlaylists.value.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    DiscoverySectionHeader(
                        title = stringResource(id = R.string.discovery_recommend_playlists_title),
                        onClick = { navController.toUI(UI.RecommendPlaylistsDetail) }
                    )

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val railLayout = calculateAdaptiveImageLayout(
                            containerWidth = maxWidth,
                            startPadding = 20.dp,
                            endPadding = 20.dp
                        )
                        val pagerState = rememberPagerState(pageCount = { recommendPlaylists.value.size })

                        HorizontalPager(
                            state = pagerState,
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                pagerSnapDistance = DiscoveryRailSnapDistance
                            ),
                            pageSize = PageSize.Fixed(railLayout.pageSize),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                            key = { recommendPlaylists.value[it].globalCollectionId },
                            beyondViewportPageCount = 1
                        ) { page ->
                            val playlist = recommendPlaylists.value[page]
                            RecommendPlaylistCard(
                                playlist,
                                modifier = Modifier.width(railLayout.itemSize),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            ) {
                                openOnlinePlaylist(
                                    PlaylistSelection(
                                        source = PlaylistSelection.Source.Recommend,
                                        id = playlist.globalCollectionId,
                                        name = playlist.name,
                                        cover = playlist.artworkUrl,
                                        songCount = DiscoveryObject.recommendPlaylistSongCounts[playlist.globalCollectionId]
                                            ?: playlist.songCount
                                            ?: 0,
                                        intro = playlist.intro
                                    )
                                )
                            }
                        }
                    }
                }
            } else {
                // 未加载到数据时显示状态（loading/empty/error；idle/ok 时不渲染，不影响其它 Section）
                OnlineStatusItem(
                    status = recommendPlaylistsStatus.value,
                    loadingText = stringResource(id = R.string.online_playlists_loading),
                    emptyText = stringResource(id = R.string.discovery_recommend_playlists_empty)
                )
            }
        }

        item("NewSongsSection") {
            if (newSongs.value.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    // Header 复用 DiscoverySectionHeader（20sp Bold + chevron「>」），
                    // 整行点击进入完整新歌列表页（不显示「查看全部」文字）。
                    DiscoverySectionHeader(
                        title = stringResource(id = R.string.discovery_new_songs_title),
                        onClick = { navController.toUI(UI.NewSongsDetail) }
                    )

                    // 横向分页多列歌曲（HorizontalPager 松手吸附，与排行榜/新专辑/精选歌单同款手感）：
                    //   每列固定 4 首，使用全部 newSongs.value（不再 .take(20)）；
                    //   MusicList horizontalPadding=0 → 封面左边缘 = startInset = 20dp = Header；
                    //   pageWidth = screenWidth - 59（第二列封面左边缘 = startInset + pageWidth = screenWidth - 39，
                    //   封面右边缘 = screenWidth + 13 → 屏内可见 39dp = 3/4 封面）；
                    //   startInset(20) + pageWidth + endInset(39) = screenWidth → 吸附等式成立，
                    //   每个 Page snap 后左边缘落在同一 X 坐标（含末页）。
                    //   verticalAlignment = Top：末列不足 4 首时贴左上，不垂直居中。
                    val songs = newSongs.value
                    val displaySongs = remember(songs) {
                        songs.map { KugouRepository.toDisplayMediaItem(it) }
                    }
                    val songsPerColumn = 4
                    val columnCount = (songs.size + songsPerColumn - 1) / songsPerColumn
                    val pagerState = rememberPagerState(pageCount = { columnCount })

                    val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                    val startInset = 20.dp
                    val endInset = 39.dp
                    // 横屏 / 分屏 / 小窗时 screenWidth 可能 < 59dp，coerceAtLeast 防止 pageWidth 为负
                    val pageWidth = (screenWidth - startInset - endInset).coerceAtLeast(100.dp)

                    HorizontalPager(
                        state = pagerState,
                        flingBehavior = PagerDefaults.flingBehavior(
                            state = pagerState,
                            pagerSnapDistance = DiscoveryRailSnapDistance
                        ),
                        pageSize = PageSize.Fixed(pageWidth),
                        contentPadding = PaddingValues(start = startInset, end = endInset),
                        // foundation 1.7.0-beta07 默认 CenterVertically → 末页不足 4 首会垂直居中；
                        // 显式 Top 使所有页面统一顶部对齐。
                        verticalAlignment = Alignment.Top,
                        key = { "${displaySongs[it * songsPerColumn].mediaId ?: "page"}-$it" },
                        beyondViewportPageCount = 1
                    ) { page ->
                        Column(Modifier.fillMaxWidth()) {
                            repeat(songsPerColumn) { row ->
                                val globalIndex = page * songsPerColumn + row
                                if (globalIndex >= songs.size) return@repeat
                                MusicList(
                                    displaySongs[globalIndex],
                                    horizontalPadding = 0.dp
                                ) {
                                    playNewSongsAt(globalIndex)
                                }
                                if (row < songsPerColumn - 1 && globalIndex + 1 < songs.size) {
                                    OnlineListItemDivider(startPadding = 66.dp, endPadding = 16.dp)
                                }
                            }
                        }
                    }
                }
            }
        }

        item("NewAlbumsSection") {
            if (newAlbums.value.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    DiscoverySectionHeader(
                        title = stringResource(id = R.string.discovery_new_albums_title),
                        onClick = { navController.toUI(UI.NewAlbumsDetail) }
                    )

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val railLayout = calculateAdaptiveImageLayout(
                            containerWidth = maxWidth,
                            startPadding = 20.dp,
                            endPadding = 20.dp
                        )
                        val pagerState = rememberPagerState(pageCount = { newAlbums.value.size })

                        HorizontalPager(
                            state = pagerState,
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                pagerSnapDistance = DiscoveryRailSnapDistance
                            ),
                            pageSize = PageSize.Fixed(railLayout.pageSize),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                            key = { newAlbums.value[it].albumId },
                            beyondViewportPageCount = 1
                        ) { page ->
                            val album = newAlbums.value[page]
                            NewAlbumCard(
                                album,
                                modifier = Modifier.width(railLayout.itemSize),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            ) {
                                OnlineAlbumObject.setSelected(album)
                                navController.toUI(UI.OnlineAlbumDetail)
                            }
                        }
                    }
                }
            }
        }

        item("RankSection") {
            if (rankList.value.isNotEmpty()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 24.dp)
                ) {
                    DiscoverySectionHeader(
                        title = stringResource(id = R.string.discovery_rank_title),
                        onClick = { navController.toUI(UI.RankListDetail) }
                    )

                    BoxWithConstraints(Modifier.fillMaxWidth()) {
                        val railLayout = calculateAdaptiveImageLayout(
                            containerWidth = maxWidth,
                            startPadding = 20.dp,
                            endPadding = 20.dp
                        )
                        val pagerState = rememberPagerState(pageCount = { rankList.value.size })

                        HorizontalPager(
                            state = pagerState,
                            flingBehavior = PagerDefaults.flingBehavior(
                                state = pagerState,
                                pagerSnapDistance = DiscoveryRailSnapDistance
                            ),
                            pageSize = PageSize.Fixed(railLayout.pageSize),
                            contentPadding = PaddingValues(start = 20.dp, end = 20.dp),
                            key = { rankList.value[it].rankId },
                            beyondViewportPageCount = 1
                        ) { page ->
                            val rank = rankList.value[page]
                            RankCardItem(
                                rank,
                                modifier = Modifier.width(railLayout.itemSize),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            ) {
                                RankObject.setSelected(rank)
                                navController.toUI(UI.RankDetail)
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
 * 榜单方卡（LocalAlbums AlbumItems 同构）：150dp 宽方卡，
 * ShadowImage(7dp 圆角) + 14sp 榜名 + 13sp 曲目数副标题。
 * 「排行榜大全」Grid 页复用时传 [modifier] = fillMaxWidth（默认 150dp 横滑卡宽）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
internal fun RankCardItem(
    rank: KugouRank,
    modifier: Modifier = Modifier.width(150.dp),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val prefetchScope = rememberCoroutineScope()
    Column(
        modifier
            .clickable {
                prefetchScope.launch {
                    preloadRawCover(context, rank.coverUrl)
                    onClick()
                }
            }
    ) {
        // 固定正方形外壳承载 shared element；ShadowImage 只在外壳内部绘制
        val coverModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(
                                key = "rank/${rank.rankId}"
                            ),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            )
        Box(coverModifier) {
            ShadowImage(
                dataLambda = { rank.coverUrl.takeIf { it.isNotEmpty() } },
                contentDescription = rank.name,
                modifier = Modifier.fillMaxSize(),
                shadowAlpha = 0f,
                cornerRadius = 7.dp,
                imageQuality = ImageQuality.HIGH
            )
        }
        Spacer(Modifier.height(5.dp))
        Text(
            text = rank.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.9f)
        )
        if (rank.songCount > 0) {
            Text(
                text = "${rank.songCount} " + stringResource(id = R.string.online_playlists_song_unit),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.6f)
            )
        }
    }
}

/**
 * 统一 Section Header：20sp Bold 标题 + 右侧简洁 chevron「>」。
 * chevron 图标盒 24dp（圆头描边画法，转角/端头圆角，可见尺寸约 16dp，略小于 20sp 标题），
 * 距标题 4dp 空隙（图标内左侧另留约 1dp）。
 * 传入 [onClick] 时整行（标题 + 箭头）可点击，进入该栏目的「查看全部」页，
 * 与「最近播放」Header 的整行点击行为一致。
 */
@Composable
private fun DiscoverySectionHeader(title: String, onClick: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .then(
                    if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = title,
                fontWeight = FontWeight.Bold,
                fontSize = 20.sp,
                lineHeight = 20.sp
            )
            Spacer(Modifier.width(4.dp))
            Icon(
                painter = painterResource(id = R.drawable.ic_chevron_right),
                contentDescription = null,
                modifier = Modifier
                    .size(24.dp)
                    .alpha(0.35f),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.height(10.dp))
    }
}

/**
 * 新专辑方卡（与 RankCardItem 同构）：150dp 宽方卡，
 * ShadowImage(7dp 圆角) + 14sp 专辑名 + 13sp 歌手副标题。
 * 「本周新发行大全」Grid 页复用时传 [modifier] = fillMaxWidth。
 */
@Composable
internal fun NewAlbumCard(
    album: KugouNewAlbum,
    modifier: Modifier = Modifier.width(150.dp),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val prefetchScope = rememberCoroutineScope()
    val sharedKey = "album/online/${album.albumId}"
    Column(
        modifier.clickable {
            prefetchScope.launch {
                preloadRawCover(context, album.coverUrl)
                onClick()
            }
        }
    ) {
        val imageModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(sharedKey),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            )
        Box(imageModifier) {
            ShadowImage(
                dataLambda = { album.coverUrl.takeIf { it.isNotEmpty() } },
                contentDescription = album.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 5.dp),
                shadowAlpha = 0f,
                cornerRadius = 7.dp,
                imageQuality = ImageQuality.HIGH
            )
        }
        Text(
            text = album.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.9f)
        )
        Text(
            text = album.singerName,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.6f)
        )
    }
}

/**
 * 精选歌单方卡（与 RankCardItem 同构）：150dp 宽方卡，
 * ShadowImage(7dp 圆角) + 14sp 歌单名 + 13sp 歌曲数副标题。
 * 「精选歌单大全」Grid 页复用时传 [modifier] = fillMaxWidth。
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
internal fun RecommendPlaylistCard(
    playlist: KugouRecommendPlaylist,
    modifier: Modifier = Modifier.width(150.dp),
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    onClick: () -> Unit
) {
    val id = playlist.globalCollectionId
    val sourceCount = playlist.songCount
    val cachedCount = DiscoveryObject.recommendPlaylistSongCounts[id]
    val count = cachedCount ?: sourceCount

    LaunchedEffect(id, sourceCount) {
        if (sourceCount != null) {
            if (!DiscoveryObject.recommendPlaylistSongCounts.containsKey(id)) {
                DiscoveryObject.recommendPlaylistSongCounts[id] = sourceCount
            }
            return@LaunchedEffect
        }
        if (DiscoveryObject.recommendPlaylistSongCounts.containsKey(id) ||
            DiscoveryObject.recommendPlaylistSongCountRequests.containsKey(id)
        ) {
            return@LaunchedEffect
        }
        DiscoveryObject.recommendPlaylistSongCountRequests[id] = true
        try {
            KugouRepository.getRecommendPlaylistSongCount(id)
                .getOrNull()
                ?.let { resolved -> DiscoveryObject.recommendPlaylistSongCounts[id] = resolved }
        } finally {
            DiscoveryObject.recommendPlaylistSongCountRequests.remove(id)
        }
    }

    val context = LocalContext.current
    val prefetchScope = rememberCoroutineScope()
    Column(
        modifier
            .clickable {
                prefetchScope.launch {
                    preloadRawCover(context, playlist.artworkUrl)
                    onClick()
                }
            }
    ) {
        val imageModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                    with(sharedTransitionScope) {
                        Modifier.sharedElement(
                            sharedContentState = rememberSharedContentState(
                                key = "playlist/${PlaylistSelection.Source.Recommend.name}/${playlist.globalCollectionId}"
                            ),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            )
        Box(imageModifier) {
            ShadowImage(
                dataLambda = { playlist.artworkUrl.takeIf { it.isNotEmpty() } },
                contentDescription = playlist.name,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = 5.dp),
                shadowAlpha = 0f,
                cornerRadius = 7.dp,
                imageQuality = ImageQuality.HIGH
            )
        }
        Text(
            text = playlist.name,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.alpha(0.9f)
        )
        if (count != null) {
            Text(
                text = "$count " + stringResource(id = R.string.online_playlists_song_unit),
                fontSize = 13.sp,
                lineHeight = 17.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(0.6f)
            )
        }
    }
}
