package yos.music.player.widget

import android.content.Context
import android.os.Looper
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import yos.music.player.code.MediaController
import yos.music.player.code.NotificationPlaybackMode
import yos.music.player.code.notificationModeOf
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.objects.MediaViewModelObject

data class WidgetTrackSnapshot(
    val mediaId: String? = null,
    val uri: String? = null,
    val title: String = "",
    val artist: String = "",
    val thumbUri: String? = null,
)

data class WidgetSnapshot(
    val lastTrack: WidgetTrackSnapshot? = null,
    val queue: List<WidgetTrackSnapshot> = emptyList(),
)

data class SonifyWidgetUiState(
    val active: Boolean,
    val isPlaying: Boolean,
    val mode: NotificationPlaybackMode,
    val currentTrack: WidgetTrackSnapshot?,
    val queue: List<WidgetTrackSnapshot>,
)

object SonifyWidgetState {
    private const val MMKV_ID = "sonify_widget"
    private const val KEY_SNAPSHOT = "widget_snapshot_v1"
    const val MAX_QUEUE_ITEMS = 20

    private val gson = Gson()

    @Volatile
    var hasPlayedInSession: Boolean = false

    fun YosMediaItem.toWidgetSnapshot(): WidgetTrackSnapshot {
        return WidgetTrackSnapshot(
            mediaId = mediaId,
            uri = uri?.toString(),
            title = title?.takeIf { it.isNotBlank() } ?: "Sonify",
            artist = artists?.takeIf { it.isNotBlank() } ?: "",
            thumbUri = thumb?.toString(),
        )
    }

    /**
     * 生成空载队列展示顺序：
     * 第 1 首为上次播放曲目，其余按「接下来要播的顺序」（从上次曲目之后向下排列，
     * 到达队尾后绕回队首），去重且最多保留 [maxItems] 首。
     */
    fun buildOrderedQueue(
        lastTrack: WidgetTrackSnapshot?,
        fullQueue: List<WidgetTrackSnapshot>,
        maxItems: Int = MAX_QUEUE_ITEMS,
    ): List<WidgetTrackSnapshot> {
        if (fullQueue.isEmpty()) {
            return if (lastTrack != null) listOf(lastTrack) else emptyList()
        }
        val rotated = if (lastTrack != null) {
            val idx = fullQueue.indexOfFirst { sameTrack(it, lastTrack) }
            if (idx >= 0) {
                fullQueue.drop(idx) + fullQueue.take(idx)
            } else {
                listOf(lastTrack) + fullQueue
            }
        } else {
            fullQueue
        }

        val seen = HashSet<String>(rotated.size)
        val result = ArrayList<WidgetTrackSnapshot>(minOf(rotated.size, maxItems))
        for (item in rotated) {
            val key = trackKey(item)
            if (seen.add(key)) {
                result.add(item)
                if (result.size >= maxItems) break
            }
        }
        return result
    }

    private fun sameTrack(a: WidgetTrackSnapshot, b: WidgetTrackSnapshot): Boolean {
        if (!a.mediaId.isNullOrBlank() && !b.mediaId.isNullOrBlank()) {
            return a.mediaId == b.mediaId
        }
        if (!a.uri.isNullOrBlank() && !b.uri.isNullOrBlank()) {
            return a.uri == b.uri
        }
        return a.title == b.title && a.artist == b.artist
    }

    private fun trackKey(item: WidgetTrackSnapshot): String {
        if (!item.mediaId.isNullOrBlank()) return "id:${item.mediaId}"
        if (!item.uri.isNullOrBlank()) return "uri:${item.uri}"
        return "txt:${item.title}|${item.artist}"
    }

    fun saveSnapshot(context: Context, snapshot: WidgetSnapshot) {
        ensureMmkv(context)
        runCatching {
            MMKV.mmkvWithID(MMKV_ID).encode(KEY_SNAPSHOT, gson.toJson(snapshot))
        }
    }

    fun loadSnapshot(context: Context): WidgetSnapshot? {
        ensureMmkv(context)
        return runCatching {
            val raw = MMKV.mmkvWithID(MMKV_ID).decodeString(KEY_SNAPSHOT) ?: return null
            gson.fromJson(raw, WidgetSnapshot::class.java)
        }.getOrNull()
    }

    fun resolveCurrentState(
        context: Context,
        forceEmpty: Boolean = false,
    ): SonifyWidgetUiState {
        // 进程内优先读内存状态（服务或恢复流程已填充）
        val memCurrent = if (Looper.myLooper() == Looper.getMainLooper()) {
            MediaController.musicPlaying.value
        } else {
            runCatching { MediaController.musicPlaying.value }.getOrNull()
        }
        val memQueue = if (Looper.myLooper() == Looper.getMainLooper()) {
            MediaController.playingMusicList.value
        } else {
            runCatching { MediaController.playingMusicList.value }.getOrNull()
        }

        val isPlaying = !forceEmpty && runCatching { MediaViewModelObject.isPlaying.value }.getOrDefault(false)
        val shuffle = runCatching { MediaViewModelObject.shuffleModeEnabled.value }.getOrDefault(false)
        val repeat = runCatching { MediaViewModelObject.repeatMode.intValue }.getOrDefault(0)
        val active = !forceEmpty && (hasPlayedInSession || isPlaying) && memCurrent != null

        if (memCurrent != null || !memQueue.isNullOrEmpty()) {
            val currentSnap = memCurrent?.toWidgetSnapshot()
            val orderedQueue = buildOrderedQueue(
                lastTrack = currentSnap,
                fullQueue = memQueue.orEmpty().map { it.toWidgetSnapshot() },
            )
            return SonifyWidgetUiState(
                active = active,
                isPlaying = isPlaying,
                mode = notificationModeOf(shuffle, repeat),
                currentTrack = currentSnap ?: orderedQueue.firstOrNull(),
                queue = orderedQueue,
            )
        }

        // 进程刚被唤醒/未跑恢复流程时，读精简快照；若无快照再回退 MusicLibrary
        val cached = loadSnapshot(context)
        if (cached != null && (cached.lastTrack != null || cached.queue.isNotEmpty())) {
            val ordered = buildOrderedQueue(cached.lastTrack, cached.queue)
            return SonifyWidgetUiState(
                active = false,
                isPlaying = false,
                mode = NotificationPlaybackMode.ORDER,
                currentTrack = cached.lastTrack ?: ordered.firstOrNull(),
                queue = ordered,
            )
        }

        ensureMmkv(context)
        val status = runCatching { MusicLibrary.loadPlayStatus() }.getOrNull()
        val list = runCatching { MusicLibrary.loadPlayList() }.getOrNull()
        val lastSnap = status?.music?.toWidgetSnapshot()
        val queueSnaps = list?.playingMusicList.orEmpty().map { it.toWidgetSnapshot() }
        val ordered = buildOrderedQueue(lastSnap, queueSnaps)
        val fallbackState = SonifyWidgetUiState(
            active = false,
            isPlaying = false,
            mode = notificationModeOf(
                status?.shuffleModeEnabled == true,
                status?.repeatMode ?: 0,
            ),
            currentTrack = lastSnap ?: ordered.firstOrNull(),
            queue = ordered,
        )
        if (fallbackState.currentTrack != null || ordered.isNotEmpty()) {
            saveSnapshot(context, WidgetSnapshot(fallbackState.currentTrack, ordered))
        }
        return fallbackState
    }

    fun persistFromLive(
        context: Context,
        current: YosMediaItem?,
        queue: List<YosMediaItem>?,
    ) {
        val currentSnap = current?.toWidgetSnapshot()
        val queueSnaps = queue.orEmpty().map { it.toWidgetSnapshot() }
        val ordered = buildOrderedQueue(currentSnap, queueSnaps)
        if (currentSnap != null || ordered.isNotEmpty()) {
            saveSnapshot(context, WidgetSnapshot(currentSnap, ordered))
        }
    }

    private fun ensureMmkv(context: Context) {
        runCatching {
            if (MMKV.getRootDir() == null) {
                MMKV.initialize(context.applicationContext)
            }
        }
    }
}
