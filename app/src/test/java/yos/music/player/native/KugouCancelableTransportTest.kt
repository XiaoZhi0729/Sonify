package yos.music.player.native

import java.io.IOException
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class KugouCancelableTransportTest {
    private class FakeCall : Call {
        var callback: Callback? = null
        var cancelled = false
        override fun request(): Request = Request.Builder().url("http://localhost/test").build()
        override fun execute(): Response = error("blocking execute must not be used")
        override fun enqueue(responseCallback: Callback) { callback = responseCallback }
        override fun cancel() { cancelled = true }
        override fun isExecuted() = callback != null
        override fun isCanceled() = cancelled
        override fun clone(): Call = FakeCall()
        override fun timeout() = Timeout.NONE
    }

    @Test fun cancellingSuspendedTransportCancelsUnderlyingCall() = runTest {
        val call = FakeCall()
        val task = async { call.awaitResponseBody() }
        runCurrent()
        assertNotNull(call.callback)
        task.cancel()
        task.join()
        assertTrue(call.cancelled)
        call.callback!!.onFailure(call, IOException("http://secret/?token=credential"))
    }

    @Test fun non2xxBodyIsPreservedForBusinessClassification() = runTest {
        val call = FakeCall()
        val task = async { call.awaitResponseBody() }
        runCurrent()
        call.callback!!.onResponse(call, Response.Builder().request(call.request())
            .protocol(Protocol.HTTP_1_1).code(502).message("Bad Gateway")
            .body("{\"error_code\":20018}".toResponseBody("application/json".toMediaType())).build())
        assertEquals(502 to "{\"error_code\":20018}", task.await())
    }

    @Test fun sanitizingTimeoutRetainsItsFailureCategory() {
        for (original in listOf(java.net.SocketTimeoutException("https://secret/token"),
            java.io.InterruptedIOException("https://secret/token"))) {
            val sanitized = sanitizedTransportFailure(original)
            assertEquals(original.javaClass, sanitized.javaClass)
            assertFalse(sanitized.toString().contains("https://"))
            assertNull(sanitized.cause)
        }
    }

    @Test fun transportFailureDoesNotExposeUrlOrCredentials() = runTest {
        val call = FakeCall()
        val task = async {
            try { call.awaitResponseBody(); null } catch (e: IOException) { e }
        }
        runCurrent()
        call.callback!!.onFailure(call, IOException("http://secret/?token=credential"))
        val error = task.await()!!
        // Coroutine stack-trace recovery may add another sanitized IOException as cause.
        generateSequence<Throwable>(error) { it.cause }.forEach {
            assertFalse(it.toString().contains("credential"))
            assertFalse(it.toString().contains("http://"))
        }
    }
}
