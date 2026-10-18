package yos.music.player.data.objects

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository

/**
 * 「我的歌单」内存缓存：避免每次打开「添加至歌单」都重新请求 `/user/playlist` 造成卡顿。
 *
 * 失效信号：
 * - [KugouSyncCoordinator.playlistsRevision]：创建/删除歌单、加歌/删歌、收藏变化都会 bump；
 * - 5 分钟 TTL：兜底其它设备上的歌单变化；
 * - 登录/登出时 [reset]（由 KugouApiService 调用），防账号串数据。
 *
 * 「创建中」歌单不在这里缓存——调用方每次从 PendingPlaylistStore 现拼，对账绑定后不会缺项。
 */
object MyPlaylistsCache {
    private const val TTL_MS = 5 * 60 * 1000L

    private val mutex = Mutex()
    private var cached: List<KugouPlaylist>? = null
    private var cachedRevision: Int = -1
    private var cachedAt: Long = 0L

    /** 同步取现有缓存（不管是否过期）：供面板首帧直接渲染，后台再按需刷新。 */
    fun peek(): List<KugouPlaylist>? = cached

    /** 缓存有效（同修订号且未过期）时直接返回；否则重新拉取。并发调用会串行化。 */
    suspend fun get(force: Boolean = false): Result<List<KugouPlaylist>> {
        if (!force && isValid()) {
            cached?.let { return Result.success(it) }
        }
        return mutex.withLock {
            // 拿到锁后 double-check：可能已被前一个请求刷新
            if (!force && isValid()) {
                cached?.let { return@withLock Result.success(it) }
            }
            val revision = KugouSyncCoordinator.playlistsRevision.value
            KugouRepository.getMyPlaylists().onSuccess {
                cached = it
                cachedRevision = revision
                cachedAt = System.currentTimeMillis()
            }
        }
    }

    private fun isValid(): Boolean {
        if (cached == null) return false
        if (cachedRevision != KugouSyncCoordinator.playlistsRevision.value) return false
        return System.currentTimeMillis() - cachedAt < TTL_MS
    }

    fun reset() {
        cached = null
        cachedRevision = -1
        cachedAt = 0L
    }
}
