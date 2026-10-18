package yos.music.player.ui.widgets.basic

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsList
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.objects.BlacklistState
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.objects.MyPlaylistsCache
import yos.music.player.data.repositories.FavoriteRepository
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.PendingPlaylistStore
import yos.music.player.native.KugouApiService
import yos.music.player.ui.navigation.LocalArtistNavigator
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.withNight

/** 两块页面（一级菜单 / 歌单列表）共用固定宽度：列宽按内容算，换页会跳宽。 */
private val AddToPlaylistPanelWidth = 216.dp

/** 歌单列表固定表头高度（行高 ≈ 8 + 10.5×2 + 19）。 */
private val AddToPlaylistHeaderHeight = 48.dp

/** 歌曲菜单专用行内上下边距：比默认 10.5dp 收紧一点。 */
private val SongMenuRowVerticalPadding = 8.dp

// 一级菜单 ⇄ 歌单列表的换页动画：弹簧都不过冲太明显，可被下一次点击直接打断重定向。
// 位移只走面板宽度的 1/5，配合淡入淡出，读感是"内容平移"而不是整块玻璃在飞。
private val AddToPlaylistSlideSpec = spring<IntOffset>(dampingRatio = 0.9f, stiffness = 420f)
private val AddToPlaylistSizeSpec = spring<IntSize>(dampingRatio = 0.92f, stiffness = 380f)
private val AddToPlaylistFadeSpec = tween<Float>(180, easing = FastOutSlowInEasing)

private enum class AddToPlaylistPage { Actions, Playlists }

/**
 * 歌单选择项：服务端歌单用 listid/gid 作 key；「创建中」歌单用 localId 作 key 并把
 * [pendingLocalId] 一并带出去，加歌时走挂起队列而不是服务端直调。
 */
private data class PlaylistOption(
    val key: String,
    val name: String,
    val songCount: Int,
    val coverUrl: String = "",
    val pendingLocalId: String? = null,
)

/**
 * 歌曲行右侧三竖点 → 歌曲操作菜单。
 *
 * 一级：顶部竖版动作（喜爱 / 下一首 / 播放）+ 前往艺人 / 添加至歌单 / 不感兴趣，
 * 歌单内还会多一条「从歌单移除」。点「添加至歌单」后同一块面板切换成歌单列表。
 *
 * 显隐：只对带酷狗 hash 的在线歌曲渲染（`mediaId = kugou-online-<hash>`）。
 */
@Composable
fun SongAddToPlaylistButton(
    music: YosMediaItem,
    onPlay: () -> Unit,
    onDelete: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val hash = music.kugouHash() ?: return
    val songTitle = music.title ?: defaultTitle
    val songArtist = music.artistsName ?: defaultArtistsName
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // 与主页右上角「更多」同一套联动：面板揭示进度回传，按钮随之让位/收缩并淡出。
    // 锚点仍由外层未变换的 Box 捕获，变换只作用在内层图标。
    val follow = rememberLiquidDropdownFollowState()

    val artistName = music.artistsList?.firstOrNull()?.trim().orEmpty()
    val artistNavigator = LocalArtistNavigator.current
    val canOpenArtist = artistNavigator != null &&
        artistName.isNotEmpty() && artistName != defaultArtistsName
    // 以下状态都读在调用方作用域：弹层 content 注册在宿主的独立组合树里，
    // 在 lambda 内读 State 不会触发宿主重组。
    val isFavorite = FavoriteRepository.isFavorite(music)
    val notInterested = BlacklistState.isMarked(hash)
    // 「下一首播放」状态：这首歌是否已经排在当前曲之后（playingMusicList 与 timeline 同序）
    val queue = MediaController.playingMusicList.value.orEmpty()
    val currentIndex = queue.indexOfFirst {
        it.mediaId == MediaController.musicPlaying.value?.mediaId
    }
    val isNextInQueue = currentIndex >= 0 &&
        currentIndex + 1 < queue.size &&
        queue[currentIndex + 1].mediaId == music.mediaId

    var expanded by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(AddToPlaylistPage.Actions) }
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    val heightCap = rememberAddToPlaylistHeightCap(anchorBounds)

    // 打开菜单时后台加载黑名单状态（歌单列表由面板自己按需加载）
    LaunchedEffect(expanded) {
        if (expanded) BlacklistState.ensureLoaded()
    }

    Box(
        modifier = modifier
            .size(40.dp)
            .onGloballyPositioned { coords ->
                val pos = coords.positionInWindow()
                anchorBounds = IntRect(
                    left = pos.x.toInt(),
                    top = pos.y.toInt(),
                    right = pos.x.toInt() + coords.size.width,
                    bottom = pos.y.toInt() + coords.size.height,
                )
            },
        contentAlignment = Alignment.Center
    ) {
        val interactionSource = remember { MutableInteractionSource() }
        Box(
            modifier = Modifier
                .size(40.dp)
                // 联动必须挂在锚点捕获节点的下游：按钮会随面板进度位移/收缩，
                // 锚点若跟着走会形成逐帧抖动回环（同 HomeMenuButton）
                .liquidDropdownAnchorFollow(follow)
                .clip(YosRoundedCornerShape(12.dp))
                .clickable(
                    interactionSource = interactionSource,
                    indication = null
                ) {
                    // 每次打开都回到一级页，避免上次停在歌单列表
                    page = AddToPlaylistPage.Actions
                    expanded = true
                },
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_song_menu_more_vert),
                contentDescription = stringResource(R.string.add_to_playlist_menu_entry),
                modifier = Modifier.size(20.dp),
                tint = LocalContentColor.current.copy(alpha = 0.55f)
            )
        }

        LiquidDropdownLayout(
            expanded = expanded,
            anchorBounds = anchorBounds,
            onDismissRequest = { expanded = false },
            surfaceAlpha = 0.6f,
            backdrop = LocalTitlePageBackdrop.current,
            onFractionProgress = follow::onProgress,
        ) {
            AddToPlaylistPanel(
                page = page,
                heightCap = heightCap,
                song = music,
                canOpenArtist = canOpenArtist,
                isFavorite = isFavorite,
                notInterested = notInterested,
                isNextInQueue = isNextInQueue,
                onBack = { page = AddToPlaylistPage.Actions },
                onOpenPicker = { page = AddToPlaylistPage.Playlists },
                onOpenArtist = {
                    expanded = false
                    artistNavigator?.invoke(music)
                },
                onToggleFavorite = {
                    expanded = false
                    val removing = isFavorite
                    scope.launch {
                        val result = if (removing) {
                            FavoriteRepository.removeFavorite(music)
                        } else {
                            FavoriteRepository.addFavorite(music)
                        }
                        result.onFailure { e ->
                            Toast.makeText(
                                context,
                                e.message ?: context.getString(R.string.song_menu_favorite_failed),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                },
                onPlay = {
                    expanded = false
                    onPlay()
                },
                onPlayNext = {
                    expanded = false
                    scope.launch {
                        val ok = runCatching { MediaController.playNext(music) }.isSuccess
                        Toast.makeText(
                            context,
                            if (ok) R.string.song_menu_play_next_done
                            else R.string.song_menu_play_next_failed,
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                onNotInterested = {
                    // 可切换：已标记则取消；点完自动收起菜单
                    expanded = false
                    val target = !notInterested
                    scope.launch {
                        KugouRepository.setNotInterested(hash, "$songArtist - $songTitle", target)
                            .onSuccess {
                                BlacklistState.applyLocal(hash, target)
                                Toast.makeText(
                                    context,
                                    if (target) R.string.song_menu_not_interested_done
                                    else R.string.song_menu_not_interested_removed,
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                            .onFailure { e ->
                                Toast.makeText(
                                    context,
                                    e.message ?: context.getString(
                                        R.string.song_menu_not_interested_failed
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                    }
                },
                onDelete = onDelete?.let { delete ->
                    {
                        expanded = false
                        delete()
                    }
                },
                onDismiss = { expanded = false },
            )
        }
    }
}

/**
 * 面板高度上限：取锚点上下两侧的**较大**余量，再减半（歌单列表别拉太长），
 * 下限 290dp 保证一级菜单（三按钮 + 四行）不被裁掉。
 */
@Composable
private fun rememberAddToPlaylistHeightCap(anchorBounds: IntRect): Dp {
    if (anchorBounds == IntRect.Zero) return 290.dp
    val density = LocalDensity.current
    val screenPx = with(density) {
        LocalConfiguration.current.screenHeightDp.dp.toPx()
    }
    // 迷你条 + 底栏会盖住页面底部：可用高度先扣掉这块，避免菜单长到它们后面
    val bottomOverlayPx = with(density) { LocalBottomOverlayHeight.current.toPx() }
    val usablePx = (screenPx - bottomOverlayPx).coerceAtLeast(0f)
    val space = maxOf(usablePx - anchorBounds.bottom, anchorBounds.top.toFloat())
    return (with(density) { space.toDp() } - 40.dp)
        .times(0.5f)
        .coerceAtLeast(290.dp)
}

/**
 * 面板内容：一级菜单 / 歌单列表。
 *
 * [page] 等值全部由调用方读好后传入（见 [SongAddToPlaylistButton] 里的说明），
 * 不要在这里读 State。
 */
@Composable
private fun AddToPlaylistPanel(
    page: AddToPlaylistPage,
    heightCap: Dp,
    song: YosMediaItem,
    canOpenArtist: Boolean,
    isFavorite: Boolean,
    notInterested: Boolean,
    isNextInQueue: Boolean,
    onBack: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenArtist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onNotInterested: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    Column(
        modifier = Modifier
            .width(AddToPlaylistPanelWidth)
            .heightIn(max = heightCap)
    ) {
        // 换页动画：新页从行进方向平移 1/5 宽 + 淡入，旧页反向退出，容器高度由
        // SizeTransform 弹簧跟随。两个方向对称、全部走 spring，重复点击可在动画中途
        // 直接改向（可打断），每次展开的观感一致（可复现）。
        AnimatedContent(
            targetState = page,
            transitionSpec = {
                val direction = if (targetState == AddToPlaylistPage.Playlists) 1 else -1
                ContentTransform(
                    targetContentEnter = slideInHorizontally(
                        animationSpec = AddToPlaylistSlideSpec,
                        initialOffsetX = { width -> direction * (width / 5) }
                    ) + fadeIn(animationSpec = AddToPlaylistFadeSpec),
                    initialContentExit = slideOutHorizontally(
                        animationSpec = AddToPlaylistSlideSpec,
                        targetOffsetX = { width -> -direction * (width / 5) }
                    ) + fadeOut(animationSpec = AddToPlaylistFadeSpec),
                    sizeTransform = SizeTransform(clip = true) { _, _ ->
                        AddToPlaylistSizeSpec
                    }
                )
            },
            modifier = Modifier.clipToBounds(),
            label = "AddToPlaylistPage",
        ) { currentPage ->
            AddToPlaylistPageContent(
                page = currentPage,
                heightCap = heightCap,
                song = song,
                canOpenArtist = canOpenArtist,
                isFavorite = isFavorite,
                notInterested = notInterested,
                isNextInQueue = isNextInQueue,
                onBack = onBack,
                onOpenPicker = onOpenPicker,
                onOpenArtist = onOpenArtist,
                onToggleFavorite = onToggleFavorite,
                onPlay = onPlay,
                onPlayNext = onPlayNext,
                onNotInterested = onNotInterested,
                onDelete = onDelete,
                onDismiss = onDismiss,
            )
        }
    }
}

@Composable
private fun AddToPlaylistPageContent(
    page: AddToPlaylistPage,
    heightCap: Dp,
    song: YosMediaItem,
    canOpenArtist: Boolean,
    isFavorite: Boolean,
    notInterested: Boolean,
    isNextInQueue: Boolean,
    onBack: () -> Unit,
    onOpenPicker: () -> Unit,
    onOpenArtist: () -> Unit,
    onToggleFavorite: () -> Unit,
    onPlay: () -> Unit,
    onPlayNext: () -> Unit,
    onNotInterested: () -> Unit,
    onDelete: (() -> Unit)?,
    onDismiss: () -> Unit,
) {
    when (page) {
        AddToPlaylistPage.Actions -> Column(
            modifier = Modifier
                .width(AddToPlaylistPanelWidth)
                .verticalScroll(rememberScrollState())
        ) {
            // 顶部竖版动作区（对齐参考图）：图标在上、文字在下，两个按钮等分宽度
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 8.dp, top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TopActionButton(
                    icon = painterResource(
                        if (isFavorite) R.drawable.ic_song_menu_star
                        else R.drawable.ic_song_menu_star_outline
                    ),
                    label = stringResource(
                        if (isFavorite) R.string.song_menu_unfavorite
                        else R.string.song_menu_favorite
                    ),
                    onClick = onToggleFavorite,
                    modifier = Modifier.weight(1f),
                )
                TopActionButton(
                    icon = painterResource(
                        if (isNextInQueue) R.drawable.ic_song_menu_plus_circle_fill
                        else R.drawable.ic_song_menu_plus_circle
                    ),
                    label = stringResource(R.string.song_menu_play_next),
                    onClick = onPlayNext,
                    modifier = Modifier.weight(1f),
                )
                TopActionButton(
                    icon = painterResource(R.drawable.ic_song_menu_play),
                    label = stringResource(R.string.song_menu_play),
                    onClick = onPlay,
                    modifier = Modifier.weight(1f),
                    // play.fill 的三角形占满 24 高，同尺寸下比圆/星显得更大，缩一档做视觉配平
                    iconSize = 19.dp,
                )
            }
            MenuDivider()
            if (canOpenArtist) {
                LiquidDropdownRow(
                    text = stringResource(R.string.song_menu_go_artist),
                    selected = false,
                    onClick = onOpenArtist,
                    isFirst = false,
                    showSelectedIcon = false,
                    verticalPadding = SongMenuRowVerticalPadding,
                    leadingIcon = painterResource(R.drawable.ic_song_recognition),
                )
                MenuDivider()
            }
            LiquidDropdownRow(
                text = stringResource(R.string.add_to_playlist_menu_entry),
                selected = false,
                onClick = onOpenPicker,
                isFirst = false,
                showSelectedIcon = false,
                verticalPadding = SongMenuRowVerticalPadding,
                leadingIcon = painterResource(R.drawable.ic_song_menu_playlist),
            )
            MenuDivider()
            LiquidDropdownRow(
                text = stringResource(
                    if (notInterested) R.string.song_menu_cancel_not_interested
                    else R.string.song_menu_not_interested
                ),
                selected = false,
                onClick = onNotInterested,
                isFirst = false,
                isLast = onDelete == null,
                showSelectedIcon = false,
                verticalPadding = SongMenuRowVerticalPadding,
                leadingIcon = painterResource(
                    if (notInterested) R.drawable.ic_song_menu_thumbs_down_fill
                    else R.drawable.ic_song_menu_thumbs_down
                ),
            )
            if (onDelete != null) {
                MenuDivider()
                LiquidDropdownRow(
                    text = stringResource(R.string.online_playlist_track_remove),
                    selected = false,
                    onClick = onDelete,
                    isFirst = false,
                    isLast = true,
                    showSelectedIcon = false,
                    verticalPadding = SongMenuRowVerticalPadding,
                    leadingIcon = painterResource(R.drawable.ic_song_menu_trash),
                    destructive = true,
                )
            }
        }

        AddToPlaylistPage.Playlists -> AddToPlaylistPickerPanel(
            song = song,
            heightCap = heightCap,
            onBack = onBack,
            onDismiss = onDismiss,
        )
    }
}

/**
 * 歌单选择面板（不含外层玻璃）：加载「我的歌单」→ 列表（含封面）→ 选中后确认弹窗。
 *
 * 同时供歌曲行菜单和播放页「更多」菜单复用：调用方只需要给一块下拉/子面板的位置，
 * 本组件自己负责加载、返回表头、确认弹窗与加歌提交。
 */
@Composable
fun AddToPlaylistPickerPanel(
    song: YosMediaItem,
    heightCap: Dp,
    onBack: () -> Unit,
    onDismiss: () -> Unit,
) {
    val hash = song.kugouHash() ?: return
    val songTitle = song.title ?: defaultTitle
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 首帧直接吃内存缓存：重复打开不再闪加载行
    val cachedOptions = remember {
        MyPlaylistsCache.peek()?.let(::buildPlaylistOptions)
    }
    var loading by remember { mutableStateOf(cachedOptions == null) }
    var loginRequired by remember { mutableStateOf(false) }
    var loadFailed by remember { mutableStateOf(false) }
    var options by remember { mutableStateOf(cachedOptions ?: emptyList()) }
    var confirmTarget by remember { mutableStateOf<PlaylistOption?>(null) }
    var adding by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        // 有缓存时列表已经在屏幕上，后台静默刷新；只有完全没数据才显示加载行
        loading = options.isEmpty()
        loginRequired = false
        loadFailed = false
        if (!KugouApiService.isLoggedIn()) {
            options = emptyList()
            loginRequired = true
            loading = false
            return@LaunchedEffect
        }
        MyPlaylistsCache.get()
            .onSuccess { options = buildPlaylistOptions(it) }
            .onFailure {
                // 刷新失败时保留缓存内容，只有本来就没数据才显示失败行
                if (options.isEmpty()) loadFailed = true
            }
        loading = false
    }

    // 系统返回先回一级菜单；点面板外仍然整体关闭（onDismissRequest 不经过这里）
    BackHandler(onBack = onBack)
    Column(
        modifier = Modifier
            .width(AddToPlaylistPanelWidth)
            .heightIn(max = heightCap)
    ) {
        PickerHeaderRow(onBack = onBack)
        // 内容滚到表头下面时渐隐（只在已滚动时出现，静止态第一行不被削顶）
        val listScrollState = rememberScrollState()
        val scrolled by remember {
            derivedStateOf { listScrollState.value > 0 }
        }
        val topFade by animateDpAsState(
            targetValue = if (scrolled) 16.dp else 0.dp,
            animationSpec = tween(180),
            label = "pickerTopFade",
        )
        Column(
            modifier = Modifier
                .width(AddToPlaylistPanelWidth)
                // 加载中 → 列表落地的高度变化也走同一条弹簧，避免硬跳
                .animateContentSize(AddToPlaylistSizeSpec)
                .heightIn(
                    max = (heightCap - AddToPlaylistHeaderHeight).coerceAtLeast(120.dp)
                )
                .fadeTopEdge(topFade)
                .verticalScroll(listScrollState)
        ) {
            when {
                loading -> PickerStatusRow(R.string.add_to_playlist_loading)
                loginRequired -> PickerStatusRow(R.string.add_to_playlist_login_required)
                loadFailed -> PickerStatusRow(R.string.add_to_playlist_load_failed)
                options.isEmpty() -> PickerStatusRow(R.string.add_to_playlist_empty)
                else -> options.forEachIndexed { index, option ->
                    LiquidDropdownRow(
                        text = option.name,
                        selected = false,
                        onClick = { confirmTarget = option },
                        isFirst = index == 0,
                        isLast = index == options.lastIndex,
                        showSelectedIcon = false,
                        verticalPadding = SongMenuRowVerticalPadding,
                        marquee = true,
                        leading = {
                            Box(Modifier.size(26.dp)) {
                                ShadowImageWithCache(
                                    dataLambda = {
                                        option.coverUrl.takeIf { it.isNotEmpty() }
                                    },
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize(),
                                    cornerRadius = 4.dp,
                                    shadowAlpha = 0f,
                                    imageQuality = ImageQuality.LOW,
                                )
                            }
                        },
                        trailing = {
                            Text(
                                text = stringResource(
                                    R.string.add_to_playlist_song_count,
                                    option.songCount
                                ),
                                fontSize = 12.sp,
                                lineHeight = 12.sp,
                                color = (Color.Black withNight Color.White)
                                    .copy(alpha = 0.5f),
                            )
                        },
                    )
                }
            }
        }
    }

    confirmTarget?.let { target ->
        SonifyDialog(
            title = stringResource(R.string.add_to_playlist_menu_entry),
            onDismissRequest = { if (!adding) confirmTarget = null },
            message = stringResource(
                R.string.add_to_playlist_confirm_message,
                songTitle,
                target.name
            ),
            positiveContent = stringResource(R.string.common_ok),
            onPositive = {
                if (adding) return@SonifyDialog
                adding = true
                scope.launch {
                    val result = if (target.pendingLocalId != null) {
                        PendingPlaylistStore.addSong(target.pendingLocalId, songTitle, hash)
                    } else {
                        KugouRepository.addTracksToPlaylist(target.key, songTitle, hash)
                    }
                    adding = false
                    confirmTarget = null
                    result
                        .onSuccess {
                            KugouSyncCoordinator.notifyPlaylistsChanged()
                            Toast.makeText(
                                context,
                                context.getString(R.string.add_to_playlist_success, target.name),
                                Toast.LENGTH_SHORT
                            ).show()
                            onDismiss()
                        }
                        .onFailure {
                            Toast.makeText(
                                context,
                                R.string.add_to_playlist_failed,
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                }
            },
            negativeContent = stringResource(R.string.common_cancel),
            onNegative = { if (!adding) confirmTarget = null },
            positiveEnabled = !adding,
            negativeEnabled = !adding,
            dismissEnabled = !adding,
            dismissOnBackPress = true,
            dismissOnClickOutside = true,
        )
    }
}

/** 顶部竖版动作按钮：图标在上、文字在下（对齐参考图的胶囊按钮）。 */
@Composable
private fun TopActionButton(
    icon: Painter,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    iconSize: Dp = 22.dp,
) {
    val contentColor = Color.Black withNight Color.White
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    Column(
        modifier = modifier
            .height(56.dp)
            .clip(YosRoundedCornerShape(17.dp))
            .drawBehind {
                if (pressed) drawRect(contentColor.copy(alpha = 0.10f))
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            painter = icon,
            contentDescription = null,
            modifier = Modifier.size(iconSize),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.height(5.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            lineHeight = 14.sp,
            color = contentColor.copy(alpha = 0.9f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 菜单分割线：细线 + 左右缩进，与参考图的行间分隔一致。 */
@Composable
private fun MenuDivider() {
    val contentColor = Color.Black withNight Color.White
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp)
            .height(0.5.dp)
            .background(contentColor.copy(alpha = 0.12f))
    )
}

/** 歌单列表固定表头：返回箭头 + 标题，行高/内边距对齐 [LiquidDropdownRow]。 */
@Composable
private fun PickerHeaderRow(onBack: () -> Unit) {
    val contentColor = Color.Black withNight Color.White
    val interactionSource = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .padding(start = 8.dp, end = 8.dp, top = 8.dp)
            .clip(YosRoundedCornerShape(17.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onBack,
            )
            .padding(horizontal = 14.dp, vertical = 10.5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_song_menu_back),
            contentDescription = stringResource(R.string.add_to_playlist_back),
            modifier = Modifier.size(18.dp),
            tint = contentColor,
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = stringResource(R.string.add_to_playlist_picker_title),
            fontSize = 15.6.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
            color = contentColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickerStatusRow(textRes: Int) {
    LiquidDropdownRow(
        text = stringResource(textRes),
        selected = false,
        onClick = {},
        enabled = false,
        isFirst = true,
        isLast = true,
        showSelectedIcon = false,
        verticalPadding = SongMenuRowVerticalPadding,
    )
}

/**
 * 顶部渐隐遮罩：把滚动到表头下面的内容淡出，避免在表头边缘被生硬切断。
 * `fade` 为 0 时不绘制（静止态第一行保持完整）。
 */
private fun Modifier.fadeTopEdge(fade: Dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val fadePx = fade.toPx()
        if (fadePx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                    startY = 0f,
                    endY = fadePx,
                ),
                blendMode = BlendMode.DstIn,
            )
        }
    }

/**
 * 服务端歌单 + 创建中歌单 → 选项列表。
 * 空「默认收藏」（is_def=1 且 count=0）与资料库页同规则隐藏；创建中歌单只取未失败、
 * 未绑定、且名字还没出现在服务端列表里的（已出现的会由资料库页对账绑定）。
 */
private fun buildPlaylistOptions(playlists: List<KugouPlaylist>): List<PlaylistOption> {
    val remote = playlists
        .filterNot { it.isDef == 1 && it.songCount == 0 }
        .mapNotNull { playlist ->
            val key = playlist.listid.ifEmpty { playlist.gid }
            if (key.isEmpty()) null
            else PlaylistOption(key, playlist.name, playlist.songCount, coverUrl = playlist.coverUrl)
        }
    val remoteNames = remote.mapTo(HashSet()) { it.name }
    val pending = PendingPlaylistStore.all()
        .filter { !it.failed && !it.isBound && it.name !in remoteNames }
        .map { PlaylistOption(it.localId, it.name, 0, pendingLocalId = it.localId) }
    return pending + remote
}
