package yos.music.player.data.repositories

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import yos.music.player.data.objects.CollectedPlaylistSnapshot

class CollectedPlaylistLogicTest {
    private fun row(id: String, gid: String = "collection_3_99_8_0", name: String = "同名") =
        KugouPlaylist(id, "collection_3_7_${id}_0", name, "", 1, type = 1, source = 1, listCreateGid = gid)

    @Test fun sourceIdentityCannotConfuseAccountAndSourceIds() {
        val source = playlistSourceIdentity("collection_3_99_8_12")!!
        assertEquals("99", source.userid)
        assertEquals("8", source.listid)
        assertNull(playlistSourceIdentity("8"))
        assertNull(playlistSourceIdentity("collection_3_0_8_12"))
        assertNull(playlistSourceIdentity("collection_3_99_8"))
        assertNull(playlistSourceIdentity("collection_3_99_8_bad"))
        assertEquals("collection_3_99_8_0", row("42").sourceGid)
        assertEquals("42", row("42").listid)
        assertEquals("", row("42").copy(listCreateGid = "").sourceGid)
    }

    @Test fun collectionCannotBecomeWritableEvenWithExplicitPermission() {
        assertFalse(row("42").copy(isEdit = true).canEditTracks)
        val own = row("42").copy(type = 0, source = 1)
        assertTrue(own.canEditTracks)
        assertFalse(own.copy(isEdit = false).canEditTracks)
        assertTrue(own.copy(isDef = 2).canEditTracks)
        assertFalse(row("42").copy(source = 2, isEdit = true).canEditTracks)
        assertFalse(row("42").copy(source = 7).isCollectedPlaylist)
        assertTrue(row("42").copy(source = 3).isCollectedPlaylist)
    }

    @Test fun missingStatusIsUnknownAndNestedFailureOverridesSuccess() {
        assertEquals(PlaylistBusinessOutcome.UNKNOWN, playlistBusinessOutcome(mapOf("data" to mapOf("id" to "42"))))
        assertEquals(PlaylistBusinessOutcome.SUCCESS, playlistBusinessOutcome(mapOf("status" to 1)))
        assertEquals(PlaylistBusinessOutcome.FAILURE, playlistBusinessOutcome(mapOf("status" to 1, "error_code" to 20028)))
        assertEquals(PlaylistBusinessOutcome.FAILURE, playlistBusinessOutcome(mapOf("status" to 1, "data" to mapOf("errcode" to 17))))
        assertEquals(PlaylistBusinessOutcome.FAILURE, playlistBusinessOutcome(mapOf("status" to 0, "errcode" to 0)))
    }

    @Test fun laterPageFailureNeverReturnsPartialLibrary() = runBlocking {
        var calls = 0
        val result = loadAllUserPlaylistPages(1) { page ->
            calls++
            if (page == 1) Result.success(UserPlaylistPage(listOf(row("1"))))
            else Result.failure(IllegalStateException("page unavailable"))
        }
        assertTrue(result.isFailure)
        assertEquals(2, calls)
    }

    @Test fun fullPaginationPreservesEntriesBeyondFirstPageAndStopsAtTotal() = runBlocking {
        val pages = mutableListOf<Int>()
        val result = loadAllUserPlaylistPages(1) { page ->
            pages += page
            Result.success(UserPlaylistPage(listOf(row(page.toString())), total = 4))
        }
        assertEquals(listOf("1", "2", "3", "4"), result.getOrThrow().map { it.listid })
        assertEquals(listOf(1, 2, 3, 4), pages)
    }

    @Test fun repeatedPagesAndTruncatedTotalFail() = runBlocking {
        val repeated = loadAllUserPlaylistPages(1) { Result.success(UserPlaylistPage(listOf(row("42")))) }
        assertTrue(repeated.isFailure)
        val truncated = loadAllUserPlaylistPages(2) { Result.success(UserPlaylistPage(listOf(row("42")), 4)) }
        assertTrue(truncated.isFailure)
    }

    @Test fun sameNameDifferentSourcesStaySeparateAndDeleteTombstoneSuppressesResidue() {
        val a = row("42")
        val b = row("43", "collection_3_99_9_0")
        val result = mergeCollectedPlaylistEntries(listOf(a, b), listOf(a.copy(coverUrl = "cover")))
        assertEquals(2, result.size)
        assertEquals("42", result.first().listid)
        assertEquals("cover", result.first().coverUrl)
        assertEquals(listOf(b), mergeCollectedPlaylistEntries(listOf(a, b), emptyList(), setOf(a.sourceGid)))
    }

    @Test fun workCannotCrossAccountCredentialOrResetGeneration() {
        val generation = PlaylistGeneration("userA", 3, 4)
        assertTrue(generation.matches("userA", 3, 4))
        assertFalse(generation.matches("userB", 3, 4))
        assertFalse(generation.matches("userA", 5, 4))
        assertFalse(generation.matches("userA", 3, 6))
    }

    @Test fun snapshotRejectsWrongAccountNullItemsAndNullRequiredFields() {
        val gson = Gson()
        val valid = gson.toJson(CollectedPlaylistSnapshot("userA", listOf(row("42"))))
        assertEquals(1, gson.fromJson(valid, CollectedPlaylistSnapshot::class.java).validated("userA")!!.size)
        assertNull(gson.fromJson(valid, CollectedPlaylistSnapshot::class.java).validated("userB"))
        assertNull(gson.fromJson("""{"userid":"userA","entries":[null]}""", CollectedPlaylistSnapshot::class.java).validated("userA"))
        assertNull(gson.fromJson(valid.replace("\"name\":\"同名\"", "\"name\":null"), CollectedPlaylistSnapshot::class.java).validated("userA"))
    }
}
