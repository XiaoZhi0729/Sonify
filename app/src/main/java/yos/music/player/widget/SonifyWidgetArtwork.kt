package yos.music.player.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.util.LruCache
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import coil.size.Precision
import kotlinx.coroutines.runBlocking
import yos.music.player.ui.widgets.basic.hiresCoverUrl

object SonifyWidgetArtwork {

    private val cache = object : LruCache<String, Bitmap>(10) {}

    suspend fun loadRounded(
        context: Context,
        thumbUri: String?,
        sizePx: Int,
        radiusPx: Float,
    ): Bitmap? {
        if (thumbUri.isNullOrBlank()) return null
        val safeInputSize = sizePx.coerceAtLeast(1)
        val clampedSize = safeInputSize.coerceIn(64, 280)
        val bitmapScale = clampedSize.toFloat() / safeInputSize.toFloat()
        val effectiveRadiusPx = radiusPx * bitmapScale
        val cacheKey = "$thumbUri|$clampedSize|${effectiveRadiusPx.toInt()}"
        cache.get(cacheKey)?.let { cached ->
            if (!cached.isRecycled) return cached
        }

        val resolvedData: Any = runCatching {
            val parsed = Uri.parse(thumbUri)
            hiresCoverUrl(parsed) ?: parsed
        }.getOrDefault(thumbUri)

        val request = ImageRequest.Builder(context.applicationContext)
            .data(resolvedData)
            .size(clampedSize, clampedSize)
            .precision(Precision.INEXACT)
            .allowHardware(false)
            .build()

        val result = runCatching { context.applicationContext.imageLoader.execute(request) }.getOrNull()
        val drawable = (result as? SuccessResult)?.drawable ?: return null
        val srcBitmap = drawableToBitmap(drawable, clampedSize) ?: return null
        val rounded = bakeRoundedCover(srcBitmap, clampedSize, effectiveRadiusPx, bitmapScale)
        cache.put(cacheKey, rounded)
        return rounded
    }

    fun loadRoundedBlocking(
        context: Context,
        thumbUri: String?,
        sizePx: Int,
        radiusPx: Float,
    ): Bitmap? = runBlocking {
        loadRounded(context, thumbUri, sizePx, radiusPx)
    }

    private fun drawableToBitmap(
        drawable: android.graphics.drawable.Drawable,
        targetSize: Int,
    ): Bitmap? {
        if (drawable is android.graphics.drawable.BitmapDrawable) {
            val bmp = drawable.bitmap
            if (bmp != null && !bmp.isRecycled) return bmp
        }
        val w = drawable.intrinsicWidth.takeIf { it > 0 } ?: targetSize
        val h = drawable.intrinsicHeight.takeIf { it > 0 } ?: targetSize
        return runCatching {
            Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bmp ->
                val canvas = Canvas(bmp)
                drawable.setBounds(0, 0, w, h)
                drawable.draw(canvas)
            }
        }.getOrNull()
    }

    private fun bakeRoundedCover(
        src: Bitmap,
        targetSize: Int,
        radiusPx: Float,
        bitmapScale: Float = 1f,
    ): Bitmap {
        val out = Bitmap.createBitmap(targetSize, targetSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)

        val scale = maxOf(
            targetSize.toFloat() / src.width.coerceAtLeast(1),
            targetSize.toFloat() / src.height.coerceAtLeast(1),
        )
        val dx = (targetSize - src.width * scale) * 0.5f
        val dy = (targetSize - src.height * scale) * 0.5f
        val matrix = Matrix().apply {
            setScale(scale, scale)
            postTranslate(dx, dy)
        }

        val shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
            setLocalMatrix(matrix)
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            this.shader = shader
        }
        val rect = RectF(0f, 0f, targetSize.toFloat(), targetSize.toFloat())
        canvas.drawRoundRect(rect, radiusPx, radiusPx, paint)

        // Subtle inner rim matching prototype .art-shadow (0.5px rgba(255,255,255,0.18))
        val strokePx = (1.5f * bitmapScale).coerceIn(1f, 1.5f)
        val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = strokePx
            color = 0x2EFFFFFF
        }
        val inset = strokePx * 0.5f
        canvas.drawRoundRect(
            RectF(inset, inset, targetSize - inset, targetSize - inset),
            (radiusPx - inset).coerceAtLeast(0f),
            (radiusPx - inset).coerceAtLeast(0f),
            rimPaint,
        )
        return out
    }
}
