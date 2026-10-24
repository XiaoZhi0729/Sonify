package yos.music.player.data.objects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class OnlinePlaylistCacheParseTest {

    @Test
    fun `legacy obfuscated keys are rejected as a whole`() {
        assertNull(OnlinePlaylistCache.parseCached("""[{"a":"x","b":"y","c":"z"}]"""))
    }

    @Test
    fun `broken json is rejected`() {
        assertNull(OnlinePlaylistCache.parseCached("{not json"))
    }

    @Test
    fun `null element and invalid required fields are dropped`() {
        val json = """[null,
            {"hash":"H1","name":"N1","artist":"A1"},
            {"hash":"H2","name":null,"artist":"A2"},
            {"hash":null,"name":"N3","artist":"A3"},
            {"hash":"H4","name":"N4","artist":""}]"""
        val list = OnlinePlaylistCache.parseCached(json)!!

        assertEquals(1, list.size)
        assertEquals("H1", list[0].hash)
        assertEquals("N1", list[0].name)
        assertEquals("A1", list[0].artist)
    }

    @Test
    fun `entirely invalid nonempty array is rejected`() {
        assertNull(OnlinePlaylistCache.parseCached("""[{"hash":"","name":"N","artist":"A"}]"""))
    }

    @Test
    fun `mixed entries retain valid tracks and all fields`() {
        val json = """[
            {"hash":"BAD","name":"","artist":"A"},
            {"hash":"H1","name":"N1","artist":"A1","album":"AL",
             "durationMs":1234,"coverUrl":"https://x/1.jpg","fileId":11,
             "mixsongId":22,"albumId":33}]"""
        val list = OnlinePlaylistCache.parseCached(json)!!

        assertEquals(1, list.size)
        with(list[0]) {
            assertEquals("H1", hash)
            assertEquals("N1", name)
            assertEquals("A1", artist)
            assertEquals("AL", album)
            assertEquals(1234L, durationMs)
            assertEquals("https://x/1.jpg", coverUrl)
            assertEquals(11L, fileId)
            assertEquals(22L, mixsongId)
            assertEquals(33L, albumId)
        }
    }

    @Test
    fun `empty array remains a valid empty cache`() {
        val list = OnlinePlaylistCache.parseCached("[]")

        assertNotNull(list)
        assertEquals(0, list!!.size)
    }
}
