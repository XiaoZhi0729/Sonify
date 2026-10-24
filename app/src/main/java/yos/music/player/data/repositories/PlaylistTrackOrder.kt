package yos.music.player.data.repositories

/**
 * 「无排序信息」哨兵：`/playlist/track/all`（精选歌单，schema 无 sort 字段）以及旧版落盘缓存
 * 解析出来的曲目都是这个值。[Int.MIN_VALUE] 用作缺省而非 0，因为 0 是合法的
 * 「最新/最上」排序位置。
 */
internal const val PLAYLIST_SORT_MISSING = Int.MIN_VALUE

/**
 * 歌单曲目的最终显示顺序。
 *
 * 依据：`/playlist/track/all/new`（上游 `/v4/get_list_all_file`）给每首歌带一个 `sort` 字段，
 * 它是服务端维护的**手动排列位置**——`0` = 最新、显示在最上，升序即酷狗客户端从上到下的顺序；
 * 每次新增歌曲服务端会给全表重编号。服务端数组本身是按 `collecttime`（收藏时间，unix 秒）
 * 旧→新返回的，`sort` 与数组位置并不一致（抓包实测 100 首里有 48 首按两种口径排出来的位置不同）。
 *
 * 所以顺序是 payload 属性，不需要也绝不能靠「知道这是不是系统歌单」再整体反转去猜。
 */
internal object PlaylistTrackOrder {

    /**
     * 按服务端 `sort` 升序重排；并列时收藏时间新的在前（与 `sort=0` 最新在最上的语义一致，
     * 且 [sortedWith] 稳定，完全并列时保持服务端返回顺序）。
     *
     * 任一曲目缺 `sort`（精选歌单 schema、旧缓存）就返回 `null` 表示「本批没有排序信息」，
     * 由调用方决定兜底策略——绝不能拿一部分曲目乱排，那比不排更糟。
     */
    fun byServerSort(tracks: List<KugouPlaylistTrack>): List<KugouPlaylistTrack>? {
        if (tracks.isEmpty()) return tracks
        if (tracks.any { it.sortOrder == PLAYLIST_SORT_MISSING }) return null
        return tracks.sortedWith(
            compareBy<KugouPlaylistTrack> { it.sortOrder }.thenByDescending { it.collectTime }
        )
    }
}
