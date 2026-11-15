// Copyright 2026, mochi-compose contributors
// SPDX-License-Identifier: Apache-2.0

package yos.music.player.ui.widgets.mochi

import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.promeg.pinyinhelper.Pinyin
import java.text.Normalizer
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.mochi.lerp as mochiLerp

/** 索引条的几何：竖条按字母数均分，手指的纵坐标换成第几个 */
internal object IndexBarGeometry {
    fun indexAt(y: Float, height: Float, count: Int): Int {
        if (count <= 0 || height <= 0f) return 0
        return (y / height * count).toInt().coerceIn(0, count - 1)
    }

    /** 第 i 个字母的中心 */
    fun centerOf(i: Int, height: Float, count: Int): Float =
        if (count <= 0) 0f else (i + 0.5f) * height / count
}

/**
 * 源自 `mochi-compose` 的字母索引条（通讯录右边的 A–Z）：
 * 按住或滑动时每换一个字母轻震一下并回调 [onSelect]（由调用方滚动列表），
 * 左边弹出一个放大的字母气泡跟着手指，字母切换时模糊替换；松手气泡缩回。
 */
@Composable
fun MochiIndexBar(
    labels: List<String>,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    onDragActiveChange: ((Boolean) -> Unit)? = null,
) {
    if (labels.isEmpty()) return
    val view = LocalView.current
    val haptics = remember(view) { MochiHaptics(view, MochiHapticProfile.IosLike) }
    val select by rememberUpdatedState(onSelect)
    val dragActiveChange by rememberUpdatedState(onDragActiveChange)
    val currentLabels by rememberUpdatedState(labels)
    var current by remember { mutableIntStateOf(-1) }
    var fingerY by remember { mutableFloatStateOf(0f) }
    val bubble = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val accent = accentColor
    val dark = isFlamingoInDarkMode()
    val bubbleContainer = if (dark) Color(0xFF2C2C2E) else Color.White

    BoxWithConstraints(
        modifier
            .width(IndexBarWidth)
            .semantics { contentDescription = "索引" },
    ) {
        // 小高度场景（如横屏/分屏）下自适应紧凑行高，防止底部字母被裁切
        val density = androidx.compose.ui.platform.LocalDensity.current
        val verticalPadDp = 4.dp
        val verticalPadTotalPx = with(density) { (verticalPadDp * 2).toPx() }
        val availablePx = (constraints.maxHeight.toFloat() - verticalPadTotalPx).coerceAtLeast(1f)
        val maxRowPx = if (constraints.hasBoundedHeight && labels.isNotEmpty()) {
            (availablePx / labels.size).coerceAtLeast(1f)
        } else {
            Float.POSITIVE_INFINITY
        }
        val compact = maxRowPx < with(density) { 14.dp.toPx() }
        val textFontSize = if (compact) 9.sp else 11.sp
        val textLineHeight = if (compact) 11.sp else 14.sp

        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.Center)
                .systemGestureExclusion()
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        down.consume()
                        dragActiveChange?.invoke(true)
                        fun track(y: Float) {
                            val activeLabels = currentLabels
                            if (activeLabels.isEmpty()) return
                            fingerY = y.coerceIn(0f, size.height.toFloat())
                            val i = IndexBarGeometry.indexAt(y, size.height.toFloat(), activeLabels.size)
                            if (i != current) {
                                current = i
                                haptics.tick()
                                select(i)
                            }
                        }
                        try {
                            scope.launch { bubble.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 600f)) }
                            track(down.position.y)
                            while (true) {
                                val c = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!c.pressed) break
                                c.consume()
                                track(c.position.y)
                            }
                        } finally {
                            current = -1
                            dragActiveChange?.invoke(false)
                            scope.launch { bubble.animateTo(0f, spring(dampingRatio = 1f, stiffness = 700f)) }
                        }
                    }
                }
                .padding(vertical = verticalPadDp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            labels.forEach { labelText ->
                Text(
                    text = labelText,
                    style = TextStyle(
                        fontSize = textFontSize,
                        lineHeight = textLineHeight,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center,
                    ),
                    color = accent,
                    maxLines = 1,
                )
            }
        }

        // 放大的字母气泡：在条的左边，纵向跟着手指（unbounded 仅用于不裁切气泡，align = TopStart 避免撑宽父容器）
        val label = labels.getOrNull(current.coerceAtLeast(0)) ?: ""
        Box(
            Modifier
                .align(Alignment.TopStart)
                .wrapContentSize(align = Alignment.TopStart, unbounded = true)
                .offset {
                    IntOffset(
                        -BubbleOffset.roundToPx() - BubbleSize.roundToPx(),
                        (fingerY - BubbleSize.toPx() / 2f).roundToInt(),
                    )
                }
                .graphicsLayer {
                    val s = bubble.value.coerceAtLeast(0f)
                    scaleX = s
                    scaleY = s
                    alpha = s.coerceIn(0f, 1f)
                    transformOrigin = TransformOrigin(1f, 0.5f)
                }
                .size(BubbleSize)
                .drawWithCache {
                    val path = Path().apply { addOval(Rect(Offset.Zero, size)) }
                    val shadow = BubbleShadow.elevated(this, 0.6f, dark)
                    onDrawBehind { drawBubbleShadow(path, shadow, Color.Black) }
                }
                .background(bubbleContainer, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            MochiBlurText(
                text = label,
                style = TextStyle(fontSize = 28.sp, lineHeight = 32.sp, fontWeight = FontWeight.Bold),
                color = accent,
                contentAlignment = Alignment.Center,
            )
        }
    }
}

private val IndexBarWidth = 18.dp
private val BubbleSize = 56.dp
private val BubbleOffset = 12.dp

@Immutable
private data class BubbleShadow(
    val ambientOffset: Float,
    val ambientSigma: Float,
    val ambientAlpha: Float,
    val keyOffset: Float,
    val keySigma: Float,
    val keyAlpha: Float,
) {
    companion object {
        fun elevated(density: Density, elevation: Float, dark: Boolean, visibility: Float = 1f): BubbleShadow =
            with(density) {
                val cl = elevation.coerceIn(0f, 1f)
                BubbleShadow(
                    ambientOffset = mochiLerp(2.dp.toPx(), 8.dp.toPx(), cl),
                    ambientSigma = mochiLerp(6.dp.toPx(), 22.dp.toPx(), cl),
                    ambientAlpha = (if (dark) mochiLerp(0.22f, 0.42f, cl) else mochiLerp(0.06f, 0.13f, cl)) * visibility,
                    keyOffset = mochiLerp(0.5.dp.toPx(), 1.5.dp.toPx(), cl),
                    keySigma = mochiLerp(1.5.dp.toPx(), 3.dp.toPx(), cl),
                    keyAlpha = (if (dark) 0.25f else mochiLerp(0.07f, 0.09f, cl)) * visibility,
                )
            }
    }
}

private val SoftShadowsSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P
private const val FallbackRings = 4

private fun DrawScope.drawBubbleShadow(path: Path, shadow: BubbleShadow, color: Color) {
    if (shadow.ambientAlpha <= 0f && shadow.keyAlpha <= 0f) return
    if (SoftShadowsSupported) {
        val native = path.asAndroidPath()
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        drawIntoCanvas { c ->
            val canvas = c.nativeCanvas
            fun layer(offset: Float, sigma: Float, alpha: Float) {
                if (alpha <= 0f) return
                val radius = max(0.5f, (sigma - 0.5f) / 0.57735f)
                paint.color = color.copy(alpha = 1f / 255f).toArgb()
                paint.setShadowLayer(radius, 0f, offset, color.copy(alpha = color.alpha * alpha).toArgb())
                canvas.drawPath(native, paint)
            }
            layer(shadow.ambientOffset, shadow.ambientSigma, shadow.ambientAlpha)
            layer(shadow.keyOffset, shadow.keySigma, shadow.keyAlpha)
        }
    } else {
        val bounds = path.getBounds()
        fun rings(offset: Float, sigma: Float, alpha: Float) {
            if (alpha <= 0f) return
            val spread = sigma * 2f
            for (k in FallbackRings downTo 1) {
                val r = bounds.inflate(spread * k / FallbackRings).translate(0f, offset)
                drawRoundRect(
                    color.copy(alpha = 1f),
                    topLeft = r.topLeft,
                    size = r.size,
                    cornerRadius = CornerRadius(minOf(r.width, r.height) / 2f),
                    alpha = color.alpha * alpha / FallbackRings * (1f - (k - 1f) / FallbackRings),
                )
            }
        }
        rings(shadow.ambientOffset, shadow.ambientSigma, shadow.ambientAlpha)
        rings(shadow.keyOffset, shadow.keySigma, shadow.keyAlpha)
    }
}

/**
 * 按名称排序并生成字母索引条所需的数据快照（同一快照原子输出，避免列表与索引不同步）。
 */
@Immutable
data class IndexedListResult<T>(
    val sortedItems: List<T>,
    val labels: List<String>,
    val firstItemIndices: List<Int>,
)

object AlphabetIndex {
    fun <T> build(
        items: List<T>,
        descending: Boolean = false,
        nameSelector: (T) -> String,
    ): IndexedListResult<T> {
        if (items.isEmpty()) return IndexedListResult(emptyList(), emptyList(), emptyList())
        val keyed = items.map { item ->
            val name = nameSelector(item)
            Triple(item, initialLetterOf(name), sortKeyOf(name))
        }
        val sorted = if (descending) {
            keyed.sortedWith(compareByDescending<Triple<T, String, String>> { it.third }.thenByDescending { nameSelector(it.first) })
        } else {
            keyed.sortedWith(compareBy<Triple<T, String, String>> { it.third }.thenBy { nameSelector(it.first) })
        }
        val labels = ArrayList<String>(27)
        val firstIndices = ArrayList<Int>(27)
        var lastLabel: String? = null
        sorted.forEachIndexed { index, (_, letter, _) ->
            if (letter != lastLabel) {
                labels.add(letter)
                firstIndices.add(index)
                lastLabel = letter
            }
        }
        return IndexedListResult(
            sortedItems = sorted.map { it.first },
            labels = labels,
            firstItemIndices = firstIndices,
        )
    }

    fun initialLetterOf(name: String): String {
        val trimmed = stripLeadingDecorations(name)
        if (trimmed.isEmpty()) return "#"
        // 优先匹配常见多音姓氏与音乐高频词头
        POLYPHONE_PREFIXES.firstOrNull { (prefix, _) -> trimmed.startsWith(prefix) }?.let { (_, letter) ->
            return letter
        }
        val first = trimmed[0]
        return charToInitialLetter(first)
    }

    /**
     * 一级前缀由 [initialLetterOf] 直接生成（字母组 `"0_$letter"`，`#` 组 `"1_#"`），
     * 从数学上保证同字母条目 100% 连续不撕裂；二级键对前 24 个实质字符逐字转写以保证组内发音有序。
     */
    fun sortKeyOf(name: String): String {
        val letter = initialLetterOf(name)
        val groupPrefix = if (letter == "#") "1_#" else "0_$letter"
        val trimmed = stripLeadingDecorations(name)
        if (trimmed.isEmpty()) return "${groupPrefix}_"
        val sb = StringBuilder(groupPrefix.length + 32)
        sb.append(groupPrefix).append('_')
        val limit = minOf(trimmed.length, 24)
        for (i in 0 until limit) {
            val ch = trimmed[i]
            if (ch.isWhitespace()) continue
            if (i == 0) {
                POLYPHONE_PREFIXES.firstOrNull { (prefix, _) -> trimmed.startsWith(prefix) }?.let { (_, polyLetter) ->
                    sb.append(polyLetter)
                    continue
                }
            }
            sb.append(charToSortToken(ch))
        }
        return sb.toString()
    }

    private fun stripLeadingDecorations(raw: String): String {
        var idx = 0
        while (idx < raw.length && isSkippablePrefix(raw[idx])) {
            idx++
        }
        return if (idx == 0) raw else raw.substring(idx)
    }

    private fun isSkippablePrefix(c: Char): Boolean =
        c.isWhitespace() || c in SKIPPABLE_PREFIX_CHARS

    private fun charToInitialLetter(c: Char): String {
        val token = charToSortToken(c)
        val first = token.firstOrNull() ?: return "#"
        return if (first in 'A'..'Z') first.toString() else "#"
    }

    private fun charToSortToken(c: Char): String {
        // 1. ASCII 字母与数字
        if (c in 'a'..'z') return (c - ('a' - 'A')).toString()
        if (c in 'A'..'Z') return c.toString()
        if (c in '0'..'9') return c.toString()

        // 2. 特殊拉丁字母变体与西欧带音标字符（NFD 剥离音标）
        SPECIAL_LATIN_MAP[c]?.let { return it }
        val normalized = Normalizer.normalize(c.toString(), Normalizer.Form.NFD)
        for (nc in normalized) {
            when (nc) {
                in 'A'..'Z' -> return nc.toString()
                in 'a'..'z' -> return (nc - ('a' - 'A')).toString()
            }
        }

        // 3. 日语平假名 / 片假名（含半角片假名）
        kanaToRomaji(c)?.let { return it }

        // 4. 韩语谚文音节与兼容字母
        hangulToRomaji(c)?.let { return it }

        // 5. 日语独有国字/新字体（如 桜/凪/駅/涙 等，避免被拼音表误映射或漏映射）
        JP_KANJI_FALLBACK[c]?.let { return it }

        // 6. 中文汉字走 TinyPinyin（必须校验输出首字符是 ASCII A-Z，避免未收录汉字漏网）
        val py = runCatching { Pinyin.toPinyin(c) }.getOrNull()
        if (!py.isNullOrEmpty()) {
            val upper = py.uppercase()
            if (upper[0] in 'A'..'Z') return upper
        }

        // 7. 其他小语种（西里尔字母、希腊字母、阿拉伯文等）走 Android ICU Transliterator
        icuTransliterate(c)?.let { return it }

        return "#"
    }

    private fun kanaToRomaji(c: Char): String? {
        val full = HALFWIDTH_KANA_MAP[c] ?: c
        val code = full.code
        // 统一把片假名 (U+30A1..U+30F6) 映射到平假名 (U+3041..U+3096) 查表
        val hira = if (code in 0x30A1..0x30F6) (code - 0x60).toChar() else full
        return HIRAGANA_ROMAJI[hira]
    }

    private fun hangulToRomaji(c: Char): String? {
        val code = c.code
        if (code in 0xAC00..0xD7A3) {
            val sIndex = code - 0xAC00
            val choseong = sIndex / (21 * 28)
            val jungseong = (sIndex % (21 * 28)) / 28
            val cPart = HANGUL_CHOSEONG[choseong]
            return if (cPart.isEmpty()) HANGUL_JUNGSEONG[jungseong] else cPart + HANGUL_JUNGSEONG[jungseong]
        }
        return HANGUL_COMPAT_JAMO[c]
    }

    private fun icuTransliterate(c: Char): String? {
        val trans = icuTransliterator ?: return null
        val out = runCatching { trans.transliterate(c.toString()) }.getOrNull() ?: return null
        val normalized = Normalizer.normalize(out, Normalizer.Form.NFD)
        val sb = StringBuilder()
        for (nc in normalized) {
            when (nc) {
                in 'A'..'Z' -> sb.append(nc)
                in 'a'..'z' -> sb.append(nc - ('a' - 'A'))
            }
        }
        return sb.toString().takeIf { it.isNotEmpty() }
    }

    private val icuTransliterator: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII") }.getOrNull()
        } else {
            null
        }
    }

    private val SKIPPABLE_PREFIX_CHARS = setOf(
        '《', '》', '「', '」', '『', '』', '【', '】', '〈', '〉', '（', '）',
        '(', ')', '[', ']', '{', '}', '"', '\'', '“', '”', '‘', '’',
        '·', '•', '・', '-', '—', '_', '~', '～', '!', '！', '?', '？', '*', '.', '、'
    )

    private val POLYPHONE_PREFIXES = listOf(
        "单依纯" to "S",
        "单雄信" to "S",
        "单田芳" to "S",
        "解晓东" to "X",
        "仇" to "Q",
        "查良镛" to "Z",
        "曾" to "Z",
        "重庆" to "C",
        "重逢" to "C",
        "重温" to "C",
        "重复" to "C",
        "重生" to "C",
        "重演" to "C",
        "长安" to "C",
        "长城" to "C",
        "长春" to "C",
        "长沙" to "C",
        "厦门" to "X",
        "西藏" to "Z",
        "乐队" to "Y",
        "乐团" to "Y",
        "乐章" to "Y",
        "乐曲" to "Y",
        "乐器" to "Y",
        "乐园" to "Y",
        "音乐" to "Y",
        "秘鲁" to "B",
    )

    private val SPECIAL_LATIN_MAP = mapOf(
        'Ø' to "O", 'ø' to "O",
        'Æ' to "AE", 'æ' to "AE",
        'Œ' to "OE", 'œ' to "OE",
        'ß' to "SS", 'ẞ' to "SS",
        'Ł' to "L", 'ł' to "L",
        'Ð' to "D", 'ð' to "D",
        'Þ' to "TH", 'þ' to "TH",
    )

    private val JP_KANJI_FALLBACK = mapOf(
        '凪' to "NAGI",
        '雫' to "SHIZUKU",
        '辻' to "TSUJI",
        '込' to "KOMI",
        '峠' to "TOUGE",
        '畑' to "HATA",
        '畠' to "HATAKE",
        '桜' to "SAKURA",
        '駅' to "EKI",
        '涙' to "NAMIDA",
    )

    private val HIRAGANA_ROMAJI = mapOf(
        'あ' to "A", 'ぁ' to "A",
        'い' to "I", 'ぃ' to "I",
        'う' to "U", 'ぅ' to "U", 'ゔ' to "V",
        'え' to "E", 'ぇ' to "E",
        'お' to "O", 'ぉ' to "O",
        'か' to "KA", 'が' to "GA", 'き' to "KI", 'ぎ' to "GI", 'く' to "KU", 'ぐ' to "GU",
        'け' to "KE", 'げ' to "GE", 'こ' to "KO", 'ご' to "GO", 'ヵ' to "KA", 'ヶ' to "KE",
        'さ' to "SA", 'ざ' to "ZA", 'し' to "SHI", 'じ' to "JI", 'す' to "SU", 'ず' to "ZU",
        'せ' to "SE", 'ぜ' to "ZE", 'そ' to "SO", 'ぞ' to "ZO",
        'た' to "TA", 'だ' to "DA", 'ち' to "CHI", 'ぢ' to "JI", 'っ' to "T", 'つ' to "TSU", 'づ' to "ZU",
        'て' to "TE", 'で' to "DE", 'と' to "TO", 'ど' to "DO",
        'な' to "NA", 'に' to "NI", 'ぬ' to "NU", 'ね' to "NE", 'の' to "NO",
        'は' to "HA", 'ば' to "BA", 'ぱ' to "PA",
        'ひ' to "HI", 'び' to "BI", 'ぴ' to "PI",
        'ふ' to "FU", 'ぶ' to "BU", 'ぷ' to "PU",
        'へ' to "HE", 'べ' to "BE", 'ぺ' to "PE",
        'ほ' to "HO", 'ぼ' to "BO", 'ぽ' to "PO",
        'ま' to "MA", 'み' to "MI", 'む' to "MU", 'め' to "ME", 'も' to "MO",
        'や' to "YA", 'ゃ' to "YA", 'ゆ' to "YU", 'ゅ' to "YU", 'よ' to "YO", 'ょ' to "YO",
        'ら' to "RA", 'り' to "RI", 'る' to "RU", 'れ' to "RE", 'ろ' to "RO",
        'わ' to "WA", 'ゎ' to "WA", 'ゐ' to "WI", 'ゑ' to "WE", 'を' to "WO", 'ん' to "N",
        'ー' to "",
    )

    private val HALFWIDTH_KANA_MAP = mapOf(
        'ｱ' to 'ア', 'ｲ' to 'イ', 'ｳ' to 'ウ', 'ｴ' to 'エ', 'ｵ' to 'オ',
        'ｶ' to 'カ', 'ｷ' to 'キ', 'ｸ' to 'ク', 'ｹ' to 'ケ', 'ｺ' to 'コ',
        'ｻ' to 'サ', 'ｼ' to 'シ', 'ｽ' to 'ス', 'ｾ' to 'セ', 'ｿ' to 'ソ',
        'ﾀ' to 'タ', 'ﾁ' to 'チ', 'ﾂ' to 'ツ', 'ﾃ' to 'テ', 'ﾄ' to 'ト',
        'ﾅ' to 'ナ', 'ﾆ' to 'ニ', 'ﾇ' to 'ヌ', 'ﾈ' to 'ネ', 'ﾉ' to 'ノ',
        'ﾊ' to 'ハ', 'ﾋ' to 'ヒ', 'ﾌ' to 'フ', 'ﾍ' to 'ヘ', 'ﾎ' to 'ホ',
        'ﾏ' to 'マ', 'ﾐ' to 'ミ', 'ﾑ' to 'ム', 'ﾒ' to 'メ', 'ﾓ' to 'モ',
        'ﾔ' to 'ヤ', 'ﾕ' to 'ユ', 'ﾖ' to 'ヨ',
        'ﾗ' to 'ラ', 'ﾘ' to 'リ', 'ﾙ' to 'ル', 'ﾚ' to 'レ', 'ﾛ' to 'ロ',
        'ﾜ' to 'ワ', 'ｦ' to 'ヲ', 'ﾝ' to 'ン',
        'ｧ' to 'ァ', 'ｨ' to 'ィ', 'ｩ' to 'ゥ', 'ｪ' to 'ェ', 'ｫ' to 'ォ',
        'ｯ' to 'ッ', 'ｬ' to 'ャ', 'ｭ' to 'ュ', 'ｮ' to 'ョ',
    )

    private val HANGUL_CHOSEONG = arrayOf(
        "G", "K", "N", "D", "T", "R", "M", "B", "P", "S",
        "S", "", "J", "J", "C", "K", "T", "P", "H",
    )

    private val HANGUL_JUNGSEONG = arrayOf(
        "A", "AE", "YA", "YAE", "EO", "E", "YEO", "YE", "O", "WA",
        "WAE", "OE", "YO", "U", "WO", "WE", "WI", "YU", "EU", "UI", "I",
    )

    private val HANGUL_COMPAT_JAMO = mapOf(
        'ㄱ' to "G", 'ㄲ' to "K", 'ㄴ' to "N", 'ㄷ' to "D", 'ㄸ' to "T", 'ㄹ' to "R",
        'ㅁ' to "M", 'ㅂ' to "B", 'ㅃ' to "P", 'ㅅ' to "S", 'ㅆ' to "S", 'ㅇ' to "O",
        'ㅈ' to "J", 'ㅉ' to "J", 'ㅊ' to "C", 'ㅋ' to "K", 'ㅌ' to "T", 'ㅍ' to "P", 'ㅎ' to "H",
    )
}
