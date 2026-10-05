package yos.music.player.data.objects

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import yos.music.player.data.repositories.ArtistAudioPage
import yos.music.player.data.repositories.KugouNewSong

@OptIn(ExperimentalCoroutinesApi::class)
class ArtistSongsStateTest {
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun artistStatesAreIsolatedAndRegistryReusesOnlyMatchingArtist() = runTest(dispatcher) {
        val cachedA = ArtistSongsObject.forArtist("state-test-artist-a")
        val cachedB = ArtistSongsObject.forArtist("state-test-artist-b")
        assertSame(cachedA, ArtistSongsObject.forArtist("state-test-artist-a"))
        assertNotSame(cachedA, cachedB)
        assertNotSame(cachedA.songs, cachedB.songs)

        val requests = mutableListOf<Pair<String, Int>>()
        val a = ArtistSongsState("artist-a", fetchPage = { id, pageNumber ->
            requests += id to pageNumber
            Result.success(page(song("a"), rawCount = 30))
        }, requestScope = backgroundScope)
        val b = ArtistSongsState("artist-b", fetchPage = { id, pageNumber ->
            requests += id to pageNumber
            Result.success(page(song("b"), rawCount = 1))
        }, requestScope = backgroundScope)

        assertTrue(a.loadNextPage())
        assertEquals(1, b.page.value)
        assertTrue(b.songs.value.isEmpty())
        assertTrue(b.loadNextPage())

        assertEquals(listOf("a"), a.songs.value.map { it.hash })
        assertEquals(listOf("b"), b.songs.value.map { it.hash })
        assertEquals(2, a.page.value)
        assertEquals(2, b.page.value)
        assertFalse(a.endReached.value)
        assertTrue(b.endReached.value)
        assertEquals(listOf("artist-a" to 1, "artist-b" to 1), requests)
    }

    @Test
    fun concurrentRequestsForSamePageAreDeduplicated() = runTest(dispatcher) {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val state = ArtistSongsState("dedupe", fetchPage = { _, pageNumber ->
            calls += 1
            assertEquals(1, pageNumber)
            gate.await()
            Result.success(page(song("only"), song("only"), rawCount = 2))
        }, requestScope = backgroundScope)

        val first = launch { assertTrue(state.loadNextPage()) }
        advanceUntilIdle()
        val second = launch { assertFalse(state.loadNextPage()) }
        advanceUntilIdle()
        assertEquals(1, calls)
        assertTrue(state.loading.value)
        assertEquals(1, state.page.value)

        gate.complete(Unit)
        advanceUntilIdle()
        first.join()
        second.join()
        assertEquals(listOf("only"), state.songs.value.map { it.hash })
        assertEquals(2, state.page.value)
        assertFalse(state.loading.value)
    }

    @Test
    fun failedAppendPreservesCursorAndCanBeRetried() = runTest(dispatcher) {
        val requestedPages = mutableListOf<Int>()
        val state = ArtistSongsState("retry", fetchPage = { _, pageNumber ->
            requestedPages += pageNumber
            when (requestedPages.size) {
                1 -> Result.success(page(song("first"), rawCount = 30))
                2 -> Result.failure(IllegalStateException("temporary"))
                else -> Result.success(page(song("first"), song("second"), rawCount = 2))
            }
        }, requestScope = backgroundScope)

        assertTrue(state.loadNextPage())
        val firstSongs = state.songs.value
        assertEquals(2, state.page.value)
        assertFalse(state.loadNextPage())
        assertEquals(2, state.page.value)
        assertSame(firstSongs, state.songs.value)
        assertEquals("temporary", state.loadError.value)
        assertFalse(state.loading.value)
        assertFalse(state.endReached.value)

        // 使用页面的显式重试入口，同一页成功后才推进游标。
        state.requestNextPage()
        testScheduler.runCurrent()
        assertEquals(listOf(1, 2, 2), requestedPages)
        assertEquals(listOf("first", "second"), state.songs.value.map { it.hash })
        assertEquals(3, state.page.value)
        assertNull(state.loadError.value)
        assertTrue(state.endReached.value)
    }

    @Test
    fun fullPageMadeOnlyOfDuplicatesDoesNotEndEarly() = runTest(dispatcher) {
        val requestedPages = mutableListOf<Int>()
        val repeated = song("same")
        val state = ArtistSongsState("duplicates", fetchPage = { _, pageNumber ->
            requestedPages += pageNumber
            when (pageNumber) {
                1, 2 -> Result.success(ArtistAudioPage(List(30) { repeated }, 30))
                3 -> Result.success(page(song("new"), rawCount = 1))
                else -> error("unexpected page $pageNumber")
            }
        }, requestScope = backgroundScope)

        assertTrue(state.loadNextPage())
        assertFalse(state.endReached.value)
        assertEquals(listOf("same"), state.songs.value.map { it.hash })
        assertTrue(state.loadNextPage())
        assertFalse(state.endReached.value)
        assertEquals(3, state.page.value)
        assertEquals(listOf("same"), state.songs.value.map { it.hash })
        assertTrue(state.loadNextPage())
        assertEquals(listOf("same", "new"), state.songs.value.map { it.hash })
        assertEquals(listOf(1, 2, 3), requestedPages)
        assertTrue(state.endReached.value)
        assertFalse(state.loadNextPage())
        assertEquals(listOf(1, 2, 3), requestedPages)
    }

    @Test
    fun ensureLoadedContinuesAfterCallingPageScopeIsCancelled() = runTest(dispatcher) {
        val gate = CompletableDeferred<Unit>()
        var calls = 0
        val requestScope = CoroutineScope(SupervisorJob() + dispatcher)
        val pageScope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val state = ArtistSongsState("independent", fetchPage = { _, _ ->
                calls += 1
                gate.await()
                Result.success(page(song("loaded"), rawCount = 1))
            }, requestScope = requestScope)

            val pageJob = pageScope.launch {
                state.ensureLoaded()
                awaitCancellation()
            }
            advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(state.loading.value)
            pageJob.cancelAndJoin()
            pageScope.cancel()

            // 下一页组合接续首拉，不创建第二个在途请求。
            state.ensureLoaded()
            advanceUntilIdle()
            assertEquals(1, calls)
            assertTrue(state.loading.value)
            gate.complete(Unit)
            advanceUntilIdle()
            assertEquals(listOf("loaded"), state.songs.value.map { it.hash })
            assertFalse(state.loading.value)
            assertEquals(2, state.page.value)
            state.ensureLoaded()
            advanceUntilIdle()
            assertEquals(1, calls)
        } finally {
            pageScope.cancel()
            requestScope.cancel()
        }
    }

    @Test
    fun ensureLoadedDoesNotAutomaticallyRetryFirstPageError() = runTest(dispatcher) {
        var calls = 0
        val state = ArtistSongsState("first-page-error", fetchPage = { _, _ ->
            calls += 1
            Result.failure(IllegalStateException("offline"))
        }, requestScope = backgroundScope)

        state.ensureLoaded()
        testScheduler.runCurrent()
        assertEquals("offline", state.loadError.value)
        assertEquals(1, state.page.value)
        state.ensureLoaded()
        testScheduler.runCurrent()
        assertEquals(1, calls)
        assertFalse(state.loading.value)
    }

    private fun song(hash: String) = KugouNewSong(
        hash = hash,
        name = hash,
        author = "artist",
        albumName = null,
        durationMs = 1_000L,
        artworkUrl = null
    )

    private fun page(vararg songs: KugouNewSong, rawCount: Int) =
        ArtistAudioPage(songs.toList(), rawCount)
}
