package yos.music.player.ui.pages.library.playlists

import yos.music.player.data.repositories.KugouPlaylistTrack

/*
 * 详情页（在线歌单 / 在线专辑）的页内检索：纯本地过滤，不发网络请求。
 *
 * 独立成文件（零 Compose / 零 Android 依赖）是为了能进 JVM 单测：
 * 放进 Compose 文件里会让测试加载该文件的顶层类，从而牵出 Compose 运行时。
 */

/**
 * 关键词是否命中该字段。
 *
 * 接收者声明成 `String?`：这些曲目字段在 Kotlin 里是非空的，但 Gson 反序列化不做校验
 * （跨构建混淆键名漂移时会把字段写成 null），直接调 `contains` 会在渲染搜索结果时 NPE。
 * `lazyItemKeys` 里对 hash 的同款防御是既有先例。
 */
internal fun String?.containsQuery(keyword: String): Boolean =
    this != null && keyword.isNotEmpty() && contains(keyword, ignoreCase = true)

/**
 * 通用检索：按 [name] / [artist] 两个取值函数筛选，返回「原索引 + 元素」对。
 *
 * 必须带原索引：结果行的播放按整张列表的位置执行（播放队列是完整歌单/专辑），
 * 若用过滤后的下标，点第 1 条会播错歌。
 *
 * [query] 先 trim，空串（含纯空格）返回全部 —— 调用方据此恢复原列表。
 *
 * 泛化到这里是因为在线歌单用 [KugouPlaylistTrack]（字段 name/artist）、
 * 在线专辑用 KugouNewSong（字段 name/author），类型不同但检索语义完全一致。
 */
internal fun <T> filterTracks(
    tracks: List<T>,
    query: String,
    name: (T) -> String?,
    artist: (T) -> String?
): List<Pair<Int, T>> {
    val keyword = query.trim()
    if (keyword.isEmpty()) {
        return List(tracks.size) { index -> index to tracks[index] }
    }
    val result = ArrayList<Pair<Int, T>>()
    tracks.forEachIndexed { index, track ->
        if (name(track).containsQuery(keyword) || artist(track).containsQuery(keyword)) {
            result += index to track
        }
    }
    return result
}

/**
 * 在线歌单曲目检索（歌名 + 歌手）。
 *
 * 保留这个具名重载而不是让调用方各自传 lambda：歌单页是既有调用点，
 * 也便于单测直接覆盖「原索引」这条最关键的行为。
 */
internal fun filterPlaylistTracks(
    tracks: List<KugouPlaylistTrack>,
    query: String
): List<Pair<Int, KugouPlaylistTrack>> = filterTracks(
    tracks = tracks,
    query = query,
    name = { it.name },
    artist = { it.artist }
)
