package yos.music.player.code.utils.artist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * hero 烘焙图缓存的内存闸：计数漂移会让缓存无界增长或误删热图，两者都难在 UI 上发现，
 * 故对 [BoundedLru] 单独定测。字节按 4B/px 的 ARGB_8888 口径。
 */
class BoundedLruTest {

    /** 用宽高模拟一张 ARGB_8888 位图的字节数，避免测试里造真位图。 */
    private class Fake(val w: Int, val h: Int) {
        val bytes: Long get() = w.toLong() * h * 4L
    }

    private fun lru(maxCount: Int, maxBytes: Long) =
        BoundedLru<String, Fake>(maxCount, maxBytes) { it.bytes }

    @Test
    fun `条数超限时淘汰最久未使用`() {
        val cache = lru(maxCount = 2, maxBytes = Long.MAX_VALUE)
        cache.put("a", Fake(1, 1))
        cache.put("b", Fake(1, 1))
        cache.put("c", Fake(1, 1))
        assertEquals(listOf("b", "c"), cache.keys())
        assertNull(cache.get("a"))
    }

    @Test
    fun `get 会把条目提到队尾避免被淘汰`() {
        val cache = lru(maxCount = 2, maxBytes = Long.MAX_VALUE)
        cache.put("a", Fake(1, 1))
        cache.put("b", Fake(1, 1))
        cache.get("a")            // a 变为最近使用
        cache.put("c", Fake(1, 1))
        assertEquals(listOf("a", "c"), cache.keys())
    }

    @Test
    fun `字节超限时也会淘汰即使条数未满`() {
        // 每张 1024×1536×4 = 6MiB（恰好 6291456B）。闸门取 11MiB：两张 12MiB 才真正越过，
        // 用 12MiB 会与两张之和相等、`> ` 不成立而测不到淘汰。
        val cache = lru(maxCount = 8, maxBytes = 11L * 1024 * 1024)
        cache.put("a", Fake(1024, 1536))
        cache.put("b", Fake(1024, 1536))
        assertEquals(listOf("b"), cache.keys())
    }

    @Test
    fun `覆盖同一键时字节计数不重复累加`() {
        val cache = lru(maxCount = 8, maxBytes = 24L * 1024 * 1024)
        val one = Fake(1024, 1536)           // 6.29MB
        val expected = one.bytes
        cache.put("a", one)
        cache.put("a", one)
        cache.put("a", one)
        assertEquals(expected, cache.approxBytes())
        assertEquals(listOf("a"), cache.keys())
    }

    @Test
    fun `clear 后字节计数归零`() {
        val cache = lru(maxCount = 8, maxBytes = 24L * 1024 * 1024)
        cache.put("a", Fake(1024, 1536))
        cache.clear()
        assertEquals(0L, cache.approxBytes())
        assertEquals(emptyList<String>(), cache.keys())
    }

    @Test
    fun `单张就超字节闸门时仍保留该张不把缓存冲空`() {
        // 这是「只按字节封顶」的坑：闸门小于单张时必须至少留一张，否则永远 miss
        val cache = lru(maxCount = 8, maxBytes = 1L * 1024 * 1024)
        cache.put("big", Fake(1024, 1536))
        assertEquals(listOf("big"), cache.keys())
        assertEquals(6291456L, cache.approxBytes())
    }
}
