package yos.music.player.code.utils.others

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundEffect

@Stable
object BitmapResolver {
    fun bitmapCompress(bitmap: Bitmap, lowQuality: Boolean = false): Bitmap {
        val px = if (lowQuality) 4 else (if (NowplayingBackgroundEffect) 96 else 32)
        val originalWidth = bitmap.width
        val originalHeight = bitmap.height
        var compressedBitmap = bitmap

        val size = minOf(originalWidth, originalHeight)
        val xOffset = (originalWidth - size) / 2
        val yOffset = (originalHeight - size) / 2
        val squareBitmap = Bitmap.createBitmap(bitmap, xOffset, yOffset, size, size)

        if (size > px) {
            val scaleFactor = size / px
            val scaledSize = size / scaleFactor
            compressedBitmap = Bitmap.createScaledBitmap(squareBitmap, scaledSize, scaledSize, true)
        }

        val config = Bitmap.Config.RGB_565
        return compressedBitmap.copy(config, false)
    }
}