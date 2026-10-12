package yos.music.player.data.objects

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import yos.music.player.data.repositories.KugouNewSong
import java.io.File

/**
 * 在线专辑详情「首屏」磁盘缓存：曲目列表落 filesDir，TTL 内进页先渲染、随后后台 revalidate。
 * 与 [OnlinePlaylistCache] 同构（见其说明）。
 */
object OnlineAlbumCache {
    private const val DIR_NAME = "page_cache"
    private const val TTL_MS = 12 * 60 * 60 * 1000L

    private val gson = Gson()
    private val listType = object : TypeToken<List<KugouNewSong>>() {}.type

    @Volatile
    private var dir: File? = null

    private fun dir(context: Context): File =
        dir ?: synchronized(this) {
            dir ?: File(context.filesDir, DIR_NAME).also { it.mkdirs() }.also { dir = it }
        }

    private fun fileFor(context: Context, key: String): File =
        File(dir(context), "al_" + key.replace(Regex("[^A-Za-z0-9_.-]"), "_") + ".json")

    fun read(context: Context, key: String): List<KugouNewSong>? {
        if (key.isBlank()) return null
        val f = fileFor(context, key)
        if (!f.isFile) return null
        if (System.currentTimeMillis() - f.lastModified() > TTL_MS) {
            f.delete()
            return null
        }
        val json = runCatching { f.readText() }.getOrNull() ?: return null
        f.setLastModified(System.currentTimeMillis())
        return runCatching { gson.fromJson<List<KugouNewSong>>(json, listType) }.getOrNull()
    }

    fun write(context: Context, key: String, tracks: List<KugouNewSong>) {
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
}
