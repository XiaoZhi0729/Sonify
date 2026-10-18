package yos.music.player.native

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.math.BigInteger
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.interfaces.RSAPublicKey
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.TimeUnit

/**
 * 酷狗「内容黑名单」（不感兴趣）直连实现。
 *
 * 参考 md3Music 6.0.5：`kugou_api_client.editBlacklist` / `getBlacklist` →
 * Rust `blacklist.rs` → `POST relationuser.kugou.com/{v1/edit_list_items,v2/get_list_items}`。
 * 本项目的 `libkugou_server.so` 没有 `/blacklist` 路由，所以这里按 Rust 侧契约直连上游：
 *
 * - `p` = lite 公钥 raw RSA（无填充）加密 `{"clienttime":...,"token":"..."}`，输出 hex；
 * - query 签名 = md5(盐 + 按 key 排序的 `k=v` 直接拼接 + body + 盐)；
 * - 身份必须是概念版 lite（appid=3116 / clientver=11440），标准版会回 20018。
 *
 * 设备标识 dfid/mid 取自 Rust 服务落盘的 `filesDir/device_info.json`（与登录请求同源，
 * 避免风控把直连请求当成陌生设备）。
 */
internal object KugouBlacklistApi {
    private const val TAG = "KugouBlacklist"
    private const val HOST = "https://relationuser.kugou.com"
    private const val EDIT_PATH = "/v1/edit_list_items"
    private const val LIST_PATH = "/v2/get_list_items"
    private const val ROUTE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA"
    private const val APP_ID = "3116"
    private const val CLIENT_VER = "11440"
    private const val KG_TID = "473"
    private const val PAGE_SIZE = 500
    private const val MAX_PAGES = 10
    private const val UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"

    /** md3Music `crypto.rs::PUBLIC_LITE_RAS_KEY`（概念版公钥，与登录 token 的签发身份一致）。 */
    private const val LITE_PUBLIC_KEY =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDECi0Np2UR87scwrvTr72L6oO01rBbbBPriSDFPxr3" +
            "Z5syug0O24QyQO8bg27+0+4kBzTBTBOZ/WWU0WryL1JSXRTXLgFVxtzIY41Pe7lPOgsfTCn5kZcvKhYK" +
            "JesKnnJDNr5/abvTGf+rHG3YRwsCHcQ08/q6ifSioBszvb3QiwIDAQAB"

    private data class Auth(
        val token: String,
        val userId: String,
        val dfid: String,
        val mid: String,
    )

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val liteKey: RSAPublicKey by lazy {
        val der = Base64.decode(LITE_PUBLIC_KEY, Base64.DEFAULT)
        KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(der)) as RSAPublicKey
    }

    /**
     * 标记 / 取消「不感兴趣」（歌曲桶 source=3）。
     * 只上报服务端黑名单，不做任何本地过滤/列表删除（与 md3Music 一致）。
     */
    suspend fun setNotInterested(
        hash: String,
        name: String,
        mixSongId: Long,
        marked: Boolean,
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val auth = authOrNull()
                ?: return@withContext Result.failure(IllegalStateException("请先登录酷狗账号"))
            val clientTime = System.currentTimeMillis() / 1000
            val p = rsaRawHex(encryptPlain(token = auth.token, clientTime = clientTime))
            val items = JSONArray().put(
                JSONObject()
                    .put("k", hash.lowercase())
                    .put(
                        "v",
                        JSONObject()
                            .put("n", name)
                            .put("m", mixSongId.toString())
                            .put("t", clientTime.toString())
                            .toString()
                    )
            )
            // 字段顺序与 Rust json! 宏一致（serde_json preserve_order）
            val body = JSONObject()
                .put("userid", auth.userId.toLongOrNull() ?: 0L)
                .put("source", 3)
                .put("label", "song")
                .put("items", items)
                .put("action", if (marked) "add" else "delete")
                .put("p", p)
                .toString()
            signedPost(EDIT_PATH, body, auth, clientTime).map { Unit }
        } catch (e: Exception) {
            Log.e(TAG, "setNotInterested failed", e)
            Result.failure(e)
        }
    }

    /**
     * 拉取当前账号歌曲桶黑名单的全部 hash（分页，最多 10 页）。
     * 用于菜单里「不感兴趣 / 取消不感兴趣」的状态判定。
     */
    suspend fun fetchMarkedHashes(): Result<Set<String>> = withContext(Dispatchers.IO) {
        try {
            val auth = authOrNull()
                ?: return@withContext Result.failure(IllegalStateException("请先登录酷狗账号"))
            val all = linkedSetOf<String>()
            var page = 1
            while (page <= MAX_PAGES) {
                val clientTime = System.currentTimeMillis() / 1000
                val p = rsaRawHex(encryptPlain(token = auth.token, clientTime = clientTime))
                val body = JSONObject()
                    .put("userid", auth.userId.toLongOrNull() ?: 0L)
                    .put("source", 3)
                    .put("label", "song")
                    .put("p", p)
                    .put("page", page)
                    .put("pagesize", PAGE_SIZE)
                    .toString()
                val json = signedPost(LIST_PATH, body, auth, clientTime)
                    .getOrElse { return@withContext Result.failure(it) }
                val data = json.optJSONObject("data") ?: json
                val items = data.optJSONArray("items") ?: JSONArray()
                for (i in 0 until items.length()) {
                    val item = items.optJSONObject(i) ?: continue
                    val key = item.optString("song_k").ifEmpty { item.optString("k") }
                    if (key.isNotEmpty()) all += key.lowercase()
                }
                val total = data.optInt("total", all.size)
                if (items.length() < PAGE_SIZE || all.size >= total) break
                page++
            }
            Log.d(TAG, "blacklist loaded count=${all.size}")
            Result.success(all)
        } catch (e: Exception) {
            Log.e(TAG, "fetchMarkedHashes failed", e)
            Result.failure(e)
        }
    }

    private fun authOrNull(): Auth? {
        if (!KugouApiService.isLoggedIn()) return null
        val token = KugouApiService.token ?: return null
        val userId = KugouApiService.userid ?: return null
        val (dfid, mid) = readDeviceInfo()
        return Auth(token = token, userId = userId, dfid = dfid, mid = mid)
    }

    /**
     * 带 lite 签名的 POST。返回 HTTP 2xx 且业务成功（error_code 0/缺失、status 1/缺失）的 JSON。
     */
    private fun signedPost(
        path: String,
        body: String,
        auth: Auth,
        clientTime: Long,
    ): Result<JSONObject> {
        // 与 request.rs create_request 的默认 query 参数一致（uuid 固定 "-"）
        val params = linkedMapOf(
            "appid" to APP_ID,
            "clientver" to CLIENT_VER,
            "clienttime" to clientTime.toString(),
            "dfid" to auth.dfid,
            "mid" to auth.mid,
            "token" to auth.token,
            "userid" to auth.userId,
            "uuid" to "-",
        )
        val signature = signature(params, body)
        val url = (HOST + path).toHttpUrl().newBuilder().apply {
            params.forEach { (k, v) -> addQueryParameter(k, v) }
            addQueryParameter("signature", signature)
        }.build()
        val request = Request.Builder()
            .url(url)
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .header("User-Agent", UA)
            .header("dfid", auth.dfid)
            .header("mid", auth.mid)
            .header("clienttime", clientTime.toString())
            .header("kg-rc", "1")
            .header("kg-thash", "5d816a0")
            .header("kg-rec", "1")
            .header("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F")
            .header("KG-TID", KG_TID)
            .header(
                "Cookie",
                "KUGOU_API_PLATFORM=lite; token=${auth.token}; userid=${auth.userId}"
            )
            .build()
        return client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty()
            Log.d(TAG, "$path -> HTTP ${response.code}, body=${text.take(400)}")
            if (!response.isSuccessful) {
                Result.failure(IOException("HTTP ${response.code}: ${text.take(160)}"))
            } else {
                val json = runCatching { JSONObject(text) }.getOrNull()
                if (json == null) {
                    Result.failure(IOException("$path 响应不是 JSON"))
                } else {
                    // md3Music isBizOk：error_code 0/缺失、status 1/缺失
                    val errorCode = json.optInt("error_code", 0)
                    val status = json.optInt("status", 1)
                    if (errorCode != 0 || status != 1) {
                        Result.failure(
                            IOException(
                                "error_code=$errorCode status=$status " +
                                    json.optString("error_msg")
                            )
                        )
                    } else {
                        Result.success(json)
                    }
                }
            }
        }
    }

    private fun encryptPlain(token: String, clientTime: Long): String =
        """{"clienttime":$clientTime,"token":${JSONObject.quote(token)}}"""

    /** md5(盐 + 按 key 排序的 `k=v` 直接拼接 + body + 盐)，对齐 Rust signature_android_params。 */
    private fun signature(params: Map<String, String>, body: String): String {
        val joined = params.entries
            .sortedBy { it.key }
            .joinToString(separator = "") { "${it.key}=${it.value}" }
        return md5(ROUTE_SALT + joined + body + ROUTE_SALT)
    }

    private fun md5(value: String): String {
        val digest = MessageDigest.getInstance("MD5")
            .digest(value.toByteArray(Charsets.UTF_8))
        return buildString(digest.size * 2) {
            digest.forEach { append("%02x".format(it)) }
        }
    }

    /**
     * Raw RSA（无填充），对齐 JS cryptoRSAEncrypt：
     * 明文右侧补零到模长 → BigEndian modPow → 左补零到模长 → hex。
     */
    private fun rsaRawHex(plain: String): String {
        val key = liteKey
        val modLen = (key.modulus.bitLength() + 7) / 8
        val data = plain.toByteArray(Charsets.UTF_8)
        require(data.size <= modLen) { "RSA plaintext too long: ${data.size} > $modLen" }
        val padded = ByteArray(modLen).also { data.copyInto(it) }
        val cipher = BigInteger(1, padded).modPow(key.publicExponent, key.modulus)
        val raw = cipher.toByteArray()
        val out = ByteArray(modLen)
        val copyLen = minOf(raw.size, modLen)
        System.arraycopy(raw, raw.size - copyLen, out, modLen - copyLen, copyLen)
        return buildString(modLen * 2) {
            out.forEach { append("%02x".format(it)) }
        }
    }

    /** Rust 服务落盘的设备身份（与登录请求同源）；缺失时回退 "-"（与 Rust 默认一致）。 */
    private fun readDeviceInfo(): Pair<String, String> {
        return try {
            val dir = KugouApiService.appFilesDir ?: return "-" to "-"
            val file = File(dir, "device_info.json")
            if (!file.exists()) return "-" to "-"
            val json = JSONObject(file.readText())
            val dfid = json.optString("dfid").ifEmpty { "-" }
            val mid = json.optString("mid").ifEmpty { "-" }
            dfid to mid
        } catch (e: Exception) {
            Log.w(TAG, "readDeviceInfo failed", e)
            "-" to "-"
        }
    }
}
