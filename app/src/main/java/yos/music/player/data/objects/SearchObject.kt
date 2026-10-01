package yos.music.player.data.objects

import androidx.compose.runtime.mutableStateOf
import com.google.gson.Gson
import com.tencent.mmkv.MMKV
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.repositories.KugouLyricSearchResult
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouPlaylistBrief
import yos.music.player.data.repositories.KugouSearchSong

/**
 * 搜索一级 Tab 的页面级状态 holder（进程生命周期）。
 *
 * 四 Tab 导航下切换一级页面会销毁页面 composition，本地 remember 状态随之丢弃
 * （saveState/restoreState 只恢复 NavBackStackEntry 与 rememberSaveable 层，
 * 普通 remember 不在其列）。参照项目既有内存 holder 模式（OnlinePlaylistObject
 * 页间传参 / recommendMusicList 跨 Tab 保存 Home 推荐），将搜索关键状态放 object，
 * 切 Tab 返回后原样恢复——只恢复已取得的数据，绝不重新请求 /search。
 *
 * 搜索历史走 MMKV 持久化（mutableDataSaverListStateOf，范式同 recentlyPlayed）：
 * 去重头插、上限 10 条，对齐上游 md3Music search_page.dart。
 */
object SearchObject {
    /** 搜索类型（结果 Tab）：song / album / special / lyric。 */
    const val TYPE_SONG = "song"
    const val TYPE_ALBUM = "album"
    const val TYPE_SPECIAL = "special"
    const val TYPE_LYRIC = "lyric"

    val searchText = mutableStateOf("")
    val results = mutableStateOf<List<KugouSearchSong>>(emptyList())
    // 与 results 同步的队列映射（占位符 URI，入队零网络请求）
    val queue = mutableStateOf<List<YosMediaItem>>(emptyList())
    // "idle" | "loading" | "empty" | "ok" | "error:<msg>"
    val status = mutableStateOf("idle")

    // ---------- 多类型搜索（Tab 切换不重搜：按类型缓存已取得结果） ----------

    /** 当前结果 Tab 类型。 */
    val currentType = mutableStateOf(TYPE_SONG)

    val albumResults = mutableStateOf<List<KugouNewAlbum>>(emptyList())
    val playlistResults = mutableStateOf<List<KugouPlaylistBrief>>(emptyList())
    val lyricResults = mutableStateOf<List<KugouLyricSearchResult>>(emptyList())

    /** 各类型已搜到的总数（分页判断用；0=未知）。 */
    val songTotal = mutableStateOf(0)

    // ---------- 空态区：搜索历史（MMKV 持久化）与热搜 ----------

    // gson 必须声明在 history 之前：history 的初始化器调用 loadHistoryFromDisk()
    // 用到它，object 属性按声明顺序初始化，后置声明在该时点还是 null（NPE 被吞成空列表）
    private val gson = Gson()
    private const val HISTORY_LIMIT = 10
    private const val HISTORY_KEY = "search_history_v1"

    /**
     * 搜索历史：去重头插、上限 10 条（写入入口在 [recordHistory]）。
     * 用 MMKV+Gson 手动持久化——data_saver 的 mutableDataSaverListStateOf 不支持
     * List<String> 落盘（convertListToString 对 String 抛 unsupportedType，实测崩溃）。
     */
    val history = mutableStateOf(loadHistoryFromDisk())

    /** 热搜词（进程内缓存，进搜索页时空态拉一次）。 */
    val hotWords = mutableStateOf<List<String>>(emptyList())

    /** 搜索联想词（输入防抖后刷新；空关键词清空）。 */
    val suggestions = mutableStateOf<List<String>>(emptyList())

    /** 最近一次实际执行搜索的关键词（判断联想区 vs 结果区的依据）。 */
    val lastSearched = mutableStateOf<String?>(null)

    private fun historyMmkv() = MMKV.mmkvWithID("yos_search")

    private fun loadHistoryFromDisk(): List<String> = runCatching {
        val raw = historyMmkv().decodeString(HISTORY_KEY) ?: return emptyList()
        gson.fromJson(raw, Array<String>::class.java).toList()
    }.getOrDefault(emptyList())

    /** 记录一次成功搜索：去重头插、上限 [HISTORY_LIMIT]，同步落盘。 */
    fun recordHistory(keyword: String) {
        val trimmed = keyword.trim()
        if (trimmed.isEmpty()) return
        val next = listOf(trimmed) + history.value.filterNot { it.equals(trimmed, ignoreCase = true) }
        history.value = next.take(HISTORY_LIMIT)
        runCatching { historyMmkv().encode(HISTORY_KEY, gson.toJson(history.value)) }
    }

    fun clearHistory() {
        history.value = emptyList()
        runCatching { historyMmkv().removeValueForKey(HISTORY_KEY) }
    }

    /** 切换结果类型；切回已搜过的类型不重置状态（由页面按缓存判断是否补拉）。 */
    fun switchType(type: String) {
        currentType.value = type
    }

    /** 一次全新搜索：清空全部类型缓存与联想。 */
    fun resetForNewSearch() {
        results.value = emptyList()
        queue.value = emptyList()
        albumResults.value = emptyList()
        playlistResults.value = emptyList()
        lyricResults.value = emptyList()
        songTotal.value = 0
        suggestions.value = emptyList()
        currentType.value = TYPE_SONG
    }
}
