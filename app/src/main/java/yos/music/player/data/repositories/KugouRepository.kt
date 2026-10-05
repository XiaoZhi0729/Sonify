package yos.music.player.data.repositories

import android.net.Uri
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.native.KugouApiService

/**
 * 酷狗在线音乐仓库（第一阶段最小实现）。
 *
 * 整理自 POC 期间的临时 HTTP 调用（PocDebugScreen），正式业务链：
 *   搜索 → YosMediaItem → 现有 MediaController → Media3 播放。
 *
 * 字段解析均按真机实测结构，双写兼容 PascalCase / lowercase
 * （与 md3Music Dart kugou_models.dart 同款策略）。
 * 传输层复用 [KugouApiService]（Kotlin → Rust 本地 HTTP server），不改动 Rust/.so/Media3。
 */

/** 在线搜索结果（领域模型）。duration 单位毫秒；artworkUrl 已替换 {size} 占位符。
 * albumId 供收藏添加协议使用（name|hash|albumId|mixsongid，md3Music 已验证）。 */
data class KugouSearchSong(
    val hash: String,
    val mid: String,
    val name: String,
    val author: String,
    val duration: Long,
    val artworkUrl: String?,
    val albumId: Long = 0
)

/**
 * 播放链接解析结果：url + 服务端回写的实际音质（请求档位不可用时可能低于请求档）。
 * [quality] 可空：缺字段或值不可识别时为 null（= 不知道），**不再默认等于请求档**——
 * 那会让一次完全无证据的解析被下游当成"请求档已确认"，是假 Hi-Res 的另一条来源。
 */
data class KugouResolvedPlayUrl(
    val url: String,
    val quality: String?
)

/** 歌词搜索结果：歌曲 + 命中的歌词片段（作副标题展示）。 */
data class KugouLyricSearchResult(
    val song: KugouSearchSong,
    val lyricFragment: String?
)

/** 歌单搜索结果（specialid/gcid 双 id：详情打开优先 gcid，对齐上游 KugouPlaylistBrief）。 */
data class KugouPlaylistBrief(
    val specialId: Long,
    val globalCollectionId: Long?,
    val name: String,
    val cover: String?,
    val songCount: Int
)

/** 我的在线歌单（领域模型）。字段按 /user/playlist 真机实测：name/count/pic/listid/global_collection_id。
 * isDef 真机实测：1=默认收藏，2=系统「我喜欢」（定位收藏歌单的唯一可靠标识，不按名称判断）。 */
data class KugouPlaylist(
    val listid: String,
    val gid: String,
    val name: String,
    val coverUrl: String,
    val songCount: Int,
    val isDef: Int = 0
)

/** 精选歌单（/top/playlist 实测：data.special_list[]；封面 imgurl/flexible_cover 含 {size}）。
 *  globalCollectionId 是取歌唯一 ID（specialId 仅展示/标识，不可用于 /playlist/track/all/new，
 *  实测把 specialid 当 listid 会得到 20017）。 */
data class KugouRecommendPlaylist(
    val specialId: String,
    val globalCollectionId: String,
    val name: String,
    val artworkUrl: String,
    /** null means the list response did not provide a trustworthy total. */
    val songCount: Int?,
    val intro: String? = null
)

/** Read the first usable integer without conflating missing with zero. */
internal fun parseOptionalInt(values: Map<String, Any?>, vararg keys: String): Int? {
    for (key in keys) {
        val raw = values[key] ?: continue
        val value = when (raw) {
            is Number -> raw.toInt()
            is String -> raw.toIntOrNull()
            else -> null
        }
        if (value != null) return value
    }
    return null
}

private fun JSONObject.optionalValue(key: String): Any? =
    if (!has(key) || isNull(key)) null else opt(key)

private fun JSONObject.toFieldMap(): Map<String, Any?> =
    keys().asSequence().associateWith { key -> optionalValue(key) }

internal fun parseRecommendPlaylist(
    fields: Map<String, Any?>,
    artworkResolver: (String?) -> String?
): KugouRecommendPlaylist? {
    fun text(key: String): String = fields[key]?.toString().orEmpty()
    val gcid = text("global_collection_id")
    val name = text("specialname").ifEmpty { text("name") }
    if (gcid.isEmpty() || name.isEmpty()) return null
    return KugouRecommendPlaylist(
        specialId = text("specialid").ifEmpty { text("special_id") },
        globalCollectionId = gcid,
        name = name,
        artworkUrl = artworkResolver(text("imgurl").ifEmpty { text("flexible_cover") }) ?: "",
        songCount = parseOptionalInt(fields, "songcount", "song_count", "count"),
        intro = text("intro").takeIf { it.isNotEmpty() }
    )
}

/** Parse the /top/playlist payload independently of the network service. */
internal fun parseRecommendPlaylists(
    json: JSONObject,
    artworkResolver: (String?) -> String?
): List<KugouRecommendPlaylist> {
    val data = json.optJSONObject("data") ?: json
    val arr = data.optJSONArray("special_list")
        ?: data.optJSONArray("list")
        ?: data.optJSONArray("plist")
        ?: return emptyList()
    return (0 until arr.length()).mapNotNull { i ->
        runCatching { parseRecommendPlaylist(arr.getJSONObject(i).toFieldMap(), artworkResolver) }.getOrNull()
    }
}

/** 歌单内歌曲（领域模型）。字段按 /playlist/track/all/new 真机实测：
 *  hash、name（可能为"歌手 - 歌名.mp3"形式）、singerinfo[].name、timelen（毫秒）、
 *  album 系双写、封面在 trans_param.union_cover（含 {size} 占位符）。
 *  fileId/mixsongId/albumId 真机实测存在，供收藏增删协议使用（fileid 删除优先，hash 兜底）。 */
data class KugouPlaylistTrack(
    val hash: String,
    val name: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val coverUrl: String?,
    val fileId: Long = 0,
    val mixsongId: Long = 0,
    val albumId: Long = 0
)

/** 在线歌词结果：LRC（行级）+ KRC（逐字，可能为 null）+ 翻译/音译。 */
data class KugouLyrics(
    val lrc: String,
    val krc: String?,
    val translation: String? = null,
    val romanization: String? = null
)

/** Normalized fields from one provider lyric response. */
data class KugouLyricResponse(
    val lrc: String? = null,
    val krc: String? = null,
    val translation: String? = null
)

internal fun parseKugouLyricFields(
    fields: Map<String, String?>,
    krcResponse: Boolean
): KugouLyricResponse {
    fun firstText(vararg names: String): String? = names.asSequence()
        .mapNotNull { fields[it] }
        .firstOrNull { it.isNotBlank() }

    val translation = firstText("translated_content", "trans", "lrcContentChi")
    return if (krcResponse) {
        KugouLyricResponse(
            krc = firstText("decodeKrcContent", "decoded_krc_content", "krcContent", "decodeContent"),
            translation = translation
        )
    } else {
        KugouLyricResponse(
            lrc = firstText("decodeContent", "decoded_content", "lrcContent", "content", "lyrics"),
            translation = translation
        )
    }
}

internal fun parseKugouLyricResponse(json: JSONObject, krcResponse: Boolean): KugouLyricResponse {
    val data = json.optJSONObject("data") ?: json
    val fields = buildMap {
        listOf(
            "decodeContent", "decoded_content", "lrcContent", "content", "lyrics",
            "decodeKrcContent", "decoded_krc_content", "krcContent",
            "translated_content", "trans", "lrcContentChi"
        ).forEach { key -> put(key, data.optString(key, "")) }
    }
    return parseKugouLyricFields(fields, krcResponse)
}

/** 排行榜单（/rank/list 实测字段：rankid/rankname/imgurl/songcount）。 */
data class KugouRank(val rankId: String, val name: String, val coverUrl: String, val songCount: Int)

/** 榜单歌曲（/rank/audio 已被 Rust 扁平化；duration 实测秒）。 */
data class KugouRankSong(
    val hash: String,
    val name: String,
    val author: String,
    val albumName: String?,
    val durationSec: Long,
    val artworkUrl: String?
)

/** 新歌（/top/song 实测：data 为 KMR 原始歌曲数组，顶层 hash/songname/author_name/
 * album_name/timelength(ms)/album_sizable_cover，与 /rank/audio 同构但字段位置不同）。
 * durationMs 已归一化为毫秒；artworkUrl 已替换 {size} 占位符。 */
data class KugouNewSong(
    val hash: String,
    val name: String,
    val author: String,
    val albumName: String?,
    val durationMs: Long,
    val artworkUrl: String?
)

/** 艺人歌曲单页：rawCount 是原始数组长度，包含被解析过滤的条目和重复歌曲。 */
data class ArtistAudioPage(
    val songs: List<KugouNewSong>,
    val rawCount: Int
)

/** 新专辑（/top/album 实测：data 按地区分 chn/eur/jpn/kor 数组，
 * 每项 albumid/albumname/singername/imgurl/{size}/songcount/publishtime）。 */
data class KugouNewAlbum(
    val albumId: String,
    val name: String,
    val singerName: String,
    val coverUrl: String,
    val songCount: Int,
    val publishTime: String?
)

/** 在线专辑详情（/album/detail 实测：data 为数组取第一个；
 * album_id/album_name/author_name/sizable_cover/{size}/intro/publish_date/authors[]）。
 * 用于在线专辑详情页头部元数据；歌曲列表由 /album/songs 另行拉取。 */
data class KugouAlbumDetail(
    val albumId: String,
    val name: String,
    val artistName: String,
    val coverUrl: String,
    val intro: String?,
    val publishDate: String?,
    val songCount: Int,
    val artistId: String?
)

/** 歌手搜索条目（/search/artist 实测 data 直接为数组：singerid/singername/sizable_avatar|imgurl|avatar）。 */
data class KugouArtistBrief(
    val singerId: String,
    val name: String,
    val avatarUrl: String?
)

/** 歌手详情（/artist/detail 实测 data 数组取第一个；字段族 singername|author_name /
 * intro|description|desc / sizable_avatar|imgurl|img|pic|avatar_url / songcount|song_count /
 * albumcount|album_count / 关注态 is_follow|isfollow|followed）。对齐 Dart KugouArtistDetail。 */
data class KugouArtistDetailData(
    val artistId: String,
    val name: String,
    val avatarUrl: String?,
    val intro: String?,
    val songCount: Int,
    val albumCount: Int,
    val isFollowed: Boolean
)

object KugouRepository {

    private const val TAG = "KugouRepository"

    /**
     * 上游拒播异常（非播放器 bug）：酷狗 /song/url 未发放可用音源。
     * [userReason] 面向用户的简明原因（依实际字段判定，不泛化）；
     * 异常 message 携带完整诊断字段（status/priv_status/pay_block_tpl/cpy_map），供日志记录。
     */
    class KugouPlayBlockedException(val userReason: String, fullDetail: String) : IOException(fullDetail)

    /** 队列占位符 URI scheme：入队时无真实 URL，由 KugouResolvingDataSource 在播放前惰性解析。 */
    const val PLACEHOLDER_SCHEME = "kugou"

    /** 在线歌曲队列占位符 URI：kugou://song/<hash>（真实 CDN URL 由数据源在 open() 时解析）。 */
    fun placeholderUri(hash: String): Uri = Uri.parse("$PLACEHOLDER_SCHEME://song/${hash.lowercase()}")

    /** 封面 URL 归一化：酷狗图片 URL 含 {size} 占位符，对齐 Dart _resolveArtworkUri 替换为 400。
     * 并统一升级为 https：自建歌单封面实测大量位于 c1.kgimg.com，而应用网络安全配置
     * 仅对 kugou.com 系域名放行明文 HTTP，http 的 kgimg.com 封面会被系统拦截导致
     * 封面显示占位图；实测 imge.kugou.com / c1.kgimg.com 的 https 均 200（image 类型）。 */
    private fun resolveArtworkUrl(raw: String?): String? =
        raw?.takeIf { it.isNotEmpty() }
            ?.replace("{size}", "400")
            ?.let { if (it.startsWith("http://")) "https://" + it.removePrefix("http://") else it }

    /** 关键词搜索在线歌曲。对应 Rust GET /search（type=song）。 */
    suspend fun searchSongs(keyword: String, page: Int = 1, pageSize: Int = 20): Result<List<KugouSearchSong>> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().searchSongs(keyword, page, pageSize).fold(
                    onSuccess = { json ->
                        // 实测结构：{ status, data: { total, lists: [ {...} ] } }
                        val data = json.optJSONObject("data") ?: json
                        val list = data.optJSONArray("lists")
                            ?: data.optJSONArray("songs")
                            ?: data.optJSONArray("info")
                        if (list == null) {
                            return@fold Result.failure<List<KugouSearchSong>>(
                                IllegalStateException("搜索响应缺少歌曲列表")
                            )
                        }
                        val songs = (0 until list.length()).mapNotNull { i ->
                            runCatching { parseSearchSong(list.getJSONObject(i)) }.getOrNull()
                        }.filter { it.hash.isNotEmpty() }
                        Result.success(songs)
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "searchSongs exception", e)
                Result.failure(e)
            }
        }

    /** /search 歌曲条目解析（song 与 lyric 两类响应同构）；顺带登记收藏协议所需歌曲身份。 */
    private fun parseSearchSong(song: JSONObject): KugouSearchSong? {
        val name = song.optString("SongName")
            .ifEmpty { song.optString("songname") }
        val author = song.optString("SingerName")
            .ifEmpty { song.optString("singername") }
        val hash = song.optString("FileHash")
            .ifEmpty { song.optString("hash") }
        // /search 的 Duration 实测单位为秒
        val durationSec = song.optLong("Duration", song.optLong("duration", 0))
        // 封面实测字段：顶层 Image，备用 trans_param.union_cover
        //（对齐 Dart KugouSongDetail.fromJson 取链；AlbumImage 实测为空不可用）
        val artwork = resolveArtworkUrl(
            song.optString("Image", "").ifEmpty {
                song.optJSONObject("trans_param")?.optString("union_cover", "") ?: ""
            }
        )
        // AlbumID 实测为 PascalCase（收藏添加协议需要）
        val albumId = song.optLong("AlbumID", song.optLong("album_id", 0))
        val searchSong = KugouSearchSong(
            hash = hash,
            mid = song.optString("MixSongID", song.optString("mid")),
            name = name,
            author = author,
            duration = durationSec * 1000,
            artworkUrl = artwork,
            albumId = albumId
        )
        // 顺带登记歌曲身份（收藏添加协议需 albumId/mixsongid）
        registerSongIdentity(hash, albumId, searchSong.mid.toLongOrNull() ?: 0, 0)
        return searchSong
    }

    /** 歌词搜索。对应 Rust GET /search?type=lyric（complexsearch /v1/search/lyric），结果带匹配片段。 */
    suspend fun searchLyricSongs(keyword: String, page: Int = 1, pageSize: Int = 20): Result<List<KugouLyricSearchResult>> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().searchSongs(keyword, page, pageSize, type = "lyric").fold(
                    onSuccess = { json ->
                        val data = json.optJSONObject("data") ?: json
                        val list = data.optJSONArray("lists")
                            ?: data.optJSONArray("songs")
                            ?: data.optJSONArray("info")
                        if (list == null) {
                            return@fold Result.failure<List<KugouLyricSearchResult>>(
                                IllegalStateException("歌词搜索响应缺少列表")
                            )
                        }
                        val results = (0 until list.length()).mapNotNull { i ->
                            runCatching {
                                val obj = list.getJSONObject(i)
                                parseSearchSong(obj)?.let { song ->
                                    KugouLyricSearchResult(
                                        song = song,
                                        lyricFragment = obj.optString("Lyric").ifEmpty { null }
                                    )
                                }
                            }.getOrNull()
                        }.filter { it.song.hash.isNotEmpty() }
                        Result.success(results)
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "searchLyricSongs exception", e)
                Result.failure(e)
            }
        }

    /** 热搜榜。对应 Rust GET /search/hot（msearch /api/v3/search/hot_tab）。 */
    suspend fun getHotSearch(): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().searchHot().fold(
                onSuccess = { json ->
                    val data = json.opt("data") ?: json
                    val list = when (data) {
                        is JSONArray -> data
                        is JSONObject -> data.optJSONArray("list") ?: data.optJSONArray("info")
                        else -> null
                    }
                    val words = list?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            runCatching {
                                when (val e = arr.get(i)) {
                                    is String -> e
                                    is JSONObject -> listOf("searchword", "keyword", "name")
                                        .firstNotNullOfOrNull { key -> e.optString(key).ifEmpty { null } }
                                    else -> null
                                }
                            }.getOrNull()
                        }.filter { it.isNotBlank() }
                    } ?: emptyList()
                    Result.success(words)
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getHotSearch exception", e)
            Result.failure(e)
        }
    }

    /**
     * 搜索联想。对应 Rust GET /search/suggest（searchtip /v2/getSearchTip）。
     * data[].RecordDatas[].HintInfo 为字符串或 {HintWords} 双形态（对齐上游解析）。
     */
    suspend fun getSearchSuggest(keywords: String): Result<List<String>> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().searchSuggest(keywords).fold(
                onSuccess = { json ->
                    val data = json.opt("data")
                    val words = mutableListOf<String>()
                    when (data) {
                        is JSONArray -> {
                            for (i in 0 until data.length()) {
                                val category = data.optJSONObject(i) ?: continue
                                val records = category.optJSONArray("RecordDatas") ?: continue
                                for (j in 0 until records.length()) {
                                    val record = records.optJSONObject(j) ?: continue
                                    when (val hint = record.opt("HintInfo")) {
                                        is String -> if (hint.isNotBlank()) words.add(hint)
                                        is JSONObject ->
                                            listOf("HintWords", "keyword")
                                                .firstNotNullOfOrNull { key -> hint.optString(key).ifEmpty { null } }
                                                ?.let { if (it.isNotBlank()) words.add(it) }
                                    }
                                }
                            }
                        }

                        is JSONObject -> {
                            val list = data.optJSONArray("list") ?: data.optJSONArray("info")
                            list?.let { arr ->
                                for (i in 0 until arr.length()) {
                                    when (val e = arr.get(i)) {
                                        is String -> if (e.isNotBlank()) words.add(e)
                                        is JSONObject -> listOf("keyword", "searchword", "name")
                                            .firstNotNullOfOrNull { key -> e.optString(key).ifEmpty { null } }
                                            ?.let { if (it.isNotBlank()) words.add(it) }
                                    }
                                }
                            }
                        }
                    }
                    Result.success(words.distinct())
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getSearchSuggest exception", e)
            Result.failure(e)
        }
    }

    /** 专辑搜索。对应 Rust GET /search/album（msearchcdn /api/v3/search/singer 同族），字段同族 /top/album。 */
    suspend fun searchAlbums(keyword: String, page: Int = 1, pageSize: Int = 20): Result<List<KugouNewAlbum>> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().searchAlbums(keyword, page, pageSize).fold(
                    onSuccess = { json ->
                        val data = json.optJSONObject("data") ?: json
                        val list = data.optJSONArray("info")
                            ?: data.optJSONArray("list")
                            ?: data.optJSONArray("albums")
                        if (list == null) {
                            return@fold Result.failure<List<KugouNewAlbum>>(
                                IllegalStateException("专辑搜索响应缺少列表")
                            )
                        }
                        val albums = (0 until list.length()).mapNotNull { i ->
                            runCatching {
                                val a = list.getJSONObject(i)
                                val albumId = a.optString("albumid", a.optString("album_id"))
                                val name = a.optString("albumname", a.optString("album_name"))
                                if (albumId.isEmpty() || name.isEmpty()) return@runCatching null
                                KugouNewAlbum(
                                    albumId = albumId,
                                    name = name,
                                    singerName = a.optString("singername", a.optString("author_name", "")),
                                    coverUrl = resolveArtworkUrl(
                                        a.optString("imgurl", a.optString("sizable_cover", ""))
                                    ) ?: "",
                                    songCount = a.optInt("songcount", a.optInt("song_count", 0)),
                                    publishTime = a.optString("publishtime", a.optString("publish_date", ""))
                                        .takeIf { it.isNotEmpty() }
                                )
                            }.getOrNull()
                        }
                        Result.success(albums)
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "searchAlbums exception", e)
                Result.failure(e)
            }
        }

    /** 艺人专辑。真实上游路由已按 author_id 归属查询，不做搜索候选或逐张详情验证。 */
    suspend fun getVerifiedArtistAlbums(
        artistId: String,
        artistName: String
    ): Result<List<KugouNewAlbum>> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().getArtistAlbums(artistId).fold(
                onSuccess = { json ->
                    val data = json.optJSONObject("data") ?: json
                    val list = data.optJSONArray("list")
                        ?: data.optJSONArray("albums")
                        ?: data.optJSONArray("info")
                        // kmr /kmr/v1/author/albums 的 data 直接是专辑数组（无 list 包裹）
                        ?: json.optJSONArray("data")
                        ?: return@fold Result.failure<List<KugouNewAlbum>>(
                            IllegalStateException("艺人专辑响应缺少列表")
                        )
                    val albums = (0 until list.length()).mapNotNull { index ->
                        runCatching {
                            val item = list.getJSONObject(index)
                            val albumId = item.optString("albumid")
                                .ifEmpty { item.optString("album_id") }
                                .ifEmpty { item.optString("AlbumID") }
                                .ifEmpty { item.optString("id") }
                            val name = item.optString("albumname")
                                .ifEmpty { item.optString("album_name") }
                                .ifEmpty { item.optString("AlbumName") }
                                .ifEmpty { item.optString("name") }
                            if (albumId.isEmpty() || name.isEmpty()) return@runCatching null
                            KugouNewAlbum(
                                albumId = albumId,
                                name = name,
                                singerName = item.optString("singername")
                                    .ifEmpty { item.optString("author_name") }
                                    .ifEmpty { artistName },
                                coverUrl = resolveArtworkUrl(
                                    item.optString("imgurl")
                                        .ifEmpty { item.optString("sizable_cover") }
                                        .ifEmpty { item.optString("img") }
                                        .ifEmpty { item.optString("pic") }
                                ) ?: "",
                                songCount = item.optInt("songcount", item.optInt("song_count", 0)),
                                publishTime = item.optString("publishtime")
                                    .ifEmpty { item.optString("publish_date") }
                                    .takeIf { it.isNotEmpty() }
                            )
                        }.getOrNull()
                    }
                    Result.success(albums)
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getVerifiedArtistAlbums exception", e)
            Result.failure(e)
        }
    }


    /** 歌单搜索。对应 Rust GET /search/special（mobilecdnbj /api/v3/search/special）。 */
    suspend fun searchSpecials(keyword: String, page: Int = 1, pageSize: Int = 20): Result<List<KugouPlaylistBrief>> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().searchSpecial(keyword, page, pageSize).fold(
                    onSuccess = { json ->
                        val data = json.optJSONObject("data") ?: json
                        val list = data.optJSONArray("info")
                            ?: data.optJSONArray("list")
                            ?: data.optJSONArray("specials")
                        if (list == null) {
                            return@fold Result.failure<List<KugouPlaylistBrief>>(
                                IllegalStateException("歌单搜索响应缺少列表")
                            )
                        }
                        val playlists = (0 until list.length()).mapNotNull { i ->
                            runCatching {
                                val e = list.getJSONObject(i)
                                val specialId = e.optLong("specialid", e.optLong("id", 0))
                                if (specialId == 0L) return@runCatching null
                                KugouPlaylistBrief(
                                    specialId = specialId,
                                    globalCollectionId = e.optLong("global_collection_id", e.optLong("gid", 0))
                                        .takeIf { it > 0 },
                                    name = e.optString("specialname").ifEmpty { e.optString("name") },
                                    cover = resolveArtworkUrl(
                                        e.optString("imgurl").ifEmpty {
                                            e.optString("sizable_cover").ifEmpty { e.optString("img") }
                                        }
                                    ),
                                    songCount = e.optInt("songcount", e.optInt("song_count", e.optInt("count", 0)))
                                )
                            }.getOrNull()
                        }
                        Result.success(playlists)
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "searchSpecials exception", e)
                Result.failure(e)
            }
        }

    // ---------- 排行榜（Discovery；协议对齐 md3Music KugouRankList / getRankAudio） ----------

    /**
     * 排行榜列表（对齐 Dart getRankList → KugouRankList.fromJson）。
     * GET /rank/list?withsong=1 → data.info[]，双写兼容 rankid|id、rankname|name、
     * imgurl|img_9|banner_9；封面经 [resolveArtworkUrl] 替换 {size} 并升级 https。
     */
    suspend fun getRankList(): Result<List<KugouRank>> = withContext(Dispatchers.IO) {
        try {
            val json = KugouApiService.getInstance().getRankList().getOrNull()
                ?: return@withContext Result.failure(IOException("/rank/list 请求失败"))
            val data = json.optJSONObject("data") ?: json
            val arr = data.optJSONArray("info")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("ranks")
                ?: return@withContext Result.success(emptyList())
            val list = (0 until arr.length()).mapNotNull { i ->
                runCatching {
                    val r = arr.getJSONObject(i)
                    val id = r.optString("rankid", r.optString("id", r.optString("rank_id")))
                    val name = r.optString("rankname", r.optString("name"))
                    if (id.isEmpty() || name.isEmpty()) return@runCatching null
                    KugouRank(
                        rankId = id,
                        name = name,
                        coverUrl = resolveArtworkUrl(
                            r.optString("imgurl", r.optString("img_9", r.optString("banner_9")))
                        ) ?: "",
                        // 实测 /rank/list 无 songcount/song_count 字段（239KB 响应中 0 次出现），
                        // 真实总数在每个榜单项的 extra.resp.all_total（如 TOP500→500）
                        songCount = r.optInt("songcount", r.optInt("song_count",
                            r.optJSONObject("extra")?.optJSONObject("resp")?.optInt("all_total", 0) ?: 0))
                    )
                }.getOrNull()
            }
            Result.success(list)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 榜单全部歌曲（对齐 Dart getRankSongs：分页循环拉全量，batch 30、
     * 上限 100 页防异常翻页；末页不足一页即停）。
     */
    suspend fun getRankSongs(rankId: String): Result<List<KugouRankSong>> = withContext(Dispatchers.IO) {
        try {
            val api = KugouApiService.getInstance()
            val all = mutableListOf<KugouRankSong>()
            for (page in 1..100) {
                val json = api.getRankAudio(rankId, page = page, pageSize = 30).getOrNull()
                    ?: return@withContext if (all.isEmpty()) {
                        Result.failure(IOException("/rank/audio 请求失败"))
                    } else {
                        Result.success(all)
                    }
                val pageSongs = parseRankSongs(json)
                all.addAll(pageSongs)
                if (pageSongs.size < 30) break
            }
            Log.d(TAG, "getRankSongs rankId=$rankId parsed=${all.size}")
            Result.success(all)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** 榜单歌曲单页解析（对齐 Dart KugouSongDetail.fromJson 完整字段链）。
     *  实测 /rank/audio 的 data.songlist[] 为 kmr 原始嵌套结构（顶层有 songname，
     *  Rust 的扁平化判断因此短路原样透传）：hash 在 audio_info.hash / trans_param.ogg_128_hash，
     *  封面在 trans_param.union_cover / album_info.sizable_cover，duration 在 audio_info.duration。 */
    private fun parseRankSongs(json: JSONObject): List<KugouRankSong> {
        val data = json.optJSONObject("data") ?: json
        val arr = data.optJSONArray("songlist")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("songs")
            ?: data.optJSONArray("info")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val s = arr.getJSONObject(i)
                val transParam = s.optJSONObject("trans_param")
                val audioInfo = s.optJSONObject("audio_info")
                val albumInfo = s.optJSONObject("album_info")
                // hash：对齐 Dart 完整链（含嵌套兑底）
                val hash = s.optString("hash").ifEmpty { s.optString("FileHash") }
                    .ifEmpty { s.optString("Hash128") }
                    .ifEmpty { s.optString("SQFileHash") }
                    .ifEmpty { s.optString("HQFileHash") }
                    .ifEmpty { transParam?.optString("ogg_128_hash") ?: "" }
                    .ifEmpty { audioInfo?.optString("hash") ?: "" }
                if (hash.isEmpty()) return@runCatching null
                // 歌手：author_name 优先（本接口实测顶层存在），SingerName/artist_name/singername 兑底
                var author = s.optString("author_name")
                    .ifEmpty { s.optString("SingerName") }
                    .ifEmpty { s.optString("artist_name") }
                    .ifEmpty { s.optString("singername") }
                if (author.isEmpty()) {
                    val si = s.optJSONArray("singerinfo")
                    if (si != null && si.length() > 0) {
                        author = (0 until si.length()).mapNotNull { j ->
                            si.optJSONObject(j)?.optString("name")?.takeIf { it.isNotEmpty() }
                        }.joinToString("、")
                    }
                }
                // 歌名：songname 优先（对齐 Dart toSong：仅含 " - " 时剥前缀，歌手缺失时从文件名提取）
                val rawName = s.optString("songname")
                    .ifEmpty { s.optString("SongName") }
                    .ifEmpty { s.optString("name") }
                    .ifEmpty { s.optString("ori_audio_name") }
                    .ifEmpty { s.optString("FileName") }
                    .ifEmpty { s.optString("filename") }
                    .ifEmpty { s.optJSONObject("base")?.optString("audio_name") ?: "" }
                var title = rawName
                val sepIdx = rawName.indexOf(" - ")
                if (sepIdx > 0) {
                    val rest = rawName.substring(sepIdx + 3)
                    if (rest.isNotBlank()) {
                        if (author.isEmpty()) author = rawName.substring(0, sepIdx)
                        title = rest
                    }
                }
                // 专辑名：含 album_info.album_name 嵌套兑底
                val albumName = s.optString("album_name")
                    .ifEmpty { s.optString("AlbumName") }
                    .ifEmpty { s.optString("albumname") }
                    .ifEmpty { s.optJSONObject("albuminfo")?.optString("name") ?: "" }
                    .ifEmpty { albumInfo?.optString("album_name") ?: "" }
                    .takeIf { it.isNotEmpty() }
                // duration：对齐 Dart _normalizeDuration（原始值 > 10000 视为毫秒，除以 1000）；
                // 实测 kmr 嵌套结构无顶层 duration/audio_info.duration，毫秒时长在
                // audio_info.duration_128/320/flac/high（对齐 Dart 闭包兑底顺序）
                val rawDuration = sequenceOf(
                    s.optLong("time_length", -1L),
                    s.optLong("HQDuration", -1L),
                    s.optLong("Duration", -1L),
                    s.optLong("duration", -1L),
                    audioInfo?.optLong("duration", -1L) ?: -1L,
                    s.optLong("SuperDuration", -1L),
                    s.optLong("timelength", -1L),
                    audioInfo?.optLong("duration_flac", -1L) ?: -1L,
                    audioInfo?.optLong("duration_320", -1L) ?: -1L,
                    audioInfo?.optLong("duration_high", -1L) ?: -1L,
                    audioInfo?.optLong("duration_128", -1L) ?: -1L
                ).firstOrNull { it >= 0 } ?: 0L
                val durationSec = if (rawDuration > 10000) rawDuration / 1000 else rawDuration
                // 封面：含 trans_param.union_cover / album_info.sizable_cover 嵌套兑底
                val artwork = s.optString("sizable_cover")
                    .ifEmpty { s.optString("Image") }
                    .ifEmpty { s.optString("ImgUrl") }
                    .ifEmpty { s.optString("img") }
                    .ifEmpty { s.optString("pic") }
                    .ifEmpty { s.optString("cover") }
                    .ifEmpty { transParam?.optString("union_cover") ?: "" }
                    .ifEmpty { albumInfo?.optString("sizable_cover") ?: "" }
                    .ifEmpty { albumInfo?.optString("cover") ?: "" }
                KugouRankSong(
                    hash = hash,
                    name = title,
                    author = author,
                    albumName = albumName,
                    durationSec = durationSec,
                    artworkUrl = resolveArtworkUrl(artwork)
                )
            }.getOrNull()
        }
    }

    /** 榜单歌曲 → 队列用 [YosMediaItem]（占位符 URI，真实 URL 由播放器惰性解析；duration 秒→毫秒）。 */
    fun toQueueMediaItem(song: KugouRankSong): YosMediaItem = buildQueueItem(
        hash = song.hash,
        title = song.name,
        artist = song.author,
        album = song.albumName,
        durationMs = song.durationSec * 1000,
        artworkUrl = song.artworkUrl
    )

    /** 榜单歌曲 → 仅展示用 [YosMediaItem]（uri 为空，复用 MusicList Item 视觉规范）。 */
    fun toDisplayMediaItem(song: KugouRankSong): YosMediaItem = YosMediaItem(
        uri = null,
        mediaId = "kugou-online-${song.hash.lowercase()}",
        mimeType = "audio/mpeg",
        title = song.name,
        writer = null,
        compilation = null,
        composer = null,
        artists = song.author,
        album = song.albumName,
        albumArtists = null,
        thumb = song.artworkUrl?.let { Uri.parse(it) },
        trackNumber = null,
        discNumber = null,
        genre = null,
        recordingDay = null,
        recordingMonth = null,
        recordingYear = null,
        releaseYear = null,
        artistId = null,
        albumId = null,
        genreId = null,
        author = song.author,
        addDate = null,
        duration = song.durationSec * 1000,
        modifiedDate = null,
        cdTrackNumber = null
    )

    // ---------- 新歌 / 新专辑 / 在线专辑（Discovery 第二阶段） ----------

    /**
     * 新歌速递（Discovery「新歌」横向 Section）。对齐 Dart getTopSong（纯 API 封装，无 UI 消费）。
     * GET /top/song?page= → data 直接是 KMR 原始歌曲数组（与 /rank/audio 同构），
     * 单页 30 首、total 顶层声明；第一版只取首页，不做分页/查看全部。
     */
    suspend fun getTopSongs(page: Int = 1, pageSize: Int = 30): Result<List<KugouNewSong>> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().getTopSong(page).fold(
                    onSuccess = { json ->
                        Result.success(parseTopSongs(json))
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "getTopSongs exception", e)
                Result.failure(e)
            }
        }

    /**
     * 新专辑推荐（Discovery「新专辑」横向 Section）。对齐 Dart getTopAlbum。
     * GET /top/album?page= → data 按地区分 chn/eur/jpn/kor，扁平合并为单一列表。
     */
    suspend fun getTopAlbums(page: Int = 1, pageSize: Int = 30): Result<List<KugouNewAlbum>> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().getTopAlbum(page).fold(
                    onSuccess = { json ->
                        Result.success(parseTopAlbums(json))
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "getTopAlbums exception", e)
                Result.failure(e)
            }
        }

    /**
     * 在线专辑详情（头部元数据）。GET /album/detail?album_id= → data 为数组取第一个。
     * songCount 真机实测 /album/detail 无 songcount 字段，返回 0；详情页以实际加载歌曲数为准。
     */
    suspend fun getAlbumDetail(albumId: String): Result<KugouAlbumDetail> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().getAlbumDetail(albumId).fold(
                onSuccess = { json ->
                    val item = json.optJSONArray("data")?.optJSONObject(0)
                        ?: return@fold Result.failure<KugouAlbumDetail>(
                            IllegalStateException("专辑详情响应为空")
                        )
                    val id = item.optString("album_id", item.optString("albumid", albumId))
                    val name = item.optString("album_name", item.optString("albumname", ""))
                    var artist = item.optString("author_name", item.optString("singername", ""))
                    var artistId: String? = null
                    if (artist.isEmpty()) {
                        val authors = item.optJSONArray("authors")
                        if (authors != null && authors.length() > 0) {
                            val first = authors.optJSONObject(0)
                            artist = first?.optString("author_name", "") ?: ""
                            artistId = first?.optString("author_id", "")?.takeIf { it.isNotEmpty() }
                        }
                    }
                    val cover = item.optString("sizable_cover", item.optString("imgurl", ""))
                    Result.success(
                        KugouAlbumDetail(
                            albumId = id,
                            name = name,
                            artistName = artist,
                            coverUrl = resolveArtworkUrl(cover) ?: "",
                            intro = item.optString("intro", "").takeIf { it.isNotEmpty() },
                            publishDate = item.optString("publish_date", item.optString("publishtime", ""))
                                .takeIf { it.isNotEmpty() },
                            songCount = item.optInt("songcount", item.optInt("song_count", 0)),
                            artistId = artistId
                        )
                    )
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getAlbumDetail exception", e)
            Result.failure(e)
        }
    }

    /**
     * 在线专辑全量歌曲（分页拉取）。GET /album/songs?album_id=&page=&pagesize=
     * → data.songs[] 扁平结构，duration 实测毫秒；末页不足 30 即停，上限 100 页防异常翻页。
     */
    suspend fun getAlbumSongs(albumId: String): Result<List<KugouNewSong>> = withContext(Dispatchers.IO) {
        try {
            val api = KugouApiService.getInstance()
            val all = mutableListOf<KugouNewSong>()
            for (page in 1..100) {
                val json = api.getAlbumSongs(albumId, page = page, pageSize = 30).getOrNull()
                    ?: return@withContext if (all.isEmpty()) {
                        Result.failure(IOException("/album/songs 请求失败"))
                    } else {
                        Result.success(all)
                    }
                val pageSongs = parseAlbumSongs(json)
                all.addAll(pageSongs)
                if (pageSongs.size < 30) break
            }
            Log.d(TAG, "getAlbumSongs albumId=$albumId parsed=${all.size}")
            Result.success(all)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /** /top/song 单页解析：data 直接是数组（区别于 /rank/audio 的 data.songlist）。
     *  每项为 KMR 原始结构，顶层 hash/songname/author_name/album_name/timelength(ms)/
     *  album_sizable_cover；duration > 10000 视为毫秒，否则视为秒换算。 */
    private fun parseTopSongs(json: JSONObject): List<KugouNewSong> {
        val data = json.optJSONArray("data") ?: return emptyList()
        return (0 until data.length()).mapNotNull { i ->
            runCatching {
                val s = data.getJSONObject(i)
                val transParam = s.optJSONObject("trans_param")
                val audioInfo = s.optJSONObject("audio_info")
                val albumInfo = s.optJSONObject("album_info")
                val hash = s.optString("hash").ifEmpty { s.optString("FileHash") }
                    .ifEmpty { s.optString("Hash128") }
                    .ifEmpty { transParam?.optString("ogg_128_hash") ?: "" }
                    .ifEmpty { audioInfo?.optString("hash") ?: "" }
                if (hash.isEmpty()) return@runCatching null

                var author = s.optString("author_name")
                    .ifEmpty { s.optString("SingerName") }
                    .ifEmpty { s.optString("artist_name") }
                    .ifEmpty { s.optString("singername") }
                if (author.isEmpty()) {
                    val authors = s.optJSONArray("authors")
                    if (authors != null && authors.length() > 0) {
                        author = (0 until authors.length()).mapNotNull { j ->
                            authors.optJSONObject(j)?.optString("author_name")?.takeIf { it.isNotEmpty() }
                        }.joinToString("、")
                    }
                }
                if (author.isEmpty()) {
                    val si = s.optJSONArray("singerinfo")
                    if (si != null && si.length() > 0) {
                        author = (0 until si.length()).mapNotNull { j ->
                            si.optJSONObject(j)?.optString("name")?.takeIf { it.isNotEmpty() }
                        }.joinToString("、")
                    }
                }

                val rawName = s.optString("songname").ifEmpty { s.optString("SongName") }
                    .ifEmpty { s.optString("name") }
                    .ifEmpty { s.optString("filename") }
                var title = rawName
                val sepIdx = rawName.indexOf(" - ")
                if (sepIdx > 0) {
                    val rest = rawName.substring(sepIdx + 3)
                    if (rest.isNotBlank()) {
                        if (author.isEmpty()) author = rawName.substring(0, sepIdx)
                        title = rest
                    }
                }

                val albumName = s.optString("album_name").ifEmpty { s.optString("albumname") }
                    .ifEmpty { albumInfo?.optString("album_name") ?: "" }
                    .takeIf { it.isNotEmpty() }

                val rawDuration = sequenceOf(
                    s.optLong("timelength", -1L),
                    s.optLong("duration", -1L),
                    s.optLong("time_length", -1L),
                    audioInfo?.optLong("duration", -1L) ?: -1L,
                    audioInfo?.optLong("duration_128", -1L) ?: -1L,
                    audioInfo?.optLong("duration_320", -1L) ?: -1L
                ).firstOrNull { it >= 0 } ?: 0L
                val durationMs = if (rawDuration > 10000) rawDuration else rawDuration * 1000

                val artwork = s.optString("album_sizable_cover")
                    .ifEmpty { s.optString("sizable_cover") }
                    .ifEmpty { s.optString("Image") }
                    .ifEmpty { s.optString("ImgUrl") }
                    .ifEmpty { s.optString("img") }
                    .ifEmpty { s.optString("pic") }
                    .ifEmpty { s.optString("cover") }
                    .ifEmpty { transParam?.optString("union_cover") ?: "" }
                    .ifEmpty { albumInfo?.optString("sizable_cover") ?: "" }
                    .ifEmpty { albumInfo?.optString("cover") ?: "" }

                KugouNewSong(
                    hash = hash,
                    name = title,
                    author = author,
                    albumName = albumName,
                    durationMs = durationMs,
                    artworkUrl = resolveArtworkUrl(artwork)
                )
            }.getOrNull()
        }
    }

    /** /top/album 单页解析：扁平合并 chn/eur/jpn/kor 四个地区数组。 */
    private fun parseTopAlbums(json: JSONObject): List<KugouNewAlbum> {
        val data = json.optJSONObject("data") ?: json
        val result = mutableListOf<KugouNewAlbum>()
        for (region in listOf("chn", "eur", "jpn", "kor")) {
            val arr = data.optJSONArray(region) ?: continue
            for (i in 0 until arr.length()) {
                runCatching {
                    val a = arr.getJSONObject(i)
                    val albumId = a.optString("albumid", a.optString("album_id"))
                    val name = a.optString("albumname", a.optString("album_name"))
                    if (albumId.isEmpty() || name.isEmpty()) return@runCatching
                    val singer = a.optString("singername", a.optString("author_name", ""))
                    val cover = a.optString("imgurl", a.optString("sizable_cover", ""))
                    result.add(
                        KugouNewAlbum(
                            albumId = albumId,
                            name = name,
                            singerName = singer,
                            coverUrl = resolveArtworkUrl(cover) ?: "",
                            songCount = a.optInt("songcount", a.optInt("song_count", 0)),
                            publishTime = a.optString("publishtime", a.optString("publish_date", ""))
                                .takeIf { it.isNotEmpty() }
                        )
                    )
                }.getOrNull()
            }
        }
        return result
    }

    /** /album/songs 单页解析：data.songs[] 已被 Rust 重塑为扁平结构，duration 实测毫秒。 */
    private fun parseAlbumSongs(json: JSONObject): List<KugouNewSong> {
        val data = json.optJSONObject("data") ?: json
        val arr = data.optJSONArray("songs")
            ?: data.optJSONArray("list")
            ?: data.optJSONArray("info")
            ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val s = arr.getJSONObject(i)
                val hash = s.optString("hash").ifEmpty { s.optString("hash_128") }
                if (hash.isEmpty()) return@runCatching null

                var author = s.optString("author_name", s.optString("singername", ""))
                if (author.isEmpty()) {
                    val si = s.optJSONArray("singerinfo")
                    if (si != null && si.length() > 0) {
                        author = (0 until si.length()).mapNotNull { j ->
                            si.optJSONObject(j)?.optString("name")?.takeIf { it.isNotEmpty() }
                        }.joinToString("、")
                    }
                }

                val rawName = s.optString("songname", s.optString("name", ""))
                var title = rawName
                val sepIdx = rawName.indexOf(" - ")
                if (sepIdx > 0) {
                    val rest = rawName.substring(sepIdx + 3)
                    if (rest.isNotBlank()) {
                        if (author.isEmpty()) author = rawName.substring(0, sepIdx)
                        title = rest
                    }
                }

                val rawDuration = s.optLong("duration", 0L)
                val durationMs = if (rawDuration > 10000) rawDuration else rawDuration * 1000

                val artwork = s.optString("cover")
                    .ifEmpty { s.optString("sizable_cover") }
                    .ifEmpty { s.optString("img") }
                    .ifEmpty { s.optString("pic") }

                KugouNewSong(
                    hash = hash,
                    name = title,
                    author = author,
                    albumName = s.optString("album_name", "").takeIf { it.isNotEmpty() },
                    durationMs = durationMs,
                    artworkUrl = resolveArtworkUrl(artwork)
                )
            }.getOrNull()
        }
    }

    /**
     * 通用 KMR 歌曲数组提取：每日推荐 /personal/fm /artist/audios 的 data 形态不一
     * （对象包 song_list/songs/list/info，或直接为数组），统一在此归一。
     */
    private fun extractSongArray(json: JSONObject): JSONArray? {
        when (val data = json.opt("data")) {
            is JSONArray -> return data
            is JSONObject -> return data.optJSONArray("song_list")
                ?: data.optJSONArray("songs")
                ?: data.optJSONArray("list")
                ?: data.optJSONArray("info")
        }
        return json.optJSONArray("song_list")
            ?: json.optJSONArray("songs")
            ?: json.optJSONArray("list")
            ?: json.optJSONArray("info")
    }

    /**
     * 通用歌曲条目解析（/recommend/songs、/personal/fm、/artist/audios 共用），
     * 字段族与 /top/song 同构但键位不定，全部做宽容回退；duration > 10000 视为毫秒。
     */
    private fun parseKmrSongItem(s: JSONObject): KugouNewSong? {
        val transParam = s.optJSONObject("trans_param")
        val audioInfo = s.optJSONObject("audio_info")
        val albumInfo = s.optJSONObject("album_info")
        val hash = s.optString("hash").ifEmpty { s.optString("FileHash") }
            .ifEmpty { s.optString("Hash128") }
            .ifEmpty { transParam?.optString("ogg_128_hash") ?: "" }
            .ifEmpty { audioInfo?.optString("hash") ?: "" }
        if (hash.isEmpty()) return null

        var author = s.optString("author_name")
            .ifEmpty { s.optString("SingerName") }
            .ifEmpty { s.optString("artist_name") }
            .ifEmpty { s.optString("singername") }
        if (author.isEmpty()) {
            val authors = s.optJSONArray("authors")
            if (authors != null && authors.length() > 0) {
                author = (0 until authors.length()).mapNotNull { j ->
                    authors.optJSONObject(j)?.optString("author_name")?.takeIf { it.isNotEmpty() }
                }.joinToString("、")
            }
        }
        if (author.isEmpty()) {
            val si = s.optJSONArray("singerinfo")
            if (si != null && si.length() > 0) {
                author = (0 until si.length()).mapNotNull { j ->
                    si.optJSONObject(j)?.optString("name")?.takeIf { it.isNotEmpty() }
                }.joinToString("、")
            }
        }

        // /artist/audios 条目歌名只在 audio_name（含后缀），其余接口走 songname/filename 族
        val rawName = s.optString("songname").ifEmpty { s.optString("SongName") }
            .ifEmpty { s.optString("name") }
            .ifEmpty { s.optString("filename") }
            .ifEmpty { s.optString("audio_name") }
            .ifEmpty { s.optString("ori_song_name") }
            .ifEmpty { s.optString("OriSongName") }
        var title = rawName
        val sepIdx = rawName.indexOf(" - ")
        if (sepIdx > 0) {
            val rest = rawName.substring(sepIdx + 3)
            if (rest.isNotBlank()) {
                if (author.isEmpty()) author = rawName.substring(0, sepIdx)
                title = rest
            }
        }

        val albumName = s.optString("album_name").ifEmpty { s.optString("albumname") }
            .ifEmpty { albumInfo?.optString("album_name") ?: "" }
            .takeIf { it.isNotEmpty() }

        val rawDuration = sequenceOf(
            s.optLong("timelength", -1L),
            s.optLong("duration", -1L),
            s.optLong("time_length", -1L),
            audioInfo?.optLong("duration", -1L) ?: -1L,
            audioInfo?.optLong("duration_128", -1L) ?: -1L
        ).firstOrNull { it >= 0 } ?: 0L
        val durationMs = if (rawDuration > 10000) rawDuration else rawDuration * 1000

        val artwork = s.optString("album_sizable_cover")
            .ifEmpty { s.optString("sizable_cover") }
            .ifEmpty { s.optString("Image") }
            .ifEmpty { s.optString("ImgUrl") }
            .ifEmpty { s.optString("img") }
            .ifEmpty { s.optString("pic") }
            .ifEmpty { s.optString("cover") }
            .ifEmpty { transParam?.optString("union_cover") ?: "" }
            .ifEmpty { albumInfo?.optString("sizable_cover") ?: "" }
            .ifEmpty { albumInfo?.optString("cover") ?: "" }

        return KugouNewSong(
            hash = hash,
            name = title,
            author = author,
            albumName = albumName,
            durationMs = durationMs,
            artworkUrl = resolveArtworkUrl(artwork)
        )
    }

    /** 通用歌曲列表解析：提取数组 → 逐项宽容解析，过滤无 hash 条目。 */
    private fun parseSongDetailList(json: JSONObject): List<KugouNewSong> {
        val arr = extractSongArray(json) ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            runCatching { parseKmrSongItem(arr.getJSONObject(i)) }.getOrNull()
        }
    }

    /**
     * 每日推荐歌曲（Discovery「每日推荐」；对齐 Dart getRecommendDaily）。
     * GET /recommend/songs → data.song_list[]（KMR 歌曲字段族，结构不定走通用解析）。
     * userid 经 authHeader() cookie 带入；未登录 Rust 侧回退 userid=0 的通用推荐。
     */
    suspend fun getEverydayRecommendSongs(): Result<List<KugouNewSong>> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().getRecommendDailySongs().fold(
                onSuccess = { json -> Result.success(parseSongDetailList(json)) },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getEverydayRecommendSongs exception", e)
            Result.failure(e)
        }
    }

    /**
     * 私人FM 歌曲列表（Discovery「私人电台」；对齐 Dart getPersonalFm）。
     * GET /personal/fm → data.song_list[]；需登录（未登录上游返回错误，UI 侧先做登录门控）。
     */
    suspend fun getPersonalFmSongs(
        mode: String = "normal",
        hash: String = "",
        songId: String = ""
    ): Result<List<KugouNewSong>> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().getPersonalFm(mode = mode, hash = hash, songId = songId).fold(
                onSuccess = { json ->
                    val songs = parseSongDetailList(json)
                    if (songs.isEmpty()) {
                        Result.failure(IOException("私人FM 未返回歌曲（可能未登录或无可用推荐）"))
                    } else {
                        Result.success(songs)
                    }
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getPersonalFmSongs exception", e)
            Result.failure(e)
        }
    }

    /** 新歌 → 队列用 [YosMediaItem]（占位符 URI，真实 URL 由播放器惰性解析）。 */
    fun toQueueMediaItem(song: KugouNewSong): YosMediaItem = buildQueueItem(
        hash = song.hash,
        title = song.name,
        artist = song.author,
        album = song.albumName,
        durationMs = song.durationMs,
        artworkUrl = song.artworkUrl
    )

    /** 新歌 → 仅展示用 [YosMediaItem]（uri 为空，复用 MusicList Item 视觉规范）。 */
    fun toDisplayMediaItem(song: KugouNewSong): YosMediaItem = YosMediaItem(
        uri = null,
        mediaId = "kugou-online-${song.hash.lowercase()}",
        mimeType = "audio/mpeg",
        title = song.name,
        writer = null,
        compilation = null,
        composer = null,
        artists = song.author,
        album = song.albumName,
        albumArtists = null,
        thumb = song.artworkUrl?.let { Uri.parse(it) },
        trackNumber = null,
        discNumber = null,
        genre = null,
        recordingDay = null,
        recordingMonth = null,
        recordingYear = null,
        releaseYear = null,
        artistId = null,
        albumId = null,
        genreId = null,
        author = song.author,
        addDate = null,
        duration = song.durationMs,
        modifiedDate = null,
        cdTrackNumber = null
    )

    // ---------- 云端收藏（酷狗系统「我喜欢」，协议对齐 md3Music FavoritesProvider） ----------

    /** 云端收藏状态：hash 集合（小写）。Compose State，UI 直接读取重组，不发网络请求。 */
    val favoriteHashes = mutableStateOf<Set<String>>(emptySet())

    /** 歌曲身份：hash(小写) → 收藏协议所需 albumId/mixsongId/fileId。 */
    data class SongIdentity(val albumId: Long, val mixsongId: Long, val fileId: Long)

    private val songIdentity = ConcurrentHashMap<String, SongIdentity>()

    /** 解析时顺带登记歌曲身份；非零字段优先，避免后续解析覆盖已有信息。 */
    fun registerSongIdentity(hash: String, albumId: Long, mixsongId: Long, fileId: Long) {
        if (hash.isEmpty()) return
        val key = hash.lowercase()
        val old = songIdentity[key]
        songIdentity[key] = SongIdentity(
            albumId = if (albumId > 0) albumId else old?.albumId ?: 0,
            mixsongId = if (mixsongId > 0) mixsongId else old?.mixsongId ?: 0,
            fileId = if (fileId > 0) fileId else old?.fileId ?: 0
        )
    }

    private var myFavoritePlaylist: KugouPlaylist? = null

    /** 定位系统「我喜欢」歌单：仅认 is_def == 2（真机实测标识），不按名称判断，避免同名普通歌单误匹配。 */
    suspend fun findMyFavoritePlaylist(): KugouPlaylist? {
        myFavoritePlaylist?.let { return it }
        val list = getMyPlaylists().getOrNull() ?: return null
        return list.firstOrNull { it.isDef == 2 }?.also { myFavoritePlaylist = it }
    }

    /**
     * 同步云端收藏状态：定位「我喜欢」→ 分页拉全量歌曲 → hash 集合灌入 [favoriteHashes]。
     * App 启动/登录态恢复后调用一次；同步时会顺带登记 fileid 等身份（供后续删除使用）。
     */
    suspend fun syncKugouFavorites(): Result<Int> = withContext(Dispatchers.IO) {
        try {
            if (!KugouApiService.isLoggedIn()) {
                favoriteHashes.value = emptySet()
                return@withContext Result.failure<Int>(IllegalStateException("未登录酷狗账号"))
            }
            val fav = findMyFavoritePlaylist()
                ?: return@withContext Result.failure<Int>(IllegalStateException("未找到系统「我喜欢」歌单（is_def=2）"))
            val listid = fav.listid.ifEmpty { fav.gid }
            val tracks = getPlaylistSongs(listid).getOrElse { return@withContext Result.failure<Int>(it) }
            favoriteHashes.value = tracks.map { it.hash.lowercase() }.toSet()
            Log.d(TAG, "云端收藏同步完成 count=${tracks.size}")
            Result.success(tracks.size)
        } catch (e: Exception) {
            Log.e(TAG, "syncKugouFavorites exception", e)
            Result.failure(e)
        }
    }

    /** 本地乐观更新/回滚收藏集合（服务端调用由调用方编排）。 */
    fun setFavoriteLocal(hash: String, favorite: Boolean) {
        val key = hash.lowercase()
        val now = favoriteHashes.value
        favoriteHashes.value = if (favorite) now + key else now - key
    }

    /**
     * 云端添加收藏（纯服务端调用，不改状态）。
     * 严格复用 md3Music 协议：data = "歌名|hash|albumId|mixsongid" → /playlist/tracks/add。
     */
    suspend fun cloudAddFavorite(hash: String, title: String): Result<Unit> {
        val fav = findMyFavoritePlaylist()
            ?: return Result.failure(IllegalStateException("未找到系统「我喜欢」歌单（is_def=2）"))
        val id = songIdentity[hash.lowercase()]
        val data = "$title|$hash|${id?.albumId ?: 0}|${id?.mixsongId ?: 0}"
        return KugouApiService.getInstance()
            .addPlaylistTracks(fav.listid.ifEmpty { fav.gid }, data)
            .map { Unit }
    }

    /**
     * 云端移除收藏（纯服务端调用，不改状态）。
     * 严格复用 md3Music 协议：fileid 优先、hash 兜底（Rust 两种均支持）→ /playlist/tracks/del。
     */
    suspend fun cloudRemoveFavorite(hash: String): Result<Unit> {
        val fav = findMyFavoritePlaylist()
            ?: return Result.failure(IllegalStateException("未找到系统「我喜欢」歌单（is_def=2）"))
        val fileId = songIdentity[hash.lowercase()]?.fileId ?: 0
        val fileids = if (fileId > 0) fileId.toString() else hash
        return KugouApiService.getInstance()
            .deletePlaylistTracks(fav.listid.ifEmpty { fav.gid }, fileids)
            .map { Unit }
    }

    /** 查询歌曲收藏协议身份（albumId/mixsongId/fileId，搜索解析时登记）。 */
    fun songIdentityFor(hash: String): SongIdentity? = songIdentity[hash.lowercase()]

    /** 向任意自有歌单加歌（/playlist/tracks/add，协议 name|hash|albumId|mixsongId）。 */
    suspend fun addTracksToPlaylist(listid: String, title: String, hash: String): Result<Unit> {
        val id = songIdentity[hash.lowercase()]
        val data = "$title|$hash|${id?.albumId ?: 0}|${id?.mixsongId ?: 0}"
        return KugouApiService.getInstance()
            .addPlaylistTracks(listid, data)
            .map { Unit }
    }

    /** 从任意自有歌单删歌（/playlist/tracks/del，fileid 优先、hash 兜底）。 */
    suspend fun removeTrackFromPlaylist(listid: String, fileId: Long, hash: String): Result<Unit> {
        val fileids = if (fileId > 0) fileId.toString() else hash
        return KugouApiService.getInstance()
            .deletePlaylistTracks(listid, fileids)
            .map { Unit }
    }

    /** 搜索结果 → 队列用 [YosMediaItem]（uri 为占位符，真实 URL 由播放器惰性解析）。 */
    fun toQueueMediaItem(song: KugouSearchSong): YosMediaItem = buildQueueItem(
        hash = song.hash,
        title = song.name,
        artist = song.author,
        album = null,
        durationMs = song.duration,
        artworkUrl = song.artworkUrl
    )

    /** 歌单歌曲 → 队列用 [YosMediaItem]（uri 为占位符，真实 URL 由播放器惰性解析）。 */
    fun toQueueMediaItem(track: KugouPlaylistTrack): YosMediaItem = buildQueueItem(
        hash = track.hash,
        title = track.name,
        artist = track.artist,
        album = track.album,
        durationMs = track.durationMs,
        artworkUrl = track.coverUrl
    )

    /**
     * 在线歌曲 → 队列用 [YosMediaItem] 的统一构建。
     * mediaId 保持 kugou-online-<hash>（歌词钩子依赖该前缀），thumb 保留真实封面 URL，
     * uri 为占位符——ExoPlayer 播到该曲时才经数据源解析真实 CDN URL。
     */
    private fun buildQueueItem(
        hash: String,
        title: String,
        artist: String,
        album: String?,
        durationMs: Long,
        artworkUrl: String?
    ): YosMediaItem = YosMediaItem(
        uri = placeholderUri(hash),
        mediaId = "kugou-online-${hash.lowercase()}",
        mimeType = "audio/mpeg",
        title = title,
        writer = null,
        compilation = null,
        composer = null,
        artists = artist,
        album = album,
        albumArtists = null,
        thumb = artworkUrl?.let { Uri.parse(it) },
        trackNumber = null,
        discNumber = null,
        genre = null,
        recordingDay = null,
        recordingMonth = null,
        recordingYear = null,
        releaseYear = null,
        artistId = null,
        albumId = null,
        genreId = null,
        author = artist,
        addDate = null,
        duration = durationMs,
        modifiedDate = null,
        cdTrackNumber = null
    )

    // ---------- 惰性 URL 解析（运行周期内内存缓存） ----------

    private data class CachedUrl(
        /** 缓存键对应的**请求**档位拿到的 URL。 */
        val url: String,
        /** 服务端回写的实际档位（null = 未知）。键是"想要什么"，这里是"实际拿到什么"，
         *  两者可以不同：降级链在低档精确命中时，会把 FLAC 流记在 high 的键下。 */
        val stamped: String?,
        val resolvedAt: Long
    )

    private val urlCache = ConcurrentHashMap<String, CachedUrl>()

    /** 缓存写入唯一入口：禁止任何调用点自行构造 [CachedUrl]，防止又漏掉 stamped。 */
    private fun putCached(hash: String, requested: String, resolved: KugouResolvedPlayUrl) {
        urlCache["${hash.lowercase()}:$requested"] =
            CachedUrl(resolved.url, resolved.quality, System.currentTimeMillis())
    }

    /**
     * 测试专用：直接向 URL 缓存植入一条带真实档位的条目（等价于一次已完成的解析）。
     * 只给单测用，让"命中缓存上报的是真实档而非键档位"这条断言（A1）能在 JVM 里验证。
     */
    internal fun seedCachedUrlForTest(hash: String, requested: String, url: String, stamped: String?) {
        urlCache["${hash.lowercase()}:$requested"] =
            CachedUrl(url, KugouQuality.parse(stamped), System.currentTimeMillis())
    }

    /**
     * 测试专用：读一条缓存项携带的实际档位（null 且无该键 = 条目不存在）。
     * 用来断言"显式降档会丢掉高于目标的缓存"，不必依赖网络。
     */
    internal fun stampedOfCachedUrl(hash: String, requested: String): String? =
        urlCache["${hash.lowercase()}:$requested"]?.stamped

    /** 测试专用：清空音质相关内存态，保证用例之间不互相污染。 */
    internal fun resetQualityStateForTest() {
        urlCache.clear()
        failedCache.clear()
        factByHash.clear()
        pendingByHash.clear()
        // 能力表由解析结果回写，不一起清会让用例之间互相污染
        SongQualityCapabilityStore.clearForTest()
    }

    /** 解析失败负缓存：酷狗拒播（如 status=3 版权/付费拦截）的 hash 短期内必然继续失败，
     * 窗口内快速失败，避免重试/点击反复请求 Rust 与酷狗。 */
    private data class FailedResolve(val failedAt: Long, val reason: String)

    private val failedCache = ConcurrentHashMap<String, FailedResolve>()

    /** URL 缓存有效期：酷狗 CDN URL 实测可存活数小时，30 分钟 TTL 留足安全余量。 */
    private const val URL_CACHE_TTL_MS = 30 * 60 * 1000L

    /** 负缓存有效期：30 秒。历史上是 2 分钟，且用户显式切音质不清它——
     *  于是"刚切过一次失败的 Hi-Res 再点一次"会直接命中负缓存抛错，把用户操作变成跳歌。
     *  缩短窗口 + [prepareForExplicitSwitch] 在显式意图时作废，两头一起堵。 */
    private const val FAIL_CACHE_TTL_MS = 30 * 1000L

    /**
     * 解析整链的全局截止。没有它，弱网下"逐档试探 × 单档 3 次重试 × readTimeout 15s"
     * 的乘积就是分钟级阻塞（真机实测 92s）。10s 给弱网正常解析（实测 5~8s）留了余量，
     * 超时后走 lastGood 兜底而非直接判死；观察一段时间后再决定是否收紧。
     */
    private const val RESOLVE_TOTAL_TIMEOUT_MS = 10_000L

    /**
     * 阻塞式解析播放 URL，供 Media3 数据源在加载线程调用（底层本就是阻塞 OkHttp）。
     * [quality]/[intentSource] 为本次解析的**意图**及其来源，由
     * [QualityIntentResolver.resolve] 产出（与 UI 同一源头）。
     * 运行周期内内存缓存命中直接复用，避免重播/回跳同一首时重复请求 /song/url；
     * 解析失败抛 [KugouPlayBlockedException]/[IOException]，由播放器的失败跳过逻辑接管；
     * 失败 hash 进入负缓存（缓存用户可见原因）。
     * 结果写进 [factOf]：意图与**实际**档位分开存，实际档位取服务端回写值，
     * 可能低于请求档（上游对不可用高档常静默发放低档链接）。
     *
     * 「已缓存更高音源优先」：本会话内该曲某条仍在 TTL 内的缓存 URL 实际档位高于
     * 本次意图时，直接用它（不重请也不谎报）。注意：用户**显式**切低档时这些高缓存
     * 会先被 [prepareForExplicitSwitch] 丢掉——显式意图优先于被动复用（否则省流量的
     * 设置会在 30 分钟内被静默推翻）。
     */
    fun resolvePlayUrlBlocking(
        hash: String,
        quality: String,
        intentSource: IntentSource = IntentSource.WIFI_PREF
    ): String {
        val lower = hash.lowercase()
        val key = "$lower:$quality"
        urlCache[key]?.takeIf { isFreshUrl(it) }
            ?.let {
                // 命中缓存上报的是缓存项自带的实际档位（可能为未知），绝不拿键当事实
                rememberResolvedQuality(hash, quality, intentSource, it.stamped)
                QualityTrace.log(
                    "RESOLVE", "path" to "cache_hit", "hash" to lower,
                    "intent" to quality, "stamped" to it.stamped
                )
                return it.url
            }
        failedCache[key]?.takeIf { System.currentTimeMillis() - it.failedAt < FAIL_CACHE_TTL_MS }
            ?.let { failed ->
                // 负缓存命中，但本会话仍有可用的已解析音源时不抛——切音质永不把正在播的歌变成跳歌
                val lastGood = findHighestCachedUrl(hash)
                if (lastGood != null) {
                    rememberResolvedQuality(hash, quality, intentSource, lastGood.stamped)
                    QualityTrace.log(
                        "RESOLVE", "path" to "failcache_salvage", "hash" to lower,
                        "intent" to quality, "stamped" to lastGood.stamped
                    )
                    return lastGood.url
                }
                throw KugouPlayBlockedException(failed.reason, "负缓存命中 hash=$hash: ${failed.reason}")
            }
        // 「已缓存更高音源优先」：以缓存项的**实际**档位判定，而不是拿键猜
        findHighestCachedUrl(hash)?.takeIf {
            it.stamped != null && KugouQuality.rank(it.stamped) > KugouQuality.rank(quality)
        }?.let { cached ->
            rememberResolvedQuality(hash, quality, intentSource, cached.stamped)
            QualityTrace.log(
                "RESOLVE", "path" to "sticky_higher", "hash" to lower,
                "intent" to quality, "stamped" to cached.stamped
            )
            return cached.url
        }
        val resolveStartedAt = System.currentTimeMillis()
        // 整链全局截止：降档链(≤5档) × 每档重试 × 单次 OkHttp readTimeout(15s) 的乘积
        // 在弱网下可达分钟级（真机实测 92s），整段时间 Loader 线程阻塞、播放器停在 BUFFERING。
        // 超时不算"曲目被拒"也不算"取消"：在 runBlocking 内捕获落回 Result.failure，
        // 由下方 lastGood 兜底接管（本会话解析成功过则续播，从未成功则走跳歌，与解析失败同义）。
        val result = runBlocking {
            try {
                kotlinx.coroutines.withTimeout(RESOLVE_TOTAL_TIMEOUT_MS) {
                    resolvePlayUrlWithFallback(hash, quality)
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                QualityTrace.log(
                    "RESOLVE", "path" to "timeout", "hash" to lower,
                    "cost" to (System.currentTimeMillis() - resolveStartedAt)
                )
                Result.failure(e)
            }
        }
        if (result.isFailure) {
            // 切歌取消（ExoPlayer 中断 Loader 线程）不是解析失败的证据：原样抛回，
            // 不写负缓存——写进去会让 30s 内快速切回同一首在负缓存里直接跳歌。
            // 超时（TimeoutCancellationException）不算取消，仍走下面 lastGood 兜底。
            val firstCause = result.exceptionOrNull()
            if (firstCause is InterruptedException ||
                (firstCause is kotlinx.coroutines.CancellationException &&
                        firstCause !is kotlinx.coroutines.TimeoutCancellationException)
            ) throw firstCause
            // 兜底：整链失败但本会话曾解析成功过（任一档位 URL 仍在缓存期）时，
            // 直接复用最后可用的 URL——切音质永不把"正在播的歌"变成跳歌；
            // 只有从未成功解析过的歌才抛错走 onPlayerError 跳歌路径。
            val lastGood = findHighestCachedUrl(hash)
            if (lastGood != null) {
                rememberResolvedQuality(hash, quality, intentSource, lastGood.stamped)
                QualityTrace.log(
                    "RESOLVE", "path" to "fallback_lastgood", "hash" to lower,
                    "intent" to quality, "stamped" to lastGood.stamped
                )
                return lastGood.url
            }
            val cause = result.exceptionOrNull()
            val blocked = cause as? KugouPlayBlockedException
            val userReason = blocked?.userReason ?: (cause?.message ?: "未知原因")
            failedCache[key] = FailedResolve(System.currentTimeMillis(), userReason)
            QualityTrace.log("RESOLVE", "path" to "failed", "hash" to lower, "reason" to userReason)
            throw blocked ?: IOException("在线歌曲 URL 解析失败 hash=$hash: ${cause?.message}", cause)
        }
        val resolved = result.getOrThrow()
        failedCache.remove(key)
        putCached(hash, quality, resolved)
        // 实际拿到的是更低档：这条 URL 对该真实档位同样有效，按实际档再存一份，
        // 下次请求该档时零网络且读数正确（以前只存 high 键，于是 high 键里装着 FLAC 流）。
        resolved.quality?.takeIf { it != quality }?.let { putCached(hash, it, resolved) }
        rememberResolvedQuality(hash, quality, intentSource, resolved.quality)
        // cost = 降级链总耗时（含 Rust server 按需启动）：冷启动首曲解析耗时的主要构成，
        // 也是"要不要预热 server / 提前解析"决策的唯一数据来源
        QualityTrace.log(
            "RESOLVE", "path" to "network", "hash" to lower,
            "intent" to quality, "stamped" to resolved.quality,
            "cost" to (System.currentTimeMillis() - resolveStartedAt)
        )
        return resolved.url
    }

    /** 缓存项是否仍在 TTL 内。 */
    private fun isFreshUrl(cached: CachedUrl): Boolean =
        System.currentTimeMillis() - cached.resolvedAt < URL_CACHE_TTL_MS

    /**
     * 找该 hash 仍在 TTL 内的缓存 URL，按**实际档位**（缓存值里的 stamped）取最高。
     *
     * 历史实现取的是"键档位最高"的那个，于是"high 键装 FLAC 流"的污染项会被当成
     * 高音源复用并上报 Hi-Res。stamped 为 null 的项仍可作为音源兜底，但不会声称任何档位。
     */
    private fun findHighestCachedUrl(hash: String): CachedQualityUrl? {
        val lower = hash.lowercase()
        val now = System.currentTimeMillis()
        var best: CachedQualityUrl? = null
        for (q in KugouQuality.ALL) {
            urlCache["$lower:$q"]
                ?.takeIf { now - it.resolvedAt < URL_CACHE_TTL_MS }
                ?.let { cached ->
                    val bestRank = best?.stamped?.let { KugouQuality.rank(it) } ?: -1
                    val nowRank = cached.stamped?.let { KugouQuality.rank(it) } ?: -1
                    if (best == null || nowRank > bestRank) {
                        best = CachedQualityUrl(cached.url, cached.stamped)
                    }
                }
        }
        return best
    }

    /** [stamped] 为 null 表示"这条 URL 的实际档位不知道"——可当音源兜底，不可当读数。 */
    private data class CachedQualityUrl(val url: String, val stamped: String?)

    // ---------- 音质事实（UI 显示的唯一依据） ----------

    /** 每曲最近一次解析得到的事实（意图 + 声明 + 观测 + 结论）。 */
    private val factByHash = ConcurrentHashMap<String, QualityFact>()

    /** 事实写入版本号：map 非快照状态，UI 用它订阅"解析完成"以触发重组。 */
    @Stable
    val actualQualityVersion = mutableIntStateOf(0)

    /** 正在切换中的目标档（hash → tier）。存在期间旧事实**保留**，UI 据此压暗而非清空。 */
    private val pendingByHash = ConcurrentHashMap<String, String>()

    /**
     * 每曲最近一次解析得到的事实（意图 + 声明 + 观测 + 结论）。UI 显示音质的唯一依据。
     *
     * 历史上这里只存"实际档位"一个字符串，于是"本会话内曾经到的最高档"被另存为
     * bestCachedQualityByHash 一张只升不降、不带 TTL 的表：两套记忆靠人手工同步，
     * 而"粘性表遇不上 urlCache 的 30 分钟"最终导致用户显式降档被静默推翻。
     * 现在只有事实 + urlCache 两处，粘性由 [findHighestCachedUrl] 实时推导。
     */
    fun factOf(hash: String?): QualityFact? = hash?.lowercase()?.let { factByHash[it] }

    /** 当前实际档位（未知返回 null）。兼容旧调用点，语义已收紧为"只报有证据的档位"。 */
    fun actualQualityOf(hash: String): String? = factOf(hash)?.playingTier

    fun pendingTierOf(hash: String?): String? = hash?.lowercase()?.let { pendingByHash[it] }

    fun clearPending(hash: String?) {
        if (hash != null && pendingByHash.remove(hash.lowercase()) != null) actualQualityVersion.intValue++
    }

    /**
     * 用户显式切档前的准备：作废本曲负缓存、丢掉"实际档位高于目标"的缓存项，
     * 并把旧事实标成"切换中"。
     *
     * 与被它取代的 invalidateResolvedCache 的关键差别：**不删事实、不整表清缓存**。
     * 事实被删会让徽标在解析空窗整块消失（闪一下并挤动进度时间）；缓存被全清则把
     * 唯一的兜底弹药也扔了，网络抖动时直接变成跳歌。
     *
     * 但**高于目标档的缓存必须丢**：否则 30 分钟 TTL 内，用户把流量档从无损调到
     * 标准，「已缓存更高音源优先」会一直拿无损——省流量的显式意图被静默推翻（而且烧钱）。
     * 按**实际档位**而非缓存键判删："high 键装 FLAC 流"这种项在目标为无损时应当留下。
     */
    fun prepareForExplicitSwitch(hash: String, targetTier: String) {
        val lower = hash.lowercase()
        val targetRank = KugouQuality.rank(targetTier)
        KugouQuality.ALL.forEach { q ->
            failedCache.remove("$lower:$q")
            val cached = urlCache["$lower:$q"]
            val stampedRank = cached?.stamped?.let { KugouQuality.rank(it) }
            if (cached != null && stampedRank != null && stampedRank > targetRank) {
                urlCache.remove("$lower:$q")
            }
        }
        pendingByHash[lower] = targetTier
        actualQualityVersion.intValue++
        QualityTrace.log("SWITCH_PREPARE", "hash" to lower, "target" to targetTier)
    }

    /** 探测（不动播放现场）：成功返回事实，失败返回 null 并标记 FAILED。 */
    fun probeForSwitch(
        hash: String,
        decision: QualityIntentResolver.Decision
    ): QualityFact? {
        try {
            resolvePlayUrlBlocking(hash, decision.tier, decision.source)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 取消不是"切换失败"：不能拿它把用户刚选的那一档回滚掉
            throw e
        } catch (e: Exception) {
            QualityTrace.log(
                "PROBE_FAIL", "hash" to hash, "target" to decision.tier, "error" to e.message
            )
            markSwitchFailed(hash, decision)
            return null
        }
        return factOf(hash) ?: run {
            markSwitchFailed(hash, decision)
            null
        }
    }

    /** 切换失败：结论记 FAILED，但**保留原实际档位**（现场未动，UI 要能说"仍按 X 播放"）。 */
    fun markSwitchFailed(hash: String, decision: QualityIntentResolver.Decision) {
        val lower = hash.lowercase()
        pendingByHash.remove(lower)
        val previous = factByHash[lower]
        factByHash[lower] = previous?.copy(
            verdict = QualityVerdict.FAILED,
            resolvedAt = System.currentTimeMillis()
        ) ?: QualityFact.of(decision.tier, decision.source, null, null, null)
            .copy(verdict = QualityVerdict.UNKNOWN)
        actualQualityVersion.intValue++
        QualityTrace.log("SWITCH_FAILED", "hash" to lower, "target" to decision.tier)
    }

    /**
     * 记录解码器观测档位（交叉校验）。
     *
     * 在线流的 bitrate 实测常为 -1（FLAC 头不声明码率），拿它推翻服务端回写会造成大量
     * 假"未知"，所以在线只记指标、不改结论；观测真正用作读数来源的是本地文件。
     */
    fun recordObservedTier(hash: String?, observedTier: String?) {
        if (hash == null || observedTier == null) return
        val fact = factOf(hash) ?: return
        if (fact.stampedTier != null && fact.stampedTier != observedTier) {
            QualityTrace.declaredVsObservedMismatch++
            QualityTrace.log(
                "MISMATCH", "hash" to hash.lowercase(),
                "declared" to fact.stampedTier, "observed" to observedTier
            )
        }
        factByHash[hash.lowercase()] = fact.copy(observedTier = observedTier)
    }

    /**
     * 记录一次解析结果：写入事实（含结论）并通知 UI 重组。
     * [stampedTier] 必须是服务端回写值或缓存项携带值，**不得传请求档位**（断言 A1）。
     */
    private fun rememberResolvedQuality(
        hash: String,
        intentTier: String,
        intentSource: IntentSource,
        stampedTier: String?
    ) {
        val lower = hash.lowercase()
        val previous = factByHash[lower]
        val fact = QualityFact.of(
            intentTier = intentTier,
            intentSource = intentSource,
            stampedTier = KugouQuality.parse(stampedTier),
            stampedRaw = stampedTier,
            observedTier = previous?.observedTier
        )
        factByHash[lower] = fact
        if (pendingByHash[lower] == intentTier) pendingByHash.remove(lower)
        actualQualityVersion.intValue++
        // 能力表回写：只有"服务端确实发了更低档"与"确实拿到请求档"两种结论算证据。
        // 解析失败/未回写档位不记，避免把一次网络抖动写成长期置灰
        when (fact.verdict) {
            QualityVerdict.DOWNGRADED ->
                SongQualityCapabilityStore.recordDowngrade(lower, intentTier, fact.stampedTier)

            QualityVerdict.CONFIRMED ->
                SongQualityCapabilityStore.recordAvailable(lower, fact.stampedTier)

            else -> Unit
        }
    }

    /**
     * 带自动降级的播放链接解析：按 请求档→…→标准音质 逐档请求，每档核对服务端
     * 回写的实际音质——"拿到链接"≠"拿到请求音质"（上游对不可用高档常静默发放
     * 更低档位），档位一致才立即采用；全部不匹配取实际音质最高的一档。
     * 服务端未回写档位时（[KugouResolvedPlayUrl.quality] 为 null）不参与"最高档"竞争，
     * 宁可少拿一档也不编造证据。
     * 全部失败返回最后一次错误（保留 [KugouPlayBlockedException] 语义供跳歌与负缓存）。
     */
    private suspend fun resolvePlayUrlWithFallback(hash: String, requested: String): Result<KugouResolvedPlayUrl> {
        var best: KugouResolvedPlayUrl? = null
        var lastFailure: Throwable? = null
        // 链上每一档的"请求 → 实发"全部收集。降级过程本身就是在逐档试探，一趟下来
        // 已经问过哪些档、哪些档给不出货全部知道了；只记最终采用那一档就会把它
        // 丢光，面板因此只能"用户点一档、才知道一档"（现象：Hi-Res 置灰后无损仍可点，
        // 点下去又静默退回 320K）。
        val probes = mutableListOf<Pair<String, String?>>()
        for (q in KugouQuality.downgradeChain(requested)) {
            resolvePlayUrl(hash, q).fold(
                onSuccess = { resolved ->
                    probes += q to resolved.quality
                    if (resolved.quality == q) {
                        SongQualityCapabilityStore.recordProbeChain(hash, probes)
                        return Result.success(resolved)
                    }
                    val candidateRank = resolved.quality?.let { KugouQuality.rank(it) }
                    val bestRank = best?.quality?.let { KugouQuality.rank(it) }
                    // 无档位证据的链接（candidateRank == null）只在"还没有任何候选"时被采用，
                    // 不挤掉已知档位的候选：宁可少拿一档，也不编造证据
                    if (best == null ||
                        (candidateRank != null && (bestRank == null || candidateRank > bestRank))
                    ) {
                        best = resolved
                    }
                },
                onFailure = { lastFailure = it }
            )
        }
        best?.let {
            SongQualityCapabilityStore.recordProbeChain(hash, probes)
            return Result.success(it)
        }
        // 全程失败：不产生任何能力结论（失败 != 拿不到这一档，可能只是网络）
        return Result.failure(lastFailure ?: IOException("所有音质档位均解析失败 hash=$hash"))
    }

    /** 正在做阶梯探测的 hash：同一首不并发重复问（切歌与打开面板可能同时触发）。 */
    private val ladderProbing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** 本会话内"问过但服务端没回写档位"的档：不再重试，也不据此置灰。 */
    private val ladderAskedBlind = java.util.concurrent.ConcurrentHashMap<String, MutableSet<String>>()

    /**
     * 播放即探测：把这首歌的档位阶梯从最高档往下问一轮，结论写进能力表。
     *
     * 为什么值得主动问：能力表只记"解析实际用过的那一档"时，面板只能让用户点一档
     * 才知道一档（现象：封顶 320 的歌里无损仍可点，点下去静默退回 320）。而阶梯是单调
     * 的：一次"请求 high 实得 flac"就同时定了 high 拿不到与其余四档拿得到——成本通常
     * 是一个请求（实测 65~158ms），不是四个。
     *
     * 边界：已有结论的档不重复耗请求；解析失败直接放弃整轮（失败不代表拿不到，不能
     * 拿它置灰）；服务端没回写档位时只记住"这档问过"，本会话不再重试。
     */
    suspend fun probeAllQualities(hash: String) {
        val lower = hash.lowercase()
        if (!ladderProbing.add(lower)) return
        try {
            val askedBlind = ladderAskedBlind.getOrPut(lower) { mutableSetOf() }
            // 无论有没有事要做都记一行：没这行就无法证明"切歌挂钩真的 fire 了"——
            // 档位已全部已知时下面一个请求也不会发，日志里什么都不留。
            QualityTrace.log(
                "LADDER", "hash" to lower,
                "need" to SongQualityCapabilityStore.tiersNeedingProbe(lower).toString()
            )
            for (tier in KugouQuality.ALL.reversed()) {
                if (tier !in SongQualityCapabilityStore.tiersNeedingProbe(lower)) continue
                synchronized(askedBlind) { if (tier in askedBlind) continue }
                val before = SongQualityCapabilityStore.provenUnavailableOf(lower)
                // 失败就整轮收尾：网络不可达时继续问只会拿到更多无结论的失败
                val resolved = resolvePlayUrl(lower, tier).getOrNull() ?: return
                SongQualityCapabilityStore.recordProbeChain(lower, listOf(tier to resolved.quality))
                val stillBlind = SongQualityCapabilityStore.provenUnavailableOf(lower) == before &&
                        tier in SongQualityCapabilityStore.tiersNeedingProbe(lower)
                if (stillBlind) synchronized(askedBlind) { askedBlind.add(tier) }
                QualityTrace.log(
                    "LADDER_ASK", "hash" to lower, "asked" to tier, "stamped" to resolved.quality,
                    "stillNeed" to SongQualityCapabilityStore.tiersNeedingProbe(lower).toString()
                )
            }
        } finally {
            ladderProbing.remove(lower)
        }
    }

    /**
     * 解析真实播放 URL。对应 Rust GET /song/url?hash&quality。
     * 实测：url 字段可能是字符串或数组（兼容 play_url）；非 2xx 视为失败。
     * data.quality 为服务端回写的实际音质（按码率反推）；旧版后端缺该字段时按请求档处理。
     */
    suspend fun resolvePlayUrl(hash: String, quality: String): Result<KugouResolvedPlayUrl> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().getSongUrl(hash, quality).fold(
                    onSuccess = { json ->
                        val data = json.optJSONObject("data") ?: json
                        var url = ""
                        when (val raw = data.opt("url") ?: data.opt("play_url")) {
                            is String -> url = raw
                            is org.json.JSONArray -> if (raw.length() > 0) url = raw.optString(0, "")
                        }
                        if (url.isNotEmpty()) {
                            // 只认服务端回写的值；缺失/空/不可识别一律 null（未知），不默认等于请求档
                            val stampedRaw = data.optString("quality", "").ifEmpty { null }
                            Result.success(KugouResolvedPlayUrl(url, KugouQuality.parse(stampedRaw)))
                        } else {
                            // 完整失败原因（证据级）：status 优先取顶层（实测拒播响应无 data 包裹），
                            // 其余字段双位置兼容；全量记录便于排查
                            val status = data.optInt("status", json.optInt("status", -1))
                            val errCode = json.optInt("error_code", data.optInt("error_code", 0))
                            val privStatus = data.optInt("priv_status", json.optInt("priv_status", -1))
                            val trans = data.optJSONObject("trans_param")
                            val payBlockTpl = trans?.optInt("pay_block_tpl", 0) ?: 0
                            val cpyMap = trans?.optString("cpy_map", "0")?.toIntOrNull() ?: 0
                            val detail =
                                "status=$status priv_status=$privStatus pay_block_tpl=$payBlockTpl cpy_map=$cpyMap err_code=$errCode"
                            Log.w(TAG, "播放 URL 解析被拒 hash=$hash quality=$quality $detail")
                            // 用户可见原因：依据真实字段判定，不把所有拒播都当作 VIP 限制
                            val reason = when {
                                status == 3 && cpyMap != 0 && payBlockTpl == 1 ->
                                    "暂无可用音源（版权与付费限制）"
                                status == 3 && cpyMap != 0 ->
                                    "暂无可用音源（版权限制）"
                                status == 3 && payBlockTpl == 1 ->
                                    "暂无可用音源（付费或权限限制）"
                                status == 3 ->
                                    "暂无可用音源（上游限制）"
                                else -> "未返回播放 URL (status=$status)"
                            }
                            Result.failure(KugouPlayBlockedException(reason, detail))
                        }
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消不是解析失败：吞掉它会把"切歌取消"伪装成网络失败，
                // 在 resolvePlayUrlBlocking 里污染 30s 负缓存（快速切回同一首直接跳歌）
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "resolvePlayUrl exception", e)
                Result.failure(e)
            }
        }

    /** 搜索结果 → [YosMediaItem]（字段对齐 MusicLibrary.kt 定义；uri 为在线流地址，thumb 为封面 URL）。 */
    fun toMediaItem(song: KugouSearchSong, playUrl: String): YosMediaItem = YosMediaItem(
        uri = Uri.parse(playUrl),
        mediaId = "kugou-online-${song.hash.lowercase()}",
        mimeType = "audio/mpeg",
        title = song.name,
        writer = null,
        compilation = null,
        composer = null,
        artists = song.author,
        album = null,
        albumArtists = null,
        thumb = song.artworkUrl?.let { Uri.parse(it) },
        trackNumber = null,
        discNumber = null,
        genre = null,
        recordingDay = null,
        recordingMonth = null,
        recordingYear = null,
        releaseYear = null,
        artistId = null,
        albumId = null,
        genreId = null,
        author = song.author,
        addDate = null,
        duration = song.duration,
        modifiedDate = null,
        cdTrackNumber = null
    )

    // ---------- 第三阶段：在线歌词 ----------

    /**
     * 获取在线歌词。复用 POC 已真机验证的两步链（KugouApiService.getLyric）：
     *   GET /search/lyric?hash → candidates[0].id/accesskey
     *   GET /lyric?id&accesskey&fmt=lrc|krc&decode=true → data.decodeContent
     * LRC 为必备回退；KRC 逐字数据失败/缺失时为 null，不影响 LRC 与播放。
     */
    suspend fun getLyrics(songHash: String): Result<KugouLyrics> = withContext(Dispatchers.IO) {
        try {
            val api = KugouApiService.getInstance()
            val lrcResponse = api.getLyric(songHash, "lrc").fold(
                onSuccess = { parseKugouLyricResponse(it, krcResponse = false) },
                onFailure = { e -> return@withContext Result.failure<KugouLyrics>(e) }
            )
            val lrc = lrcResponse.lrc.orEmpty()
            if (lrc.isBlank()) {
                return@withContext Result.failure(IllegalStateException("歌词响应无 decodeContent"))
            }
            val krcResponse = api.getLyric(songHash, "krc").getOrNull()
                ?.let { parseKugouLyricResponse(it, krcResponse = true) }
            Result.success(
                KugouLyrics(
                    lrc = lrc,
                    krc = krcResponse?.krc,
                    translation = lrcResponse.translation ?: krcResponse?.translation
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "getLyrics exception", e)
            Result.failure(e)
        }
    }

    // ---------- 第二阶段：我的在线歌单 ----------

    /**
     * 获取当前登录账号的歌单列表。对应 Rust GET /user/playlist（KugouApiService.getMyPlaylists）。
     * 实测返回 data.info[]，字段：name/count/pic/listid/global_collection_id。
     * 封面 imgurl/pic 含 {size} 占位符，替换为 400。
     */
    suspend fun getMyPlaylists(): Result<List<KugouPlaylist>> = withContext(Dispatchers.IO) {
        try {
            if (!KugouApiService.isLoggedIn()) {
                return@withContext Result.failure<List<KugouPlaylist>>(
                    IllegalStateException("未登录酷狗账号")
                )
            }
            KugouApiService.getInstance().getMyPlaylists().fold(
                onSuccess = { json ->
                    val data = json.optJSONObject("data") ?: json
                    val arr = data.optJSONArray("info")
                        ?: data.optJSONArray("list")
                        ?: data.optJSONArray("special_list")
                    if (arr == null) {
                        return@fold Result.failure<List<KugouPlaylist>>(
                            IllegalStateException("歌单响应缺少列表字段")
                        )
                    }
                    val playlists = (0 until arr.length()).mapNotNull { i ->
                        runCatching {
                            val p = arr.getJSONObject(i)
                            // 封面取链对齐 Dart KugouPlaylistBrief：sizable_cover/imgurl/img/pic/cover_url/cover；
                            // 实测部分歌单（如默认“我喜欢”）这些字段均为空，
                            // 由 resolvePlaylistCover 通过 /playlist/detail 兜底
                            val rawCover = p.optString("sizable_cover", "")
                                .ifEmpty { p.optString("imgurl", "") }
                                .ifEmpty { p.optString("img", "") }
                                .ifEmpty { p.optString("pic", "") }
                                .ifEmpty { p.optString("cover_url", "") }
                                .ifEmpty { p.optString("cover", "") }
                            KugouPlaylist(
                                listid = p.optString("listid", ""),
                                gid = p.optString("global_collection_id", ""),
                                name = p.optString("name", p.optString("specialname", "")),
                                coverUrl = resolveArtworkUrl(rawCover) ?: "",
                                songCount = p.optInt("count", p.optInt("songcount", 0)),
                                // 真机实测：1=默认收藏，2=系统「我喜欢」（收藏歌单定位标识，不按名称）
                                isDef = p.optInt("is_def", 0)
                            )
                        }.getOrNull()
                    }.filter { it.listid.isNotEmpty() || it.gid.isNotEmpty() }
                    Result.success(playlists)
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getMyPlaylists exception", e)
            Result.failure(e)
        }
    }

    /**
     * 新建自建歌单。GET /playlist/add（name/type=0/source=1）。
     * 成功判定：业务包 status==1 且 errcode==0；否则取 error 字段或原样透出。
     * 返回乐观占位行：listid 尽力从响应解析（data.id/listid/gid），解析不到为空串
     * （酷狗 cloudlist 有秒级传播延迟，列表以 MMKV 挂起操作 + 延迟对账收敛）。
     */
    suspend fun createMyPlaylist(name: String, isPrivate: Boolean = false): Result<KugouPlaylist> = withContext(Dispatchers.IO) {
        try {
            if (!KugouApiService.isLoggedIn()) {
                return@withContext Result.failure(IllegalStateException("未登录酷狗账号"))
            }
            KugouApiService.getInstance().createPlaylist(name, if (isPrivate) 1 else 0).fold(
                onSuccess = { json ->
                    val status = json.optInt("status", 1)
                    val errcode = json.optInt("errcode", 0)
                    if (status == 1 && errcode == 0) {
                        val data = json.optJSONObject("data")
                        val newId = data?.optString("id")?.takeIf { it.isNotEmpty() && it != "null" }
                            ?: data?.optString("listid")?.takeIf { it.isNotEmpty() && it != "null" }
                            ?: data?.optString("gid")?.takeIf { it.isNotEmpty() && it != "null" }
                            ?: ""
                        Result.success(
                            KugouPlaylist(listid = newId, gid = "", name = name, coverUrl = "", songCount = 0, isDef = 0)
                        )
                    } else {
                        val reason = json.optString("error").ifEmpty { "status=$status errcode=$errcode" }
                        Result.failure(IOException("创建歌单失败：$reason"))
                    }
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "createMyPlaylist exception", e)
            Result.failure(e)
        }
    }

    /**
     * 删除自建歌单。GET /playlist/del?type=0（对齐上游 playlist_page.dart:788）。
     * 守卫：is_def != 0 的系统歌单（1=默认收藏、2=「我喜欢」）拒绝删除。
     */
    suspend fun deleteMyPlaylist(playlist: KugouPlaylist): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!KugouApiService.isLoggedIn()) {
                return@withContext Result.failure(IllegalStateException("未登录酷狗账号"))
            }
            if (playlist.isDef != 0) {
                return@withContext Result.failure(IllegalArgumentException("系统歌单不允许删除"))
            }
            val listid = playlist.listid.ifEmpty { playlist.gid }
            if (listid.isEmpty()) {
                return@withContext Result.failure(IllegalArgumentException("歌单缺少 listid"))
            }
            KugouApiService.getInstance().deletePlaylist(listid, type = 0).fold(
                onSuccess = { json ->
                    val status = json.optInt("status", 1)
                    val errcode = json.optInt("errcode", 0)
                    if (status == 1 && errcode == 0) {
                        Result.success(Unit)
                    } else {
                        val reason = json.optString("error").ifEmpty { "status=$status errcode=$errcode" }
                        Result.failure(IOException("删除歌单失败：$reason"))
                    }
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "deleteMyPlaylist exception", e)
            Result.failure(e)
        }
    }

    /**
     * 歌单封面兜底（情况 B）：/user/playlist 的 pic/imgurl 为空时，
     * 通过 /playlist/detail?ids=<gid> 取封面（对齐 Dart getPlaylistDetail + KugouPlaylistBrief 取链）。
     * 接口也未返回封面时返回 null（情况 C，UI 用默认占位图，不伪造 URL）。
     */
    suspend fun resolvePlaylistCover(playlist: KugouPlaylist): String? = withContext(Dispatchers.IO) {
        try {
            val gid = playlist.gid.ifEmpty { playlist.listid }
            if (gid.isEmpty()) return@withContext null
            KugouApiService.getInstance().getPlaylistDetail(gid).getOrNull()?.let { json ->
                // 实测 data 可能是数组或含 info 数组的对象，双形态兼容
                val dataAny = json.opt("data")
                val item = when (dataAny) {
                    is org.json.JSONArray -> dataAny.optJSONObject(0)
                    is JSONObject -> dataAny.optJSONArray("info")?.optJSONObject(0) ?: dataAny
                    else -> null
                }
                val raw = item?.optString("sizable_cover", "")
                    ?.ifEmpty { item.optString("imgurl", "") }
                    ?.ifEmpty { item.optString("img", "") }
                    ?.ifEmpty { item.optString("pic", "") }
                    ?.ifEmpty { item.optString("cover_url", "") }
                    ?.ifEmpty { item.optString("cover", "") }
                    ?.ifEmpty {
                        item.optJSONObject("trans_param")?.optString("union_cover", "") ?: ""
                    }
                resolveArtworkUrl(raw)
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolvePlaylistCover exception", e)
            null
        }
    }

    /**
     * 获取歌单内全量歌曲（分页拉取）。对应 Rust GET /playlist/track/all/new
     * （KugouApiService.getPlaylistSongsByListid）。接口实测 pagesize 默认 30、
     * 响应带 data.count 总数；单页请求会导致百首歌单只入队前 30 首，
     * 故按页拉全（pagesize=200，对齐 Dart playlist_page.dart 全量策略），
     * 上限 9999 首防异常响应无限翻页。
     * 顺序：普通歌单服务端即存储顺序，原样返回；系统「我喜欢」（is_def==2）
     * 服务端返回新收藏在最前，此处反转成收藏先后顺序（对齐 MD3 playlist_page
     * 的显示语义），仅命中该列表，不影响其他歌单。
     * 实测字段：timelen 单位毫秒；歌手在 singerinfo[].name；
     * name 可能为"歌手 - 歌名.mp3"形式，展示前去除扩展名与歌手前缀。
     */
    suspend fun getPlaylistSongs(
        listid: String,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): Result<List<KugouPlaylistTrack>> =
        withContext(Dispatchers.IO) {
            try {
                val api = KugouApiService.getInstance()
                val pageSize = 200
                val maxSongs = 9999
                val all = mutableListOf<KugouPlaylistTrack>()
                var page = 1
                while (all.size < maxSongs) {
                    val json = api.getPlaylistSongsByListid(listid, page, pageSize)
                        .getOrElse { return@withContext Result.failure<List<KugouPlaylistTrack>>(it) }
                    val data = json.optJSONObject("data") ?: json
                    val arr = data.optJSONArray("info")
                        ?: data.optJSONArray("songs")
                        ?: data.optJSONArray("list")
                    if (arr == null) {
                        if (page == 1) {
                            // 空歌单：业务成功但响应没有歌曲列表字段（实测新建空歌单即此形态）
                            // → 视为空列表而非解析失败；业务失败（status/errcode 异常）才报错
                            val statusOk = json.optInt("status", 1) == 1 && json.optInt("errcode", 0) == 0
                            if (statusOk) {
                                return@withContext Result.success(emptyList())
                            }
                            return@withContext Result.failure<List<KugouPlaylistTrack>>(
                                IllegalStateException("歌单歌曲响应缺少列表字段")
                            )
                        }
                        break
                    }
                    val batch = parsePlaylistTracks(arr)
                    all.addAll(batch)
                    // 末页判断：返回不足一页，或已达接口声明的总数
                    val total = data.optInt("count", -1)
                    onProgress?.invoke(all.size, total)
                    if (batch.size < pageSize || (total in 0..maxSongs && all.size >= total)) break
                    page++
                }
                // 仅当请求的就是系统「我喜欢」时反转（listid/gid 与 findMyFavoritePlaylist
                // 同源归一化，PlaylistSelection.id 也是 listid.ifEmpty { gid }，可直接比对）。
                // findMyFavoritePlaylist 失败返回 null，不阻断正常加载。
                val favoriteListid = runCatching {
                    findMyFavoritePlaylist()?.let { it.listid.ifEmpty { it.gid } }
                }.getOrNull()
                val ordered = if (favoriteListid != null && favoriteListid == listid) {
                    all.asReversed()
                } else {
                    all
                }
                Result.success(ordered.take(maxSongs))
            } catch (e: Exception) {
                Log.e(TAG, "getPlaylistSongs exception", e)
                Result.failure(e)
            }
        }

    /**
     * 歌手搜索（激活 /search/artist 死代码；对齐 Dart searchArtists）。
     * 实测 data 直接为数组（兼容 {info:[...]} 包裹形态），头像字段多变走宽容回退。
     */
    suspend fun searchArtists(keyword: String): Result<List<KugouArtistBrief>> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().searchArtists(keyword).fold(
                onSuccess = { json ->
                    val data = json.opt("data")
                    val arr = when (data) {
                        is JSONArray -> data
                        is JSONObject -> data.optJSONArray("info") ?: data.optJSONArray("artists")
                        else -> null
                    } ?: return@fold Result.failure<List<KugouArtistBrief>>(
                        IllegalStateException("歌手搜索响应缺少列表")
                    )
                    val artists = (0 until arr.length()).mapNotNull { i ->
                        runCatching {
                            val a = arr.getJSONObject(i)
                            val id = a.optString("singerid", a.optString("artist_id", ""))
                                .ifEmpty { a.optString("AuthorID", a.optString("author_id", "")) }
                            val name = a.optString("singername", a.optString("author_name", ""))
                                .ifEmpty { a.optString("name", "") }
                            if (id.isEmpty() || name.isEmpty()) return@runCatching null
                            KugouArtistBrief(
                                singerId = id,
                                name = name,
                                avatarUrl = resolveArtworkUrl(
                                    a.optString("sizable_avatar")
                                        .ifEmpty { a.optString("imgurl") }
                                        .ifEmpty { a.optString("avatar") }
                                        .ifEmpty { a.optString("img") }
                                        .ifEmpty { a.optString("pic") }
                                )
                            )
                        }.getOrNull()
                    }
                    Result.success(artists)
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "searchArtists exception", e)
            Result.failure(e)
        }
    }

    /**
     * 歌手详情（对齐 Dart getArtistDetail / KugouArtistDetail.fromJson）。
     * GET /artist/detail?id= → data 数组取第一个；关注态仅登录时有值。
     */
    suspend fun getArtistDetail(artistId: String): Result<KugouArtistDetailData> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().getArtistDetail(artistId).fold(
                onSuccess = { json ->
                    val item = json.optJSONArray("data")?.optJSONObject(0)
                        ?: json.optJSONObject("data")?.let { d ->
                            d.optJSONArray("info")?.optJSONObject(0) ?: d
                        }
                        ?: return@fold Result.failure<KugouArtistDetailData>(
                            IllegalStateException("歌手详情响应为空")
                        )
                    val id = item.optString("singerid", item.optString("artist_id", ""))
                        .ifEmpty { item.optString("AuthorID", item.optString("author_id", artistId)) }
                        .ifEmpty { artistId }
                    var name = item.optString("singername").ifEmpty { item.optString("author_name") }
                        .ifEmpty { item.optString("name") }
                    if (name.isEmpty()) {
                        val si = item.optJSONArray("singerinfo")
                        if (si != null && si.length() > 0) {
                            name = si.optJSONObject(0)?.optString("name") ?: ""
                        }
                    }
                    val isFollowed = sequenceOf(
                        item.optInt("is_follow", -1),
                        item.optInt("isfollow", -1),
                        item.optInt("followed", -1)
                    ).firstOrNull { it >= 0 } == 1
                    Result.success(
                        KugouArtistDetailData(
                            artistId = id,
                            name = name,
                            avatarUrl = resolveArtworkUrl(
                                item.optString("sizable_avatar")
                                    .ifEmpty { item.optString("imgurl") }
                                    .ifEmpty { item.optString("img") }
                                    .ifEmpty { item.optString("pic") }
                                    .ifEmpty { item.optString("avatar_url") }
                            ),
                            intro = sequenceOf("intro", "description", "desc")
                                .map { item.optString(it) }
                                .firstOrNull { it.isNotEmpty() },
                            songCount = item.optInt("songcount", item.optInt("song_count", 0)),
                            albumCount = item.optInt("albumcount", item.optInt("album_count", 0)),
                            isFollowed = isFollowed
                        )
                    )
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getArtistDetail exception", e)
            Result.failure(e)
        }
    }

    /**
     * 歌手歌曲单页。rawCount 取解析前数组长度，缺少列表字段返回独立失败。
     * sort="hot" 对齐上游热度排序；默认排序不表示热门。
     */
    suspend fun getArtistAudioPage(
        artistId: String,
        page: Int = 1,
        pageSize: Int = 30,
        sort: String = ""
    ): Result<ArtistAudioPage> = withContext(Dispatchers.IO) {
        try {
            KugouApiService.getInstance().getArtistAudios(artistId, page, pageSize, sort).fold(
                onSuccess = { json ->
                    val array = extractSongArray(json)
                        ?: return@fold Result.failure<ArtistAudioPage>(
                            IllegalStateException("艺人歌曲响应缺少列表")
                        )
                    val songs = (0 until array.length()).mapNotNull { index ->
                        runCatching { parseKmrSongItem(array.getJSONObject(index)) }.getOrNull()
                    }
                    Result.success(ArtistAudioPage(songs = songs, rawCount = array.length()))
                },
                onFailure = { e -> Result.failure(e) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "getArtistAudioPage exception", e)
            Result.failure(e)
        }
    }

    /** 兼容列表调用；分页结束判断请使用 [getArtistAudioPage] 的 rawCount。 */
    suspend fun getArtistAudios(
        artistId: String,
        page: Int = 1,
        pageSize: Int = 30,
        sort: String = ""
    ): Result<List<KugouNewSong>> = getArtistAudioPage(artistId, page, pageSize, sort).map { it.songs }

    /** 关注歌手。需登录（token/userid 经 authHeader() cookie 带入）。 */
    suspend fun followArtist(artistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        KugouApiService.getInstance().followArtist(artistId).map { }
    }

    /** 取关歌手。需登录（token/userid 经 authHeader() cookie 带入）。 */
    suspend fun unfollowArtist(artistId: String): Result<Unit> = withContext(Dispatchers.IO) {
        KugouApiService.getInstance().unfollowArtist(artistId).map { }
    }

    /**
     * 精选歌单推荐（Discovery「精选歌单」横向 Section）。对齐 Dart getPlaylist → /top/playlist。
     * 实测 data.special_list[]：specialid(int)/global_collection_id(string)/specialname/
     * imgurl/flexible_cover（封面，含 {size}）/songcount（部分缺失）。内嵌 songs 仅是预览，不作为全量。
     */
    suspend fun getRecommendPlaylists(page: Int = 1): Result<List<KugouRecommendPlaylist>> =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().getTopPlaylist(page).fold(
                    onSuccess = { json ->
                        Result.success(::parseRecommendPlaylists.invoke(json, ::resolveArtworkUrl))
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "getRecommendPlaylists exception", e)
                Result.failure(e)
            }
        }

    private val recommendSongCountCache = ConcurrentHashMap<String, Int>()

/** Extract a detail total without using the returned preview song array. */
internal fun parseRecommendPlaylistSongCount(fields: Map<String, Any?>): Int? =
    parseOptionalInt(fields, "count")

private fun parseRecommendPlaylistSongCount(json: JSONObject): Int? {
    val data = json.optJSONObject("data") ?: json
    return parseRecommendPlaylistSongCount(data.toFieldMap())
}

/** 获取精选歌单全量歌曲数量；只读取详情响应中的 data.count。 */
suspend fun getRecommendPlaylistSongCount(globalCollectionId: String): Result<Int> =

        withContext(Dispatchers.IO) {
            if (globalCollectionId.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("globalCollectionId is blank"))
            }
            recommendSongCountCache[globalCollectionId]?.let { return@withContext Result.success(it) }
            try {
                KugouApiService.getInstance().getPlaylistSongsByGcid(
                    globalCollectionId = globalCollectionId,
                    page = 1,
                    pageSize = 1
                ).fold(
                    onSuccess = { json ->
                        val count = parseRecommendPlaylistSongCount(json)
                            ?: return@fold Result.failure<Int>(
                                IllegalStateException("精选歌单详情响应缺少歌曲总数")
                            )
                        recommendSongCountCache[globalCollectionId] = count
                        Result.success(count)
                    },
                    onFailure = { e -> Result.failure(e) }
                )
            } catch (e: Exception) {
                Log.e(TAG, "getRecommendPlaylistSongCount exception", e)
                Result.failure(e)
            }
        }

    /**
     * 精选歌单全量歌曲（分页拉取，复用 parsePlaylistTracks，不新建 track 模型）。
     * 对应 GET /playlist/track/all?global_collection_id=<gcid>（非 /playlist/track/all/new）。
     * 分页策略对齐 getPlaylistSongs：末页不足 pageSize 或累计 >= data.count 即停，无 200 首上限。
     */
    suspend fun getPlaylistSongsByGcid(
        globalCollectionId: String,
        onProgress: ((loaded: Int, total: Int) -> Unit)? = null
    ): Result<List<KugouPlaylistTrack>> =
        withContext(Dispatchers.IO) {
            try {
                val api = KugouApiService.getInstance()
                val pageSize = 30
                val maxSongs = 9999
                val all = mutableListOf<KugouPlaylistTrack>()
                var page = 1
                while (all.size < maxSongs) {
                    val json = api.getPlaylistSongsByGcid(globalCollectionId, page, pageSize)
                        .getOrElse { return@withContext Result.failure<List<KugouPlaylistTrack>>(it) }
                    val data = json.optJSONObject("data") ?: json
                    val arr = data.optJSONArray("songs")
                        ?: data.optJSONArray("info")
                        ?: data.optJSONArray("list")
                    if (arr == null) {
                        if (page == 1) {
                            return@withContext Result.failure<List<KugouPlaylistTrack>>(
                                IllegalStateException("精选歌单歌曲响应缺少列表字段")
                            )
                        }
                        break
                    }
                    val batch = parsePlaylistTracks(arr)
                    all.addAll(batch)
                    val total = data.optInt("count", -1)
                    onProgress?.invoke(all.size, total)
                    if (batch.size < pageSize || (total in 0..maxSongs && all.size >= total)) break
                    page++
                }
                Log.d(TAG, "getPlaylistSongsByGcid gcid=$globalCollectionId parsed=${all.size}")
                Result.success(all.take(maxSongs))
            } catch (e: Exception) {
                Log.e(TAG, "getPlaylistSongsByGcid exception", e)
                Result.failure(e)
            }
        }

    /** 歌单歌曲数组 → 领域模型列表（单页解析，供分页拉取与封面兜底复用）。 */
    private fun parsePlaylistTracks(arr: JSONArray): List<KugouPlaylistTrack> =
        (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val s = arr.getJSONObject(i)
                val hash = s.optString("hash", s.optString("Hash", ""))
                if (hash.isEmpty()) return@runCatching null

                // 歌手：实测无 singername 字段，在 singerinfo[].name（对齐 Dart KugouSongDetail）
                var artist = s.optString("singername", s.optString("SingerName", ""))
                if (artist.isEmpty()) {
                    val si = s.optJSONArray("singerinfo")
                    if (si != null && si.length() > 0) {
                        val names = mutableListOf<String>()
                        for (j in 0 until si.length()) {
                            val nm = si.optJSONObject(j)?.optString("name", "") ?: ""
                            if (nm.isNotEmpty()) names.add(nm)
                        }
                        artist = names.joinToString("、")
                    }
                }

                // 歌名：songname 优先；否则从"歌手 - 歌名.mp3"形式提取并去扩展名
                var name = s.optString("songname", s.optString("SongName", ""))
                if (name.isEmpty()) {
                    name = s.optString("name", "")
                    if (artist.isNotEmpty() && name.startsWith("$artist - ")) {
                        name = name.removePrefix("$artist - ")
                    }
                    name = name.substringBeforeLast('.').ifEmpty { name }
                }

                // 专辑：双写兼容，可能缺失
                val albumInfo = s.optJSONObject("albuminfo")
                val album = s.optString("album_name", s.optString("albumname",
                    albumInfo?.optString("name", "") ?: "")).takeIf { it.isNotEmpty() }

                // 收藏协议身份字段（真机实测：fileid 数字、mixsongid 数字、album_id 字符串）
                val fileId = s.optLong("fileid", 0)
                val mixsongId = s.optLong("mixsongid", 0)
                val albumIdNum = s.optString("album_id", "").toLongOrNull()
                    ?: albumInfo?.optLong("id", 0) ?: 0

                KugouPlaylistTrack(
                    hash = hash,
                    name = name,
                    artist = artist,
                    album = album,
                    durationMs = s.optLong("timelen", s.optLong("duration", 0)),
                    // 封面实测字段：trans_param.union_cover（含 {size} 占位符）
                    coverUrl = resolveArtworkUrl(
                        s.optJSONObject("trans_param")?.optString("union_cover", "")
                    ),
                    fileId = fileId,
                    mixsongId = mixsongId,
                    albumId = albumIdNum
                ).also {
                    // 顺带登记歌曲身份（收藏增删协议需要）
                    registerSongIdentity(hash, albumIdNum, mixsongId, fileId)
                }
            }.getOrNull()
        }

    /**
     * 歌单封面兜底第三级：/user/playlist 与 /playlist/detail 均无封面时，
     * 取歌单第一页歌曲中第一首有封面的歌作为歌单封面（不伪造 URL，全无则 null）。
     * 只拉首页小批量（pagesize=30），避免为一张封面拉全量曲目。
     */
    suspend fun resolvePlaylistCoverFromTracks(listid: String): String? =
        withContext(Dispatchers.IO) {
            try {
                KugouApiService.getInstance().getPlaylistSongsByListid(listid, 1, 30)
                    .getOrNull()?.let { json ->
                        val data = json.optJSONObject("data") ?: json
                        val arr = data.optJSONArray("info")
                            ?: data.optJSONArray("songs")
                            ?: data.optJSONArray("list")
                        arr?.let { parsePlaylistTracks(it) }
                    }?.firstOrNull { !it.coverUrl.isNullOrEmpty() }?.coverUrl
            } catch (e: Exception) {
                Log.e(TAG, "resolvePlaylistCoverFromTracks exception", e)
                null
            }
        }

    /** 搜索结果 → 仅展示用 [YosMediaItem]（uri 为空，复用现有 MusicList Item 视觉规范）。 */
    fun toDisplayMediaItem(song: KugouSearchSong): YosMediaItem = YosMediaItem(
        uri = null,
        mediaId = "kugou-online-${song.hash.lowercase()}",
        mimeType = "audio/mpeg",
        title = song.name,
        writer = null,
        compilation = null,
        composer = null,
        artists = song.author,
        album = null,
        albumArtists = null,
        thumb = song.artworkUrl?.let { Uri.parse(it) },
        trackNumber = null,
        discNumber = null,
        genre = null,
        recordingDay = null,
        recordingMonth = null,
        recordingYear = null,
        releaseYear = null,
        artistId = null,
        albumId = null,
        genreId = null,
        author = song.author,
        addDate = null,
        duration = song.duration,
        modifiedDate = null,
        cdTrackNumber = null
    )

    /** 歌单歌曲 → 仅展示用 [YosMediaItem]（uri 为空，复用现有 MusicList Item 视觉规范）。 */
    fun toDisplayMediaItem(track: KugouPlaylistTrack): YosMediaItem = YosMediaItem(
        uri = null,
        mediaId = "kugou-online-${track.hash.lowercase()}",
        mimeType = "audio/mpeg",
        title = track.name,
        writer = null,
        compilation = null,
        composer = null,
        artists = track.artist,
        album = track.album,
        albumArtists = null,
        thumb = track.coverUrl?.let { Uri.parse(it) },
        trackNumber = null,
        discNumber = null,
        genre = null,
        recordingDay = null,
        recordingMonth = null,
        recordingYear = null,
        releaseYear = null,
        artistId = null,
        albumId = null,
        genreId = null,
        author = track.artist,
        addDate = null,
        duration = track.durationMs,
        modifiedDate = null,
        cdTrackNumber = null
    )
}
