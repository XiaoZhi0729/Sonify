package yos.music.player.data.objects

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.data.NormalSaver
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.native.KugouApiService

/**
 * 「资料库›专辑」里的一条收藏专辑。
 *
 * [albumId] 只放**纯数字** album id（/album/detail 只吃这个）；列表条目给不出数字 id 时
 * 留空，等点击时用专辑名反查（见 [CollectedAlbumsObject.resolveAlbumId]）。
 * 收藏时我们自己知道 albumId，所以本 App 收藏的条目永远能解析。
 *
 * 每个字段都必须带 @SerializedName：release 构建会重命名属性，Gson 反射写出的
 * 键名会漂移，跨变体读回时字段全成 null（真机踩过的老坑）。
 */
data class KugouCollectedAlbum(
    @SerializedName("albumId") val albumId: String,
    @SerializedName("listid") val listid: String,
    @SerializedName("name") val name: String,
    @SerializedName("coverUrl") val coverUrl: String,
    @SerializedName("songCount") val songCount: Int,
    @SerializedName("artistId") val artistId: String = "",
    /**
     * 乐观条目的存活截止时间（毫秒时间戳）。> now 表示「刚收藏、云端还没传播出来」，
     * 拉列表合并时必须保留，否则用户刚点完 + 就发现列表里没有这张专辑。
     */
    @SerializedName("pendingUntil") val pendingUntil: Long = 0L
)

private data class CollectedAlbumsSnapshot(
    @SerializedName("userid") val userid: String = "",
    @SerializedName("loadedAt") val loadedAt: Long = 0L,
    @SerializedName("albums") val albums: List<KugouCollectedAlbum> = emptyList()
)

/**
 * 收藏专辑（「资料库›专辑」页 + 在线专辑详情页 +/✓ 按钮）的唯一数据源。
 *
 * 数据在酷狗账号云端（/playlist/add type=1&source=2 建的「专辑收藏」条目），
 * 本对象负责：拉取（/user/playlist 过滤 source=2）、乐观增删、MMKV 快照缓存。
 *
 * 三个必须小心的点：
 * 1. 云端传播延迟 5~8 秒：收藏成功后列表里暂时还查不到，故本地条目带 [KugouCollectedAlbum.pendingUntil]，
 *    合并时保留；同时按 3s/8s/20s 三轮静默对账，把真实 listid 补回来（取消收藏要用它）。
 * 2. 缓存按 userid 隔离：切号/登出时旧账号的收藏必须立刻不可见。
 * 3. albumId 可能缺失：条目里 `list_create_listid`/`specialid` 未必是数字 id，
 *    此时用「名称」匹配兜底（对齐 md3Music 的做法），点进详情前再按名搜索解析。
 */
object CollectedAlbumsObject {
    private const val KEY = "kugou_collected_albums_v1"
    private val TTL = 5 * 60 * 1000L
    private const val PENDING_MS = 120_000L
    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _albums = mutableStateOf<List<KugouCollectedAlbum>>(emptyList())
    val albums: State<List<KugouCollectedAlbum>> = _albums

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading

    /** 是否已经成功拉过一次（详情页按钮用它做门禁，避免「先 + 后 ✓」闪变）。 */
    private val _loaded = mutableStateOf(false)
    val loaded: State<Boolean> = _loaded

    private val _error = mutableStateOf<String?>(null)
    val error: State<String?> = _error

    private var loadedAt = 0L
    private var loadedUserid: String? = null
    private var loadJob: Job? = null
    private var reconcileJob: Job? = null
    private var preloadDone = false

    /**
     * 刚取消收藏的专辑（albumId/name → 时间戳）。取消同样有 5~8 秒传播延迟，
     * 此窗口内重新收藏时不能把服务端残留条目当成「已收藏」而跳过创建。
     */
    private val recentlyUncollected = mutableMapOf<String, Long>()

    /** 是否已收藏：albumId 精确命中优先，名称兜底（条目可能给不出数字 albumId）。 */
    fun contains(albumId: String?, name: String?): Boolean {
        val list = _albums.value
        return list.any { entry ->
            (!albumId.isNullOrBlank() && entry.albumId == albumId) ||
                (!name.isNullOrBlank() && entry.name == name)
        }
    }

    /** 进页/进详情时调用：TTL 内且同账号不重复请求。 */
    fun ensureLoaded(force: Boolean = false) {
        preload()
        if (!KugouApiService.isLoggedIn()) {
            // 未登录：立刻清掉旧账号残留，避免详情页显示别人的「✓」
            clearState()
            return
        }
        val uid = KugouApiService.userid ?: return
        if (loadedUserid != null && loadedUserid != uid) {
            clearState()
        }
        if (!force && loadedUserid == uid && System.currentTimeMillis() - loadedAt < TTL) return
        synchronized(this) {
            if (loadJob?.isActive == true) return
            loadJob = scope.launch {
                loadInternal(silent = !force)
            }
        }
    }

    /** 详情页点「+」/「✓」：返回新状态（true=已收藏）。失败已回滚，调用方只需提示。 */
    suspend fun toggle(album: KugouNewAlbum, artistId: String): Result<Boolean> {
        if (!KugouApiService.isLoggedIn()) {
            return Result.failure(IllegalStateException("未登录酷狗账号"))
        }
        val collected = contains(album.albumId, album.name)
        return if (collected) {
            uncollect(album).map { false }
        } else {
            collect(album, artistId).map { true }
        }
    }

    private suspend fun collect(album: KugouNewAlbum, artistId: String): Result<Unit> {
        val uid = KugouApiService.userid.orEmpty()
        val entry = KugouCollectedAlbum(
            albumId = album.albumId.takeIf { it.isDigitsOnly() }.orEmpty(),
            listid = "",
            name = album.name,
            coverUrl = album.coverUrl,
            songCount = album.songCount,
            artistId = artistId,
            pendingUntil = System.currentTimeMillis() + PENDING_MS
        )
        // 乐观：立刻显示为已收藏（详情页图标翻转、专辑页出现该条目）
        applyLocal(entry, add = true, uid = uid)

        // 防重复：本地态可能因冷启动竞态/云端传播延迟而漏掉「其实已收藏」的条目，
        // 直接再建会在账号里留下两条同名收藏。先向服务端确认一次，已存在就只补齐本地状态。
        // 例外：刚刚取消收藏（删除还在传播）时不做该检查，否则会把残留条目当已收藏而跳过创建。
        val justUncollected = recentlyUncollected.any { (key, at) ->
            System.currentTimeMillis() - at < 15_000L &&
                (key == album.albumId || key == album.name)
        }
        val existing = if (justUncollected) {
            null
        } else {
            KugouRepository.resolveCollectedAlbumListId(album.albumId, album.name).getOrNull()
        }
        if (!existing.isNullOrBlank()) {
            applyLocal(entry.copy(listid = existing), add = true, uid = uid)
            KugouSyncCoordinator.notifyAlbumsChanged()
            return Result.success(Unit)
        }

        val result = KugouRepository.collectAlbum(
            name = album.name,
            artistId = artistId,
            albumId = album.albumId
        )
        return result.fold(
            onSuccess = { listid ->
                if (listid.isNotEmpty()) {
                    applyLocal(entry.copy(listid = listid), add = true, uid = uid)
                }
                KugouSyncCoordinator.notifyAlbumsChanged()
                scheduleReconcile()
                Result.success(Unit)
            },
            onFailure = { e ->
                // 回滚：云端没建成，本地也不能留着
                applyLocal(entry, add = false, uid = uid)
                Result.failure(e)
            }
        )
    }

    private suspend fun uncollect(album: KugouNewAlbum): Result<Unit> {
        val uid = KugouApiService.userid.orEmpty()
        val current = _albums.value.firstOrNull {
            (album.albumId.isNotBlank() && it.albumId == album.albumId) || it.name == album.name
        }
        // listid 解析：本地条目 → 重新拉列表匹配（刚收藏时可能还没有）
        var listid = current?.listid.orEmpty()
        if (listid.isBlank()) {
            listid = KugouRepository
                .resolveCollectedAlbumListId(album.albumId, album.name)
                .getOrNull()
                .orEmpty()
        }
        if (listid.isBlank()) {
            return Result.failure(IllegalStateException("not_found"))
        }
        if (current != null) applyLocal(current, add = false, uid = uid)
        // 记录取消时间：15 秒内重新收藏时跳过「服务端已有」的重复检查
        recentlyUncollected[album.albumId.ifBlank { album.name }] = System.currentTimeMillis()
        if (album.name.isNotBlank()) {
            recentlyUncollected[album.name] = System.currentTimeMillis()
        }

        return KugouRepository.uncollectAlbum(listid).fold(
            onSuccess = {
                KugouSyncCoordinator.notifyAlbumsChanged()
                scheduleReconcile()
                Result.success(Unit)
            },
            onFailure = { e ->
                // 回滚：云端没删掉，本地要还原
                current?.let { applyLocal(it, add = true, uid = uid) }
                Result.failure(e)
            }
        )
    }

    /** 点进详情前把非数字/缺失的 albumId 解析成数字 id（按名搜索，结果有内存缓存）。 */
    suspend fun resolveAlbumId(album: KugouCollectedAlbum): String? {
        album.albumId.takeIf { it.isNotBlank() && it.isDigitsOnly() }?.let { return it }
        return KugouRepository.searchAlbumIdByName(album.name).getOrNull()
    }

    // ---------------- 内部 ----------------

    private fun clearState() {
        _albums.value = emptyList()
        loadedAt = 0L
        loadedUserid = null
        _loaded.value = false
    }

    private fun preload() {
        if (preloadDone) return
        // 登录态还没恢复时不要标记「已预载」：否则这一轮直接跳过，之后再也不读快照，
        // 冷启动早期会表现为「已收藏的专辑显示成未收藏」（真机踩过）。
        val uid = KugouApiService.userid ?: return
        preloadDone = true
        runCatching {
            val json = NormalSaver.readData(KEY, "")
            if (json.isNullOrBlank()) return
            val snapshot = gson.fromJson(json, CollectedAlbumsSnapshot::class.java) ?: return
            if (snapshot.userid != uid) return
            // first() 触发元素 checkcast：混淆丢签名导致 Gson 解出 LinkedTreeMap 时，
            // CCE 被 runCatching 就地接住，脏数据不进 UI
            if (snapshot.albums.firstOrNull()?.name?.isNotBlank() == true) {
                _albums.value = snapshot.albums
                loadedAt = snapshot.loadedAt
                loadedUserid = snapshot.userid
                _loaded.value = true
            }
        }
    }

    private suspend fun loadInternal(silent: Boolean) {
        if (!silent) _loading.value = true
        _error.value = null
        try {
            val uid = KugouApiService.userid ?: return
            val entries = KugouRepository.getCollectedAlbumEntries()
            entries.onSuccess { list ->
                val now = System.currentTimeMillis()
                val prev = _albums.value
                val prevByListid = prev.filter { it.listid.isNotBlank() }.associateBy { it.listid }
                val prevByName = prev.associateBy { it.name }

                val merged = list.map { e ->
                    val previous = prevByListid[e.listid] ?: prevByName[e.name]
                    KugouCollectedAlbum(
                        albumId = e.listCreateListid.takeIf { it.isDigitsOnly() }
                            ?: previous?.albumId?.takeIf { it.isNotBlank() }
                            ?: e.specialId.takeIf { it.isDigitsOnly() }
                            ?: e.gid.takeIf { it.isDigitsOnly() }
                            ?: "",
                        listid = e.listid,
                        name = e.name,
                        // 封面兜底：服务端为空时用上次（可能来自收藏时记录）的封面
                        coverUrl = e.coverUrl.ifEmpty { previous?.coverUrl.orEmpty() },
                        songCount = e.songCount.takeIf { it > 0 } ?: previous?.songCount ?: 0,
                        artistId = previous?.artistId.orEmpty()
                    )
                }.toMutableList()

                // 刚收藏、云端尚未传播出来的条目：保留到 pendingUntil 到期
                prev.filter { it.pendingUntil > now && merged.none { m -> m.name == it.name } }
                    .forEach { merged.add(0, it) }

                // 同一专辑可能被本 App 与官方 App 各建一条：按 albumId（无则名称）去重
                val seen = HashSet<String>()
                val deduped = merged.filter { seen.add(it.albumId.ifBlank { "name:${it.name}" }) }

                _albums.value = deduped
                loadedAt = System.currentTimeMillis()
                loadedUserid = uid
                _loaded.value = true
                persist(uid, deduped)
            }.onFailure { e ->
                _error.value = e.message ?: e.javaClass.simpleName
            }
        } finally {
            if (!silent) _loading.value = false
            loadJob = null
        }
    }

    private fun applyLocal(entry: KugouCollectedAlbum, add: Boolean, uid: String) {
        val updated = _albums.value.toMutableList()
        updated.removeAll { it.name == entry.name || (entry.albumId.isNotBlank() && it.albumId == entry.albumId) }
        if (add) updated.add(0, entry)
        _albums.value = updated
        loadedAt = System.currentTimeMillis()
        loadedUserid = uid
        _loaded.value = true
        persist(uid, updated)
    }

    private fun persist(userid: String, albums: List<KugouCollectedAlbum>) {
        runCatching {
            NormalSaver.saveData(KEY, gson.toJson(CollectedAlbumsSnapshot(userid, loadedAt, albums)))
        }
    }

    /**
     * 收藏成功后静默对账：云端 5~8 秒才传播出来，分三轮把真实 listid / 封面补回来。
     * 期间失败不打扰用户（列表已有乐观条目）。
     */
    private fun scheduleReconcile() {
        reconcileJob?.cancel()
        reconcileJob = scope.launch {
            for (delayMs in longArrayOf(3_000L, 8_000L, 20_000L)) {
                delay(delayMs)
                if (!KugouApiService.isLoggedIn()) return@launch
                loadInternal(silent = true)
            }
        }
    }

    private fun String.isDigitsOnly(): Boolean = isNotEmpty() && all { it.isDigit() }
}
