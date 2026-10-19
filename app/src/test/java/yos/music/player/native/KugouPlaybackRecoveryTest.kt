package yos.music.player.native

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import yos.music.player.data.repositories.KugouAuthInvalidException
import yos.music.player.data.repositories.KugouSignatureRejectedException
import yos.music.player.data.repositories.KugouTokenRejectedException
import yos.music.player.data.repositories.KugouVerificationRequiredException

class KugouPlaybackRecoveryTest {
    private val token = mapOf("error_code" to 20018, "status" to 0)
    private val verification = mapOf("errcode" to 20028, "status" to 0)
    private val success = mapOf("status" to 1, "data" to mapOf("url" to "playable"))

    private suspend fun scenario(
        responses: List<PlaybackResponse>,
        budget: PlaybackRecoveryBudget = PlaybackRecoveryBudget(1),
        refreshed: AuthRefreshResult = AuthRefreshResult.Committed(AuthSnapshot(2, "t", "u", null)),
        registered: Boolean = true,
        onRefresh: () -> Unit = {},
        onRegister: () -> Unit = {}
    ): Result<PlaybackResponse> {
        var version = budget.currentAuthVersion
        var index = 0
        return try {
            Result.success(recoverKugouPlayback(budget, { version }, {
                check(index < responses.size) { "Unexpected extra playback request" }
                responses[index++]
            }, {
                onRefresh()
                if (refreshed is AuthRefreshResult.Committed) version = refreshed.snapshot.version
                refreshed
            }, { onRegister(); registered }))
        } catch (error: Exception) { Result.failure(error) }
    }

    @Test fun bothHttp200And502TokenRejectionRefreshAndReplayExactlyOnce() = runTest {
        for (httpCode in listOf(200, 502)) {
            var refreshes = 0
            val budget = PlaybackRecoveryBudget(1)
            val result = scenario(listOf(PlaybackResponse(httpCode, token), PlaybackResponse(200, success)),
                budget, onRefresh = { refreshes++ })
            assertTrue(result.isSuccess)
            assertEquals(1, refreshes)
            assertEquals(1L, budget.initialAuthVersion)
            assertEquals(2L, budget.currentAuthVersion)
        }
    }

    @Test fun onlyExplicitRefreshRejectionOrRepeatedTokenRejectionConfirmsAuthInvalid() = runTest {
        assertTrue(scenario(listOf(PlaybackResponse(200, token)), refreshed = AuthRefreshResult.Rejected)
            .exceptionOrNull() is KugouAuthInvalidException)
        assertTrue(scenario(listOf(PlaybackResponse(200, token), PlaybackResponse(502, token)))
            .exceptionOrNull() is KugouAuthInvalidException)
        assertTrue(scenario(listOf(PlaybackResponse(200, token)), refreshed = AuthRefreshResult.Unavailable)
            .exceptionOrNull() is KugouTokenRejectedException)
    }

    @Test fun verificationReplay500OrEmptyBodyNeverBecomesAuthInvalid() = runTest {
        for (failure in listOf(PlaybackResponse(500, null), PlaybackResponse(200, null))) {
            var registrations = 0
            val error = scenario(listOf(PlaybackResponse(502, verification), failure),
                onRegister = { registrations++ }).exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error is KugouAuthInvalidException)
            assertFalse(error is KugouVerificationRequiredException)
            assertEquals(1, registrations)
        }
    }

    @Test fun verificationNeedsSuccessfulRegistrationBeforeReplayAndRemainsDistinct() = runTest {
        assertTrue(scenario(listOf(PlaybackResponse(200, verification)), registered = false)
            .exceptionOrNull() is KugouVerificationRequiredException)
        assertTrue(scenario(listOf(PlaybackResponse(200, verification), PlaybackResponse(200, verification)))
            .exceptionOrNull() is KugouVerificationRequiredException)
        assertTrue(scenario(listOf(PlaybackResponse(200, verification), PlaybackResponse(200, success))).isSuccess)
    }

    @Test fun illegalKeyNeverBlindlyRefreshes() = runTest {
        for (httpCode in listOf(200, 502)) {
            val error = scenario(listOf(PlaybackResponse(httpCode, mapOf("error_code" to 31833))),
                onRefresh = { fail("signature errors must not refresh") },
                onRegister = { fail("signature errors must not register") }).exceptionOrNull()
            assertTrue(error is KugouSignatureRejectedException)
        }
    }

    @Test fun oneBudgetLimitsRecoveryAcrossProtocolAndQualityFallbacks() = runTest {
        val budget = PlaybackRecoveryBudget(1)
        assertTrue(scenario(listOf(PlaybackResponse(200, verification), PlaybackResponse(200, success)), budget).isSuccess)
        val error = scenario(listOf(PlaybackResponse(200, token)), budget,
            onRefresh = { fail("shared budget must not refresh again") }).exceptionOrNull()
        assertTrue(error is KugouTokenRejectedException)
    }

    @Test fun committedRefreshIsRememberedAcrossLaterProtocolCalls() = runTest {
        val budget = PlaybackRecoveryBudget(1)
        assertTrue(scenario(listOf(PlaybackResponse(200, token), PlaybackResponse(200, success)), budget).isSuccess)
        assertTrue(scenario(listOf(PlaybackResponse(200, token)), budget,
            onRefresh = { fail("cannot spend another recovery") }).exceptionOrNull() is KugouAuthInvalidException)
    }

    @Test fun tokenReplayOrdinaryFailureDoesNotConfirmAuthInvalid() = runTest {
        for (failure in listOf(PlaybackResponse(500, null), PlaybackResponse(200, null))) {
            val error = scenario(listOf(PlaybackResponse(200, token), failure)).exceptionOrNull()
            assertTrue(error is IOException)
            assertFalse(error is KugouAuthInvalidException)
        }
    }

    @Test fun staleBudgetStopsBeforeTransportAndCancellationPropagates() = runTest {
        try {
            recoverKugouPlayback(PlaybackRecoveryBudget(1), { 2L },
                { error("stale budget must not request") }, { error("no refresh") }, { error("no register") })
            fail("expected stale-generation failure")
        } catch (_: CancellationException) { }
        try {
            recoverKugouPlayback(PlaybackRecoveryBudget(1), { 1L },
                { throw CancellationException("cancelled") }, { error("no refresh") }, { error("no register") })
            fail("expected cancellation")
        } catch (_: CancellationException) { }
    }

    @Test fun refreshVerificationAndSignatureKeepTheirOwnFailureType() = runTest {
        assertTrue(scenario(listOf(PlaybackResponse(200, token)), refreshed = AuthRefreshResult.VerificationRequired)
            .exceptionOrNull() is KugouVerificationRequiredException)
        assertTrue(scenario(listOf(PlaybackResponse(200, token)), refreshed = AuthRefreshResult.SignatureRejected)
            .exceptionOrNull() is KugouSignatureRejectedException)
    }

    @Test fun concurrentOldGenerationResponseSharesCommittedRefreshAndReplays() = runTest {
        val auth = KugouAuthSession(scope = backgroundScope)
        auth.login("old", "u", null)
        val original = auth.snapshot()
        val budget = PlaybackRecoveryBudget(original.version)
        var requests = 0
        val response = recoverKugouPlayback(budget, { auth.snapshot().version }, {
            requests++
            if (requests == 1) {
                // A different in-flight playback request refreshes while this request was travelling.
                auth.refresh(original) { AuthRefreshResult.Updated(AuthCredentials("new", "u", null)) }
                PlaybackResponse(502, token)
            } else PlaybackResponse(200, success)
        }, { error("must share the already-completed refresh") }, { error("no verification") },
            { auth.sharedRefresh(original.version) })
        assertEquals(success, response.root)
        assertEquals(2, requests)
        assertEquals(auth.snapshot().version, budget.currentAuthVersion)
        assertFalse(budget.consume())
    }

    @Test fun refreshResponseRequiresCredentialsAndPreservesNon2xxBusinessTypes() {
        assertSame(AuthRefreshResult.Unavailable, parseAuthRefreshResponse(200, mapOf("status" to 1, "data" to emptyMap<String, Any>())))
        assertSame(AuthRefreshResult.Rejected, parseAuthRefreshResponse(502, mapOf("error_code" to 20018)))
        assertSame(AuthRefreshResult.VerificationRequired, parseAuthRefreshResponse(502, verification))
        assertSame(AuthRefreshResult.SignatureRejected, parseAuthRefreshResponse(200, mapOf("error_code" to 31833)))
        assertSame(AuthRefreshResult.Unavailable, parseAuthRefreshResponse(500, null))
        val valid = parseAuthRefreshResponse(200, mapOf("status" to 1, "data" to mapOf("token" to "t", "userid" to 42)))
        assertTrue(valid is AuthRefreshResult.Updated)
        assertEquals("42", (valid as AuthRefreshResult.Updated).credentials.userid)
        assertNull(valid.credentials.vipToken)
    }

    @Test fun refreshRejectionIsEndpointScopedAndDoesNotTreatSignatureOrVerificationAsAuth() {
        assertTrue(isRefreshTokenRejected(mapOf("error_code" to "20018")))
        assertTrue(isRefreshTokenRejected(mapOf("data" to mapOf("message" to "token api error."))))
        for (code in listOf(20017, 20028, 31833)) assertFalse(isRefreshTokenRejected(mapOf("error_code" to code)))
        assertFalse(isRefreshTokenRejected(emptyMap<String, Any>()))
    }
}
