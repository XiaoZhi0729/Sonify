package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouRecommendPlaylistTest {
    private fun parse(fields: Map<String, Any?>): KugouRecommendPlaylist =
        parseRecommendPlaylist(fields) { it }!!

    @Test
    fun prefersSongcountThenSongCountThenCount() {
        assertEquals(11, parse(mapOf("global_collection_id" to "a", "specialname" to "A", "songcount" to 11, "song_count" to 12, "count" to 13)).songCount)
        assertEquals(12, parse(mapOf("global_collection_id" to "b", "specialname" to "B", "song_count" to 12, "count" to 13)).songCount)
        assertEquals(13, parse(mapOf("global_collection_id" to "c", "specialname" to "C", "count" to 13)).songCount)
    }

    @Test
    fun preservesKnownZero() {
        assertEquals(0, parse(mapOf("global_collection_id" to "zero", "specialname" to "Zero", "songcount" to 0)).songCount)
    }

    @Test
    fun representsMissingCountAsUnknown() {
        assertNull(parse(mapOf("global_collection_id" to "unknown", "specialname" to "Unknown")).songCount)
    }

    @Test
    fun acceptsNumericStrings() {
        assertEquals(37, parse(mapOf("global_collection_id" to "string", "specialname" to "String", "count" to "37")).songCount)
    }

    @Test
    fun detailCountDoesNotUsePreviewArrayLength() {
        assertEquals(
            37,
            KugouRepository.parseRecommendPlaylistSongCount(mapOf("count" to 37, "songs" to listOf("one")))
        )
        assertNull(
            KugouRepository.parseRecommendPlaylistSongCount(mapOf("songs" to listOf("one")))
        )
    }
}
