package yos.music.player.ui.pages.library.artists

import android.graphics.Color as AndroidColor
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.geometry.Rect
import yos.music.player.ui.navigation.rememberPageData
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.navigation.ArtistBackdrop
import yos.music.player.ui.widgets.effects.overlayEffect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.navigation.NavController
import coil.compose.AsyncImage
import coil.imageLoader
import coil.request.ImageRequest
import com.cormor.overscroll.core.overScrollVertical
import com.cormor.overscroll.core.rememberOverscrollFlingBehavior
import com.google.accompanist.insets.navigationBarsHeight
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import yos.music.player.data.objects.ArtistSongsState
import yos.music.player.data.objects.OnlineAlbumObject
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.ui.pages.discovery.NewAlbumCard
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.objects.ArtistSongsObject
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.repositories.KugouArtistDetailData
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.navigation.ArtistArtworkColors
import yos.music.player.ui.navigation.ArtistPageAppearance
import yos.music.player.ui.navigation.PageScrimTransition
import yos.music.player.ui.widgets.basic.circleEdgeLight
import yos.music.player.ui.UI
import yos.music.player.ui.pages.library.MusicList
import yos.music.player.ui.pages.library.OnlineListItemDivider
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.data.objects.ArtistPresentationCache
import yos.music.player.data.objects.FollowedArtistsObject
import yos.music.player.data.objects.KugouFollowedArtist
import yos.music.player.data.libraries.SettingsLibrary
import android.widget.Toast
import yos.music.player.ui.widgets.basic.TitleBar
import yos.music.player.ui.widgets.basic.LocalTitlePageColor
import yos.music.player.ui.widgets.basic.LiquidTopBarButton

/**
 * 歌手详情页（Apple Music 艺人页式排版）：
 * 全屏头像 hero（顶部浮层玻璃返回键 + 名字随滚动渐显小标题）+ 头像 Palette 取色
 * 向内容区自然过渡 + i/播放/关注三圆钮 + 全部歌曲横向分页列表（主页「新歌精选」同款参数），
 * 区块标题整行可点进入整页列表 [ArtistSongsDetail]。
 *
 * 解析链不变：入参只有歌手名（本地歌手无在线 id）时先 /search/artist 解析出 singerid
 * （同名精确匹配优先），再拉 /artist/detail 头部 + /artist/audios 分页歌曲。
 * 歌曲分页状态与整页共享于 [ArtistSongsObject]（横排翻页追加、整页拉到完）。
 * 简介收进液态玻璃底部弹窗 [ArtistIntroSheet]；关注按钮需登录（乐观更新，失败回滚）。
 */

/** 手机 hero 高度占屏比例（旧公式，保护性保留，勿改）。 */
private const val HeroPhoneHeightFraction = 0.45f
/** 平板竖屏 hero 高度上限占屏比例（典型平板竖屏不触发，保证「按宽度取方形」）。 */
private const val HeroTabletMaxHeightFraction = 0.75f
/** 平板倒影带绝对高度上限：方形 hero 会让 bandHeight=0.5*heroHeight 过大，需限幅。 */
private const val HeroTabletReflectionBandMaxDp = 260f
/** 平板竖屏把内容列表整体上抬的高度：方形 hero 让内容起始过低，直接落在 hero 高度上补偿。 */
private const val HeroTabletContentRaiseDp = 40f
/**
 * 倒影烘焙的最大宽度（像素）。源图仅按 1024px 取回（见下方 ImageRequest.size(1024)），
 * 因此按全宽烘焙纯属把 1024 源图放大存进大位图：平板方形 hero 全宽可达 ~2000px，
 * 位图约 2000×2650 ≈ 21MB（手机仅 ~1100px/7MB），既无任何画质收益，又极易 OOM 或
 * 超出取图死线 → 烘焙概率性失败 → 倒影缺失硬衔接。封顶到源图分辨率，绘制时再放大回整盒。
 */
private const val HeroBakeMaxWidthPx = 1024f

@OptIn(androidx.compose.animation.ExperimentalSharedTransitionApi::class)
@Composable
fun ArtistDetail(
    navController: NavController,
    artistId: String?,
    artistName: String,
    entryId: String,
    scrimTransition: PageScrimTransition,
    sharedTransitionScope: androidx.compose.animation.SharedTransitionScope? = null,
    animatedVisibilityScope: androidx.compose.animation.AnimatedVisibilityScope? = null
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val isDark = isFlamingoInDarkMode()

    val account = KugouAccountState
    val cachedDetail = remember(artistId, artistName) { ArtistPresentationCache.peek(artistId, artistName) }
    val detail = rememberPageData<KugouArtistDetailData?>("artist.detail") { cachedDetail }
    val resolvedId = rememberPageData("artist.resolvedId") { artistId?.takeIf { it.isNotBlank() } ?: cachedDetail?.artistId.orEmpty() }
    val resolveError = rememberPageData<String?>("artist.resolveError") { null }
    val headerLoaded = rememberPageData("artist.headerLoaded") { false }
    val hasHeaderDetail = rememberPageData("artist.hasHeaderDetail") { false }
    val headerLoading = rememberPageData("artist.headerLoading") { true }
    val retry = rememberPageData("artist.retry") { 0 }
    val isFollowed = rememberPageData("artist.isFollowed") { false }
    val followBusy = remember(artistId, artistName) { mutableStateOf(false) }
    val introOpen = remember(artistId, artistName) { mutableStateOf(false) }
    val songsState = remember(resolvedId.value) {
        resolvedId.value.takeIf { it.isNotBlank() }?.let(ArtistSongsObject::forArtist)
    }
    val songs = songsState?.songs?.value.orEmpty()
    val hotSongs = rememberPageData("artist.hotSongs") { emptyList<KugouNewSong>() }
    val hotLoading = rememberPageData("artist.hotLoading") { true }
    val hotError = rememberPageData<String?>("artist.hotError") { null }
    val hotRetry = rememberPageData("artist.hotRetry") { 0 }
    val hotLoadedRetry = rememberPageData("artist.hotLoadedRetry") { -1 }
    val albums = rememberPageData("artist.albums") { emptyList<KugouNewAlbum>() }
    val albumsLoading = rememberPageData("artist.albumsLoading") { true }
    val albumsError = rememberPageData<String?>("artist.albumsError") { null }
    val albumsRetry = rememberPageData("artist.albumsRetry") { 0 }
    val albumsLoadedRetry = rememberPageData("artist.albumsLoadedRetry") { -1 }

    val headerAccount = rememberPageData<String?>("artist.headerAccount") { null }
    val loadedRetry = rememberPageData("artist.loadedRetry") { -1 }
    val configuration = LocalConfiguration.current
    // 设备/方向判定：最窄宽与方向无关（screenWidthDp 在手机横屏也会 >600，仅凭宽度判平板会误伤）。
    val screenWidthDp = configuration.screenWidthDp.toFloat()
    val screenHeightDp = configuration.screenHeightDp.toFloat()
    val isTablet = configuration.smallestScreenWidthDp >= 600
    val landscapeTablet = isTablet && screenWidthDp > screenHeightDp
    val portraitTablet = isTablet && screenWidthDp <= screenHeightDp
    // 头像 hero 定形：手机沿用旧版「屏高 45% × 整宽」（逐字不变）；平板竖屏改为「按宽度取方形
    // 铺满」（屏比 <1 时 heroHeight=maxWidth，1:1 头像不再被上下重度裁切），平板横屏走横向头部
    // （头像是方形封面）。heroAspect 必须与下方渲染盒逐支同形——它同时喂给取色采样
    // (sampleArtistBottomColor) 与 paletteKey，否则取色裁窗与 Crop 会错位、顶栏 tint 也偏色。
    val heroAspect = when {
        portraitTablet -> screenWidthDp /
            (minOf(screenWidthDp, screenHeightDp * HeroTabletMaxHeightFraction) - HeroTabletContentRaiseDp)
                .coerceAtLeast(200f)
        landscapeTablet -> 1f
        else -> screenWidthDp / (screenHeightDp * HeroPhoneHeightFraction).coerceAtLeast(200f)
    }
    val avatarUrl = detail.value?.avatarUrl?.takeIf { it.isNotBlank() }
    val paletteKey = avatarUrl?.let { "$it#bottom-weighted-v2-128-$heroAspect" }
    val paletteState = remember(paletteKey) { paletteKey?.let(ArtistPresentationCache::palette) }
    val originalRgb = paletteState?.value
    val artworkColors = remember(originalRgb, isDark) { ArtistArtworkColors.fromRgb(originalRgb, isDark) }
    val hasResolvedArtwork = originalRgb != null
    val bgColorState = animateColorAsState(
        targetValue = artworkColors.accent,
        animationSpec = if (hasResolvedArtwork) tween(600) else tween(0),
        label = "artistPageBg"
    )
    val contentColor = if (hasResolvedArtwork) artworkColors.content
    else if (isDark) Color.White else Color(0xFF161616)
    DisposableEffect(entryId, bgColorState, scrimTransition) {
        // 进度与页面 fadeIn/fadeOut 同帧同曲线（同一 AnimatedContent Transition），
        // 底栏遮罩用它把默认色向本页背景色同步插值（见 ArtistPageAppearance）。
        val registration = ArtistPageAppearance.register(entryId, bgColorState, scrimTransition)
        onDispose { ArtistPageAppearance.unregister(entryId, registration) }
    }
    // 登记本页 artistId → 调色板缓存键：子页面按来源读缓存 State 实时取色，
    // 与艺人页是否仍在组合无关（快速点进专辑也不怕调色板被取消）
    androidx.compose.runtime.SideEffect {
        paletteKey?.let { ArtistBackdrop.bind(resolvedId.value, it) }
    }
    val seedDraw: ContentDrawScope.() -> Unit = remember(bgColorState) {
        {
            drawRect(
                color = bgColorState.value,
                size = Size(size.width * 3f, size.height * 3f),
                topLeft = Offset(-size.width, -size.height)
            )
            drawContent()
        }
    }
    val pageBackdrop = rememberLayerBackdrop(onDraw = seedDraw)
    val listState = rememberLazyListState()
    // Both pager counts and their item keys use the same immutable list snapshot.
    val pagerState = androidx.compose.runtime.key(songsState) {
        rememberPagerState(pageCount = { (songs.size + 3) / 4 })
    }
    val hotPagerState = androidx.compose.runtime.key(songsState) {
        rememberPagerState(pageCount = { (hotSongs.value.size + 3) / 4 })
    }
    val heroScrolledOut = remember(listState) {
        derivedStateOf {
            val info = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "Hero" }
            info == null || info.offset + info.size <= 0
        }
    }
    val smallTitleAlpha by animateFloatAsState(
        targetValue = if (heroScrolledOut.value) 1f else 0f,
        animationSpec = tween(150),
        label = "artistSmallTitle"
    )

    LaunchedEffect(artistId, artistName, retry.value, account.userid) {
        if (headerLoaded.value && loadedRetry.value == retry.value && headerAccount.value == account.userid) return@LaunchedEffect
        resolveError.value = null
        headerLoading.value = true
        headerLoaded.value = false
        try {
            var id = resolvedId.value
            if (id.isBlank()) {
                val matches = KugouRepository.searchArtists(artistName).getOrThrow()
                ensureActive()
                val brief = matches.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                    ?: matches.firstOrNull()
                if (brief == null || brief.singerId.isBlank()) {
                    resolveError.value = context.getString(R.string.artist_detail_not_found)
                    return@LaunchedEffect
                }
                id = brief.singerId
                resolvedId.value = id
                val briefDetail = KugouArtistDetailData(
                    id, brief.name, brief.avatarUrl, null, 0, 0, false
                )
                detail.value = detail.value ?: briefDetail
                ArtistPresentationCache.rememberBrief(briefDetail, artistName)
            }
            ArtistSongsObject.forArtist(id).ensureLoaded()
            val result = ArtistPresentationCache.loadDetail(id, artistName, account.userid.orEmpty())
            ensureActive()
            detail.value = result
            hasHeaderDetail.value = result.songCount > 0 || result.albumCount > 0 || result.intro != null
            isFollowed.value = ArtistPresentationCache.followed(id, account.userid.orEmpty()) ?: result.isFollowed
            headerLoaded.value = true
            headerAccount.value = account.userid
            loadedRetry.value = retry.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            resolveError.value = context.getString(R.string.load_more_failed) +
                e.message?.takeIf { it.isNotBlank() }?.let { "\n$it" }.orEmpty()
        } finally {
            headerLoading.value = false
        }
    }

    LaunchedEffect(songsState, hotRetry.value) {
        val id = songsState?.artistId ?: return@LaunchedEffect
        if (hotLoadedRetry.value == hotRetry.value && hotError.value == null) return@LaunchedEffect
        hotLoading.value = true
        hotError.value = null
        try {
            val result = KugouRepository.getArtistAudios(id, page = 1, pageSize = 12, sort = "hot").getOrThrow()
            ensureActive()
            hotSongs.value = result
            hotLoadedRetry.value = hotRetry.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            hotError.value = e.message ?: context.getString(R.string.load_more_failed)
        } finally {
            hotLoading.value = false
        }
    }
    LaunchedEffect(songsState, artistName, albumsRetry.value) {
        val id = songsState?.artistId ?: return@LaunchedEffect
        if (albumsLoadedRetry.value == albumsRetry.value && albumsError.value == null) return@LaunchedEffect
        albumsLoading.value = true
        albumsError.value = null
        try {
            val result = KugouRepository.getVerifiedArtistAlbums(id, artistName).getOrThrow()
            ensureActive()
            albums.value = result
            albumsLoadedRetry.value = albumsRetry.value
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            albumsError.value = e.message ?: context.getString(R.string.load_more_failed)
        } finally {
            albumsLoading.value = false
        }
    }

    // 调色板计算：用 fire-and-forget（在缓存的 scope 上跑），不绑定本页生命周期。
    // 这样即便用户在本页提取色算完前就点进专辑、本页离开组合，计算也不会被取消，
    // 结果照常写回缓存，专辑详情页随后自动上色（修复「背景变白、要退出重进才恢复」）。
    LaunchedEffect(paletteKey, avatarUrl) {
        val url = avatarUrl ?: return@LaunchedEffect
        val cacheKey = paletteKey ?: return@LaunchedEffect
        ArtistPresentationCache.ensurePalette(cacheKey) {
            val drawable = context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
            ).drawable ?: return@ensurePalette null
            val bitmap = drawable.toBitmap()
            withContext(Dispatchers.Default) { sampleArtistBottomColor(bitmap, heroAspect) }
        }
    }

    // 头像预热：详情解析出头像 URL 后，用与英雄图**完全一致的缓存键**预取。
    // 快速进出/返回时英雄图直接命中内存缓存，不再白屏等待照片。
    LaunchedEffect(avatarUrl) {
        val url = avatarUrl ?: return@LaunchedEffect
        runCatching {
            context.imageLoader.execute(
                ImageRequest.Builder(context).data(url).size(1024).allowHardware(true)
                    .memoryCacheKey("artist-hero:$url").diskCacheKey(url).build()
            )
        }
    }

    LaunchedEffect(pagerState, songsState) {
        val state = songsState ?: return@LaunchedEffect
        snapshotFlow {
            ArtistPagingSnapshot(
                pagerState.currentPage, state.songs.value.size, state.page.value,
                state.loading.value, state.loadError.value, state.endReached.value
            )
        }.distinctUntilChanged().collect { snapshot ->
            if (snapshot.count > 0 && !snapshot.loading && snapshot.error == null &&
                !snapshot.endReached && snapshot.current >= (snapshot.count + 3) / 4 - 2
            ) state.requestNextPage()
        }
    }

    // 推荐专辑：以最新发布为准（ISO 形式日期字典序即可比较）；
    // 全部缺发行日期时回退接口顺序首项（酷狗作者专辑列表通常已按时间倒序）。
    val latestAlbum = remember(albums.value) {
        albums.value.filter { !it.publishTime.isNullOrBlank() }
            .maxByOrNull { it.publishTime!!.take(10) }
            ?: albums.value.firstOrNull()
    }
    // 推荐专辑歌曲数：作者专辑与专辑详情元数据都可能缺 songcount（返回 0）；
    // 详情页展示的真实数量来自 /album/songs 实际条数，故按
    // 「元数据 songcount → getAlbumSongs 条数」兜底，仍拿不到则不显示该行。
    // 初值优先读 ArtistPresentationCache（跨页面/跨进程），这样从艺人列表
    // 重新进入本页时立即可显示；命不中才走网络并在成功后写回缓存。
    val latestAlbumCount = rememberPageData("artist.latestAlbumCount") {
        latestAlbum?.let { ArtistPresentationCache.albumSongCount(it.albumId) } ?: -1
    }
    LaunchedEffect(latestAlbum?.albumId, latestAlbum?.songCount) {
        val album = latestAlbum ?: return@LaunchedEffect
        if (album.songCount > 0) {
            latestAlbumCount.value = album.songCount
            ArtistPresentationCache.rememberAlbumSongCount(album.albumId, album.songCount)
            return@LaunchedEffect
        }
        if (latestAlbumCount.value > 0) return@LaunchedEffect
        // 新返回栈条目首次组合时 albums 还未加载，PageData 初值必然取不到缓存；
        // 专辑列表到位后这里补一次缓存查询，命中即直接显示、零请求。
        ArtistPresentationCache.albumSongCount(album.albumId)?.takeIf { it > 0 }?.let {
            latestAlbumCount.value = it
            return@LaunchedEffect
        }
        val meta = runCatching { KugouRepository.getAlbumDetail(album.albumId) }.getOrNull()?.getOrNull()
        meta?.songCount?.takeIf { it > 0 }?.let {
            latestAlbumCount.value = it
            ArtistPresentationCache.rememberAlbumSongCount(album.albumId, it)
            return@LaunchedEffect
        }
        val songs = runCatching { KugouRepository.getAlbumSongs(album.albumId) }.getOrNull()?.getOrNull()
        if (!songs.isNullOrEmpty()) {
            latestAlbumCount.value = songs.size
            ArtistPresentationCache.rememberAlbumSongCount(album.albumId, songs.size)
        }
    }

    fun playAt(list: List<KugouNewSong>, index: Int) {        if (index !in list.indices) return
        scope.launch(Dispatchers.IO) {
            val queue = list.map { KugouRepository.toQueueMediaItem(it) }
            MediaController.prepare(queue[index], queue)
        }
    }

    fun toggleFollow() {
        if (followBusy.value || headerLoading.value || !headerLoaded.value) return
        if (!account.isLoggedIn) {
            navController.navigate(UI.Settings.KugouLogin)
            return
        }
        val id = resolvedId.value.takeIf { it.isNotBlank() } ?: return
        val userId = account.userid
        val followedArtist = KugouFollowedArtist(
            artistId = id,
            name = detail.value?.name?.takeIf { it.isNotBlank() } ?: artistName,
            avatarUrl = detail.value?.avatarUrl
        )
        followBusy.value = true
        val target = !isFollowed.value
        isFollowed.value = target
        ArtistPresentationCache.setFollowed(id, userId.orEmpty(), target)
        // 乐观回写收藏艺人列表（在线音乐-艺人页的数据源），失败回滚时反转
        FollowedArtistsObject.applyFollowChange(followedArtist, target)
        scope.launch {
            try {
                val result = if (target) KugouRepository.followArtist(id) else KugouRepository.unfollowArtist(id)
                if (account.userid == userId) result.onFailure {
                    isFollowed.value = !target
                    ArtistPresentationCache.setFollowed(id, userId.orEmpty(), !target)
                    FollowedArtistsObject.applyFollowChange(followedArtist, !target)
                }
            } catch (e: CancellationException) {
                if (account.userid == userId) {
                    isFollowed.value = !target
                    ArtistPresentationCache.setFollowed(id, userId.orEmpty(), !target)
                    FollowedArtistsObject.applyFollowChange(followedArtist, !target)
                }
                throw e
            } catch (_: Exception) {
                if (account.userid == userId) {
                    isFollowed.value = !target
                    ArtistPresentationCache.setFollowed(id, userId.orEmpty(), !target)
                    FollowedArtistsObject.applyFollowChange(followedArtist, !target)
                }
            } finally {
                followBusy.value = false
            }
        }
    }

    CompositionLocalProvider(LocalContentColor provides contentColor) {
    BoxWithConstraints(Modifier.fillMaxSize().drawBehind { drawRect(bgColorState.value) }) {
        // 平板竖屏内容整体上抬 40dp：方形 hero 让列表起始过低，直接减在 hero 高度上
        // （其余比例与此前的 SquareFullBleed 方案一致，hero 略小于正方，裁切可忽略）。
        val heroHeight = (if (portraitTablet)
            minOf(maxWidth, maxHeight * HeroTabletMaxHeightFraction) - HeroTabletContentRaiseDp.dp
        else maxHeight * HeroPhoneHeightFraction).coerceAtLeast(200.dp)
    // BoxWithConstraints 作用域内捕获：item lambda 有自己的接收者，禁止隐式访问外层 maxWidth
    val heroMaxWidth = maxWidth
        // 倒影带 + 照片：静态内容在后台线程一次性烘焙成一张位图（滚动零成本平移；
        // 实时 RenderEffect 层每帧重执行着色器是滑动卡顿的根源）。烘焙前 item 内的
        // AsyncImage 照常先显示，烘焙完成后背景层（照片+倒影）盖在列表之下。LazyColumn
        // 会把 item 内容裁切在 item 边界内，倒影因此画在列表之外，位置按 Hero item 的
        // 视口 offset 逐帧跟随；Hero item 在照片底边越过视口顶的瞬间即被回收，而倒影
        // 此后还应可见 bandHeight——门禁按照片底边余量线性渐隐，不跳变不提前消失。
        // 平板方形 hero 会让 0.5*heroHeight 的倒影带胀到约 1/3 屏高（首屏被压没），故限幅；
        // 手机带高约 200dp，260dp 上限不误伤，公式保持原样。
        val bandHeight = if (portraitTablet)
            minOf(heroHeight * ArtistReflectionFraction, HeroTabletReflectionBandMaxDp.dp)
        else heroHeight * ArtistReflectionFraction
        val density = LocalDensity.current
        val heroHeightPx = with(density) { heroHeight.toPx() }
        val bandHeightPx = with(density) { bandHeight.toPx() }
        val bakeWidthPx = with(density) { heroMaxWidth.toPx() }
        val maxBlurPx = with(density) { ArtistReflectionMaxBlurDp.dp.toPx() }
        var bakedHero by remember(bakeWidthPx, heroHeightPx, bandHeightPx) {
            mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        }
        LaunchedEffect(detail.value?.avatarUrl, bakeWidthPx, heroHeightPx, bandHeightPx) {
            // 平板横屏走横向头部（照片在左、无「照片底边向下」区域），不烘焙倒影。
            if (landscapeTablet) return@LaunchedEffect
            val url = detail.value?.avatarUrl ?: return@LaunchedEffect
            bakedHero = null
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    // 烘焙在后台软件画布上进行，需要软件位图（HARDWARE 位图不可离屏像素操作）
                    .allowHardware(false)
                    .size(1024)
                    // 独立缓存键：预热/上屏走 HARDWARE 位图缓存，互不污染
                    .memoryCacheKey("artist-hero-bake:${url}")
                    .diskCacheKey(url)
                    .build()
            )
            val src = result.drawable?.toBitmap() ?: return@LaunchedEffect
            // 烘焙分辨率封顶到源图（≤1024px）：源图本就只有 1024px，全宽烘焙（平板 ~2000px）
            // 等于把源图放大 2 倍塞进 ~21MB 位图，零画质收益却极易 OOM/超时 → 概率性烘焙失败
            // → 倒影缺失的硬衔接。这里按比例缩小几何与模糊半径一起烘焙，绘制时再放大回整盒。
            val bakeScale = (HeroBakeMaxWidthPx / bakeWidthPx).coerceAtMost(1f)
            val bakeW = (bakeWidthPx * bakeScale).roundToInt().coerceAtLeast(1)
            val bakePhotoH = (heroHeightPx * bakeScale).roundToInt().coerceAtLeast(1)
            val bakeBandH = (bandHeightPx * bakeScale).roundToInt().coerceAtLeast(1)
            val bakeBlurPx = maxBlurPx * bakeScale
            // 平板竖屏用「边缘延展」（照片底边纵向延展 + 渐进模糊溶入提取色），不做镜像——
            // 方形 hero 的镜像会把下半张人脸翻转贴上来，观感像第二张脸。手机保留原镜像倒影。
            val edgeReflection = portraitTablet
            val shader = newArtistHeroReflectionShader(
                bakeW, bakePhotoH, bakeBandH, bakeBlurPx, edgeReflection
            )
            bakedHero = withContext(Dispatchers.Default) {
                runCatching {
                    bakeHeroReflection(src, bakeW, bakePhotoH, bakeBandH, bakeBlurPx, shader, edgeReflection)
                }
                    // 烘焙失败会退回「锐利照片 + 提取色」硬衔接，必须留痕（debug 包看 logcat）
                    .onFailure { android.util.Log.w("ArtistDetail", "hero reflection bake failed (${bakeW}x${bakePhotoH + bakeBandH})", it) }
                    .getOrNull()
            }?.asImageBitmap()
        }
        val heroViewportTop = remember(listState) {
            derivedStateOf {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "Hero" }?.offset
            }
        }
        @Composable fun HeroPhoto(modifier: Modifier) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(detail.value?.avatarUrl)
                    .error(R.drawable.songcredits_monogram_person)
                    .placeholder(R.drawable.songcredits_monogram_person)
                    .fallback(R.drawable.songcredits_monogram_person)
                    .allowHardware(true)
                    .size(1024)
                    // 与上方预热同键，命中内存缓存即刻显示
                    .memoryCacheKey("artist-hero:${detail.value?.avatarUrl}")
                    .diskCacheKey(detail.value?.avatarUrl)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
        ) {
            // 平板横屏无倒影背景层（bakedHero 恒为 null，这里也不再挂空绘制层）。
            if (!landscapeTablet) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, heroViewportTop.value ?: 0) }
                        .height(heroHeight + bandHeight)
                        .graphicsLayer {
                            val o = heroViewportTop.value
                            alpha = if (o == null) 0f
                            else (((o + heroHeightPx) / bandHeightPx).coerceIn(0f, 1f))
                        }
                        .drawWithContent {
                            // 烘焙位图宽可能小于盒子（按源图 1024px 封顶），按盒子尺寸放大绘制
                            bakedHero?.let {
                                drawImage(
                                    it,
                                    dstSize = androidx.compose.ui.unit.IntSize(
                                        size.width.roundToInt(), size.height.roundToInt()
                                    )
                                )
                            }
                        }
                )
            }
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical(allowTopOverscroll = false),
                // 平板横屏改用横向头部（非沉浸式）：列表加 54dp 顶 padding，
                // 与 RankDetail/OnlineAlbumDetail 等既有的平板详情页约定一致，
                // 头部不至于被固定的半透明顶栏遮挡。竖屏（手机/平板）保持沉浸式。
                contentPadding = if (landscapeTablet) PaddingValues(top = 54.dp) else PaddingValues(0.dp),
                overscrollEffect = null,
                flingBehavior = rememberOverscrollFlingBehavior { listState }
            ) {
                item("Hero") {
                    if (landscapeTablet) {
                        // 平板横屏：左方形头像 + 右名字/操作/数量/简介（复用既有平板详情页头部风格）。
                        ArtistTabletLandscapeHeader(
                            name = detail.value?.name?.ifEmpty { artistName } ?: artistName,
                            avatarUrl = detail.value?.avatarUrl,
                            hasIntro = !detail.value?.intro.isNullOrBlank(),
                            onIntro = { introOpen.value = true },
                            songsEnabled = songs.isNotEmpty(),
                            onPlay = { playAt(songs, 0) },
                            showFollow = resolvedId.value.isNotBlank(),
                            isFollowed = isFollowed.value,
                            followEnabled = !followBusy.value && !headerLoading.value,
                            onFollow = { toggleFollow() },
                            countsLine = if (hasHeaderDetail.value) detail.value?.let {
                                stringResource(R.string.artist_detail_songs, it.songCount) +
                                    " · " + stringResource(R.string.artist_detail_albums, it.albumCount)
                            } else null,
                            intro = detail.value?.intro,
                            loading = headerLoading.value,
                            error = resolveError.value,
                            onRetry = { retry.value += 1 }
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(heroHeight)) {
                            // 仅烘焙完成前显示锐利照片（秒出）；完成后由背景层接管照片区——
                            // 渐进模糊的路径延伸进照片下半部，item 若继续盖一张锐利照片
                            // 会把模糊段完全遮住（真机实测如此）
                            if (bakedHero == null) {
                                HeroPhoto(Modifier.fillMaxSize())
                            }
                            Text(
                                text = detail.value?.name?.ifEmpty { artistName } ?: artistName,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                                style = TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.45f), blurRadius = 32f)),
                                modifier = Modifier
                                    .align(Alignment.BottomCenter)
                                    .padding(horizontal = 24.dp)
                                    .padding(bottom = 8.dp)
                            )
                        }
                    }
                }

                // 三圆钮行与数量行只在竖屏（手机 / 平板竖屏）出现；平板横屏已并入横向头部。
                if (!landscapeTablet) {
                // 三圆钮行：i（简介弹窗）｜▶ 播放｜☆ 收藏（静态圆钮，收藏后实心红）
                item("Actions") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 14.dp, bottom = 10.dp),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val intro = detail.value?.intro
                        if (!intro.isNullOrBlank()) {
                            // 注意：这里不能用 LiquidTopBarButton（消费 pageBackdrop）——
                            // 按钮在 pageBackdrop 录制层内部，采样源包含自身会构成
                            // RenderNode 自引用，RenderThread 无限递归原生栈溢出秒崩
                            ArtistRoundActionButton(
                                icon = R.drawable.ic_mini_info,
                                contentDescription = stringResource(id = R.string.artist_detail_intro_title),
                                iconTint = if (introOpen.value) Color.White else Color.Unspecified,
                                highlighted = introOpen.value,
                                iconSize = 32.dp,
                                onClick = { introOpen.value = true }
                            )
                            Spacer(Modifier.width(26.dp))
                        }
                        // EvenOdd 几何镂空不依赖离屏清除，孔洞始终透出真实背景。
                        ArtistPlayButton(enabled = songs.isNotEmpty()) { playAt(songs, 0) }
        if (resolvedId.value.isNotBlank()) {
                            Spacer(Modifier.width(26.dp))
                            // 收藏钮：无任何动效的静态圆钮，背景常驻；
                            // 收藏后只有星星变红色实心，圆底样式不变，切换瞬时完成。
                            ArtistRoundActionButton(
                                icon = if (isFollowed.value) R.drawable.ic_artist_star_fill
                                else R.drawable.ic_artist_star,
                                contentDescription = stringResource(
                                    id = if (isFollowed.value) R.string.artist_detail_unfollow
                                    else R.string.artist_detail_follow
                                ),
                                iconTint = if (isFollowed.value) {
                                    if (isDark) Color(0xFFFF453A) else Color(0xFFE53935)
                                } else Color.Unspecified,
                                highlighted = false,
                                iconSize = 28.dp,
                                motionEffects = false,
                                dimWhenDisabled = false,
                                enabled = !followBusy.value && !headerLoading.value,
                                onClick = { toggleFollow() }
                            )
                        }
                    }
                }

                item("HeaderStatus") {
                    Column(Modifier.fillMaxWidth()) {
                        if (headerLoading.value) ArtistHeaderSkeleton()
                        if (hasHeaderDetail.value) {
                            detail.value?.let { info ->
                                // 次级信息"微光"分流：暗取色底 = 白 + Plus 加色（播放页歌手名
                                // 同款质感：加色混合把白当光加进背景）；亮取色底 = 深色弱化
                                // （亮底上白字无论普通混合还是加色都必然发淡融底）。阈值与
                                // ArtistArtworkColors.content 的明暗判定一致（0.179）。
                                val lightBackdrop = bgColorState.value.luminance() > 0.179f
                                Text(
                                    text = stringResource(R.string.artist_detail_songs, info.songCount) +
                                        " · " + stringResource(R.string.artist_detail_albums, info.albumCount),
                                    fontSize = 14.sp,
                                    color = if (lightBackdrop) Color(0xFF161616).copy(alpha = 0.55f)
                                            else Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                        .padding(bottom = 8.dp)
                                        .then(if (lightBackdrop) Modifier else Modifier.overlayEffect())
                                )
                            }
                        }
                        resolveError.value?.let { error ->
                            ArtistSectionError(error, headerLoading.value) { retry.value += 1 }
                        }
                    }
                }
                }
                if (songsState != null) {
                    latestAlbum?.let { album ->
                        item("LatestAlbum") {
                            // 组合期同步兜底缓存：从列表重新进入时，卡片与数量同帧出现；
                            // LaunchedEffect 只负责命不中时的网络解析与写回。
                            val count = if (latestAlbumCount.value > 0) latestAlbumCount.value
                            else ArtistPresentationCache.albumSongCount(album.albumId) ?: -1
                            ArtistLatestAlbumCard(
                                album = album,
                                songCount = count,
                                underlayColor = bgColorState.value,
                                sharedTransitionScope = sharedTransitionScope,
                                animatedVisibilityScope = animatedVisibilityScope
                            ) { selected ->
                                // 防抖：转场窗口内忽略第二下误触（见 NavGuard）
                                NavGuard.run {
                                    OnlineAlbumObject.setSelected(selected)
                                    // 带 albumId + 来源歌手 id：多层堆叠各自解析、背景取本歌手色
                                    navController.navigate(
                                        UI.onlineAlbumRoute(selected.albumId, resolvedId.value)
                                    )
                                }
                            }
                        }
                    }
                    item("HotHeader") {
                        // 标题整行可点 → 热门歌曲大全页（与「全部歌曲」同款）
                        ArtistSectionHeader(stringResource(R.string.artist_detail_hot_songs)) {
                            navController.navigate(UI.artistHotSongsRoute(resolvedId.value, artistName))
                        }
                    }
                    item("HotSongs") {
                        ArtistSongSection(
                            songs = hotSongs.value,
                            loading = hotLoading.value,
                            error = hotError.value,
                            pagerState = hotPagerState,
                            onRetry = { hotRetry.value += 1 },
                            onPlayAt = { playAt(hotSongs.value, it) }
                        )
                    }
                    item("AlbumsHeader") {
                        // 标题整行可点 → 专辑大全页（与「全部歌曲」同款）
                        ArtistSectionHeader(stringResource(R.string.artist_detail_albums_section)) {
                            navController.navigate(UI.artistAlbumsRoute(resolvedId.value, artistName))
                        }
                    }
                    item("Albums") {
                        ArtistAlbumsSection(
                            albums = albums.value,
                            loading = albumsLoading.value,
                            error = albumsError.value,
                            onRetry = { albumsRetry.value += 1 },
                            sharedTransitionScope = sharedTransitionScope,
                            animatedVisibilityScope = animatedVisibilityScope,
                            onOpen = { album ->
                                // 防抖：记录本次入口时间戳，堵住「第二下落到详情页歌手名链接」的穿透
                                NavGuard.run {
                                    OnlineAlbumObject.setSelected(album)
                                    navController.navigate(
                                        UI.onlineAlbumRoute(album.albumId, resolvedId.value)
                                    )
                                }
                            }
                        )
                    }
                    item("AllSongsHeader") {
                        ArtistSectionHeader(stringResource(R.string.artist_detail_all_songs)) {
                            navController.navigate(UI.artistSongsRoute(resolvedId.value, artistName))
                        }
                    }
                    item("AllSongs") {
                        ArtistSongSection(
                            songs = songs,
                            loading = songsState.loading.value,
                            error = songsState.loadError.value,
                            pagerState = pagerState,
                            onRetry = { songsState.requestNextPage() },
                            onPlayAt = { playAt(songs, it) }
                        )
                    }
                } else if (headerLoading.value) {
                    item("ResolvingSongs") { ArtistSongsSkeleton() }
                }

                item("navbar") {
                    Spacer(modifier = Modifier.navigationBarsHeight(134.dp))
                }
            }
        }

        // 顶栏浮层：材质和设置页 TitleBar 保持一致；采样消费者位于内容录制层之外。
        // 液态玻璃分支维持「一滚动就浮现」；普通（非玻璃）分支与标题同源：Hero 完全滚出才浮现。
        val barVisible = listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        val barVisibleNoGlass = heroScrolledOut.value
        val barAlpha by animateFloatAsState(if (barVisible) 1f else 0f, tween(150), label = "artistBarAlpha")
        Box(
            Modifier
                .fillMaxWidth()
                .zIndex(1f)
        ) {
            androidx.compose.animation.AnimatedVisibility(
                visible = if (SettingsLibrary.BarBlurEffect) barVisible else barVisibleNoGlass,
                modifier = Modifier.matchParentSize(),
                enter = androidx.compose.animation.fadeIn(tween(120)),
                exit = androidx.compose.animation.fadeOut(tween(120))
            ) {
                Spacer(
                    Modifier
                        .then(
                            if (SettingsLibrary.BarBlurEffect) {
                                yos.music.player.ui.widgets.basic.titleBarProgressiveBlur(
                                    backdrop = pageBackdrop,
                                    tintColor = bgColorState.value
                                )
                            } else {
                                Modifier.drawPlainBackdrop(
                                    backdrop = pageBackdrop,
                                    shape = { androidx.compose.ui.graphics.RectangleShape },
                                    effects = { blur(14.dp.toPx()) },
                                    onDrawSurface = { drawRect(bgColorState.value.copy(alpha = 0.7f)) }
                                )
                            }
                        )
                        .fillMaxSize()
                )
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(54.dp)
            ) {
                // padding 在 height 之前（与 Title.kt 顶栏同构）：若写在 height 之后，
                // 5dp 内边距会把 48dp 内容区压成 43dp，返回钮整体上浮 2.5dp。
                Box(Modifier.fillMaxWidth().padding(top = 5.dp).height(48.dp)) {
                    Box(Modifier.align(Alignment.CenterStart).padding(start = 16.5.dp)) {
                        if (SettingsLibrary.BarBlurEffect) {
                            LiquidTopBarButton(
                                onClick = { navController.popBackStack() },
                                backdrop = pageBackdrop,
                                icon = painterResource(id = R.drawable.ic_back),
                                contentDescription = stringResource(R.string.artist_detail_back),
                                iconSize = 18.dp,
                                iconOffset = DpOffset((-2).dp, 0.dp),
                                backdropAlpha = { barAlpha },
                                shadowAlpha = { barAlpha },
                                draggable = true,
                                adaptiveLuminance = ArtistPageAppearance.activeEntryId.value == entryId
                            )
                        } else {
                            Box(
                                Modifier.size(42.dp).clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { navController.popBackStack() }
                                ),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_back),
                                    contentDescription = stringResource(R.string.artist_detail_back),
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                    Text(
                        text = detail.value?.name?.ifEmpty { artistName } ?: artistName,
                        fontSize = 18.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.Center)
                            .padding(horizontal = 70.dp)
                            .alpha(smallTitleAlpha)
                    )
                }
                if (!SettingsLibrary.BarBlurEffect) {
                    // 对齐必须挂在 AnimatedVisibility 本身（外层 Box 的直接子项），
                    // 写在内容 lambda 里的 Spacer 上会被其内部容器吞掉，分割线会停在顶栏顶端。
                    androidx.compose.animation.AnimatedVisibility(
                        visible = barVisibleNoGlass,
                        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                    ) {
                        Spacer(
                            Modifier.fillMaxWidth()
                                .alpha(0.12f).height(1.dp).background(Color.Black withNight Color.White)
                        )
                    }
                }
            }
        }

        ArtistIntroSheet(
            visible = introOpen.value,
            title = stringResource(id = R.string.artist_detail_intro_title),
            content = detail.value?.intro.orEmpty(),
            backdrop = pageBackdrop,
            onDismiss = { introOpen.value = false }
        )
    }
    }
}

/**
 * 动作区半透明圆钮（不消费 backdrop）：处于 pageBackdrop 录制层内部，
 * 任何 drawBackdrop 消费者都会构成自引用递归崩溃，故用实色半透明圆。
 * 填充为半透明白，透明度与「最新专辑」卡片同值（浅 0.22 / 深 0.12）；
 * 图标统一白色；高亮（highlighted，如简介展开）分支保持不变。
 */
@Composable
private fun ArtistRoundActionButton(
    icon: Int,
    contentDescription: String?,
    iconTint: Color,
    highlighted: Boolean,
    enabled: Boolean = true,
    iconSize: androidx.compose.ui.unit.Dp = 23.dp,
    highlightContainerColor: Color? = null,
    motionEffects: Boolean = true,
    dimWhenDisabled: Boolean = true,
    onClick: () -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.96f else 1f, tween(120), label = "artistActionPress")
    Box(
        modifier = Modifier
            .then(if (motionEffects) Modifier.graphicsLayer { scaleX = scale; scaleY = scale } else Modifier)
            .size(42.dp)
            .clip(CircleShape)
            .background(
                when {
                    highlighted -> highlightContainerColor ?: MaterialTheme.colorScheme.primary.copy(alpha = 0.55f)
                    else -> Color.White.copy(alpha = if (isDark) 0.12f else 0.22f)
                }
            )
            // 圆钮高光：上下同强的纯色白描边（用户定版 alpha 0.1）
            .border(
                width = 0.75.dp,
                color = Color.White.copy(alpha = 0.10f),
                shape = CircleShape
            )
            .alpha(if (dimWhenDisabled && !enabled) 0.45f else 1f)
            .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = icon),
            contentDescription = contentDescription,
            modifier = Modifier.size(iconSize),
            tint = when {
                iconTint != Color.Unspecified -> iconTint
                else -> Color.White
            }
        )
    }
}

/**
 * 播放圆钮：EvenOdd 几何镂空，孔洞始终透出真实背景（不依赖离屏清除）。
 * 从原三圆钮行内联抽出，供竖向三圆钮行与平板横屏头部复用。
 */
@Composable
private fun ArtistPlayButton(enabled: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(64.dp)
            .drawBehind {
                // 三角形：左移 4% 圆角放大；右侧尖角圆角必须沿上下两边的切线方向收缩
                // （控制点落在边的方向上），否则会呈现「尖角外贴圆点」的伪影（用户截图实锤）
                val path = Path().apply {
                    val w = size.width
                    val h = size.height
                    moveTo(w * 0.37f, h * 0.33f)
                    cubicTo(w * 0.37f, h * 0.2925f, w * 0.389f, h * 0.28f, w * 0.42f, h * 0.299f)
                    lineTo(w * 0.678f, h * 0.475f)
                    cubicTo(w * 0.701f, h * 0.491f, w * 0.701f, h * 0.510f, w * 0.677f, h * 0.525f)
                    lineTo(w * 0.42f, h * 0.695f)
                    cubicTo(w * 0.389f, h * 0.714f, w * 0.37f, h * 0.701f, w * 0.37f, h * 0.664f)
                    close()
                }
                val button = Path().apply {
                    fillType = PathFillType.EvenOdd
                    addOval(Rect(Offset.Zero, size))
                    addPath(path)
                }
                drawPath(button, color = Color.White)
            }
            .circleEdgeLight(Color.White.copy(alpha = 0.8f), 0.15.dp, 0.5.dp)
            .semantics { contentDescription = context.getString(R.string.normal_button_play) }
            .alpha(if (enabled) 1f else 0.45f)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
    )
}

/**
 * 平板横屏头部：左方形头像 + 右「名字 / 三圆钮 / 数量 / 简介」信息列，
 * 复用其它平板详情页（DetailPageHeader）的横向头部几何与 Apple Music 观感。
 * 本页横屏为非沉浸式（外层 LazyColumn 已加 54dp 顶 padding），故这里再 statusBarsPadding()
 * 让内容落到固定顶栏之下。注意：本组件处于 pageBackdrop 录制层内部，禁止使用任何
 * drawBackdrop 消费者（采样源含自身 → RenderNode 自引用递归 → RenderThread 原生栈溢出秒崩），
 * 故操作钮只用实色半透明圆钮（ArtistRoundActionButton）。
 */
@Composable
private fun ArtistTabletLandscapeHeader(
    name: String,
    avatarUrl: String?,
    hasIntro: Boolean,
    onIntro: () -> Unit,
    songsEnabled: Boolean,
    onPlay: () -> Unit,
    showFollow: Boolean,
    isFollowed: Boolean,
    followEnabled: Boolean,
    onFollow: () -> Unit,
    countsLine: String?,
    intro: String?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isFlamingoInDarkMode()
    BoxWithConstraints(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(top = 14.dp, bottom = 8.dp)
            .padding(horizontal = 18.dp)
    ) {
        // 方形封面随窗宽自适应；窄窗钳到下限 130dp，宽窗钳到上限 300dp（与 DetailPageHeader 同）
        val coverSize = (maxWidth * 0.28f).coerceIn(130.dp, 300.dp)
        Row(
            modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
            verticalAlignment = Alignment.Top
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(avatarUrl)
                    .error(R.drawable.songcredits_monogram_person)
                    .placeholder(R.drawable.songcredits_monogram_person)
                    .fallback(R.drawable.songcredits_monogram_person)
                    .allowHardware(true)
                    .size(1024)
                    // 与竖向 Hero 同键，命中内存缓存即刻显示
                    .memoryCacheKey("artist-hero:$avatarUrl")
                    .diskCacheKey(avatarUrl)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(coverSize).clip(YosRoundedCornerShape(7.dp))
            )
            Spacer(Modifier.width(15.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = name,
                    fontSize = 26.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                // 弹性空隙：把操作行推到信息列底部（与封面下缘对齐，同 DetailPageHeader）
                Spacer(Modifier.weight(1f))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (hasIntro) {
                        ArtistRoundActionButton(
                            icon = R.drawable.ic_mini_info,
                            contentDescription = stringResource(id = R.string.artist_detail_intro_title),
                            iconTint = Color.Unspecified,
                            highlighted = false,
                            iconSize = 24.dp,
                            onClick = onIntro
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                    ArtistPlayButton(enabled = songsEnabled, onClick = onPlay)
                    if (showFollow) {
                        Spacer(Modifier.width(20.dp))
                        ArtistRoundActionButton(
                            icon = if (isFollowed) R.drawable.ic_artist_star_fill else R.drawable.ic_artist_star,
                            contentDescription = stringResource(
                                id = if (isFollowed) R.string.artist_detail_unfollow
                                else R.string.artist_detail_follow
                            ),
                            iconTint = if (isFollowed) {
                                if (isDark) Color(0xFFFF453A) else Color(0xFFE53935)
                            } else Color.Unspecified,
                            highlighted = false,
                            iconSize = 28.dp,
                            motionEffects = false,
                            dimWhenDisabled = false,
                            enabled = followEnabled,
                            onClick = onFollow
                        )
                    }
                }
                if (countsLine != null) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = countsLine,
                        fontSize = 14.sp,
                        color = LocalContentColor.current.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (!intro.isNullOrBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(
                        text = intro,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        color = LocalContentColor.current.copy(alpha = 0.5f),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                if (loading) {
                    Spacer(Modifier.height(10.dp))
                    ArtistHeaderSkeleton()
                }
                if (error != null) {
                    Spacer(Modifier.height(6.dp))
                    ArtistSectionError(error, loading, onRetry)
                }
            }
        }
    }
}

/** Match ContentScale.Crop and average the bottom 28%, weighted toward the lower edge. */
internal fun sampleArtistBottomColor(bitmap: android.graphics.Bitmap, heroAspect: Float): Int? {
    val imageAspect = bitmap.width.toFloat() / bitmap.height
    val cropWidth = if (imageAspect > heroAspect) (bitmap.height * heroAspect).toInt().coerceIn(1, bitmap.width) else bitmap.width
    val cropHeight = if (imageAspect < heroAspect) (bitmap.width / heroAspect).toInt().coerceIn(1, bitmap.height) else bitmap.height
    val left = (bitmap.width - cropWidth) / 2
    val top = (bitmap.height - cropHeight) / 2
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var total = 0.0
    val bottomStart = (cropHeight * 0.72f).toInt()
    for (y in bottomStart until cropHeight) {
        val lowerWeight = 1.0 + 3.0 * (y - bottomStart) / (cropHeight - bottomStart).coerceAtLeast(1)
        for (x in 0 until cropWidth) {
            val pixel = bitmap.getPixel(left + x, top + y)
            val weight = lowerWeight * AndroidColor.alpha(pixel) / 255.0
            red += AndroidColor.red(pixel) * weight
            green += AndroidColor.green(pixel) * weight
            blue += AndroidColor.blue(pixel) * weight
            total += weight
        }
    }
    if (total == 0.0) return null
    return AndroidColor.rgb((red / total).toInt(), (green / total).toInt(), (blue / total).toInt())
}
