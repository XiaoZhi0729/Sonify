package yos.music.player.code.utils.lrc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricLoadCoordinatorTest {
    private fun payload(text: String) = LyricLoadCoordinator.Payload(
        entries = listOf(LyricEntry(mainLyric = listOf(0f to text))),
        otherSideForLines = listOf(false)
    )

    @Test
    fun trackEventAfterLyricSuccessDoesNotReplaceCachedLyrics() {
        val coordinator = LyricLoadCoordinator()
        val selection = coordinator.select("kugou-online-a") as LyricLoadCoordinator.Selection.Load
        val result = payload("A")

        assertTrue(coordinator.complete(selection.request, result))
        val replay = coordinator.select("kugou-online-a")

        assertTrue(replay is LyricLoadCoordinator.Selection.Cached)
        assertEquals(result, (replay as LyricLoadCoordinator.Selection.Cached).payload)
    }

    @Test
    fun staleSongCompletionIsCachedButCannotPublishToCurrentSong() {
        val coordinator = LyricLoadCoordinator()
        val a = coordinator.select("kugou-online-a") as LyricLoadCoordinator.Selection.Load
        val b = coordinator.select("kugou-online-b") as LyricLoadCoordinator.Selection.Load

        assertTrue(!coordinator.complete(a.request, payload("A")))
        assertTrue(coordinator.complete(b.request, payload("B")))

        val aReplay = coordinator.select("kugou-online-a")
        assertTrue(aReplay is LyricLoadCoordinator.Selection.Cached)
        assertEquals("A", (aReplay as LyricLoadCoordinator.Selection.Cached)
            .payload.entries.first().mainLyric.first().second)
    }

    @Test
    fun failedRequestCanBeRetried() {
        val coordinator = LyricLoadCoordinator()
        val first = coordinator.select("kugou-online-a") as LyricLoadCoordinator.Selection.Load

        assertTrue(coordinator.fail(first.request))
        val retry = coordinator.select("kugou-online-a")

        assertTrue(retry is LyricLoadCoordinator.Selection.Load)
        assertTrue((retry as LyricLoadCoordinator.Selection.Load).request.generation > first.request.generation)
    }

    @Test
    fun selectingSameSongWhileLoadingDoesNotStartDuplicateRequest() {
        val coordinator = LyricLoadCoordinator()
        val first = coordinator.select("kugou-online-a")
        val second = coordinator.select("kugou-online-a")

        assertTrue(first is LyricLoadCoordinator.Selection.Load)
        assertTrue(second is LyricLoadCoordinator.Selection.InFlight)
        assertEquals(
            (first as LyricLoadCoordinator.Selection.Load).request,
            (second as LyricLoadCoordinator.Selection.InFlight).request
        )
    }
}
