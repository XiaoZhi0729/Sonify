package yos.music.player.data.repositories

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Process-local authentication versions isolate requests; disk keys remain stable across restarts. */
internal data class SongPlayabilityAccountKey(val userid: String?, val vip: Boolean, val authVersion: Long) {
    val storageNamespace: String get() = (userid ?: "anon") + if (vip) ".v" else ""
    val requestNamespace: String get() = "$storageNamespace.a$authVersion"
}

/** Account-scoped per-song coordination; no Android, credentials, or account-budget mutations. */
internal class SongPlayabilityProbeCoordinator<T : Any>(
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    private val backgroundCooldownMs: Long = 15_000,
    private val manualReuseMs: Long = 5_000
) {
    private class Active<T>(val fresh: Boolean, val done: CompletableDeferred<Result<T>>)
    private data class Recent<T>(val value: T, val until: Long, val fresh: Boolean)

    private val lock = Any()
    private val active = mutableMapOf<String, Active<T>>()
    private val recent = LinkedHashMap<String, Recent<T>>()

    /** Manual requests wait for an automatic probe, then run fresh; automatic probes join manual ones. */
    suspend fun run(
        key: String,
        fresh: Boolean,
        inconclusive: (T) -> Boolean,
        block: suspend () -> T
    ): T? {
        while (true) {
            currentCoroutineContext().ensureActive()
            val (request, owner) = synchronized(lock) {
                val running = active[key]
                if (running != null) {
                    running to false
                } else {
                    val previous = recent[key]
                    if (previous != null && clock() < previous.until) {
                        if (previous.fresh) return previous.value
                        if (!fresh) return null
                    }
                    Active(fresh, CompletableDeferred<Result<T>>()).also { active[key] = it } to true
                }
            }
            if (!owner) {
                val result = request.done.await()
                if (fresh && !request.fresh) continue
                return result.getOrThrow()
            }
            try {
                val result = block()
                currentCoroutineContext().ensureActive()
                synchronized(lock) {
                    if (fresh || inconclusive(result)) {
                        val now = clock()
                        recent.entries.removeAll { now >= it.value.until }
                        recent[key] = Recent(
                            result,
                            now + if (fresh) manualReuseMs else backgroundCooldownMs,
                            fresh
                        )
                        while (recent.size > 512) recent.remove(recent.keys.first())
                    }
                    active.remove(key)
                    request.done.complete(Result.success(result))
                }
                return result
            } catch (error: Throwable) {
                synchronized(lock) {
                    active.remove(key)
                    request.done.complete(Result.failure(error))
                }
                throw error
            }
        }
    }
}
