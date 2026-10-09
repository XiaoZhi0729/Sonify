package yos.music.player.data.objects

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.native.KugouApiService

/**
 * 酷狗在线状态刷新的中央调度。
 *
 * 四组修订号分别驱动对应页面重新拉取（页面在 LaunchedEffect 的 key 里观察其值）：
 * 收藏/歌单/关注/发现。写操作成功后调用 notifyXxx()；refreshAll() 与
 * startPolling() 是「回前台 / 登录后 / 定时」的统一入口。
 *
 * 收藏同步做差分检测：与当前内存集合一致时不 bump 修订号，避免定时轮询
 * 反复触发页面重载。
 */
object KugouSyncCoordinator {

    /** 云端收藏（「我喜欢」）变化。影响「喜爱」Tab 与歌单列表。 */
    val favoritesRevision = mutableStateOf(0)

    /** 歌单增删/歌单内歌曲变化。 */
    val playlistsRevision = mutableStateOf(0)

    /** 关注歌手变化。 */
    val artistsRevision = mutableStateOf(0)

    /** 首页发现流（排行榜/新歌/新专辑/精选歌单）需重拉。 */
    val discoveryRevision = mutableStateOf(0)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pollJob: Job? = null

    fun notifyFavoritesChanged() {
        favoritesRevision.value++
        playlistsRevision.value++
    }

    fun notifyPlaylistsChanged() {
        playlistsRevision.value++
    }

    fun notifyArtistsChanged() {
        artistsRevision.value++
    }

    fun notifyDiscoveryChanged() {
        discoveryRevision.value++
    }

    /**
     * 重新同步云端收藏 hash 集合；返回是否发生变化。
     * 未登录直接返回 false；同步失败静默忽略（保留内存中的现值）。
     */
    suspend fun resyncFavoritesIfChanged(): Boolean {
        if (!KugouApiService.isLoggedIn()) return false
        val before = KugouRepository.favoriteHashes.value
        runCatching { KugouRepository.syncKugouFavorites() }
        return KugouRepository.favoriteHashes.value != before
    }

    /** 回到前台 / 登录后 / 手动刷新：强制重拉收藏与关注歌手。 */
    suspend fun refreshAll() {
        if (resyncFavoritesIfChanged()) notifyFavoritesChanged()
        FollowedArtistsObject.ensureLoaded(force = true)
        notifyArtistsChanged()
    }

    fun refreshAllAsync() {
        scope.launch { refreshAll() }
    }

    /** 定时轮询：只做轻量收藏差分同步（跨设备一致性最敏感的状态）。 */
    fun startPolling(intervalMs: Long = 60_000L) {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                delay(intervalMs)
                if (resyncFavoritesIfChanged()) notifyFavoritesChanged()
            }
        }
    }

    fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }
}
