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
        }.filter { entry ->
            // 与 KRC 路径一致：制作人员署名不是歌词，不上屏
            entry.mainLyric.any { it.second.isNotBlank() } &&
                !entry.mainLyric.joinToString("") { it.second }.isNonLyricMetadata()
        }
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
     *
     * 前缀首字符排除标点（`（女：` 这类带前导括号的行来自歌词正文的舞台提示，
     * 跨词拼接时会把括号一并吞进标签）。`\p{L}\p{N}` 覆盖中日韩汉字、假名、拉丁字母
     * 与数字，人名标签（`蒋敦豪:`）仍能识别。
     */
    private val singerLabelRegex = Regex("^[\\p{L}\\p{N}][^:：]{0,15}\\s*[:：]$")

    /**
     * 演唱角色词：只有这些词允许出现在「标签与歌词同一行」的形态里（`男：君を`）。
     *
     * 制作人员署名不会用这些词（`词：`/`曲：`/`Guitar：` 都不在集合内），
     * 因此同行形态用白名单即可安全区分，不必依赖频次。
     */
    private val singerRoleWords = setOf(
        "男", "女", "合", "合唱", "对唱", "男声", "女声", "男声部", "女声部",
        "全体", "齐唱", "合声", "男女", "众人",
    )

    /** 一个报幕标签及其在行内覆盖的元素数（KRC 常把 `男：` 切成 `["男", "："]`）。 */
    private data class SingerLabel(val text: String, val consumed: Int)

    /**
     * 真对唱报幕标签的最低出现次数。
     *
     * 单唱歌曲里偶尔出现一次人名冒号行（如舞台提示），不足以构成对唱：
     * 真对唱的报幕标签会随演唱轮次反复出现。
     */
    private val singerLabelMinOccurrences = 2

    /** 至少两个不同的标签才算对唱：只有一个报幕标签的歌曲全程左对齐。 */
    private val minDistinctSingerLabels = 2

    /** 标签去掉尾部冒号后的核心词。 */
    private fun labelCore(label: String): String =
        label.trim().removeSuffix("：").removeSuffix(":").trim()

    /**
     * 返回该行的报幕标签；不是标签返回 null。
     *
     * 判据是**结构**而非词表：演唱报幕标签总是独占一行（`男：` 后面没有内容），
     * 而制作人员署名总是「标签 + 姓名」同行（`词：潘源良`、`Guitar：秋山浩徳`）。
     * 实测 219 首真实歌词里 41 处同行标签全部是制作信息，无一例外。
     *
     * 同行形态只在标签是演唱角色词时才认（`男：君を`）：这类词不会出现在制作署名里，
     * 因此无需依赖频次或语种就能安全放行。
     *
     * 这条结构判据替代了早期的纯频次方案——串烧歌曲（如《季候风》两首歌各带一份
     * `词：`/`曲：`）会让制作信息重复出现，仅靠频次必然误判。
     */
    private fun singerLabelOf(entry: LyricEntry): SingerLabel? {
        val whole = entry.mainLyric.joinToString("") { it.second }.trim()
        // 制作人员行先排除：它们形似标签，但绝不是演唱者
        if (whole.isNonLyricMetadata()) return null
        if (singerLabelRegex.matches(whole)) {
            // 整行就是标签（`男：` 独占一行）：覆盖锚点外的全部元素
            return SingerLabel(whole, (entry.mainLyric.size - 1).coerceAtLeast(0))
        }
        // 行首连续若干词拼起来是「演唱角色词 + 冒号」：KRC 逐字切分下 `男：` 常成 ["男", "："]
        val limit = minOf(3, entry.mainLyric.size - 1)
        for (count in 1..limit) {
            val candidate = entry.mainLyric.subList(1, count + 1)
                .joinToString("") { it.second }.trim()
            if (candidate.isNotEmpty() &&
                singerLabelRegex.matches(candidate) &&
                labelCore(candidate) in singerRoleWords
            ) {
                return SingerLabel(candidate, count)
            }
        }
        return null
    }

    /** 返回下一次切换后应处的侧：首个报幕标签只记录歌手，不翻转（第一位演唱者固定靠左）。 */
    private fun switchSideOnLabel(label: String, lastSinger: String?, otherSide: Boolean): Boolean =
        if (lastSinger == null || lastSinger == label) otherSide else !otherSide

    /**
     * 清空行首报幕标签覆盖的元素文本，保留元素本身与时间点。
     *
     * 词级时间轴（ELRC 序列化、扫色窗口）依赖元素数量与顺序，因此不能删除元素。
     * 也不能只删 `mainLyric[1]`：`男：` 被逐字切成两个词时会残留半截冒号。
     */
    private fun stripSingerLabel(
        mainLyric: List<Pair<Float, String>>,
        consumed: Int,
    ): List<Pair<Float, String>> =
        mainLyric.mapIndexed { index, (time, text) ->
            // index 0 是行起点锚点，标签从 index 1 开始
            if (index in 1..consumed) time to "" else time to text
        }

    /**
     * 计算每行应处的侧，并把报幕标签从正文里剥掉。
     *
     * 标签识别靠 [singerLabelOf] 的结构判据（演唱标签独占一行，制作署名带姓名）。
     * 这里再做两件事：
     *  - 频次校验：只出现一次的标签不构成对唱，避免单唱歌曲里的偶发人名冒号行翻转；
     *  - 至少两个不同标签才分栏，单标签歌曲全程左对齐。
     */
    private fun processOtherSide(entries: List<LyricEntry>): List<LyricEntry> {
        val labels = entries.map { singerLabelOf(it) }
        val occurrences = HashMap<String, MutableList<Int>>()
        labels.forEachIndexed { index, label ->
            if (label != null) occurrences.getOrPut(label.text) { mutableListOf() } += index
        }
        // 重复出现的标签才是真歌手；单次出现视为偶发冒号行
        val repeated = occurrences.filterValues { it.size >= singerLabelMinOccurrences }
        // 只出现一次的标签仍可能是歌手（某位歌手只唱一句），但必须夹在重复标签之间才算
        val validLabels = HashSet<String>(repeated.keys)
        occurrences.forEach { (label, positions) ->
            if (label in repeated) return@forEach
            val index = positions.first()
            val before = labels.subList(0, index).any { it != null && it.text in repeated }
            val after = labels.subList(index + 1, labels.size).any { it != null && it.text in repeated }
            if (before && after) validLabels += label
        }
        // 至少两个不同的歌手标签才分栏；单唱歌曲全程左对齐
        val duet = validLabels.size >= minDistinctSingerLabels

        val sides = ArrayList<Boolean>(entries.size)
        val filtered = ArrayList<LyricEntry>(entries.size)
        var otherSide = false
        var lastSinger: String? = null
        entries.forEachIndexed { index, entry ->
            val label = labels[index]?.takeIf { it.text in validLabels }
            if (label != null) {
                otherSide = switchSideOnLabel(label.text, lastSinger, otherSide)
                lastSinger = label.text
            }
            val stripped = if (label != null) {
                entry.copy(mainLyric = stripSingerLabel(entry.mainLyric, label.consumed))
            } else {
                entry
            }
            // 整行就是报幕标签（`男：`）：没有正文可显示，整行删除
            if (label != null && stripped.mainLyric.none { it.second.isNotBlank() }) return@forEachIndexed
            sides += if (duet) otherSide else false
            filtered += stripped
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
