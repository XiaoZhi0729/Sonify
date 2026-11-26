package yos.music.player.data.objects

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import yos.music.player.data.repositories.KugouPlaylist

class UserPlaylistsSnapshotTest {

    private val gson = Gson()

    private fun samplePlaylist(listid: String = "1001", name: String = "My Mix"): KugouPlaylist =
        KugouPlaylist(
            listid = listid,
            gid = "g_$listid",
            name = name,
            coverUrl = "https://img.example.com/$listid.jpg",
            songCount = 12
        )

    @Test
    fun validatedReturnsPlaylistsForMatchingUserid() {
        val snapshot = UserPlaylistsSnapshot(
            userid = "u_123",
            loadedAt = 1_700_000_000_000L,
            playlists = listOf(samplePlaylist("1"), samplePlaylist("2", "Chill"))
        )
        val decoded = gson.fromJson(gson.toJson(snapshot), UserPlaylistsSnapshot::class.java)
        val result = decoded.validated("u_123")
        assertEquals(2, result?.size)
        assertEquals("My Mix", result?.get(0)?.name)
        assertEquals("Chill", result?.get(1)?.name)
    }

    @Test
    fun validatedRejectsMismatchedOrBlankUserid() {
        val snapshot = UserPlaylistsSnapshot(
            userid = "u_123",
            loadedAt = 1_700_000_000_000L,
            playlists = listOf(samplePlaylist())
        )
        assertNull(snapshot.validated("u_999"))
        assertNull(snapshot.validated(""))
        assertNull(snapshot.validated("   "))
    }

    @Test
    fun validatedRejectsCorruptedEntriesMissingRequiredFields() {
        val json = """
            {
              "userid": "u_123",
              "loadedAt": 1000,
              "playlists": [
                { "listid": "10", "gid": "g10", "coverUrl": "https://a.jpg", "songCount": 5 }
              ]
            }
        """.trimIndent()
        val snapshot = gson.fromJson(json, UserPlaylistsSnapshot::class.java)
        assertNull(snapshot.validated("u_123"))
    }
}
