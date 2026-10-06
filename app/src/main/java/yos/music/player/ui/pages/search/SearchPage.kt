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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.objects.ArtistPresentationCache
import yos.music.player.data.objects.SearchObject
import yos.music.player.data.repositories.KugouArtistBrief
import yos.music.player.data.repositories.KugouLyricSearchResult
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouPlaylistBrief
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.KugouSearchSong
import yos.music.player.ui.UI
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.navigation.NavGuard
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
 * 本页能力（对照上游 md3Music search_page.dart）：
 * - 空态区：搜索历史（MMKV 持久化，去重头插上限 10，可一键清空）+ 热搜榜 Top15
 * - 输入联想：onValueChange 250ms 防抖拉 /search/suggest，点击词条即搜
 * - 多类型结果 Tab：歌曲 / 歌手 / 专辑 / 歌单 / 歌词，按类型缓存（切 Tab 不重搜）
 *   - 歌手 → ArtistDetail（带 singerId 直进，免按名解析）；专辑 → OnlineAlbumObject + OnlineAlbumDetail；歌单 → PlaylistSelection(gcid) → OnlinePlaylistDetail
 *   - 歌词结果副标题展示命中片段，点击同歌曲整列表播放
 * - 歌曲/专辑/歌单/歌词 Tab 滚动分页（每页 20，滚动到底前自动加载，空页/去重后无新增即到底；
 *   /search/artist 上游无分页参数，歌手 Tab 一次性全量展示）
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
    // 非歌曲分页 Tab 的滚动分页状态（同歌曲 Tab：页码独立计数 + 去重判定到底）
    val albumNextPage = remember("SearchPage_albumNextPage") { mutableStateOf(2) }
    val albumEndReached = remember("SearchPage_albumEndReached") { mutableStateOf(false) }
    val playlistNextPage = remember("SearchPage_playlistNextPage") { mutableStateOf(2) }
    val playlistEndReached = remember("SearchPage_playlistEndReached") { mutableStateOf(false) }
    val lyricNextPage = remember("SearchPage_lyricNextPage") { mutableStateOf(2) }
    val lyricEndReached = remember("SearchPage_lyricEndReached") { mutableStateOf(false) }
    val listState = rememberLazyListState()
    // 翻页重叠已按 hash 去重，但歌词匹配等结果仍可能重复，item key 按出现序号唯一化兜底。
    // items 与 keys 必须派生自同一次状态读取：LazyColumn 内容块有独立重组作用域且
    // key lambda 延迟到测量期才执行，若两处分别读 results.value，内容块可能拿到
    // 新列表而 keys 还是上一代空表（SearchPage.kt:463 OOB 崩溃根因）
    val songItems = results.value
    val resultKeys = remember(songItems) { lazyItemKeys(songItems) { it.hash } }
    val lyricItems = SearchObject.lyricResults.value
    val lyricKeys = remember(lyricItems) { lazyItemKeys(lyricItems) { it.song.hash } }
    val artistItems = SearchObject.artistResults.value
    val artistKeys = remember(artistItems) { lazyItemKeys(artistItems) { it.singerId } }

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
    val requestFailedText = stringResource(id = R.string.search_request_failed)
    val keyboardController = LocalSoftwareKeyboardController.current

    // 统一搜索流程：重置全部类型缓存 → 搜歌曲页 1 → 记录历史
    fun doSearch() {
        val keyword = searchText.value.trim()
        if (keyword.isEmpty() || busy.value) return
        busy.value = true
        status.value = "loading"
        songEndReached.value = false
        songNextPage.value = 2
        albumEndReached.value = false
        albumNextPage.value = 2
        playlistEndReached.value = false
        playlistNextPage.value = 2
        lyricEndReached.value = false
        lyricNextPage.value = 2
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
            SearchObject.TYPE_ARTIST -> SearchObject.artistResults.value.isNotEmpty()
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
                SearchObject.TYPE_ARTIST -> KugouRepository.searchArtists(keyword)
                    .onSuccess { SearchObject.artistResults.value = it }
                    .map { it.size }
                    .getOrElse { -1 }

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
                count < 0 -> "error:$requestFailedText"
                count == 0 -> "empty"
                else -> "ok"
            }
            // 首页即空：该类型没有更多，防滚动分页空转
            if (count == 0) when (type) {
                SearchObject.TYPE_SONG -> songEndReached.value = true
                SearchObject.TYPE_ALBUM -> albumEndReached.value = true
                SearchObject.TYPE_SPECIAL -> playlistEndReached.value = true
                SearchObject.TYPE_LYRIC -> lyricEndReached.value = true
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

    /**
     * 非歌曲分页 Tab（专辑/歌单/歌词）的通用滚动分页：拉取 [nextPage] 页并按
     * [keyOf] 去重追加，去重后无新增置 [endReached]；失败保持页码待下次滚动重试。
     */
    fun <T> loadMorePage(
        keyword: String,
        fetch: suspend (Int, Int) -> Result<List<T>>,
        listState: MutableState<List<T>>,
        nextPage: MutableState<Int>,
        endReached: MutableState<Boolean>,
        keyOf: (T) -> String
    ) {
        if (busy.value || endReached.value || listState.value.isEmpty()) return
        busy.value = true
        val page = nextPage.value
        scope.launch {
            fetch(page, PAGE_SIZE)
                .onSuccess { more ->
                    val existing = listState.value.mapTo(HashSet()) { keyOf(it) }
                    val fresh = more.filter { keyOf(it) !in existing }
                    if (fresh.isEmpty()) {
                        endReached.value = true
                    } else {
                        listState.value = listState.value + fresh
                        nextPage.value = page + 1
                    }
                }
            busy.value = false
        }
    }

    /** 按当前结果 Tab 分发的滚动加载入口（滚动到底自动触发）。 */
    fun loadMore() {
        val keyword = SearchObject.lastSearched.value ?: return
        when (SearchObject.currentType.value) {
            SearchObject.TYPE_SONG -> loadMoreSongs()
            SearchObject.TYPE_ALBUM -> loadMorePage(
                keyword,
                { page, size -> KugouRepository.searchAlbums(keyword, page, size) },
                SearchObject.albumResults, albumNextPage, albumEndReached
            ) { it.albumId }

            SearchObject.TYPE_SPECIAL -> loadMorePage(
                keyword,
                { page, size -> KugouRepository.searchSpecials(keyword, page, size) },
                SearchObject.playlistResults, playlistNextPage, playlistEndReached
            ) { it.globalCollectionId?.toString() ?: it.specialId.toString() }

            SearchObject.TYPE_LYRIC -> loadMorePage(
                keyword,
                { page, size -> KugouRepository.searchLyricSongs(keyword, page, size) },
                SearchObject.lyricResults, lyricNextPage, lyricEndReached
            ) { it.song.hash }
        }
    }

    // 滚动到底自动加载（距底部 ≤6 项触发），同 NewAlbumsDetail/RecommendPlaylistsDetail 模式
    LaunchedEffect(listState) {
        snapshotFlow {
            val info = listState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
            info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 6
        }.distinctUntilChanged().collect { nearBottom ->
            if (nearBottom) loadMore()
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
                            SearchObject.TYPE_ARTIST to R.string.search_type_artist,
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
                    SearchObject.TYPE_ARTIST -> itemsIndexed(
                        artistItems,
                        key = { index, _ -> "ar:${artistKeys.getOrElse(index) { "oob_$index" }}" }
                    ) { index, artist ->
                        ArtistResultRow(artist) {
                            // 带 singerId 直进详情，跳过 ArtistDetail 内的按名解析
                            NavGuard.run {
                                navController.navigate(UI.artistDetailRoute(artist.singerId, artist.name))
                            }
                        }
                        if (index < artistItems.size - 1) {
                            OnlineListItemDivider()
                        }
                    }

                    SearchObject.TYPE_ALBUM -> item("albums") {
                        AlbumGrid(
                            albums = SearchObject.albumResults.value,
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope
                        ) { album ->
                            NavGuard.run {
                                OnlineAlbumObject.setSelected(album)
                                navController.navigate(UI.onlineAlbumRoute(album.albumId))
                            }
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
                        lyricItems,
                        key = { index, _ -> "ly:${lyricKeys.getOrElse(index) { "oob_$index" }}" }
                    ) { index, item ->
                        LyricResultRow(item) { playLyricSong(item) }
                        if (index < lyricItems.size - 1) {
                            OnlineListItemDivider()
                        }
                    }

                    else -> {
                        itemsIndexed(
                            songItems,
                            key = { index, _ -> resultKeys.getOrElse(index) { "oob_$index" } }
                        ) { index, song ->
                            MusicList(KugouRepository.toDisplayMediaItem(song)) {
                                playSong(song)
                            }
                            if (index < songItems.size - 1) {
                                OnlineListItemDivider()
                            }
                        }
                    }
                }

                // 滚动分页：距底自动加载，加载中在尾部显示小指示器（歌手 Tab 无分页不显示）
                val paginatedListNotEmpty = when (SearchObject.currentType.value) {
                    SearchObject.TYPE_SONG -> songItems.isNotEmpty()
                    SearchObject.TYPE_ALBUM -> SearchObject.albumResults.value.isNotEmpty()
                    SearchObject.TYPE_SPECIAL -> SearchObject.playlistResults.value.isNotEmpty()
                    SearchObject.TYPE_LYRIC -> lyricItems.isNotEmpty()
                    else -> false
                }
                if (busy.value && paginatedListNotEmpty) {
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

/** 歌手结果行：48dp 圆形头像 + 歌手名（头像缺失走人形占位图）。
 * 圆形裁切照抄 OnlineArtists.OnlineArtistItem 的已验证画法——勿用 ShadowImageWithCache
 * 加 cornerRadius=半边长 凑圆：YosRoundedCornerShape 满圆角几何退化（同 31/62 胶囊坑），
 * 且其内置 12px 灰描边在小圆上比例过大。
 * 头像兜底：内置 Rust /search/artist 实测只回 singerid/singername 无头像字段，
 * 行内经 [ArtistPresentationCache.loadDetail]（空 accountId，不污染真实账号关注态）
 * 补拉 /artist/detail 头像，顺带预热详情页缓存。 */
@Composable
private fun ArtistResultRow(artist: KugouArtistBrief, onClick: () -> Unit) {
    val density = LocalDensity.current
    val avatarUrl = remember(artist.singerId) { mutableStateOf(artist.avatarUrl) }
    LaunchedEffect(artist.singerId) {
        if (avatarUrl.value.isNullOrBlank()) {
            runCatching {
                ArtistPresentationCache.loadDetail(artist.singerId, artist.name, "")
            }.onSuccess { detail ->
                if (avatarUrl.value.isNullOrBlank() && !detail.avatarUrl.isNullOrBlank()) {
                    avatarUrl.value = detail.avatarUrl
                }
            }
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 22.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(avatarUrl.value)
                .crossfade(true)
                .error(R.drawable.songcredits_monogram_person)
                .placeholder(R.drawable.songcredits_monogram_person)
                .fallback(R.drawable.songcredits_monogram_person)
                .allowHardware(true)
                .precision(Precision.INEXACT)
                .size(128)
                .build(),
            contentDescription = artist.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    clip = true
                    shape = CircleShape
                }
                .drawWithCache {
                    onDrawWithContent {
                        drawContent()
                        val outline = CircleShape.createOutline(
                            Size(size.width, size.height),
                            LayoutDirection.Ltr,
                            density
                        )
                        drawOutline(
                            outline = outline,
                            color = Color.DarkGray.copy(alpha = 0.08f),
                            style = Stroke(width = 6f)
                        )
                        drawOutline(
                            outline = outline,
                            color = Color.DarkGray.copy(alpha = 0.4f),
                            style = Stroke(width = 6f),
                            blendMode = BlendMode.Overlay
                        )
                    }
                }
        )
        Spacer(Modifier.width(14.dp))
        Text(
            text = artist.name,
            fontSize = 16.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}
