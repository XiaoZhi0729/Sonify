package yos.music.player.code.utils.lrc

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Coordinates asynchronous lyric requests without allowing stale results to
 * update the currently selected media item.
 */
class LyricLoadCoordinator {
    data class Request(val mediaId: String, val generation: Long)

    data class Payload(
        val entries: List<LyricEntry>,
        val otherSideForLines: List<Boolean>
    )

    sealed interface Selection {
        val request: Request

        data class Load(override val request: Request) : Selection
        data class InFlight(override val request: Request) : Selection
        data class Cached(
            override val request: Request,
            val payload: Payload
        ) : Selection
    }

    private val nextGeneration = AtomicLong(0L)
    private val cache = ConcurrentHashMap<String, Payload>()
    private val inFlight = ConcurrentHashMap<String, Request>()

    @Volatile
    private var currentRequest: Request? = null

    @Synchronized
    fun select(mediaId: String): Selection {
        val cachedPayload = cache[mediaId]
        if (cachedPayload != null) {
            val request = Request(mediaId, nextGeneration.incrementAndGet())
            currentRequest = request
            return Selection.Cached(request, cachedPayload)
        }

        val existing = inFlight[mediaId]
        if (existing != null) {
            currentRequest = existing
            return Selection.InFlight(existing)
        }

        val request = Request(mediaId, nextGeneration.incrementAndGet())
        currentRequest = request
        inFlight[mediaId] = request
        return Selection.Load(request)
    }

    /**
     * Stores successful results even when the request is no longer current,
     * so returning to that song can reuse the completed request. The boolean
     * tells the caller whether it is still safe to publish to the UI.
     */
    fun complete(request: Request, payload: Payload): Boolean {
        inFlight.remove(request.mediaId, request)
        cache[request.mediaId] = payload
        return currentRequest == request
    }

    fun fail(request: Request): Boolean {
        inFlight.remove(request.mediaId, request)
        return currentRequest == request
    }

    fun isCurrent(request: Request): Boolean = currentRequest == request

    fun cached(mediaId: String): Payload? = cache[mediaId]
}
