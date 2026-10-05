package yos.music.player.code.utils.lrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class YosLrcFactoryTest {
    private val factory = YosLrcFactory()

    @Test
    fun sameTimestampLrcKeepsInlineTranslation() {
        val entries = factory.formatLrcEntries("[00:01.00]Hello\n[00:01.00]你好")

        assertEquals(1, entries.size)
        assertEquals("Hello", entries.single().mainLyric.joinToString("") { it.second })
        assertEquals("你好", entries.single().translation)
    }

    @Test
    fun separateTranslationLrcIsMatchedByTimestamp() {
        val entries = factory.formatLrcEntries(
            "[00:01.00]Hello\n[00:03.00]World",
            "[00:01.20]你好\n[00:03.10]世界"
        )

        assertEquals(listOf("你好", "世界"), entries.map { it.translation })
    }

    @Test
    fun unicodeEscapedTranslationIsDecodedBeforeRendering() {
        val entry = factory.formatLrcEntries(
            "[00:01.00]If you",
            "[00:01.00]\\U82E5\\U4F60"
        ).single()

        assertEquals("若你", entry.translation)
    }

    @Test
    fun singleLanguageLrcHasNoSyntheticTranslation() {
        val entry = factory.formatLrcEntries("[00:01.00]Hello").single()

        assertNull(entry.translation)
        assertEquals("Hello", entry.mainLyric.joinToString("") { it.second })
    }

    @Test
    fun krcKeepsWordTimingWhenTranslationHasWholeSecondTimestamp() {
        val entry = factory.formatKrcEntries(
            "[1000,2000]<0,500,0>H<500,500,0>i",
            "[00:01]你好"
        ).single()

        assertEquals("你好", entry.translation)
        assertTrue(entry.mainLyric.map { it.first }.distinct().size > 2)
    }

    @Test
    fun embeddedTranslationsSkipOriginalSingerMetadataRows() {
        val languageJson = """
            {"content":[{"language":0,"lyricContent":[["译文一"],["译文二"]]}]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            """
                [language:$encoded]
                [0,500]原曲歌手：歌手名
                [1000,1000]<0,500,0>First<500,500,0>line
                [3000,1000]<0,500,0>Second<500,500,0>line
            """.trimIndent()
        )

        assertEquals(listOf("Firstline", "Secondline"), entries.map { it.mainLyric.joinToString("") { pair -> pair.second } })
        assertEquals(listOf("译文一", "译文二"), entries.map { it.translation })
    }

    @Test
    fun emptyEmbeddedTranslationSlotDoesNotShiftLaterLines() {
        val languageJson = """{"content":[{"language":0,"lyricContent":[["译文一"],[],["译文三"]]}]}"""
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            """
                [language:$encoded]
                [1000,1000]<0,500,0>First<500,500,0>line
                [3000,1000]<0,500,0>Middle<500,500,0>line
                [5000,1000]<0,500,0>Third<500,500,0>line
            """.trimIndent()
        )

        assertEquals(listOf("译文一", null, "译文三"), entries.map { it.translation })
    }

    @Test
    fun krcEndMarkerIsNotUsedAsTranslation() {
        val entry = factory.formatKrcEntries(
            "[1000,2000]<0,500,0>H<500,500,0>i"
        ).single()

        assertNull(entry.translation)
        assertEquals("Hi", entry.mainLyric.joinToString("") { it.second })
        assertEquals(3000f, entry.mainLyric.last().first, 0.0f)
    }

    @Test
    fun krcLanguagePayloadProvidesTranslation() {
        val languageJson = """
            {"content":[
              {"language":0,"lyricContent":[["你好"]]},
              {"language":1,"lyricContent":[["ni","hao"]]}
            ]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H<500,500,0>i"
        )

        assertEquals("你好", entries.single().translation)
    }

    @Test
    fun krcLanguagePayloadAllowsFieldsAfterLyricContent() {
        val languageJson = """
            {"content":[{"language":0,"lyricContent":[["你好"]],"extra":true}]}
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entry = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H"
        ).single()

        assertEquals("你好", entry.translation)
    }

    @Test
    fun romanizationPayloadIsNotShownAsTranslation() {
        val languageJson = """{"content":[{"language":1,"lyricContent":[["若你"]]}]}"""
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entry = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H"
        ).single()

        assertNull(entry.translation)
    }

    @Test
    fun explicitTranslationTakesPriorityOverEmbeddedKrcTranslation() {
        val languageJson = """{"content":[{"language":0,"lyricContent":[["内嵌译文"]]}]}"""
        val encoded = Base64.getEncoder().encodeToString(languageJson.toByteArray())
        val entries = factory.formatKrcEntries(
            "[language:$encoded]\n[1000,2000]<0,500,0>H<500,500,0>i",
            "[00:01.00]显式译文"
        )

        assertEquals("显式译文", entries.single().translation)
    }

    @Test
    fun malformedLanguagePayloadDegradesWithoutDroppingKrc() {
        val entries = factory.formatKrcEntries(
            "[language:not-valid-base64]\n[1000,2000]<0,500,0>H"
        )

        assertTrue(entries.isNotEmpty())
        assertNull(entries.single().translation)
    }

    // ---- 逐字时间戳 LRC（每个字符各带 [mm:ss.xxx] 标签，常见于 FLAC 内嵌歌词）----

    @Test
    fun wordTimedLrcMergesIntoSingleKaraokeEntry() {
        val entries = factory.formatLrcEntries("[00:10.000]夜[00:10.500]奔[00:11.000] - [00:11.500]黄")

        assertEquals(1, entries.size)
        val entry = entries.single()
        // 词间空格保留（" - " 不被 trim 吞掉），不再一字一行
        assertEquals("夜奔 - 黄", entry.mainLyric.joinToString("") { it.second })
        // 词级形状：行起点锚点 + 逐词 + 尾部空锚点（与 KRC 逐词路径同形）
        assertEquals(listOf("", "夜", "奔", " - ", "黄", ""), entry.mainLyric.map { it.second })
        // 词时间语义 = 该词高亮结束时间 = 下一个标签时间
        assertEquals(10000f, entry.mainLyric.first().first, 0f)
        assertEquals(10500f, entry.mainLyric[1].first, 0f)
        assertEquals(11000f, entry.mainLyric[2].first, 0f)
        assertEquals(11500f, entry.mainLyric[3].first, 0f)
    }

    @Test
    fun wordTimedLrcKeepsEnglishTokensIntact() {
        // 夜奔-黄诗扶 FLAC 内嵌歌词的真实两行：英文按整词带标签，中文按字带标签
        val lrc = "[00:02.108]Tureleon[00:02.176]郭[00:02.244]超[00:02.312]\n" +
            "[00:02.449] [00:02.517]Guitar：[00:02.585]Tureleon[00:02.653]郭[00:02.721]超[00:02.789]"
        val entries = factory.formatLrcEntries(lrc)

        assertEquals(2, entries.size)
        assertEquals("Tureleon郭超", entries[0].mainLyric.joinToString("") { it.second })
        assertTrue(entries[0].mainLyric.size <= 5)
        assertEquals(" Guitar：Tureleon郭超", entries[1].mainLyric.joinToString("") { it.second })
        // 前行末词的结束时间由下一行首个时间戳回填
        assertEquals(2449f, entries[0].mainLyric.last().first, 0f)
    }

    @Test
    fun lastWordOfFinalWordTimedLineFallsBackToStartPlusThreeSeconds() {
        val entry = factory.formatLrcEntries("[00:10.000]夜[00:10.500]奔").single()

        assertEquals(13000f, entry.mainLyric.last().first, 0f)
    }

    @Test
    fun wordTimedLrcTranslationMatchesByTimestamp() {
        val entries = factory.formatLrcEntries(
            "[00:10.000]夜[00:10.500]奔\n[00:20.000]第[00:20.500]二[00:21.000]行",
            "[00:10.200]逃\n[00:20.100]Line two"
        )

        assertEquals(listOf("逃", "Line two"), entries.map { it.translation })
    }

    @Test
    fun classicMultiTimestampLrcBehaviorIsUnchanged() {
        val entries = factory.formatLrcEntries("[00:01.00][00:02.00]Hello\n[00:03.00]World")

        assertEquals(listOf("Hello", "World"), entries.map { it.mainLyric.joinToString("") { pair -> pair.second } })
        assertEquals(listOf(2000f, 3000f), entries.map { it.startTime })
        // 行级行保持两元素形状（行级高亮，无词级时间轴）
        assertTrue(entries.all { it.mainLyric.size == 2 })
    }

    @Test
    fun plainTextFallbackPathIsUnchangedByWordTimedParsing() {
        val entries = factory.formatLrcEntriesWithFallback("第一行\n第二行", 20_000)

        assertEquals(2, entries.size)
        assertEquals(listOf("第一行", "第二行"), entries.map { it.mainLyric.joinToString("") { pair -> pair.second } })
        assertEquals(listOf(0f, 10000f), entries.map { it.startTime })
        assertTrue(entries.all { it.mainLyric.size == 2 })
    }
}
