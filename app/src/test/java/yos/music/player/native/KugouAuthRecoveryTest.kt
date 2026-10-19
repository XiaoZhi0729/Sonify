package yos.music.player.native

import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class KugouAuthRecoveryTest {
    @Test fun oldRefreshCannotOverwriteSameAccountRelogin() = runTest {
        val auth = KugouAuthSession(scope = backgroundScope)
        auth.login("old", "user", "vip-old")
        val original = auth.snapshot()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        val refresh = async {
            auth.refresh(original) {
                started.complete(Unit)
                finish.await()
                AuthRefreshResult.Updated(AuthCredentials("refreshed", "user", null))
            }
        }
        started.await()
        auth.clear()
        auth.login("new-login", "user", "vip-new")
        finish.complete(Unit)
        assertTrue(refresh.await() is AuthRefreshResult.Superseded)
        assertEquals("new-login", auth.snapshot().token)
        assertEquals("vip-new", auth.snapshot().vipToken)
    }

    @Test fun failedRefreshIsSharedAndThrottledOnlyForSameGeneration() = runTest {
        var clock = 0L
        val auth = KugouAuthSession(nowMillis = { clock }, scope = backgroundScope)
        auth.login("t", "u", null)
        val original = auth.snapshot()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun request(): AuthRefreshResult {
            calls++
            started.complete(Unit)
            finish.await()
            return AuthRefreshResult.Rejected
        }
        val first = async { auth.refresh(original, ::request) }
        started.await()
        val second = async { auth.refresh(original, ::request) }
        finish.complete(Unit)
        assertSame(AuthRefreshResult.Rejected, first.await())
        assertSame(AuthRefreshResult.Rejected, second.await())
        assertSame(AuthRefreshResult.Rejected, auth.refresh(original, ::request))
        assertEquals(1, calls)
        clock = 1501
        auth.refresh(original, ::request)
        assertEquals(2, calls)
        auth.login("t", "u", null)
        auth.refresh(auth.snapshot(), ::request)
        assertEquals(3, calls)
    }

    @Test fun missingVipRefreshPreservesOnlyCapturedVipAndRejectsDifferentUser() = runTest {
        val auth = KugouAuthSession(scope = backgroundScope)
        auth.login("t", "u", "captured-vip")
        val original = auth.snapshot()
        val result = auth.refresh(original) {
            AuthRefreshResult.Updated(AuthCredentials("updated", "u", null))
        }
        assertTrue(result is AuthRefreshResult.Committed)
        assertEquals("captured-vip", auth.snapshot().vipToken)
        assertTrue(auth.snapshot().version > original.version)
        val now = auth.snapshot()
        val mismatch = auth.refresh(now) { AuthRefreshResult.Updated(AuthCredentials("wrong", "other", "other-vip")) }
        assertSame(AuthRefreshResult.Unavailable, mismatch)
        assertEquals("updated", auth.snapshot().token)
    }

    @Test fun cancelledRefreshOwnerDoesNotCancelOtherWaiters() = runTest {
        val auth = KugouAuthSession(scope = backgroundScope)
        auth.login("old", "u", null)
        val snapshot = auth.snapshot()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        val owner = async { auth.refresh(snapshot) {
            calls++
            started.complete(Unit)
            finish.await()
            AuthRefreshResult.Updated(AuthCredentials("new", "u", null))
        } }
        started.await()
        val waiter = async { auth.refresh(snapshot) { error("must join shared refresh") } }
        owner.cancel()
        owner.join()
        finish.complete(Unit)
        assertTrue(waiter.await() is AuthRefreshResult.Committed)
        assertEquals(1, calls)
        assertEquals("new", auth.snapshot().token)
    }

    @Test fun deviceRegistrationIsSharedAndThrottledPerGeneration() = runTest {
        var clock = 0L
        val auth = KugouAuthSession(nowMillis = { clock }, scope = backgroundScope)
        auth.login("t", "u", null)
        val snapshot = auth.snapshot()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<Unit>()
        var calls = 0
        suspend fun register(): Boolean {
            calls++
            started.complete(Unit)
            finish.await()
            return true
        }
        val owner = async { auth.register(snapshot, ::register) }
        started.await()
        val waiter = async { auth.register(snapshot, ::register) }
        owner.cancel()
        owner.join()
        finish.complete(Unit)
        assertTrue(waiter.await())
        assertTrue(auth.register(snapshot, ::register))
        assertEquals(1, calls)
        clock = 1501
        assertTrue(auth.register(snapshot, ::register))
        assertEquals(2, calls)
    }

    @Test fun emptyRefreshCredentialsCannotCommitEvenOnSuccessStatus() = runTest {
        val auth = KugouAuthSession(scope = backgroundScope)
        auth.login("t", "u", "vip")
        val snapshot = auth.snapshot()
        assertSame(AuthRefreshResult.Unavailable, auth.refresh(snapshot) {
            AuthRefreshResult.Updated(AuthCredentials("", "u", null))
        })
        assertEquals(snapshot.version, auth.snapshot().version)
        assertEquals("t", auth.snapshot().token)
    }

    @Test fun restoreCannotOverwriteLoginAndSnapshotHeadersNeverMixGenerations() {
        val auth = KugouAuthSession()
        val beforeRestore = auth.snapshot()
        val first = auth.login("one", "one-user", "one-vip")
        val second = auth.login("two", "two-user", null)
        assertFalse(auth.restore(beforeRestore, "disk", "disk-user", "disk-vip"))
        assertEquals("token=one;userid=one-user;vip_token=one-vip", first.header())
        assertEquals("token=two;userid=two-user", second.header())
        assertEquals(second.version, auth.snapshot().version)
        assertFalse(first.toString().contains("one-vip"))
    }
}
