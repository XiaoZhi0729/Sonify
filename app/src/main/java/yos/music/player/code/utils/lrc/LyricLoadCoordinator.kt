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

    /**
     * 进程级歌词内存缓存：访问序 LinkedHashMap + 上限淘汰，避免听一整天后无界增长。
     * 磁盘层（[LyricDiskCache]）负责持久，这里只做当前会话的热命中。
     */
    private val cache = object : LinkedHashMap<String, Payload>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Payload>): Boolean =
            size > MAX_ENTRIES
    }
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
    @Synchronized
    fun complete(request: Request, payload: Payload): Boolean {
        inFlight.remove(request.mediaId, request)
        cache[request.mediaId] = payload
        return currentRequest == request
    }

    @Synchronized
    fun fail(request: Request): Boolean {
        inFlight.remove(request.mediaId, request)
        return currentRequest == request
    }

    fun isCurrent(request: Request): Boolean = currentRequest == request

    /**
     * 预取结果入缓存：只写内存热缓存，**不动** [currentRequest]/[inFlight]。
     * 供「下一首歌词预取」使用——提前准备好数据，但不能改变当前曲的发布语义；
     * 若该曲已有在途请求则不覆盖（那边完成后会自己写入）。
     */
    @Synchronized
    fun prewarm(mediaId: String, payload: Payload) {
        if (inFlight.containsKey(mediaId)) return
        cache[mediaId] = payload
    }

    /** 该曲是否有在途请求（预取器据此避免重复发起）。 */
    @Synchronized
    fun isInFlight(mediaId: String): Boolean = inFlight.containsKey(mediaId)

    @Synchronized
    fun cached(mediaId: String): Payload? = cache[mediaId]

    private companion object {
        /** 内存热缓存上限：LRU 淘汰，磁盘层负责更长久的持久化。 */
        const val MAX_ENTRIES = 300
    }
}
