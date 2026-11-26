package yos.music.player.ui.pages.library

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.player.FadeExo.fadePlay
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.PlayList
import yos.music.player.data.libraries.PlayListLibrary
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.CollectedAlbumsObject
import yos.music.player.data.objects.CollectedPlaylistsObject
import yos.music.player.data.objects.DiscoveryObject
import yos.music.player.data.objects.FollowedArtistsObject
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouCollectedAlbum
import yos.music.player.data.objects.KugouProfileState
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.objects.KugouVipState
import yos.music.player.data.objects.LibraryObject
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.objects.PersonalFmObject
import yos.music.player.data.objects.UserPlaylistsObject
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouProfileRepository
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.PendingPlaylistStore
import yos.music.player.ui.UI
import yos.music.player.ui.navigation.LocalPageGradient
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.navigation.preloadAlbumBackdropColor
import yos.music.player.ui.navigation.preloadPlaylistBackdropColor
import yos.music.player.ui.navigation.rememberPageData
import yos.music.player.ui.pages.discovery.RecentListeningRail
import yos.music.player.ui.pages.library.playlists.sharedPlaylistCoverKey
import yos.music.player.ui.pages.library.playlists.validPlaylistCollectionGid
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.DiscoveryMinImageSize
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.calculateAdaptiveImageLayout
import yos.music.player.ui.widgets.basic.preloadRawCover
import yos.music.player.ui.widgets.mochi.MochiSegmentedControl
import yos.music.player.ui.widgets.mochi.MochiSegmentedDefaults

private sealed interface CollectionEntry {
    val gridItem: LibraryGridItem

    data class OnlinePlaylistEntry(
        val playlist: KugouPlaylist,
        override val gridItem: LibraryGridItem,
    ) : CollectionEntry

    data class CollectedAlbumEntry(
        val album: KugouCollectedAlbum,
        override val gridItem: LibraryGridItem,
    ) : CollectionEntry

    data class LocalPlaylistEntry(
        val playlist: PlayList,
        val resolvedSongs: List<YosMediaItem>,
        override val gridItem: LibraryGridItem,
    ) : CollectionEntry
}

private fun parsePinnedSet(raw: String): Set<String> =
    raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()

private fun togglePinnedKey(key: String) {
    val current = parsePinnedSet(SettingsLibrary.LibraryPinnedIds).toMutableSet()
    if (!current.add(key)) current.remove(key)
    SettingsLibrary.LibraryPinnedIds = current.joinToString(",")
}

private fun onlinePlaylistKey(pl: KugouPlaylist): String =
    pl.listid.ifEmpty { pl.gid }.ifEmpty { pl.sourceGid }

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun NewLibrary(
    navController: NavController,
    onOpenSettings: () -> Unit,
    onOpenOnlinePlaylist: (PlaylistSelection) -> Unit,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val windowInfo = LocalWindowInfo.current
    val fallbackScreenWidthDp = LocalConfiguration.current.screenWidthDp
    val containerWidth = with(density) {
        val px = windowInfo.containerSize.width
        if (px > 0) px.toDp() else fallbackScreenWidthDp.dp
    }
    val gridLayout = remember(containerWidth) {
        calculateAdaptiveImageLayout(
            containerWidth = containerWidth,
            startPadding = 20.dp,
            endPadding = 20.dp,
            minImageSize = DiscoveryMinImageSize,
            spacing = 15.dp
        )
    }
    val gridColumns = gridLayout.itemCount.coerceAtLeast(2)
    val gridCoverSizePx = with(density) { gridLayout.itemSize.roundToPx() }

    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val heroPagerState = rememberPagerState(pageCount = { 2 })
    val recentListState = rememberLazyListState()
    val artistsListState = rememberLazyListState()

    val showLocal = SettingsLibrary.ShowLocalLibrary
    var selectedTab by rememberPageData("NewLibrary_selected_tab") { LibrarySourceTab.Online }
    LaunchedEffect(showLocal) {
        if (!showLocal && selectedTab == LibrarySourceTab.Local) {
            selectedTab = LibrarySourceTab.Online
        }
    }

    val accountUserid = KugouAccountState.userid
    val isLoggedIn = KugouAccountState.isLoggedIn
    val vipDetail = KugouVipState.vipDetail
    val isVip = KugouAccountState.hasVipToken || (vipDetail?.isVip == true)
    // 用户资料（头像/昵称）：来自 /user/detail，冷启动先读 MMKV 缓存（不闪字母）
    val profileAvatarUrl = KugouProfileState.avatarUrl
    val profileNickname = KugouProfileState.nickname

    val refreshing = remember { mutableStateOf(false) }
    val contextEntry = remember { mutableStateOf<CollectionEntry?>(null) }

    // 记录当前从本页触发播放的集合 key（配合全局 isPlaying 状态同步网格右下角的播放/暂停图标）
    var activeCollectionPlayKey by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(isLoggedIn, accountUserid) {
        DiscoveryObject.ensureFresh()
        PersonalFmObject.ensureLoaded()
        if (isLoggedIn) {
            FollowedArtistsObject.ensureLoaded()
            CollectedAlbumsObject.ensureLoaded()
            CollectedPlaylistsObject.ensureLoaded()
            UserPlaylistsObject.ensureLoaded()
            KugouProfileRepository.ensureLoaded()
        } else {
            UserPlaylistsObject.reset()
        }
    }

    val playlistsRev = KugouSyncCoordinator.playlistsRevision.value
    LaunchedEffect(playlistsRev) {
        if (playlistsRev > 0 && isLoggedIn) {
            UserPlaylistsObject.ensureLoaded(force = true)
        }
    }
    val artistsRev = KugouSyncCoordinator.artistsRevision.value
    LaunchedEffect(artistsRev) {
        if (artistsRev > 0 && isLoggedIn) {
            FollowedArtistsObject.ensureLoaded(force = true)
        }
    }
    val albumsRev = KugouSyncCoordinator.albumsRevision.value
    LaunchedEffect(albumsRev) {
        if (albumsRev > 0 && isLoggedIn) {
            CollectedAlbumsObject.ensureLoaded(force = true)
        }
    }

    val dailySongs = DiscoveryObject.everydayRecommendSongs.value
    val fmSongs = PersonalFmObject.songs.value
    var fmFocusIndex by remember { mutableIntStateOf(PersonalFmObject.homeFocusIndex) }
    LaunchedEffect(fmSongs) {
        snapshotFlow {
            MediaController.isFmQueue.value to MediaController.musicPlaying.value?.mediaId
        }
            .distinctUntilChanged()
            .collect { (isFm, playingId) ->
                if (!isFm || playingId.isNullOrBlank()) return@collect
                val idx = fmSongs.indexOfFirst { song ->
                    playingId.equals(song.hash, ignoreCase = true) ||
                        playingId.endsWith(song.hash, ignoreCase = true)
                }
                if (idx >= 0 && idx != fmFocusIndex) {
                    fmFocusIndex = idx
                    PersonalFmObject.homeFocusIndex = idx
                }
            }
    }
    val recentSongsRaw = MusicLibrary.recentlyPlayed
    val recentSongs = remember(recentSongsRaw) { recentSongsRaw.take(10) }
    val followedArtists = if (isLoggedIn) FollowedArtistsObject.artists.value else emptyList()
    val collectedAlbums = if (isLoggedIn) CollectedAlbumsObject.albums.value else emptyList()
    val collectedPlaylists = if (isLoggedIn) CollectedPlaylistsObject.entries.value else emptyList()
    val userPlaylists = if (isLoggedIn) UserPlaylistsObject.playlists.value else emptyList()
    val mergedOnlinePlaylists = remember(userPlaylists, collectedPlaylists, isLoggedIn) {
        if (!isLoggedIn) emptyList()
        else CollectedPlaylistsObject.mergeEntries(userPlaylists)
            .filterNot { it.source == 2 || (it.isDef == 1 && it.songCount == 0) }
    }

    val pinnedRaw = SettingsLibrary.LibraryPinnedIds
    val pinnedSet = remember(pinnedRaw) { parsePinnedSet(pinnedRaw) }

    val onlineCollectionEntries = remember(mergedOnlinePlaylists, collectedAlbums, pinnedSet, context) {
        val plEntries = mergedOnlinePlaylists.map { pl ->
            val playlistId = onlinePlaylistKey(pl)
            val key = "pl:$playlistId"
            val sub = if (pl.songCount > 0) {
                "${pl.songCount} " + context.getString(R.string.online_playlists_song_unit)
            } else ""
            val canRemove = pl.isCollectedPlaylist || (pl.isDef == 0 && pl.canEditTracks)
            CollectionEntry.OnlinePlaylistEntry(
                playlist = pl,
                gridItem = LibraryGridItem(
                    pinKey = key,
                    title = pl.name,
                    subtitle = sub,
                    coverData = pl.coverUrl.takeIf { it.isNotBlank() },
                    isPinned = key in pinnedSet,
                    canRemove = canRemove,
                    sharedCoverKey = sharedPlaylistCoverKey(PlaylistSelection.Source.User, playlistId)
                )
            )
        }
        val albEntries = collectedAlbums.map { album ->
            val key = "alb:${album.albumId.ifBlank { album.name }}"
            CollectionEntry.CollectedAlbumEntry(
                album = album,
                gridItem = LibraryGridItem(
                    pinKey = key,
                    title = album.name,
                    subtitle = if (album.songCount > 0) {
                        context.getString(R.string.page_library_album_desc, album.songCount)
                    } else "",
                    coverData = album.coverUrl.takeIf { it.isNotBlank() },
                    isPinned = key in pinnedSet,
                    canRemove = true,
                    sharedCoverKey = album.albumId.takeIf { it.isNotBlank() }?.let { "album/collected/$it" }
                )
            )
        }
        (plEntries + albEntries).sortedByDescending { it.gridItem.isPinned }
    }
    val onlineGridItems = remember(onlineCollectionEntries) { onlineCollectionEntries.map { it.gridItem } }
    val onlineGridRows = remember(onlineGridItems, gridColumns) { onlineGridItems.chunked(gridColumns) }
    val onlineEntryByKey = remember(onlineCollectionEntries) { onlineCollectionEntries.associateBy { it.gridItem.pinKey } }

    val localSongs = MusicLibrary.songs
    val localByUri = remember(localSongs) { localSongs.associateBy { it.uri } }
    val localPlaylistEntries = remember(PlayListLibrary.playList, localByUri, pinnedSet, context) {
        PlayListLibrary.playList.map { pl ->
            val resolved = pl.songDataList.mapNotNull { localByUri[it] }
            val key = "lpl:${pl.listID}"
            CollectionEntry.LocalPlaylistEntry(
                playlist = pl,
                resolvedSongs = resolved,
                gridItem = LibraryGridItem(
                    pinKey = key,
                    title = pl.name,
                    subtitle = if (resolved.isNotEmpty()) {
                        context.getString(R.string.page_library_album_desc, resolved.size)
                    } else "",
                    coverData = resolved.firstOrNull()?.thumb,
                    isPinned = key in pinnedSet,
                    canRemove = true
                )
            )
        }.sortedByDescending { it.gridItem.isPinned }
    }
    val localGridItems = remember(localPlaylistEntries) { localPlaylistEntries.map { it.gridItem } }
    val localGridRows = remember(localGridItems, gridColumns) { localGridItems.chunked(gridColumns) }
    val localEntryByKey = remember(localPlaylistEntries) { localPlaylistEntries.associateBy { it.gridItem.pinKey } }

    suspend fun resolveTracks(entry: CollectionEntry): List<YosMediaItem> = when (entry) {
        is CollectionEntry.LocalPlaylistEntry -> entry.resolvedSongs
        is CollectionEntry.CollectedAlbumEntry -> {
            val albumId = CollectedAlbumsObject.resolveAlbumId(entry.album)
            if (albumId.isNullOrBlank()) emptyList()
            else KugouRepository.getAlbumSongs(albumId).getOrDefault(emptyList())
                .map { KugouRepository.toQueueMediaItem(it) }
        }
        is CollectionEntry.OnlinePlaylistEntry -> {
            val pl = entry.playlist
            val id = onlinePlaylistKey(pl)
            val result = when {
                pl.listid.startsWith("local_") -> Result.success(emptyList())
                validPlaylistCollectionGid(id) != null -> KugouRepository.getPlaylistSongsByGcid(id)
                else -> {
                    val primary = KugouRepository.getPlaylistSongs(id)
                    val gcid = validPlaylistCollectionGid(pl.sourceGid)
                    if (pl.isCollectedPlaylist && gcid != null && primary.getOrNull().isNullOrEmpty()) {
                        KugouRepository.getPlaylistSongsByGcid(gcid)
                    } else primary
                }
            }
            result.getOrDefault(emptyList()).map { KugouRepository.toQueueMediaItem(it) }
        }
    }

    fun playEntryNow(entry: CollectionEntry) {
        val key = entry.gridItem.pinKey
        if (activeCollectionPlayKey == key && MediaController.playingMusicList.value?.isNotEmpty() == true) {
            if (MediaViewModelObject.isPlaying.value) {
                MediaController.mediaControl?.fadePause()
            } else {
                MediaController.mediaControl?.fadePlay()
            }
            return
        }
        scope.launch {
            val queue = resolveTracks(entry)
            if (queue.isEmpty()) {
                Toast.makeText(context, R.string.library_action_empty_tracks, Toast.LENGTH_SHORT).show()
                return@launch
            }
            activeCollectionPlayKey = key
            launch(Dispatchers.IO) {
                MediaController.prepare(queue.first(), queue)
            }
        }
    }

    fun playEntryNext(entry: CollectionEntry) {
        scope.launch {
            val queue = resolveTracks(entry)
            if (queue.isEmpty()) {
                Toast.makeText(context, R.string.library_action_empty_tracks, Toast.LENGTH_SHORT).show()
                return@launch
            }
            queue.asReversed().forEach { item ->
                MediaController.playNext(item)
            }
            Toast.makeText(context, R.string.library_action_added_next, Toast.LENGTH_SHORT).show()
        }
    }

    fun openEntry(entry: CollectionEntry) {
        when (entry) {
            is CollectionEntry.LocalPlaylistEntry -> {
                navController.navigate(UI.localPlaylistDetailRoute(entry.playlist.listID))
            }
            is CollectionEntry.CollectedAlbumEntry -> {
                val album = entry.album
                scope.launch {
                    val albumId = CollectedAlbumsObject.resolveAlbumId(album)
                    if (albumId.isNullOrBlank()) {
                        Toast.makeText(context, R.string.album_open_failed, Toast.LENGTH_SHORT).show()
                        return@launch
                    }
                    SharedCoverStyle.lastSourceCorner = 7.dp
                    OnlineAlbumObject.setSharedCoverKey(albumId, "album/collected/$albumId")
                    OnlineAlbumObject.setSelected(
                        KugouNewAlbum(
                            albumId = albumId,
                            name = album.name,
                            singerName = "",
                            coverUrl = album.coverUrl,
                            songCount = album.songCount,
                            publishTime = null
                        )
                    )
                    coroutineScope {
                        launch { preloadRawCover(context, album.coverUrl) }
                        launch { preloadAlbumBackdropColor(context, albumId, album.coverUrl) }
                    }
                    NavGuard.run {
                        navController.navigate(UI.onlineAlbumRoute(albumId))
                    }
                }
            }
            is CollectionEntry.OnlinePlaylistEntry -> {
                val pl = entry.playlist
                val playlistId = onlinePlaylistKey(pl)
                val isLocalPending = pl.listid.startsWith("local_")
                SharedCoverStyle.lastSourceCorner = 7.dp
                scope.launch {
                    coroutineScope {
                        launch { preloadRawCover(context, pl.coverUrl) }
                        launch {
                            preloadPlaylistBackdropColor(
                                context = context,
                                source = PlaylistSelection.Source.User.name,
                                playlistId = playlistId,
                                coverUrl = pl.coverUrl
                            )
                        }
                    }
                    onOpenOnlinePlaylist(
                        PlaylistSelection(
                            source = PlaylistSelection.Source.User,
                            id = playlistId,
                            name = pl.name,
                            cover = pl.coverUrl,
                            songCount = pl.songCount,
                            sourceGid = pl.sourceGid.takeIf { it.isNotBlank() },
                            canEditTracks = pl.canEditTracks || isLocalPending,
                            ownerUserid = if (pl.isCollectedPlaylist) pl.listCreateUserid else accountUserid
                        )
                    )
                }
            }
        }
    }

    fun removeEntry(entry: CollectionEntry) {
        when (entry) {
            is CollectionEntry.LocalPlaylistEntry -> PlayListLibrary.remove(entry.playlist)
            is CollectionEntry.CollectedAlbumEntry -> {
                scope.launch {
                    val album = entry.album
                    CollectedAlbumsObject.toggle(
                        album = KugouNewAlbum(
                            albumId = album.albumId,
                            name = album.name,
                            singerName = "",
                            coverUrl = album.coverUrl,
                            songCount = album.songCount,
                            publishTime = null
                        ),
                        artistId = album.artistId
                    ).onFailure {
                        Toast.makeText(context, it.message ?: context.getString(R.string.album_open_failed), Toast.LENGTH_SHORT).show()
                    }
                }
            }
            is CollectionEntry.OnlinePlaylistEntry -> {
                val pl = entry.playlist
                scope.launch {
                    when {
                        pl.isCollectedPlaylist -> {
                            CollectedPlaylistsObject.uncollect(pl)
                                .onSuccess {
                                    UserPlaylistsObject.removeLocal {
                                        it.isCollectedPlaylist && it.sourceGid == pl.sourceGid
                                    }
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: context.getString(R.string.playlist_collect_failed), Toast.LENGTH_SHORT).show()
                                }
                        }
                        pl.listid.startsWith("local_") -> {
                            PendingPlaylistStore.delete(pl.listid)
                            UserPlaylistsObject.removeLocal { it === pl }
                        }
                        else -> {
                            KugouRepository.deleteMyPlaylist(pl)
                                .onSuccess {
                                    UserPlaylistsObject.removeLocal { it === pl }
                                    KugouSyncCoordinator.notifyPlaylistsChanged()
                                }
                                .onFailure {
                                    Toast.makeText(context, it.message ?: context.getString(R.string.playlist_collect_failed), Toast.LENGTH_SHORT).show()
                                }
                        }
                    }
                }
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Title(
            title = stringResource(id = R.string.page_library_title),
            listState = listState,
            extraTopPadding = 57.dp,
            refreshing = refreshing.value,
            onRefresh = {
                refreshing.value = true
                scope.launch {
                    DiscoveryObject.refreshAll()
                    if (isLoggedIn) {
                        KugouSyncCoordinator.refreshAllAsync()
                    }
                    delay(450)
                    refreshing.value = false
                }
            },
            leftBarIcon = {
                KugouProfileAvatarButton(
                    isLoggedIn = isLoggedIn,
                    nickname = profileNickname ?: vipDetail?.nickname,
                    userid = accountUserid,
                    isVip = isVip,
                    onClick = { navController.toUI(UI.Settings.KugouLogin) },
                    avatarUrl = profileAvatarUrl
                )
            },
            rightBarIcon = {
                TitleBarIcon(
                    icon = Icons.Filled.MoreVert,
                    onBack = onOpenSettings
                )
            }
        ) {
            if (showLocal) {
                item("segmented_control", contentType = "segmented_control") {
                    val pageGradient = LocalPageGradient.current
                    val isDark = isFlamingoInDarkMode()
                    val defaultThumb = if (isDark) MaterialTheme.colorScheme.surfaceVariant else Color.White
                    val thumbColor = pageGradient?.averageColor(defaultThumb) ?: defaultThumb
                    MochiSegmentedControl(
                        selectedIndex = if (selectedTab == LibrarySourceTab.Online) 0 else 1,
                        onSelect = { idx ->
                            selectedTab = if (idx == 0) LibrarySourceTab.Online else LibrarySourceTab.Local
                        },
                        items = listOf(
                            stringResource(id = R.string.library_tab_online),
                            stringResource(id = R.string.library_tab_local)
                        ),
                        colors = MochiSegmentedDefaults.colors(
                            thumb = thumbColor
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp)
                            .padding(bottom = 18.dp)
                    )
                }
            }

            if (selectedTab == LibrarySourceTab.Local && showLocal) {
                item("local_nav_rows", contentType = "local_nav_rows") {
                    val songsTitle = stringResource(id = R.string.page_library_songs)
                    LibraryLocalNavRows(
                        songCount = localSongs.size,
                        albumCount = MusicLibrary.albums.size,
                        artistCount = MusicLibrary.artists.size,
                        onSongsClick = {
                            LibraryObject.setTargetListWithTitle(songsTitle, localSongs)
                            navController.toUI(UI.NormalMusic)
                        },
                        onAlbumsClick = { navController.toUI(UI.LocalAlbums) },
                        onArtistsClick = { navController.toUI(UI.LocalArtists) }
                    )
                }

                item("local_playlists_header", contentType = "section_header") {
                    Spacer(Modifier.height(22.dp))
                    LibrarySectionHeader(
                        title = stringResource(id = R.string.library_local_playlists_title),
                        showChevron = true,
                        onTitleClick = { navController.toUI(UI.PlayLists) }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (localPlaylistEntries.isEmpty()) {
                    item("local_playlists_empty", contentType = "empty_text") {
                        Text(
                            text = stringResource(id = R.string.library_local_playlists_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                        )
                    }
                } else {
                    itemsIndexed(
                        items = localGridRows,
                        key = { _, row -> "local_row_${row.first().pinKey}" },
                        contentType = { _, _ -> "library_collection_row" }
                    ) { rowIndex, rowItems ->
                        LibraryCollectionGridRow(
                            items = rowItems,
                            columns = gridColumns,
                            coverSizePx = gridCoverSizePx,
                            isLastRow = rowIndex == localGridRows.lastIndex,
                            isItemPlaying = { pinKey ->
                                MediaViewModelObject.isPlaying.value && activeCollectionPlayKey == pinKey
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            placeholderRes = R.drawable.placeholder_playlist_default,
                            onItemClick = { grid -> localEntryByKey[grid.pinKey]?.let(::openEntry) },
                            onItemPlayClick = { grid -> localEntryByKey[grid.pinKey]?.let(::playEntryNow) },
                            onItemLongClick = { grid -> contextEntry.value = localEntryByKey[grid.pinKey] }
                        )
                    }
                }
            } else {
                // 在线音乐页 1：顶部双功能小组件自适应区
                item("hero_widget_rail", contentType = "hero_widget_rail") {
                    LibraryHeroWidgetRail(
                        containerWidth = containerWidth,
                        pagerState = heroPagerState,
                        dailySongs = dailySongs,
                        fmSongs = fmSongs,
                        fmIndex = fmFocusIndex,
                        nowPlayingMediaIdProvider = { MediaController.musicPlaying.value?.mediaId },
                        isGlobalPlayingProvider = { MediaViewModelObject.isPlaying.value },
                        onPlayDailyToggle = {
                            if (dailySongs.isNotEmpty()) {
                                val currentMediaId = MediaController.musicPlaying.value?.mediaId
                                val isDailyActive = !currentMediaId.isNullOrBlank() && dailySongs.any { song ->
                                    currentMediaId.equals(song.hash, ignoreCase = true) ||
                                        currentMediaId.endsWith(song.hash, ignoreCase = true)
                                }
                                if (isDailyActive) {
                                    if (MediaViewModelObject.isPlaying.value) MediaController.mediaControl?.fadePause()
                                    else MediaController.mediaControl?.fadePlay()
                                } else {
                                    activeCollectionPlayKey = null
                                    val queue = dailySongs.map { KugouRepository.toQueueMediaItem(it) }
                                    scope.launch(Dispatchers.IO) {
                                        MediaController.prepare(queue.first(), queue)
                                    }
                                }
                            }
                        },
                        onPlayDailySongAt = { index ->
                            if (index in dailySongs.indices) {
                                activeCollectionPlayKey = null
                                val song = dailySongs[index]
                                val currentMediaId = MediaController.musicPlaying.value?.mediaId
                                val isSame = !currentMediaId.isNullOrBlank() &&
                                    (currentMediaId.equals(song.hash, ignoreCase = true) ||
                                        currentMediaId.endsWith(song.hash, ignoreCase = true))
                                if (isSame) {
                                    if (MediaViewModelObject.isPlaying.value) MediaController.mediaControl?.fadePause()
                                    else MediaController.mediaControl?.fadePlay()
                                } else {
                                    val queue = dailySongs.map { KugouRepository.toQueueMediaItem(it) }
                                    scope.launch(Dispatchers.IO) {
                                        MediaController.prepare(queue[index], queue)
                                    }
                                }
                            }
                        },
                        onOpenDailyDetail = { navController.toUI(UI.EverydayRecommendDetail) },
                        onSwitchFm = { nextIdx ->
                            activeCollectionPlayKey = null
                            fmFocusIndex = nextIdx
                            PersonalFmObject.homeFocusIndex = nextIdx
                            PersonalFmObject.ensureAhead(nextIdx)
                            if (nextIdx in fmSongs.indices) {
                                val queue = fmSongs.map { KugouRepository.toQueueMediaItem(it) }
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(queue[nextIdx], queue, fmQueue = true)
                                }
                            }
                        },
                        onPlayCurrentFmToggle = {
                            if (fmSongs.isEmpty()) {
                                navController.toUI(UI.MusicGallery)
                            } else {
                                activeCollectionPlayKey = null
                                val idx = fmFocusIndex.coerceIn(0, fmSongs.lastIndex)
                                val song = fmSongs[idx]
                                val currentMediaId = MediaController.musicPlaying.value?.mediaId
                                val isSame = !currentMediaId.isNullOrBlank() &&
                                    (currentMediaId.equals(song.hash, ignoreCase = true) ||
                                        currentMediaId.endsWith(song.hash, ignoreCase = true))
                                if (isSame) {
                                    if (MediaViewModelObject.isPlaying.value) MediaController.mediaControl?.fadePause()
                                    else MediaController.mediaControl?.fadePlay()
                                } else {
                                    val queue = fmSongs.map { KugouRepository.toQueueMediaItem(it) }
                                    scope.launch(Dispatchers.IO) {
                                        MediaController.prepare(queue[idx], queue, fmQueue = true)
                                    }
                                }
                            }
                        },
                        onOpenGallery = { navController.toUI(UI.MusicGallery) },
                        onOpenFmList = { navController.toUI(UI.PersonalFmDetail) }
                    )
                }

                // 在线音乐页 2：最近播放（与主页「继续收听」一致的样式）
                if (recentSongs.isNotEmpty()) {
                    item("recent_songs_section", contentType = "recent_songs_section") {
                        Spacer(Modifier.height(22.dp))
                        LibrarySectionHeader(
                            title = stringResource(id = R.string.library_recent_played),
                            showChevron = true,
                            onTitleClick = { navController.toUI(UI.RecentPlayedDetail) }
                        )
                        Spacer(Modifier.height(10.dp))
                        RecentListeningRail(
                            songs = recentSongs,
                            listState = recentListState,
                            onSongClick = { music, queue ->
                                activeCollectionPlayKey = null
                                scope.launch(Dispatchers.IO) {
                                    MediaController.prepare(music, queue)
                                }
                            }
                        )
                    }
                }

                // 在线音乐页 3：关注的艺人
                if (followedArtists.isNotEmpty()) {
                    item("followed_artists_section", contentType = "followed_artists_section") {
                        Spacer(Modifier.height(22.dp))
                        LibrarySectionHeader(
                            title = stringResource(id = R.string.library_followed_artists),
                            showChevron = true,
                            onTitleClick = { navController.toUI(UI.OnlineArtists) }
                        )
                        Spacer(Modifier.height(11.dp))
                        LibraryFollowedArtistsRail(
                            artists = followedArtists,
                            containerWidth = containerWidth,
                            listState = artistsListState,
                            onArtistClick = { artist ->
                                navController.navigate(
                                    UI.artistDetailRoute(
                                        artistId = artist.artistId,
                                        artistName = artist.name
                                    )
                                )
                            }
                        )
                    }
                }

                // 在线音乐页 4：歌单与收藏专辑（与最近播放、关注的艺人同款右箭头交互）
                item("collection_header", contentType = "section_header") {
                    Spacer(Modifier.height(22.dp))
                    LibrarySectionHeader(
                        title = stringResource(id = R.string.library_collection_title),
                        showChevron = true,
                        onTitleClick = { navController.toUI(UI.OnlinePlaylists) }
                    )
                    Spacer(Modifier.height(12.dp))
                }

                if (!isLoggedIn) {
                    item("collection_login_tip", contentType = "login_tip") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 20.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = stringResource(id = R.string.library_collection_login_hint),
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                            )
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { navController.navigate(UI.Settings.KugouLogin) }) {
                                Text(text = stringResource(id = R.string.library_profile_login))
                            }
                        }
                    }
                } else if (onlineCollectionEntries.isEmpty()) {
                    item("collection_empty", contentType = "empty_text") {
                        Text(
                            text = stringResource(id = R.string.library_collection_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.56f),
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    itemsIndexed(
                        items = onlineGridRows,
                        key = { _, row -> "online_row_${row.first().pinKey}" },
                        contentType = { _, _ -> "library_collection_row" }
                    ) { rowIndex, rowItems ->
                        LibraryCollectionGridRow(
                            items = rowItems,
                            columns = gridColumns,
                            coverSizePx = gridCoverSizePx,
                            isLastRow = rowIndex == onlineGridRows.lastIndex,
                            isItemPlaying = { pinKey ->
                                MediaViewModelObject.isPlaying.value && activeCollectionPlayKey == pinKey
                            },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onItemClick = { grid -> onlineEntryByKey[grid.pinKey]?.let(::openEntry) },
                            onItemPlayClick = { grid -> onlineEntryByKey[grid.pinKey]?.let(::playEntryNow) },
                            onItemLongClick = { grid -> contextEntry.value = onlineEntryByKey[grid.pinKey] }
                        )
                    }
                }
            }
        }
    }

    contextEntry.value?.let { entry ->
        LibraryCollectionContextDialog(
            item = entry.gridItem,
            onPlayNow = { playEntryNow(entry) },
            onTogglePin = { togglePinnedKey(entry.gridItem.pinKey) },
            onPlayNext = { playEntryNext(entry) },
            onRemove = { removeEntry(entry) },
            onDismiss = { contextEntry.value = null }
        )
    }
}
