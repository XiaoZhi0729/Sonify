package yos.music.player.ui.widgets

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.SnapSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.CacheDrawScope
import androidx.compose.ui.draw.DrawResult
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.lerp
import coil.imageLoader
import coil.request.ImageRequest
import android.graphics.drawable.BitmapDrawable
import androidx.palette.graphics.Palette
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.SubcomposeLayout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.fastForEach
import androidx.compose.ui.util.fastForEachIndexed
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import yos.music.player.code.utils.lrc.YosMediaEvent
import yos.music.player.code.utils.lrc.YosUIConfig
import yos.music.player.code.utils.lrc.LyricEntry
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MainViewModelObject
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.widgets.basic.YosWrapper
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import kotlin.math.sqrt

val yosEasing = CubicBezierEasing(0.75f, 0.0f, 0.25f, 1.0f)

// ===== 已唱字色（对齐 Flamingo：白 + 封面提取色）=====
// 基色 = 70% 白 + 30% 封面提取色；未唱字 = 白 50%。提取色由 YosLyricView 顶层按当前封面更新。
private const val LyricSungCoverMix = 0.3f

private object LyricCoverTint {
    val color = mutableStateOf(Color.White)
}

// ===== 长音辉光参数 =====
// 对齐 MD3Music/AMLL：单峰海浪包络 + 字芯镂空外圈发光
// 时长门控：短于 MinNoteMs 的普通吐字不发光，MinNoteMs~FullNoteMs 间线性渐强
private const val LyricGlowMinNoteMs = 700f      // 最短发光词时长
private const val LyricGlowFullNoteMs = 1500f    // 完全发光词时长
private const val LyricGlowMaxAlpha = 1.0f       // 峰值辉光不透明度（最大100%）
private const val LyricGlowBlurRadius = 12f      // 辉光模糊半径（dp）收紧到12
private const val LyricGlowDuFloorMs = 1000f     // 包络最短持续时间
private const val LyricGlowReanchorToleranceMs = 150f  // 时钟重锚容差

// 海浪包络曲线控制点（cubic-bezier）
private const val LyricGlowBezInP1 = 0.2f
private const val LyricGlowBezInP2 = 0.4f
private const val LyricGlowBezOutP1 = 0.3f
private const val LyricGlowBezOutP2 = 0.0f

/** CSS cubic-bezier 前两控制点版（P0=0、P3=1） */
private fun glowCubicBezier(t: Float, p1: Float, p2: Float): Float {
    val u = 1f - t
    return 3f * u * u * t * p1 + 3f * u * t * t * p2 + t * t * t
}

private const val SmartWbwFallbackMsPerChar = 280f
private const val SmartWbwMaxLineMs = 12_000f

private fun isCjkChar(c: Char): Boolean {
    val block = Character.UnicodeBlock.of(c)
    return block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
        block == Character.UnicodeBlock.HIRAGANA ||
        block == Character.UnicodeBlock.KATAKANA ||
        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
        block == Character.UnicodeBlock.HALFWIDTH_AND_FULLWIDTH_FORMS
}

/**
 * 非逐字行 → 合成词级时间轴：按字符数线性均摊行时长。CJK 逐字成词，拉丁词组按空白切分（空白并入前一词）。
 * 输出形状与 KRC 逐词一致：首元素 (行起点, "")，其后为 (词结束时间, 词文本)。
 * [nextStartMs] 为下一行起点，≤ 行起点（末行）时按每字 280ms 估算，并封顶 12s。
 */
private fun synthesizeSmartWbw(
    lines: List<Pair<Float, String>>,
    nextStartMs: Float
): List<Pair<Float, String>> {
    val start = lines.firstOrNull()?.first ?: return lines
    val text = lines.joinToString("") { it.second }
    if (text.isBlank()) return lines

    val units = ArrayList<String>()
    val sb = StringBuilder()
    fun flush() {
        if (sb.isNotEmpty()) {
            units += sb.toString()
            sb.clear()
        }
    }
    for (c in text) {
        when {
            isCjkChar(c) -> {
                flush()
                units += c.toString()
            }
            c.isWhitespace() -> {
                if (sb.isNotEmpty()) sb.append(c)
                else if (units.isNotEmpty()) units[units.lastIndex] = units.last() + c
                else sb.append(c)
            }
            else -> {
                if (sb.isNotEmpty() && sb.last().isWhitespace()) flush()
                sb.append(c)
            }
        }
    }
    flush()
    if (units.isEmpty()) return lines

    val totalChars = units.sumOf { it.length }
    val rawDuration = nextStartMs - start
    val duration = (if (rawDuration > 0f) rawDuration else totalChars * SmartWbwFallbackMsPerChar)
        .coerceAtMost(SmartWbwMaxLineMs)

    val result = ArrayList<Pair<Float, String>>(units.size + 1)
    result += start to ""
    var acc = 0
    for (u in units) {
        acc += u.length
        result += (start + duration * acc / totalChars) to u
    }
    return result
}

// 滚动弹簧：mass 0.9 / damping 15 / stiffness 90。Compose 的 spring 固定 mass=1，
// 需折算：ω = sqrt(k/m) = 10 rad/s → stiffness = ω² = 100；ζ = c / (2·sqrt(k·m)) = 15 / 18 ≈ 0.833。
private val LyricScrollSpring: AnimationSpec<Float> =
    spring(dampingRatio = 15f / 18f, stiffness = 100f)

// ===== 行 alpha / 浮起 / 胶囊底参数（对齐 Flamingo 新版）=====
private const val LyricNonCurrentLineAlpha = 0.4f
private val LyricLineAlphaSpec: AnimationSpec<Float> =
    tween(durationMillis = 260, easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f))
private const val LyricContainerAlphaCurrent = 0.95f
private const val LyricContainerAlphaOther = 0.82f
private const val LyricRiseLeadMs = 180f      // 普通词浮起提前量
private const val LyricRiseTailMs = 400f      // 普通词浮起窗口尾部
private const val LyricRisePx = 4f            // 浮起幅度

/**
 * 歌词滚动状态容器：由调用方在常驻层持有（如 NowPlaying）。
 * 歌词页在开启/关闭、歌词↔队列切换、横竖屏间往返时会整体离开组合，
 * 内部 remember 的状态随之销毁；把列表位置提升到这里，滚动位置即可跨组合保留。
 *
 * 注意这里**只提升位置、不提升 LazyListState 本体**：
 * LazyListState 是单归属对象，内部 connection 与滚动互斥锁指向当前持有它的那个
 * LazyList，旧列表销毁时会清理它们。而 MainActivity 声明了
 * `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`，旋转不重建
 * Activity，于是 remember { YosLyricScrollState() } 跨方向存活——竖屏歌词页与横屏歌词
 * 面板这两个不同的 LazyColumn 会在同一次重组里交接同一个 LazyListState。
 * 交接顺序不保证「先接后卸」，旧列表也可能带着进行中的 fling 被销毁；任何一种都会让
 * 新列表的 scrollBy 得到 0 consumed，于是它一点都不消费竖直手势，事件泄给外壳的收起
 * draggable——表现为「横屏歌词滑不动、整页跟着上下动又弹回」，而直接横屏进入不出问题
 * （没有交接）。所以这里改成传位置过去，由每个组合位点各自 new 一个 LazyListState。
 */
@Stable
class YosLyricScrollState {
    /** 上一次停在第几行（含首行占位，与 LazyListState.firstVisibleItemIndex 同义） */
    var firstVisibleItemIndex: Int = 0

    /** 该行内的像素偏移，与 LazyListState.firstVisibleItemScrollOffset 同义 */
    var firstVisibleItemOffset: Int = 0

    val viewportHeight = mutableIntStateOf(0)
    val lastLineHeight = mutableIntStateOf(0)
}

/**
 * YosLyricView 主控件
 * @param lrcEntriesLambda 处理完毕的 Lrc 文本
 * @param liveTimeLambda 当前歌曲进度
 * @param mediaEvent YosLyricView 媒体事件
 * @param translationLambda 是否开启翻译
 * @param blurLambda 是否启用模糊效果
 * @param uiConfig YosLyricView UI 控制，仅管理在日常使用中不经常调节的选项
 * @param lyricState 跨组合保留的滚动状态（列表位置、容器高度、末行高度）
 */
@Composable
fun YosLyricView(
    //mediaViewModel: MediaViewModel,
    lrcEntriesLambda: () -> List<LyricEntry>,
    liveTimeLambda: () -> Int,
    mediaEvent: YosMediaEvent,
    lyricState: YosLyricScrollState,
    translationLambda: () -> Boolean = { true },
    blurLambda: () -> Boolean = { false },
    //animationConfig: YosAnimationConfig = YosAnimationConfig(),
    uiConfig: YosUIConfig = YosUIConfig(),
    weightLambda: () -> Boolean,
    modifier: Modifier,
    onBackClick: () -> Unit,
    /**
     * 末句固定落点所依据的底部遮挡高度比例（0~1），由调用方按布局静态给出，不随控件显隐变化。
     * 竖屏传 [LAST_LINE_OBSTRUCTION_FRACTION]，使末句固定在控件之上；横屏无底部遮挡传 0。
     */
    lastLineObstructionFraction: Float = 0f
) {
    println("重组：YosLyricView")
    val context = LocalContext.current
    val lyricCoverUri = MediaViewModelObject.bitmap.value
    LaunchedEffect(lyricCoverUri) {
        if (lyricCoverUri == null) {
            LyricCoverTint.color.value = Color.White
            return@LaunchedEffect
        }
        val tintArgb = runCatching {
            val result = context.imageLoader.execute(
                ImageRequest.Builder(context)
                    .data(lyricCoverUri)
                    .size(96)
                    .allowHardware(false)
                    .build()
            )
            val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@runCatching null
            withContext(Dispatchers.Default) {
                val palette = Palette.from(bitmap).generate()
                val fallback = 0
                listOf(
                    palette.getLightVibrantColor(fallback),
                    palette.getVibrantColor(fallback),
                    palette.getLightMutedColor(fallback),
                    palette.getMutedColor(fallback)
                ).firstOrNull { it != fallback }
            }
        }.getOrNull()
        LyricCoverTint.color.value = if (tintArgb != null && tintArgb != 0) Color(tintArgb) else Color.White
    }
    val mainTextBasicColor = Color(uiConfig.mainTextBasicColor)
    val subTextBasicColor = Color(uiConfig.subTextBasicColor)
    //Color(0xFF919191)
    val otherSideForLines = MediaViewModelObject.otherSideForLines

    val lrcEntries = lrcEntriesLambda()

    //val thisLyricLines = MediaViewModelObject.mainLyricLines
    if (lrcEntries.isEmpty() || otherSideForLines.isEmpty() /*|| thisLyricLines.isEmpty()*/) {
        println(
            lrcEntries.isEmpty()
                .toString() + otherSideForLines.isEmpty()/* + thisLyricLines.isEmpty()*/
        )
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxHeight(if (weightLambda()) LYRIC_AREA_FRACTION_CONTROLS_VISIBLE else 1f)
                .fillMaxWidth()
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }) {
                    onBackClick()
                }
        ) {
            Text(
                text = uiConfig.noLrcText,
                fontSize = 18.sp,
                color = Color(uiConfig.mainTextBasicColor)
            )
        }
    } else {
        // 每个组合位点各持有一个 LazyListState（单归属），位置从 lyricState 播种，
        // 滚动时再写回，供下一个位点（另一方向 / 面板重新入场）接续。
        // remember 的槽位按组合位点区分：竖屏那处与横屏那处因此不会共用同一个实例。
        val scrollState = remember(lyricState) {
            LazyListState(
                firstVisibleItemIndex = lyricState.firstVisibleItemIndex,
                firstVisibleItemScrollOffset = lyricState.firstVisibleItemOffset
            )
        }
        YosWrapper {
            LaunchedEffect(scrollState) {
                snapshotFlow {
                    scrollState.firstVisibleItemIndex to
                            scrollState.firstVisibleItemScrollOffset
                }.collect { (index, offset) ->
                    lyricState.firstVisibleItemIndex = index
                    lyricState.firstVisibleItemOffset = offset
                }
            }
        }
        val currentLyricIndex =
            remember("YosLyricView_currentLyricIndex") { MainViewModelObject.syncLyricIndex }
        /*val noAnimateItems by remember {
            derivedStateOf { scrollState.layoutInfo.totalItemsCount - scrollState.layoutInfo.visibleItemsInfo.size - 1 }
        }
        val showAnimate by remember {
            derivedStateOf {
                currentLyricIndex in scrollState.layoutInfo.visibleItemsInfo.map { it.index - 1 } && currentLyricIndex > 0 && currentLyricIndex < noAnimateItems
            }
        }*/
        val blankSpacer: (LazyListScope.() -> Unit) = {
            item {
                Box(
                    modifier = Modifier
                        .height(uiConfig.blankHeight.dp)
                ) {
                }
            }
        }
        //val coroutineScope = rememberCoroutineScope()
        val enableLyricScroll = remember("YosLyricView_enableLyricScroll") {
            mutableStateOf(true)
        }
        // 进入组合后的首次定位标志：为 true 时用无动画 scrollToItem 直接落到当前行，
        // 避免“从列表顶部动画滚一遍”；定位完成后置 false，后续行变化仍走动画跟随。
        // 每次进入组合（重开歌词、切页返回）都会重建为 true，重新校正一次位置。
        val pendingEntryPositioning = remember { mutableStateOf(true) }

        /*val lastClickTime = rememberSaveable(key = "YosLyricView_lastClickTime") {
            mutableLongStateOf(0L)
        }*/

        /*YosWrapper {
            LaunchedEffect(enableLyricScroll.value, lastClickTime.longValue) {
                if (!enableLyricScroll.value) {
                    val time = 1500L
                    delay(time)
                    withContext(Dispatchers.Main) {
                        if (TimeUtils.getNowMills() - lastClickTime.longValue >= time) {
                            enableLyricScroll.value = true
                        }
                    }
                }
            }
        }*/

        val height = lyricState.viewportHeight
        val lastLyricHeight = lyricState.lastLineHeight

        // 末句固定停在"控件可见时也看得到"的高度：按底部遮挡后的可视区居中，
        // 不随控件显隐上下移动。遮挡比例由调用方静态给出（竖屏非 0、横屏 0）。
        val obstructionFraction = lastLineObstructionFraction

        // 换歌时（lrcEntries 引用变化，同一首歌内引用稳定）重置末句高度缓存与定位标志：
        // 旧歌的末句高度会污染尾部余量计算，且换歌后应无动画直接落到新歌当前行。
        // 首次组合跳过，避免清掉跨组合保留的测量值。
        val previousEntries = remember { mutableStateOf<List<LyricEntry>?>(null) }
        LaunchedEffect(lrcEntries) {
            val previous = previousEntries.value
            if (previous != null && previous != lrcEntries) {
                lastLyricHeight.intValue = 0
                pendingEntryPositioning.value = true
            }
            previousEntries.value = lrcEntries
        }

        val density = LocalDensity.current
        val trailingExtraHeight = with(density) {
            // 末句高度未测量（末句尚未组合过）时按 0 代入：先给足尾部余量，
            // 避免播放到末尾时内容不足、末句滚不进居中点后再来一次大幅冲刺修正。
            // 控件遮挡时落点上移，尾部余量由纯函数同步追加。
            lyricTrailingExtraPx(
                viewportHeight = height.intValue,
                lastLineHeight = lastLyricHeight.intValue,
                blankHeightPx = uiConfig.blankHeight.dp.roundToPx(),
                contentPaddingPx = 16.dp.roundToPx(),
                obstructionFraction = obstructionFraction
            ).toDp()
        }

        val targetWeight = 0.0618f
        val targetOffset = remember(height.intValue) { height.intValue * targetWeight }
        // 顶部边距

        val space = 0.dp
        // 行距

        val measurer = rememberTextMeasurer(
            cacheSize = 32
        )

        val visibleItems = remember("YosLyricView_visibleItems") {
            derivedStateOf {
                scrollState.layoutInfo.visibleItemsInfo
            }
        }
        val targetItem = remember("YosLyricView_targetItem") {
            derivedStateOf {
                visibleItems.value.find {
                    it.index == currentLyricIndex.intValue + 1
                }
            }
        }
        val currentOffset = remember("YosLyricView_currentOffset", targetOffset) {
            derivedStateOf {
                targetItem.value?.offset ?: targetOffset.toInt()
            }
        }
        val scrollDistance = remember("YosLyricView_scrollDistance", targetOffset) {
            derivedStateOf {
                currentOffset.value - targetOffset
            }
        }
        val nowFirst = remember("YosLyricView_nowFirst") {
            derivedStateOf {
                scrollState.firstVisibleItemIndex
            }
        }
        val supportBlur = rememberSaveable(key = "supportBlur") {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
        }

        val isUserScrolling = remember { mutableStateOf(false) }
        val nestedScrollConnection = remember {
            @Stable
            object : NestedScrollConnection {
                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    isUserScrolling.value = true
                    return Offset.Zero
                }

                override suspend fun onPostFling(
                    consumed: Velocity,
                    available: Velocity
                ): Velocity {
                    isUserScrolling.value = false
                    return super.onPostFling(consumed, available)
                }
            }
        }

        YosWrapper {
            LaunchedEffect(isUserScrolling.value) {
                if (isUserScrolling.value) {
                    enableLyricScroll.value = false
                } else {
                    delay(1600)
                    enableLyricScroll.value = true
                }
            }
        }

        YosWrapper {
            LazyColumn(
                state = scrollState,
                contentPadding = PaddingValues(vertical = 16.dp),/*
            verticalArrangement = Arrangement.spacedBy(5.dp),*/
                modifier =
                modifier
                    .fillMaxSize()
                    /*.drawWithCache {
                        onDrawWithContent {
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
                                    Color.Transparent,
                                    Color(0x59000000),
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
                                    Color(0x59000000),
                                    Color(0x3F000000),
                                    Color(0x21000000),
                                )
                            }

                            drawContent()

                            drawRect(
                                brush = Brush.verticalGradient(colors),
                                blendMode = BlendMode.DstIn
                            )
                        }
                    }*/
                    /*.scrollable(state = rememberScrollableState {
                        enableLyricScroll.value = false
                        lastClickTime.longValue =
                            TimeUtils.getNowMills()
                        it
                    }, orientation = Orientation.Vertical)*/
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }) {
                        onBackClick()
                    }
                    .nestedScroll(nestedScrollConnection)
                    .onSizeChanged {
                        // 高度变化即更新（横竖屏切换后 targetOffset 才能跟着算准）
                        if (it.height != 0 && it.height != height.intValue) {
                            height.intValue = it.height
                        }
                    }
            ) {
                //println("重组：歌词列表")
                blankSpacer()
                itemsIndexed(
                    items = lrcEntries,
                    key = { index, line -> "lyric_${index}_${line.startTime}" }/*,
                contentType = { _, _ -> "YosLyricView_item" }*/
                ) { index, lines ->
                    val isCurrent = remember(lines) {
                        derivedStateOf {
                            index == currentLyricIndex.intValue
                        }
                    }

                    val isTop = remember(lines) {
                        derivedStateOf {
                            index == (currentLyricIndex.intValue - 1)
                        }
                    }

                    val showStateAnimation = remember(index) {
                        derivedStateOf {
                            (currentLyricIndex.intValue in scrollState.layoutInfo.visibleItemsInfo.map { it.index - 1 } && currentLyricIndex.intValue >= 0) && enableLyricScroll.value
                        }
                    }

                    val isLyricEmpty = rememberSaveable(index) {
                        mutableStateOf(
                            lines.mainLyric.all { it.second.isBlank() }
                        )
                    }

                    key("lyric_content_$index") {
                            val translation = lines.translation

                        val blur = remember(index) {
                            derivedStateOf {
                                if (!showStateAnimation.value || index == currentLyricIndex.intValue || !blurLambda() || !supportBlur) {
                                    0f
                                } else {
                                    (abs(index - currentLyricIndex.intValue) * 2.5f).coerceAtMost(
                                        8f
                                    )
                                }
                            }
                        }

                        val otherSide = remember(index) {
                            otherSideForLines.getOrElse(index) { false }
                        }

                        YosWrapper {
                            LyricItem(
                                isCurrentLambda = {
                                    isCurrent.value
                                },
                                isTopLambda = {
                                    isTop.value
                                },
                                mainLyric = lines.mainLyric,
                                translation,
                                translationLambda(),
                                //mainTextSize = uiConfig.mainTextSize,
                                subTextSize = uiConfig.subTextSize,
                                blur = { blur.value },
                                mainTextBasicColor,
                                subTextBasicColor,
                                otherSide = otherSide,
                                liveTimeLambda = liveTimeLambda,
                                measurer = measurer,
                                isLyricEmpty = { isLyricEmpty.value },
                                nextTime = {
                                    if (index + 1 > lrcEntries.size - 1) {
                                        0f
                                    } else {
                                        lrcEntries[(index + 1)].startTime
                                    }
                                },
                                onHeightChanged = if (index == lrcEntries.lastIndex) {
                                    { itemHeight -> lastLyricHeight.intValue = itemHeight }
                                } else {
                                    null
                                }
                            ) {
                                Vibrator.doubleClick(context)
                                currentLyricIndex.intValue = index
                                        mediaEvent.onSeek(lines.startTime.toInt())

                            }
                        }
                    }

                    key(index) {
                        YosWrapper {
                            /*//println(mainLyricSide.value+":"+mainLyricSide.value.isNotBlank())
                        if ((*//*(mainLyricSide.isBlank() && isCurrent.value && countdownPercent.value != 0f) || *//*mainLyricSide.value.isNotBlank())) {
                                val offset = animateDpAsState(
                                    targetValue = if (index <= currentLyricIndex.value || !showStateAnimation.value) 0.dp else 6.18.dp * (index - (nowFirst.value / 2)),
                                    animationSpec = spring(
                                        stiffness = 70f,
                                        dampingRatio = 0.8f,
                                        visibilityThreshold = 0.001.dp
                                    )
                                )
                                Spacer(modifier = Modifier.height(offset.value))
                            }*/

                            //val nowFirst = remember(index) { derivedStateOf { scrollState.firstVisibleItemIndex } }

                            /*val space = 16.dp*/ /*remember(index) {
                                    derivedStateOf {
                                        if (lines.isNotEmpty() && isCurrent.value) 5.dp else
                                    }
                                }*/

                            //val visibleItems = remember(index) { derivedStateOf { scrollState.layoutInfo.visibleItemsInfo } }

                            /*val nowVisible = remember(visibleItems) {
                        visibleItems.value.size
                    }*/

                            //val targetItem = visibleItems.value.find { it.index == currentLyricIndex.intValue /** 2*/ + 1 }


                            val show = remember(index) {
                                derivedStateOf { !isLyricEmpty.value || isCurrent.value }
                            }

                            val thisScrollDistance = if (targetItem.value != null) {
                                (scrollDistance.value / (visibleItems.value.size)).toDp()
                            } else {
                                0.dp
                            }

                            val thisTargetHeight = remember(index) {
                                mutableStateOf(space)
                            }

                            YosWrapper {
                                LaunchedEffect(currentLyricIndex.intValue) {
                                    if (pendingEntryPositioning.value) {
                                        // 进入组合的定位阶段不重放波浪动画，避免“重新载入”的闪烁感
                                        return@LaunchedEffect
                                    }
                                    if (visibleItems.value.isEmpty()) {
                                        //println(mainLyric.value.text+" 未设置")
                                        return@LaunchedEffect
                                    }
                                    //println(mainLyric.value.text+" "+(index >= currentLyricIndex.intValue && showStateAnimation.value && show.value))
                                    if (index >= currentLyricIndex.intValue - 1 && showStateAnimation.value && show.value) {
                                        val weight =
                                            (1f - ((index - (nowFirst.value)) / visibleItems.value.size))
                                        delay((550 * (1f - weight)).toLong())
                                        thisTargetHeight.value =
                                            (thisScrollDistance * weight).plus(space)
                                        delay(
                                            ((550 / 1.95f) * weight).toLong()
                                        )
                                        thisTargetHeight.value = space
                                    } else if (show.value) {
                                        thisTargetHeight.value = space
                                    } else {
                                        thisTargetHeight.value = 0.dp
                                    }
                                }
                            }

                            val offset = animateDpAsState(
                                targetValue = thisTargetHeight.value,
                                animationSpec = if (thisTargetHeight.value == 0.dp || thisTargetHeight.value == space/*16.dp || thisTargetHeight.value == 5.dp*/) {
                                    spring(
                                        stiffness = 105F,
                                        dampingRatio = /*0.85f*/ 1f,
                                        visibilityThreshold = 0.0001.dp
                                    )
                                    //tween(durationMillis = 510, easing = yosEasing)
                                } else {
                                    tween(
                                        durationMillis = 550,
                                        easing = yosEasing
                                    )
                                }
                            )

                            YosWrapper {
                                Spacer(modifier = Modifier.height(offset.value))
                            }
                        }
                    }


                }
                blankSpacer()
                item("extra_blank") {
                    Spacer(modifier = Modifier.height(trailingExtraHeight))
                }
            }
        }

        YosWrapper {
            //val lifecycleState = LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
            LaunchedEffect(
                currentLyricIndex.intValue,
                translationLambda(),
                height.intValue,
                lastLyricHeight.intValue
            ) {
                try {
                    if (enableLyricScroll.value) {
                        if (pendingEntryPositioning.value) {
                            // 容器高度未就绪时不定位（key 含 height，就绪后本 effect 会重启），
                            // 保证 targetOffset 偏移一次算准，避免定位后再动画修正。
                            if (height.intValue <= 0) {
                                return@LaunchedEffect
                            }
                            pendingEntryPositioning.value = false
                            if (currentLyricIndex.intValue >= 0) {
                                // 重新进入歌词页：无动画直接落到当前行（落点与下方动画分支一致）
                                scrollState.scrollToItem(
                                    index = (currentLyricIndex.intValue + 1).coerceAtLeast(0),
                                    scrollOffset = -targetOffset.toInt()
                                )
                            } else {
                                // 换歌后首次载入：按当前进度无动画定位
                                val liveTime = liveTimeLambda()
                                val nextIndex = lrcEntries.indexOfFirst { line ->
                                    line.startTime > liveTime
                                }
                                if (nextIndex != -1) {
                                    if (nextIndex - 1 != currentLyricIndex.intValue) {
                                        scrollState.scrollToItem(
                                            index = nextIndex.coerceAtLeast(0),
                                            scrollOffset = -targetOffset.toInt()
                                        )
                                        currentLyricIndex.intValue = nextIndex - 1
                                    }
                                } else if (currentLyricIndex.intValue != lrcEntries.size - 1) {
                                    scrollState.scrollToItem(
                                        index = lrcEntries.size.coerceAtLeast(0),
                                        scrollOffset = -targetOffset.toInt()
                                    )
                                    currentLyricIndex.intValue = lrcEntries.size - 1
                                }
                            }
                            return@LaunchedEffect
                        }
                        /*visibleItems = scrollState.layoutInfo.visibleItemsInfo
                        targetItem =
                            visibleItems.find { it.index == currentLyricIndex.intValue */
                        /** 2*/
                        /** 2*/ /* + 1 }*/

                        val lastLyricIndex = lrcEntries.lastIndex
                        val isLastLyric = currentLyricIndex.intValue == lastLyricIndex
                        val layoutInfo = scrollState.layoutInfo
                        val viewportHeight =
                            (layoutInfo.viewportEndOffset - layoutInfo.viewportStartOffset)
                                .coerceAtLeast(1)

                        // 末句的居中落点：默认停在歌词可视区的 Y 轴中线上；
                        // 竖屏控件可见时可视区被底部遮挡压缩，落点随之抬高，末句不再被控件盖住。
                        val centerScrollOffset = lastLineCenterScrollOffset(
                            viewportHeight = viewportHeight,
                            lastLineHeight = lastLyricHeight.intValue,
                            obstructionFraction = obstructionFraction
                        )
                        val lastItemInfo =
                            layoutInfo.visibleItemsInfo.firstOrNull { it.index == lastLyricIndex + 1 }

                        if (isLastLyric) {
                            if (lastItemInfo != null && lastLyricHeight.intValue > 0) {
                                // 末句可见：按当前位置增量滚到居中点，动画与普通行一致（缓入缓出），
                                // 不用 animateScrollToItem 的默认 spring 长距离冲刺。
                                val delta = (lastItemInfo.offset - centerScrollOffset).toFloat()
                                if (abs(delta) > 0.5f) {
                                    scrollState.animateScrollBy(
                                        delta,
                                        tween(durationMillis = 550, easing = yosEasing)
                                    )
                                }
                            } else {
                                // 末句不在视口内（用户滚走后恢复）：scrollOffset 必须取负值，
                                // 负值才是“item 顶部放在视口顶下方 centerScrollOffset”；
                                // 正值会把请求位置顶到最大滚动距离之外整整一个视口高，
                                // 动画以两倍距离冲刺后被边界硬顶住（滚动力度过大的根源）。
                                scrollState.animateScrollToItem(
                                    index = lastLyricIndex + 1,
                                    scrollOffset = -centerScrollOffset
                                )
                            }
                        } else if (
                            try {
                                if (currentLyricIndex.intValue - 1 < 0) false
                                else (
                                        (lrcEntries[(currentLyricIndex.intValue - 1)].mainLyric.getOrNull(1)?.second.isNullOrBlank())
                                        /*&&
                                        (lrcEntries[(currentLyricIndex.intValue).coerceAtLeast(
                                            0
                                        )].first().first - lrcEntries[(currentLyricIndex.intValue - 1)].first().first > 900f)*/)
                                // 这里有一个特殊的更改，因为 Apple Music 歌词转过来有两个连续一样的时间轴，LrcFactory 已规范处理。
                            } catch (_: Exception) {
                                false
                            }
                        ) {
                            // 上一行翻译为空：跳过增量滚动，避免抖动。
                        } else if (targetItem.value != null /*|| lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED)*/) {
                            /*currentOffset.value = targetItem.value?.offset?:targetOffset.toInt()
                            scrollDistance.value = currentOffset - targetOffset*/
                            // 增量滚动前把距离钳到“末句顶部恰好到居中点”：列表尾部余量按
                            // 末句居中精确设计，最后几行的常规落点（视口顶部 6.18%）会超出
                            // 最大滚动距离，不钳制的话动画会冲向不可达目标、在中段高速撞上边界。
                            var distance = scrollDistance.value.toFloat()
                            if (distance > 0f && lastItemInfo != null &&
                                lastItemInfo.offset - distance < centerScrollOffset
                            ) {
                                distance = (lastItemInfo.offset - centerScrollOffset)
                                    .toFloat()
                                    .coerceAtLeast(0f)
                            }
                            if (abs(distance) > 0.5f) {
                                scrollState.animateScrollBy(
                                    distance,
                                    animationSpec = tween(
                                        durationMillis = 550,
                                        //delayMillis = 15,
                                        easing = yosEasing
                                    )
                                )
                            }
                        } else {
                            scrollState.animateScrollToItem(
                                index = (currentLyricIndex.intValue
                                        /** 2*/
                                        /** 2*/
                                        + 1).coerceAtLeast(0),
                                scrollOffset = -targetOffset.toInt()
                            )
                        }
                    }
                } catch (_: Exception) {
                }
            }
        }

        /*YosWrapper {
            LaunchedEffect(Unit) {
                while (true) {
                    val liveTime = liveTimeLambda()
                    val nextIndex = lrcEntries.indexOfFirst { line ->
                        line.startTime > liveTime
                    }

                    if (nextIndex != -1 && nextIndex - 1 != currentLyricIndex.intValue) {
                        currentLyricIndex.intValue = nextIndex - 1
                    } else if (nextIndex == -1 && currentLyricIndex.intValue != lrcEntries.size - 1) {
                        currentLyricIndex.intValue = lrcEntries.size - 1
                    }

                    delay(100)
                }
            }
        }*/

    }
}

/*@Composable
fun Dp.toPx(): Float {
    val density = LocalDensity.current
    return this.value * density.density
}*/

@Composable
fun Float.toDp(): Dp {
    val density = LocalDensity.current
    return (this / density.density).dp
}

@Composable
private fun LazyItemScope.Line(
    lines: List<Pair<Float, String>>,
    style: TextStyle,
    measurer: TextMeasurer,
    modifier: Modifier,
    viewAlign: Alignment.Horizontal,
    draw: CacheDrawScope.(Constraints, TextLayoutResult) -> DrawResult
) =
    YosWrapper {
        /*val styledString = remember(style, lines) {
            buildAnnotatedString {
                lines.forEachIndexed { _, char ->
                    if (char.second.isNotEmpty()) {
                        withStyle(style.toSpanStyle()) {
                            append(char.second)
                        }
                    }
                }
            }
        }*/

        val styledString = remember(style, lines) {
            buildString {
                lines.forEach { char ->
                    if (char.second.isNotEmpty()) {
                        append(char.second)
                    }
                }
            }
        }


        Column(
            horizontalAlignment = viewAlign,
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                }
        ) {
            SubcomposeLayout(modifier = modifier) { constraints ->

                val measureResult = measurer.measure(
                    text = styledString,
                    style = style,
                    constraints = Constraints(
                        minWidth = 0,
                        maxWidth = constraints.maxWidth,
                    ),
                    layoutDirection = LayoutDirection.Ltr
                )

                val height = (style.lineHeight * measureResult.lineCount)

                val width = runCatching {
                    (0 until measureResult.lineCount).maxOf {
                        measureResult.getBoundingBox(
                            measureResult.getLineEnd(it, visibleEnd = true) - 1
                        ).right
                    }
                }.getOrDefault(constraints.maxWidth.toFloat())

                val content = subcompose(lines) {
                    Spacer(
                        Modifier
                            .fillMaxSize()
                            .drawWithCache { draw(constraints, measureResult) }
                    )
                }.first()


                val placeable = content.measure(
                    Constraints.fixed(width.roundToInt(), height.roundToPx())
                )

                layout(placeable.width, placeable.height) {
                    placeable.place(0, 0)
                }

                /*layout(placeable.width, placeable.height) {
                    placeable.placeRelative(0, 0)
                }*/
            }
        }
    }

/*@Composable
private fun LazyItemScope.Line(
    lines: List<Pair<Float, String>>,
    style: TextStyle,
    measurer: TextMeasurer,
    modifier: Modifier,
    viewAlign: Alignment.Horizontal,
    draw: CacheDrawScope.(Constraints, TextLayoutResult) -> DrawResult
) =
    YosWrapper {
        val styledString = remember(style, lines) {
            buildString {
                lines.forEach { char ->
                    if (char.second.isNotEmpty()) {
                        append(char.second)
                    }
                }
            }
        }

        Column(
            modifier = modifier,
            horizontalAlignment = viewAlign
        ) {
            Layout(
                content = {
                    Spacer(
                        Modifier
                            .fillMaxSize()
                            .drawWithCache {
                                val constraints = Constraints(
                                    minWidth = 0,
                                    maxWidth = size.width.toInt()
                                )
                                val measureResult = measurer.measure(
                                    text = styledString,
                                    style = style,
                                    constraints = constraints
                                )
                                draw(constraints, measureResult)
                            }
                    )
                }
            ) { measurables, constraints ->

                val measureResult = measurer.measure(
                    text = styledString,
                    style = style,
                    constraints = Constraints(
                        minWidth = 0,
                        maxWidth = constraints.maxWidth
                    )
                )

                // 确保高度计算正确，包含所有文本行
                val height = measureResult.size.height

                val width = runCatching {
                    (0 until measureResult.lineCount).maxOf {
                        measureResult.getBoundingBox(
                            measureResult.getLineEnd(it, visibleEnd = true) - 1
                        ).right
                    }
                }.getOrDefault(constraints.maxWidth.toFloat()).roundToInt()

                val placeable = measurables.first().measure(
                    Constraints.fixed(width, height)
                )

                layout(width, height) {
                    placeable.placeRelative(0, 0)
                }
            }
        }
    }*/

val easing: Easing = EaseInOutQuad

@Composable
@OptIn(ExperimentalFoundationApi::class)
fun LazyItemScope.LyricItem(
    isCurrentLambda: () -> Boolean,
    isTopLambda: () -> Boolean,
    mainLyric: List<Pair<Float, String>>,
    translation: String?,
    showTranslation: Boolean,
    //mainTextSize: Int,
    subTextSize: Int,
    blur: () -> Float,
    /*showBlur: Boolean,*/
    mainTextBasicColor: Color,
    subTextBasicColor: Color,
    measurer: TextMeasurer,
    isLyricEmpty: () -> Boolean,
    nextTime: () -> Float,
    otherSide: Boolean,
    liveTimeLambda: () -> Int,
    onHeightChanged: ((Int) -> Unit)? = null,
    onClick: () -> Unit
) {
    println("重组：歌词 $mainLyric")

    val viewAlign = if (otherSide) Alignment.End else Alignment.Start

    val focusedColor = lerp(Color.White, LyricCoverTint.color.value, LyricSungCoverMix)
    val unfocusedColor = Color(0x2EFFFFFF)  // 未唱字：保持原有颜色（勿改，用户要求）

    // @Composable 样式在组合期取一次，供非组合的测量/draw lambda 捕获使用
    val mainStyle = mainTextStyle()
    //Color(0x33FFFFFF)

    //val focusedSolidBrush = SolidColor(focusedColor)

    val unfocusedSolidBrush = SolidColor(unfocusedColor)

    val isNotOneByOne = rememberSaveable(mainLyric) {
        mutableStateOf(
            mainLyric.all { it.first == mainLyric.firstOrNull()?.first }
        )

    }

    val liveTime = remember(mainLyric) { mutableIntStateOf(liveTimeLambda()) }

// 自驱动辉光时钟（ms）：仅当前行逐帧推进，与播放位置解耦防抖动
    val glowTime = remember(mainLyric) { mutableFloatStateOf(-1f) }

    YosWrapper {
        // 辉光时钟驱动：只在当前行且逐字模式下运行
        val glowRunning = remember(mainLyric) {
            derivedStateOf { !isNotOneByOne.value && isCurrentLambda() }
        }
        LaunchedEffect(glowRunning.value) {
            if (!glowRunning.value) {
                glowTime.value = -1f
                return@LaunchedEffect
            }
            // 直接跟随播放位置：暂停时位置不变 → 辉光冻结稳定不闪；
            // 播放时逐帧采样，无自驱动外推，消除偏差>150ms 时的锯齿重锚闪烁
            while (true) {
                withFrameNanos {
                    glowTime.value = liveTimeLambda().toFloat()
                }
            }
        }
    }

    YosWrapper {
        val launch = remember(mainLyric) {
            derivedStateOf {
                isLyricEmpty() || !isNotOneByOne.value
            }
        }
        if (launch.value) {
            LaunchedEffect(Unit) {
                while (true) {
                    withContext(Dispatchers.Main) {
                        liveTime.intValue = liveTimeLambda()
                    }
                    delay(10L)
                }
            }
        }
    }

    YosWrapper {
        Column(
            Modifier
                .padding(horizontal = 9.dp)
                .onSizeChanged { onHeightChanged?.invoke(it.height) },
            horizontalAlignment = viewAlign
        ) {
            val otherSideAnimate = if (otherSide) {
                TransformOrigin(1f, 0.25f)
            } else {
                TransformOrigin(0f, 0.25f)
            }
            //println("重组：倒计时 "+ mainLyric.isBlank()+ " "+ isCurrentLambda() + " " + (progress() != 0f))

            val otherSideTransformOrigin =
                if (otherSide) TransformOrigin(
                    1f,
                    0.5f
                ) else TransformOrigin(
                    0f,
                    0.5f
                )

            /*val otherSideThisLine = remember(mainLyric) {
                mainLyric.last().second.endsWith(":") || mainLyric.last().second.endsWith(
                    "："
                )
            }*/

            val tweenSpecWithDelay: AnimationSpec<Float> = remember(mainLyric) {
                TweenSpec(
                    durationMillis = 270,
                    easing = yosEasing,
                    delay = /*45*/ /*115*/ 110
                )
            }

            val tweenSpecWithoutDelay: AnimationSpec<Float> = remember(mainLyric) {
                TweenSpec(durationMillis = /*270*/ 300, easing = yosEasing,delay = 45)
            }

            val scale = animateFloatAsState(
                targetValue = if (isCurrentLambda()) 1.005f else 1f,
                animationSpec = if (isCurrentLambda()) tweenSpecWithDelay else tweenSpecWithoutDelay
            )

            /*val blurValue = remember(mainLyric) {
                derivedStateOf {
                    if (blur() == 0f || !showBlur) 0f else blur()
                }
            }*/

            val cardPadding = if (otherSide) {
                Modifier.padding(start = 28.dp)
            } else {
                Modifier.padding(end = 28.dp)
            }

            if (isLyricEmpty()) {
                Column(Modifier.animateContentSize()) {
                    val percent = remember(mainLyric) {
                        derivedStateOf {
                            val m = mainLyric.first().first
                            /*(if ((nextTime() - m) < 900f) {
                                0f
                            } else {
                                */((liveTime.intValue - m).coerceAtLeast(0f) / (nextTime() - m))
                            /*})*/.coerceAtMost(1f)
                        }
                    }
                    val show = remember(mainLyric) {
                        derivedStateOf { (isLyricEmpty() && isCurrentLambda() && percent.value != 0f) }
                    }
                    AnimatedVisibility(
                        show.value,
                        enter = fadeIn(animationSpec = TweenSpec(
                            durationMillis = 550,
                            easing = yosEasing,
                            delay = 300
                        )) + scaleIn(
                            initialScale = 0.85f,
                            transformOrigin = otherSideAnimate,
                            animationSpec = TweenSpec(
                                durationMillis = 550,
                                easing = yosEasing,
                                delay = 300
                            )
                        ),
                        exit = fadeOut() + scaleOut(
                            targetScale = 0.85f,
                            transformOrigin = otherSideAnimate,
                            animationSpec = TweenSpec(
                                durationMillis = 340,
                                easing = yosEasing
                            )
                        )
                    ) {
                        YosWrapper {
                            LyricCard(
                                { scale.value },
                                cardPadding,
                                otherSideTransformOrigin,
                                viewAlign,
                                //{ otherSideThisLine },
                                //onClick
                            ) {

                                Column(
                                    Modifier
                                        .padding(start = 20.dp, end = 20.dp)
                                        .padding(top = 8.dp, bottom = 10.dp),
                                    horizontalAlignment = viewAlign
                                ) {
                                    CountdownAnimation(
                                        { percent.value },
                                        colorLambda = { mainTextBasicColor })
                                }

                            }
                        }
                    }
                }
            } else {
                YosWrapper {
                    LyricCard(
                        { scale.value },
                        cardPadding,
                        otherSideTransformOrigin,
                        viewAlign,
                        //{ otherSideThisLine },
                        //onClick
                    ) {

                        val blurValue = animateDpAsState(
                            blur().dp, SnapSpec(delay = if (isTopLambda()) 260 else 0)
                        )

                        val blurModifier = remember(mainLyric) {
                            derivedStateOf {
                                val thisBlur = blur()
                                if (thisBlur == 0f) {
                                    Modifier
                                } else {
                                    Modifier.blur(
                                        blurValue.value,
                                        /*thisBlur.dp*/
                                        edgeTreatment = BlurredEdgeTreatment.Unbounded,
                                    )
                                }
                            }
                        }

                        YosWrapper {
                            Column(
                                Modifier
                                    .then(blurModifier.value)
                                    .fillMaxWidth(),
                                horizontalAlignment = viewAlign
                            ) {
                                val textAlign = if (otherSide) TextAlign.End else TextAlign.Start

                                val alphaTweenSpecWithDelay: AnimationSpec<Float> =
                                    remember(mainLyric) {
                                        TweenSpec(
                                            durationMillis = 260,
                                            easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f),
                                            delay = 145
                                        )
                                    }

                                val alphaTweenSpecWithoutDelay: AnimationSpec<Float> =
                                    remember(mainLyric) {
                                        TweenSpec(
                                            durationMillis = 260,
                                            easing = CubicBezierEasing(0.25f, 1f, 0.5f, 1f),
                                            delay = 80
                                        )
                                    }

                                YosWrapper {
                                    val thisAlphaAnimated = animateFloatAsState(
                                        // 对齐 Flamingo：当前 1.0 / 非当前 0.4（含逐字行）
                                        targetValue = if (isCurrentLambda()) 1f else 0.4f,
                                        animationSpec = if (isCurrentLambda()) alphaTweenSpecWithDelay else alphaTweenSpecWithoutDelay
                                    )

                                    val thisAlpha = remember(mainLyric) {
                                        derivedStateOf {
                                            thisAlphaAnimated.value
                                        }
                                    }

                                    val otherSidePadding = remember(mainLyric) {
                                        derivedStateOf {
                                            if (otherSide) {
                                                Modifier.padding(
                                                    start = 20.dp,
                                                    end = if (mainLyric.last().second.endsWith("：")) 3.dp else 20.dp
                                                )
                                            } else {
                                                Modifier.padding(
                                                    start = 20.dp,
                                                    end = 20.dp
                                                )
                                            }
                                        }
                                    }

                                    val showHighLight = remember(mainLyric) {
                                        derivedStateOf {
                                            if (isNotOneByOne.value) {
                                                true
                                            } else {
                                                liveTime.intValue >= (mainLyric.lastOrNull()?.first ?: 0f)
                                            }
                                        }
                                    }

                                    Line(
                                        lines = mainLyric,
                                        style = if (otherSide) mainStyle.copy(textAlign = TextAlign.End) else mainStyle,
                                        measurer = measurer,
                                        modifier = Modifier
                                            .graphicsLayer {
                                                this.alpha = thisAlpha.value
                                                compositingStrategy =
                                                    CompositingStrategy.ModulateAlpha
                                            }
                                            .padding(vertical = 4.dp)
                                            .then(otherSidePadding.value)
                                            .clickable(
                                                indication = null,
                                                interactionSource = remember { MutableInteractionSource() }
                                            ) {
                                                onClick()
                                            },
                                        viewAlign = viewAlign
                                    ) { parentConstraints, measureResult ->


                                        if (isNotOneByOne.value) {
                                            // 当不是逐字时
                                            // 不论情况全高亮
                                            return@Line onDrawWithContent {
                                                drawText(
                                                    textLayoutResult = measureResult,
                                                    color = focusedColor
                                                )
                                            }
                                        }

                                        if (!isCurrentLambda()) {
                                            // 是逐字 但不是当前行
                                            // 是否已播放完？
                                            if (showHighLight.value) {
                                                // 高亮
                                                println("高亮：$mainLyric")
                                                return@Line onDrawWithContent {
                                                    drawText(
                                                        textLayoutResult = measureResult,
                                                        color = focusedColor,
                                                        topLeft = Offset(0F, -4F)
                                                    )
                                                }
                                            } else {
                                                // 不高亮
                                                return@Line onDrawWithContent {
                                                    drawText(
                                                        textLayoutResult = measureResult,
                                                        color = unfocusedColor
                                                    )
                                                }
                                            }
                                        }

                                        // 以下为逐字处理

                                        var sum = 0
                                        var lastTime = 0f

                                        val wordsToDraw = arrayListOf<DrawWord>()

                                        var averageTime = 0f

                                        lastTime = mainLyric.first().first

                                        mainLyric.fastForEachIndexed { wordIndex, word ->

                                            // 旧的逐字处理逻辑
                                            /*val process = processWords(word.second)

                                    process.fastForEach { word ->

                                        // 非逐字转移到上面处理
                                        *//*if (isNotOneByOne.value) {
                                                word.split("").fastForEach { charWord ->
                                                    wordsToDraw += DrawWord(
                                                        time = word.first,
                                                        word = charWord,
                                                        layout = measurer.measure(
                                                            text = charWord,
                                                            style = mainStyle,
                                                            constraints = measureResult.layoutInput.constraints,
                                                            layoutDirection = if (viewAlign.value == Alignment.End) LayoutDirection.Rtl else LayoutDirection.Ltr
                                                        ),
                                                        topLeft = measureResult.getBoundingBox(sum.coerceAtMost(
                                                            mainLyric.sumOf { it.second.length } - 1).coerceAtLeast(0)).topLeft,
                                                        brush = { _, _ ->
                                                               focusedSolidBrush
                                                        }
                                                    ).also {
                                                        sum += charWord.length
                                                    }
                                                }

                                                return@fastForEach
                                            }*//*
                                        }*/

                                            //println(word.second + "：" + sum.coerceAtMost(mainLyric.sumOf { it.second.length } - 1).coerceAtLeast(0) + "，共 "+ mainLyric.sumOf { it.second.length })

                                            // 新逻辑

                                            val thisWord = word.second

                                            if (thisWord.isEmpty()) {
                                                return@fastForEachIndexed
                                            }

                                            averageTime = (word.first - lastTime) / thisWord.length

                                            val thisWordGroupLastTime = if (wordIndex - 1 < 0) {
                                                mainLyric.first().first
                                            } else {
                                                mainLyric[(wordIndex - 1)].first
                                            }
                                            // 浮起（对齐 Flamingo）：提前 180ms 起坡，
                                            // 窗口 = 180ms + 词时长 + 400ms，smoothstep 缓动
                                            val wordDurationMs0 = word.first - thisWordGroupLastTime
                                            val riseWindow = 180f + wordDurationMs0 + 400f
                                            val riseT =
                                                if (riseWindow <= 0f) 0f
                                                else ((liveTime.intValue - (thisWordGroupLastTime - 180f)) / riseWindow).coerceIn(0f, 1f)
                                            val riseSmooth = riseT * riseT * (3f - 2f * riseT)
                                            val topLeftWeight = 4 * riseSmooth

                                            val wordDurationMs = word.first - thisWordGroupLastTime
                                            // 捕获词组时间参数供辉光lambda使用
                                            val capturedWordGroupStartTime = thisWordGroupLastTime
                                            val capturedWordDuration = wordDurationMs

                                            thisWord.forEach { char ->

                                                //println("$char：$lastTime to ${lastTime + averageTime}")

                                                val charWord = char.toString()

                                                val layout = measurer.measure(
                                                    text = charWord,
                                                    style = if (otherSide) mainStyle.copy(
                                                        textAlign = TextAlign.End
                                                    ) else mainStyle,
                                                    constraints = measureResult.layoutInput.constraints
                                                )

                                                val thisWordLastTime = lastTime
                                                val thisWordAverageTime = averageTime

                                                wordsToDraw += DrawWord(
                                                    time = lastTime + averageTime,
                                                    word = charWord,
                                                    glowStart = thisWordLastTime,
                                                    glowEnd = thisWordLastTime + thisWordAverageTime,
                                                    layout = layout,
                                                    topLeft = measureResult.getBoundingBox(sum.coerceAtMost(
                                                        mainLyric.sumOf { it.second.length } - 1)
                                                        .coerceAtLeast(0)).topLeft.minus(
                                                            Offset(
                                                                0F,
                                                                topLeftWeight
                                                            )
                                                            ),
                                                    brush = { px, percent ->
                                                        if (thisWord == " ") {
                                                            return@DrawWord unfocusedSolidBrush
                                                        }

                                                        val beforeColor = if (percent <= -0.5f) {
                                                            unfocusedColor
                                                        } else {
                                                            focusedColor
                                                        }

                                                        val afterColor = if (percent >= 1f) {
                                                            focusedColor
                                                        } else {
                                                            unfocusedColor
                                                        }
                                                        Brush.horizontalGradient(
                                                            0f to beforeColor,
                                                            (percent - px).coerceIn(0f, 1f) to beforeColor,
                                                            (percent + px).coerceIn(0f, 1f) to afterColor
                                                        )
                                                    },
                                                    percent = {
                                                        if (thisWord == " ") {
                                                            return@DrawWord 0f
                                                        }

                                                        ((liveTime.intValue - thisWordLastTime) / thisWordAverageTime)

                                                    },
glow = {
                                                        // 逐字窗口：本字只在自己的时间区间内发光
                                                        // （修复同词多字时首、尾字错峰长音同时发光）
                                                        val currentGlowTime = glowTime.value
                                                        if (currentGlowTime < 0f) {
                                                            return@DrawWord 0f
                                                        }

                                                        val charDuration = thisWordAverageTime
                                                        if (charDuration < LyricGlowMinNoteMs) {
                                                            return@DrawWord 0f
                                                        }

                                                        val charEnd = thisWordLastTime + charDuration
                                                        if (currentGlowTime < thisWordLastTime || currentGlowTime > charEnd) {
                                                            return@DrawWord 0f
                                                        }

                                                        val t = ((currentGlowTime - thisWordLastTime) / charDuration).coerceIn(0f, 1f)

                                                        // 单峰海浪包络：前半段 bezIn 升峰、后半段 bezOut 衰减
                                                        val envelope = if (t < 0.5f) {
                                                            glowCubicBezier(t * 2f, LyricGlowBezInP1, LyricGlowBezInP2)
                                                        } else {
                                                            1f - glowCubicBezier((t - 0.5f) * 2f, LyricGlowBezOutP1, LyricGlowBezOutP2)
                                                        }

                                                        // 强度按本字时长缩放：短音更淡、长音更亮
                                                        val durationScale = ((charDuration - LyricGlowMinNoteMs) /
                                                            (LyricGlowFullNoteMs - LyricGlowMinNoteMs)).coerceIn(0f, 1f)
                                                        envelope * LyricGlowMaxAlpha * sqrt(durationScale)
                                                    }
                                                ).also {
                                                    sum += charWord.length
                                                    lastTime += averageTime
                                                }
                                            }
                                        }

                                        onDrawBehind {
                                            // 每帧全行只放行一个字发光：重复字（同词内同形字）各自窗口重叠时
                                            // 也只亮当前这一字，杜绝“念一个字两个同形字一起亮”。
                                            val glowNow = glowTime.value
                                            var activeGlowIdx = -1
                                            if (glowNow >= 0f) {
                                                for (i in wordsToDraw.indices) {
                                                    val w = wordsToDraw[i]
                                                    if (glowNow >= w.glowStart && glowNow < w.glowEnd) {
                                                        activeGlowIdx = i
                                                        break
                                                    }
                                                }
                                            }
                                            wordsToDraw.fastForEachIndexed { i, l ->
                                                val glowStrength = if (i == activeGlowIdx) l.glow() else 0f

                                                // ==== 临时诊断：打印每帧命中辉光窗口的所有字 ====
                                                if (glowNow >= 0f && i == activeGlowIdx) {
                                                    val dbg = StringBuilder()
                                                    wordsToDraw.fastForEachIndexed { j, w2 ->
                                                        if (glowNow >= w2.glowStart && glowNow < w2.glowEnd) {
                                                            dbg.append(" #").append(j).append("'").append(w2.word)
                                                                .append("'w[${w2.glowStart.toInt()},${w2.glowEnd.toInt()})x=${w2.topLeft.x.toInt()}")
                                                        }
                                                    }
                                                    println("GLOWDBG t=${glowNow.toInt()} active=$activeGlowIdx hit=$dbg")
                                                }
                                                
                                                // 绘制辉光层（如果有辉光强度）
                                                if (glowStrength > 0.01f) {
                                                    drawIntoCanvas { canvas ->
                                                        val blurRadius = LyricGlowBlurRadius * density
                                                        
                                                        // saveLayer用于隔离模糊效果
                                                        canvas.nativeCanvas.saveLayer(
                                                            l.topLeft.x - blurRadius,
                                                            l.topLeft.y - blurRadius,
                                                            l.topLeft.x + l.layout.size.width + blurRadius,
                                                            l.topLeft.y + l.layout.size.height + blurRadius,
                                                            null
                                                        )
                                                        
                                                        // 绘制模糊的白色文字（辉光底层）
                                                        drawText(
                                                            textLayoutResult = l.layout,
                                                            topLeft = l.topLeft,
                                                            color = Color.White.copy(alpha = glowStrength),
                                                            shadow = Shadow(
                                                                color = Color.White.copy(alpha = glowStrength),
                                                                offset = Offset.Zero,
                                                                blurRadius = blurRadius
                                                            )
                                                        )
                                                        
                                                        // 镂空字芯：用Clear模式擦除中间部分
                                                        drawText(
                                                            textLayoutResult = l.layout,
                                                            topLeft = l.topLeft,
                                                            color = Color.White,
                                                            blendMode = BlendMode.Clear
                                                        )
                                                        
                                                        canvas.nativeCanvas.restore()
                                                    }
                                                }
                                                drawText(
                                                    textLayoutResult = l.layout,
                                                    topLeft = l.topLeft,
                                                    brush = l.brush(
                                                        0.3f,
                                                        l.percent()
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                                YosWrapper {
                                    AnimatedVisibility(showTranslation && translation != null) {
                                        translation?.let {
                                            val translationAlpha = animateFloatAsState(
                                                targetValue = if (isCurrentLambda()) 0.5f else 0.14f,
                                                animationSpec = if (isCurrentLambda()) alphaTweenSpecWithDelay else alphaTweenSpecWithoutDelay
                                            )

                                            val translationOtherSidePadding = if (otherSide) {
                                                Modifier.padding(
                                                    start = 20.dp,
                                                    end = 20.dp
                                                )
                                            } else {
                                                Modifier.padding(
                                                    start = 20.dp,
                                                    end = 20.dp
                                                )
                                            }

                                            Text(
                                                text = it,
                                                fontSize = subTextSize.sp,
                                                color = subTextBasicColor,
                                                fontWeight = FontWeight.Normal,
                                                modifier = Modifier
                                                    .graphicsLayer {
                                                        this.alpha =
                                                            translationAlpha.value
                                                        compositingStrategy =
                                                            CompositingStrategy.ModulateAlpha
                                                    }
                                                    .then(translationOtherSidePadding)
                                                    .padding(top = 5.dp),
                                                lineHeight = (subTextSize + 5).sp,
                                                letterSpacing = 0.3.sp,
                                                textAlign = textAlign
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

@Composable
private fun LyricCard(
    scale: () -> Float,
    cardPadding: Modifier,
    otherSideTransformOrigin: TransformOrigin,
    viewAlign: Alignment.Horizontal,
    //otherSideThisLine: () -> Boolean,
    //onClick: () -> Unit,
    content: @Composable () -> Unit,
) =
    YosWrapper {
        Column(
            modifier = Modifier
                .graphicsLayer {
                    //compositingStrategy = CompositingStrategy.ModulateAlpha
                    val scaleValue = scale()
                    scaleX = scaleValue
                    scaleY = scaleValue
                    transformOrigin = otherSideTransformOrigin
                }
                .fillMaxWidth()
                .then(cardPadding)
                .padding(top = 9.dp, bottom = 9.dp),
            horizontalAlignment = viewAlign
        ) {
            content()
        }
    }

@Composable
fun CountdownAnimation(progress: () -> Float, colorLambda: () -> Color) {
    val infiniteTransition = rememberInfiniteTransition()
    val scale = infiniteTransition.animateFloat(
        initialValue = 0.95f,
        targetValue = 1.12f,
        animationSpec = infiniteRepeatable(
            animation = tween(1800, easing = yosEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = Modifier.graphicsLayer {
            //compositingStrategy = CompositingStrategy.Offscreen
            scaleX = scale.value
            scaleY = scale.value
            alpha = 0.8f
        },
        contentAlignment = Alignment.CenterStart
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(horizontal = 5.dp)
        ) {
            for (i in 1..3) {
                /*val alpha = animateFloatAsState(
                    targetValue = if (progress() >= i / 4f) min(
                        1f,
                        (progress() - (i - 1) / 4f) * 4
                    ) else 0f,
                    animationSpec = tween(
                        if (progress() > 0) (progress() * 1200).toInt() else 1200,
                        easing = LinearEasing
                    )
                )*/

                val average = 1f / 3f
                val beforePadding = (i-1) * average
                val thisPercent = (progress() - beforePadding)  / ((i * average) - beforePadding)
                val alpha = 0.2f + (0.8f * thisPercent).coerceIn(0f, 0.8f)

                Box(
                    modifier = Modifier
                        .size(11.dp)
                        .background(
                            colorLambda().copy(alpha = alpha),
                            shape = CircleShape
                        )
                )
            }
        }
    }
}


/** 主行样式（对齐 Flamingo：37.5/47.5sp）。@Composable 使字号缩放/字重/行平衡设置变化即时生效。 */
@Composable
fun mainTextStyle(): TextStyle {
    // LyricFontScale 即字号百分比（设置页 80~120），与 fontScale 全局 clamp 同义
    val scale = SettingsLibrary.LyricFontScale.coerceIn(0.8f, 1.2f)
    return TextStyle(
        fontSize = (37.5f * scale).sp,
        lineHeight = (47.5f * scale).sp,
        fontWeight =
        when (SettingsLibrary.LyricFontWeight) {
            "Thin" -> FontWeight.Thin
            "ExtraLight" -> FontWeight.ExtraLight
            "Light" -> FontWeight.Light
            "Regular" -> FontWeight.Normal
            "Medium" -> FontWeight.Medium
            "SemiBold" -> FontWeight.SemiBold
            "Bold" -> FontWeight.Bold
            "ExtraBold" -> FontWeight.ExtraBold
            "Black" -> FontWeight.Black
            else -> FontWeight.ExtraBold
        },
        letterSpacing = 0.05.sp,
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None
        ),
        lineBreak = LineBreak(
            strategy = if (SettingsLibrary.LyricLineBalance) LineBreak.Strategy.Balanced else LineBreak.Strategy.Simple,
            LineBreak.Strictness.Default,
            LineBreak.WordBreak.Default
        )
    )
}

/*val BackgroundTextStyle = TextStyle(
    fontSize = 34.sp,
    lineHeight = 42.sp,
    fontWeight = FontWeight.Bold
).copy(
    lineHeightStyle = LineHeightStyle(
        alignment = LineHeightStyle.Alignment.Center,
        trim = LineHeightStyle.Trim.None
    )
)*/

@Stable
private data class DrawWord(
    val time: Float,
    val word: String,
    val layout: TextLayoutResult,
    val topLeft: Offset,
    val brush: (px: Float, percent: Float) -> Brush,
    val percent: () -> Float,
    /** 当前字的长音辉光不透明度（0 = 不画辉光） */
    val glow: () -> Float = { 0f },
    /** 本字辉光窗口 [glowStart, glowEnd)，用于每帧只放行一个发光字 */
    val glowStart: Float = 0f,
    val glowEnd: Float = 0f
)

/*
fun processWords(input: String): List<String> {
    val result = mutableListOf<String>()
    var word = ""
    for (char in input) {
        if (char == ' ') {
            if (word.isNotEmpty()) {
                result.add(word)
                word = ""
            }
            result.add(" ")
        } else {
            word += char
        }
    }
    if (word.isNotEmpty()) {
        result.add(word)
    }
    return result
}*/
