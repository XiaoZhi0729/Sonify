package yos.music.player.data.objects

import android.content.Context
import com.google.gson.Gson
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
    private val listType = object : TypeToken<List<KugouPlaylistTrack>>() {}.type

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
        f.setLastModified(System.currentTimeMillis())
        val parsed = runCatching { gson.fromJson<List<KugouPlaylistTrack>>(json, listType) }.getOrNull()
        if (parsed == null || parsed.any { it.hash == null || it.name == null }) {
            // Gson 不校验 Kotlin 非空：跨构建混淆键名漂移（release 写 a/b/c、debug 写原名）
            // 或损坏缓存会让字段整体为 null，回流 UI 即 NPE。整文件作废，重新走网络。
            f.delete()
            return null
        }
        return parsed
    }

    fun write(context: Context, key: String, tracks: List<KugouPlaylistTrack>) {
        if (key.isBlank()) return
        runCatching {
            val f = fileFor(context, key)
            f.writeText(gson.toJson(tracks, listType))
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
                    runCatching { gson.fromJson<List<KugouPlaylistTrack>>(f.readText(), listType) }
                        .getOrNull() ?: emptyList()
                }
                ?.filter { t -> runCatching { t.hash.isNotBlank() && t.name.isNotBlank() }.getOrDefault(false) }
                ?: emptyList()
        }.getOrDefault(emptyList())
}
