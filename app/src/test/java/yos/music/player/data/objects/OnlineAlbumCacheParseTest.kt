package yos.music.player.data.objects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Gson 反序列化绕过 Kotlin 非空校验：旧版混淆键名 / 损坏 JSON 会让非空字段为 null。
 * 读侧必须清洗掉这些条目，否则 null 会流进 placeholderUri(hash) 的非空参数检查
 * （R8 改写成 param.getClass()）→ NPE 杀进程。
 */
class OnlineAlbumCacheParseTest {

    @Test
    fun `legacy obfuscated keys are rejected as a whole`() {
        // 旧版本 release 把字段名混淆成 a/b/c 落盘
        assertNull(OnlineAlbumCache.parseCached("""[{"a":"x","b":"y","c":"z"}]"""))
    }

    @Test
    fun `broken json is rejected`() {
        assertNull(OnlineAlbumCache.parseCached("{not json"))
    }

    @Test
    fun `null element and null fields are dropped`() {
        val json = """[null,
            {"hash":"H1","name":"N1","author":"A1","durationMs":1000},
            {"hash":"H2","name":null,"author":"A2"},
            {"hash":null,"name":"N3","author":"A3"}]"""
        val list = OnlineAlbumCache.parseCached(json)!!
        assertEquals(1, list.size)
        assertEquals("H1", list[0].hash)
        assertEquals("N1", list[0].name)
        assertEquals("A1", list[0].author)
    }

    @Test
    fun `blank fields are dropped`() {
        val json = """[{"hash":"","name":"N","author":"A"}]"""
        assertNull(OnlineAlbumCache.parseCached(json))
    }

    @Test
    fun `healthy cache round-trips`() {
        val json = """[{"hash":"H1","name":"N1","author":"A1",
            "albumName":"AL","durationMs":1234,"artworkUrl":"https://x/1.jpg"}]"""
        val list = OnlineAlbumCache.parseCached(json)!!
        assertEquals(1, list.size)
        assertEquals(1234L, list[0].durationMs)
        assertEquals("AL", list[0].albumName)
    }
}
