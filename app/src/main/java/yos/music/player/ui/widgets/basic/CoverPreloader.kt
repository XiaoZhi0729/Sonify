package yos.music.player.ui.widgets.basic

import android.content.Context
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision

/** Loads the exact RAW request used by ShadowImage before opening the detail page. */
suspend fun preloadRawCover(context: Context, url: String) {
    if (url.isBlank()) return
    val request = ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(coverCacheKey(url, ImageQuality.RAW))
        .diskCacheKey(url)
        .precision(Precision.EXACT)
        .size(coil.size.Size.ORIGINAL)
        .allowHardware(true)
        .build()
    context.imageLoader.execute(request)
}

/**
 * 预取卡片封面（主页横排卡片用）。
 *
 * 请求参数必须与 [ShadowImage] 的 HIGH 档**逐项一致**——Coil 的内存缓存按
 * "缓存键 + 位图尺寸精确匹配" 校验，差一个 precision 或 size 就变成
 * "预取了但卡片仍然重新解码"，白白多跑一次磁盘/网络。
 *
 * 用 `enqueue` 异步下发、不挂起等待：调用方是在组合里一次性预热一整排，
 * 阻塞任何一张都会拖慢首帧。
 */
fun prefetchCover(context: Context, url: String?) {
    if (url.isNullOrBlank()) return
    val request = ImageRequest.Builder(context)
        .data(url)
        .memoryCacheKey(coverCacheKey(url, ImageQuality.HIGH))
        .diskCacheKey(url)
        .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
        .size(400)
        .allowHardware(true)
        .build()
    context.imageLoader.enqueue(request)
}

