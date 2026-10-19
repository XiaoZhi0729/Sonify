package yos.music.player.data.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SongPlayabilityProbeCoordinatorTest {
    @Test
    fun persistenceUsesStableAccountWhileRequestsIncludeAuthenticationVersion() {
        val before = SongPlayabilityAccountKey("123", true, 1)
        val refreshed = SongPlayabilityAccountKey("123", true, 2)
        val other = SongPlayabilityAccountKey("456", true, 2)
        val noVip = SongPlayabilityAccountKey("123", false, 2)
        assertEquals(before.storageNamespace, refreshed.storageNamespace)
        assertEquals(false, before.requestNamespace == refreshed.requestNamespace)
        assertEquals(false, before.storageNamespace == other.storageNamespace)
        assertEquals(false, before.storageNamespace == noVip.storageNamespace)
    }
    @Test
    fun oldAccountCompletionDoesNotSuppressNewAccountProbe() = runTest {
        val probes = SongPlayabilityProbeCoordinator<String>()
        val oldResponse = CompletableDeferred<String>()
        val old = async { probes.run("account.a1/hash", false, { true }) { oldResponse.await() } }
        runCurrent()
        assertEquals("new", probes.run("account.a2/hash", true, { false }) { "new" })
        oldResponse.complete("old")
        assertEquals("old", old.await())
        assertEquals("new", probes.run("account.a2/hash", true, { false }) { "duplicate" })
    }
    @Test
    fun manualRequestsShareInFlightAndFiveSecondResultIncludingAutomaticChecks() = runTest {
        var now = 0L
        var calls = 0
        val probes = SongPlayabilityProbeCoordinator<String>(clock = { now })
        val response = CompletableDeferred<String>()
        suspend fun check(fresh: Boolean) = probes.run("account/hash", fresh, { false }) {
            calls++
            response.await()
        }
        val first = async { check(true) }
        runCurrent()
        val second = async { check(true) }
        val automatic = async { check(false) }
        runCurrent()
        assertEquals(1, calls)
        response.complete("blocked")
        assertEquals("blocked", first.await())
        assertEquals("blocked", second.await())
        assertEquals("blocked", automatic.await())
        assertEquals("blocked", check(true))
        assertEquals(1, calls)
        now = 5_000
        assertEquals("blocked", check(true))
        assertEquals(2, calls)
    }

    @Test
    fun manualCheckWaitsForAutomaticThenRunsFreshOnce() = runTest {
        val probes = SongPlayabilityProbeCoordinator<String>()
        val automaticResponse = CompletableDeferred<String>()
        var freshCalls = 0
        val automatic = async {
            probes.run("account/hash", false, { true }) { automaticResponse.await() }
        }
        runCurrent()
        val manual = async {
            probes.run("account/hash", true, { false }) { freshCalls++; "fresh" }
        }
        val duplicate = async {
            probes.run("account/hash", true, { false }) { freshCalls++; "duplicate" }
        }
        runCurrent()
        assertEquals(0, freshCalls)
        automaticResponse.complete("unknown")
        assertEquals("unknown", automatic.await())
        assertEquals("fresh", manual.await())
        assertEquals("fresh", duplicate.await())
        assertEquals(1, freshCalls)
    }

    @Test
    fun cancellingWaiterDoesNotCancelSharedOwnerAndCancelledOwnerCanRetry() = runTest {
        val probes = SongPlayabilityProbeCoordinator<String>()
        val response = CompletableDeferred<String>()
        val owner = async { probes.run("account/hash", true, { false }) { response.await() } }
        runCurrent()
        val waiter = async { probes.run("account/hash", true, { false }) { "duplicate" } }
        runCurrent()
        waiter.cancel()
        response.complete("playable")
        assertEquals("playable", owner.await())

        val cancelled = async {
            probes.run("account/other", true, { false }) { CompletableDeferred<String>().await() }
        }
        runCurrent()
        cancelled.cancel()
        cancelled.join()
        assertEquals("retry", probes.run("account/other", true, { false }) { "retry" })
    }

    @Test
    fun inconclusiveBackgroundChecksAreThrottledPerHashAndAccount() = runTest {
        var now = 0L
        var calls = 0
        val probes = SongPlayabilityProbeCoordinator<String>(clock = { now })
        suspend fun check(key: String) = probes.run(key, fresh = false, inconclusive = { true }) {
            calls++
            "unknown"
        }

        assertEquals("unknown", check("account.1/hash"))
        assertEquals(null, check("account.1/hash"))
        assertEquals("unknown", check("account.1/other"))
        assertEquals("unknown", check("account.2/hash"))
        assertEquals(3, calls)
        now = 15_000
        assertEquals("unknown", check("account.1/hash"))
        assertEquals(4, calls)
    }
}
