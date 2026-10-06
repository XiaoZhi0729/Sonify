package yos.music.player.data.objects

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import yos.music.player.data.NormalSaver
import yos.music.player.data.repositories.KugouArtistDetailData
import yos.music.player.data.repositories.KugouRepository
import java.util.Locale

internal data class ArtistCachedDetail(
    val detail: KugouArtistDetailData,
    val loadedAt: Long,
    val complete: Boolean
)

internal data class ArtistCachedPalette(val key: String, val rgb: Int)

internal data class ArtistCacheSnapshot(
    val details: List<ArtistCachedDetail> = emptyList(),
    val aliases: Map<String, String> = emptyMap(),
    val palettes: List<ArtistCachedPalette> = emptyList(),
    val albumSongCounts: Map<String, Int> = emptyMap()
)

internal class ArtistPresentationStore(
    private val read: () -> String,
    private val write: (String) -> Unit,
    private val fetchDetail: suspend (String) -> KugouArtistDetailData,
    private val scope: CoroutineScope,
    private val now: () -> Long = System::currentTimeMillis
) {
    private data class Follow(val value: Boolean, val loadedAt: Long)
    private val gson = Gson()
    private val details = linkedMapOf<String, ArtistCachedDetail>()
    private val aliases = linkedMapOf<String, String>()
    private val palettes = linkedMapOf<String, State<Int?>>()
    private val albumSongCounts = linkedMapOf<String, Int>()
    private val follows = mutableMapOf<Pair<String, String>, Follow>()
    private val revisions = mutableMapOf<Pair<String, String>, Int>()
    private val detailRequests = mutableMapOf<Pair<String, String>, CompletableDeferred<KugouArtistDetailData>>()
    private val paletteRequests = mutableMapOf<String, CompletableDeferred<Int?>>()
    private var loaded = false

    @Synchronized
    fun preload() {
        if (loaded) return
        loaded = true
        val snapshot = runCatching { gson.fromJson(read(), ArtistCacheSnapshot::class.java) }.getOrNull() ?: return
        // 元素访问触发 checkcast：若混淆丢签名致 Gson 解出 LinkedTreeMap，
        // CCE 就地吞掉按缓存缺失处理，不冒泡到调用方（preload 在主线程）
        runCatching {
            snapshot.details.orEmpty().takeLast(64).forEach {
                if (it.detail.artistId.isNotBlank()) details[it.detail.artistId] = it.copy(detail = it.detail.copy(isFollowed = false))
            }
            snapshot.aliases.orEmpty().entries.toList().takeLast(128).forEach { entry ->
                val name = entry.key
                val id = entry.value
                if (details.containsKey(id)) aliases[name] = id
            }
            snapshot.palettes.orEmpty().takeLast(128).forEach { palettes[it.key] = mutableStateOf(it.rgb) }
            snapshot.albumSongCounts.orEmpty().entries.toList().takeLast(128).forEach {
                if (it.value > 0) albumSongCounts[it.key] = it.value
            }
        }
    }

    @Synchronized
    fun peek(id: String?, name: String): KugouArtistDetailData? {
        preload()
        val key = id?.takeIf { it.isNotBlank() } ?: aliases[alias(name)] ?: return null
        val entry = details.remove(key) ?: return null
        details[key] = entry
        return entry.detail
    }

    @Synchronized
    fun rememberBrief(detail: KugouArtistDetailData, name: String) {
        preload()
        if (detail.artistId.isBlank()) return
        aliases[alias(name)] = detail.artistId
        aliases[alias(detail.name)] = detail.artistId
        if (details[detail.artistId]?.complete != true) {
            details[detail.artistId] = ArtistCachedDetail(detail.copy(isFollowed = false), now(), false)
        }
        persist()
    }

    @Synchronized
    fun followed(id: String, accountId: String): Boolean? = follows[id to accountId]
        ?.takeIf { now() - it.loadedAt in 0 until FOLLOW_TTL }?.value

    @Synchronized
    fun setFollowed(id: String, accountId: String, value: Boolean) {
        val key = id to accountId
        follows[key] = Follow(value, now())
        revisions[key] = (revisions[key] ?: 0) + 1
        while (follows.size > 128) {
            val oldest = follows.minByOrNull { it.value.loadedAt }!!.key
            follows.remove(oldest)
            revisions.remove(oldest)
        }
    }

    suspend fun loadDetail(id: String, name: String, accountId: String, force: Boolean = false): KugouArtistDetailData {
        val request = synchronized(this) {
            preload()
            val cached = details[id]
            val follow = followed(id, accountId)
            if (!force && cached?.complete == true && now() - cached.loadedAt in 0 until DETAIL_TTL && follow != null) {
                return cached.detail.copy(isFollowed = follow)
            }
            val key = id to accountId
            detailRequests[key] ?: CompletableDeferred<KugouArtistDetailData>().also { deferred ->
                detailRequests[key] = deferred
                val revision = revisions[key] ?: 0
                scope.launch {
                    try {
                        val result = fetchDetail(id)
                        val published = synchronized(this@ArtistPresentationStore) {
                            details.remove(id)
                            details[id] = ArtistCachedDetail(result.copy(isFollowed = false), now(), true)
                            aliases[alias(name)] = id
                            aliases[alias(result.name)] = id
                            if ((revisions[key] ?: 0) == revision) setFollowed(id, accountId, result.isFollowed)
                            persist()
                            result.copy(isFollowed = follows[key]?.value ?: result.isFollowed)
                        }
                        deferred.complete(published)
                    } catch (e: Exception) {
                        deferred.completeExceptionally(e)
                    } finally {
                        synchronized(this@ArtistPresentationStore) { detailRequests.remove(key) }
                    }
                }
            }
        }
        return request.await()
    }

    @Synchronized
    fun palette(key: String): State<Int?> {
        preload()
        val state = palettes.remove(key) ?: mutableStateOf<Int?>(null)
        palettes[key] = state
        trimPalettes()
        return state
    }

    suspend fun loadPalette(key: String, compute: suspend () -> Int?): Int? {
        val request = synchronized(this) {
            val state = palette(key)
            state.value?.let { return it }
            paletteRequests[key] ?: CompletableDeferred<Int?>().also { deferred ->
                paletteRequests[key] = deferred
                scope.launch {
                    try {
                        val rgb = compute()
                        if (rgb != null) synchronized(this@ArtistPresentationStore) {
                            // Preserve the State already observed by a page, even if LRU evicted its key.
                            (state as androidx.compose.runtime.MutableState<Int?>).value = rgb
                            palettes.remove(key)
                            palettes[key] = state
                            persist()
                        }
                        deferred.complete(rgb)
                    } catch (e: Exception) {
                        deferred.completeExceptionally(e)
                    } finally {
                        synchronized(this@ArtistPresentationStore) { paletteRequests.remove(key) }
                    }
                }
            }
        }
        return request.await()
    }

    /**
     * 在缓存自身的 CoroutineScope 上启动调色板计算（fire-and-forget）。
     * 关键：不绑定调用方（艺人页）的生命周期——艺人页离开组合也不会取消计算，
     * 结果照常写回 [palette] State，供子页面（专辑/歌曲详情）读到并上色。
     * 重复调用由 [loadPalette] 内部按 key 去重，不会重复加载。
     */
    fun ensurePalette(key: String, compute: suspend () -> Int?) {
        if (key.isBlank()) return
        scope.launch { runCatching { loadPalette(key, compute) } }
    }

    private fun persist() {
        while (details.size > 64) details.remove(details.keys.first())
        aliases.entries.removeAll { !details.containsKey(it.value) }
        while (aliases.size > 128) aliases.remove(aliases.keys.first())
        trimPalettes()
        val snapshot = ArtistCacheSnapshot(
            details.values.toList(), aliases.toMap(),
            palettes.mapNotNull { (key, state) -> state.value?.let { ArtistCachedPalette(key, it) } },
            albumSongCounts.toMap()
        )
        write(gson.toJson(snapshot))
    }

    private fun trimPalettes() {
        while (palettes.size > 128) palettes.remove(palettes.keys.first())
    }

    /** 推荐专辑卡片歌曲数：酷狗作者专辑/详情接口常缺 songcount，真实数量只能
     * 拉歌曲列表数出来（昂贵），故跨页面+跨进程缓存，避免每次进艺人页重载。 */
    @Synchronized
    fun albumSongCount(albumId: String): Int? {
        preload()
        return albumSongCounts.remove(albumId)?.also { albumSongCounts[albumId] = it }
    }

    @Synchronized
    fun rememberAlbumSongCount(albumId: String, count: Int) {
        preload()
        if (albumId.isBlank() || count <= 0) return
        albumSongCounts.remove(albumId)
        albumSongCounts[albumId] = count
        while (albumSongCounts.size > 128) albumSongCounts.remove(albumSongCounts.keys.first())
        persist()
    }

    private fun alias(name: String) = name.trim().lowercase(Locale.ROOT)

    companion object {
        internal const val DETAIL_TTL = 24 * 60 * 60 * 1000L
        internal const val FOLLOW_TTL = 5 * 60 * 1000L
    }
}

/** Public artwork survives navigation and restart; follow status belongs only to its account. */
object ArtistPresentationCache {
    private const val KEY = "artist_presentation_cache_v1"
    private val store by lazy {
        ArtistPresentationStore(
            read = { NormalSaver.readData(KEY, "") },
            write = { NormalSaver.saveData(KEY, it) },
            fetchDetail = { KugouRepository.getArtistDetail(it).getOrThrow() },
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        )
    }

    fun preload() = store.preload()
    fun peek(id: String?, name: String) = store.peek(id, name)
    fun rememberBrief(detail: KugouArtistDetailData, alias: String) = store.rememberBrief(detail, alias)
    fun followed(id: String, accountId: String) = store.followed(id, accountId)
    fun setFollowed(id: String, accountId: String, followed: Boolean) = store.setFollowed(id, accountId, followed)
    suspend fun loadDetail(id: String, name: String, accountId: String, force: Boolean = false) =
        store.loadDetail(id, name, accountId, force)
    fun palette(key: String): State<Int?> = store.palette(key)
    suspend fun loadPalette(key: String, compute: suspend () -> Int?): Int? = store.loadPalette(key, compute)
    fun ensurePalette(key: String, compute: suspend () -> Int?) = store.ensurePalette(key, compute)
    fun albumSongCount(albumId: String): Int? = store.albumSongCount(albumId)
    fun rememberAlbumSongCount(albumId: String, count: Int) = store.rememberAlbumSongCount(albumId, count)
}
