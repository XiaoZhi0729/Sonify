package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 高潮片段解析与进度条归一化单测。
 *
 * 上游 `/song/climax` 的形态实测有两种（`data` 为 List 或 Map），字段名与值类型
 * （字符串/数字毫秒）也不统一，这里把这些兼容分支全部钉住。
 *
 * 走 Map 入参：org.json 在 JVM 单测里是 android.jar 桩，不能直接构造 JSONObject
 * ——与 [KugouSpecialPlaylistParseTest] 同一约定。
 */
class SongClimaxParseTest {

    // ---------- parseSongClimaxFields ----------

    @Test
    fun `data as list with string millis`() {
        val result = parseSongClimaxFields(
            mapOf(
                "status" to 1,
                "data" to listOf(mapOf("start_time" to "61500", "end_time" to "98000"))
            )
        )
        assertEquals(SongClimax(61500L, 98000L), result)
    }

    @Test
    fun `data as map with numeric millis`() {
        val result = parseSongClimaxFields(
            mapOf("data" to mapOf("start_time" to 61500, "end_time" to 98000))
        )
        assertEquals(SongClimax(61500L, 98000L), result)
    }

    @Test
    fun `no data layer falls back to root fields`() {
        val result = parseSongClimaxFields(
            mapOf("start_time" to 61500, "end_time" to 98000)
        )
        assertEquals(SongClimax(61500L, 98000L), result)
    }

    @Test
    fun `camelCase aliases are accepted`() {
        val result = parseSongClimaxFields(
            mapOf("data" to mapOf("startTime" to 30000, "endTime" to 60000))
        )
        assertEquals(SongClimax(30000L, 60000L), result)
    }

    @Test
    fun `climax_start alias is accepted`() {
        val result = parseSongClimaxFields(
            mapOf("data" to mapOf("climax_start" to 30000, "climax_end" to 60000))
        )
        assertEquals(SongClimax(30000L, 60000L), result)
    }

    @Test
    fun `missing start yields null`() {
        assertNull(parseSongClimaxFields(mapOf("data" to mapOf("end_time" to 98000))))
    }

    @Test
    fun `zero start in payload yields null`() {
        assertNull(parseSongClimaxFields(mapOf("data" to mapOf("start_time" to 0, "end_time" to 98000))))
    }

    @Test
    fun `end not after start yields null`() {
        assertNull(
            parseSongClimaxFields(mapOf("data" to mapOf("start_time" to 60000, "end_time" to 60000)))
        )
        assertNull(
            parseSongClimaxFields(mapOf("data" to mapOf("start_time" to 60000, "end_time" to 30000)))
        )
    }

    @Test
    fun `missing end is allowed and reported as zero`() {
        val result = parseSongClimaxFields(mapOf("data" to mapOf("start_time" to 61500)))
        assertEquals(SongClimax(61500L, 0L), result)
    }

    @Test
    fun `empty list yields null`() {
        assertNull(parseSongClimaxFields(mapOf("data" to emptyList<Map<String, Any>>())))
    }

    @Test
    fun `unparseable start text yields null`() {
        assertNull(parseSongClimaxFields(mapOf("data" to mapOf("start_time" to "abc"))))
    }

    // ---------- climaxMarkFraction ----------

    @Test
    fun `fraction is start over duration`() {
        assertEquals(0.5f, climaxMarkFraction(60000L, 120000L)!!, 0.0001f)
    }

    @Test
    fun `unknown duration yields null`() {
        assertNull(climaxMarkFraction(60000L, 0L))
        assertNull(climaxMarkFraction(60000L, -1L))
    }

    @Test
    fun `zero start yields null`() {
        assertNull(climaxMarkFraction(0L, 120000L))
    }

    @Test
    fun `near end of track is suppressed`() {
        // 0.99 落在 2% 禁画区内：贴着端点画出来会被圆头轨道吃掉，读起来像轨道缺陷
        assertNull(climaxMarkFraction(99000L, 100000L))
    }

    @Test
    fun `near start of track is suppressed`() {
        assertNull(climaxMarkFraction(1000L, 100000L))
    }

    @Test
    fun `boundary values just inside the guard are kept`() {
        assertEquals(0.03f, climaxMarkFraction(3000L, 100000L)!!, 0.0001f)
        assertEquals(0.97f, climaxMarkFraction(97000L, 100000L)!!, 0.0001f)
    }
}
