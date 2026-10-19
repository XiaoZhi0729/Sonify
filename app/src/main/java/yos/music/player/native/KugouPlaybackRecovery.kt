package yos.music.player.native

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
import yos.music.player.data.repositories.KugouAuthInvalidException
import yos.music.player.data.repositories.KugouSignatureRejectedException
import yos.music.player.data.repositories.KugouTokenRejectedException
import yos.music.player.data.repositories.KugouVerificationRequiredException
import yos.music.player.data.repositories.KugouUpstreamError
import yos.music.player.data.repositories.KugouUpstreamErrorType

/** One immutable credential generation. Never render credentials in diagnostics. */
class AuthSnapshot internal constructor(
    val version: Long,
    internal val token: String?,
    internal val userid: String?,
    internal val vipToken: String?
) {
    val isLoggedIn: Boolean get() = !token.isNullOrEmpty() && !userid.isNullOrEmpty()
    val hasVipToken: Boolean get() = !vipToken.isNullOrEmpty()
    internal fun header(): String? = if (!isLoggedIn) null else buildString {
        append("token=").append(token).append(";userid=").append(userid)
        vipToken?.let { append(";vip_token=").append(it) }
    }
    override fun toString(): String = "AuthSnapshot(version=$version, loggedIn=$isLoggedIn, hasVip=$hasVipToken)"
}

internal class AuthCredentials(val token: String, val userid: String, val vipToken: String?)
internal sealed interface AuthRefreshResult {
    class Updated(val credentials: AuthCredentials) : AuthRefreshResult
    class Committed(val snapshot: AuthSnapshot) : AuthRefreshResult
    data object Rejected : AuthRefreshResult
    data object VerificationRequired : AuthRefreshResult
    data object SignatureRejected : AuthRefreshResult
    data object Unavailable : AuthRefreshResult
    data object Superseded : AuthRefreshResult
}

/** Synchronous mutations and their persistence callback share one lock and one generation. */
internal class KugouAuthSession(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
    private val onChanged: (AuthSnapshot) -> Unit = {},
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val onRefresh: (AuthRefreshResult, Long) -> Unit = { _, _ -> }
) {
    private val lock = Any()
    @Volatile private var current = AuthSnapshot(0, null, null, null)
    private class Flight(val expected: AuthSnapshot, val result: CompletableDeferred<AuthRefreshResult>, var completedAt: Long? = null)
    private val flights = mutableMapOf<Long, Flight>()

    fun snapshot(): AuthSnapshot = current
    fun snapshotForVersion(version: Long): AuthSnapshot? = synchronized(lock) {
        current.takeIf { it.version == version } ?: flights[version]?.expected
    }
    suspend fun sharedRefresh(version: Long): AuthRefreshResult {
        val flight = synchronized(lock) { flights[version] } ?: return AuthRefreshResult.Superseded
        val result = flight.result.await()
        return if (result is AuthRefreshResult.Committed && current.version == result.snapshot.version) result
        else AuthRefreshResult.Superseded
    }
    fun login(token: String, userid: String, vipToken: String?): AuthSnapshot = synchronized(lock) {
        commit(token, userid, vipToken)
    }
    fun loginIfCurrent(expected: AuthSnapshot, token: String, userid: String, vipToken: String?): AuthSnapshot? = synchronized(lock) {
        if (current.version != expected.version) null else commit(token, userid, vipToken)
    }
    fun clear(): AuthSnapshot = synchronized(lock) { commit(null, null, null) }
    fun restore(expected: AuthSnapshot, token: String?, userid: String?, vipToken: String?): Boolean = synchronized(lock) {
        if (current.version != expected.version) false
        else { commit(token, userid, vipToken); true }
    }
    private fun commit(token: String?, userid: String?, vipToken: String?): AuthSnapshot {
        val loggedIn = !token.isNullOrBlank() && !userid.isNullOrBlank()
        val next = AuthSnapshot(current.version + 1, token?.takeIf { loggedIn }, userid?.takeIf { loggedIn },
            vipToken?.takeIf { loggedIn && it.isNotBlank() })
        current = next
        onChanged(next)
        return next
    }

    suspend fun refresh(expected: AuthSnapshot, request: suspend () -> AuthRefreshResult): AuthRefreshResult {
        var owner = false
        val flight = synchronized(lock) {
            val now = nowMillis()
            flights.entries.removeAll { (_, value) -> value.completedAt?.let { now - it >= 1500 } == true }
            flights[expected.version] ?: run {
                if (current.version != expected.version) return AuthRefreshResult.Superseded
                Flight(expected, CompletableDeferred()).also { flights[expected.version] = it; owner = true }
            }
        }
        if (owner) scope.launch {
            val result = try {
                val response = withTimeout(15_000) { request() }
                synchronized(lock) {
                    when {
                        current.version != expected.version -> AuthRefreshResult.Superseded
                        response is AuthRefreshResult.Updated -> {
                            val c = response.credentials
                            if (c.userid != expected.userid || c.token.isBlank() || c.userid.isBlank()) AuthRefreshResult.Unavailable
                            else AuthRefreshResult.Committed(commit(c.token, c.userid, c.vipToken ?: expected.vipToken))
                        }
                        else -> response
                    }
                }
            } catch (_: Exception) {
                if (current.version == expected.version) AuthRefreshResult.Unavailable else AuthRefreshResult.Superseded
            }
            synchronized(lock) { flight.completedAt = nowMillis() }
            runCatching { onRefresh(result, current.version) }
            flight.result.complete(result)
        }
        val result = flight.result.await()
        val resultVersion = (result as? AuthRefreshResult.Committed)?.snapshot?.version ?: expected.version
        return if (current.version == resultVersion) result else AuthRefreshResult.Superseded
    }

    private class Registration(val result: CompletableDeferred<Boolean>, var completedAt: Long? = null)
    private val registrations = mutableMapOf<Long, Registration>()
    suspend fun register(expected: AuthSnapshot, request: suspend () -> Boolean): Boolean {
        var owner = false
        val flight = synchronized(lock) {
            val now = nowMillis()
            registrations.entries.removeAll { (_, value) -> value.completedAt?.let { now - it >= 1500 } == true }
            if (current.version != expected.version) throw CancellationException("Authentication generation changed")
            registrations[expected.version] ?: Registration(CompletableDeferred()).also {
                registrations[expected.version] = it
                owner = true
            }
        }
        if (owner) scope.launch {
            val result = try { withTimeout(15_000) { request() } } catch (_: Exception) { false }
            synchronized(lock) { flight.completedAt = nowMillis() }
            flight.result.complete(result)
        }
        val result = flight.result.await()
        if (current.version != expected.version) throw CancellationException("Authentication generation changed")
        return result
    }

}

/** Share this object across all protocol/quality fallbacks for one logical resolution. */
class PlaybackRecoveryBudget(val initialAuthVersion: Long = KugouApiService.authVersion()) {
    @Volatile var currentAuthVersion: Long = initialAuthVersion
        private set
    private var consumed = false
    internal var recoveryType: String? = null
        private set
    private var diagnosticEmitted = false
    @Synchronized internal fun recordRecovery(type: String) { if (recoveryType == null) recoveryType = type }
    @Synchronized internal fun claimDiagnostic(): Boolean = if (diagnosticEmitted || recoveryType == null) false else {
        diagnosticEmitted = true
        true
    }
    @Volatile internal var replayed: Boolean = false
        private set
    internal fun markReplay() { replayed = true }
    @Synchronized internal fun consume(): Boolean = if (consumed) false else { consumed = true; true }
    internal fun refreshed(version: Long) { currentAuthVersion = version }
}

internal data class PlaybackResponse(val httpCode: Int, val root: Map<*, *>?)

/** Token codes here are scoped strictly to /login/token, not other API endpoints. */
internal fun isRefreshTokenRejected(root: Map<*, *>): Boolean {
    fun rejected(map: Map<*, *>): Boolean {
        val code = listOf("error_code", "errcode", "code").any { map[it]?.toString()?.trim() == "20018" }
        val message = listOf("message", "errmsg", "error", "error_msg").any {
            (map[it] as? String)?.contains("token api error", ignoreCase = true) == true
        }
        return code || message
    }
    return rejected(root) || (root["data"] as? Map<*, *>)?.let(::rejected) == true
}

internal fun parseAuthRefreshResponse(httpCode: Int, root: Map<*, *>?): AuthRefreshResult {
    if (root == null) return AuthRefreshResult.Unavailable
    when (KugouUpstreamError.classify(root)) {
        KugouUpstreamErrorType.VerificationRequired -> return AuthRefreshResult.VerificationRequired
        KugouUpstreamErrorType.SignatureRejected -> return AuthRefreshResult.SignatureRejected
        else -> Unit
    }
    if (isRefreshTokenRejected(root)) return AuthRefreshResult.Rejected
    if (httpCode !in 200..299 || root["status"]?.toString() != "1") return AuthRefreshResult.Unavailable
    val data = root["data"] as? Map<*, *> ?: root
    fun value(key: String): String? = data[key]?.toString()?.takeIf { it.isNotBlank() }
        ?: root[key]?.toString()?.takeIf { it.isNotBlank() }
    val token = value("token") ?: return AuthRefreshResult.Unavailable
    val userid = value("userid") ?: return AuthRefreshResult.Unavailable
    return AuthRefreshResult.Updated(AuthCredentials(token, userid, value("vip_token")))
}

internal fun sanitizedTransportFailure(error: IOException): IOException = when (error) {
    is java.net.SocketTimeoutException -> java.net.SocketTimeoutException("HTTP transport timeout")
    is java.io.InterruptedIOException -> java.io.InterruptedIOException("HTTP transport interrupted")
    else -> IOException("HTTP transport unavailable")
}

/** Cancellation stays attached while headers and the entire response body are read. */
internal suspend fun Call.awaitResponseBody(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(sanitizedTransportFailure(e))
        }
        override fun onResponse(call: Call, response: Response) {
            try {
                val result = response.use { it.code to (it.body?.string() ?: "") }
                if (continuation.isActive) continuation.resume(result)
            } catch (error: IOException) {
                if (continuation.isActive) continuation.resumeWithException(sanitizedTransportFailure(error))
            }
        }
    })
}


/** Protocol-independent recovery; transport, storage and Android state are supplied by callers. */
internal suspend fun recoverKugouPlayback(
    budget: PlaybackRecoveryBudget,
    authVersion: () -> Long,
    request: suspend () -> PlaybackResponse,
    refresh: suspend () -> AuthRefreshResult,
    register: suspend () -> Boolean,
    sharedRefresh: suspend () -> AuthRefreshResult = { AuthRefreshResult.Superseded }
): PlaybackResponse {
    var refreshed = budget.currentAuthVersion != budget.initialAuthVersion
    suspend fun syncGeneration(): Boolean {
        if (budget.currentAuthVersion == authVersion()) return false
        val shared = sharedRefresh()
        if (shared !is AuthRefreshResult.Committed || shared.snapshot.version != authVersion() || !budget.consume()) {
            throw CancellationException("Playback authentication generation changed")
        }
        budget.recordRecovery("SharedTokenRefresh")
        budget.refreshed(shared.snapshot.version)
        refreshed = true
        return true
    }
    if (syncGeneration()) budget.markReplay()
    var response = request()
    while (true) {
        if (syncGeneration()) {
            budget.markReplay()
            response = request()
            continue
        }
        val type = response.root?.let { KugouUpstreamError.classify(it, songUrlContext = true) }
        type?.let { budget.recordRecovery(it.name) }
        when (type) {
            KugouUpstreamErrorType.SignatureRejected -> throw KugouSignatureRejectedException("Playback signature rejected")
            KugouUpstreamErrorType.VerificationRequired -> {
                if (!budget.consume() || !register()) throw KugouVerificationRequiredException("Playback verification required")
            }
            KugouUpstreamErrorType.TokenRejected -> {
                if (refreshed) throw KugouAuthInvalidException("Playback token rejected after refresh")
                if (!budget.consume()) throw KugouTokenRejectedException("Playback token rejected; recovery budget exhausted")
                when (val result = refresh()) {
                    is AuthRefreshResult.Committed -> {
                        budget.refreshed(result.snapshot.version)
                        refreshed = true
                    }
                    AuthRefreshResult.Rejected -> throw KugouAuthInvalidException("Refresh token explicitly rejected")
                    AuthRefreshResult.VerificationRequired -> throw KugouVerificationRequiredException("Refresh verification required")
                    AuthRefreshResult.SignatureRejected -> throw KugouSignatureRejectedException("Refresh signature rejected")
                    AuthRefreshResult.Superseded -> throw CancellationException("Playback authentication generation changed")
                    else -> throw KugouTokenRejectedException("Playback token rejected; refresh unavailable")
                }
            }
            null -> {
                if (response.httpCode !in 200..299) throw IOException("Playback HTTP ${response.httpCode}")
                if (response.root == null) throw IOException("Playback response body is empty or invalid")
                return response
            }
        }
        syncGeneration()
        budget.markReplay()
        response = request()
    }
}
