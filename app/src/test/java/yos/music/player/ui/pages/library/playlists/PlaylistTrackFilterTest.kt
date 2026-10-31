package yos.music.player.ui.pages.library.playlists

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.data.repositories.KugouPlaylistTrack

/**
 * 歌单页内检索的纯逻辑测试。
 *
 * 重点不在「能不能搜到」，而在**原索引**：结果行的播放与删除都按整张歌单的位置执行，
 * 一旦用过滤后的下标，点第 1 条就会播/删错歌。
 */
class PlaylistTrackFilterTest {

    private fun track(
        hash: String,
        name: String,
        artist: String = "歌手",
        durationMs: Long = 200_000L
    ) = KugouPlaylistTrack(
        hash = hash,
        name = name,
        artist = artist,
        album = null,
        durationMs = durationMs,
        coverUrl = null
    )

    private val tracks = listOf(
        track("h1", "晴天", "周杰伦"),
        track("h2", "七里香", "周杰伦"),
        track("h3", "夜曲", "周杰伦"),
        track("h4", "海阔天空", "Beyond"),
        track("h5", "Yellow", "Coldplay")
    )

    @Test
    fun `empty query returns all tracks with identity indices`() {
        val result = filterPlaylistTracks(tracks, "")
        assertEquals(tracks.size, result.size)
        result.forEachIndexed { i, pair ->
            assertEquals(i, pair.first)
            assertEquals(tracks[i], pair.second)
        }
    }

    @Test
    fun `whitespace only query is treated as empty`() {
        assertEquals(tracks.size, filterPlaylistTracks(tracks, "   ").size)
        assertEquals(tracks.size, filterPlaylistTracks(tracks, "\t\n ").size)
    }

    @Test
    fun `query is trimmed before matching`() {
        val result = filterPlaylistTracks(tracks, "  晴天  ")
        assertEquals(1, result.size)
        assertEquals(0, result.first().first)
    }

    @Test
    fun `matches song name and keeps original index`() {
        val result = filterPlaylistTracks(tracks, "七里香")
        assertEquals(1, result.size)
        // 关键：原索引是 1，不是过滤后的 0
        assertEquals(1, result.first().first)
        assertEquals("h2", result.first().second.hash)
    }

    @Test
    fun `matches artist and keeps original indices`() {
        val result = filterPlaylistTracks(tracks, "周杰伦")
        assertEquals(3, result.size)
        assertEquals(listOf(0, 1, 2), result.map { it.first })
    }

    @Test
    fun `matching is case insensitive`() {
        assertEquals(1, filterPlaylistTracks(tracks, "yellow").size)
        assertEquals(1, filterPlaylistTracks(tracks, "YELLOW").size)
        assertEquals(1, filterPlaylistTracks(tracks, "beyond").size)
    }

    @Test
    fun `partial match on name works`() {
        val result = filterPlaylistTracks(tracks, "阔天")
        assertEquals(1, result.size)
        assertEquals(3, result.first().first)
    }

    @Test
    fun `no match returns empty list`() {
        assertTrue(filterPlaylistTracks(tracks, "不存在的歌").isEmpty())
    }

    @Test
    fun `results preserve original playlist order`() {
        val result = filterPlaylistTracks(tracks, "e")
        // 名字/歌手含 e 的：七里香(1, 不含)… 用实际断言代替猜测
        val expected = tracks.mapIndexedNotNull { index, t ->
            val hit = t.name.contains("e", ignoreCase = true) ||
                t.artist.contains("e", ignoreCase = true)
            if (hit) index else null
        }
        assertEquals(expected, result.map { it.first })
        assertEquals(expected.sorted(), result.map { it.first })
    }

    @Test
    fun `null fields do not crash`() {
        // Gson 反序列化不校验 Kotlin 非空：跨构建混淆键名漂移时字段运行时会是 null。
        // 这里只能直接验证判据本身（构造出字段为 null 的 data class 在 Kotlin 里不可能），
        // 而 filterPlaylistTracks 的匹配分支正是走这个扩展函数。
        assertFalse((null as String?).containsQuery("晴天"))
        assertFalse((null as String?).containsQuery(""))
    }

    @Test
    fun `containsQuery rejects empty keyword`() {
        // 空关键词一律不命中：调用方已用 trim 后空串短路，这里是第二道保险
        assertFalse("abc".containsQuery(""))
        assertFalse((null as String?).containsQuery("a"))
        assertTrue("abc".containsQuery("b"))
    }
}
