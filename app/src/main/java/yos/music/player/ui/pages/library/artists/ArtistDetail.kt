package yos.music.player.ui.pages.library.artists

import android.graphics.Color as AndroidColor
import androidx.core.graphics.drawable.toBitmap
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Image
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
import yos.music.player.ui.widgets.basic.statusBarsPadding
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
import yos.music.player.code.utils.artist.HeroBakeCache
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
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
import yos.music.player.ui.widgets.basic.navigationBarsHeight
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawPlainBackdrop
import com.kyant.backdrop.effects.blur
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
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
import yos.music.player.ui.navigation.artistIdentityRgb
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
import yos.music.player.data.objects.KugouSyncCoordinator
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
 * 简介点击「i」后在页内联展开（item "Intro"，位于头部信息与内容区之间），不再走弹窗；
 * 正文复用「最新专辑」卡片的次级文字材质（取色明暗分流 + 暗底加色微光）。
 * 关注按钮需登录（乐观更新，失败回滚）。
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
    val isLandscape = screenWidthDp > screenHeightDp
    // 头像 hero 定形：手机竖屏沿用旧版「屏高 45% × 整宽」（逐字不变）；平板竖屏改为「按宽度取方形
    // 铺满」（屏比 <1 时 heroHeight=maxWidth，1:1 头像不再被上下重度裁切），横屏（手机/平板）
    // 统一走方形头像（头像是方形封面）。heroAspect 必须与下方渲染盒逐支同形——它同时喂给取色采样
    // (sampleArtistBottomColor) 与 paletteKey，否则取色裁窗与 Crop 会错位、顶栏 tint 也偏色。
    val heroAspect = when {
        portraitTablet -> screenWidthDp /
            (minOf(screenWidthDp, screenHeightDp * HeroTabletMaxHeightFraction) - HeroTabletContentRaiseDp)
                .coerceAtLeast(200f)
        isLandscape -> 1f  // 横屏（手机+平板）统一方形
        else -> screenWidthDp / (screenHeightDp * HeroPhoneHeightFraction).coerceAtLeast(200f)
    }
    val avatarUrl = detail.value?.avatarUrl?.takeIf { it.isNotBlank() }
    // 无照片歌手（酷狗未上传头像 → avatarUrl 恒为 null）不能沿用「从照片取色」的链路：
    // 该链路下整页背景退化为主题兜底 0xFFF2F2F4/0xFF1B1B1D，恰好等于页面底色，hero 与
    // 内容区毫无分界，观感即「白板 + 没有背景色」。改由艺人标识派生身份色顶上。
    // 两种"无可用头像"：① avatarUrl 为空（详情已定，避免加载中误判致有图歌手闪一帧）；
    // ② 有 URL 但解码失败（heroDecodeFailed）或加载失败（heroImageFailed，Coil onError）。
    // 这两个失败状态在此处提前声明——下方取色键与身份色判定都要读它们。
    var heroImageFailed by remember(avatarUrl) { mutableStateOf(false) }
    val heroDecodeFailed = remember(avatarUrl) { mutableStateOf(false) }
    val avatarAbsent = detail.value != null && avatarUrl == null
    val noUsableAvatar = avatarAbsent || heroImageFailed || heroDecodeFailed.value
    // 身份标识：优先酷狗数值 artistId（跨语言稳定）；拿不到才退化为歌手名。
    val identitySeed = resolvedId.value.takeIf { it.isNotBlank() }
        ?: detail.value?.name?.takeIf { it.isNotBlank() }
        ?: artistName.takeIf { it.isNotBlank() }
    val identityPaletteKey = identitySeed?.let { "artist-identity:$it#$heroAspect" }
    // 取色键优先由头像 URL 派生；无可用头像时改用身份派生键；详情缓存未命中（avatarUrl
    // 未知）时回落到持久化的 "artistId#aspect → paletteKey" 索引，保证首帧就能读到缓存色，
    // 不再接近白底再渐变。
    val avatarPaletteKey = avatarUrl
        ?.takeIf { !heroImageFailed && !heroDecodeFailed.value }
        ?.let { "$it#bottom-weighted-v2-128-$heroAspect" }
    val paletteKey = avatarPaletteKey
        ?: (if (noUsableAvatar) identityPaletteKey else null)
        ?: resolvedId.value.takeIf { it.isNotBlank() }
            ?.let { ArtistPresentationCache.paletteKeyFor(it, heroAspect) }
    val paletteState = remember(paletteKey) { paletteKey?.let(ArtistPresentationCache::palette) }
    // 身份派生色：本地确定值，无需解码，首帧直接参与取色（paletteState 可能尚未落值）。
    val identityRgb = remember(identitySeed, noUsableAvatar, isDark) {
        identitySeed?.takeIf { noUsableAvatar }?.let { artistIdentityRgb(it, isDark) }
    }
    val originalRgb = paletteState?.value ?: identityRgb
    val artworkColors = remember(originalRgb, isDark) { ArtistArtworkColors.fromRgb(originalRgb, isDark) }
    val hasResolvedArtwork = originalRgb != null
    val bgColorState = animateColorAsState(
        targetValue = artworkColors.accent,
        animationSpec = if (hasResolvedArtwork) tween(600) else tween(0),
        label = "artistPageBg"
    )
    // 取色未解析（或无图歌手兜底）时页面实际铺的底色。揭示门未开期间页面透出的就是它，
    // 故「当前显示底色」的插值起点必须用它，而不是最终提取色（见下方 scrimColorState）。
    val placeholderAccent = remember(isDark) { ArtistArtworkColors.fromRgb(null, isDark).accent }
    // 页面背景色必须**组合期读取、捕获值进绘制 lambda**：此页绘制期的状态观察会断链
    // （调色板就绪后目标色已切、组合期读者全部更新，唯绘制期钉在进入前的主题兜底色
    // 浅 0xFFF2F2F4/深 0xFF1B1B1D，倒影淡出带半透明透出它 = 用户看到的「过渡带从上到下
    // 发白/发黑」）。协程采集→普通状态→绘制期直读的方案也失败，说明断链与数据源无关；
    // 捕获值走重组→modifier-diff 强制重绘，不依赖绘制期观察。
    val bg = bgColorState.value
    val bgLogHolder = remember { longArrayOf(Long.MIN_VALUE) }
    if (bgLogHolder[0] != bg.hashCode().toLong()) {
        bgLogHolder[0] = bg.hashCode().toLong()
        android.util.Log.d("HeroTrack", "bgCompose=$bg resolved=$hasResolvedArtwork")
    }
    val contentColor = if (hasResolvedArtwork) artworkColors.content
    else if (isDark) Color.White else Color(0xFF161616)
    // 底栏渐隐遮罩（NavPageVisual 底部渐变）与本页共享同一个注册色，注册的是
    // 「当前实际显示的页面底色」而非最终提取色——揭示门未开时页面还透着导航底衬，
    // 遮罩必须跟着停在兜底色，否则会出现「页面仍是主题底、底栏那段渐变却已经变成歌手
    // 提取色」的错位（它比照片/取色/倒影更早到场）。注册动作随揭示门 alpha 移到
    // BoxWithConstraints 内（那里才算得出 backdropAlpha），见下方 scrimColorState。
    // 把身份派生色落进调色板缓存：① 子页面/底栏遮罩经 palette(key) 读到同一色；
    // ② 登记 artistId#aspect → key，令「二次进入且详情缓存未命中」也能首帧取到派生色。
    // 用 LaunchedEffect（按 key 去重、只跑一次）而非 SideEffect（每次重组都跑），避免每帧触达缓存。
    LaunchedEffect(identityPaletteKey, identityRgb, heroAspect) {
        if (identityPaletteKey != null && identityRgb != null) {
            ArtistPresentationCache.seedPalette(identityPaletteKey, identityRgb)
            resolvedId.value.takeIf { it.isNotBlank() }?.let {
                ArtistPresentationCache.rememberArtistPalette(it, heroAspect, identityPaletteKey)
            }
        }
    }
    // 登记本页 artistId → 调色板缓存键：子页面按来源读缓存 State 实时取色，
    // 与艺人页是否仍在组合无关（快速点进专辑也不怕调色板被取消）
    androidx.compose.runtime.SideEffect {
        paletteKey?.let { ArtistBackdrop.bind(resolvedId.value, it) }
    }
    val seedDraw: ContentDrawScope.() -> Unit = remember(bg) {
        {
            drawRect(
                color = bg,
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
                val viaName = matches.firstOrNull { it.name.equals(artistName, ignoreCase = true) }
                    ?: matches.firstOrNull()
                // /search/artist 不做繁简/译名归一化，对新歌手/外国艺人稳定零命中
                // （实测 SIENNA SPIRO 返回 data:null）→ 改用歌曲搜索取歌曲自带的 singerid 兜底。
                val brief = viaName?.takeIf { it.singerId.isNotBlank() }
                    ?: KugouRepository.searchArtistBySongSearch(artistName)
                if (brief == null) {
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

    // 头像「单次解码」：hero 上屏、调色板采样、倒影烘焙共用同一张软件位图。
    // 此前三者各解码一次（1024 上屏 / 1024 软件烘焙 / 128 取色），同一张头像要走三遍
    // 网络/磁盘+解码，且取色被排在最后 —— 这正是「先出一张图、提取色随后才冒出」的分批
    // 出现来源。统一键 + allowHardware(false) 后：hero 上屏命中同一内存缓存（零解码），
    // 取色与烘焙复用同一张位图对象。
    val heroBitmap = remember(avatarUrl) { mutableStateOf<android.graphics.Bitmap?>(null) }
    // 单向闩锁：有缓存取色（或已有详情缓存）时首帧即揭示，不再等 1024px 异步解码落地——
    // 这是「白一下」的主因。之后只由解码 effect 单向置 true，绝不回退。
    val heroRevealed = remember(avatarUrl) {
        mutableStateOf(hasResolvedArtwork || cachedDetail != null)
    }
    LaunchedEffect(avatarUrl, heroAspect, noUsableAvatar) {
        // 无可用头像（无 url，或 url 加载/解码已失败）：无照片可取色，直接揭示，
        // hero 交给身份派生色 + monogram 分支渲染。旧实现在此静默返回，揭示门永久关闭
        // → 英雄区永久空白，正是本 bug 的机制。
        if (avatarUrl == null || heroImageFailed || heroDecodeFailed.value) {
            if (noUsableAvatar) heroRevealed.value = true
            return@LaunchedEffect
        }
        val url = avatarUrl
        val cacheKey = paletteKey ?: return@LaunchedEffect
        val bitmap = runCatching {
            context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(url)
                    // 取色/烘焙需读像素 → 必须软件位图；上屏同一张（内存缓存命中）
                    .allowHardware(false)
                    .size(1024)
                    .memoryCacheKey("artist-hero:$url")
                    .diskCacheKey(url)
                    .build()
            ).drawable?.toBitmap()
        }.getOrNull() ?: run {
            heroDecodeFailed.value = true
            heroRevealed.value = true
            return@LaunchedEffect
        }
        heroBitmap.value = bitmap
        // 取色仍在缓存自身 scope 上算（不随本页销毁取消，子页面仍能读到）；这里 await 只为
        // 「照片与提取色同帧落位」—— 取色算完当帧才揭示照片，替换掉旧的两段式 fire-and-forget。
        runCatching {
            ArtistPresentationCache.loadPalette(cacheKey) {
                withContext(Dispatchers.Default) {
                    val sampleSrc = if (bitmap.width > 128) android.graphics.Bitmap.createScaledBitmap(
                        bitmap, 128,
                        (128f * bitmap.height / bitmap.width).roundToInt().coerceAtLeast(1), false
                    ) else bitmap
                    sampleArtistBottomColor(sampleSrc, heroAspect)
                }
            }
        }
        // 登记 artistId#aspect → paletteKey，供下次（含详情缓存未命中）首帧反查取色
        resolvedId.value.takeIf { it.isNotBlank() }?.let {
            ArtistPresentationCache.rememberArtistPalette(it, heroAspect, cacheKey)
        }
        heroRevealed.value = true
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
                // 写成功：通知「关注歌手」列表页刷新（失败时不通知）
                if (result.isSuccess) KugouSyncCoordinator.notifyArtistsChanged()
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
    val bgDrawHolder = remember { longArrayOf(Long.MIN_VALUE) }
    BoxWithConstraints(
        Modifier.fillMaxSize()
    ) {
        // 平板竖屏内容整体上抬 40dp：方形 hero 让列表起始过低，直接减在 hero 高度上
        // （其余比例与此前的 SquareFullBleed 方案一致，hero 略小于正方，裁切可忽略）。
        // 横屏（手机+平板）统一方形头像：取短边（maxHeight）作为方形边长，确保头像完整可见。
        val heroHeight = when {
            portraitTablet -> minOf(maxWidth, maxHeight * HeroTabletMaxHeightFraction) - HeroTabletContentRaiseDp.dp
            isLandscape -> maxHeight  // 横屏方形：高度 = 短边（屏高）
            else -> maxHeight * HeroPhoneHeightFraction
        }.coerceAtLeast(200.dp)
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
        // 烘焙几何与缓存键提到组合期（原先在烘焙 effect 内算）：只有这样首帧才能同步探
        // 内存缓存 [HeroBakeCache.memoryGet]，命中即首帧落位（回访「倒影秒出」）。几何只依赖
        // 盒尺寸，不依赖源位图，故组合期可算；计算逐字与旧 effect 内一致。
        val edgeReflection = portraitTablet || isLandscape
        val bakeScale = (HeroBakeMaxWidthPx / bakeWidthPx).coerceAtMost(1f)
        val bakeW = (bakeWidthPx * bakeScale).roundToInt().coerceAtLeast(1)
        val bakePhotoH = (heroHeightPx * bakeScale).roundToInt().coerceAtLeast(1)
        val bakeBandH = (bandHeightPx * bakeScale).roundToInt().coerceAtLeast(1)
        val bakeBlurPx = maxBlurPx * bakeScale
        val bakeCacheKey = remember(avatarUrl, heroAspect, edgeReflection, bakeW, bakePhotoH, bakeBandH) {
            // 配方版本进键：着色器/渐隐算法变了必须换键，否则磁盘里的旧烘焙图永远复用
            avatarUrl?.let { "v$ArtistHeroBakeRecipeVersion|$it|$heroAspect|$edgeReflection|${bakeW}x${bakePhotoH}x${bakeBandH}" }
        }
        // 烘焙位图按头像 URL 记忆（不含几何尺寸）：旋转等尺寸变化触发重烘焙期间保留旧图
        // 继续显示——回到同方向后旧图与新盒子几何一致，交换逐像素相同完全无感；烘焙失败也
        // 不丢图。此前按尺寸做 remember key 且 effect 开头先置空，旋转后合成图立刻消失、
        // 只剩 item 里一张无倒影的锐利 1:1 原图，直到重烘焙落地（有几率长达数秒甚至永久）。
        // 初值同步探内存缓存（主线程安全）：命中则首帧即非空，揭示门首帧即开、不加渐入。
        var bakedHero by remember(avatarUrl) {
            mutableStateOf(bakeCacheKey?.let(HeroBakeCache::memoryGet))
        }
        var pendingHeroBake by remember(avatarUrl) {
            mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null)
        }
        // 烘焙「落地」兜底：成功时只在 [pendingHeroBake] 提升门（视口静止）放行后才算落地，
        // 故成功分支不置此位；仅烘焙失败（或解码失败）时置真，避免揭示门干等一个永不到来的
        // 倒影——退化为「锐利照片 + 提取色」硬衔接（与旧行为一致）。
        var backdropBakeSettled by remember(avatarUrl) { mutableStateOf(false) }
        // 背景「整体揭示」门：hero 照片 + 提取色底 + 倒影/渐变模糊三者加载时序不同，
        // 各自就绪各自上屏 → 冷启动「空白底→照片→取色渐变→倒影突现」四段式。这里把它们
        // 绑到同一条 [backdropAlpha] 上：门未开前英雄区不显示背景（透出导航底衬），门开后
        // 三者同帧渐入。文字/圆钮/列表不参与，照常先出。判据收敛到可单测的纯函数。
        val backdropReady = artistBackdropReady(
            immersiveBackdrop = !isLandscape,
            // 必须「详情已定且无头像」：冷启动首帧详情未返回，裸判 avatarUrl==null 会让门
            // 先开后关（alpha 1→0→1 闪一下）。无头像即无背景元素可等，直接放行。
            // 加载/解码失败同样无照片可等（回落到身份派生色 + monogram），一并放行。
            avatarAbsent = noUsableAvatar,
            contentResolved = heroRevealed.value,
            // 烘焙产物就绪即算落地，不必等「视口静止」的提升门（该门只影响何时把合成图
            // 从 pending 换到 baked，且同几何交换逐像素相同、晚几步无观感差异）。若把门
            // 绑死在提升门，用户在烘焙落地前把英雄区滑出视口会让提升门永远等不到，
            // 全屏底色随之永不显示。失败/解码失败仍由另外两位兜底，不让门干等。
            bakeSettled = bakedHero != null || pendingHeroBake != null ||
                backdropBakeSettled || heroDecodeFailed.value
        )
        // 首帧初值 = target：内存烘焙缓存命中（或已有缓存取色）时首帧即 1f、不产生任何动画，
        // 满足回访「秒出」。单向保持（不回转）由门自身的单向性保证。
        // 组合期读成普通 Float 再进绘制/图层 lambda（同 bg 的处理）：本页绘制期状态观察会
        // 断链，不能依赖绘制期读 State 触发重绘，必须走「重组 → 捕获值进 modifier → diff」。
        // State 本体保留给 scrimColorState（底栏遮罩在绘制期读它，见下）。
        val backdropAlphaState = animateFloatAsState(
            targetValue = if (backdropReady) 1f else 0f,
            animationSpec = if (backdropReady) tween(400) else tween(0),
            label = "artistBackdropReveal"
        )
        val backdropAlpha = backdropAlphaState.value
        // 注册给底栏遮罩的颜色 = 「当前实际显示的页面底色」：门未开时停在兜底色，
        // 门开后与照片/取色/倒影同一条 400ms 曲线滑到提取色，消除「底栏那段底部渐变
        // 抢先变成歌手色」的错位。derivedStateOf 让 NavPageVisual 在绘制期读到时
        // 拿到当帧值，不额外触发本页重组。
        val scrimColorState = remember(placeholderAccent) {
            derivedStateOf { lerp(placeholderAccent, bgColorState.value, backdropAlphaState.value) }
        }
        DisposableEffect(entryId, scrimColorState, scrimTransition) {
            // 进度与页面 fadeIn/fadeOut 同帧同曲线（同一 AnimatedContent Transition），
            // 底栏遮罩用它把默认色向本页背景色同步插值（见 ArtistPageAppearance）。
            val registration = ArtistPageAppearance.register(entryId, scrimColorState, scrimTransition)
            onDispose { ArtistPageAppearance.unregister(entryId, registration) }
        }
        // 全屏底色随背景揭示门渐入：门未开前整页不铺提取色/兜底色，透出 NavPageVisual 底衬
        // （从普通页进入=主题色，与今天「空白底」同色；艺人页互转=上一个歌手的提取色）。
        // 颜色与 alpha 在组合期捕获为普通值，绘制期只读捕获值（此页绘制期状态观察会断链，见上）。
        // BoxWithConstraints 的子项按声明序叠放，本层作为第一个子项铺在内容之下。
        // （不能写成容器自身的 .background(bg)：gate 依赖 bakedHero，而 bakedHero 依赖
        //  maxWidth 派生的烘焙几何，只能在 BoxWithConstraints 作用域内求值。）
        val backdropFill = bg
        val backdropFillAlpha = backdropAlpha
        Box(
            Modifier
                .fillMaxSize()
                .drawBehind { drawRect(color = backdropFill, alpha = backdropFillAlpha) }
        )
        // Hero item 视口偏移：横竖屏切换的全树重测会让「绘制期 lambda 直接读
        // derivedStateOf(layoutInfo)」的观察链断链——层冻结在失效前一帧（列表已滚走、
        // 合成图钉在屏幕上，而同一 layoutInfo 的组合期读者如小标题仍正常）。改为协程层
        // snapshotFlow 采集（与组合期读者同一条旋转后仍存活的观察路径）写入普通状态，
        // 绘制期只读普通状态；层本体另由组合期 heroScrolledOut 门禁移除，杜绝僵尸层。
        val heroViewportTop = remember { mutableStateOf<Int?>(null) }
        LaunchedEffect(listState) {
            var lastLogged = Int.MIN_VALUE
            snapshotFlow {
                listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "Hero" }?.offset
            }.collect { v ->
                heroViewportTop.value = v
                val cur = v ?: Int.MIN_VALUE
                if (lastLogged == Int.MIN_VALUE || cur - lastLogged !in -199..199) {
                    android.util.Log.d("HeroTrack", "collector heroTop=$v")
                    lastLogged = cur
                }
            }
        }
        LaunchedEffect(avatarUrl, bakeWidthPx, heroHeightPx, bandHeightPx, originalRgb, heroBitmap.value) {
            // 横屏（平板+手机）走横向头部（照片在左、无「照片底边向下」区域），不烘焙倒影。
            if (isLandscape) return@LaunchedEffect
            // 加载/解码失败：无源位图可烘，直接返回（否则会拿 url 键做无意义磁盘查询）。
            if (avatarUrl == null || heroImageFailed || heroDecodeFailed.value) return@LaunchedEffect
            // 延展模式（方形 hero：平板竖屏）的带内要随深度溶入提取色——照片底部区域常是
            // 大片白/浅色，调色板未就绪就烘焙会烘出错误着色的带（用户时序判断:过渡带不能用兜底色显示）。
            // 先让上屏照片顶住,调色板到位后本 effect 因 key 变化自动重烘焙。
            if (portraitTablet && originalRgb == null) return@LaunchedEffect
            // 几何与缓存键已在组合期算好（见上方 bakeCacheKey）；这里不再重算。
            // 无头像（bakeCacheKey 为 null）时无倒影可烘。
            val cacheKey = bakeCacheKey ?: return@LaunchedEffect
            // 缓存命中（内存→磁盘）：直接落位、跳过重烘焙——这是「回访时倒影立刻在」的关键。
            HeroBakeCache.memoryGet(cacheKey)?.let {
                bakedHero = it
                return@LaunchedEffect
            }
            withContext(Dispatchers.IO) { HeroBakeCache.load(context, cacheKey) }?.let {
                bakedHero = it
                return@LaunchedEffect
            }
            // 与上屏/取色共用同一张软件位图（单次解码），不再各自解码。
            val src = heroBitmap.value ?: return@LaunchedEffect
            val shader = newArtistHeroReflectionShader(
                bakeW, bakePhotoH, bakeBandH, bakeBlurPx, edgeReflection
            )
            val bakedBitmap = withContext(Dispatchers.Default) {
                runCatching {
                    bakeHeroReflection(src, bakeW, bakePhotoH, bakeBandH, bakeBlurPx, shader, edgeReflection)
                }
                    // 烘焙失败会退回「锐利照片 + 提取色」硬衔接，必须留痕（debug 包看 logcat）
                    .onFailure { android.util.Log.w("ArtistDetail", "hero reflection bake failed (${bakeW}x${bakePhotoH + bakeBandH})", it) }
                    .getOrNull()
                    ?.also { HeroBakeCache.put(context, cacheKey, it) }
            }
            if (bakedBitmap == null) {
                // 烘焙失败：宣告「倒影不会来了」，揭示门立即放行、退化为锐利照片（不让门干等）。
                backdropBakeSettled = true
                return@LaunchedEffect
            }
            pendingHeroBake = bakedBitmap.asImageBitmap()
            android.util.Log.d(
                "HeroTrack",
                "bake done ${bakeW}x${bakePhotoH + bakeBandH} edge=$edgeReflection " +
                    "geo w=$bakeWidthPx hero=$heroHeightPx band=$bandHeightPx aspect=$heroAspect rgb=$originalRgb dark=$isDark"
            )
        }
        // 接管落位门禁：重烘焙结果等「Hero item 仍在视口内且滚动静止」才提升为 bakedHero。
        // 滚动中途直接换上，会在照片半出屏的位置以 <1 的 alpha 让整张合成图突然叠在提取色
        // 背景上；滚出视口后才落位则 alpha 门禁恒 0、合成图永远不可见。同几何交换逐像素
        // 相同，落位时机不影响观感；首次烘焙在静止且可见时换上即为设计中的接管。
        LaunchedEffect(pendingHeroBake) {
            if (pendingHeroBake == null) return@LaunchedEffect
            snapshotFlow { heroViewportTop.value to listState.isScrollInProgress }
                .first { (o, scrolling) -> o != null && !scrolling }
            bakedHero = pendingHeroBake ?: return@LaunchedEffect
            pendingHeroBake = null
            android.util.Log.d("HeroTrack", "bake promoted")
        }
        @Composable fun HeroPhoto(modifier: Modifier) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(detail.value?.avatarUrl)
                    // 不设占位/兜底图：解码期间保持透明，透出页面底色（缓存命中即取色），
                    // 避免白色人形图标在解码窗口里闪一下。加载失败（onError）另行回落到
                    // 身份派生色 + monogram，仍不落白色人形。
                    .allowHardware(false)
                    .size(1024)
                    // 与上方单次解码同键，命中内存缓存即刻显示（零额外解码）
                    .memoryCacheKey("artist-hero:${detail.value?.avatarUrl}")
                    .diskCacheKey(detail.value?.avatarUrl)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = modifier,
                // 网络/解码失败：置位回落派生色 + monogram，不再让英雄区空白。
                onError = { heroImageFailed = true }
            )
        }

        /**
         * 无可用头像时的 hero：身份派生色实底 + 名字首字母。取代原先「一块与页面同色的
         * 空 Box」——那正是用户看到的白板。颜色由派生色承载，故 hero 与内容区有明确分界，
         * 且底栏遮罩/子页面沿用同一色（见 seedPalette）。
         */
        @Composable fun ArtistMonogramHero(name: String, background: Color, content: Color) {
            val initial = remember(name) { name.trim().take(1).uppercase() }
            Box(
                Modifier.fillMaxSize().background(background),
                contentAlignment = Alignment.Center
            ) {
                if (initial.isNotEmpty()) {
                    Text(
                        text = initial,
                        fontSize = 132.sp,
                        fontWeight = FontWeight.Bold,
                        color = content.copy(alpha = 0.88f),
                        // 上移：避开底部居中的歌手名，避免两者视觉重叠
                        modifier = Modifier.offset(y = (-24).dp)
                    )
                }
            }
        }

        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(pageBackdrop)
        ) {
            // 平板横屏无倒影背景层（bakedHero 恒为 null，这里也不再挂空绘制层）；
            // Hero 滚出视口（heroScrolledOut，组合期读取）时整层从组合中移除——即便
            // 采集/绘制链再出任何异常，也不可能残留「合成图钉在背景上」的僵尸层。
            // 烘焙未完成时（平板竖屏调色板未就绪提前退出）也不挂空层，防止半透明空 graphicsLayer 显示为白色渐变。
            if (!isLandscape && !heroScrolledOut.value && bakedHero != null) {
                val lastLoggedAlpha = remember { floatArrayOf(Float.NaN) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .offset { IntOffset(0, heroViewportTop.value ?: 0) }
                        .height(heroHeight + bandHeight)
                        .graphicsLayer {
                            val o = heroViewportTop.value
                            val scrollA = if (o == null) 0f
                            else (((o + heroHeightPx) / bandHeightPx).coerceIn(0f, 1f))
                            // 再乘背景揭示门 alpha：倒影随照片/提取色同帧渐入，不再突然叠上来。
                            val a = scrollA * backdropAlpha
                            alpha = a
                            val last = lastLoggedAlpha[0]
                            if (last.isNaN() || a - last > 0.05f || last - a > 0.05f) {
                                android.util.Log.d("HeroTrack", "layer alpha=$a o=$o")
                                lastLoggedAlpha[0] = a
                            }
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
                // 横屏（平板+手机）改用横向头部（非沉浸式）：列表加 54dp 顶 padding，
                // 与 RankDetail/OnlineAlbumDetail 等既有的平板详情页约定一致，
                // 头部不至于被固定的半透明顶栏遮挡。竖屏（手机/平板）保持沉浸式。
                contentPadding = if (isLandscape) PaddingValues(top = 54.dp) else PaddingValues(0.dp),
                overscrollEffect = null,
                flingBehavior = rememberOverscrollFlingBehavior { listState }
            ) {
                item("Hero") {
                    if (isLandscape) {
                        // 横屏（平板+手机）：左方形头像 + 右名字/操作/数量/简介
                        ArtistTabletLandscapeHeader(
                            name = detail.value?.name?.ifEmpty { artistName } ?: artistName,
                            avatarUrl = detail.value?.avatarUrl,
                            avatarRevealed = heroRevealed.value,
                            hasIntro = !detail.value?.intro.isNullOrBlank(),
                            introOpen = introOpen.value,
                            onIntro = { introOpen.value = !introOpen.value },
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
                            loading = headerLoading.value,
                            error = resolveError.value,
                            onRetry = { retry.value += 1 }
                        )
                    } else {
                        // 英雄区（照片 + 名字）随背景揭示门整体渐入：门未开前全透明，透出底衬；
                        // 门开后照片、提取色、倒影同帧落位——不再「照片先出、颜色后到」。
                        // 名字一并被 alpha 覆盖：它硬编码白色 + 黑投影，本就画在 hero 图上，
                        // 底衬为浅色时不可读，随图淡入与现状等价且避免「照片没来名字先飘着」。
                        Box(
                            Modifier.fillMaxWidth().height(heroHeight)
                                .graphicsLayer { alpha = backdropAlpha }
                        ) {
                            // 优先：无可用头像（无 url / 加载失败 / 解码失败）→ 身份派生色 + 首字母。
                            // 其次：烘焙完成（bakedHero != null）由背景层接管照片区——渐进模糊的路径
                            // 延伸进照片下半部，item 若继续盖一张锐利照片会把模糊段完全遮住。
                            if (noUsableAvatar) {
                                ArtistMonogramHero(
                                    name = detail.value?.name?.ifEmpty { artistName } ?: artistName,
                                    background = bg,
                                    content = contentColor
                                )
                            } else if (bakedHero == null) {
                                HeroPhoto(Modifier.fillMaxSize())
                            }
                            Text(
                                text = detail.value?.name?.ifEmpty { artistName } ?: artistName,
                                fontSize = 30.sp,
                                fontWeight = FontWeight.Bold,
                                // 照片 hero：白名 + 黑投影（画在照片上）。无照片 hero：背景已是
                                // 派生实色，改用与整页一致的 contentColor，避免白名在浅色派生底上隐形。
                                color = if (noUsableAvatar) contentColor else Color.White,
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

                // 三圆钮行与数量行只在竖屏（手机 / 平板竖屏）出现；横屏已并入横向头部。
                if (!isLandscape) {
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
                        // 三个按钮全部由页面文字色派生（圆钮底色 = 文字色 18%，播放圆实心 = 文字色），
                        // 浅色取色底上翻成深色，与标题/简介的翻转时机同源。
                        val roundContainer = contentColor.copy(alpha = 0.18f)
                        if (!intro.isNullOrBlank()) {
                            // 注意：这里不能用 LiquidTopBarButton（消费 pageBackdrop）——
                            // 按钮在 pageBackdrop 录制层内部，采样源包含自身会构成
                            // RenderNode 自引用，RenderThread 无限递归原生栈溢出秒崩
                            ArtistRoundActionButton(
                                icon = R.drawable.ic_mini_info,
                                contentDescription = stringResource(id = R.string.artist_detail_intro_title),
                                // 展开态圆底是主题红的 55%，图标保持纯白（红底上深色图标不可读）
                                iconTint = if (introOpen.value) Color.White else contentColor,
                                highlighted = introOpen.value,
                                iconSize = 32.dp,
                                containerColor = roundContainer,
                                onClick = { introOpen.value = !introOpen.value }
                            )
                            Spacer(Modifier.width(26.dp))
                        }
                        // EvenOdd 几何镂空不依赖离屏清除，孔洞始终透出真实背景。
                        ArtistPlayButton(
                            enabled = songs.isNotEmpty(),
                            contentColor = contentColor
                        ) { playAt(songs, 0) }
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
                                } else contentColor,
                                highlighted = false,
                                iconSize = 28.dp,
                                motionEffects = false,
                                dimWhenDisabled = false,
                                containerColor = roundContainer,
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
                // 简介内联展开项：位于头部信息（Hero/Actions/HeaderStatus）与内容区之间，
                // 与参考排版一致。点击「i」切换显隐，展/收走 expand/shrink + 淡入淡出。
                item("Intro") {
                    AnimatedVisibility(
                        visible = introOpen.value,
                        enter = expandVertically(tween(260)) + fadeIn(tween(200)),
                        exit = shrinkVertically(tween(220)) + fadeOut(tween(160))
                    ) {
                        val intro = detail.value?.intro
                        if (!intro.isNullOrBlank()) {
                            Column(Modifier.fillMaxWidth()) {
                                ArtistSectionHeader(stringResource(R.string.artist_detail_intro_title))
                                // 正文材质 = 最新专辑卡片「发行日期 / 歌曲数量」同款：
                                // 取色底明暗分流（暗 = 白 + Plus 加色微光；亮 = 深色弱化），
                                // 阈值 0.179 与 ArtistArtworkColors.content 一致。
                                val lightBackdrop = bgColorState.value.luminance() > 0.179f
                                Text(
                                    text = intro,
                                    fontSize = 14.sp,
                                    lineHeight = 22.sp,
                                    color = if (lightBackdrop) Color(0xFF161616).copy(alpha = 0.55f)
                                            else Color.White.copy(alpha = 0.45f),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 20.dp)
                                        .padding(bottom = 6.dp)
                                        .then(if (lightBackdrop) Modifier else Modifier.overlayEffect())
                                )
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
                                    onDrawSurface = { drawRect(bg.copy(alpha = 0.7f)) }
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
    }
    }
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
    avatarRevealed: Boolean,
    hasIntro: Boolean,
    introOpen: Boolean,
    onIntro: () -> Unit,
    songsEnabled: Boolean,
    onPlay: () -> Unit,
    showFollow: Boolean,
    isFollowed: Boolean,
    followEnabled: Boolean,
    onFollow: () -> Unit,
    countsLine: String?,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit
) {
    val context = LocalContext.current
    val isDark = isFlamingoInDarkMode()
    // 页面主文字色由外层 CompositionLocalProvider 注入，这里直接继承；
    // 操作行三个按钮全部由它派生，浅色取色底上翻成深色。
    val contentColor = LocalContentColor.current
    val roundContainer = contentColor.copy(alpha = 0.18f)
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
                    .data(if (avatarRevealed) avatarUrl else null)
                    .error(R.drawable.songcredits_monogram_person)
                    .placeholder(R.drawable.songcredits_monogram_person)
                    .fallback(R.drawable.songcredits_monogram_person)
                    .allowHardware(false)
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
                            // 展开态圆底是主题红的 55%，图标保持纯白（红底上深色图标不可读）
                            iconTint = if (introOpen) Color.White else contentColor,
                            highlighted = introOpen,
                            iconSize = 24.dp,
                            containerColor = roundContainer,
                            onClick = onIntro
                        )
                        Spacer(Modifier.width(20.dp))
                    }
                    ArtistPlayButton(
                        enabled = songsEnabled,
                        contentColor = contentColor,
                        onClick = onPlay
                    )
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
                            } else contentColor,
                            highlighted = false,
                            iconSize = 28.dp,
                            motionEffects = false,
                            dimWhenDisabled = false,
                            containerColor = roundContainer,
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

/**
 * 艺人页背景「整体揭示」门的判据。
 *
 * 背景 = hero 照片 + 提取色底 + 倒影/渐变模糊，三者加载时序不同；门未开前英雄区不显示
 * 背景（透出导航底衬），门开后三者用同一条 alpha 同帧渐入，消除「空白底 → 照片 →
 * 取色渐变 → 倒影突现」的分批闪现。抽成纯函数以便单测锁定各反例（见 ArtistBackdropRevealTest）。
 *
 * - [immersiveBackdrop]：false = 横屏，走 54dp 顶 padding 的横向头部、无倒影，不介入。
 * - [avatarAbsent]：无头像歌手没有背景元素可等，直接放行（否则英雄区永久空白）。
 *   调用方须以「详情已定且无头像」判定，避免冷启动首帧详情未返回时门先开后关。
 * - [contentResolved]：照片解码 + 取色都已完成（照片与颜色必须同帧落位）。
 * - [bakeSettled]：倒影已落地或已确认不会到来（烘焙/解码失败兜底），否则门会干等。
 */
internal fun artistBackdropReady(
    immersiveBackdrop: Boolean,
    avatarAbsent: Boolean,
    contentResolved: Boolean,
    bakeSettled: Boolean
): Boolean = !immersiveBackdrop || avatarAbsent || (contentResolved && bakeSettled)

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
