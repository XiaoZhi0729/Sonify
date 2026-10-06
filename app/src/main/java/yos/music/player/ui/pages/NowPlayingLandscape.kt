@file:Suppress("DEPRECATION")

package yos.music.player.ui.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.EaseOutQuart
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.MutableLongState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blankj.utilcode.util.TimeUtils
import com.google.accompanist.insets.navigationBarsPadding
import com.google.accompanist.insets.statusBarsPadding
import yos.music.player.code.utils.lrc.LyricEntry
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.libraries.artistsName
import yos.music.player.data.libraries.defaultArtistsName
import yos.music.player.data.libraries.defaultTitle
import yos.music.player.data.models.MainViewModel
import yos.music.player.data.models.MediaViewModel
import yos.music.player.ui.pages.NowPlayingPage.Album
import yos.music.player.ui.pages.NowPlayingPage.Lyric
import yos.music.player.ui.pages.NowPlayingPage.PlayingList
import yos.music.player.ui.widgets.YosLyricScrollState
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.basic.ShadowImageWithCache
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.effects.overlayEffect

/**
 * 播放页横屏适配入口：手机与平板是两套结构，在函数内按 smallestScreenWidthDp 分流。
 * - 平板（>= 600dp）：左媒体栏 + 右歌词/队列栏分栏（见下）
 * - 手机（< 600dp）：封面居左 + 竖排控件栈居右（见 [NowPlayingLandscapePhone]）
 *
 * 平板横屏：
 * - Album（无歌词）：封面+标题+控件 居中一列
 * - Lyric：左列同上（左对齐），右栏歌词
 * - PlayingList：左列同上，右栏播放队列
 * - 底部悬浮行：输出设备（左）、歌词/队列入口（右）；翻译切换（右上）
 *
 * 分栏与尺寸（以正确布局的真机截图为准，1120x800dp 横屏平板）：
 * - 左媒体栏 45%（504dp），右歌词/队列栏 57%（638.4dp，自屏幕右缘起算）
 * - 封面视觉正方形 = 45% 屏高，控件带 = 视觉封面 + 120dp
 * - 传输键 52/52.5dp、键间距 30dp（竖屏仍为 61/58.5dp、43dp）
 * - 内容区上 96dp / 下 44dp：下方留白不能再大，否则音量行会被 Column 挤成 0 高度
 *
 * 实现要点（避免切换时"重载"感）：
 * - 左列媒体是常驻单一实例，"居中 ↔ 左对齐"仅对容器 start padding 做动画
 * - 右侧歌词/队列面板用 AnimatedVisibility，从屏幕外右侧以 ~365ms 滑入 + 淡入，
 *   离开时反向滑出 + 淡出；面板隐藏时会离开组合，歌词滚动位置由常驻层传入的
 *   YosLyricScrollState 保留，重新进入时无动画定位到当前行，不会从顶部重滚
 * - 控件在横屏下常显（由 NowPlaying 的触摸超时逻辑保证）
 */
@Composable
internal fun NowPlayingLandscape(
    mainViewModel: MainViewModel,
    mediaViewModel: MediaViewModel,
    isPlayingStatusLambda: () -> Boolean,
    isPlayingOnChanged: (Boolean) -> Unit,
    nowPageLambda: () -> String,
    showControl: MutableState<Boolean>,
    lastClickTime: MutableLongState,
    translation: MutableState<Boolean>,
    lrcEntries: () -> List<LyricEntry>,
    thisMusicPlayingLambda: () -> YosMediaItem?,
    shuffleModeEnabled: MutableState<Boolean>,
    repeatMode: MutableIntState,
    onPrevious: () -> Unit,
    onStatus: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onLyrics: () -> Unit,
    onPlaylist: () -> Unit,
    onSlider: () -> Unit,
    onWhile: suspend () -> Unit,
    lyricState: YosLyricScrollState,
    onControlGesture: (Boolean) -> Unit = {},
    // 外壳封面 morph：上报横屏全屏封面节点的 LayoutCoordinates。
    // 横屏左列封面常驻（Lyric/PlayingList 页也在左列显示同一封面），
    // 与竖屏不同，morph 在所有子页面均可触发。
    albumCoverCoordsOnChanged: (LayoutCoordinates) -> Unit = {},
    // 外壳封面 morph 进行中时为 true：本节点停止绘制，
    // 避免与外壳单封面层同时画出两张图。
    albumCoverSuppressed: () -> Boolean = { false },
    // 歌手名点击 → 艺人主页（导航与收回由外壳回调负责）
    onOpenArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current

    // 手机横屏与平板横屏是两套结构，不是同一套的参数缩放：
    // 手机横屏只有 ~853x394dp，分栏（媒体栏 + 歌词/队列栏）放不下，
    // 改走「封面居左 + 竖排控件栈居右」。用 smallestScreenWidthDp 判定，
    // 因为它与方向无关；screenWidthDp 在横屏时手机也会超过 600。
    if (configuration.smallestScreenWidthDp < 600) {
        NowPlayingLandscapePhone(
            mainViewModel = mainViewModel,
            mediaViewModel = mediaViewModel,
            isPlayingStatusLambda = isPlayingStatusLambda,
            isPlayingOnChanged = isPlayingOnChanged,
            nowPageLambda = nowPageLambda,
            showControl = showControl,
            lastClickTime = lastClickTime,
            translation = translation,
            lrcEntries = lrcEntries,
            thisMusicPlayingLambda = thisMusicPlayingLambda,
            shuffleModeEnabled = shuffleModeEnabled,
            repeatMode = repeatMode,
            onPrevious = onPrevious,
            onStatus = onStatus,
            onNext = onNext,
            onSeek = onSeek,
            onLyrics = onLyrics,
            onPlaylist = onPlaylist,
            onSlider = onSlider,
            onWhile = onWhile,
            lyricState = lyricState,
            onControlGesture = onControlGesture,
            albumCoverCoordsOnChanged = albumCoverCoordsOnChanged,
            albumCoverSuppressed = albumCoverSuppressed,
            onOpenArtist = onOpenArtist
        )
        return
    }

    Box(Modifier.fillMaxSize()) {

        // 主内容区（顶部给翻译按钮留白）。
        // 底部留白从 84dp 收到 44dp：媒体列在音量行之前的内容就接近 620dp，
        // 音量行（26.5dp）+ PlayerControl 底部 15dp 会被 Column 挤到 0 高度而“静默消失”
        // （真机实测：音量条区域零墨迹，不是被浅色背景吃掉）。
        // 收小后内容区 660dp ≥ 整列 658dp，音量行拿回完整高度；
        // 底部悬浮行在另一个 Box 里贴底对齐，与音量行在 x 上不重叠。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 96.dp, bottom = 44.dp)
        ) {
            val paneWidth = maxWidth

            val configuration = LocalConfiguration.current
            val screenHeight = configuration.screenHeightDp.dp

            // 分栏比例（以正确布局的真机截图为准，1120x800dp 横屏平板）：
            // - 左媒体栏 45%（504dp）：控件列中心 252dp
            // - 右歌词/队列栏 57%（638.4dp，自屏幕右缘起算）：歌词文字左缘 510.9dp
            //   （原 60% 会让歌词文字落在 477.3dp，比目标偏左 33dp）
            // 两栏在视觉上错开，不会互相压字。
            val mediaPaneWidth = paneWidth * 0.45f
            val rightPaneFraction = 0.57f

            // 封面呼吸内缩：播放态 7dp、暂停态 42dp。
            // 横屏取 42dp（竖屏为 34dp）：参考图实测「封面下缘 → 歌名墨迹上缘」= 57.7dp，
            // 等于 内缩 + 标题顶垫 12dp + 行首留白 ≈ 4dp，只有 42dp 内缩能对上。
            val breathInsetMax = 42.dp

            // 视觉正方形：约 45% 屏高（参考图 362dp），并限制不超过左媒体栏可用宽度。
            // 注意这里约束的是「看得见的封面」，不是布局占位——占位还要留出两侧呼吸内缩，
            // 否则暂停态会内缩 84dp（原写法实测只剩 292dp，明显小于参考图）。
            val coverVisual = minOf(screenHeight * 0.45f, mediaPaneWidth - 120.dp)
                .coerceAtLeast(120.dp)
            // 布局占位 = 视觉正方形 + 两侧最大内缩：呼吸只改视觉尺寸，不推动下方标题与控件。
            val coverSize = coverVisual + breathInsetMax * 2

            // 标题行/控件行与封面同轴的宽度：进度条比视觉封面左右各宽 60dp
            // （参考图：控件带 482dp / 视觉封面 362dp）
            val contentWidth = coverVisual + 120.dp

            val page = nowPageLambda()
            val paneOpen = page != Album

            // 左列媒体：Album 在整块 pane 内居中（保持原行为）；
            // 右栏打开后，再以 contentWidth 为基准在左媒体栏内居中。
            val mediaStartPadding by animateDpAsState(
                targetValue = if (paneOpen) {
                    ((mediaPaneWidth - contentWidth) / 2).coerceAtLeast(0.dp)
                } else {
                    ((paneWidth - contentWidth) / 2).coerceAtLeast(0.dp)
                },
                animationSpec = tween(durationMillis = 300),
                label = "mediaStartPadding"
            )


            // 左列媒体（常驻实例：封面不重载、进度轮询不重启）
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(start = mediaStartPadding)
            ) {
                LandscapeMediaColumn(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(contentWidth),
                    coverSize = coverSize,
                    breathInsetMax = breathInsetMax,
                    contentWidth = contentWidth,
                    isPlayingStatusLambda = isPlayingStatusLambda,
                    isPlayingOnChanged = isPlayingOnChanged,
                    thisMusicPlayingLambda = thisMusicPlayingLambda,
                    shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                    onShuffleChanged = { shuffleModeEnabled.value = it },
                    repeatModeLambda = { repeatMode.intValue },
                    onRepeatChanged = { repeatMode.intValue = it },
                    onPrevious = onPrevious,
                    onStatus = onStatus,
                    onNext = onNext,
                    onSeek = onSeek,
                    onSlider = onSlider,
                    onWhile = onWhile,
                    onControlGesture = onControlGesture,
                    albumCoverCoordsOnChanged = albumCoverCoordsOnChanged,
                    albumCoverSuppressed = albumCoverSuppressed,
                    onOpenArtist = onOpenArtist
                )
            }

            val previousPage = remember { mutableStateOf(page) }
            val paneSlideIn = slideInHorizontally(
                animationSpec = tween(durationMillis = 365, easing = FastOutSlowInEasing),
                initialOffsetX = { fullWidth -> fullWidth }
            ) + fadeIn(animationSpec = tween(250))
            val paneSlideOut = slideOutHorizontally(
                animationSpec = tween(durationMillis = 274, easing = FastOutSlowInEasing),
                targetOffsetX = { fullWidth -> fullWidth }
            ) + fadeOut(animationSpec = tween(205))
            val paneFadeIn = fadeIn(animationSpec = tween(250))
            val paneFadeOut = fadeOut(animationSpec = tween(205))

            // 固定右栏尺寸，内容只在 X 轴平移；不使用 AnimatedContent 的尺寸变换，
            // 因此不会出现从右下向左上的斜向/缩放运动。
            // 栏宽取 57%（638.4dp），滑入位移比 50%（560dp）时多 14%，
            // 故把 enter/exit 时长同比放大（320→365 / 240→274），保持原来的行进速度感。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .fillMaxWidth(rightPaneFraction)
                    // 媒体列为了救回音量行把内容区下沿从 716dp 扩到 756dp，
                    // 但歌词/队列不该跟着变高（否则整块歌词下移 20dp）：
                    // 这里把右栏的下沿钉回原来的 716dp。
                    .padding(bottom = 40.dp)
            ) {
                // 从 Album 打开歌词时水平滑入；从队列切到歌词时只淡入淡出。
                AnimatedVisibility(
                    visible = page == Lyric,
                    enter = if (previousPage.value == Album) paneSlideIn else paneFadeIn,
                    exit = if (page == PlayingList) paneFadeOut else paneSlideOut,
                    modifier = Modifier.fillMaxSize()
                ) {
                    Lyric(
                        lyricState = lyricState,
                        lrcEntries = lrcEntries,
                        weightLambda = { false },
                        translationLambda = { translation.value },
                        mainViewModel = mainViewModel,
                        mediaViewModel = mediaViewModel,
                        onBackClick = {
                            showControl.value = true
                            lastClickTime.longValue = TimeUtils.getNowMills()
                        },
                        // 参考图：歌词区（含无歌词占位文字块）中心在屏顶起 438.6dp，
                        // 即 pane 中心之下约 32dp；占位文字块在歌词区内垂直居中，
                        // 故顶部垫高取 32dp（原 8dp 会让歌词整体偏高 24dp）。
                        topSpacerHeight = 32.dp,
                        topSpacerWithStatusBar = false,
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // 从 Album 打开队列时水平滑入；从歌词切到队列时只淡入淡出。
                AnimatedVisibility(
                    visible = page == PlayingList,
                    enter = if (previousPage.value == Album) paneSlideIn else paneFadeIn,
                    exit = if (page == Lyric) paneFadeOut else paneSlideOut,
                    modifier = Modifier.fillMaxSize()
                ) {
                    PlayingList(
                        shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                        shuffleModeOnChanged = { shuffleModeEnabled.value = it },
                        repeatModeLambda = { repeatMode.intValue },
                        repeatModeOnChanged = { repeatMode.intValue = it },
                        thisMusicPlayingLambda = thisMusicPlayingLambda,
                        heightFraction = 1f,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            // 在本次状态切换的 transition 参数读取完后再记录当前页，保留上一次页面用于下一次判断。
            SideEffect { previousPage.value = page }

        }

        // 翻译切换（右上角）
        Row(
            Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 4.dp, end = 24.dp)
        ) {
            TranslationToggleButton(
                translation = translation,
                enabled = true
            ) {
                Vibrator.click(context)
                translation.value = !translation.value
                showControl.value = true
                lastClickTime.longValue = TimeUtils.getNowMills()
                SettingsLibrary.NowPlayingTranslation = translation.value
            }
        }

        // 底部悬浮行：输出设备（左）& 歌词/队列入口（右）
        Row(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .alpha(0.4f)
                .navigationBarsPadding()
                .padding(start = 20.dp, end = 20.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AirPlay(fill = false)

            Spacer(Modifier.weight(1f))

            // 两个入口各自 56dp 定宽槽位、紧邻排布：参考图实测两个图标中心相距 55.5dp
            // （歌词 1016.5dp / 队列 1072.0dp），即槽位之间没有额外间隔。
            LyricsEntryButton(
                modifier = Modifier.width(56.dp),
                onLyrics = onLyrics,
                nowPage = nowPageLambda
            )

            QueueEntryButton(
                modifier = Modifier.width(56.dp),
                onPlaylist = onPlaylist,
                nowPage = nowPageLambda
            )
        }
    }
}

/**
 * 横屏媒体列（平板）：封面 + 标题 + 控件（进度/时间/传输/音量），固定宽度、左对齐。
 * 所在容器的 start padding 由 [NowPlayingLandscape] 动画控制（居中 ↔ 左对齐）。
 * coverSize 是布局占位，看得见的正方形 = coverSize - 2 x 当前呼吸内缩。
 */
@Composable
private fun LandscapeMediaColumn(
    modifier: Modifier = Modifier,
    coverSize: Dp,
    breathInsetMax: Dp,
    contentWidth: Dp,
    isPlayingStatusLambda: () -> Boolean,
    isPlayingOnChanged: (Boolean) -> Unit,
    thisMusicPlayingLambda: () -> YosMediaItem?,
    shuffleModeEnabledLambda: () -> Boolean,
    onShuffleChanged: (Boolean) -> Unit,
    repeatModeLambda: () -> Int,
    onRepeatChanged: (Int) -> Unit,
    onPrevious: () -> Unit,
    onStatus: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onSlider: () -> Unit,
    onWhile: suspend () -> Unit,
    onControlGesture: (Boolean) -> Unit = {},
    // 外壳封面 morph：上报横屏封面视觉矩形（内层正方形 Box）的 LayoutCoordinates。
    // 与竖屏结构对齐——外层 Box 承载呼吸 padding，内层 Box.fillMaxWidth().aspectRatio(1f)
    // 是 morph 终点的实际视觉正方形，交接时逐像素吻合。
    albumCoverCoordsOnChanged: (LayoutCoordinates) -> Unit = {},
    // 外壳封面 morph 进行中时为 true：本节点在图层面隐藏，由外壳单封面层接管。
    albumCoverSuppressed: () -> Boolean = { false },
    // 歌手名点击 → 艺人主页
    onOpenArtist: (String) -> Unit = {}
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 封面（保留播放/暂停呼吸缩放）
        LandscapeAlbumCover(
            coverSize = coverSize,
            breathInsetMax = breathInsetMax,
            isPlayingStatusLambda = isPlayingStatusLambda,
            thisMusicPlayingLambda = thisMusicPlayingLambda,
            albumCoverCoordsOnChanged = albumCoverCoordsOnChanged,
            albumCoverSuppressed = albumCoverSuppressed
        )

        // 标题/歌手 + 收藏等（与进度条左右缘对齐：补偿 PlayerControl 内部 25dp padding）
        LandscapeTitleBlock(
            modifier = Modifier
                .padding(top = 12.dp)
                .width(contentWidth)
                .padding(horizontal = 25.dp),
            thisMusicPlayingLambda = thisMusicPlayingLambda,
            onOpenArtist = onOpenArtist
        )

        // 进度/时间/传输（五键 shuffle+prev/play/next+repeat）/音量，定高不扩展
        // 横屏传输键比竖屏小一档（参考图实测：prev/next 52dp、play 52.5dp、键间距 30dp，
        // 三键中心相距 82.5dp；竖屏的 61/58.5/43 会给出 102.75dp，明显偏大）
        PlayerControl(
            isPlayingLambda = isPlayingStatusLambda,
            isPlayingOnChanged = isPlayingOnChanged,
            onPrevious = onPrevious,
            onStatus = onStatus,
            onNext = onNext,
            onSeek = onSeek,
            onSlider = onSlider,
            shuffleModeEnabledLambda = shuffleModeEnabledLambda,
            onShuffleChanged = onShuffleChanged,
            repeatModeLambda = repeatModeLambda,
            onRepeatChanged = onRepeatChanged,
            transportExpands = false,
            showShuffleRepeat = true,
            showBottomRow = false,
            transportButtonSize = 52.dp,
            playButtonSize = 52.5.dp,
            transportSpacing = 30.dp,
            modifier = Modifier
                .padding(top = 6.dp)
                .width(contentWidth),
            onWhile = onWhile,
            onControlGesture = onControlGesture
        )
    }
}

/**
 * 横屏封面（手机/平板共用）：保留播放/暂停呼吸缩放。
 *
 * coverSize 是「布局占位」，看得见的正方形 = coverSize - 2 x 当前呼吸内缩；
 * 内缩播放态 breathInsetMin（默认 7dp）、暂停态 breathInsetMax。占位按最大内缩
 * 预留，因此呼吸只改变视觉尺寸，不会推动下方标题与控件（两处调用点若把内缩算错，
 * 就会出现「额定尺寸对得上、看得见的小一圈」——平板横屏曾经就是这样）。
 *
 * 内层 fillMaxWidth().aspectRatio(1f) 是 morph 终点的实际视觉正方形，
 * 坐标只上报这一层，与竖屏 Album 结构逐像素吻合；ShadowImageWithCache 默认
 * cornerRadius=8.dp，与 morphCornerEndDp=8f 一致。
 */
@Composable
private fun LandscapeAlbumCover(
    coverSize: Dp,
    breathInsetMax: Dp,
    // 播放态的最小内缩：手机横屏用它把「看得见的封面」钉在额定尺寸附近，
    // 平板与旧行为保持 7dp。
    breathInsetMin: Dp = 7.dp,
    isPlayingStatusLambda: () -> Boolean,
    thisMusicPlayingLambda: () -> YosMediaItem?,
    topPadding: Dp = 8.dp,
    albumCoverCoordsOnChanged: (LayoutCoordinates) -> Unit = {},
    albumCoverSuppressed: () -> Boolean = { false }
) {
    YosWrapper {
        val springSpec: AnimationSpec<Float> = remember("LandscapeAlbum_springSpec") {
            SpringSpec(stiffness = 300f, dampingRatio = 1f, visibilityThreshold = 0.001f)
        }

        val tweenSpec: AnimationSpec<Float> = remember("LandscapeAlbum_tweenSpec") {
            TweenSpec(durationMillis = 350, easing = EaseOutQuart)
        }

        val scale = animateFloatAsState(
            targetValue = if (isPlayingStatusLambda()) 0f else 1f,
            animationSpec = if (isPlayingStatusLambda()) springSpec else tweenSpec,
            visibilityThreshold = 0.001f
        )

        // 播放态收到 breathInsetMin，暂停态线性放大到 breathInsetMax
        val dp = breathInsetMin + (breathInsetMax - breathInsetMin) * scale.value

        Box(
            modifier = Modifier.padding(top = topPadding),
            contentAlignment = Alignment.BottomCenter
        ) {
            // 外层：size(coverSize) 为布局占位；padding 为播放/暂停呼吸动效
            Box(
                modifier = Modifier
                    .size(coverSize)
                    .padding(start = dp, end = dp, bottom = dp),
                contentAlignment = Alignment.BottomCenter
            ) {
                // 内层：fillMaxWidth().aspectRatio(1f) 为实际视觉正方形，
                // onGloballyPositioned 上报给 CoverMorphGeometry.album
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .onGloballyPositioned { albumCoverCoordsOnChanged(it) },
                    contentAlignment = Alignment.Center
                ) {
                    ShadowImageWithCache(
                        dataLambda = { thisMusicPlayingLambda()?.thumb },
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                                // morph 期间由外壳单封面层接管，本节点在图层面隐藏；
                                // 读 State 只失效图层，不触发重组、不改布局占位。
                                alpha = if (albumCoverSuppressed()) 0f else 1f
                            },
                        imageQuality = ImageQuality.RAW,
                        shadowOverlay = true
                    )
                }
            }
        }
    }
}

/**
 * 横屏标题块（手机/平板共用）：歌名 + 歌手 + 收藏/更多，与进度条左右缘对齐。
 * 横向 padding 由调用方给：控件带用 25dp（补偿 PlayerControl 内部 padding），
 * 手机横屏沿用竖屏的 32dp（参考图里标题比轨道再内收约 7dp）。
 */
@Composable
private fun LandscapeTitleBlock(
    modifier: Modifier = Modifier,
    thisMusicPlayingLambda: () -> YosMediaItem?,
    // 歌手名点击 → 艺人主页（导航与收回由外壳回调负责）
    onOpenArtist: (String) -> Unit = {}
) {
    YosWrapper {
        AnimatedContent(
            targetState = thisMusicPlayingLambda(),
            transitionSpec = {
                fadeIn() togetherWith fadeOut()
            },
            modifier = modifier
        ) {
            Row(
                Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .padding(end = 15.dp)
                ) {
                    Text(
                        text = it?.title ?: defaultTitle,
                        fontSize = 19.5.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = it?.artistsName ?: defaultArtistsName,
                        fontSize = 18.5.sp,
                        modifier = Modifier
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                onOpenArtist(it?.artistsName.orEmpty())
                            }
                            .overlayEffect(),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = Color.White.copy(alpha = 0.35f)
                    )
                }

                YosWrapper {
                    ActionButtonsRow(thisMusicPlayingLambda)
                }
            }
        }
    }
}

/**
 * 播放页横屏（手机）适配布局：封面居左 + 竖排控件栈居右。
 *
 * 手机横屏只有约 853x394dp，放不下平板那套「左媒体栏 + 右歌词/队列栏」分栏，
 * 因此按参考图改成：封面在左侧垂直居中，右侧一列沿用竖屏顺序
 * （歌名/歌手 → 进度 → 时间 → 传输 → 音量）。打开歌词或队列时整块控件区被
 * 替换，封面与标题不动，底部行始终留在列尾——切换时不产生整页重排。
 *
 * 参考图实测（Album 态与歌词态两张互相校验）：
 * - 封面左缘 79.8dp；额定尺寸按屏高 0.78x 取（在整窗口垂直居中，不受右列高度封顶，
 *   实见 ≈300dp、占窗高约 76%；旧写法实见仅 ≈0.57x屏高，用户反馈“看不出大”）
 * - 控件带（进度条实占）410.6..801.2，中心 605.9
 * - 传输只有三键（手机横屏不放随机/循环）：prev/play/next 中心
 *   490.0 / 605.6 / 721.0 → 按钮沿用竖屏 61 / 58.5dp，键间距取 56dp
 * - 底部行中心 435.4 / 606.4 / 777.1，与「56dp 槽位 + SpaceBetween 铺在控件带内」吻合
 */
@Composable
private fun NowPlayingLandscapePhone(
    mainViewModel: MainViewModel,
    mediaViewModel: MediaViewModel,
    isPlayingStatusLambda: () -> Boolean,
    isPlayingOnChanged: (Boolean) -> Unit,
    nowPageLambda: () -> String,
    showControl: MutableState<Boolean>,
    lastClickTime: MutableLongState,
    translation: MutableState<Boolean>,
    lrcEntries: () -> List<LyricEntry>,
    thisMusicPlayingLambda: () -> YosMediaItem?,
    shuffleModeEnabled: MutableState<Boolean>,
    repeatMode: MutableIntState,
    onPrevious: () -> Unit,
    onStatus: (Boolean) -> Unit,
    onNext: () -> Unit,
    onSeek: (Float) -> Unit,
    onLyrics: () -> Unit,
    onPlaylist: () -> Unit,
    onSlider: () -> Unit,
    onWhile: suspend () -> Unit,
    lyricState: YosLyricScrollState,
    onControlGesture: (Boolean) -> Unit,
    albumCoverCoordsOnChanged: (LayoutCoordinates) -> Unit,
    albumCoverSuppressed: () -> Boolean,
    onOpenArtist: (String) -> Unit = {}
) {
    val context = LocalContext.current

    Box(Modifier.fillMaxSize()) {
        // 顶部 56dp / 底部 18dp：内容区整体比原来下移 10dp（用户：除最顶部小白条外，
        // 左侧封面与右侧控件全部向下移动 10dp）。上沿 +10、下沿 -10 使居中基准整体下移 10dp，
        // 内容区高度不变（仍 320dp），封面/右列的内部布局与相对间距完全不变，只整体平移。
        // 顶部小白条（NowPlayingHandle）在 NowPlaying.kt 里单独绘制、不受此 padding 影响，故留在原位。
        // 底部仍先吃 navigationBarsPadding（真实手势条 inset），再垫 18dp。
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .navigationBarsPadding()
                .padding(top = 56.dp, bottom = 18.dp)
        ) {
            // 封面尺寸按用户参考图（红框）标定，且区分两态：
            // - 播放态（开始播放后放大）看得见的正方形 ≈305dp，填满红框（x53→374、y50→351）；
            // - 暂停态 ≈228dp。呼吸只改视觉尺寸、不改占位，因此不推动右列。
            // 占位 coverSize = 播放态视觉 + 2 x 播放内缩；暂停内缩 = 播放内缩 + (播放-暂停)/2。
            val playInset = 4.dp
            val coverPlayVisible = 305.dp
            val coverPauseVisible = 228.dp
            val breathInsetMax = playInset + (coverPlayVisible - coverPauseVisible) / 2f
            val coverSize = coverPlayVisible + playInset * 2f

            val page = nowPageLambda()
            val paneFadeIn = fadeIn(animationSpec = tween(durationMillis = 260))
            val paneFadeOut = fadeOut(animationSpec = tween(durationMillis = 200))

            // 封面：左缘按红框取 start=49dp（播放态内缩 4dp 后视觉左缘≈53dp），
            // 在内容区垂直居中；不再作为 Row 子项，避免右列高度反过来封顶封面。
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 49.dp)
            ) {
                LandscapeAlbumCover(
                    coverSize = coverSize,
                    breathInsetMax = breathInsetMax,
                    breathInsetMin = playInset,
                    topPadding = 0.dp,
                    isPlayingStatusLambda = isPlayingStatusLambda,
                    thisMusicPlayingLambda = thisMusicPlayingLambda,
                    albumCoverCoordsOnChanged = albumCoverCoordsOnChanged,
                    albumCoverSuppressed = albumCoverSuppressed
                )
            }

            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 右列起点固定在参考图位置（标题左缘≈427dp）：封面 49dp + 占位 313dp + 空档 34dp
                Spacer(Modifier.width(49.dp + coverSize + 34.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .padding(end = 27.dp)
                ) {
                    LandscapeTitleBlock(
                        // 歌词页：把标题块与下方区域的间距从 20dp 收到 4dp，让歌词区向上
                        // 延伸到歌手名那一行（用户：区域上方再延伸一点、到歌手名那块儿）；
                        // 其他页（Album/队列）保持 20dp，不动已调好的控件间距。
                        modifier = Modifier
                            .padding(bottom = if (page == Lyric) 4.dp else 20.dp)
                            .padding(horizontal = 32.dp),
                        thisMusicPlayingLambda = thisMusicPlayingLambda,
                        onOpenArtist = onOpenArtist
                    )

                    // 控件区 / 歌词 / 队列。不用水平滑入：手机横屏这块区域
                    // 本来就在屏内，滑进来反而像整页在动。
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                    ) {
                        val controlsAlpha by animateFloatAsState(
                            targetValue = if (page == Album) 1f else 0f,
                            // snap：控件（含音量条/进度条）走 overlayEffect 的 Plus 提亮，
                            // 若让 alpha 经 tween 中间态会被渲染进离屏层→Plus 失效、
                            // 途中偏暗、结束才变亮。直接到位即无该过渡。
                            animationSpec = snap(),
                            label = "PhoneLandscapeControlsAlpha"
                        )

                        // 控件常驻组合：进度轮询与滑块位置都挂在它身上。
                        // 这里用 alpha 收起而不是 AnimatedVisibility：后者的 content
                        // 退场后会离开组合，sliderPosition（普通 remember）随之丢失，
                        // 切回 Album 会看到进度条从 0 重新长到当前进度。
                        PlayerControl(
                            isPlayingLambda = isPlayingStatusLambda,
                            isPlayingOnChanged = isPlayingOnChanged,
                            onPrevious = onPrevious,
                            onStatus = onStatus,
                            onNext = onNext,
                            onSeek = onSeek,
                            onSlider = onSlider,
                            shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                            onShuffleChanged = { shuffleModeEnabled.value = it },
                            repeatModeLambda = { repeatMode.intValue },
                            onRepeatChanged = { repeatMode.intValue = it },
                            transportExpands = true,
                            showShuffleRepeat = false,
                            showBottomRow = false,
                            transportSpacing = 56.dp,
                            modifier = Modifier
                                .fillMaxSize()
                                .alpha(controlsAlpha),
                            onWhile = onWhile,
                            onControlGesture = onControlGesture
                        )

                        if (page != Album) {
                            // 收起期间挡住控件触摸：看不见但还能点的播放键不能留。
                            // 放在歌词/队列之前，上层面板仍然吃得到自己的手势。
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

                        androidx.compose.animation.AnimatedVisibility(
                            visible = page == Lyric,
                            enter = paneFadeIn,
                            exit = paneFadeOut,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            Box(modifier = Modifier.fillMaxSize()) {
                                Lyric(
                                    lyricState = lyricState,
                                    lrcEntries = lrcEntries,
                                    weightLambda = { false },
                                    translationLambda = { translation.value },
                                    mainViewModel = mainViewModel,
                                    mediaViewModel = mediaViewModel,
                                    onBackClick = {
                                        showControl.value = true
                                        lastClickTime.longValue = TimeUtils.getNowMills()
                                    },
                                    topSpacerHeight = 0.dp,
                                    topSpacerWithStatusBar = false,
                                    modifier = Modifier.fillMaxSize()
                                )

                                // 翻译切换放在歌词区右上角：手机横屏的标题行右端
                                // 已经住着收藏★/更多⋯，屏幕右上角再放一个 48dp 触摸盒
                                // 会压到 ⋯ 的中心（这个碰撞在竖屏上真实发生过一次）。
                                Row(
                                    Modifier
                                        .align(Alignment.TopEnd)
                                        .padding(top = 4.dp)
                                ) {
                                    TranslationToggleButton(
                                        translation = translation,
                                        enabled = true
                                    ) {
                                        Vibrator.click(context)
                                        translation.value = !translation.value
                                        showControl.value = true
                                        lastClickTime.longValue = TimeUtils.getNowMills()
                                        SettingsLibrary.NowPlayingTranslation = translation.value
                                    }
                                }
                            }
                        }

                        androidx.compose.animation.AnimatedVisibility(
                            visible = page == PlayingList,
                            enter = paneFadeIn,
                            exit = paneFadeOut,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            PlayingList(
                                shuffleModeEnabledLambda = { shuffleModeEnabled.value },
                                shuffleModeOnChanged = { shuffleModeEnabled.value = it },
                                repeatModeLambda = { repeatMode.intValue },
                                repeatModeOnChanged = { repeatMode.intValue = it },
                                thisMusicPlayingLambda = thisMusicPlayingLambda,
                                heightFraction = 1f,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }

                    // 底部行：歌词入口 / 输出设备 / 队列入口，铺在控件带宽度内
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 25.dp)
                            .alpha(0.4f),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        LyricsEntryButton(
                            modifier = Modifier.width(56.dp),
                            onLyrics = onLyrics,
                            nowPage = nowPageLambda
                        )

                        AirPlay(fill = false)

                        QueueEntryButton(
                            modifier = Modifier.width(56.dp),
                            onPlaylist = onPlaylist,
                            nowPage = nowPageLambda
                        )
                    }
                }
            }
        }
    }
}
