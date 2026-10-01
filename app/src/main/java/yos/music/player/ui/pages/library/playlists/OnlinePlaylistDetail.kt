package yos.music.player.ui.pages.library.playlists

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import yos.music.player.ui.navigation.PlaylistSelection
import com.cormor.overscroll.core.overScrollVertical
import com.cormor.overscroll.core.rememberOverscrollFlingBehavior
import com.google.accompanist.insets.navigationBarsHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.repositories.KugouPlaylistTrack
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.pages.library.DetailPageHeader
import yos.music.player.ui.pages.library.DetailSongRowWide
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.pages.library.formatDurationMs
import yos.music.player.ui.pages.library.albums.NormalButton
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.enterCoverCorner
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBar
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.rememberAlpha
import yos.music.player.ui.widgets.basic.rememberShowSmallTitle
import yos.music.player.ui.widgets.basic.rememberTitleBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import yos.music.player.ui.widgets.effects.ShadowType

/**
 * 在线歌单详情页（正式 UI，视觉已归一）。
 *
 * 数据链：KugouRepository.getPlaylistSongs(listid) → /playlist/track/all/new（Rust 现有路由）。
 * 播放链（整列表）：全部歌曲映射为队列用 YosMediaItem（uri 为占位符，真实 URL 由播放器
 *        惰性解析）→ 现有 MediaController.prepare(clicked, 完整列表) → Media3 整列表队列。
 * 页面结构（参考 Apple Music 歌单详情布局）：左上大封面 + 封面右侧歌单名/曲目数 +
 * 下方随机/播放 NormalButton + MusicList 统一歌曲 Item + 浮动返回按钮。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OnlinePlaylistDetail(
    navController: NavController,
    selection: PlaylistSelection? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val routeSelection = selection
    val source = routeSelection?.source
    val playlistId = routeSelection?.id
    val playlistName = routeSelection?.name.orEmpty()
    val coverUrl = routeSelection?.cover.orEmpty()
    val songCount = routeSelection?.songCount
    val playlistIntro = routeSelection?.intro
    val hasSelection = routeSelection != null

    if (!hasSelection) {
        // 边界：无选中歌单（如进程重建后 holder 丢失）
        Title(
            title = stringResource(id = R.string.page_online_playlists_title),
            onBack = { navController.popBackStack() }
        ) {
            item("NoPlaylist") {
                // 对齐原版列表空态规范（NormalMusic/LocalAlbums：18sp / α0.6）
                Text(
                    text = stringResource(id = R.string.online_playlist_detail_missing),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
        }
        return
    }

    val tracks = remember("OnlinePlaylistDetail_tracks") {
        mutableStateOf<List<KugouPlaylistTrack>>(emptyList())
    }
    // 与 tracks 同步的队列映射（占位符 URI，入队零网络请求）
    val queue = remember("OnlinePlaylistDetail_queue") {
        mutableStateOf<List<YosMediaItem>>(emptyList())
    }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("OnlinePlaylistDetail_status") { mutableStateOf("loading") }
    // 分页加载进度文案（大歌单按页拉取时显示 已加载 X / Y）
    val loadProgress = remember("OnlinePlaylistDetail_loadProgress") { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    val loadingBaseText = stringResource(id = R.string.online_playlist_loading_songs)
    val progressFormat = stringResource(id = R.string.online_playlist_loaded_progress)

    fun loadTracks() {
        status.value = "loading"
        loadProgress.value = ""
        scope.launch {
            val onProgress: (Int, Int) -> Unit = { loaded, total ->
                // 回调在 IO 线程，切回主线程更新状态；仅在未拉完时显示进度
                scope.launch {
                    loadProgress.value = if (total > 0 && loaded < total) {
                        progressFormat.format(loaded, total)
                    } else {
                        ""
                    }
                }
            }
            val result: Result<List<KugouPlaylistTrack>> = when (source) {
                PlaylistSelection.Source.User ->
                    KugouRepository.getPlaylistSongs(playlistId.orEmpty(), onProgress)
                PlaylistSelection.Source.Recommend ->
                    KugouRepository.getPlaylistSongsByGcid(playlistId.orEmpty(), onProgress)
                null -> Result.failure(IllegalStateException("Missing playlist selection"))
            }
            result
                .onSuccess { list ->
                    tracks.value = list
                    queue.value = list.map { KugouRepository.toQueueMediaItem(it) }
                    status.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    tracks.value = emptyList()
                    queue.value = emptyList()
                    status.value = "error:${e.message}"
                }
        }
    }

    // 整列表播放：全部歌曲进 Media3 队列，从 index 处开始；URL 由播放器惰性解析
    fun playAt(index: Int) {
        val list = queue.value
        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(list[index], list)
        }
    }

    LaunchedEffect(source, playlistId) {
        loadTracks()
    }

    // 宽屏（≥600dp）歌曲行启用三列布局：歌手列起于屏幕正中央、时长贴右（对齐 Apple Music 平板横屏）
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600

    Box(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val state = rememberLazyListState()
        val titleBackdrop = rememberTitleBackdrop()
        val showSmallTitle = rememberShowSmallTitle(rememberAlpha(state), state)

        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(titleBackdrop)
                .overScrollVertical(),
            flingBehavior = rememberOverscrollFlingBehavior { state },
            contentPadding = PaddingValues(bottom = 18.dp, top = 54.dp)
        ) {
            item("PlaylistHeader") {
                if (isWideScreen) {
                    // 平板（≥600dp）：Apple Music 式头部（封面左上/信息右侧/按钮沉底/简介）
                        DetailPageHeader(
                            title = playlistName,
                            coverData = { coverUrl.takeIf { it.isNotEmpty() } },
                            coverSharedElementKey = sharedPlaylistCoverKey(source, playlistId),
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                        tag = "PLAYLIST",
                        countText = "${if (tracks.value.isNotEmpty()) tracks.value.size else songCount} " +
                            stringResource(id = R.string.online_playlists_song_unit),
                        intro = playlistIntro,
                        onPlayAll = { playAt(0) },
                        onShuffle = {
                            val list = queue.value
                            if (list.isNotEmpty()) {
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                playAt(list.indices.random())
                            }
                        }
                    )
                } else {
                    // 手机（<600dp）：保持改版前的旧版居中布局
                        PlaylistHeaderCompact(
                            playlistName = playlistName,
                            displayCount = if (tracks.value.isNotEmpty()) tracks.value.size else songCount,
                            coverUrl = coverUrl,
                            coverSharedElementKey = sharedPlaylistCoverKey(source, playlistId),
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                        onPlayAll = { playAt(0) },
                        onShuffle = {
                            val list = queue.value
                            if (list.isNotEmpty()) {
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                playAt(list.indices.random())
                            }
                        }
                    )
                }
            }

            item("Status") {
                OnlineStatusItem(
                    status = status.value,
                    loadingText = loadProgress.value.ifEmpty { loadingBaseText },
                    emptyText = stringResource(id = R.string.online_playlist_songs_empty)
                )
            }

            item {
                OnlineDetailDivider()
            }

            OnlineDetailSongs(
                tracks = tracks.value,
                isWide = isWideScreen,
                onPlayAt = { playAt(it) }
            )

            item {
                OnlineDetailDivider()
            }

            item("navbar") {
                Spacer(modifier = Modifier.navigationBarsHeight(134.dp))
            }
        }

        // 顶栏：滚动离屏浮现小标题 + 模糊，几何与参考页 TitleBar 一致
        TitleBar(
            title = playlistName,
            onBack = { navController.popBackStack() },
            showSmallTitle = showSmallTitle,
            backdrop = titleBackdrop
        )
    }
}

/**
 * 详情页横向分割线（对齐 AlbumInfo 的 AlbumDivider 规范）。
 */
internal fun sharedPlaylistCoverKey(
    source: PlaylistSelection.Source?,
    id: String?
): String? = if (source != null && !id.isNullOrEmpty()) {
    "playlist/${source.name}/$id"
} else null

@Composable
private fun OnlineDetailDivider(modifier: Modifier = Modifier) =
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp)
            .alpha(0.2f)
            .height(0.5.dp)
            .background(Color.Black withNight Color.White)
    )

/**
 * 手机（<600dp）旧版居中头部：与 2026-09-06 改版前完全一致
 * （居中大封面 + 居中歌单名/曲目数/PLAYLIST + 全宽 播放/随机 按钮，无简介）。
 * 平板宽屏使用共享 DetailPageHeader（Apple Music 式，见 DetailPageParts.kt）。
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun PlaylistHeaderCompact(
    playlistName: String,
    displayCount: Int?,
    coverUrl: String,
    coverSharedElementKey: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.5.dp)
            .padding(horizontal = 18.dp)
            .statusBarsPadding(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 大封面：与 AlbumInfo 同款 ShadowImage 规范，URL 为空时组件内部显示占位图
        val coverModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 54.5.dp)
            .aspectRatio(1f)
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
                dataLambda = { coverUrl.takeIf { it.isNotEmpty() } },
                contentDescription = null,
                // 转场中圆角从来源封面值渐变到 7dp，静止恒为 7dp
                cornerRadius = enterCoverCorner(animatedVisibilityScope, 7.dp),
                imageQuality = ImageQuality.RAW,
                shadowType = ShadowType.Medium
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = playlistName,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(2.dp))

        // 已加载出歌曲时优先显示实际 tracks.size；未加载时用 source songCount 兜底
        if (displayCount != null) {
            Text(
                text = "$displayCount " +
                    stringResource(id = R.string.online_playlists_song_unit),
                fontSize = 17.5.sp,
                textAlign = TextAlign.Center,
                lineHeight = 23.5.sp,
                color = MaterialTheme.colorScheme.primary
            )
        }

        Text(
            text = "PLAYLIST",
            fontSize = 11.5.sp,
            modifier = Modifier
                .alpha(0.4f)
                .padding(top = 2.dp)
        )

        YosWrapper {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp, bottom = 15.dp)
            ) {
                // 播放：全部歌曲进队列，从第一首开始（URL 惰性解析，入队零等待）
                NormalButton(
                    icon = painterResource(id = R.drawable.button_icon_play),
                    label = stringResource(id = R.string.normal_button_play),
                    modifier = Modifier.weight(1f)
                ) {
                    onPlayAll()
                }
                Spacer(modifier = Modifier.width(15.dp))
                // 随机：全部歌曲进队列 + Media3 原生 shuffle，从随机位置开始
                //（对齐 NormalMusic/AlbumInfo 本地模式）
                NormalButton(
                    icon = painterResource(id = R.drawable.button_icon_shuffle),
                    label = stringResource(id = R.string.normal_button_shuffle),
                    modifier = Modifier.weight(1f)
                ) {
                    onShuffle()
                }
            }
        }
    }
}

/**
 * 歌曲列表：统一歌曲 Item + 歌曲间分割线（对齐本地列表 MusicList 视觉规范）。
 * 宽屏（≥600dp）换用三列行：歌名（左半）+ 歌手（起于屏幕中央）+ 时长（贴右）。
 */
private fun LazyListScope.OnlineDetailSongs(
    tracks: List<KugouPlaylistTrack>,
    isWide: Boolean,
    onPlayAt: (Int) -> Unit
) {
    itemsIndexed(
        tracks,
        key = { _, track -> track.hash }
    ) { index, track ->
        key(track.hash) {
            // 统一歌曲 Item：与本地列表同款 MusicList 视觉规范；
            // 点击 → 全部歌曲进队列，从被点击歌曲开始播放
            if (isWide) {
                DetailSongRowWide(music = KugouRepository.toDisplayMediaItem(track)) {
                    onPlayAt(index)
                }
            } else {
                MusicList(KugouRepository.toDisplayMediaItem(track)) {
                    onPlayAt(index)
                }
            }
        }

        key("divider_$index") {
            if (index < tracks.size - 1) {
                Spacer(
                    modifier = Modifier
                        .fillMaxWidth()
                        // 右侧与列表首末分割线一致留 18dp，任何屏幕都不顶到边缘
                        .padding(start = 88.dp, end = 16.dp)
                        .alpha(0.15f)
                        .height(0.5.dp)
                        .background(Color.Black withNight Color.White)
                )
            }
        }
    }
}
