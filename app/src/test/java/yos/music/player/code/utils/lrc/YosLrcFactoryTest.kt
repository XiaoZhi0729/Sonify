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
}
