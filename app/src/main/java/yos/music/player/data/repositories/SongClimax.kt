package yos.music.player.data.repositories

import org.json.JSONObject
import yos.music.player.native.toPlainAny

/**
 * 歌曲高潮片段（副歌区间），来自酷狗服务端预计算，客户端不做任何音频分析。
 *
 * 上游链路：`/song/climax` → Rust `misc.rs::handle_song_climax` →
 * `https://expendablekmrcdn.kugou.com/v1/audio_climax/audio`，
 * 返回 `{ start_time, end_time }`，单位毫秒。
 *
 * [endMs] 允许为 0：部分歌曲上游只给起点。进度条标记只需要 [startMs]。
 */
data class SongClimax(
    val startMs: Long,
    val endMs: Long
)

/**
 * 从已拍平成 Map/List 的响应里取高潮区间；取不到或数据不自洽时返回 null。
 *
 * 走 Map 入参而不是 JSONObject：org.json 在 JVM 单测里是 android.jar 桩，
 * 不能直接构造（与 [parseKugouLyricFields] 同一约定）。
 *
 * 兼容形态（实测上游两种都出现过）：
 *   - `{"data":[{ "start_time":"61500", "end_time":"98000" }]}`
 *   - `{"data":{ "start_time":61500, "end_time":98000 }}`
 *   - 无 data 层时字段直接平铺在根上
 */
internal fun parseSongClimaxFields(payload: Map<*, *>): SongClimax? {
    val data = when (val raw = payload["data"]) {
        is List<*> -> raw.firstOrNull() as? Map<*, *>
        is Map<*, *> -> raw
        else -> payload
    } ?: return null

    val start = data.longAt("start_time", "startTime", "climax_start", "climaxStart") ?: return null
    val end = data.longAt("end_time", "endTime", "climax_end", "climaxEnd") ?: 0L
    if (start <= 0L) return null
    // end 可以缺省（只标起点），但给了就必须在 start 之后，否则整条数据不可信
    if (end > 0L && end <= start) return null
    return SongClimax(startMs = start, endMs = end.coerceAtLeast(0L))
}

internal fun parseSongClimaxResponse(json: JSONObject): SongClimax? {
    val plain = toPlainAny(json) as? Map<*, *> ?: return null
    return parseSongClimaxFields(plain)
}

/**
 * 高潮起点在进度条上的归一化位置（0..1）；不该画标记时返回 null。
 *
 * 两端各留 2% 的禁画区：贴着轨道端点画出来的点会和圆头轨道/端点重合，
 * 看起来像轨道本身的缺陷而不是一个标记。
 */
internal fun climaxMarkFraction(startMs: Long, durationMs: Long): Float? {
    if (durationMs <= 0L || startMs <= 0L) return null
    val fraction = startMs.toFloat() / durationMs.toFloat()
    if (fraction < 0.02f || fraction > 0.98f) return null
    return fraction
}

/** 多字段名回退取毫秒值；上游有时给字符串、有时给数字。 */
private fun Map<*, *>.longAt(vararg keys: String): Long? {
    for (key in keys) when (val v = this[key]) {
        is Number -> return v.toLong()
        is String -> v.trim().toLongOrNull()?.let { return it }
    }
    return null
}
