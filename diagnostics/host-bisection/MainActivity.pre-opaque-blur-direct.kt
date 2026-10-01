@file:OptIn(ExperimentalSharedTransitionApi::class)

package yos.music.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewModelScope
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.google.accompanist.insets.ProvideWindowInsets
import com.google.accompanist.insets.navigationBarsHeight
import androidx.navigation.compose.NavHost
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import uk.akane.libphonograph.hasScopedStorageWithMediaTypes
import yos.music.player.code.MediaController
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.player.FadeExo.fadePlay
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.models.ImageViewModel
import yos.music.player.data.models.MainViewModel
import yos.music.player.data.models.MediaViewModel
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.UI
import yos.music.player.ui.UI.Settings.Companion.ExoplayerSetting
import yos.music.player.ui.pages.Home
import yos.music.player.ui.pages.discovery.Discovery
import yos.music.player.ui.pages.search.SearchPage
import yos.music.player.ui.pages.NowPlaying
import yos.music.player.ui.pages.NowPlayingPage.Album
import yos.music.player.ui.pages.library.Library
import yos.music.player.ui.pages.library.NormalMusic
import yos.music.player.ui.pages.library.albums.AlbumInfo
import yos.music.player.ui.pages.library.albums.LocalAlbums
import yos.music.player.ui.pages.library.artists.LocalArtists
import yos.music.player.ui.pages.library.playlists.PlayLists
import yos.music.player.ui.pages.library.playlists.OnlinePlaylistDetail
import yos.music.player.ui.pages.library.playlists.OnlinePlaylists
import yos.music.player.ui.pages.settings.Settings
import yos.music.player.ui.pages.settings.account.KugouLogin
import yos.music.player.ui.pages.settings.audio.exoPlayer.ExoPlayerSettings
import yos.music.player.ui.pages.settings.audio.exoPlayer.MediaCodec
import yos.music.player.ui.pages.settings.extend.statusBarLyric.LyricGetter
import yos.music.player.ui.pages.settings.library.LibraryOverview
import yos.music.player.ui.pages.settings.others.About
import yos.music.player.ui.pages.settings.performance.LyricSetting
import yos.music.player.ui.pages.settings.performance.NotificationSetting
import yos.music.player.ui.pages.settings.performance.userinterface.ScreenCornerSetDialog
import yos.music.player.ui.pages.settings.performance.userinterface.UserInterfaceSetting
import yos.music.player.ui.theme.YosMusicTheme
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.theme.withNight
import yos.music.player.ui.widgets.basic.BottomNavigator
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.NavItem
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.YosWrapper
import java.io.File
import kotlin.math.abs

/*//MediaPlayer全局控制器
var mediaController = yos.music.player.code.MediaController*/

class MainActivity : BaseActivity() {

    private val mediaViewModel: MediaViewModel by viewModels()
    private val mainViewModel: MainViewModel by viewModels()

    private val imageViewModel: ImageViewModel by viewModels()

    @Suppress("DEPRECATION")
    @OptIn(
        ExperimentalAnimationApi::class,
        ExperimentalSharedTransitionApi::class
    )
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installSplashScreen()
        enableEdgeToEdge()
        setContent {
            YosMusicTheme {
                ProvideWindowInsets {
                    val context = LocalContext.current
                    val density = LocalDensity.current
                    val safeDrawingBottomPx = WindowInsets.safeDrawing.getBottom(density).toFloat()

                    val offsetY = remember("MainActivity_offsetY") { Animatable(0f) }
                    val parentHeight =
                        remember("MainActivity_parentHeight") { mutableIntStateOf(0) }
                    val screenCorner = remember("MainActivity_screenCorner") {
                        val corner = SettingsLibrary.ScreenCorner.toInt()
                        if (corner == 0) 1 else corner
                    }

                    val scope = rememberCoroutineScope()

                    /*val parentHeightDp = remember(parentHeight.intValue) {
                        with(density) {
                            parentHeight.intValue.toDp()
                        }
                    }*/

                    println("重组：底层载体")

                    /*Surface(
                        modifier = Modifier
                            .fillMaxSize(),
                        color = Color.Transparent,
                        contentColor = Color.Black withNight Color.White
                    ) {*/
                        println("重组：主载体")
                        val miniPlayerHeight = 62.dp
                        val height = remember("MainActivity_height") { mutableIntStateOf(0) }

                        val navHeight = remember("MainActivity_navHeight") {
                            with(density) {
                                height.intValue.toDp().plus(miniPlayerHeight)
                            }
                        }
                        // 逻辑初始化区域
                        val navController = rememberNavController()
                        val navSpec = spring(
                            stiffness = 400f,
                            dampingRatio = 1f,
                            visibilityThreshold = 0.001f
                        )
                        // val scaffoldState = rememberBottomSheetScaffoldState()
                        val route = rememberSaveable(key = "MainActivity_route") {
                            mutableStateOf(UI.HomePage)
                        }
                        // 记录当前路线

                        YosWrapper {
                            val backstackEntry =
                                navController.currentBackStackEntryAsState()
                            route.value =
                                backstackEntry.value?.destination?.route ?: UI.HomePage
                        }

                        // 显示控制区域
                        val yosBottomSheetConfig = object {
                            val progress
                                get() = if (parentHeight.intValue == 0) 0f else abs(offsetY.value / parentHeight.intValue).coerceIn(
                                    0f,
                                    1f
                                )
                            val menuAlpha
                                get() = 1f - progress
                            val mainContainerCardScale
                                get() = 0.9f + (0.1f * menuAlpha)
                            val thisShowCorner
                                get() = progress > 0f

                            val barShowCorner
                                get() = progress < 1f
                            val showMenu
                                get() = progress < 1f

                            val barShapeValue
                                get() = lerp(12, screenCorner, progress)

                            val RTCorner
                                get() = screenCorner == 0
                        }

                        val showNowPlaying = remember("MainActivity_showNowPlaying") {
                            derivedStateOf {
                                yosBottomSheetConfig.menuAlpha < 0.3f
                            }
                        }

                        val nowPageNowPlaying =
                            rememberSaveable(key = "MainActivity_nowPageNowPlaying") {
                                mutableStateOf(Album)
                            }

                        val navBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop()

                        YosWrapper {
                            val isNight = isFlamingoInDarkMode()
                            if (showNowPlaying.value) {
                                rememberSystemUiController().run {
                                    setNavigationBarColor(
                                        Color.Transparent,
                                        darkIcons = false,
                                        navigationBarContrastEnforced = false
                                    )
                                    setStatusBarColor(Color.Transparent, darkIcons = false)
                                    var activity = LocalContext.current as? Activity
                                    DisposableEffect(Unit) {
                                        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                        onDispose {
                                            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                            activity = null
                                        }
                                    }
                                }
                            } else {
                                rememberSystemUiController().run {
                                    setNavigationBarColor(
                                        color = Color.Transparent,
                                        darkIcons = !isNight,
                                        navigationBarContrastEnforced = false
                                    )

                                    setStatusBarColor(Color.Transparent, darkIcons = !isNight)
                                }
                            }
                        }

                        // 导航区域
                        val defaultHome = stringResource(id = R.string.page_home_title)
                        val defaultLibrary = stringResource(id = R.string.page_library_title)
                        val defaultSearch = stringResource(id = R.string.page_search_title)

                        val nowLabel = rememberSaveable(key = "MainActivity_nowLabel") {
                            mutableStateOf(defaultHome)
                        }

                        // 以下为实际显示

                        // 主界面
                        YosWrapper {
                            println("重组：主界面")

                            Surface(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .graphicsLayer {
                                        val thisMainContainerCardScale =
                                            yosBottomSheetConfig.mainContainerCardScale
                                        scaleX = thisMainContainerCardScale
                                        scaleY = thisMainContainerCardScale
                                    }
                                    .graphicsLayer {
                                        //compositingStrategy = CompositingStrategy.Offscreen
                                        //transformOrigin = TransformOrigin(0.5f, 1f)

                                        if (yosBottomSheetConfig.thisShowCorner && !yosBottomSheetConfig.RTCorner) {
                                            compositingStrategy = CompositingStrategy.Offscreen
                                            clip = true
                                            shape = YosRoundedCornerShape(screenCorner.dp)
                                        } else {
                                            compositingStrategy = CompositingStrategy.ModulateAlpha
                                            clip = false
                                        }

                                        if (!yosBottomSheetConfig.barShowCorner) {
                                            alpha = 0f
                                        }
                                    }

                                ,
                                color = MaterialTheme.colorScheme.background,
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ) {
                                // 主界面本体
                                YosWrapper {
                                    BackHandler(showNowPlaying.value) {
                                        // println("isVisible: ${showNowPlaying.value}, nowPageNowPlaying.value: ${nowPageNowPlaying.value}")
                                        if (nowPageNowPlaying.value != Album) {
                                            nowPageNowPlaying.value =
                                                Album
                                        } else {
                                            scope.launch {
                                                // scaffoldState.bottomSheetState.partialExpand()
                                                offsetY.animateTo(0f, animationSpec = navSpec)
                                            }
                                        }
                                    }
                                }

                                YosWrapper {
                                    NavHost(
                                            modifier = Modifier.layerBackdrop(navBackdrop),
                                            navController = navController,
                                            startDestination = UI.HomePage,
                                            enterTransition = {
                                                slideInHorizontally(
                                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                                    initialOffsetX = { it }
                                                )
                                            },
                                            exitTransition = {
                                                slideOutHorizontally(
                                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                                    targetOffsetX = { -it }
                                                )
                                            },
                                            popEnterTransition = {
                                                slideInHorizontally(
                                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                                    initialOffsetX = { -it }
                                                )
                                            },
                                            popExitTransition = {
                                                slideOutHorizontally(
                                                    animationSpec = tween(300, easing = FastOutSlowInEasing),
                                                    targetOffsetX = { it }
                                                )
                                            }) {

                                            composable(UI.HomePage) {
                                                Discovery(navController)
                                            }
                                            composable(UI.RankDetail) {
                                                yos.music.player.ui.pages.discovery.RankDetail(navController)
                                            }
                                            composable(UI.OnlineAlbumDetail) {
                                                yos.music.player.ui.pages.discovery.OnlineAlbumDetail(navController)
                                            }
                                            composable(UI.NewSongsDetail) {
                                                yos.music.player.ui.pages.discovery.NewSongsDetail(navController)
                                            }
                                            composable(UI.RecentPlayedDetail) {
                                                yos.music.player.ui.pages.discovery.RecentPlayedDetail(navController)
                                            }
                                            composable(UI.RankListDetail) {
                                                yos.music.player.ui.pages.discovery.RankListDetail(navController)
                                            }
                                            composable(UI.NewAlbumsDetail) {
                                                yos.music.player.ui.pages.discovery.NewAlbumsDetail(navController)
                                            }
                                            composable(UI.RecommendPlaylistsDetail) {
                                                yos.music.player.ui.pages.discovery.RecommendPlaylistsDetail(navController)
                                            }
                                            composable(UI.Library) {
                                                Library(
                                                    navController
                                                )
                                            }

                                            composable(UI.PlayLists) {
                                                PlayLists(navController)
                                            }
                                            composable(UI.NormalMusic) {
                                                NormalMusic(navController)
                                            }
                                            composable(UI.Search) {
                                                SearchPage(navController)
                                            }
                                            composable(UI.OnlinePlaylists) {
                                                OnlinePlaylists(navController)
                                            }
                                            composable(UI.OnlinePlaylistDetail) {
                                                OnlinePlaylistDetail(navController)
                                            }
                                            composable(UI.LocalAlbums) {
                                                LocalAlbums(
                                                    navController,
                                                    null,
                                                    this@composable
                                                )
                                            }
                                            composable(UI.LocalArtists) {
                                                LocalArtists(navController)
                                            }

                                            composable(UI.AlbumInfo) {
                                                AlbumInfo(
                                                    navController,
                                                    null,
                                                    this@composable
                                                )
                                            }

                                            composable(UI.Settings.Main) {
                                                Settings(
                                                    navController
                                                )
                                            }
                                            composable(UI.Settings.LibraryOverview) {
                                                LibraryOverview(navController)
                                            }
                                            composable(UI.Settings.LyricGetter) {
                                                LyricGetter(navController)
                                            }
                                            composable(ExoplayerSetting) {
                                                ExoPlayerSettings(navController)
                                            }
                                            composable(UI.Settings.About) {
                                                About(
                                                    navController
                                                )
                                            }
                                            composable(UI.Settings.MediaCodec) {
                                                MediaCodec(navController)
                                            }

                                            composable(UI.Settings.LyricSetting) {
                                                LyricSetting(navController)
                                            }
                                            composable(UI.Settings.UserInterfaceSetting) {
                                                UserInterfaceSetting(navController)
                                            }
                                            composable(UI.Settings.NotificationSetting) {
                                                NotificationSetting(navController)
                                            }
                                            // 酷狗账号正式登录页
                                            composable(UI.Settings.KugouLogin) {
                                                KugouLogin(navController)
                                            }
                                            // POC debug entry
                                            composable("poc_debug") {
                                                yos.music.player.poc.PocDebugScreen(
                                                    navController::popBackStack
                                                )
                                            }
                                        }


                                }

                                // 底部导航栏
                                YosWrapper {
                                    Box(
                                        Modifier
                                            .fillMaxSize(),
                                        contentAlignment = Alignment.BottomCenter
                                    ) {
                                        // 官方 LiquidBottomTabs 三层玻璃：
                                        // 采样源 navBackdrop 挂在 NavHost 上（真实绘制页面内容的节点），
                                        // 不再使用 haze 双分支遮罩与 128dp 渐变，避免录制空层导致拖动重影。
                                        val navItems = remember(
                                            defaultHome,
                                            defaultLibrary,
                                            defaultSearch
                                        ) {
                                            listOf(
                                                NavItem(
                                                    defaultHome,
                                                    R.drawable.ic_uitabbar_home
                                                ),
                                                NavItem(
                                                    defaultLibrary,
                                                    R.drawable.ic_uitabbar_library
                                                ),
                                                NavItem(
                                                    defaultSearch,
                                                    R.drawable.ic_uitabbar_search
                                                )
                                            )
                                        }
                                        val externalIndex: () -> Int = {
                                            when (route.value) {
                                                UI.HomePage -> 0
                                                UI.Library -> 1
                                                UI.Search -> 2
                                                else -> 0
                                            }
                                        }

                                        BottomNavigator(
                                            initialIndex = externalIndex(),
                                            externalIndex = externalIndex,
                                            onIndexChange = { index ->
                                                nowLabel.value = navItems[index].label
                                                val target = when (index) {
                                                    0 -> UI.HomePage
                                                    1 -> UI.Library
                                                    2 -> UI.Search
                                                    else -> null
                                                }
                                                if (target != null) {
                                                    navController.navigate(target) {
                                                        popUpTo(navController.graph.startDestinationId) {
                                                            saveState = true
                                                        }
                                                        launchSingleTop = true
                                                        restoreState = true
                                                    }
                                                }
                                            },
                                            items = navItems,
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .onSizeChanged {
                                                    height.intValue = it.height
                                                },
                                            backdrop = navBackdrop
                                        )
                                    }
                                }
                            }

                            // 弹窗
                            YosWrapper {
                                val showCornerSetDialog =
                                    remember("MainActivity_showCornerSetDialog") {
                                        mutableStateOf(!SettingsLibrary.ScreenCornerSet)
                                    }

                                if (showCornerSetDialog.value) {
                                    ScreenCornerSetDialog {
                                        showCornerSetDialog.value = false
                                    }
                                } else {
                                    CheckAndRequestPermission()
                                }
                            }
                        }

                        // 播放条&播放界面
                        YosWrapper {
                            if (height.intValue == 0) return@YosWrapper

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged {
                                        parentHeight.intValue = it.height
                                    }
                                    .graphicsLayer {
                                        //compositingStrategy = CompositingStrategy.Offscreen
                                        val plus =
                                            0.07f * yosBottomSheetConfig.progress
                                        this.scaleX = 0.93f + plus
                                        this.scaleY = 0.93f + plus
                                        this.translationY =
                                            -(height.intValue + 10) * (yosBottomSheetConfig.menuAlpha) - safeDrawingBottomPx
                                        this.transformOrigin = TransformOrigin(0.5f, 1f)
                                    },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                YosWrapper {
                                    val miniPlayerHeightPx = remember("MainActivity_miniPlayerHeightPx") {
                                        with(density) {
                                            miniPlayerHeight.toPx()
                                        }
                                    }

                                    val miniPlayerShadow = remember("MainActivity_miniPlayerShadow") {
                                        with(density) {
                                            7.5.dp.toPx()
                                        }
                                    }

                                    val color = Color.White withNight Color(0xFF1C1C1E)

                                    println("重组：播放条&播放界面 外层")

                                    val dragState = rememberDraggableState { delta ->
                                        scope.launch {
                                            offsetY.snapTo(offsetY.value + delta)
                                        }
                                    }

                                    /*.graphicsLayer {
                                        if (yosBottomSheetConfig.barShowCorner) {
                                            shadowElevation = 7.5.dp.toPx()
                                            spotShadowColor = Color.Black.copy(alpha = 0.2f)

                                            *//*compositingStrategy =
                                                        CompositingStrategy.Offscreen*//*
                                                }
                                            }*/
                                    /*.drawWithContent {
                                        drawContent()
                                        drawOutline(
                                            outline = navShape.createOutline(size, layoutDirection, this),
                                            color = color.copy(alpha = yosBottomSheetConfig.menuAlpha),
                                            style = Stroke(width = 0.3.dp.toPx())
                                        )
                                    }*/

                                    Surface(
                                        modifier = Modifier.graphicsLayer {
                                            translationY =
                                                ((parentHeight.intValue - miniPlayerHeightPx) * (1 - yosBottomSheetConfig.progress))

                                            if (yosBottomSheetConfig.barShowCorner) {
                                                shape = YosRoundedCornerShape(
                                                    roundSize = yosBottomSheetConfig.barShapeValue.dp,
                                                    mSize = Size(
                                                        width = size.width,
                                                        height = (miniPlayerHeightPx + (parentHeight.intValue - miniPlayerHeightPx) * yosBottomSheetConfig.progress)
                                                    )
                                                )
                                                shadowElevation = miniPlayerShadow
                                                spotShadowColor = Color.Black.copy(alpha = 0.2f)
                                            }
                                        }
                                            .graphicsLayer {
                                                //translationY = ((parentHeight.intValue - miniPlayerHeightPx) * (1 - yosBottomSheetConfig.progress))

                                                if (yosBottomSheetConfig.barShowCorner) {
                                                    compositingStrategy = CompositingStrategy.Offscreen
                                                    clip = true
                                                    shape = YosRoundedCornerShape(
                                                        roundSize = yosBottomSheetConfig.barShapeValue.dp,
                                                        mSize = Size(
                                                            width = size.width,
                                                            height = (miniPlayerHeightPx + (parentHeight.intValue - miniPlayerHeightPx) * yosBottomSheetConfig.progress)
                                                        )
                                                    )
                                                } else {
                                                    compositingStrategy = CompositingStrategy.ModulateAlpha
                                                    clip = false
                                                }
                                            }
                                            .draggable(
                                                reverseDirection = true,
                                                orientation = Orientation.Vertical,
                                                state = dragState,
                                                onDragStopped = { velocity ->
                                                    offsetY.updateBounds(
                                                        0f,
                                                        parentHeight.intValue.toFloat()
                                                    )
                                                    scope.launch {
                                                        //println("速度：$velocity 高度：${miniPlayerHeight + (parentHeightDp - miniPlayerHeight) * progress}")
                                                        if (velocity < 0f) {
                                                            offsetY.animateTo(
                                                                0f,
                                                                initialVelocity = velocity
                                                            )
                                                        } else {
                                                            offsetY.animateTo(
                                                                parentHeight.intValue.toFloat(),
                                                                initialVelocity = velocity
                                                            )
                                                        }
                                                    }
                                                }
                                            )
                                            .layout { measurable, constraints ->
                                                val placeable = measurable.measure(
                                                    constraints.copy(
                                                        minHeight = parentHeight.intValue,
                                                        maxHeight = parentHeight.intValue
                                                    )
                                                )
                                                layout(
                                                    placeable.width,
                                                    placeable.height
                                                ) {
                                                    placeable.placeRelative(0, 0)
                                                }
                                            },
                                        color = Color.Transparent
                                    ) {
                                        println("重组：播放条&播放界面")

                                        val isPlaying =
                                            rememberSaveable(key = "MainActivity_isPlaying") {
                                                MediaViewModelObject.isPlaying
                                            }

                                        // NowPlaying
                                        YosWrapper {
                                            NowPlaying(
                                                mainViewModel = mainViewModel,
                                                mediaViewModel = mediaViewModel,
                                                navController = navController,
                                                isPlayingStatusLambda = { isPlaying.value },
                                                isPlayingOnChanged = {
                                                    isPlaying.value = it
                                                },
                                                nowPageLambda = { nowPageNowPlaying.value },
                                                showMiniPlayer = { yosBottomSheetConfig.showMenu }
                                            ) {
                                                nowPageNowPlaying.value = it
                                            }
                                        }

                                        // 迷你播放状态
                                        YosWrapper {
                                            //println("变换：菜单透明度 $menuAlpha")
                                            if (yosBottomSheetConfig.showMenu) {
                                                YosWrapper {
                                                    Column(
                                                        Modifier
                                                            .fillMaxSize()
                                                            .graphicsLayer {
                                                                compositingStrategy =
                                                                    CompositingStrategy.ModulateAlpha
                                                                this.alpha =
                                                                    yosBottomSheetConfig.menuAlpha
                                                            }
                                                            .background(color)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(miniPlayerHeight)
                                                                .clickable(
                                                                    interactionSource = remember { MutableInteractionSource() },
                                                                    indication = null,
                                                                    onClick = {
                                                                        scope.launch {
                                                                            offsetY.animateTo(
                                                                                parentHeight.intValue.toFloat(),
                                                                                animationSpec = navSpec
                                                                            )
                                                                        }
                                                                    })
                                                        ) {

                                                            YosWrapper {
                                                                val showMiniBarHaze = remember(
                                                                    "MainActivity_showMiniBarHaze",
                                                                    yosBottomSheetConfig.menuAlpha
                                                                ) {
                                                                    derivedStateOf {
                                                                        yosBottomSheetConfig.menuAlpha == 1f && SettingsLibrary.BarBlurEffect
                                                                    }
                                                                }
                                                                this@Column.AnimatedVisibility(
                                                                    visible = showMiniBarHaze.value,
                                                                    modifier = Modifier
                                                                        .fillMaxWidth()
                                                                        .height(
                                                                            navHeight
                                                                        ),
                                                                    enter = fadeIn(tween(100)),
                                                                    exit = fadeOut(tween(100))
                                                                ) {
                                                                    Spacer(
                                                                        modifier = Modifier
                                                                            .fillMaxSize()
                                                                            .drawBackdrop(
                                                                                backdrop = navBackdrop,
                                                                                shape = { androidx.compose.ui.graphics.RectangleShape },
                                                                                effects = {
                                                                                    vibrancy()
                                                                                    blur(48.dp.toPx())
                                                                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                                                                        lens(16.dp.toPx(), 24.dp.toPx())
                                                                                    }
                                                                                },
                                                                                onDrawSurface = {
                                                                                    drawRect(color.copy(alpha = 1f))
                                                                                }
                                                                            )
                                                                    )
                                                                }
                                                            }

                                                            Row(
                                                                Modifier
                                                                    .height(miniPlayerHeight)
                                                                    .fillMaxWidth()
                                                                    .padding(
                                                                        horizontal = 8.dp
                                                                    )
                                                            ) {
                                                                Row(
                                                                    Modifier
                                                                        .height(
                                                                            miniPlayerHeight
                                                                        )
                                                                        .weight(1f),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    YosWrapper {
                                                                        ShadowImageWithCache(
                                                                            dataLambda = { MediaViewModelObject.bitmap.value },
                                                                            contentDescription = null,
                                                                            modifier = Modifier
                                                                                .size(47.dp),
                                                                            cornerRadius = 6.dp,
                                                                            shadowAlpha = 0f,
                                                                            imageQuality = ImageQuality.LOW
                                                                        )
                                                                    }
                                                                    Column(
                                                                        Modifier
                                                                            .padding(
                                                                                start = 10.dp,
                                                                                end = 5.dp
                                                                            )
                                                                    ) {
                                                                        Text(
                                                                            text = MediaController.musicPlaying.value?.title
                                                                                ?: defaultTitle,
                                                                            fontWeight = FontWeight.Medium,
                                                                            fontSize = 16.sp,
                                                                            lineHeight = 16.sp,
                                                                            maxLines = 1,
                                                                            overflow = TextOverflow.Ellipsis,
                                                                            color = Color.Black withNight Color.White
                                                                        )
                                                                        /*Text(
                                                                text = musicPlaying.value?.Artist
                                                                    ?: "未知艺术家",
                                                                fontSize = 13.5.sp,
                                                                lineHeight = 13.5.sp,
                                                                modifier = Modifier.alpha(
                                                                    0.6f
                                                                ),
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                color = Color.Black withNight Color.White
                                                            )*/
                                                                    }
                                                                }
                                                                Row(
                                                                    Modifier
                                                                        .fillMaxHeight()
                                                                        .padding(end = 10.dp),
                                                                    verticalAlignment = Alignment.CenterVertically
                                                                ) {
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(34.dp)
                                                                            .clickable(
                                                                                interactionSource = remember { MutableInteractionSource() },
                                                                                indication = ripple(
                                                                                    bounded = false
                                                                                ),
                                                                                onClick = {
                                                                                    Vibrator.click(
                                                                                        context
                                                                                    )
                                                                                    isPlaying.value =
                                                                                        !isPlaying.value
                                                                                    if (isPlaying.value) {
                                                                                        MediaController.mediaControl?.fadePlay()
                                                                                    } else {
                                                                                        MediaController.mediaControl?.fadePause()
                                                                                    }
                                                                                }),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        AnimatedContent(
                                                                            targetState = isPlaying.value,
                                                                            transitionSpec = {
                                                                                (scaleIn(
                                                                                    initialScale = 0.3f
                                                                                ) + fadeIn()).togetherWith(
                                                                                    scaleOut(
                                                                                        targetScale = 0.3f
                                                                                    ) + fadeOut()
                                                                                )
                                                                            }) {
                                                                            if (it) {
                                                                                Icon(
                                                                                    painterResource(
                                                                                        id = R.drawable.ic_nowplaying_mp_pause
                                                                                    ),
                                                                                    contentDescription = "Pause",
                                                                                    modifier = Modifier
                                                                                        .fillMaxSize(),
                                                                                    tint = Color.Black withNight Color.White
                                                                                )
                                                                            } else {
                                                                                Icon(
                                                                                    painterResource(
                                                                                        id = R.drawable.ic_nowplaying_mp_play
                                                                                    ),
                                                                                    contentDescription = "Play",
                                                                                    modifier = Modifier
                                                                                        .fillMaxSize(),
                                                                                    tint = Color.Black withNight Color.White
                                                                                )
                                                                            }
                                                                        }
                                                                    }
                                                                    Spacer(
                                                                        modifier = Modifier.width(
                                                                            18.dp
                                                                        )
                                                                    )
                                                                    Box(
                                                                        modifier = Modifier
                                                                            .size(36.dp)
                                                                            .clickable(
                                                                                interactionSource = remember { MutableInteractionSource() },
                                                                                indication = ripple(
                                                                                    bounded = false
                                                                                ),
                                                                                onClick = {
                                                                                    Vibrator.click(
                                                                                        context
                                                                                    )
                                                                                    MediaController.mediaControl?.seekToNextMediaItem()
                                                                                }),
                                                                        contentAlignment = Alignment.Center
                                                                    ) {
                                                                        Icon(
                                                                            painterResource(
                                                                                id = R.drawable.ic_nowplaying_mp_fforward
                                                                            ),
                                                                            contentDescription = "Next",
                                                                            modifier = Modifier
                                                                                .fillMaxSize(),
                                                                            tint = Color.Black withNight Color.White
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
                                }
                            }
                        }
                    /*}*/
                }
            }
        }
    }

        @Composable
    fun CheckAndRequestPermission() {
        val context = LocalContext.current
        val requestPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val isGranted = permissions.entries.all { it.value }
            if (isGranted) {
                // Load music list here
                loadMusic(context, enforce = true)
                sendBroadcast(Intent("yos.music.player.BLUETOOTH_STATUS_REFRESH"))
            } else {
                // Set music list to empty if permission is denied
                // mainMusicList.value = mutableListOf()
            }
        }

        YosWrapper {
            LaunchedEffect(Unit) {

                var permissions = emptyArray<String>()

                if ((hasScopedStorageWithMediaTypes()
                            && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_MEDIA_AUDIO
                    ) != PackageManager.PERMISSION_GRANTED)
                    /*|| (!hasScopedStorageV2()
                            && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED)*/
                    || (!hasScopedStorageWithMediaTypes()
                            && ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.READ_EXTERNAL_STORAGE
                    ) != PackageManager.PERMISSION_GRANTED)
                ) {
                    permissions += arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)

                    if (hasScopedStorageWithMediaTypes()) {
                        permissions += arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
                    }
                }

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        permissions += Manifest.permission.BLUETOOTH_CONNECT
                }

                if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    permissions += Manifest.permission.POST_NOTIFICATIONS
                }

                if (permissions.isNotEmpty()) {
                    requestPermissionLauncher.launch(permissions)
                }
                else {
                    loadMusic(context)
                }
            }
        }
    }


    /*LaunchedEffect(permissionState.value) {
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_MEDIA_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
            } else {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.READ_EXTERNAL_STORAGE
                ) == PackageManager.PERMISSION_GRANTED
            }

            if (hasPermission) {
                permissionState.value = PermissionState.DENIED
                loadMusic(context)
            }
        }

        val showDialog = remember("CheckPermission_showDialog") {
            derivedStateOf { permissionState.value != PermissionState.DENIED  }
        }

        if (showDialog.value) {
            val dialogProperties = ModalBottomSheetProperties(
                securePolicy = SecureFlagPolicy.Inherit,
                isFocusable = true,
                shouldDismissOnBackPress = false
            )

            val bottomSheetState = rememberModalBottomSheetState()
            val scope = rememberCoroutineScope()

            YosWrapper {
                OptionDialog(
                    title = stringResource(id = R.string.permission_grant_title),
                    subTitle = stringResource(id = R.string.permission_grant_subtitle),
                    content = {
                        Text(text = stringResource(id = R.string.permission_grant_desc))
                    },
                    positiveContent = stringResource(id = R.string.permission_grant_button_positive),
                    properties = dialogProperties,
                    bottomSheetState = bottomSheetState,
                    onPositive = {
                        scope.launch { bottomSheetState.hide() }.invokeOnCompletion {
                            if (!bottomSheetState.isVisible) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    requestPermissionLauncher.launch(Manifest.permission.READ_MEDIA_AUDIO)
                                } else {
                                    requestPermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                                }
                                permissionState.value = PermissionState.DENIED
                            }
                        }
                    },
                    negativeContent = stringResource(id = R.string.permission_grant_button_negative),
                    onNegative = {
                        scope.launch { bottomSheetState.hide() }.invokeOnCompletion {
                            if (!bottomSheetState.isVisible) {
                                mainMusicList.value = mutableListOf()
                                permissionState.value = PermissionState.DENIED
                            }
                        }
                    }
                ) {
                    mainMusicList.value = mutableListOf()
                    permissionState.value = PermissionState.DENIED
                }
            }
        }*/

    /*enum class PermissionState {
        UNKNOWN,
        GRANTED,
        DENIED
    }*/

    private fun loadMusic(context: Context, enforce: Boolean = false) {
        val needRefresh = SettingsLibrary.RefreshEveryTime
        if (needRefresh || enforce) {
            mediaViewModel.viewModelScope.launch(Dispatchers.IO) {
                // Application中已还原，这里算是后台扫描
                // mainMusicList.value = MusicScanner(context).getMusicList()
                MusicLibrary.scanMedia(context)
                println("刷新媒体库")
            }
        }
    }

}

fun readFile(path: String): String? {
    return try {
        File(path).readText()
    } catch (e: Exception) {
        //e.printStackTrace()
        null
    }
}