package yos.music.player.data.objects

import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRepository

/**
 * 缓存条目的存储分隔符：版本号与 URL 都不会包含 TAB，所以按**第一个** TAB 切分
 * 永远能拿到完整的版本号。
 *
 * 不要用 `|` 之类会出现在版本键内部的字符：版本键是 `listVer_songCount` 形态，
 * 曾经用 `|` 拼版本，读取时按第一个 `|` 切分会只拿到 `listVer`，与写入的
 * `listVer|count` 永不相等，导致缓存每次进页都判定失效、反复重算。
 */
private const val FIELD_SEPARATOR = '\t'

/** 序列化缓存条目（版本 + URL）。纯函数，供单测锁定分隔符契约。 */
internal fun encodeCoverEntry(version: String, url: String): String =
    "$version$FIELD_SEPARATOR$url"

/** 反序列化缓存条目；格式不合法（无分隔符 / 空版本 / 空 URL）返回 null 当作未命中。 */
internal fun decodeCoverEntry(raw: String): Pair<String, String>? {
    val sep = raw.indexOf(FIELD_SEPARATOR)
    if (sep <= 0) return null
    val version = raw.substring(0, sep)
    val url = raw.substring(sep + 1)
    if (version.isEmpty() || url.isEmpty()) return null
    return version to url
}

/**
 * 在线歌单封面缓存（`listid/gid → 歌单内最新添加歌曲的封面 URL`）。
 *
 * 口径：封面 = 「最新添加歌曲」的封面，即歌单里 `sort` 最小的那首（见
 * [yos.music.player.data.repositories.PlaylistTrackOrder.newestCover]）；用户自定义过封面
 * （`is_custom_pic=1`）的歌单不参与，直接沿用服务端 `pic`。
 *
 * 版本键 = `listVer` + `songCount`：加删歌改 `count`，手动重排序改 `list_ver`，
 * 两者合起来才覆盖「封面该变」的全部场景。App 内加删歌成功后还会显式 [invalidate]，
 * 不必等下一次 `/user/playlist` 刷新拿到新的 `list_ver`。
 *
 * MMKV id 用 v2 命名空间：旧 `kugou_playlist_cover_cache` 存的是按「最旧那首」口径
 * 算出的封面，当种子用会先闪一张错的封面，因此不读取（旧文件留在原地）。
 */
object PlaylistCoverCache {

    private const val MMKV_ID = "kugou_playlist_cover_cache_v2"

    /** 一次列表刷新可能要算多个歌单，限流避免开页瞬间打满并发网络请求。 */
    private const val MAX_CONCURRENT = 3

    private val mmkv: MMKV? by lazy { runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull() }
    private val gate = Semaphore(MAX_CONCURRENT)
    /** 在途去重：列表页与详情页可能同时为同一张封面发起计算，算完的活不该被调用方取消掉。 */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = HashMap<String, Deferred<String?>>()
    private val inFlightGuard = Mutex()

    /** 缓存版本键：两个维度任一变化都视为封面可能已变。 */
    fun version(pl: KugouPlaylist): String = "${pl.listVer}_${pl.songCount}"

    private fun key(id: String): String = "cover:$id"

    /**
     * 读已缓存的封面（同步、零网络）。[version] 为 null 表示不校验版本，仅用于
     * 「同一歌单在各处显示同一张封面」的展示兜底。
     *
     * 解析不了的条目（如早期用 `版本|URL` 写的旧格式）顺手删掉，免得留在盘上永远不命中。
     */
    fun read(id: String, version: String? = null): String? {
        if (id.isEmpty()) return null
        val raw = mmkv?.decodeString(key(id)) ?: return null
        val decoded = decodeCoverEntry(raw)
        if (decoded == null) {
            mmkv?.removeValueForKey(key(id))
            return null
        }
        val (storedVersion, url) = decoded
        if (version != null && storedVersion != version) return null
        return url
    }

    /** 按歌单身份读缓存（校验版本，并跳过不适用该规则 / 自定义封面的歌单）。 */
    fun read(pl: KugouPlaylist): String? {
        if (!pl.usesTrackCover || pl.isCustomPic) return null
        return read(pl.listid.ifEmpty { pl.gid }, version(pl))
    }

    fun write(id: String, version: String, url: String) {
        if (id.isEmpty() || url.isEmpty()) return
        mmkv?.encode(key(id), encodeCoverEntry(version, url))
    }

    /** 歌单内容变了（加删歌）后丢弃缓存，下次展示重新计算。 */
    fun invalidate(id: String) {
        if (id.isEmpty()) return
        mmkv?.removeValueForKey(key(id))
    }

    /**
     * 确保拿到该歌单的封面：命中版本直接返回缓存，否则拉一次歌单尾部页计算并落盘。
     *
     * 返回 null 表示「算不出来」（不适用该规则 / 空歌单 / 曲目全无封面 / 网络失败），
     * 调用方应保留服务端 `pic` 而不是清空封面。
     */
    suspend fun ensure(pl: KugouPlaylist): String? {
        // 收藏的他人歌单 / 收藏的专辑沿用服务端封面；用户自定义过封面时服务端 pic 就是
        // 用户选的图，同样不能覆盖
        if (!pl.usesTrackCover || pl.isCustomPic) return null
        val id = pl.listid.ifEmpty { pl.gid }
        if (id.isEmpty() || pl.songCount <= 0) return null
        read(pl)?.let { return it }
        val job = inFlightGuard.withLock {
            inFlight[id] ?: scope.async {
                try {
                    gate.withPermit {
                        KugouRepository.resolvePlaylistCoverFromTracks(id, pl.songCount)
                    }?.also { write(id, version(pl), it) }
                } finally {
                    // 结果已落盘，后续调用走缓存；条目自身从在途表摘除
                    inFlightGuard.withLock { inFlight.remove(id) }
                }
            }.also { inFlight[id] = it }
        }
        return runCatching { job.await() }.getOrNull()
    }
}
