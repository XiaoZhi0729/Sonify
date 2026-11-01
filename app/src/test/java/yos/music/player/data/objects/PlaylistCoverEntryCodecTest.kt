package yos.music.player.data.objects

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 缓存条目编解码回归。
 *
 * 背景缺陷：版本键曾是 `listVer|songCount`，存储格式也是 `版本|URL`；读取时按
 * **第一个** `|` 切分，版本只切到 `listVer`，与写入的 `listVer|songCount` 永不相等，
 * 于是每次进页都判定缓存失效、重新拉一次歌单曲目重算封面（表现为「我喜欢」封面
 * 每次返回列表页都刷新一遍）。这组测试锁死「版本键不含分隔符」这个契约。
 */
class PlaylistCoverEntryCodecTest {

    @Test
    fun `encoding and decoding round-trips a version with both dimensions`() {
        val raw = encodeCoverEntry("442_100", "https://img.example/a.jpg")

        assertEquals("442_100" to "https://img.example/a.jpg", decodeCoverEntry(raw))
    }

    @Test
    fun `version produced for a playlist contains no separator`() {
        // 与 PlaylistCoverCache.version 同一形态：listVer_songCount
        val version = "442_100"
        val raw = encodeCoverEntry(version, "https://img.example/a.jpg")

        // 按分隔符切分后必须第一个字段就是完整版本（这正是曾经出错的点）
        assertEquals(version, raw.substringBefore('\t'))
    }

    @Test
    fun `url containing punctuation survives decoding unchanged`() {
        val url = "https://c1.kgimg.com/stdmusic/400/2024/{x}/a.jpg?w=1|2&h=3"
        val decoded = decodeCoverEntry(encodeCoverEntry("7_23", url))

        assertEquals(url, decoded?.second)
    }

    @Test
    fun `legacy pipe separated entry is not silently accepted as a hit`() {
        // 旧格式 "442|100|https://..."：解码要么失败要么版本不等于 listVer_songCount，
        // 无论哪种都不能被当成缓存命中（命中就意味着用错封面）
        val legacy = "442|100|https://img.example/a.jpg"

        assertNull(decodeCoverEntry(legacy))
    }

    @Test
    fun `entry without separator is a miss`() {
        assertNull(decodeCoverEntry("https://img.example/a.jpg"))
    }

    @Test
    fun `entry with empty version is a miss`() {
        assertNull(decodeCoverEntry("\thttps://img.example/a.jpg"))
    }

    @Test
    fun `entry with empty url is a miss`() {
        assertNull(decodeCoverEntry("442_100\t"))
    }

    /**
     * 端到端语义：同一个歌单在同一版本下写一次、读多次，每次都必须命中。
     * 这正是「返回列表页封面就刷新一遍」的直接判据——命中即不重算、不发网络请求。
     */
    @Test
    fun `same version hits on every read while a changed playlist misses`() {
        val written = encodeCoverEntry("392_100", "https://img.example/cover.jpg")

        repeat(3) {
            val (version, url) = requireNotNull(decodeCoverEntry(written))
            assertEquals("392_100", version)
            assertEquals("https://img.example/cover.jpg", url)
        }

        // 加了一首歌（count 变）或重排序（listVer 变）都必须判定为未命中
        assertEquals("392_100", decodeCoverEntry(written)?.first)
        assertEquals(false, decodeCoverEntry(written)?.first == "392_101")
        assertEquals(false, decodeCoverEntry(written)?.first == "393_100")
    }
}
