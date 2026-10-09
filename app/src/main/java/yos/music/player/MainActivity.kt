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
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.ripple
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import yos.music.player.ui.widgets.basic.LocalGlassContentColor
import yos.music.player.ui.widgets.basic.LocalTitleOverlayHost
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.TitleOverlayHost
import yos.music.player.ui.widgets.basic.adaptiveBlurPx
import yos.music.player.ui.widgets.basic.adaptiveGlassColorControls
import yos.music.player.ui.widgets.basic.rememberAdaptiveBackdropLuminance
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import coil.compose.AsyncImage
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.scale
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import yos.music.player.ui.widgets.basic.ProvideRawWindowInsets
import yos.music.player.ui.widgets.basic.navigationBarsPadding
import yos.music.player.ui.widgets.basic.rawNavigationBarsBottomPx
import androidx.navigation.compose.NavHost
import com.google.accompanist.systemuicontroller.rememberSystemUiController
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.emptyBackdrop
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.RoundedRectangle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import yos.music.player.ui.widgets.liquid.WrapHighlightStyle
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import uk.akane.libphonograph.hasScopedStorageWithMediaTypes
import androidx.media3.common.Player.REPEAT_MODE_OFF
import androidx.media3.common.Player.REPEAT_MODE_ALL
import androidx.media3.common.Player.REPEAT_MODE_ONE
import yos.music.player.code.MediaController
import yos.music.player.code.MediaController.mediaControl
import yos.music.player.code.YosPlaybackService
import yos.music.player.code.utils.others.GlassDiagnostics
import yos.music.player.code.utils.others.GlassProbe
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.code.utils.player.FadeExo.fadePause
import yos.music.player.code.utils.player.FadeExo.fadePlay
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.libraries.toMultipleArtists
import yos.music.player.data.models.ImageViewModel
import yos.music.player.data.models.MainViewModel
import yos.music.player.data.models.MediaViewModel
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.UI
import yos.music.player.ui.navigation.AppNavigator
import yos.music.player.ui.navigation.ArtistPageAppearance
import yos.music.player.ui.navigation.NavGuard
import yos.music.player.ui.navigation.accentTintOf
import yos.music.player.ui.navigation.AppTabsShell
import yos.music.player.ui.navigation.HouseId
import yos.music.player.ui.navigation.houseForTabIndex
import yos.music.player.ui.navigation.SharedNavHost
import yos.music.player.ui.UI.Settings.Companion.ExoplayerSetting
import yos.music.player.ui.pages.Home
import yos.music.player.ui.pages.discovery.Discovery
import yos.music.player.ui.pages.search.SearchPage
import yos.music.player.ui.pages.NowPlaying
import yos.music.player.ui.pages.NowPlayingPage.Album
import yos.music.player.ui.pages.RepeatToggleButton
import yos.music.player.ui.pages.ShuffleToggleButton
import yos.music.player.ui.pages.library.Library
import yos.music.player.ui.pages.library.NormalMusic
import yos.music.player.ui.pages.library.albums.AlbumInfo
import yos.music.player.ui.pages.library.albums.LocalAlbums
import yos.music.player.ui.pages.library.artists.LocalArtists
import yos.music.player.ui.pages.library.playlists.PlayLists
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
import yos.music.player.ui.widgets.basic.coverCacheKey
import yos.music.player.ui.widgets.effects.ShadowType
import yos.music.player.ui.widgets.effects.YosFloatingLight
import yos.music.player.ui.widgets.basic.YosWrapper
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt

/*//MediaPlayer全局控制器
var mediaController = yos.music.player.code.MediaController*/

class MainActivity : BaseActivity() {
    private val updateViewModel: yos.music.player.update.UpdateViewModel by viewModels()
    private var updateStartupReady by mutableStateOf(false)
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
                ProvideRawWindowInsets {
                    yos.music.player.update.UpdateHost(updateViewModel, updateStartupReady)
                    val context = LocalContext.current
                    val density = LocalDensity.current
                    // 播放壳玻璃的消融探针。默认值就是"修好后该有的样子"，开关只做消融，
                    // 所以同一个包能自己 A/B（settings put global flamingo_probe keepdecor）。
                    // 只读一次：Settings.Global 是跨进程查询，放进这个每帧重组的作用域里会让
                    // 探针自己变成掉帧原因。
                    val glassProbe = remember("MainActivity_glassProbe") {
                        // install 而不是 read：底栏与封面图组件在另一个文件里，需要一个进程级入口。
                        GlassProbe.install(context).also { probe ->
                            GlassDiagnostics.event(
                                "probe_install",
                                "flags=" + probe.toString()
                            )
                        }
                    }
                    GlassDiagnostics.state(
                        "settings_bar_blur",
                        "value=${SettingsLibrary.BarBlurEffect}"
                    )
                    // offsetY 是播放器唯一的连续状态：拖拽、点击和系统返回都只驱动它
                    val offsetY = remember("MainActivity_offsetY") { Animatable(0f) }
                    val parentHeight =
                        remember("MainActivity_parentHeight") { mutableIntStateOf(0) }
                    val parentWidth =
                        remember("MainActivity_parentWidth") { mutableIntStateOf(0) }
                    val navBarBottomInsetPx =
                        rawNavigationBarsBottomPx()
                    val effectiveScreenCorner = yos.music.player.ui.theme.rememberScreenCornerRadius()
                    val screenCorner = rememberUpdatedState(effectiveScreenCorner)
                    val screenCornerPx = rememberUpdatedState(with(density) { effectiveScreenCorner.toPx() })
                    val bottomBarWidthPx = remember("MainActivity_bottomBarWidthPx") { mutableIntStateOf(0) }
                    val height = remember("MainActivity_height") { mutableIntStateOf(0) }
                    val lastPlayerAnchor = remember("MainActivity_lastPlayerAnchor") {
                        mutableStateOf(0f)
                    }
                    // progress 的分母：offsetY 实际被写入过的上界（即 offsetY.updateBounds
                    // 时使用的那个锚点）。与 lastPlayerAnchor 同步维护。
                    // 动画与拖拽都用它作边界，因此 offsetY 永远不会超过它，
                    // 用它作分母时展开完成 p 精确等于 1。
                    val expandedAnchorPxSnapshot = remember("MainActivity_progressDenom") {
                        mutableFloatStateOf(0f)
                    }
                    val dragActive = remember("MainActivity_dragActive") {
                        mutableStateOf(false)
                    }
                    val dragOffsetY = remember("MainActivity_dragOffsetY") {
                        mutableFloatStateOf(0f)
                    }
                    // 本次拖拽手势中壳是否发生过**真实位移**。dragActive 只代表手势
                    // 被接管：按住不动、或朝已顶死的方向拉（如收起态向下拉）时它也是
                    // true，但壳纹丝不动——材质过渡层若以它为判据，会在壳没有任何
                    // 位移时闪现动画期半透明材质。置位见 playerDragState（coerce 前后
                    // 有差值才算动过），清除见 onDragStopped。
                    val shellDragDisplaced = remember("MainActivity_shellDragDisplaced") {
                        mutableStateOf(false)
                    }
                    // 音量条/进度条等控件按下即占用本次手势：shell 的竖直收起拖拽必须让位。
                    // 否则手指先上下移动（触发收起判定）再左右移动时，
                    // 调音量与关闭播放器会同时生效。
                    val controlGestureActive = remember("MainActivity_controlGestureActive") {
                        mutableStateOf(false)
                    }
                    // 迷你条高度 = 收起态壳高度，是壳几何的源头（collapsedTopPx / 胶囊圆角 /
                    // shellHeight 下限 / 迷你内容宿主都读它），所以按屏宽分档而不是在分支里局部改：
                    // 宽屏（平板、手机横屏 split）保持 62dp，窄屏（手机竖屏）按参考图实测定为 43dp。
                    // 取 screenWidthDp 而不是父容器实测宽：后者首帧为 0，会让平板闪一帧 43dp 的条；
                    // Configuration 在首次组合就是确定值。同一个判据也用来选迷你内容分支（见 isWideMiniBar），
                    // 保证“壳高度”与“内容分支”永不错位。
                    val isWideMiniBar = LocalConfiguration.current.screenWidthDp.dp >= 600.dp
                    val miniPlayerHeight = if (isWideMiniBar) 62.dp else 43.dp
                    val miniPlayerHeightPx = with(density) { miniPlayerHeight.toPx() }
                    // 封面 morph 的几何来源：迷你封面节点与全屏 Album 封面节点的
                    // 窗口矩形，以及 morph 层自身的窗口原点。存的是不可变值而非
                    // LayoutCoordinates——后者每次回调都是同一实例，写进 MutableState
                    // 会被判定"没变"而不失效，端点被冻结在首帧（详见 CoverMorphGeometry）。
                    val coverGeometry = remember("MainActivity_coverGeometry") {
                        CoverMorphGeometry()
                    }
                    // 触发条件：仅平板横屏。冷启时宽高为 0 → false，不闪 split。
                    val isSplitMode =
                        parentWidth.intValue > 0 &&
                                parentHeight.intValue > 0 &&
                                parentWidth.intValue > parentHeight.intValue

                        val scope = rememberCoroutineScope()
                        val playerMotionJob = remember("MainActivity_playerMotionJob") {
                            mutableStateOf<Job?>(null)
                        }
                        // 动画已到端点但真实 Album 还在提交最后布局帧时，
                        // 暂时锁住 morph 的进度，避免 0.999… 的尾差参与最后一帧。
                        val morphProgressOverride = remember("MainActivity_morphProgressOverride") {
                            mutableStateOf<Float?>(null)
                        }
                        val morphHandoffRect = remember("MainActivity_morphHandoffRect") {
                            mutableStateOf<Rect?>(null)
                        }

                    /*val parentHeightDp = remember(parentHeight.intValue) {
                        with(density) {
                            parentHeight.intValue.toDp()
                        }
                    }*/


                    /*Surface(
                        modifier = Modifier
                            .fillMaxSize(),
                        color = Color.Transparent,
                        contentColor = Color.Black withNight Color.White
                    ) {*/

                        val navHeight = remember("MainActivity_navHeight") {
                            with(density) {
                                height.intValue.toDp().plus(miniPlayerHeight)
                            }
                        }
                        // 五个导航控制器：根共享层 + 四个独立房子。
                        val rootNavController = rememberNavController()
                        val homeNavController = rememberNavController()
                        val libraryNavController = rememberNavController()
                        val searchNavController = rememberNavController()
                        val favoritesNavController = rememberNavController()
                        val selectedHouse = rememberSaveable(key = "MainActivity_selectedHouse") {
                            mutableStateOf(HouseId.Home)
                        }
                        val activeHouseController = when (selectedHouse.value) {
                            HouseId.Home -> homeNavController
                            HouseId.Library -> libraryNavController
                            HouseId.Search -> searchNavController
                            HouseId.Favorites -> favoritesNavController
                        }
                        // Key the observation to its controller so a tab switch cannot briefly
                        // reuse the previous retained house's entry before the new flow emits.
                        val activeEntry = key(activeHouseController) {
                            activeHouseController.currentBackStackEntryAsState()
                        }
                        val rootEntry = rootNavController.currentBackStackEntryAsState()
                        GlassDiagnostics.state(
                            "navigation",
                            "house=${selectedHouse.value} active=${activeEntry.value?.destination?.route} root=${rootEntry.value?.destination?.route}"
                        )
                        val navigationKey = "${selectedHouse.value}:${activeEntry.value?.id}:${activeEntry.value?.destination?.route}"
                        val navigationSettling = remember("MainActivity_navigationSettling") {
                            mutableStateOf(false)
                        }
                        val previousNavigationKey = remember("MainActivity_previousNavigationKey") {
                            mutableStateOf<String?>(null)
                        }
                        // 酷狗在线状态同步：回到前台强制刷新收藏/关注，并在前台期间定时差分轮询；
                        // 退到后台即停（不常驻耗电）。
                        val onlineSyncLifecycleOwner = LocalLifecycleOwner.current
                        LaunchedEffect(Unit) {
                            onlineSyncLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                                KugouSyncCoordinator.refreshAll()
                                KugouSyncCoordinator.startPolling()
                                try {
                                    awaitCancellation()
                                } finally {
                                    KugouSyncCoordinator.stopPolling()
                                }
                            }
                        }
                        LaunchedEffect(navigationKey) {
                            if (previousNavigationKey.value == null) {
                                previousNavigationKey.value = navigationKey
                                navigationSettling.value = false
                            } else if (previousNavigationKey.value != navigationKey) {
                                previousNavigationKey.value = navigationKey
                                navigationSettling.value = true
                                delay(450L)
                                navigationSettling.value = false
                            }
                        }
                        GlassDiagnostics.state(
                            "page_transitioning",
                            "artist=${ArtistPageAppearance.hasTransitioningRegistration()} nav=${navigationSettling.value}"
                        )
                        androidx.compose.runtime.SideEffect {
                            ArtistPageAppearance.activeEntryId.value = if (rootEntry.value?.destination?.route == "shared_empty") {
                                activeEntry.value?.id
                            } else null
                        }
                        val defaultScrimColor = rememberUpdatedState(Color.White withNight Color.Black)
                        // tint 通道与遮罩同源同进度（见 ArtistPageAppearance.blendedTintColor）：
                        // 注册色即各页已提取的调色板 State，绘制期整形为玻璃着色。
                        // isDark 经 rememberUpdatedState 进闭包，绘制期读取不触发重组。
                        val isNightForTint = rememberUpdatedState(isFlamingoInDarkMode())
                        val tintColorProvider: () -> Color = remember(activeEntry) {
                            {
                                ArtistPageAppearance.blendedTintColor(
                                    default = Color.Transparent,
                                    activeId = activeEntry.value?.id
                                ) { source -> accentTintOf(source, isNightForTint.value) }
                            }
                        }
                        val scrimColorProvider: () -> Color = remember(
                            activeEntry, rootEntry, defaultScrimColor
                        ) {
                            {
                                // SharedNavHost's empty destination exposes the selected house.
                                // Any shared overlay must stop sampling the artist beneath it.
                                if (rootEntry.value?.destination?.route == "shared_empty") {
                                    // 不再只取当前 entry 的注册色：把每个生效的艺人页注册按其
                                    // 进出场过渡进度混入默认色——进度与页面淡入淡出同帧同曲线，
                                    // 遮罩换色因此与页面进退场共享同一时机（详见 ArtistPageAppearance）。
                                    ArtistPageAppearance.blendedScrimColor(
                                        default = defaultScrimColor.value,
                                        activeId = activeEntry.value?.id
                                    )
                                } else {
                                    defaultScrimColor.value
                                }
                            }
                        }
                        val appNavigator = remember {
                            AppNavigator(
                                switchHouseAction = { house ->
                                    rootNavController.popBackStack("shared_empty", false)
                                    selectedHouse.value = house
                                    when (house) {
                                        HouseId.Home -> KugouSyncCoordinator.notifyDiscoveryChanged()
                                        HouseId.Favorites -> KugouSyncCoordinator.notifyFavoritesChanged()
                                        HouseId.Library -> {
                                            KugouSyncCoordinator.notifyPlaylistsChanged()
                                            KugouSyncCoordinator.notifyArtistsChanged()
                                        }
                                        HouseId.Search -> Unit
                                    }
                                },
                                openSettingsAction = { house ->
                                    val target = when (house) {
                                        HouseId.Home -> homeNavController
                                        HouseId.Library -> libraryNavController
                                        HouseId.Search -> searchNavController
                                        HouseId.Favorites -> favoritesNavController
                                    }
                                    target.navigate(UI.Settings.Main)
                                },
                                openLibrarySongsAction = {
                                    rootNavController.popBackStack("shared_empty", false)
                                    selectedHouse.value = HouseId.Library
                                    libraryNavController.navigate(UI.NormalMusic)
                                }
                            )
                        }
                        // 展开/收起主曲线。stiffness/dampingRatio 与 Flamingo 最新版一致
                        // （反编译确认 SpringSpec(1.0, 400)）；差别在收尾阈值——
                        // 原先 0.001f：按临界阻尼弹簧(ω=√400=20) 估算，行程 2300px 要约 880ms
                        // 才判定停止；改用 1f 后约 520ms，消掉末尾约 360ms 的"龟速爬行"
                        // （那段每帧只动零点几像素，观感是"快到了却迟迟不落定"）。
                        // 实测展开动画出帧持续 915.8ms → 541.0ms。1px 内停下肉眼不可辨。
                        val navSpec = spring(
                            stiffness = 400f,
                            dampingRatio = 1f,
                            visibilityThreshold = 1f
                        )

                        // 显示控制区域
                        val yosBottomSheetConfig = object {
                            // 收起态壳底边（手机/竖屏）= parentHeight - bottomBar - inset - 5dp（与底栏顶 5dp 间隙对齐）
                            // 收起态壳底边（split 模式）= parentHeight - navBarBottomInset（与底栏底边对齐，迷你条与底栏共用底边）
                            // 收起态壳顶边 = collapsedBottomPx - 迷你条高度（宽屏 62dp / 窄屏 43dp）
                            // split 模式：迷你条在左 70%，底栏在右 30%；水平 5dp 间距在 shellRightInsetPx；
                            // 竖向不再扣除底栏自身高度。
                            val collapsedBottomPx: Float
                                get() = with(density) {
                                    if (parentHeight.intValue == 0) return@with 0f
                                    if (isSplitMode) {
                                        // 左右并排：shell 与底栏共用底边。
                                        // navigationBarsPadding() 使底栏内容底边位于 parentHeight - bottom inset。
                                        return@with parentHeight.intValue.toFloat() - navBarBottomInsetPx
                                    }
                                    parentHeight.intValue.toFloat() -
                                            (height.intValue + navBarBottomInsetPx + 5.dp.toPx())
                                }
                            val collapsedTopPx: Float
                                get() = with(density) {
                                    if (parentHeight.intValue == 0) return@with 0f
                                    (collapsedBottomPx - miniPlayerHeightPx).coerceAtLeast(0f)
                                }
                            // 展开态：底边从 collapsedBottomPx 插值到 parentHeight，
                            // 顶边同步从 collapsedTopPx 移动到屏幕顶端。
                            // 这就是"当前几何下"的理论锚点，会随底栏高度/手势栏 inset
                            // 变化；它只用于绘制插值，不用于 progress（见 progress 注释）。
                            val expandedAnchorPx: Float
                                get() = collapsedTopPx
                            val progress: Float
                                get() {
                                    val currentOffset = if (dragActive.value) {
                                        dragOffsetY.floatValue
                                    } else {
                                        offsetY.value
                                    }
                                    // 分母用"offsetY 实际能达到的最大值"，即展开完成时
                                    // 记录下的锚点，而不是实时的 expandedAnchorPx。
                                    //
                                    // 原因（真机实测的根因）：底栏高度在展开完成后才稳定，
                                    // expandedAnchorPx 会从 2281 跳到 2346。而 offsetY 是
                                    // 按旧锚点 2281 收敛的，于是 offsetY/实时锚点 永远停在
                                    // 0.9723——播放器差 65px 没铺满、封面也差一点没落位，
                                    // 交回静态封面时往上跳一下。
                                    // offsetY 的上界与记录下来的锚点始终一致，用它作分母，
                                    // 展开完成时 p 恒等于 1，收起时恒等于 0。
                                    val denom = expandedAnchorPxSnapshot.floatValue
                                    return if (denom <= 0f) 0f
                                    else (currentOffset / denom).coerceIn(0f, 1f)
                                }
                            val currentBottomPx: Float
                                get() {
                                    // 底边与顶边用同一条 progress 直通，不再叠加二次平滑：
                                    // smoothStep 两端速度为 0，会让底边在起步/收尾时"冻住"。
                                    return collapsedBottomPx +
                                            (parentHeight.intValue.toFloat() - collapsedBottomPx) * progress
                                }
                            // 迷你内容与播放页互补（恒和为 1），两者都走完整段行程。
                            // 参考 Flamingo 源码：迷你条是整块幕布按 1-progress 淡出，
                            // 提前在前 1/10 行程归零会让前段像"已经做完"，中段只剩边缘在爬。
                            val miniAlpha: Float
                                get() = 1f - progress
                            // 播放页随行程连续显现，与 miniAlpha 互补，不留"内容先落地"的空档。
                            val playerAlpha: Float
                                get() = progress
                            // 左右边与上下边共享同一条 progress：四边在 progress=1 同时抵达屏幕边缘。
                            val insetProgress: Float
                                get() = progress
                            // 毛玻璃随行程线性收敛，不再在 70% 就把模糊化完。
                            val opticFade: Float
                                get() = progress
                            // 描边在 progress 0.5 之前淡到 0：0.5 处 backdrop 会按性能门禁
                            // 硬切 highlight，若那时描边仍接近满不透明度会产生可见跳变。
                            val edgeFade: Float
                                get() = smoothStep(0.30f, 0.50f, progress)

                            // 主页面缩放直接复用播放器 progress 时间线
                            val mainContainerCardScale
                                get() = 1f - 0.07f * progress
                            val pageScale
                                get() = mainContainerCardScale

                            // 顶边驱动，按当前底部 inset 反推壳高度
                            val shellTopPx: Float
                                get() = lerp(collapsedTopPx, 0f, progress)
                            val shellBottomInset: Float
                                get() = parentHeight.intValue.toFloat() - currentBottomPx
                            val shellHeightPx: Float
                                get() = (parentHeight.intValue.toFloat() - shellTopPx - shellBottomInset)
                                    .coerceAtLeast(miniPlayerHeightPx)

                            val shellWidthPx: Float
                                get() = parentWidth.intValue.toFloat() - shellLeftInsetPx - shellRightInsetPx
                            val shellLeftInsetPx: Float
                                get() = with(density) {
                                    val screen = parentWidth.intValue.toFloat()
                                    if (screen <= 0f) return@with 0f
                                    if (!isSplitMode) {
                                        if (bottomBarWidthPx.intValue <= 0) return@with 0f
                                        val collapsedHorizontal =
                                            ((screen - bottomBarWidthPx.intValue) / 2f).coerceAtLeast(0f)
                                        return@with collapsedHorizontal * (1f - insetProgress)
                                    }
                                    // split：shell 左边 = splitRegionStart
                                    val splitRegion = screen * 0.93f
                                    val splitRegionStart = (screen - splitRegion) / 2f
                                    splitRegionStart * (1f - insetProgress)
                                }
                            val shellRightInsetPx: Float
                                get() = with(density) {
                                    val screen = parentWidth.intValue.toFloat()
                                    if (screen <= 0f) return@with 0f
                                    if (!isSplitMode) {
                                        if (bottomBarWidthPx.intValue <= 0) return@with 0f
                                        val collapsedHorizontal =
                                            ((screen - bottomBarWidthPx.intValue) / 2f).coerceAtLeast(0f)
                                        return@with collapsedHorizontal * (1f - insetProgress)
                                    }
                                    // split：shell 右边 = splitRegionEnd - bottomBarWidth - 15dp
                                    val splitRegion = screen * 0.93f
                                    val splitRegionStart = (screen - splitRegion) / 2f
                                    val splitRegionEnd = splitRegionStart + splitRegion
                                    val bottomBarWidth = splitRegion * 0.30f
                                    val gap = 15.dp.toPx()
                                    val collapsedRight = bottomBarWidth + gap + (screen - splitRegionEnd)
                                    collapsedRight * (1f - insetProgress)
                                }
                            val shellHorizontalInsetPx: Float
                                get() = shellLeftInsetPx + shellRightInsetPx

                            // shellRadius：动画期间始终保留圆角，避免最后几帧变成直角。
                            // 完全展开后的静止态由独立的落位状态切换为矩形铺满屏幕。
                            val shellRadius: Float
                                get() = with(density) {
                                    val targetCorner = screenCornerPx.value
                                    val collapsedRadius = miniPlayerHeightPx / 2f - 0.5f.dp.toPx()
                                    val settledExpanded = !dragActive.value &&
                                            playerMotionJob.value == null &&
                                            progress > 0.5f
                                    val target = if (settledExpanded) 0f
                                        else lerp(collapsedRadius, targetCorner, progress)
                                    val heightClamp = shellHeightPx / 2f - 0.5f.dp.toPx()
                                    val widthClamp = shellWidthPx / 2f - 0.5f.dp.toPx()
                                    minOf(target, heightClamp, widthClamp).coerceAtLeast(0f)
                                }
                        }

                        suspend fun waitForMorphHandoff(target: Float) {
                            var previousRect: Rect? = null
                            var previousAnchor = yosBottomSheetConfig.expandedAnchorPx
                            var stableFrames = 0
                            val endpointRect = {
                                if (target >= 0.5f) {
                                    localRect(coverGeometry.hero, coverGeometry.album)
                                } else {
                                    localRect(coverGeometry.hero, coverGeometry.mini)
                                }
                            }
                            morphHandoffRect.value = endpointRect()
                            repeat(8) {
                                withFrameNanos { }
                                val anchorNow = yosBottomSheetConfig.expandedAnchorPx
                                val rectNow = endpointRect()
                                val anchorStable = abs(anchorNow - previousAnchor) <= 0.5f
                                val rectStable = previousRect != null && rectNow != null &&
                                        abs(rectNow.left - previousRect!!.left) <= 0.5f &&
                                        abs(rectNow.top - previousRect!!.top) <= 0.5f &&
                                        abs(rectNow.width - previousRect!!.width) <= 0.5f &&
                                        abs(rectNow.height - previousRect!!.height) <= 0.5f
                                morphHandoffRect.value = rectNow
                                if (!anchorStable) {
                                    offsetY.updateBounds(0f, anchorNow)
                                    expandedAnchorPxSnapshot.floatValue = anchorNow
                                    offsetY.snapTo(target * anchorNow)
                                    stableFrames = 0
                                } else if (rectStable) {
                                    stableFrames++
                                } else {
                                    stableFrames = 0
                                }
                                previousAnchor = anchorNow
                                previousRect = rectNow
                                if (stableFrames >= 2) return
                            }
                        }

                        fun animatePlayerTo(
                            targetProgress: Float,
                            initialVelocity: Float = 0f,
                            startOffset: Float? = null
                        ) {
                            playerMotionJob.value?.cancel()
                            morphProgressOverride.value = null
                            morphHandoffRect.value = null
                            playerMotionJob.value = scope.launch {
                                val target = targetProgress.coerceIn(0f, 1f)
                                try {
                                    val anchor = yosBottomSheetConfig.expandedAnchorPx
                                    if (anchor <= 0f) return@launch
                                    offsetY.updateBounds(0f, anchor)
                                    expandedAnchorPxSnapshot.floatValue = anchor
                                    if (startOffset != null) {
                                        offsetY.snapTo(startOffset.coerceIn(0f, anchor))
                                    }
                                    offsetY.animateTo(
                                        targetValue = target * anchor,
                                        initialVelocity = initialVelocity,
                                        animationSpec = navSpec
                                    )

                                    // 运动期间 anchor 可能因底栏/inset 变化。先把最终位置
                                    // 收敛到最新端点，再等一帧让 Album 的布局坐标提交完成；
                                    // 这一帧内 morph 仍保持接管，避免真实封面抢先向上补跳。
                                    val anchorNow = yosBottomSheetConfig.expandedAnchorPx
                                    if (anchorNow > 0f) {
                                        offsetY.updateBounds(0f, anchorNow)
                                        expandedAnchorPxSnapshot.floatValue = anchorNow
                                        offsetY.snapTo(target * anchorNow)
                                        // 端点阶段不再读取 0.999… 的实时进度：先锁住
                                        // morph 的最终几何，再等待布局/变换提交完成。
                                        morphProgressOverride.value = target
                                        waitForMorphHandoff(target)
                                    }
                                } finally {
                                    if (playerMotionJob.value === coroutineContext[Job]) {
                                        morphProgressOverride.value = null
                                        morphHandoffRect.value = null
                                        playerMotionJob.value = null
                                    }
                                }
                            }
                        }


                        // 播放页歌手名 → 艺人主页：在**用户当前所在的房子**（Tab）内
                        // push ArtistDetail，再收回播放页——收回动画揭示的正是艺人页，
                        // 且系统返回自然逐层回退到打开播放页前的停留位置。
                        // 播放页侧 ArtistsTapText 已按点击位置定位具体歌手（点谁传谁）；
                        // 此处 toMultipleArtists().firstOrNull() 仅作兜底：ArtistDetail 按单个
                        // 歌手名精确解析。防抖按单层原则放在最外层导航发起方；栈顶已是同名艺人页则只收回不重复入栈。
                        val openArtistFromPlayer: (String) -> Unit = { rawName ->
                            val name = rawName.toMultipleArtists().firstOrNull()?.trim().orEmpty()
                            if (name.isNotEmpty() && name != defaultArtistsName) {
                                val topEntry = activeEntry.value
                                val alreadyThere =
                                    topEntry?.destination?.route == UI.ArtistDetailPattern &&
                                        topEntry.arguments?.getString(UI.ArtistDetailNameArg) == name
                                if (!alreadyThere) NavGuard.run {
                                    activeHouseController.navigate(
                                        UI.artistDetailRoute(artistId = null, artistName = name)
                                    )
                                }
                                animatePlayerTo(0f)
                            }
                        }


                        val showNowPlaying = remember("MainActivity_showNowPlaying") {
                            derivedStateOf {
                                // 对齐参考 Flamingo 源码的 menuAlpha < 0.3（即 progress > 0.7）：
                                // 系统栏配色/常亮切换移到行程后段，前段仍在形变中不做硬切换。
                                yosBottomSheetConfig.progress > 0.70f
                            }
                        }
                        val miniPlayerVisible = remember("MainActivity_miniPlayerVisible") {
                            derivedStateOf { yosBottomSheetConfig.progress < 0.16f }
                        }
                        val miniContentInteractive = remember("MainActivity_miniContentInteractive") {
                            derivedStateOf { yosBottomSheetConfig.progress < 0.10f }
                        }
                        val pageScale = remember("MainActivity_pageScale") {
                            derivedStateOf { yosBottomSheetConfig.pageScale }
                        }
                        // 页面卡片圆角生命周期：仅在播放壳形变动画进行中（拖拽占用或运动
                        // Job 存活）启用"屏幕圆角"设定值；动画完全结束立即回 RectangleShape
                        // 直角，不常驻裁剪页面（这就是此前任何页面截图四角露黑的根因层）。
                        // 静止落在展开位时页面被壳完全盖住，维持设定值保证收起起步时圆角连续；
                        // 信号全部来自动画生命周期（dragActive / playerMotionJob），无 delay。
                        val pageCorner = remember("MainActivity_pageCorner") {
                            derivedStateOf {
                                val playerAnimating = dragActive.value ||
                                        playerMotionJob.value != null
                                if (playerAnimating || yosBottomSheetConfig.progress > 0.5f) {
                                    screenCorner.value
                                } else {
                                    0.dp
                                }
                            }
                        }

                        val nowPageNowPlaying =
                            rememberSaveable(key = "MainActivity_nowPageNowPlaying") {
                                mutableStateOf(Album)
                            }

                        // 封面 morph 是否接管绘制。
                        //
                        // 关键：本状态是"隐藏原封面"与"hero 层绘制"的**唯一**依据。
                        // 三个消费点（迷你封面 alpha、Album 封面 alpha、hero 层）读的都是
                        // 这一个值，因此不可能出现"原封面已隐藏、hero 又没画"的空窗。
                        //
                        // 判据只取**动画生命周期**：拖拽占用中，或展开/收起运动 Job 存活。
                        //   · 运动 Job 在 finally 里清空（与 pageCorner 用的是同一个信号，
                        //     那个已经在真机验证过），所以落位后一定翻回 false，
                        //     封面随即交回真实的静态节点。
                        //
                        // 绝不能再叠加 progress 区间判断（曾经的写法）：
                        // progress 落位后停在 0.9999911（anchor 会漂），永远进不了
                        // "progress < 0.98" 这个退出条件，于是布尔值永久卡在 true——
                        // 大封面被永久隐藏、morph 层永久在上层画，表现就是
                        // 封面停在运动态、播放/暂停时下方真实封面缩放看不见（手机实测）。
                        // 这也印证了项目既有经验：落位判断不能用 progress==1 这类数值阈值。
                        //
                        // 其余门禁：
                        //  - 竖屏当前页是 Album：歌词/队列页的目标是 69dp 顶栏封面，不在本版范围；
                        //    横屏左列封面在所有子页面均常驻（Lyric/PlayingList 也在左列显示同一封面），
                        //    几何端点始终相同，故横屏不限制当前页。
                        //  - 几何已就绪，避免首帧用错落点。
                        // 注意横竖判定直接读 parentWidth/parentHeight，不读外层缓存的
                        // isSplitMode：那是普通 Boolean，在 derivedStateOf 里会变成常量。
                        val coverMorphActive = remember("MainActivity_coverMorphActive") {
                            derivedStateOf {
                                val split = parentWidth.intValue > 0 &&
                                        parentHeight.intValue > 0 &&
                                        parentWidth.intValue > parentHeight.intValue
                                // 竖屏仅在 Album 页触发 morph（歌词/队列页目标是顶栏小封面）；
                                // 横屏左列封面在所有子页面均常驻，几何端点相同，故横屏不限制当前页。
                                if (!split && nowPageNowPlaying.value != Album) return@derivedStateOf false
                                (dragActive.value || playerMotionJob.value != null) &&
                                        coverGeometry.ready.value
                            }
                        }

                        val navBackdropBackground = MaterialTheme.colorScheme.background
                        val navBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop {
                            GlassDiagnostics.tick(
                                "nav_backdrop_producer",
                                "size=${size.width.toInt()}x${size.height.toInt()}"
                            )
                            // Producer 的全屏底层保持黑色；页面视觉层仍单独使用主题背景。
                            drawRect(Color.Black)
                            drawContent()
                        }
                        GlassDiagnostics.state(
                            "nav_backdrop",
                            "instance=${System.identityHashCode(navBackdrop)} producerAlways=${glassProbe.producerAlwaysMounted}"
                        )
                        YosWrapper {
                            val isNight = isFlamingoInDarkMode()
                            val systemUiController = rememberSystemUiController()
                            LaunchedEffect(showNowPlaying.value, isNight) {
                                if (showNowPlaying.value) {
                                    systemUiController.setNavigationBarColor(
                                        Color.Transparent,
                                        darkIcons = false,
                                        navigationBarContrastEnforced = false
                                    )
                                    systemUiController.setStatusBarColor(
                                        Color.Transparent,
                                        darkIcons = false
                                    )
                                } else {
                                    systemUiController.setNavigationBarColor(
                                        color = Color.Transparent,
                                        darkIcons = !isNight,
                                        navigationBarContrastEnforced = false
                                    )
                                    systemUiController.setStatusBarColor(
                                        Color.Transparent,
                                        darkIcons = !isNight
                                    )
                                }
                            }
                            val activity = LocalContext.current as? Activity
                            val keepScreenOn = showNowPlaying.value
                            DisposableEffect(keepScreenOn) {
                                if (keepScreenOn) {
                                    activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                }
                                onDispose {
                                    if (keepScreenOn) {
                                        activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                                    }
                                }
                            }
                        }

                        // 导航区域
                        val defaultHome = stringResource(id = R.string.page_home_title)
                        val defaultLibrary = stringResource(id = R.string.page_library_title)
                        val defaultSearch = stringResource(id = R.string.page_search_title)
                        val defaultFavorites = stringResource(id = R.string.page_favorites_title)

                        val nowLabel = rememberSaveable(key = "MainActivity_nowLabel") {
                            mutableStateOf(defaultHome)
                        }

                        // 以下为实际显示

                        // 主界面
                        // 主界面
                        YosWrapper {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = Color.Black,
                                contentColor = MaterialTheme.colorScheme.onBackground
                            ) {
                                // 底栏渐隐遮罩：下边界是屏幕底，上边界是迷你条顶边。
                                // 非 split：迷你条高 + 5dp 间隙 + 底栏高 + 手势栏 inset；
                                // split：迷你条与底栏共用底边，只算迷你条高 + 手势栏 inset。
                                // 底栏尚未测得（height==0）时先不画，避免首帧出现一块空渐变。
                                val bottomScrimHeight = with(density) {
                                    if (height.intValue <= 0) 0.dp
                                    else (
                                            miniPlayerHeightPx +
                                                    (if (isSplitMode) 0f
                                                    else 5.dp.toPx() + height.intValue) +
                                                    navBarBottomInsetPx
                                            ).toDp()
                                }
                                NavPageVisual(
                                    navBackdrop = navBackdrop,
                                    pageScale = pageScale,
                                    pageCorner = pageCorner,
                                    pageBackground = navBackdropBackground,
                                    scrimColorProvider = scrimColorProvider,
                                    bottomScrimHeight = bottomScrimHeight,
                                    // 渐隐段 = 迷你条自身高度：屏幕底到迷你条底边是
                                    // 纯 65% 不透明，再在迷你条高度内渐隐到顶边为 0。
                                    bottomScrimFadeHeight = miniPlayerHeight
                                ) {
                                    AppTabsShell(
                                        selectedHouse = selectedHouse.value,
                                        homeController = homeNavController,
                                        libraryController = libraryNavController,
                                        searchController = searchNavController,
                                        favoritesController = favoritesNavController,
                                        navigator = appNavigator,
                                        modifier = Modifier.fillMaxSize(),
                                        probe = glassProbe
                                    )
                                    SharedNavHost(
                                        navController = rootNavController,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }

                                NavBottomVisual(
                                    navBackdrop = navBackdrop,
                                    selectedHouse = { selectedHouse.value },
                                    onHouseSelected = { house -> appNavigator.switchHouse(house) },
                                    onHouseReselected = { house ->
                                        when (house) {
                                            HouseId.Home -> homeNavController.popBackStack(UI.HomePage, false)
                                            HouseId.Library -> libraryNavController.popBackStack(UI.Library, false)
                                            HouseId.Search -> searchNavController.popBackStack(UI.Search, false)
                                            HouseId.Favorites -> favoritesNavController.popBackStack(UI.Favorites, false)
                                        }
                                        when (house) {
                                            HouseId.Home -> KugouSyncCoordinator.notifyDiscoveryChanged()
                                            HouseId.Favorites -> KugouSyncCoordinator.notifyFavoritesChanged()
                                            HouseId.Library -> {
                                                KugouSyncCoordinator.notifyPlaylistsChanged()
                                                KugouSyncCoordinator.notifyArtistsChanged()
                                            }
                                            HouseId.Search -> Unit
                                        }
                                    },
                                    nowLabel = nowLabel,
                                    defaultHome = defaultHome,
                                    defaultLibrary = defaultLibrary,
                                    defaultSearch = defaultSearch,
                                    defaultFavorites = defaultFavorites,
                                    bottomBarWidthPx = bottomBarWidthPx,
                                    containerTintProvider = tintColorProvider,
                                    height = height,
                                    isSplitMode = isSplitMode,
                                    parentWidth = parentWidth,
                                    glassProbe = glassProbe,
                                )
                            }



                            // 弹窗
                            YosWrapper {
                                val showCornerSetDialog =
                                    remember("MainActivity_showCornerSetDialog") {
                                        mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.S && !SettingsLibrary.ScreenCornerSet)
                                    }

                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && showCornerSetDialog.value) {
                                    ScreenCornerSetDialog {
                                        showCornerSetDialog.value = false
                                    }
                                } else {
                                    CheckAndRequestPermission()
                                }
                            }
                        }

                        // 注册在 NavHost 之后，使展开播放器优先处理系统返回；
                        // 仍置于动画 Shell 外，避免 alpha/clip/位移影响回调生命周期。
                        // 系统返回直接收起全屏播放器，不逐层退出歌词/播放列表页；
                        // 那两页回 Album 只由页内点击 PlayingBar 触发，与拖拽收起行为一致。
                        BackHandler(enabled = yosBottomSheetConfig.progress > 0.01f) {
                            animatePlayerTo(0f)
                        }

                        // 播放条&播放界面
                        YosWrapper {
                            // 底栏经 navigationBarsPadding 抬离手势栏，播放条的上移量
                            // 必须同样包含手势栏高度，否则会盖住底栏顶部
                            val navBarBottomInset = rawNavigationBarsBottomPx()

                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .onSizeChanged {
                                        parentHeight.intValue = it.height
                                        parentWidth.intValue = it.width
                                    },
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                YosWrapper {
                                    val miniPlayerHeightPx = remember("MainActivity_miniPlayerHeightPx") {
                                        with(density) {
                                            miniPlayerHeight.toPx()
                                        }
                                    }

                                    val color = Color.White withNight Color(0xFF1C1C1E)
                                    val hazeSurfaceAlpha = if (isFlamingoInDarkMode()) 0.55f else 0.85f


                                    LaunchedEffect(
                                        parentHeight.intValue,
                                        height.intValue,
                                        navBarBottomInsetPx
                                    ) {
                                        val newAnchor = yosBottomSheetConfig.expandedAnchorPx
                                        if (newAnchor <= 0f) return@LaunchedEffect
                                        val oldAnchor = lastPlayerAnchor.value
                                        val currentProgress = if (oldAnchor > 0f) {
                                            (offsetY.value / oldAnchor).coerceIn(0f, 1f)
                                        } else {
                                            0f
                                        }
                                        lastPlayerAnchor.value = newAnchor
                                        // progress 的分母只在**静止**时跟随新锚点更新：
                                        // 运动中出现的新锚点还没被写进 offsetY，用它作分母会
                                        // 让 p 立刻偏离 1。静止时更新是安全的——offsetY 会被
                                        // 下面的 snapTo 同步换算到新锚点，二者一致。
                                        if (!dragActive.value && playerMotionJob.value == null) {
                                            expandedAnchorPxSnapshot.floatValue = newAnchor
                                        }
                                        // 运动进行中绝不能 snap：snapTo 会取消正在跑的
                                        // animateTo，把动画直接钉死在半途。真机实测：
                                        // 展开途中 anchor 因底栏高度变化被重算，本 effect
                                        // 触发 snapTo → 动画被取消 → progress 永久停在
                                        // 0.9723（offsetY=2281.25 / anchor=2346.25），
                                        // 播放器差 65px 没铺满，封面交回静态节点时往上跳一下。
                                        // 运动期间只更新 anchor 记录与 bounds，位移由
                                        // animatePlayerTo / settleFromDrag 结尾的落位校正
                                        // 用当前 anchor 统一收敛到精确端点。
                                        if (dragActive.value || playerMotionJob.value != null) {
                                            offsetY.updateBounds(0f, newAnchor)
                                            return@LaunchedEffect
                                        }
                                        offsetY.updateBounds(0f, newAnchor)
                                        offsetY.snapTo(currentProgress * newAnchor)
                                    }

                                    val shellNestedScroll = remember {
                                        object : NestedScrollConnection {
                                            override fun onPreScroll(
                                                available: Offset,
                                                source: NestedScrollSource
                                            ): Offset {
                                                // PlayerShell 的 pointerInput 是唯一拖拽写入入口。
                                                // nested scroll 不再异步修改 offsetY，避免双路径竞争。
                                                return Offset.Zero
                                            }

                                            override fun onPostScroll(
                                                consumed: Offset,
                                                available: Offset,
                                                source: NestedScrollSource
                                            ): Offset {
                                                return Offset.Zero
                                            }

                                            override suspend fun onPostFling(
                                                consumed: Velocity,
                                                available: Velocity
                                            ): Velocity {
                                                // 子歌词/播放列表到达边界后的剩余 fling 仍属于子列表。
                                                // 不把它误判成 PlayerShell 的收起/展开手势，否则快速甩到顶部
                                                // 会把全屏播放器错误收缩成迷你播放器；由子列表自行回弹。
                                                return Velocity.Zero
                                            }
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

                                                                        // 直读全局单例，不能用 rememberSaveable 包：状态恢复时它会用
                                        // "上次保存的布尔值"新建局部副本，与真身永久脱钩——服务端
                                        // onIsPlayingChanged 只写全局，播放页按钮就会停在旧值
                                        // （队末自然停止后"播放页显示在播、迷你条显示暂停"的根因）。
                                        val isPlaying = MediaViewModelObject.isPlaying

                                    // 全屏播放器宿主：尺寸与父容器一致，并随 Shell 顶边连续展开。
                                    val shellHeightPx =
                                        (parentHeight.intValue - yosBottomSheetConfig.shellTopPx - yosBottomSheetConfig.shellBottomInset).coerceAtLeast(miniPlayerHeightPx)
                                    val shellHeightDp = with(density) {
                                        shellHeightPx.toDp()
                                    }
                                    val shellRadiusDp = with(density) {
                                        yosBottomSheetConfig.shellRadius.toDp()
                                    }
                                    val shellShape: androidx.compose.ui.graphics.Shape =
                                        RoundedRectangle(shellRadiusDp)
                                    // 展开/关闭动画期间，外壳表面统一为纯色过渡层，液态玻璃与
                                    // Haze 两种材质都采用；动画期不做模糊采样，静止后恢复各自材质。
                                    // "动画中" = 壳真实位移中：拖拽发生过位移（shellDragDisplaced）
                                    // 或落位动画在跑（playerMotionJob）。不能用 dragActive——它
                                    // 在手指一接管就置 true，按住不动/朝顶死方向拉时壳没有位移，
                                    // 材质不该切换。
                                    // 过渡层颜色与不透明度均跟随迷你条区域的底栏渐隐遮罩
                                    // （scrimColorProvider + bottomScrimAlpha）：默认主题下仍是
                                    // 白/黑 + 0.65，艺人页会随页面取色混合，动画覆盖层与迷你条
                                    // 周围环境观感一致（取值见下方 drawBehind）。
                                    val shellAnimating = shellDragDisplaced.value ||
                                        playerMotionJob.value != null
                                    // 玻璃边缘带按深浅色分两套配方（数值与 ClassYaba LiquidButton 一致）。
                                    val shellIsNight = isFlamingoInDarkMode()
                                    // 盖层 alpha：进入动画期**直切打满**（组合期 if 短路读常数 1，
                                    // 与表面/背景停画门禁同帧生效）——若淡入，门禁已停画的头几帧
                                    // 盖层还接近 0，材质层又是空的，整壳呈全透明闪变；退回静止时
                                    // 保留 0.2s 淡出（Animatable 从 1 渐到 0，露出原材质）。
                                    val shellFillAnim = remember("MainActivity_shellFillAnim") {
                                        Animatable(0f)
                                    }
                                    LaunchedEffect(shellAnimating) {
                                        if (shellAnimating) {
                                            shellFillAnim.snapTo(1f)
                                        } else {
                                            shellFillAnim.animateTo(0f, tween(durationMillis = 200))
                                        }
                                    }
                                    val animFillAlpha = if (shellAnimating) 1f else shellFillAnim.value
                                    val shellShadowElevation by animateDpAsState(
                                        targetValue = if (shellAnimating) 0.dp else 4.dp,
                                        animationSpec = tween(durationMillis = 200),
                                        label = "shellShadowElevation"
                                    )
                                    val velocityThreshold = with(density) { 300.dp.toPx() }

                                    fun settleFromDrag(releaseVelocity: Float?) {
                                        val anchor = yosBottomSheetConfig.expandedAnchorPx
                                        if (anchor <= 0f) return
                                        val finalOffset = dragOffsetY.floatValue.coerceIn(0f, anchor)
                                        val target = when {
                                            releaseVelocity != null && releaseVelocity > velocityThreshold -> 1f
                                            releaseVelocity != null && releaseVelocity < -velocityThreshold -> 0f
                                            // 提交距离阈值：原先要拖过行程一半才展开，偏"黏"。
                                            // Flamingo 最新版按屏幕高 0.18 判定（反编译确认
                                            // `fH * 0.18f`），换算到本项目的 anchor（≈2300px）
                                            // 约为行程的 0.22，拖过约 1/5 即果断展开。
                                            finalOffset >= anchor * 0.22f -> 1f
                                            else -> 0f
                                        }

                                        // 空转短路：壳已顶死在落点端点上（无位移手势直接松手——
                                        // 按住不动/收起态向下拉，或位移后又拖回端点），落位动画
                                        // 在 updateBounds 钳制下不会产生任何位移。此时不能启动
                                        // 动画 Job："动画中"判据含 playerMotionJob 非空，空转 Job
                                        // 会把材质过渡层直切打满再淡出——表现就是松手闪一下。
                                        // 簿记对齐静止态即可：dragActive 落 false；offsetY 本就停
                                        // 在该端点（拖拽只写 dragOffsetY，不碰 offsetY），无需
                                        // snapTo；morph 状态未被本次手势触碰，保持静止态的 null。
                                        val pinnedAtTarget =
                                            (target == 0f && finalOffset <= 0f) ||
                                                    (target == 1f && finalOffset >= anchor)
                                        if (pinnedAtTarget) {
                                            dragActive.value = false
                                            return
                                        }

                                        playerMotionJob.value?.cancel()
                                        playerMotionJob.value = scope.launch {
                                            offsetY.updateBounds(0f, anchor)
                                            expandedAnchorPxSnapshot.floatValue = anchor
                                            offsetY.snapTo(finalOffset)
                                            dragActive.value = false
                                            try {
                                                offsetY.animateTo(
                                                    targetValue = target * anchor,
                                                    initialVelocity = releaseVelocity ?: 0f,
                                                    animationSpec = navSpec
                                                )
                                                val anchorNow = yosBottomSheetConfig.expandedAnchorPx
                                                if (anchorNow > 0f) {
                                                    offsetY.updateBounds(0f, anchorNow)
                                                    expandedAnchorPxSnapshot.floatValue = anchorNow
                                                    offsetY.snapTo(target * anchorNow)
                                                    morphProgressOverride.value = target
                                                    waitForMorphHandoff(target)
                                                }
                                            } finally {
                                                if (playerMotionJob.value === coroutineContext[Job]) {
                                                    morphProgressOverride.value = null
                                                    morphHandoffRect.value = null
                                                }
                                                // 正常结束、取消、协程异常都必须清空运动 Job：
                                                // 它是"动画已结束"的唯一生命周期信号，残留非 null
                                                // 会让落位直角判断永远失效。快速重复拖拽时旧协程
                                                // 的 finally 会发现 value 已换成新 Job 而跳过清理。
                                                if (playerMotionJob.value === coroutineContext[Job]) {
                                                    playerMotionJob.value = null
                                                }
                                            }
                                        }
                                    }

                                    // 本次手势是否已由控件接管：接管后 shell 全程不位移，也不进入 dragActive
                                    val gestureVetoed = remember("MainActivity_gestureVetoed") {
                                        mutableStateOf(false)
                                    }

                                    val playerDragState = rememberDraggableState { delta ->
                                        if (gestureVetoed.value || controlGestureActive.value) {
                                            return@rememberDraggableState
                                        }
                                        val anchor = yosBottomSheetConfig.expandedAnchorPx
                                        if (anchor > 0f) {
                                            val before = dragOffsetY.floatValue
                                            dragOffsetY.floatValue =
                                                (before + delta).coerceIn(0f, anchor)
                                            // coerce 后没有变化 = 壳已顶到边界、视觉上没动
                                            // （收起态向下拉 / 展开态向上拉），不置位，材质不变。
                                            if (dragOffsetY.floatValue != before) {
                                                shellDragDisplaced.value = true
                                            }
                                        }
                                    }

                                    // 播放页玻璃弹层基础设施：仿 Title 脚手架的 backdrop+overlay host。
                                    // backdrop 采样全屏播放内容（layerBackdrop 挂在下方内容层），
                                    // 音质下拉的 vibrancy/blur 采样它，材质与设置页逐值一致；
                                    // 宿主槽在壳 Box 之后（clip 外），弹层不被壳圆角裁剪、坐标同系。
                                    val playerBackdrop = com.kyant.backdrop.backdrops.rememberLayerBackdrop {
                                        drawRect(color)
                                        drawContent()
                                    }
                                    val playerOverlayHost = remember { TitleOverlayHost() }
                                    GlassDiagnostics.state(
                                        "shell_gate",
                                        "barBlur=${SettingsLibrary.BarBlurEffect} shellGlass=${glassProbe.shellGlass} " +
                                                "radius=${shellRadiusDp.value} progressBucket=${(yosBottomSheetConfig.progress * 10f).toInt()} " +
                                                "animating=$shellAnimating " +
                                                "backdropDraw=${glassProbe.shellBackdropDraw}"
                                    )
                                    val shellAnimatingForDraw by rememberUpdatedState(
                                        shellAnimating
                                    )
                                    val shellProgressForDraw by rememberUpdatedState(yosBottomSheetConfig.progress)
                                    val shellBackdropDrawEnabled by rememberUpdatedState(glassProbe.shellBackdropDraw)
                                    val shellColorForDraw by rememberUpdatedState(color)
                                    val shellSurfaceAlphaForDraw by rememberUpdatedState(hazeSurfaceAlpha)
                                    val liquidShellOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit =
                                        remember {
                                            { drawBackdrop ->
                                                if (!shellAnimatingForDraw &&
                                                    shellBackdropDrawEnabled &&
                                                    shellProgressForDraw < 0.55f
                                                ) {
                                                    drawBackdrop()
                                                }
                                            }
                                        }
                                    val plainShellOnDrawBackdrop: DrawScope.(DrawScope.() -> Unit) -> Unit =
                                        remember {
                                            { drawBackdrop ->
                                                if (!shellAnimatingForDraw && shellProgressForDraw < 0.55f) {
                                                    drawBackdrop()
                                                }
                                            }
                                        }
                                    CompositionLocalProvider(
                                        LocalTitlePageBackdrop provides playerBackdrop,
                                        LocalTitleOverlayHost provides playerOverlayHost,
                                    ) {
                                    // 单一 PlayerShell：唯一 bounds、shape、Backdrop 和裁剪边界。
                                    // 顶边由 progress 驱动，底边按底部 inset 保持与导航栏的间隙。
                                    Box(
                                        Modifier
                                            .fillMaxWidth()
                                            .padding(
                                                start = with(density) { yosBottomSheetConfig.shellLeftInsetPx.toDp() },
                                                end = with(density) { yosBottomSheetConfig.shellRightInsetPx.toDp() }
                                            )
                                            .height(shellHeightDp)
                                            .offset {
                                                // 用布局位移把壳的底部 inset 写进 LayoutCoordinates。
                                                // Haze 的 source/consumer 映射依赖布局坐标；graphicsLayer
                                                // translationY 只改绘制矩阵，动画结束后会留下错误的 Y 采样基准。
                                                IntOffset(
                                                    x = 0,
                                                    y = -yosBottomSheetConfig.shellBottomInset.roundToInt()
                                                )
                                            }
                                            .graphicsLayer {
                                                // 这个 modifier 必须位于材质节点之前：Compose modifier
                                                // 链的前项包住后项，才能裁掉 Haze/backdrop 的离屏输出。
                                                shape = shellShape
                                                clip = true
                                            }
                                            .then(
                                                // 落位静止（radius==0，RectangleShape）时不能挂
                                                // backdrop：其形状蒙版只在节点尺寸变化时重录，动画
                                                // 停住后仅改 shape 不生效，弧角会永久残留（真机实证）。
                                                // p=1 时 backdrop 本来只画不透明底色 drawRect(color,1)，
                                                // background(color, shellShape) 与它逐像素等价，切换
                                                // 无感；动画重启时回到本分支，节点重建、蒙版按当前
                                                // 圆角重录。玻璃效果本身不动。
                                                if (SettingsLibrary.BarBlurEffect &&
                                                    glassProbe.shellGlass &&
                                                    shellRadiusDp != 0.dp
                                                ) {
                                                    Modifier.drawBackdrop(
                                                        backdrop = navBackdrop,
                                                        shape = { shellShape },
                                                        effects = {
                                                            // 运动期只保留轻模糊：真正贵的是 highlight/shadow
                                                            // （各 ~17.7ms 的近全屏离屏记录，已在下方运动期置 null），
                                                            // vibrancy/lens 量级小但同样可省；blur 在已记录的层上
                                                            // 只值 0-2ms，故运动期照留——模糊质感不丢。
                                                            val animating = shellAnimatingForDraw
                                                            if (glassProbe.shellBlur) {
                                                                blur(4.dp.toPx() * (1f - yosBottomSheetConfig.opticFade))
                                                            }
                                                            if (!animating) {
                                                                vibrancy()
                                                                if (glassProbe.shellLens &&
                                                                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                                                                ) {
                                                                    lens(
                                                                        minOf(16.dp.toPx() * (1f - yosBottomSheetConfig.opticFade), shellRadiusDp.toPx()),
                                                                        32.dp.toPx() * (1f - yosBottomSheetConfig.opticFade)
                                                                    )
                                                                }
                                                            }
                                                        },
                                                        // 黑边环绕高光（数值与 ClassYaba LiquidButton blackSideHighlight
                                                        // 完全一致）：WrapHighlightStyle 沿轮廓整圈连续，左右最深、
                                                        // 上下保留 baseline=0.4，黑边不会在上下消失。白色提亮高光
                                                        // 由下方 emptyBackdrop 覆盖层单独叠加。
                                                        // 门禁保留：运动期 / progress>=0.5 / 探针 nohigh 时为 null
                                                        // （库内 highlight 每帧 record 离屏层，展开掉帧主因）。
                                                        highlight = {
                                                            if (!glassProbe.shellHighlight ||
                                                                shellInMotion(glassProbe, shellDragDisplaced.value,
                                                                    playerMotionJob.value != null) ||
                                                                yosBottomSheetConfig.progress >= 0.5f
                                                            ) null
                                                            else Highlight.Default.copy(
                                                                width = if (shellIsNight) 0.4f.dp else 0.5f.dp,
                                                                blurRadius = if (shellIsNight) 0.1f.dp else 0.2f.dp,
                                                                alpha = 1f,
                                                                style = WrapHighlightStyle(
                                                                    color = Color.Black.copy(alpha = if (shellIsNight) 0.4f else 0.5f),
                                                                    blendMode = BlendMode.SrcOver,
                                                                    angle = 0f,
                                                                    falloff = 0.9f,
                                                                    baseline = 0.4f
                                                                )
                                                            )
                                                        },
                                                        shadow = {
                                                            if (!glassProbe.shellShadow ||
                                                                shellInMotion(glassProbe, shellDragDisplaced.value,
                                                                    playerMotionJob.value != null) ||
                                                                yosBottomSheetConfig.progress >= 0.5f
                                                            ) null
                                                            else com.kyant.backdrop.shadow.Shadow.Default.copy(
                                                                // 与 highlight 的门禁对齐：0.5 处 alpha 正好为 0，
                                                                // 避免阴影在 0.5 还有一半不透明度时被硬切掉。
                                                                alpha = 1f - smoothStep(0f, 0.50f, yosBottomSheetConfig.progress)
                                                            )
                                                        },
                                                        onDrawBackdrop = liquidShellOnDrawBackdrop,
                                                        onDrawSurface = {
                                                            // 动画期不画材质表面：由统一的纯色过渡层接管（颜色
                                                            // 与 alpha 均跟随底栏遮罩），
                                                            // 避免与过渡遮罩重复叠加。
                                                            if (!shellAnimatingForDraw) {
                                                                val underlay = smoothStep(0.35f, 0.55f, shellProgressForDraw)
                                                                val tint = 0.5f * (1f - shellProgressForDraw)
                                                                drawRect(shellColorForDraw.copy(alpha = maxOf(tint, underlay)))
                                                                // 提取色 tint 画在表面色之上、描边高光之下（高光由库画在
                                                                // surface 之后）；随壳展开进度归零，全屏播放页不着色。
                                                                // 绘制期读取：艺人页转场逐帧只重绘不重组。
                                                                val shellAccent = tintColorProvider()
                                                                if (shellAccent.alpha > 0.005f) {
                                                                    drawRect(shellAccent.copy(alpha = shellAccent.alpha * (1f - shellProgressForDraw)))
                                                                }
                                                            }
                                                        }
                                                    )
                                                } else if (!SettingsLibrary.BarBlurEffect && shellRadiusDp != 0.dp) {
                                                    // 关闭工具栏液态玻璃时，与底栏使用同一个 Kyant
                                                    // drawBackdrop backend，避免 Haze 与底栏的 surface
                                                    // 合成顺序不同而产生颜色偏差。
                                                    // 与上方玻璃分支同一条 0 半径守卫：落位静止
                                                    // （radius==0）改走下方 background 兜底，否则
                                                    // backdrop 的圆角蒙版在尺寸冻结后不重录，动画末帧
                                                    // 的弧角会永久残留（默认即此分支，就是"展开后
                                                    // 四角仍是圆角"的根因）。
                                                    // 动画期门禁与上方液态玻璃分支完全对齐：
                                                    //  - backdrop 内容（blur 12dp 全屏合成）运动期停画，
                                                    //    颜色由下方统一的纯色过渡层接管；
                                                    //  - 投影环每帧 record 一个比壳大 radius*4 的离屏层，
                                                    //    沿用玻璃分支同一条 0.5 门禁与 alpha 渐隐。
                                                    // 缺了这两条门禁时，动画期节点尺寸逐帧变化会让
                                                    // blur 蒙版与投影离屏层反复重录——这就是
                                                    // "关闭液态玻璃时展开卡顿"的主因。
                                                    Modifier.drawBackdrop(
                                                        backdrop = navBackdrop,
                                                        shape = { shellShape },
                                                        effects = { blur(12.dp.toPx()) },
                                                        // 黑边环绕高光：与上方玻璃分支同配方
                                                        // （ClassYaba blackSideHighlight），关玻璃态也保留
                                                        // 描边（用户要求与底栏磨砂胶囊一致）。门禁同玻璃
                                                        // 分支：运动期/展开期/探针 nohigh 时不画。
                                                        highlight = {
                                                            if (!glassProbe.shellHighlight ||
                                                                shellInMotion(glassProbe, shellDragDisplaced.value,
                                                                    playerMotionJob.value != null) ||
                                                                yosBottomSheetConfig.progress >= 0.5f
                                                            ) null
                                                            else Highlight.Default.copy(
                                                                width = if (shellIsNight) 0.4f.dp else 0.5f.dp,
                                                                blurRadius = if (shellIsNight) 0.1f.dp else 0.2f.dp,
                                                                alpha = 1f,
                                                                style = WrapHighlightStyle(
                                                                    color = Color.Black.copy(alpha = if (shellIsNight) 0.4f else 0.5f),
                                                                    blendMode = BlendMode.SrcOver,
                                                                    angle = 0f,
                                                                    falloff = 0.9f,
                                                                    baseline = 0.4f
                                                                )
                                                            )
                                                        },
                                                        shadow = {
                                                            if (shellInMotion(glassProbe, shellDragDisplaced.value,
                                                                    playerMotionJob.value != null) ||
                                                                yosBottomSheetConfig.progress >= 0.5f
                                                            ) null
                                                            else com.kyant.backdrop.shadow.Shadow.Default.copy(
                                                                alpha = 1f - smoothStep(0f, 0.50f, yosBottomSheetConfig.progress)
                                                            )
                                                        },
                                                        innerShadow = { null },
                                                        onDrawBackdrop = plainShellOnDrawBackdrop,
                                                        onDrawSurface = {
                                                            if (!shellAnimatingForDraw) {
                                                                drawRect(shellColorForDraw.copy(alpha = shellSurfaceAlphaForDraw))
                                                                val shellAccent = tintColorProvider()
                                                                if (shellAccent.alpha > 0.005f) {
                                                                    drawRect(shellAccent.copy(alpha = shellAccent.alpha * (1f - shellProgressForDraw)))
                                                                }
                                                            }
                                                        }
                                                    )
                                                } else {
                                                    Modifier.background(color, shellShape)
                                                }
                                            )
                                            // 动画期纯色覆盖层（0.2s 过渡淡入淡出）：动画开始淡入，
                                            // 结束淡出露出原材质；两方向均 0.2s，不瞬间切换。
                                            // 颜色与不透明度都跟随底栏渐隐遮罩（scrimColorProvider +
                                            // bottomScrimAlpha，见文件级常量注释），与迷你条周围
                                            // 环境观感同源。液态玻璃与关闭液态玻璃（Kyant
                                            // drawBackdrop）两种材质都启用：关闭玻璃的分支表面层
                                            // 在动画期停画，若没有这层接管颜色，迷你条会从
                                            // "85% 表面色"跳到"模糊内容透底"再跳回——
                                            // 这就是静止态与动画态色差的来源。
                                            .then(
                                                if (animFillAlpha > 0.001f) {
                                                    // 取值在绘制期读取（scrimColorProvider 读的都是快照
                                                    // 状态）：艺人页转场逐帧混色只失效重绘，不重组这棵
                                                    // 壳作用域；drawBehind 与 background 语义等价（画在
                                                    // 后续内容之后/之下），只是把取值时机推迟到 draw。
                                                    Modifier.drawBehind {
                                                        // drawRect 没有 shape 参数：圆角矩形用 drawOutline
                                                        // 画（语义等价 background(color, shellShape)）。
                                                        drawOutline(
                                                            outline = shellShape.createOutline(
                                                                size,
                                                                layoutDirection,
                                                                this
                                                            ),
                                                            color = scrimColorProvider()
                                                                .copy(alpha = bottomScrimAlpha * animFillAlpha)
                                                        )
                                                    }
                                                } else Modifier
                                            )
                                            // 白色提亮高光覆盖层（数值与 ClassYaba LiquidButton defaultHighlight
                                            // 一致）：angle=90 直上直下，亮在上下直边；挂在 emptyBackdrop 上
                                            // 不再采样玻璃内容，不会盖住下层的黑色环绕高光。门禁与黑边一致。
                                            .then(
                                                Modifier.drawBackdrop(
                                                    backdrop = emptyBackdrop(),
                                                    shape = { shellShape },
                                                    effects = {},
                                                    highlight = {
                                                        if (!glassProbe.shellHighlight ||
                                                            shellInMotion(glassProbe, shellDragDisplaced.value,
                                                                playerMotionJob.value != null) ||
                                                            yosBottomSheetConfig.progress >= 0.5f
                                                        ) null
                                                        else Highlight.Default.copy(
                                                            width = 1f.dp,
                                                            blurRadius = if (shellIsNight) 0.5f.dp else 0.1f.dp,
                                                            alpha = if (shellIsNight) 1f else 0.8f,
                                                            style = HighlightStyle.Default(
                                                                color = Color.White.copy(alpha = if (shellIsNight) 0.1f else 0.2f),
                                                                blendMode = BlendMode.Plus,
                                                                angle = 90f,
                                                                falloff = 1f
                                                            )
                                                        )
                                                    },
                                                    shadow = { null },
                                                    innerShadow = { null },
                                                    onDrawBackdrop = { },
                                                    onDrawSurface = {
                                                        // 上下高光的内阴影延伸：顶部高光向下、底部高光向上
                                                        // 各拉一条白色内渐变带，颜色与白高光一致、同用 Plus
                                                        // 叠加。画在本层 surface 记录内（复用已有图层、不新增
                                                        // 离屏记录），节点已按 shellShape 裁剪，圆角处跟随轮廓。
                                                        // 门禁与白高光一致。
                                                        if (!glassProbe.shellHighlight ||
                                                            shellInMotion(glassProbe, shellDragDisplaced.value,
                                                                playerMotionJob.value != null) ||
                                                            yosBottomSheetConfig.progress >= 0.5f
                                                        ) {
                                                            // 运动期/展开期不画
                                                        } else {
                                                            val glowColor = Color.White.copy(
                                                                alpha = if (shellIsNight) 0.1f else 0.2f
                                                            )
                                                            val glowH = 6.dp.toPx()
                                                            // 顶边高光向下渐隐
                                                            drawRect(
                                                                brush = Brush.verticalGradient(
                                                                    0f to glowColor,
                                                                    1f to Color.Transparent,
                                                                    startY = 0f,
                                                                    endY = glowH
                                                                ),
                                                                size = Size(size.width, glowH),
                                                                blendMode = BlendMode.Plus
                                                            )
                                                            // 底边高光向上渐隐
                                                            drawRect(
                                                                brush = Brush.verticalGradient(
                                                                    0f to Color.Transparent,
                                                                    1f to glowColor,
                                                                    startY = size.height - glowH,
                                                                    endY = size.height
                                                                ),
                                                                topLeft = Offset(0f, size.height - glowH),
                                                                size = Size(size.width, glowH),
                                                                blendMode = BlendMode.Plus
                                                            )
                                                        }
                                                    }
                                                )
                                            )
                                            // 外壳内容坐标系：迷你封面与全屏封面都相对它换算。
                                            .onGloballyPositioned {
                                                coverGeometry.shell = it
                                                coverGeometry.refreshReady()
                                            }
                                            .nestedScroll(shellNestedScroll)
                                            .draggable(
                                                state = playerDragState,
                                                orientation = Orientation.Vertical,
                                                reverseDirection = true,
                                                // 手指按在音量条/进度条上时，本次手势归控件所有：
                                                // 收起手势直接不启动。否则先上下再左右移动会同时
                                                // 触发调音量与关闭播放器（竖直 slop 先被 shell 吃掉）。
                                                enabled = !controlGestureActive.value,
                                                onDragStarted = {
                                                    if (controlGestureActive.value) {
                                                        // 手势起点在控件上：本次由控件独占，
                                                        // shell 不位移，也不置 dragActive。
                                                        gestureVetoed.value = true
                                                    } else {
                                                        gestureVetoed.value = false
                                                        playerMotionJob.value?.cancel()
                                                        dragOffsetY.floatValue = offsetY.value
                                                        dragActive.value = true
                                                    }
                                                },
                                                onDragStopped = { velocity ->
                                                    if (gestureVetoed.value) {
                                                        gestureVetoed.value = false
                                                    } else {
                                                        // 先清位移标记再落位：settleFromDrag 会同步
                                                        // 启动落位动画 Job（playerMotionJob 立即非空），
                                                        // "动画中"判据无缝衔接，盖层不会中途淡出。
                                                        shellDragDisplaced.value = false
                                                        settleFromDrag(velocity)
                                                    }
                                                }
                                            )
                                    ) {
                                        // 壳内"背景光效+全屏播放页"公共采样层：音质下拉的玻璃采样它——
                                        // 只采内容层会缺背景光效、配上固定黑种子就成了"黑色板"；
                                        // 种子色用壳面颜色（随深浅色），玻璃观感与设置页同源
                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .then(
                                                    // 库内 LayerBackdropNode.draw() 每帧无条件 recordLayer
                                                    // 整个全屏内容，既不查脏也不管有没有人在采样。
                                                    // 展开/收起动画期间没有任何玻璃弹层挂着，这层就是
                                                    // 白付一份全屏 display list 重建，所以只在真有弹层
                                                    // 在册时才挂。slots 在本作用域读：它只在弹层开/关时变，
                                                    // 不会引入逐帧订阅。prodalways 复现改前行为供同包 A/B。
                                                    if (glassProbe.shellProducer &&
                                                        (glassProbe.producerAlwaysMounted ||
                                                                playerOverlayHost.slots.isNotEmpty())
                                                    ) Modifier.layerBackdrop(playerBackdrop)
                                                    else Modifier
                                                )
                                        ) {
                                            // Background remains mounted while the shell morphs, so album changes never expose a transparent frame.
                                            Box(
                                                modifier = Modifier
                                                    .requiredSize(
                                                        with(density) { parentWidth.intValue.toDp() },
                                                        with(density) { parentHeight.intValue.toDp() }
                                                    )
                                                    .align(Alignment.TopCenter)
                                                    .graphicsLayer {
                                                        alpha = yosBottomSheetConfig.playerAlpha
                                                    }
                                            ) {
                                                if (glassProbe.shellBackgroundEffect) {
                                                    YosFloatingLight(
                                                        modifier = Modifier.fillMaxSize(),
                                                        album = { MediaViewModelObject.bitmap.value },
                                                        isPlaying = { isPlaying.value },
                                                        nowPage = { nowPageNowPlaying.value },
                                                        showMiniPlayer = miniPlayerVisible.value
                                                    )
                                                }
                                            }

                                            // 全屏播放器内容：双维固定测量 + 顶边对齐，常驻。
                                            Box(
                                                modifier = Modifier
                                                    .requiredSize(
                                                        with(density) { parentWidth.intValue.toDp() },
                                                        with(density) { parentHeight.intValue.toDp() }
                                                    )
                                                    .align(Alignment.TopCenter)
                                                    .graphicsLayer {
                                                        compositingStrategy =
                                                            CompositingStrategy.ModulateAlpha
                                                        // 早饱和：内容在前 45% 行程即达到最终 alpha，
                                                        // 之后全程满不透明——切换大小封面时标题/歌手/
                                                        // 收藏/更多不再经历半透明发灰。与迷你条用同一
                                                        // 窗口互补（和恒为 1），不引入重叠。
                                                        alpha = smoothStep(0f, 0.45f, yosBottomSheetConfig.progress)
                                                    }
                                                    // 全屏播放页坐标系：Album 封面相对它换算。
                                                    // 页面内部布局与展开进度无关，所以这个偏移恒定；
                                                    // 页本身随外壳居中漂浮不影响它。
                                                    .onGloballyPositioned {
                                                        coverGeometry.page = it
                                                        coverGeometry.refreshReady()
                                                    }
                                            ) {
                                            NowPlaying(
                                                modifier = Modifier.fillMaxSize(),
                                                mainViewModel = mainViewModel,
                                                mediaViewModel = mediaViewModel,
                                                navController = rootNavController,
                                                onOpenArtist = openArtistFromPlayer,
                                                isPlayingStatusLambda = { isPlaying.value },
                                                isPlayingOnChanged = {
                                                    isPlaying.value = it
                                                },
                                                nowPageLambda = { nowPageNowPlaying.value },
                                                showMiniPlayer = miniPlayerVisible.value,
                                                onControlGesture = {
                                                    controlGestureActive.value = it
                                                },
                                                nowPageOnChanged = {
                                                    nowPageNowPlaying.value = it
                                                },
                                                albumCoverCoordsOnChanged = { coords ->
                                                    coverGeometry.album = coords
                                                    coverGeometry.refreshReady()
                                                },
                                                albumCoverSuppressed = {
                                                    coverMorphActive.value
                                                }
                                            )
                                        }
                                        }

                                        // 迷你内容：收起态的遮罩层；壳体扩张后由 Shell clip 自然遮盖，不再用作整页切换。
                                        // 迷你条不参与自适应亮度（底栏/迷你条已放弃适配），provide null
                                        // 让消费方回退主题色；保留 CompositionLocalProvider 结构最小化 diff。
                                        CompositionLocalProvider(
                                            LocalGlassContentColor provides null
                                        ) {
                                        BoxWithConstraints(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(miniPlayerHeight)
                                                .align(Alignment.TopCenter)
                                                .graphicsLayer {
                                                    compositingStrategy =
                                                        CompositingStrategy.ModulateAlpha
                                                    // 与内容层同一窗口互补：1 - smoothStep(0,0.45,progress)，
                                                    // 保证两者之和恒为 1，交叉淡入淡出在前 45% 完成。
                                                    this.alpha = 1f - smoothStep(0f, 0.45f, yosBottomSheetConfig.progress)
                                                }
                                                .then(
                                                    if (miniContentInteractive.value) {
                                                        Modifier.clickable(
                                                            interactionSource = remember { MutableInteractionSource() },
                                                            indication = null,
                                                            onClick = {
                                                                animatePlayerTo(1f)
                                                            }
                                                        )
                                                    } else Modifier
                                                )
                                        ) {
                                            // 分支判据与壳高度同一个变量：两者若分别取 maxWidth 与
                                            // screenWidthDp，边界机型会出现“62dp 的壳里装 31dp 内容”。
                                            if (isWideMiniBar) {
                                                // 平板扩展：Apple Music 风格扁平工具条（左侧 5 传输键 + 中部封面/标题/歌手 + 右侧歌词/队列入口）
                                                TabletMiniContent(
                                                    progressGate = miniContentInteractive.value,
                                                    animatePlayerTo = { animatePlayerTo(it) },
                                                    onLyrics = {
                                                        nowPageNowPlaying.value = yos.music.player.ui.pages.NowPlayingPage.Lyric
                                                        animatePlayerTo(1f)
                                                    },
                                                    onPlaylist = {
                                                        nowPageNowPlaying.value = yos.music.player.ui.pages.NowPlayingPage.PlayingList
                                                        animatePlayerTo(1f)
                                                    },
                                                    // 封面 morph：平板竖屏、手机竖屏、横屏 split 共用同一套
                                                    // 端点几何，只是迷你封面尺寸不同（平板/横屏 40dp，手机 26dp）。
                                                    // 横屏目标封面由 NowPlayingLandscape 主动上报，
                                                    // morph 三种模式均可接管。
                                                    coverCoordsOnChanged = { coords ->
                                                        coverGeometry.mini = coords
                                                        coverGeometry.refreshReady()
                                                    },
                                                    coverSuppressed = { coverMorphActive.value }
                                                )
                                            } else {
                                                // 手机/竖屏：紧凑迷你条（43dp），尺寸按参考图实测值定。
                                                Row(
                                                    Modifier
                                                        .height(miniPlayerHeight)
                                                        .fillMaxWidth()
                                                        // 胶囊左边到封面左边：参考图 29/923 屏宽 = 12.4dp（原版 8dp）
                                                        .padding(
                                                            start = 12.dp,
                                                            end = 8.dp
                                                        )
                                                ) {
                                                    Row(
                                                        Modifier
                                                            .height(miniPlayerHeight)
                                                            .weight(1f),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // 封面 26dp：参考图实测 61/923 屏宽 = 26.0dp。
                                                        // 这里不再用“封面 = 两行行高之和”的等式：
                                                        // 参考图的封面本身就比标题+歌手的文字块
                                                        // （12+11=23dp）高出约 3dp。
                                                        val miniCoverSize = 26.dp
                                                        YosWrapper {
                                                            ShadowImageWithCache(
                                                                dataLambda = { MediaViewModelObject.bitmap.value },
                                                                contentDescription = null,
                                                                modifier = Modifier
                                                                    .size(miniCoverSize)
                                                                    .graphicsLayer {
                                                                        // morph 期间由外壳单封面层绘制，本节点在图层面隐藏；
                                                                        // 读 State 只失效图层，不触发重组、不改布局。
                                                                        alpha =
                                                                            if (coverMorphActive.value) 0f else 1f
                                                                    }
                                                                    .onGloballyPositioned {
                                                                        coverGeometry.mini = it
                                                                        coverGeometry.refreshReady()
                                                                    },
                                                                cornerRadius = 4.dp,
                                                                shadowAlpha = 0f,
                                                                imageQuality = ImageQuality.LOW
                                                            )
                                                        }
                                                        Column(
                                                            // 封面右缘到文字：参考图 19/923 屏宽 = 8.1dp（原版 10dp）
                                                            Modifier.padding(start = 8.dp, end = 5.dp),
                                                            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
                                                        ) {
                                                            val adaptiveMiniColor =
                                                                LocalGlassContentColor.current
                                                                    ?: (Color.Black withNight Color.White)
                                                            Text(
                                                                text = MediaController.musicPlaying.value?.title
                                                                    ?: defaultTitle,
                                                                fontWeight = FontWeight.Medium,
                                                                fontSize = 12.sp,
                                                                lineHeight = 12.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                color = adaptiveMiniColor
                                                            )
                                                            Text(
                                                                text = MediaController.musicPlaying.value?.artists ?: "",
                                                                fontWeight = FontWeight.Normal,
                                                                fontSize = 11.sp,
                                                                lineHeight = 11.sp,
                                                                maxLines = 1,
                                                                overflow = TextOverflow.Ellipsis,
                                                                color = adaptiveMiniColor.copy(alpha = 0.6f)
                                                            )
                                                        }
                                                    }
                                                    Row(
                                                        Modifier
                                                            .fillMaxHeight()
                                                            .padding(end = 10.dp),
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        // 触摸盒 30dp + 图标 24dp：30+9+30 让两键中心距
                                                        // 正好 39dp（参考图实测）。图标单独收到 24dp 是因为
                                                        // 32 视口里 play 墨迹占 18/32，24dp 盒→13.5dp 墨迹，
                                                        // 对上参考图的 13.7dp；若墨迹填满 30dp 盒会明显偏大。
                                                        PlayPauseButton(
                                                            progressGate = miniContentInteractive.value,
                                                            boxSize = 30.dp,
                                                            iconSize = 24.dp
                                                        )
                                                        Spacer(modifier = Modifier.width(9.dp))
                                                        TransportIconButton(
                                                            iconRes = R.drawable.ic_nowplaying_mp_fforward,
                                                            contentDescription = "Next",
                                                            enabled = miniContentInteractive.value,
                                                            boxSize = 30.dp,
                                                            iconSize = 24.dp,
                                                            onClick = {
                                                                MediaController.userSkipNext()
                                                            }
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                        }

                                        // 外壳封面 morph：形变过程中由这一层单独绘制封面，
                                        // 使迷你封面与全屏 Album 封面成为同一条 progress 上的
                                        // 连续几何过渡，而不是两张图交叉淡入淡出。
                                        // 铺满外壳内容区（而非整屏）：这样它天然被壳裁剪，
                                        // 且局部坐标系与两个端点所在坐标系一致，
                                        // 变换量可直接使用，不必再做任何原点换算。
                                        // 逐帧只改 graphicsLayer，不重建布局/形状/阴影。
                                        CoverMorphLayer(
                                            progress = {
                                                morphProgressOverride.value
                                                    ?: yosBottomSheetConfig.progress
                                            },
                                            active = { coverMorphActive.value },
                                            geometry = coverGeometry,
                                            bitmap = { MediaViewModelObject.bitmap.value },
                                            handoffRect = morphHandoffRect.value,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }

                                    // 玻璃弹层宿主槽：必须在壳 Box 之外（壳有 clip(shellShape)）；
                                    // 本层与壳同处全屏未缩放坐标系，anchorBounds 与采样映射同系。
                                    Box(modifier = Modifier.fillMaxSize()) {
                                        playerOverlayHost.slots.forEach { (slotKey, slot) ->
                                            key(slotKey) { slot.content.value?.invoke() }
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

    @Composable
    private fun NavPageVisual(
        navBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
        pageScale: State<Float>,
        pageCorner: State<androidx.compose.ui.unit.Dp>,
        pageBackground: Color,
        scrimColorProvider: () -> Color,
        bottomScrimHeight: Dp,
        bottomScrimFadeHeight: Dp,
        content: @Composable () -> Unit
    ) {
        GlassDiagnostics.state(
            "page_geometry",
            "backdrop=${System.identityHashCode(navBackdrop)} " +
                    "scale=${(pageScale.value * 1000f).toInt()} corner=${pageCorner.value.value} " +
                    "scrim=${bottomScrimHeight.value.toInt()} fade=${bottomScrimFadeHeight.value.toInt()}"
        )
        // This host remains in full-screen coordinates; scaling belongs to its child.
        // 生产者的 LayoutCoordinates 决定玻璃消费端（底栏/迷你条）的采样映射，
        // 因此它必须留在全屏坐标系里；缩放只作用于其子节点。
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(navBackdrop)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        // 页面缩放必须走**绘制期 canvas 变换**，不能用 graphicsLayer：
                        // graphicsLayer 会引入离屏层，而 scale 每帧变化 → 该全屏离屏层每帧重建，
                        // 连带子树里的 backdrop 生产者每帧按全屏尺寸重录（实测提交阶段 8.3ms、
                        // 等待 RenderThread 5.6ms）。canvas 变换只改绘制矩阵，不建层。
                        // 实测展开动画满帧 77.5%→87.4%，且**模糊与页面后退效果都保留**。
                        .drawWithContent {
                            scale(pageScale.value, pivot = center) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        .graphicsLayer {
                            shape = RoundedCornerShape(pageCorner.value)
                            clip = true
                        }
                        .background(pageBackground)
                ) {
                    content()
                }
            }

            // 底栏渐隐遮罩：覆盖底栏与迷你条所占的整段底部区域（下边界=页面底，
            // 上边界=迷你条顶边）。屏幕底到迷你条底边之间是 65% 不透明的实心段
            // （即 35% 透明），再在迷你条高度内线性渐隐到顶边为 0。位置在页面
                            // 内容之上、底栏/迷你条之下。可见艺人页使用其背景色（且随其进出场
                            // 过渡同步混入/混出），其他页面回退主题白/黑。
            //
            // **刻意画在 layerBackdrop 录制子树之外**：65% 遮罩会把玻璃采样输入
            // 压成均匀色，blur/lens 无纹理可透，底栏呈现"纯色板"。玻璃改为采样
            // 未遮罩的页面内容（纹理保留），遮罩只负责底栏周边的视觉压暗；可读性
            // 由自适应内容色 + 自适应表面纱承担。遮罩仍复制页面的 scale+圆角裁剪
            // 变换，展开/收起播放器时与页面同缩放、不越出页面边界。
            if (bottomScrimHeight > 0.dp) {
                // 渐隐段占整块遮罩的高度比例：0 在顶边，1 在页面底。实心段从
                // 这个比例一直延伸到底边。
                val fadeFraction =
                    (bottomScrimFadeHeight / bottomScrimHeight).coerceIn(0f, 1f)
                val scrimAlpha = bottomScrimAlpha
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .drawWithContent {
                            scale(pageScale.value, pivot = center) {
                                this@drawWithContent.drawContent()
                            }
                        }
                        .graphicsLayer {
                            shape = RoundedCornerShape(pageCorner.value)
                            clip = true
                        }
                ) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(bottomScrimHeight)
                            .drawBehind {
                                // Read the registered animated State in draw, not composition:
                                // the artist's existing color animation directly drives each frame.
                                val scrimColor = scrimColorProvider()
                                drawRect(
                                    brush = Brush.verticalGradient(
                                        colorStops = arrayOf(
                                            0f to scrimColor.copy(alpha = 0f),
                                            fadeFraction to scrimColor.copy(alpha = scrimAlpha),
                                            1f to scrimColor.copy(alpha = scrimAlpha)
                                        )
                                    )
                                )
                            }
                    )
                }
            }
        }
    }

    @Composable
    private fun NavBottomVisual(
        navBackdrop: com.kyant.backdrop.backdrops.LayerBackdrop,
        selectedHouse: () -> HouseId,
        onHouseSelected: (HouseId) -> Unit,
        onHouseReselected: (HouseId) -> Unit,
        nowLabel: androidx.compose.runtime.MutableState<String>,
        defaultHome: String,
        defaultLibrary: String,
        defaultSearch: String,
        defaultFavorites: String,
        bottomBarWidthPx: androidx.compose.runtime.MutableIntState,
        height: androidx.compose.runtime.MutableIntState,
        isSplitMode: Boolean,
        parentWidth: androidx.compose.runtime.MutableIntState,
        glassProbe: yos.music.player.code.utils.others.GlassProbe,
        containerTintProvider: () -> Color,
    ) {
YosWrapper {
    val density = LocalDensity.current
    // 首帧测量时 parentWidth.intValue 仍为 0；split 分支的几何会让 Modifier.width(0.dp)
    // 进入 LiquidBottomTabs 的 BoxWithConstraints，触发 coerceIn(0f, -px) 崩溃。
    // 首帧走非 split 几何，等 onSizeChanged 写入后再切回 split。
    val effectiveSplitMode = isSplitMode && parentWidth.intValue > 0
    val splitRegionSideInset = with(density) {
        ((parentWidth.intValue * (1f - 0.93f)) / 2f).toDp()
    }
    val splitBarWidthDp = with(density) {
        // 防御：保底 1.dp，避免 constraints.maxWidth=0 让 LiquidBottomTabs 内部 coerceIn 抛错。
        maxOf(1.dp, (parentWidth.intValue * 0.93f * 0.30f).toDp())
    }
    Box(
        Modifier
            .fillMaxSize()
            .navigationBarsPadding()
            .then(
                if (effectiveSplitMode) {
                    Modifier.padding(
                        start = splitRegionSideInset,
                        end = splitRegionSideInset
                    )
                } else {
                    Modifier
                }
            ),
            contentAlignment = if (effectiveSplitMode) Alignment.BottomEnd else Alignment.BottomCenter
        ) {
            val navItems = remember(
                defaultHome,
                defaultLibrary,
                defaultSearch,
                defaultFavorites
            ) {
                listOf(
                    NavItem(defaultHome, R.drawable.ic_uitabbar_home),
                    NavItem(defaultFavorites, R.drawable.ic_uitabbar_favorites),
                    NavItem(defaultLibrary, R.drawable.ic_uitabbar_library),
                    NavItem(defaultSearch, R.drawable.ic_uitabbar_search)
                )
            }
            val externalIndex: () -> Int = {
                selectedHouse().tabIndex
            }
            // 注：关闭"工具栏液态玻璃"时，BottomNavigator 内部会退回 flamingo 原始扁平底栏。
            BottomNavigator(
                initialIndex = externalIndex(),
                externalIndex = externalIndex,
                onIndexChange = { index ->
                    nowLabel.value = navItems[index].label
                    val target = when (index) {
                        0 -> UI.HomePage
                        1 -> UI.Favorites
                        2 -> UI.Library
                        3 -> UI.Search
                        else -> null
                    }
                    if (target != null) {
                        houseForTabIndex(index)?.let { house ->
                            nowLabel.value = navItems[index].label
                            if (house == selectedHouse()) {
                                onHouseReselected(house)
                            } else {
                                onHouseSelected(house)
                            }
                        }
                    }
                },
                onTabReselected = { index ->
                    houseForTabIndex(index)?.let { house ->
                        if (house == selectedHouse()) {
                            onHouseReselected(house)
                        }
                    }
                },
                items = navItems,
                containerGlassEnabled = glassProbe.navContainerGlass,
                hiddenProducerEnabled = glassProbe.navHiddenProducer,
                tabGlassEnabled = glassProbe.navTabGlass,
                containerTintProvider = containerTintProvider,
                modifier = if (effectiveSplitMode) {
                    Modifier
                        .width(splitBarWidthDp)
                        .onSizeChanged {
                            bottomBarWidthPx.intValue = it.width
                            height.intValue = it.height
                        }
                } else {
                    Modifier
                        .fillMaxWidth(0.93f)
                        .onSizeChanged {
                            bottomBarWidthPx.intValue = it.width
                            height.intValue = it.height
                        }
                },
                backdrop = navBackdrop
            )
        }
    }
    }

    @Composable
    fun CheckAndRequestPermission() {
        val context = LocalContext.current
        val requestPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            updateStartupReady = true
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
                    updateStartupReady = true
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

// 底栏渐隐遮罩的基础不透明度。播放器展开/收回动画覆盖层的颜色与透明度
// 都跟随同一来源（遮罩颜色取 scrimColorProvider，alpha 取本常量），
// 保证动画期外壳与迷你条周围环境观感一致。两处必须同源，改这里即同步。
private const val bottomScrimAlpha = 0.65f

private fun routeToPrimaryTabIndex(route: String?): Int = when (route) {
    UI.HomePage -> 0
    UI.Library -> 1
    UI.Search -> 2

    // 二级页面/设置等不属于任何主 Tab 的路由返回 -1，
    // 底栏据此保持进入前的高亮（设置从哪个 Tab 进入就保持哪个 Tab）
    else -> -1
}

/**
 * 封面 morph 的几何来源。
 *
 * 保存四个节点的"活" LayoutCoordinates：外壳内容 Box、全屏播放页 Box、
 * 迷你封面、全屏 Album 封面。换算在**绘制阶段**进行，因此读到的都是当帧布局，
 * 不存在"写入时机不同步"的漂移。
 *
 * 为什么不用窗口绝对坐标（这是"封面顶边被壳裁掉"的根因）：
 * 外壳在展开时高度从 62dp 长到整屏，而 morph 层与全屏播放页都是
 * requiredSize(屏幕宽, 屏幕高) 的节点——**比外壳大**。Compose 对超出父容器的
 * 子节点会按对齐方式居中放置，于是它们的原点并不在外壳顶边，而是随外壳高度
 * 上下漂浮（实测 hero.y == 外壳顶 + (外壳高 − 屏幕高)/2，逐帧吻合）。
 * 用这个漂浮的原点去减封面坐标，插值出来的轨迹中段就会拱到壳顶之上被裁。
 *
 * 因此这里改成量取**相对各自稳定父容器的偏移**：
 *  - 迷你封面相对外壳内容（迷你条就贴在外壳内容顶边，偏移恒定）；
 *  - 全屏封面相对播放页（页面内部布局与展开进度无关，偏移恒定）。
 * 两个偏移在整段动画中都是常量，插值在"外壳内容坐标系"里就是一条直线，
 * 两端点都为正 → 封面始终落在壳内，且两端与真实封面逐像素重合。
 */
private class CoverMorphGeometry {
    var shell: LayoutCoordinates? = null
    var page: LayoutCoordinates? = null
    var mini: LayoutCoordinates? = null
    var album: LayoutCoordinates? = null
    var hero: LayoutCoordinates? = null

    /**
     * 几何是否就绪。用 Boolean 而不是判断上面的引用：Boolean 是不可变值，
     * 写进 MutableState 能正常触发失效；而 LayoutCoordinates 每次回调都是
     * 同一实例，写进 State 会被等值比较判为"没变"而不失效（此前动画
     * 时有时无的根因）。所以坐标放普通字段、就绪标志放 State。
     */
    val ready = mutableStateOf(false)

    fun refreshReady() {
        ready.value = shell != null && page != null && mini != null && album != null
    }

    // 迷你封面在外壳内容坐标系中的矩形（恒定：始终在壳顶下方 11dp）。
    fun miniInHero(hero: LayoutCoordinates?): Rect? = localRect(hero, mini)

    // 全屏 Album 封面在**外壳内容坐标系**中的矩形。
    //
    // 必须用"相对外壳"而不是"相对播放页"：播放页是 requiredSize(屏幕宽,屏幕高)
    // 的节点，比外壳高，会被 Compose 垂直居中，因此它相对外壳原点会上下漂浮
    // （真机实测：p=0.857 时 pageRelHero.top = -184，p=1.0 时回到 0）。
    // 用页内偏移当终点会丢掉这 184px 的漂浮量——动画途中封面被压低约 184px，
    // 落位时再猛地补上，表现就是"封面略低、结束时往上闪一下"。
    // 相对外壳测量则在落位时与真实封面逐像素一致（p=1 时 pageRelHero=0，两者等价），
    // 且随外壳同步平移、轨迹自然。
    fun albumInHero(hero: LayoutCoordinates?): Rect? = localRect(hero, album)
}

// 把 child 的布局矩形换算到 space 的坐标系。这里故意不读取
// localBoundingBoxOf：shared-element 的绘制变换属于另一条转场，不能污染
// PlayerShell morph 的稳定布局基准。child 本身是明确的正方形内容容器。
private fun localRect(space: LayoutCoordinates?, child: LayoutCoordinates?): Rect? {
    if (space == null || child == null || !space.isAttached || !child.isAttached) return null
    return try {
        val topLeft = space.localPositionOf(child, Offset.Zero)
        val size = child.size
        if (size.width <= 0 || size.height <= 0) return null
        Rect(
            left = topLeft.x,
            top = topLeft.y,
            right = topLeft.x + size.width,
            bottom = topLeft.y + size.height
        )
    } catch (e: IllegalStateException) {
        null
    }
}

// 视觉圆角：迷你封面 4dp → 全屏 Album 8dp，与 ShadowImageWithCache 的入参保持一致。
// 两处迷你封面（手机 28dp / 平板 40dp）的 cornerRadius 必须与此同步，
// 否则 morph 交接瞬间圆角会跳变。
private val morphCornerStartDp = 4f
private val morphCornerEndDp = 8f

// 封面 morph 层的布局基准上限：与 NowPlaying 全屏封面的限宽一致。
// 基准取"不小于两个端点"的尺寸，于是整个 morph 只缩小、不放大，
// 图片始终以足够的分辨率绘制，不会出现先放大再缩小的糊边。
private val morphBaseMaxWidth = 460.dp

// 一次 morph 的解算结果：hero 层局部坐标下的目标矩形，以及相对基准的缩放。
private class MorphFrame(
    val left: Float,
    val top: Float,
    val size: Float,
    val scale: Float
)

// 解算当前帧：两个端点都已在"外壳内容坐标系"中，直接按 progress 直线插值。
//
// 只在绘制阶段调用，因此读到的永远是当帧布局（不会像把 LayoutCoordinates 存进
// MutableState 那样被冻结在首帧），也不会产生逐帧重组与逐帧状态写入——
// 绘制块本来就要逐帧读 progress 才能跟手，这些几何读数只是顺带。
//
// 轨迹为什么是直线而不是抛物线：两个端点都锚在外壳内容顶边
// （迷你封面在顶边下 11dp，全屏封面在顶边下约 160dp），且外壳内容随壳顶边
// 同步上移，所以"封面粘在拉伸的壳上"这条物理直觉对应的正是直线——
// 封面跟着壳上移，同时相对壳缓慢下滑到自己的槽位。直线也保证两端点同为正、
// 轨迹不会拱出壳顶（抛物线/缓动在中段会顶到壳沿之上被裁，真机实测过）。
private fun resolveMorphFrame(
    geometry: CoverMorphGeometry,
    hero: LayoutCoordinates?,
    progress: Float,
    basePx: Float,
    handoffRect: Rect? = null
): MorphFrame? {
    val mini = geometry.miniInHero(hero) ?: return null
    val album = handoffRect ?: geometry.albumInHero(hero) ?: return null
    if (basePx <= 0f) return null
    val p = progress.coerceIn(0f, 1f)
    // 封面视觉上必须始终是正方形。真实图片节点由 fillMaxWidth()
    // + aspectRatio(1f) 决定尺寸，因此终点必须以 width 为准；不能取较小边，
    // 否则一个测量上的高度差会让 morph 最后一帧比静态封面小，交接时突然放大。
    // 高度只作为诊断意义上的布局信息，不参与正方形尺寸解算。
    val miniSize = mini.width
    val albumSize = album.width
    if (miniSize <= 0f || albumSize <= 0f) return null
    var size = miniSize + (albumSize - miniSize) * p
    if (size <= 0f) return null
    val miniCenter = Offset(
        mini.left + miniSize / 2f,
        mini.top + miniSize / 2f
    )
    val albumCenter = Offset(
        album.left + albumSize / 2f,
        album.top + albumSize / 2f
    )
    val center = Offset(
        miniCenter.x + (albumCenter.x - miniCenter.x) * p,
        miniCenter.y + (albumCenter.y - miniCenter.y) * p
    )
    var left = center.x - size / 2f
    var top = center.y - size / 2f

    // 安全网：极端情况下（例如壳还没长开、而封面已按 progress 长大）
    // 把封面平移回壳内；平移放不下才收缩。
    val heroW = if (hero != null && hero.isAttached) hero.size.width.toFloat() else 0f
    val heroH = if (hero != null && hero.isAttached) hero.size.height.toFloat() else 0f
    if (heroW > 0f && heroH > 0f) {
        val maxScale = minOf(heroW / size, heroH / size)
        if (maxScale < 1f) size *= maxScale
        left = center.x - size / 2f
        top = center.y - size / 2f
        left = left.coerceIn(0f, (heroW - size).coerceAtLeast(0f))
        top = top.coerceIn(0f, (heroH - size).coerceAtLeast(0f))
    }
    return MorphFrame(left, top, size, size / basePx)
}

/**
 * 外壳单封面层：在迷你封面矩形与全屏 Album 封面矩形之间，按同一条 progress
 * 插值位置、尺寸、圆角与阴影。
 *
 * 实现要点（每条都对应一个踩过的坑）：
 *  - 节点**固定**在基准尺寸，只用 graphicsLayer 做平移/缩放，不逐帧改布局，
 *    避免每帧重新测量图片与重建离屏纹理；
 *  - 圆角、描边、阴影都在节点自身的绘制里完成，并按 1/scale 反向补偿本地值。
 *    补偿必须用**当前帧解算出的 scale**，不能用节点自身尺寸算——
 *    节点尺寸永远是基准值，那样算出来恒等于 1，补偿失效，
 *    圆角会被缩成直角、描边会被缩成 1px（真机实测过）；
 *  - 阴影按与全屏封面同一套参数（ShadowType.Large + Overlay）绘制，
 *    透明度从迷你态 0 渐显到 0.23，落位交回原节点时两端像素一致，
 *    不会出现"动画结束突然冒出一层阴影"；
 *  - 不挂 Offscreen / backdrop / blur：这些是展开掉帧的已知主因，
 *    圆角用 clipPath 完成，描边用极便宜的路径描边，阴影只做一次模糊；
 *  - active 为 false 时整层不绘制，静止端点交回原封面节点。
 */
@Composable
private fun CoverMorphLayer(
    progress: () -> Float,
    active: () -> Boolean,
    geometry: CoverMorphGeometry,
    bitmap: () -> Any?,
    handoffRect: Rect? = null,
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val url = bitmap()
    val baseWidth = morphBaseMaxWidth.coerceAtMost(
        with(density) { LocalConfiguration.current.screenWidthDp.dp }
    )
    val basePx = with(density) { baseWidth.toPx() }
    // 阴影参数与全屏 Album 封面同源（ShadowType.Large、alpha 0.23、Overlay）。
    val shadowType = ShadowType.Large
    val shadowBlurPx = with(density) { shadowType.blur.toPx() }
    val shadowPaint = remember { Paint() }
    // 阴影的 BlurMaskFilter 原先每帧 new 一个再写回 paint。模糊半径是常量，
    // 因此只建一次即可（每帧 new 会持续分配，且写 maskFilter 会脏化 paint）。
    val shadowMaskFilter = remember(shadowBlurPx) {
        if (shadowBlurPx > 0f) {
            android.graphics.BlurMaskFilter(
                shadowBlurPx,
                android.graphics.BlurMaskFilter.Blur.NORMAL
            )
        } else null
    }

    // 用 Painter 直接绘制，而不是放一个超大尺寸的 AsyncImage 节点。
    //
    // 为什么不放图片节点：本层铺满外壳，而外壳展开早期只有 62dp 高；图片节点
    // 要保持足够分辨率就得有封面那么大（约 460dp），于是"子节点比父容器大"。
    // Compose 对这种超出父容器的子节点会按对齐方式居中放置——实测该节点落在
    // 容器顶边上方 497px（=(155−1150)/2），缩到迷你尺寸后整块跑到壳外被裁，
    // 表现为展开起点封面完全消失。绘制在容器自身的画布上则完全没有这个问题：
    // 画布尺寸恒等于外壳，坐标就是外壳坐标，不存在任何对齐偏移。
    val painter = coil.compose.rememberAsyncImagePainter(
        model = coil.request.ImageRequest.Builder(context)
            .data(url)
            // morph 进度由手势驱动，crossfade 会与它叠加出二次淡入，故关闭。
            .crossfade(false)
            .error(R.drawable.placeholder_music_default_artwork)
            .fallback(R.drawable.placeholder_music_default_artwork)
            .placeholder(R.drawable.placeholder_music_default_artwork)
            // 与全屏 Album 封面同缓存槽（cover|RAW），命中同一份位图；
            // LOW 槽作占位，RAW 未就绪时先显示迷你条那张，不闪空图。
            .memoryCacheKey(coverCacheKey(url, ImageQuality.RAW))
            .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
            .allowHardware(true)
            // 与全屏 Album 封面完全同参数（EXACT + ORIGINAL）：Coil 会校验缓存
            // 位图与请求尺寸一致，参数不同会退化为重新解码，既掉帧又让 morph
            // 中途出现画质跳变。同参 → 命中同一份位图。
            .precision(coil.size.Precision.EXACT)
            .size(coil.size.Size.ORIGINAL)
            .build()
    )

    Canvas(
        modifier = modifier.onGloballyPositioned { geometry.hero = it }
    ) {
        val frame = resolveMorphFrame(
            geometry,
            geometry.hero,
            progress(),
            basePx,
            handoffRect = handoffRect
        )
        // 读 State 只触发重绘，不触发重组；解不出端点就什么都不画。
        if (!active() || frame == null) return@Canvas
        val scale = frame.scale
        if (scale <= 0f) return@Canvas
        val p = progress().coerceIn(0f, 1f)

        val side = frame.size
        if (side <= 0f) return@Canvas

        // 圆角用**和真实封面同一个形状类**（YosRoundedCornerShape，超椭圆连续圆角），
        // 不能图省事用 RoundRect：同为 6dp，超椭圆角与正圆弧角的观感不同，
        // 交接瞬间能看出角部形状跳变（真机实测）。
        val radiusDp = (morphCornerStartDp +
                (morphCornerEndDp - morphCornerStartDp) * p).dp
        val shape = YosRoundedCornerShape(radiusDp)
        val frameRect = Rect(Offset(frame.left, frame.top), Size(side, side))
        // createOutline 生成的轮廓永远位于坐标原点 (0,0)，而封面画在
        // frame.left / frame.top 处。必须把裁剪路径平移到封面位置，
        // 否则遮罩停在左上角、封面右下角会被裁掉（真机实测：表现为
        // "图片本身没圆角、被一个偏左上方的圆角遮罩切掉了右下方"）。
        val outline = shape.createOutline(Size(side, side), layoutDirection, this)
        val path = Path().apply {
            addOutline(outline)
            translate(Offset(frame.left, frame.top))
        }

        // 阴影与描边都在**屏幕坐标**里按真实像素绘制，和真实封面节点完全同参：
        // 那些节点的坐标空间本来就是 1:1 屏幕空间，所以模糊半径和描边宽度
        // 都不需要任何换算。
        // 曾经把模糊按 1/scale 补偿是错的：BlurMaskFilter 的半径是设备像素、
        // 不随画布缩放走，除以 scale 后 p 越小模糊越大（起点约 276dp），
        // 阴影从"巨大弥散"渐变到正常，交接时就暴露成"阴影过渡有问题"。
        val shadowAlpha = morphShadowAlpha * smoothStep(0.15f, 1f, p)
        if (shadowAlpha > 0.001f) {
            drawCoverShadow(
                paint = shadowPaint,
                frameRect = frameRect,
                radiusDp = radiusDp,
                alpha = shadowAlpha,
                blurPx = shadowBlurPx,
                shadowType = shadowType,
                density = this,
                maskFilter = shadowMaskFilter
            )
        }

        // 与 ShadowImageWithCache 一致：先按圆角裁剪，再画内容与两道 12px 描边。
        clipPath(path) {
            // 只有位图需要在缩放空间里画；圆角、描边、阴影都在屏幕空间完成。
            withTransform({
                translate(frame.left, frame.top)
                scale(scale, scale, pivot = Offset.Zero)
            }) {
                drawPainterCropped(painter, Size(basePx, basePx))
            }
            drawPath(path, Color.Gray.copy(alpha = 0.1f), style = morphStroke)
            drawPath(
                path,
                Color.Gray.copy(alpha = 0.5f),
                style = morphStroke,
                blendMode = BlendMode.Overlay
            )
        }
    }
}

// 封面阴影透明度上限，与 ShadowImageWithCache 的默认 shadowAlpha 一致。
private const val morphShadowAlpha = 0.23f

/**
 * 以 ContentScale.Crop 的语义把 Painter 画进 box。
 *
 * 必须自己算裁切：真实封面节点用的是 `Image(contentScale = Crop)`，而 `Painter.draw()`
 * 只是把图**拉伸**到给定尺寸。非正方形封面下两者结果不同——morph 期间会拉伸变形，
 * 落位交回真实节点时再"啪"地跳成裁切版。用同样的 Crop 逻辑就不存在这个差异。
 */
private fun DrawScope.drawPainterCropped(
    painter: androidx.compose.ui.graphics.painter.Painter,
    box: Size
) {
    val intrinsic = painter.intrinsicSize
    if (!intrinsic.isSpecified || intrinsic.width <= 0f || intrinsic.height <= 0f) {
        with(painter) { draw(box) }
        return
    }
    val k = maxOf(box.width / intrinsic.width, box.height / intrinsic.height)
    val dw = intrinsic.width * k
    val dh = intrinsic.height * k
    translate((box.width - dw) / 2f, (box.height - dh) / 2f) {
        with(painter) { draw(Size(dw, dh)) }
    }
}

// 复刻 DropShadow.dropShadow 的几何，参数与全屏封面完全同参：
// 面积按 areaWeight 收缩、角半径沿用同一 dp 值（不随收缩缩放）、
// 偏移 = offsetY*边长 + 居中补偿，模糊半径直接用 dp 换算的像素值。
//
// 关键：本函数在**屏幕坐标**下调用，因此不需要任何 1/scale 补偿——
// DropShadow 也是在节点自身（1:1 屏幕）空间里按这套像素值画的，
// 参数相同，交接时才逐像素一致。
// morph 封面两道描边共用的 Stroke（宽度固定，避免每帧新建对象）。
private val morphStroke = Stroke(width = 12f)

private fun DrawScope.drawCoverShadow(
    paint: Paint,
    frameRect: Rect,
    radiusDp: androidx.compose.ui.unit.Dp,
    alpha: Float,
    blurPx: Float,
    shadowType: ShadowType,
    density: androidx.compose.ui.unit.Density,
    // 由调用方 remember 的模糊滤镜，避免每帧分配。
    maskFilter: android.graphics.BlurMaskFilter? = null
) {
    val w = frameRect.width
    val h = frameRect.height
    if (w <= 0f || h <= 0f) return
    val weight = shadowType.areaWeight
    val shadowShape = YosRoundedCornerShape(radiusDp)
    val shadowOutline = shadowShape.createOutline(
        Size(w * weight, h * weight),
        LayoutDirection.Ltr,
        density
    )
    val offsetX = shadowType.offsetX * w + (w * (1f - weight)) / 2f
    val offsetY = shadowType.offsetY * h + (h * (1f - weight)) / 2f

    paint.color = Color(0xFF000000).copy(alpha = alpha)
    paint.blendMode = BlendMode.Overlay
    if (blurPx > 0f) {
        paint.asFrameworkPaint().maskFilter = maskFilter
    }
    val canvas = drawContext.canvas
    canvas.save()
    canvas.translate(frameRect.left + offsetX, frameRect.top + offsetY)
    canvas.drawOutline(shadowOutline, paint)
    canvas.restore()
}

// 平滑阶梯：把 0..1 进度映射为 edge0..edge1 之间的 Hermite 平滑值。
// 用于 miniAlpha / insetProgress / playerAlpha / opticFade / edgeFade 的统一时间轴。
private fun smoothStep(edge0: Float, edge1: Float, x: Float): Float {
    if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
    val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

// 迷你播放条高度（顶层常量，便于 TabletMiniContent / PlayPauseButton / TransportIconButton 直接引用）
private val miniPlayerHeightDp: Dp = 62.dp

// 通用传输图标按钮（上一首 / 下一首 / 歌词 / 队列等），外壳与原胶囊内 next Box 行为一致。
// boxSize 默认 36dp；平板右侧歌词/队列调用处传 34dp 与 PlayPause 对齐。
// iconSize 只在“触摸盒要大于图标墨迹”时传（窄屏紧凑迷你条）；默认 null 走 fillMaxSize，
// 所有既有调用方的观感不变。
@Composable
private fun TransportIconButton(
    iconRes: Int,
    contentDescription: String?,
    enabled: Boolean,
    boxSize: Dp = 36.dp,
    iconSize: Dp? = null,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(boxSize)
            .clickable(
                enabled = enabled,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(id = iconRes),
            contentDescription = contentDescription,
            modifier = if (iconSize == null) Modifier.fillMaxSize() else Modifier.size(iconSize),
            tint = Color.Black withNight Color.White
        )
    }
}

// 播放/暂停按钮（手机默认 34dp，平板分支调用方显式传 46dp 等更大尺寸）。
// iconSize 语义与 TransportIconButton 一致：仅把墨迹缩小，触摸盒仍是 boxSize。
@Composable
private fun PlayPauseButton(
    progressGate: Boolean,
    boxSize: Dp = 34.dp,
    iconSize: Dp? = null
) {
    val iconModifier = if (iconSize == null) Modifier.fillMaxSize() else Modifier.size(iconSize)
    Box(
        modifier = Modifier
            .size(boxSize)
            .clickable(
                enabled = progressGate,
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(bounded = false),
                onClick = {
                    val newValue = !MediaViewModelObject.isPlaying.value
                    MediaViewModelObject.isPlaying.value = newValue
                    if (newValue) {
                        MediaController.mediaControl?.fadePlay()
                    } else {
                        MediaController.mediaControl?.fadePause()
                    }
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        AnimatedContent(
            targetState = MediaViewModelObject.isPlaying.value,
            transitionSpec = {
                (scaleIn(initialScale = 0.3f) + fadeIn()).togetherWith(
                    scaleOut(targetScale = 0.3f) + fadeOut()
                )
            }
        ) { playing ->
            if (playing) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nowplaying_mp_pause),
                    contentDescription = "Pause",
                    modifier = iconModifier,
                    tint = Color.Black withNight Color.White
                )
            } else {
                Icon(
                    painter = painterResource(id = R.drawable.ic_nowplaying_mp_play),
                    contentDescription = "Play",
                    modifier = iconModifier,
                    tint = Color.Black withNight Color.White
                )
            }
        }
    }
}

// 平板扩展迷你条（maxWidth >= 600.dp 时启用）。
// 从左到右：随机播放 | 上一首 | 播放/暂停 | 下一首 | 循环模式 | 封面 | 歌曲(标题/歌手) | 歌词 | 播放列表
@Composable
private fun TabletMiniContent(
    progressGate: Boolean,
    animatePlayerTo: (Float) -> Unit,
    onLyrics: () -> Unit,
    onPlaylist: () -> Unit,
    // 封面 morph：上报本迷你封面节点的坐标；suppressed 为 true 时在图层面隐藏，
    // 由外壳单封面层接管，避免与全屏封面同时画出两张图。
    coverCoordsOnChanged: (LayoutCoordinates) -> Unit = {},
    coverSuppressed: () -> Boolean = { false }
) {
    val context = LocalContext.current
    // 与迷你条玻璃面（withNight）同源判定应用内主题，避免系统/应用主题不一致时副标题反色
    val isLight = !isFlamingoInDarkMode()
    val subTitleColor = if (isLight) Color.Black.copy(alpha = 0.6f) else Color.White.copy(alpha = 0.6f)

    // shuffle / repeat 状态统一读 MediaViewModelObject 共享值；点击只改 mediaControl，由 Player.Listener 回流
    val shuffleEnabledState = MediaViewModelObject.shuffleModeEnabled
    val repeatModeState = MediaViewModelObject.repeatMode

    Row(
        modifier = Modifier
            .height(miniPlayerHeightDp)
            .fillMaxWidth()
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // --- 左：5 个传输控制（间距 12dp，与右端歌词/队列统一） ---
        ShuffleToggleButton(
            shuffleModeEnabledLambda = { shuffleEnabledState.value },
            onShuffleChanged = { shuffleEnabledState.value = it },
            buttonSize = 34.dp
        )
        Spacer(modifier = Modifier.width(16.dp))
        TransportIconButton(
            iconRes = R.drawable.ic_nowplaying_rewind,
            contentDescription = "Previous",
            enabled = progressGate,
            boxSize = 32.dp,
            onClick = {
                Vibrator.click(context)
                MediaController.userSkipPrevious()
            }
        )
        Spacer(modifier = Modifier.width(16.dp))
                                                        PlayPauseButton(progressGate = progressGate, boxSize = 46.dp)
        Spacer(modifier = Modifier.width(16.dp))
        TransportIconButton(
            iconRes = R.drawable.ic_nowplaying_fforward,
            contentDescription = "Next",
            enabled = progressGate,
            boxSize = 32.dp,
            onClick = {
                Vibrator.click(context)
                MediaController.userSkipNext()
            }
        )
        Spacer(modifier = Modifier.width(16.dp))
        RepeatToggleButton(
            repeatModeLambda = { repeatModeState.intValue },
            onRepeatChanged = { repeatModeState.intValue = it },
            buttonSize = 34.dp
        )

        // --- 中：封面 + 标题/歌手 ---
        Spacer(modifier = Modifier.width(16.dp))
        YosWrapper {
            ShadowImageWithCache(
                dataLambda = { MediaViewModelObject.bitmap.value },
                contentDescription = null,
                                                                modifier = Modifier.size(40.dp)
                                                                    .graphicsLayer {
                                                                        alpha =
                                                                            if (coverSuppressed()) 0f else 1f
                                                                    }
                                                                    .onGloballyPositioned {
                                                                        coverCoordsOnChanged(it)
                                                                    },
                                                                cornerRadius = 4.dp,
                shadowAlpha = 0f,
                imageQuality = ImageQuality.LOW
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center
        ) {
            // 自适应内容色（迷你条收起态由 MainActivity provide）：null 回退主题色
            val adaptiveMiniColor =
                LocalGlassContentColor.current ?: (Color.Black withNight Color.White)
            val adaptiveSubColor =
                LocalGlassContentColor.current?.copy(alpha = 0.6f) ?: subTitleColor
            Text(
                text = MediaController.musicPlaying.value?.title ?: defaultTitle,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                lineHeight = 16.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = adaptiveMiniColor
            )
            Text(
                text = MediaController.musicPlaying.value?.artists ?: "",
                fontWeight = FontWeight.Normal,
                fontSize = 12.sp,
                lineHeight = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = adaptiveSubColor
            )
        }
        Spacer(modifier = Modifier.width(10.dp))

        // --- 右：歌词 + 播放列表（与 PlayPause 同尺寸 34dp、间距 10dp） ---
        TransportIconButton(
            iconRes = R.drawable.ic_nowplaying_lyrics,
            contentDescription = "Lyrics",
            enabled = progressGate,
            boxSize = 34.dp,
            onClick = onLyrics
        )
        Spacer(modifier = Modifier.width(10.dp))
        TransportIconButton(
            iconRes = R.drawable.ic_nowplaying_queue,
            contentDescription = "Queue",
            enabled = progressGate,
            boxSize = 34.dp,
            onClick = onPlaylist
        )
    }
}

/**
 * 播放壳是否正处于运动中（拖拽中，或展开/收起动画的 Job 还活着）。
 *
 * 写成文件级私有函数而不是 composable 内的局部 fun：局部 fun 一旦捕获外部变量，每次外层重组
 * 都会新建一个包装对象，而这个作用域在动画期间是逐帧重组的——正好是要优化的那条路径。
 *
 * 两个运动信号来自动画生命周期而不是延时/阈值判定：运动 Job 为 null 是"落定"的唯一可靠
 * 信号，用 progress==1 会因 anchor 漂移永远等不到（见 shellRadius 的同款注释）。
 */
private fun shellInMotion(
    probe: GlassProbe,
    dragActive: Boolean,
    motionJobAlive: Boolean,
): Boolean = probe.dropDecorationsInMotion && (dragActive || motionJobAlive)
