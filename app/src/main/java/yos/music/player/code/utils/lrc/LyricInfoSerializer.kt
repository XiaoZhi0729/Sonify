package yos.music.player.code.utils.lrc

import androidx.media3.common.MediaItem
import com.google.gson.JsonObject

/**
 * 将当前歌词序列化为 LyricInfo 协议 JSON（写入 MediaSession 元数据 extras 的 [EXTRAS_KEY] 键），
 * 供 HyperLyric（LyricInfo 歌词源）与 ColorOS 锁屏歌词等系统组件读取。
 *
 * 协议约定（与 limczhh/LyricInfo、limczhh/HyperLyric 对齐）：
 * - `lyric`：逐行 LRC，每行仅一个行首 `[mm:ss.mmm]` 标签，`\n` 分隔，空文本行丢弃；
 * - `rawLyric`：ELRC 逐字格式 `[行起] <词起>词<词起>词…`；HyperLyric 侧存在该字段时优先按逐字渲染；
 * - `translation`：与 `lyric` 同格式的独立 LRC，HyperLyric 按行时间戳精确匹配挂到原文行；
 * - `songName`/`artist`/`lyric` 任一为空时整体不写入（HyperLyric 端 `lyric` 为空即放弃显示）。
 */
object LyricInfoSerializer {

    const val EXTRAS_KEY = "lyricInfo"

    /** 从媒体条目取歌曲信息后序列化。 */
    fun encode(item: MediaItem, entries: List<LyricEntry>): String? = encode(
        songName = item.mediaMetadata.title?.toString().orEmpty(),
        artist = item.mediaMetadata.artist?.toString().orEmpty(),
        album = item.mediaMetadata.albumTitle?.toString(),
        songId = item.mediaId,
        entries = entries
    )

    fun encode(
        songName: String,
        artist: String,
        album: String?,
        songId: String?,
        entries: List<LyricEntry>
    ): String? {
        val lines = buildLines(entries)
        if (lines.isEmpty()) return null

        val json = JsonObject().apply {
            addProperty("songName", songName.trim())
            addProperty("artist", artist.trim())
            addProperty("lyric", lines.joinToString("\n") { "[${formatTime(it.timeMs)}]${it.text}" })
            songId?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("songId", it) }
            album?.trim()?.takeIf { it.isNotEmpty() }?.let { addProperty("album", it) }
            buildElrc(lines)?.let { addProperty("rawLyric", it) }
            buildTranslation(lines)?.let { addProperty("translation", it) }
        }
        return json.toString()
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
                // 行级：无词级时间轴，输出普通 LRC 行
                return@mapNotNull "[${formatTime(line.timeMs)}]${line.text}"
            }
            val words = StringBuilder()
            var prevTime = startAnchor
            for (index in 1 until line.mainLyric.size) {
                val (elementEndMs, elementText) = line.mainLyric[index]
                if (elementText.isNotBlank()) {
                    words.append('<').append(formatTime(prevTime)).append('>').append(elementText)
                }
                prevTime = elementEndMs
            }
            if (words.isEmpty()) {
                "[${formatTime(line.timeMs)}]${line.text}"
            } else {
                "[${formatTime(line.timeMs)}]$words"
            }
        }.joinToString("\n")
    }

    /** 翻译独立通道：与原文行使用同一时间源，HyperLyric 按时间戳精确匹配。全空时省略整个字段。 */
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
