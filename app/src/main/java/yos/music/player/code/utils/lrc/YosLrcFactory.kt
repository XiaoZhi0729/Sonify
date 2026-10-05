package yos.music.player.code.utils.lrc

import androidx.compose.runtime.mutableStateListOf
import yos.music.player.data.objects.MediaViewModelObject
import kotlin.math.abs

/** Converts provider lyric payloads into timed lines with explicit translations. */
class YosLrcFactory(private val formatText: Boolean = true) {
    private data class TimedText(
        val time: Float,
        val text: String,
        val words: List<WordSpan>? = null
    )

    /** 词级片段。[endMs] 是该词高亮结束时间（与 KRC 词级同语义）；行末词由下一行起点回填。 */
    private data class WordSpan(val endMs: Float?, val text: String)

    fun formatLrcEntries(lrcText: String, translationText: String? = null): List<LyricEntry> {
        val rows = parseLrcRows(lrcText)
        if (rows.isEmpty()) {
            processOtherSide(emptyList())
            return emptyList()
        }
        val translations = translationText?.let {
            runCatching { parseLrcRows(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val entries = rows.groupBy { it.time }.toSortedMap().entries.toList()
            .mapIndexed { index, grouped ->
                val time = grouped.key
                val group = grouped.value
            val texts = group.map { it.text }.filter { it.isNotBlank() }
            val main = texts.firstOrNull().orEmpty()
            val inline = texts.drop(1).lastOrNull()
            // 逐字行：与 KRC 逐词路径同形——首元素行起点锚点 + (词结束时间, 词文本) + 尾部空锚点，
            // 渲染层据此走逐字卡拉OK高亮，序列化端据此判定词级 ELRC
            val wordRow = group.firstOrNull { !it.words.isNullOrEmpty() }
            val mainLyric = if (wordRow != null) {
                val spans = wordRow.words.orEmpty()
                listOf(time to "") +
                    spans.map { (it.endMs ?: time) to it.text } +
                    listOf((spans.last().endMs ?: time) to "")
            } else {
                listOf(time to "", time to normalize(main))
            }
            LyricEntry(
                mainLyric = mainLyric,
                translation = (
                    nearestText(translations, time)
                        ?: translations.getOrNull(index)?.text
                        ?: inline
                    )?.takeIf { it.isNotBlank() }
            )
        }.filter { entry -> entry.mainLyric.any { it.second.isNotBlank() } }
        return processOtherSide(entries)
    }

    /**
     * 先按普通 LRC 解析；解析为空且内容非空时，视为无时间戳的纯文本歌词
     * （常见于 M4A ©lyr / MP3 USLT 内嵌），按歌曲总时长均分合成时间轴后再走同一解析，
     * 保证滚动 UI 依赖的递增 startTime 成立。
     */
    fun formatLrcEntriesWithFallback(lrcText: String, totalDurationMs: Long): List<LyricEntry> {
        val entries = formatLrcEntries(lrcText)
        if (entries.isNotEmpty() || lrcText.isBlank()) return entries

        val lines = lrcText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() && it != "//" }
            .toList()
        if (lines.isEmpty()) {
            processOtherSide(emptyList())
            return emptyList()
        }

        val durationMs = if (totalDurationMs > 0) totalDurationMs else lines.size * 5_000L
        val synthesized = StringBuilder()
        lines.forEachIndexed { index, line ->
            val time = durationMs * index / lines.size
            val minute = time / 60_000
            val second = time % 60_000 / 1_000
            val millis = time % 1_000
            synthesized.append("[%02d:%02d.%03d]".format(minute, second, millis))
                .append(line)
                .append('\n')
        }
        return formatLrcEntries(synthesized.toString())
    }

    fun formatKrcEntries(krcText: String, translationText: String? = null): List<LyricEntry> {
        val embeddedTranslations = extractEmbeddedTranslations(krcText)
        val explicitTranslations = translationText?.let {
            runCatching { parseLrcRows(it) }.getOrDefault(emptyList())
        }.orEmpty()
        val entries = mutableListOf<LyricEntry>()
        val lineRegex = Regex("^\\[(-?\\d+),(-?\\d+)\\](.*)$")
        val wordRegex = Regex("<(-?\\d+),(-?\\d+)(?:,-?\\d+)?>([^<]*)")
        val numericRowCount = krcText.lineSequence().count { lineRegex.matches(it.trim()) }
        var sourceRowOrdinal = 0
        var lyricRowOrdinal = 0
        krcText.lineSequence().forEach { rawLine ->
            val match = lineRegex.find(rawLine.trim()) ?: return@forEach
            val rowOrdinal = sourceRowOrdinal++
            val start = match.groupValues[1].toFloat()
            val duration = match.groupValues[2].toFloat()
            val words = wordRegex.findAll(match.groupValues[3]).toList()
            val lineText = words.joinToString("") { it.groupValues[3] }
            if (words.isEmpty() || lineText.isNonLyricMetadata()) return@forEach
            val translationOrdinal = lyricRowOrdinal++
            val embeddedOrdinal = if (embeddedTranslations.size >= numericRowCount) {
                rowOrdinal
            } else {
                translationOrdinal
            }
            val timedWords = mutableListOf(start to "")
            words.forEach { word ->
                val end = start + word.groupValues[1].toFloat() + word.groupValues[2].toFloat()
                val text = word.groupValues[3]
                if (text.isNotEmpty()) timedWords += end to text
            }
            if (timedWords.size > 1) {
                timedWords += (start + duration) to ""
                val translation = nearestText(explicitTranslations, start)
                    ?: embeddedTranslations.getOrNull(embeddedOrdinal)
                entries += LyricEntry(timedWords, translation?.takeIf { it.isNotBlank() })
            }
        }
        return processOtherSide(entries)
    }

    private fun String.isNonLyricMetadata(): Boolean {
        val value = trim()
        return value.startsWith("原曲歌手") ||
            value.startsWith("作词") || value.startsWith("作曲") ||
            value.startsWith("编曲") || value.startsWith("制作") ||
            value.startsWith("演唱") || value.startsWith("出品")
    }

    private val lineRegex = Regex("\\[(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?\\]([^\\[]*)")

    private fun parseLrcRows(text: String): List<TimedText> {
        val result = mutableListOf<TimedText>()
        // 逐字行的末词结束时间无行内后继标签可依，挂起等待下一行首个时间戳回填；-1 表示无挂起行
        var openWordRowIndex = -1
        text.lineSequence().forEach { line ->
            val matches = lineRegex.findAll(line).toList()
            if (matches.isEmpty()) return@forEach
            val millis = matches.map { timestampMillis(it) }
            if (openWordRowIndex >= 0) {
                val pending = result[openWordRowIndex]
                val words = pending.words.orEmpty()
                result[openWordRowIndex] = pending.copy(
                    words = words.dropLast(1) + words.last().copy(endMs = millis.first())
                )
                openWordRowIndex = -1
            }
            // 逐字行判定：时间戳出现在非空白文本之后。注意 group4（[^\[]*）包含在 match
            // 范围内、相邻 match 之间的间隙恒为空，因此要看前一个 match 的 group4 文本。
            // 仅末尾标签前是空白（如 `[00:01] [00:02]text`）仍按经典行处理，保持旧行为。
            val isWordTimed = line.substring(0, matches.first().range.first).isNotBlank() ||
                matches.dropLast(1).any { it.groupValues[4].isNotBlank() }
            if (isWordTimed) {
                // 词片段不做 trim（区别于整行 normalize），词间空格作为独立词保留以维持排版间距
                val words = matches.mapIndexed { index, match ->
                    WordSpan(
                        endMs = if (index + 1 < matches.size) millis[index + 1] else null,
                        text = normalizeWord(match.groupValues[4])
                    )
                }.filter { it.text.isNotEmpty() }
                if (words.isNotEmpty()) {
                    openWordRowIndex = result.size
                    result += TimedText(millis.first(), words.joinToString("") { it.text }, words)
                }
                return@forEach
            }
            matches.forEachIndexed { index, match ->
                val value = normalize(match.groupValues[4])
                if (value.isNotBlank() && value != "//") {
                    result += TimedText(millis[index], value)
                }
            }
        }
        openWordRowIndex.takeIf { it >= 0 }?.let { index ->
            val pending = result[index]
            val words = pending.words.orEmpty()
            result[index] = pending.copy(
                words = words.dropLast(1) + words.last().copy(endMs = pending.time + 3_000f)
            )
        }
        return result
    }

    private fun timestampMillis(match: MatchResult): Float {
        val fraction = match.groupValues[3]
            .ifEmpty { "000" }
            .padEnd(3, '0')
            .take(3)
        return (match.groupValues[1].toInt() * 60_000 +
            match.groupValues[2].toInt() * 1_000 + fraction.toInt()).toFloat()
    }

    private fun nearestText(rows: List<TimedText>, time: Float): String? =
        rows.minByOrNull { abs(it.time - time) }
            ?.takeIf { abs(it.time - time) <= 500f }
            ?.text

    private fun normalize(text: String): String =
        decodeUnicodeEscapes(text).let {
            if (formatText) it.replace(Regex("\\s+"), " ").trim() else it
        }

    /** 词级片段版 normalize：折叠空白但不 trim，避免吞掉词间空格。 */
    private fun normalizeWord(text: String): String =
        decodeUnicodeEscapes(text).let {
            if (formatText) it.replace(Regex("\\s+"), " ") else it
        }

    private fun decodeUnicodeEscapes(text: String): String {
        val result = StringBuilder(text.length)
        var index = 0
        while (index < text.length) {
            if (text[index] == '\\' && index + 1 < text.length) {
                val marker = text[index + 1]
                val digits = when (marker) {
                    'u' -> 4
                    'U' -> {
                        val longHex = text.substring(index + 2)
                            .take(8)
                            .takeWhile { it.isDigit() || it.lowercaseChar() in 'a'..'f' }
                        if (longHex.length >= 8) 8 else 4
                    }
                    else -> 0
                }
                if (digits > 0 && index + 2 + digits <= text.length) {
                    val hex = text.substring(index + 2, index + 2 + digits)
                    val codePoint = hex.toLongOrNull(16)
                    if (codePoint != null && codePoint <= Character.MAX_CODE_POINT) {
                        result.appendCodePoint(codePoint.toInt())
                        index += 2 + digits
                        continue
                    }
                }
            }
            result.append(text[index])
            index++
        }
        return result.toString()
    }

    private fun extractEmbeddedTranslations(krcText: String): List<String?> {
        val encoded = krcText.lineSequence()
            .mapNotNull { Regex("^\\[language:([^]]+)]$").find(it.trim())?.groupValues?.get(1) }
            .firstOrNull() ?: return emptyList()
        val decoded = Base64Codec.decode(encoded)?.toString(Charsets.UTF_8) ?: return emptyList()
        return extractSingleElementLyricRows(decoded)
    }

    /** Extracts language=0 rows while retaining empty row positions. */
    private fun extractSingleElementLyricRows(json: String): List<String?> {
        extractJsonObjects(json).forEach { objectText ->
            val language = Regex(
                "\"language\"\\s*:\\s*(?:0|\"0\")"
            )
            if (!language.containsMatchIn(objectText)) return@forEach
            val contentKey = objectText.indexOf("\"lyricContent\"")
            if (contentKey == -1) return@forEach
            val arrayStart = objectText.indexOf('[', contentKey)
            if (arrayStart == -1) return@forEach
            val arrayEnd = findMatchingBracket(objectText, arrayStart, '[', ']')
            if (arrayEnd == -1) return@forEach
            val rows = splitJsonArrayElements(objectText.substring(arrayStart + 1, arrayEnd))
            return rows.map { row ->
                val match = Regex("^\\s*\\[\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"\\s*]\\s*$")
                    .find(row)
                match?.let { normalize(decodeJsonString(it.groupValues[1])) }
                    ?.takeIf { it.isNotBlank() }
            }
        }
        return emptyList()
    }

    private fun splitJsonArrayElements(arrayBody: String): List<String> {
        val elements = mutableListOf<String>()
        var start = 0
        var depth = 0
        var quoted = false
        var escaped = false
        arrayBody.forEachIndexed { index, char ->
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '"') quoted = false
                return@forEachIndexed
            }
            when (char) {
                '"' -> quoted = true
                '[', '{' -> depth++
                ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    elements += arrayBody.substring(start, index)
                    start = index + 1
                }
            }
        }
        if (start < arrayBody.length) elements += arrayBody.substring(start)
        return elements
    }

    private fun extractJsonObjects(json: String): List<String> {
        val objects = mutableListOf<String>()
        var index = 0
        while (index < json.length) {
            if (json[index] == '{') {
                val end = findMatchingBracket(json, index, '{', '}')
                if (end != -1) {
                    objects += json.substring(index, end + 1)
                }
            }
            index++
        }
        return objects
    }

    private fun findMatchingBracket(text: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var escaped = false
        var quoted = false
        for (index in start until text.length) {
            val char = text[index]
            if (quoted) {
                if (escaped) escaped = false
                else if (char == '\\') escaped = true
                else if (char == '\"') quoted = false
                continue
            }
            when (char) {
                '\"' -> quoted = true
                open -> depth++
                close -> {
                    depth--
                    if (depth == 0) return index
                }
            }
        }
        return -1
    }

    private fun decodeJsonString(value: String): String =
        value.replace(Regex("\\\\([\\\"\\\\/bfnrt])")) { match ->
            when (match.groupValues[1]) {
                "b" -> "\\b"
                "f" -> "\\u000C"
                "n" -> "\\n"
                "r" -> "\\r"
                "t" -> "\\t"
                else -> match.groupValues[1]
            }
        }

    private fun processOtherSide(entries: List<LyricEntry>): List<LyricEntry> {
        val sides = mutableStateListOf<Boolean>()
        var otherSide = false
        var lastSinger: String? = null
        val filtered = entries.map { entry ->
            val text = entry.mainLyric.joinToString("") { it.second }
            var isSingerLabel = false
            if (text.endsWith(":") || text.endsWith("：")) {
                otherSide = !otherSide
            } else if (entry.mainLyric.size > 1) {
                val candidate = entry.mainLyric[1].second
                if (candidate.matches(Regex(".+\\s*:\\s*"))) {
                    isSingerLabel = true
                    if (lastSinger == null || lastSinger != candidate) otherSide = !otherSide
                    lastSinger = candidate
                }
            }
            sides += otherSide
            if (isSingerLabel) entry.copy(
                mainLyric = entry.mainLyric.filterIndexed { index, _ -> index != 1 }
            ) else entry
        }
        MediaViewModelObject.otherSideForLines.clear()
        MediaViewModelObject.otherSideForLines.addAll(sides)
        return filtered
    }
}

/** Small API-23-compatible decoder boundary; malformed input returns null. */
internal object Base64Codec {
    private const val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

    fun decode(value: String): ByteArray? = runCatching {
        val clean = value.filterNot(Char::isWhitespace).let {
            it + "=".repeat((4 - it.length % 4) % 4)
        }
        val output = ArrayList<Byte>(clean.length * 3 / 4)
        var buffer = 0
        var bits = 0
        clean.forEach { char ->
            if (char == '=') return@forEach
            val index = alphabet.indexOf(char)
            require(index >= 0)
            buffer = (buffer shl 6) or index
            bits += 6
            if (bits >= 8) {
                bits -= 8
                output += ((buffer shr bits) and 0xff).toByte()
            }
        }
        output.toByteArray()
    }.getOrNull()
}
