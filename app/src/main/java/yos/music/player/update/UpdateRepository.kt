package yos.music.player.update

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.Authenticator
import okhttp3.Call
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class UpdateRepository(
    client: OkHttpClient = UpdateTransport.defaultClient(),
    manifestUrl: String = DEFAULT_MANIFEST_URL
) {
    private val url = manifestUrl.toHttpUrl().also(UpdateUrls::manifest)
    private val transport = UpdateTransport(client)

    suspend fun check(currentVersion: Long): UpdateManifest? {
        require(currentVersion >= 0)
        val manifest = withTimeoutOrNull(30_000L) {
            transport.read(url, manifest = true) { response, checkActive ->
                val body = response.body ?: throw IOException("Empty update manifest")
                require(body.contentLength() <= UpdateManifest.MAX_MANIFEST_BYTES) { "Update manifest too large" }
                val bytes = body.byteStream().use {
                    readBounded(it, UpdateManifest.MAX_MANIFEST_BYTES, checkActive)
                }
                val json = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString()
                UpdateManifest.parse(json)
            }
        } ?: throw IOException("Update check timed out")
        return manifest.takeIf { it.versionCode > currentVersion }
    }

    companion object {
        const val DEFAULT_MANIFEST_URL = "https://raw.githubusercontent.com/XiaoZhi0729/Sonify/master/updates/latest.json"
    }
}

internal class UpdateTransport(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            UpdateUrls.redirect(request.url, request.url.host == "raw.githubusercontent.com")
            chain.proceed(request.newBuilder().removeHeader("Authorization")
                .removeHeader("Proxy-Authorization").removeHeader("Cookie").build())
        }.build()

    suspend fun <T> read(
        initialUrl: HttpUrl,
        manifest: Boolean,
        consume: (Response, () -> Unit) -> T
    ): T = withContext(kotlinx.coroutines.Dispatchers.IO) {
        coroutineScope {
            val call = AtomicReference<Call?>()
            val response = AtomicReference<Response?>()
            val watcher = launch(kotlinx.coroutines.Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                try { awaitCancellation() } finally {
                    call.get()?.cancel()
                    response.get()?.close()
                }
            }
            try {
                if (manifest) UpdateUrls.manifest(initialUrl) else UpdateUrls.apk(initialUrl)
                var url = initialUrl
                var redirects = 0
                while (true) {
                    coroutineContext.ensureActive()
                    val request = Request.Builder().url(url)
                        .header("Accept", if (manifest) "application/json" else "application/octet-stream")
                        .header("Accept-Encoding", "identity").build()
                    val nextCall = client.newCall(request)
                    call.set(nextCall)
                    coroutineContext.ensureActive()
                    val nextResponse = nextCall.execute()
                    response.set(nextResponse)
                    nextResponse.use { result ->
                        coroutineContext.ensureActive()
                        UpdateUrls.redirect(result.request.url, manifest)
                        if (result.code in setOf(301, 302, 303, 307, 308)) {
                            if (++redirects > 5) throw IOException("Too many update redirects")
                            val location = result.header("Location") ?: throw IOException("Missing redirect location")
                            url = url.resolve(location) ?: throw IOException("Invalid update redirect")
                            UpdateUrls.redirect(url, manifest)
                        } else {
                            if (result.code != 200) throw IOException("Update HTTP ${result.code}")
                            require(result.header("Content-Encoding").let { it == null || it.equals("identity", true) }) {
                                "Encoded update response is not supported"
                            }
                            val value = consume(result) { coroutineContext.ensureActive() }
                            coroutineContext.ensureActive()
                            return@coroutineScope value
                        }
                    }
                    response.set(null)
                }
                @Suppress("UNREACHABLE_CODE")
                error("Unreachable update response")
            } catch (failure: Exception) {
                coroutineContext.ensureActive()
                throw failure
            } finally {
                watcher.cancel()
                call.get()?.cancel()
                response.getAndSet(null)?.close()
            }
        }
    }

    companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(20, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.MINUTES).build()
    }
}

internal fun readBounded(input: InputStream, maximum: Long, checkActive: () -> Unit = {}): ByteArray {
    val output = ByteArrayOutputStream()
    val buffer = ByteArray(8192)
    var total = 0L
    while (true) {
        checkActive()
        val count = input.read(buffer)
        if (count == -1) break
        total += count
        require(total <= maximum) { "Update data exceeds size limit" }
        output.write(buffer, 0, count)
    }
    checkActive()
    return output.toByteArray()
}
