package yos.music.player.data.recognition

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import yos.music.player.code.MediaController
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.KugouSearchSong

/** 识别出来的歌曲（已把酷狗响应里的多套字段名归一化）。 */
data class RecognizedSong(
    val name: String,
    val artist: String,
    val album: String?,
    val coverUrl: String?,
    val score: String?,
    val hash: String?,
    val albumAudioId: String?
)

/**
 * /audio/match 响应解析。
 *
 * 酷狗这个接口的 `data` 既可能是数组也可能是对象，字段名还有 camel/snake/首字母大写
 * 三套写法（与参照实现 recognition_utils.dart 的取键顺序一致）。
 */
object RecognitionParser {

    private val NAME_KEYS = listOf("songname", "song_name", "name", "SongName")
    private val ARTIST_KEYS = listOf("singername", "singer_name", "SingerName", "author_name")
    private val ALBUM_KEYS = listOf("album_name", "AlbumName", "albumname")
    private val COVER_KEYS = listOf("imgurl", "sizable_cover", "union_cover")
    private val HASH_KEYS = listOf("hash", "FileHash", "file_hash")
    private val ALBUM_AUDIO_ID_KEYS = listOf("album_audio_id", "MixSongID", "mixsongid")

    private fun firstNonEmpty(json: JSONObject?, keys: List<String>): String? {
        if (json == null) return null
        for (key in keys) {
            val value = json.optString(key)
            if (value.isNotEmpty() && value != "null") return value
        }
        return null
    }

    private fun payload(json: JSONObject): JSONObject? {
        val data = json.opt("data")
        return when (data) {
            is JSONArray -> if (data.length() > 0) data.optJSONObject(0) else null
            is JSONObject -> data
            else -> json
        }
    }

    /** 解析成功返回歌曲；响应里没有歌名也没有 hash（未识别/静音/版权缺失）返回 null。 */
    fun parse(json: JSONObject): RecognizedSong? {
        val audioInfo = payload(json) ?: return null
        val name = firstNonEmpty(audioInfo, NAME_KEYS)
        val hash = firstNonEmpty(audioInfo, HASH_KEYS)
        val albumAudioId = firstNonEmpty(audioInfo, ALBUM_AUDIO_ID_KEYS)
        // 与参照实现的 hasSongData 判据一致：有歌名或有 hash 才算识别到
        if (name == null && hash == null) return null

        val cover = firstNonEmpty(audioInfo, COVER_KEYS)?.replace("{size}", "480")
        return RecognizedSong(
            name = name ?: "未知歌曲",
            artist = firstNonEmpty(audioInfo, ARTIST_KEYS) ?: "未知歌手",
            album = firstNonEmpty(audioInfo, ALBUM_KEYS),
            coverUrl = cover,
            score = firstNonEmpty(audioInfo, listOf("score")),
            hash = hash,
            albumAudioId = albumAudioId
        )
    }
}

/**
 * 播放识别结果：有 hash 直接进队列播放；没有 hash 时按「歌手 歌名」搜索兜底，
 * 优先用 album_audio_id（MixSongID）精确匹配。
 *
 * 只起播（迷你条会出现），不强行把播放页推出来——用户此刻多半还在看识别结果。
 */
suspend fun playRecognizedSong(song: RecognizedSong): Result<Unit> = withContext(Dispatchers.IO) {
    try {
        var hash = song.hash
        var artwork = song.coverUrl

        if (hash.isNullOrEmpty()) {
            val query = if (song.artist.isNotEmpty() && song.artist != "未知歌手") {
                "${song.artist} ${song.name}"
            } else {
                song.name
            }
            val songs = KugouRepository.searchSongs(query, pageSize = 5).getOrElse {
                return@withContext Result.failure(it)
            }
            val matched = song.albumAudioId
                ?.let { id -> songs.firstOrNull { it.mid == id } }
                ?: songs.firstOrNull()
                ?: return@withContext Result.failure(Exception("未找到可播放的歌曲源"))
            hash = matched.hash
            if (artwork.isNullOrEmpty()) artwork = matched.artworkUrl
        }

        val queueSong = KugouSearchSong(
            hash = hash,
            mid = song.albumAudioId.orEmpty(),
            name = song.name,
            author = song.artist,
            duration = 0L,
            artworkUrl = artwork,
            albumId = 0L
        )
        val item = KugouRepository.toQueueMediaItem(queueSong)
        // shuffle 默认 false：识曲结果必须直接播这一首，不能被上一次的随机模式带走
        MediaController.prepare(item, listOf(item))
        Result.success(Unit)
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }
}
