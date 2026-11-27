package yos.music.player.data.objects

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.tencent.mmkv.MMKV
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import yos.music.player.code.utils.others.BitmapResolver
import yos.music.player.ui.widgets.effects.imageResolve
import java.nio.ByteBuffer

internal data class DecodedMask(
    val width: Int,
    val height: Int,
    val pixels: IntArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DecodedMask) return false
        return width == other.width && height == other.height && pixels.contentEquals(other.pixels)
    }

    override fun hashCode(): Int {
        var result = width
        result = 31 * result + height
        result = 31 * result + pixels.contentHashCode()
        return result
    }
}

private const val MAX_MASK_DIMENSION = 32

internal fun encodeMaskPixels(width: Int, height: Int, pixels: IntArray): ByteArray {
    require(width in 1..MAX_MASK_DIMENSION) { "width out of bounds: $width" }
    require(height in 1..MAX_MASK_DIMENSION) { "height out of bounds: $height" }
    require(pixels.size == width * height) { "pixels size mismatch: ${pixels.size} != ${width * height}" }
    val buffer = ByteBuffer.allocate(8 + pixels.size * 4)
    buffer.putInt(width)
    buffer.putInt(height)
    for (pixel in pixels) {
        buffer.putInt(pixel)
    }
    return buffer.array()
}

internal fun decodeMaskPixels(bytes: ByteArray): DecodedMask? {
    if (bytes.size < 8) return null
    val buffer = ByteBuffer.wrap(bytes)
    val width = buffer.int
    val height = buffer.int
    if (width !in 1..MAX_MASK_DIMENSION || height !in 1..MAX_MASK_DIMENSION) return null
    val expectedSize = 8 + width * height * 4
    if (bytes.size != expectedSize) return null
    val pixels = IntArray(width * height)
    for (i in pixels.indices) {
        pixels[i] = buffer.int
    }
    return DecodedMask(width, height, pixels)
}

/**
 * 最近播放 / 首页推荐大卡底部取色遮罩缓存（内存 LRU + MMKV 字节持久化 + 后台去重预热）。
 *
 * 1. 每张遮罩仅 4×4 像素（~72 字节），MMKV 同步解码耗时微秒级，首帧即可直接拿到 [ImageBitmap]，
 *    消除卡片底部从黑底闪到彩色的视觉跳变。
 * 2. [ensure] 运行在缓存自身作用域（[Dispatchers.IO]），页面切换/退出不会取消正在计算的遮罩，
 *    且移除旧版人为 `delay(200)`。
 */
object RecommendMaskCache {
    private const val MMKV_ID = "recommend_mask_cache_v1"
    private const val MAX_MEMORY_ENTRIES = 128
    private const val MAX_DISK_ENTRIES = 200
    private const val MAX_CONCURRENT_WARMUP = 3

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mmkv: MMKV? by lazy { runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull() }
    private val gate = Semaphore(MAX_CONCURRENT_WARMUP)
    private val inFlightGuard = Mutex()
    private val inFlight = HashMap<String, Deferred<ImageBitmap?>>()

    private val memoryLock = Any()
    private val memoryCache = object : LinkedHashMap<String, ImageBitmap>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?): Boolean {
            return size > MAX_MEMORY_ENTRIES
        }
    }

    fun keyOf(thumb: Any?): String? = thumb?.toString()?.takeIf { it.isNotBlank() }

    fun peek(key: String?): ImageBitmap? {
        if (key.isNullOrBlank()) return null
        return synchronized(memoryLock) { memoryCache[key] }
    }

    fun peekOrDisk(key: String?): ImageBitmap? {
        if (key.isNullOrBlank()) return null
        peek(key)?.let { return it }
        val bytes = runCatching { mmkv?.decodeBytes(key) }.getOrNull() ?: return null
        val decoded = decodeMaskPixels(bytes) ?: run {
            runCatching { mmkv?.removeValueForKey(key) }
            return null
        }
        val imageBitmap = runCatching {
            Bitmap.createBitmap(decoded.pixels, decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                .asImageBitmap()
        }.getOrNull() ?: return null
        synchronized(memoryLock) {
            memoryCache[key] = imageBitmap
        }
        return imageBitmap
    }

    private fun putMemoryAndDisk(key: String, bitmap: Bitmap): ImageBitmap {
        val w = bitmap.width.coerceIn(1, MAX_MASK_DIMENSION)
        val h = bitmap.height.coerceIn(1, MAX_MASK_DIMENSION)
        val argb = if (bitmap.config == Bitmap.Config.ARGB_8888 && bitmap.width == w && bitmap.height == h) {
            bitmap
        } else {
            val scaled = if (bitmap.width != w || bitmap.height != h) {
                Bitmap.createScaledBitmap(bitmap, w, h, true)
            } else {
                bitmap
            }
            val copied = scaled.copy(Bitmap.Config.ARGB_8888, false)
            if (scaled !== bitmap) scaled.recycle()
            copied
        }
        if (argb !== bitmap) {
            bitmap.recycle()
        }

        val pixels = IntArray(w * h)
        argb.getPixels(pixels, 0, w, 0, 0, w, h)
        val imageBitmap = argb.asImageBitmap()

        synchronized(memoryLock) {
            memoryCache[key] = imageBitmap
        }

        runCatching {
            mmkv?.let { store ->
                if (store.count() > MAX_DISK_ENTRIES) {
                    val keys = store.allKeys()
                    if (keys != null && keys.size > MAX_DISK_ENTRIES) {
                        val removeCount = keys.size - MAX_DISK_ENTRIES + 20
                        for (i in 0 until removeCount.coerceAtMost(keys.size)) {
                            if (keys[i] != key) {
                                store.removeValueForKey(keys[i])
                            }
                        }
                    }
                }
                store.encode(key, encodeMaskPixels(w, h, pixels))
            }
        }
        return imageBitmap
    }

    suspend fun ensure(context: Context, thumb: Any?): ImageBitmap? {
        val key = keyOf(thumb) ?: return null
        peekOrDisk(key)?.let { return it }

        val appContext = context.applicationContext ?: context
        val job = inFlightGuard.withLock {
            inFlight[key] ?: scope.async {
                try {
                    peekOrDisk(key)?.let { return@async it }
                    val request = ImageRequest.Builder(appContext)
                        .data(thumb)
                        // 遮罩输入只需要小方图（bitmapCompress 还会再压），不设上限会全尺寸软件解码
                        .size(192)
                        .allowHardware(false)
                        .build()
                    val loaded = runCatching {
                        (appContext.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap()
                    }.getOrNull() ?: return@async null
                    // loaded 可能是 Coil 内存缓存中的共享 Bitmap（toBitmap() 不一定复制），绝对不能 recycle(loaded)
                    val compressed = BitmapResolver.bitmapCompress(loaded, lowQuality = true)
                    val mask = imageResolve(appContext, compressed)
                    if (mask !== compressed) {
                        compressed.recycle()
                    }
                    putMemoryAndDisk(key, mask)
                } catch (_: Throwable) {
                    null
                } finally {
                    withContext(NonCancellable) {
                        inFlightGuard.withLock { inFlight.remove(key) }
                    }
                }
            }.also { inFlight[key] = it }
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        }
    }

    fun warmUp(context: Context, thumbs: Iterable<Any?>) {
        val appContext = context.applicationContext ?: context
        val candidates = thumbs.mapNotNull { thumb ->
            val key = keyOf(thumb) ?: return@mapNotNull null
            if (peek(key) != null) null else thumb
        }.distinctBy { keyOf(it) }
        if (candidates.isEmpty()) return

        scope.launch {
            for (thumb in candidates) {
                launch {
                    gate.withPermit {
                        ensure(appContext, thumb)
                    }
                }
            }
        }
    }

    fun preloadPersisted(thumbs: Iterable<Any?>) {
        val keys = thumbs.mapNotNull { keyOf(it) }.distinct()
        if (keys.isEmpty()) return
        scope.launch {
            for (key in keys) {
                peekOrDisk(key)
            }
        }
    }
}
