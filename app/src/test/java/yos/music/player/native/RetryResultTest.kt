package yos.music.player.native

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryResultTest {
    @Test
    fun retriesThreeTimesBeforeReturningRecoveredResult() = runBlocking {
        var attempts = 0

        val result = retryResult(
            maxRetries = 3,
            initialDelayMs = 0
        ) {
            attempts += 1
            if (attempts < 4) Result.failure(IllegalStateException("temporary"))
            else Result.success("loaded")
        }

        assertEquals(4, attempts)
        assertEquals("loaded", result.getOrNull())
    }

    @Test
    fun returnsLastFailureAfterThreeRetries() = runBlocking {
        var attempts = 0

        val result = retryResult(
            maxRetries = 3,
            initialDelayMs = 0
        ) {
            attempts += 1
            Result.failure<String>(IllegalStateException("failure-$attempts"))
        }

        assertEquals(4, attempts)
        assertTrue(result.isFailure)
        assertEquals("failure-4", result.exceptionOrNull()?.message)
    }
}
