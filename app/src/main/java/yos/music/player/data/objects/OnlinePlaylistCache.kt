package yos.music.player.data.objects

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import yos.music.player.data.repositories.KugouPlaylistTrack
import java.io.File

/**
 * 在线歌单详情「首屏」磁盘缓存：元数据 + 曲目列表落 filesDir，TTL 内进页先渲染、随后
 * 后台 revalidate。对标 md3Music `FavoriteListsCache`（专为离线读设计的持久列表缓存）。
 *
 * 只缓存「服务器返回的曲目列表」；挂起歌单（创建中/未绑定）不缓存。自有歌单增删后由
 * 调用方按 id 覆写（重新落盘最新列表），无需额外失效逻辑。
 */
object OnlinePlaylistCache {
    private const val DIR_NAME = "page_cache"
    private const val TTL_MS = 12 * 60 * 60 * 1000L

    private val gson = Gson()
    private val writeType = object : TypeToken<List<KugouPlaylistTrack>>() {}.type

    /**
     * 读盘专用 DTO：字段一律可空。
     * 同 [OnlineAlbumCache]：Gson 绕过 Kotlin 非空校验，读侧必须用可空类型判空，
     * 对非空类型属性写 `x == null` 会被编译器/R8 折叠成 false。
     */
    private data class CachedTrack(
        @SerializedName("hash") val hash: String? = null,
        @SerializedName("name") val name: String? = null,
        @SerializedName("artist") val artist: String? = null,
        @SerializedName("album") val album: String? = null,
        @SerializedName("durationMs") val durationMs: Long = 0L,
        @SerializedName("coverUrl") val coverUrl: String? = null,
        @SerializedName("fileId") val fileId: Long = 0L,
        @SerializedName("mixsongId") val mixsongId: Long = 0L,
        @SerializedName("albumId") val albumId: Long = 0L
    )

    private val readType = object : TypeToken<List<CachedTrack?>>() {}.type

    private fun CachedTrack?.toTrackOrNull(): KugouPlaylistTrack? {
        val dto = this ?: return null
        val hash = dto.hash?.takeIf { it.isNotBlank() } ?: return null
        val name = dto.name?.takeIf { it.isNotBlank() } ?: return null
        val artist = dto.artist?.takeIf { it.isNotBlank() } ?: return null
        return KugouPlaylistTrack(
            hash = hash,
            name = name,
            artist = artist,
            album = dto.album?.takeIf { it.isNotBlank() },
            durationMs = dto.durationMs,
            coverUrl = dto.coverUrl?.takeIf { it.isNotBlank() },
            fileId = dto.fileId,
            mixsongId = dto.mixsongId,
            albumId = dto.albumId
        )
    }

    /** 纯函数，便于单测：解析 + 逐条清洗；整份不可用（全毒/JSON 损坏）返回 null。 */
    internal fun parseCached(json: String): List<KugouPlaylistTrack>? {
        val parsed = runCatching { gson.fromJson<List<CachedTrack?>>(json, readType) }.getOrNull()
            ?: return null
        val tracks = parsed.mapNotNull { it.toTrackOrNull() }
        return if (tracks.isEmpty() && parsed.isNotEmpty()) null else tracks
    }

    @Volatile
    private var dir: File? = null

    private fun dir(context: Context): File =
        dir ?: synchronized(this) {
            dir ?: File(context.filesDir, DIR_NAME).also { it.mkdirs() }.also { dir = it }
        }

    private fun fileFor(context: Context, key: String): File =
        File(dir(context), "pl_" + key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    fun read(context: Context, key: String): List<KugouPlaylistTrack>? {
        if (key.isBlank()) return null
        val f = fileFor(context, key)
        if (!f.isFile) return null
        if (System.currentTimeMillis() - f.lastModified() > TTL_MS) {
            f.delete()
            return null
        }
        val json = runCatching { f.readText() }.getOrNull() ?: return null
        val tracks = parseCached(json)
        if (tracks == null) {
            f.delete()
            return null
        }
        // 只有校验通过才续期 TTL，避免毒化条目永不过期
        f.setLastModified(System.currentTimeMillis())
        return tracks
    }

    /** 单条作废（不牵连同目录下其它专辑/歌单缓存）。 */
    fun delete(context: Context, key: String) {
        if (key.isBlank()) return
        runCatching { fileFor(context, key).delete() }
    }

    fun write(context: Context, key: String, tracks: List<KugouPlaylistTrack>) {
        if (key.isBlank()) return
        runCatching {
            val f = fileFor(context, key)
            f.writeText(gson.toJson(tracks, writeType))
            f.setLastModified(System.currentTimeMillis())
        }
    }

    fun clear(context: Context) {
        runCatching { dir(context).listFiles()?.forEach { it.delete() } }
    }

    /**
     * 列出缓存里所有曲目（忽略 TTL——过期的元数据仍可用来回填索引）。
     * 供设置页「数据与储存」首次启动回填 hash→歌名/歌手/封面索引。
     */
    fun allTracks(context: Context): List<KugouPlaylistTrack> =
        runCatching {
            dir(context).listFiles()
                ?.filter { it.isFile && it.name.startsWith("pl_") && it.name.endsWith(".json") }
                ?.flatMap { f ->
                    runCatching { parseCached(f.readText()) }.getOrNull() ?: emptyList()
                }
                ?: emptyList()
        }.getOrDefault(emptyList())
}
