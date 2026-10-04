package yos.music.player.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.BufferedSource
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class UpdateRepositoryTest {
    private fun json() = UpdateManifest(1, "com.sonify.music", 2, "0.2", "2026-10-04T00:00:00Z", 23,
        listOf("arm64-v8a"), "https://github.com/XiaoZhi0729/Sonify/releases/download/v2/app.apk",
        3, "a".repeat(64), "https://github.com/XiaoZhi0729/Sonify/releases/tag/v2", mapOf("en" to listOf("Update"))).toJson()

    private fun client(handler: (Interceptor.Chain) -> Response): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { handler(it) }.build()

    private fun response(chain: Interceptor.Chain, code: Int = 200, body: ResponseBody = json().toResponseBody("application/json".toMediaType())) =
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(code).message("test").body(body)

    @Test fun returnsOnlyStrictlyNewerVersion() = runBlocking {
        val repository = UpdateRepository(client { response(it).build() })
        assertEquals(2L, repository.check(1)?.versionCode)
        assertNull(repository.check(2))
        assertNull(repository.check(3))
    }

    @Test fun failureIsNotReportedAsNoUpdate() = runBlocking {
        try {
            UpdateRepository(client { response(it, 503).build() }).check(1)
            fail("Expected HTTP failure")
        } catch (_: IOException) { }
    }

    @Test fun unsafeRedirectIsRejectedBeforeSecondRequest() = runBlocking {
        val calls = AtomicInteger()
        try {
            UpdateRepository(client {
                calls.incrementAndGet()
                response(it, 302).header("Location", "http://localhost/latest.json").build()
            }).check(1)
            fail("Expected redirect validation")
        } catch (_: IllegalArgumentException) { }
        assertEquals(1, calls.get())
    }

    @Test fun oversizedUnknownLengthBodyIsRejected() = runBlocking {
        val oversized = " ".repeat(UpdateManifest.MAX_MANIFEST_BYTES.toInt() + 1).toResponseBody()
        try {
            UpdateRepository(client { response(it, body = oversized).build() }).check(1)
            fail("Expected body size validation")
        } catch (_: IllegalArgumentException) { }
    }

    @Test fun cancellationClosesBlockingBodyAndCancelsCall() = runBlocking {
        val started = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val source = object : Source {
            override fun read(sink: okio.Buffer, byteCount: Long): Long {
                started.countDown()
                if (!closed.await(5, TimeUnit.SECONDS)) throw IOException("Test body did not close")
                throw IOException("Closed")
            }
            override fun timeout(): Timeout = Timeout.NONE
            override fun close() { closed.countDown() }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "application/json".toMediaType()
            override fun contentLength() = -1L
            override fun source(): BufferedSource = source
        }
        var call: okhttp3.Call? = null
        val repository = UpdateRepository(client {
            call = it.call()
            response(it, body = body).build()
        })
        val operation = async(Dispatchers.Default) { repository.check(1) }
        withTimeout(2000) { while (started.count != 0L) delay(10) }
        operation.cancel()
        try { operation.await(); fail("Expected cancellation") } catch (_: CancellationException) { }
        assertEquals(0L, closed.count)
        assertTrue(call?.isCanceled() == true)
    }
}
