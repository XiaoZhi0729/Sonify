package yos.music.player.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 歌单取色缓存键契约：
 * - 同一歌单在封面 URL 变化（增删曲目导致最新封面变更）时必须生成不同的缓存键，避免复用旧封面色。
 * - 不同来源或不同歌单 ID 互相隔离，且不与专辑取色键碰撞。
 */
class PlaylistPaletteKeyTest {

    @Test
    fun differentCoverUrlsProduceDistinctKeysForSamePlaylist() {
        val k1 = playlistPaletteKey("User", "1001", "https://example.com/cover_a.jpg")
        val k2 = playlistPaletteKey("User", "1001", "https://example.com/cover_b.jpg")
        assertNotEquals(k1, k2)
    }

    @Test
    fun sameInputsProduceDeterministicKey() {
        val k1 = playlistPaletteKey("User", "1001", "https://example.com/cover_a.jpg")
        val k2 = playlistPaletteKey("User", "1001", "https://example.com/cover_a.jpg")
        assertEquals(k1, k2)
    }

    @Test
    fun sourcePlaylistAndAlbumNamespacesStayIsolated() {
        val userKey = playlistPaletteKey("User", "123", "https://example.com/a.jpg")
        val recommendKey = playlistPaletteKey("Recommend", "123", "https://example.com/a.jpg")
        val albumKey = albumPaletteKey("123")
        assertNotEquals(userKey, recommendKey)
        assertNotEquals(userKey, albumKey)
        assertNotEquals(recommendKey, albumKey)
    }
}
