package yos.music.player.data.objects

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.repositories.DyCoverLookupResult
import yos.music.player.data.repositories.KugouCommentRepository
import yos.music.player.data.repositories.NetworkKind
import yos.music.player.data.repositories.NetworkObserver
import yos.music.player.data.repositories.parseDyCoverResponse
import yos.music.player.native.KugouApiService
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 酷狗专辑动态封面状态中心与双层缓存（MMKV 元数据 + 150MB 磁盘 MP4 LRU）。
 *
 * 与 [SongClimaxState] 同构：切歌时由 `MediaController` 驱动，UI（竖屏卡片、全屏封面、横屏大封面）
 * 仅订阅 [videoUri] 与 [mediaId]。
 */
@Stable
object DynamicCoverState {

    private const val TAG = "DynamicCoverState"
    private const val MMKV_ID = "sonify_dynamic_cover_meta"
    private const val ONLINE_PREFIX = "kugou-online-"
    private const val CACHE_DIR_NAME = "dycover_cache"
    private const val MAX_META_ENTRIES = 3000
    private const val NEGATIVE_TTL_MS = 24 * 60 * 60 * 1000L
    private const val DISK_CACHE_MAX_BYTES = 150L * 1024L * 1024L
    private const val MIN_VALID_VIDEO_BYTES = 1024L

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val downloadClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .build()
    }

    private data class StoredMeta(
        @SerializedName("id") val albumAudioId: Long,
        @SerializedName("has") val hasCover: Boolean,
        @SerializedName("t") val ts: Long
    )

    @Volatile
    private var appContext: Context? = null

    private val store by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull()
    }

    private val activeDownloads = ConcurrentHashMap.newKeySet<Long>()
    private val trimLock = Any()
    private var fetchJob: Job? = null

    /** 当前状态归属的曲目 mediaId。 */
    var mediaId: String? by mutableStateOf(null)
        private set

    /**
     * 当前曲目可播放的动态封面视频 URI；null 表示无动态封面或被开关/网络策略门禁。
     * 若本地磁盘缓存已命中，返回 `file://`；否则先返回本地回环流代理 URL 并后台落盘。
     */
    var videoUri: Uri? by mutableStateOf(null)
        private set

    /** 在 Application.onCreate 主线程初始化，提前绑定 Context 并预热快照状态。 */
    fun init(context: Context) {
        appContext = context.applicationContext
        mediaId
        videoUri
    }

    /** 切歌入口（由 MediaController.handleTrackSwitched 调用）。 */
    fun onTrackChanged(newMediaId: String?) {
        if (newMediaId == mediaId && (videoUri != null || fetchJob?.isActive == true)) return
        mediaId = newMediaId
        evaluateCurrentTrack()
    }

    /** 设置页切换「专辑动态封面」或「允许移动网络下加载」时立即刷新当前曲目。 */
    fun onSettingChanged() {
        evaluateCurrentTrack()
    }

    /** 网络形态切换时触发（例如从移动网络切回 Wi-Fi 时自动补发探测）。 */
    fun onNetworkChanged() {
        if (videoUri == null && mediaId != null && SettingsLibrary.NowPlayingDynamicCover) {
            evaluateCurrentTrack()
        }
    }

    private fun evaluateCurrentTrack() {
        fetchJob?.cancel()
        fetchJob = null

        val currentId = mediaId
        val hash = extractOnlineHash(currentId)
        if (hash == null || !SettingsLibrary.NowPlayingDynamicCover) {
            videoUri = null
            return
        }

        val cachedMeta = readMetaCache(hash)
        if (cachedMeta != null) {
            if (cachedMeta.hasCover && cachedMeta.albumAudioId > 0L) {
                val cachedFile = cachedFileFor(cachedMeta.albumAudioId)
                if (cachedFile != null && cachedFile.exists() && cachedFile.length() > MIN_VALID_VIDEO_BYTES) {
                    runCatching { cachedFile.setLastModified(System.currentTimeMillis()) }
                    videoUri = Uri.fromFile(cachedFile)
                    YosDiagnostics.log("DYCOVER", "src" to "disk", "id" to cachedMeta.albumAudioId)
                    return
                }
            } else if (!cachedMeta.hasCover &&
                System.currentTimeMillis() - cachedMeta.ts < NEGATIVE_TTL_MS
            ) {
                videoUri = null
                return
            }
        }

        if (!isNetworkAllowed()) {
            videoUri = null
            return
        }

        videoUri = null
        val requestId = currentId ?: return
        fetchJob = scope.launch {
            try {
                resolveAndPublish(requestId, hash, cachedMeta)
            } catch (cancel: kotlinx.coroutines.CancellationException) {
                throw cancel
            } catch (e: Exception) {
                Log.w(TAG, "resolveAndPublish failed: ${e.message}")
            }
        }
    }

    private suspend fun resolveAndPublish(
        requestId: String,
        hash: String,
        cachedMeta: StoredMeta?
    ) {
        val albumAudioId = cachedMeta?.albumAudioId?.takeIf { it > 0L }
            ?: runCatching { KugouCommentRepository.resolveMixSongId(hash) }.getOrDefault(0L)
        if (albumAudioId <= 0L) return

        // 拿到 albumAudioId 后再查一次本地磁盘缓存（同一专辑音轨可能对应不同 hash）
        val cachedFile = cachedFileFor(albumAudioId)
        if (cachedFile != null && cachedFile.exists() && cachedFile.length() > MIN_VALID_VIDEO_BYTES) {
            runCatching { cachedFile.setLastModified(System.currentTimeMillis()) }
            writeMetaCache(hash, StoredMeta(albumAudioId, hasCover = true, ts = System.currentTimeMillis()))
            withContext(Dispatchers.Main) {
                if (mediaId == requestId && SettingsLibrary.NowPlayingDynamicCover) {
                    videoUri = Uri.fromFile(cachedFile)
                }
            }
            return
        }

        val confirmedHasCover = if (cachedMeta?.hasCover == true && cachedMeta.albumAudioId == albumAudioId) {
            true
        } else {
            val lookup = runCatching {
                KugouApiService.getInstance().getAlbumDynamicCover(albumAudioId).getOrNull()
                    ?.let { parseDyCoverResponse(it, albumAudioId) }
            }.getOrNull() ?: return

            when (lookup) {
                is DyCoverLookupResult.Absent -> {
                    writeMetaCache(hash, StoredMeta(albumAudioId, hasCover = false, ts = System.currentTimeMillis()))
                    withContext(Dispatchers.Main) {
                        if (mediaId == requestId) videoUri = null
                    }
                    return
                }
                is DyCoverLookupResult.Present -> {
                    writeMetaCache(hash, StoredMeta(albumAudioId, hasCover = true, ts = System.currentTimeMillis()))
                    true
                }
            }
        }

        if (!confirmedHasCover) return

        val streamUrl = KugouApiService.getInstance().getAlbumDynamicCoverMediaUrl(albumAudioId)
            ?: return
        withContext(Dispatchers.Main) {
            if (mediaId == requestId && SettingsLibrary.NowPlayingDynamicCover) {
                videoUri = Uri.parse(streamUrl)
                YosDiagnostics.log("DYCOVER", "src" to "stream", "id" to albumAudioId)
            }
        }

        // 后台异步将 MP4 落盘缓存，供下次播放离线/免流量秒开
        enqueueDiskCacheDownload(albumAudioId, streamUrl)
    }

    private fun enqueueDiskCacheDownload(albumAudioId: Long, streamUrl: String) {
        val dir = cacheDir() ?: return
        val target = File(dir, "$albumAudioId.mp4")
        if (target.exists() && target.length() > MIN_VALID_VIDEO_BYTES) return
        if (!activeDownloads.add(albumAudioId)) return

        scope.launch {
            val tmp = File(dir, "$albumAudioId.mp4.tmp")
            try {
                val request = Request.Builder().url(streamUrl).get().build()
                downloadClient.newCall(request).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val body = resp.body ?: return@use
                    body.byteStream().use { input ->
                        FileOutputStream(tmp).use { output ->
                            input.copyTo(output, bufferSize = 16 * 1024)
                        }
                    }
                }
                if (tmp.exists() && tmp.length() > MIN_VALID_VIDEO_BYTES) {
                    if (target.exists()) target.delete()
                    if (tmp.renameTo(target)) {
                        target.setLastModified(System.currentTimeMillis())
                        trimDiskCacheIfNeeded(dir, excludeName = target.name)
                    } else {
                        tmp.delete()
                    }
                } else {
                    tmp.delete()
                }
            } catch (e: Exception) {
                Log.w(TAG, "cache download failed for $albumAudioId: ${e.message}")
                runCatching { tmp.delete() }
            } finally {
                activeDownloads.remove(albumAudioId)
            }
        }
    }

    private fun isNetworkAllowed(): Boolean {
        val ctx = appContext ?: return false
        val kind = NetworkObserver.kind(ctx)
        return when (kind) {
            NetworkKind.UNMETERED -> true
            NetworkKind.METERED -> SettingsLibrary.NowPlayingDynamicCoverMobile
            NetworkKind.UNKNOWN -> false
        }
    }

    private fun extractOnlineHash(id: String?): String? =
        id?.takeIf { it.startsWith(ONLINE_PREFIX) }
            ?.removePrefix(ONLINE_PREFIX)
            ?.lowercase()
            ?.takeIf { it.isNotEmpty() }

    private fun cacheDir(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, CACHE_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun cachedFileFor(albumAudioId: Long): File? =
        cacheDir()?.let { File(it, "$albumAudioId.mp4") }

    /** 本地缓存视频解码失败时移除损坏文件并回退静态封面。 */
    fun onPlaybackError(uri: Uri?) {
        if (uri?.scheme == "file") {
            val path = uri.path
            if (!path.isNullOrEmpty()) {
                runCatching { File(path).delete() }
            }
            if (videoUri == uri) {
                videoUri = null
            }
        }
    }

    private fun trimDiskCacheIfNeeded(dir: File, excludeName: String? = null) {
        synchronized(trimLock) {
            val files = dir.listFiles { file ->
                file.isFile && file.name.endsWith(".mp4")
            } ?: return
            var totalBytes = files.sumOf { it.length() }
            if (totalBytes <= DISK_CACHE_MAX_BYTES) return
            val sorted = files
                .filter { it.name != excludeName }
                .sortedBy { it.lastModified() }
            for (file in sorted) {
                if (totalBytes <= DISK_CACHE_MAX_BYTES) break
                val len = file.length()
                if (file.delete()) {
                    totalBytes -= len
                }
            }
        }
    }

    private fun readMetaCache(hash: String): StoredMeta? {
        val s = store ?: return null
        val json = runCatching { s.decodeString(hash) }.getOrNull() ?: return null
        return runCatching { gson.fromJson(json, StoredMeta::class.java) }.getOrNull()
    }

    private fun writeMetaCache(hash: String, meta: StoredMeta) {
        val s = store ?: return
        runCatching {
            s.encode(hash, gson.toJson(meta))
            trimMetaIfNeeded(s)
        }
    }

    private fun trimMetaIfNeeded(s: MMKV) {
        synchronized(trimLock) {
            val keys = s.allKeys() ?: return
            if (keys.size <= MAX_META_ENTRIES) return
            val drop = (MAX_META_ENTRIES / 10).coerceAtLeast(1)
            keys.asSequence()
                .mapNotNull { k ->
                    val st = runCatching { s.decodeString(k)?.let { gson.fromJson(it, StoredMeta::class.java) } }
                        .getOrNull() ?: return@mapNotNull null
                    k to st.ts
                }
                .sortedBy { it.second }
                .take(drop)
                .forEach { s.removeValueForKey(it.first) }
        }
    }
}
