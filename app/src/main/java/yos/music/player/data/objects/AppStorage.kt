package yos.music.player.data.objects

import android.content.Context
import android.text.format.Formatter
import coil.imageLoader
import yos.music.player.code.OnlineAudioCache
import yos.music.player.code.utils.artist.HeroBakeCache
import yos.music.player.code.utils.lrc.LyricDiskCache
import java.io.File

/**
 * 应用存储用量统计与缓存清理中枢（设置 > 数据与储存）。
 *
 * 本项目自有缓存都已从 cacheDir 搬到 filesDir（系统"清除缓存"不会动），
 * 所以"缓存"= 这些自有目录 + cacheDir 残余；"用户数据"= dataDir 去掉缓存后的部分。
 */
object AppStorage {

    data class Usage(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
        val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
    }

    /** 自有缓存目录名（均在 filesDir）。"清除缓存"清的就是这些 + cacheDir。 */
    private val CACHE_DIR_NAMES = listOf(
        "image_cache", "kugou_audio_cache", "lyric_cache", "hero_bake_cache", "page_cache"
    )

    private fun dirSize(dir: File?): Long {
        if (dir == null || !dir.exists()) return 0L
        return runCatching { dir.walkTopDown().filter { it.isFile }.sumOf { it.length() } }.getOrDefault(0L)
    }

    fun cacheBytes(context: Context): Long {
        var sum = dirSize(context.cacheDir)
        CACHE_DIR_NAMES.forEach { sum += dirSize(File(context.filesDir, it)) }
        return sum
    }

    fun usage(context: Context): Usage {
        val cache = cacheBytes(context)
        val data = (dirSize(context.dataDir) - cache).coerceAtLeast(0L)
        val app = runCatching { File(context.applicationInfo.sourceDir).length() }.getOrDefault(0L)
        return Usage(app, data, cache)
    }

    fun format(context: Context, bytes: Long): String =
        Formatter.formatShortFileSize(context, bytes)

    /**
     * 清空全部缓存：图片（Coil 磁盘+内存）/音频字节/在线歌词/烘焙倒影/页面首屏 + cacheDir 残余。
     * 不触碰设置、账号、歌单等真正数据。音频走 SimpleCache 的 removeResource，
     * 不直接删目录（避免与在用的缓存实例状态不一致）。
     */
    fun clearCaches(context: Context) {
        runCatching { context.imageLoader.diskCache?.clear() }
        runCatching { context.imageLoader.memoryCache?.clear() }
        runCatching { OnlineAudioCache.clearCache(context) }
        runCatching { LyricDiskCache.clear(context) }
        runCatching { HeroBakeCache.clear(context) }
        // page_cache 目录歌单/专辑共用，清一个即可
        runCatching { OnlinePlaylistCache.clear(context) }
        runCatching { context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
    }
}
