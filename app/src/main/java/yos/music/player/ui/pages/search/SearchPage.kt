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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
import yos.music.player.data.objects.DiscoveryObject
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
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.AppleSearchField
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.SearchCancelButton
import yos.music.player.ui.widgets.basic.SearchFieldRow
import yos.music.player.ui.widgets.basic.SearchMorphSpec
import yos.music.player.ui.widgets.basic.SharedCoverStyle
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.preloadRawCover
import yos.music.player.ui.widgets.dropdown.LiquidGlassDropdownMenuItem
import yos.music.player.ui.widgets.dropdown.TitleBarGlassMenu

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
    // 右上角「更多」菜单展开态（听歌识曲 / 设置）
    val showMenu = remember("SearchPage_menu") { mutableStateOf(false) }
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

    val scope = rememberCoroutineScope()

    // 上次会话残留的 loading 态自愈 + 热搜榜预取（空态区展示，进程内缓存）
    LaunchedEffect(Unit) {
        if (status.value == "loading" && !busy.value) {
            status.value = if (results.value.isEmpty()) "idle" else "ok"
        }
        if (SearchObject.hotWords.value.isEmpty()) {
            // /search/hot 返回的是榜单分类词（热搜榜/飙升榜/DJ榜…），不是歌曲热词；
            // 接口无排名字段，序号由列表顺序派生，不做假数据
            KugouRepository.getHotSearch().onSuccess {
                SearchObject.hotWords.value = it.take(HOT_LIST_LIMIT)
            }
        }
        // 回到浏览态（无关键词、未激活）时回到顶部：搜索 Tab 切走再切回时
        // composition 重建但 listState 会恢复上次位置，不归位就会停在页面中部
        if (searchText.value.isEmpty() && !SearchObject.searchActive.value) {
            listState.scrollToItem(0)
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
        // 听歌识曲与设置收进右上角「更多」菜单，与主页同一套 TitleBarGlassMenu。
        // 原先是搜索框右侧一个裸麦克风圆钮：既挤占输入宽度，又容易被读成语音输入。
        rightBarIcon = {
            SearchMenuButton(
                expanded = showMenu.value,
                onExpandChange = { showMenu.value = it },
                onSongRecognition = {
                    showMenu.value = false
                    keyboardController?.hide()
                    focusManager.clearFocus()
                    NavGuard.run { navController.toUI(UI.SongRecognition) }
                },
                onSettings = {
                    showMenu.value = false
                    openSettings()
                }
            )
        },
        extraTopPadding = 57.dp,
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
                        placeholder = stringResource(id = R.string.page_online_search_placeholder),
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
            // 空态区：搜索历史 + 热搜榜
            keyword.isEmpty() && !SearchObject.searchActive.value -> {
                item("browse_header") {
                    SectionHeader(stringResource(id = R.string.search_browse_categories))
                }
                item("browse_categories") {
                    CategoryGrid(
                        onOpen = { target ->
                            keyboardController?.hide()
                            focusManager.clearFocus()
                            // 新歌精选读 DiscoveryObject 且自身没有加载逻辑，
                            // 直接进可能停在空骨架；ensureFresh 自带 60s 节流，重复调用零开销
                            if (target == CategoryTarget.NewSongs) DiscoveryObject.ensureFresh()
                            NavGuard.run { navController.toUI(target.route) }
                        }
                    )
                }
                if (SearchObject.hotWords.value.isNotEmpty()) {
                    item("hot_header") {
                        SectionHeader(stringResource(id = R.string.search_hot))
                    }
                    item("hot_list") {
                        // 热搜内容是 /search/hot 返回的榜单分类词（热搜榜/飙升榜/DJ榜…），
                        // 不是歌曲热词；接口无排名字段，序号由列表顺序派生，不造假数据
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

            // 搜索激活但关键词为空：最近搜索（Apple Music 行为，点框才出现）
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
                        key = { _, w -> "recent:$w" }
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
                        key = { index, _ -> "ly:${lyricKeys.getOrElse(index) { "oob_$index" }}" }
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

/** 无动作按钮的分区标题（浏览分类 / 热搜榜 / 歌曲）。 */
@Composable
private fun SectionHeader(title: String) = SectionHeaderRow(title)

/**
 * 右上角「更多」按钮 + 锚定其上的玻璃菜单（听歌识曲 / 设置）。
 *
 * 与主页的 HomeMenuButton 同构，复用 TitleBarGlassMenu 的锚点联动：面板揭示进度
 * 回传后按钮本体随之位移/收缩/淡出再复位。必须渲染在 Title 的 rightBarIcon 槽内
 * —— 该槽是 TitleBar 子树，LocalTitlePageBackdrop / LocalTitleOverlayHost 才可用。
 */
@Composable
private fun SearchMenuButton(
    expanded: Boolean,
    onExpandChange: (Boolean) -> Unit,
    onSongRecognition: () -> Unit,
    onSettings: () -> Unit
) {
    TitleBarGlassMenu(
        expanded = expanded,
        onExpand = { onExpandChange(true) },
        onDismiss = { onExpandChange(false) },
        triggerIcon = Icons.Filled.MoreVert,
        triggerContentDescription = stringResource(id = R.string.search_more),
        modifier = Modifier.padding(end = 10.dp),
    ) {
        LiquidGlassDropdownMenuItem(
            text = stringResource(R.string.song_recognition_title),
            onClick = {
                onExpandChange(false)
                onSongRecognition()
            },
        )
        LiquidGlassDropdownMenuItem(
            text = stringResource(R.string.home_menu_settings),
            onClick = {
                onExpandChange(false)
                onSettings()
            },
        )
    }
}

/**
 * 浏览态的分类砖块目标。只列真实存在、且已在 SearchNavHost 注册的路由，
 * 漏注册即 "destination cannot be found" 崩溃。
 */
private enum class CategoryTarget(val route: String, val labelRes: Int) {
    NewSongs(UI.NewSongsDetail, R.string.discovery_new_songs_title),
    RecommendPlaylists(UI.RecommendPlaylistsDetail, R.string.discovery_recommend_playlists_title),
    RankList(UI.RankListDetail, R.string.discovery_rank_title),
    NewAlbums(UI.NewAlbumsDetail, R.string.discovery_new_albums_title),
    Everyday(UI.EverydayRecommendDetail, R.string.home_everyday_recommend_title),
    PersonalFm(UI.PersonalFmDetail, R.string.home_personal_fm_title),
    Gallery(UI.MusicGallery, R.string.page_music_gallery_title),
    Recognition(UI.SongRecognition, R.string.song_recognition_title)
}

/** 每块砖的渐变底 + 右下角旋转浅色方块（不用网络封面，避免依赖 Discovery 时序）。 */
private val CategoryTarget.topColor: Color
    get() = when (this) {
        CategoryTarget.NewSongs -> Color(0xFF5B4CE0)
        CategoryTarget.RecommendPlaylists -> Color(0xFF1E2A5A)
        CategoryTarget.RankList -> Color(0xFFC81E3A)
        CategoryTarget.NewAlbums -> Color(0xFF0E8F6E)
        CategoryTarget.Everyday -> Color(0xFFE0428E)
        CategoryTarget.PersonalFm -> Color(0xFFFF7A3D)
        CategoryTarget.Gallery -> Color(0xFF6F3A86)
        CategoryTarget.Recognition -> Color(0xFF0B6E8A)
    }

private val CategoryTarget.bottomColor: Color
    get() = when (this) {
        CategoryTarget.NewSongs -> Color(0xFF9B5DE5)
        CategoryTarget.RecommendPlaylists -> Color(0xFF3E7CB1)
        CategoryTarget.RankList -> Color(0xFFF0553F)
        CategoryTarget.NewAlbums -> Color(0xFF4CC38A)
        CategoryTarget.Everyday -> Color(0xFFF76F9C)
        CategoryTarget.PersonalFm -> Color(0xFFF2385A)
        CategoryTarget.Gallery -> Color(0xFF9B6FB8)
        CategoryTarget.Recognition -> Color(0xFF22A6B3)
    }

/** 浏览分类砖块：2 列网格，每块 80dp 高。 */
@Composable
private fun CategoryGrid(onOpen: (CategoryTarget) -> Unit) {
    val rows = CategoryTarget.entries.chunked(2)
    Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp)) {
        rows.forEach { rowTargets ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                rowTargets.forEach { target ->
                    Box(
                        Modifier
                            .weight(1f)
                            .height(72.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(
                                Brush.linearGradient(
                                    listOf(target.topColor, target.bottomColor)
                                )
                            )
                            .clickable { onOpen(target) }
                    ) {
                        // 顶部高光：与项目玻璃语言一致的浅色渐层，避免纯渐变显得扁平
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(34.dp)
                                .background(
                                    Brush.verticalGradient(
                                        listOf(Color.White.copy(alpha = 0.18f), Color.Transparent)
                                    )
                                )
                        )
                        // 右下角旋转 45° 的浅色方块，模拟 Apple Music 分类砖的封面露出
                        Box(
                            Modifier
                                .align(Alignment.BottomEnd)
                                .offset(x = 22.dp, y = 22.dp)
                                .size(50.dp)
                                .graphicsLayer { rotationZ = 45f }
                                .background(Color.White.copy(alpha = 0.22f), RoundedCornerShape(5.dp))
                        )
                        Text(
                            text = stringResource(id = target.labelRes),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .align(Alignment.TopStart)
                                .padding(horizontal = 11.dp, vertical = 10.dp)
                        )
                    }
                }
                // 奇数个目标时补一个空位，保持网格对齐
                if (rowTargets.size == 1) Spacer(Modifier.weight(1f))
            }
        }
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
 * 不自带三竖点菜单 —— MusicList 行尾已有 SongAddToPlaylistButton，再加会重复。
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
            imageQuality = ImageQuality.LOW
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
                                // 记录来源 key：详情页据此与本卡封面配对
                                OnlineAlbumObject.setSharedCoverKey(album.albumId, "album/online/${album.albumId}")
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
                        val playlistId = playlist.globalCollectionId
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
