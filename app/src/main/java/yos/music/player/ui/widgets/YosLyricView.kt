package yos.music.player.ui.widgets

import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.EaseInOutQuad
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.snap
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
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import android.util.Log
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
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
import yos.music.player.code.utils.lrc.LyricLayoutSpec
import yos.music.player.code.utils.lrc.LyricPreparedCache
import yos.music.player.code.utils.lrc.LyricRenderWarmup
import yos.music.player.code.utils.lrc.buildSyncedLyrics
import yos.music.player.code.utils.lrc.lyricListStateKey
import yos.music.player.code.utils.lrc.lyricMainTextStyle
import yos.music.player.code.utils.lrc.lyricRenderKey
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MainViewModelObject
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.ui.widgets.basic.YosWrapper
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas

import androidx.compose.runtime.produceState
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.ui.composable.list.LyricsLazyListState
import com.mocharealm.accompanist.lyrics.ui.composable.list.rememberLyricsLazyListState
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLyricsView
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsAnchor
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.LyricsFade
import com.mocharealm.accompanist.lyrics.ui.composable.lyrics.KaraokeLineText

// ===== 已唱字色（对齐 Flamingo：白 + 封面提取色）=====
// 基色 = 70% 白 + 30% 封面提取色；未唱字 = 白 50%。提取色由 YosLyricView 顶层按当前封面更新。
private const val LyricSungCoverMix = 0.3f

private object LyricCoverTint {
    val color = mutableStateOf(Color.White)
}


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
 * 主行样式（对齐 Flamingo：37.5/47.5sp）。@Composable 使字号缩放/字重/行平衡设置变化即时生效。
 * 样式构造本身在 [lyricMainTextStyle] 里，后台预热读同一份，保证两边排版输入一致。
 */
@Composable
fun mainTextStyle(): TextStyle = lyricMainTextStyle()

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


/**
 * 歌词页（整页交给第三方库）。
 *
 * 与逐行版相比：整份歌词只有一个 scene / 一条时间轴，排版只在歌词变化时做一次；
 * 列表、自动滚动、预取、行内 reveal 都由库负责，避免每行各自排版造成的滚动卡顿
 * 以及「原文晚到、译文先出现」。
 *
 * 参数保持与旧 [YosLyricView] 一致，调用方无需改动。
 */
@Composable
fun YosLyricViewLib(
    lrcEntriesLambda: () -> List<LyricEntry>,
    liveTimeLambda: () -> Int,
    mediaEvent: YosMediaEvent,
    lyricState: YosLyricScrollState,
    translationLambda: () -> Boolean = { true },
    blurLambda: () -> Boolean = { false },
    uiConfig: YosUIConfig = YosUIConfig(),
    weightLambda: () -> Boolean,
    modifier: Modifier,
    onBackClick: () -> Unit,
    lastLineObstructionFraction: Float = 0f,
) {
    val entries = lrcEntriesLambda()
    val otherSideForLines = MediaViewModelObject.otherSideForLines
    val style = mainTextStyle()
    val textColor = lerp(Color.White, LyricCoverTint.color.value, LyricSungCoverMix)
    val context = LocalContext.current

    val lyrics = remember(entries) {
        buildSyncedLyrics(entries, otherSideForLines)
    }

    // 库要求 currentPosition 必须来自 Compose 状态，否则 snapshotFlow 收不到变化、歌词不会走。
    // 播放器位置是普通 getter，这里用一个 60Hz 的镜像状态桥接（只在值变化时写入）。
    val positionState = remember { mutableIntStateOf(liveTimeLambda()) }
    LaunchedEffect(Unit) {
        while (true) {
            withFrameNanos {
                val value = liveTimeLambda()
                if (value != positionState.intValue) positionState.intValue = value
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() }
            ) { onBackClick() }
    ) {
        if (entries.isEmpty()) {
            Text(
                text = uiConfig.noLrcText,
                color = Color(0x66FFFFFF),
                modifier = Modifier.align(Alignment.Center)
            )
        } else {
            // 库内部左右各留 16dp，封面是 32dp；这里再补 16dp，两侧与封面齐平
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                val localDensity = LocalDensity.current
                // 库内部排版宽度 = maxWidth - 32dp
                val layoutWidthPx =
                    with(localDensity) { (maxWidth - 32.dp).toPx() }.coerceAtLeast(1f)
                // 缓存键由 LyricRenderCache 统一生成：后台预热用同一个键，页面打开即命中。
                val mediaIdKey = MediaViewModelObject.lyricMediaId.value ?: entries.hashCode().toString()
                // 设置项须作为 remember 的键读取，否则改了字号/字重/行平衡仍会命中旧排版。
                val fontScaleSetting = SettingsLibrary.LyricFontScale
                val fontWeightSetting = SettingsLibrary.LyricFontWeight
                val lineBalanceSetting = SettingsLibrary.LyricLineBalance
                val cacheKey = remember(
                    lyrics,
                    layoutWidthPx,
                    fontScaleSetting,
                    fontWeightSetting,
                    lineBalanceSetting,
                    localDensity.fontScale,
                ) {
                    lyricRenderKey(
                        mediaIdKey = mediaIdKey,
                        layoutWidthDp = layoutWidthPx / localDensity.density,
                        subTextSize = uiConfig.subTextSize,
                        systemFontScale = localDensity.fontScale,
                        entriesSize = entries.size,
                        fontScaleSetting = fontScaleSetting,
                        fontWeightSetting = fontWeightSetting,
                        lineBalance = lineBalanceSetting,
                    )
                }
                // 排版结果只读缓存：这里绝不发起测量。组合期测量的结果回写状态会让库的
                // remember(request, preparedLyrics) 键翻转，整份 scene 作废重建 = 一次可见闪变。
                // 未预热时交给库自己排一次（冷路径，与预热前观感相同）。
                val preparedLyrics = remember(cacheKey) {
                    LyricPreparedCache.preparedFor(cacheKey)
                }
                // 列表状态缓存：同一首歌切页回来直接复用，不再从顶部滚一次。
                // 键只含歌曲与栏宽、不含字号/字重：改样式不该把歌词弹回顶部重滚。
                val listStateKey = lyricListStateKey(mediaIdKey, layoutWidthPx / localDensity.density)
                val listState = remember(listStateKey) {
                    LyricPreparedCache.listStateFor(listStateKey) ?: LyricsLazyListState()
                }
                LaunchedEffect(cacheKey) {
                    LyricPreparedCache.putListState(listStateKey, listState)
                    // 歌词页已接管这首歌：放弃同键后台预热，避免两边重复测量
                    LyricRenderWarmup.markLive(cacheKey)
                    // 记录真实排版宽度，供下次启动时的预热估算（按横竖屏分别保存）
                    LyricLayoutSpec.record(context, layoutWidthPx, localDensity.density)
                    // 命中与否是「打开播放页有没有卡一下」的直接判据
                    runCatching {
                        yos.music.player.code.utils.others.YosDiagnostics.log(
                            "LYRIC_RENDER_READY",
                            "warm" to (preparedLyrics != null),
                            "lines" to entries.size,
                        )
                    }
                }
                DisposableEffect(cacheKey) {
                    onDispose {
                        LyricRenderWarmup.clearLive(cacheKey)
                        // 页面在预热完成前就打开了 → 预热已被取消，库里那份排版也拿不到。
                        // 离开歌词页时补一次后台预热，否则「开歌词页→返回→再打开」会每次重排。
                        if (LyricPreparedCache.preparedFor(cacheKey) == null) {
                            LyricRenderWarmup.onLyricsPublished(
                                context = context,
                                mediaIdKey = mediaIdKey,
                                entries = entries,
                                otherSideForLines = otherSideForLines.toList(),
                                reason = "rewarm_after_leave",
                            )
                        }
                    }
                }
                // 进入页面后的第一次跟随用瞬时定位：离开歌词页期间播放位置已经变了，
                // 回来时不要从旧位置滚过去。之后恢复正常补间。
                var firstFollow by remember { mutableStateOf(true) }
                LaunchedEffect(Unit) {
                    delay(600)
                    firstFollow = false
                }
                KaraokeLyricsView(
                    listState = listState,
                    lyrics = lyrics,
                    currentPosition = { positionState.intValue },
                    onLineClicked = { line -> mediaEvent.onSeek(line.start) },
                    onLinePressed = {},
                    modifier = Modifier.fillMaxSize(),
                    normalLineTextStyle = style,
                    translationTextStyle = style.copy(
                        fontSize = uiConfig.subTextSize.sp,
                        fontWeight = FontWeight.Normal,
                    ),
                    accompanimentLineTextStyle = style.copy(fontSize = 22.sp),
                    phoneticTextStyle = style.copy(fontSize = 18.sp),
                    textColor = textColor,
                    // HDR：加色混合，让歌词在封面上泛光
                    blendMode = if (SettingsLibrary.LyricHdrEffect) BlendMode.Plus else BlendMode.SrcOver,
                    useBlurEffect = blurLambda(),
                    showTranslation = translationLambda(),
                    showPhonetic = false,
                    anchor = LyricsAnchor.Fraction(0.0618f),
                    topFade = LyricsFade.ToAnchor(16.dp),
                    bottomFade = LyricsFade.Fraction(0.5f),
                    itemSpacing = 12.dp,
                    scrollAnimationSpec =
                        if (firstFollow) snap() else tween(650, easing = FastOutSlowInEasing),
                    preparedLyrics = preparedLyrics,
                    // 场景缓存：切页回来复用排版 + 渲染资源 + 已栅格化的行，等同页面常驻
                    sceneCacheKey = "$cacheKey|${textColor.value}",
                )
            }
        }
    }
}
