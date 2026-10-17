package yos.music.player.ui.pages.discovery

import android.widget.Toast
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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.cormor.overscroll.core.overScrollVertical
import com.cormor.overscroll.core.rememberOverscrollFlingBehavior
import yos.music.player.ui.widgets.basic.navigationBarsHeight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.CollectedAlbumsObject
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.objects.OnlineAlbumCache
import yos.music.player.data.repositories.AlbumArtist
import yos.music.player.data.repositories.KugouAlbumDetail
import yos.music.player.ui.UI
import yos.music.player.ui.lazyItemKeys
import yos.music.player.ui.navigation.ArtistArtworkColors
import yos.music.player.ui.navigation.ArtistPageAppearance
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.navigation.PageScrimTransition
import yos.music.player.ui.navigation.artworkContentColor
import yos.music.player.ui.navigation.rememberAlbumBackdropColors
import yos.music.player.ui.navigation.rememberArtistBackdropColors
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.pages.library.AlbumTrackRow
import yos.music.player.ui.pages.library.AlbumTrackRowWide
import yos.music.player.ui.pages.library.DetailIntro
import yos.music.player.ui.pages.library.DetailPageHeader
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.pages.library.artists.ArtistCollectButton
import yos.music.player.ui.pages.library.artists.ArtistRoundActionButton
import yos.music.player.ui.pages.library.artists.PlayPillButton
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.LiquidDropdownColumn
import yos.music.player.ui.widgets.basic.LiquidDropdownLayout
import yos.music.player.ui.widgets.basic.LiquidDropdownRow
import yos.music.player.ui.widgets.basic.LocalTitleOverlayHost
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.LocalTitlePageColor
import yos.music.player.ui.widgets.basic.enterCoverCorner
import yos.music.player.ui.widgets.basic.ShadowImage
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.basic.TitleBar
import yos.music.player.ui.widgets.basic.TitleBarIcon
import yos.music.player.ui.widgets.basic.TitleOverlayHost
import yos.music.player.ui.widgets.basic.TitleOverlayHostSlot
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.liquidDropdownAnchorFollow
import yos.music.player.ui.widgets.basic.rememberLiquidDropdownFollowState
import yos.music.player.ui.widgets.basic.rememberAlpha
import yos.music.player.ui.widgets.basic.rememberShowSmallTitle
import yos.music.player.ui.widgets.basic.rememberTitleBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import yos.music.player.ui.widgets.effects.ShadowType

/**
 * 在线专辑详情页（结构对齐 AlbumInfo / RankDetail / OnlinePlaylistDetail 详情模板）。
 *
 * 数据链：KugouRepository.getAlbumDetail(albumId) → GET /album/detail（头部元数据）
 *         + KugouRepository.getAlbumSongs(albumId) → GET /album/songs 分页拉全量。
 * 播放链（整列表）：全部歌曲映射为队列用 YosMediaItem（uri 为占位符，真实 URL
 * 由播放器惰性解析）→ 现有 MediaController.prepare(clicked, 完整列表) → Media3 队列。
 *
 * 说明：本地 AlbumInfo 以「本地专辑名 → MusicLibrary.Album[albumName]」为数据源，
 * 无法直接承载「albumId → /album/songs」的在线专辑；故按 OnlinePlaylistDetail 同款
 * 既有模式新建本页，并完整复用 AlbumInfo 的视觉组件（ShadowImage/NormalButton/AlbumTrackRow/分割线）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun OnlineAlbumDetail(
    navController: NavController,
    albumId: String = "",
    sourceArtistId: String? = null,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
    // 本条目在返回栈里的 id：用于向 ArtistPageAppearance 注册底栏遮罩色
    // （与歌手页同机制，转场进度取本页自身进出场）。
    entryId: String = "",
    scrimTransition: PageScrimTransition? = null
) {
    // 按路由里的 albumId 取本页专辑：多层堆叠时各自解析自己的那一张，
    // 不再共用「最后选中」的全局槽（否则返回时全部变成最新那张）。
    val album = OnlineAlbumObject.album(albumId)

    if (album == null) {
        // 边界：无选中专辑（如进程重建后 holder 丢失）
        Title(
            title = stringResource(id = R.string.discovery_new_albums_title),
            onBack = { navController.popBackStack() }
        ) {
            item("NoAlbum") {
                Text(
                    text = stringResource(id = R.string.online_album_detail_missing),
                    fontSize = 18.sp,
                    modifier = Modifier
                        .padding(horizontal = 22.dp, vertical = 10.dp)
                        .alpha(0.6f)
                )
            }
        }
        return
    }

    val detail = remember("OnlineAlbumDetail_detail") {
        mutableStateOf<KugouAlbumDetail?>(null)
    }
    val tracks = remember("OnlineAlbumDetail_tracks") {
        mutableStateOf<List<KugouNewSong>>(emptyList())
    }
    // 酷狗接口可能返回重复 FileHash，item key 按出现序号唯一化。
    // items 与 keys 派生自同一次读取（约定见 LazyItemKeys.kt）
    val trackItems = tracks.value
    val songKeys = remember(trackItems) { lazyItemKeys(trackItems) { it.hash } }
    // 与 tracks 同步的队列映射（占位符 URI，入队零网络请求）
    val queue = remember("OnlineAlbumDetail_queue") {
        mutableStateOf<List<YosMediaItem>>(emptyList())
    }
    // "loading" | "ok" | "empty" | "error:<msg>"
    val status = remember("OnlineAlbumDetail_status") { mutableStateOf("loading") }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    // ---- 加入资料库（收藏专辑） ----
    // 收藏 = 酷狗账号下一条 source=2 的「专辑收藏」条目（/playlist/add type=1&source=2），
    // 属于账号云端数据（官方 App 同账号可见）。状态来自 CollectedAlbumsObject
    // （MMKV 快照 + /user/playlist），点击乐观翻转，失败回滚并提示。
    val account = KugouAccountState
    val collected = CollectedAlbumsObject.contains(album.albumId, album.name)
    var collectBusy by remember("OnlineAlbumDetail_collectBusy") { mutableStateOf(false) }
    // 列表还没加载完时按钮先禁用（半透明），避免「先 + 后 ✓」的闪变。
    // 未登录时保持可点：点它去登录页。
    val collectReady = !account.isLoggedIn || CollectedAlbumsObject.loaded.value

    fun toggleCollect() {
        if (collectBusy) return
        if (!account.isLoggedIn) {
            navController.navigate(UI.Settings.KugouLogin)
            return
        }
        val target = album
        val wasCollected = collected
        // 收藏接口需要 artistId；详情接口没回来时传空（上游按 0 处理）
        val artistId = detail.value?.artistId.orEmpty()
        collectBusy = true
        scope.launch {
            try {
                CollectedAlbumsObject.toggle(target, artistId).onFailure { e ->
                    val messageRes = when {
                        e.message == "not_found" -> R.string.album_uncollect_not_found
                        wasCollected -> R.string.album_uncollect_failed
                        else -> R.string.album_collect_failed
                    }
                    Toast.makeText(context, messageRes, Toast.LENGTH_SHORT).show()
                }
            } finally {
                collectBusy = false
            }
        }
    }

    LaunchedEffect(album.albumId, account.isLoggedIn) {
        if (account.isLoggedIn) CollectedAlbumsObject.ensureLoaded()
    }

    fun load(silent: Boolean = false) {
        if (!silent) status.value = "loading"
        scope.launch {
            // 头部元数据失败不影响歌曲列表展示（封面/名称兜底用选中专辑字段）
            KugouRepository.getAlbumDetail(album.albumId)
                .onSuccess { detail.value = it }
                .onFailure { e ->
                    android.util.Log.d("OnlineAlbumDetail", "album detail failed: ${e.message}")
                }

            KugouRepository.getAlbumSongs(album.albumId)
                .onSuccess { list ->
                    tracks.value = list
                    queue.value = list.map { KugouRepository.toQueueMediaItem(it) }
                    status.value = if (list.isEmpty()) "empty" else "ok"
                    if (list.isNotEmpty()) OnlineAlbumCache.write(context, album.albumId, list)
                    android.util.Log.d(
                        "OnlineAlbumDetail",
                        "albumId=${album.albumId} albumName=${album.name} parsed=${list.size}"
                    )
                }
                .onFailure { e ->
                    // 静默 revalidate 失败时保留缓存首屏，不打成错误态
                    if (!silent) {
                        tracks.value = emptyList()
                        queue.value = emptyList()
                        status.value = "error:${e.message}"
                    }
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

    LaunchedEffect(Unit) {
        // 首屏优先：TTL 内的磁盘缓存先渲染（零网络），随后静默 revalidate
        OnlineAlbumCache.read(context, album.albumId)?.let { cached ->
            if (cached.isNotEmpty()) {
                tracks.value = cached
                queue.value = cached.map { KugouRepository.toQueueMediaItem(it) }
                status.value = "ok"
            }
        }
        load(silent = tracks.value.isNotEmpty())
    }

    // 平板（≥600dp）启用 Apple Music 式头部与三列歌曲行；手机保持旧版布局
    val isWideScreen = LocalConfiguration.current.screenWidthDp >= 600
    // 专辑作者名：窄屏行仅当某首歌的歌手与专辑作者不同时才显示该行歌手（与本地专辑一致）
    val albumArtistNames = remember(detail.value, album) {
        albumArtists(detail.value, album.singerName).map { it.name }
    }
    // 目标封面 key：跟随入口点击时按本专辑 id 记录的来源 key（多层堆叠时各自正确）；
    // 记录缺失或与本专辑不符时回退默认 key。
    val recordedCoverKey = OnlineAlbumObject.coverKeyFor(album.albumId)
        ?.takeIf { it.endsWith("/${album.albumId}") }
    val coverSharedElementKey = recordedCoverKey ?: "album/online/${album.albumId}"

    // ---- 封面色底（与歌手页共用同一条取色管线） ----
    // 详情接口的封面优先，未返回时用选中专辑的列表封面兜底；取色按 albumId 建键，
    // 两个地址是同一张图的不同尺寸，不会因 URL 变化重算。
    val paletteCoverUrl = detail.value?.coverUrl?.takeIf { it.isNotEmpty() } ?: album.coverUrl
    val albumColors = rememberAlbumBackdropColors(album.albumId, paletteCoverUrl)
    // 从歌手页进来时以歌手色为过渡起点：否则本页第一帧会先露出主题兜底色再渐变，
    // 在歌手色底上闪一下白/黑。
    val (artistAccent, artistContent) = rememberArtistBackdropColors(sourceArtistId)
    val isDark = isFlamingoInDarkMode()
    val themeAccent = remember(isDark) { ArtistArtworkColors.fromRgb(null, isDark).accent }
    val resolvedAccent = albumColors?.accent ?: artistAccent
    val backdropState = animateColorAsState(
        targetValue = resolvedAccent ?: themeAccent,
        animationSpec = if (resolvedAccent != null) tween(600) else tween(0),
        label = "onlineAlbumBackdrop"
    )
    val backdrop = backdropState.value
    // 文字色：专辑提取色 → 来源歌手色 → 主题默认（继承 LocalContentColor）
    val contentColor = albumColors?.let { artworkContentColor(it.accent) }
        ?: artistContent
        ?: LocalContentColor.current

    // 底栏遮罩 / NavHost 底衬：注册本条目当前底色，与页面铺底同源同帧
    if (entryId.isNotBlank() && scrimTransition != null) {
        DisposableEffect(entryId, scrimTransition) {
            val registration = ArtistPageAppearance.register(entryId, backdropState, scrimTransition)
            onDispose { ArtistPageAppearance.unregister(entryId, registration) }
        }
    }

    // 滚动状态 / 顶栏模糊采样源 / 页面级弹层宿主。
    // 弹层宿主与 Title 页同款：LiquidDropdown 注册进它就能**同窗口**渲染，
    // 跨窗口 Popup 采不到本页的 layerBackdrop，玻璃材质会退化成实色面板。
    val state = rememberLazyListState()
    // 采样种子用**目标色**而不是逐帧的动画值：底色 600ms 渐变期间每帧换种子会重建
    // LayerBackdrop，把整个列表的录制层反复重录（本机 RenderThread 上还会丢子层）。
    // 同时显式传色，避免在 CompositionLocalProvider 之前读不到 LocalTitlePageColor
    // 而退成深色模式的黑底。
    val titleBackdrop = rememberTitleBackdrop(resolvedAccent ?: themeAccent)
    val showSmallTitle = rememberShowSmallTitle(rememberAlpha(state), state)
    val overlayHost = remember { TitleOverlayHost() }
    // 右上角「更多」：占位菜单（分享按钮按要求不复刻）
    var menuOpen by remember { mutableStateOf(false) }

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
        LazyColumn(
            state = state,
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(titleBackdrop)
                .overScrollVertical(),
            flingBehavior = rememberOverscrollFlingBehavior { state },
            contentPadding = PaddingValues(bottom = 18.dp, top = 54.dp)
        ) {
            item("AlbumHeader") {
                if (isWideScreen) {
                    // 平板（≥600dp）：Apple Music 式头部（含 歌手/发行日期/简介）
                    DetailPageHeader(
                        title = detail.value?.name?.takeIf { it.isNotEmpty() } ?: album.name,
                        coverData = {
                            (detail.value?.coverUrl?.takeIf { it.isNotEmpty() } ?: album.coverUrl)
                                .takeIf { it.isNotEmpty() }
                        },
                        tag = "ALBUM",
                        coverSharedElementKey = coverSharedElementKey,
                        sharedTransitionScope = sharedTransitionScope,
                        animatedVisibilityScope = animatedVisibilityScope,
                        countText = "${if (tracks.value.isNotEmpty()) tracks.value.size else album.songCount} " +
                            stringResource(id = R.string.online_playlists_song_unit),
                        intro = detail.value?.intro,
                        extraLines = {
                            // 歌手名：逐个作者单独成链接（见手机头部同款说明）
                            val authors = albumArtists(detail.value, album.singerName)
                            if (authors.isNotEmpty()) {
                                AlbumArtistLinks(
                                    artists = authors,
                                    textAlign = TextAlign.Start,
                                    modifier = Modifier.padding(top = 2.dp)
                                ) { artist ->
                                    navController.navigate(
                                        UI.artistDetailRoute(
                                            artistId = artist.id.takeIf { it.isNotEmpty() },
                                            artistName = artist.name
                                        )
                                    )
                                }
                            }

                            // 发行日期（在线专辑额外展示，本地 AlbumInfo 无此字段）
                            detail.value?.publishDate?.let { date ->
                                Text(
                                    text = date,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .alpha(0.4f)
                                        .padding(top = 2.dp)
                                )
                            }
                        },
                        onPlayAll = {
                            // 「播放」= 顺序播放：显式关闭随机（随机为进程级粘性，必须关）
                            MediaController.mediaControl?.shuffleModeEnabled = false
                            playAt(0)
                        },
                        onShuffle = {
                            val list = queue.value
                            if (list.isNotEmpty()) {
                                MediaController.mediaControl?.shuffleModeEnabled = true
                                playAt(list.indices.random())
                            }
                        },
                        // 平板头部同款收藏开关（播放右侧的 + / ✓）
                        collected = collected,
                        onToggleCollect = { toggleCollect() },
                        collectEnabled = !collectBusy && collectReady,
                        accentColor = contentColor
                    )
                } else {
                // 手机：保持旧版居中头部
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
                    // 大封面：与 AlbumInfo/RankDetail 同款 ShadowImage 规范；
                    // 详情封面优先，失败/未加载时用选中专辑封面兜底。
                    // 注意：aspectRatio 必须由 ShadowImage 自己按**缩进后的宽度**算，
                    // 不能写在 padding 外面——否则 Box 按整行宽度算成正方形，里面真正的
                    // 封面比 Box 矮一截，下方会多出约 (2×50dp) 的空白把文字推远。
                    val coverUrl = detail.value?.coverUrl?.takeIf { it.isNotEmpty() } ?: album.coverUrl
                    val coverModifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 50.dp)
                        .then(
                            if (sharedTransitionScope != null && animatedVisibilityScope != null) {
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
                        text = detail.value?.name?.takeIf { it.isNotEmpty() } ?: album.name,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    // 歌手名：合辑/多歌手专辑逐个作者单独成链接（各自带自己的名字/id 进对应
                    // 艺人页）；不能把拼接整串当单歌手去搜索，否则解析失败。
                    val authors = albumArtists(detail.value, album.singerName)
                    if (authors.isNotEmpty()) {
                        AlbumArtistLinks(
                            artists = authors,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.padding(top = 2.dp)
                        ) { artist ->
                            navController.navigate(
                                UI.artistDetailRoute(
                                    artistId = artist.id.takeIf { it.isNotEmpty() },
                                    artistName = artist.name
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 数量 / 发行年份 / 类型合并成一行（对齐参考图的 meta 行）：
                    // 原先「N 首」单独一行 17.5sp 满不透明，视觉重量压过歌手名。
                    val songCountDisplay = if (tracks.value.isNotEmpty()) tracks.value.size else album.songCount
                    val songUnit = stringResource(id = R.string.online_playlists_song_unit)
                    val publishYear = detail.value?.publishDate
                        ?.take(4)
                        ?.takeIf { year -> year.any { it.isDigit() } }
                    val metaLine = buildList {
                        add("$songCountDisplay $songUnit")
                        publishYear?.let(::add)
                        add("ALBUM")
                    }.joinToString(" · ")
                    Text(
                        text = metaLine,
                        fontSize = 12.5.sp,
                        lineHeight = 16.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .alpha(0.6f)
                            .padding(top = 4.dp)
                    )

                    // 圆钮材质对齐歌手页「简介」按钮：半透明白圆（浅 0.22 / 深 0.12）+ 白色图标，
                    // 不传 containerColor 即用默认材质。
                    YosWrapper {
                        // 操作行：与歌手页同款圆钮（白色镂空播放圆 + 半透明动作圆），
                        // 顺序对齐参考页：随机（左）· 播放（中）· 加入（右，暂开占位菜单）。
                        // 圆钮底与图标都取页面文字色，浅色封面底上也不隐形。
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(top = 14.dp, bottom = 10.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 随机：全部歌曲进队列 + Media3 原生 shuffle，从随机位置开始
                            ArtistRoundActionButton(
                                icon = R.drawable.button_icon_shuffle,
                                contentDescription = stringResource(id = R.string.normal_button_shuffle),
                                iconTint = Color.Unspecified,
                                highlighted = false,
                                iconSize = 24.dp,
                                enabled = queue.value.isNotEmpty()
                            ) {
                                val list = queue.value
                                if (list.isNotEmpty()) {
                                    MediaController.mediaControl?.shuffleModeEnabled = true
                                    playAt(list.indices.random())
                                }
                            }
                            Spacer(Modifier.width(13.dp))
                            // 播放：全部歌曲进队列，从第一首开始（顺序播放：先关随机）
                            PlayPillButton(
                                label = stringResource(id = R.string.normal_button_play),
                                enabled = queue.value.isNotEmpty()
                            ) {
                                MediaController.mediaControl?.shuffleModeEnabled = false
                                playAt(0)
                            }
                            Spacer(Modifier.width(13.dp))
                            // 加入资料库：未收藏「+」、已收藏「✓」，点击后两个图标互相形变
                            ArtistCollectButton(
                                collected = collected,
                                iconSize = 24.dp,
                                enabled = !collectBusy && collectReady
                            ) {
                                toggleCollect()
                            }
                        }
                    }

                    // 简介：常驻在三个按钮下方；超过两行时行尾接「…更多」可点展开
                    detail.value?.intro?.takeIf { it.isNotBlank() }?.let { intro ->
                        DetailIntro(
                            text = intro,
                            lightBackdrop = backdrop.luminance() > 0.179f,
                            modifier = Modifier.padding(
                                start = 2.dp,
                                end = 2.dp,
                                top = 2.dp,
                                bottom = 12.dp
                            )
                        )
                    }
                }
                }
            }

            item("Status") {
                OnlineStatusItem(
                    status = status.value,
                    loadingText = stringResource(id = R.string.online_playlist_loading_songs),
                    emptyText = stringResource(id = R.string.online_album_songs_empty),
                    contentColor = contentColor
                )
            }

            item {
                OnlineAlbumDivider()
            }

            itemsIndexed(
                trackItems,
                key = { index, _ -> songKeys.getOrElse(index) { "oob_$index" } }
            ) { index, song ->
                key(songKeys.getOrElse(index) { "oob_$index" }) {
                    // 专辑歌曲行（无封面）：行首显示专辑内序号；
                    // 点击 → 全部歌曲进队列，从被点击歌曲开始播放
                    if (isWideScreen) {
                        AlbumTrackRowWide(
                            music = KugouRepository.toDisplayMediaItem(song),
                            trackNumber = index + 1
                        ) { playAt(index) }
                    } else {
                        AlbumTrackRow(
                            music = KugouRepository.toDisplayMediaItem(song),
                            trackNumber = index + 1,
                            mainArtists = albumArtistNames
                        ) { playAt(index) }
                    }
                }

                key("divider_$index") {
                    if (index < trackItems.size - 1) {
                        Spacer(
                            modifier = Modifier
                                .fillMaxWidth()
                                // 缩进对齐行首序号列（宽屏标题起点 62dp / 窄屏 50dp，
                                // 与本地专辑页一致）；右侧留 18dp 不顶边
                                .padding(
                                    start = if (isWideScreen) 62.dp else 50.dp,
                                    end = 16.dp
                                )
                                .alpha(if (isWideScreen) 0.15f else 0.25f)
                                .height(0.5.dp)
                                .background(LocalContentColor.current)
                        )
                    }
                }
            }

            item {
                OnlineAlbumDivider()
            }

            // 末尾统计行（与本地专辑页 AlbumInfo 的「N 首歌曲, 约 M 分钟」一致）
            if (trackItems.isNotEmpty()) {
                item("AlbumOthers") {
                    Text(
                        text = stringResource(
                            id = R.string.page_library_album_info_others,
                            trackItems.size,
                            (trackItems.sumOf { it.durationMs } / 60000).toInt()
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

            // 顶栏：滚动离屏浮现小标题 + 模糊，几何与参考页 TitleBar 一致。
            // 返回键 / 右上角图标着色跟随页面文字色：未开「工具栏液态玻璃」时
            // TitleBar 默认是主题红，在彩色底上会脏。
            TitleBar(
                title = detail.value?.name?.takeIf { it.isNotEmpty() } ?: album.name,
                onBack = { navController.popBackStack() },
                showSmallTitle = showSmallTitle,
                backdrop = titleBackdrop,
                backIconTint = contentColor,
                rightBarIcon = {
                    // 与本地资料库「歌曲」页同款：MoreHoriz 图标 + 锚点捕获 + 按钮联动，
                    // 弹层住在页面作用域内（同窗口玻璃采样），动画同一套引擎。
                    val anchorBounds = remember { mutableStateOf(IntRect.Zero) }
                    val followState = rememberLiquidDropdownFollowState()

                    TitleBarIcon(
                        modifier = Modifier
                            .onGloballyPositioned { coords ->
                                val pos = coords.positionInWindow()
                                val size = coords.size
                                anchorBounds.value = IntRect(
                                    left = pos.x.toInt(),
                                    top = pos.y.toInt(),
                                    right = pos.x.toInt() + size.width,
                                    bottom = pos.y.toInt() + size.height
                                )
                            }
                            // 联动必须挂在锚点捕获的下游：按钮会随面板进度下沉/收缩，
                            // 锚点若跟着走会形成逐帧抖动回环
                            .liquidDropdownAnchorFollow(followState),
                        icon = Icons.Rounded.MoreHoriz,
                        contentColor = contentColor,
                        onBack = { menuOpen = true }
                    )

                    // 占位菜单（分享按钮按要求不复刻）：「查看歌手」是现有能力，
                    // 其余动作待补，先以置灰行占位。
                    val menuArtists = albumArtists(detail.value, album.singerName)
                    LiquidDropdownLayout(
                        expanded = menuOpen,
                        anchorBounds = anchorBounds.value,
                        onDismissRequest = { menuOpen = false },
                        surfaceAlpha = 0.6f,
                        backdrop = LocalTitlePageBackdrop.current,
                        onFractionProgress = followState::onProgress
                    ) {
                        LiquidDropdownColumn {
                            menuArtists.firstOrNull()?.let { artist ->
                                LiquidDropdownRow(
                                    text = stringResource(id = R.string.online_album_more_view_artist),
                                    selected = false,
                                    onClick = {
                                        menuOpen = false
                                        navController.navigate(
                                            UI.artistDetailRoute(
                                                artistId = artist.id.takeIf { it.isNotEmpty() },
                                                artistName = artist.name
                                            )
                                        )
                                    },
                                    isFirst = true,
                                    isLast = false,
                                    showSelectedIcon = false
                                )
                            }
                            LiquidDropdownRow(
                                text = stringResource(id = R.string.online_album_more_placeholder),
                                selected = false,
                                enabled = false,
                                onClick = {},
                                isFirst = menuArtists.isEmpty(),
                                isLast = true,
                                showSelectedIcon = false
                            )
                        }
                    }
                }
            )

            // 页面级弹层宿主：弹层渲染在列表与顶栏之上（与 Title 页同位置）
            TitleOverlayHostSlot(overlayHost)
        }
    }
}

/**
 * 专辑作者列表：优先详情接口的逐项作者 [KugouAlbumDetail.artists]；
 * 缺失时把兜底歌手串按枚举分隔符拆开（合辑会拼成「A、B、C」整串）。
 */
private fun albumArtists(detail: KugouAlbumDetail?, fallbackSinger: String): List<AlbumArtist> {
    val fromDetail = detail?.artists.orEmpty().filter { it.name.isNotBlank() }
    if (fromDetail.isNotEmpty()) return fromDetail
    return fallbackSinger
        .split('、', '，', ',', '；', ';')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .map { AlbumArtist("", it) }
}

/**
 * 逐作者可点链接：每个作者独立承载点击（各自名字/id 进对应艺人页），
 * 用 FlowRow 在宽度不足时自动换行；分隔符「·」不可点。整体 60% 透明度。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AlbumArtistLinks(
    artists: List<AlbumArtist>,
    textAlign: TextAlign,
    modifier: Modifier = Modifier,
    onClick: (AlbumArtist) -> Unit
) {
    FlowRow(
        modifier = modifier.alpha(0.6f),
        horizontalArrangement = if (textAlign == TextAlign.Center) Arrangement.Center else Arrangement.Start
    ) {
        artists.forEachIndexed { index, artist ->
            if (index > 0) {
                Text(" · ", fontSize = 14.sp, lineHeight = 20.sp)
            }
            Text(
                text = artist.name,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                textAlign = textAlign,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable {
                    NavGuard.run { onClick(artist) }
                }
            )
        }
    }
}

/**
 * 详情页横向分割线（对齐 AlbumInfo 的 AlbumDivider / RankDetail 的 RankDetailDivider 规范）。
 */
@Composable
private fun OnlineAlbumDivider(modifier: Modifier = Modifier) =
    Spacer(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 16.dp)
            .alpha(0.2f)
            .height(0.5.dp)
            .background(LocalContentColor.current)
    )
