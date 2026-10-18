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
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.objects.BlacklistState
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouVipState
import yos.music.player.data.objects.MyPlaylistsCache
import yos.music.player.data.repositories.KugouAuthInvalidException
import yos.music.player.data.repositories.KugouUpstreamError

/**
 * Kotlin HTTP client wrapper around md3Music's Rust HTTP server.
 * 
 * Provides search and playback URL APIs that mirror the Dart/Flutter interface.
 */
class KugouApiService private constructor() {
    companion object {
        private const val TAG = "KugouApiService"

        /**
         * 概念版（lite）平台标识，对应 JS 原版服务端的 `process.env.platform = 'lite'`
         * （util/runtime.js:51）。Rust 移植把服务端注入值写成了字面量 "undefined"，故由客户端补上。
         */
        private const val KUGOU_PLATFORM = "lite"

        /**
         * 收藏页解锁 ID（酷狗约定：歌曲被收藏后，即便无版权/需付费也能用该 page_id 解锁播放）。
         * 服务端缺省是三个 ID 的串，实测**解锁不了**，必须显式传这一个。
         */
        private const val PREFERRED_PPAGE_ID = "356753938"
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

        /**
         * 原生 server 的 dataDir：Rust 侧用它持久化 `device_info.json`（dfid/mid/guid/uuid，
         * 见 [KugouNativeBridge.startServer] 的注释）。由 Application.onCreate 注入真实
         * `filesDir`。
         *
         * 历史 bug（本次 20028 的根因）：这里曾写死 `/data/data/yos.music.player.oss/files`
         * ——旧包名。包名改成 com.sonify.music 后该目录属于**别的应用沙箱，不可写**，
         * 设备身份永远存不下来、每次进程重启都重新生成，酷狗于是把每次会话当成陌生设备，
         * 风控直接回 `errcode:20028 "本次请求需要验证"`。与 md3Music server.rs:191-195
         * 点名的风控诱因（per-launch 重生成身份）同源。
         */
        @Volatile
        var appFilesDir: String? = null

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

        /**
         * 是否持有会员凭据（只暴露布尔，不暴露明文）。
         * 播放解析据此决定要不要先走会员协议 /song/url/new（priv_url）——
         * 非会员账号发这条请求没有意义，只会多一次上游调用。
         */
        fun hasVipToken(): Boolean = vipToken != null

        /**
         * 冷启动补齐会员凭据：已登录且当前**没有** vip_token 时才刷新一次
         * （对齐 md3Music refreshLogin）。返回是否真的发起了请求，便于日志核对。
         */
        suspend fun refreshLoginIfMissingVip(): Boolean {
            if (!isLoggedIn() || vipToken != null) return false
            return getInstance().refreshLogin()
        }

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
            // 不感兴趣黑名单是账号级数据：换账号必须丢弃上一个账号的缓存
            BlacklistState.reset()
            MyPlaylistsCache.reset()
            // 持久化留证（logcat 会被刷掉，诊断文件不会）：本次登录/刷新有没有拿到会员凭据。
            // 这里是全部登录路径的唯一写入点，一条记录即可覆盖扫码/验证码/刷新三种来源。
            YosDiagnostics.log(
                "AUTH_APPLY", "userid" to userid,
                "hasVipToken" to (this.vipToken != null), "tokenLen" to token.length
            )
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
            BlacklistState.reset()
            MyPlaylistsCache.reset()
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

    /**
     * 识曲专用 client：指纹接口上游要算完一整段指纹才回包，15s 的读超时不够，
     * 单独放宽到 30s（其余 GET 仍走 [client]）。
     */
    private val audioMatchClient by lazy {
        client.newBuilder()
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build()
    }

    /** 保证 Rust server 在运行（幂等）。返回是否可用；启动瞬时失败最多重试 3 次。 */
    private suspend fun ensureServer(): Boolean = retryResult {
        try {
            // 设备身份持久化目录：优先用 Application 注入的真实 filesDir（可写）；
            // 回退值是当前包名，绝不能再用旧包名 yos.music.player.oss（见 appFilesDir 注释）。
            val dataDir = appFilesDir ?: "/data/data/com.sonify.music/files"
            if (!KugouNativeBridge.isServerRunning()) {
                val p = KugouNativeBridge.startServer(dataDir = dataDir)
                if (p <= 0) return@retryResult Result.failure<Boolean>(IOException("Failed to start server"))
            } else if (KugouNativeBridge.getServerPort() <= 0) {
                KugouNativeBridge.startServer(dataDir = dataDir)
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
        extraHeaders: Map<String, String> = emptyMap(),
        maxRetries: Int = 3
    ): Pair<Int, String> = retryResult(maxRetries) {
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
        extraHeaders: Map<String, String> = emptyMap(),
        // true 时日志只记路径、不记 query：评论正文等用户内容不得进日志
        redactLog: Boolean = false
    ): Pair<Int, String> {
        val baseUrl = KugouNativeBridge.getBaseUrl()
        val builder = baseUrl.toHttpUrl().newBuilder()
        path.trimStart('/').split('/').filter { it.isNotEmpty() }.forEach { builder.addPathSegment(it) }
        params.forEach { (k, v) -> builder.addQueryParameter(k, v) }
        // /login/qr/* 在登录前调用，此时 authHeader()==null，不带头，与 Dart 行为一致
        val rb = Request.Builder().url(builder.build()).get()
        // 平台标识必须由客户端显式带上：JS 原版服务端启动时无条件 `process.env.platform='lite'`
        // （util/runtime.js:51），每个请求都带 KUGOU_API_PLATFORM=lite；Rust 移植把这个缺省值
        // 写成了字面量 "undefined"（server.rs:284），而该 cookie 只在"缺失时"补——于是上游收到的
        // 是 undefined，认不出概念版（也就认不出签到领的概念版会员）。带上真值即可覆盖。
        rb.addHeader("Cookie", "KUGOU_API_PLATFORM=$KUGOU_PLATFORM")
        authHeader()?.let { rb.addHeader("Authorization", it) }
        extraHeaders.forEach { (k, v) -> rb.header(k, v) }
        val req = rb.build()
        // 证据链要求：确认登录态确实通过 Authorization 头传给 Rust。
        // 只记录“有无”及组成，不记录 token 明文。
        val authDesc = if (req.header("Authorization") != null) {
            "present(token,userid${if (vipToken != null) ",vip_token" else ""})"
        } else "none"
        Log.d(TAG, "GET ${if (redactLog) path else req.url} Authorization=$authDesc")
        val response = client.newCall(req).execute()
        val code = response.code
        val body = response.body?.string() ?: ""
        response.close()
        return code to body
    }

    /**
     * 同 [httpGetWithRetry]，但**非 2xx 也把 (code, body) 原样返回**（不抛异常）。
     *
     * 播放路径专用：20028 / illegal key 的判据全在错误体里，而通用封装对非 2xx 直接
     * 抛 `IOException("HTTP xxx: ...")`、把体丢掉——历史上 getSongUrl 因此从未真正
     * 识别过鉴权失效（那段 `if (code !in 200..299)` 是不可达的死代码）。
     * 只有传输层异常（连接失败/超时）才按 [maxRetries] 重试。
     */
    private suspend fun httpGetAllowNonOk(
        path: String,
        params: Map<String, String>,
        extraHeaders: Map<String, String> = emptyMap(),
        maxRetries: Int = 1
    ): Pair<Int, String> = retryResult(maxRetries) {
        try {
            Result.success(httpGetOnce(path, params, extraHeaders))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure<Pair<Int, String>>(error)
        }
    }.getOrElse { throw it }

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
     * 听歌识曲：把 8000Hz / 16bit / 单声道 PCM 发给酷狗指纹接口。
     *
     * 对应 Rust 路由 `POST /audio/match`（modules/audio_match.rs）→ 上游
     * `/fingerprint.service/v1/music_trackid_mulit`。
     *
     * 三个必须注意的点：
     * 1. body 是**裸 PCM**（application/octet-stream），不是 JSON；
     * 2. **没匹配到时上游返回 HTTP 502 + JSON 体**（`status=0`），这是正常答复，
     *    所以必须先尝试把 body 解析成 JSON 再谈失败，否则每轮"没听出来"都会被
     *    当成网络错误；
     * 3. 结果依赖 body（每段都不同），必须带 `x-apicache-bypass` 绕开 apicache。
     */
    suspend fun audioMatch(pcm: ByteArray): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) {
                return@withContext Result.failure(IOException("Failed to start server"))
            }
            if (pcm.isEmpty()) {
                return@withContext Result.failure(IOException("Empty PCM"))
            }

            val builder = KugouNativeBridge.getBaseUrl().toHttpUrl().newBuilder()
            // 路径必须逐段 addPathSegment（整体传 "audio/match" 会被编码成 %2F → 404）
            builder.addPathSegment("audio")
            builder.addPathSegment("match")
            builder.addQueryParameter("timestamp", System.currentTimeMillis().toString())
            // Rust 侧 param_or_cookie_num(q, "userid", 0)：显式带上更稳（未登录传 0）
            builder.addQueryParameter("userid", userid ?: "0")

            val rb = Request.Builder()
                .url(builder.build())
                .post(pcm.toRequestBody("application/octet-stream".toMediaType()))
                .addHeader("Cookie", "KUGOU_API_PLATFORM=$KUGOU_PLATFORM")
                .addHeader("x-apicache-bypass", "1")
            authHeader()?.let { rb.addHeader("Authorization", it) }
            val req = rb.build()

            val response = audioMatchClient.newCall(req).execute()
            val code = response.code
            val body = response.body?.string() ?: ""
            response.close()

            Log.d(TAG, "POST /audio/match -> HTTP $code, body=${body.take(400)}")

            val json = runCatching { JSONObject(body) }.getOrNull()
                ?: return@withContext Result.failure(
                    IOException("/audio/match HTTP $code: ${body.take(300)}")
                )
            Result.success(json)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "audioMatch exception", error)
            Result.failure(error)
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

                val params = mapOf(
                    "hash" to hash.lowercase(),
                    "quality" to quality,
                    // 收藏页解锁 ID：酷狗约定"歌曲被收藏后，即便无版权/需付费也可用该 page_id 解锁播放"。
                    // 必须传**单个** 356753938——服务端缺省值是三个 ID 的串
                    // （"356753938,823673182,967485191"），实测那个串解锁不了：同一首付费歌带缺省串
                    // 回 `status:3 priv_status:0 pay_block_tpl:1`（无地址），带单 ID 回
                    // `status:1 priv_status:1` + 完整地址。md3Music 也是显式传这一个（Dart
                    // kugou_api_client.dart:1255-1267「已收藏无版权歌曲兜底」）。
                    "ppage_id" to PREFERRED_PPAGE_ID,
                )
                // 播放路径的重试降为 1 次：这条链跑在 ExoPlayer Loader 线程上且外层
                // 有 10s 全局截止（resolvePlayUrlBlocking），3 次重试 × readTimeout 15s
                // 只会把整段准备时间拖到分钟级；瞬时失败交给重试一次 + 失败跳歌兜底。
                // 用 httpGetAllowNonOk：非 2xx 的响应体必须拿得到（20028/illegal key 在体里）。
                // 必须绕 apicache：Rust 的缓存键是 host+path+query、**不含 Authorization**
                // （server.rs:246），登录前的 200 响应会在 2 分钟内喂给已登录请求，
                // 是"同一首歌一会儿能播、一会儿被判无音源"的现成通道。
                val (code, body) = httpGetAllowNonOk("/song/url", params, VIP_NO_CACHE_HEADERS, maxRetries = 1)

                Log.d(TAG, "/song/url -> HTTP $code, body length=${body.length}, loggedIn=${isLoggedIn()}")
                if (body.isNotEmpty()) {
                    body.chunked(3500).forEachIndexed { idx, chunk ->
                        Log.d(TAG, "/song/url body[$idx]: $chunk")
                    }
                }

                // 上游风控"本次请求需要验证"(20028)：多为设备身份/sign_key 失效。对齐 md3Music
                // kugou_api_client.dart:1112-1126：重注册设备后重试一次，有机会自愈（缺 dfid
                // 的场景一次就好）；重试仍失败才当账号级失效处理（挂起而非逐首跳）。
                // 注意两种 HTTP 形态都可能在体里带 20028，故先于状态码判断。
                if (KugouUpstreamError.isNeedVerify(body)) {
                    Log.w(TAG, "/song/url 需要验证(20028)，重注册设备后重试一次")
                    registerDevice()
                    val (code2, body2) = httpGetAllowNonOk("/song/url", params, VIP_NO_CACHE_HEADERS, maxRetries = 1)
                    Log.d(TAG, "/song/url(重试) -> HTTP $code2, body length=${body2.length}")
                    if (code2 in 200..299 && body2.isNotEmpty() &&
                        !KugouUpstreamError.isNeedVerify(body2)
                    ) {
                        return@withContext Result.success(JSONObject(body2))
                    }
                    return@withContext Result.failure(
                        KugouAuthInvalidException("/song/url 重试后仍需验证 HTTP $code2: ${body2.take(300)}")
                    )
                }

                if (code !in 200..299) {
                    // 鉴权失效单独开一类：它不是这一首的问题（逐首逐档重试只会把 502 刷成屏），
                    // 也不是网络抖动（不值得退避重试）。判据见 [KugouUpstreamError.isAuthInvalid]。
                    val authInvalid = KugouUpstreamError.isAuthInvalid(code, body)
                    return@withContext Result.failure(
                        if (authInvalid) KugouAuthInvalidException("/song/url HTTP $code: ${body.take(300)}")
                        else IOException("/song/url HTTP $code: ${body.take(300)}")
                    )
                }
                if (body.isEmpty()) {
                    return@withContext Result.failure(IOException("Empty response body"))
                }

                Result.success(JSONObject(body))

            } catch (e: kotlinx.coroutines.CancellationException) {
                // 取消（切歌/超时）不是请求失败：吞掉它会把取消伪装成网络失败层层上报
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Get URL exception", e)
                Result.failure(e)
            }
        }

    /**
     * 会员播放地址：Rust GET /song/url/new → 上游 /v6/priv_url。
     *
     * 与 [getSongUrl]（v5/url）的关键差别：priv_url 会把 **vip_token 作为
     * `tracker_param.viptoken` 上传**、并把 `vip` 置成会员档（song_url_new.rs）；
     * v5/url 只能把凭据放在 cookie 里，实测付费/VIP 歌在这条路上拿不到链接
     * （真机同一 hash：/song/url 回 `status=2` 无 url，/song/url/new 出货）。
     * md3Music 的 Dart 侧是同一策略——有 vip_token 就优先 priv_url
     * （kugou_api_client.dart:964-977）。
     *
     * 响应里 url 可能是"按档位排列的数组"，但 Rust 侧 apply_quality 已按请求档挑好并
     * 归一化成 `data.url`（字符串）+ `data.quality`，故判读方式与 [getSongUrl] 一致。
     */
    suspend fun getSongUrlNew(
        hash: String,
        quality: String = "128",
        albumId: String? = null,
        albumAudioId: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))

            val params = buildMap {
                put("hash", hash.lowercase())
                put("quality", quality)
                albumId?.takeIf { it.isNotBlank() }?.let { put("album_id", it) }
                albumAudioId?.takeIf { it.isNotBlank() }?.let { put("album_audio_id", it) }
            }
            // 只记录"有无会员凭据"，不记录明文（与 authHeader 的取证口径一致）
            Log.d(TAG, "GET /song/url/new hash=$hash quality=$quality hasVipToken=${vipToken != null}")

            val (code, body) = httpGetAllowNonOk("/song/url/new", params, VIP_NO_CACHE_HEADERS, maxRetries = 1)
            Log.d(TAG, "/song/url/new -> HTTP $code, body length=${body.length}")
            if (body.isNotEmpty()) {
                body.chunked(3500).forEachIndexed { idx, chunk ->
                    Log.d(TAG, "/song/url/new body[$idx]: $chunk")
                }
            }

            // 20028 自愈与 v5 同款：重注册设备后重试一次，仍失败才当账号级失效
            if (KugouUpstreamError.isNeedVerify(body)) {
                Log.w(TAG, "/song/url/new 需要验证(20028)，重注册设备后重试一次")
                registerDevice()
                val (code2, body2) =
                    httpGetAllowNonOk("/song/url/new", params, VIP_NO_CACHE_HEADERS, maxRetries = 1)
                Log.d(TAG, "/song/url/new(重试) -> HTTP $code2, body length=${body2.length}")
                if (code2 in 200..299 && body2.isNotEmpty() &&
                    !KugouUpstreamError.isNeedVerify(body2)
                ) {
                    return@withContext Result.success(JSONObject(body2))
                }
                return@withContext Result.failure(
                    KugouAuthInvalidException("/song/url/new 重试后仍需验证 HTTP $code2: ${body2.take(300)}")
                )
            }

            if (code !in 200..299) {
                val authInvalid = KugouUpstreamError.isAuthInvalid(code, body)
                return@withContext Result.failure(
                    if (authInvalid) KugouAuthInvalidException("/song/url/new HTTP $code: ${body.take(300)}")
                    else IOException("/song/url/new HTTP $code: ${body.take(300)}")
                )
            }
            if (body.isEmpty()) {
                return@withContext Result.failure(IOException("Empty response body"))
            }

            Result.success(JSONObject(body))

        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Get URL(new) exception", e)
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
                // 登录响应必须留证：会员凭据（vip_token）到底有没有回、回在哪一层，
                // 只有看这里的原始报文才能定性（历史现象：登录后 hasVip=false 无从追查）
                body.chunked(3500).forEachIndexed { idx, chunk ->
                    Log.d(TAG, "/login/qr/check body[$idx]: $chunk")
                }
                // 凭据可能落在 data 内、也可能只在顶层（Rust handle_qr_check 两处都写），两层都读，
                // 避免"上游其实给了会员凭据、只是我们读错层级"。
                val t = data.optString("token", "").ifEmpty { json.optString("token", "") }
                val u = data.optString("userid", "").ifEmpty { json.optString("userid", "") }
                val vt = data.optString("vip_token", "").ifEmpty { json.optString("vip_token", "") }
                // 持久化留证（logcat 会被刷掉，诊断文件不会）：登录到底有没有带回会员凭据
                YosDiagnostics.log(
                    "LOGIN_QR", "status" to status,
                    "token" to t.isNotEmpty(), "userid" to u.isNotEmpty(),
                    "vip_token" to vt.isNotEmpty()
                )
                if (t.isNotEmpty() && u.isNotEmpty()) {
                    applyLogin(t, u, vt)
                    // 登录响应没带会员凭据时补一次刷新（对齐 md3Music refreshLogin）：
                    // vip_token 是会员歌走 /song/url/new(priv_url) 的唯一凭据来源
                    if (vipToken == null) refreshLogin()
                }
            }
            Result.success(status)
        } catch (e: Exception) {
            Log.e(TAG, "loginQrCheck exception", e)
            Result.failure(e)
        }
    }

    /**
     * 用当前 token 刷新登录态（Rust GET /login/token → 上游 /v5/login_by_token）。
     *
     * 对齐 md3Music 的 refreshLogin / _tryRefreshToken
     * （kugou_api_client.dart:2686-2694 与 2728-2745）：它同时回传 **vip_token**，
     * 而 vip_token 是会员歌曲走 `/song/url/new`(priv_url) 的唯一凭据来源。
     * 本仓历史上从不调用它——登录响应若没带 vip_token，就永远拿不到会员能力
     * （现象：付费歌一律被上游按 `priv_status=0` 拒绝）。
     *
     * 只在"当前没有会员凭据"或显式需要时调用；失败不影响已登录态（token/userid 保留原值）。
     */
    suspend fun refreshLogin(): Boolean = withContext(Dispatchers.IO) {
        val t = token ?: return@withContext false
        val u = userid ?: return@withContext false
        try {
            if (!ensureServer()) return@withContext false
            val (code, body) = httpGetWithRetry(
                "/login/token",
                mapOf("token" to t, "userid" to u),
                maxRetries = 1
            )
            Log.d(TAG, "/login/token -> HTTP $code, body length=${body.length}")
            if (body.isNotEmpty()) {
                body.chunked(3500).forEachIndexed { idx, chunk ->
                    Log.d(TAG, "/login/token body[$idx]: $chunk")
                }
            }
            if (code !in 200..299 || body.isEmpty()) return@withContext false
            val json = JSONObject(body)
            if (json.optInt("status", 0) != 1) {
                Log.d(
                    TAG,
                    "/login/token 未成功: status=${json.optInt("status", -1)} " +
                        "error_code=${json.optInt("error_code", 0)}"
                )
                // 持久化留证：刷新被上游拒绝（未拿到新凭据）
                YosDiagnostics.log(
                    "AUTH_REFRESH_FAIL", "http" to code,
                    "status" to json.optInt("status", -1),
                    "error_code" to json.optInt("error_code", 0)
                )
                return@withContext false
            }
            val data = json.optJSONObject("data") ?: json
            val nt = data.optString("token", "").ifEmpty { json.optString("token", "") }.ifEmpty { t }
            val nu = data.optString("userid", "").ifEmpty { json.optString("userid", "") }.ifEmpty { u }
            val nv = data.optString("vip_token", "").ifEmpty { json.optString("vip_token", "") }
            // 刷新响应没带 vip_token 时保留原有凭据，绝不把它清空
            applyLogin(nt, nu, nv.ifEmpty { vipToken.orEmpty() })
            Log.d(TAG, "/login/token 刷新完成: hasVipToken=${vipToken != null}")
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "refreshLogin exception", e)
            // 持久化留证：刷新请求本身失败（非 2xx / 超时），拿不到新凭据
            YosDiagnostics.log("AUTH_REFRESH_FAIL", "why" to (e.message ?: e.javaClass.simpleName))
            false
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
                // 必须绕 apicache（Rust 缓存所有 200 响应 2 分钟）：否则新建/删除歌单后
                // 重新拉列表拿到的是旧缓存，用户会以为操作没生效（真机已复现）
                val (code, body) = httpGetWithRetry("/user/playlist", params, VIP_NO_CACHE_HEADERS)
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
                // 必须绕 apicache：收藏/加删歌后重拉「我喜欢」或歌单，2 分钟缓存会返回旧列表
                val (code, body) = httpGetWithRetry("/playlist/track/all/new", params, VIP_NO_CACHE_HEADERS)
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
     *
     * 走 [httpGetAllowNonOk]（非 2xx 也拿得到体）：上游把「listfile 查无此物」
     * 包在 HTTP 502 里（`{"error_code":20010,"errmsg":"get other listfile fail","data":{}}`），
     * 用 httpGetWithRetry 会把它当抖动退避重试 3 次后仍只抛 200 字原始 JSON。
     * 这里先识别确定性错误码再决定抛什么——见 [KugouUpstreamError.isListFileMissing]。
     */
    suspend fun getPlaylistSongsByGcid(globalCollectionId: String, page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val (code, body) = httpGetAllowNonOk(
                    "/playlist/track/all",
                    mapOf("global_collection_id" to globalCollectionId, "page" to page.toString(), "pagesize" to pageSize.toString()),
                    VIP_NO_CACHE_HEADERS
                )
                Log.d(TAG, "/playlist/track/all -> HTTP $code, body length=${body.length}, gcid=$globalCollectionId")
                if (KugouUpstreamError.isListFileMissing(body)) {
                    // 确定性业务失败（该 gcid 查无此物 / 传成数字 specialid），重试无意义。
                    // 换一句可读文案，别让 UI 直接吃 200 字原始 JSON。
                    Log.w(TAG, "/playlist/track/all listfile 缺失 gcid=$globalCollectionId: ${body.take(200)}")
                    return@withContext Result.failure(
                        IOException("该歌单暂不可用（上游未返回曲目列表）")
                    )
                }
                if (code !in 200..299) return@withContext Result.failure(IOException("/playlist/track/all HTTP $code: ${body.take(200)}"))
                if (body.isEmpty()) return@withContext Result.failure(IOException("Empty response body"))
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
            val (code, body) = httpGetWithRetry("/album/detail", mapOf("album_id" to albumId), VIP_NO_CACHE_HEADERS)
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
                    mapOf("album_id" to albumId, "page" to page.toString(), "pagesize" to pageSize.toString()),
                    VIP_NO_CACHE_HEADERS
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
            val (code, body) = httpGetWithRetry("/artist/detail", mapOf("id" to artistId), VIP_NO_CACHE_HEADERS)
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

    /** 歌手专辑（对齐上游 artist.rs：GET /artist/albums?id&page&pagesize&sort）。 */
    suspend fun getArtistAlbums(
        artistId: String,
        page: Int = 1,
        pageSize: Int = 30,
        sort: String = ""
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = buildMap {
                put("id", artistId)
                put("page", page.toString())
                put("pagesize", pageSize.toString())
                if (sort.isNotEmpty()) put("sort", sort)
            }
            val (code, body) = httpGetWithRetry("/artist/albums", params, VIP_NO_CACHE_HEADERS)
            Log.d(TAG, "/artist/albums -> HTTP $code, body length=${body.length}")
            if (code !in 200..299) return@withContext Result.failure(IOException("/artist/albums HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getArtistAlbums exception", e)
            Result.failure(e)
        }
    }

    /**
     * 歌手歌曲（对齐 Dart getArtistAudios，kugou_api_client.dart:2489；Rust artist.rs:41）。
     *   GET /artist/audios?id&page&pagesize&sort（Rust → POST /kmr/v1/audio_group/author）
     */
    suspend fun getArtistAudios(
        artistId: String,
        page: Int = 1,
        pageSize: Int = 30,
        sort: String = ""
    ): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = buildMap {
                    put("id", artistId)
                    put("page", page.toString())
                    put("pagesize", pageSize.toString())
                    if (sort.isNotEmpty()) put("sort", sort)
                }
                val (code, body) = httpGetWithRetry("/artist/audios", params, VIP_NO_CACHE_HEADERS)
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

    /**
     * 我关注的歌手列表（对齐 Dart getUserFollow，kugou_api_client.dart:3507；Rust user.rs handle_follow
     * → relationuser.kugou.com /v4/follow_list）。token/userid 经 authHeader() cookie 带入，
     * 加密 body 由 Rust 端自行构造，客户端无需业务参数。需登录。
     * 返回结构：data.lists[]（多 key 兜底 info/list/fans；条目 singerid/singername/pic 系，由调用方解析）。
     * 必须 bypass apicache：关注/取关后 2 分钟内重拉列表不能吃到旧缓存。
     */
    suspend fun getUserFollowedArtists(): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = userid?.let { mapOf("userid" to it) } ?: emptyMap()
            httpGetVipBusiness("/user/follow", params)
        } catch (e: Exception) {
            Log.e(TAG, "getUserFollowedArtists exception", e)
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
            val (code, body) = httpGetWithRetry("/playlist/detail", params, VIP_NO_CACHE_HEADERS)
            Log.d(TAG, "/playlist/detail -> HTTP $code, body length=${body.length}")
            body.chunked(3500).forEachIndexed { i, c -> Log.d(TAG, "/playlist/detail body[$i]: $c") }
            if (code !in 200..299) return@withContext Result.failure(IOException("/playlist/detail HTTP $code: ${body.take(200)}"))
            Result.success(JSONObject(body))
        } catch (e: Exception) {
            Log.e(TAG, "getPlaylistDetail exception", e)
            Result.failure(e)
        }
    }

    /**
     * 新建歌单（对齐 Dart createPlaylist，kugou_api_client.dart:3156；Rust playlist.rs:133）。
     *   GET /playlist/add?name=&type=0&source=1&is_pri=&list_create_userid=0&list_create_listid=0
     * （Rust → POST /cloudlist.service/v5/add_list；userid/token 经 authHeader() cookie 带入）
     * 走 httpGetVipBusiness：创建/删除必须绕 apicache，且业务错误可能包在 4xx 里。
     */
    suspend fun createPlaylist(name: String, isPri: Int = 0): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = mapOf(
                "name" to name,
                "type" to "0",
                "source" to "1",
                "is_pri" to isPri.toString(),
                "list_create_userid" to "0",
                "list_create_listid" to "0"
            )
            httpGetVipBusiness("/playlist/add", params)
        } catch (e: Exception) {
            Log.e(TAG, "createPlaylist exception", e)
            Result.failure(e)
        }
    }

    /**
     * 收藏专辑（对齐 md3Music `collectAlbum`，kugou_api_client.dart:4304；Rust playlist.rs handle_add）。
     *   GET /playlist/add?name=&type=1&source=2&list_create_userid=<artistId>
     *                    &list_create_listid=<albumId>&list_create_gid=
     * 语义：在账号下建一条「专辑收藏」条目（source=2 标记来源为专辑），之后在
     * /user/playlist 里以 type=1&source=2 出现；取消收藏走 /playlist/del?type=1。
     * 走 httpGetVipBusiness：必须绕 apicache，且业务错误可能包在 4xx 里。
     */
    suspend fun collectAlbum(
        name: String,
        artistId: String,
        albumId: String
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = mapOf(
                "name" to name,
                "type" to "1",
                "source" to "2",
                "list_create_userid" to artistId,
                "list_create_listid" to albumId,
                "list_create_gid" to ""
            )
            httpGetVipBusiness("/playlist/add", params)
        } catch (e: Exception) {
            Log.e(TAG, "collectAlbum exception", e)
            Result.failure(e)
        }
    }

    /**
     * 删除自建歌单（对齐 Dart deletePlaylist(listid, type=0)，kugou_api_client.dart:3208
     * 与上游 playlist_page.dart:788 一致用 type=0；Rust playlist.rs:184 → /v2/delete_list）。
     * 注意 is_def != 0 的系统歌单（我喜欢/默认收藏）不允许传给本接口（仓库层守卫）。
     */
    suspend fun deletePlaylist(listid: String, type: Int = 0): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/playlist/del", mapOf("listid" to listid, "type" to type.toString()))
        } catch (e: Exception) {
            Log.e(TAG, "deletePlaylist exception", e)
            Result.failure(e)
        }
    }

    // ==================== 歌曲评论（Rust comment_*.rs，参数契约与 md3Music 逐字一致） ====================
    // 读接口走 httpGetVipBusiness：评论实时变化，必须绕 apicache（2 分钟），且业务错误
    // 可能包在 4xx/502 里；写接口**只请求一次**——发布成功即产生真实公开内容，重试会重复
    // 发布（Rust comment_send.rs 顶部的安全边界）。日志不记正文（redactLog）。

    /** 评论写接口专用：单次请求，不重试；非 2xx 也保留业务 JSON（error_code/status）。 */
    private suspend fun httpGetBusinessOnce(
        path: String,
        params: Map<String, String>
    ): Result<JSONObject> = try {
        val (code, body) = httpGetOnce(path, params, VIP_NO_CACHE_HEADERS, redactLog = true)
        Log.d(TAG, "$path -> HTTP $code, body length=${body.length}")
        val business = if (body.isNotEmpty()) runCatching { JSONObject(body) }.getOrNull() else null
        when {
            business != null && (business.has("error_code") || business.has("status")) ->
                Result.success(business)
            code !in 200..299 -> Result.failure(IOException("$path HTTP $code: ${body.take(200)}"))
            body.isEmpty() -> Result.failure(IOException("$path empty response body"))
            business == null -> Result.failure(IOException("$path invalid JSON body"))
            else -> Result.success(business)
        }
    } catch (error: kotlinx.coroutines.CancellationException) {
        throw error
    } catch (error: Throwable) {
        Result.failure(error)
    }

    /**
     * 歌曲评论列表（Rust comment_music.rs → /mcomment/v1/cmtlist）。
     * [mixsongid] 即 album_audio_id（KugouRepository.songIdentityFor(hash)?.mixSongId）。
     * 响应含 data.list / data.total / data.childrenid（最热接口必需的评论区 id）。
     */
    suspend fun getMusicComments(mixsongid: String, page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                httpGetVipBusiness(
                    "/comment/music",
                    mapOf(
                        "mixsongid" to mixsongid,
                        "page" to page.toString(),
                        "pagesize" to pageSize.toString(),
                        "show_classify" to "1",
                        "show_hotword_list" to "1"
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "getMusicComments exception", e)
                Result.failure(e)
            }
        }

    /** 最热评论（/comment/music/topliked）：必传 childrenid，上游不接受 mixsongid。 */
    suspend fun getTopLikedComments(childrenId: String, page: Int = 1, pageSize: Int = 30): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                httpGetVipBusiness(
                    "/comment/music/topliked",
                    mapOf(
                        "childrenid" to childrenId,
                        "page" to page.toString(),
                        "pagesize" to pageSize.toString()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "getTopLikedComments exception", e)
                Result.failure(e)
            }
        }

    /** 楼层回复（/comment/floor）：special_id 取评论区 childrenid，tid 为楼层根评论 id。 */
    suspend fun getCommentFloor(
        specialId: String,
        tid: String,
        mixsongid: String?,
        page: Int = 1,
        pageSize: Int = 50
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = buildMap {
                put("special_id", specialId)
                put("tid", tid)
                put("page", page.toString())
                put("pagesize", pageSize.toString())
                mixsongid?.takeIf { it.isNotBlank() }?.let { put("mixsongid", it) }
            }
            httpGetVipBusiness("/comment/floor", params)
        } catch (e: Exception) {
            Log.e(TAG, "getCommentFloor exception", e)
            Result.failure(e)
        }
    }

    /**
     * 歌曲详情（Rust audio.rs → kmr /v1/audio/audio）：只用于兜底解析 album_audio_id。
     * 身份表（搜索/歌单/榜单/FM 解析时登记）没命中时才调用。
     */
    suspend fun getSongDetailByHash(hash: String): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            httpGetVipBusiness("/audio", mapOf("hash" to hash.lowercase()))
        } catch (e: Exception) {
            Log.e(TAG, "getSongDetailByHash exception", e)
            Result.failure(e)
        }
    }

    /** 发歌曲顶层评论（/comment/music/send → commentsv3/add）。只传 mixsongid 时 Rust 自行解析 special_id。 */
    suspend fun sendMusicComment(
        content: String,
        mixsongid: String?,
        specialId: String?
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = buildMap {
                put("content", content)
                mixsongid?.takeIf { it.isNotBlank() }?.let { put("mixsongid", it) }
                specialId?.takeIf { it.isNotBlank() }?.let { put("special_id", it) }
            }
            httpGetBusinessOnce("/comment/music/send", params)
        } catch (e: Exception) {
            Log.e(TAG, "sendMusicComment exception", e)
            Result.failure(e)
        }
    }

    /** 楼层回复（/comment/floor/send → commentsv2/reply）。pid 非空表示回复楼内某条回复。 */
    suspend fun sendFloorReply(
        content: String,
        specialId: String,
        tid: String,
        pid: String?,
        mixsongid: String?,
        replyUserName: String?,
        replyContent: String?
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = buildMap {
                put("content", content)
                put("special_id", specialId)
                put("tid", tid)
                put("resource_type", "song")
                pid?.takeIf { it.isNotBlank() }?.let { put("pid", it) }
                mixsongid?.takeIf { it.isNotBlank() }?.let { put("mixsongid", it) }
                replyUserName?.takeIf { it.isNotBlank() }?.let { put("reply_user_name", it) }
                replyContent?.takeIf { it.isNotBlank() }?.let { put("reply_content", it) }
            }
            httpGetBusinessOnce("/comment/floor/send", params)
        } catch (e: Exception) {
            Log.e(TAG, "sendFloorReply exception", e)
            Result.failure(e)
        }
    }

    /** 删除自己的评论（/comment/music/del → commentsv2/delcomment）。删楼中楼回复时 tid 必传。 */
    suspend fun deleteComment(
        cid: String,
        specialId: String?,
        mixsongid: String?,
        tid: String?
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = buildMap {
                put("cid", cid)
                specialId?.takeIf { it.isNotBlank() }?.let { put("special_id", it) }
                mixsongid?.takeIf { it.isNotBlank() }?.let { put("mixsongid", it) }
                tid?.takeIf { it.isNotBlank() }?.let { put("tid", it) }
            }
            httpGetBusinessOnce("/comment/music/del", params)
        } catch (e: Exception) {
            Log.e(TAG, "deleteComment exception", e)
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

    /**
     * 听歌等级查询/听歌时长上报（对齐 Dart getGradeInfo，kugou_api_client.dart:2826）：
     * GET /user/grade/info。查询模式不带参（返回服务器累计秒数 data.d_sec）；
     * 上报模式带 d_sec（本地累计总秒数）+ diff_sec（本次增量秒数），服务器按 diff_sec 记账。
     * 成功判定须 status==1 && error_code==0（由调用方 ListeningGradeTracker 校验）。
     */
    suspend fun getUserGradeInfo(dSec: Long? = null, diffSec: Long? = null): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                val params = buildMap {
                    if (dSec != null) put("d_sec", dSec.toString())
                    if (diffSec != null) put("diff_sec", diffSec.toString())
                }
                httpGetVipBusiness("/user/grade/info", params)
            } catch (e: Exception) {
                Log.e(TAG, "getUserGradeInfo exception", e)
                Result.failure(e)
            }
        }

    /**
     * 真实播放历史上报（对齐 Dart uploadPlayHistory，kugou_api_client.dart:3039）：
     * GET /playhistory/upload?mxid=&ot=&pc= → 上游 /playhistory/v1/upload_songs。
     * mxid 即 MixSongID（album_audio_id），ot 为秒级时间戳，pc 为播放次数。
     */
    suspend fun uploadPlayHistory(mxid: Long, ot: Long, playCount: Int = 1): Result<JSONObject> =
        withContext(Dispatchers.IO) {
            try {
                if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
                httpGetVipBusiness(
                    "/playhistory/upload",
                    mapOf(
                        "mxid" to mxid.toString(),
                        "ot" to ot.toString(),
                        "pc" to playCount.toString()
                    )
                )
            } catch (e: Exception) {
                Log.e(TAG, "uploadPlayHistory exception", e)
                Result.failure(e)
            }
        }

    /**
     * CSCC 真实播放事件上报（对齐 md3Music reportListenEvent，kugou_api_client.dart:3712）：
     * GET /user/listen/report?event=start|end&mixsongid=&duration=&state=。
     * end 事件必带 duration（实际播放毫秒，扣除暂停/拖动弹）；这是酷狗「真实播放统计」
     * 的记账信号——对部分账号 diff_sec 差量上报不记账，靠本事件累计听歌时长。
     * 会话与签名在 Rust 侧完成，本方法只传业务参数。
     */
    suspend fun reportListenEvent(
        event: String,
        mixsongid: String,
        duration: Long? = null,
        state: String? = null,
        deviceModel: String? = null,
        systemVersion: String? = null
    ): Result<JSONObject> = withContext(Dispatchers.IO) {
        try {
            if (!ensureServer()) return@withContext Result.failure(Exception("Server not running"))
            val params = buildMap {
                put("event", event)
                put("mixsongid", mixsongid)
                // CSCC 与等级接口同样有响应缓存，带变化时间戳绕过（POST/GET 皆然）
                put("timestamp", System.currentTimeMillis().toString())
                duration?.let { put("duration", it.toString()) }
                state?.let { put("state", it) }
                deviceModel?.let { put("device_model", it) }
                systemVersion?.let { put("system_version", it) }
            }
            httpGetVipBusiness("/user/listen/report", params)
        } catch (e: Exception) {
            Log.e(TAG, "reportListenEvent exception", e)
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
                    // 登录响应留证：会员凭据有没有回、回在哪一层（见 loginQrCheck 同款注释）
                    val raw = body.toString()
                    raw.chunked(3500).forEachIndexed { idx, chunk ->
                        Log.d(TAG, "/login/cellphone body[$idx]: $chunk")
                    }
                    when (val result = parsePhoneLoginResponse(toPlainAny(body))) {
                        is PhoneLoginResult.Success -> {
                            applyLogin(result.token, result.userid, result.vipToken)
                            // 持久化留证：验证码登录有没有带回会员凭据
                            YosDiagnostics.log(
                                "LOGIN_CELL", "status" to body.optInt("status", 0),
                                "token" to result.token.isNotEmpty(),
                                "userid" to result.userid.isNotEmpty(),
                                "vip_token" to result.vipToken.isNotEmpty()
                            )
                            // 登录响应没带会员凭据时补一次刷新（对齐 md3Music refreshLogin）
                            if (vipToken == null) refreshLogin()
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
