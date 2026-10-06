package yos.music.player.code.utils.lrc

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricInfoSerializerTest {

    private fun parse(json: String) = JsonParser.parseString(json).asJsonObject

    @Test
    fun `行级歌词生成标准 LRC 且毫秒三位补零`() {
        val json = LyricInfoSerializer.encode(
            songName = "测试歌", artist = "测试歌手", album = null, songId = "id-1",
            entries = listOf(
                LyricEntry(mainLyric = listOf(4713f to "", 4713f to "编曲")),
                LyricEntry(mainLyric = listOf(61500f to "", 61500f to "混音"))
            )
        )!!
        val obj = parse(json)
        assertEquals("[00:04.713]编曲\n[01:01.500]混音", obj.get("lyric").asString)
        assertNull(obj.get("rawLyric"))
        assertEquals("id-1", obj.get("songId").asString)
        assertEquals("测试歌", obj.get("songName").asString)
        assertEquals("测试歌手", obj.get("artist").asString)
    }

    @Test
    fun `空文本行被丢弃`() {
        val json = LyricInfoSerializer.encode(
            songName = "t", artist = "a", album = null, songId = null,
            entries = listOf(
                LyricEntry(mainLyric = listOf(1000f to "", 1000f to "有词")),
                LyricEntry(mainLyric = listOf(2000f to "", 2000f to "   "))
            )
        )!!
        assertEquals("[00:01.000]有词", parse(json).get("lyric").asString)
    }

    @Test
    fun `词级歌词转换为 ELRC 且词开始时间取前一元素`() {
        // mainLyric 时间语义 = 元素结束时间；首元素是行开始锚点
        val json = LyricInfoSerializer.encode(
            songName = "t", artist = "a", album = null, songId = null,
            entries = listOf(
                LyricEntry(
                    mainLyric = listOf(
                        4000f to "",
                        4700f to "编",
                        4900f to "曲",
                        5200f to ""
                    )
                )
            )
        )!!
        assertEquals(
            "[00:04.000]<00:04.000>编<00:04.700>曲<00:05.200>",
            parse(json).get("rawLyric").asString
        )
    }

    @Test
    fun `混合行级与词级时行级行回退为普通 LRC 行`() {
        val json = LyricInfoSerializer.encode(
            songName = "t", artist = "a", album = null, songId = null,
            entries = listOf(
                LyricEntry(mainLyric = listOf(1000f to "", 1000f to "普通行")),
                LyricEntry(mainLyric = listOf(2000f to "", 2600f to "词", 3000f to ""))
            )
        )!!
        val rawLyric = parse(json).get("rawLyric").asString
        assertEquals("[00:01.000]普通行\n[00:02.000]<00:02.000>词<00:03.000>", rawLyric)
    }

    @Test
    fun `翻译与原文行使用相同时间戳`() {
        val json = LyricInfoSerializer.encode(
            songName = "t", artist = "a", album = null, songId = null,
            entries = listOf(
                LyricEntry(mainLyric = listOf(4713f to "", 4713f to "原文"), translation = "译文"),
                LyricEntry(mainLyric = listOf(9000f to "", 9000f to "第二行"), translation = null)
            )
        )!!
        val obj = parse(json)
        // 规范键 translationLyric + 旧别名 translation，内容一致
        assertEquals("[00:04.713]译文", obj.get("translationLyric").asString)
        assertEquals("[00:04.713]译文", obj.get("translation").asString)
    }

    @Test
    fun `翻译全空时省略 translation 字段`() {
        val json = LyricInfoSerializer.encode(
            songName = "t", artist = "a", album = null, songId = null,
            entries = listOf(LyricEntry(mainLyric = listOf(1000f to "", 1000f to "原文")))
        )!!
        val obj = parse(json)
        assertNull(obj.get("translation"))
        assertNull(obj.get("translationLyric"))
    }

    @Test
    fun `无歌词返回 null`() {
        assertNull(
            LyricInfoSerializer.encode("t", "a", null, null, emptyList())
        )
        assertNull(
            LyricInfoSerializer.encode(
                "t", "a", null, null,
                listOf(LyricEntry(mainLyric = listOf(1000f to "", 1000f to " ")))
            )
        )
    }

    @Test
    fun `songId 与 album 为空时字段缺省`() {
        val json = LyricInfoSerializer.encode(
            songName = "t", artist = "a", album = "  ", songId = "",
            entries = listOf(LyricEntry(mainLyric = listOf(1000f to "", 1000f to "词")))
        )!!
        val obj = parse(json)
        assertFalse(obj.has("songId"))
        assertFalse(obj.has("album"))
    }

    @Test
    fun `零点几秒的时间补零正确`() {
        val json = LyricInfoSerializer.encode(
            songName = "t", artist = "a", album = null, songId = null,
            entries = listOf(LyricEntry(mainLyric = listOf(45f to "", 45f to "词")))
        )!!
        assertEquals("[00:00.045]词", parse(json).get("lyric").asString)
    }

    @Test
    fun `songName 与 artist 保留原文不阻断歌词写入`() {
        val json = LyricInfoSerializer.encode(
            songName = "", artist = "", album = null, songId = "x",
            entries = listOf(LyricEntry(mainLyric = listOf(1000f to "", 1000f to "词")))
        )
        assertTrue(json != null && parse(json!!).get("lyric").asString == "[00:01.000]词")
    }

    @Test
    fun `协议建议字段齐全且代次按传入写入`() {
        val json = LyricInfoSerializer.encode(
            songName = "歌", artist = "手", album = "专", songId = "id-9",
            entries = listOf(LyricEntry(mainLyric = listOf(1000f to "", 1000f to "词"))),
            sessionGeneration = 7
        )!!
        val obj = parse(json)
        assertEquals(0, obj.get("lyricType").asInt)
        assertEquals(false, obj.get("noLyric").asBoolean)
        assertEquals("com.sonify.music", obj.get("provider").asString)
        assertEquals("com.sonify.music-v1", obj.get("source").asString)
        assertEquals("id-9|歌|手", obj.get("trackKey").asString)
        assertEquals(7, obj.get("sessionGeneration").asInt)
    }

    @Test
    fun `代次为零与身份为空时相应字段缺省`() {
        val json = LyricInfoSerializer.encode(
            songName = "", artist = "手", album = null, songId = null,
            entries = listOf(LyricEntry(mainLyric = listOf(1000f to "", 1000f to "词"))),
            sessionGeneration = 0
        )!!
        val obj = parse(json)
        assertFalse(obj.has("sessionGeneration"))
        assertFalse(obj.has("songId"))
        // 无 mediaId 且无歌名：无可稳定身份键
        assertFalse(obj.has("trackKey"))
    }

    @Test
    fun `无 songId 时 trackKey 退化为歌名加歌手身份`() {
        val json = LyricInfoSerializer.encode(
            songName = "歌", artist = "手", album = null, songId = null,
            entries = listOf(LyricEntry(mainLyric = listOf(1000f to "", 1000f to "词")))
        )!!
        assertEquals("|歌|手", parse(json).get("trackKey").asString)
    }
}
