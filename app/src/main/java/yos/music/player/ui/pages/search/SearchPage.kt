package yos.music.player.ui.pages.search

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Search
import yos.music.player.ui.widgets.mochi.LocalMochiZoneLaunchState
import yos.music.player.ui.widgets.mochi.MochiActivityIndicator
import yos.music.player.ui.widgets.mochi.MochiZoneLaunchTarget
import yos.music.player.ui.widgets.mochi.mochiPressDeform
import yos.music.player.ui.widgets.mochi.mochiZoneSource
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.size.Precision
import com.kyant.capsule.ContinuousRoundedRectangle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
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
import yos.music.player.data.repositories.KugouIpZone
import yos.music.player.data.repositories.KugouLyricSearchResult
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouPlaylistBrief
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.KugouSearchSong
import yos.music.player.ui.UI
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.navigation.preloadAlbumBackdropColor
import yos.music.player.ui.navigation.preloadPlaylistBackdropColor
import yos.music.player.ui.navigation.rememberZoneCardTintColor
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.AppleSearchField
import yos.music.player.ui.widgets.basic.DiscoveryMinImageSize
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.SearchCancelButton
import yos.music.player.ui.widgets.basic.SearchFieldRow
import yos.music.player.ui.widgets.basic.SearchMorphSpec
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.SongAddToPlaylistButton
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.calculateAdaptiveImageLayout
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
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = SearchObject.browseScrollIndex,
        initialFirstVisibleItemScrollOffset = SearchObject.browseScrollOffset
    )
    // 翻页重叠已按 hash 去重，但歌词匹配等结果仍可能重复，item key 按出现序号唯一化兜底。
    // items 与 keys 必须派生自同一次状态读取：LazyColumn 内容块有独立重组作用域且
    // key lambda 延迟到测量期才执行，若两处分别读 results.value，内容块可能拿到
    // 新列表而 keys 还是上一代空表（SearchPage.kt:463 OOB 崩溃根因）
    //
    // 歌曲 Tab 顶部有「最佳结果」大卡复用首曲，故列表展示时砍掉首条。keys 必须
    // 同步 drop：只砍 items 不砍 keys 会让每行拿到上一首的 key（身份错位），且卡片
    // 若用首曲 hash 做 key 会与移位后的行 key 撞车 → LazyColumn 抛 Key already used。
    // 注意 keys 要从全量算完再 drop（lazyItemKeys(all).drop(1)），不能对 drop 后的
    // 列表重算 —— 后者会重置重复计数，重复曲目重新拿到裸 hash 再次撞键。
    val songItemsAll = results.value
    val allSongKeys = remember(songItemsAll) { lazyItemKeys(songItemsAll) { it.hash } }
    val topSong = songItemsAll.firstOrNull()
    val songItems = if (topSong != null) songItemsAll.drop(1) else songItemsAll
    val resultKeys = if (topSong != null) allSongKeys.drop(1) else allSongKeys
    val lyricItems = SearchObject.lyricResults.value
    val lyricKeys = remember(lyricItems) { lazyItemKeys(lyricItems) { it.song.hash } }
    val artistItems = SearchObject.artistResults.value
    val artistKeys = remember(artistItems) { lazyItemKeys(artistItems) { it.singerId } }
    val zones = SearchObject.ipZones.value
    val zonesLoading = SearchObject.ipZonesLoading.value
    val zonesError = SearchObject.ipZonesError.value
    val zoneColumns = rememberZoneColumns()
    val zoneRows = remember(zones, zoneColumns) { zones.chunked(zoneColumns) }

    val scope = rememberCoroutineScope()
    val zoneLaunchState = LocalMochiZoneLaunchState.current

    fun loadIpZones(force: Boolean = false) {
        if (SearchObject.ipZonesLoading.value) return
        if (!force && SearchObject.ipZones.value.isNotEmpty()) return
        SearchObject.ipZonesLoading.value = true
        SearchObject.ipZonesError.value = null
        scope.launch {
            KugouRepository.getIpZones()
                .onSuccess { zones ->
                    SearchObject.ipZones.value = zones
                    SearchObject.ipZonesError.value = null
                }
                .onFailure { e ->
                    SearchObject.ipZonesError.value = e.message ?: e.javaClass.simpleName
                }
            SearchObject.ipZonesLoading.value = false
        }
    }

    // 上次会话残留的 loading 态自愈 + 编辑精选专区 & 热搜榜预取（空态区展示，进程内缓存）
    LaunchedEffect(Unit) {
        if (status.value == "loading" && !busy.value) {
            status.value = if (results.value.isEmpty()) "idle" else "ok"
        }
        loadIpZones()
        if (SearchObject.hotWords.value.isEmpty()) {
            // /search/hot 返回的是榜单分类词（热搜榜/飙升榜/DJ榜…），不是歌曲热词；
            // 接口无排名字段，序号由列表顺序派生，不做假数据
            KugouRepository.getHotSearch().onSuccess {
                SearchObject.hotWords.value = it.take(HOT_LIST_LIMIT)
            }
        }
        // 仅在首次无滚动记录时置顶；从专区详情返回时保留原有滚动位置，使 Mochi 缩回落点对齐
        if (searchText.value.isEmpty() && !SearchObject.searchActive.value &&
            SearchObject.browseScrollIndex == 0 && SearchObject.browseScrollOffset == 0
        ) {
            listState.scrollToItem(0)
        }
    }

    // 浏览态下实时记住滚动位置
    LaunchedEffect(listState) {
        snapshotFlow {
            listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset
        }.collect { (idx, offset) ->
            if (searchText.value.isEmpty() && !SearchObject.searchActive.value) {
                SearchObject.browseScrollIndex = idx
                SearchObject.browseScrollOffset = offset
            }
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
    val emptyText = stringResource(id = R.string.search_no_result)
    val requestFailedText = stringResource(id = R.string.search_request_failed)
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    // 统一搜索流程：重置全部类型缓存 → 搜歌曲页 1 → 记录历史
    fun doSearch() {
        val keyword = searchText.value.trim()
        if (keyword.isEmpty() || busy.value) return
        SearchObject.searchActive.value = true
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
            ) { it.globalCollectionId ?: it.specialId.toString() }

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
        topRightIcon = null,
        onTopRightIcon = null,
        rightBarIcon = {
            TitleBarIcon(
                icon = Icons.Filled.MoreVert,
                onBack = openSettings
            )
        },
        extraTopPadding = 37.dp,
        listState = listState
    ) {
        item("SearchField") {
            // 形变进度：0 = 浏览态，1 = 搜索激活态。单一动画时钟驱动整行，
            // 曲线与在线歌单页同一套 spring(1.2/800)，观感是"推出去后自然停住"
            val morph = remember("SearchPage_morph") { Animatable(0f) }
            val active = SearchObject.searchActive.value
            LaunchedEffect(active) {
                morph.animateTo(if (active) 1f else 0f, SearchMorphSpec)
            }

            // 搜索行：搜索框与「取消」的宽度由 SearchFieldRow 在同一次布局里手工分配。
            // 不用 Row + weight/fillMaxWidth —— 那会让两个兄弟节点争抢约束，
            // 表现为搜索框宽度算错被挤到中间、取消按钮分到 0 宽却仍可点击。
            SearchFieldRow(
                fraction = morph.value,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp)
                    .padding(top = 5.dp),
                field = {
                    AppleSearchField(
                        text = searchText.value,
                        placeholder = stringResource(id = R.string.page_search_title),
                        onValueChange = { searchText.value = it },
                        onSearch = {
                            if (searchText.value.isNotEmpty()) {
                                keyboardController?.hide()
                                doSearch()
                            }
                        },
                        // 点框内清除钮：只清文字，保持激活态（回到最近搜索，与 Apple Music 一致）
                        onClear = {
                            searchText.value = ""
                            SearchObject.suggestions.value = emptyList()
                        },
                        modifier = Modifier.fillMaxSize(),
                        fraction = morph.value,
                        onFocusChanged = { focused ->
                            // 只在获得焦点时置 true；丢失焦点不置 false，否则滚动导致
                            // 输入框滚出屏幕被销毁时会误收起历史列表
                            if (focused) SearchObject.searchActive.value = true
                        }
                    )
                },
                cancel = {
                    SearchCancelButton(
                        fraction = morph.value,
                        onClick = {
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            searchText.value = ""
                            SearchObject.suggestions.value = emptyList()
                            SearchObject.searchActive.value = false
                        }
                    )
                }
            )
        }

        val keyword = searchText.value.trim()
        val showSuggest = keyword.isNotEmpty() &&
            keyword != SearchObject.lastSearched.value &&
            SearchObject.suggestions.value.isNotEmpty()

        when {
            // 浏览态：胶囊搜索框下方直接按行虚拟化铺编辑精选专区双列大卡
            keyword.isEmpty() && !SearchObject.searchActive.value -> {
                item("browse_top_spacer") {
                    Spacer(Modifier.height(12.dp))
                }
                when {
                    zoneRows.isNotEmpty() -> {
                        itemsIndexed(
                            items = zoneRows,
                            key = { _, row -> "zone_row:${row.first().id}" },
                            contentType = { _, _ -> "zone_row" }
                        ) { rowIndex, rowZones ->
                            IpZoneRow(
                                rowZones = rowZones,
                                columns = zoneColumns,
                                bottomSpacing = if (rowIndex < zoneRows.lastIndex) 12.dp else 0.dp,
                                onOpen = { zone ->
                                    keyboardController?.hide()
                                    focusManager.clearFocus()
                                    if (zoneLaunchState != null) {
                                        zoneLaunchState.open(
                                            MochiZoneLaunchTarget(
                                                zoneId = zone.id,
                                                ipId = zone.ipId,
                                                name = zone.name,
                                                coverUrl = zone.iconUrl.orEmpty()
                                            )
                                        )
                                    } else {
                                        NavGuard.run {
                                            navController.navigate(
                                                UI.ipZoneDetailRoute(
                                                    zoneId = zone.id,
                                                    ipId = zone.ipId,
                                                    name = zone.name,
                                                    cover = zone.iconUrl.orEmpty()
                                                )
                                            )
                                        }
                                    }
                                }
                            )
                        }
                    }

                    zonesLoading -> {
                        item("browse_zones_loading") {
                            IpZoneLoadingGrid()
                        }
                    }

                    zonesError != null -> {
                        item("browse_zones_error") {
                            IpZoneErrorState(
                                onRetry = { loadIpZones(force = true) }
                            )
                        }
                    }
                }
            }

            // 搜索激活但关键词为空：最近搜索 + 热搜榜
            keyword.isEmpty() -> {
                item("recent_header") {
                    // 与浏览态共用 SectionHeaderRow：两侧行高一致，标题基线才不会漂移
                    SectionHeaderRow(
                        title = stringResource(id = R.string.search_recent),
                        action = if (SearchObject.history.value.isNotEmpty()) {
                            {
                                Text(
                                    text = stringResource(id = R.string.search_clear_history),
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        // 纯文字按钮：不要涟漪（与「取消」同一套语汇）
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { SearchObject.clearHistory() }
                                        .padding(horizontal = 8.dp, vertical = 11.dp)
                                )
                            }
                        } else null
                    )
                }
                val historyWords = SearchObject.history.value
                if (historyWords.isEmpty()) {
                    item("recent_empty") {
                        Text(
                            text = stringResource(id = R.string.search_recent_empty),
                            fontSize = 14.sp,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 22.dp, vertical = 28.dp)
                        )
                    }
                } else {
                    itemsIndexed(
                        historyWords,
                        key = { _, w -> "recent:$w" },
                        contentType = { _, _ -> "search_history" }
                    ) { index, word ->
                        RecentSearchRow(
                            word = word,
                            onWordClick = {
                                searchText.value = word
                                doSearch()
                            },
                            onDelete = { SearchObject.removeHistory(index) }
                        )
                        if (index < historyWords.size - 1) OnlineListItemDivider()
                    }
                }
                if (SearchObject.hotWords.value.isNotEmpty()) {
                    item("hot_header") {
                        SectionHeader(stringResource(id = R.string.search_hot))
                    }
                    item("hot_list") {
                        RankedWordList(
                            words = SearchObject.hotWords.value.take(HOT_LIST_LIMIT),
                            onWordClick = { word ->
                                searchText.value = word
                                doSearch()
                            }
                        )
                    }
                }
            }

            // 输入联想区
            showSuggest -> {
                itemsIndexed(
                    SearchObject.suggestions.value,
                    key = { _, w -> "sg:$w" },
                    contentType = { _, _ -> "search_suggest" }
                ) { _, word ->
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
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable { ensureTypeLoaded(type) }
                                    .padding(vertical = 6.dp)
                            ) {
                                Text(
                                    text = stringResource(id = labelRes),
                                    fontSize = 15.5.sp,
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (selected) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                                )
                                // 选中态底部指示条：只用变色在 5 个等宽 Tab 里不够明确
                                Spacer(Modifier.height(3.dp))
                                Box(
                                    Modifier
                                        .height(2.dp)
                                        .width(if (selected) 18.dp else 0.dp)
                                        .background(
                                            MaterialTheme.colorScheme.primary,
                                            RoundedCornerShape(1.dp)
                                        )
                                )
                            }
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
                        key = { index, _ -> "ar:${artistKeys.getOrElse(index) { "oob_$index" }}" },
                        contentType = { _, _ -> "search_artist" }
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
                                id = playlist.globalCollectionId
                                    ?: playlist.specialId.toString(),
                                sourceGid = playlist.globalCollectionId,
                                name = playlist.name,
                                cover = playlist.cover ?: "",
                                songCount = playlist.songCount
                            )
                            navController.navigate(selection.toRoute())
                        }
                    }

                    SearchObject.TYPE_LYRIC -> itemsIndexed(
                        lyricItems,
                        key = { index, _ -> "ly:${lyricKeys.getOrElse(index) { "oob_$index" }}" },
                        contentType = { _, _ -> "search_lyric" }
                    ) { index, item ->
                        LyricResultRow(item) { playLyricSong(item) }
                        if (index < lyricItems.size - 1) {
                            OnlineListItemDivider()
                        }
                    }

                    // 显式写 TYPE_SONG 而不是靠 else 兜底：否则将来新增结果类型会
                    // 静默继承「最佳结果」卡与歌曲列表
                    SearchObject.TYPE_SONG -> {
                        if (topSong != null && status.value == "ok") {
                            item("top_result") {
                                TopResultCard(topSong) { playSong(topSong) }
                            }
                            if (songItems.isNotEmpty()) {
                                item("songs_header") {
                                    SectionHeader(stringResource(id = R.string.search_type_song))
                                }
                            }
                        }
                        itemsIndexed(
                            songItems,
                            key = { index, _ -> resultKeys.getOrElse(index) { "oob_$index" } },
                            contentType = { _, _ -> "search_song" }
                        ) { index, song ->
                            MusicList(KugouRepository.toDisplayMediaItem(song)) {
                                playSong(song)
                            }
                            if (index < songItems.size - 1) {
                                OnlineListItemDivider()
                            }
                        }
                    }

                    else -> {}
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
                            MochiActivityIndicator(size = 20.dp)
                        }
                    }
                }
            }
        }
    }
}

private const val PAGE_SIZE = 20

/** 热搜榜展示条数。接口无分页参数，重请求返回同一批，故不做「换一批」。 */
private const val HOT_LIST_LIMIT = 10

/**
 * 分区标题行（浏览分类 / 热搜榜 / 最近搜索 / 歌曲）。
 *
 * 固定 44dp 行高、标题垂直居中：浏览态与最近搜索态共用同一行高，标题基线才不会
 * 因为右侧有没有动作按钮而上下漂移（此前最近搜索带「清除」按钮，整行被撑高，
 * 导致「浏览分类」看起来比「最近搜索」偏上）。
 *
 * [action] 为右侧动作按钮（如「清除」），热区自身撑满 44dp，不会因行高收敛而变小。
 */
@Composable
private fun SectionHeaderRow(title: String, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .padding(start = 22.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            modifier = Modifier.weight(1f)
        )
        action?.invoke()
    }
}

/** 无动作按钮的分区标题（热搜榜 / 歌曲）。 */
@Composable
internal fun SectionHeader(title: String) = SectionHeaderRow(title)

private val ZONE_FALLBACK_PALETTES = listOf(
    Color(0xFF607876),
    Color(0xFFAE6825),
    Color(0xFF9E5BA7),
    Color(0xFF667387),
    Color(0xFFCC1A10),
    Color(0xFF1D800E),
    Color(0xFF7B73A4),
    Color(0xFF9C6949)
)

internal val ZoneCardCornerRadius = 18.dp
internal val ZoneCardShape = ContinuousRoundedRectangle(ZoneCardCornerRadius)

private fun zoneFallbackColor(zoneId: Long): Color {
    val index = ((zoneId % ZONE_FALLBACK_PALETTES.size) + ZONE_FALLBACK_PALETTES.size) % ZONE_FALLBACK_PALETTES.size
    return ZONE_FALLBACK_PALETTES[index.toInt()]
}

@Composable
private fun rememberZoneColumns(): Int {
    val widthDp = LocalConfiguration.current.screenWidthDp
    return when {
        widthDp >= 840 -> 4
        widthDp >= 600 -> 3
        else -> 2
    }
}

/**
 * 浏览态编辑精选专区单行（供 `LazyColumn` 虚拟化按行复用，避免一次组合全部 48 张卡片）：
 * 左右 18.dp 边距与顶部搜索框对齐，采用 G2 平滑圆角、左色渐变与左下角小字标题。
 */
@Composable
private fun IpZoneRow(
    rowZones: List<KugouIpZone>,
    columns: Int,
    bottomSpacing: androidx.compose.ui.unit.Dp,
    onOpen: (KugouIpZone) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, bottom = bottomSpacing),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        rowZones.forEach { zone ->
            IpZoneCard(
                zone = zone,
                modifier = Modifier.weight(1f),
                onClick = { onOpen(zone) }
            )
        }
        repeat(columns - rowZones.size) {
            Spacer(Modifier.weight(1f))
        }
    }
}

/**
 * 专区卡片共享画面组件：左色渐变 + 右侧裁掉原图底部白字水印的配图 + 左下角小字标题。
 * 搜索页网格与 Mochi 容器变形转场预览层共用同一套绘制。
 */
@Composable
internal fun IpZoneCardArtwork(
    name: String,
    iconUrl: String?,
    tintColor: Color,
    modifier: Modifier = Modifier,
    enableCrossfade: Boolean = true
) {
    val context = LocalContext.current
    Box(
        modifier = modifier.background(tintColor)
    ) {
        if (!iconUrl.isNullOrBlank()) {
            // 右置 84% 宽 + 顶部对齐裁切（1.68f 比例，只露原图顶部 190px < 203px），
            // 彻底裁掉酷狗原图底部白字，同时让主体自然落在右侧，与左侧纯色渐变融合
            AsyncImage(
                model = remember(iconUrl, enableCrossfade) {
                    ImageRequest.Builder(context)
                        .data(iconUrl)
                        .memoryCacheKey("ip-zone-icon:$iconUrl")
                        .placeholderMemoryCacheKey("ip-zone-icon:$iconUrl")
                        .crossfade(enableCrossfade)
                        .allowHardware(true)
                        .precision(Precision.INEXACT)
                        .size(360, 220)
                        .build()
                },
                contentDescription = name,
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter,
                modifier = Modifier
                    .fillMaxWidth(0.84f)
                    .fillMaxHeight()
                    .align(Alignment.CenterEnd)
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        0.00f to tintColor,
                        0.32f to tintColor,
                        0.48f to tintColor.copy(alpha = 0.82f),
                        0.66f to tintColor.copy(alpha = 0.34f),
                        0.84f to Color.Transparent,
                        1.00f to Color.Transparent
                    )
                )
        )
        Text(
            text = name,
            fontSize = 13.5.sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 13.dp, bottom = 10.dp, end = 12.dp)
        )
    }
}

@Composable
private fun IpZoneCard(
    zone: KugouIpZone,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val fallback = remember(zone.id) { zoneFallbackColor(zone.id) }
    val tintColor = rememberZoneCardTintColor(
        zoneId = zone.id,
        iconUrl = zone.iconUrl,
        fallback = fallback
    )

    Box(
        modifier = modifier
            .aspectRatio(2.0f)
            .mochiZoneSource(
                zoneId = zone.id,
                cornerRadius = ZoneCardCornerRadius,
                color = tintColor
            )
            .mochiPressDeform()
            .clip(ZoneCardShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
    ) {
        IpZoneCardArtwork(
            name = zone.name,
            iconUrl = zone.iconUrl,
            tintColor = tintColor,
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
private fun IpZoneLoadingGrid() {
    val columns = rememberZoneColumns()
    val placeholderColor = (Color.LightGray withNight Color.DarkGray).copy(alpha = 0.22f)
    val rowCount = 4
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        repeat(rowCount) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .aspectRatio(2.0f)
                            .clip(ZoneCardShape)
                            .background(placeholderColor)
                    )
                }
            }
        }
    }
}

@Composable
private fun IpZoneErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = stringResource(id = R.string.search_zone_load_failed),
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(id = R.string.search_zone_retry),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onRetry)
                .padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

/** 热搜榜：带序号的列表，1–3 名序号用主色。序号由列表顺序派生（接口无排名字段）。 */
@Composable
private fun RankedWordList(words: List<String>, onWordClick: (String) -> Unit) {
    Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
        words.forEachIndexed { index, word ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable { onWordClick(word) }
                    .padding(horizontal = 4.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${index + 1}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (index < 3) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.width(26.dp)
                )
                Text(
                    text = word,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }
            if (index < words.size - 1) {
                OnlineListItemDivider(startPadding = 30.dp)
            }
        }
    }
}

/** 最近搜索的一行：时钟图标 + 词条 + 单条删除。 */
@Composable
private fun RecentSearchRow(
    word: String,
    onWordClick: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Outlined.Schedule,
            contentDescription = null,
            modifier = Modifier.size(16.dp).alpha(0.45f)
        )
        Text(
            text = word,
            fontSize = 16.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(8.dp))
                .clickable { onWordClick() }
                .padding(start = 12.dp, top = 14.dp, bottom = 14.dp)
        )
        Box(
            Modifier
                .size(30.dp)
                .clip(CircleShape)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(id = R.drawable.ic_search_clear),
                contentDescription = stringResource(id = R.string.search_clear_history),
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
            )
        }
    }
}

/**
 * 结果页「最佳结果」大卡。列表里已 drop 掉首曲，故卡片不会与列表重复。
 */
@Composable
private fun TopResultCard(song: KugouSearchSong, onClick: () -> Unit) {
    val display = KugouRepository.toDisplayMediaItem(song)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 18.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            .clickable { onClick() }
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ShadowImageWithCache(
            dataLambda = { display.thumb },
            contentDescription = song.name,
            modifier = Modifier.size(72.dp),
            cornerRadius = 8.dp,
            shadowAlpha = 0f,
            // 72dp = 234 物理像素。LOW 只解码 128px（≈1.8× 放大，边界可见发虚），
            // HIGH 解码 400px 与源图分辨率相当，且不额外占流量（URL 档位本来就是 400）。
            imageQuality = ImageQuality.HIGH,
            matchDisplaySize = true
        )
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(
                text = stringResource(id = R.string.search_top_result),
                fontSize = 11.5.sp,
                // 标签降一档：让下方 18sp Bold 的歌名成为唯一视觉重心
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
            Text(
                text = song.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
            Text(
                text = song.author,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        SongAddToPlaylistButton(music = display, onPlay = onClick)
    }
}

/**
 * 搜索结果封面网格的自适应容器（手机 2 列，平板/宽屏按 [DiscoveryMinImageSize] 自动扩展多列）。
 *
 * 外层左右留白 14dp + 单卡内边距 8dp = 封面外缘 22dp（与页头及 Tab 对齐）、卡间封面间距 16dp，
 * 因此以 `startPadding = 22.dp, endPadding = 22.dp, spacing = 16.dp` 计算列数。
 */
@Composable
internal fun <T> SearchAdaptiveGrid(
    items: List<T>,
    itemContent: @Composable (item: T, modifier: Modifier) -> Unit
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val columns = calculateAdaptiveImageLayout(
            containerWidth = maxWidth,
            startPadding = 22.dp,
            endPadding = 22.dp,
            minImageSize = DiscoveryMinImageSize,
            spacing = 16.dp
        ).itemCount.coerceAtLeast(2)
        val rows = remember(items, columns) { items.chunked(columns) }

        Column(Modifier.padding(horizontal = 14.dp)) {
            rows.forEach { rowItems ->
                Row(Modifier.fillMaxWidth()) {
                    rowItems.forEach { item ->
                        itemContent(item, Modifier.weight(1f))
                    }
                    repeat(columns - rowItems.size) {
                        Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

/** 专辑/歌单自适应网格共用的正方形封面卡片。 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun SearchMediaGridCard(
    coverUrl: String?,
    title: String,
    subtitle: String,
    sharedCoverKey: String,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
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
                            sharedContentState = rememberSharedContentState(key = sharedCoverKey),
                            animatedVisibilityScope = animatedVisibilityScope
                        )
                    }
                } else Modifier
            )
        Box(coverModifier) {
            ShadowImageWithCache(
                dataLambda = { coverUrl },
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 8.dp,
                shadowAlpha = 0f,
                // 两列及多列网格的封面在手机/平板端实际像素约 400-550px。LOW 只解码 128px，
                // 等于把图硬放大 4 倍以上——这是"搜索里专辑/歌单封面特别糊"的根因。
                // HIGH 解码 400px 正好等于源图分辨率（URL 档位本就是 400），且不增加流量。
                imageQuality = ImageQuality.HIGH,
                matchDisplaySize = true
            )
        }
        Text(
            text = title,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp)
        )
        Text(
            text = subtitle,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .padding(top = 2.dp)
                .alpha(0.5f)
        )
    }
}

/** 专辑结果自适应网格（手机 2 列，平板/宽屏自动多列）。 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun AlbumGrid(
    albums: List<KugouNewAlbum>,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope?,
    onAlbumClick: (KugouNewAlbum) -> Unit
) {
    val context = LocalContext.current
    val preloadScope = rememberCoroutineScope()
    SearchAdaptiveGrid(albums) { album, itemModifier ->
        LaunchedEffect(album.albumId, album.coverUrl) {
            preloadAlbumBackdropColor(context, album.albumId, album.coverUrl)
        }
        val sharedKey = "album/online/${album.albumId}"
        SearchMediaGridCard(
            coverUrl = album.coverUrl,
            title = album.name,
            subtitle = album.singerName,
            sharedCoverKey = sharedKey,
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = itemModifier,
            onClick = {
                SharedCoverStyle.lastSourceCorner = 8.dp
                // 记录来源 key：详情页据此与本卡封面配对
                OnlineAlbumObject.setSharedCoverKey(album.albumId, sharedKey)
                preloadScope.launch {
                    coroutineScope {
                        launch { preloadRawCover(context, album.coverUrl) }
                        launch { preloadAlbumBackdropColor(context, album.albumId, album.coverUrl) }
                    }
                    onAlbumClick(album)
                }
            }
        )
    }
}

/** 歌单结果自适应网格（手机 2 列，平板/宽屏自动多列）。 */
@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
internal fun PlaylistGrid(
    playlists: List<KugouPlaylistBrief>,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope?,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope?,
    onPlaylistClick: (KugouPlaylistBrief) -> Unit
) {
    val context = LocalContext.current
    val preloadScope = rememberCoroutineScope()
    SearchAdaptiveGrid(playlists) { playlist, itemModifier ->
        val playlistId = playlist.globalCollectionId
            ?: playlist.specialId.toString()
        LaunchedEffect(playlistId, playlist.cover) {
            preloadPlaylistBackdropColor(
                context = context,
                source = PlaylistSelection.Source.Recommend.name,
                playlistId = playlistId,
                coverUrl = playlist.cover
            )
        }
        SearchMediaGridCard(
            coverUrl = playlist.cover,
            title = playlist.name,
            subtitle = if (playlist.songCount > 0) "${playlist.songCount} 首" else "",
            sharedCoverKey = "playlist/Recommend/$playlistId",
            sharedTransitionScope = sharedTransitionScope,
            animatedVisibilityScope = animatedVisibilityScope,
            modifier = itemModifier,
            onClick = {
                SharedCoverStyle.lastSourceCorner = 8.dp
                preloadScope.launch {
                    coroutineScope {
                        launch { preloadRawCover(context, playlist.cover ?: "") }
                        launch {
                            preloadPlaylistBackdropColor(
                                context = context,
                                source = PlaylistSelection.Source.Recommend.name,
                                playlistId = playlistId,
                                coverUrl = playlist.cover
                            )
                        }
                    }
                    onPlaylistClick(playlist)
                }
            }
        )
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
            // 48dp = 156 物理像素，LOW 的 128px 只放 1.22 倍，肉眼无差别；
            // 这里刻意保持 LOW（列表行数量多，提到 400px 只是白占内存）。
            // 网格卡片那种 4 倍放大才必须改。
            imageQuality = ImageQuality.LOW
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
internal fun ArtistResultRow(artist: KugouArtistBrief, onClick: () -> Unit) {
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
            val imgContext = LocalContext.current
        AsyncImage(
            model = remember(avatarUrl.value) {
        ImageRequest.Builder(imgContext)
                        .data(avatarUrl.value)
                        .crossfade(true)
                        .error(R.drawable.songcredits_monogram_person)
                        .placeholder(R.drawable.songcredits_monogram_person)
                        .fallback(R.drawable.songcredits_monogram_person)
                        .allowHardware(true)
                        .precision(Precision.INEXACT)
                        .size(128)
                        .build()
        },
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
                                        val outline = CircleShape.createOutline(
                        Size(size.width, size.height),
                        LayoutDirection.Ltr,
                        density
                    )
                    onDrawWithContent {
                        drawContent()
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
