package yos.music.player.data.objects

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.native.KugouApiService

/** Account-scoped cache shared by playlist pickers. Pending creations are merged by callers. */
object MyPlaylistsCache {
    private const val TTL_MS = 5 * 60 * 1000L
    private data class Snapshot(
        val entries: List<KugouPlaylist>,
        val authVersion: Long,
        val revision: Int,
        val at: Long
    )

    private val mutex = Mutex()
    @Volatile private var cached: Snapshot? = null
    @Volatile private var generation = 0L

    fun peek(): List<KugouPlaylist>? = cached
        ?.takeIf { KugouApiService.isLoggedIn() && it.authVersion == KugouApiService.authVersion() }
        ?.entries

    suspend fun get(force: Boolean = false): Result<List<KugouPlaylist>> {
        if (!force) validSnapshot()?.let { return Result.success(it.entries) }
        return mutex.withLock {
            if (!force) validSnapshot()?.let { return@withLock Result.success(it.entries) }
            val auth = KugouApiService.authSnapshot()
            if (!auth.isLoggedIn) return@withLock Result.failure(IllegalStateException("未登录酷狗账号"))
            val expectedGeneration = generation
            val revision = KugouSyncCoordinator.playlistsRevision.value
            val result = KugouRepository.getAllUserPlaylistEntries(auth)
                .map { entries -> entries.filterNot { it.source == 2 } }
            if (auth.version != KugouApiService.authVersion() || generation != expectedGeneration) {
                return@withLock Result.failure(IllegalStateException("账号状态已变化，请重试"))
            }
            result.onSuccess {
                cached = Snapshot(it, auth.version, revision, System.currentTimeMillis())
            }
        }
    }

    private fun validSnapshot(): Snapshot? = cached?.takeIf {
        KugouApiService.isLoggedIn() &&
            it.authVersion == KugouApiService.authVersion() &&
            it.revision == KugouSyncCoordinator.playlistsRevision.value &&
            System.currentTimeMillis() - it.at < TTL_MS
    }

    fun reset() {
        generation++
        cached = null
    }
}
