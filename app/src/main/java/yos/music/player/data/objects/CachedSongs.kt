package yos.music.player.data.objects

import android.content.Context
import android.net.Uri
import yos.music.player.code.OnlineAudioCache
import yos.music.player.code.utils.lrc.LyricDiskCache
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem

/**
 * 「按歌曲挑选清理」的数据组装：把某一类缓存里的 hash 还原成可读的歌 + 各自的字节数。
 *
 * 只支持 [AppStorage.Category.AUDIO] 与 [AppStorage.Category.LYRIC]（图片按封面 URL 存、
 * 同专辑共用，无法按曲精确清理）。
 *
 * 歌名来源优先级：元数据索引 [CacheSongIndex] → 「已存队列 + 最近播放」回退。
 * 两者都没有时 title=null，UI 显示「未知歌曲 · hash前8位」。
 */
object CachedSongs {

    data class CachedSong(
        val hash: String,
        val title: String?,
        val artist: String?,
        val thumb: Uri?,
        val sizeBytes: Long
    )

    private data class Meta(val title: String?, val artist: String?, val thumb: String?)

    /** 某一类缓存的按曲列表，按大小倒序（大缓存排前，便于优先清理）。 */
    fun build(context: Context, category: AppStorage.Category): List<CachedSong> {
        val sizes = when (category) {
            AppStorage.Category.AUDIO -> OnlineAudioCache.cachedSongSizes(context)
            AppStorage.Category.LYRIC -> LyricDiskCache.cachedSongSizes(context)
            else -> return emptyList()
        }
        if (sizes.isEmpty()) return emptyList()
        val index = CacheSongIndex.all()
        val fallback by lazy { fallbackMeta() }
        return sizes.entries
            .map { (hash, size) ->
                val meta = index[hash]?.let { Meta(it.title, it.artist, it.thumb) } ?: fallback[hash]
                CachedSong(
                    hash = hash,
                    title = meta?.title,
                    artist = meta?.artist,
                    thumb = meta?.thumb?.let { runCatching { Uri.parse(it) }.getOrNull() },
                    sizeBytes = size
                )
            }
            .sortedByDescending { it.sizeBytes }
    }

    /** 删除所选歌曲的该类缓存。 */
    fun delete(context: Context, category: AppStorage.Category, hashes: Collection<String>) {
        when (category) {
            AppStorage.Category.AUDIO -> hashes.forEach { OnlineAudioCache.deleteSong(context, it) }
            AppStorage.Category.LYRIC -> hashes.forEach { LyricDiskCache.delete(context, it) }
            else -> Unit
        }
    }

    /** 回退元数据：索引里没有时用「已存队列 + 最近播放」兜底。 */
    private fun fallbackMeta(): Map<String, Meta> {
        val out = HashMap<String, Meta>()
        fun add(item: YosMediaItem) {
            val mediaId = item.mediaId ?: return
            if (!mediaId.startsWith("kugou-online-")) return
            val hash = mediaId.removePrefix("kugou-online-").lowercase()
            if (hash.isBlank() || out.containsKey(hash)) return
            out[hash] = Meta(item.title, item.artists, item.thumb?.toString())
        }
        runCatching { MusicLibrary.loadPlayList().playingMusicList?.forEach { add(it) } }
        runCatching { MusicLibrary.recentlyPlayed.forEach { add(it) } }
        return out
    }
}
