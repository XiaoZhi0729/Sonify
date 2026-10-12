package yos.music.player.code.utils.lrc

import android.content.Context
import com.google.gson.Gson
import java.io.File

/**
 * 在线歌词磁盘缓存：把酷狗 [yos.music.player.data.repositories.KugouRepository.getLyrics]
 * 拿到的原始 LRC/KRC/翻译/罗马音文本按歌曲 hash 落盘到 filesDir（系统"清除缓存"不会动），
 * 重启后同一首歌可零网络上屏。
 *
 * 只存原始文本、不存解析结果：
 *  - 体积小、无损；
 *  - 解析链是确定性纯函数（含 [YosLrcFactory] 内写入 otherSideForLines 的 processOtherSide），
 *    重解析与首次解析逐位等价，payload 无需单独持久化。
 *
 * 上限按曲数 LRU（[MAX_ENTRIES]）：每次读取触碰 mtime，超限时淘汰最久未读的。
 */
object LyricDiskCache {

    private const val DIR_NAME = "lyric_cache"
    private const val MAX_ENTRIES = 800

    private val gson = Gson()

    data class CachedLyrics(
        val lrc: String,
        val krc: String? = null,
        val translation: String? = null,
        val romanization: String? = null
    )

    @Volatile
    private var dir: File? = null

    private fun dir(context: Context): File =
        dir ?: synchronized(this) {
            dir ?: File(context.filesDir, DIR_NAME).also { it.mkdirs() }.also { dir = it }
        }

    private fun fileFor(context: Context, hash: String): File =
        File(dir(context), hash.lowercase().replace(Regex("[^a-z0-9]"), "_") + ".json")

    /** 命中返回原始歌词文本；未命中返回 null。命中会触碰 mtime 以实现 LRU。 */
    fun read(context: Context, hash: String): CachedLyrics? {
        if (hash.isBlank()) return null
        val f = fileFor(context, hash)
        return synchronized(this) {
            if (!f.isFile) {
                null
            } else {
                val parsed = runCatching { gson.fromJson(f.readText(), CachedLyrics::class.java) }.getOrNull()
                if (parsed == null) {
                    f.delete()
                    null
                } else {
                    f.setLastModified(System.currentTimeMillis())
                    parsed
                }
            }
        }
    }

    fun write(
        context: Context,
        hash: String,
        lrc: String,
        krc: String?,
        translation: String?,
        romanization: String?
    ) {
        if (hash.isBlank() || lrc.isBlank()) return
        val f = fileFor(context, hash)
        synchronized(this) {
            runCatching {
                f.writeText(gson.toJson(CachedLyrics(lrc, krc, translation, romanization)))
                trim(context)
            }
        }
    }

    /** 目录总大小（字节），供设置页缓存管理展示。 */
    fun sizeBytes(context: Context): Long =
        synchronized(this) { dir(context).listFiles()?.sumOf { it.length() } ?: 0L }

    fun clear(context: Context) {
        synchronized(this) { dir(context).listFiles()?.forEach { it.delete() } }
    }

    private fun trim(context: Context) {
        val files = dir(context).listFiles()?.filter { it.isFile && it.name.endsWith(".json") } ?: return
        if (files.size <= MAX_ENTRIES) return
        files.sortedBy { it.lastModified() }.take(files.size - MAX_ENTRIES).forEach { it.delete() }
    }
}
