@file:Suppress("DEPRECATION")

package yos.music.player.ui.pages

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.Crossfade
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalOverscrollConfiguration
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import androidx.compose.ui.graphics.Shape
import androidx.compose.material3.ripple
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProgressIndicatorDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SliderPositions
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.zIndex
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.lerp
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import android.graphics.BlurMaskFilter
import android.graphics.RenderEffect as AndroidRenderEffect
import androidx.annotation.RequiresApi
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastMap
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ONE
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.blankj.utilcode.util.TimeUtils
import com.google.accompanist.insets.navigationBarsHeight
import com.google.accompanist.insets.statusBarsHeight
import com.google.accompanist.insets.navigationBarsPadding
import com.google.accompanist.insets.statusBarsPadding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.MediaController.mediaControl
import yos.music.player.code.MediaController.musicPlaying
import yos.music.player.code.MediaController.playingMusicList
import yos.music.player.code.SystemMediaControlResolver
import yos.music.player.code.VolumeChangeReceiver
import yos.music.player.code.YosPlaybackService
import yos.music.player.code.qualityLabelResOf
import yos.music.player.code.utils.lrc.YosMediaEvent
import yos.music.player.code.utils.lrc.YosUIConfig
import yos.music.player.code.utils.lrc.LyricEntry
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.player.FadeExo.fadePlay
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.roundToInt
import yos.music.player.data.repositories.FavoriteRepository
import yos.music.player.data.repositories.KugouQuality
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.models.MainViewModel
import yos.music.player.data.models.MediaViewModel
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.pages.NowPlayingPage.Album
import yos.music.player.ui.pages.NowPlayingPage.Lyric
import yos.music.player.ui.pages.NowPlayingPage.PlayingList
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.YosLyricScrollState
import yos.music.player.ui.widgets.YosLyricView
import yos.music.player.ui.widgets.LAST_LINE_OBSTRUCTION_FRACTION
import yos.music.player.ui.widgets.effects.YosFloatingLight
import yos.music.player.ui.widgets.audio.MusicQualityIndicator
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.LiquidDropdownColumn
import yos.music.player.ui.widgets.basic.LiquidDropdownLayout
import yos.music.player.ui.widgets.basic.LiquidDropdownPlacement
import yos.music.player.ui.widgets.basic.LiquidDropdownProgress
import yos.music.player.ui.widgets.basic.LiquidDropdownRow
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.OptionDialog
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.liquidDropdownAnchorFollow
import yos.music.player.ui.widgets.basic.liquidDropdownHeightCap
import yos.music.player.ui.widgets.liquid.YosSwitch
import yos.music.player.ui.widgets.basic.rememberLiquidDropdownFollowState
import yos.music.player.ui.widgets.effects.ShadowType
import yos.music.player.ui.widgets.effects.overlayEffect


@Stable
object NowPlayingPage {
    const val Album = "Album"
    const val PlayingList = "PlayingList"
    const val Lyric = "Lyric"
}

private const val ShareAlbumKey = "album"
private const val AnimDurationMillis = 300

// 手机竖屏：播放页内容整体下移 21.5dp（0.5cm≈31.5dp 基础上按用户校准上抬 10dp）。
// 背景层在 MainActivity 的 PlayerShell 内、顶部小把手单独绘制，均不跟随此偏移。
private val PhonePortraitContentShift = 21.5.dp

/*
private val MaterialFadeInTransitionSpec
    get() = SharedElementsTransitionSpec(
        pathMotionFactory = LinearMotionFactory,
        durationMillis = AnimDurationMillis,
        fadeMode = FadeMode.In,
        easing = EaseOutQuart
    )

private val MaterialFadeOutTransitionSpec
    get() = SharedElementsTransitionSpec(
        pathMotionFactory = LinearMotionFactory,
        durationMillis = AnimDurationMillis,
        fadeMode = FadeMode.Out,
        easing = EaseOutQuart
    )
*/

@ExperimentalSharedTransitionApi
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun NowPlaying(
    mainViewModel: MainViewModel,
    mediaViewModel: MediaViewModel,
    navController: NavController,
    isPlayingStatusLambda: () -> Boolean,
    isPlayingOnChanged: (Boolean) -> Unit,
    nowPageLambda: () -> String,
    showMiniPlayer: Boolean,
    nowPageOnChanged: (String) -> Unit,
    // 控件（音量条/进度条）按下与抬起：true 表示本次手势已由控件占用，
    // 外层 shell 的竖直收起拖拽需要让位。
    onControlGesture: (Boolean) -> Unit = {},
    // 外壳封面 morph：上报全屏 Album 封面节点的 LayoutCoordinates，
    // 由外壳在绘制阶段换算到外壳坐标系，得到帧间不变量。
    albumCoverCoordsOnChanged: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    // 外壳封面 morph 进行中时为 true：本节点停止绘制，
    // 避免与外壳的单封面层同时画出两张图。
    albumCoverSuppressed: () -> Boolean = { false },
    modifier: Modifier = Modifier
) {
    // 控件区"焦点占用"观察层：任一指针按住（点击/长按/拖拽）期间为 true。
    //
    // 必须挂在**播放器的共同祖先**（Surface 根节点）上。若挂在控制带那种
    // 与标题行重叠的兄弟层上，pointerInput 节点即使不消费事件也会成为该区域的
    // HitTest 目标，从而抢走标题行 ⋯/★ 按钮下半部分的点击——真机表现就是
    // "只有按钮上沿能点"。控制带（0.437f）与封面页标题行（0.595f）本就重叠
    // ≈80px，任何铺在控制带顶部的指针节点都会落在 ⋯ 上。挂在祖先上则只观察、
    // 不遮蔽任何后代（标题行与控制带都是它的后代）。
    val controlPointerPressed = remember { mutableStateOf(false) }

    Surface(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                try {
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val pressed = event.changes.any { it.pressed }
                            if (pressed != controlPointerPressed.value) {
                                controlPointerPressed.value = pressed
                            }
                        }
                    }
                } finally {
                    // 离开组合/手势取消时复位，避免"按下"状态卡死导致控件永不自动隐藏。
                    controlPointerPressed.value = false
                }
            },
        contentColor = Color.White,
        color = Color.Transparent
    ) {
        val context = LocalContext.current

        // 平板适配：仅横屏走分栏适配布局；竖屏保持手机单列逻辑（内容限宽居中做平板适配）
        val configuration = LocalConfiguration.current
        val isLandscape = configuration.screenWidthDp > configuration.screenHeightDp
        // 平板（screenWidthDp >= 600，同项目其余页面判定）与横屏不做整体下移。
        val isPhonePortrait = !isLandscape && configuration.screenWidthDp < 600
        // 必须用 offset（布局位移）而不是 graphicsLayer.translationY（绘制位移）：
        // sharedElement 的动画落点读取布局坐标，graphicsLayer 会导致切大封面时
        // 动画终点停在未偏移的旧位置、结束后再跳到新位置。
        val phonePortraitShift = if (isPhonePortrait) {
            Modifier.offset(y = PhonePortraitContentShift)
        } else {
            Modifier
        }

        val lrcEntries: MutableState<List<LyricEntry>> =
            MediaViewModelObject.lrcEntries
        val bitmap: MutableState<Uri?> = MediaViewModelObject.bitmap
        val page = nowPageLambda()

        val thisMusicPlaying = remember("NowPlaying_thisMusicPlaying") {
            musicPlaying
        }

        val lastClickTime = rememberSaveable(key = "NowPlaying_lastClickTime") {
            mutableLongStateOf(0L)
        }

        // 歌词滚动状态提升到常驻层：竖屏歌词页、横屏歌词面板在开启/关闭、
        // 歌词↔队列切换时都会整体离开组合，位置由这里保留，重建时无动画定位，不再重滚。
        val lyricScrollState = remember { YosLyricScrollState() }

        val showControl = rememberSaveable(key = "NowPlaying_showControl") {
            mutableStateOf(!showMiniPlayer)
        }
        LaunchedEffect(showMiniPlayer, page) {
            if (!showMiniPlayer) {
                showControl.value = true
            }
        }

        // 下方控件区的「焦点占用」：任一指针按住（点击/长按/拖拽）或音质下拉展开时为 true。
        // 原实现只按 lastClickTime 计时，于是两件事都会误判为空闲：
        // 1) 手指按住某个控件但尚未触发 click（long press 期间、拖动过程中）——计时照走，
        //    控件在手指下淡出，松手后按钮动作照常触发，观感像"操作到一半自己消失"；
        // 2) 打开音质下拉——展开只改 menuOpen，不写 lastClickTime，2.5s 后整组控件淡出，
        //    只剩一个 panel 孤零零留在屏上。
        // 只要按住或有面板展开，就不该自动隐藏，因此把这两路信号并入计时条件。
        // controlPointerPressed 由挂在 Surface 根节点上的观察层写入（见函数顶部），此处只读。
        val qualityMenuOpen = remember { mutableStateOf(false) }
        val controlBusy = controlPointerPressed.value || qualityMenuOpen.value

        val translation = rememberSaveable(key = "NowPlaying_translation") {
            mutableStateOf(SettingsLibrary.NowPlayingTranslation)
        }

        val shuffleModeEnabled = MediaViewModelObject.shuffleModeEnabled
        val repeatMode = MediaViewModelObject.repeatMode

        /*val nowPage = rememberSaveable(key = "NowPlaying_nowPage") {
            MainViewModelObject.nowPage
        }*/


        // 触摸超时（横屏常显控件，竖屏保持原行为）
        YosWrapper {
            LaunchedEffect(
                showControl.value, page, lastClickTime.longValue, isLandscape, controlBusy
            ) {
                if (isLandscape) {
                    if (!showControl.value) {
                        showControl.value = true
                    }
                    return@LaunchedEffect
                }
                if (page != Lyric && !showControl.value) {
                    showControl.value = true
                }
                // 有焦点（按住控件 / 音质面板展开）时不启动计时，控件保持常显；
                // 焦点一释放 effect 因 controlBusy 变化重启，从那一刻重新计 2.5s。
                if (showControl.value && !controlBusy) {
                    val time = 2500L
                    delay(time)
                    withContext(Dispatchers.Main) {
                        if (TimeUtils.getNowMills() - lastClickTime.longValue >= time && page == Lyric) {
                            showControl.value = false
                        }
                    }
                }
            }
        }


        // 播放控制回调：横竖屏共用一份
        val onPreviousAction: () -> Unit = {
            mediaControl?.seekToPreviousMediaItem()
            showControl.value = true
            lastClickTime.longValue = TimeUtils.getNowMills()
        }
        val onStatusAction: (Boolean) -> Unit = { status ->
            if (status) {
                mediaControl?.fadePlay()
            } else {
                mediaControl?.fadePause()
            }
            showControl.value = true
            lastClickTime.longValue = TimeUtils.getNowMills()
        }
        val onNextAction: () -> Unit = {
            mediaControl?.seekToNextMediaItem()
            showControl.value = true
            lastClickTime.longValue = TimeUtils.getNowMills()
        }
        val onSeekAction: (Float) -> Unit = { position ->
            mediaControl?.seekTo(position.toLong())
        }
        val onLyricsAction: () -> Unit = {
            if (nowPageLambda() == Lyric) {
                nowPageOnChanged(Album)
            } else {
                nowPageOnChanged(Lyric)
            }
        }
        val onPlaylistAction: () -> Unit = {
            if (nowPageLambda() == PlayingList) {
                nowPageOnChanged(Album)
            } else {
                nowPageOnChanged(PlayingList)
            }
        }
        val onSliderAction: () -> Unit = {
            showControl.value = true
            lastClickTime.longValue = TimeUtils.getNowMills()
        }
        val onWhileAction: suspend () -> Unit = {
            shuffleModeEnabled.value = mediaControl?.shuffleModeEnabled ?: false
            repeatMode.intValue = mediaControl?.repeatMode ?: REPEAT_MODE_OFF
        }


        // 实际显示区
        YosWrapper {
            if (isLandscape) {
                // 横屏把手按用户参考图（红椭圆）标定：墨迹中心≈ y33dp（把手高 4.5dp，
                // 4.5/2 + top = 33 → top=30dp）；竖屏仍走状态栏 padding + 20dp。
                YosWrapper { NowPlayingHandle(topOverride = 30.dp) }
                NowPlayingLandscape(
                    mainViewModel = mainViewModel,
                    mediaViewModel = mediaViewModel,
                    isPlayingStatusLambda = isPlayingStatusLambda,
                    isPlayingOnChanged = isPlayingOnChanged,
                    nowPageLambda = nowPageLambda,
                    showControl = showControl,
                    lastClickTime = lastClickTime,
                    translation = translation,
                    lrcEntries = { lrcEntries.value },
                    thisMusicPlayingLambda = { thisMusicPlaying.value },
                    shuffleModeEnabled = shuffleModeEnabled,
                    repeatMode = repeatMode,
                    onPrevious = onPreviousAction,
                    onStatus = onStatusAction,
                    onNext = onNextAction,
                    onSeek = onSeekAction,
                    onLyrics = onLyricsAction,
                    onPlaylist = onPlaylistAction,
                    onSlider = onSliderAction,
                    onWhile = onWhileAction,
                    lyricState = lyricScrollState,
                    onControlGesture = onControlGesture,
                    albumCoverCoordsOnChanged = albumCoverCoordsOnChanged,
                    albumCoverSuppressed = albumCoverSuppressed
                )
                return@YosWrapper
            }
            /*
        val controlAlpha = animateFloatAsState(
            targetValue = if (showControl.value) 1f else 0f,
            tween(200)
        )

        val buttonEnabled = remember("NowPlaying_buttonEnabled") {
            derivedStateOf { controlAlpha.value != 0f }
        }

        val translationButtonEnabled = remember("NowPlaying_translationButtonEnabled") {
            derivedStateOf { buttonEnabled.value && alpha.value != 0f }
        }*/

            val scope = rememberCoroutineScope()

            val alphaAnim = remember { Animatable(0f) }

            val skipPageTransition = showMiniPlayer

            // alphaAnim 只服务于歌词页相关的轻量控制项，不再控制整棵歌词树。
            // 歌词树由下方唯一的页面宿主管理，避免 PlayerShell、alphaAnim 与 Crossfade
            // 同时对全屏歌词做三层透明度合成。
            YosWrapper {
                LaunchedEffect(page, showMiniPlayer) {
                    val targetAlpha = when {
                        showMiniPlayer -> 0f
                        page == Lyric -> 1f
                        else -> 0f
                    }
                    alphaAnim.animateTo(
                        targetAlpha,
                        animationSpec = tween(220, easing = FastOutSlowInEasing)
                    )
                }
            }

            val translationButtonEnabled = remember("NowPlaying_translationButtonEnabled") {
                derivedStateOf { showControl.value && page == Lyric }
            }

            // 这是小把手
            YosWrapper {
                NowPlayingHandle()
            }

            // 主 View
            YosWrapper {
                SharedTransitionLayout {
                    Crossfade(
                        targetState = page,
                        animationSpec = if (skipPageTransition) {
                            snap()
                        } else {
                            tween(durationMillis = 220, easing = FastOutSlowInEasing)
                        },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        //println("nowPage: ${nowPageLambda()}")
                        //println("nowPageIt: $it")
                        when (it) {
                            Album ->
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .statusBarsPadding()
                                        .padding(top = 22.dp)
                                        .clickable(enabled = false, onClick = {})
                                        .then(phonePortraitShift)
                                ) {
                                    YosWrapper {
                                        Column(Modifier.fillMaxHeight(0.595f)) {
                                            val isVisible = page == Album

                                            Album(
                                                modifier = Modifier.sharedElementWithCallerManagedVisibility(
                                                    sharedContentState = rememberSharedContentState(
                                                        key = ShareAlbumKey
                                                    ),
                                                    visible = isVisible
                                                ),
                                                albumUrl = { thisMusicPlaying.value?.thumb },
                                                isPlaying = isPlayingStatusLambda,
                                                phonePortraitExtraInset = if (isPhonePortrait) 5.dp else 0.dp,
                                                phonePortraitBouncyScale = isPhonePortrait,
                                                coordsOnChanged = albumCoverCoordsOnChanged,
                                                suppressed = albumCoverSuppressed
                                            )
                                            AnimatedContent(
                                                targetState = thisMusicPlaying.value,
                                                transitionSpec = {
                                                    fadeIn() togetherWith fadeOut()
                                                }, modifier = Modifier
                                                    .padding(bottom = 20.dp)
                                                    .padding(horizontal = 32.dp)
                                            ) {
                                                Row(
                                                    Modifier
                                                        .fillMaxWidth(),
                                                    verticalAlignment = Alignment.CenterVertically
                                                ) {
                                                    Column(
                                                        Modifier
                                                            .fillMaxWidth()
                                                            .weight(1f)
                                                            .padding(end = 15.dp)
                                                    ) {
                                                        Text(
                                                            text = it?.title
                                                                ?: defaultTitle,/*
                                                        fontWeight = FontWeight.Bold,*/
                                                            fontSize = 19.5.sp,
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            fontWeight = FontWeight.Medium
                                                        )
                                                        Text(
                                                            text = it?.artistsName
                                                                ?: defaultArtistsName,
                                                            fontSize = 18.5.sp,
                                                            modifier = Modifier.overlayEffect(),
                                                            maxLines = 1,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = Color.White.copy(alpha = 0.35f)
                                                        )
                                                    }

                                                    YosWrapper {
                                                        ActionButtonsRow {
                                                            it
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                            Lyric ->
                                Box(Modifier.fillMaxSize()) {
                                    YosWrapper {
                                        Lyric(
                                            lyricState = lyricScrollState,
                                            lrcEntries = { lrcEntries.value },
                                            weightLambda = { showControl.value },
                                            translationLambda = { translation.value },
                                            onBackClick = {
                                                showControl.value = true
                                                lastClickTime.longValue =
                                                    TimeUtils.getNowMills()
                                            },
                                            mainViewModel = mainViewModel,
                                            mediaViewModel = mediaViewModel,
                                            modifier = Modifier.fillMaxSize(),
                                            // 竖屏控件层压在底部：末句固定上抬到控件之上，不随控件显隐移动。
                                            lastLineObstructionFraction =
                                                LAST_LINE_OBSTRUCTION_FRACTION
                                        )
                                    }
                                    YosWrapper {
                                        val isVisible = page == Lyric
                                        Box(
                                            Modifier
                                                .fillMaxWidth()
                                                .statusBarsPadding()
                                                .padding(top = 22.dp)
                                        ) {
                                        PlayingBar(
                                            modifier = Modifier.sharedElementWithCallerManagedVisibility(
                                                sharedContentState = rememberSharedContentState(
                                                    key = ShareAlbumKey
                                                ),
                                                visible = isVisible
                                            ),
                                            albumUrlLambda = {
                                                thisMusicPlaying.value?.thumb
                                            },
                                            musicPlayingLambda = { thisMusicPlaying.value }) {
                                            nowPageOnChanged(Album)
                                        }
                                        }
                                    }
                                }

                            PlayingList ->
                                YosWrapper {
                                    Column(
                                        Modifier
                                            .fillMaxSize()
                                            .statusBarsPadding()
                                            .padding(top = 22.dp)
                                            .clickable(enabled = false, onClick = {})
                                    ) {
                                        val isVisible = page == PlayingList
                                        PlayingBar(
                                            modifier = Modifier.sharedElementWithCallerManagedVisibility(
                                                sharedContentState = rememberSharedContentState(
                                                    key = ShareAlbumKey
                                                ),
                                                visible = isVisible
                                            ),
                                            albumUrlLambda = {
                                                thisMusicPlaying.value?.thumb
                                            },
                                            musicPlayingLambda = { thisMusicPlaying.value }) {
                                            nowPageOnChanged(Album)
                                        }
                                        // 列表态下仅歌曲列表下移；顶部 PlayingBar 行（小封面/歌名/按钮）保持原位。
                                        YosWrapper {
                                            Box(Modifier.then(phonePortraitShift)) {
                                                PlayingList(
                                                    shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                                                    shuffleModeOnChanged = { shuffleModeSet ->
                                                        shuffleModeEnabled.value = shuffleModeSet
                                                    },
                                                    repeatModeLambda = { repeatMode.intValue },
                                                    repeatModeOnChanged = { repeatModeSet ->
                                                        repeatMode.intValue = repeatModeSet
                                                    },
                                                    thisMusicPlayingLambda = { thisMusicPlaying.value }
                                                )
                                            }
                                        }
                                    }
                                }
                        }
                    }
                }
            }

            // 音乐控制
            YosWrapper {
                Column(
                    Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .navigationBarsPadding()
                        .then(phonePortraitShift), verticalArrangement = Arrangement.Bottom
                ) {
                    Box(
                        Modifier
                            /*.fillMaxHeight(0.385f)*/
                            .fillMaxHeight(0.437f)
                            .fillMaxWidth()
                    ) {

                        YosWrapper {
                            if (showControl.value) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(top = 40.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null,
                                            onClick = {
                                                //showControl.value = true
                                                /*lastClickTime.longValue =
                                                TimeUtils.getNowMills()*/
                                            })
                                )
                            }
                        }

                        YosWrapper {
                            // 歌词页控件显隐：用「常驻组合 + ModulateAlpha 承载动画 alpha」
                            // 取代 AnimatedVisibility 的 fadeIn。AnimatedVisibility 的淡入会把
                            // 子树离屏合成，内层 overlayEffect 的 Plus 加性提亮因此在透明底上失效
                            // →途中发灰、结束才回亮。ModulateAlpha 则逐绘制调制 alpha，Plus
                            // 仍直接叠加到真实背景，动画期间颜色保持稳定；同时保留渐显/渐隐与位移。
                            val controlVisible = showControl.value
                            val controlAlpha by animateFloatAsState(
                                targetValue = if (controlVisible) 1f else 0f,
                                animationSpec = tween(
                                    durationMillis = 220,
                                    easing = FastOutSlowInEasing
                                ),
                                label = "PortraitControlAlpha"
                            )
                            Box(
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Column(
                                    Modifier
                                        .fillMaxSize()
                                        .graphicsLayer {
                                            compositingStrategy =
                                                CompositingStrategy.ModulateAlpha
                                            // 只做渐显/渐隐，不做位移：歌词页控件会自动隐藏，
                                            // 若给隐藏态加 translationY 会让整组控件淡出时向下沉，
                                            // 与原版（淡出+向顶收缩）方向相反，观感错误。
                                            alpha = controlAlpha
                                        },
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    PlayerControl(
                                        isPlayingLambda = isPlayingStatusLambda,
                                        isPlayingOnChanged = isPlayingOnChanged,
                                        onPrevious = onPreviousAction,
                                        onStatus = onStatusAction,
                                        onNext = onNextAction,
                                        onSeek = onSeekAction,
                                        onLyrics = onLyricsAction,
                                        onPlaylist = onPlaylistAction,
                                        nowPage = {
                                            nowPageLambda()
                                        },
                                        onSlider = onSliderAction,
                                        onControlGesture = onControlGesture,
                                        onQualityMenuExpandedChanged = { qualityMenuOpen.value = it },
                                        modifier = Modifier
                                            .padding(top = 52.dp),
                                        onWhile = onWhileAction)
                                }

                                // 翻译按钮改成覆盖层，不再作为 Column 的首个子项：
                                // 之前它只在歌词页出现，会把下方 PlayerControl 的可用高度挤掉
                                // 约 30dp（传输行是 weight(1f)：进度条/音质/时间随之下移约 30dp、
                                // 传输键下移约 15dp），切进歌词瞬间即为用户看到的"控件向下闪现"。
                                // 覆盖层不参与纵向布局，各页 PlayerControl 位置从此完全一致。
                                // 只挂在歌词页：封面/列表页顶边会压住标题行的「更多」按钮
                                //（Album 列 fillMaxHeight(0.595f) + 控制带 fillMaxHeight(0.437f)
                                // = 1.032，重叠 ≈80px；真机逐点实测「⋯」只有上沿可点时验证过）。
                                if (page == Lyric) {
                                    Box(
                                        modifier = Modifier
                                            .align(Alignment.TopStart)
                                            .fillMaxWidth()
                                            .graphicsLayer {
                                                compositingStrategy =
                                                    CompositingStrategy.ModulateAlpha
                                                alpha = controlAlpha
                                            }
                                    ) {
                                        YosWrapper {
                                            Row(
                                                Modifier
                                                    .fillMaxWidth()
                                                    .padding(horizontal = 32.dp)
                                                    .graphicsLayer {
                                                        compositingStrategy =
                                                            CompositingStrategy.ModulateAlpha
                                                        this.alpha = alphaAnim.value
                                                    },
                                                horizontalArrangement = Arrangement.End
                                            ) {
                                                YosWrapper {
                                                    TranslationToggleButton(
                                                        translation = translation,
                                                        enabled = translationButtonEnabled.value
                                                    ) {
                                                        Vibrator.click(context)
                                                        translation.value = !translation.value
                                                        showControl.value = true
                                                        lastClickTime.longValue =
                                                            TimeUtils.getNowMills()
                                                        SettingsLibrary.NowPlayingTranslation =
                                                            translation.value
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }

                                // 收起后控件仍常驻组合（保住进度轮询与滑块位置），这里挡住点击，
                                // 避免「看不见却能点」的播放键；淡出结束前 alpha 已趋 0，观感一致。
                                if (!controlVisible) {
                                    Box(
                                        modifier = Modifier
                                            .matchParentSize()
                                            .clickable(
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null,
                                                onClick = {}
                                            )
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

@Composable
private fun ColumnScope.Album(
    modifier: Modifier,
    albumUrl: () -> Uri?,
    isPlaying: () -> Boolean,
    // 手机竖屏专用：播放/暂停两态都在原内缩基础上再加的额外内缩（封面整体再小一点）。
    phonePortraitExtraInset: Dp = 0.dp,
    // 手机竖屏专用：缩放动画用欠阻尼弹簧（先冲过目标尺寸再回弹）；平板竖屏保持原动画。
    phonePortraitBouncyScale: Boolean = false,
    // 外壳封面 morph：本封面节点的坐标上报；suppressed 为 true 时不绘制，
    // 由外壳单封面层接管，避免两张图叠加。
    coordsOnChanged: (androidx.compose.ui.layout.LayoutCoordinates) -> Unit = {},
    suppressed: () -> Boolean = { false }
) = Box(
    Modifier
        .weight(1f)
        .padding(top = 20.dp)
        .padding(horizontal = 15.dp)
        .padding(bottom = 33.dp),
    contentAlignment = Alignment.BottomCenter
) {
    // 手机竖屏：仅"开始播放"（封面放大）方向用欠阻尼弹簧——轻微冲过额定尺寸一次再
    // 柔和落定（低刚度 + 接近临界阻尼，无多次晃动）；暂停缩回方向保持原 tween。
    // 平板竖屏保持原动画：播放向临界阻尼弹簧、暂停向 tween。
    val springSpec: AnimationSpec<Float> = remember("Album_springSpec") {
        SpringSpec(stiffness = 300f, dampingRatio = 1f, visibilityThreshold = 0.001f)
    }
    val bouncySpringSpec: AnimationSpec<Float> = remember("Album_bouncySpringSpec") {
        SpringSpec(stiffness = 200f, dampingRatio = 0.68f, visibilityThreshold = 0.001f)
    }
    val tweenSpec: AnimationSpec<Float> = remember("Album_tweenSpec") {
        TweenSpec(durationMillis = 350, easing = EaseOutQuart)
    }

    val scale = animateFloatAsState(
        targetValue = if (isPlaying()) 0f else 1f,
        animationSpec = when {
            phonePortraitBouncyScale && isPlaying() -> bouncySpringSpec
            isPlaying() -> springSpec
            else -> tweenSpec
        },
        visibilityThreshold = 0.001f
    )

    YosWrapper {
        // 播放态 scale→0 内缩 7dp，暂停态 scale→1 内缩 34dp；手机竖屏再叠加额外内缩。
        val dp = (7 + (27 * scale.value)).dp + phonePortraitExtraInset
        // 平板竖屏适配：封面限宽居中（手机屏宽不足 460dp，no-op）
        Box(
            Modifier.fillMaxWidth(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Box(
                Modifier.widthIn(max = 460.dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // 播放/暂停内缩只作用于外层；内层明确锁定为正方形图片内容框。
                // 坐标回调只挂在这个 aspectRatio 节点上，不包含外层底部 padding，
                // 也不受 ShadowImage 内部阴影/描边 coordinator 的边界语义影响。
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = dp, end = dp, bottom = dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .onGloballyPositioned { coordsOnChanged(it) },
                        contentAlignment = Alignment.Center
                    ) {
                        ShadowImageWithCache(
                            dataLambda = albumUrl,
                            contentDescription = null,
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                    // 在外壳单封面层接管期间隐藏本节点：读 State 发生在
                                    // layer 阶段，只失效图层、不触发重组，也不改布局占位。
                                    alpha = if (suppressed()) 0f else 1f
                                    // scaleX = scale.value
                                    // scaleY = scale.value
                                }
                                // shared-element 继续包住真实图片，但不参与 morph
                                // 的坐标回调，避免其变换污染终点几何。
                                .then(modifier),
                            imageQuality = ImageQuality.RAW,
                            shadowOverlay = true
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalFoundationApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PlayingList(
    shuffleModeEnabledLambda: () -> Boolean,
    shuffleModeOnChanged: (Boolean) -> Unit,
    repeatModeLambda: () -> Int,
    repeatModeOnChanged: (Int) -> Unit,
    thisMusicPlayingLambda: () -> YosMediaItem?,
    heightFraction: Float = 0.545f,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current

    Spacer(modifier = Modifier.height(12.dp))

    val musicList = remember("PlayingList_musicList") {
        playingMusicList
    }

    // 队列编辑模式：多选删除 / 拖拽重排 / 排序（长按行也可进入编辑）
    var editMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<String>() }
    var showSortMenu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val queueScope = rememberCoroutineScope()

    var dragGeometry by remember { mutableStateOf<QueueDragGeometryState?>(null) }
    var dragKey by remember { mutableStateOf<String?>(null) }
    var dragActive by remember { mutableStateOf(false) }
    var dragLatched by remember { mutableStateOf(false) }
    val itemHeightPx = remember { mutableIntStateOf(0) }
    val queueItems = musicList.value ?: emptyList()
    val queueKeys = remember(queueItems) {
        val occurrences = mutableMapOf<String, Int>()
        queueItems.mapIndexed { _, music ->
            val base = music.mediaId?.let { "media:$it" }
                ?: "object:${System.identityHashCode(music)}"
            val occurrence = occurrences.merge(base, 1, Int::plus)!! - 1
            "$base#$occurrence"
        }
    }
    // 落手到列表真正重排之间保留冻结几何；重排那一帧 fromIndex 处的 key 已换人，
    // displayGeometry 在同一组合内失效，让位位移与 animateItem 位移动画同帧抵消，
    // 避免"先弹回旧位、再跳到新位"的两次跳变
    val displayGeometry = dragGeometry
        ?.takeIf { dragKey != null && queueKeys.getOrNull(it.fromIndex) == dragKey }

    fun clearDrag() {
        dragActive = false
        dragLatched = false
        dragGeometry = null
        dragKey = null
    }

    fun commitDrag(viewportStartPx: Int, viewportEndPx: Int) {
        val geometry = dragGeometry ?: run {
            clearDrag()
            return
        }
        // 停掉自动滚动，但保留几何进入"落定"阶段，等列表重排同帧交接
        dragActive = false
        // 落点按浮层实际显示位置计算，手指伸出可视区外的部分不计入目标行数
        val displacement = QueueDragMath.displayedDisplacementPx(
            draggedTopPx = geometry.draggedTopPx,
            untransformedTopPx = geometry.untransformedTopPx,
            itemHeightPx = itemHeightPx.intValue,
            viewportStartPx = viewportStartPx,
            viewportEndPx = viewportEndPx
        )
        val target = QueueDragMath.targetIndex(
            fromIndex = geometry.fromIndex,
            logicalOffsetPx = displacement,
            itemHeightPx = itemHeightPx.intValue,
            itemCount = musicList.value?.size ?: 0
        )
        if (target != null && target != geometry.fromIndex) {
            queueScope.launch { MediaController.moveQueueItem(geometry.fromIndex, target) }
        } else {
            clearDrag()
        }
    }

    fun toggleSelect(index: Int, music: YosMediaItem) {
        val id = music.mediaId ?: "index:$index"
        if (id in selectedIds) selectedIds.remove(id) else selectedIds.add(id)
    }

    YosWrapper {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .fillMaxHeight(heightFraction),
        ) {
            val hide = remember("PlayingList_hide") {
                derivedStateOf {
                    musicList.value.isNullOrEmpty() || shuffleModeEnabledLambda()
                }
            }

            // 标题栏交叉淡化动画（修复问题1：标题切换闪现）
            AnimatedContent(
                targetState = editMode,
                transitionSpec = {
                    fadeIn(tween(200, delayMillis = 50)) togetherWith 
                    fadeOut(tween(150))
                },
                label = "titleBarCrossfade"
            ) { isEditMode ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 30.dp)
                        .padding(top = 10.dp)
                        .height(65.dp), 
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isEditMode) {
                        // 编辑模式顶栏：✕ / 已选 N / 全选 / 删除（对齐上游 player_playlist_view）
                        Text(
                            text = "✕",
                            fontSize = 19.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        editMode = false
                                        clearDrag()
                                        selectedIds.clear()
                                    }

                                .padding(6.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(id = R.string.queue_selected_count, selectedIds.size),
                            fontSize = 16.5.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = stringResource(id = R.string.queue_select_all),
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable {
                                    val all = musicList.value ?: emptyList()
                                    if (selectedIds.size >= all.size) selectedIds.clear()
                                    else {
                                        selectedIds.clear()
                                        all.forEachIndexed { i, m -> selectedIds.add(m.mediaId ?: "index:$i") }
                                    }
                                }
                                .padding(6.dp)
                        )
                        Spacer(modifier = Modifier.width(14.dp))
                        Text(
                            text = stringResource(id = R.string.queue_delete),
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { if (selectedIds.isNotEmpty()) confirmDelete = true }
                                .padding(6.dp)
                        )
                    } else {
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .weight(1f)
                        ) {
                            Text(
                                text = stringResource(id = R.string.page_library_playlists),
                                fontSize = 16.5.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier/*.padding(top = 10.dp)*/
                            )
                            Text(
                                text = stringResource(
                                    id = R.string.page_library_playlists_music_total,
                                    musicList.value?.size ?: 0
                                ),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier
                                    .padding(top = 2.dp)
                                    .overlayEffect()
                                    .alpha(0.35f)
                            )
                        }

                        Row(
                            modifier = Modifier
                                .overlayEffect()
                                .alpha(0.6f),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 排序（破坏性重排，语义对齐上游 sortPlaylist）
                            Box {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_sort),
                                    contentDescription = stringResource(id = R.string.queue_sort),
                                    modifier = Modifier
                                        .size(26.dp)
                                        .clickable { showSortMenu = true }
                                        .padding(3.dp)
                                )
                                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                                    DropdownMenuItem(
                                        text = { Text(stringResource(id = R.string.queue_sort_title_asc)) },
                                        onClick = {
                                            showSortMenu = false
                                            queueScope.launch {
                                                MediaController.sortQueue(compareBy { it.title ?: "" })
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(id = R.string.queue_sort_title_desc)) },
                                        onClick = {
                                            showSortMenu = false
                                            queueScope.launch {
                                                MediaController.sortQueue(compareBy { it.title ?: "" }, descending = true)
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(id = R.string.queue_sort_duration_asc)) },
                                        onClick = {
                                            showSortMenu = false
                                            queueScope.launch {
                                                MediaController.sortQueue(compareBy { it.duration ?: 0L })
                                            }
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(stringResource(id = R.string.queue_sort_duration_desc)) },
                                        onClick = {
                                            showSortMenu = false
                                            queueScope.launch {
                                                MediaController.sortQueue(compareBy { it.duration ?: 0L }, descending = true)
                                            }
                                        }
                                    )
                                }
                            }

                            Text(
                                text = stringResource(id = R.string.queue_edit),
                                fontSize = 14.sp,
                                modifier = Modifier
                                    .padding(start = 2.dp, end = 8.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .clickable {
                                        editMode = true
                                        selectedIds.clear()
                                    }
                                    .padding(6.dp)
                            )

                            ShuffleToggleButton(shuffleModeEnabledLambda, shuffleModeOnChanged)
                            RepeatToggleButton(repeatModeLambda, repeatModeOnChanged, startPadding = 10.dp)
                        }
                    }
                }
            }


            if (hide.value) {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_uitabbar_library),
                        contentDescription = null,
                        modifier = Modifier
                            .overlayEffect()
                            .size(70.dp)
                            .alpha(0.6f)
                    )
                    Text(
                        text = stringResource(id = R.string.playlist_unavailable_title),
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        modifier = Modifier.padding(top = 18.dp, bottom = 12.dp)
                    )
                    YosWrapper {
                        val msg = remember("PlayingList_msg") {
                            derivedStateOf {
                                if (musicList.value.isNullOrEmpty()) {
                                    R.string.playlist_unavailable_desc
                                } else {
                                    R.string.playlist_shuffle_desc
                                }
                            }
                        }
                        Text(
                            text = stringResource(id = msg.value),
                            fontSize = 16.sp,
                            color = Color.White,
                            modifier = Modifier
                                .overlayEffect()
                                .alpha(0.4f)
                        )
                    }
                }
            } else {
                val musicIndex = remember(musicList.value, thisMusicPlayingLambda()) {
                    musicList.value?.indexOf(musicPlaying.value) ?: 0
                }
                val scope = rememberCoroutineScope()
                val state = rememberLazyListState(
                    initialFirstVisibleItemIndex = musicIndex + 1,
                    initialFirstVisibleItemScrollOffset = -15
                )
                val density = LocalDensity.current

                LaunchedEffect(queueKeys) {
                    if (dragActive && dragKey != null) {
                        val sourceIndex = queueKeys.indexOf(dragKey)
                        val geometry = dragGeometry
                        if (sourceIndex < 0 || geometry == null || sourceIndex != geometry.fromIndex) {
                            clearDrag()
                        }
                    }
                }

                // 落定阶段兜底：移动失败或列表迟迟未更新时，避免浮层和隐藏状态永久残留
                LaunchedEffect(dragActive) {
                    if (!dragActive && dragLatched) {
                        delay(800)
                        if (!dragActive) clearDrag()
                    }
                }

                LaunchedEffect(dragActive, itemHeightPx.intValue) {
                    if (dragActive && itemHeightPx.intValue > 0) {
                        val edgeThresholdPx = with(density) { 80.dp.toPx() }
                        val maxSpeedPxPerSecond = with(density) { 480.dp.toPx() }
                        var lastFrameNanos = 0L
                        while (dragActive) {
                            val geometry = dragGeometry ?: break
                            val layoutInfo = state.layoutInfo
                            val draggedCenter = geometry.draggedTopPx + itemHeightPx.intValue / 2f
                            val now = withFrameNanos { it }
                            val elapsedSeconds = if (lastFrameNanos == 0L) 0f
                            else ((now - lastFrameNanos).coerceIn(0L, 100_000_000L) / 1_000_000_000f)
                            lastFrameNanos = now
                            val delta = QueueDragMath.edgeScrollDelta(
                                draggedCenterPx = draggedCenter,
                                viewportStartPx = layoutInfo.viewportStartOffset.toFloat(),
                                viewportEndPx = layoutInfo.viewportEndOffset.toFloat(),
                                edgeThresholdPx = edgeThresholdPx,
                                maxDeltaPx = maxSpeedPxPerSecond * elapsedSeconds,
                                canScrollBackward = state.canScrollBackward,
                                canScrollForward = state.canScrollForward
                            )
                            if (delta != 0f) {
                                var consumed = 0f
                                state.scroll { consumed = scrollBy(delta) }
                                if (consumed != 0f && dragActive) {
                                    dragGeometry = dragGeometry?.afterScroll(consumed)
                                }
                            }
                        }
                    }
                }

                YosWrapper {
                    CompositionLocalProvider(LocalOverscrollConfiguration provides null) {
                        var listWidthPx by remember { mutableIntStateOf(0) }
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .onSizeChanged { listWidthPx = it.width }
                                // queueKeys 必须作为 key：否则闭包固化的还是上一次顺序的映射，
                                // 重排一次后 dragKey 与当前队列对不上，拖拽全程无视觉反馈
                                .pointerInput(editMode, queueKeys) {
                                    awaitEachGesture {
                                        val down = awaitFirstDown(requireUnconsumed = false)
                                        if (!editMode || listWidthPx <= 0) return@awaitEachGesture

                                        val handleStart = listWidthPx - with(density) { 64.dp.toPx() }
                                        if (down.position.x < handleStart) return@awaitEachGesture

                                        val viewportTop = state.layoutInfo.viewportStartOffset
                                        val item = state.layoutInfo.visibleItemsInfo.firstOrNull { info ->
                                            info.index > 0 &&
                                                down.position.y >= info.offset - viewportTop &&
                                                down.position.y < info.offset - viewportTop + info.size
                                        } ?: return@awaitEachGesture
                                        val liveIndex = item.index - 1
                                        if (liveIndex !in (musicList.value ?: emptyList()).indices) {
                                            return@awaitEachGesture
                                        }

                                        val longPress = awaitLongPressOrCancellation(down.id)
                                        if (longPress == null) return@awaitEachGesture

                                        dragGeometry = QueueDragGeometryState(
                                            fromIndex = liveIndex,
                                            originTopPx = item.offset.toFloat()
                                        )
                                        dragKey = queueKeys[liveIndex]
                                        val completed = drag(longPress.id) { change ->
                                            val geometry = dragGeometry?.afterFingerMove(
                                                change.position.y - change.previousPosition.y
                                            )
                                            dragGeometry = geometry
                                            if (!dragActive && geometry != null &&
                                                QueueDragMath.hasCrossedTouchSlop(
                                                    geometry.fingerOffsetPx,
                                                    viewConfiguration.touchSlop
                                                )
                                            ) {
                                                dragActive = true
                                                dragLatched = true
                                            }
                                            change.consume()
                                        }
                                        try {
                                            if (completed && dragActive) {
                                                commitDrag(
                                                    state.layoutInfo.viewportStartOffset,
                                                    state.layoutInfo.viewportEndOffset
                                                )
                                            } else clearDrag()
                                        } finally {
                                            dragActive = false
                                        }
                                    }
                                }
                        ) {
                            LazyColumn(
                                state = state,
                                modifier = Modifier
                                    .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.Offscreen
                            }
                            .drawWithCache {
                                val colors = listOf(
                                        Color.Transparent,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Black,
                                        Color.Transparent
                                )
                                val gradient = Brush.verticalGradient(colors)

                                onDrawWithContent {
                                    drawContent()
                                    drawRect(
                                        brush = gradient,
                                        blendMode = BlendMode.DstIn
                                    )
                                }
                            }/*, contentPadding = PaddingValues(vertical = 12.dp)*/
                        ) {
                            item("blank_before") {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            itemsIndexed(
                                queueItems,
                                key = { index, _ -> queueKeys.getOrNull(index) ?: "index:$index" }
                            ) { index, music ->
                                val currentDragGeometry = displayGeometry?.takeIf { dragLatched }
                                val currentDragIndex = currentDragGeometry?.fromIndex
                                // 与落点一致：让位/落定渲染也按浮层实际显示位置计算
                                val displayedDisplacement = currentDragGeometry?.let {
                                    QueueDragMath.displayedDisplacementPx(
                                        draggedTopPx = it.draggedTopPx,
                                        untransformedTopPx = it.untransformedTopPx,
                                        itemHeightPx = itemHeightPx.intValue,
                                        viewportStartPx = state.layoutInfo.viewportStartOffset,
                                        viewportEndPx = state.layoutInfo.viewportEndOffset
                                    )
                                } ?: 0f
                                val currentTarget = currentDragGeometry?.let {
                                QueueDragMath.targetIndex(
                                    fromIndex = it.fromIndex,
                                    logicalOffsetPx = displayedDisplacement,
                                    itemHeightPx = itemHeightPx.intValue,
                                    itemCount = musicList.value?.size ?: 0
                                )
                            }
                            val targetDisplacement = when {
                                currentDragGeometry == null || itemHeightPx.intValue == 0 -> 0f
                                index == currentDragIndex -> displayedDisplacement
                                currentTarget != null && currentDragIndex != null &&
                                    ((currentTarget > currentDragIndex && index > currentDragIndex && index <= currentTarget) ||
                                        (currentTarget < currentDragIndex && index >= currentTarget && index < currentDragIndex)) ->
                                    if (currentTarget > currentDragIndex) -itemHeightPx.intValue.toFloat()
                                    else itemHeightPx.intValue.toFloat()
                                else -> 0f
                            }
                                
                                // 让位动画：被拖行跟手不动画，其他行平滑让位
                                val animatedDisplacement by animateFloatAsState(
                                    targetValue = targetDisplacement,
                                    animationSpec = if (index == currentDragIndex) snap()
                                                   else tween(durationMillis = 120),
                                    label = "itemDisplacement_$index"
                                )
                                
                                SmallMusicListItem(
                                    music,
                                    index = index,
                                    totalCount = musicList.value?.size ?: 0,
                                    editMode = editMode,
                                    selected = (music.mediaId ?: "index:$index") in selectedIds,
                                    dragging = index == currentDragIndex,
                                    hideWhenDragging = true,
                                    displacementPx = animatedDisplacement,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = tween(durationMillis = 120),
                                        placementSpec = tween(durationMillis = 120)
                                    ),
                                    onRowMeasured = { if (itemHeightPx.intValue == 0) itemHeightPx.intValue = it },
                                    itemClick = {

                                        if (editMode) {
                                            toggleSelect(index, music)
                                        } else {
                                            scope.launch(Dispatchers.IO) {
                                                MediaController.prepare(
                                                    music,
                                                    musicList.value ?: emptyList()
                                                )
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (!editMode) {
                                            editMode = true
                                            selectedIds.clear()
                                        }
                                        toggleSelect(index, music)
                                    }
                                )
                            }
                            item("blank_after") {
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                        }

                        val floatingDrag = displayGeometry?.takeIf { dragLatched }
                        val floatingIndex = floatingDrag?.fromIndex
                        val floatingMusic = floatingIndex?.let { queueItems.getOrNull(it) }
                        if (floatingDrag != null && floatingMusic != null && itemHeightPx.intValue > 0) {
                            SmallMusicListItem(
                                music = floatingMusic,
                                index = floatingIndex,
                                totalCount = queueItems.size,
                                editMode = editMode,
                                selected = (floatingMusic.mediaId ?: "index:$floatingIndex") in selectedIds,
                                dragging = true,
                                hideWhenDragging = false,
                                displacementPx = 0f,
                                onRowMeasured = {},
                                itemClick = {},
                                onLongClick = {},
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .offset {
                                        val info = state.layoutInfo
                                        val top = QueueDragMath.clampDraggedTop(
                                            floatingDrag.draggedTopPx,
                                            itemHeightPx.intValue,
                                            info.viewportStartOffset,
                                            info.viewportEndOffset
                                        )
                                        IntOffset(0, top.roundToInt())
                                    }
                                    .zIndex(10f)
                            )
                        }
                    }
                }
            }

            // 多选删除二次确认
            if (confirmDelete) {
                OptionDialog(
                    icon = {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_trash),
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(52.dp)
                        )
                    },
                    title = stringResource(id = R.string.queue_delete_confirm, selectedIds.size),
                    content = null,
                    positiveContent = stringResource(id = R.string.queue_delete),
                    destructive = true,
                    negativeContent = stringResource(id = R.string.common_cancel),
                    onPositive = {
                        confirmDelete = false
                        val list = musicList.value ?: emptyList()
                        val indices = list.indices.filter { i ->
                            (list[i].mediaId ?: "index:$i") in selectedIds
                        }
                        selectedIds.clear()
                        editMode = false
                        queueScope.launch { MediaController.removeQueueItems(indices) }
                    },
                    onNegative = { confirmDelete = false },
                    onDismissRequest = { confirmDelete = false }
                )
            }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)

@Composable
private fun SmallMusicListItem(
    music: YosMediaItem,
    index: Int,
    totalCount: Int,
    editMode: Boolean = false,
    selected: Boolean = false,
    dragging: Boolean = false,
    hideWhenDragging: Boolean = false,
    displacementPx: Float = 0f,
    onRowMeasured: (Int) -> Unit = {},
    itemClick: () -> Unit,
    onLongClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val floating = dragging && !hideWhenDragging
    val checkboxVisibility = remember {
        androidx.compose.animation.core.MutableTransitionState(editMode)
    }.apply { targetState = editMode }
    val handleVisibility = remember {
        androidx.compose.animation.core.MutableTransitionState(editMode)
    }.apply { targetState = editMode }

    // 拖拽期间保持稳定缩放，避免 spring 追赶手指造成抖动。
    val dragScale by animateFloatAsState(
        targetValue = if (dragging) 1.015f else 1f,
        animationSpec = tween(durationMillis = 100),
        label = "dragScale"
    )

    // 修复问题2：逐行延迟出现，上限240ms
    val itemDelay = minOf(index * 20, 240)


    Row(
        modifier = modifier
            .height(64.dp)
            .fillMaxWidth()
            .alpha(if (dragging && hideWhenDragging) 0f else 1f)
            .onSizeChanged { onRowMeasured(it.height) }
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = displacementPx
                scaleX = dragScale
                scaleY = dragScale
                        shape = RoundedCornerShape(12.dp)
                clip = false
            }
            .background(
                color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else Color.Transparent,
                shape = RoundedCornerShape(12.dp)
            )
            .combinedClickable(
                onClick = itemClick,
                onLongClick = if (editMode) null else onLongClick
            )
            .padding(horizontal = 30.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧选择区：修复问题2、3、8
        AnimatedVisibility(
            visibleState = checkboxVisibility,
            enter = fadeIn(tween(200, delayMillis = itemDelay)) + 
                    expandHorizontally(tween(220, delayMillis = itemDelay, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + 
                   shrinkHorizontally(tween(200, easing = FastOutSlowInEasing))
        ) {
            Row {
                // 选择圆点从封面位置出现（修复问题3：从封面右侧24dp处开始）
                val checkboxOffsetX by animateFloatAsState(
                    targetValue = if (editMode) 0f else 24f,
                    animationSpec = tween(durationMillis = 220, delayMillis = itemDelay, easing = FastOutSlowInEasing),
                    label = "checkboxOffset_$index"
                )
                val checkboxScale by animateFloatAsState(
                    targetValue = if (editMode) 1f else 0.86f,
                    animationSpec = tween(durationMillis = 220, delayMillis = itemDelay, easing = FastOutSlowInEasing),
                    label = "checkboxScale_$index"
                )
                val checkboxBlurPx by transition.animateFloat(
                    transitionSpec = { tween(220, delayMillis = if (targetState == EnterExitState.Visible) itemDelay else 0) },
                    label = "checkboxBlur"
                ) { state ->
                    if (state == EnterExitState.Visible) 0f else with(density) { 6.dp.toPx() }
                }
                val checkboxBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && checkboxBlurPx > 0.01f) {
                    AndroidRenderEffect.createBlurEffect(checkboxBlurPx, checkboxBlurPx, android.graphics.Shader.TileMode.DECAL)
                        .asComposeRenderEffect()
                } else null
                
                Box(
                    modifier = Modifier
                        .size(21.dp)
                        .graphicsLayer {
                            translationX = checkboxOffsetX
                            scaleX = checkboxScale
                            scaleY = checkboxScale
                            if (checkboxBlur != null) {
                                renderEffect = checkboxBlur
                            }
                        }
                        .clip(RoundedCornerShape(50))
                        .background(
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = RoundedCornerShape(50)
                        )
                        .border(
                            width = 1.5.dp,
                            color = if (selected) MaterialTheme.colorScheme.primary
                            else Color.White.copy(alpha = 0.4f),
                            shape = RoundedCornerShape(50)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (selected) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_checkmark),
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(12.dp)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(14.dp))
            }
        }

        // 封面 + 歌曲信息：整体向右让位（修复问题6：文本省略）
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ShadowImageWithCache(
                dataLambda = { music.thumb },
                contentDescription = null,
                modifier = Modifier
                    .size(48.dp)
                    .then(if (dragging && floating) Modifier.shadow(4.dp, RoundedCornerShape(4.dp)) else Modifier),
                cornerRadius = 4.dp,
                shadowAlpha = 0f,
                imageQuality = ImageQuality.LOW
            )

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = 8.dp)
            ) {
                Text(
                    text = music.title ?: defaultTitle,
                    modifier = Modifier.padding(bottom = 1.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 16.sp,
                    lineHeight = 16.sp,
                    style = if (dragging && floating) TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.45f), Offset(0f, 2f), 3f)) else LocalTextStyle.current,
                )

                Text(
                    text = music.artistsName ?: defaultArtistsName,
                    modifier = Modifier.alpha(0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 11.5.sp,
                    lineHeight = 11.5.sp,
                    style = if (dragging && floating) TextStyle(shadow = Shadow(Color.Black.copy(alpha = 0.35f), Offset(0f, 1f), 2f)) else LocalTextStyle.current,
                )
            }
        }

        // 右侧拖拽区：修复问题10、11
        AnimatedVisibility(
            visibleState = handleVisibility,
            enter = fadeIn(tween(200, delayMillis = 80)) + 
                    expandHorizontally(tween(220, delayMillis = 80, easing = FastOutSlowInEasing)),
            exit = fadeOut(tween(180)) + 
                   shrinkHorizontally(tween(200, easing = FastOutSlowInEasing))
        ) {
            // 修复问题10：把手从屏幕右侧32dp外进入
            val handleOffsetX by animateFloatAsState(
                targetValue = if (editMode) 0f else 32f,
                animationSpec = tween(durationMillis = 220, delayMillis = 80, easing = FastOutSlowInEasing),
                label = "handleOffset_$index"
            )
            val handleBlurPx = if (editMode) 0f else with(density) { 5.dp.toPx() }
            val handleBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && handleBlurPx > 0f) {
                AndroidRenderEffect.createBlurEffect(handleBlurPx, handleBlurPx, android.graphics.Shader.TileMode.DECAL)
                    .asComposeRenderEffect()
            } else null
            
            
            Text(
                text = "≡",
                fontSize = 26.sp,
                lineHeight = 26.sp,
                modifier = Modifier
                    .graphicsLayer {
                        translationX = handleOffsetX
                        if (handleBlur != null) {
                            renderEffect = handleBlur
                        }
                    }
                    .alpha(0.45f)
                    .padding(4.dp)
            )
        }
    }
}

@Composable
internal fun Lyric(
    lyricState: YosLyricScrollState,
    lrcEntries: () -> List<LyricEntry>,
    weightLambda: () -> Boolean,
    translationLambda: () -> Boolean,
    mainViewModel: MainViewModel,
    mediaViewModel: MediaViewModel,
    onBackClick: () -> Unit,
    topSpacerHeight: Dp = 110.dp,
    topSpacerWithStatusBar: Boolean = true,
    modifier: Modifier = Modifier,
    lastLineObstructionFraction: Float = 0f
) = YosWrapper {

    val context = LocalContext.current


    Column(
        modifier.fillMaxSize()
    ) {
        YosWrapper {

            Spacer(
                modifier = if (topSpacerWithStatusBar) {
                    Modifier.statusBarsHeight(topSpacerHeight)
                } else {
                    Modifier.height(topSpacerHeight)
                }
            )


            YosLyricView(
                //mediaViewModel = mediaViewModel,
                lyricState = lyricState,
                lrcEntriesLambda = lrcEntries,
                liveTimeLambda = {
                    (mediaControl?.currentPosition ?: 0).toInt()
                },
                mediaEvent = object : YosMediaEvent {
                    override fun onSeek(position: Int) {
                        mediaControl?.seekTo(position.toLong())
                    }
                },
                translationLambda = translationLambda,
                blurLambda = {
                    SettingsLibrary.LyricBlurEffect
                },
                uiConfig = YosUIConfig(
                    noLrcText = stringResource(id = R.string.tip_no_lyrics)
                ),
                weightLambda = weightLambda,
                modifier = Modifier.drawWithCache {
                    val overlayPaint = Paint().apply {
                        blendMode = BlendMode.Plus
                    }
                    val rect = Rect(0f, 0f, size.width, size.height)
                    val colors = if (weightLambda()) {
                        listOf(
                            Color.Transparent,
                            Color(0x59000000),
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color(0x59000000),
                            Color(0x21000000),
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent,
                            Color.Transparent
                        )
                    } else {
                        listOf(
                            // 顶部淡出（4 项 / 20% 区域）
                            Color.Transparent,
                            Color(0x59000000),
                            Color.Black,
                            Color.Black,
                            // 中段实色（歌词主体完全可见，12 项 / 60% 区域）
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            Color.Black,
                            // 底部淡出（4 项 / 20% 区域，与顶部对称）
                            Color(0x59000000),
                            Color(0x3F000000),
                            Color(0x21000000),
                            Color.Transparent
                        )
                    }
                    val gradient = Brush.verticalGradient(colors)

                    onDrawWithContent {
                        val canvas = this.drawContext.canvas
                        canvas.saveLayer(rect, overlayPaint)
                        drawContent()
                        drawRect(
                            brush = gradient,
                            blendMode = BlendMode.DstIn
                        )
                        canvas.restore()
                    }
                },
                onBackClick = onBackClick,
                lastLineObstructionFraction = lastLineObstructionFraction
            )
        }
    }
}

@Composable
internal fun ActionButtonsRow(musicPlayingLambda: () -> YosMediaItem?) {
    var moreMenuOpen by remember { mutableStateOf(false) }
    // 「更多」按钮本体的窗口系边界，供玻璃弹层定位与展开方向判定。
    // 必须是按钮本体（28dp）而不是整行：弹层按锚点右缘内收 27dp 对齐、缩放原点取
    // 最近锚点角，锚点给整行会把菜单甩到行尾（音质胶囊同一个教训）
    var moreAnchorBounds by remember { mutableStateOf(IntRect.Zero) }
    // 按钮本体随面板揭示进度的联动（弹层可见顶边压在锚点上方 7dp，按钮就住在玻璃底下）：
    // 面板向下长时按钮跟着同向下沉一点、缩一点、淡掉，收起后回原位。
    // 联动变换挂在**内层**的 AnimatedContent 上，外层节点的 onGloballyPositioned 报的才是
    // 未被变换的锚点——锚点若被联动带走，弹层会跟着锚点重定位，形成逐帧放大的抖动回环。
    // 旧写法是一个跟 moreMenuOpen 的 animateFloatAsState：点击那一帧就把图标淡掉（面板还没
    // 长到位，玻璃底下先露一个洞），而且只有透明度、没有位移与缩放。
    val moreFollow = rememberLiquidDropdownFollowState()

    Row(
        modifier = Modifier
            .overlayEffect(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val dp = 28.dp

        val context = LocalContext.current
        val scope = rememberCoroutineScope()

        Box(
            modifier = Modifier
                .clickable(
                    onClick = {
                        //println("收藏 开始")
                        val musicPlaying = musicPlayingLambda()
                        //println("收藏 $musicPlaying")
                        if (musicPlaying != null) {
                            Vibrator.click(context)
                            //println("收藏 切换状态
                            // 统一收藏门面：本地 → FavPlayListLibrary；在线 → 酷狗「我喜欢」（乐观更新，失败回滚）
                            val nowFavorite = FavoriteRepository.isFavorite(musicPlaying)
                            scope.launch {
                                val result = if (nowFavorite) {
                                    FavoriteRepository.removeFavorite(musicPlaying)
                                } else {
                                    FavoriteRepository.addFavorite(musicPlaying)
                                }
                                result.onFailure { e ->
                                    // 非阻塞提示（未登录/同步失败等），收藏状态已回滚，播放不受影响
                                    Toast.makeText(
                                        context,
                                        e.message ?: "收藏操作失败",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                }
                            }
                            //println("收藏 完毕")
                        }
                    },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                )
                .size(dp),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = musicPlayingLambda()?.let { FavoriteRepository.isFavorite(it) }
                    ?: false,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                }) {
                if (it) {
                    Icon(
                        painterResource(id = R.drawable.ic_nowplaying_favorited),
                        contentDescription = null,
                        modifier = Modifier
                            .size(dp)
                    )
                } else {
                    Icon(
                        painterResource(id = R.drawable.ic_nowplaying_favorite),
                        contentDescription = null,
                        modifier = Modifier
                            .overlayEffect()
                            .size(dp)
                    )
                }
            }
        }

        Spacer(modifier = Modifier.width(14.dp))

        Box(
            modifier = Modifier
                .clickable(
                    onClick = {
                        Vibrator.click(context)
                        moreMenuOpen = true
                    },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() })
                .size(dp)
                .onGloballyPositioned { coords ->
                    val pos = coords.positionInWindow()
                    val size = coords.size
                    moreAnchorBounds = IntRect(
                        left = pos.x.toInt(),
                        top = pos.y.toInt(),
                        right = pos.x.toInt() + size.width,
                        bottom = pos.y.toInt() + size.height
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            // 图标原本是竖排三点，旋转写在 drawable 里（ic_nowplaying_more*）。
            // 真机逐点探测曾发现：图标中心 ~60x60px 不吃点击（只有外圈能触发），
            // 而结构完全相同的收藏★中心正常；换过三种写法（graphicsLayer 在容器上 /
            // 在图标上 / 点击层改成最上层 Spacer）死区形状一模一样——拦截发生在本
            // 子树之外。根因是控制带顶部铺着一个指针节点，而控制带（0.437f）与
            // 封面页标题行（0.595f）本就重叠 ≈80px，于是它压住了本按钮下半部分。
            // 已把该指针节点上移到 Surface 根（见 NowPlaying 顶部注释）；若死区再现，
            // 先查是否有新的指针/覆盖节点又落回控制带顶部这条重叠带。
            AnimatedContent(
                targetState = moreMenuOpen,
                transitionSpec = {
                    fadeIn() togetherWith fadeOut()
                },
                modifier = Modifier.liquidDropdownAnchorFollow(moreFollow)
            ) {
                if (it) {
                    Icon(
                        painterResource(id = R.drawable.ic_nowplaying_more_fill),
                        contentDescription = null,
                        modifier = Modifier
                            .size(dp)
                    )
                } else {
                    Icon(
                        painterResource(id = R.drawable.ic_nowplaying_more),
                        contentDescription = null,
                        modifier = Modifier
                            .overlayEffect()
                            .size(dp)
                    )
                }
            }
        }
    }

    NowPlayingMoreMenu(
        visible = moreMenuOpen,
        anchorBounds = moreAnchorBounds,
        onAnchorProgress = moreFollow::onProgress,
        onDismiss = { moreMenuOpen = false }
    )
}

/** 更多菜单的段落：Root = 两级都收起（只看到根列表），Speed/Sleep = 对应子面板展开。*/
private enum class MoreSection { Root, Speed, Sleep }

/** 倍速候选（与改造前底部弹层的取值逐值一致）。*/
private val MoreSpeedOptions = listOf(0.25, 0.5, 0.75, 1.0, 1.25, 1.5, 2.0, 3.0, 4.0)

/** 定时关闭候选分钟数（与改造前底部弹层的取值逐值一致）。*/
private val MoreSleepOptions = listOf(5, 10, 15, 30, 60, 90)

/**
 * 更多菜单两块面板的宽度（父子必须一致才对得齐）。钉死而不靠内容量：
 * 列宽取前 8 个子项的最大固有宽度，不钉会随展开跳一帧。Miuix 的合法区间是 200~288dp。
 */
private val MoreMenuPanelWidth = 216.dp

/**
 * 播放页「更多」菜单（ActionButtonsRow 的更多按钮点亮）：玻璃下拉，与音质徽标
 * 点开的面板同材质同动画，**两级堆叠**（Miuix CascadingListPopup 的形态）。
 *
 * 一级是两行入口（倍速播放 / 定时关闭）；点一行后：
 * - 父面板退让——缩到 0.95 并盖一层压暗遮罩（Miuix CascadingPrimaryContent 的
 *   primaryScale + maskAlpha 语义），读得出"它在后面"；
 * - 子面板是**第二块玻璃表面**，堆叠在触发行下缘，靠 zIndex 抬到父面板之上；
 *   它自带全屏点外层，所以点父面板任意处＝收回二级。
 *
 * 与 Miuix 的一处偏离：它把子面板顶边对齐触发行**顶边**（行本身变成子面板的标题行），
 * 本菜单只有两行入口，那样父面板会被整个盖掉、堆叠层次读不出来，所以改成贴行**下缘**。
 *
 * 这里**不再**有音质相关的任何东西：选档只住在进度条下方的音质徽标里，两处各一套
 * 档位列表必然漂移（历史上就是两处各自读写全局偏好才乱掉的）；而"恢复本首跟随设置"
 * 也按产品决定去掉——它的全局出口是设置页：改一次偏好会一并清掉所有本曲覆盖。
 *
 * [onAnchorProgress] 只接给**父面板**：二级堆叠面板长在触发行下方，与按钮本体无关。
 */
@Composable
private fun NowPlayingMoreMenu(
    visible: Boolean,
    anchorBounds: IntRect,
    onAnchorProgress: (LiquidDropdownProgress) -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val currentSpeed = SettingsLibrary.PlaybackSpeed
    val sleepRemaining = MediaController.sleepTimerRemainingSeconds.intValue
    val speedTitle = stringResource(id = R.string.nowplaying_menu_speed)
    val sleepTitle = stringResource(id = R.string.nowplaying_menu_sleep_timer)

    // 二级状态与行锚点提在这一层：父面板写锚点、子面板读锚点
    var section by remember { mutableStateOf(MoreSection.Root) }
    val sleepPanelMinutes = remember(section, sleepRemaining) {
        if (sleepRemaining > 0) {
            ((sleepRemaining + 59) / 60).coerceIn(SleepCustomMinMinutes, SleepCustomMaxMinutes)
        } else {
            SleepCustomMinMinutes
        }
    }
    var speedRowBounds by remember { mutableStateOf(IntRect.Zero) }
    var sleepRowBounds by remember { mutableStateOf(IntRect.Zero) }
    // 退场动画期间 section 已回到 Root，锚点与内容都得留在上一段，否则子面板会在收起
    // 途中跳到 (0,0) 或闪成另一段（Miuix 用 displayedItem 做同一件事）
    var subAnchor by remember { mutableStateOf(IntRect.Zero) }
    var displayedSection by remember { mutableStateOf(MoreSection.Root) }
    LaunchedEffect(section, speedRowBounds, sleepRowBounds) {
        val next = when (section) {
            MoreSection.Speed -> speedRowBounds
            MoreSection.Sleep -> sleepRowBounds
            MoreSection.Root -> IntRect.Zero
        }
        if (next != IntRect.Zero) {
            subAnchor = next
            displayedSection = section
        }
    }
    // 父面板退让进度：弹簧数值同 Miuix（folmeSpring(0.99,0.45s) / (0.95,0.2s) 换算成 stiffness）
    val recede = remember { Animatable(0f) }
    LaunchedEffect(section) {
        val open = section != MoreSection.Root
        recede.animateTo(
            targetValue = if (open) 1f else 0f,
            animationSpec = if (open) {
                spring(dampingRatio = 0.99f, stiffness = 195f)
            } else {
                spring(dampingRatio = 0.95f, stiffness = 987f)
            }
        )
    }
    LaunchedEffect(visible) { if (!visible) section = MoreSection.Root }

    val speedOpen = section == MoreSection.Speed
    val sleepOpen = section == MoreSection.Sleep

    // ---------- 一级：两行入口 ----------
    // 不能 if (!visible) return：弹层靠常驻组合播退出动画（同 MusicQualityIndicator 的用法）
    LiquidDropdownLayout(
        expanded = visible,
        anchorBounds = anchorBounds,
        onDismissRequest = onDismiss,
        // 与音质下拉同材质：播放页专属表面透明度 0.6（设置页保持 0.72/0.8 不变）
        surfaceAlpha = 0.6f,
        backdrop = LocalTitlePageBackdrop.current,
        recedeFraction = { recede.value },
        // 逐帧进度回传给按钮本体：图标随面板的揭示方向下沉/收缩/淡出（与音质胶囊同一套联动）
        onFractionProgress = onAnchorProgress
    ) {
        // 宽度钉死让父子两块面板对齐（列宽取前 8 个子项固有宽度，不钉会随内容跳）；
        // 高度按锚点到屏底余量封顶，超出部分走列自带的 verticalScroll 内滚
        LiquidDropdownColumn(
            modifier = Modifier
                .width(MoreMenuPanelWidth)
                .heightIn(max = liquidDropdownHeightCap(anchorBounds))
        ) {
            MoreEntryRow(
                title = speedTitle,
                value = "${currentSpeed}x",
                expanded = speedOpen,
                isFirst = true,
                onBounds = { speedRowBounds = it },
                onClick = {
                    Vibrator.click(context)
                    section = if (speedOpen) MoreSection.Root else MoreSection.Speed
                }
            )
            MoreEntryRow(
                title = sleepTitle,
                value = if (sleepRemaining > 0)
                    stringResource(id = R.string.sleep_timer_remaining, formatSleepRemaining(sleepRemaining))
                else stringResource(id = R.string.sleep_timer_off),
                expanded = sleepOpen,
                isLast = true,
                onBounds = { sleepRowBounds = it },
                onClick = {
                    Vibrator.click(context)
                    section = if (sleepOpen) MoreSection.Root else MoreSection.Sleep
                }
            )
        }
    }

    // ---------- 二级：堆叠在触发行下方的第二块玻璃 ----------
    LiquidDropdownLayout(
        expanded = visible && section != MoreSection.Root,
        anchorBounds = subAnchor,
        // 点外（含点父面板）只收回二级；返回键也先到这一层——本层的 BackHandler 比父面板
        // 更晚注册，OnBackPressedDispatcher 逆序派发
        onDismissRequest = { section = MoreSection.Root },
        surfaceAlpha = 0.6f,
        backdrop = LocalTitlePageBackdrop.current,
        placement = LiquidDropdownPlacement.StackBelowAnchor,
        // 宿主 slots 是 HashMap 快照、插入序不可控，靠 zIndex 显式压在父面板之上
        zIndex = 1f
    ) {
        LiquidDropdownColumn(
            modifier = Modifier
                .width(MoreMenuPanelWidth)
                .heightIn(max = liquidDropdownHeightCap(subAnchor))
        ) {
            if (displayedSection == MoreSection.Speed) {
                MoreSpeedOptions.forEachIndexed { index, speed ->
                    LiquidDropdownRow(
                        text = "${speed}x",
                        selected = speed == currentSpeed,
                        isFirst = index == 0,
                        isLast = index == MoreSpeedOptions.lastIndex,
                        onClick = {
                            Vibrator.click(context)
                            MediaController.applyPlaybackSpeed(speed)
                            // 选完即收：单选菜单停在屏幕上会让用户以为没生效（同音质面板）
                            onDismiss()
                        }
                    )
                }
            } else {
                LiquidDropdownRow(
                    text = stringResource(id = R.string.sleep_timer_off),
                    selected = sleepRemaining <= 0,
                    isFirst = true,
                    onClick = {
                        Vibrator.click(context)
                        MediaController.cancelSleepTimer()
                        onDismiss()
                    }
                )
                MoreSleepOptions.forEach { minutes ->
                    LiquidDropdownRow(
                        text = stringResource(id = R.string.sleep_timer_minutes, minutes),
                        selected = false,
                        onClick = {
                            Vibrator.click(context)
                            MediaController.startSleepTimer(minutes)
                            onDismiss()
                        }
                    )
                }
                SleepTimerSliderRow(
                    initialMinutes = sleepPanelMinutes,
                    onCommit = { minutes ->
                        MediaController.startSleepTimer(minutes)
                        onDismiss()
                    }
                )
                SleepTimerFinishRow()
            }
        }
    }
}

/** “播完当前歌曲后暂停”受控 Toggle 行。 */
@Composable
private fun SleepTimerFinishRow() {
    val context = LocalContext.current
    val checked = SettingsLibrary.EnableFinishCurrentSongBeforePause
    LiquidDropdownRow(
        text = stringResource(id = R.string.sleep_timer_finish_current),
        selected = false,
        isLast = true,
        showSelectedIcon = false,
        trailing = {
            YosSwitch(
                checked = checked,
                onCheckedChange = { value ->
                    Vibrator.click(context)
                    SettingsLibrary.EnableFinishCurrentSongBeforePause = value
                },
                modifier = Modifier.height(24.dp),
                switchHeight = 24.dp,
                checkedState = { SettingsLibrary.EnableFinishCurrentSongBeforePause },
                // 玻璃采样源对齐面板本体：与 LiquidDropdown 弹层同一块播放页主背景层
                // （LocalTitlePageBackdrop），拇指玻璃采页面内容而非固定深色画板；
                // 弹层槽在记录层之外，不构成循环引用。
                contentBackdrop = LocalTitlePageBackdrop.current
            )
        },
        // 状态只由 Toggle 控件改变；父行保留点击语义但不再重复翻转，避免一次点击被处理两次。
        onClick = {}
    )
}

/** 剩余秒数 → h:mm:ss / mm:ss。 */
private fun formatSleepRemaining(totalSeconds: Int): String {
    val h = totalSeconds / 3600
    val m = (totalSeconds % 3600) / 60
    val s = totalSeconds % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s)
    else String.format("%02d:%02d", m, s)
}

/**
 * 一级入口行：标题 + 当前值摘要 + 展开尖角。
 *
 * [onBounds] 把本行的窗口系边界回传给调用方——它就是二级堆叠面板的锚点（Miuix
 * CascadingPrimaryRow 同一手法：行 Box 的 onGloballyPositioned 喂 anchorBoundsByItem）。
 * 尖角走比面板更快的弹簧（Miuix 的错峰：展开时箭头先到位）：收起朝右、展开朝上
 * （点它收回）。
 *
 * 当前值走 [LiquidDropdownRow] 的 trailing 槽而不是拼进 text（项目 UI 规范：状态标注
 * 必须是独立控件，拼进文本会让这一行比别的行长一截）；入口行没有"选中"语义，
 * 勾选位关掉。
 */
@Composable
private fun MoreEntryRow(
    title: String,
    value: String?,
    expanded: Boolean,
    onBounds: (IntRect) -> Unit,
    isFirst: Boolean = false,
    isLast: Boolean = false,
    onClick: () -> Unit,
) {
    // 尖角走比面板更快的弹簧（Miuix 的错峰：展开时箭头先到位、面板后收尾）
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) -90f else 0f,
        animationSpec = spring(
            dampingRatio = 0.95f,
            stiffness = if (expanded) 987f else 439f
        ),
        label = "moreChevron"
    )
    // 与 LiquidDropdownRow 同一种单色：深色白、浅色黑
    val rowTextColor = Color.Black withNight Color.White
    LiquidDropdownRow(
        text = title,
        selected = false,
        isFirst = isFirst,
        isLast = isLast,
        showSelectedIcon = false,
        modifier = Modifier.onGloballyPositioned { coords ->
            val pos = coords.positionInWindow()
            val size = coords.size
            onBounds(
                IntRect(
                    left = pos.x.toInt(),
                    top = pos.y.toInt(),
                    right = pos.x.toInt() + size.width,
                    bottom = pos.y.toInt() + size.height
                )
            )
        },
        onClick = onClick,
        trailing = {
            if (value != null) {
                Text(text = value, fontSize = 13.sp, color = rowTextColor)
                Spacer(modifier = Modifier.width(8.dp))
            }
            Icon(
                painter = painterResource(id = R.drawable.ic_action_next),
                contentDescription = null,
                modifier = Modifier
                    .height(9.dp)
                    .graphicsLayer { rotationZ = chevronRotation },
                tint = rowTextColor.copy(alpha = 0.45f)
            )
        }
    )
}

/**
 * 定时关闭自定义滑块的档位：90 分钟起、15 分钟一档、上限 240 分钟（沿用旧输入框的 240 上限）。
 * 起点接在预设档（5/10/15/30/60/90）末尾，不重叠也不留空洞。
 */
private val SleepCustomMinMinutes = 90
private val SleepCustomStepMinutes = 15
private val SleepCustomMaxMinutes = 240

/**
 * 定时关闭的自定义时长滑块（替掉旧的"输入框 + 确定"行：那一行在 216dp 面板里装不下，
 * 而且弹层里的输入框依赖输入法焦点，路径很脆）。
 *
 * 规则：横向拖动、有档位（不是无级）、未走到的部分是灰色档位点、走到的部分是实心白条；
 * 白条与圆点随 [spring] 收敛到档位，拖动中轨道加粗——两者都是本文件里进度条/音量条已在用的
 * 语言（[handleSliderGesture]：过 slop 后相对拖动、没过的按落点绝对定位）。
 *
 * 颜色跟面板其余文字同一个单色（深色白、浅色黑）：纯白填充在浅色玻璃上会看不见。
 * 松手即提交（与预设档"选完即收"一致），不需要确定按钮。
 */
@Composable
private fun SleepTimerSliderRow(
    isLast: Boolean = false,
    initialMinutes: Int = SleepCustomMinMinutes,
    onCommit: (Int) -> Unit,
) {
    val context = LocalContext.current
    val steps = (SleepCustomMaxMinutes - SleepCustomMinMinutes) / SleepCustomStepMinutes
    val nearestStep = ((initialMinutes - SleepCustomMinMinutes) / SleepCustomStepMinutes.toFloat())
        .roundToInt().coerceIn(0, steps)
    var stepIndex by remember { mutableIntStateOf(nearestStep) }
    LaunchedEffect(initialMinutes) {
        stepIndex = ((initialMinutes - SleepCustomMinMinutes) / SleepCustomStepMinutes.toFloat())
            .roundToInt().coerceIn(0, steps)
    }
    var dragging by remember { mutableStateOf(false) }
    // 进度用弹簧收敛：跨档时不是瞬移，而是滑进下一格
    val progress by animateFloatAsState(
        targetValue = if (steps == 0) 0f else stepIndex.toFloat() / steps,
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 500f),
        label = "sleepSliderProgress"
    )
    val trackHeight by animateDpAsState(
        targetValue = if (dragging) 9.dp else 6.dp,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioLowBouncy,
            stiffness = 500f
        ),
        label = "sleepSliderTrack"
    )
    val textColor = Color.Black withNight Color.White
    val minutes = SleepCustomMinMinutes + stepIndex * SleepCustomStepMinutes
    val thumbRadius = 8.dp
    // 拇指半径的 px 值在组合期算好：pointerInput 作用域里的 density 是 Float（不是 Density），
    // 不能 with(density)；DrawScope 里的同名属性又才是 Density，两处共用一个预算值最稳
    val thumbPx = with(LocalDensity.current) { thumbRadius.toPx() }
    var trackWidthPx by remember { mutableFloatStateOf(0f) }

    // 档位 i 的圆心 x（内缩一个拇指半径，不让它搭出轨道两端）
    fun xOf(fraction: Float, widthPx: Float): Float {
        val usable = (widthPx - 2f * thumbPx).coerceAtLeast(1f)
        return thumbPx + usable * fraction
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 8.dp,
                end = 8.dp,
                top = 0.dp,
                // 与 LiquidDropdownRow 的 isLast 同规则：末行多给 8dp 呼吸
                bottom = if (isLast) 8.dp else 0.dp
            )
            .padding(start = 14.dp, end = 14.dp, top = 10.5.dp, bottom = 10.5.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(id = R.string.sleep_timer_custom),
                fontSize = 15.6.sp,
                fontWeight = FontWeight.Medium,
                color = textColor,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(id = R.string.sleep_timer_minutes, minutes),
                fontSize = 13.sp,
                color = textColor
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .onSizeChanged { trackWidthPx = it.width.toFloat() }
                .drawBehind {
                    val h = with(density) { trackHeight.toPx() }
                    val y = (size.height - h) / 2f
                    val corner = CornerRadius(h / 2f)
                    // 未走到的部分：灰色档位点（一格一个，读得出不是无级）
                    for (i in (stepIndex + 1)..steps) {
                        drawCircle(
                            color = textColor.copy(alpha = 0.28f),
                            radius = with(density) { 2.2.dp.toPx() },
                            center = Offset(
                                xOf(i.toFloat() / steps, size.width),
                                size.height / 2f
                            )
                        )
                    }
                    // 已走到的部分：实心白条
                    val fillEnd = xOf(progress, size.width)
                    drawRoundRect(
                        color = textColor,
                        topLeft = Offset(0f, y),
                        size = Size(fillEnd, h),
                        cornerRadius = corner
                    )
                    drawCircle(
                        color = textColor,
                        radius = thumbPx,
                        center = Offset(fillEnd, size.height / 2f)
                    )
                }
                .pointerInput(steps) {
                    var downX = 0f
                    var startIndex = 0
                    awaitPointerEventScope {
                        while (true) {
                            handleSliderGesture(
                                relativeDragEnabled = true,
                                onDown = { p ->
                                    dragging = true
                                    downX = p.x
                                    startIndex = stepIndex
                                },
                                onDragDelta = { dx ->
                                    val usable = (trackWidthPx - 2f * thumbPx).coerceAtLeast(1f)
                                    val next = (startIndex + dx / usable * steps)
                                        .roundToInt().coerceIn(0, steps)
                                    if (next != stepIndex) {
                                        stepIndex = next
                                        Vibrator.click(context)
                                    }
                                },
                                onEnd = { wasRelative ->
                                    dragging = false
                                    val committedStep = if (!wasRelative) {
                                        // 没越过 slop：按落点绝对定位（同进度条/音量条的约定）
                                        val usable = (trackWidthPx - 2f * thumbPx).coerceAtLeast(1f)
                                        (((downX - thumbPx) / usable).coerceIn(0f, 1f) * steps)
                                            .roundToInt().coerceIn(0, steps)
                                    } else {
                                        stepIndex
                                    }
                                    stepIndex = committedStep
                                    onCommit(
                                        SleepCustomMinMinutes + committedStep * SleepCustomStepMinutes
                                    )
                                }
                            )
                        }
                    }
                }
        )
    }
}

@Composable
private fun PlayingBar(
    modifier: Modifier,
    albumUrlLambda: () -> Uri?,
    musicPlayingLambda: () -> YosMediaItem?,
    onAlbumClick: () -> Unit
) = YosWrapper {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.5.dp)
            .padding(top = 22.dp)
            .height(70.dp), verticalAlignment = Alignment.CenterVertically
    ) {
        ShadowImageWithCache(
            dataLambda = albumUrlLambda, contentDescription = null, modifier = modifier
                .size(69.dp)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {
                        onAlbumClick()
                    }), cornerRadius = 5.dp,
            imageQuality = ImageQuality.LOW,
            shadowType = ShadowType.Small,
            shadowOverlay = true
        )
        Column(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(start = 12.dp, end = 15.dp)
        ) {
            Text(
                text = musicPlayingLambda()?.title ?: defaultTitle,/*
                fontWeight = FontWeight.Bold,*/
                fontSize = 16.5.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                lineHeight = 16.5.sp
            )
            Text(
                text = musicPlayingLambda()?.artistsName
                    ?: defaultArtistsName,
                fontSize = 15.sp,
                modifier = Modifier.overlayEffect(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.35f)
            )
        }

        YosWrapper {
            ActionButtonsRow(musicPlayingLambda)
        }
    }

}

@Composable
fun RowScope.AirPlay(fill: Boolean = true) {
    val contextCompose = LocalContext.current
    val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    val connectedDevices =
        remember("AirPlay_connectedDevices") { mutableStateOf<List<BluetoothDevice>>(emptyList()) }
    val audioDeviceName = remember("AirPlay_audioDeviceName") { mutableStateOf("") }
    val showName = remember("AirPlay_showName") { mutableStateOf(false) }

    YosWrapper {
        DisposableEffect(Unit) {
            val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED).apply {
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
                addAction("yos.music.player.BLUETOOTH_STATUS_REFRESH")
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val action = intent?.action
                    if (action == BluetoothDevice.ACTION_ACL_CONNECTED || action == BluetoothDevice.ACTION_ACL_DISCONNECTED || action == "yos.music.player.BLUETOOTH_STATUS_REFRESH") {
                        if (ActivityCompat.checkSelfPermission(
                                contextCompose,
                                Manifest.permission.BLUETOOTH_CONNECT
                            ) != PackageManager.PERMISSION_GRANTED
                        ) {
                            return
                        }
                        connectedDevices.value =
                            bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()

                        val thisName =
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                connectedDevices.value.firstOrNull { it.bluetoothClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO && it.isConnected() }?.alias
                            } else {
                                connectedDevices.value.firstOrNull { it.bluetoothClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO && it.isConnected() }?.name
                            }
                        showName.value = thisName != null
                        if (thisName != null) {
                            audioDeviceName.value = thisName.trim()
                        }
                    }
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                contextCompose.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                contextCompose.registerReceiver(receiver, filter)
            }

            if (ActivityCompat.checkSelfPermission(
                    contextCompose,
                    Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            ) {
                connectedDevices.value = bluetoothAdapter?.bondedDevices?.toList() ?: emptyList()
                val thisName =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        connectedDevices.value.firstOrNull { it.bluetoothClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO && it.isConnected() }?.alias
                    } else {
                        connectedDevices.value.firstOrNull { it.bluetoothClass.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO && it.isConnected() }?.name
                    }
                showName.value = thisName != null
                if (thisName != null) {
                    audioDeviceName.value = thisName.trim()
                }
            }

            onDispose {
                runCatching {
                    contextCompose.unregisterReceiver(receiver)
                }
            }
        }
    }

    YosWrapper {
        val context = LocalContext.current

        val systemMediaControlResolver = SystemMediaControlResolver(context)

        val clickModifier = Modifier
            .clickable(
                onClick = {
                    systemMediaControlResolver.intentSystemMediaDialog()
                },
                indication = null,
                interactionSource = remember { MutableInteractionSource() })

        if (fill) {
            // 竖屏：图标在上、设备名在下（原样式）
            Column(
                modifier = Modifier
                    .heightIn(min = 53.dp)
                    .navigationBarsHeight(48.dp)
                    .weight(1f)
                    .then(clickModifier),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(modifier = Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(targetState = showName.value, transitionSpec = {
                        (scaleIn(initialScale = 0.3f) + fadeIn()).togetherWith(
                            scaleOut(
                                targetScale = 0.3f
                            ) + fadeOut()
                        )
                    }, contentAlignment = Alignment.Center) {
                        if (it) {
                            Icon(
                                painterResource(id = R.drawable.ic_earphone),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(27.dp)
                            )
                        } else {
                            Icon(
                                painterResource(id = R.drawable.ic_nowplaying_airplay),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(21.5.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(showName.value, enter = scaleIn(initialScale = 0.3f) + fadeIn(), exit = scaleOut(
                    targetScale = 0.3f
                ) + fadeOut()) {
                    Text(
                        text = audioDeviceName.value,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        } else {
            // 横屏：图标 + 设备名横向排列，贴左下角
            Row(
                modifier = Modifier
                    .widthIn(max = 240.dp)
                    .then(clickModifier),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.height(36.dp), contentAlignment = Alignment.Center) {
                    AnimatedContent(targetState = showName.value, transitionSpec = {
                        (scaleIn(initialScale = 0.3f) + fadeIn()).togetherWith(
                            scaleOut(
                                targetScale = 0.3f
                            ) + fadeOut()
                        )
                    }, contentAlignment = Alignment.Center) {
                        if (it) {
                            Icon(
                                painterResource(id = R.drawable.ic_earphone),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(27.dp)
                            )
                        } else {
                            Icon(
                                painterResource(id = R.drawable.ic_nowplaying_airplay),
                                contentDescription = null,
                                modifier = Modifier
                                    .size(21.5.dp)
                            )
                        }
                    }
                }

                AnimatedVisibility(showName.value, enter = scaleIn(initialScale = 0.3f) + fadeIn(), exit = scaleOut(
                    targetScale = 0.3f
                ) + fadeOut()) {
                    Text(
                        text = audioDeviceName.value,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        lineHeight = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 6.dp)
                    )
                }
            }
        }
    }
}

private fun BluetoothDevice.isConnected(): Boolean {
    return runCatching {
        val isConnectedMethod =
            BluetoothDevice::class.java.getMethod("isConnected")
        isConnectedMethod.isAccessible = true
        isConnectedMethod.invoke(this) as Boolean
    }.getOrDefault(false)
}

// 顶部小把手（横竖屏共用）；topOverride 非空时不再吃状态栏 padding，
// 直接距屏顶该距离（手机横屏状态栏在侧边，沿用竖屏的 top 基准会把把手压低）。
@Composable
private fun NowPlayingHandle(topOverride: Dp? = null) {
    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(
                    if (topOverride != null) Modifier.padding(top = topOverride)
                    else Modifier.statusBarsPadding().padding(top = 20.dp)
                ), contentAlignment = Alignment.Center
        ) {
            Box(
                Modifier
                    .overlayEffect()
                    .size(
                        width = 32.dp,
                        height = 4.5.dp
                    )
                    .background(Color(0x4DFFFFFF), RoundedCornerShape(2.25.dp))
                    .clip(RoundedCornerShape(2.25.dp))
            )
        }
    }
}

// 翻译切换按钮（竖屏控制层顶部 & 横屏右上角共用）
@Composable
internal fun TranslationToggleButton(
    translation: MutableState<Boolean>,
    enabled: Boolean,
    onPress: () -> Unit
) {
    Box(
        modifier = Modifier
            .overlayEffect()
            .alpha(0.4f)
            .clickable(
                enabled = enabled,
                onClick = onPress,
                indication = null,
                interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = translation.value,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }) {
            if (it) {
                Icon(
                    painterResource(id = R.drawable.ic_nowplaying_translateon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                )
            } else {
                Icon(
                    painterResource(id = R.drawable.ic_nowplaying_translate),
                    contentDescription = null,
                    modifier = Modifier
                        .size(30.dp)
                )
            }
        }
    }
}

// 歌词入口按钮（竖屏控制层底部 & 横屏底部悬浮行共用）
@Composable
internal fun LyricsEntryButton(
    modifier: Modifier,
    onLyrics: () -> Unit,
    nowPage: () -> String
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clickable(
                onClick = { onLyrics() },
                indication = null,
                interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = nowPage() == Lyric,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }) {
            if (it) {
                Icon(
                    painterResource(id = R.drawable.ic_nowplaying_lyricson),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                )
            } else {
                Icon(
                    painterResource(id = R.drawable.ic_nowplaying_lyrics),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                )
            }
        }
    }
}

// 队列入口按钮（竖屏控制层底部 & 横屏底部悬浮行共用）
@Composable
internal fun QueueEntryButton(
    modifier: Modifier,
    onPlaylist: () -> Unit,
    nowPage: () -> String
) {
    Box(
        modifier = modifier
            .height(36.dp)
            .clickable(
                onClick = { onPlaylist() },
                indication = null,
                interactionSource = remember { MutableInteractionSource() }),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = nowPage() == PlayingList,
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            }) {
            if (it) {
                Icon(
                    painterResource(id = R.drawable.ic_nowplaying_queueon),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                )
            } else {
                Icon(
                    painterResource(id = R.drawable.ic_nowplaying_queue),
                    contentDescription = null,
                    modifier = Modifier
                        .size(32.dp)
                )
            }
        }
    }
}

// 传输键通用外壳（统一点击震动反馈）
@Composable
private fun TransportButton(
    size: Dp,
    onClick: () -> Unit,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    Box(
        modifier = Modifier
            .size(size)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                onClick = {
                    Vibrator.click(context)
                    onClick()
                }),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PlayPauseIcon(isPlayingLambda: () -> Boolean) {
    // 缩放保留，但透明度用 snap 直接到位：白色图标经父级淡入（graphicsLayer alpha）时
    // 会被渲染进离屏层，overlayEffect 的 Plus 提亮在离屏层里会失效→动画途中偏暗、
    // 结束才变亮。让 alpha 不经过中间态，就不会再出现这个“先暗后亮”的过渡。
    AnimatedContent(targetState = isPlayingLambda(), transitionSpec = {
        (scaleIn(initialScale = 0.3f) + fadeIn(snap())).togetherWith(
            scaleOut(
                targetScale = 0.3f
            ) + fadeOut(snap())
        )
    }) {
        if (it) {
            Icon(
                painterResource(id = R.drawable.ic_nowplaying_pause),
                contentDescription = "Pause",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(10.dp)
            )
        } else {
            Icon(
                painterResource(id = R.drawable.ic_nowplaying_play),
                contentDescription = "Play",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(9.dp)
            )
        }
    }
}

// 随机播放切换（队列页头部 & 横屏传输行共用）。
// 与翻译、歌词、播放列表入口共用透明玻璃图标材质：不绘制纯白背景，
// 通过 overlayEffect + 0.4 alpha 保持播放页控制项的加亮方式一致。
@Composable
internal fun ShuffleToggleButton(
    shuffleModeEnabledLambda: () -> Boolean,
    onShuffleChanged: (Boolean) -> Unit,
    buttonSize: Dp = 36.dp
) {
    val context = LocalContext.current
    YosWrapper {
        Box(
            modifier = Modifier
                .overlayEffect()
                .alpha(0.4f)
                .clickable(
                    onClick = {
                        Vibrator.click(context)
                        mediaControl?.shuffleModeEnabled = !shuffleModeEnabledLambda()
                        mediaControl?.let { YosPlaybackService().setCustomButtons(it) }
                        onShuffleChanged(!shuffleModeEnabledLambda())
                    },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() })
                .size(buttonSize),
            contentAlignment = Alignment.Center
        ) {
            if (shuffleModeEnabledLambda()) {
                Box(
                    modifier = Modifier
                        .size(buttonSize)
                        .background(
                            Color.White,
                            shape = YosRoundedCornerShape(10.dp)
                        )
                )
            }
            Icon(
                painterResource(id = R.drawable.ic_nowplaying_shuffle),
                contentDescription = null,
                modifier = Modifier.size(buttonSize),
                tint = if (shuffleModeEnabledLambda()) Color.Black else Color.White
            )
        }
    }
}

// 循环模式切换（队列页头部 & 横屏传输行共用），使用同一套透明玻璃图标材质。
@Composable
internal fun RepeatToggleButton(
    repeatModeLambda: () -> Int,
    onRepeatChanged: (Int) -> Unit,
    startPadding: Dp = 0.dp,
    buttonSize: Dp = 36.dp
) {
    val context = LocalContext.current
    YosWrapper {
        Box(
            modifier = Modifier
                .overlayEffect()
                .alpha(0.4f)
                .clickable(
                    onClick = {
                        Vibrator.click(context)
                        val targetMode = when (repeatModeLambda()) {
                            REPEAT_MODE_OFF -> REPEAT_MODE_ALL
                            REPEAT_MODE_ALL -> REPEAT_MODE_ONE
                            else -> REPEAT_MODE_OFF
                        }
                        mediaControl?.repeatMode = targetMode
                        mediaControl?.let { YosPlaybackService().setCustomButtons(it) }
                        onRepeatChanged(targetMode)
                    },
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() })
                .padding(start = startPadding)
                .size(buttonSize),
            contentAlignment = Alignment.Center
        ) {
            AnimatedContent(
                targetState = repeatModeLambda(),
                transitionSpec = { fadeIn() togetherWith fadeOut() }
            ) { mode ->
                if (repeatModeLambda() == REPEAT_MODE_ALL || repeatModeLambda() == REPEAT_MODE_ONE) {
                    Box(
                        modifier = Modifier
                            .size(buttonSize)
                            .background(
                                Color.White,
                                shape = YosRoundedCornerShape(10.dp)
                            )
                    )
                }
                Icon(
                    painter = painterResource(
                        id = if (mode == REPEAT_MODE_ONE) R.drawable.ic_nowplaying_repeatone
                        else R.drawable.ic_nowplaying_repeat
                    ),
                    contentDescription = null,
                    modifier = Modifier.size(buttonSize),
                    tint = if (repeatModeLambda() == REPEAT_MODE_ALL || repeatModeLambda() == REPEAT_MODE_ONE) {
                        Color.Black
                    } else {
                        Color.White
                    }
                )
            }
        }
    }
}

@SuppressLint("UnusedBoxWithConstraintsScope")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerControl(
    isPlayingLambda: () -> Boolean,
    isPlayingOnChanged: (Boolean) -> Unit,
    onPrevious: () -> Unit,
    onStatus: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onLyrics: () -> Unit = {},
    onPlaylist: () -> Unit = {},
    nowPage: () -> String = { Album },
    onSlider: () -> Unit,
    onWhile: suspend () -> Unit = {},
    // 控件按下/抬起上报，供外层 shell 判定是否让出竖直收起手势
    onControlGesture: (Boolean) -> Unit = {},
    // 音质下拉展开态上报：宿主据此暂停竖屏"2.5s 自动隐藏控件"
    onQualityMenuExpandedChanged: (Boolean) -> Unit = {},
    shuffleModeEnabledLambda: () -> Boolean = { false },
    onShuffleChanged: (Boolean) -> Unit = {},
    repeatModeLambda: () -> Int = { REPEAT_MODE_OFF },
    onRepeatChanged: (Int) -> Unit = {},
    transportExpands: Boolean = true,
    showShuffleRepeat: Boolean = false,
    showBottomRow: Boolean = true,
    // 传输键额定尺寸与间距：竖屏保持原值，横屏由 NowPlayingLandscape 按参考图收一档。
    // 做成入参而不是直接改常量，是为了不让平板横屏的比例调整牵动竖屏。
    transportButtonSize: Dp = 61.dp,
    playButtonSize: Dp = 58.5.dp,
    transportSpacing: Dp = 43.dp,
    modifier: Modifier = Modifier
) {
    val playingDuration = rememberSaveable(key = "PlayerControl_playingDuration") {
        mutableLongStateOf(0L)
    }
    val context = LocalContext.current
    val playedTime = rememberSaveable(key = "PlayerControl_playedTime") { mutableStateOf("0:00") }
    val remainingTime =
        rememberSaveable(key = "PlayerControl_remainingTime") { mutableStateOf("-0:00") }
    val sliderPosition = remember("PlayerControl_sliderPosition") { mutableFloatStateOf(0f) }
    val isSliding = remember("PlayerControl_isSliding") {
        mutableStateOf(false)
    }
    val sliderInteraction = remember("PlayerControl_sliderInteraction") {
        MutableInteractionSource()
    }
    val sliderPressed by sliderInteraction.collectIsPressedAsState()
    val sliderDragged by sliderInteraction.collectIsDraggedAsState()
    val sliderTouched = remember("PlayerControl_sliderTouched") { mutableStateOf(false) }
    // 进度条相对拖动：轨道像素宽度 + 按下时的位置基准
    val sliderWidthPx = remember("PlayerControl_sliderWidthPx") { mutableFloatStateOf(0f) }
    val sliderRelativeBase = remember("PlayerControl_sliderRelBase") { mutableFloatStateOf(0f) }
    val sliderRelDragged = remember("PlayerControl_sliderRelDragged") { mutableStateOf(false) }
    // 按下期间由本控件独占数值：忽略 M3 内建的绝对拖拽/点按写入
    val sliderPressActive = remember("PlayerControl_sliderPressActive") { mutableStateOf(false) }
    val sliderPressX = remember("PlayerControl_sliderPressX") { mutableFloatStateOf(0f) }
    // 拖动期间换算用的宽度：按下时进度条高度/时间字号会动画变化，
    // 轨道宽度随之轻微改变，故在本次手势开始时冻结一次。
    val sliderDragWidthPx = remember("PlayerControl_sliderDragWidthPx") { mutableFloatStateOf(0f) }
    // sliderTouched 直接监视 pointer down/up，覆盖"按下但未拖动"的场景
    val sliderActive = sliderPressed || sliderDragged || sliderTouched.value
    val sliderPressedSpringDp = spring<Dp>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 500f
    )
    val sliderPressedSpringFloat = spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 500f
    )
    val sliderTrackHeight by animateDpAsState(
        targetValue = if (sliderActive) 10.5.dp else 7.dp,
        animationSpec = sliderPressedSpringDp,
        label = "sliderTrackHeight"
    )
    val sliderTimeFontSize by animateFloatAsState(
        targetValue = if (sliderActive) 19f else 12f,
        animationSpec = sliderPressedSpringFloat,
        label = "sliderTimeFontSize"
    )
    val sliderTimeAlpha by animateFloatAsState(
        targetValue = if (sliderActive) 1f else 0.3f,
        animationSpec = tween(durationMillis = 160),
        label = "sliderTimeAlpha"
    )
    val sliderControlAlpha by animateFloatAsState(
        targetValue = if (sliderActive) 0.7f else 0.45f,
        animationSpec = tween(durationMillis = 160),
        label = "sliderControlAlpha"
    )

    YosWrapper {
        Column(
            modifier = modifier
                .fillMaxWidth()
                .padding(horizontal = 25.dp)
                .padding(bottom = 15.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {

            YosWrapper {
                // 启动作用
                YosWrapper {
                    val lifecycleState =
                        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()

                        LaunchedEffect(Unit) {
                            var lastDurationMs = Long.MIN_VALUE
                            var lastPositionSecond = Long.MIN_VALUE
                            while (true) {
                                if (lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)) {
                                    val duration = mediaControl?.duration ?: 0L
                                    val position = mediaControl?.currentPosition ?: 0L
                                    if (duration != lastDurationMs) {
                                        playingDuration.longValue = duration
                                        lastDurationMs = duration
                                    }
                                    if (!isSliding.value && duration > 0L) {
                                        val totalSeconds = position.coerceAtLeast(0) / 1000
                                        if (totalSeconds != lastPositionSecond) {
                                            playedTime.value = formatTime(totalSeconds)
                                            sliderPosition.floatValue = position.coerceAtLeast(0).toFloat()
                                            val remainingSeconds =
                                                duration.coerceAtLeast(0) / 1000 - totalSeconds
                                            remainingTime.value = "-${formatTime(remainingSeconds)}"
                                            lastPositionSecond = totalSeconds
                                        }
                                    }
                                }
                                delay(700)
                            }
                        }
                }

                // 进度条
                YosWrapper {
                    //println("重组：控制区域内部 - 进度条")
                    Slider(
                        value = sliderPosition.floatValue,
                        onValueChange = { newValue ->
                            // 按下期间数值由本控件独占，忽略 M3 内建绝对拖拽的写入
                            if (!sliderPressActive.value) {
                                isSliding.value = true

                                sliderPosition.floatValue = newValue
                                val newTotalSeconds = newValue.toLong() / 1000
                                playedTime.value = formatTime(newTotalSeconds)

                                val newRemainingSeconds =
                                    playingDuration.longValue / 1000 - newTotalSeconds
                                remainingTime.value = "-${formatTime(newRemainingSeconds)}"

                                onSlider()
                            }
                        },
                        onValueChangeFinished = {
                            // 按下期间统一由 pointerInput 的 onEnd 收尾，避免重复震动/重复 seek
                            if (!sliderPressActive.value) {
                                Vibrator.longClick(context)
                                onSeek(sliderPosition.floatValue)
                                isSliding.value = false
                            }
                        },
                        valueRange = 0f..playingDuration.longValue.toFloat().coerceAtLeast(0f),
                        colors = SliderDefaults.colors(
                            activeTrackColor = Color.White,
                            inactiveTrackColor = Color(0x0DFFFFFF)
                        ),
                        interactionSource = sliderInteraction,
                        modifier = Modifier
                            .onSizeChanged { sliderWidthPx.floatValue = it.width.toFloat() }
                            .pointerInput(Unit) {
                                // finally 保证节点在手势中途被销毁时也会归还手势，
                                // 否则外层会永久认为控件占用中，收起手势失效。
                                try {
                                    awaitPointerEventScope {
                                        while (true) {
                                            handleSliderGesture(
                                                relativeDragEnabled = sliderWidthPx.floatValue > 0f,
                                                // 等真正拿到 down 再置"占用"，理由同音量条
                                                onDown = { p ->
                                                    sliderTouched.value = true
                                                    // 基准/宽度在按下瞬间冻结，并开始独占数值
                                                    sliderRelativeBase.floatValue =
                                                        sliderPosition.floatValue
                                                    sliderDragWidthPx.floatValue =
                                                        sliderWidthPx.floatValue
                                                    sliderPressX.floatValue = p.x
                                                    sliderRelDragged.value = false
                                                    sliderPressActive.value = true
                                                    onControlGesture(true)
                                                },
                                                onDragDelta = { totalDx ->
                                                    isSliding.value = true
                                                    sliderRelDragged.value = true
                                                    val newValue =
                                                        (sliderRelativeBase.floatValue +
                                                                totalDx / sliderDragWidthPx.floatValue *
                                                                playingDuration.longValue)
                                                            .coerceIn(
                                                                0f,
                                                                playingDuration.longValue.toFloat()
                                                                    .coerceAtLeast(0f)
                                                            )
                                                    sliderPosition.floatValue = newValue
                                                    val newTotalSeconds =
                                                        newValue.toLong() / 1000
                                                    playedTime.value =
                                                        formatTime(newTotalSeconds)
                                                    remainingTime.value =
                                                        "-${formatTime(playingDuration.longValue / 1000 - newTotalSeconds)}"
                                                    onSlider()
                                                },
                                                onEnd = { wasRelativeDrag ->
                                                    val target = if (wasRelativeDrag) {
                                                        sliderPosition.floatValue
                                                    } else {
                                                        // 纯点按：按落点绝对定位
                                                        val w = sliderWidthPx.floatValue
                                                        if (w > 0f) {
                                                            (sliderPressX.floatValue / w).coerceIn(0f, 1f) *
                                                                    playingDuration.longValue
                                                        } else {
                                                            sliderPosition.floatValue
                                                        }
                                                    }
                                                    Vibrator.longClick(context)
                                                    onSeek(target)
                                                    isSliding.value = false
                                                    sliderPressActive.value = false
                                                },
                                            )
                                            sliderTouched.value = false
                                            onControlGesture(false)
                                        }
                                    }
                                } finally {
                                    sliderTouched.value = false
                                    sliderPressActive.value = false
                                    onControlGesture(false)
                                }
                            }
                            .overlayEffect()
                            .alpha(sliderControlAlpha)
                            .height(sliderTrackHeight + 6.dp),
                        thumb = {
                        },
                        track = {
                            Track(
                                sliderPositions = SliderPositions(
                                    initialActiveRange = 0f..(sliderPosition.floatValue / playingDuration.longValue)
                                ), height = sliderTrackHeight
                            )
                        }
                    )
                }

                // 控制按钮&进度文本
                YosWrapper {
                    //println("重组：控制区域内部 - 控制按钮&进度文本")
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp, horizontal = 7.dp)
                            .height(22.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = playedTime.value,
                                fontSize = sliderTimeFontSize.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.3.sp,
                                color = Color.White.copy(alpha = sliderTimeAlpha),
                                modifier = Modifier.overlayEffect()
                            )
                            Text(
                                text = remainingTime.value,
                                fontSize = sliderTimeFontSize.sp,
                                fontWeight = FontWeight.Medium,
                                letterSpacing = 0.3.sp,
                                color = Color.White.copy(alpha = sliderTimeAlpha),
                                modifier = Modifier.overlayEffect()
                            )
                        }

                        MusicQualityIndicator(
                            onExpandedChanged = onQualityMenuExpandedChanged
                        )
                    }


                    // 传输键：竖屏 weight 撑满剩余空间（平板三等分 / 手机紧凑）；横屏定高五键
                    if (transportExpands) {
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            BoxWithConstraints(
                                Modifier.fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (maxWidth >= 600.dp) {
                                    // 平板竖屏：三键三等分大间距
                                    Row(
                                        Modifier.fillMaxWidth(),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            TransportButton(transportButtonSize, onPrevious) {
                                                Icon(
                                                    painterResource(id = R.drawable.ic_nowplaying_rewind),
                                                    contentDescription = "Previous",
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(10.dp)
                                                )
                                            }
                                        }
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            TransportButton(playButtonSize, {
                                                isPlayingOnChanged(!isPlayingLambda())
                                                onStatus(isPlayingLambda())
                                            }) {
                                                PlayPauseIcon(isPlayingLambda)
                                            }
                                        }
                                        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                            TransportButton(transportButtonSize, onNext) {
                                                Icon(
                                                    painterResource(id = R.drawable.ic_nowplaying_fforward),
                                                    contentDescription = "Next",
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .padding(10.dp)
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        TransportButton(transportButtonSize, onPrevious) {
                                            Icon(
                                                painterResource(id = R.drawable.ic_nowplaying_rewind),
                                                contentDescription = "Previous",
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(10.dp)
                                            )
                                        }

                                        Spacer(modifier = Modifier.width(transportSpacing))

                                        TransportButton(playButtonSize, {
                                            isPlayingOnChanged(!isPlayingLambda())
                                            onStatus(isPlayingLambda())
                                        }) {
                                            PlayPauseIcon(isPlayingLambda)
                                        }
                                        Spacer(modifier = Modifier.width(transportSpacing))
                                        TransportButton(transportButtonSize, onNext) {
                                            Icon(
                                                painterResource(id = R.drawable.ic_nowplaying_fforward),
                                                contentDescription = "Next",
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .padding(10.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    } else {
                        // 横屏：shuffle + prev/play/next + repeat 五键均布
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ShuffleToggleButton(shuffleModeEnabledLambda, onShuffleChanged)
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TransportButton(transportButtonSize, onPrevious) {
                                    Icon(
                                        painterResource(id = R.drawable.ic_nowplaying_rewind),
                                        contentDescription = "Previous",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(10.dp)
                                    )
                                }

                                Spacer(modifier = Modifier.width(transportSpacing))

                                TransportButton(playButtonSize, {
                                    isPlayingOnChanged(!isPlayingLambda())
                                    onStatus(isPlayingLambda())
                                }) {
                                    PlayPauseIcon(isPlayingLambda)
                                }
                                Spacer(modifier = Modifier.width(transportSpacing))
                                TransportButton(transportButtonSize, onNext) {
                                    Icon(
                                        painterResource(id = R.drawable.ic_nowplaying_fforward),
                                        contentDescription = "Next",
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(10.dp)
                                    )
                                }
                            }
                            RepeatToggleButton(repeatModeLambda, onRepeatChanged)
                        }
                    }
                }
            }

            // 音量调节
            YosWrapper {
                if (SettingsLibrary.NowPlayingShowVolumeBar) {
                    VolumeSlider(context = context, onSlider, onControlGesture)
                }
            }

            // 底部 歌词&播放列表（横屏由 NowPlayingLandscape 承担）
            if (showBottomRow) {
                YosWrapper {
                    //println("重组：控制区域内部 - 底部栏")
                    Row(
                        modifier = Modifier
                            .overlayEffect()
                            .fillMaxWidth()
                            .alpha(0.4f),
                        horizontalArrangement = Arrangement.Center
                    ) {
                        LyricsEntryButton(
                            modifier = Modifier.weight(1f),
                            onLyrics = onLyrics,
                            nowPage = nowPage
                        )

                        Spacer(modifier = Modifier.weight(0.1f))

                        AirPlay()

                        Spacer(modifier = Modifier.weight(0.1f))

                        QueueEntryButton(
                            modifier = Modifier.weight(1f),
                            onPlaylist = onPlaylist,
                            nowPage = nowPage
                        )
                    }
                }
            }

            // 边距填充
            /*YosWrapper {
                Spacer(modifier = Modifier.navigationBarsHeight(5.dp))
            }*/
            // 为显示设备名称，迁移到 AirPlay 底部处理
        }
    }
}

/**
 * 音量条/进度条共用的指针处理：控件整体接管本次手势。
 *
 * M3 Slider 自带的拖拽是**绝对定位**，而且它用的是 material3 内部的 slop 检测
 * 副本，**不理会 change 是否已被消费**，所以在外面 consume 挡不住它：它会在
 * 越过 slop 时抢先写一次绝对数值，既把可见位置闪到手指处，又会污染我们
 * 记录的拖动基准。
 *
 * 因此这里的做法是"整个手势由控件独占"：
 * - 数据基准在**按下瞬间**（onDown）记录，之后不再读取会被 M3 改写的值；
 * - 调用方在按下期间用一个 pressed 标志忽略 M3 的 onValueChange，
 *   数值全部由本回调驱动；
 * - 没越过 slop 的按下按"点按"处理，由调用方在 [onEnd] 里按落点绝对定位，
 *   因此不依赖 M3 自身的点按语义。
 *
 * [onDragDelta] 收到的是**相对按下点的累计位移**（不是本帧增量），调用方用
 * "按下瞬间的数值 + 累计位移/宽度"算目标值。
 */
private suspend fun AwaitPointerEventScope.handleSliderGesture(
    relativeDragEnabled: Boolean,
    onDown: (androidx.compose.ui.geometry.Offset) -> Unit = {},
    onDragDelta: (deltaPx: Float) -> Unit,
    onEnd: (wasRelativeDrag: Boolean) -> Unit,
) {
    val down = awaitFirstDown(
        requireUnconsumed = false,
        pass = PointerEventPass.Initial
    )
    onDown(down.position)
    var relative = false
    var totalDx = 0f
    while (true) {
        val event = awaitPointerEvent(PointerEventPass.Initial)
        if (event.changes.all { !it.pressed }) break
        val change = event.changes.firstOrNull { it.id == down.id } ?: continue
        if (!relative) {
            if (relativeDragEnabled &&
                abs(change.position.x - down.position.x) > viewConfiguration.touchSlop
            ) {
                relative = true
                totalDx = change.position.x - down.position.x
                onDragDelta(totalDx)
                change.consume()
            }
        } else {
            totalDx = change.position.x - down.position.x
            onDragDelta(totalDx)
            change.consume()
        }
    }
    onEnd(relative)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VolumeSlider(
    context: Context,
    onSlider: () -> Unit,
    onControlGesture: (Boolean) -> Unit = {}
) {
    val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
    val currentVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
    val sliderPosition =
        remember("VolumeSlider_sliderPosition") { mutableFloatStateOf(currentVolume / maxVolume.toFloat()) }
    val sliding = remember("VolumeSlider_sliding") {
        mutableStateOf(false)
    }
    val volumeInteraction = remember("VolumeSlider_interaction") {
        MutableInteractionSource()
    }
    val volumePressed by volumeInteraction.collectIsPressedAsState()
    val volumeDragged by volumeInteraction.collectIsDraggedAsState()
    val volumeTouched = remember("VolumeSlider_touched") { mutableStateOf(false) }
    // volumeTouched 直接监视 pointer down/up，覆盖"按下但未拖动"的场景
    val volumeActive = volumePressed || volumeDragged || volumeTouched.value
    val volumePressedSpringDp = spring<Dp>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = 500f
    )
    val volumeTrackHeight by animateDpAsState(
        targetValue = if (volumeActive) 10.5.dp else 7.dp,
        animationSpec = volumePressedSpringDp,
        label = "volumeTrackHeight"
    )
    val volumeIconSize by animateDpAsState(
        targetValue = if (volumeActive) 28.dp else 20.dp,
        animationSpec = volumePressedSpringDp,
        label = "volumeIconSize"
    )
    val volumeAlpha by animateFloatAsState(
        targetValue = if (volumeActive) 0.7f else 0.45f,
        animationSpec = tween(durationMillis = 160),
        label = "volumeAlpha"
    )

    val volumeChangeReceiver = remember("VolumeSlider_volumeChangeReceiver") {
        VolumeChangeReceiver { newVolume ->
            sliderPosition.floatValue = newVolume / maxVolume.toFloat()
        }
    }
    val intentFilter = IntentFilter("android.media.VOLUME_CHANGED_ACTION")

    // 轨道实际像素宽度，用于把拖动距离换算成音量增量；未测量到前回退为绝对定位
    val volumeSliderWidthPx = remember("VolumeSlider_widthPx") { mutableFloatStateOf(0f) }
    val volumeRelativeBase = remember("VolumeSlider_relBase") { mutableFloatStateOf(0f) }
    val volumeRelDragged = remember("VolumeSlider_relDragged") { mutableStateOf(false) }
    // 按下期间由本控件独占数值：忽略 M3 内建的绝对拖拽/点按写入，避免闪过手指坐标
    val volumePressActive = remember("VolumeSlider_pressActive") { mutableStateOf(false) }
    val volumePressX = remember("VolumeSlider_pressX") { mutableFloatStateOf(0f) }
    // 换算用的宽度在按下瞬间冻结：按下时图标/轨道有"变粗"动画，宽度会逐帧变化
    val volumeDragWidthPx = remember("VolumeSlider_dragWidthPx") { mutableFloatStateOf(0f) }
    val volumeSetter = remember("VolumeSlider_setter") {
        { value: Float ->
            sliderPosition.floatValue = value
            audioManager.setStreamVolume(
                AudioManager.STREAM_MUSIC,
                (value * maxVolume).toInt().coerceIn(0, maxVolume),
                0
            )
        }
    }

    DisposableEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(
                volumeChangeReceiver,
                intentFilter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            context.registerReceiver(volumeChangeReceiver, intentFilter)
        }

        onDispose {
            context.unregisterReceiver(volumeChangeReceiver)
        }
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .padding(end = 1.5.dp)
            .padding(horizontal = 8.dp)
            .padding(top = 4.dp, bottom = 2.5.dp)
            .overlayEffect()
            .alpha(volumeAlpha)
    ) {
        Icon(
            painter = painterResource(id = R.drawable.ic_nowplaying_volume),
            contentDescription = "Mute",
            modifier = Modifier.size(volumeIconSize)
        )

        YosWrapper {
            val animatedProgress = if (sliding.value) {
                sliderPosition
            } else {
                animateFloatAsState(
                    targetValue = sliderPosition.floatValue,
                    animationSpec = ProgressIndicatorDefaults.ProgressAnimationSpec,
                    visibilityThreshold = 0.0001f
                )
            }

            Slider(
                value = (animatedProgress.value * maxVolume),
                onValueChange = { newValue ->
                    // 按下期间数值由本控件独占（相对拖动/点按自算），
                    // 忽略 M3 内建绝对拖拽的写入，否则会闪到手指坐标并污染基准。
                    if (!volumePressActive.value) {
                        sliding.value = true
                        sliderPosition.floatValue = newValue / maxVolume
                        audioManager.setStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            newValue.toInt().coerceIn(0, maxVolume),
                            0
                        )
                        onSlider()
                    }
                },
                valueRange = 0f..maxVolume.toFloat(),
                colors = SliderDefaults.colors(
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color(0x0DFFFFFF)
                ),
                interactionSource = volumeInteraction,
                modifier = Modifier
                    .onSizeChanged { volumeSliderWidthPx.floatValue = it.width.toFloat() }
                    .pointerInput(Unit) {
                        // finally 保证节点在手势中途被销毁时也会归还手势，
                        // 否则外层会永久认为控件占用中，收起手势失效。
                        try {
                            awaitPointerEventScope {
                                while (true) {
                                    handleSliderGesture(
                                        relativeDragEnabled = volumeSliderWidthPx.floatValue > 0f,
                                        // 必须等真正拿到 down 再置"占用"：放在 awaitFirstDown
                                        // 之前会在上一次手势结束后立刻重新置位并挂起等待，
                                        // 使外层收起手势被永久禁用。
                                        onDown = { p ->
                                            volumeTouched.value = true
                                            // 按下瞬间冻结基准/宽度，并开始独占数值。
                                            // 基准必须此刻记录：M3 内建拖拽可能随后写入
                                            // 绝对值，晚读就会把基准污染成手指坐标。
                                            volumeRelativeBase.floatValue =
                                                sliderPosition.floatValue
                                            volumeDragWidthPx.floatValue =
                                                volumeSliderWidthPx.floatValue
                                            volumePressX.floatValue = p.x
                                            volumeRelDragged.value = false
                                            volumePressActive.value = true
                                            onControlGesture(true)
                                        },
                                        onDragDelta = { totalDx ->
                                            sliding.value = true
                                            volumeRelDragged.value = true
                                            volumeSetter(
                                                (volumeRelativeBase.floatValue +
                                                        totalDx / volumeDragWidthPx.floatValue)
                                                    .coerceIn(0f, 1f)
                                            )
                                            onSlider()
                                        },
                                        onEnd = { wasRelativeDrag ->
                                            if (wasRelativeDrag) {
                                                Vibrator.longClick(context)
                                            } else {
                                                // 纯点按：按落点绝对定位（原先由 M3 点按语义承担）
                                                val w = volumeSliderWidthPx.floatValue
                                                if (w > 0f) {
                                                    volumeSetter(
                                                        (volumePressX.floatValue / w)
                                                            .coerceIn(0f, 1f)
                                                    )
                                                    Vibrator.longClick(context)
                                                }
                                            }
                                            sliding.value = false
                                            volumePressActive.value = false
                                        },
                                    )
                                    volumeTouched.value = false
                                    onControlGesture(false)
                                }
                            }
                        } finally {
                            volumeTouched.value = false
                            volumePressActive.value = false
                            onControlGesture(false)
                        }
                    }
                    .weight(1f)
                    .padding(start = 1.5.dp, end = 5.dp),
                thumb = {
                },
                track = {
                    Track(
                        sliderPositions = SliderPositions(
                            initialActiveRange = 0f..animatedProgress.value
                        ), height = volumeTrackHeight
                    )
                },
                onValueChangeFinished = {
                    // 按下期间的结束（含震动）统一由 pointerInput 的 onEnd 处理，
                    // 这里只兜底 M3 自身结束手势的情况，避免双次震动。
                    if (!volumePressActive.value) {
                        Vibrator.longClick(context)
                        sliding.value = false
                    }
                }
            )
        }
        Icon(
            painter = painterResource(id = R.drawable.ic_nowplaying_volume_full),
            contentDescription = "Max Volume",
            modifier = Modifier.size(volumeIconSize)
        )
    }
}

@Composable
private fun Track(
    sliderPositions: SliderPositions,
    modifier: Modifier = Modifier,
    height: Dp
) = YosWrapper {
    val inactiveTrackColor = Color.White.copy(alpha = 0.5f)
    val activeTrackColor = Color.White
    val inactiveTickColor = Color.White.copy(alpha = 0.5f)
    val activeTickColor = Color.White
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val isRtl = layoutDirection == LayoutDirection.Rtl
        // 端点内缩一个圆头半径 + 1dp：墨迹完全落在轨道画布内部，
        // 不依赖外层裁剪行为（M3 Slider 会内缩轨道并可能裁掉越界绘制）
        val capInset = height.toPx() / 2f + 1.dp.toPx()
        val sliderLeft = Offset(capInset, center.y)
        val sliderRight = Offset(size.width - capInset, center.y)
        val sliderStart = if (isRtl) sliderRight else sliderLeft
        val sliderEnd = if (isRtl) sliderLeft else sliderRight
        val tickSize = 2.0.dp.toPx()
        val trackStrokeWidth = height.toPx()
        drawLine(
            inactiveTrackColor,
            sliderStart,
            sliderEnd,
            trackStrokeWidth,
            StrokeCap.Round
        )
        val sliderValueEnd = Offset(
            sliderStart.x +
                    (sliderEnd.x - sliderStart.x) * sliderPositions.activeRange.endInclusive,
            center.y
        )

        val sliderValueStart = Offset(
            sliderStart.x +
                    (sliderEnd.x - sliderStart.x) * sliderPositions.activeRange.start,
            center.y
        )

        drawLine(
            activeTrackColor,
            sliderValueStart,
            sliderValueEnd,
            trackStrokeWidth,
            StrokeCap.Round
        )
        sliderPositions.tickFractions.groupBy {
            it > sliderPositions.activeRange.endInclusive ||
                    it < sliderPositions.activeRange.start
        }.forEach { (outsideFraction, list) ->
            drawPoints(
                list.fastMap {
                    Offset(lerp(sliderStart, sliderEnd, it).x, center.y)
                },
                PointMode.Points,
                (if (outsideFraction) inactiveTickColor else activeTickColor),
                tickSize,
                StrokeCap.Round
            )
        }
    }
}

fun formatTime(seconds: Long): String {
    val minutes = seconds / 60
    val secs = seconds % 60
    return "$minutes:${if (secs < 10) "0$secs" else "$secs"}"
}