package yos.music.player.native

import android.util.Log
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.IOException
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouVipState

/**
 * Kotlin HTTP client wrapper around md3Music's Rust HTTP server.
 * 
 * Provides search and playback URL APIs that mirror the Dart/Flutter interface.
 */
class KugouApiService private constructor() {
    companion object {
        private const val TAG = "KugouApiService"
        @Volatile
        private var instance: KugouApiService? = null

        fun getInstance(): KugouApiService {
            return instance ?: synchronized(this) {
                instance ?: KugouApiService().apply {
                    instance = this
                    ensureAuthRestored()
                }
            }
        }

        private val authRestored = java.util.concurrent.atomic.AtomicBoolean(false)

        /**
         * 冷启动登录态恢复入口（幂等、线程安全）。
         *
         * 必须在 MMKV.initialize 之后调用；与 Rust Server 完全解耦：
         * 只读 MMKV kugou_auth，不启动 server、不调用任何酷狗 API。
         * 由 Application.onCreate 在 App 初始化阶段调用一次，保证任何页面
         * 组合前 isLoggedIn() 即可用；getInstance() 幂等兜底，防止遗漏。
         */
        fun ensureAuthRestored() {
            if (authRestored.compareAndSet(false, true)) {
                restoreAuth()
                restoreDfid()
                Log.d(TAG, "ensureAuthRestored done: loggedIn=${isLoggedIn()}")
            }
        }

        // ---------- 登录态持久化（MMKV；与 Dart kugou_api_client 的 prefs 语义一致） ----------
        private const val MMKV_UID = "kugou_auth"
        private const val K_TOKEN = "kugou_token"
        private const val K_USERID = "kugou_userid"
        private const val K_VIP = "kugou_vip_token"
        private const val K_DFID = "kugou_dfid"

        /** 从 MMKV 恢复上次登录态。POC 生命周期内 token 短暂有效，恢复后可能过期；
         *  但至少避免每次重装都重新扫码，过期时由业务侧重新登录即可。 */
        private fun restoreAuth() {
            runCatching {
                val store = MMKV.mmkvWithID(MMKV_UID)
                val tok = store.decodeString(K_TOKEN)
                val uid = store.decodeString(K_USERID)
                val vt = store.decodeString(K_VIP)
                Log.d(TAG, "restoreAuth: tok=${tok != null} uid=$uid vip=${vt != null}")
                if (tok != null && uid != null) {
                    token = tok
                    userid = uid
                    vipToken = vt?.takeIf { it.isNotEmpty() }
                    // 同步全局可观察状态（UI 唯一观察源）
                    KugouAccountState.apply(uid, vipToken != null)
                    // 按账号重载本地已签标记（kugou_youth_signed_days_<uid>）
                    KugouVipState.onAccountResolved(uid)
                    Log.d(TAG, "Login state restored: userid=$uid, hasVip=${vipToken != null}")
                } else {
                    // 无可恢复登录态，显式归零（初始态即未登录，防御性写法）
                    KugouAccountState.apply(null, false)
                    KugouVipState.onAccountResolved(null)
                    Log.d(TAG, "restoreAuth: nothing to restore (tok/uid missing)")
                }
            }.onFailure { e ->
                Log.e(TAG, "restoreAuth FAILED: $e")
            }
        }

        private fun restoreDfid() {
            runCatching {
                val store = MMKV.mmkvWithID(MMKV_UID)
                val d = store.decodeString(K_DFID)
                if (d != null) dfid = d
            }
        }

        // ---------- POC 登录态（内存态，仅本进程生命周期；敏感值不写日志、不进 UI） ----------
        @Volatile var token: String? = null; private set
        @Volatile var userid: String? = null; private set
        @Volatile var vipToken: String? = null; private set

        /** 只暴露“是否已登录”，不暴露敏感值。 */
        fun isLoggedIn(): Boolean = token != null && userid != null

        /** 掩码显示用，如 "abc123…(len=42)"。 */
        fun maskedToken(): String {
            val t = token ?: return "-"
            return if (t.length <= 6) "***" else "${t.take(6)}…(len=${t.length})"
        }

        /** 与 Dart _onRequest 一致（kugou_api_client.dart:100-107）：
         *  Authorization: <token>;<userid>;[vip_token]
         *  Rust build_query（server.rs:662-673）会把它解析进 cookie → request.rs:409-410 读取。 */
        fun authHeader(): String? {
            val t = token ?: return null
            val u = userid ?: return null
            val parts = mutableListOf("token=$t", "userid=$u")
            vipToken?.takeIf { it.isNotEmpty() }?.let { parts.add("vip_token=$it") }
            return parts.joinToString(";")
        }

        fun applyLogin(token:String, userid: String, vipToken:String) {
            this.token = token
            this.userid = userid
            this.vipToken = vipToken.takeIf { it.isNotEmpty() }
            // 持久化，避免每次重装/重启都要重新扫码
            runCatching {
                val store = MMKV.mmkvWithID(MMKV_UID)
                store.encode(K_TOKEN, token)
                store.encode(K_USERID, userid)
                if (vipToken.isNotEmpty()) store.encode(K_VIP, vipToken) else store.removeValueForKey(K_VIP)
            }
            // 同步全局可观察状态（登录页/设置页立即变为已登录）
            KugouAccountState.apply(userid, this.vipToken != null)
            // 按账号重载本地已签标记：重登同一账号立即恢复"今日已签"显示，
            // 无需等网络/重启（修复登出→重登显示"今日未签到"的 bug）
            KugouVipState.onAccountResolved(userid)
            // 敏感值不落日志
            Log.d(TAG, "Login state applied: userid=$userid, hasVip=${this.vipToken != null}")
        }

        fun clearLogin() {
            token = null; userid = null; vipToken = null
            runCatching {
                val store = MMKV.mmkvWithID(MMKV_UID)
                store.removeValuesForKeys(arrayOf(K_TOKEN, K_USERID, K_VIP))
            }
            // 同步全局可观察状态（dfid 与本地音乐/收藏数据不属于账号身份，保留不动）
            KugouAccountState.clear()
            // VIP 签到态同属账号身份：服务端派生态清空；本地已签日期按账号保留
            // （仅切回匿名命名空间，重登同一账号由 onAccountResolved 恢复）
            KugouVipState.clear()
            Log.d(TAG, "Login state cleared")
        }

        fun saveDfid(d: String) {
            dfid = d
            runCatching {
                val store = MMKV.mmkvWithID(MMKV_UID)
                store.encode(K_DFID, d)
            }
        }
        @Volatile private var dfid: String? = null
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    /** 保证 Rust server 在运行（幂等）。返回是否可用；启动瞬时失败最多重试 3 次。 */
    private suspend fun ensureServer(): Boolean = retryResult {
        try {
            if (!KugouNativeBridge.isServerRunning()) {
                val p = KugouNativeBridge.startServer(dataDir = "/data/data/yos.music.player.oss/files")
                if (p <= 0) return@retryResult Result.failure<Boolean>(IOException("Failed to start server"))
            } else if (KugouNativeBridge.getServerPort() <= 0) {
                KugouNativeBridge.startServer(dataDir = "/data/data/yos.music.player.oss/files")
            }
            if (KugouNativeBridge.getServerPort() > 0) {
                Result.success(true)
            } else {
                Result.failure(IOException("Server not running"))
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }.getOrDefault(false)

    /**
     * 通用 GET 封装：带登录态 Authorization，返回 (HTTP code, body)。
     *  注意：路径必须按 '/' 拆分后逐段 addPathSegment ——
     *  直接 addPathSegment("song/url") 会把内嵌的 '/' 编码成 %2F，导致 404。
     *  瞬时连接失败、非 2xx 或空响应最多自动重试 3 次；成功响应不重复请求。
     */
    private suspend fun httpGetWithRetry(
        path: String,
        params: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String> = retryResult {
        try {
            val response = httpGetOnce(path, params, extraHeaders)
            when {
                response.first !in 200..299 -> Result.failure(
                    IOException("HTTP ${response.first}: ${response.second.take(300)}")
                )
                response.second.isEmpty() -> Result.failure(IOException("Empty response body"))
                else -> Result.success(response)
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }.getOrElse { throw it }

    private fun httpGetOnce(
        path: String,
        params: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap()
    ): Pair<Int, String> {
        val baseUrl = KugouNativeBridge.getBaseUrl()
        val builder = baseUrl.toHttpUrl().newBuilder()
        path.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        // /login/qr/* 在登录前调用，此时 authHeader()==null，不带头，与 Dart 行为一致
        val rb = Request.Builder().url(builder.build()).get()
        authHeader()?.let { rb.addHeader("Authorization", it) }
        extraHeaders.forEach { (k, v) -> rb.header(k, v) }
        val req = rb.build()
        // 证据链要求：确认登录态确实通过 Authorization 头传给 Rust。
        // 只记录“有无”及组成，不记录 token 明文。
        val authDesc = if (req.header("Authorization") != null) {
            "present(token,userid${if (vipToken != null) ",vip_token" else ""})"
        } else "none"
        Log.d(TAG, "GET ${req.url} Authorization=$authDesc")
        val response = client.newCall(req).execute()
        val code = response.code
        val body = response.body?.string() ?: ""
        response.close()
        return code to body
    }

    /**
     * Search for songs by keyword.
     *
     * 严格对应 md3Music Rust 实际路由（kugou_api_server/rust/src/modules/search.rs）：
     *   GET /search?keywords=<kw>&page=<n>&pagesize=<n>&type=song
     * 参数是 query string（不是 JSON body），keywords 优先、keyword 回退。
     * 返回结构：{ status:1, data: { lists: [ {SongName, SingerName, FileHash, Duration, ...} ], total } }
     * （已用真机 curl 实测确认，样本存于 LunaYaba/.fetch/poc_search.json）
     */
    suspend fun searchSongs(keyword: String, page: Int = 1, pageSize: Int = 20, type: String = "song"): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) {
                    return@withContext Result.failure(Exception("Failed to start server"))
                }

                val params = mapOf(
                    "keywords" to keyword,
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString(),
                    "type" to type
                )
                val (code, body) = httpGetWithRetry("/search", params)

                Log.d(TAG, "GET /search -> HTTP $code")
                if (body.isNotEmpty()) {
                    // Logcat 单条有长度限制，分段输出保证完整可见
                    body.chunked(3500).forEachIndexed { idx, chunk ->
                        Log.d(TAG, "/search body[$idx]: $chunk")
                    }
                }

                if (code !in 200..299) {
                    return@withContext Result.failure(IOException("/search HTTP $code: ${body.take(200)}"))
                }
                if (body.isEmpty()) {
                    return@withContext Result.failure(IOException("Empty response body"))
                }

                Result.success(JSONObject(body))

            } catch (e: Exception) {
                Log.e(TAG, "Search exception", e)
                Result.failure(e)
            }
        }

    /**
     * Get playback URL for a song.
     *
     * 严格对应 Rust 实际路由（kugou_api_server/rust/src/modules/song_url.rs + mod.rs:227）：
     *   GET /song/url?hash=<FileHash>&quality=128
     * 登录态通过 Authorization 头传递（Rust server.rs:662-673 解析进 cookie，
     * request.rs:409-410 读出 token/userid，userid 参与 sign_key 计算）。
     * 返回结构（实测）：未登录时 HTTP 502 + {error_code:31833, error:"illegal key"}；
     * 成功时 data 内含 url/play_url/fileSize/bitRate 等（KugouPlayUrl 字段）。
     * 完整响应体分段写入 Logcat，保留对照证据。
     */
    suspend fun getSongUrl(hash: String, quality: String = "128"): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) {
                    return@withContext Result.failure(Exception("Server not running"))
                }

                Log.d(TAG, "Getting URL for hash=$hash quality=$quality loggedIn=${isLoggedIn()}")

                val params = mapOf("hash" to hash.lowercase(), "quality" to quality)
                val (code, body) = httpGetWithRetry("/song/url", params)

                Log.d(TAG, "/song/url -> HTTP $code, body length=${body.length}, loggedIn=${isLoggedIn()}")
                if (body.isNotEmpty()) {
                    body.chunked(3500).forEachIndexed { idx, chunk ->
                        Log.d(TAG, "/song/url body[$idx]: $chunk")
                    }
                }

                if (code !in 200..299) {
                    return@withContext Result.failure(IOException("/song/url HTTP $code: ${body.take(300)}"))
                }
                if (body.isEmpty()) {
                    return@withContext Result.failure(IOException("Empty response body"))
                }

                Result.success(JSONObject(body))

            } catch (e: Exception) {
                Log.e(TAG, "Get URL exception", e)
                Result.failure(e)
            }
        }

    // ---------- QR 登录（复用 md3Music Rust 现有路由，不重新实现协议） ----------
    // 链路（与 Dart kugou_api_client.dart:2180-2228 / kugou_provider.dart:911-953 一致）：
    //   GET /login/qr/key              -> data.qrcode (key)
    //   GET /login/qr/create?key&qrimg -> data.base64 (PNG data URL)
    //   轮询 GET /login/qr/check?key   -> data.status==4 时 token/userid/vip_token

    suspend fun loginQrKey(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/login/qr/key", mapOf("timestamp" to System.currentTimeMillis().toString()))
            Log.d(TAG, "/login/qr/key -> HTTP $code")
            if (code !in 200..299) return@withContext Result.failure(IOException("HTTP $code"))
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: json
            val qrcode = data.optString("qrcode", "")
            if (qrcode.isEmpty()) return@withContext Result.failure(IOException("No qrcode in response"))
            Result.success(qrcode)
        } catch (e: Exception) {
            Log.e(TAG, "loginQrKey exception", e)
            Result.failure(e)
        }
    }

    /** 返回 base64 PNG data URL（Rust 本地生成，login.rs:484-504）。 */
    suspend fun loginQrCreate(key: String): Result<String> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = httpGetWithRetry("/login/qr/create", mapOf(
                "key" to key,
                "qrimg" to "true",
                "timestamp" to System.currentTimeMillis().toString()
            ))
            Log.d(TAG, "/login/qr/create -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("HTTP $code"))
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: json
            val base64 = data.optString("base64", "")
            if (base64.isEmpty()) return@withContext Result.failure(IOException("No base64 image in response"))
            Result.success(base64)
        } catch (e: Exception) {
            Log.e(TAG, "loginQrCreate exception", e)
            Result.failure(e)
        }
    }

    /** 轮询检查。返回 data.status；status==4 时提取 token/userid 并保存（不写日志/UI）。 */
    suspend fun loginQrCheck(key: String): Result<Int> = withContext(Dispatchers.IO) {
        try {
            val (code, body) = httpGetWithRetry("/login/qr/check", mapOf(
                "key" to key,
                "timestamp" to System.currentTimeMillis().toString()
            ))
            if (code !in 200..299) return@withContext Result.failure(IOException("HTTP $code"))
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: json
            val status = data.optInt("status", -1)
            if (status == 4) {
                val t = data.optString("token", "")
                val u = data.optString("userid", "")
                val vt = data.optString("vip_token", "")
                if (t.isNotEmpty() && u.isNotEmpty()) {
                    applyLogin(t, u, vt)
                }
            }
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "loginQrCheck exception", e)
            Result.failure(e)
        }
    }

    /** 设备注册：GET /register/dev -> data.dfid（Dart registerDevice 对应，kugou_api_client.dart:390-400）。 */
    suspend fun registerDevice(): Result<String> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/register/dev", emptyMap())
            Log.d(TAG, "/register/dev -> HTTP $code")
            if (code !in 200..299) return@withContext Result.failure(IOException("HTTP $code"))
            val json = JSONObject(body)
            val data = json.optJSONObject("data") ?: json
            val dfid = data.optString("dfid", "")
            if (dfid.isEmpty()) return@withContext Result.failure(IOException("No dfid in response"))
            saveDfid(dfid)
            Result.success(dfid)
        } catch (e: Exception) {
            Log.e(TAG, "registerDevice exception", e)
            Result.failure(e)
        }
    }

    // ---------- POC Capability: 歌词 / 歌单 / 专辑 / 歌手（临时，验证后移除） ----------

    /**
     * 歌词（严格对齐 Dart kugou_api_client.dart:1058-1163 两步链路）：
     *   1. GET /search/lyric?hash=<hash>            -> candidates[0].id / accesskey
     *   2. GET /lyric?id&accesskey&fmt=lrc&decode=true -> data.decodeContent（LRC 明文）
     * Rust 侧实现：modules/search_more.rs(handle_lyric) + modules/lyric.rs。
     * 返回完整 /lyric 响应 JSON；POC 页面自行取 decodeContent 展示。
     */
    suspend fun getLyric(hash: String, fmt: String = "lrc"): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))

            val (sCode, sBody) = httpGetWithRetry("/search/lyric", mapOf("hash" to hash.lowercase()))
            Log.d(TAG, "/search/lyric -> HTTP $sCode, body length=${sBody.length}")
            sBody.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/search/lyric body[$i]: $c") }
            if (sCode !in 200..299) return@withContext Result.failure(IOException("/search/lyric HTTP $sCode: ${sBody.take(200)}"))

            val sJson = JSONObject(sBody)
            val sData = sJson.optJSONObject("data") ?: sJson
            val candidates = sData.optJSONArray("candidates")
            val first = candidates?.optJSONObject(0)
            if (first == null) {
                return@withContext Result.failure(IOException("No lyric candidates in /search/lyric response"))
            }
            val lyricId = first.optString("id", "")
            val accesskey = first.optString("accesskey", "")
            if (lyricId.isEmpty()) {
                return@withContext Result.failure(IOException("Lyric candidate has no id"))
            }

            val params = mutableMapOf(
                "id" to lyricId,
                "fmt" to fmt,
                "decode" to "true"
            )
            if (accesskey.isNotEmpty()) params["accesskey"] = accesskey
            val (code, body) = httpGetWithRetry("/lyric", params)
            Log.d(TAG, "/lyric -> HTTP $code, body length=${body.length}")
            body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/lyric body[$i]: $c") }
            if (code !in 200..299) return@withContext Result.failure(IOException("/lyric HTTP $code: ${body.take(200)}"))

            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getLyric exception", e)
            Result.failure(e)
        }
    }

    /**
     * 我的歌单（对齐 Dart kugou_api_client.dart:2325-2342）。
     *   GET /user/playlist?page&pagesize[&userid]（token/userid 也走 Authorization 头，
     *   Rust user.rs:605 cookie_or_param_str 两者都会读）
     * 返回结构：data.info[]（KugouPlaylistBrief 字段：specialid/specialname/imgurl/songcount/listid/global_collection_id/type）。
     */
    suspend fun getMyPlaylists(page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = mutableMapOf("page" to page.toString(), "pagesize" to pageSize.toString())
                userid?.let { params["userid"] = it }
                val (code, body) = httpGetWithRetry("/user/playlist", params)
                Log.d(TAG, "/user/playlist -> HTTP $code, body length=${body.length}, loggedIn=${isLoggedIn()}")
                body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/user/playlist body[$i]: $c") }
                if (code !in 200..299) return@withContext Result.failure(IOException("/user/playlist HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "getMyPlaylists exception", e)
                Result.failure(e)
            }
        }

    /**
     * 自己歌单的歌曲列表（对齐 Dart getPlaylistSongsByListid，kugou_api_client.dart:1529-1551）。
     *   GET /playlist/track/all/new?listid&page&pagesize（Rust playlist.rs:304，token/userid 走 Authorization）
     * 返回结构：data.info[]（KugouSongDetail 字段：hash/songname/singername/timelen/album 系）。
     */
    suspend fun getPlaylistSongsByListid(listid: String, page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = mutableMapOf(
                    "listid" to listid,
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString()
                )
                userid?.let { params["userid"] = it }
                val (code, body) = httpGetWithRetry("/playlist/track/all/new", params)
                Log.d(TAG, "/playlist/track/all/new -> HTTP $code, body length=${body.length}")
                body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/playlist/track/all/new body[$i]: $c") }
                if (code !in 200..299) return@withContext Result.failure(IOException("/playlist/track/all/new HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "getPlaylistSongsByListid exception", e)
                Result.failure(e)
            }
        }

    /**
     * 精选歌单推荐（对齐 Dart getPlaylist → getTopPlaylist，kugou_api_client.dart:1470）。
     *   GET /top/playlist?page=（Rust top.rs handle_playlist → /v2/special_recommend）
     * 实测返回 data.special_list[]：specialid / global_collection_id / specialname /
     * imgurl / flexible_cover / songcount（部分缺失）。
     */
    suspend fun getTopPlaylist(page: Int = 1): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/top/playlist", mapOf("page" to page.toString()))
            Log.d(TAG, "/top/playlist -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("/top/playlist HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getTopPlaylist exception", e)
            Result.failure(e)
        }
    }

    /**
     * 精选歌单歌曲（对齐 Dart getPlaylistTrackAll → /playlist/track/all）。
     *   GET /playlist/track/all?global_collection_id=&page=&pagesize=
     * Rust playlist.rs handle_track_all → /pubsongs/v2/get_other_list_file_nofilt。
     * 实测返回 data.songs[]（hash/name/singerinfo[]/timelen/cover/albuminfo），data.count 总数。
     * 注意：精选歌单无 listid，不能走 /playlist/track/all/new（specialid 当 listid 会 20017）。
     */
    suspend fun getPlaylistSongsByGcid(globalCollectionId: String, page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val (code, body) = httpGetWithRetry(
                    "/playlist/track/all",
                    mapOf("global_collection_id" to globalCollectionId, "page" to page.toString(), "pagesize" to pageSize.toString())
                )
                Log.d(TAG, "/playlist/track/all -> HTTP $code, body length=${body.length}")
                if (code !in 200..299) return@withContext Result.failure(IOException("/playlist/track/all HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "getPlaylistSongsByGcid exception", e)
                Result.failure(e)
            }
        }

    /**
     * 歌单添加歌曲（严格对齐 Dart addPlaylistTracks，kugou_api_client.dart:2576-2584）。
     *   GET /playlist/tracks/add?listid&data（Rust playlist.rs:329 → /cloudlist.service/v6/add_song）
     * data 格式（md3Music 已验证协议）："歌名|hash|albumId|mixsongid"，多首逗号分隔。
     */
    suspend fun addPlaylistTracks(listid: String, data: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                if (!isLoggedIn()) return@withContext Result.failure(Exception("未登录酷狗账号"))
                val params = mutableMapOf("listid" to listid, "data" to data)
                userid?.let { params["userid"] = it }
                val (code, body) = httpGetOnce("/playlist/tracks/add", params)
                Log.d(TAG, "/playlist/tracks/add -> HTTP $code, body=${body.take(500)}")
                if (code !in 200..299) return@withContext Result.failure(IOException("/playlist/tracks/add HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "addPlaylistTracks exception", e)
                Result.failure(e)
            }
        }

    /**
     * 歌单移除歌曲（严格对齐 Dart deletePlaylistTracks，kugou_api_client.dart:2586-2594）。
     *   GET /playlist/tracks/del?listid&fileids（Rust playlist.rs:379 → /cloudlist.service/v4/delete_songs）
     * fileids：数字 fileid 优先；Rust 对非数字项自动降级为 {fileid:0, hash}，故 hash 可作兜底；多首逗号分隔。
     */
    suspend fun deletePlaylistTracks(listid: String, fileids: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                if (!isLoggedIn()) return@withContext Result.failure(Exception("未登录酷狗账号"))
                val params = mutableMapOf("listid" to listid, "fileids" to fileids)
                userid?.let { params["userid"] = it }
                val (code, body) = httpGetOnce("/playlist/tracks/del", params)
                Log.d(TAG, "/playlist/tracks/del -> HTTP $code, body=${body.take(500)}")
                if (code !in 200..299) return@withContext Result.failure(IOException("/playlist/tracks/del HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "deletePlaylistTracks exception", e)
                Result.failure(e)
            }
        }

    /** 专辑详情（对齐 Dart getAlbumDetail，kugou_api_client.dart:2722）：GET /album/detail?album_id=<id>。 */
    suspend fun getAlbumDetail(albumId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/album/detail", mapOf("album_id" to albumId))
            Log.d(TAG, "/album/detail -> HTTP $code, body length=${body.length}")
            body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/album/detail body[$i]: $c") }
            if (code !in 200..299) return@withContext Result.failure(IOException("/album/detail HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getAlbumDetail exception", e)
            Result.failure(e)
        }
    }

    /**
     * 新歌速递（对齐 Dart getTopSong，kugou_api_client.dart:1714）：GET /top/song?page=。
     * Rust top.rs handle_song → /musicadservice/container/v1/newsong_publish（rank_id=21608）。
     * 实测返回 { status, error_code, data: [KMR 原始歌曲数组], total }；data 直接是数组，
     * 每项顶层 hash/songname/author_name/album_name/timelength(ms)/album_sizable_cover。
     */
    suspend fun getTopSong(page: Int = 1): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/top/song", mapOf("page" to page.toString()))
            Log.d(TAG, "/top/song -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("/top/song HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getTopSong exception", e)
            Result.failure(e)
        }
    }

    /**
     * 新专辑推荐（对齐 Dart getTopAlbum，kugou_api_client.dart:1710）：GET /top/album?page=。
     * Rust top.rs handle_album → /musicadservice/v1/mobile_newalbum_sp。
     * 实测返回 { status, error_code, data: { chn:[], eur:[], jpn:[], kor:[] } }；
     * 每项 albumid/albumname/singername/imgurl/{size}/songcount/publishtime。
     */
    suspend fun getTopAlbum(page: Int = 1): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/top/album", mapOf("page" to page.toString()))
            Log.d(TAG, "/top/album -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("/top/album HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getTopAlbum exception", e)
            Result.failure(e)
        }
    }

    /**
     * 专辑歌曲列表（对齐 Dart getAlbumSongs，kugou_api_client.dart:2762）：
     *   GET /album/songs?album_id=&page=&pagesize=
     * Rust album.rs handle_album_songs → /v1/album_audio/lite，响应被重塑为扁平 data.songs[]：
     * hash/songname/author_name/singerinfo[]/album_name/duration(ms)/cover/{size}。
     */
    suspend fun getAlbumSongs(albumId: String, page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val (code, body) = httpGetWithRetry(
                    "/album/songs",
                    mapOf("album_id" to albumId, "page" to page.toString(), "pagesize" to pageSize.toString())
                )
                Log.d(TAG, "/album/songs -> HTTP $code, body length=${body.length}")
                if (code !in 200..299) return@withContext Result.failure(IOException("/album/songs HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "getAlbumSongs exception", e)
                Result.failure(e)
            }
        }

    /**
     * 排行榜列表（对齐 Dart getRankList，kugou_api_client.dart:1616）。
     *   GET /rank/list?withsong=1（Rust rank.rs:240 → /ocean/v6/rank/list）
     * 返回 data.info[]：rankid / rankname / imgurl(含{size}) / songcount。
     */
    suspend fun getRankList(withsong: Int = 1): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/rank/list", mapOf("withsong" to withsong.toString()))
            Log.d(TAG, "/rank/list -> HTTP $code, body length=${body.length}")
            body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/rank/list body[$i]: $c") }
            if (code !in 200..299) return@withContext Result.failure(IOException("/rank/list HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getRankList exception", e)
            Result.failure(e)
        }
    }

    /**
     * 榜单歌曲（对齐 Dart getRankAudio，kugou_api_client.dart:1647）。
     *   GET /rank/audio?rankid&rank_cid&page&pagesize（Rust rank.rs:9 → kmr/v2/rank/audio）
     * 响应已被 Rust 扁平化为 data.songlist[]：songname / author_name / singerinfo[] /
     * album_name / duration（实测秒）/ cover|sizable_cover。
     */
    suspend fun getRankAudio(rankId: String, page: Int = 1, pageSize: Int = 30, rankCid: Int = 0): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = mapOf(
                    "rankid" to rankId,
                    "rank_cid" to rankCid.toString(),
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString()
                )
                val (code, body) = httpGetWithRetry("/rank/audio", params)
                Log.d(TAG, "/rank/audio -> HTTP $code, body length=${body.length}")
                body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/rank/audio body[$i]: $c") }
                if (code !in 200..299) return@withContext Result.failure(IOException("/rank/audio HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "getRankAudio exception", e)
                Result.failure(e)
            }
        }

    /** 歌手详情（对齐 Dart getArtistDetail，kugou_api_client.dart:1945）：GET /artist/detail?id=<author_id>。 */
    suspend fun getArtistDetail(artistId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/artist/detail", mapOf("id" to artistId))
            Log.d(TAG, "/artist/detail -> HTTP $code, body length=${body.length}")
            body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/artist/detail body[$i]: $c") }
            if (code !in 200..299) return@withContext Result.failure(IOException("/artist/detail HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getArtistDetail exception", e)
            Result.failure(e)
        }
    }

    /** 专辑搜索（对齐 Dart searchAlbums，kugou_api_client.dart:478；Rust search_more.rs:18）：GET /search/album?keyword=。 */
    suspend fun searchAlbums(keyword: String, page: Int = 1, pageSize: Int = 20): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = mapOf(
                    "keyword" to keyword,
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString()
                )
                val (code, body) = httpGetWithRetry("/search/album", params)
                Log.d(TAG, "/search/album -> HTTP $code, body length=${body.length}")
                if (code !in 200..299) return@withContext Result.failure(IOException("/search/album HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "searchAlbums exception", e)
                Result.failure(e)
            }
        }

    /**
     * 每日推荐歌曲（对齐 Dart getRecommendDaily，kugou_api_client.dart:2180；Rust everyday.rs:43）。
     *   GET /recommend/songs（Rust → POST everydayrec.service /everyday_song_recommend）
     * userid 由 authHeader() 经 cookie 带入（Rust 侧缺省回退 "0"，未登录返回通用推荐）。
     */
    suspend fun getRecommendDailySongs(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/recommend/songs", emptyMap())
            Log.d(TAG, "/recommend/songs -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("/recommend/songs HTTP $code: ${body.take(200)}"))
            if (body.isEmpty()) return@withContext Result.failure(IOException("Empty response body"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getRecommendDailySongs exception", e)
            Result.failure(e)
        }
    }

    /**
     * 私人FM（对齐 Dart getPersonalFm，kugou_api_client.dart:2312；Rust fm.rs:171）。
     *   GET /personal/fm?mode&song_pool_id&hash&songid&action（Rust → POST persnfm.service /v2/personal_recommend）
     * 需登录（userid+token 由 authHeader() cookie 带入）；响应必须绕 apicache——
     * 2 分钟缓存会让「换一批」拿到同一批歌，故走 httpGetVipBusiness。
     */
    suspend fun getPersonalFm(
        mode: String = "normal",
        songPoolId: Int = 0,
        hash: String = "",
        songId: String = "",
        action: String = "play"
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = buildMap {
                put("mode", mode)
                put("song_pool_id", songPoolId.toString())
                put("action", action)
                if (hash.isNotEmpty()) put("hash", hash)
                if (songId.isNotEmpty()) put("songid", songId)
            }
            httpGetVipBusiness("/personal/fm", params)
        } catch (e: Exception) {
            Log.e(TAG, "getPersonalFm exception", e)
            Result.failure(e)
        }
    }

    /**
     * 歌手歌曲（对齐 Dart getArtistAudios，kugou_api_client.dart:2489；Rust artist.rs:41）。
     *   GET /artist/audios?id&page&pagesize（Rust → POST /kmr/v1/audio_group/author）
     */
    suspend fun getArtistAudios(artistId: String, page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = mapOf(
                    "id" to artistId,
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString()
                )
                val (code, body) = httpGetWithRetry("/artist/audios", params)
                Log.d(TAG, "/artist/audios -> HTTP $code, body length=${body.length}")
                if (code !in 200..299) return@withContext Result.failure(IOException("/artist/audios HTTP $code: ${body.take(200)}"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "getArtistAudios exception", e)
                Result.failure(e)
            }
        }

    /** 关注歌手（对齐 Dart followArtist，kugou_api_client.dart:2519；Rust artist.rs:106）：GET /artist/follow?id=。需登录。 */
    suspend fun followArtist(artistId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/artist/follow", mapOf("id" to artistId))
        } catch (e: Exception) {
            Log.e(TAG, "followArtist exception", e)
            Result.failure(e)
        }
    }

    /** 取关歌手（对齐 Dart unfollowArtist，kugou_api_client.dart:2527；Rust artist.rs:139）：GET /artist/unfollow?id=。需登录。 */
    suspend fun unfollowArtist(artistId: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/artist/unfollow", mapOf("id" to artistId))
        } catch (e: Exception) {
            Log.e(TAG, "unfollowArtist exception", e)
            Result.failure(e)
        }
    }

    /** 歌手搜索（对齐 Dart searchArtists，kugou_api_client.dart:508；Rust search_more.rs:34）：GET /search/artist?keyword=。 */
    suspend fun searchArtists(keyword: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/search/artist", mapOf("keyword" to keyword))
            Log.d(TAG, "/search/artist -> HTTP $code, body length=${body.length}")
            body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/search/artist body[$i]: $c") }
            if (code !in 200..299) return@withContext Result.failure(IOException("/search/artist HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "searchArtists exception", e)
            Result.failure(e)
        }
    }

    /** 热搜榜（Rust search_more.rs handle_hot → msearch /api/v3/search/hot_tab）：GET /search/hot。 */
    suspend fun searchHot(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/search/hot", emptyMap())
            Log.d(TAG, "/search/hot -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("/search/hot HTTP $code: ${body.take(200)}"))
            if (body.isEmpty()) return@withContext Result.failure(IOException("Empty response body"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "searchHot exception", e)
            Result.failure(e)
        }
    }

    /** 搜索联想（Rust search_suggest.rs → searchtip /v2/getSearchTip）：GET /search/suggest?keywords=。 */
    suspend fun searchSuggest(keywords: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val (code, body) = httpGetWithRetry("/search/suggest", mapOf("keywords" to keywords))
            Log.d(TAG, "/search/suggest -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("/search/suggest HTTP $code: ${body.take(200)}"))
            if (body.isEmpty()) return@withContext Result.failure(IOException("Empty response body"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "searchSuggest exception", e)
            Result.failure(e)
        }
    }

    /** 歌单搜索（Rust search_more.rs handle_special → mobilecdnbj /api/v3/search/special）：GET /search/special。 */
    suspend fun searchSpecial(keyword: String, page: Int = 1, pageSize: Int = 20): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = mapOf(
                    "keyword" to keyword,
                    "page" to page.toString(),
                    "pagesize" to pageSize.toString()
                )
                val (code, body) = httpGetWithRetry("/search/special", params)
                Log.d(TAG, "/search/special -> HTTP $code, body length=${body.length}")
                if (code !in 200..299) return@withContext Result.failure(IOException("/search/special HTTP $code: ${body.take(200)}"))
                if (body.isEmpty()) return@withContext Result.failure(IOException("Empty response body"))
                Result.success(JSONObject(body))
            } catch (e: Exception) {
                Log.e(TAG, "searchSpecial exception", e)
                Result.failure(e)
            }
        }

    /** 歌单详情（对齐 Dart getPlaylistDetail，kugou_api_client.dart:1489；Rust playlist.rs:203）。
     *  Rust server.rs build_query 会合并 query/JSON body，故直接 GET ?ids= 即可（Dart 用 POST data 等价）。 */
    suspend fun getPlaylistDetail(ids: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = mutableMapOf("ids" to ids)
            userid?.let { params["userid"] = it }
            val (code, body) = httpGetWithRetry("/playlist/detail", params)
            Log.d(TAG, "/playlist/detail -> HTTP $code, body length=${body.length}")
            body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/playlist/detail body[$i]: $c") }
            if (code !in 200..299) return@withContext Result.failure(IOException("/playlist/detail HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylistDetail exception", e)
            Result.failure(e)
        }
    }

    // ==================== VIP / 签到（酷狗 youth 活动，对齐 md3Music kugou_api_client.dart） ====================
    // Rust 路由分发只按路径匹配、忽略 HTTP method，且 build_query 会把 query 与
    // JSON body 合并（与 getPlaylistDetail 同理），故 Dart 端的 POST 全部等价改用 GET。
    // Rust apicache 会缓存所有 200 响应 2 分钟——签到失败重试、领取后立即刷新详情
    // 都会被旧缓存污染，因此本组接口全部带 x-apicache-bypass 头。

    private val VIP_NO_CACHE_HEADERS = mapOf("x-apicache-bypass" to "1")

    /**
     * VIP/签到专用 GET：酷狗上游把业务错误（131001=今日已领取、20028=需安全验证等）
     * 连同业务 JSON 一起包在 HTTP 502/4xx 里透传（Rust respond_module 原样转发上游
     * 状态码，实测 OPD：502 + {"status":0,"error_code":131001}）。这类响应是合法的
     * 业务结果，绝不能按 HTTP 错误重试或丢弃——判据：body 可解析为含
     * error_code/status 字段的 JSON。
     */
    private suspend fun httpGetVipBusiness(
        path: String,
        params: Map<String, String> = emptyMap()
    ): Result<JSONObject> = retryResult {
        try {
            val (code, body) = httpGetOnce(path, params, VIP_NO_CACHE_HEADERS)
            Log.d(TAG, "$path -> HTTP $code, body length=${body.length}")
            if (body.isNotEmpty()) {
                val business = runCatching { JSONObject(body) }.getOrNull()
                if (business != null && (business.has("error_code") || business.has("status"))) {
                    return@retryResult Result.success(business)
                }
            }
            if (code !in 200..299) {
                return@retryResult Result.failure(IOException("$path HTTP $code: ${body.take(200)}"))
            }
            if (body.isEmpty()) {
                return@retryResult Result.failure(IOException("$path empty response body"))
            }
            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@retryResult Result.failure(IOException("$path invalid JSON body"))
            Result.success(json)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }
    }

    /** VIP 详情（对齐 Dart getUserVipDetail，kugou_api_client.dart:2811）：GET /user/vip/detail。 */
    suspend fun getVipDetail(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/user/vip/detail")
        } catch (e: Exception) {
            Log.e(TAG, "getVipDetail exception", e)
            Result.failure(e)
        }
    }

    /** 服务器时间（对齐 Dart getServerNow）：GET /server/now。 */
    suspend fun getServerNow(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/server/now")
        } catch (e: Exception) {
            Log.e(TAG, "getServerNow exception", e)
            Result.failure(e)
        }
    }

    /** 每日签到第一步：领取畅听 VIP（对齐 Dart claimDayVip L3574）：GET /youth/day/vip?receive_day=yyyy-MM-dd。 */
    suspend fun youthDayVip(receiveDay: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/youth/day/vip", mapOf("receive_day" to receiveDay))
        } catch (e: Exception) {
            Log.e(TAG, "youthDayVip exception", e)
            Result.failure(e)
        }
    }

    /** 每日签到第二步：升级概念版 VIP（对齐 Dart upgradeDayVip L3585）：GET /youth/day/vip/upgrade。 */
    suspend fun youthDayVipUpgrade(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/youth/day/vip/upgrade")
        } catch (e: Exception) {
            Log.e(TAG, "youthDayVipUpgrade exception", e)
            Result.failure(e)
        }
    }

    /** 当月打卡记录（对齐 Dart getYouthMonthVipRecord）：GET /youth/month/vip/record?month=yyyy-MM（必须传，缺省返回最早月份）。 */
    suspend fun youthMonthVipRecord(month: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/youth/month/vip/record", mapOf("month" to month))
        } catch (e: Exception) {
            Log.e(TAG, "youthMonthVipRecord exception", e)
            Result.failure(e)
        }
    }

    /** 听歌上报领取 VIP（对齐 Dart listenSong L3553，无需参数）：GET /youth/listen/song。error_code 130012=今日已领取。 */
    suspend fun youthListenSong(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/youth/listen/song")
        } catch (e: Exception) {
            Log.e(TAG, "youthListenSong exception", e)
            Result.failure(e)
        }
    }

    /** 广告播放上报领取 VIP（对齐 Dart claimAdVip L3558）：GET /youth/vip。error_code 30002=今日次数用光。 */
    suspend fun youthAdVip(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/youth/vip")
        } catch (e: Exception) {
            Log.e(TAG, "youthAdVip exception", e)
            Result.failure(e)
        }
    }

    /** 查询验证码格式（对齐 Dart getVerifyInfo L3595）：GET /get/verify/info?eventid=。20028 时返回 v_type/txappid。 */
    suspend fun getVerifyInfo(eventid: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/get/verify/info", mapOf("eventid" to eventid))
        } catch (e: Exception) {
            Log.e(TAG, "getVerifyInfo exception", e)
            Result.failure(e)
        }
    }

    /** 提交二次验证结果（对齐 Dart verifyUserInfo L3608）：GET /verify/user/info?eventid&v_type&verifycode。 */
    suspend fun verifyUserInfo(eventid: String, vType: Int, verifycode: String): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                httpGetVipBusiness(
                    "/verify/user/info",
                    mapOf(
                        "eventid" to eventid,
                        "v_type" to vType.toString(),
                        "verifycode" to verifycode
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "verifyUserInfo exception", e)
                Result.failure(e)
            }
        }

    // ---------- 手机号（短信验证码）登录 ----------
    // Rust 路由已内置（app/libs/*.so 二进制已验证）：/captcha/sent、/login/cellphone。
    // 加密/签名/手机号打码全在 Rust 内部完成（参考实现 misc.rs:135-155 / login.rs:138-231），
    // Kotlin 只做 query 传参与响应解析，不重新实现协议。与 VIP 接口同理：
    //   1) 酷狗上游把业务失败（含 34175 多账号候选列表）包在 HTTP 502/4xx 里透传，
    //      必须走 httpGetVipBusiness，不能走 httpGetWithRetry（非 2xx 会重试 3 次后丢弃）；
    //   2) 必须带 x-apicache-bypass（Rust apicache 缓存所有 200 响应 2 分钟，
    //      否则倒计时内二次发码会命中缓存假成功）。

    /** 发送短信验证码：GET /captcha/sent?mobile=<11位手机号>。上游成功响应 status=1。 */
    suspend fun sendLoginCaptcha(mobile: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/captcha/sent", mapOf(
                "mobile" to mobile,
                "timestamp" to System.currentTimeMillis().toString()
            )).fold(
                onSuccess = { body ->
                    Log.d(TAG, "/captcha/sent -> status=${body.optInt("status", 0)}")
                    if (body.optInt("status", 0) == 1) Result.success(Unit)
                    else Result.failure(
                        KugouLoginException(
                            body.optInt("error_code", 0),
                            body.optString("error_msg", "").takeIf { it.isNotEmpty() }
                        )
                    )
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "sendLoginCaptcha exception", e)
            Result.failure(e)
        }
    }

    /**
     * 手机验证码登录：GET /login/cellphone?mobile&code[&userid]。
     *
     * 成功（status==1）时 Rust 已解密 secu_params 并合并进 data（login.rs:201-228），
     * 此处直接 applyLogin（与 loginQrCheck 同一凭证写入点），UI 观察 KugouAccountState 即可；
     * error_code=34175（一个手机号绑定多个账号）返回 NeedChooseAccount，
     * UI 弹窗让用户选择后携带 userid 再次调用本方法完成登录。
     */
    suspend fun loginByCellphone(
        mobile: String,
        code: String,
        userid: String? = null
    ): Result<PhoneLoginResult> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = mutableMapOf(
                "mobile" to mobile,
                "code" to code,
                "timestamp" to System.currentTimeMillis().toString()
            )
            if (!userid.isNullOrBlank()) params["userid"] = userid
            httpGetVipBusiness("/login/cellphone", params).fold(
                onSuccess = { body ->
                    Log.d(TAG, "/login/cellphone -> status=${body.optInt("status", 0)}, error_code=${body.optInt("error_code", 0)}")
                    when (val result = parsePhoneLoginResponse(toPlainAny(body))) {                        is PhoneLoginResult.Success -> {
                            applyLogin(result.token, result.userid, result.vipToken)
                            Result.success(result)
                        }
                        else -> Result.success(result)
                    }
                },
                onFailure = { Result.failure(it) }
            )
        } catch (e: Exception) {
            Log.e(TAG, "loginByCellphone exception", e)
            Result.failure(e)
        }
    }

    /**
     * Simple test connection to verify server is responding.
     */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!KugouNativeBridge.isServerRunning()) {
                Log.e(TAG, "Server not running")
                return@withContext false
            }

            val baseUrl = KugouNativeBridge.getBaseUrl()
            val request = Request.Builder()
                .url("$baseUrl/")
                .get()
                .build()

            val response = client.newCall(request).execute()
            val code = response.code
            response.close()
            // 注意：Rust server 对 "/" 没有路由，返回 404 是正常的；
            // 只要拿到任意 HTTP 响应（code>0）即证明服务器存活可达。
            val success = code > 0
            Log.d(TAG, "Connection test: ${if (success) "✓ OK (HTTP $code)" else "✗ Failed (no response)"}")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Connection test failed", e)
            false
        }
    }
}

/** 手机验证码登录候选账号（error_code=34175：一个手机号绑定多个酷狗账号）。 */
data class KugouLoginAccountCandidate(
    val userid: String,
    val nickname: String?,
    val avatar: String?
)

/** /login/cellphone 的解析结果（凭证写入由 API 层负责，本类型只承载数据）。 */
sealed class PhoneLoginResult {
    /** 登录成功（携带 token/userid/vip_token，API 层已 applyLogin）。 */
    data class Success(val token: String, val userid: String, val vipToken: String) : PhoneLoginResult()

    /** 一个手机号绑定多个账号，需用户选择后携带 userid 重新提交登录。 */
    data class NeedChooseAccount(val candidates: List<KugouLoginAccountCandidate>) : PhoneLoginResult()

    /** 登录失败（errorMsg 为上游透传文案，可能为空；UI 需回退到本地通用文案）。 */
    data class Failed(val errorCode: Int, val errorMsg: String?) : PhoneLoginResult()
}

/** 登录/发码业务失败异常（携带上游 error_code/error_msg，供 UI 透传展示）。 */
class KugouLoginException(val errorCode: Int, val errorMsg: String?) :
    Exception(errorMsg?.takeIf { it.isNotBlank() } ?: "kugou login failed (error_code=$errorCode)")

/** 34175：一个手机号绑定多个酷狗账号（响应 data.info_list 携带候选列表）。 */
internal const val KUGOU_LOGIN_ERROR_MULTI_ACCOUNT = 34175

/**
 * /login/cellphone 响应解析（入参是 [toPlainAny] 展开后的纯 stdlib 结构，
 * 纯 JVM 可测；字段兼容集对齐参考项目 kugou_provider.dart:1116-1219 与
 * KugouLoginAccount.fromJson —— 成功读 data.token/userid/vip_token，
 * 多账号候选列表兼容 info_list/user_list/userList/lists/list 五种字段名）。
 */
internal fun parsePhoneLoginResponse(rootAny: Any?): PhoneLoginResult {
    // 非 Map 形态（理论上不出现，防御）：按无信息失败处理
    val root = rootAny as? Map<*, *> ?: return PhoneLoginResult.Failed(0, null)
    val status = root.intAt("status")
    val errorCode = root.intAt("error_code") ?: 0
    val data = root["data"] as? Map<*, *>

    if (status == 1) {
        val token = data?.strAt("token").orEmpty()
        val userid = data?.strAt("userid").orEmpty()
        val vipToken = data?.strAt("vip_token").orEmpty()
        return if (token.isNotEmpty() && userid.isNotEmpty()) {
            PhoneLoginResult.Success(token, userid, vipToken)
        } else {
            // status=1 但缺关键字段：视为上游异常响应，按失败处理（不带误导性文案）
            PhoneLoginResult.Failed(errorCode, null)
        }
    }

    if (errorCode == KUGOU_LOGIN_ERROR_MULTI_ACCOUNT && data != null) {
        val raw = data["info_list"] ?: data["user_list"] ?: data["userList"]
            ?: data["lists"] ?: data["list"]
        val candidates = (raw as? List<*>)
            ?.filterIsInstance<Map<*, *>>()
            ?.mapNotNull(::parseLoginAccountCandidate)
            .orEmpty()
        if (candidates.isNotEmpty()) return PhoneLoginResult.NeedChooseAccount(candidates)
    }

    val errorMsg = root.strAt("error_msg", "msg", "error")
        ?: data?.strAt("error_msg", "msg", "error")
    return PhoneLoginResult.Failed(errorCode, errorMsg)
}

/** 候选账号条目解析（字段别名集与参考 KugouLoginAccount.fromJson 一致；userid 为空丢弃）。 */
internal fun parseLoginAccountCandidate(entry: Map<*, *>): KugouLoginAccountCandidate? {
    val userid = entry.strAt("userid", "userId", "id", "user_id")?.trim().orEmpty()
    if (userid.isEmpty()) return null
    return KugouLoginAccountCandidate(
        userid = userid,
        nickname = entry.strAt("nickname", "user_name", "name"),
        avatar = entry.strAt("avatar", "pic", "img", "imgurl")
    )
}

/** org.json 结构 → 纯 stdlib 结构（Map/List/String/Number/Boolean/null），供纯 JVM 解析函数使用。 */
internal fun toPlainAny(value: Any?): Any? = when (value) {
    is JSONObject -> value.keys().asSequence().associateWith { k -> toPlainAny(value.opt(k)) }
    is org.json.JSONArray -> List(value.length()) { i -> toPlainAny(value.opt(i)) }
    JSONObject.NULL -> null
    else -> value
}

private fun Map<*, *>.intAt(key: String): Int? = when (val v = this[key]) {
    is Number -> v.toInt()
    is String -> v.toIntOrNull()
    else -> null
}

/** 多字段名回退取文本；数值型（如 userid 上游可能回数字）转十进制字符串。 */
private fun Map<*, *>.strAt(vararg keys: String): String? {
    for (key in keys) when (val v = this[key]) {
        is Number -> return v.toLong().toString()
        is String -> if (v.isNotBlank()) return v
    }
    return null
}
