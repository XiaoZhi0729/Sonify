package yos.music.player.ui.pages.demo

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Visibility
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.media3.common.Player
import androidx.navigation.NavController
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.hdr.LyricHdr
import yos.music.player.code.utils.lrc.LyricEntry
import yos.music.player.code.utils.lrc.YosMediaEvent
import yos.music.player.code.utils.lrc.YosUIConfig
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.player.FadeExo.fadePlay
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.objects.DynamicCoverState
import yos.music.player.data.objects.MainViewModelObject
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.navigation.popBackStackSafely
import yos.music.player.ui.pages.ActionButtonsRow
import yos.music.player.ui.pages.AirPlay
import yos.music.player.ui.pages.ArtistsTapText
import yos.music.player.ui.pages.CommentEntryButton
import yos.music.player.ui.pages.LyricsEntryButton
import yos.music.player.ui.pages.NowPlayingPage
import yos.music.player.ui.pages.PlayerControl
import yos.music.player.ui.pages.PlayerControlBandFraction
import yos.music.player.ui.pages.PlayingList
import yos.music.player.ui.pages.QueueEntryButton
import yos.music.player.ui.pages.TranslationToggleButton
import yos.music.player.ui.pages.comments.SongCommentsPanel
import yos.music.player.ui.pages.playerControlBandHeight
import yos.music.player.ui.widgets.LYRIC_TOP_FADE_INSET_DP
import yos.music.player.ui.widgets.YosLyricScrollState
import yos.music.player.ui.widgets.YosLyricViewLib
import yos.music.player.ui.widgets.basic.LocalTitleOverlayHost
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.TitleOverlayHost
import yos.music.player.ui.widgets.basic.TitleOverlayHostSlot
import yos.music.player.ui.widgets.basic.kugouHash
import yos.music.player.ui.widgets.basic.navigationBarsPadding
import yos.music.player.ui.widgets.basic.rawNavigationBarsBottomDp
import yos.music.player.ui.widgets.basic.rawStatusBarsTopDp
import yos.music.player.ui.widgets.basic.statusBarsPadding
import yos.music.player.ui.widgets.basic.warmUpPlaybackCover
import yos.music.player.ui.widgets.effects.LocalTrackArtworkTransition
import yos.music.player.ui.widgets.effects.YosFloatingLight
import yos.music.player.ui.widgets.effects.overlayEffect
import yos.music.player.ui.widgets.effects.progressiveblur.DemoBackgroundMode
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveBlurConfig
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveBlurMath
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveBlurMode
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveCurveType
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveOrientationMode
import yos.music.player.ui.widgets.effects.progressiveblur.ProgressiveVideoBlurBackground
import yos.music.player.ui.widgets.effects.progressiveblur.ResolvedMirrorSplits
import yos.music.player.ui.widgets.effects.rememberTrackArtworkTransition
import yos.music.player.ui.widgets.effects.warmUpFloatingLightArtwork
import yos.music.player.ui.widgets.effects.warmUpMeshArtwork
import yos.music.player.ui.widgets.lyricTopMaskEndPx
import java.util.Locale
import kotlin.math.roundToInt

private val DemoMorphFloatSpec = spring<Float>(
    dampingRatio = 0.96f,
    stiffness = 400f,
    visibilityThreshold = 0.0005f,
)

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun ProgressiveBlurDemoPage(navController: NavController) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val configuration = LocalConfiguration.current
    val activity = remember(context) { context.findActivity() }
    val originalOrientation = remember(activity) {
        activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    DisposableEffect(activity) {
        onDispose {
            activity?.requestedOrientation = originalOrientation
        }
    }

    val currentSong = MediaController.musicPlaying.value
    val isAudioPlaying = MediaViewModelObject.isPlaying.value
    val lrcEntries = MediaViewModelObject.lrcEntries.value
    val videoUri = DynamicCoverState.videoUri
    val dynamicCoverSettingEnabled = SettingsLibrary.NowPlayingDynamicCover

    val isTablet = configuration.smallestScreenWidthDp >= 600
    val lyricState = remember { YosLyricScrollState() }

    var tabletLandscapeConfig by remember {
        mutableStateOf(
            ProgressiveBlurDemoPrefs.load(
                ProgressiveBlurDemoPrefs.SLOT_TABLET_LANDSCAPE,
                ProgressiveBlurConfig.defaultTabletLandscape(),
            ),
        )
    }
    var tabletPortraitConfig by remember {
        mutableStateOf(
            ProgressiveBlurDemoPrefs.load(
                ProgressiveBlurDemoPrefs.SLOT_TABLET_PORTRAIT,
                ProgressiveBlurConfig.defaultTabletPortrait(),
            ),
        )
    }
    var phoneLandscapeConfig by remember {
        mutableStateOf(
            ProgressiveBlurDemoPrefs.load(
                ProgressiveBlurDemoPrefs.SLOT_PHONE_LANDSCAPE,
                ProgressiveBlurConfig.defaultPhoneLandscape(),
            ),
        )
    }
    var phonePortraitConfig by remember {
        mutableStateOf(
            ProgressiveBlurDemoPrefs.load(
                ProgressiveBlurDemoPrefs.SLOT_PHONE_PORTRAIT,
                ProgressiveBlurConfig.defaultPhonePortrait(),
            ),
        )
    }

    var bgMode by remember { mutableStateOf(ProgressiveBlurDemoPrefs.loadBackgroundMode()) }
    var currentPage by remember { mutableStateOf(NowPlayingPage.Album) }
    val translation = remember { mutableStateOf(true) }
    var showPortraitControls by remember { mutableStateOf(true) }
    val shuffleModeEnabled = remember {
        mutableStateOf(MediaController.mediaControl?.shuffleModeEnabled ?: false)
    }
    val repeatMode = remember {
        mutableIntStateOf(MediaController.mediaControl?.repeatMode ?: Player.REPEAT_MODE_OFF)
    }

    LaunchedEffect(currentPage) {
        if (currentPage != NowPlayingPage.Lyric) {
            showPortraitControls = true
        }
    }

    val showCommentsEntry =
        SettingsLibrary.NowPlayingShowCommentsEntry && currentSong?.kugouHash() != null
    LaunchedEffect(currentSong?.mediaId, showCommentsEntry) {
        if (currentPage == NowPlayingPage.Comment && !showCommentsEntry) {
            currentPage = NowPlayingPage.Album
        }
    }

    var showTuningPanel by remember { mutableStateOf(false) }
    var cleanPreview by remember { mutableStateOf(false) }
    var forceStaticCover by remember { mutableStateOf(false) }
    var keepVideoPlayingWhenPaused by remember { mutableStateOf(true) }

    var sourceUsingVideo by remember { mutableStateOf(false) }
    var sourceWidth by remember { mutableIntStateOf(0) }
    var sourceHeight by remember { mutableIntStateOf(0) }

    val horizontalCutoutPadding = remember(configuration, view, density) {
        val insets = runCatching {
            ViewCompat.getRootWindowInsets(view)
                ?.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout(),
                )
        }.getOrNull()
        val sidePx = maxOf(insets?.left ?: 0, insets?.right ?: 0)
        with(density) { sidePx.toDp() }
    }

    val demoBackdrop = rememberLayerBackdrop {
        drawRect(Color.Black)
        drawContent()
    }
    val demoOverlayHost = remember { TitleOverlayHost() }
    val demoTrackTransition = rememberTrackArtworkTransition(
        source = { MediaViewModelObject.bitmap.value },
        prepare = { target ->
            val uri = target as? android.net.Uri
            if (uri != null) {
                coroutineScope {
                    launch { warmUpPlaybackCover(context, uri) }
                    launch { warmUpFloatingLightArtwork(context, uri) }
                    launch { warmUpMeshArtwork(context, uri) }
                }
            }
        },
    )

    val togglePage: (String) -> Unit = { target ->
        currentPage = if (currentPage == target) NowPlayingPage.Album else target
    }
    val onPreviousAction: () -> Unit = { MediaController.userSkipPrevious() }
    val onNextAction: () -> Unit = { MediaController.userSkipNext() }
    val onStatusAction: (Boolean) -> Unit = { playing ->
        val controller = MediaController.mediaControl
        if (playing) controller?.fadePlay() else controller?.fadePause()
    }
    val onSeekAction: (Float) -> Unit = { pos ->
        MediaController.mediaControl?.seekTo(pos.toLong())
    }
    val onWhileAction: suspend () -> Unit = {
        val controller = MediaController.mediaControl
        shuffleModeEnabled.value = controller?.shuffleModeEnabled ?: false
        repeatMode.intValue = controller?.repeatMode ?: Player.REPEAT_MODE_OFF
    }
    val onOpenArtistDemo: (String) -> Unit = { name ->
        Toast.makeText(context, "Demo：点击歌手 $name", Toast.LENGTH_SHORT).show()
    }
    val onOpenAlbumDemo: () -> Unit = {
        Toast.makeText(context, "Demo：前往该歌曲专辑", Toast.LENGTH_SHORT).show()
    }
    val onOpenLoginDemo: () -> Unit = {
        Toast.makeText(context, "Demo 模式：请返回主界面登录后发表评论", Toast.LENGTH_SHORT).show()
    }

    val lyricHdrEffect = SettingsLibrary.LyricHdrEffect
    val lyricHdrStrength = SettingsLibrary.LyricHdrStrength
    val extendedHdrActive = LyricHdr.isExtendedHdrActive(lyricHdrEffect, lyricHdrStrength)
    val songTitle = currentSong?.title?.takeIf { it.isNotBlank() } ?: defaultTitle

    CompositionLocalProvider(
        LocalTitlePageBackdrop provides demoBackdrop,
        LocalTitleOverlayHost provides demoOverlayHost,
        LocalTrackArtworkTransition provides demoTrackTransition,
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = Color.Black,
            contentColor = Color.White,
        ) {
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black),
            ) {
                val containerMaxWidth = maxWidth
                val containerMaxHeight = maxHeight
                val isLandscape =
                    configuration.orientation == Configuration.ORIENTATION_LANDSCAPE ||
                        containerMaxWidth > containerMaxHeight

                val tuningPanelMaxHeight = when {
                    isLandscape && isTablet -> (containerMaxHeight * 0.44f).coerceIn(220.dp, 340.dp)
                    isLandscape -> (containerMaxHeight * 0.48f).coerceIn(155.dp, 240.dp)
                    else -> (containerMaxHeight * 0.34f).coerceIn(190.dp, 260.dp)
                }

                val activeConfig: ProgressiveBlurConfig
                val activeSlotKey: String
                val activeSlotLabel: String
                val defaultForSlot: ProgressiveBlurConfig
                when {
                    isLandscape && isTablet -> {
                        activeConfig = tabletLandscapeConfig
                        activeSlotKey = ProgressiveBlurDemoPrefs.SLOT_TABLET_LANDSCAPE
                        activeSlotLabel = "平板横屏"
                        defaultForSlot = ProgressiveBlurConfig.defaultTabletLandscape()
                    }
                    isLandscape -> {
                        activeConfig = phoneLandscapeConfig
                        activeSlotKey = ProgressiveBlurDemoPrefs.SLOT_PHONE_LANDSCAPE
                        activeSlotLabel = "手机横屏"
                        defaultForSlot = ProgressiveBlurConfig.defaultPhoneLandscape()
                    }
                    isTablet -> {
                        activeConfig = tabletPortraitConfig
                        activeSlotKey = ProgressiveBlurDemoPrefs.SLOT_TABLET_PORTRAIT
                        activeSlotLabel = "平板竖屏"
                        defaultForSlot = ProgressiveBlurConfig.defaultTabletPortrait()
                    }
                    else -> {
                        activeConfig = phonePortraitConfig
                        activeSlotKey = ProgressiveBlurDemoPrefs.SLOT_PHONE_PORTRAIT
                        activeSlotLabel = "手机竖屏"
                        defaultForSlot = ProgressiveBlurConfig.defaultPhonePortrait()
                    }
                }

                val resolvedSplits = ProgressiveBlurMath.resolveMirrorSplits(
                    viewportWidth = containerMaxWidth.value,
                    viewportHeight = containerMaxHeight.value,
                    config = activeConfig,
                )

                val updateActiveConfig: ((ProgressiveBlurConfig) -> ProgressiveBlurConfig) -> Unit =
                    { transform ->
                        val updated = transform(activeConfig).sanitized()
                        ProgressiveBlurDemoPrefs.save(activeSlotKey, updated)
                        when (activeSlotKey) {
                            ProgressiveBlurDemoPrefs.SLOT_TABLET_LANDSCAPE -> tabletLandscapeConfig = updated
                            ProgressiveBlurDemoPrefs.SLOT_TABLET_PORTRAIT -> tabletPortraitConfig = updated
                            ProgressiveBlurDemoPrefs.SLOT_PHONE_LANDSCAPE -> phoneLandscapeConfig = updated
                            else -> phonePortraitConfig = updated
                        }
                    }

                // Portrait page morph progress: 0f = Album, 1f = Lyric / PlayingList / Comment
                val portraitPageProgress = remember { Animatable(0f) }
                LaunchedEffect(currentPage, isLandscape) {
                    val target = if (!isLandscape && currentPage != NowPlayingPage.Album) 1f else 0f
                    if (isLandscape) {
                        portraitPageProgress.snapTo(0f)
                    } else {
                        portraitPageProgress.animateTo(target, DemoMorphFloatSpec)
                    }
                }

                // Tablet landscape Option A morph progress: 0f = centered Album, 1f = left-docked split pane
                val tabletSplitProgress by animateFloatAsState(
                    targetValue = if (isLandscape && isTablet && currentPage != NowPlayingPage.Album) 1f else 0f,
                    animationSpec = tween(durationMillis = 300, easing = FastOutSlowInEasing),
                    label = "tabletSplitProgress",
                )

                val coverOriginX = if (isLandscape && isTablet) {
                    ProgressiveBlurMath.centeredOriginX(resolvedSplits.splitX, tabletSplitProgress)
                } else {
                    0f
                }
                val pageBlurProgress = if (!isLandscape) portraitPageProgress.value else 0f
                val alphaFadeMode = bgMode != DemoBackgroundMode.OFF

                val statusTopFallbackDp = rawStatusBarsTopDp()
                val portraitFallbackTargetRect = remember(
                    containerMaxWidth,
                    containerMaxHeight,
                    statusTopFallbackDp,
                ) {
                    val w = containerMaxWidth.value.coerceAtLeast(1f)
                    val h = containerMaxHeight.value.coerceAtLeast(1f)
                    val left = 28.5f / w
                    val top = (statusTopFallbackDp.value + 34f) / h
                    val width = 69f / w
                    val height = 69f / h
                    Rect(left, top, left + width, top + height)
                }
                var measuredNormalizedBarRect by remember(isLandscape) { mutableStateOf<Rect?>(null) }
                val activeMorphTargetRect = if (!isLandscape) {
                    measuredNormalizedBarRect ?: portraitFallbackTargetRect
                } else {
                    null
                }
                val morphCornerPx = with(density) { 5.dp.toPx() }

                var rootCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }

                // Main content layer captured for liquid glass dropdowns (ActionButtonsRow / Quality / Sort)
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onGloballyPositioned { rootCoords = it }
                        .layerBackdrop(
                            demoBackdrop,
                            enabled = { demoOverlayHost.slots.isNotEmpty() },
                        ),
                ) {
                    // Layer 0: Production YosFloatingLight (Drift or PearMesh) only when bgMode != OFF
                    if (bgMode != DemoBackgroundMode.OFF) {
                        YosFloatingLight(
                            modifier = Modifier.fillMaxSize(),
                            album = { currentSong?.thumb },
                            isPlaying = { isAudioPlaying || keepVideoPlayingWhenPaused },
                            nowPage = { currentPage },
                            showMiniPlayer = false,
                            enableMesh = bgMode == DemoBackgroundMode.MESH,
                            animateAlways = true,
                            overrideBackgroundMode = if (bgMode == DemoBackgroundMode.MESH) 2 else 1,
                        )
                    }

                    // Layer 1: Progressive Blur GLES dynamic cover + shader-driven portrait cover morph
                    ProgressiveVideoBlurBackground(
                        videoUri = videoUri,
                        fallbackArtworkUrl = currentSong?.thumb,
                        config = activeConfig,
                        isPlaying = isAudioPlaying || keepVideoPlayingWhenPaused,
                        forceStaticCover = forceStaticCover,
                        coverOriginX = coverOriginX,
                        pageBlurProgress = pageBlurProgress,
                        alphaFadeMode = alphaFadeMode,
                        morphTargetRect = activeMorphTargetRect,
                        morphCornerPx = morphCornerPx,
                        onSourceInfoChanged = { usingVideo, width, height ->
                            sourceUsingVideo = usingVideo
                            sourceWidth = width
                            sourceHeight = height
                        },
                        modifier = Modifier.fillMaxSize(),
                    )

                    // Layer 2: Form-factor 1:1 Production Player Chrome
                    when {
                        isLandscape && isTablet -> {
                            DemoTabletLandscapeContent(
                                maxWidth = containerMaxWidth,
                                resolvedSplits = resolvedSplits,
                                horizontalCutoutPadding = horizontalCutoutPadding,
                                tabletSplitProgress = tabletSplitProgress,
                                currentPage = currentPage,
                                currentSong = currentSong,
                                isAudioPlaying = isAudioPlaying,
                                showCommentsEntry = showCommentsEntry,
                                translation = translation,
                                lrcEntries = lrcEntries,
                                lyricState = lyricState,
                                extendedHdrActive = extendedHdrActive,
                                lyricHdrEffect = lyricHdrEffect,
                                shuffleModeEnabled = shuffleModeEnabled,
                                repeatMode = repeatMode,
                                tuningBottomClearance = if (!cleanPreview && showTuningPanel) {
                                    tuningPanelMaxHeight + 24.dp
                                } else {
                                    0.dp
                                },
                                onTogglePage = togglePage,
                                onPrevious = onPreviousAction,
                                onStatus = onStatusAction,
                                onNext = onNextAction,
                                onSeek = onSeekAction,
                                onWhile = onWhileAction,
                                onOpenArtist = onOpenArtistDemo,
                                onOpenAlbum = onOpenAlbumDemo,
                                onOpenLogin = onOpenLoginDemo,
                            )
                        }

                        isLandscape -> {
                            DemoPhoneLandscapeContent(
                                resolvedSplits = resolvedSplits,
                                horizontalCutoutPadding = horizontalCutoutPadding,
                                currentPage = currentPage,
                                currentSong = currentSong,
                                isAudioPlaying = isAudioPlaying,
                                showCommentsEntry = showCommentsEntry,
                                translation = translation,
                                lrcEntries = lrcEntries,
                                lyricState = lyricState,
                                extendedHdrActive = extendedHdrActive,
                                lyricHdrEffect = lyricHdrEffect,
                                shuffleModeEnabled = shuffleModeEnabled,
                                repeatMode = repeatMode,
                                tuningPanelOpen = !cleanPreview && showTuningPanel,
                                onTogglePage = togglePage,
                                onPrevious = onPreviousAction,
                                onStatus = onStatusAction,
                                onNext = onNextAction,
                                onSeek = onSeekAction,
                                onWhile = onWhileAction,
                                onOpenArtist = onOpenArtistDemo,
                                onOpenAlbum = onOpenAlbumDemo,
                                onOpenLogin = onOpenLoginDemo,
                            )
                        }

                        else -> {
                            DemoPortraitContent(
                                maxHeight = containerMaxHeight,
                                isTablet = isTablet,
                                resolvedSplits = resolvedSplits,
                                currentPage = currentPage,
                                portraitPageProgress = portraitPageProgress.value,
                                currentSong = currentSong,
                                isAudioPlaying = isAudioPlaying,
                                showPortraitControls = showPortraitControls,
                                showCommentsEntry = showCommentsEntry,
                                translation = translation,
                                lrcEntries = lrcEntries,
                                lyricState = lyricState,
                                extendedHdrActive = extendedHdrActive,
                                lyricHdrEffect = lyricHdrEffect,
                                shuffleModeEnabled = shuffleModeEnabled,
                                repeatMode = repeatMode,
                                rootCoordsProvider = { rootCoords },
                                tuningBottomClearance = if (!cleanPreview && showTuningPanel) {
                                    tuningPanelMaxHeight + 12.dp
                                } else {
                                    0.dp
                                },
                                onNormalizedBarRectChanged = { rect ->
                                    if (measuredNormalizedBarRect != rect) {
                                        measuredNormalizedBarRect = rect
                                    }
                                },
                                onToggleControls = { showPortraitControls = !showPortraitControls },
                                onWakeControls = { showPortraitControls = true },
                                onTogglePage = togglePage,
                                onReturnAlbum = { currentPage = NowPlayingPage.Album },
                                onPrevious = onPreviousAction,
                                onStatus = onStatusAction,
                                onNext = onNextAction,
                                onSeek = onSeekAction,
                                onWhile = onWhileAction,
                                onOpenArtist = onOpenArtistDemo,
                                onOpenAlbum = onOpenAlbumDemo,
                                onOpenLogin = onOpenLoginDemo,
                            )
                        }
                    }
                }

                // Floating liquid glass overlay menus from ActionButtonsRow / Quality Pill / PlayingList Sort
                TitleOverlayHostSlot(demoOverlayHost)

                // Top floating Demo Chrome (compact pill)
                AnimatedVisibility(
                    visible = !cleanPreview,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 4.dp, end = 12.dp + horizontalCutoutPadding),
                ) {
                    DemoFloatingBar(
                        isLandscape = isLandscape,
                        showTuningPanel = showTuningPanel,
                        onBack = { navController.popBackStackSafely() },
                        onSwitchLandscape = {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                        },
                        onSwitchPortrait = {
                            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                        },
                        onToggleCleanPreview = { cleanPreview = true },
                        onToggleTuningPanel = { showTuningPanel = !showTuningPanel },
                    )
                }

                // Subtle restore pill when clean preview is active
                AnimatedVisibility(
                    visible = cleanPreview,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .statusBarsPadding()
                        .padding(top = 6.dp, end = 14.dp + horizontalCutoutPadding),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.42f))
                            .border(0.5.dp, Color.White.copy(alpha = 0.2f), RoundedCornerShape(50))
                            .clickable { cleanPreview = false }
                            .padding(horizontal = 10.dp, vertical = 5.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Visibility,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(14.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            text = "退出纯净",
                            color = Color.White,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }

                // Collapsible tuning panel
                val panelAlignment = when {
                    isLandscape && isTablet -> Alignment.BottomEnd
                    isLandscape -> Alignment.BottomStart
                    else -> Alignment.BottomCenter
                }
                AnimatedVisibility(
                    visible = !cleanPreview && showTuningPanel,
                    enter = fadeIn() + slideInVertically { it / 3 },
                    exit = fadeOut() + slideOutVertically { it / 3 },
                    modifier = Modifier
                        .align(panelAlignment)
                        .navigationBarsPadding()
                        .padding(
                            start = 14.dp + horizontalCutoutPadding,
                            end = 14.dp + horizontalCutoutPadding,
                            bottom = 12.dp,
                        ),
                ) {
                    TuningPanelCard(
                        isLandscape = isLandscape,
                        isTablet = isTablet,
                        maxHeight = tuningPanelMaxHeight,
                        slotLabel = activeSlotLabel,
                        config = activeConfig,
                        bgMode = bgMode,
                        resolvedSplits = resolvedSplits,
                        songTitle = songTitle,
                        sourceUsingVideo = sourceUsingVideo,
                        sourceWidth = sourceWidth,
                        sourceHeight = sourceHeight,
                        dynamicCoverSettingEnabled = dynamicCoverSettingEnabled,
                        forceStaticCover = forceStaticCover,
                        keepVideoPlayingWhenPaused = keepVideoPlayingWhenPaused,
                        onSelectBgMode = { mode ->
                            bgMode = mode
                            ProgressiveBlurDemoPrefs.saveBackgroundMode(mode)
                        },
                        onEnableDynamicCover = {
                            SettingsLibrary.NowPlayingDynamicCover = true
                            DynamicCoverState.onSettingChanged()
                        },
                        onToggleForceStatic = { forceStaticCover = !forceStaticCover },
                        onToggleKeepPlaying = { keepVideoPlayingWhenPaused = !keepVideoPlayingWhenPaused },
                        onUpdateConfig = updateActiveConfig,
                        onCopyConfig = {
                            val text = ProgressiveBlurDemoPrefs.formatCopyText(
                                activeSlotLabel,
                                activeConfig,
                                resolvedSplits,
                                bgMode,
                            )
                            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                            clipboard?.setPrimaryClip(ClipData.newPlainText("progressive_blur_config", text))
                            Toast.makeText(context, "已复制当前参数", Toast.LENGTH_SHORT).show()
                        },
                        onResetDefault = {
                            val resetCfg = ProgressiveBlurDemoPrefs.reset(activeSlotKey, defaultForSlot)
                            when (activeSlotKey) {
                                ProgressiveBlurDemoPrefs.SLOT_TABLET_LANDSCAPE -> tabletLandscapeConfig = resetCfg
                                ProgressiveBlurDemoPrefs.SLOT_TABLET_PORTRAIT -> tabletPortraitConfig = resetCfg
                                ProgressiveBlurDemoPrefs.SLOT_PHONE_LANDSCAPE -> phoneLandscapeConfig = resetCfg
                                else -> phonePortraitConfig = resetCfg
                            }
                        },
                        onCollapse = { showTuningPanel = false },
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoPortraitContent(
    maxHeight: Dp,
    isTablet: Boolean,
    resolvedSplits: ResolvedMirrorSplits,
    currentPage: String,
    portraitPageProgress: Float,
    currentSong: YosMediaItem?,
    isAudioPlaying: Boolean,
    showPortraitControls: Boolean,
    showCommentsEntry: Boolean,
    translation: MutableState<Boolean>,
    lrcEntries: List<LyricEntry>,
    lyricState: YosLyricScrollState,
    extendedHdrActive: Boolean,
    lyricHdrEffect: Boolean,
    shuffleModeEnabled: MutableState<Boolean>,
    repeatMode: MutableIntState,
    rootCoordsProvider: () -> LayoutCoordinates?,
    tuningBottomClearance: Dp,
    onNormalizedBarRectChanged: (Rect) -> Unit,
    onToggleControls: () -> Unit,
    onWakeControls: () -> Unit,
    onTogglePage: (String) -> Unit,
    onReturnAlbum: () -> Unit,
    onPrevious: () -> Unit,
    onStatus: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onWhile: suspend () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val density = LocalDensity.current
    val statusTopDp = rawStatusBarsTopDp()
    val navBottomDp = rawNavigationBarsBottomDp()
    val controlBandFraction = if (isTablet) 0.397f else PlayerControlBandFraction
    val availableHeight = (maxHeight - statusTopDp - navBottomDp).coerceAtLeast(0.dp)
    val computedBandHeight = playerControlBandHeight(availableHeight, controlBandFraction)
    val controlBandHeight = maxOf(computedBandHeight, 285.dp).coerceAtMost(availableHeight.coerceAtLeast(285.dp))

    var progressTopFromBottom by remember { mutableStateOf<Dp?>(null) }
    var activeSubpage by remember { mutableStateOf(NowPlayingPage.Lyric) }
    if (currentPage != NowPlayingPage.Album) {
        activeSubpage = currentPage
    }

    val p = portraitPageProgress.coerceIn(0f, 1f)

    val controlsVisible = currentPage != NowPlayingPage.Lyric || showPortraitControls
    val controlAlpha by animateFloatAsState(
        targetValue = if (controlsVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
        label = "portraitControlAlpha",
    )
    val controlMounted by remember {
        derivedStateOf { controlAlpha > 0f }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Top handle pill
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 8.dp),
        ) {
            Box(
                modifier = Modifier
                    .overlayEffect()
                    .graphicsLayer { alpha = p }
                    .size(width = 36.dp, height = 4.5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        }

        // Album state cover hitbox (toggles Lyric)
        if (currentPage == NowPlayingPage.Album) {
            val coverHitHeight = maxHeight * resolvedSplits.splitY
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(coverHitHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTogglePage(NowPlayingPage.Lyric) },
                    ),
            )
        }

        // Always-mounted 69dp anchor Box so shader morphTargetRect is measured on frame 1 (no Image drawn here — GLES renders the card smoothly)
        Box(
            modifier = Modifier
                .statusBarsPadding()
                .padding(top = 34.dp, start = 28.5.dp)
                .size(69.dp)
                .onGloballyPositioned { coords ->
                    val root = rootCoordsProvider()
                    if (root != null && root.isAttached && coords.isAttached &&
                        root.size.width > 0 && root.size.height > 0
                    ) {
                        val topLeft = root.localPositionOf(coords, Offset.Zero)
                        val rw = root.size.width.toFloat()
                        val rh = root.size.height.toFloat()
                        val left = topLeft.x / rw
                        val top = topLeft.y / rh
                        val width = coords.size.width.toFloat() / rw
                        val height = coords.size.height.toFloat() / rh
                        onNormalizedBarRectChanged(Rect(left, top, left + width, top + height))
                    }
                }
                .then(
                    if (currentPage != NowPlayingPage.Album) {
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onReturnAlbum,
                        )
                    } else {
                        Modifier
                    },
                ),
        )

        // Subpage content area inside stationary clipped window above PlayerControl
        if (p > 0.005f) {
            val barTopGap = 34.dp
            val barHeight = 70.dp
            val subpageReserve = maxOf(
                progressTopFromBottom ?: (navBottomDp + controlBandHeight),
                navBottomDp + tuningBottomClearance,
            ) + 8.dp
            val lyricRestingReserve = if (controlsVisible && tuningBottomClearance > 0.dp) {
                tuningBottomClearance + navBottomDp
            } else {
                0.dp
            }
            val windowBottomReserve = when (activeSubpage) {
                NowPlayingPage.Lyric -> lyricRestingReserve
                else -> subpageReserve
            }
            val morphClipReservePx = with(density) {
                (subpageReserve - windowBottomReserve).coerceAtLeast(0.dp).toPx()
            }
            val activeClipBottomReductionPx =
                if (activeSubpage == NowPlayingPage.Lyric && controlsVisible && morphClipReservePx > 0f) {
                    val releaseT = ((p - 0.80f) / 0.20f).coerceIn(0f, 1f)
                    morphClipReservePx * (1f - releaseT)
                } else {
                    0f
                }
            val lyricObstruction = if (controlsVisible) {
                (subpageReserve.value / maxHeight.value.coerceAtLeast(1f)).coerceIn(0f, 0.75f)
            } else {
                0f
            }

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
                    .padding(top = barTopGap + barHeight, bottom = windowBottomReserve)
                    .clipToBounds()
                    .drawWithContent {
                        if (activeClipBottomReductionPx > 0.5f) {
                            clipRect(
                                left = 0f,
                                top = 0f,
                                right = size.width,
                                bottom = (size.height - activeClipBottomReductionPx).coerceAtLeast(0f),
                            ) {
                                this@drawWithContent.drawContent()
                            }
                        } else {
                            drawContent()
                        }
                    },
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            translationY = size.height * (1f - p)
                            alpha = ((p - 0.12f) / 0.88f).coerceIn(0f, 1f)
                        },
                ) {
                    AnimatedContent(
                        targetState = activeSubpage,
                        transitionSpec = {
                            (scaleIn(initialScale = 0.92f, animationSpec = tween(220)) +
                                fadeIn(tween(220))) togetherWith
                                (scaleOut(targetScale = 0.92f, animationSpec = tween(180)) +
                                    fadeOut(tween(180)))
                        },
                        modifier = Modifier.fillMaxSize(),
                        label = "demoPortraitSubpageSwitch",
                    ) { targetSubpage ->
                        when (targetSubpage) {
                            NowPlayingPage.Lyric -> {
                                key("portrait_lyric") {
                                    DemoSyncedLyrics(
                                        lyricState = lyricState,
                                        lrcEntries = lrcEntries,
                                        translationEnabled = translation.value,
                                        extendedHdrActive = extendedHdrActive,
                                        lyricHdrEffect = lyricHdrEffect,
                                        weightActive = controlsVisible,
                                        lastLineObstructionFraction = lyricObstruction.coerceAtLeast(
                                            if (controlsVisible) controlBandFraction else 0f,
                                        ),
                                        onTap = onToggleControls,
                                        onSeekJump = onWakeControls,
                                    )
                                }
                            }

                            NowPlayingPage.PlayingList -> {
                                PlayingList(
                                    shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                                    shuffleModeOnChanged = { shuffleModeEnabled.value = it },
                                    repeatModeLambda = { repeatMode.intValue },
                                    repeatModeOnChanged = { repeatMode.intValue = it },
                                    thisMusicPlayingLambda = { currentSong },
                                    heightFraction = 1f,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            NowPlayingPage.Comment -> {
                                SongCommentsPanel(
                                    hash = currentSong?.kugouHash(),
                                    onOpenLogin = onOpenLogin,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }

                            else -> Unit
                        }
                    }
                }
            }
        }

        // Persistent Bottom PlayerControl band across all 4 portrait pages
        if (controlMounted) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .height(controlBandHeight)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                        alpha = controlAlpha
                        translationY = (1f - controlAlpha) * 28.dp.toPx()
                    },
            ) {
                PlayerControl(
                    isPlayingLambda = { isAudioPlaying },
                    isPlayingOnChanged = { },
                    onPrevious = onPrevious,
                    onStatus = onStatus,
                    onNext = onNext,
                    onSeek = onSeek,
                    onLyrics = { onTogglePage(NowPlayingPage.Lyric) },
                    onPlaylist = { onTogglePage(NowPlayingPage.PlayingList) },
                    onComments = { onTogglePage(NowPlayingPage.Comment) },
                    showCommentsEntry = showCommentsEntry,
                    nowPage = { currentPage },
                    onSlider = onWakeControls,
                    onWhile = onWhile,
                    shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                    onShuffleChanged = { shuffleModeEnabled.value = it },
                    repeatModeLambda = { repeatMode.intValue },
                    onRepeatChanged = { repeatMode.intValue = it },
                    transportExpands = true,
                    showShuffleRepeat = false,
                    showBottomRow = true,
                    onProgressTopChanged = { sliderCoords ->
                        val root = rootCoordsProvider()
                        if (root != null && root.isAttached && sliderCoords.isAttached) {
                            val y = root.localPositionOf(sliderCoords, Offset.Zero).y
                            progressTopFromBottom = with(density) { (root.size.height - y).toDp() }
                        }
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = 52.dp),
                )
            }
        }

        // Single persistent Title / Artist / ActionButtonsRow header gliding in lockstep with the shader cover morph
        val barTopDp = statusTopDp + 34.dp
        val barHeightDp = 70.dp
        val albumBottomInsetDp =
            (controlBandHeight + navBottomDp - 24.dp).coerceAtLeast(navBottomDp + 220.dp)
        val albumTopDp = (maxHeight - albumBottomInsetDp - barHeightDp).coerceAtLeast(barTopDp)
        val currentTopDp = lerp(albumTopDp, barTopDp, p)
        val currentStartDp = lerp(32.dp, 28.5.dp + 69.dp + 12.dp, p)
        val currentEndDp = lerp(32.dp, 28.5.dp, p)

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = currentTopDp, start = currentStartDp, end = currentEndDp)
                .height(barHeightDp),
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(end = 12.dp),
            ) {
                Text(
                    text = currentSong?.title?.takeIf { it.isNotBlank() } ?: defaultTitle,
                    fontSize = 19.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.graphicsLayer {
                        val s = (19.5f - 3.0f * p) / 19.5f
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
                ArtistsTapText(
                    artistsName = currentSong?.artistsName,
                    placeholder = defaultArtistsName,
                    fontSize = 18.5.sp,
                    onArtistClick = onOpenArtist,
                    modifier = Modifier.graphicsLayer {
                        val s = (18.5f - 3.5f * p) / 18.5f
                        scaleX = s
                        scaleY = s
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ActionButtonsRow(
                    musicPlayingLambda = { currentSong },
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                )
                if (activeSubpage == NowPlayingPage.Lyric && p > 0.15f) {
                    val translateReveal = ((p - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    val translateScale = 0.8f + 0.2f * translateReveal
                    Box(
                        contentAlignment = Alignment.CenterEnd,
                        modifier = Modifier
                            .width(36.dp * translateReveal)
                            .graphicsLayer {
                                alpha = translateReveal
                                scaleX = translateScale
                                scaleY = translateScale
                            },
                    ) {
                        TranslationToggleButton(
                            translation = translation,
                            enabled = currentPage == NowPlayingPage.Lyric && p > 0.6f,
                            onPress = {
                                translation.value = !translation.value
                                SettingsLibrary.NowPlayingTranslation = translation.value
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DemoTabletLandscapeContent(
    maxWidth: Dp,
    resolvedSplits: ResolvedMirrorSplits,
    horizontalCutoutPadding: Dp,
    tabletSplitProgress: Float,
    currentPage: String,
    currentSong: YosMediaItem?,
    isAudioPlaying: Boolean,
    showCommentsEntry: Boolean,
    translation: MutableState<Boolean>,
    lrcEntries: List<LyricEntry>,
    lyricState: YosLyricScrollState,
    extendedHdrActive: Boolean,
    lyricHdrEffect: Boolean,
    shuffleModeEnabled: MutableState<Boolean>,
    repeatMode: MutableIntState,
    tuningBottomClearance: Dp,
    onTogglePage: (String) -> Unit,
    onPrevious: () -> Unit,
    onStatus: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onWhile: suspend () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val coverOriginX = ProgressiveBlurMath.centeredOriginX(resolvedSplits.splitX, tabletSplitProgress)
    val coverCenterX = maxWidth * (coverOriginX + resolvedSplits.splitX * 0.5f)
    val controlsWidth = (maxWidth * resolvedSplits.splitX).coerceIn(360.dp, 470.dp)
    val maxMediaStart = (maxWidth - controlsWidth - 12.dp).coerceAtLeast(12.dp)
    val mediaStartPadding = (coverCenterX - controlsWidth * 0.5f).coerceIn(12.dp, maxMediaStart)
    val rightPaneFraction = (1f - resolvedSplits.splitX).coerceIn(0.48f, 0.56f)

    Box(modifier = Modifier.fillMaxSize()) {
        // Handle pill
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 12.dp),
        ) {
            Box(
                modifier = Modifier
                    .overlayEffect()
                    .size(width = 60.dp, height = 4.5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        }

        // Lockstep Media Column: glides smoothly from center to top-left underneath the GPU cover
        Column(
            modifier = Modifier
                .fillMaxHeight()
                .padding(start = mediaStartPadding, bottom = 52.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .width(controlsWidth),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Tappable cover region above title/controls (toggles Lyric)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTogglePage(NowPlayingPage.Lyric) },
                    ),
            )

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 25.dp),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(end = 15.dp),
                ) {
                    Text(
                        text = currentSong?.title?.takeIf { it.isNotBlank() } ?: defaultTitle,
                        fontSize = 19.5.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ArtistsTapText(
                        artistsName = currentSong?.artistsName,
                        placeholder = defaultArtistsName,
                        fontSize = 18.5.sp,
                        onArtistClick = onOpenArtist,
                    )
                }
                ActionButtonsRow(
                    musicPlayingLambda = { currentSong },
                    onOpenArtist = onOpenArtist,
                    onOpenAlbum = onOpenAlbum,
                )
            }

            PlayerControl(
                isPlayingLambda = { isAudioPlaying },
                isPlayingOnChanged = { },
                onPrevious = onPrevious,
                onStatus = onStatus,
                onNext = onNext,
                onSeek = onSeek,
                onSlider = { },
                onWhile = onWhile,
                shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                onShuffleChanged = { shuffleModeEnabled.value = it },
                repeatModeLambda = { repeatMode.intValue },
                onRepeatChanged = { repeatMode.intValue = it },
                transportExpands = false,
                showShuffleRepeat = true,
                showBottomRow = false,
                transportButtonSize = 52.dp,
                playButtonSize = 52.5.dp,
                transportSpacing = 30.dp,
                modifier = Modifier
                    .padding(top = 6.dp)
                    .width(controlsWidth),
            )
        }

        // Right Split Panel (Lyric / PlayingList / Comment)
        val previousPage = remember { mutableStateOf(currentPage) }
        val paneSlideIn = slideInHorizontally(
            animationSpec = tween(durationMillis = 365, easing = FastOutSlowInEasing),
            initialOffsetX = { fullWidth -> fullWidth },
        ) + fadeIn(animationSpec = tween(250))
        val paneSlideOut = slideOutHorizontally(
            animationSpec = tween(durationMillis = 274, easing = FastOutSlowInEasing),
            targetOffsetX = { fullWidth -> fullWidth },
        ) + fadeOut(animationSpec = tween(205))
        val paneFadeIn = fadeIn(animationSpec = tween(250))
        val paneFadeOut = fadeOut(animationSpec = tween(205))

        Box(
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .fillMaxHeight()
                .fillMaxWidth(rightPaneFraction)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(
                    top = 52.dp,
                    bottom = maxOf(56.dp, tuningBottomClearance),
                    end = 20.dp + horizontalCutoutPadding,
                ),
        ) {
            AnimatedVisibility(
                visible = currentPage == NowPlayingPage.Lyric,
                enter = if (previousPage.value == NowPlayingPage.Album) paneSlideIn else paneFadeIn,
                exit = if (currentPage == NowPlayingPage.PlayingList || currentPage == NowPlayingPage.Comment) {
                    paneFadeOut
                } else {
                    paneSlideOut
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                key("tablet_landscape_lyric") {
                    DemoSyncedLyrics(
                        lyricState = lyricState,
                        lrcEntries = lrcEntries,
                        translationEnabled = translation.value,
                        extendedHdrActive = extendedHdrActive,
                        lyricHdrEffect = lyricHdrEffect,
                        weightActive = false,
                        lastLineObstructionFraction = 0f,
                        onTap = { onTogglePage(NowPlayingPage.Lyric) },
                    )
                }
            }

            AnimatedVisibility(
                visible = currentPage == NowPlayingPage.PlayingList,
                enter = if (previousPage.value == NowPlayingPage.Album) paneSlideIn else paneFadeIn,
                exit = if (currentPage == NowPlayingPage.Lyric || currentPage == NowPlayingPage.Comment) {
                    paneFadeOut
                } else {
                    paneSlideOut
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                PlayingList(
                    shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                    shuffleModeOnChanged = { shuffleModeEnabled.value = it },
                    repeatModeLambda = { repeatMode.intValue },
                    repeatModeOnChanged = { repeatMode.intValue = it },
                    thisMusicPlayingLambda = { currentSong },
                    heightFraction = 1f,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            AnimatedVisibility(
                visible = currentPage == NowPlayingPage.Comment,
                enter = if (previousPage.value == NowPlayingPage.Album) paneSlideIn else paneFadeIn,
                exit = if (currentPage == NowPlayingPage.Lyric || currentPage == NowPlayingPage.PlayingList) {
                    paneFadeOut
                } else {
                    paneSlideOut
                },
                modifier = Modifier.fillMaxSize(),
            ) {
                SongCommentsPanel(
                    hash = currentSong?.kugouHash(),
                    onOpenLogin = onOpenLogin,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        SideEffect { previousPage.value = currentPage }

        // Top-right TranslationToggleButton on Lyric
        if (currentPage == NowPlayingPage.Lyric) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(top = 46.dp, end = 24.dp + horizontalCutoutPadding),
            ) {
                TranslationToggleButton(
                    translation = translation,
                    enabled = true,
                    onPress = {
                        translation.value = !translation.value
                        SettingsLibrary.NowPlayingTranslation = translation.value
                    },
                )
            }
        }

        // Bottom Floating Entry Row matching NowPlayingLandscape.kt
        val bottomRowStartPadding = lerp(
            start = 20.dp + horizontalCutoutPadding,
            stop = (mediaStartPadding + 25.dp).coerceAtLeast(20.dp),
            fraction = tabletSplitProgress,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .alpha(0.4f)
                .navigationBarsPadding()
                .padding(start = bottomRowStartPadding, end = 20.dp + horizontalCutoutPadding, bottom = 4.dp),
        ) {
            AirPlay(fill = false)

            Spacer(Modifier.weight(1f))

            LyricsEntryButton(
                modifier = Modifier.width(56.dp),
                onLyrics = { onTogglePage(NowPlayingPage.Lyric) },
                nowPage = { currentPage },
            )
            if (showCommentsEntry) {
                CommentEntryButton(
                    modifier = Modifier.width(56.dp),
                    onComments = { onTogglePage(NowPlayingPage.Comment) },
                    nowPage = { currentPage },
                )
            }
            QueueEntryButton(
                modifier = Modifier.width(56.dp),
                onPlaylist = { onTogglePage(NowPlayingPage.PlayingList) },
                nowPage = { currentPage },
            )
        }
    }
}

@Composable
private fun DemoPhoneLandscapeContent(
    resolvedSplits: ResolvedMirrorSplits,
    horizontalCutoutPadding: Dp,
    currentPage: String,
    currentSong: YosMediaItem?,
    isAudioPlaying: Boolean,
    showCommentsEntry: Boolean,
    translation: MutableState<Boolean>,
    lrcEntries: List<LyricEntry>,
    lyricState: YosLyricScrollState,
    extendedHdrActive: Boolean,
    lyricHdrEffect: Boolean,
    shuffleModeEnabled: MutableState<Boolean>,
    repeatMode: MutableIntState,
    tuningPanelOpen: Boolean,
    onTogglePage: (String) -> Unit,
    onPrevious: () -> Unit,
    onStatus: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onWhile: suspend () -> Unit,
    onOpenArtist: (String) -> Unit,
    onOpenAlbum: () -> Unit,
    onOpenLogin: () -> Unit,
) {
    val leftWeight = resolvedSplits.splitX.coerceIn(0.35f, 0.58f)
    val paneFadeIn = fadeIn(animationSpec = tween(durationMillis = 260))
    val paneFadeOut = fadeOut(animationSpec = tween(durationMillis = 200))

    Box(modifier = Modifier.fillMaxSize()) {
        // Handle pill
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 18.dp),
        ) {
            Box(
                modifier = Modifier
                    .overlayEffect()
                    .size(width = 60.dp, height = 4.5.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White.copy(alpha = 0.35f)),
            )
        }

        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = horizontalCutoutPadding),
        ) {
            // Left anchored crisp square cover hitbox
            Box(
                modifier = Modifier
                    .weight(leftWeight)
                    .fillMaxHeight()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTogglePage(NowPlayingPage.Lyric) },
                    ),
            )

            // Right control + subpage column
            Column(
                modifier = Modifier
                    .weight(1f - leftWeight)
                    .fillMaxHeight()
                    .statusBarsPadding()
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 18.dp, top = 36.dp, bottom = 8.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .padding(bottom = if (currentPage == NowPlayingPage.Lyric) 4.dp else 10.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(end = 12.dp),
                    ) {
                        Text(
                            text = currentSong?.title?.takeIf { it.isNotBlank() } ?: defaultTitle,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        ArtistsTapText(
                            artistsName = currentSong?.artistsName,
                            placeholder = defaultArtistsName,
                            fontSize = 15.sp,
                            onArtistClick = onOpenArtist,
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        ActionButtonsRow(
                            musicPlayingLambda = { currentSong },
                            onOpenArtist = onOpenArtist,
                            onOpenAlbum = onOpenAlbum,
                        )
                        if (currentPage == NowPlayingPage.Lyric) {
                            TranslationToggleButton(
                                translation = translation,
                                enabled = true,
                                onPress = {
                                    translation.value = !translation.value
                                    SettingsLibrary.NowPlayingTranslation = translation.value
                                },
                            )
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                ) {
                    val controlsAlphaState = animateFloatAsState(
                        targetValue = if (currentPage == NowPlayingPage.Album) 1f else 0f,
                        animationSpec = snap(),
                        label = "phoneLandscapeControlsAlpha",
                    )

                    PlayerControl(
                        isPlayingLambda = { isAudioPlaying },
                        isPlayingOnChanged = { },
                        onPrevious = onPrevious,
                        onStatus = onStatus,
                        onNext = onNext,
                        onSeek = onSeek,
                        onSlider = { },
                        onWhile = onWhile,
                        shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                        onShuffleChanged = { shuffleModeEnabled.value = it },
                        repeatModeLambda = { repeatMode.intValue },
                        onRepeatChanged = { repeatMode.intValue = it },
                        transportExpands = true,
                        showShuffleRepeat = false,
                        showBottomRow = false,
                        transportButtonSize = 48.dp,
                        playButtonSize = 50.dp,
                        transportSpacing = 36.dp,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = controlsAlphaState.value },
                    )

                    if (currentPage != NowPlayingPage.Album) {
                        Box(
                            modifier = Modifier
                                .matchParentSize()
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null,
                                    onClick = { },
                                ),
                        )
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = currentPage == NowPlayingPage.Lyric,
                        enter = paneFadeIn,
                        exit = paneFadeOut,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        key("phone_landscape_lyric") {
                            DemoSyncedLyrics(
                                lyricState = lyricState,
                                lrcEntries = lrcEntries,
                                translationEnabled = translation.value,
                                extendedHdrActive = extendedHdrActive,
                                lyricHdrEffect = lyricHdrEffect,
                                weightActive = tuningPanelOpen,
                                lastLineObstructionFraction = if (tuningPanelOpen) 0.18f else 0f,
                                onTap = { onTogglePage(NowPlayingPage.Lyric) },
                            )
                        }
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = currentPage == NowPlayingPage.PlayingList,
                        enter = paneFadeIn,
                        exit = paneFadeOut,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        PlayingList(
                            shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                            shuffleModeOnChanged = { shuffleModeEnabled.value = it },
                            repeatModeLambda = { repeatMode.intValue },
                            repeatModeOnChanged = { repeatMode.intValue = it },
                            thisMusicPlayingLambda = { currentSong },
                            heightFraction = 1f,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    androidx.compose.animation.AnimatedVisibility(
                        visible = currentPage == NowPlayingPage.Comment,
                        enter = paneFadeIn,
                        exit = paneFadeOut,
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        SongCommentsPanel(
                            hash = currentSong?.kugouHash(),
                            onOpenLogin = onOpenLogin,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }

                // Bottom equal-weight entry row matching NowPlayingLandscapePhone
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .alpha(0.4f)
                        .padding(horizontal = 14.dp),
                ) {
                    val rowScope = this
                    LyricsEntryButton(
                        modifier = Modifier.weight(1f),
                        onLyrics = { onTogglePage(NowPlayingPage.Lyric) },
                        nowPage = { currentPage },
                    )
                    if (showCommentsEntry) {
                        CommentEntryButton(
                            modifier = Modifier.weight(1f),
                            onComments = { onTogglePage(NowPlayingPage.Comment) },
                            nowPage = { currentPage },
                        )
                    }
                    Box(
                        modifier = Modifier.weight(1f),
                        contentAlignment = Alignment.Center,
                    ) {
                        rowScope.AirPlay(fill = false)
                    }
                    QueueEntryButton(
                        modifier = Modifier.weight(1f),
                        onPlaylist = { onTogglePage(NowPlayingPage.PlayingList) },
                        nowPage = { currentPage },
                    )
                }
            }
        }
    }
}

@Composable
private fun DemoSyncedLyrics(
    lyricState: YosLyricScrollState,
    lrcEntries: List<LyricEntry>,
    translationEnabled: Boolean,
    extendedHdrActive: Boolean,
    lyricHdrEffect: Boolean,
    weightActive: Boolean,
    lastLineObstructionFraction: Float,
    onTap: () -> Unit,
    onSeekJump: () -> Unit = {},
) {
    val lyricModifier = if (extendedHdrActive) {
        Modifier
            .fillMaxSize()
            .clipToBounds()
    } else {
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val overlayPaint = Paint().apply {
                    blendMode = if (lyricHdrEffect) BlendMode.Plus else BlendMode.SrcOver
                }
                val rect = Rect(0f, 0f, size.width, size.height)
                val topFadeEnd = lyricTopMaskEndPx(
                    size.height,
                    LYRIC_TOP_FADE_INSET_DP.dp.toPx(),
                )
                val topGradient = if (topFadeEnd > 0f) {
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black),
                        startY = 0f,
                        endY = topFadeEnd,
                    )
                } else {
                    null
                }
                val bottomGradient = Brush.verticalGradient(
                    listOf(
                        Color.Black,
                        Color(0x59000000),
                        Color(0x21000000),
                        Color.Transparent,
                    ),
                    startY = size.height * if (weightActive) 0.65f else 0.80f,
                    endY = size.height,
                )

                onDrawWithContent {
                    val canvas = this.drawContext.canvas
                    canvas.saveLayer(rect, overlayPaint)
                    drawContent()
                    topGradient?.let { drawRect(brush = it, blendMode = BlendMode.DstIn) }
                    drawRect(brush = bottomGradient, blendMode = BlendMode.DstIn)
                    canvas.restore()
                }
            }
            .clipToBounds()
    }

    YosLyricViewLib(
        lyricState = lyricState,
        lrcEntriesLambda = { lrcEntries },
        liveTimeLambda = {
            MainViewModelObject.scrubPositionMs.value
                ?: (MediaController.mediaControl?.currentPosition ?: 0L).toInt()
        },
        mediaEvent = object : YosMediaEvent {
            override fun onSeek(position: Int) {
                MediaController.mediaControl?.seekTo(position.toLong())
                onSeekJump()
            }
        },
        translationLambda = { translationEnabled },
        blurLambda = { SettingsLibrary.LyricBlurEffect },
        uiConfig = YosUIConfig(
            noLrcText = stringResource(id = R.string.tip_no_lyrics),
        ),
        weightLambda = { weightActive },
        modifier = lyricModifier,
        onBackClick = onTap,
        lastLineObstructionFraction = lastLineObstructionFraction,
    )
}

@Composable
private fun DemoFloatingBar(
    isLandscape: Boolean,
    showTuningPanel: Boolean,
    onBack: () -> Unit,
    onSwitchLandscape: () -> Unit,
    onSwitchPortrait: () -> Unit,
    onToggleCleanPreview: () -> Unit,
    onToggleTuningPanel: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(Color.Black.copy(alpha = 0.44f))
            .border(0.5.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 4.dp),
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onBack),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
                modifier = Modifier.size(14.dp),
            )
        }

        OrientationPillOption(
            label = "横",
            selected = isLandscape,
            onClick = onSwitchLandscape,
        )
        OrientationPillOption(
            label = "竖",
            selected = !isLandscape,
            onClick = onSwitchPortrait,
        )

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(if (showTuningPanel) Color.White else Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onToggleTuningPanel)
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            Icon(
                imageVector = Icons.Rounded.Tune,
                contentDescription = "Tuning",
                tint = if (showTuningPanel) Color.Black else Color.White,
                modifier = Modifier.size(13.dp),
            )
            Spacer(Modifier.width(3.dp))
            Text(
                text = "调参",
                color = if (showTuningPanel) Color.Black else Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }

        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.12f))
                .clickable(onClick = onToggleCleanPreview),
        ) {
            Icon(
                imageVector = Icons.Rounded.VisibilityOff,
                contentDescription = "Clean preview",
                tint = Color.White,
                modifier = Modifier.size(13.dp),
            )
        }
    }
}

@Composable
private fun OrientationPillOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) Color.White else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 9.dp, vertical = 3.5.dp),
    ) {
        Text(
            text = label,
            color = if (selected) Color.Black else Color.White.copy(alpha = 0.85f),
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TuningPanelCard(
    isLandscape: Boolean,
    isTablet: Boolean,
    maxHeight: Dp,
    slotLabel: String,
    config: ProgressiveBlurConfig,
    bgMode: DemoBackgroundMode,
    resolvedSplits: ResolvedMirrorSplits,
    songTitle: String,
    sourceUsingVideo: Boolean,
    sourceWidth: Int,
    sourceHeight: Int,
    dynamicCoverSettingEnabled: Boolean,
    forceStaticCover: Boolean,
    keepVideoPlayingWhenPaused: Boolean,
    onSelectBgMode: (DemoBackgroundMode) -> Unit,
    onEnableDynamicCover: () -> Unit,
    onToggleForceStatic: () -> Unit,
    onToggleKeepPlaying: () -> Unit,
    onUpdateConfig: ((ProgressiveBlurConfig) -> ProgressiveBlurConfig) -> Unit,
    onCopyConfig: () -> Unit,
    onResetDefault: () -> Unit,
    onCollapse: () -> Unit,
) {
    val cardModifier = when {
        isLandscape && isTablet -> Modifier
            .widthIn(max = 420.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
        isLandscape -> Modifier
            .widthIn(max = 460.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
        else -> Modifier
            .fillMaxWidth()
            .heightIn(max = maxHeight)
    }

    Column(
        modifier = cardModifier
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xC4101218))
            .border(0.5.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(20.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { },
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            val sourceTag = when {
                sourceUsingVideo -> "动态封面视频 (${sourceWidth}×${sourceHeight})"
                sourceWidth > 0 -> "静态封面兜底 (${sourceWidth}×${sourceHeight})"
                else -> "静态封面兜底"
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$songTitle · ${slotLabel}参数",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = if (bgMode == DemoBackgroundMode.OFF) {
                        sourceTag
                    } else {
                        "$sourceTag · 融合模式（边缘自动羽化透出底层动态背景）"
                    },
                    color = if (sourceUsingVideo) Color(0xFF7CE8FF) else Color.White.copy(alpha = 0.65f),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(Color.White.copy(alpha = 0.14f))
                        .clickable(onClick = onCopyConfig)
                        .padding(horizontal = 9.dp, vertical = 5.dp),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ContentCopy,
                        contentDescription = "Copy config",
                        tint = Color.White,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "复制参数",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onResetDefault),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Refresh,
                        contentDescription = "Reset",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(Color.White.copy(alpha = 0.12f))
                        .clickable(onClick = onCollapse),
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Close,
                        contentDescription = "Collapse",
                        tint = Color.White,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
        ) {
            if (!dynamicCoverSettingEnabled) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0x33FFB900))
                        .border(0.5.dp, Color(0x88FFB900), RoundedCornerShape(10.dp))
                        .clickable(onClick = onEnableDynamicCover)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Text(
                        text = "当前设置已关闭专辑动态封面",
                        color = Color(0xFFFFDE85),
                        fontSize = 11.5.sp,
                    )
                    Text(
                        text = "一键开启",
                        color = Color(0xFFFFB900),
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            // 3-way Background Mode Switcher
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.padding(bottom = 6.dp),
            ) {
                DemoChip(
                    label = "背景: 关闭",
                    active = bgMode == DemoBackgroundMode.OFF,
                    onClick = { onSelectBgMode(DemoBackgroundMode.OFF) },
                )
                DemoChip(
                    label = "背景: 慢速漂移",
                    active = bgMode == DemoBackgroundMode.DRIFT,
                    onClick = { onSelectBgMode(DemoBackgroundMode.DRIFT) },
                )
                DemoChip(
                    label = "背景: 流体网格",
                    active = bgMode == DemoBackgroundMode.MESH,
                    onClick = { onSelectBgMode(DemoBackgroundMode.MESH) },
                )
            }

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                DemoChip(
                    label = if (config.blurMode == ProgressiveBlurMode.TRUE_PROGRESSIVE) {
                        "真·多级渐进"
                    } else {
                        "iOS 蒙版"
                    },
                    active = config.blurMode == ProgressiveBlurMode.TRUE_PROGRESSIVE,
                    onClick = {
                        onUpdateConfig {
                            it.copy(
                                blurMode = if (it.blurMode == ProgressiveBlurMode.TRUE_PROGRESSIVE) {
                                    ProgressiveBlurMode.ALPHA_MASKED_IOS
                                } else {
                                    ProgressiveBlurMode.TRUE_PROGRESSIVE
                                },
                            )
                        }
                    },
                )
                DemoChip(
                    label = if (config.curveType == ProgressiveCurveType.SOFTERSTEP) {
                        "Softerstep"
                    } else {
                        "Smoothstep"
                    },
                    active = config.curveType == ProgressiveCurveType.SOFTERSTEP,
                    onClick = {
                        onUpdateConfig {
                            it.copy(
                                curveType = if (it.curveType == ProgressiveCurveType.SOFTERSTEP) {
                                    ProgressiveCurveType.SMOOTHSTEP
                                } else {
                                    ProgressiveCurveType.SOFTERSTEP
                                },
                            )
                        }
                    },
                )
                val modeLabel = when (config.orientationMode) {
                    ProgressiveOrientationMode.TOP_LEFT_2D -> "模式: 左上 2D"
                    ProgressiveOrientationMode.HORIZONTAL -> "模式: 横向"
                    ProgressiveOrientationMode.VERTICAL -> "模式: 纵向"
                }
                DemoChip(
                    label = modeLabel,
                    active = true,
                    onClick = {
                        onUpdateConfig {
                            val next = when (it.orientationMode) {
                                ProgressiveOrientationMode.TOP_LEFT_2D -> ProgressiveOrientationMode.HORIZONTAL
                                ProgressiveOrientationMode.HORIZONTAL -> ProgressiveOrientationMode.VERTICAL
                                ProgressiveOrientationMode.VERTICAL -> ProgressiveOrientationMode.TOP_LEFT_2D
                            }
                            val nextCoverSize = when {
                                next == ProgressiveOrientationMode.TOP_LEFT_2D && it.coverSize >= 0.90f -> 0.65f
                                next != ProgressiveOrientationMode.TOP_LEFT_2D && it.coverSize < 0.80f -> 1.00f
                                else -> it.coverSize
                            }
                            it.copy(orientationMode = next, coverSize = nextCoverSize)
                        }
                    },
                )
                DemoChip(
                    label = if (forceStaticCover) "强制静态封面" else "动态视频优先",
                    active = !forceStaticCover,
                    onClick = onToggleForceStatic,
                )
                DemoChip(
                    label = "参考线",
                    active = config.showDebugGuides,
                    onClick = {
                        onUpdateConfig { it.copy(showDebugGuides = !it.showDebugGuides) }
                    },
                )
                DemoChip(
                    label = "暂停时续播背景",
                    active = keepVideoPlayingWhenPaused,
                    onClick = onToggleKeepPlaying,
                )
            }

            Spacer(Modifier.height(6.dp))

            val coverSizeRange = if (config.orientationMode == ProgressiveOrientationMode.TOP_LEFT_2D) {
                0.35f..0.95f
            } else {
                0.65f..1.25f
            }
            val coverSizeText = when (config.orientationMode) {
                ProgressiveOrientationMode.TOP_LEFT_2D -> String.format(
                    Locale.US,
                    "%.2f (宽%d%%×高%d%% · 1:1)",
                    config.coverSize,
                    (resolvedSplits.splitX * 100).roundToInt(),
                    (resolvedSplits.splitY * 100).roundToInt(),
                )
                ProgressiveOrientationMode.HORIZONTAL -> String.format(
                    Locale.US,
                    "%.2f (宽%d%% · 1:1)",
                    config.coverSize,
                    (resolvedSplits.splitX * 100).roundToInt(),
                )
                ProgressiveOrientationMode.VERTICAL -> String.format(
                    Locale.US,
                    "%.2f (高%d%% · 1:1)",
                    config.coverSize,
                    (resolvedSplits.splitY * 100).roundToInt(),
                )
            }
            TuningSliderRow(
                label = "封面大小 coverSize",
                value = config.coverSize,
                valueText = coverSizeText,
                range = coverSizeRange,
                onValueChange = { v -> onUpdateConfig { it.copy(coverSize = v) } },
            )
            TuningSliderRow(
                label = "模糊起点 blurStart",
                value = config.blurStart,
                range = 0.00f..0.85f,
                onValueChange = { v ->
                    onUpdateConfig {
                        it.copy(blurStart = v, blurEnd = maxOf(it.blurEnd, v + 0.02f))
                    }
                },
            )
            TuningSliderRow(
                label = "模糊终点 blurEnd",
                value = config.blurEnd,
                range = 0.10f..1.00f,
                onValueChange = { v ->
                    onUpdateConfig {
                        it.copy(blurEnd = v, blurStart = minOf(it.blurStart, v - 0.02f))
                    }
                },
            )
            if (config.orientationMode == ProgressiveOrientationMode.TOP_LEFT_2D) {
                TuningSliderRow(
                    label = "下侧模糊起点 blurStartY",
                    value = config.blurStartY,
                    range = 0.00f..0.85f,
                    onValueChange = { v ->
                        onUpdateConfig {
                            it.copy(blurStartY = v, blurEndY = maxOf(it.blurEndY, v + 0.02f))
                        }
                    },
                )
                TuningSliderRow(
                    label = "下侧模糊终点 blurEndY",
                    value = config.blurEndY,
                    range = 0.10f..1.00f,
                    onValueChange = { v ->
                        onUpdateConfig {
                            it.copy(blurEndY = v, blurStartY = minOf(it.blurStartY, v - 0.02f))
                        }
                    },
                )
            }
            TuningSliderRow(
                label = "模糊等级 maxBlurLevel",
                value = config.maxBlurLevel,
                range = 0.0f..7.0f,
                onValueChange = { v -> onUpdateConfig { it.copy(maxBlurLevel = v) } },
            )
            TuningSliderRow(
                label = "氛围融合 ambientBlend",
                value = config.ambientBlend,
                range = 0.0f..0.85f,
                onValueChange = { v -> onUpdateConfig { it.copy(ambientBlend = v) } },
            )
            TuningSliderRow(
                label = "过渡指数 curvePower",
                value = config.curvePower,
                range = 0.4f..3.0f,
                onValueChange = { v -> onUpdateConfig { it.copy(curvePower = v) } },
            )
            TuningSliderRow(
                label = "压暗浓度 scrimMaxAlpha",
                value = config.scrimMaxAlpha,
                range = 0.0f..0.85f,
                onValueChange = { v -> onUpdateConfig { it.copy(scrimMaxAlpha = v) } },
            )
            TuningSliderRow(
                label = "压暗曲线 scrimPower",
                value = config.scrimPower,
                range = 0.5f..3.0f,
                onValueChange = { v -> onUpdateConfig { it.copy(scrimPower = v) } },
            )
            TuningSliderRow(
                label = "模糊饱和度 blurSaturation",
                value = config.blurSaturation,
                range = 0.7f..1.6f,
                onValueChange = { v -> onUpdateConfig { it.copy(blurSaturation = v) } },
            )
        }
    }
}

@Composable
private fun DemoChip(
    label: String,
    active: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .background(
                if (active) Color.White.copy(alpha = 0.22f)
                else Color.White.copy(alpha = 0.07f),
            )
            .border(
                width = 0.5.dp,
                color = if (active) Color.White.copy(alpha = 0.45f)
                else Color.White.copy(alpha = 0.16f),
                shape = RoundedCornerShape(50),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    ) {
        Text(
            text = label,
            color = if (active) Color.White else Color.White.copy(alpha = 0.70f),
            fontSize = 11.5.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun TuningSliderRow(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    valueText: String? = null,
    onValueChange: (Float) -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = label,
                color = Color.White.copy(alpha = 0.85f),
                fontSize = 11.5.sp,
            )
            Text(
                text = valueText ?: String.format(Locale.US, "%.2f", value),
                color = Color(0xFF7CE8FF),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onValueChange,
            valueRange = range,
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White.copy(alpha = 0.85f),
                inactiveTrackColor = Color.White.copy(alpha = 0.22f),
            ),
            modifier = Modifier
                .fillMaxWidth()
                .height(28.dp),
        )
    }
}
