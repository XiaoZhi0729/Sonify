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
        // 内嵌翻译/罗马音分类 = 结构优先 + language 兜底（对齐 md3Music `_extractTranslationFromKrc`
        // 的结构分类，保留 language!=0 归罗马音的既有语义）。不能只看 language 值：实测存在
        // 罗马音与翻译都是 language=0、且罗马音条目排在前面的数据（Precious My Heroes），
        // 按值取第一个命中会把罗马音条目当翻译，逐行匹配不上单元素格式 → 整首译文全空。
        val languageEntries = embeddedLanguageEntries(krcText)
        val embeddedTranslations = languageEntries
            .firstOrNull { it.language == 0 && !it.syllableStyle && it.hasText }
            ?.rows
            ?.map { row -> row.firstOrNull()?.takeIf(String::isNotBlank) }
        val embeddedRomanizations = languageEntries
            .firstOrNull { it.language != 0 || it.syllableStyle }
            ?.rows
            ?.map { row -> row.joinToString(" ").takeIf(String::isNotBlank) }
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
            val embeddedOrdinal = if ((embeddedTranslations?.size ?: 0) >= numericRowCount) {
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
                    ?: embeddedTranslations?.getOrNull(embeddedOrdinal)
                entries += LyricEntry(
                    mainLyric = timedWords,
                    translation = translation?.takeIf { it.isNotBlank() },
                    romanization = embeddedRomanizations?.getOrNull(embeddedOrdinal)?.takeIf { it.isNotBlank() }
                )
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

    /** A2 增强型 LRC 的字级标签（组结构与 lineRegex 一致：分/秒/毫秒），如 `<00:32.960>`。 */
    private val angleWordRegex = Regex("<(\\d{1,3}):(\\d{2})(?:\\.(\\d{1,3}))?>")

    private fun parseLrcRows(text: String): List<TimedText> {
        val result = mutableListOf<TimedText>()
        // 逐字行的末词结束时间无行内后继标签可依，挂起等待下一行首个时间戳回填；-1 表示无挂起行
        var openWordRowIndex = -1
        text.lineSequence().forEach { line ->
            val matches = lineRegex.findAll(line).toList()
            val wordTagMatches = angleWordRegex.findAll(line).toList()
            if (matches.isEmpty() && wordTagMatches.isEmpty()) return@forEach
            val millis = matches.map { timestampMillis(it) }
            val firstTimestamp = millis.firstOrNull() ?: timestampMillis(wordTagMatches.first())
            if (openWordRowIndex >= 0) {
                val pending = result[openWordRowIndex]
                val words = pending.words.orEmpty()
                result[openWordRowIndex] = pending.copy(
                    words = words.dropLast(1) + words.last().copy(endMs = firstTimestamp)
                )
                openWordRowIndex = -1
            }
            // A2 增强型 LRC（常见于 FLAC/MP3 内嵌歌词）：行级 [mm:ss] 之外每个字还带
            // <mm:ss> 字级标签。按字级标签切词，词结束 = 下一标签时间；行末孤立标签其后
            // 无文本、被空文本过滤自然成为末词的结束标记。末词无后继标签时沿用挂起机制
            // 由下一行首个时间戳回填。
            if (wordTagMatches.isNotEmpty()) {
                val words = wordTagMatches.mapIndexed { index, match ->
                    val textStart = match.range.last + 1
                    val textEnd = wordTagMatches.getOrNull(index + 1)?.range?.first ?: line.length
                    WordSpan(
                        endMs = wordTagMatches.getOrNull(index + 1)?.let { timestampMillis(it) },
                        text = normalizeWord(line.substring(textStart, textEnd))
                    )
                }.filter { it.text.isNotEmpty() }
                if (words.isNotEmpty()) {
                    // 行末孤立标签已给出末词结束时间的行不再挂起，避免被下一行首戳/兜底覆盖；
                    // 方括号路径的回填语义不受影响
                    openWordRowIndex = if (words.last().endMs == null) result.size else -1
                    result += TimedText(firstTimestamp, words.joinToString("") { it.text }, words)
                }
                return@forEach
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

    /** KRC `[language:]` 载荷（Base64 JSON）的 content 数组里的一个条目。 */
    private data class EmbeddedLanguageEntry(
        val language: Int?,
        /** 每行的字符串元素（已 normalize、去空白元素；空行 = 空列表，保留占位）。 */
        val rows: List<List<String>>
    ) {
        /** 任一行是多元素数组 → 按字/音节拆分风格 → 罗马音；整行单元素 → 翻译。 */
        val syllableStyle: Boolean get() = rows.any { it.size > 1 }
        val hasText: Boolean get() = rows.any { it.isNotEmpty() }
    }

    /**
     * 解析第一个 `[language:...]` 块的 content 数组为条目列表。
     * 直接按数组顶层拆分（不从整段 JSON 抽对象）：外层容器也含子条目的
     * `"language":0` 子串，按对象抽取会把外层当条目、取到第一个 lyricContent。
     */
    private fun embeddedLanguageEntries(krcText: String): List<EmbeddedLanguageEntry> {
        val encoded = krcText.lineSequence()
            .mapNotNull { Regex("^\\[language:([^]]+)]$").find(it.trim())?.groupValues?.get(1) }
            .firstOrNull() ?: return emptyList()
        val decoded = Base64Codec.decode(encoded)?.toString(Charsets.UTF_8) ?: return emptyList()
        val contentKey = decoded.indexOf("\"content\"")
        if (contentKey == -1) return emptyList()
        val arrayStart = decoded.indexOf('[', contentKey)
        if (arrayStart == -1) return emptyList()
        val arrayEnd = findMatchingBracket(decoded, arrayStart, '[', ']')
        if (arrayEnd == -1) return emptyList()
        return splitJsonArrayElements(decoded.substring(arrayStart + 1, arrayEnd)).mapNotNull { entryText ->
            val rows = lyricContentElementRows(entryText) ?: return@mapNotNull null
            val language = Regex("\"language\"\\s*:\\s*\"?(\\d+)\"?")
                .find(entryText)?.groupValues?.get(1)?.toInt()
            EmbeddedLanguageEntry(language, rows)
        }
    }

    /** 取一个 language 对象 lyricContent 每行的字符串元素（已 normalize、去空元素）。 */
    private fun lyricContentElementRows(objectText: String): List<List<String>>? {
        val contentKey = objectText.indexOf("\"lyricContent\"")
        if (contentKey == -1) return null
        val arrayStart = objectText.indexOf('[', contentKey)
        if (arrayStart == -1) return null
        val arrayEnd = findMatchingBracket(objectText, arrayStart, '[', ']')
        if (arrayEnd == -1) return null
        val rows = splitJsonArrayElements(objectText.substring(arrayStart + 1, arrayEnd))
        return rows.map { row ->
            val innerStart = row.indexOf('[')
            val innerEnd = row.lastIndexOf(']')
            if (innerStart == -1 || innerEnd <= innerStart) return@map emptyList<String>()
            splitJsonArrayElements(row.substring(innerStart + 1, innerEnd)).mapNotNull { element ->
                val match = Regex("^\\s*\"((?:\\\\.|[^\"\\\\])*)\"\\s*$").find(element)
                    ?: return@mapNotNull null
                normalize(decodeJsonString(match.groupValues[1])).takeIf { it.isNotBlank() }
            }
        }
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

    /**
     * 演唱者报幕标签：`男：`/`女：`/`合：`/歌手名：`——冒号收尾，前缀短且自身不含冒号。
     * 歌名 `Re:Re:` 也是「冒号收尾的短文本」，但前缀里带冒号，不能当报幕标签：
     * 一旦误判，第一行就会把整首歌词翻到另一侧（单歌手歌曲全程靠右）。
     */
    private val singerLabelRegex = Regex("^[^:：\\s][^:：]{0,15}\\s*[:：]$")

    /** 返回下一次切换后应处的侧：首个报幕标签只记录歌手，不翻转（第一位演唱者固定靠左）。 */
    private fun switchSideOnLabel(label: String, lastSinger: String?, otherSide: Boolean): Boolean {
        val key = label.trim()
        return if (lastSinger == null || lastSinger == key) otherSide else !otherSide
    }

    private fun processOtherSide(entries: List<LyricEntry>): List<LyricEntry> {
        val sides = mutableStateListOf<Boolean>()
        var otherSide = false
        var lastSinger: String? = null
        val filtered = entries.map { entry ->
            val text = entry.mainLyric.joinToString("") { it.second }
            var isSingerLabel = false
            if (singerLabelRegex.matches(text.trim())) {
                otherSide = switchSideOnLabel(text, lastSinger, otherSide)
                lastSinger = text.trim()
            } else if (entry.mainLyric.size > 1) {
                val candidate = entry.mainLyric[1].second
                if (singerLabelRegex.matches(candidate.trim())) {
                    isSingerLabel = true
                    otherSide = switchSideOnLabel(candidate, lastSinger, otherSide)
                    lastSinger = candidate.trim()
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
