package yos.music.player.ui.pages.library.artists

import android.graphics.Color as AndroidColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.graphics.drawable.toBitmap
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
@Composable
fun ArtistDetail(
    navController: NavController,
    artistId: String?,
    artistName: String,
    entryId: String,
    scrimTransition: PageScrimTransition
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
    val heroAspect = configuration.screenWidthDp.toFloat() /
        (configuration.screenHeightDp * 0.6f).coerceAtLeast(200f)
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

    LaunchedEffect(songsState, avatarUrl, paletteKey) {
        val url = avatarUrl ?: return@LaunchedEffect
        val cacheKey = paletteKey ?: return@LaunchedEffect
        try {
            ArtistPresentationCache.loadPalette(cacheKey) {
                val drawable = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(url).size(128).allowHardware(false).build()
                ).drawable ?: return@loadPalette null
                val bitmap = drawable.toBitmap()
                withContext(Dispatchers.Default) { sampleArtistBottomColor(bitmap, heroAspect) }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Artwork failure leaves the theme fallback in place.
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

    fun playAt(list: List<KugouNewSong>, index: Int) {
        if (index !in list.indices) return
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
        val heroHeight = (maxHeight * 0.6f).coerceAtLeast(200.dp)

        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .overScrollVertical(allowTopOverscroll = false),
                overscrollEffect = null,
                flingBehavior = rememberOverscrollFlingBehavior { listState }
            ) {
                item("Hero") {
                    Box(Modifier.fillMaxWidth().height(heroHeight)) {
                        AsyncImage(
                            model = ImageRequest.Builder(context)
                                .data(detail.value?.avatarUrl)
                                .error(R.drawable.songcredits_monogram_person)
                                .placeholder(R.drawable.songcredits_monogram_person)
                                .fallback(R.drawable.songcredits_monogram_person)
                                .allowHardware(true)
                                .size(1024)
                                .build(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        // 封面 → 提取色自然过渡（绘制期直读动画色，与页面背景同步）
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .align(Alignment.BottomCenter)
                                .drawBehind {
                                    drawRect(
                                        Brush.verticalGradient(
                                            0f to bgColorState.value.copy(alpha = 0f),
                                            1f to bgColorState.value,
                                            startY = 0f, endY = size.height
                                        ),
                                        size = Size(size.width, size.height + 24.dp.toPx())
                                    )
                                    drawRect(Brush.verticalGradient(
                                        0f to Color.Transparent,
                                        0.65f to Color.Black.copy(alpha = 0.23f),
                                        1f to Color.Transparent
                                    ))
                                }
                        )
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
                                .padding(bottom = 16.dp)
                        )
                    }
                }

                // 三圆钮行：i（简介弹窗）｜▶ 播放｜☆ 收藏（静态圆钮，收藏后实心红）
                item("Actions") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 20.dp),
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
                                onClick = { introOpen.value = true }
                            )
                            Spacer(Modifier.width(26.dp))
                        }
                        // EvenOdd 几何镂空不依赖离屏清除，孔洞始终透出真实背景。
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .drawBehind {
                                    val path = Path().apply {
                                        val w = size.width
                                        val h = size.height
                                        moveTo(w * 0.40f, h * 0.33f)
                                        cubicTo(w * 0.40f, h * 0.30f, w * 0.415f, h * 0.29f, w * 0.44f, h * 0.305f)
                                        lineTo(w * 0.70f, h * 0.47f)
                                        cubicTo(w * 0.735f, h * 0.49f, w * 0.735f, h * 0.51f, w * 0.70f, h * 0.53f)
                                        lineTo(w * 0.44f, h * 0.695f)
                                        cubicTo(w * 0.415f, h * 0.71f, w * 0.40f, h * 0.70f, w * 0.40f, h * 0.67f)
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
                                .alpha(if (songs.isEmpty()) 0.45f else 1f)
                                .clickable(enabled = songs.isNotEmpty(), role = Role.Button) { playAt(songs, 0) }
                        )
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
                                iconSize = 23.dp,
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
                                Text(
                                    text = stringResource(R.string.artist_detail_songs, info.songCount) +
                                        " · " + stringResource(R.string.artist_detail_albums, info.albumCount),
                                    fontSize = 14.sp,
                                    modifier = Modifier.align(Alignment.CenterHorizontally)
                                        .padding(bottom = 16.dp).alpha(0.65f)
                                )
                            }
                        }
                        resolveError.value?.let { error ->
                            ArtistSectionError(error, headerLoading.value) { retry.value += 1 }
                        }
                    }
                }
                if (songsState != null) {
                    item("HotHeader") {
                        ArtistSectionHeader(stringResource(R.string.artist_detail_hot_songs))
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
                        ArtistSectionHeader(stringResource(R.string.artist_detail_albums_section))
                    }
                    item("Albums") {
                        ArtistAlbumsSection(
                            albums = albums.value,
                            loading = albumsLoading.value,
                            error = albumsError.value,
                            onRetry = { albumsRetry.value += 1 },
                            onOpen = { album ->
                                OnlineAlbumObject.setSelected(album)
                                navController.navigate(UI.OnlineAlbumDetail)
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
                Box(Modifier.fillMaxWidth().height(48.dp).padding(top = 5.dp)) {
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
 * 任何 drawBackdrop 消费者都会构成自引用递归崩溃，故用实色半透明圆
 * （容器色对齐 LiquidTopBarButton 默认：浅色白 0.76 / 深色 #242424 0.84）。
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
                    isDark -> Color(0xFF242424).copy(alpha = 0.84f)
                    else -> Color.White.copy(alpha = 0.76f)
                }
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
                highlighted -> Color.White
                isDark -> Color.White.copy(alpha = 0.9f)
                else -> Color.Black.copy(alpha = 0.85f)
            }
        )
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
