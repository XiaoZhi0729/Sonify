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
