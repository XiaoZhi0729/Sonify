package yos.music.player.update

import kotlinx.coroutines.CancellationException
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
    manifestUrls: List<String> = DEFAULT_MANIFEST_URLS,
    previewManifestUrls: List<String> = DEFAULT_PREVIEW_MANIFEST_URLS
) {
    constructor(
        client: OkHttpClient = UpdateTransport.defaultClient(),
        manifestUrl: String
    ) : this(client, listOf(manifestUrl), emptyList())

    private val stableUrls = manifestUrls.also { require(it.isNotEmpty()) { "At least one manifest URL is required" } }
        .map { it.toHttpUrl().also(UpdateUrls::manifest) }
    private val previewUrls = previewManifestUrls.map { it.toHttpUrl().also(UpdateUrls::manifest) }
    private val transport = UpdateTransport(client)

    suspend fun check(currentVersion: Long, includePreview: Boolean = false): UpdateManifest? {
        require(currentVersion >= 0)
        val manifest = withTimeoutOrNull(30_000L) {
            if (!includePreview || previewUrls.isEmpty()) {
                return@withTimeoutOrNull fetchFirstAvailable(stableUrls)
            }
            var stableFailure: Exception? = null
            val stable = try {
                fetchFirstAvailable(stableUrls)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                stableFailure = failure
                null
            }
            var previewFailure: Exception? = null
            val preview = try {
                fetchFirstAvailable(previewUrls)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                previewFailure = failure
                null
            }
            when {
                stable != null && preview != null ->
                    if (preview.versionCode > stable.versionCode) preview else stable
                stable != null -> stable
                preview != null -> preview
                else -> throw (stableFailure ?: previewFailure ?: IOException("Update check failed"))
            }
        } ?: throw IOException("Update check timed out")
        return manifest.takeIf { it.versionCode > currentVersion }
    }

    private suspend fun fetchFirstAvailable(urls: List<HttpUrl>): UpdateManifest {
        var lastFailure: Exception? = null
        for (index in urls.indices) {
            val targetUrl = urls[index]
            val attemptTimeoutMs = if (urls.size > 1 && index < urls.lastIndex) 12_000L else 30_000L
            try {
                val parsed = withTimeoutOrNull(attemptTimeoutMs) {
                    transport.read(targetUrl, manifest = true) { response, checkActive ->
                        val body = response.body ?: throw IOException("Empty update manifest")
                        require(body.contentLength() <= UpdateManifest.MAX_MANIFEST_BYTES) {
                            "Update manifest too large"
                        }
                        val bytes = body.byteStream().use {
                            readBounded(it, UpdateManifest.MAX_MANIFEST_BYTES, checkActive)
                        }
                        val json = Charsets.UTF_8.newDecoder()
                            .onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT)
                            .decode(ByteBuffer.wrap(bytes))
                            .toString()
                        UpdateManifest.parse(json)
                    }
                } ?: throw IOException("Update check timed out")
                return parsed
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failure: Exception) {
                lastFailure = failure
            }
        }
        throw (lastFailure ?: IOException("Update check failed"))
    }

    companion object {
        const val GITEE_MANIFEST_URL = "https://gitee.com/XiaoZhi114514/Sonify/raw/master/updates/latest.json"
        const val GITHUB_MANIFEST_URL = "https://raw.githubusercontent.com/XiaoZhi0729/Sonify/master/updates/latest.json"
        val DEFAULT_MANIFEST_URLS = listOf(GITEE_MANIFEST_URL, GITHUB_MANIFEST_URL)
        const val DEFAULT_MANIFEST_URL = GITEE_MANIFEST_URL

        const val GITEE_PREVIEW_MANIFEST_URL = "https://gitee.com/XiaoZhi114514/Sonify/raw/master/updates/preview.json"
        const val GITHUB_PREVIEW_MANIFEST_URL = "https://raw.githubusercontent.com/XiaoZhi0729/Sonify/master/updates/preview.json"
        val DEFAULT_PREVIEW_MANIFEST_URLS = listOf(GITEE_PREVIEW_MANIFEST_URL, GITHUB_PREVIEW_MANIFEST_URL)
    }
}

private enum class UpdateRequestKind { MANIFEST, APK }

internal class UpdateTransport(client: OkHttpClient) {
    private val client = client.newBuilder().followRedirects(false).followSslRedirects(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE)
        .proxyAuthenticator(Authenticator.NONE)
        .addNetworkInterceptor { chain ->
            val request = chain.request()
            val isManifest = request.tag(UpdateRequestKind::class.java) == UpdateRequestKind.MANIFEST
            UpdateUrls.redirect(request.url, isManifest)
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
                        .tag(
                            UpdateRequestKind::class.java,
                            if (manifest) UpdateRequestKind.MANIFEST else UpdateRequestKind.APK
                        )
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
