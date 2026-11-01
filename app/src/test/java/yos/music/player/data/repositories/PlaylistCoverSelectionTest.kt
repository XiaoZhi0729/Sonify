package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 封面源回归：在线歌单封面 = 「最新添加歌曲的封面」，即 `sort` 最小的那首
 * （`sort=0` 是歌单列表第一行，也就是最新加进来的歌）。
 *
 * 抓包样本同 [PlaylistTrackOrderTest]：`/playlist/track/all/new`（listid=2, count=100），
 * 服务端数组按 collecttime 旧→新返回，首曲 `sort=99`——所以「取数组第一首」拿到的是
 * 歌单里**最早**加的那首，这正是本函数要避免的错误。
 */
class PlaylistCoverSelectionTest {

    private fun track(
        hash: String,
        sort: Int,
        cover: String?,
        collect: Long = 0L
    ) = KugouPlaylistTrack(
        hash = hash,
        name = hash,
        artist = "artist",
        album = null,
        durationMs = 0L,
        coverUrl = cover,
        sortOrder = sort,
        collectTime = collect
    )

    @Test
    fun `smallest sort wins even when it is last in server order`() {
        // 服务端顺序：最旧的在前（sort 大），最新的在最后（sort 0）
        val tracks = listOf(
            track("oldest", sort = 99, cover = "cover-oldest"),
            track("middle", sort = 50, cover = "cover-middle"),
            track("newest", sort = 0, cover = "cover-newest")
        )

        assertEquals("cover-newest", PlaylistTrackOrder.newestCover(tracks))
    }

    @Test
    fun `tracks without cover are skipped`() {
        val tracks = listOf(
            track("newest-no-cover", sort = 0, cover = null),
            track("blank-cover", sort = 1, cover = ""),
            track("next", sort = 2, cover = "cover-next")
        )

        assertEquals("cover-next", PlaylistTrackOrder.newestCover(tracks))
    }

    @Test
    fun `missing sort never masquerades as the first track`() {
        // 缺 sort 的曲目若参与比较会被当成 0（或极值），从而错误地决定整张封面
        val tracks = listOf(
            track("unknown", sort = PLAYLIST_SORT_MISSING, cover = "cover-unknown"),
            track("real", sort = 5, cover = "cover-real")
        )

        assertEquals("cover-real", PlaylistTrackOrder.newestCover(tracks))
    }

    @Test
    fun `all tracks missing sort yields null rather than a guess`() {
        val tracks = listOf(
            track("a", PLAYLIST_SORT_MISSING, "cover-a"),
            track("b", PLAYLIST_SORT_MISSING, "cover-b")
        )

        assertNull(PlaylistTrackOrder.newestCover(tracks))
    }

    @Test
    fun `ties on sort prefer the more recently collected song`() {
        val tracks = listOf(
            track("older", sort = 3, cover = "cover-older", collect = 100L),
            track("newer", sort = 3, cover = "cover-newer", collect = 200L),
            track("top", sort = 1, cover = "cover-top", collect = 10L)
        )

        assertEquals("cover-top", PlaylistTrackOrder.newestCover(tracks))
    }

    @Test
    fun `tie on both sort and collect time keeps a cover instead of returning null`() {
        val tracks = listOf(
            track("first", sort = 0, cover = "cover-first", collect = 500L),
            track("second", sort = 0, cover = "cover-second", collect = 500L)
        )

        assertEquals("cover-first", PlaylistTrackOrder.newestCover(tracks))
    }

    @Test
    fun `empty playlist has no cover to pick`() {
        assertNull(PlaylistTrackOrder.newestCover(emptyList()))
    }

    @Test
    fun `playlist whose tracks all lack covers yields null so caller keeps server pic`() {
        val tracks = listOf(
            track("a", sort = 0, cover = null),
            track("b", sort = 1, cover = "")
        )

        assertNull(PlaylistTrackOrder.newestCover(tracks))
    }
}
