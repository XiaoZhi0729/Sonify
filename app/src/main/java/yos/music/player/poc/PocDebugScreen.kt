package yos.music.player.poc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Base64
import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONObject
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.native.KugouApiService
import yos.music.player.native.KugouNativeBridge

private const val TAG = "PocDebugScreen"

/** Checkpoint 6 实验固定歌曲：晴天 - 周杰伦（Checkpoint 5 真实搜索结果）。
 *  登录前后必须使用完全相同的 hash 和 quality，只改变登录状态这一个变量。 */
private const val TEST_HASH = "b3a52a7a958bf0aed0ebfba2e9a818b7"

/** Data class for search result song info. Used in PocDebugScreen and elsewhere. */
data class SongInfo(
    val hash: String,
    val mid: String,
    val name: String,
    val author: String,
    val duration: Long
)

/**
 * Minimal POC debug screen for testing md3Music Rust Native online playback.
 * 
 * Checkpoint flow:
 * 1. Load .so
 * 2. Start server
 * 3. Test HTTP API
 * 4. Search songs
 * 5. Get playback URL
 * 6. Create YosMediaItem
 * 7. Play via MediaController
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PocDebugScreen(
    onNavigateUp: () -> Unit = {}
) {
    val context = LocalContext.current // Get context at composable level
    
    var serverStatus by remember { mutableStateOf("Not started") }
    var port by remember { mutableIntStateOf(0) }
    var isLoading by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<SongInfo>>(emptyList()) }
    var searchText by remember { mutableStateOf("") }
    var logMessages by remember { mutableStateOf<List<String>>(emptyList()) }
    var playableUrl by remember { mutableStateOf<String?>(null) }

    // ---------- Checkpoint 6 登录实验状态（临时，验证后移除） ----------
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrStatus by remember { mutableStateOf("未生成") }
    var loginBusy by remember { mutableStateOf(false) }
    var beforeResult by remember { mutableStateOf("") }
    var afterResult by remember { mutableStateOf("") }

    // ---------- Checkpoint 7 播放状态（临时，验证后移除） ----------
    var realSongUrl by remember { mutableStateOf<String?>(null) }
    var playStateText by remember { mutableStateOf("未播放") }
    var rememberedObserver by remember { mutableStateOf(false) }
    var qrKey by remember { mutableStateOf("") }

    // ---------- POC Capability Verification State (临时)
    var serverRunning by remember { mutableStateOf(KugouNativeBridge.isServerRunning()) }
    var currentPort by remember { mutableIntStateOf(KugouNativeBridge.getServerPort()) }
    var baseUrl by remember { mutableStateOf(runCatching { KugouNativeBridge.getBaseUrl() }.getOrDefault("N/A")) }
    
    val qualityResults = remember { mutableStateOf(mutableMapOf<String, String>()) }
    var lyricResult by remember { mutableStateOf<String?>(null) }
    var playlistResult by remember { mutableStateOf<String?>(null) }
    var albumResult by remember { mutableStateOf<String?>(null) }
    var artistResult by remember { mutableStateOf<String?>(null) }
    var commentResult by remember { mutableStateOf<String?>(null) }
    var mediaItemCheck by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()

    // ---------- POC Capability Test Functions (临时)
    fun log(msg: String) {
        val timestamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
        logMessages = listOf("$timestamp: $msg") + logMessages.take(49)
    }

    fun ensureServerAndPort() {
        if (!KugouNativeBridge.isServerRunning()) {
            val p = KugouNativeBridge.startServer(dataDir = context.filesDir.absolutePath)
            if (p > 0) {
                serverRunning = true
                currentPort = p
                baseUrl = "http://127.0.0.1:$p"
                log("POC: Server started on port $p")
            }
        } else {
            serverRunning = KugouNativeBridge.isServerRunning()
            currentPort = KugouNativeBridge.getServerPort()
            baseUrl = if (currentPort > 0) "http://127.0.0.1:$currentPort" else "N/A"
        }
    }

    suspend fun testQuality(quality: String) {
        try {
            isLoading = true
            ensureServerAndPort()
            
            log("Testing quality=$quality with hash=$TEST_HASH")
            val result = KugouApiService.getInstance().getSongUrl(TEST_HASH, quality)
            
            result.fold(
                onSuccess = { json ->
                    val data = json.optJSONObject("data") ?: json
                    val status = data.optInt("status", -1)
                    val errorCode = data.optInt("error_code", -1)
                    val url = data.optString("url", "")
                    val hasUrl = url.isNotEmpty()
                    val fileSize = data.optLong("fileSize", data.optLong("file_size", 0))
                    val bitRate = data.optInt("bitRate", data.optInt("bitrate", 0))
                    val format = data.optString("format", "")
                    val timeLength = data.optLong("timeLength", data.optLong("timelength", 0))
                    val privStatus = data.optInt("priv_status", -1)
                    val failProcess = json.optJSONArray("fail_process")?.toString() ?: "-"
                    
                    val success = status == 2 || errorCode == 0
                    val summary = when {
                        !success -> "❌ Failed (status=$status, error_code=$errorCode)"
                        !hasUrl -> "❌ No URL returned"
                        else -> "✅ Success | ${if (quality == "high") "Hi-Res" else quality.uppercase()} | size=${fileSize}KB bitrate=${bitRate}kbps dur=${timeLength}s priv_status=$privStatus"
                    }
                    
                    qualityResults.value[quality] = summary
                    qualityResults.value["all_passed"] = if (qualityResults.value.size == 4) "全部通过 ✅" else "部分失败 ⚠️"
                    
                    log("Quality $quality: $summary")
                },
                onFailure = { e ->
                    qualityResults.value[quality] = "❌ Exception: ${e.message}"
                    log("Quality $quality failed: ${e.message}")
                }
            )
            isLoading = false
        } catch (ex: Exception) {
            log("testQuality exception: ${ex.message}")
            isLoading = false
        }
    }

    suspend fun testLyric() {
        try {
            isLoading = true
            ensureServerAndPort()

            log("Testing lyric for hash=$TEST_HASH ...")
            // 真实两步链路（对齐 Dart kugou_api_client.getLyric）：
            // /search/lyric?hash → candidates[0].id/accesskey → /lyric?id&accesskey&fmt=lrc&decode=true
            KugouApiService.getInstance().getLyric(TEST_HASH).fold(
                onSuccess = { json ->
                    val data = json.optJSONObject("data") ?: json
                    val decoded = data.optString("decodeContent", "")
                    val status = json.optInt("status", -1)
                    if (decoded.isNotEmpty()) {
                        val lines = decoded.lines().filter { it.isNotBlank() }
                        lyricResult = "✅ 真实歌词 (status=$status, ${decoded.length}字符, ${lines.size}行)\n" +
                                lines.take(6).joinToString("\n") + "\n..."
                        log("✓ Lyric OK: ${decoded.length} chars, first=${lines.firstOrNull()?.take(40)}")
                    } else {
                        lyricResult = "❌ 无 decodeContent (status=$status)"
                        log("✗ Lyric: empty decodeContent")
                    }
                },
                onFailure = { e ->
                    lyricResult = "❌ ${e.message}"
                    log("✗ Lyric failed: ${e.message}")
                }
            )
            isLoading = false
        } catch (ex: Exception) {
            lyricResult = "Exception: ${ex.message}"
            isLoading = false
        }
    }

    suspend fun testMyPlaylists() {
        try {
            isLoading = true
            ensureServerAndPort()
            mediaItemCheck = null

            if (!KugouApiService.isLoggedIn()) {
                playlistResult = "❌ 未登录，请先在 Checkpoint 6 扫码登录"
                log("✗ Playlist: not logged in")
                isLoading = false
                return
            }

            log("Fetching user playlists...")
            val plJson = KugouApiService.getInstance().getMyPlaylists().getOrNull()
            if (plJson == null) {
                playlistResult = "❌ /user/playlist 请求失败"
                log("✗ /user/playlist failed")
                isLoading = false
                return
            }
            val plData = plJson.optJSONObject("data") ?: plJson
            val arr = plData.optJSONArray("info")
                ?: plData.optJSONArray("list")
                ?: plData.optJSONArray("special_list")
            if (arr == null || arr.length() == 0) {
                playlistResult = "❌ 歌单列表为空（data.info 缺失或无歌单）"
                log("✗ Playlist list empty")
                isLoading = false
                return
            }
            // 实测 /user/playlist 返回字段为 name/count/pic/listid/global_collection_id/is_def
            //（与 Dart KugouPlaylistBrief 的 specialname/songcount/imgurl 双写兼容同款策略）。
            // 第一个歌单可能为空（如“默认收藏” count=0），选第一个非空歌单拉详情，
            // 与 Dart favorites_provider 找“我喜欢”(is_def=2) 的语义一致。
            var p0: org.json.JSONObject? = null
            for (i in 0 until arr.length()) {
                val item = arr.optJSONObject(i) ?: continue
                val cnt = item.optInt("count", item.optInt("songcount", 0))
                if (cnt > 0) { p0 = item; break }
            }
            if (p0 == null) p0 = arr.optJSONObject(0)
            val pl = p0!!
            val pid = pl.optString("specialid", pl.optString("global_collection_id", pl.optString("id", "")))
            val pname = pl.optString("name", pl.optString("specialname", ""))
            val cover = pl.optString("imgurl", pl.optString("pic", "")).replace("{size}", "400")
            val count = pl.optInt("count", pl.optInt("songcount", 0))
            val listid = pl.optString("listid", "")
            val gid = pl.optString("global_collection_id", "")
            log("✓ 选中歌单: name=$pname id=$pid listid=$listid gid=$gid count=$count")

            // 歌单详情歌曲（优先 listid 链路，与 Dart favorites_provider 一致）
            val useListid = listid.ifEmpty { gid }
            val tracksJson = KugouApiService.getInstance().getPlaylistSongsByListid(useListid).getOrNull()
            val tData = tracksJson?.optJSONObject("data")
            val tArr = tData?.optJSONArray("info")
                ?: tData?.optJSONArray("songs")
                ?: tData?.optJSONArray("list")
            if (tArr == null || tArr.length() == 0) {
                playlistResult = "✅ 歌单列表真实返回（${arr.length()} 个歌单）\n选中: $pname (id=$pid, $count 首)\n❌ 该歌单歌曲接口返回为空"
                log("✗ Playlist tracks empty (listid=$useListid)")
                isLoading = false
                return
            }
            val sb = StringBuilder()
            sb.append("✅ 歌单列表真实返回（${arr.length()} 个歌单）\n选中: $pname | listid=$listid | $count 首\ncover=$cover\n")
            var firstHash = ""; var firstName = ""; var firstArtist = ""; var firstDur = 0L
            val n = minOf(3, tArr.length())
            for (i in 0 until n) {
                val s = tArr.getJSONObject(i)
                // /playlist/track/all/new 实测字段：hash/name/singername/timelen（KugouSongDetail 双写兼容）。
                // 注意：timelen 实测单位为毫秒（与 /search 的 Duration 秒不同），映射时不再 *1000
                val hash = s.optString("hash", s.optString("Hash", ""))
                val name = s.optString("songname", s.optString("name", s.optString("SongName", "")))
                // 实测该接口无 singername 字段，歌手在 singerinfo[].name 数组
                //（对齐 Dart KugouSongDetail.fromJson：singerinfo → authors → singername 链）
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
                val dur = s.optLong("timelen", s.optLong("duration", 0))
                if (i == 0) { firstHash = hash; firstName = name; firstArtist = artist; firstDur = dur }
                sb.append("$i. $name - $artist (hash=${hash.take(8)}…, ${dur / 1000}s)\n")
            }

            // 映射验证：第一首 → 现有 YosMediaItem（字段对齐 Checkpoint 7 playRealSong）
            if (firstHash.isNotEmpty()) {
                val mapped = YosMediaItem(
                    uri = Uri.EMPTY,
                    mediaId = "poc-pl-$firstHash",
                    mimeType = "audio/mpeg",
                    title = firstName,
                    writer = null,
                    compilation = null,
                    composer = null,
                    artists = firstArtist,
                    album = null,
                    albumArtists = null,
                    thumb = null,
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
                    author = firstArtist,
                    addDate = null,
                    duration = firstDur,
                    modifiedDate = null,
                    cdTrackNumber = null
                )
                mediaItemCheck = "✓ YosMediaItem 映射成功: title=${mapped.title}, artists=${mapped.artists}, mediaId=${mapped.mediaId}"
                log("✓ YosMediaItem mapped: ${mapped.title}")
            }
            playlistResult = sb.toString()
            log("✓ Playlist tracks: ${tArr.length()} songs")
            isLoading = false
        } catch (ex: Exception) {
            playlistResult = "Exception: ${ex.message}"
            isLoading = false
        }
    }

    suspend fun testAlbum() {
        try {
            isLoading = true
            ensureServerAndPort()
            log("Album: /search/album 晴天 → /album/detail")
            // 第一步：专辑搜索拿真实 album_id（不硬编码 ID）；data 可能是 Map（含 info）或直接数组
            val sJson = KugouApiService.getInstance().searchAlbums("晴天").getOrNull()
            val sDataAny = sJson?.opt("data")
            val sArr = when (sDataAny) {
                is org.json.JSONArray -> sDataAny
                is JSONObject -> sDataAny.optJSONArray("info") ?: sDataAny.optJSONArray("list")
                else -> null
            }
            val s0 = sArr?.optJSONObject(0)
            val albumId = s0?.optString("albumid", s0?.optString("album_id", "")) ?: ""
            val albumName = s0?.optString("albumname", s0?.optString("album_name", "")) ?: ""
            if (albumId.isEmpty()) {
                albumResult = "❌ /search/album 未返回 album_id"
                log("✗ Album: no album_id from search")
                isLoading = false
                return
            }
            log("搜到专辑: $albumName (album_id=$albumId)")
            // 第二步：专辑详情
            KugouApiService.getInstance().getAlbumDetail(albumId).fold(
                onSuccess = { json ->
                    // Dart getAlbumDetail 同款多格式兼容：data 可能是 Map 或 List
                    val rawData = json.opt("data")
                    val info = when (rawData) {
                        is JSONObject -> {
                            val arr = rawData.optJSONArray("info")
                            arr?.optJSONObject(0) ?: rawData
                        }
                        is org.json.JSONArray -> rawData.optJSONObject(0)
                        else -> null
                    }
                    val name = info?.optString("album_name", info?.optString("albumname", "")) ?: ""
                    val cover = info?.optString("sizable_cover", info?.optString("cover_url", "")) ?: ""
                    val publish = info?.optString("publish_date", "") ?: ""
                    if (name.isNotEmpty() || cover.isNotEmpty()) {
                        albumResult = "✅ 专辑详情真实返回\n搜索: $albumName (id=$albumId)\n详情: name=$name publish=$publish\ncover=${cover.take(80)}"
                        log("✓ Album detail OK: $name")
                    } else {
                        albumResult = "⚠️ /album/detail 已返回但无 name/cover（完整 body 见 Logcat）"
                        log("⚠️ Album detail: no name/cover fields")
                    }
                },
                onFailure = { e ->
                    albumResult = "❌ /album/detail 失败: ${e.message}"
                    log("✗ Album detail failed: ${e.message}")
                }
            )
            isLoading = false
        } catch (ex: Exception) {
            albumResult = "Exception: ${ex.message}"
            isLoading = false
        }
    }

    suspend fun testArtist() {
        try {
            isLoading = true
            ensureServerAndPort()
            log("Artist: /search/artist 周杰伦 → /artist/detail")
            val sJson = KugouApiService.getInstance().searchArtists("周杰伦").getOrNull()
            // 实测 /search/artist 的 data 直接是数组 [{singername,singerid},...]（非 Map），双形态兼容
            val sDataAny = sJson?.opt("data")
            val sArr = when (sDataAny) {
                is org.json.JSONArray -> sDataAny
                is JSONObject -> sDataAny.optJSONArray("info") ?: sDataAny.optJSONArray("list")
                else -> null
            }
            val s0 = sArr?.optJSONObject(0)
            val artistId = s0?.optString("singerid", s0?.optString("singer_id", "")) ?: ""
            val artistName = s0?.optString("singername", s0?.optString("singer_name", "")) ?: ""
            if (artistId.isEmpty()) {
                artistResult = "❌ /search/artist 未返回 singerid"
                log("✗ Artist: no singerid from search")
                isLoading = false
                return
            }
            log("搜到歌手: $artistName (singerid=$artistId)")
            KugouApiService.getInstance().getArtistDetail(artistId).fold(
                onSuccess = { json ->
                    // /artist/detail 实测 data 也可能是数组，双形态兼容
                    val dataAny = json.opt("data")
                    val info = when (dataAny) {
                        is org.json.JSONArray -> dataAny.optJSONObject(0)
                        is JSONObject -> dataAny.optJSONArray("info")?.optJSONObject(0) ?: dataAny
                        else -> null
                    } ?: json
                    val name = info.optString("author_name", info.optString("singername", ""))
                    val intro = info.optString("intro", "")
                    if (name.isNotEmpty()) {
                        artistResult = "✅ 歌手详情真实返回\n搜索: $artistName (id=$artistId)\n详情: name=$name\nintro=${intro.take(60)}…"
                        log("✓ Artist detail OK: $name")
                    } else {
                        artistResult = "⚠️ /artist/detail 已返回但无 name（完整 body 见 Logcat）"
                        log("⚠️ Artist detail: no name field")
                    }
                },
                onFailure = { e ->
                    artistResult = "❌ /artist/detail 失败: ${e.message}"
                    log("✗ Artist detail failed: ${e.message}")
                }
            )
            isLoading = false
        } catch (ex: Exception) {
            artistResult = "Exception: ${ex.message}"
            isLoading = false
        }
    }

    suspend fun testComment() {
        try {
            isLoading = true
            ensureServerAndPort()
            // Placeholder for /comment/music
            commentResult = "⚠️  Optional feature - skip for now"
            isLoading = false
        } catch (ex: Exception) {
            commentResult = "Exception: ${ex.message}"
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        // Update server status immediately
        try {
            serverRunning = KugouNativeBridge.isServerRunning()
            currentPort = KugouNativeBridge.getServerPort()
            baseUrl = if (serverRunning && currentPort > 0) {
                "http://127.0.0.1:$currentPort"
            } else {
                "N/A (server not running)"
            }
            log("POC Capability: Server=$serverRunning, Port=$currentPort, BaseURL=$baseUrl")
        } catch (e: Exception) {
            log("Failed to read server state: ${e.message}")
        }
    }

    // 统一搜索流程（UI 按钮与临时广播触发共用）。
    // POC 临时设施：广播触发器仅用于 adb 命令行验证，不依赖触摸注入，
    // Checkpoint 5 验证完成后随本次 POC 代码一起移除。
    fun runSearch() {
        if (searchText.isBlank() || isLoading) return
        scope.launch {
            isLoading = true

            // Ensure server is running
            if (!KugouNativeBridge.isServerRunning()) {
                val p = KugouNativeBridge.startServer(dataDir = context.filesDir.absolutePath)
                if (p <= 0) {
                    log("Server start failed, aborting search")
                    isLoading = false
                    return@launch
                }
                port = p
            }

            // Checkpoint 3: Access local API
            log("Checkpoint 3: Accessing local HTTP API...")
            val success = KugouApiService.getInstance().testConnection()
            if (!success) {
                log("✗ Checkpoint 3 FAILED: Cannot reach server")
                isLoading = false
                return@launch
            }
            log("✓ Checkpoint 3 PASSED: HTTP API accessible")

            // Checkpoint 5: Search via Rust local HTTP server /search
            log("Checkpoint 5: Searching for '$searchText' via /search ...")
            val result = KugouApiService.getInstance().searchSongs(searchText)
            result.fold(
                onSuccess = { json ->
                    // 实测结构（poc_search.json）：
                    // { status, error_code, error_msg, data: { total, page, pagesize, lists: [ {...} ] } }
                    val data = json.optJSONObject("data") ?: json
                    val list = data.optJSONArray("lists")
                        ?: data.optJSONArray("songs")
                        ?: data.optJSONArray("info")
                    if (list == null) {
                        log("✗ Checkpoint 5 FAILED (layer G): no lists/songs/info array in response")
                        isLoading = false
                        return@fold
                    }
                    val total = data.optInt("total", 0)

                    val songs = (0 until list.length()).mapNotNull { i ->
                        runCatching {
                            val song = list.getJSONObject(i)
                            // 双写兼容（md3Music kugou_models.dart 同款策略）：
                            // PascalCase / lowercase 字段名、数组 / 字符串歌手名。
                            val name = song.optString("SongName").ifEmpty { song.optString("songname") }
                            val author = song.optString("SingerName").ifEmpty { song.optString("singername") }
                            val hash = song.optString("FileHash").ifEmpty { song.optString("hash") }
                            val durationSec = song.optLong("Duration", song.optLong("duration", 0))
                            SongInfo(
                                hash = hash,
                                mid = song.optString("MixSongID", song.optString("mid")),
                                name = name,
                                author = author,
                                duration = durationSec * 1000
                            )
                        }.getOrNull()
                    }

                    searchResults = songs
                    if (songs.isNotEmpty()) {
                        log("✓ Checkpoint 5 PASSED: parsed ${songs.size} songs (server total=$total)")
                    } else {
                        log("✗ Checkpoint 5 FAILED (layer G/H): JSON list empty or unparsed")
                    }
                    isLoading = false
                },
                onFailure = { e ->
                    log("✗ Checkpoint 5 FAILED: ${e.message}")
                    isLoading = false
                }
            )
        }
    }

    // ---------- Checkpoint 6 登录实验（临时，验证后移除） ----------

    /** 从 /song/url 响应提取关键字段生成对照记录（完整 body 已在 Logcat）。
     *  同时把真实 url 写入 realSongUrl，供 Checkpoint 7 播放按钮使用。 */
    fun summarizeSongUrl(label: String, json: JSONObject): String {
        val data = json.optJSONObject("data") ?: json
        var url = ""
        when (val raw = data.opt("url") ?: data.opt("play_url")) {
            is String -> url = raw
            is org.json.JSONArray -> if (raw.length() > 0) url = raw.optString(0, "")
        }
        val errCode = json.optInt("error_code", data.optInt("error_code", 0))
        val status = json.optInt("status", -1)
        val fileSize = data.optLong("fileSize", data.optLong("file_size", 0L))
        val bitRate = data.optInt("bitRate", data.optInt("bit_rate", 0))
        if (status == 1 && errCode == 0 && url.isNotEmpty()) {
            realSongUrl = url
            log("✓ $label 真实播放 URL: $url")
        } else {
            log("✗ $label 未拿到 URL: status=$status err=$errCode")
        }
        Log.d(TAG, "$label /song/url summary: status=$status error_code=$errCode hasUrl=${url.isNotEmpty()} fileSize=$fileSize bitRate=$bitRate")
        if (url.isNotEmpty()) Log.d(TAG, "$label url=$url")
        return "$label: status=$status err=$errCode url=${if (url.isNotEmpty()) "YES" else "NO"} size=$fileSize br=$bitRate"
    }

    /** 用固定歌曲请求 /song/url，把摘要写入指定结果槽。 */
    fun testSongUrl(label: String, setter: (String) -> Unit) {
        scope.launch {
            log("Testing /song/url ($label, loggedIn=${KugouApiService.isLoggedIn()})...")
            KugouApiService.getInstance().getSongUrl(TEST_HASH, "128").fold(
                onSuccess = { json -> setter(summarizeSongUrl(label, json)) },
                onFailure = { e ->
                    // 非 2xx 时 message 含响应体开头（如 HTTP 502: {\"error_code\":31833...}）
                    setter("$label: ${e.message?.take(150)}")
                    log("✗ /song/url $label: ${e.message?.take(100)}")
                }
            )
        }
    }

    /** Checkpoint 7：把真实 URL → YosMediaItem → 现有 MediaController.prepare → Media3 播放。
     *  复用 NormalMusic.kt:217 的既有调用模式：scope.launch(Dispatchers.IO) { prepare(music, list) }。 */
    fun playRealSong() {
        val url = realSongUrl
        if (url.isNullOrEmpty()) {
            log("✗ Checkpoint 7: 没有可用 URL，请先点③拿到真实播放 URL")
            playStateText = "无 URL，先点③"
            return
        }
        if (MediaController.mediaControl == null) {
            log("✗ Checkpoint 7: mediaControl 尚未连接（MediaSession 未就绪）")
            playStateText = "mediaControl 未连接"
            return
        }
        scope.launch(Dispatchers.IO) {
            log("Checkpoint 7: 构建 YosMediaItem 并调用 MediaController.prepare")
            // YosMediaItem 字段对齐 MusicLibrary.kt:75 与 toMediaItem()（mediaId 非空，duration 毫秒）
            val music = YosMediaItem(
                uri = Uri.parse(url),
                mediaId = "poc-20160901-$TEST_HASH",
                mimeType = "audio/mpeg",
                title = "晴天",
                writer = null,
                compilation = null,
                composer = null,
                artists = "周杰伦",
                album = null,
                albumArtists = null,
                thumb = null,
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
                author = "周杰伦",
                addDate = null,
                duration = 269_000L,
                modifiedDate = null,
                cdTrackNumber = null
            )
            val list = listOf(music)
            try {
                MediaController.prepare(music, list)
                log("✓ Checkpoint 7: prepare 已调用")
            } catch (e: Exception) {
                log("✗ Checkpoint 7: prepare 异常 ${e.message}")
                playStateText = "prepare 异常: ${e.message?.take(80)}"
            }
        }
    }

    /** 监听 MediaController/Player 状态，输出检测证据（STATE_READY / isPlaying / 进度 / error）。
     *  在首次调用播放时执行 addListener，避免重复注册。 */
    fun attachPlayerObserver() {
        val controller = MediaController.mediaControl ?: return
        val alreadyAttached = rememberedObserver
        if (alreadyAttached) return
        rememberedObserver = true
        controller.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                val stateName = when (state) {
                    Player.STATE_IDLE -> "IDLE"
                    Player.STATE_BUFFERING -> "BUFFERING"
                    Player.STATE_READY -> "READY"
                    Player.STATE_ENDED -> "ENDED"
                    else -> "?"
                }
                val dur = controller.duration.takeIf { it > 0 }
                val pos = controller.currentPosition
                runCatching {
                    playStateText = "state=$stateName($state) dur=${dur ?: "?"} pos=${pos}ms isPlaying=${controller.isPlaying} pwr=${controller.playWhenReady}"
                }
                Log.d(TAG, "Checkpoint7 onPlaybackStateChanged -> $stateName dur=$dur pos=$pos isPlaying=${controller.isPlaying}")
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                Log.d(TAG, "Checkpoint7 onIsPlayingChanged -> $isPlaying")
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                Log.e(TAG, "Checkpoint7 onPlayerError -> ${error.errorCodeName} ${error.message}")
                runCatching { playStateText = "ERROR ${error.errorCodeName}: ${error.message?.take(60)}" }
            }
        })
    }

    /** QR 登录第二步：轮询 check（状态码语义同 md3Music login_page.dart）。
     *  4=成功；2/803=已扫码待确认；800/0/402=过期。 */
    fun pollQr() {
        scope.launch {
            var attempts = 0
            while (attempts < 120 && !KugouApiService.isLoggedIn()) {
                delay(1500)
                attempts++
                val st = KugouApiService.getInstance().loginQrCheck(qrKey).getOrNull() ?: continue
                when (st) {
                    4 -> {
                        qrStatus = "✓ 已登录 (userid=${KugouApiService.userid ?: "?"})"
                        log("✓ QR 登录成功，token/userid/vip_token 已保存在 Kotlin 内存（不显示明文）")
                        loginBusy = false
                        return@launch
                    }
                    2, 803 -> qrStatus = "已扫码，请在手机上确认登录..."
                    800, 0, 402 -> {
                        qrStatus = "二维码已过期，请重新生成"
                        loginBusy = false
                        return@launch
                    }
                }
            }
            if (!KugouApiService.isLoggedIn()) {
                qrStatus = "轮询超时，请重新生成"
                loginBusy = false
            }
        }
    }

    /** QR 登录第一步：取 key + 生成二维码图片（Rust 本地渲染 PNG base64）。 */
    fun refreshQr() {
        if (loginBusy) return
        loginBusy = true
        qrStatus = "请求二维码..."
        qrBitmap = null
        scope.launch {
            val api = KugouApiService.getInstance()
            val key = api.loginQrKey().getOrNull()
            if (key == null) {
                qrStatus = "失败: key 请求未返回"
                loginBusy = false
                return@launch
            }
            qrKey = key
            api.loginQrCreate(key).fold(
                onSuccess = { dataUrl ->
                    // data:image/png;base64,xxxx
                    val b64 = dataUrl.substringAfter(",", "")
                    if (b64.isEmpty()) {
                        qrStatus = "失败: base64 图片为空"
                        loginBusy = false
                        return@launch
                    }
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    qrBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    qrStatus = "等待扫码..."
                    log("QR 二维码已生成，等待扫码")
                    pollQr()
                },
                onFailure = { e ->
                    qrStatus = "失败: ${e.message}"
                    loginBusy = false
                }
            )
        }
    }

    // 临时广播触发器：adb shell am broadcast -a yos.music.player.poc.SEARCH --es keyword "晴天"
    // Capability 验证触发（本设备禁触摸注入，沿用 SEARCH 同款 adb 验证模式）：
    //   adb shell am broadcast -a yos.music.player.poc.CAPABILITY --es test lyric|playlist|album|artist|all
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    "yos.music.player.poc.CAPABILITY" -> {
                        val test = intent.getStringExtra("test") ?: "all"
                        log("POC broadcast: capability test=$test")
                        scope.launch {
                            when (test) {
                                "lyric" -> testLyric()
                                "playlist" -> testMyPlaylists()
                                "album" -> testAlbum()
                                "artist" -> testArtist()
                                else -> {
                                    testLyric()
                                    testMyPlaylists()
                                    testAlbum()
                                    testArtist()
                                }
                            }
                        }
                    }
                    else -> {
                        val kw = intent.getStringExtra("keyword") ?: "晴天"
                        searchText = kw
                        log("POC broadcast: search keyword=$kw")
                        runSearch()
                    }
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction("yos.music.player.poc.SEARCH")
            addAction("yos.music.player.poc.CAPABILITY")
        }
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            // 需 EXPORTED：触发方是 adb shell（不同 UID）；NOT_EXPORTED 会将其拦截。
            // 该接收器仅接收调试用 action，无敏感副作用。
            context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            context.registerReceiver(receiver, filter)
        }
        onDispose { context.unregisterReceiver(receiver) }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            // 底部预留播放器高度，避免 footer/按钮被底部播放器遮住无法滚动到。
            .padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 240.dp)
    ) {
        TopAppBar(
            title = { Text("POC: Rust Native Online Playback") },
            navigationIcon = {
                IconButton(onClick = onNavigateUp) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer,
                titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
            )
        )

        Spacer(modifier = Modifier.height(16.dp))

        // Checkpoint 1: Native library status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Checkpoint 1: .so Loading", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Status: ${if (KugouNativeBridge.isServerRunning()) "✓ LOADED" else "Loaded (server off)"}",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Checkpoint 2: Server control
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Checkpoint 2: Server Control", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "Server Status:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = serverStatus,
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Medium)
                    )
                }
                
                Spacer(modifier = Modifier.height(12.dp))
                
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            scope.launch {
                                isLoading = true
                                log("Starting server...")
                                val p = KugouNativeBridge.startServer(dataDir = context.filesDir.absolutePath)
                                if (p > 0) {
                                    port = p
                                    serverStatus = "Running on port $p"
                                    log("✓ Checkpoint 2 PASSED: Server started on port $p")
                                } else {
                                    serverStatus = "Failed to start"
                                    log("✗ Checkpoint 2 FAILED: Server failed to start")
                                }
                                isLoading = false
                            }
                        },
                        enabled = !isLoading && !KugouNativeBridge.isServerRunning()
                    ) {
                        Text("Start Server")
                    }
                    
                    Button(
                        onClick = {
                            scope.launch {
                                log("Stopping server...")
                                KugouNativeBridge.stopServer()
                                serverStatus = "Stopped"
                                port = 0
                                log("Server stopped")
                            }
                        },
                        enabled = !isLoading && KugouNativeBridge.isServerRunning()
                    ) {
                        Text("Stop Server")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Checkpoint 3-7: Search and play
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Checkpoints 3-7: Search → URL → Play", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))
                
                OutlinedTextField(
                    value = searchText,
                    onValueChange = { searchText = it },
                    placeholder = { Text("Enter song keyword") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Button(
                    onClick = {
                        if (searchText.isBlank()) return@Button
                        runSearch()
                    },
                    enabled = !isLoading && searchText.isNotBlank()
                ) {
                    Text("Search Songs")
                }
                
                if (searchResults.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(12.dp))

                    // 搜索结果：普通柱状展示（不用 LazyColumn），避免与外层 verticalScroll
                    // 嵌套导致滚动被截断，从而保证底部“测试音源”按钮能滚动到可点击位置。
                    Text("Search Results (${searchResults.size})", fontWeight = FontWeight.Medium)
                    searchResults.forEach { song ->
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    log("Selected: ${song.name} - ${song.author} (hash=${song.hash})")
                                }
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Text(song.name, fontWeight = FontWeight.Medium)
                                Text(song.author, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
                
                // Log display
                if (logMessages.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text("Log Output", fontWeight = FontWeight.Medium)
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(150.dp)
                            .background(Color(0xFF1E1E1E))
                            .padding(8.dp),
                        contentAlignment = Alignment.TopStart
                    ) {
                        Text(
                            text = logMessages.joinToString("\n"),
                            color = Color.White,
                            fontSize = 12.sp,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ---------- Checkpoint 6: 登录实验（临时，验证后移除） ----------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Checkpoint 6: Login Experiment", fontWeight = FontWeight.Bold)
                Text(
                    "歌曲: 晴天 - 周杰伦\nFileHash: $TEST_HASH",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    "登录状态: ${if (KugouApiService.isLoggedIn()) "✓ 已登录 (userid=" + (KugouApiService.userid ?: "?") + ")" else "未登录"}",
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))

                // ① 登录前测试
                Button(
                    onClick = { testSongUrl("登录前") { beforeResult = it } },
                    enabled = !loginBusy && !isLoading
                ) { Text("① Test /song/url (登录前)") }
                Text(beforeResult.ifEmpty { "-" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(8.dp))

                // ② QR 登录
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { refreshQr() }, enabled = !loginBusy && !isLoading) { Text("② 登录酷狗账号") }
                    OutlinedButton(
                        onClick = {
                            KugouApiService.clearLogin()
                            qrStatus = "已退出登录"
                            afterResult = ""
                            log("登录态已清除")
                        },
                        enabled = !loginBusy
                    ) { Text("清除登录") }
                }
                Text("二维码状态: $qrStatus", fontSize = 12.sp)
                qrBitmap?.let { bmp ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "酷狗登录二维码",
                        modifier = Modifier
                            .size(200.dp)
                            .background(Color.White)
                            .padding(8.dp)
                    )
                    Text("用酷狗 App 扫码登录", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                Spacer(modifier = Modifier.height(8.dp))

                // ③ 登录后测试（同一 hash、同一 quality，只变登录态）
                Button(
                    onClick = { testSongUrl("登录后") { afterResult = it } },
                    enabled = !loginBusy && !isLoading && KugouApiService.isLoggedIn()
                ) { Text("③ Test /song/url (登录后)") }
                Text(afterResult.ifEmpty { "-" }, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)

                Spacer(modifier = Modifier.height(12.dp))

                // ④ Checkpoint 7 播放：真实 URL → YosMediaItem → MediaController → Media3
                Text("Checkpoint 7: 真实播放", fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        attachPlayerObserver()
                        playRealSong()
                    },
                    enabled = realSongUrl != null && KugouApiService.isLoggedIn()
                ) { Text("④ 播放《晴天》(MediaController)") }
                Text(playStateText, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                realSongUrl?.let {
                    Text("URL 可用: ${it.take(60)}...", fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                }
                if (realSongUrl == null) {
                    Text("请先点③获取真实 URL", fontSize = 10.sp, color = Color(0xFFC62828))
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

// ========== POC Capability Verification Section (临时，验证后移除) ==========
Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
) {
    Column(modifier = Modifier.padding(16.dp)) {
        Text("POC Capability Verification", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                
        Spacer(modifier = Modifier.height(12.dp))

        // Server Status
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Rust Server", fontWeight = FontWeight.Medium)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Running: ${if (serverRunning) "✓ YES" else "❌ NO"}")
                Text("Port: ${if (currentPort > 0) currentPort else "N/A"}")
                Text("Base URL: $baseUrl", style = MaterialTheme.typography.labelSmall)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Quality Tests
        Text("A. Sound Quality Tests (128/320/flac/high)", fontWeight = FontWeight.Bold, fontSize = 14.sp)
        Spacer(modifier = Modifier.height(8.dp))
                
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(onClick = { scope.launch { testQuality("128") } }, enabled = !isLoading, modifier = Modifier.weight(1f)) {
                Text("测试 128")
            }
            Button(onClick = { scope.launch { testQuality("320") } }, enabled = !isLoading, modifier = Modifier.weight(1f)) {
                Text("测试 320")
            }
            Button(onClick = { scope.launch { testQuality("flac") } }, enabled = !isLoading, modifier = Modifier.weight(1f)) {
                Text("测试 FLAC")
            }
            Button(onClick = { scope.launch { testQuality("high") } }, enabled = !isLoading, modifier = Modifier.weight(1f)) {
                Text("测试 High")
            }
        }
                
        if (qualityResults.value.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(
                containerColor = if (qualityResults.value.contains("all_passed")) Color.Green.copy(alpha = 0.2f)
                else Color(0xFFFF8A80).copy(alpha = 0.2f))) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Summary:", fontWeight = FontWeight.Bold)
                    qualityResults.value.forEach { (k, v) ->
                        Text(k, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Lyric Test
        Button(
            onClick = { scope.launch { testLyric() } },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("B. 测试歌词 API")
        }
        lyricResult?.let { result ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(result, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Playlist Tests
        Button(
            onClick = { scope.launch { testMyPlaylists() } },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("C. 测试我的歌单")
        }
        playlistResult?.let { result ->
            Spacer(modifier = Modifier.height(4.dp))
            Text(result, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 10, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Album & Artist Tests (only if existing APIs are available)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Button(
                onClick = { scope.launch { testAlbum() } },
                enabled = !isLoading,
                modifier = Modifier.weight(1f)
            ) {
                Text("D. 测试专辑")
            }
            Button(
                onClick = { scope.launch { testArtist() } },
                enabled = !isLoading,
                modifier = Modifier.weight(1f)
            ) {
                Text("E. 测试歌手")
            }
        }
        albumResult?.let { result ->
            Text(result, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
        }
        artistResult?.let { result ->
            Text(result, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
        }
        mediaItemCheck?.let { result ->
            Text(result, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3)
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Comment Test (optional)
        Button(
            onClick = { scope.launch { testComment() } },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("F. 测试评论")
        }
        commentResult?.let { result ->
            Text(result, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 5)
        }
    }
}

// Footer
Text(
    text = "This is a temporary POC screen. It will be removed after verification.",
    style = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant),
    modifier = Modifier.fillMaxWidth().align(Alignment.CenterHorizontally)
)
    } // 关闭外层 Column（575 行处开启）
}
