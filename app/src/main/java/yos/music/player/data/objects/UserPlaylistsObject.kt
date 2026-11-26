package yos.music.player.data.objects

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.data.NormalSaver
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.PendingPlaylistStore
import yos.music.player.native.KugouApiService

internal data class UserPlaylistsSnapshot(
    @SerializedName("userid") val userid: String? = null,
    @SerializedName("loadedAt") val loadedAt: Long = 0L,
    @SerializedName("playlists") val playlists: List<KugouPlaylist?>? = null
) {
    fun validated(expectedUserid: String): List<KugouPlaylist>? {
        if (expectedUserid.isBlank() || userid != expectedUserid) return null
        val values = playlists ?: return null
        if (values.any { it == null || !validCachedUserPlaylist(it) }) return null
        return values.filterNotNull()
    }
}

@Suppress("SENSELESS_COMPARISON")
internal fun validCachedUserPlaylist(entry: KugouPlaylist): Boolean =
    entry.listid != null && entry.gid != null && entry.name != null && entry.coverUrl != null &&
        entry.listCreateGid != null && entry.listCreateUserid != null && entry.listCreateListid != null &&
        entry.specialId != null

private fun userPlaylistKey(pl: KugouPlaylist): String =
    pl.listid.ifEmpty { pl.gid }.ifEmpty { pl.sourceGid }

/**
 * 用户在线歌单列表数据源（内存 State + MMKV 持久化 + TTL + 封面批量归并更新）。
 *
 * - 将原散落在 `NewLibrary` 页面内的在线歌单请求与封面回填抽离为全局单飞数据源：
 *   冷启动 / 切回资料库在线 Tab 时首帧即有数据，且本地 ↔ 在线切换不再重拉。
 * - 封面缓存回填使用并发受限批处理，全部解析完成后一次性写入 `_playlists.value`，
 *   避免逐个歌单热更新导致整页反复重组。
 */
object UserPlaylistsObject {
    private const val KEY = "kugou_user_playlists_v1"
    private const val TTL = 5 * 60 * 1000L
    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _playlists = mutableStateOf<List<KugouPlaylist>>(emptyList())
    val playlists: State<List<KugouPlaylist>> = _playlists

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading

    private var loadedAt = 0L
    private var loadedUserid: String? = null
    private var preloadedUserid: String? = null
    private var loadJob: CompletableDeferred<Unit>? = null
    private var coverJob: Job? = null

    fun reset() {
        synchronized(this) {
            coverJob?.cancel()
            coverJob = null
            loadedAt = 0L
            loadedUserid = null
            preloadedUserid = null
        }
        _playlists.value = emptyList()
        _loading.value = false
    }

    private fun preloadIfNeeded(uid: String) {
        synchronized(this) {
            if (preloadedUserid == uid) return
            preloadedUserid = uid
        }
        runCatching {
            val raw = NormalSaver.readData(KEY, "")
            if (raw.isNullOrBlank()) return
            val snapshot = gson.fromJson(raw, UserPlaylistsSnapshot::class.java) ?: return
            val validated = snapshot.validated(uid) ?: return
            if (uid != KugouApiService.userid) return
            if (_playlists.value.isEmpty() && validated.isNotEmpty()) {
                _playlists.value = validated
                loadedAt = snapshot.loadedAt
                loadedUserid = uid
            }
        }
    }

    fun ensureLoaded(force: Boolean = false) {
        if (!KugouApiService.isLoggedIn()) {
            reset()
            return
        }
        val uid = KugouApiService.userid ?: return
        if (loadedUserid != null && loadedUserid != uid) {
            reset()
        }
        preloadIfNeeded(uid)
        if (!force && loadedUserid == uid && System.currentTimeMillis() - loadedAt < TTL) return
        synchronized(this) {
            loadJob ?: CompletableDeferred<Unit>().also { deferred ->
                loadJob = deferred
                scope.launch {
                    try {
                        loadInternal(uid)
                    } finally {
                        synchronized(this@UserPlaylistsObject) { loadJob = null }
                        deferred.complete(Unit)
                    }
                }
            }
        }
    }

    fun removeLocal(predicate: (KugouPlaylist) -> Boolean) {
        val next = _playlists.value.filterNot(predicate)
        _playlists.value = next
        val uid = loadedUserid ?: KugouApiService.userid
        if (!uid.isNullOrBlank()) {
            val snapshotAt = loadedAt
            scope.launch(Dispatchers.IO) {
                persist(uid, snapshotAt, next)
            }
        }
    }

    private suspend fun loadInternal(uid: String) {
        _loading.value = true
        try {
            val result = withContext(Dispatchers.IO) {
                KugouRepository.getAllUserPlaylistEntries()
            }
            val rawList = result.getOrNull() ?: return
            if (uid != KugouApiService.userid) return

            val prepared = withContext(Dispatchers.IO) {
                val visibleRaw = CollectedPlaylistsObject.mergeEntries(rawList)
                    .filterNot { it.source == 2 || (it.isDef == 1 && it.songCount == 0) }
                var list = visibleRaw.map { pl ->
                    val cached = PlaylistCoverCache.read(pl)
                    if (cached != null) pl.copy(coverUrl = cached) else pl
                }
                val pending = PendingPlaylistStore.all()
                    .filter { it.userid == uid && !it.isBound }
                val toBind = mutableListOf<PendingPlaylistStore.PendingPlaylist>()
                pending.forEach { entry ->
                    if (list.any { it.name == entry.name }) {
                        toBind.add(entry)
                    } else {
                        list = list + KugouPlaylist(
                            listid = entry.localId,
                            gid = "",
                            name = entry.name,
                            coverUrl = "",
                            songCount = 0,
                            isDef = 0
                        )
                    }
                }
                Triple(list, visibleRaw, toBind)
            }

            if (uid != KugouApiService.userid) return
            val (list, visibleRaw, toBind) = prepared

            val now = System.currentTimeMillis()
            _playlists.value = list
            loadedAt = now
            loadedUserid = uid

            scope.launch(Dispatchers.IO) {
                persist(uid, now, list)
                toBind.forEach { runCatching { PendingPlaylistStore.tryBind(it) } }
            }

            val targets = visibleRaw.filter { it.usesTrackCover && !it.isCustomPic && it.songCount > 0 }
            if (targets.isNotEmpty()) {
                coverJob?.cancel()
                coverJob = scope.launch {
                    val resolvedMap = withContext(Dispatchers.IO) {
                        coroutineScope {
                            targets.map { pl ->
                                async {
                                    if (uid != KugouApiService.userid) return@async null
                                    val cover = PlaylistCoverCache.ensure(pl) ?: return@async null
                                    userPlaylistKey(pl) to cover
                                }
                            }.awaitAll().filterNotNull().toMap()
                        }
                    }
                    if (resolvedMap.isEmpty() || uid != KugouApiService.userid) return@launch
                    var changed = false
                    val updated = _playlists.value.map { pl ->
                        val nextCover = resolvedMap[userPlaylistKey(pl)]
                        if (nextCover != null && pl.coverUrl != nextCover) {
                            changed = true
                            pl.copy(coverUrl = nextCover)
                        } else {
                            pl
                        }
                    }
                    if (changed) {
                        _playlists.value = updated
                        val snapshotAt = loadedAt
                        withContext(Dispatchers.IO) {
                            persist(uid, snapshotAt, updated)
                        }
                    }
                }
            }
        } finally {
            _loading.value = false
        }
    }

    private fun persist(userid: String, timestamp: Long, playlists: List<KugouPlaylist>) {
        runCatching {
            NormalSaver.saveData(
                KEY,
                gson.toJson(UserPlaylistsSnapshot(userid = userid, loadedAt = timestamp, playlists = playlists))
            )
        }
    }
}
