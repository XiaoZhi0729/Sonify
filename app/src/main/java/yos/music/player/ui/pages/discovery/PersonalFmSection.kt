package yos.music.player.ui.pages.discovery

import android.os.Build
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.objects.PersonalFmObject
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.navigation.LocalPageGradient
import yos.music.player.ui.navigation.TrackBackdropGradient
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.LocalTitlePageColor
import yos.music.player.ui.widgets.basic.coverCacheKey
import yos.music.player.ui.widgets.basic.ImageQuality
import yos.music.player.ui.widgets.liquid.glassBlurScale

/**
 * 主页「私人电台」板块。
 *
 * ## 玻璃材质
 * 一个 Pager 同时负责清晰封面和手势；玻璃采样板块私有的「底色 + 同一 Pager」，
 * 不再另铺预模糊图片。玻璃是采样源的兄弟节点，文字在材质之上。
 * 采样范围绝不扩大到整页：LazyColumn 滚动时整页采样会错位并产生自引用。
 *
 * ## 半遮
 * 圆心 X 必须 == 玻璃右缘（[fmCircleCenterX]），圆才恰好一半在玻璃里。
 * 左半被玻璃模糊，右半保持清晰并旋转。
 *
 * ## 旋转
 * 只有「正在播放」那页转；角度由 `withFrameNanos` 30fps 节流写入 state，
 * 只在 `graphicsLayer{}` 里读（项目红线：组合期读动画值 = 每帧重组）。
 */
@Composable
internal fun PersonalFmHomeSection(
    onOpenDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    val songs = PersonalFmObject.songs.value
    val loading = PersonalFmObject.loading.value
    val loggedIn = KugouAccountState.isLoggedIn

    // ensureLoaded() 不在这里：本函数只在 hasData=true 时才被组合，
    // 写在这里会死锁。触发点在 Discovery.kt 页面级 LaunchedEffect。

    Column(modifier.fillMaxWidth().padding(top = 24.dp)) {
        DiscoverySectionHeader(
            title = stringResource(id = R.string.home_personal_fm_title),
            onClick = onOpenDetail
        )

        if (songs.isEmpty()) {
            if (!loading) {
                Text(
                    text = PersonalFmObject.loadError.value
                        ?: stringResource(
                            id = if (loggedIn) R.string.personal_fm_empty
                            else R.string.personal_fm_login_hint
                        ),
                    fontSize = 15.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
                )
            }
            return@Column
        }

        PersonalFmStage(songs = songs)
    }
}

/** 首拉失败后由外层 slot 的错误行负责展示，这里只需给出文案。 */
internal fun fmEmptyText(loggedIn: Boolean, error: String?, empty: String, hint: String): String? =
    when {
        error != null -> null          // 交给 DiscoverySectionSlot 的 error 分支
        !loggedIn -> hint
        else -> empty
    }

/** 旋转帧率上限：30fps 足够，其余帧不写 state、不触发合成。 */
private const val ROTATION_FRAME_MS = 1000f / 30f

/** 每毫秒转多少度（约 18 秒一圈，接近 33⅓ RPM 的观感）。 */
private const val ROTATION_DEG_PER_MS = 0.02f

@Composable
private fun PersonalFmStage(songs: List<KugouNewSong>) {
    val isDark = isFlamingoInDarkMode()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current

    // ★队列长度会变（首拉 5→10→…→50，或「换一批」时 load() 开头会先清空）。
    // Pager 的 pageCount / key / page content 都必须读**同一个、当前**的列表快照；
    // 若各自捕获不同帧的 List，Compose 会用旧页数去索引新列表 →
    // IndexOutOfBoundsException（真机实测：Index 5 out of bounds for length 5）。
    // 用 rememberUpdatedState 给三者一个稳定的读取入口，并让索引一律走 getOrNull。
    val currentSongs by rememberUpdatedState(songs)
    val songCount = currentSongs.size

    // ---------- 播放态 ----------
    val isFmQueue = MediaController.isFmQueue.value
    val playingId = MediaController.musicPlaying.value?.mediaId
    val isPlaying = MediaViewModelObject.isPlaying.value
    // 媒体 ID 与画廊页同构（MusicGalleryPage.kt:450）
    val playingIndex = remember(songs, playingId, isFmQueue) {
        if (!isFmQueue || playingId == null) -1
        else songs.indexOfFirst { "kugou-online-${it.hash.lowercase()}" == playingId }
    }

    val initialPage = remember { PersonalFmObject.homeFocusIndex.coerceIn(0, (songCount - 1).coerceAtLeast(0)) }
    val pagerState = rememberPagerState(
        initialPage = initialPage,
        pageCount = { currentSongs.size }
    )

    /**
     * 用户是否刚做过一次真实的滑动浏览。
     *
     * 只有它置位时，"落位"才被解释成"我想听这一台"。
     * 外部跟随（通知栏切歌）、进页锚定、程序化 animateScrollToPage 都不置位——
     * 否则「自动下一首」会被当成用户意图，反过来再触发一次 prepare（死循环）。
     */
    var userBrowsing by remember { mutableStateOf(false) }

    /**
     * 把焦点落到第 [page] 台：换台 = 换歌。
     *
     * 已在播（含**暂停**）就是这首歌：不打断、不从头重播。
     * 队列用 FM 全量歌曲，与画廊页同构（`MusicGalleryPage.kt:472`）。
     */
    fun focusSongAt(page: Int) {
        val song = currentSongs.getOrNull(page) ?: return
        val item = KugouRepository.toQueueMediaItem(song)
        if (MediaController.musicPlaying.value?.mediaId == item.mediaId) return
        scope.launch(Dispatchers.IO) {
            val queue = PersonalFmObject.songs.value.map { KugouRepository.toQueueMediaItem(it) }
            // 队列内容在 IO 线程可能已变（补货），确保目标曲仍在其中
            if (queue.none { it.mediaId == item.mediaId }) return@launch
            MediaController.prepare(item, queue, fmQueue = true)
        }
    }

    /** 第 [page] 台对应的 mediaId（与画廊页同一套 ID 编码）。 */
    fun mediaIdAt(page: Int): String? =
        currentSongs.getOrNull(page)?.let { KugouRepository.toQueueMediaItem(it).mediaId }

    /**
     * 手势闸门：区分「用户拖」和「程序滑」。
     *
     * `PagerState` 没有暴露"这次滚动是不是用户发起的"，但有一个可靠判据：
     * 程序化的 `animateScrollToPage` **一定**由我们自己发起，且播放器在跟随。
     * 所以统一在调用点用 [programmaticScroll] 打标，凡未打标的 `isScrollInProgress=true`
     * 一律视为用户手势。
     */
    var programmaticScroll by remember { mutableStateOf(false) }

    // 外部切歌（通知栏 / 自动下一首 / 蓝牙）→ 平滑滑到新歌。
    // ★只在"播放中的歌真的换了"时跟随，且用户没在滑。
    //   这里**不再**兼任「浏览后回弹」——滑走就是换台，不回弹。
    val currentPlayingIndex by rememberUpdatedState(playingIndex)
    LaunchedEffect(playingId) {
        val idx = currentPlayingIndex
        if (idx >= 0 && idx != pagerState.currentPage) {
            if (!pagerState.isScrollInProgress) {
                programmaticScroll = true
                try {
                    pagerState.animateScrollToPage(idx)
                } finally {
                    programmaticScroll = false
                }
            }
        }
    }

    // 用户手指开始拖动 → 记一次浏览意图（松手落位后据此换歌）
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.isScrollInProgress }
            .distinctUntilChanged()
            .collect { scrolling ->
                // 只在"按下去开始拖"的上升沿置位，且必须不是我们自己在滑
                if (scrolling && !programmaticScroll) userBrowsing = true
            }
    }

    // ---------- ★滑到哪台，就播哪台 ----------
    // 用户口径：在私人电台里滑走就该换歌；暂停状态下滑走也要换（播新的那首）。
    //
    // 上版的两个 bug 都在这段：
    //  1. 有一条「松手 1.5s 自动滑回正在播放那台」——用户滑走会被硬拉回来；
    //  2. 完全没有"换台 = 换歌"的逻辑——拉回来之后播的还是原来那首。
    // 暂停时尤其狼狈：musicPlaying 还在（只是暂停），playingIndex 仍 >= 0，
    // 于是照样被判定"该回弹到它"。
    //
    // 现在的口径：
    //  · 落位（无拖拽、动画结束、停在整数页）且该页 ≠ 当前曲 → prepare 成该曲；
    //  · **只由真实手势触发**（userBrowsing 闸门），外部跟随、进页锚定都不算翻页意图；
    //  · 若该曲已在播（含暂停）→ 什么都不做，不打断、不从头重播。
    LaunchedEffect(pagerState) {
        snapshotFlow {
            Triple(
                pagerState.isScrollInProgress,
                pagerState.currentPage,
                pagerState.currentPageOffsetFraction
            )
        }
            .distinctUntilChanged()
            .collect { (scrolling, page, offset) ->
                val should = fmShouldFocusOnSettle(
                    userBrowsing = userBrowsing,
                    scrolling = scrolling,
                    offsetFraction = offset,
                    targetMediaId = mediaIdAt(page),
                    playingMediaId = MediaController.musicPlaying.value?.mediaId
                )
                if (!should) return@collect
                userBrowsing = false
                focusSongAt(page)
            }
    }

    // 聚焦位写回单例（滚出 LazyColumn 再回来还能续上）
    LaunchedEffect(pagerState.currentPage) {
        PersonalFmObject.homeFocusIndex = pagerState.currentPage
        PersonalFmObject.ensureQueueAhead(pagerState.currentPage)
    }

    /**
     * 连续位置 `currentPage + currentPageOffsetFraction`。
     *
     * 只给封面的淡出用（[fmFadeAlpha]）。用 `derivedStateOf` 包一层：
     * 绘制期每帧读它，但读到的只是这一个 Float，不会把整页读成"变了"。
     */
    val pagerPosition by remember(pagerState) {
        derivedStateOf { pagerState.currentPage + pagerState.currentPageOffsetFraction }
    }

    // ---------- 旋转时钟 ----------
    val systemAnimated = remember {
        Settings.Global.getFloat(
            context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f
        ) > 0f
    }

    /**
     * 旋转相位，**按台各存一份**。
     *
     * ★这里曾经是"全板块共用一个累积角度、只让正在播放那页读它"，
     * 结果一换台新页就从 0 直接跳到累积值（可能是 137°）再继续转 ——
     * 画面上就是"先跳一下、再开始转"。
     *
     * 现在每台自己计相位，谁被切过去就从**自己的** 0° 平滑起步；
     * 暂停/恢复也不会在别的台上凭空出现一个角度。
     *
     * 键是歌曲 hash（不是 page 下标）：补货/重载会让下标漂移，
     * 但同一首歌的相位应当连续。
     */
    val rotationPhases = remember { androidx.compose.runtime.mutableStateMapOf<String, Float>() }

    /** 当前正在播放那台的 key（没在播 FM 时为 null → 谁都不转）。 */
    val spinningKey = if (playingIndex >= 0) songs.getOrNull(playingIndex)?.hash?.lowercase() else null

    /**
     * 起停缓冲系数（0..1），**时长与音频淡入淡出一致**（200ms）。
     *
     * 用 `animateFloatAsState` 而不是自己算 elapsed：
     * 它天然处理"缓冲期被打断"（比如淡出到一半又点了播放），
     * 自己计时会在这种反转场景里跳变。
     *
     * `label` 里的"音频淡入淡出"是给人看的：改音频时长时这里要一起改。
     */
    val spinRamp by animateFloatAsState(
        targetValue = if (isPlaying) 1f else 0f,
        animationSpec = tween(durationMillis = FM_SPIN_FADE_MS, easing = LinearEasing),
        label = "fmSpinRamp"
    )
    // 时钟协程是长跑的，闭包里必须读"最新值"而不是组合期捕获的那一份
    val currentSpinRamp by rememberUpdatedState(spinRamp)
    val currentSpinningKey by rememberUpdatedState(spinningKey)

    /**
     * 旋转时钟。
     *
     * ★起停都有 200ms 缓冲，**时长与音频淡入淡出一致**（`FadeExo.fadeAnimationDuration`）。
     * 做法是让**角速度**自己走斜坡，而不是让角度插值：
     * 角度插值在"停止"时会倒着转回去，视觉上像倒带；
     * 速度斜坡则是"转速慢慢降下来停住"，和音量淡出是同一种感觉。
     *
     * 时钟**永远在跑**（只要系统动画开着），因为停止也需要缓冲期继续推进；
     * 只有 `spinRamp == 0` 时才真的不写 state。
     */
    LaunchedEffect(systemAnimated) {
        if (!systemAnimated) return@LaunchedEffect
        var last = 0L
        var acc = 0f
        while (true) {
            withFrameNanos { now ->
                if (last != 0L) acc += (now - last) / 1_000_000f
                last = now
                if (acc >= ROTATION_FRAME_MS) {
                    val key = currentSpinningKey
                    val ramp = currentSpinRamp
                    if (key != null && ramp > 0f) {
                        // ★不做 % 360、且按台各自推进（见 fmAdvancePhase 的注释）
                        rotationPhases[key] = fmAdvancePhase(
                            prevDegrees = rotationPhases[key],
                            deltaMs = acc * ramp,          // ← 缓冲乘在**时间**上
                            degreesPerMs = ROTATION_DEG_PER_MS
                        )
                    }
                    acc = 0f
                }
            }
        }
    }

    // ---------- 取色 ----------
    // ★★板块**背后没有任何渐变**（用户两次要求）：
    //   采样源里既没有色带、也没有提取色铺底，只有"页面底色 + 封面 + 玻璃自己那一抹色"。
    //   所以下面这些颜色**只用于玻璃内部**（色渍、歌手名、图标），
    //   一个像素都不许画到玻璃外面去。
    val focusedSong = songs.getOrNull(pagerState.currentPage)
    val gradient = rememberFmGradient(focusedSong?.artworkUrl)
    val smear = remember(gradient, isDark) { smearColorOf(gradient, isDark) }
    val accent = remember(gradient, isDark) { accentColorOf(gradient, isDark) }

    /**
     * 提取色色渍：**只画在玻璃底下那一小块**。
     *
     * 这是"加强玻璃上的颜色"的唯一手段——板块背后要干净，
     * 颜色就必须从玻璃自己这一层出来。做法仍是画进采样源（跟着模糊 + vibrancy），
     * 但几何严格限制在玻璃矩形内，绝不外溢到板块其它位置。
     *
     * 上一版把圆心放在玻璃中心、半径 1.05×玻璃宽，边缘会溢出去一圈淡色；
     * 现在收紧到玻璃矩形内切、并抬高 alpha，让玻璃里的颜色更实、外面更干净。
     */
    val smearBrush = remember(smear, isDark, density) {
        if (smear == null) null else {
            val glassLeft = with(density) { FmRailStart.toPx() }
            val glassTop = with(density) { FmGlassInsetY.toPx() }
            val glassW = with(density) { FmGlassWidth.toPx() }
            val glassH = with(density) { FmGlassHeight.toPx() }
            // 圆心压在玻璃靠左偏上的位置：那里是"半遮"的圆心附近，视觉重心所在
            val center = Offset(glassLeft + glassW * 0.42f, glassTop + glassH * 0.42f)
            // 半径取玻璃短边的一半：色渍正好内切于玻璃，边界自然消失且不外溢
            val radius = minOf(glassW, glassH) * 0.62f
            Brush.radialGradient(
                colors = listOf(
                    // ★比上一版更实（"加强玻璃上的颜色"）
                    smear.copy(alpha = if (isDark) 0.72f else 0.78f),
                    smear.copy(alpha = if (isDark) 0.30f else 0.38f),
                    Color.Transparent
                ),
                center = center,
                radius = radius
            )
        }
    }
    val glassOn = SettingsLibrary.BarBlurEffect

    /**
     * 采样用的 `onDraw`：**先画一层底色，再画内容**。
     *
     * ★★这一层就是"忘挂的那一层"，也是玻璃发虚的直接原因：
     * 封面从焦点列（相对屏幕 ≈120dp）才开始有像素，玻璃却从 20dp 起，
     * 玻璃左侧那 ~100dp 里没有任何内容可采 → 采到全透明 → 叠上表面纱 = 一块白雾。
     *
     * ★为什么必须走 `onDraw` 而不是 `backdropContent` 里的一个 Box：
     * `backdropContent` 会正常上屏，加底色就等于给板块铺一张卡片底（用户红线：无界）。
     * 走 `onDraw` 时库会把 `contentOnlyCapture` 置为 false，上屏退回 `drawContent()`
     * —— 底色**只进录制层**，屏幕上看不到。
     *
     * ★为什么是页面底色而不是接口色：用户明确要求「不要背景取色的渐变」。
     * 底色只负责让玻璃"有东西可采"，不需要有颜色个性；
     * 颜色个性由采样源里那一抹提取色色渍提供。
     *
     * 必须 `remember` 稳定：`onDraw` 是 `rememberLayerBackdrop` 的 key，
     * 每次重组换实例会新建 LayerBackdrop（玻璃瞬间脱层）。
     */
    // 采样层底色必须与真实页面背景一致：开启「封面取色背景」时走 LocalPageGradient
    // （按本板块在窗口内的纵向坐标对齐整屏渐变），关闭或无渐变时回退到页面底色。
    // 全程在绘制期读取，不把渐变放进 remember 键，避免开关/切歌时重建 LayerBackdrop。
    val pageGradient = LocalPageGradient.current
    val pageColor = LocalTitlePageColor.current ?: (if (isDark) Color.Black else Color.White)
    val pageColorState = rememberUpdatedState(pageColor)
    val rootView = LocalView.current
    val backdropRef = remember { arrayOfNulls<LayerBackdrop>(1) }
    val recordedGradientRef = remember { arrayOfNulls<TrackBackdropGradient>(1) }
    val recordedYRef = remember { floatArrayOf(Float.NaN) }

    val stageOnDraw = remember<ContentDrawScope.() -> Unit>(pageGradient) {
        {
            val active = pageGradient?.current()
            recordedGradientRef[0] = active
            if (active != null) {
                val coords = backdropRef[0]?.layerCoordinates
                val rootH = rootView.height.toFloat()
                if (coords != null && coords.isAttached && rootH > 0f) {
                    val yInWindow = coords.positionInWindow().y
                    recordedYRef[0] = yInWindow
                    val brush = Brush.verticalGradient(
                        colorStops = Array(active.stops.size) { active.positions[it] to active.stops[it] },
                        startY = -yInWindow,
                        endY = rootH - yInWindow
                    )
                    drawRect(brush)
                } else {
                    recordedYRef[0] = Float.NaN
                    drawRect(active.average)
                }
            } else {
                recordedYRef[0] = Float.NaN
                drawRect(pageColorState.value)
            }
            drawContent()
        }
    }
    val stageBackdrop = rememberLayerBackdrop(onDraw = stageOnDraw)
    backdropRef[0] = stageBackdrop
    val glassScale = glassBlurScale
    val glassEffects = remember<BackdropEffectScope.() -> Unit>(glassScale) {
        {
            vibrancy()
            blur(FmGlassBaseBlur.toPx() * glassScale)
            if (Build.VERSION.SDK_INT >= 33) lens(6.dp.toPx(), 16.dp.toPx())
        }
    }
    /**
     * 磨砂效果链：只有模糊。
     *
     * 关闭「液态玻璃效果」时**不能丢掉模糊**（用户口径）：磨砂照样要能看出背后是封面，
     * 只是没有折射、没有 vibrancy 提饱和、没有边缘光。
     * 半径用固定值、不乘 `glassBlurScale`——那个倍率属于玻璃控件；
     * 底栏的磨砂分支同样是裸 `blur(12dp)`。
     */
    val frostedEffects = remember<BackdropEffectScope.() -> Unit> {
        { blur(FmFrostedBlur.toPx()) }
    }

    // ---------- 表面色 ----------
    // 玻璃态：极薄白纱，靠采样本身出效果。
    // 磨砂态：更实的表面色（磨砂本来就不透明一些），但仍然能透出 40% 左右。
    val glassSurfaceColor = if (isDark) Color(0xFF1C1C1E).copy(alpha = 0.26f)
    else Color.White.copy(alpha = 0.22f)
    val frostedSurfaceColor = if (isDark) Color(0xFF1C1C1E).copy(alpha = 0.58f)
    else Color.White.copy(alpha = 0.56f)

    val glassSurfaceBrush = remember(glassSurfaceColor) {
        // 上沿略亮、下沿略暗：玻璃的厚度感，两档差很小，绝不能盖过采样
        Brush.verticalGradient(
            0f to glassSurfaceColor.copy(alpha = glassSurfaceColor.alpha * 1.25f),
            1f to glassSurfaceColor
        )
    }
    val frostedSurfaceBrush = remember(frostedSurfaceColor) {
        Brush.verticalGradient(
            0f to frostedSurfaceColor.copy(alpha = frostedSurfaceColor.alpha * 1.08f),
            1f to frostedSurfaceColor
        )
    }
    val glassShape = remember { RoundedCornerShape(FmGlassCorner) }

    val glassSurface = remember<DrawScope.() -> Unit>(glassSurfaceBrush) {
        { drawRect(glassSurfaceBrush) }
    }
    val frostedSurface = remember<DrawScope.() -> Unit>(frostedSurfaceBrush) {
        { drawRect(frostedSurfaceBrush) }
    }

    // ★板块**铺满屏宽**、不加 horizontal padding：
    //   用户要求封面能像其它横向轨道一样从屏幕外进来。
    //   玻璃仍然靠 [FmRailStart] 左缩进对齐标题，这个缩进只作用在
    //   玻璃定位与 Pager 的 contentPadding 上。
    // ★只加 `clipToBounds()` 防止滑出的封面画到相邻板块上；
    //   不能用 `graphicsLayer { clip = true }` —— 那会多建一个离屏合成层，
    //   而玻璃采样要读这一层的内容，等于每帧多录一次。
    val maxWidth = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp.dp
    Box(
        Modifier
            .fillMaxWidth()
            .height(FmStageHeight)
            .clipToBounds()
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction("上一台") {
                        scope.launch {
                            val t = (pagerState.currentPage - 1).coerceAtLeast(0)
                            if (t == pagerState.currentPage) return@launch
                            // 无障碍动作 = 明确的翻页意图，与手势同一条路径
                            programmaticScroll = true
                            try {
                                pagerState.animateScrollToPage(t)
                            } finally {
                                programmaticScroll = false
                            }
                            focusSongAt(t)
                        }
                        true
                    },
                    CustomAccessibilityAction("下一台") {
                        scope.launch {
                            val t = (pagerState.currentPage + 1).coerceAtMost(songs.size - 1)
                            if (t == pagerState.currentPage) return@launch
                            programmaticScroll = true
                            try {
                                pagerState.animateScrollToPage(t)
                            } finally {
                                programmaticScroll = false
                            }
                            focusSongAt(t)
                        }
                        true
                    }
                )
            }
    ) {
        // contentPadding 相对**屏幕**左缘：要把玻璃那 20dp 缩进一起算进来，
        // 圆心才会仍与玻璃右缘重合（半遮不变量）。
        val pagerPadding = PaddingValues(
            start = fmPagerStartPadding(),
            end = fmPagerEndPadding(maxWidth)
        )

        // 淡出用的三个像素量（全部相对屏幕左缘）：
        //  · 一页宽
        //  · 玻璃右缘 = 焦点列，alpha 从这里开始 1.0
        //  · 玻璃左缘，alpha 在这里归 0 —— 淡出整段都被关在玻璃内部
        // 组合期算一次即可；只随 density / 尺寸变，不随滑动变。
        val pageSizePx = with(density) { FmPageSize.toPx() }
        val fadeStartXPx = with(density) { (FmRailStart + FmGlassWidth).toPx() }
        val fadeEndXPx = with(density) { FmRailStart.toPx() }
        var imageLoadTick by remember { androidx.compose.runtime.mutableIntStateOf(0) }
        val fmRecordKey = remember(focusedSong?.hash, smearBrush, imageLoadTick, pageColor) {
            listOf(focusedSong?.hash, smearBrush, imageLoadTick, pageColor)
        }
        val fmMustRecord = remember(pagerState, spinningKey, pageGradient) {
            {
                val active = pageGradient?.current()
                val yNow = if (active != null) {
                    val coords = stageBackdrop.layerCoordinates
                    if (coords != null && coords.isAttached) coords.positionInWindow().y else Float.NaN
                } else Float.NaN
                spinningKey != null ||
                    pagerState.isScrollInProgress ||
                    active != recordedGradientRef[0] ||
                    (active != null && yNow != recordedYRef[0] && !(yNow.isNaN() && recordedYRef[0].isNaN()))
            }
        }

        PersonalFmGlassStage(
            backdrop = stageBackdrop,
            glassOn = glassOn,
            shape = glassShape,
            glassEffects = glassEffects,
            frostedEffects = frostedEffects,
            glassSurface = glassSurface,
            frostedSurface = frostedSurface,
            modifier = Modifier.fillMaxSize(),
            recordKey = fmRecordKey,
            mustRecord = fmMustRecord,
            // ★尺寸与内缩一律不在这里给：PersonalFmGlassStage 内部按
            //   FmGlassWidth / FmGlassHeight 唯一定义。这里再传 height 或
            //   padding(vertical) 就会扣两遍（历史 bug：玻璃短 16dp 且上偏 8dp）。
            backdropContent = {
                // ★采样源 = 「页面底色（onDraw 里）+ 只裁在玻璃矩形内的提取色色渍」。
                //   板块背后**没有渐变**（用户两次要求），所以色渍必须严格被玻璃形状裁住：
                //   它画在采样源里是为了"跟着模糊、跟着被 vibrancy 提饱和"，
                //   但绝不允许溢出到玻璃外面变成一个背景色块。
                Box(
                    Modifier
                        .fillMaxSize()
                        .drawWithCache {
                            val glassLeft = FmRailStart.toPx()
                            val glassTop = FmGlassInsetY.toPx()
                            val glassW = FmGlassWidth.toPx()
                            val glassH = FmGlassHeight.toPx()
                            // 路径只算一次；圆角与真实玻璃同源
                            val clipPath = androidx.compose.ui.graphics.Path().apply {
                                addRoundRect(
                                    androidx.compose.ui.geometry.RoundRect(
                                        left = glassLeft,
                                        top = glassTop,
                                        right = glassLeft + glassW,
                                        bottom = glassTop + glassH,
                                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(
                                            FmGlassCorner.toPx()
                                        )
                                    )
                                )
                            }
                            onDrawBehind {
                                val brush = smearBrush ?: return@onDrawBehind
                                clipPath(clipPath) { drawRect(brush) }
                            }
                        }
                )
                HorizontalPager(
                    state = pagerState,
                    flingBehavior = PagerDefaults.flingBehavior(
                        state = pagerState,
                        pagerSnapDistance = DiscoveryRailSnapDistance
                    ),
                    pageSize = PageSize.Fixed(FmPageSize),
                    contentPadding = pagerPadding,
                    // 队列变短时旧页回调也不能越界；返回稳定占位键让旧页退出。
                    key = { page -> fmPageKey(currentSongs, page) },
                    beyondViewportPageCount = 1,
                    modifier = Modifier.fillMaxSize()
                ) { page ->
                    val song = currentSongs.getOrNull(page) ?: return@HorizontalPager
                    // ★按**歌曲身份**取相位，不按下标：
                    //   下标会随补货/重载漂移，按下标取会让同一首歌突然拿到别人的角度。
                    //   ★非播放中的台**保留自己停下的角度**，不要归 0：
                    //   切歌时上一台如果被强制回 0，画面上同样是一次跳变
                    //   （它往往正好还在玻璃左边可见）。
                    val pageKey = song.hash.lowercase()
                    CircleCover(
                        song = song,
                        rotation = {
                            fmPhaseToDraw(
                                phaseDegrees = rotationPhases[pageKey],
                                isSpinning = pageKey == spinningKey
                            )
                        },
                        // ★按**横向位置**淡出：任何一颗封面往左走都越来越透明，
                        //   往右滑回来又越来越清楚。判据是位置，
                        //   与"这一页是不是正在播"无关（用户口径）。
                        //   ★整段淡出被关在玻璃内部：1.0 在玻璃右缘，
                        //   0.0 在玻璃左缘——滑出玻璃之前就已经彻底消失，
                        //   不会在玻璃左边拖出一截半透明的残影。
                            fadeAlpha = {
                                fmCoverAlpha(
                                    page = page,
                                    pagerPosition = pagerPosition,
                                    pageSizePx = pageSizePx,
                                    fadeStartXPx = fadeStartXPx,
                                    fadeEndXPx = fadeEndXPx
                                )
                            },
                            onImageReady = { imageLoadTick++ }
                        )
                }
            }
        ) {
            GlassContent(
                song = focusedSong,
                accent = accent,
                isDark = isDark
            )
        }
    }
}

/**
 * 清晰圆封面。
 *
 * 缓存键与 `prefetchCover` / `ShadowImage` 完全一致（`"$url|HIGH"`），
 * 这样启动预取的那一张能被真正命中，而不是又解码一次。
 * 不再用 `CompositingStrategy.Offscreen`：这里没有 blend mode，
 * `clip(CircleShape)` 已经足够，Offscreen 会白建 4 个离屏层。
 *
 * ★[rotation] 与 [fadeAlpha] 都是函数、在绘制期读：
 *   播放中的封面的旋转/透明每帧都在变，若在组合期读它们的值，
 *   整个 Pager 页会逐帧重组（项目红线：`Skeleton.kt:43`、`CoverArtworkEffect.kt:455`）。
 */
@Composable
private fun CircleCover(
    song: KugouNewSong,
    rotation: () -> Float,
    fadeAlpha: () -> Float,
    onImageReady: () -> Unit
) {
    val context = LocalContext.current
    val url = song.artworkUrl
    val request = remember(url) {
        ImageRequest.Builder(context)
            .data(url)
            .size(400)
            .memoryCacheKey(coverCacheKey(url, ImageQuality.HIGH))
            .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
            .diskCacheKey(url.orEmpty())
            .allowHardware(true)
            .crossfade(false)
            .error(R.drawable.placeholder_music_default_artwork)
            .placeholder(R.drawable.placeholder_music_default_artwork)
            .fallback(R.drawable.placeholder_music_default_artwork)
            .build()
    }
    Box(
        Modifier
            .fillMaxHeight()
            .width(FmPageSize),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            onSuccess = { onImageReady() },
            onError = { onImageReady() },
            modifier = Modifier
                .size(FmCircleDiameter)
                .clip(CircleShape)
                .graphicsLayer {
                    rotationZ = rotation()
                    // 绘制期读：同一帧内既转又隐，不需要重建图层
                    alpha = fadeAlpha()
                }
        )
    }
}
