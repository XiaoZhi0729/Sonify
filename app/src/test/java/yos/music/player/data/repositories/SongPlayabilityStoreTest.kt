package yos.music.player.data.repositories

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SongPlayabilityStoreTest {
    @Test
    fun failedFreshResultDoesNotBecomePlayable() = runTest {
        val result = resolveSongPlayabilityProbe { Result.failure<Unit>(IOException("offline")) }
        assertEquals(SongPlayabilityStore.ProbeResult.Inconclusive(null), result)
        assertEquals(SongPlayabilityStore.ProbeResult.Playable,
            resolveSongPlayabilityProbe { Result.success(Unit) })
    }

    @Test
    fun cancellationInsideResultAndThrownCancellationArePreserved() = runTest {
        val cancellation = CancellationException("row left composition")
        for (wrapped in listOf(true, false)) {
            try {
                resolveSongPlayabilityProbe<Unit> {
                    if (wrapped) Result.failure(cancellation) else throw cancellation
                }
                throw AssertionError("Cancellation must escape the probe")
            } catch (actual: CancellationException) {
                assertSame(cancellation, actual)
            }
        }
    }

    @Test
    fun wrappedCancellationIsNotConvertedIntoAnUnknownSong() = runTest {
        val cancellation = CancellationException("account changed")
        try {
            resolveSongPlayabilityProbe { Result.failure<Unit>(IOException("transport", cancellation)) }
            throw AssertionError("Wrapped cancellation must escape the probe")
        } catch (actual: CancellationException) {
            assertSame(cancellation, actual)
        }
    }

    @Test
    fun typedAccountErrorsStayInconclusiveWithTheirOwnReasonsEvenWhenWrapped() = runTest {
        val cases = listOf(
            KugouVerificationRequiredException("20028") to "请求需要安全验证，请稍后重试",
            KugouSignatureRejectedException("31833") to "请求签名被拒绝，请稍后重试",
            KugouTokenRejectedException("20017") to "登录凭据暂未被接受，请稍后重试",
            KugouAuthInvalidException("confirmed") to SongPlayabilityStore.LOGIN_EXPIRED_REASON
        )
        for ((error, reason) in cases) {
            val result = resolveSongPlayabilityProbe {
                Result.failure<Unit>(IOException("transport", error))
            }
            assertEquals(SongPlayabilityStore.ProbeResult.Inconclusive(reason), result)
        }
    }

    @Test
    fun onlyConfirmedSongBlockCanGreyTheSong() = runTest {
        val result = resolveSongPlayabilityProbe {
            Result.failure<Unit>(KugouRepository.KugouPlayBlockedException("版权限制", "test"))
        }
        assertTrue(result is SongPlayabilityStore.ProbeResult.Blocked)
        assertEquals("版权限制", (result as SongPlayabilityStore.ProbeResult.Blocked).reason)
    }
}
