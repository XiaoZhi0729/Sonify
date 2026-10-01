package yos.music.player.data.repositories

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouLyricResponseTest {
    @Test
    fun mapsLrcAndTranslationAliasesFromDataObject() {
        val response = parseKugouLyricFields(
            mapOf(
                "decoded_content" to "[00:01.00]Hello",
                "trans" to "[00:01.00]你好"
            ),
            krcResponse = false
        )

        assertEquals("[00:01.00]Hello", response.lrc)
        assertEquals("[00:01.00]你好", response.translation)
        assertNull(response.krc)
    }

    @Test
    fun mapsKrcSpecificAliasesAndFallsBackToDecodeContent() {
        val response = parseKugouLyricFields(
            mapOf("decodeContent" to "[1000,2000]<0,500>Hi"),
            krcResponse = true
        )

        assertEquals("[1000,2000]<0,500>Hi", response.krc)
    }

    @Test
    fun blankTranslationAliasIsIgnored() {
        val response = parseKugouLyricFields(
            mapOf(
                "lrcContent" to "[00:01.00]Hello",
                "translated_content" to "  "
            ),
            krcResponse = false
        )

        assertNull(response.translation)
    }
}
