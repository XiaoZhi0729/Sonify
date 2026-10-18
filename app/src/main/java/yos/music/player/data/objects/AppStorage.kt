package yos.music.player.data.objects

import android.content.Context
import android.text.format.Formatter
import coil.imageLoader
import yos.music.player.code.OnlineAudioCache
import yos.music.player.code.utils.artist.HeroBakeCache
import yos.music.player.code.utils.lrc.LyricDiskCache
import yos.music.player.data.libraries.SettingsLibrary
import java.io.File

/**
 * 应用存储用量统计与缓存清理中枢（设置 > 数据与储存）。
 *
 * 本项目自有缓存都已从 cacheDir 搬到 filesDir（系统"清除缓存"不会动），
 * 所以"缓存"= 这些自有目录 + cacheDir 残余；"用户数据"= dataDir 去掉缓存后的部分。
 */
@OptIn(coil.annotation.ExperimentalCoilApi::class)
object AppStorage {

    data class Usage(val appBytes: Long, val dataBytes: Long, val cacheBytes: Long) {
        val totalBytes: Long get() = appBytes + dataBytes + cacheBytes
    }

    /** 缓存分类（设置 > 数据与储存的"缓存"分区）。 */
    enum class Category { AUDIO, LYRIC, IMAGE, OTHER }

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
     * 某一类缓存的字节数。音频/图片走各自的 O(1) 官方计数（SimpleCache.getCacheSpace /
     * Coil DiskCache.size），不 walk 目录；歌词/其它才走目录求和。
     */
    fun categoryBytes(context: Context, category: Category): Long = when (category) {
        Category.AUDIO -> OnlineAudioCache.cacheSpace(context)
        Category.LYRIC -> LyricDiskCache.sizeBytes(context)
        Category.IMAGE -> runCatching { context.imageLoader.diskCache?.size ?: 0L }.getOrDefault(0L)
        Category.OTHER ->
            dirSize(File(context.filesDir, "hero_bake_cache")) +
                dirSize(File(context.filesDir, "page_cache")) +
                dirSize(context.cacheDir)
    }

    /**
     * 清空某一类缓存。音频走 SimpleCache 的 removeResource，不直接删目录。
     * OTHER = 烘焙倒影 + 页面首屏（歌单/专辑共用 page_cache，两个都清）+ cacheDir 残余。
     */
    fun clearCategory(context: Context, category: Category) {
        when (category) {
            Category.AUDIO -> runCatching { OnlineAudioCache.clearCache(context) }
            Category.LYRIC -> runCatching { LyricDiskCache.clear(context) }
            Category.IMAGE -> {
                runCatching { context.imageLoader.diskCache?.clear() }
                runCatching { context.imageLoader.memoryCache?.clear() }
            }
            Category.OTHER -> {
                runCatching { HeroBakeCache.clear(context) }
                runCatching { OnlinePlaylistCache.clear(context) }
                runCatching { OnlineAlbumCache.clear(context) }
                runCatching { context.cacheDir?.listFiles()?.forEach { it.deleteRecursively() } }
            }
        }
    }

    /** 音频缓存上限（字节），供 UI 显示"已用 / 上限"（读当前设置值，非已生效值）。 */
    fun audioCapBytes(): Long =
        SettingsLibrary.AudioCacheCapMb.coerceAtLeast(64).toLong() * 1024 * 1024

    /** 图片缓存上限（字节）。Coil 的 DiskCache.maxSize 只读，故这里只是回显设置值。 */
    fun imageCapBytes(): Long =
        SettingsLibrary.ImageCacheCapMb.coerceAtLeast(64).toLong() * 1024 * 1024

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
