package yos.music.player.update

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.SocketFactory

class UpdateTransportIntegrationTest {
    private val apkUrl = "https://github.com/XiaoZhi0729/Sonify/releases/download/v2/Sonify.apk"
    private val cdnUrl = "https://release-assets.githubusercontent.com/github-production-release-asset/example?token=test"

    private fun manifestJson() = UpdateManifest(
        1, "com.sonify.music", 2L, "0.2.0", "2026-10-04T12:00:00Z", 23,
        listOf("arm64-v8a"), apkUrl, 3L, "a".repeat(64),
        "https://github.com/XiaoZhi0729/Sonify/releases/tag/v2", mapOf("en" to listOf("Update"))
    ).toJson()

    @Test(timeout = 15_000L)
    fun realHttpsManifestKeepsFixedOriginAndValidatesCertificate() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setBody(manifestJson()))
            assertEquals(2L, UpdateRepository(fixture.client).check(1)?.versionCode)
            val request = fixture.request()
            assertEquals("raw.githubusercontent.com", request.getHeader("Host"))
            assertEquals("/XiaoZhi0729/Sonify/master/updates/latest.json", request.path)
            assertEquals("identity", request.getHeader("Accept-Encoding"))
            assertNotNull(request.handshake)
        }
    }

    @Test(timeout = 45_000L)
    fun checkUsesThirtySecondTotalBudgetRatherThanLongClientTimeout() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val started = System.nanoTime()
            val failure = failure { UpdateRepository(fixture.client).check(1) }
            val elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)
            assertTrue("Expected an ordinary check failure, got $failure", failure is IOException)
            assertFalse(failure is CancellationException)
            assertTrue("Budget ended too early: $elapsedMs ms", elapsedMs >= 28_000L)
            assertTrue("Budget did not interrupt the socket: $elapsedMs ms", elapsedMs < 40_000L)
            assertTrue(fixture.failed.await(3, TimeUnit.SECONDS))
            assertTrue(fixture.calls.any { it.isCanceled() })
        }
    }

    @Test(timeout = 15_000L)
    fun externalCancellationCancelsRealCallAndPreservesCancellationException() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val operation = async(Dispatchers.Default) { UpdateRepository(fixture.client).check(1) }
            try {
                fixture.request()
                operation.cancel(CancellationException("External update cancellation"))
                val failure = withTimeout(5_000L) { failure { operation.await() } }
                assertTrue("External cancellation was converted into a failure", failure is CancellationException)
                assertTrue(fixture.failed.await(3, TimeUnit.SECONDS))
                assertTrue(fixture.calls.any { it.isCanceled() })
            } finally {
                operation.cancel()
            }
        }
    }

    @Test(timeout = 15_000L)
    fun http503And404AreFailuresNotNoUpdate() = runBlocking {
        TlsServer().use { fixture ->
            for (status in listOf(503, 404)) {
                fixture.server.enqueue(MockResponse().setResponseCode(status).setBody("Unavailable"))
                val failure = failure { UpdateRepository(fixture.client).check(1) }
                assertTrue(failure is IOException)
                assertTrue(failure.message.orEmpty().contains(status.toString()))
            }
            assertEquals(2, fixture.server.requestCount)
        }
    }

    @Test(timeout = 15_000L)
    fun untrustedRedirectIsRejectedBeforeAnotherNetworkRequest() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(302)
                .setHeader("Location", "https://evil.example/updates/latest.json"))
            val failure = failure { UpdateRepository(fixture.client).check(1) }
            assertTrue(failure is IllegalArgumentException)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(1, fixture.calls.size)
        }
    }

    @Test(timeout = 15_000L)
    fun trustedCdnIsOnlyAcceptedForApkNotManifest() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl))
            val failure = failure { UpdateRepository(fixture.client).check(1) }
            assertTrue(failure is IllegalArgumentException)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test(timeout = 15_000L)
    fun githubApkRedirectToTrustedCdnUsesRealHttpsAndStripsCredentials() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", cdnUrl))
            fixture.server.enqueue(MockResponse().setBody("APK"))
            val injectedClient = fixture.client.newBuilder().addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder()
                    .header("Authorization", "Bearer must-not-leak")
                    .header("Cookie", "must-not-leak=yes").build())
            }.build()
            val bytes = UpdateTransport(injectedClient).read(apkUrl.toHttpUrl(), manifest = false) { response, active ->
                response.body!!.byteStream().use { readBounded(it, 3, active) }
            }
            assertArrayEquals("APK".toByteArray(), bytes)
            val origin = fixture.request()
            val redirected = fixture.request()
            assertEquals("github.com", origin.getHeader("Host"))
            assertEquals("release-assets.githubusercontent.com", redirected.getHeader("Host"))
            for (request in listOf(origin, redirected)) {
                assertNotNull(request.handshake)
                assertNull(request.getHeader("Authorization"))
                assertNull(request.getHeader("Cookie"))
            }
        }
    }

    @Test(timeout = 15_000L)
    fun apkRedirectToUntrustedHostIsRejectedBeforeSecondRequest() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setResponseCode(307)
                .setHeader("Location", "https://github.com.evil.example/Sonify.apk"))
            val failure = failure {
                UpdateTransport(fixture.client).read(apkUrl.toHttpUrl(), manifest = false) { response, active ->
                    response.body!!.byteStream().use { readBounded(it, 3, active) }
                }
            }
            assertTrue(failure is IllegalArgumentException)
            assertEquals(1, fixture.server.requestCount)
            assertEquals(1, fixture.calls.size)
        }
    }

    @Test(timeout = 15_000L)
    fun truncatedContentLengthCannotProduceSuccessfulManifest() = runBlocking {
        TlsServer().use { fixture ->
            val json = manifestJson()
            fixture.server.enqueue(MockResponse().setBody(json)
                .setHeader("Content-Length", json.toByteArray(Charsets.UTF_8).size + 100)
                .setSocketPolicy(SocketPolicy.DISCONNECT_AT_END))
            val failure = failure { UpdateRepository(fixture.client).check(1) }
            assertTrue("Truncated network response must fail: $failure", failure is IOException)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    @Test(timeout = 15_000L)
    fun chunkedOversizeManifestIsBoundedWithoutContentLength() = runBlocking {
        TlsServer().use { fixture ->
            fixture.server.enqueue(MockResponse().setChunkedBody(
                " ".repeat(UpdateManifest.MAX_MANIFEST_BYTES.toInt() + 1), 8192))
            val failure = failure { UpdateRepository(fixture.client).check(1) }
            assertTrue("Chunked body must enforce the manifest limit: $failure", failure is IllegalArgumentException)
            assertEquals(1, fixture.server.requestCount)
        }
    }

    private suspend fun failure(action: suspend () -> Any?): Exception {
        try { action() } catch (failure: Exception) { return failure }
        throw AssertionError("Expected update operation to fail")
    }

    private class TlsServer : AutoCloseable {
        val server = MockWebServer()
        val calls = CopyOnWriteArrayList<Call>()
        val failed = CountDownLatch(1)
        val client: OkHttpClient

        init {
            val hosts = setOf("raw.githubusercontent.com", "release-assets.githubusercontent.com", "github.com")
            val certificate = HeldCertificate.Builder().commonName("Sonify update integration test")
                .addSubjectAlternativeName("raw.githubusercontent.com")
                .addSubjectAlternativeName("release-assets.githubusercontent.com")
                .addSubjectAlternativeName("github.com").build()
            val serverTls = HandshakeCertificates.Builder().heldCertificate(certificate).build()
            val clientTls = HandshakeCertificates.Builder().addTrustedCertificate(certificate.certificate).build()
            server.useHttps(serverTls.sslSocketFactory(), false)
            server.start(InetAddress.getByName("127.0.0.1"), 0)
            client = OkHttpClient.Builder()
                .protocols(listOf(Protocol.HTTP_1_1))
                .proxy(Proxy.NO_PROXY)
                .dns(object : Dns {
                    override fun lookup(hostname: String): List<InetAddress> {
                        require(hostname in hosts) { "Unexpected integration test DNS host: $hostname" }
                        return listOf(InetAddress.getByName("127.0.0.1"))
                    }
                })
                .socketFactory(LoopbackSocketFactory(server.port))
                .sslSocketFactory(clientTls.sslSocketFactory(), clientTls.trustManager)
                .connectTimeout(5, TimeUnit.SECONDS)
                .readTimeout(90, TimeUnit.SECONDS)
                .callTimeout(120, TimeUnit.SECONDS)
                .eventListener(object : EventListener() {
                    override fun callStart(call: Call) { calls += call }
                    override fun callFailed(call: Call, ioe: IOException) { failed.countDown() }
                }).build()
        }

        fun request() = server.takeRequest(5, TimeUnit.SECONDS)
            ?: throw AssertionError("Expected a real TLS request at MockWebServer")

        override fun close() {
            calls.forEach { it.cancel() }
            client.connectionPool.evictAll()
            client.dispatcher.executorService.shutdownNow()
            server.shutdown()
        }
    }

    /** Route only TCP; the HTTPS URL, SNI and certificate hostname verification remain unchanged. */
    private class LoopbackSocketFactory(private val serverPort: Int) : SocketFactory() {
        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress) = connect(endpoint, 0)
            override fun connect(endpoint: SocketAddress, timeout: Int) {
                require(endpoint is InetSocketAddress && endpoint.port == 443 && endpoint.address.isLoopbackAddress)
                super.connect(InetSocketAddress(InetAddress.getByName("127.0.0.1"), serverPort), timeout)
            }
        }

        override fun createSocket(host: String, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: String, port: Int, localHost: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(localHost, localPort)); connect(InetSocketAddress(host, port)) }

        override fun createSocket(host: InetAddress, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(address: InetAddress, port: Int, localAddress: InetAddress, localPort: Int): Socket =
            createSocket().apply { bind(InetSocketAddress(localAddress, localPort)); connect(InetSocketAddress(address, port)) }
    }
}
