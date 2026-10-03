package yos.music.player.ui.pages.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.objects.SearchObject
import yos.music.player.data.repositories.KugouLyricSearchResult
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouPlaylistBrief
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.KugouSearchSong
import yos.music.player.ui.UI
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.SearchTextField
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.preloadRawCover

/**
 * 搜索一级 Tab 页。
 *
 * 业务链：搜索 → [KugouRepository.searchSongs]；点击歌曲结果 → 全部结果映射为队列
 * （uri 为占位符，真实 URL 由播放器惰性解析）→ MediaController.prepare 整列表队列。
 *
 * 本页能力（对照上游 md3Music search_page.dart，本批不做歌手 Tab）：
 * - 空态区：搜索历史（MMKV 持久化，去重头插上限 10，可一键清空）+ 热搜榜 Top15
 * - 输入联想：onValueChange 250ms 防抖拉 /search/suggest，点击词条即搜
 * - 多类型结果 Tab：歌曲 / 专辑 / 歌单 / 歌词，按类型缓存（切 Tab 不重搜）
 *   - 专辑 → OnlineAlbumObject + OnlineAlbumDetail；歌单 → PlaylistSelection(gcid) → OnlinePlaylistDetail
 *   - 歌词结果副标题展示命中片段，点击同歌曲整列表播放
 * - 歌曲 Tab 滚动分页（每页 20，滚动到底前自动加载，空页/去重后无新增即到底）
 * 状态保持：全部关键状态存 [SearchObject]（进程级 holder），切 Tab 原样恢复不重搜。
 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun SearchPage(
    navController: NavController,
    onOpenSettings: (() -> Unit)? = null,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
) {
    val openSettings = onOpenSettings ?: { navController.toUI(UI.Settings.Main) }
    val searchText = SearchObject.searchText
    val results = SearchObject.results
    val queue = SearchObject.queue
    val status = SearchObject.status
    val busy = remember("SearchPage_busy") { mutableStateOf(false) }
    // 歌曲 Tab 滚动分页：服务端翻页边界有重叠(实测 p1∩p2 有重复)，按 hash 去重后
    // 全部重复/空页才算到底；页码独立计数，不能用 size/PAGE_SIZE 推算
    val songEndReached = remember("SearchPage_songEndReached") { mutableStateOf(false) }
    val songNextPage = remember("SearchPage_songNextPage") { mutableStateOf(2) }
    val listState = rememberLazyListState()

    val scope = rememberCoroutineScope()

    // 上次会话残留的 loading 态自愈 + 热搜榜预取（空态区展示，进程内缓存）
    LaunchedEffect(Unit) {
        if (status.value == "loading" && !busy.value) {
            status.value = if (results.value.isEmpty()) "idle" else "ok"
        }
        if (SearchObject.hotWords.value.isEmpty()) {
            KugouRepository.getHotSearch().onSuccess { SearchObject.hotWords.value = it.take(15) }
        }
    }

    // 输入联想：每次关键词变化防抖 250ms 拉取（上游无防抖每键一请求，不抄）；
    // 关键词为空或正是已搜词时清空联想区
    LaunchedEffect(searchText.value) {
        val keyword = searchText.value.trim()
        if (keyword.isEmpty() || keyword == SearchObject.lastSearched.value) {
            SearchObject.suggestions.value = emptyList()
            return@LaunchedEffect
        }
        delay(250)
        KugouRepository.getSearchSuggest(keyword).onSuccess {
            if (searchText.value.trim() == keyword) SearchObject.suggestions.value = it
        }
    }

    val loadingText = stringResource(id = R.string.online_playlists_loading)
    val emptyText = stringResource(id = R.string.tip_no_song)
    val keyboardController = LocalSoftwareKeyboardController.current

    // 统一搜索流程：重置全部类型缓存 → 搜歌曲页 1 → 记录历史
    fun doSearch() {
        val keyword = searchText.value.trim()
        if (keyword.isEmpty() || busy.value) return
        busy.value = true
        status.value = "loading"
        songEndReached.value = false
        songNextPage.value = 2
        SearchObject.resetForNewSearch()
        SearchObject.lastSearched.value = keyword
        scope.launch {
            KugouRepository.searchSongs(keyword)
                .onSuccess { songs ->
                    results.value = songs
                    queue.value = songs.map { KugouRepository.toQueueMediaItem(it) }
                    status.value = if (songs.isEmpty()) "empty" else "ok"
                    SearchObject.recordHistory(keyword)
                }
                .onFailure { e ->
                    results.value = emptyList()
                    queue.value = emptyList()
                    status.value = "error:${e.message}"
                }
            busy.value = false
        }
    }

    /** 切换结果 Tab：有缓存直接展示，无缓存按类型补拉第一页。 */
    fun ensureTypeLoaded(type: String) {
        SearchObject.switchType(type)
        val keyword = SearchObject.lastSearched.value ?: return
        val hasCache = when (type) {
            SearchObject.TYPE_SONG -> results.value.isNotEmpty()
            SearchObject.TYPE_ALBUM -> SearchObject.albumResults.value.isNotEmpty()
            SearchObject.TYPE_SPECIAL -> SearchObject.playlistResults.value.isNotEmpty()
            SearchObject.TYPE_LYRIC -> SearchObject.lyricResults.value.isNotEmpty()
            else -> false
        }
        if (hasCache || busy.value) return
        busy.value = true
        status.value = "loading"
        scope.launch {
            val count = when (type) {
                SearchObject.TYPE_ALBUM -> KugouRepository.searchAlbums(keyword)
                    .onSuccess { SearchObject.albumResults.value = it }
                    .map { it.size }
                    .getOrElse { -1 }

                SearchObject.TYPE_SPECIAL -> KugouRepository.searchSpecials(keyword)
                    .onSuccess { SearchObject.playlistResults.value = it }
                    .map { it.size }
                    .getOrElse { -1 }

                SearchObject.TYPE_LYRIC -> KugouRepository.searchLyricSongs(keyword)
                    .onSuccess { SearchObject.lyricResults.value = it }
                    .map { it.size }
                    .getOrElse { -1 }

                else -> results.value.size
            }
            status.value = when {
                count < 0 -> "error:请求失败"
                count == 0 -> "empty"
                else -> "ok"
            }
            busy.value = false
        }
    }

    /**
     * 歌曲 Tab 滚动分页：拉取 [songNextPage] 页并按 hash 去重追加。
     * 去重后无新增（空页或与服务端翻页重叠全弹）置 [songEndReached]，之后不再请求；
     * 请求失败保持页码不变，下次滚动触发重试。
     */
    fun loadMoreSongs() {
        if (busy.value || songEndReached.value) return
        if (SearchObject.currentType.value != SearchObject.TYPE_SONG) return
        if (results.value.isEmpty()) return
        val keyword = SearchObject.lastSearched.value ?: return
        busy.value = true
        val page = songNextPage.value
        scope.launch {
            KugouRepository.searchSongs(keyword, page, PAGE_SIZE)
                .onSuccess { more ->
                    val existing = results.value.map { it.hash }.toHashSet()
                    val fresh = more.filter { it.hash !in existing }
                    if (fresh.isEmpty()) {
                        songEndReached.value = true
                    } else {
                        results.value = results.value + fresh
                        queue.value = queue.value + fresh.map {
                            KugouRepository.toQueueMediaItem(it)
                        }
                        songNextPage.value = page + 1
                    }
                }
            busy.value = false
        }
    }

    // 滚动到底自动加载（距底部 ≤6 项触发），同 NewAlbumsDetail/RecommendPlaylistsDetail 模式
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearBottom ->
            if (nearBottom) loadMoreSongs()
        }
    }

    /** 歌词结果点击：以歌词结果列表为播放上下文，从点击处整列表播放。 */
    fun playLyricSong(item: KugouLyricSearchResult) {
        val list = SearchObject.lyricResults.value.map { KugouRepository.toQueueMediaItem(it.song) }
        val index = SearchObject.lyricResults.value.indexOf(item)
        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(list[index], list)
        }
    }

    /** 歌曲结果点击（含滚动分页后的完整列表）。 */
    fun playSong(song: KugouSearchSong) {
        val index = results.value.indexOf(song)
        val list = queue.value
        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(list[index], list)
        }
    }

    Title(
        title = stringResource(id = R.string.page_search_title),
        topRightIcon = Icons.Filled.MoreVert,
        onTopRightIcon = {
            openSettings()
        },
        extraTopPadding = 57.dp,
        listState = listState
    ) {
        item("SearchField") {
            SearchTextField(
                text = searchText.value,
                placeholder = stringResource(id = R.string.page_online_search_placeholder),
                onValueChange = { searchText.value = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 5.dp),
                onSearch = {
                    if (searchText.value.isNotEmpty()) {
                        keyboardController?.hide()
                        doSearch()
                    }
                })
        }

        val keyword = searchText.value.trim()
        val showSuggest = keyword.isNotEmpty() &&
            keyword != SearchObject.lastSearched.value &&
            SearchObject.suggestions.value.isNotEmpty()

        when {
            // 空态区：搜索历史 + 热搜榜
            keyword.isEmpty() -> {
                if (SearchObject.history.value.isNotEmpty()) {
                    item("history_header") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(id = R.string.search_history),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.weight(1f)
                            )
                            Text(
                                text = stringResource(id = R.string.search_clear_history),
                                fontSize = 13.sp,
                                modifier = Modifier
                                    .alpha(0.5f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { SearchObject.clearHistory() }
                                    .padding(4.dp)
                            )
                        }
                    }
                    item("history_chips") {
                        ChipFlow(
                            words = SearchObject.history.value,
                            onChipClick = { word ->
                                searchText.value = word
                                doSearch()
                            }
                        )
                    }
                }
                if (SearchObject.hotWords.value.isNotEmpty()) {
                    item("hot_header") {
                        Text(
                            text = stringResource(id = R.string.search_hot),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 6.dp)
                        )
                    }
                    item("hot_chips") {
                        ChipFlow(
                            words = SearchObject.hotWords.value,
                            onChipClick = { word ->
                                searchText.value = word
                                doSearch()
                            }
                        )
                    }
                }
            }

            // 输入联想区
            showSuggest -> {
                itemsIndexed(SearchObject.suggestions.value, key = { _, w -> "sg:$w" }) { _, word ->
                    Text(
                        text = word,
                        fontSize = 16.sp,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                searchText.value = word
                                doSearch()
                            }
                            .padding(horizontal = 22.dp, vertical = 12.dp)
                    )
                    OnlineListItemDivider()
                }
            }

            // 结果区：类型 Tab + 按类型内容
            else -> {
                item("type_tabs") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 22.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(22.dp)
                    ) {
                        listOf(
                            SearchObject.TYPE_SONG to R.string.search_type_song,
                            SearchObject.TYPE_ALBUM to R.string.search_type_album,
                            SearchObject.TYPE_SPECIAL to R.string.search_type_special,
                            SearchObject.TYPE_LYRIC to R.string.search_type_lyric
                        ).forEach { (type, labelRes) ->
                            val selected = SearchObject.currentType.value == type
                            Text(
                                text = stringResource(id = labelRes),
                                fontSize = 15.5.sp,
                                fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f),
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { ensureTypeLoaded(type) }
                                    .padding(vertical = 6.dp)
                            )
                        }
                    }
                }

                item("Status") {
                    OnlineStatusItem(
                        status = status.value,
                        loadingText = loadingText,
                        emptyText = emptyText
                    )
                }

                when (SearchObject.currentType.value) {
                    SearchObject.TYPE_ALBUM -> item("albums") {
                        AlbumGrid(
                            albums = SearchObject.albumResults.value,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        ) { album ->
                            OnlineAlbumObject.setSelected(album)
                            navController.toUI(UI.OnlineAlbumDetail)
                        }
                    }

                    SearchObject.TYPE_SPECIAL -> item("playlists") {
                        PlaylistGrid(
                            playlists = SearchObject.playlistResults.value,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        ) { playlist ->
                            val selection = PlaylistSelection(
                                source = PlaylistSelection.Source.Recommend,
                                id = playlist.globalCollectionId?.toString()
                                    ?: playlist.specialId.toString(),
                                name = playlist.name,
                                cover = playlist.cover ?: "",
                                songCount = playlist.songCount
                            )
                            navController.navigate(selection.toRoute())
                        }
                    }

                    SearchObject.TYPE_LYRIC -> itemsIndexed(
                        SearchObject.lyricResults.value,
                        key = { _, item -> "ly:${item.song.hash}" }
                    ) { index, item ->
                        LyricResultRow(item) { playLyricSong(item) }
                        if (index < SearchObject.lyricResults.value.size - 1) {
                            OnlineListItemDivider()
                        }
                    }

                    else -> {
                        itemsIndexed(
                            results.value,
                            key = { _, song -> song.hash }
                        ) { index, song ->
                            MusicList(KugouRepository.toDisplayMediaItem(song)) {
                                playSong(song)
                            }
                            if (index < results.value.size - 1) {
                                OnlineListItemDivider()
                            }
                        }
                        // 滚动分页：距底自动加载，加载中在尾部显示小指示器
                        if (busy.value && results.value.isNotEmpty()) {
                            item("load_more_footer") {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 14.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(20.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PAGE_SIZE = 20

/** 历史/热搜词条：每行 3 个的简易流式布局（避免引入实验性 FlowRow）。 */
@Composable
private fun ChipFlow(words: List<String>, onChipClick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
        words.chunked(3).forEach { rowWords ->
            Row(Modifier.fillMaxWidth()) {
                rowWords.forEach { word ->
                    Text(
                        text = word,
                        fontSize = 13.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(horizontal = 4.dp, vertical = 5.dp)
                            .clip(RoundedCornerShape(50))
                            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f))
                            .clickable { onChipClick(word) }
                            .padding(horizontal = 13.dp, vertical = 7.dp)
                            .width(96.dp)
                    )
                }
            }
        }
    }
}

/** 专辑结果两列网格。 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun AlbumGrid(
    albums: List<KugouNewAlbum>,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope?,
    onAlbumClick: (KugouNewAlbum) -> Unit
) {
    val context = LocalContext.current
    val preloadScope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 14.dp)) {
        albums.chunked(2).forEach { rowAlbums ->
            Row(Modifier.fillMaxWidth()) {
                rowAlbums.forEach { album ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                SharedCoverStyle.lastSourceCorner = 8.dp
                                preloadScope.launch {
                                    preloadRawCover(context, album.coverUrl)
                                    onAlbumClick(album)
                                }
                            }
                            .padding(8.dp)
                    ) {
                        // 圆角保持列表自身的 8dp；转场中的圆角渐变由详情端依据 SharedCoverStyle 完成
                        val coverModifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .then(
                                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                    with(sharedTransitionScope) {
                                        Modifier.sharedElement(
                                            sharedContentState = rememberSharedContentState(
                                                key = "album/online/${album.albumId}"
                                            ),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    }
                                } else Modifier
                            )
                        Box(coverModifier) {
                            ShadowImageWithCache(
                                dataLambda = { album.coverUrl },
                                contentDescription = album.name,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 8.dp,
                                shadowAlpha = 0f,
                                imageQuality = yos.music.player.ui.widgets.basic.ImageQuality.LOW
                            )
                        }
                        Text(
                            text = album.name,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            text = album.singerName,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .alpha(0.5f)
                        )
                    }
                }
                if (rowAlbums.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 歌单结果两列网格。 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
private fun PlaylistGrid(
    playlists: List<KugouPlaylistBrief>,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope?,
    onPlaylistClick: (KugouPlaylistBrief) -> Unit
) {
    val context = LocalContext.current
    val preloadScope = rememberCoroutineScope()
    Column(Modifier.padding(horizontal = 14.dp)) {
        playlists.chunked(2).forEach { rowPlaylists ->
            Row(Modifier.fillMaxWidth()) {
                rowPlaylists.forEach { playlist ->
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(12.dp))
                            .clickable {
                                SharedCoverStyle.lastSourceCorner = 8.dp
                                preloadScope.launch {
                                    preloadRawCover(context, playlist.cover ?: "")
                                    onPlaylistClick(playlist)
                                }
                            }
                            .padding(8.dp)
                    ) {
                        val playlistId = playlist.globalCollectionId?.toString()
                            ?: playlist.specialId.toString()
                        // 圆角保持列表自身的 8dp；转场中的圆角渐变由详情端依据 SharedCoverStyle 完成
                        val coverModifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .then(
                                if (sharedTransitionScope != null && animatedVisibilityScope != null) {
                                    with(sharedTransitionScope) {
                                        Modifier.sharedElement(
                                            sharedContentState = rememberSharedContentState(
                                                key = "playlist/Recommend/$playlistId"
                                            ),
                                            animatedVisibilityScope = animatedVisibilityScope
                                        )
                                    }
                                } else Modifier
                            )
                        Box(coverModifier) {
                            ShadowImageWithCache(
                                dataLambda = { playlist.cover },
                                contentDescription = playlist.name,
                                modifier = Modifier.fillMaxSize(),
                                cornerRadius = 8.dp,
                                shadowAlpha = 0f,
                                imageQuality = yos.music.player.ui.widgets.basic.ImageQuality.LOW
                            )
                        }
                        Text(
                            text = playlist.name,
                            fontSize = 14.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 6.dp)
                        )
                        Text(
                            text = "${playlist.songCount} 首",
                            fontSize = 12.sp,
                            maxLines = 1,
                            modifier = Modifier
                                .padding(top = 2.dp)
                                .alpha(0.5f)
                        )
                    }
                }
                if (rowPlaylists.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 歌词结果行：副标题展示命中片段。 */
@Composable
private fun LyricResultRow(item: KugouLyricSearchResult, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShadowImageWithCache(
            dataLambda = { item.song.artworkUrl },
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            cornerRadius = 4.dp,
            shadowAlpha = 0f,
            imageQuality = yos.music.player.ui.widgets.basic.ImageQuality.LOW
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                text = item.song.name,
                fontSize = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = listOfNotNull(
                    item.song.author,
                    item.lyricFragment?.take(30)
                ).joinToString(" · "),
                fontSize = 11.5.sp,
                lineHeight = 15.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(top = 1.dp)
                    .alpha(0.5f)
            )
        }
    }
}
