package yos.music.player.data.objects

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import yos.music.player.data.NormalSaver
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.PlaylistGeneration
import yos.music.player.data.repositories.PlaylistMutationUnknownException
import yos.music.player.data.repositories.mergeCollectedPlaylistEntries
import yos.music.player.native.AuthSnapshot
import yos.music.player.native.KugouApiService

/** Nullable wire fields deliberately validate Gson's ability to bypass Kotlin constructors. */
internal data class CollectedPlaylistSnapshot(
    @SerializedName("userid") val userid: String? = null,
    @SerializedName("entries") val entries: List<KugouPlaylist?>? = null
) {
    fun validated(expectedUserid: String): List<KugouPlaylist>? {
        if (userid != expectedUserid) return null
        val values = entries ?: return null
        if (values.any { it == null || !validCachedPlaylist(it) }) return null
        return values.filterNotNull()
    }
}

@Suppress("SENSELESS_COMPARISON")
internal fun validCachedPlaylist(entry: KugouPlaylist): Boolean =
    entry.listid != null && entry.gid != null && entry.name != null && entry.coverUrl != null &&
        entry.listCreateGid != null && entry.listCreateUserid != null && entry.listCreateListid != null &&
        entry.specialId != null && entry.isCollectedPlaylist && entry.sourceGid.isNotBlank()

/** The original gid is the only collection identity. Names never participate in matching. */
object CollectedPlaylistsObject {
    private const val KEY = "kugou_collected_playlists_v1"
    private const val TTL = 300_000L
    private const val PROPAGATION_MS = 120_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val gson = Gson()
    private val gate = Mutex()
    private val lock = Any()
    private var epoch = 0L
    private var owner: PlaylistGeneration? = null
    private var loadedAt = 0L
    private var loadJob: Job? = null
    private var reconcileJob: Job? = null
    private data class Pending(val entry: KugouPlaylist, val add: Boolean, val until: Long, val unknown: Boolean = false)
    private val pending = mutableMapOf<String, Pending>()
    private val _entries = mutableStateOf<List<KugouPlaylist>>(emptyList())
    val entries: State<List<KugouPlaylist>> = _entries
    private val _loaded = mutableStateOf(false)
    val loaded: State<Boolean> = _loaded
    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading
    private val _error = mutableStateOf<String?>(null)
    val error: State<String?> = _error
    private val _busy = mutableStateOf<Set<String>>(emptySet())
    val busy: State<Set<String>> = _busy

    /** Called synchronously from publishAuth, including a same-account credential generation change. */
    fun reset() = synchronized(lock) {
        epoch++
        loadJob?.cancel(); loadJob = null
        reconcileJob?.cancel(); reconcileJob = null
        owner = null
        pending.clear()
        replaceEntries(emptyList())
        _loaded.value = false
        _loading.value = false
        _error.value = null
        _busy.value = emptySet()
        loadedAt = 0L
    }

    private fun generation(auth: AuthSnapshot): PlaylistGeneration = synchronized(lock) {
        val next = PlaylistGeneration(auth.userid, auth.version, epoch)
        if (owner?.matches(auth.userid, auth.version, epoch) != true) {
            pending.clear()
            replaceEntries(emptyList())
            _loaded.value = false
            _busy.value = emptySet()
            loadedAt = 0L
            owner = next
            if (auth.isLoggedIn) runCatching {
                val raw = NormalSaver.readData(KEY, "")
                gson.fromJson(raw, CollectedPlaylistSnapshot::class.java)?.validated(auth.userid.orEmpty())
                    ?.let(::replaceEntries)
            }
        }
        next
    }

    private fun active(g: PlaylistGeneration): Boolean {
        val auth = KugouApiService.authSnapshot()
        return g.matches(auth.userid, auth.version, epoch) && auth.isLoggedIn
    }

    private fun requireActive(g: PlaylistGeneration) {
        if (!active(g)) throw CancellationException("歌单账号已变化")
    }

    fun contains(sourceGid: String): Boolean {
        val auth = KugouApiService.authSnapshot()
        return synchronized(lock) {
            owner?.matches(auth.userid, auth.version, epoch) == true && auth.isLoggedIn &&
                sourceGid.isNotBlank() && _entries.value.any { it.sourceGid == sourceGid }
        }
    }

    fun ensureLoaded(force: Boolean = false) {
        val auth = KugouApiService.authSnapshot()
        if (!auth.isLoggedIn) { reset(); return }
        val g = generation(auth)
        synchronized(lock) {
            if (!force && _loaded.value && System.currentTimeMillis() - loadedAt < TTL) return
            if (loadJob?.isActive == true) return
            _loading.value = true
            _error.value = null
            loadJob = scope.launch { load(auth, g) }
        }
    }

    private suspend fun load(auth: AuthSnapshot, g: PlaylistGeneration): Result<Unit> = gate.withLock {
        synchronized(lock) {
            requireActive(g)
            _loading.value = true
            _error.value = null
        }
        try {
            val all = KugouRepository.getAllUserPlaylistEntries(auth).getOrThrow()
            synchronized(lock) {
                requireActive(g)
                val now = System.currentTimeMillis()
                val cloud = all.filter { it.isCollectedPlaylist && it.sourceGid.isNotBlank() }
                val merged = cloud.associateBy { it.sourceGid }.toMutableMap()
                val iterator = pending.iterator()
                while (iterator.hasNext()) {
                    val (gid, operation) = iterator.next()
                    val found = cloud.firstOrNull { it.sourceGid == gid }
                    val confirmed = if (operation.add) found != null && found.listid.isNotBlank() else found == null
                    if ((operation.add && confirmed) || now >= operation.until) {
                        iterator.remove()
                    } else if (operation.add) merged[gid] = found ?: operation.entry
                    else merged.remove(gid)
                }
                replaceEntries(merged.values.toList())
                _loaded.value = true
                loadedAt = now
                // Persist confirmed cloud state only. A process restart must revalidate before any write.
                runCatching { NormalSaver.saveData(KEY, gson.toJson(CollectedPlaylistSnapshot(auth.userid, cloud))) }
            }
            Result.success(Unit)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            synchronized(lock) { if (active(g)) _error.value = error.message ?: "收藏歌单加载失败" }
            Result.failure(error)
        } finally {
            synchronized(lock) { if (active(g)) _loading.value = false }
        }
    }

    suspend fun toggle(sourceGid: String, name: String, cover: String, songCount: Int): Result<Boolean> =
        mutate(sourceGid, KugouPlaylist("", "", name, cover, songCount, type = 1, source = 1,
            listCreateGid = sourceGid), removeOnly = false)

    suspend fun uncollect(entry: KugouPlaylist): Result<Unit> =
        if (!entry.isCollectedPlaylist || entry.sourceGid.isBlank()) Result.failure(IllegalArgumentException("缺少来源 gid"))
        else mutate(entry.sourceGid, entry, removeOnly = true).map { Unit }

    private suspend fun mutate(gid: String, template: KugouPlaylist, removeOnly: Boolean): Result<Boolean> =
        withContext(Dispatchers.Main.immediate) {
            val auth = KugouApiService.authSnapshot()
            if (!auth.isLoggedIn) return@withContext Result.failure(IllegalStateException("未登录酷狗账号"))
            if (gid.isBlank()) return@withContext Result.failure(IllegalArgumentException("缺少来源 gid"))
            val g = generation(auth)
            synchronized(lock) {
                requireActive(g)
                if (gid in _busy.value) return@withContext Result.failure(IllegalStateException("操作进行中"))
                _busy.value = _busy.value + gid
            }
            var previous: KugouPlaylist? = null
            var optimistic = false
            var adding = false
            try {
                // Always confirm the exact current account state before deciding whether to add or remove.
                load(auth, g).getOrThrow()
                synchronized(lock) {
                    requireActive(g)
                    if (pending[gid]?.unknown == true) throw PlaylistMutationUnknownException("上次操作仍在对账，请稍后刷新")
                    if (pending[gid] != null) throw IllegalStateException("收藏状态正在同步，请稍后重试")
                    previous = _entries.value.firstOrNull { it.sourceGid == gid }
                    if (removeOnly && previous == null) return@withContext Result.success(false)
                    adding = previous == null
                    // Do not turn a just-added, unresolved placeholder into a successful no-op delete.
                    if (!adding && previous!!.listid.isBlank()) throw IllegalStateException("收藏正在同步，请稍后取消")
                    pending[gid] = Pending(previous ?: template, adding, System.currentTimeMillis() + PROPAGATION_MS)
                    replaceEntries(_entries.value.filterNot { it.sourceGid == gid } + if (adding) listOf(template) else emptyList())
                    optimistic = true
                }
                val result = if (adding) KugouRepository.collectPlaylist(gid, template.name, auth).map { listid ->
                    synchronized(lock) {
                        requireActive(g)
                        val entry = template.copy(listid = listid)
                        pending[gid] = Pending(entry, true, System.currentTimeMillis() + PROPAGATION_MS)
                        replaceEntries(_entries.value.filterNot { it.sourceGid == gid } + entry)
                    }
                } else KugouRepository.uncollectPlaylist(previous!!, auth)
                result.getOrThrow()
                synchronized(lock) { requireActive(g); _error.value = null }
                scheduleReconcile(auth, g)
                Result.success(adding)
            } catch (cancel: CancellationException) {
                synchronized(lock) {
                    if (active(g) && optimistic) {
                        pending[gid]?.let { pending[gid] = it.copy(unknown = true) }
                        scheduleReconcile(auth, g)
                    }
                }
                throw cancel
            } catch (error: Exception) {
                synchronized(lock) {
                    if (active(g)) {
                        if (optimistic && error is PlaylistMutationUnknownException) {
                            pending[gid]?.let { pending[gid] = it.copy(unknown = true) }
                        } else if (optimistic) {
                            pending.remove(gid)
                            replaceEntries(_entries.value.filterNot { it.sourceGid == gid } + listOfNotNull(previous))
                        }
                        _error.value = error.message ?: "收藏操作失败"
                        scheduleReconcile(auth, g)
                    }
                }
                Result.failure(error)
            } finally {
                synchronized(lock) { if (active(g)) _busy.value = _busy.value - gid }
            }
        }

    /** Raw account rows remain authoritative for account listid, optimistic rows retain source gid separately. */
    fun mergeEntries(raw: List<KugouPlaylist>): List<KugouPlaylist> {
        val auth = KugouApiService.authSnapshot()
        return synchronized(lock) {
            if (owner?.matches(auth.userid, auth.version, epoch) != true || !auth.isLoggedIn) return raw.filter { it.source != 2 }
            mergeCollectedPlaylistEntries(raw, _entries.value, pending.filterValues { !it.add }.keys)
        }
    }

    private fun replaceEntries(next: List<KugouPlaylist>) {
        if (_entries.value == next) return
        _entries.value = next
        KugouSyncCoordinator.notifyPlaylistsChanged()
    }

    private fun scheduleReconcile(auth: AuthSnapshot, g: PlaylistGeneration) {
        if (!active(g)) return
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            for (wait in longArrayOf(3_000, 8_000, 20_000, 90_000)) {
                delay(wait)
                requireActive(g)
                load(auth, g)
            }
        }
    }
}
