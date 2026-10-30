package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRank
import yos.music.player.data.repositories.KugouRecommendPlaylist
import yos.music.player.data.repositories.KugouRepository

/**
 * 新发现一级 Tab 的页面级状态 holder（SearchObject 同款模式）。
 *
 * 切换一级 Tab 会销毁页面 composition，本地 remember 状态丢失；
 * 排行榜/新歌/新专辑/精选歌单列表与状态放 object，切 Tab 返回后原样恢复，不重新请求接口。
 *
 * 加载职责也收在这里（不再散落在页面里）：
 *  - **单飞**：每个板块一个 [Job]，重复调用复用同一请求，取代页面里
 *    `if (status == "loading") return` 的竞态写法；
 *  - **不随页面取消**：请求跑在进程级 IO scope，切 Tab 不再把请求掐死
 *    （历史表现：切走再回来状态卡 loading，靠页面里的"自愈"补丁兜）；
 *  - **启动即预加载**：[ensureFresh] 由 Application 在冷启动时调用，页面组合时数据
 *    往往已在路上甚至已到，骨架只闪一下。
 *
 * 刻意**不做**磁盘快照：每次启动都从空态开始，让骨架屏如实反映"这次真的在联网"。
 * 上一次的内容如果直接顶上来，用户会误以为数据是新的，还要手动刷新才知道有没有更新。
 */
@Stable
object DiscoveryObject {
    @Stable
    val rankList = mutableStateOf<List<KugouRank>>(emptyList())

    @Stable
    val newSongs = mutableStateOf<List<KugouNewSong>>(emptyList())

    @Stable
    val newAlbums = mutableStateOf<List<KugouNewAlbum>>(emptyList())

    @Stable
    val recommendPlaylists = mutableStateOf<List<KugouRecommendPlaylist>>(emptyList())

    @Stable
    val everydayRecommendSongs = mutableStateOf<List<KugouNewSong>>(emptyList())

    /** Successful detail totals keyed by global collection id; values may legitimately be zero. */
    @Stable
    val recommendPlaylistSongCounts = mutableStateMapOf<String, Int>()

    /** IDs whose detail count request is currently in flight. */
    @Stable
    val recommendPlaylistSongCountRequests = mutableStateMapOf<String, Boolean>()

    // "idle" | "loading" | "empty" | "ok" | "error:<msg>"
    @Stable
    val status = mutableStateOf("idle")

    @Stable
    val newSongsStatus = mutableStateOf("idle")

    @Stable
    val newAlbumsStatus = mutableStateOf("idle")

    @Stable
    val recommendPlaylistsStatus = mutableStateOf("idle")

    @Stable
    val everydayRecommendSongsStatus = mutableStateOf("idle")

    // ---------- 加载器 ----------

    private enum class Section { Rank, NewSongs, NewAlbums, Playlists, Everyday }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = HashMap<Section, Job>()

    /** 最近一次全部板块拉取完成的时间，用于 [ensureFresh] 的进程内防抖。 */
    @Volatile
    private var lastFetchAtMillis = 0L

    /** 冷启动 / 回到主页时的刷新入口：距上次拉取不足 [minIntervalMs] 时整段跳过。 */
    fun ensureFresh(minIntervalMs: Long = 60_000L) {
        if (lastFetchAtMillis != 0L &&
            System.currentTimeMillis() - lastFetchAtMillis < minIntervalMs
        ) {
            return
        }
        refreshAll()
    }

    /** 五个板块并行拉取；已在跑的板块复用现有请求。 */
    fun refreshAll() {
        loadRank()
        loadNewSongs()
        loadNewAlbums()
        loadRecommendPlaylists()
        loadEverydayRecommendSongs()
    }

    private fun launch(section: Section, block: suspend () -> Unit) {
        synchronized(jobs) {
            // 单飞：同一个板块的并发调用合并成一次请求。
            if (jobs[section]?.isActive == true) return
            jobs[section] = scope.launch {
                try {
                    block()
                } finally {
                    synchronized(jobs) { jobs.remove(section) }
                    lastFetchAtMillis = System.currentTimeMillis()
                }
            }
        }
    }

    /**
     * 状态必须**在调用线程同步**置为 loading（不能挪进协程）：
     * 下拉刷新在设置 refreshing=true 后立刻用 [allSettled] 判断是否收起，
     * 若 loading 晚一拍才写，指示器会被瞬间判定为"已完成"而不显示。
     */
    fun loadRank() {
        status.value = "loading"
        launch(Section.Rank) {
            KugouRepository.getRankList()
                .onSuccess { list ->
                    rankList.value = list
                    status.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    status.value = "error:${e.message}"
                }
        }
    }

    fun loadNewSongs() {
        newSongsStatus.value = "loading"
        launch(Section.NewSongs) {
            KugouRepository.getTopSongs()
                .onSuccess { list ->
                    newSongs.value = list
                    newSongsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    newSongsStatus.value = "error:${e.message}"
                }
        }
    }

    fun loadNewAlbums() {
        newAlbumsStatus.value = "loading"
        launch(Section.NewAlbums) {
            KugouRepository.getTopAlbums()
                .onSuccess { list ->
                    newAlbums.value = list
                    newAlbumsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    newAlbumsStatus.value = "error:${e.message}"
                }
        }
    }

    fun loadRecommendPlaylists() {
        recommendPlaylistsStatus.value = "loading"
        launch(Section.Playlists) {
            KugouRepository.getRecommendPlaylists()
                .onSuccess { list ->
                    recommendPlaylists.value = list
                    recommendPlaylistsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    recommendPlaylistsStatus.value = "error:${e.message}"
                }
        }
    }

    fun loadEverydayRecommendSongs() {
        everydayRecommendSongsStatus.value = "loading"
        launch(Section.Everyday) {
            KugouRepository.getEverydayRecommendSongs()
                .onSuccess { list ->
                    everydayRecommendSongs.value = list
                    everydayRecommendSongsStatus.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    everydayRecommendSongsStatus.value = "error:${e.message}"
                }
        }
    }

    /** 所有板块都不在加载中（下拉刷新收起指示器的判据）。 */
    fun allSettled(): Boolean =
        status.value != "loading" &&
            newSongsStatus.value != "loading" &&
            newAlbumsStatus.value != "loading" &&
            recommendPlaylistsStatus.value != "loading" &&
            everydayRecommendSongsStatus.value != "loading"
}
