package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KugouDynamicCoverParseTest {

    @Test
    fun `present with primary h264 url`() {
        val result = parseDyCoverFields(
            mapOf(
                "status" to 1,
                "error_code" to 0,
                "data" to listOf(
                    mapOf(
                        "album_audio_id" to 123456L,
                        "dycover" to mapOf(
                            "h264_url" to "https://cdn.example.com/cover.mp4",
                            "h265_url" to "https://cdn.example.com/cover_h265.mp4",
                            "h264_hash" to "abc123"
                        )
                    )
                )
            ),
            albumAudioId = 999L
        )
        assertEquals(
            DyCoverLookupResult.Present(
                albumAudioId = 123456L,
                h264Url = "https://cdn.example.com/cover.mp4",
                h265Url = "https://cdn.example.com/cover_h265.mp4",
                h264Hash = "abc123"
            ),
            result
        )
    }

    @Test
    fun `falls back to h264 backup url when primary is blank`() {
        val result = parseDyCoverFields(
            mapOf(
                "status" to 1,
                "error_code" to 0,
                "data" to listOf(
                    mapOf(
                        "dycover" to mapOf(
                            "h264_url" to "",
                            "h264_backup_url" to listOf("", "https://backup.example.com/cover.mp4")
                        )
                    )
                )
            ),
            albumAudioId = 777L
        )
        assertEquals(
            DyCoverLookupResult.Present(
                albumAudioId = 777L,
                h264Url = "https://backup.example.com/cover.mp4"
            ),
            result
        )
    }

    @Test
    fun `empty data or missing dycover returns Absent`() {
        assertEquals(
            DyCoverLookupResult.Absent,
            parseDyCoverFields(
                mapOf("status" to 1, "error_code" to 0, "data" to emptyList<Any>()),
                albumAudioId = 1L
            )
        )
        assertEquals(
            DyCoverLookupResult.Absent,
            parseDyCoverFields(
                mapOf(
                    "status" to 1,
                    "error_code" to 0,
                    "data" to listOf(mapOf("album_audio_id" to 1L))
                ),
                albumAudioId = 1L
            )
        )
    }

    @Test
    fun `upstream error status returns null so transient failure is not cached`() {
        assertNull(
            parseDyCoverFields(
                mapOf("status" to 0, "error_code" to 20010),
                albumAudioId = 1L
            )
        )
        assertNull(
            parseDyCoverFields(
                mapOf("status" to 1, "error_code" to 500),
                albumAudioId = 1L
            )
        )
    }
}
