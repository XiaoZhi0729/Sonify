package yos.music.player.data.objects

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mmkv.MMKV
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem

/**
 * 缓存歌曲元数据索引：hash → 歌名/歌手/封面。
 *
 * 背景：音频字节缓存（SimpleCache）与歌词缓存都只按 hash 存内容，**不带任何歌名信息**；
 * 设置页「数据与储存」要按歌曲挑选清理，就得把缓存里的 hash 还原成可读的歌。
 *
 * 记录时机：切歌时（[yos.music.player.code.MediaController.handleTrackSwitched]）与
 * [record] 同点位写入——凡被播放过（也就是被缓存过）的歌，此后都能拿到歌名。
 * 存量缓存（本功能上线前已缓存的歌）由 [backfillOnce] 在首次启动时用
 * 歌单/专辑首屏缓存 + 已存队列 + 最近播放回填一次。
 *
 * 存储：独立 MMKV 实例、按 hash 单键写入（照抄 [yos.music.player.data.repositories.KugouRepository]
 * 的 performer 侧表模式，避免整表 JSON 反复重写）。键名走 @SerializedName 冻结，防 release 混淆漂移。
 */
object CacheSongIndex {

    private const val MMKV_ID = "sonify_cache_index"
    private const val MAX_ENTRIES = 3000
    private const val BACKFILL_FLAG = "backfilled_v1"

    private val gson = Gson()

    // @SerializedName 冻结缓存 JSON 键名（Gson 反射用运行时字段名，release 混淆后漂移读不出）
    private data class Stored(
        @SerializedName("t") val title: String?,
        @SerializedName("a") val artist: String?,
        @SerializedName("u") val thumb: String?,
        @SerializedName("ts") val ts: Long
    )

    /** 对外暴露的元数据。 */
    data class Meta(val title: String?, val artist: String?, val thumb: String?)

    private val store by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull()
    }

    /** 切歌时记录当前曲的元数据。仅在线曲（mediaId 前缀 kugou-online-）入索引。 */
    fun record(item: YosMediaItem) {
        val mediaId = item.mediaId ?: return
        if (!mediaId.startsWith("kugou-online-")) return
        val hash = mediaId.removePrefix("kugou-online-")
        if (hash.isBlank()) return
        put(hash, item.title, item.artists, item.thumb?.toString())
    }

    /** 按 hash 记录（供回填与内部使用）。 */
    fun put(hash: String, title: String?, artist: String?, thumb: String?) {
        val s = store ?: return
        if (hash.isBlank()) return
        runCatching {
            s.encode(hash.lowercase(), gson.toJson(Stored(title, artist, thumb, System.currentTimeMillis())))
            trimIfNeeded(s)
        }
    }

    fun lookup(hash: String): Meta? {
        val s = store ?: return null
        val json = runCatching { s.decodeString(hash.lowercase()) }.getOrNull() ?: return null
        val st = runCatching { gson.fromJson(json, Stored::class.java) }.getOrNull() ?: return null
        return Meta(st.title, st.artist, st.thumb)
    }

    /** 一次性读出全部索引（供挑选列表批量查表，避免逐 hash decodeString）。 */
    fun all(): Map<String, Meta> {
        val s = store ?: return emptyMap()
        return runCatching {
            s.allKeys()
                ?.asSequence()
                ?.filter { it != BACKFILL_FLAG }
                ?.mapNotNull { k ->
                    val st = runCatching { s.decodeString(k)?.let { gson.fromJson(it, Stored::class.java) } }
                        .getOrNull() ?: return@mapNotNull null
                    k to Meta(st.title, st.artist, st.thumb)
                }
                ?.toMap()
                ?: emptyMap()
        }.getOrDefault(emptyMap())
    }

    /**
     * 首次启动回填一次：用存量缓存的元数据来源把索引补齐。
     * 来源 = 歌单/专辑首屏缓存（含 hash→歌名/歌手/封面）+ 已存队列 + 最近播放。
     */
    fun backfillOnce(context: Context) {
        val s = store ?: return
        if (runCatching { s.decodeBool(BACKFILL_FLAG) }.getOrDefault(false)) return
        runCatching {
            OnlinePlaylistCache.allTracks(context).forEach { put(it.hash, it.name, it.artist, it.coverUrl) }
            OnlineAlbumCache.allTracks(context).forEach { put(it.hash, it.name, it.author, it.artworkUrl) }
            MusicLibrary.loadPlayList().playingMusicList?.forEach { record(it) }
            MusicLibrary.recentlyPlayed.forEach { record(it) }
        }
        runCatching { s.encode(BACKFILL_FLAG, true) }
    }

    /** 超上限时按写入时间淘汰最旧的约 10%。 */
    private fun trimIfNeeded(s: MMKV) {
        val keys = s.allKeys() ?: return
        if (keys.size <= MAX_ENTRIES) return
        val drop = (MAX_ENTRIES / 10).coerceAtLeast(1)
        keys.asSequence()
            .filter { it != BACKFILL_FLAG }
            .mapNotNull { k ->
                val st = runCatching { s.decodeString(k)?.let { gson.fromJson(it, Stored::class.java) } }
                    .getOrNull() ?: return@mapNotNull null
                k to st.ts
            }
            .sortedBy { it.second }
            .take(drop)
            .forEach { s.removeValueForKey(it.first) }
    }
}
