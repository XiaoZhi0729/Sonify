package yos.music.player.data.objects

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import yos.music.player.data.NormalSaver
import yos.music.player.native.KugouApiService

/** 酷狗云「我关注的歌手」条目（singerid/singername/pic 经多 key 兜底解析）。 */
data class KugouFollowedArtist(
    val artistId: String,
    val name: String,
    val avatarUrl: String?
)

private data class FollowedArtistsSnapshot(
    val userid: String = "",
    val loadedAt: Long = 0L,
    val artists: List<KugouFollowedArtist> = emptyList()
)

/**
 * 已关注歌手列表的唯一数据源（酷狗云同步 + MMKV 持久缓存）。
 *
 * - 列表来自 /user/follow（relationuser follow_list），关注/取关后由
 *   ArtistDetail.toggleFollow 经 [applyFollowChange] 乐观回写，下一次
 *   [ensureLoaded] 自动过期从云端重拉真值。
 * - 持久缓存按 userid 归属隔离：切号/重登时旧账号缓存直接弃用。
 * - 成功拉取后把关注态播种进 ArtistPresentationCache，让艺人详情页
 *   收藏星标在详情接口返回前就展示正确。
 */
object FollowedArtistsObject {
    private const val KEY = "kugou_followed_artists_v2"
    private val TTL = 5 * 60 * 1000L
    private val gson = Gson()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _artists = mutableStateOf<List<KugouFollowedArtist>>(emptyList())
    val artists: State<List<KugouFollowedArtist>> = _artists

    private val _loading = mutableStateOf(false)
    val loading: State<Boolean> = _loading

    private var loadedAt = 0L
    private var loadedUserid: String? = null
    private var loadJob: CompletableDeferred<Unit>? = null
    private var preloadDone = false

    private fun preload() {
        if (preloadDone) return
        preloadDone = true
        runCatching {
            val json = NormalSaver.readData(KEY, "")
            if (json.isNullOrBlank()) return
            val snapshot = gson.fromJson(json, FollowedArtistsSnapshot::class.java) ?: return
            val uid = KugouApiService.userid ?: return
            // 缓存不属于当前账号：静默弃用，等下一次云端拉取覆盖
            if (snapshot.userid != uid) return
            if (snapshot.artists.isNotEmpty()) {
                _artists.value = snapshot.artists
                loadedAt = snapshot.loadedAt
                loadedUserid = snapshot.userid
            }
        }
    }

    /** 进页/登录后调用；TTL 内且同账号不重复请求，失败静默保留缓存。 */
    fun ensureLoaded(force: Boolean = false) {
        preload()
        if (!KugouApiService.isLoggedIn()) return
        val uid = KugouApiService.userid ?: return
        if (loadedUserid != null && loadedUserid != uid) {
            // 账号已切换：丢弃旧账号的内存态，强制走云端
            loadedAt = 0
            loadedUserid = null
            _artists.value = emptyList()
        }
        if (!force && loadedUserid == uid && System.currentTimeMillis() - loadedAt < TTL) return
        synchronized(this) {
            // fire-and-forget；并发调用方借同一个 loadJob 去重
            loadJob ?: CompletableDeferred<Unit>().also { deferred ->
                loadJob = deferred
                scope.launch {
                    try {
                        loadInternal(uid)
                    } finally {
                        synchronized(this@FollowedArtistsObject) { loadJob = null }
                        deferred.complete(Unit)
                    }
                }
            }
        }
    }

    private suspend fun loadInternal(uid: String) {
        _loading.value = true
        try {
            val parsed = KugouApiService.getInstance().getUserFollowedArtists()
                .getOrNull()
                ?.let { parseFollowedList(it) }
            if (parsed != null) {
                _artists.value = parsed
                loadedAt = System.currentTimeMillis()
                loadedUserid = uid
                persist(uid, parsed)
                parsed.forEach { ArtistPresentationCache.setFollowed(it.artistId, uid, true) }
            } else if (_artists.value.isEmpty()) {
                // 云端失败且内存为空：回退持久缓存（同账号）
                runCatching {
                    val snapshot = gson.fromJson(NormalSaver.readData(KEY, ""), FollowedArtistsSnapshot::class.java)
                    if (snapshot != null && snapshot.userid == uid && snapshot.artists.isNotEmpty()) {
                        _artists.value = snapshot.artists
                        loadedAt = snapshot.loadedAt
                        loadedUserid = uid
                    }
                }
            }
        } finally {
            _loading.value = false
        }
    }

    /**
     * 艺人详情页关注/取关后的乐观回写：立即增删列表条目并置过期，
     * 下一次进列表页会从云端重拉真值纠偏。关注时必须带 name/avatarUrl，
     * 否则列表会出现只有 id 的残缺条目。
     */
    fun applyFollowChange(artist: KugouFollowedArtist, followed: Boolean) {
        if (!KugouApiService.isLoggedIn()) return
        val uid = KugouApiService.userid ?: return
        val updated = _artists.value.toMutableList()
        if (followed) {
            updated.removeAll { it.artistId == artist.artistId }
            updated.add(0, artist)
        } else {
            updated.removeAll { it.artistId == artist.artistId }
        }
        _artists.value = updated
        loadedAt = 0
        loadedUserid = uid
        persist(uid, updated)
        ArtistPresentationCache.setFollowed(artist.artistId, uid, followed)
    }

    private fun persist(userid: String, artists: List<KugouFollowedArtist>) {
        runCatching {
            NormalSaver.saveData(KEY, gson.toJson(FollowedArtistsSnapshot(userid, loadedAt, artists)))
        }
    }

    /**
     * 解析 /user/follow 响应。字段兜底对齐 md3Music favorites_page.dart:1636-1650：
     * name = singername|nickname|user_name|name；pic = pic|user_pic|user_img|avatar，
     * 并做 http→https 替换（酷狗 http 图链被禁明文加载）。
     * 业务失败（status!=1 或 error_code!=0，酷狗 502 包体透传）返回 null 让调用方走缓存兜底。
     */
    internal fun parseFollowedList(json: JSONObject): List<KugouFollowedArtist>? {
        if (json.optInt("status", 1) != 1 || json.optInt("error_code", 0) != 0) return null
        val list = json.optJSONObject("data")?.let { data ->
            data.optJSONArray("lists")
                ?: data.optJSONArray("info")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("fans")
        } ?: json.optJSONArray("data") // 少数网关把 data 直接放数组
        list ?: return null
        return parseArtistArray(list)
    }

    private fun parseArtistArray(array: JSONArray): List<KugouFollowedArtist> {
        val out = ArrayList<KugouFollowedArtist>(array.length())
        for (i in 0 until array.length()) {
            val entry = array.optJSONObject(i) ?: continue
            // /v4/follow_list 是"关注关系"大列表：歌手条目带 singerid，
            // 关注的普通用户（酷狗好友）只有 userid。页面语义是"收藏的歌手"，
            // 且详情页只认 singerid——无 singerid 的条目（好友）一律丢弃，
            // 绝不能兜底到 userid（会把好友当歌手，真机踩过）。
            // optString 对 JSON 数值字段返回其十进制字符串形式，无需单独 optLong。
            val id = entry.optString("singerid", "")
            if (id.isBlank() || id == "null" || id == "0") continue
            val name = firstNonBlank(
                entry.optString("singername", ""), entry.optString("nickname", ""),
                entry.optString("user_name", ""), entry.optString("name", "")
            ).trim()
            if (name.isEmpty()) continue
            val pic = firstNonBlank(
                entry.optString("pic", ""), entry.optString("user_pic", ""),
                entry.optString("user_img", ""), entry.optString("avatar", "")
            ).takeIf { it.isNotBlank() }?.let { it.replaceFirst("http://", "https://") }
            out.add(KugouFollowedArtist(artistId = id, name = name, avatarUrl = pic))
        }
        return out
    }

    private fun firstNonBlank(vararg values: String): String =
        values.firstOrNull { it.isNotBlank() && it != "null" } ?: ""
}
