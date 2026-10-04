package yos.music.player.ui.pages.library.playlists

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.repositories.KugouPlaylistTrack
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.KugouSearchSong
import yos.music.player.data.repositories.PendingPlaylistStore
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
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("OnlinePlaylistDetail_status") { mutableStateOf("loading") }
    // 分页加载进度文案（大歌单按页拉取时显示 已加载 X / Y）
    val loadProgress = remember("OnlinePlaylistDetail_loadProgress") { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    // ---- 挂起歌单（创建中/创建失败）：selection.id 是 PendingPlaylistStore 的 localId ----
    val pendingEntry = remember("OnlinePlaylistDetail_pending") {
        mutableStateOf(PendingPlaylistStore.get(playlistId.orEmpty()))
    }
    val isPendingPlaylist = source == PlaylistSelection.Source.User && pendingEntry.value != null
    // 展示列表 = 服务端歌曲 + 挂起条目未补发的待加歌（绑定真实 listid 前详情页也有内容、可播）
    val displayTracks: List<KugouPlaylistTrack> = if (isPendingPlaylist) {
        val queued = pendingEntry.value?.ops?.mapNotNull { op ->
            if (!op.startsWith("A|")) return@mapNotNull null
            val p = op.split('|')
            if (p.size < 3) return@mapNotNull null
            KugouPlaylistTrack(hash = p[2], name = p[1], artist = "", album = null, durationMs = 0, coverUrl = null)
        }?.filter { q -> tracks.value.none { it.hash == q.hash } } ?: emptyList()
        tracks.value + queued
    } else {
        tracks.value
    }

    // 队列即取即建（占位符 URI，入队零网络请求；挂起歌单的待加歌同样可播）
    fun currentQueue(): List<YosMediaItem> = displayTracks.map { KugouRepository.toQueueMediaItem(it) }

    // 添加歌曲弹层 / 待删除歌曲（长按行触发，仅自有歌单）
    val showAddDialog = remember("OnlinePlaylistDetail_add") { mutableStateOf(false) }
    val removeTarget = remember("OnlinePlaylistDetail_remove") { mutableStateOf<KugouPlaylistTrack?>(null) }

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
            val entry = pendingEntry.value
            val result: Result<List<KugouPlaylistTrack>> = when {
                // 挂起歌单未绑定真实 listid：先展示空列表（待加歌来自 ops 队列）
                isPendingPlaylist && entry?.isBound != true -> Result.success(emptyList())
                isPendingPlaylist && entry != null -> KugouRepository.getPlaylistSongs(entry.realListid, onProgress)
                source == PlaylistSelection.Source.User ->
                    KugouRepository.getPlaylistSongs(playlistId.orEmpty(), onProgress)
                source == PlaylistSelection.Source.Recommend ->
                    KugouRepository.getPlaylistSongsByGcid(playlistId.orEmpty(), onProgress)
                else -> Result.failure(IllegalStateException("Missing playlist selection"))
            }
            result
                .onSuccess { list ->
                    tracks.value = list
                    status.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    tracks.value = emptyList()
                    status.value = "error:${e.message}"
                }
        }
    }

    // 整列表播放：全部歌曲进 Media3 队列，从 index 处开始；URL 由播放器惰性解析
    fun playAt(index: Int) {
        val list = currentQueue()
        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(list[index], list)
        }
    }

    LaunchedEffect(source, playlistId) {
        loadTracks()
    }

    // 挂起歌单对账轮询：绑定真实 listid（含创建失败后的重试）→ 等 ops 补发完 → 拉真实歌曲
    LaunchedEffect(playlistId) {
        val localId = playlistId.orEmpty()
        if (PendingPlaylistStore.get(localId) == null) return@LaunchedEffect
        var wasUnbound = PendingPlaylistStore.get(localId)?.isBound == false
        while (true) {
            delay(4_000L)
            var fresh = PendingPlaylistStore.get(localId) ?: break
            pendingEntry.value = fresh
            if (fresh.isBound) {
                // 等挂起的加删歌补发完再拉真实列表，避免竞态（补发中拉取会缺最后几首）
                var waited = 0
                while (fresh.ops.isNotEmpty() && waited < 30_000) {
                    delay(2_000L)
                    waited += 2_000
                    fresh = PendingPlaylistStore.get(localId) ?: break
                    pendingEntry.value = fresh
                }
                if (wasUnbound) loadTracks()
                break
            }
            wasUnbound = true
        }
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
                            val list = currentQueue()
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
                            val list = currentQueue()
                            if (list.isNotEmpty()) {
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                playAt(list.indices.random())
                            }
                        }
                    )
                }
            }

            // 挂起歌单同步状态 banner（创建中 / 创建失败可重试）；绑定完成后自动消失
            if (isPendingPlaylist && pendingEntry.value?.isBound != true) {
                item("PendingBanner") {
                    val entry = pendingEntry.value
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = if (entry?.failed == true) {
                                stringResource(id = R.string.online_playlist_pending_failed)
                            } else {
                                stringResource(id = R.string.online_playlist_pending_creating)
                            },
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                            color = if (entry?.failed == true) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.alpha(if (entry?.failed == true) 1f else 0.7f)
                        )
                        if (entry?.failed == true) {
                            Text(
                                text = stringResource(id = R.string.online_playlist_pending_retry),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .padding(top = 6.dp)
                                    .clickable {
                                        scope.launch {
                                            PendingPlaylistStore.retryCreate(entry.localId)
                                                .onSuccess { pendingEntry.value = PendingPlaylistStore.get(entry.localId) }
                                                .onFailure { e ->
                                                    status.value = "error:${e.message}"
                                                }
                                        }
                                    }
                            )
                        }
                    }
                }
            }

            // 自有歌单：添加歌曲入口（挂起/已绑定均可；挂起时先进本地队列，绑定后自动补发）
            if (source == PlaylistSelection.Source.User) {
                item("AddSongs") {
                    NormalButton(
                        icon = painterResource(id = R.drawable.ic_add),
                        label = stringResource(id = R.string.online_playlist_add_songs),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 6.dp)
                    ) {
                        showAddDialog.value = true
                    }
                }
            }

            item("Status") {
                if (status.value == "empty") {
                    // 空歌单：资料库同款图标 + 提示文字，居中（高度收敛，贴近上方「添加歌曲」按钮）
                    Box(
                        modifier = Modifier
                            .fillParentMaxWidth()
                            .fillParentMaxHeight(0.32f),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_uitabbar_library),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(70.dp)
                                    .alpha(0.3f),
                                tint = Color.Black withNight Color.White
                            )
                            Text(
                                text = stringResource(id = R.string.online_playlist_songs_empty),
                                fontSize = 16.sp,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .padding(top = 12.dp)
                                    .alpha(0.55f)
                            )
                        }
                    }
                } else {
                    OnlineStatusItem(
                        status = status.value,
                        loadingText = loadProgress.value.ifEmpty { loadingBaseText },
                        emptyText = stringResource(id = R.string.online_playlist_songs_empty)
                    )
                }
            }

            item {
                OnlineDetailDivider()
            }

            OnlineDetailSongs(
                tracks = displayTracks,
                isWide = isWideScreen,
                onPlayAt = { playAt(it) },
                onRemoveTrack = if (source == PlaylistSelection.Source.User) {
                    { index -> removeTarget.value = displayTracks.getOrNull(index) }
                } else null
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

    // 删歌确认弹层（长按歌曲行触发，仅自有歌单）
    removeTarget.value?.let { target ->
        AlertDialog(
            onDismissRequest = { removeTarget.value = null },
            title = { Text(text = stringResource(id = R.string.online_playlist_track_remove)) },
            text = {
                Text(
                    text = stringResource(id = R.string.online_playlist_track_remove_confirm, target.name),
                    fontSize = 15.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val t = target
                    removeTarget.value = null
                    scope.launch {
                        val entry = pendingEntry.value
                        val result = if (entry != null) {
                            PendingPlaylistStore.removeSong(entry.localId, t.hash, t.fileId)
                        } else {
                            KugouRepository.removeTrackFromPlaylist(playlistId.orEmpty(), t.fileId, t.hash)
                        }
                        result
                            .onSuccess {
                                if (entry != null) pendingEntry.value = PendingPlaylistStore.get(entry.localId)
                                else {
                                    tracks.value = tracks.value.filter { it.hash != t.hash }
                                    status.value = if (tracks.value.isEmpty()) "empty" else "ok"
                                }
                            }
                            .onFailure { e -> status.value = "error:${e.message}" }
                    }
                }) {
                    Text(
                        text = stringResource(id = R.string.common_ok),
                        color = MaterialTheme.colorScheme.error
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { removeTarget.value = null }) {
                    Text(text = stringResource(id = R.string.common_cancel))
                }
            }
        )
    }

    // 添加歌曲弹层（搜索 → 点按加入歌单；挂起歌单先进同步队列）
    if (showAddDialog.value) {
        AddSongsDialog(
            onDismiss = { showAddDialog.value = false },
            onAdd = { song ->
                val entry = pendingEntry.value
                val result = if (entry != null) {
                    PendingPlaylistStore.addSong(entry.localId, song.name, song.hash)
                } else {
                    KugouRepository.addTracksToPlaylist(playlistId.orEmpty(), song.name, song.hash)
                }
                result
                    .onSuccess {
                        if (entry != null) pendingEntry.value = PendingPlaylistStore.get(entry.localId)
                        else loadTracks()
                    }
                result
            }
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
 * [onRemoveTrack] 非 null 时行支持长按（自有歌单删歌）。
 */
private fun LazyListScope.OnlineDetailSongs(
    tracks: List<KugouPlaylistTrack>,
    isWide: Boolean,
    onPlayAt: (Int) -> Unit,
    onRemoveTrack: ((Int) -> Unit)? = null
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
                MusicList(
                    music = KugouRepository.toDisplayMediaItem(track),
                    itemClick = { onPlayAt(index) },
                    onLongClick = onRemoveTrack?.let { cb -> { cb(index) } }
                )
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

/**
 * 添加歌曲弹层：关键词搜索在线歌曲 → 点按加入歌单。
 * 挂起歌单（未绑定真实 listid）由调用方入同步队列，此处只消费 [onAdd] 的结果。
 */
@Composable
private fun AddSongsDialog(
    onDismiss: () -> Unit,
    onAdd: suspend (KugouSearchSong) -> Result<Unit>
) {
    val scope = rememberCoroutineScope()
    val keyword = remember { mutableStateOf("") }
    val results = remember { mutableStateOf<List<KugouSearchSong>>(emptyList()) }
    val searching = remember { mutableStateOf(false) }
    val searched = remember { mutableStateOf(false) }
    val addedHashes = remember { mutableStateOf<Set<String>>(emptySet()) }
    val error = remember { mutableStateOf<String?>(null) }
    val addingHash = remember { mutableStateOf<String?>(null) }

    fun search() {
        val kw = keyword.value.trim()
        if (kw.isEmpty() || searching.value) return
        searching.value = true
        error.value = null
        scope.launch {
            KugouRepository.searchSongs(kw)
                .onSuccess {
                    results.value = it
                    searched.value = true
                }
                .onFailure { e -> error.value = e.message ?: e.javaClass.simpleName }
            searching.value = false
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(id = R.string.online_playlist_add_songs)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = keyword.value,
                        onValueChange = { keyword.value = it },
                        placeholder = { Text(text = stringResource(id = R.string.online_playlist_add_search_hint)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { search() }, enabled = !searching.value) {
                        Text(text = stringResource(id = R.string.common_search))
                    }
                }
                error.value?.let { err ->
                    Text(
                        text = err,
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(vertical = 4.dp)
                    )
                }
                if (searching.value) {
                    Text(
                        text = stringResource(id = R.string.online_playlists_loading),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp).alpha(0.6f)
                    )
                } else if (searched.value && results.value.isEmpty()) {
                    Text(
                        text = stringResource(id = R.string.search_no_result),
                        fontSize = 14.sp,
                        modifier = Modifier.padding(vertical = 8.dp).alpha(0.6f)
                    )
                } else {
                    results.value.take(10).forEach { song ->
                        val added = song.hash in addedHashes.value
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(44.dp)
                                .clickable(enabled = !added && addingHash.value == null) {
                                    addingHash.value = song.hash
                                    scope.launch {
                                        onAdd(song)
                                            .onSuccess { addedHashes.value = addedHashes.value + song.hash }
                                            .onFailure { e -> error.value = e.message ?: e.javaClass.simpleName }
                                        addingHash.value = null
                                    }
                                },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = song.name,
                                fontSize = 14.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Text(
                                text = " - ${song.author}",
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f).alpha(0.5f)
                            )
                            Text(
                                text = if (added) stringResource(id = R.string.online_playlist_add_added) else "",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(start = 8.dp)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(id = R.string.common_ok))
            }
        }
    )
}
