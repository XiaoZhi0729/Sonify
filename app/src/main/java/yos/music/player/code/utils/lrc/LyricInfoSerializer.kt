package yos.music.player.code.utils.lrc

import androidx.media3.common.MediaItem
import com.google.gson.JsonObject

/**
 * 将当前歌词序列化为 LyricInfo 协议 JSON（写入 MediaSession 元数据 extras 的 [EXTRAS_KEY] 键），
 * 供 ColorOS 原生锁屏歌词 / ColorOS-Live-Lyrics-Bridge / HyperLyric（LyricInfo 歌词源）等
 * 系统组件读取。
 *
 * 字段契约（按 ColorOS-Live-Lyrics-Bridge `docs/PLAYER_INTEGRATION.zh-CN.md` 接入协议逐条
 * 对齐，并经 HyperLyric `LyricInfoParser.kt` 解析端源码交叉核实）：
 * - `lyric`：逐行 LRC（原生显示必需），每行仅一个行首 `[mm:ss.mmm]` 标签，`\n` 分隔，
 *   空文本行丢弃；
 * - `rawLyric`：ELRC 逐字格式 `[行起] <词起>词<词起>词…`，供逐字卡拉 OK 渲染；按协议 §4.2
 *   规则 4，词级行末尾追加无文本结束标签标记最后一字的视觉结束点（HyperLyric 解析端忽略
 *   无文本词标签，ColorOS Bridge 消费）；**行级行保持纯 LRC、绝不加末尾标签**——HyperLyric
 *   的 ELRC 解析器会把"行文本 + 孤立词标签"的行整体丢弃；
 * - 翻译 lane：规范键为 `translationLyric`（协议 §3），同时保留旧别名 `translation` 写同一
 *   内容——两者内容一致，旧版 HyperLyric（只认 translation）与 Bridge（按序识别别名键）
 *   均按行时间戳精确匹配、每条只消费一次；
 * - `lyricType: 0` / `noLyric: false` / `provider` / `source` / `trackKey` /
 *   `sessionGeneration`：协议 §2/§3 建议字段（身份声明与过期 payload 拒绝）；
 * - `roma` 不推送（协议 §4.3：罗马音不是翻译；与 MD3Music 行为一致）；
 * - HyperLyric 端 rawLyric 存在时优先且独占、`lyric` 被忽略，且不读取 format/lyricType 等
 *   字段——本 payload 对两端消费者同时成立；
 * - `lyric` 与 `rawLyric` 皆空时消费端放弃显示，故本实现在无可渲染行时返回 null 不写入；
 *   超长 payload 按协议 §7 拒绝注入（fail-open）。
 */
object LyricInfoSerializer {

    const val EXTRAS_KEY = "lyricInfo"

    /** 协议 §3 诊断字段：宿主包名 + payload 契约版本。 */
    private const val PROVIDER = "com.sonify.music"
    private const val SOURCE = "com.sonify.music-v1"

    /**
     * 协议 §7：完整候选 metadata 超过 512 KiB 时只拒绝歌词注入（fail-open，原 metadata
     * 继续发布），防止 Binder/Parcel 边界崩溃。JSON 按 UTF-8 计（CJK 每字 3 字节），
     * 取保守字符数阈值。
     */
    private const val MAX_PAYLOAD_CHARS = 150_000

    /** 从媒体条目取歌曲信息后序列化。 */
    fun encode(item: MediaItem, entries: List<LyricEntry>, sessionGeneration: Int = 0): String? = encode(
        songName = item.mediaMetadata.title?.toString().orEmpty(),
        artist = item.mediaMetadata.artist?.toString().orEmpty(),
        album = item.mediaMetadata.albumTitle?.toString(),
        songId = item.mediaId,
        entries = entries,
        sessionGeneration = sessionGeneration
    )

    fun encode(
        songName: String,
        artist: String,
        album: String?,
        songId: String?,
        entries: List<LyricEntry>,
        sessionGeneration: Int = 0
    ): String? {
        val lines = buildLines(entries)
        if (lines.isEmpty()) return null

        val name = songName.trim()
        val artistName = artist.trim()
        val id = songId?.trim().orEmpty()
        val json = JsonObject().apply {
            addProperty("songName", name)
            addProperty("artist", artistName)
            addProperty("lyric", lines.joinToString("\n") { "[${formatTime(it.timeMs)}]${it.text}" })
            addProperty("lyricType", 0)
            addProperty("noLyric", false)
            addProperty("provider", PROVIDER)
            addProperty("source", SOURCE)
            if (id.isNotEmpty()) addProperty("songId", id)
            if (id.isNotEmpty() || name.isNotEmpty()) {
                // 协议 §3/§5：稳定身份键，供消费端拒绝同会话过期 payload
                addProperty("trackKey", "$id|$name|$artistName")
            }
            album?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("album", it) }
            if (sessionGeneration > 0) addProperty("sessionGeneration", sessionGeneration)
            buildElrc(lines)?.let { addProperty("rawLyric", it) }
            buildTranslation(lines)?.let { translation ->
                addProperty("translationLyric", translation)
                // 旧版 HyperLyric 只识别 translation 别名；两个键写同一内容，消费端按序
                // 取第一个含时间标签的值，结果一致
                addProperty("translation", translation)
            }
        }
        val out = json.toString()
        if (out.length > MAX_PAYLOAD_CHARS) return null
        return out
    }

    /** 一行歌词的统一中间表示：行时间与文本来自 [LyricEntry.mainLyric] 非空片段拼接。 */
    private data class LineData(
        val timeMs: Float,
        val text: String,
        val translation: String?,
        val mainLyric: List<Pair<Float, String>>
    )

    private fun buildLines(entries: List<LyricEntry>): List<LineData> = entries.mapNotNull { entry ->
        val text = entry.mainLyric.joinToString("") { it.second }.trim()
        if (text.isEmpty()) {
            null
        } else {
            LineData(
                timeMs = entry.startTime,
                text = text,
                translation = entry.translation?.trim(),
                mainLyric = entry.mainLyric
            )
        }
    }

    /**
     * 逐字 ELRC。词级判断：[LyricEntry.mainLyric] 除首尾空锚点外还有时间点（size >= 3）。
     * mainLyric 的时间语义是"该元素结束时间"（YosLrcFactory 词级为 `(词结束时间, 词文本)`，
     * 首元素是行开始锚点），而 ELRC 词标签是"词开始时间"，因此每个词的开始时间
     * 取 mainLyric 中它前一个元素的时间。行级行回退为普通 LRC 行（HyperLyric 端容错）。
     */
    private fun buildElrc(lines: List<LineData>): String? {
        if (lines.none { it.mainLyric.size >= 3 }) return null
        return lines.mapNotNull { line ->
            val startAnchor = line.mainLyric.firstOrNull()?.first ?: return@mapNotNull null
            if (line.mainLyric.size <= 2) {
                // 行级：无词级时间轴，输出普通 LRC 行。绝不能追加末尾结束标签——
                // HyperLyric 的 ELRC 解析器遇到"行文本 + 孤立词标签"会把整行丢弃。
                return@mapNotNull "[${formatTime(line.timeMs)}]${line.text}"
            }
            val words = StringBuilder()
            var prevTime = startAnchor
            var lastTag = startAnchor
            for (index in 1 until line.mainLyric.size) {
                val (elementEndMs, elementText) = line.mainLyric[index]
                if (elementText.isNotBlank()) {
                    words.append('<').append(formatTime(prevTime)).append('>').append(elementText)
                    lastTag = prevTime
                }
                prevTime = elementEndMs
            }
            if (words.isEmpty()) {
                "[${formatTime(line.timeMs)}]${line.text}"
            } else {
                // 协议 §4.2 规则 4：源提供结束时间时，用无文本的末尾标签标记最后一个字
                // 的视觉结束点（行末空锚点即行结束时间）。HyperLyric 解析端会忽略无文本
                // 词标签，ColorOS Bridge 据此绘制逐字收尾。
                if (prevTime > lastTag) {
                    words.append('<').append(formatTime(prevTime)).append('>')
                }
                "[${formatTime(line.timeMs)}]$words"
            }
        }.joinToString("\n")
    }

    /** 翻译独立通道（translationLyric 规范键 + translation 旧别名，同内容）：与原文行使用同一时间源，消费端按行时间戳精确匹配、每条只消费一次。全空时省略整个字段。 */
    private fun buildTranslation(lines: List<LineData>): String? {
        val translated = lines.mapNotNull { line ->
            line.translation?.takeIf { it.isNotEmpty() }?.let { "[${formatTime(line.timeMs)}]$it" }
        }
        return translated.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }

    private fun formatTime(timeMs: Float): String {
        val totalMs = timeMs.toLong().coerceAtLeast(0L)
        val minute = (totalMs / 60_000L).coerceAtMost(99L)
        val second = (totalMs % 60_000L) / 1_000L
        val millis = totalMs % 1_000L
        return "%02d:%02d.%03d".format(minute, second, millis)
    }
}
