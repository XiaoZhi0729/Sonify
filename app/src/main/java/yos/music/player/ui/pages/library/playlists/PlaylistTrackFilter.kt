package yos.music.player.ui.pages.library.playlists

import yos.music.player.data.repositories.KugouPlaylistTrack

/*
 * 歌单详情页的页内检索（纯本地过滤，不发网络请求）。
 *
 * 独立成文件（零 Compose / 零 Android 依赖）是为了能进 JVM 单测：
 * 放进 Compose 文件里会让测试加载该文件的顶层类，从而牵出 Compose 运行时。
 */

/**
 * 关键词是否命中该字段。
 *
 * 接收者声明成 `String?`：`KugouPlaylistTrack` 的字段在 Kotlin 里是非空的，但 Gson
 * 反序列化不做校验（跨构建混淆键名漂移时会把字段写成 null），直接调 `contains`
 * 会在搜索结果渲染时 NPE。`lazyItemKeys` 里对 hash 的同款防御是既有先例。
 */
internal fun String?.containsQuery(keyword: String): Boolean =
    this != null && keyword.isNotEmpty() && contains(keyword, ignoreCase = true)

/**
 * 在歌单曲目里检索，返回「原索引 + 曲目」对。
 *
 * 必须带原索引：结果行的播放与删除都按整张歌单的位置执行（播放队列是完整歌单，
 * 删歌按 fileId/hash 定位），若用过滤后的下标，点第 1 条会播/删错歌。
 *
 * [query] 先 trim，空串（含纯空格）返回全部曲目 —— 调用方据此恢复原列表。
 */
internal fun filterPlaylistTracks(
    tracks: List<KugouPlaylistTrack>,
    query: String
): List<Pair<Int, KugouPlaylistTrack>> {
    val keyword = query.trim()
    if (keyword.isEmpty()) {
        return List(tracks.size) { index -> index to tracks[index] }
    }
    val result = ArrayList<Pair<Int, KugouPlaylistTrack>>()
    tracks.forEachIndexed { index, track ->
        if (track.name.containsQuery(keyword) || track.artist.containsQuery(keyword)) {
            result += index to track
        }
    }
    return result
}
