package yos.music.player.data.repositories

import org.json.JSONObject
import yos.music.player.native.toPlainAny

/**
 * 酷狗专辑动态封面（/album/dycover）解析结果。
 *
 * 严格区分「确定结果」与「非确定失败」（对齐 md3Music `DynamicCoverLookupStatus`）：
 * - [Present]：上游明确返回了可用的动态封面视频 URL，可写入正缓存。
 * - [Absent]：上游 `status == 1 && error_code == 0` 且明确无 `dycover` 视频，可写入负缓存。
 * - 返回 `null`：网络异常、JSON 异常或上游业务错误（`status != 1` / `error_code != 0`），不写缓存，
 *   避免临时波动导致歌曲被永久误判为无动态封面。
 */
sealed interface DyCoverLookupResult {
    data class Present(
        val albumAudioId: Long,
        val h264Url: String,
        val h265Url: String = "",
        val h264Hash: String = ""
    ) : DyCoverLookupResult

    data object Absent : DyCoverLookupResult
}

internal fun parseDyCoverResponse(json: JSONObject, albumAudioId: Long): DyCoverLookupResult? {
    @Suppress("UNCHECKED_CAST")
    val map = toPlainAny(json) as? Map<String, Any?> ?: return null
    return parseDyCoverFields(map, albumAudioId)
}

internal fun parseDyCoverFields(fields: Map<String, Any?>, albumAudioId: Long): DyCoverLookupResult? {
    val status = (fields["status"] as? Number)?.toInt()
        ?: fields["status"]?.toString()?.toIntOrNull()
        ?: -1
    val errorCode = (fields["error_code"] as? Number)?.toInt()
        ?: fields["error_code"]?.toString()?.toIntOrNull()
        ?: 0
    if (status != 1 || errorCode != 0) return null

    val dataList = fields["data"] as? List<*> ?: return DyCoverLookupResult.Absent
    for (entry in dataList) {
        val item = entry as? Map<*, *> ?: continue
        val dycover = item["dycover"] as? Map<*, *> ?: continue
        val primary = dycover["h264_url"]?.toString()?.trim().orEmpty()
        val backup = (dycover["h264_backup_url"] as? List<*>)
            ?.asSequence()
            ?.mapNotNull { it?.toString()?.trim() }
            ?.firstOrNull { it.isNotEmpty() }
            .orEmpty()
        val h264 = primary.ifEmpty { backup }
        if (h264.isEmpty()) continue

        val parsedId = (item["album_audio_id"] as? Number)?.toLong()
            ?: item["album_audio_id"]?.toString()?.toLongOrNull()
            ?: 0L
        return DyCoverLookupResult.Present(
            albumAudioId = if (parsedId > 0L) parsedId else albumAudioId,
            h264Url = h264,
            h265Url = dycover["h265_url"]?.toString()?.trim().orEmpty(),
            h264Hash = dycover["h264_hash"]?.toString()?.trim().orEmpty()
        )
    }
    return DyCoverLookupResult.Absent
}
