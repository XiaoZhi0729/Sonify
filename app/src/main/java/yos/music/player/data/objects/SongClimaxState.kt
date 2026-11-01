package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.repositories.SongClimax
import yos.music.player.data.repositories.parseSongClimaxResponse
import yos.music.player.native.KugouApiService

/**
 * 当前曲的高潮起点（播放页进度条标记的数据源），同时是它自己的磁盘缓存。
 *
 * 与 [VocalSeparationState] / [PlaybackBlockState] 同构：服务侧写、Compose 读。
 * 存储照抄 [CacheSongIndex] 的「独立 MMKV 实例 + 按 hash 单键写入」，避免整表 JSON 反复重写。
 *
 * 线程约定：写点全部在主线程（切歌回调 / 请求回来切回主线程），读点在组合，不加锁。
 */
@Stable
object SongClimaxState {

    private const val MMKV_ID = "sonify_song_climax"
    private const val MAX_ENTRIES = 3000

    /** 命中负缓存的冷却时间：上游确认「没有高潮」后一天内不再重问。 */
    private const val NEGATIVE_TTL_MS = 24 * 60 * 60 * 1000L

    private const val ONLINE_PREFIX = "kugou-online-"

    private val gson = Gson()

    // @SerializedName 冻结缓存 JSON 键名：Gson 走反射用运行时字段名，
    // release 混淆后键名漂移会读回全 null（CacheSongIndex / LyricDiskCache 都踩过）。
    private data class Stored(
        @SerializedName("s") val startMs: Long,
        @SerializedName("e") val endMs: Long,
        @SerializedName("t") val ts: Long
    )

    private val store by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull()
    }

    /** 高潮起点（毫秒）。0 = 没有数据（未取到 / 上游确实没有）。 */
    var startMs: Long by mutableLongStateOf(0L)
        private set

    /** 高潮终点（毫秒）。0 = 上游只给了起点，或本曲没有数据。 */
    var endMs: Long by mutableLongStateOf(0L)
        private set

    /** 当前状态归属的曲目；用来丢弃迟到的旧请求结果。 */
    var mediaId: String? by mutableStateOf(null)
        private set

    /** 正在取数据的曲目，避免队列重建时重复打接口。 */
    private var inFlightId: String? = null

    /**
     * 切歌入口（由 [yos.music.player.code.MediaController.handleTrackSwitched] 调用）。
     *
     * 与 `CacheSongIndex.record` 同点位：一次轻量 MMKV 读 + 可能的一次后台请求。
     * 本地歌曲没有 hash、没有数据源，直接清空状态。
     */
    fun onTrackChanged(newMediaId: String?) {
        if (newMediaId == mediaId && (startMs > 0L || inFlightId == newMediaId)) return
        mediaId = newMediaId
        val hash = newMediaId?.takeIf { it.startsWith(ONLINE_PREFIX) }
            ?.removePrefix(ONLINE_PREFIX)
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
        if (hash == null) {
            clear()
            return
        }
        if (!SettingsLibrary.NowPlayingShowClimaxMark) {
            clear()
            return
        }
        val cached = readCache(hash)
        if (cached != null) {
            // 负缓存：s == 0 且未过期 —— 上游确认没有，别再问
            if (cached.startMs <= 0L &&
                System.currentTimeMillis() - cached.ts < NEGATIVE_TTL_MS
            ) {
                clear()
                return
            }
            if (cached.startMs > 0L) {
                startMs = cached.startMs
                endMs = cached.endMs
                YosDiagnostics.log("CLIMAX", "src" to "cache", "s" to cached.startMs)
                return
            }
        }
        clear()
        fetch(hash)
    }

    /** 开关从关到开时补一次（否则当前曲要等下次切歌才有标记）。 */
    fun onSettingEnabled() {
        val current = mediaId ?: return
        if (startMs > 0L || inFlightId == current) return
        val hash = current.takeIf { it.startsWith(ONLINE_PREFIX) }
            ?.removePrefix(ONLINE_PREFIX)
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }
            ?: return
        fetch(hash)
    }

    fun onSettingDisabled() {
        clear()
    }

    private fun clear() {
        startMs = 0L
        endMs = 0L
    }

    private fun fetch(hash: String) {
        val requestId = ONLINE_PREFIX + hash
        inFlightId = requestId
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                KugouApiService.getInstance().getSongClimax(hash).getOrNull()
                    ?.let { parseSongClimaxResponse(it) }
            }.getOrNull()
            writeCache(hash, result ?: SongClimax(startMs = 0L, endMs = 0L))
            kotlinx.coroutines.withContext(Dispatchers.Main) {
                if (inFlightId == requestId) inFlightId = null
                // 请求期间可能已经切歌：结果只对仍是当前曲的请求生效
                if (mediaId != requestId) return@withContext
                startMs = result?.startMs ?: 0L
                endMs = result?.endMs ?: 0L
                YosDiagnostics.log(
                    "CLIMAX",
                    "src" to "net",
                    "s" to (result?.startMs ?: 0L),
                    "e" to (result?.endMs ?: 0L)
                )
            }
        }
    }

    private fun readCache(hash: String): Stored? {
        val s = store ?: return null
        val json = runCatching { s.decodeString(hash) }.getOrNull() ?: return null
        return runCatching { gson.fromJson(json, Stored::class.java) }.getOrNull()
    }

    private fun writeCache(hash: String, climax: SongClimax) {
        val s = store ?: return
        runCatching {
            s.encode(
                hash,
                gson.toJson(Stored(climax.startMs, climax.endMs, System.currentTimeMillis()))
            )
            trimIfNeeded(s)
        }
    }

    /** 超上限时按写入时间淘汰最旧的约 10%。 */
    private fun trimIfNeeded(s: MMKV) {
        val keys = s.allKeys() ?: return
        if (keys.size <= MAX_ENTRIES) return
        val drop = (MAX_ENTRIES / 10).coerceAtLeast(1)
        keys.asSequence()
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
