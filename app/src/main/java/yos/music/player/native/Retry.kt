package yos.music.player.native

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay

/** Runs an operation once and retries failed Result values up to [maxRetries] times. */
suspend fun <T> retryResult(
    maxRetries: Int = 3,
    initialDelayMs: Long = 250L,
    operation: suspend () -> Result<T>
): Result<T> {
    require(maxRetries >= 0) { "maxRetries must be non-negative" }
    require(initialDelayMs >= 0) { "initialDelayMs must be non-negative" }

    var lastFailure: Result<T>? = null
    repeat(maxRetries + 1) { attempt ->
        val result = try {
            operation()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            Result.failure(error)
        }

        if (result.isSuccess) return result
        lastFailure = result
        if (attempt < maxRetries) delay(initialDelayMs * (attempt + 1))
    }

    return lastFailure ?: Result.failure(IllegalStateException("Retry operation did not run"))
}
