package yos.music.player.code.utils.lrc

import android.content.Context
import android.content.res.Configuration
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.sp
import com.mocharealm.accompanist.lyrics.core.model.SyncedLyrics
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeAlignment
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeLine
import com.mocharealm.accompanist.lyrics.core.model.karaoke.KaraokeSyllable
import com.mocharealm.accompanist.lyrics.ui.composable.list.LyricsLazyListState
import com.mocharealm.accompanist.lyrics.ui.preparation.PreparedLyrics
import com.mocharealm.accompanist.lyrics.ui.preparation.prepareLyrics
import com.mocharealm.accompanist.lyrics.ui.profile.DefaultLyricsProfiles
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
import kotlin.math.roundToInt

/** 主行字号（sp）。样式构造与预热共用同源常量。 */
const val LyricMainFontSizeSp = 37.5f

/** 歌词主行行高（sp）。 */
private const val LyricMainLineHeightSp = 47.5f

/** 歌词区左右各留 16dp 与封面齐平，库内部再各留 16dp。 */
private const val LyricHorizontalGutterDp = 16f

/** 库内部左右各留 16dp：排版宽度 = 可用宽度 − 32dp（应用侧再减 32dp）。 */
private const val LyricLibraryGutterDp = 32f

fun isCjkChar(c: Char): Boolean {
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
 * 把本仓库的 (结束时间, 文本) 序列转换成第三方库的 KaraokeLine：词/字起点 = 上一个词的结束时间。
 * UI 组合与后台预热必须走同一份实现，否则两边排版输入不同、缓存键相同也会画出不同结果。
 */
private fun List<Pair<Float, String>>.toKaraokeLine(
    translation: String?,
    otherSide: Boolean,
): KaraokeLine {
    val lineStart = firstOrNull()?.first ?: 0f
    val lineEnd = lastOrNull()?.first ?: lineStart
    val syllables = ArrayList<KaraokeSyllable>(size)
    var cursor = lineStart
    for ((end, text) in this) {
        if (text.isEmpty()) {
            cursor = maxOf(cursor, end)
            continue
        }
        val safeEnd = maxOf(end, cursor)
        syllables += KaraokeSyllable(
            text,
            cursor.toInt(),
            safeEnd.toInt(),
            null,
            if (text.any { isCjkChar(it) }) "zh-Hans" else "en",
        )
        cursor = safeEnd
    }
    return KaraokeLine.MainKaraokeLine(
        syllables,
        translation,
        if (otherSide) KaraokeAlignment.End else KaraokeAlignment.Start,
        lineStart.toInt(),
        lineEnd.toInt(),
    )
}

/** [LyricEntry] → 库的 [SyncedLyrics]。UI 与预热共用。 */
fun buildSyncedLyrics(
    entries: List<LyricEntry>,
    otherSideForLines: List<Boolean>,
): SyncedLyrics = SyncedLyrics(
    entries.mapIndexed { index, entry ->
        entry.mainLyric.toKaraokeLine(
            entry.translation ?: entry.romanization,
            otherSideForLines.getOrElse(index) { false },
        )
    }
)

/** 排版用的四套文本样式（主行/对唱/音译/翻译），UI 与预热共用。 */
class LyricRenderStyles(
    val normal: TextStyle,
    val accompaniment: TextStyle,
    val phonetic: TextStyle,
    val translation: TextStyle,
)

/**
 * 主行样式（对齐 Flamingo：37.5/47.5sp）。纯函数版：供后台预热调用；
 * [yos.music.player.ui.widgets.mainTextStyle] 保留 @Composable 语义供组合读取。
 */
fun lyricMainTextStyle(): TextStyle {
    // LyricFontScale 即字号百分比（设置页 80~120），与 fontScale 全局 clamp 同义
    val scale = SettingsLibrary.LyricFontScale.coerceIn(0.8f, 1.2f)
    return TextStyle(
        fontSize = (LyricMainFontSizeSp * scale).sp,
        lineHeight = (LyricMainLineHeightSp * scale).sp,
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

fun lyricRenderStyles(subTextSize: Int): LyricRenderStyles {
    val normal = lyricMainTextStyle()
    return LyricRenderStyles(
        normal = normal,
        accompaniment = normal.copy(fontSize = 22.sp),
        phonetic = normal.copy(fontSize = 18.sp),
        translation = normal.copy(fontSize = subTextSize.sp, fontWeight = FontWeight.Normal),
    )
}

/**
 * 歌词排版缓存键的唯一生成处。
 *
 * 必须把「改变排版几何」的全部输入纳入：排版宽度、字号缩放/字重/行平衡、译文字号、行数、
 * 系统字体缩放。UI 与后台预热共用本函数，键相同即视为排版结果可互换。
 *
 * 宽度按 **dp 取整**而非像素：预热侧的宽度来自屏幕宽度推算、UI 侧来自实测，
 * 两者在 px 上常差一两像素（dp↔px 往返取整），按 px 做键会白丢预热结果。
 *
 * 设置项作为参数传入而不是函数内部直读：组合侧要把它们当 `remember` 的键，
 * 否则改了字号/字重后缓存键不变、仍命中旧排版。
 */
fun lyricRenderKey(
    mediaIdKey: String,
    layoutWidthDp: Float,
    subTextSize: Int,
    systemFontScale: Float,
    entriesSize: Int,
    fontScaleSetting: Float = SettingsLibrary.LyricFontScale,
    fontWeightSetting: String = SettingsLibrary.LyricFontWeight,
    lineBalance: Boolean = SettingsLibrary.LyricLineBalance,
): String = buildString {
    append(mediaIdKey)
    append('|').append(layoutWidthDp.roundToInt())
    append('|').append(fontScaleSetting)
    append('|').append(fontWeightSetting)
    append('|').append(lineBalance)
    append('|').append(subTextSize)
    append('|').append(entriesSize)
    append('|').append((systemFontScale * 100).roundToInt())
}

/**
 * 歌词列表滚动状态的缓存键。
 *
 * 与排版键（[lyricRenderKey]）刻意分开：滚动位置只跟「哪首歌 + 多宽的一栏」有关，
 * 与字号/字重/行平衡无关。若沿用排版键，改一次字重就会新建一个 [LyricsLazyListState]，
 * 歌词会跳回顶部再滚回来——看起来像重载，而不是换样式。
 *
 * 宽度必须留在键里：竖屏整页与横屏右栏是两个不同的列表，共用一个 LazyListState
 * 会在交接时丢手势（见 YosLyricView 里 LazyListState 单归属的说明）。
 */
fun lyricListStateKey(mediaIdKey: String, layoutWidthDp: Float): String =
    "$mediaIdKey|${layoutWidthDp.roundToInt()}"

/**
 * 跨页面切换保留的歌词排版结果：歌词页被销毁后回来直接复用，不重排、不重滚。
 * 保留最近三首，A→B→A 也不用重排；预热结果也进这里，页面打开即命中。
 */
object LyricPreparedCache {
    private const val MAX_ENTRIES = 3
    private const val MAX_LIST_STATES = 2

    private val prepared =
        object : LinkedHashMap<String, PreparedLyrics>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, PreparedLyrics>?,
            ): Boolean = size > MAX_ENTRIES
        }

    private val listStates =
        object : LinkedHashMap<String, LyricsLazyListState>(4, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, LyricsLazyListState>?,
            ): Boolean = size > MAX_LIST_STATES
        }

    @Synchronized
    fun preparedFor(key: String): PreparedLyrics? = prepared[key]

    @Synchronized
    fun putPrepared(key: String, value: PreparedLyrics) {
        prepared[key] = value
    }

    @Synchronized
    fun listStateFor(key: String): LyricsLazyListState? = listStates[key]

    @Synchronized
    fun putListState(key: String, value: LyricsLazyListState) {
        listStates[key] = value
    }
}

/**
 * 歌词区排版宽度（dp）的机型/方向标定。
 *
 * 预热发生在歌词页从未被组合过的时候，拿不到 `BoxWithConstraints` 的真实宽度，
 * 只能靠上一次进入歌词页时记下的值（按横/竖屏各存一份，单位 dp 以免密度变化失真）。
 * 未记录时竖屏按「屏宽 − 左右各 32dp」估算；估算不符只会浪费一次后台测量，
 * 因为缓存键里带宽度，页面组合时对不上即当作未预热。
 */
object LyricLayoutSpec {
    private const val MMKV_ID = "sonify_lyric_layout"
    private const val KEY_PORTRAIT = "portrait_width_dp"
    private const val KEY_LANDSCAPE = "landscape_width_dp"
    /** 记录时的屏幕宽度：分屏/折叠屏改变屏宽后旧记录不再可信。 */
    private const val KEY_PORTRAIT_SCREEN = "portrait_screen_dp"
    private const val KEY_LANDSCAPE_SCREEN = "landscape_screen_dp"

    private val store by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull()
    }

    private fun slot(configuration: Configuration): String =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            KEY_LANDSCAPE
        } else {
            KEY_PORTRAIT
        }

    private fun screenSlot(configuration: Configuration): String =
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) {
            KEY_LANDSCAPE_SCREEN
        } else {
            KEY_PORTRAIT_SCREEN
        }

    /** 记录歌词区真实排版宽度（px）。在歌词页组合时调用。 */
    fun record(context: Context, widthPx: Float, density: Float) {
        val s = store ?: return
        if (widthPx <= 0f || density <= 0f) return
        val configuration = context.resources.configuration
        runCatching {
            s.encode(slot(configuration), widthPx / density)
            s.encode(screenSlot(configuration), context.resources.displayMetrics.widthPixels / density)
        }
    }

    /**
     * 预热用的排版宽度（dp）。
     *
     * 横屏歌词只占右侧一栏，宽度由封面列宽推算、误差大：未记录时返回 null，宁可不预热
     * 也不要塞错键的缓存。竖屏未记录时按「屏宽 − 左右各 32dp」估算；估算不符只会浪费
     * 一次后台测量（缓存键里带宽度，页面组合时对不上即当未预热）。
     *
     * 屏幕宽度用 `widthPixels / density` 而非 `screenWidthDp`：后者是截断成整数的 dp，
     * 会让估算值比真实排版宽度少 1dp，缓存键对不上就白预热了。
     */
    fun widthDp(context: Context): Float? {
        val configuration = context.resources.configuration
        val metrics = context.resources.displayMetrics
        val density = metrics.density
        if (density <= 0f) return null
        val screenWidthDp = metrics.widthPixels / density
        if (screenWidthDp <= 0f) return null
        val recorded = runCatching { store?.decodeFloat(slot(configuration), -1f) }.getOrNull() ?: -1f
        val recordedScreen =
            runCatching { store?.decodeFloat(screenSlot(configuration), -1f) }.getOrNull() ?: -1f
        // 屏宽变了（分屏/折叠/旋转后尺寸不同）→ 旧记录失效
        if (recorded > 0f && kotlin.math.abs(recordedScreen - screenWidthDp) < 1f) return recorded
        if (configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return null
        return (screenWidthDp - LyricHorizontalGutterDp * 2 - LyricLibraryGutterDp)
            .coerceAtLeast(1f)
    }
}

/**
 * 歌词排版预热：歌词数据一发布就在后台把整首歌词的排版算好塞进 [LyricPreparedCache]，
 * 用户打开歌词页时直接命中，不再现场测量（那是「打开卡一下再闪现」的直接原因）。
 *
 * 与「歌词数据缓存」是两件事：数据在切歌时就已就绪，冷的是排版/渲染。
 *
 * 红线：
 *  - 预热参数必须与库的 `LyricsLayoutRequest.prepare()` 逐项一致
 *    （profiles / showPhonetic / fontScale / 四套样式 / 宽度），否则预热结果与真机排版不同。
 *  - 歌词页正在组合同一首歌时必须放弃预热（[markLive]），不能两边同时测量。
 *  - 预热只写渲染缓存，绝不写 [MediaViewModelObject]（预取下一首时尤其如此）。
 */
object LyricRenderWarmup {
    /** 发布后稍等再算：切歌瞬间还要起播/出封面，把 CPU 让出去。 */
    private const val SETTLE_DELAY_MS = 400L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Volatile
    private var liveKey: String? = null

    @Volatile
    private var jobKey: String? = null

    private var job: Job? = null

    /**
     * 歌词已发布：后台预排版。
     *
     * @param mediaIdKey 与 UI 组合时生成缓存键所用的同一标识。必须显式传入：
     *   「预取下一首」时全局 `lyricMediaId` 仍是当前曲，读全局会把缓存塞到错误的键上。
     */
    fun onLyricsPublished(
        context: Context?,
        mediaIdKey: String,
        entries: List<LyricEntry>,
        otherSideForLines: List<Boolean>,
        reason: String,
    ) {
        if (context == null || entries.isEmpty() || mediaIdKey.isBlank()) return
        val appContext = context.applicationContext
        val subTextSize = YosUIConfig().subTextSize
        val widthDp = LyricLayoutSpec.widthDp(appContext) ?: run {
            label(reason, "skip", "no_width_spec")
            return
        }
        val density = appContext.resources.displayMetrics.density
        val fontScale = appContext.resources.configuration.fontScale
        val widthPx = widthDp * density
        val key = lyricRenderKey(mediaIdKey, widthDp, subTextSize, fontScale, entries.size)

        if (key == liveKey) {
            label(reason, "skip", "live")
            return
        }
        if (LyricPreparedCache.preparedFor(key) != null) {
            label(reason, "skip", "cached")
            return
        }

        job?.cancel()
        jobKey = key
        job = scope.launch {
            delay(SETTLE_DELAY_MS)
            // 等待期间可能已进歌词页 / 已切歌
            if (liveKey == key) return@launch
            if (LyricPreparedCache.preparedFor(key) != null) return@launch

            val startedAt = System.currentTimeMillis()
            val prepared = runCatching {
                val styles = lyricRenderStyles(subTextSize)
                prepareLyrics(
                    lyrics = buildSyncedLyrics(entries, otherSideForLines),
                    profiles = DefaultLyricsProfiles,
                    measurer = TextMeasurer(
                        createFontFamilyResolver(appContext),
                        Density(density, fontScale),
                        LayoutDirection.Ltr,
                    ),
                    normalStyle = styles.normal,
                    accompanimentStyle = styles.accompaniment,
                    phoneticStyle = styles.phonetic,
                    width = widthPx,
                    density = density,
                    // 与 LyricsLayoutRequest.prepare() 保持逐项一致
                    showPhonetic = true,
                    fontScale = fontScale,
                    translationStyle = styles.translation,
                )
            }.getOrNull()
            val elapsed = System.currentTimeMillis() - startedAt
            if (prepared != null) {
                LyricPreparedCache.putPrepared(key, prepared)
                label(reason, "ok", "ms=$elapsed lines=${entries.size}")
            } else {
                label(reason, "fail", "ms=$elapsed")
            }
            if (jobKey == key) {
                job = null
                jobKey = null
            }
        }
    }

    /**
     * 歌词排版样式（字重/字号/行平衡）改变后，按当前曲把新键提前预热。
     *
     * 缓存键本身已含这些设置（见 [lyricRenderKey]），这里只是把新键的排版在用户回到
     * 歌词页之前算好，避免每次改样式都要走一次冷排版（空一帧 / 闪一下 / 卡一下）。
     * 没有正在显示的歌词时直接跳过：切歌后发布歌词时会用当时的设置重新预热。
     */
    fun onRenderStyleChanged(context: Context?) {
        val mediaIdKey = MediaViewModelObject.lyricMediaId.value ?: return
        val entries = MediaViewModelObject.lrcEntries.value
        if (entries.isEmpty()) return
        onLyricsPublished(
            context = context,
            mediaIdKey = mediaIdKey,
            entries = entries,
            otherSideForLines = MediaViewModelObject.otherSideForLines.toList(),
            reason = "style_changed",
        )
    }

    /** 歌词页开始组合这个键：放弃同键预热，避免两边重复测量。 */
    fun markLive(key: String) {
        liveKey = key
        if (jobKey == key) {
            job?.cancel()
            job = null
            jobKey = null
        }
    }

    fun clearLive(key: String) {
        if (liveKey == key) liveKey = null
    }

    private fun label(reason: String, stage: String, detail: String) {
        runCatching {
            yos.music.player.code.utils.others.YosDiagnostics.log(
                "LYRIC_WARMUP",
                "reason" to reason,
                "stage" to stage,
                "detail" to detail,
            )
        }
    }
}
