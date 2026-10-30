package yos.music.player.ui.pages.library.playlists

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import yos.music.player.ui.widgets.basic.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import yos.music.player.ui.navigation.PlaylistSelection
import yos.music.player.ui.lazyItemKeys
import com.cormor.overscroll.core.overScrollVertical
import com.cormor.overscroll.core.rememberOverscrollFlingBehavior
import yos.music.player.ui.widgets.basic.navigationBarsHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.CollectedPlaylistsObject
import yos.music.player.data.objects.MyPlaylistsCache
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.playlistSourceIdentity
import yos.music.player.ui.UI
import yos.music.player.native.KugouApiService
import android.widget.Toast
import yos.music.player.data.objects.OnlinePlaylistCache
import yos.music.player.data.repositories.KugouPlaylistTrack
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.KugouSearchSong
import yos.music.player.data.repositories.PendingPlaylistStore
import yos.music.player.ui.navigation.ArtistArtworkColors
import yos.music.player.ui.navigation.ArtistPageAppearance
import yos.music.player.ui.navigation.PageScrimTransition
import yos.music.player.ui.navigation.artworkContentColor
import yos.music.player.ui.navigation.rememberPlaylistBackdropColors
import yos.music.player.ui.pages.library.DetailIntro
import yos.music.player.ui.pages.library.DetailPageHeader
import yos.music.player.ui.pages.library.DetailSongRowWide
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.pages.library.artists.ArtistRoundActionButton
import yos.music.player.ui.pages.library.artists.ArtistCollectButton
import yos.music.player.ui.pages.library.artists.CompactPlayActionRow
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.LocalTitleOverlayHost
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.LocalTitlePageColor
import yos.music.player.ui.widgets.basic.enterCoverCorner
import yos.music.player.ui.widgets.basic.DetailSongsSkeleton
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.SonifyDialog
import yos.music.player.ui.widgets.basic.SonifyDialogTextField
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBar
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.TitleOverlayHost
import yos.music.player.ui.widgets.basic.TitleOverlayHostSlot
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.rememberAlpha
import yos.music.player.ui.widgets.basic.rememberContentFadeAlpha
import yos.music.player.ui.widgets.basic.rememberShowSmallTitle
import yos.music.player.ui.widgets.basic.rememberSkeletonShimmerForPage
import yos.music.player.ui.widgets.basic.rememberTitleBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import yos.music.player.ui.widgets.effects.ShadowType
import yos.music.player.ui.widgets.dropdown.LiquidGlassDropdownMenuItem
import yos.music.player.ui.widgets.dropdown.TitleBarGlassMenu
import yos.music.player.ui.navigation.popBackStackSafely

/**
 * 在线歌单详情页（视觉与在线专辑详情页同款）。
 *
 * 数据链：KugouRepository.getPlaylistSongs(listid) → /playlist/track/all/new（Rust 现有路由）。
 * 播放链（整列表）：全部歌曲映射为队列用 YosMediaItem（uri 为占位符，真实 URL 由播放器
 *        惰性解析）→ 现有 MediaController.prepare(clicked, 完整列表) → Media3 整列表队列。
 *
 * 页面结构（对齐 OnlineAlbumDetail）：封面提取色铺满全屏 → 居中大封面 + 歌单名 +
 * 「N 首 · PLAYLIST」meta 行 + 随机/播放/添加歌曲圆钮 + 可展开简介 → 序号式歌曲行
 * （无封面小图）→ 末尾「N 首歌曲, 约 M 分钟」统计行；顶栏随底色着色、底栏遮罩同色。
 */
@OptIn(ExperimentalSharedTransitionApi::class, ExperimentalMaterial3Api::class)
@Composable
fun OnlinePlaylistDetail(
    navController: NavController,
    selection: PlaylistSelection? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // 作为 Tab 根页（喜爱）时隐藏返回键；默认显示，保持既有调用点行为不变
    showBackButton: Boolean = true,
    // 本条目在返回栈里的 id：用于向 ArtistPageAppearance 注册底栏遮罩色
    // （与专辑页/歌手页同机制，转场进度取本页自身进出场）。
    entryId: String = "",
    scrimTransition: PageScrimTransition? = null
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
            onBack = if (showBackButton) ({ navController.popBackStackSafely() }) else null
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

    val selected = requireNotNull(routeSelection)
    val tracks = remember(playlistId, KugouAccountState.userid) {
        mutableStateOf<List<KugouPlaylistTrack>>(emptyList())
    }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("OnlinePlaylistDetail_status") { mutableStateOf("loading") }
    // 分页加载进度文案（大歌单按页拉取时显示 已加载 X / Y）
    val loadProgress = remember("OnlinePlaylistDetail_loadProgress") { mutableStateOf("") }
    // 下拉刷新指示器状态（独立于 status，避免刷新时全屏 loading 闪烁）
    val refreshing = remember("OnlinePlaylistDetail_refreshing") { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    val accountUserid = KugouAccountState.userid
    val selectionAccount = remember(playlistId) { accountUserid }
    val userEntry = remember(playlistId, accountUserid) { mutableStateOf<KugouPlaylist?>(null) }
    val editAllowed = source == PlaylistSelection.Source.User && accountUserid != null &&
        accountUserid == selectionAccount &&
        (selected.ownerUserid.isNullOrBlank() || selected.ownerUserid == accountUserid) &&
        (userEntry.value?.canEditTracks ?: (selected.canEditTracks == true || playlistId.orEmpty().startsWith("local_")))
    val sourceGid = validPlaylistCollectionGid(
        userEntry.value?.sourceGid?.takeIf { it.isNotBlank() }
            ?: selected.sourceGid
            ?: playlistId.takeIf { source == PlaylistSelection.Source.Recommend }
    )
    val sourceOwner = sourceGid?.let { playlistSourceIdentity(it)?.userid }
        ?: selected.ownerUserid?.takeIf { it.isNotBlank() }
    val isOwnPublicPlaylist = sourceOwner != null && sourceOwner == accountUserid
    val collected = sourceGid?.let(CollectedPlaylistsObject::contains) == true ||
        (userEntry.value?.isCollectedPlaylist == true &&
            (!CollectedPlaylistsObject.loaded.value || sourceGid == null))
    val collectBusy = remember(playlistId, accountUserid) { mutableStateOf(false) }
    val collectionBusy = collectBusy.value ||
        (sourceGid != null && sourceGid in CollectedPlaylistsObject.busy.value)
    val collectEnabled = !collectionBusy && !CollectedPlaylistsObject.loading.value && !isOwnPublicPlaylist
    val collectDescription = stringResource(
        if (isOwnPublicPlaylist) R.string.playlist_collect_own
        else if (collected) R.string.playlist_collect_remove else R.string.playlist_collect_add
    )

    fun toggleCollect() {
        if (collectionBusy) return
        if (!KugouAccountState.isLoggedIn) {
            navController.navigate(UI.Settings.KugouLogin)
            return
        }
        if (isOwnPublicPlaylist) return
        if (sourceGid == null) {
            Toast.makeText(context, R.string.playlist_collect_missing_gid, Toast.LENGTH_SHORT).show()
            return
        }
        collectBusy.value = true
        scope.launch {
            try {
                if (accountUserid != KugouAccountState.userid) return@launch
                CollectedPlaylistsObject.toggle(sourceGid, playlistName, coverUrl, songCount ?: 0)
                    .onFailure {
                        Toast.makeText(context, it.message ?: context.getString(R.string.playlist_collect_failed), Toast.LENGTH_SHORT).show()
                    }
            } finally {
                collectBusy.value = false
            }
        }
    }

    LaunchedEffect(playlistId, accountUserid) {
        if (accountUserid == null) return@LaunchedEffect
        if (source == PlaylistSelection.Source.User) {
            MyPlaylistsCache.get().onSuccess { list ->
                if (accountUserid == KugouAccountState.userid) {
                    userEntry.value = list.firstOrNull { it.listid == playlistId || it.gid == playlistId }
                }
            }
        }
        CollectedPlaylistsObject.ensureLoaded()
    }
    // 首屏缓存键：来源 + 歌单 id（自有/推荐/挂起 id 命名空间不同）
    val pageCacheKey = if (source == PlaylistSelection.Source.User) {
        "${accountUserid.orEmpty()}:${source.name}:${playlistId.orEmpty()}"
    } else "${source?.name.orEmpty()}:${playlistId.orEmpty()}"

    // ---- 挂起歌单（创建中/创建失败）：selection.id 是 PendingPlaylistStore 的 localId ----
    val pendingEntry = remember(playlistId, accountUserid) {
        mutableStateOf(PendingPlaylistStore.get(playlistId.orEmpty())?.takeIf { it.userid == accountUserid })
    }
    val isPendingPlaylist = source == PlaylistSelection.Source.User && pendingEntry.value != null
    // 展示列表 = 服务端歌曲 + 挂起条目未补发的待加歌（绑定真实 listid 前详情页也有内容、可播）
    val displayTracks: List<KugouPlaylistTrack> = if (isPendingPlaylist) {
        val queued = pendingEntry.value?.ops?.mapNotNull { op ->
            if (!op.startsWith("A|")) return@mapNotNull null
            val p = op.split('|')
            if (p.size < 3) return@mapNotNull null
            KugouPlaylistTrack(
                hash = p[2], name = p[1],
                artist = p.getOrNull(5).orEmpty(),
                album = null, durationMs = 0,
                coverUrl = p.getOrNull(6)?.takeIf { it.isNotBlank() }
            )
        }?.filter { q -> tracks.value.none { it.hash == q.hash } } ?: emptyList()
        tracks.value + queued
    } else {
        tracks.value
    }

    // 队列即取即建（占位符 URI，入队零网络请求；挂起歌单的待加歌同样可播）
    fun currentQueue(): List<YosMediaItem> = displayTracks.map { KugouRepository.toQueueMediaItem(it) }

    // 服务端+挂起条目拼出的列表可能出现重复 FileHash，item key 按出现序号唯一化
    val trackKeys = remember(displayTracks) { lazyItemKeys(displayTracks) { it.hash } }

    // 添加歌曲弹层 / 待删除歌曲（长按行触发，仅自有歌单）
    val showAddDialog = remember("OnlinePlaylistDetail_add") { mutableStateOf(false) }
    val removeTarget = remember("OnlinePlaylistDetail_remove") { mutableStateOf<KugouPlaylistTrack?>(null) }
    val removeBusy = remember(playlistId, accountUserid) { mutableStateOf(false) }
    LaunchedEffect(accountUserid, editAllowed) {
        showAddDialog.value = false
        removeTarget.value = null
    }
    // 编辑权限对确认弹层与写操作共同生效。
    val removeAt: ((Int) -> Unit)? = if (editAllowed && !removeBusy.value) {
        { index -> removeTarget.value = displayTracks.getOrNull(index) }
    } else null

    val loadingBaseText = stringResource(id = R.string.online_playlist_loading_songs)
    val progressFormat = stringResource(id = R.string.online_playlist_loaded_progress)
    // 串行化：loadTracks 有 5 个触发入口（首进/静默revalidate/下拉刷新/重试/挂起歌单补拉），
    // 无门控时会并发两套"逐页串行"的完整歌单拉取。全部 withLock 排队：既杜绝并发重复拉取，
    // 又保证加歌等变更触发的重拉不被丢弃（首版 tryLock 丢弃语义曾致新歌不显示）。
    val loadMutex = remember { Mutex() }

    fun loadTracks(pullRefresh: Boolean = false, silent: Boolean = false) {
        val requestAuthVersion = KugouApiService.authVersion()
        fun requestActive() = requestAuthVersion == KugouApiService.authVersion() &&
            accountUserid == KugouAccountState.userid
        if (pullRefresh) {
            refreshing.value = true
        } else if (!silent) {
            status.value = "loading"
            loadProgress.value = ""
        }
        scope.launch {
            // 串行而非丢弃：加歌后的重拉必须真跑一次（在跑的那次带回的是加歌前的旧列表，
            // 丢弃并入 = 新歌永远不出现）。withLock 排队等待，同一时刻只有一套逐页拉取。
            loadMutex.withLock {
            val onProgress: (Int, Int) -> Unit = { loaded, total ->
                // 回调在 IO 线程，切回主线程更新状态；仅在未拉完时显示进度
                scope.launch {
                    if (!requestActive()) return@launch
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
                source == PlaylistSelection.Source.User && validPlaylistCollectionGid(playlistId) != null ->
                    KugouRepository.getPlaylistSongsByGcid(playlistId.orEmpty(), onProgress)
                source == PlaylistSelection.Source.User -> {
                    val accountTracks = KugouRepository.getPlaylistSongs(playlistId.orEmpty(), onProgress)
                    val originalGid = validPlaylistCollectionGid(
                        userEntry.value?.sourceGid?.takeIf { it.isNotBlank() } ?: selected.sourceGid
                    )
                    val isReadOnlyCollection = userEntry.value?.isCollectedPlaylist == true ||
                        selected.canEditTracks == false
                    if (isReadOnlyCollection && originalGid != null && accountTracks.getOrNull().isNullOrEmpty()) {
                        // A cloud collection row can precede its account-side track mirror.
                        KugouRepository.getPlaylistSongsByGcid(originalGid, onProgress)
                    } else accountTracks
                }
                source == PlaylistSelection.Source.Recommend ->
                    KugouRepository.getPlaylistSongsByGcid(playlistId.orEmpty(), onProgress)
                else -> Result.failure(IllegalStateException("Missing playlist selection"))
            }
            if (!requestActive()) return@launch
            result
                .onSuccess { list ->
                    tracks.value = list
                    // 挂起歌单的待加歌在 ops 队列里（服务端列表为空是常态），不能误报空态
                    val hasQueuedAdds = pendingEntry.value?.ops?.any { it.startsWith("A|") } == true
                    status.value = if (list.isEmpty() && !hasQueuedAdds) "empty" else "ok"
                    // 服务器曲目列表落盘（挂起歌单不缓存）
                    if (list.isNotEmpty() && !isPendingPlaylist) {
                        OnlinePlaylistCache.write(context, pageCacheKey, list)
                    }
                }
                .onFailure { e ->
                    // 下拉刷新/静默失败：保留现有（含缓存）列表，只收起指示器，不打成错误态
                    if (!pullRefresh && !silent) {
                        tracks.value = emptyList()
                        status.value = "error:${e.message}"
                    }
                }
                refreshing.value = false
            }
        }
    }

    /** 手动刷新：既重拉当前列表，也通知其它页面（歌单列表/喜爱）同步。 */
    fun refreshFromPull() {
        loadTracks(pullRefresh = true)
        KugouSyncCoordinator.notifyPlaylistsChanged()
    }

    // 整列表播放：全部歌曲进 Media3 队列，从 index 处开始；URL 由播放器惰性解析
    fun playAt(index: Int) {
        val list = currentQueue()
        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            MediaController.prepare(list[index], list)
        }
    }

    LaunchedEffect(source, playlistId, accountUserid, KugouSyncCoordinator.playlistsRevision.value) {
        // 首屏优先：TTL 内的磁盘缓存先渲染（零网络），随后静默 revalidate。挂起歌单不读缓存。
        if (tracks.value.isEmpty() && !isPendingPlaylist) {
            OnlinePlaylistCache.read(context, pageCacheKey)?.let { cached ->
                if (cached.isNotEmpty()) {
                    tracks.value = cached
                    status.value = "ok"
                }
            }
        }
        loadTracks(silent = tracks.value.isNotEmpty())
    }

    // 挂起歌单对账跟踪（事件驱动，替代 4s 轮询）：store 的 update()/delete() 覆盖全部
    // 变更路径并发出 Change 事件。绑定 → 等 ops 补发完（本地读，沿用原节奏）→ 拉真实歌曲。
    LaunchedEffect(playlistId, accountUserid) {
        val localId = playlistId.orEmpty()
        val initial = PendingPlaylistStore.get(localId) ?: return@LaunchedEffect
        if (initial.userid != accountUserid || accountUserid == null) return@LaunchedEffect
        pendingEntry.value = initial
        var wasUnbound = !initial.isBound
        PendingPlaylistStore.changesFor(localId).first { change ->
            // 条目被删除：终止跟踪（原轮询 break 语义）
            var fresh = change.entry ?: return@first true
            pendingEntry.value = fresh
            if (fresh.isBound) {
                // 等挂起的加删歌补发完再拉真实列表，避免竞态（补发中拉取会缺最后几首）
                var waited = 0
                while (fresh.ops.isNotEmpty() && waited < 30_000) {
                    delay(2_000L)
                    waited += 2_000
                    fresh = PendingPlaylistStore.get(localId) ?: return@first true
                    pendingEntry.value = fresh
                }
                if (wasUnbound) loadTracks()
                return@first true
            }
            wasUnbound = true
            return@first false
        }
    }

    // 宽屏（≥600dp）启用 Apple Music 式头部与三列歌曲行；手机保持居中头部
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600
    // 头部/尾部统一曲目数：已加载优先，未加载用路由 songCount 兜底
    val displayCount = if (displayTracks.isNotEmpty()) displayTracks.size else (songCount ?: 0)

    // ---------- 骨架与内容淡入 ----------
    // 骨架只在「没歌 + 仍在加载/未失败」时出现（与主页 sectionShowsSkeleton 同判据）。
    // 注意用 displayTracks 而不是 tracks：挂起歌单的待加歌也算内容。
    val showSkeleton = displayTracks.isEmpty() &&
        status.value != "empty" &&
        !status.value.startsWith("error:")
    val shimmer = rememberSkeletonShimmerForPage(pending = showSkeleton)
    // 页面级共享 alpha：所有可见行同时渐入；结束后恒 1f，补页新行不会重新淡入。
    val contentAlpha = rememberContentFadeAlpha(hasData = displayTracks.isNotEmpty())


    // ---- 封面色底（与在线专辑详情共用同一条取色管线） ----
    // 键按「来源 + 歌单 id」建，不按封面 URL：列表封面与详情封面常是同一张图的不同尺寸。
    val playlistColors = rememberPlaylistBackdropColors(
        source = source?.name.orEmpty(),
        playlistId = playlistId.orEmpty(),
        coverUrl = coverUrl.takeIf { it.isNotEmpty() }
    )
    val isDark = isFlamingoInDarkMode()
    val themeAccent = remember(isDark) { ArtistArtworkColors.fromRgb(null, isDark).accent }
    val resolvedAccent = playlistColors?.accent
    val backdropState = animateColorAsState(
        targetValue = resolvedAccent ?: themeAccent,
        animationSpec = if (resolvedAccent != null) tween(600) else tween(0),
        label = "onlinePlaylistBackdrop"
    )
    val backdrop = backdropState.value
    // 文字色：歌单提取色 → 主题默认（继承 LocalContentColor）
    val contentColor = playlistColors?.let { artworkContentColor(it.accent) }
        ?: LocalContentColor.current

    // 底栏遮罩 / NavHost 底衬：注册本条目当前底色，与页面铺底同源同帧
    if (entryId.isNotBlank() && scrimTransition != null) {
        DisposableEffect(entryId, scrimTransition) {
            val registration = ArtistPageAppearance.register(entryId, backdropState, scrimTransition)
            onDispose { ArtistPageAppearance.unregister(entryId, registration) }
        }
    }

    // 滚动状态 / 顶栏模糊采样源。
    // 采样种子用**目标色**而不是逐帧的动画值：底色 600ms 渐变期间每帧换种子会重建
    // LayerBackdrop，把整个列表的录制层反复重录。
    val state = rememberLazyListState()
    val titleBackdrop = rememberTitleBackdrop(resolvedAccent ?: themeAccent)
    val showSmallTitle = rememberShowSmallTitle(rememberAlpha(state), state)
    // 简介底色明暗：用当前底色亮度判定（与专辑页一致）
    val lightBackdrop = backdrop.luminance() > 0.179f
    // 页面级弹层宿主：右上「更多」的 LiquidDropdown 注册进它就能**同窗口**渲染，
    // 跨窗口 Popup 采不到本页的 layerBackdrop，玻璃材质会退化成实色面板。
    val overlayHost = remember { TitleOverlayHost() }
    val menuOpen = remember("OnlinePlaylistDetail_menu") { mutableStateOf(false) }
    LaunchedEffect(accountUserid) { menuOpen.value = false }

    CompositionLocalProvider(
        LocalContentColor provides contentColor,
        LocalTitlePageColor provides backdrop,
        LocalTitlePageBackdrop provides titleBackdrop,
        LocalTitleOverlayHost provides overlayHost
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(backdrop)
        ) {
            PullToRefreshBox(
                isRefreshing = refreshing.value,
                onRefresh = { refreshFromPull() },
                modifier = Modifier.fillMaxSize()
            ) {
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
                            // 平板（≥600dp）：与专辑页同款 Apple Music 式头部
                            DetailPageHeader(
                                title = playlistName,
                                coverData = { coverUrl.takeIf { it.isNotEmpty() } },
                                coverSharedElementKey = sharedPlaylistCoverKey(source, playlistId),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                tag = "PLAYLIST",
                                countText = "$displayCount " +
                                    stringResource(id = R.string.online_playlists_song_unit),
                                intro = playlistIntro,
                                accentColor = contentColor,
                                collected = if (!editAllowed) collected else null,
                                onToggleCollect = if (!editAllowed) ({ toggleCollect() }) else null,
                                collectEnabled = collectEnabled,
                                collectDescription = collectDescription,
                                trailingIcon = if (editAllowed) {
                                    R.drawable.ic_add
                                } else null,
                                trailingIconDescription = stringResource(
                                    id = R.string.online_playlist_add_songs
                                ),
                                onTrailingClick = if (editAllowed) {
                                    { showAddDialog.value = true }
                                } else null,
                                onPlayAll = {
                                    // 「播放」= 顺序播放：显式关闭随机（随机为进程级粘性，必须关）
                                    MediaController.mediaControl?.shuffleModeEnabled = false
                                    playAt(0)
                                },
                                onShuffle = {
                                    val list = currentQueue()
                                    if (list.isNotEmpty()) {
                                        MediaController.mediaControl?.shuffleModeEnabled = true
                                        playAt(list.indices.random())
                                    }
                                }
                            )
                        } else {
                            // 手机（<600dp）：与专辑页同款居中头部
                            PlaylistHeaderCompact(
                                playlistName = playlistName,
                                displayCount = displayCount,
                                coverUrl = coverUrl,
                                coverSharedElementKey = sharedPlaylistCoverKey(source, playlistId),
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope,
                                intro = playlistIntro,
                                lightBackdrop = lightBackdrop,
                                contentColor = contentColor,
                                playEnabled = displayTracks.isNotEmpty(),
                                onPlayAll = {
                                    // 「播放」= 顺序播放：显式关闭随机（随机为进程级粘性，必须关）
                                    MediaController.mediaControl?.shuffleModeEnabled = false
                                    playAt(0)
                                },
                                onShuffle = {
                                    val list = currentQueue()
                                    if (list.isNotEmpty()) {
                                        MediaController.mediaControl?.shuffleModeEnabled = true
                                        playAt(list.indices.random())
                                    }
                                },
                                onAddSongs = if (editAllowed) {
                                    { showAddDialog.value = true }
                                } else null,
                                collected = collected,
                                collectEnabled = collectEnabled,
                                collectDescription = collectDescription,
                                onToggleCollect = { toggleCollect() }
                            )
                        }
                    }

                    if (!editAllowed && CollectedPlaylistsObject.error.value != null) {
                        item("CollectionRetry") {
                            TextButton(
                                enabled = !CollectedPlaylistsObject.loading.value && !collectionBusy,
                                onClick = { CollectedPlaylistsObject.ensureLoaded(force = true) }
                            ) { Text(stringResource(R.string.playlist_collect_load_failed), color = contentColor) }
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
                                    // 彩色底上不再用 onSurfaceVariant / primary（会与底色脏）
                                    color = if (entry?.failed == true) {
                                        MaterialTheme.colorScheme.error
                                    } else {
                                        LocalContentColor.current
                                    },
                                    modifier = Modifier.alpha(if (entry?.failed == true) 1f else 0.7f)
                                )
                                if (entry?.failed == true) {
                                    Text(
                                        text = stringResource(id = R.string.online_playlist_pending_retry),
                                        fontSize = 14.sp,
                                        color = LocalContentColor.current,
                                        modifier = Modifier
                                            .padding(top = 6.dp)
                                            .clickable {
                                                scope.launch {
                                                    PendingPlaylistStore.retryCreate(entry.localId)
                                                        .onSuccess {
                                                            pendingEntry.value =
                                                                PendingPlaylistStore.get(entry.localId)
                                                        }
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

                    if (status.value.startsWith("error:")) {
                        item("TracksRetry") {
                            TextButton(onClick = { loadTracks() }) {
                                Text(stringResource(R.string.artist_detail_retry), color = contentColor)
                            }
                        }
                    }
                    item("Status") {
                        if (status.value == "empty" && displayTracks.isEmpty()) {
                            // 空歌单：资料库同款图标 + 提示文字，居中（高度收敛）
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
                                        tint = LocalContentColor.current
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
                                emptyText = stringResource(id = R.string.online_playlist_songs_empty),
                                contentColor = contentColor
                            )
                        }
                    }

                    // 分割线仅在非空列表渲染；空歌单只有图标+文字，不要分割线
                    if (status.value != "empty") {
                        item {
                            OnlineDetailDivider()
                        }
                    }

                    // 骨架：行高与 MusicList 的 64dp 严格对齐，移除瞬间内容正好补位、不跳动。
                    // lazy item 纵向排列不重叠，做不了"骨架叠在内容上淡出"（淡出期间内容会被
                    // 推出屏幕），所以这里靠「位置零跳动 + 内容淡入」达到同样的观感。
                    if (showSkeleton) {
                        item("Skeleton") {
                            DetailSongsSkeleton(shimmer = shimmer, rows = 6)
                        }
                    }

                    // 歌曲行：保留封面小图（52dp）的统一歌曲行；
                    // 宽屏（≥600dp）三列：歌名（左半）+ 歌手（起于屏幕中央）+ 时长（贴右）。
                    // 点击 → 全部歌曲进队列，从被点击歌曲开始播放；自有歌单支持长按移除。
                    itemsIndexed(
                        displayTracks,
                        key = { index, _ -> trackKeys[index] }
                    ) { index, track ->
                        key(trackKeys[index]) {
                            // alpha 走页面级共享曲线：所有可见行同时渐入，
                            // 动画结束后恒 1f，后续补页的新行不会重新淡入。
                            Box(
                                Modifier.graphicsLayer {
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                    alpha = contentAlpha()
                                }
                            ) {
                                if (isWideScreen) {
                                    DetailSongRowWide(
                                        music = KugouRepository.toDisplayMediaItem(track),
                                        onDelete = removeAt?.let { cb -> { cb(index) } }
                                    ) {
                                        playAt(index)
                                    }
                                } else {
                                    MusicList(
                                        music = KugouRepository.toDisplayMediaItem(track),
                                        itemClick = { playAt(index) },
                                        onLongClick = removeAt?.let { cb -> { cb(index) } }
                                    )
                                }
                            }
                        }

                        key("divider_$index") {
                            if (index < displayTracks.size - 1) {
                                Spacer(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        // 缩进对齐封面右缘（行 padding 22 + 封面 52 + 间距 14 = 88dp）
                                        .padding(start = 88.dp, end = 16.dp)
                                        // 颜色 alpha 代替 Modifier.alpha(0.15f)：实心矩形不需要离屏层，
                                        // 后者每行每帧一次 saveLayer（trace: "alpha caused saveLayer 1169x2"）。
                                        .height(0.5.dp)
                                        .background(LocalContentColor.current.copy(alpha = 0.15f))
                                )
                            }
                        }
                    }

                    if (status.value != "empty") {
                        item {
                            OnlineDetailDivider()
                        }
                    }

                    // 末尾统计行（与专辑页「N 首歌曲, 约 M 分钟」一致）
                    if (displayTracks.isNotEmpty()) {
                        item("PlaylistOthers") {
                            Text(
                                text = stringResource(
                                    id = R.string.page_library_album_info_others,
                                    displayTracks.size,
                                    (displayTracks.sumOf { it.durationMs } / 60000).toInt()
                                ),
                                fontSize = 15.sp,
                                modifier = Modifier
                                    .alpha(0.4f)
                                    .padding(horizontal = 18.dp)
                                    .padding(top = 18.dp)
                            )
                        }
                    }

                    item("navbar") {
                        Spacer(modifier = Modifier.navigationBarsHeight(134.dp))
                    }
                }
            }

            // 顶栏：滚动离屏浮现小标题 + 模糊；返回键/右上图标着色跟随页面文字色
            TitleBar(
                title = playlistName,
                onBack = if (showBackButton) ({ navController.popBackStackSafely() }) else null,
                showSmallTitle = showSmallTitle,
                backdrop = titleBackdrop,
                backIconTint = contentColor,
                rightBarIcon = {
                    // 本体化玻璃菜单（NexioSchedule 1.6.5）：与专辑页同款接线
                    TitleBarGlassMenu(
                        expanded = menuOpen.value,
                        onExpand = { menuOpen.value = true },
                        onDismiss = { menuOpen.value = false },
                        triggerIcon = Icons.Rounded.MoreHoriz,
                        modifier = Modifier.padding(end = 10.dp),
                        // 与左侧返回键同色：页面底色是封面提取色，与 App 深浅主题无关，
                        // 必须按 backdrop 亮度自适应，否则深色封面上那颗钮会发脏。
                        adaptiveLuminance = true,
                        contentColor = contentColor,
                    ) {
                        // 菜单内容暂为占位（具体动作后续补）
                        LiquidGlassDropdownMenuItem(
                            text = stringResource(id = R.string.online_album_more_placeholder),
                            enabled = false,
                            onClick = {},
                        )
                    }
                }
            )

            // 页面级弹层宿主：弹层渲染在列表与顶栏之上（与专辑页同位置）
            TitleOverlayHostSlot(overlayHost)
        }
    }

    // 删歌确认弹层（长按歌曲行触发，仅自有歌单）
    removeTarget.value?.takeIf { editAllowed }?.let { target ->
        SonifyDialog(
            onDismissRequest = { if (!removeBusy.value) removeTarget.value = null },
            title = stringResource(id = R.string.online_playlist_track_remove),
            message = stringResource(id = R.string.online_playlist_track_remove_confirm, target.name),
            positiveContent = stringResource(id = R.string.common_ok),
            onPositive = {
                if (!editAllowed || removeBusy.value || accountUserid != KugouAccountState.userid) return@SonifyDialog
                removeBusy.value = true
                val t = target
                scope.launch {
                    val entry = pendingEntry.value
                    val result = if (entry != null) {
                        PendingPlaylistStore.removeSong(entry.localId, t.hash, t.fileId)
                    } else {
                        KugouRepository.removeTrackFromPlaylist(playlistId.orEmpty(), t.fileId, t.hash)
                    }
                    if (accountUserid != KugouAccountState.userid) return@launch
                    removeBusy.value = false
                    removeTarget.value = null
                    result
                        .onSuccess {
                            if (entry != null) pendingEntry.value = PendingPlaylistStore.get(entry.localId)
                            else {
                                tracks.value = tracks.value.filter { it.hash != t.hash }
                                status.value = if (tracks.value.isEmpty()) "empty" else "ok"
                                // 删歌后覆写首屏缓存，避免 12h 内读到旧列表
                                if (tracks.value.isNotEmpty()) {
                                    OnlinePlaylistCache.write(context, pageCacheKey, tracks.value)
                                }
                            }
                            KugouSyncCoordinator.notifyPlaylistsChanged()
                        }
                        .onFailure { e -> status.value = "error:${e.message}" }
                }
            },
            negativeContent = stringResource(id = R.string.common_cancel),
            onNegative = { removeTarget.value = null },
            destructive = true,
            positiveEnabled = !removeBusy.value,
            negativeEnabled = !removeBusy.value,
            dismissEnabled = !removeBusy.value,
            closeOnPositive = false
        )
    }

    // 添加歌曲弹层（搜索 → 点按加入歌单；挂起歌单先进同步队列）
    if (showAddDialog.value && editAllowed) {
        AddSongsDialog(
            onDismiss = { showAddDialog.value = false },
            onAdd = add@{ song ->
                if (!editAllowed || accountUserid != KugouAccountState.userid) {
                    return@add Result.failure(IllegalStateException(context.getString(R.string.playlist_read_only)))
                }
                val entry = pendingEntry.value
                val result = if (entry != null) {
                    PendingPlaylistStore.addSong(
                        entry.localId, song.name, song.hash,
                        artist = song.author,
                        coverUrl = song.artworkUrl.orEmpty()
                    )
                } else {
                    KugouRepository.addTracksToPlaylist(playlistId.orEmpty(), song.name, song.hash)
                }
                if (accountUserid != KugouAccountState.userid) return@add result
                result
                    .onSuccess {
                        if (entry != null) pendingEntry.value = PendingPlaylistStore.get(entry.localId)
                        else loadTracks()
                        // 通知歌单列表等页面同步（歌曲数/封面变化）
                        KugouSyncCoordinator.notifyPlaylistsChanged()
                    }
                result
            }
        )
    }
}

/**
 * 封面共享元素 key（入口卡片与详情页封面配对）。
 */
internal fun validPlaylistCollectionGid(value: String?): String? =
    value?.takeIf { playlistSourceIdentity(it) != null }

internal fun sharedPlaylistCoverKey(
    source: PlaylistSelection.Source?,
    id: String?
): String? = if (source != null && !id.isNullOrEmpty()) {
    "playlist/${source.name}/$id"
} else null

/**
 * 详情页横向分割线（对齐专辑页 OnlineAlbumDivider 规范）。
 */
@Composable
private fun OnlineDetailDivider(modifier: Modifier = Modifier) =
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp)
            .height(0.5.dp)
            .background(LocalContentColor.current.copy(alpha = 0.2f))
    )

/**
 * 手机（<600dp）居中头部：与在线专辑详情页同款
 * （居中大封面 + 歌单名 + 「N 首 · PLAYLIST」+ 随机/播放/添加 圆钮 + 可展开简介）。
 * 平板宽屏使用共享 DetailPageHeader。
 */
@Composable
@OptIn(ExperimentalSharedTransitionApi::class)
private fun PlaylistHeaderCompact(
    playlistName: String,
    displayCount: Int,
    coverUrl: String,
    coverSharedElementKey: String?,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
    intro: String?,
    lightBackdrop: Boolean,
    contentColor: Color,
    playEnabled: Boolean,
    onPlayAll: () -> Unit,
    onShuffle: () -> Unit,
    onAddSongs: (() -> Unit)?,
    collected: Boolean,
    collectEnabled: Boolean,
    collectDescription: String,
    onToggleCollect: () -> Unit
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(top = 9.5.dp)
            .padding(horizontal = 18.dp)
            .statusBarsPadding()
            // 头部整体上抬 20dp。用 layout 而不是 offset：offset 只改绘制位置，
            // 条目仍占原高度，下方歌曲列表不会跟着上移、会多出 20dp 空白。
            .layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val shift = 20.dp.roundToPx()
                layout(placeable.width, (placeable.height - shift).coerceAtLeast(0)) {
                    placeable.place(0, -shift)
                }
            },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 大封面：与专辑页同款 ShadowImage 规范，URL 为空时组件内部显示占位图。
        // 注意：aspectRatio 必须由 ShadowImage 自己按**缩进后的宽度**算，不能写在 padding 外面。
        val coverModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 50.dp)
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
                modifier = Modifier.fillMaxWidth(),
                dataLambda = { coverUrl.takeIf { it.isNotEmpty() } },
                contentDescription = null,
                // 转场中圆角从来源封面值渐变到 7dp，静止恒为 7dp
                cornerRadius = enterCoverCorner(animatedVisibilityScope, 7.dp),
                imageQuality = ImageQuality.RAW,
                shadowType = ShadowType.Medium
            )
        }

        // 封面与文字间距 10dp
        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = playlistName,
            fontSize = 20.sp,
            textAlign = TextAlign.Center,
            lineHeight = 26.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 数量 / 类型合并成一行（对齐专辑页 meta 行）：12.5sp / α0.6
        Text(
            text = "$displayCount " +
                stringResource(id = R.string.online_playlists_song_unit) +
                " · PLAYLIST",
            fontSize = 12.5.sp,
            lineHeight = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .alpha(0.6f)
                .padding(top = 4.dp)
        )

        // 操作行：随机圆钮（左）· 实心播放胶囊（中，剩余空间的 2/3）· 添加歌曲圆钮（右，仅自有歌单）
        // 三个按钮全部由页面文字色派生，浅色封面上翻成深色、深色封面上保持白色。
        YosWrapper {
            CompactPlayActionRow(
                foregroundColor = contentColor,
                playEnabled = playEnabled,
                onShuffle = onShuffle,
                onPlayAll = onPlayAll,
                modifier = Modifier.padding(top = 14.dp, bottom = 10.dp),
                trailing = if (onAddSongs != null) {
                    {
                        ArtistRoundActionButton(
                            icon = R.drawable.ic_add,
                            contentDescription = stringResource(id = R.string.online_playlist_add_songs),
                            iconTint = contentColor,
                            highlighted = false,
                            iconSize = 24.dp,
                            containerColor = contentColor.copy(alpha = 0.18f)
                        ) {
                            onAddSongs()
                        }
                    }
                } else {
                    {
                        ArtistCollectButton(
                            collected = collected,
                            enabled = collectEnabled,
                            iconTint = contentColor,
                            containerColor = contentColor.copy(alpha = 0.18f),
                            description = collectDescription,
                            onClick = onToggleCollect
                        )
                    }
                }
            )
        }

        // 简介：常驻在三个按钮下方（仅推荐歌单携带 intro）；超过两行行尾接「…更多」
        intro?.takeIf { it.isNotBlank() }?.let { text ->
            DetailIntro(
                text = text,
                lightBackdrop = lightBackdrop,
                modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 2.dp, bottom = 12.dp)
            )
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

    SonifyDialog(
        onDismissRequest = onDismiss,
        title = stringResource(id = R.string.online_playlist_add_songs),
        content = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SonifyDialogTextField(
                        value = keyword.value,
                        onValueChange = { keyword.value = it },
                        placeholder = stringResource(id = R.string.online_playlist_add_search_hint),
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
        positiveContent = stringResource(id = R.string.common_ok),
        onPositive = onDismiss,
        wide = true
    )
}
