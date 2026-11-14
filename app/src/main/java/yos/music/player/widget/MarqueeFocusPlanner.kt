package yos.music.player.widget

import android.graphics.Paint
import android.graphics.Typeface

enum class MarqueeFocusTarget {
    NONE,
    TITLE,
    ARTIST,
}

data class MarqueeOverflowState(
    val titleOverflows: Boolean,
    val artistOverflows: Boolean,
)

/**
 * Chooses which single-line native TextView receives marquee focus in a RemoteViews layout.
 * Android allows only one marquee TextView to hold focus per host hierarchy without marquee
 * focus fighting, so Title takes priority when it overflows and Artist takes focus only when
 * Title fits and Artist overflows.
 */
object MarqueeFocusPlanner {

    fun decide(
        title: String,
        artist: String,
        titleSp: Float,
        artistSp: Float,
        availableWidthPx: Int,
        scaledDensity: Float,
    ): MarqueeFocusTarget {
        return decideOverflow(
            title = title,
            artist = artist,
            titleSp = titleSp,
            artistSp = artistSp,
            availableWidthPx = availableWidthPx,
            scaledDensity = scaledDensity,
        ).toFocusTarget()
    }

    fun decideFromWidths(
        titleWidthPx: Float,
        artistWidthPx: Float,
        availableWidthPx: Int,
    ): MarqueeFocusTarget =
        decideOverflowFromWidths(titleWidthPx, artistWidthPx, availableWidthPx).toFocusTarget()

    private fun MarqueeOverflowState.toFocusTarget(): MarqueeFocusTarget = when {
        titleOverflows -> MarqueeFocusTarget.TITLE
        artistOverflows -> MarqueeFocusTarget.ARTIST
        else -> MarqueeFocusTarget.NONE
    }

    fun decideOverflow(
        title: String,
        artist: String,
        titleSp: Float,
        artistSp: Float,
        availableWidthPx: Int,
        scaledDensity: Float,
    ): MarqueeOverflowState {
        if (availableWidthPx <= 0) {
            return MarqueeOverflowState(titleOverflows = false, artistOverflows = false)
        }
        val titleWidth = if (title.isNotEmpty()) {
            measureTextWidth(title, titleSp * scaledDensity, bold = true)
        } else {
            0f
        }
        val artistWidth = if (artist.isNotEmpty()) {
            measureTextWidth(artist, artistSp * scaledDensity, bold = false)
        } else {
            0f
        }
        return decideOverflowFromWidths(
            titleWidthPx = titleWidth,
            artistWidthPx = artistWidth,
            availableWidthPx = availableWidthPx,
        )
    }

    fun decideOverflowFromWidths(
        titleWidthPx: Float,
        artistWidthPx: Float,
        availableWidthPx: Int,
    ): MarqueeOverflowState {
        if (availableWidthPx <= 0) {
            return MarqueeOverflowState(titleOverflows = false, artistOverflows = false)
        }
        val threshold = availableWidthPx + 0.5f
        return MarqueeOverflowState(
            titleOverflows = titleWidthPx > threshold,
            artistOverflows = artistWidthPx > threshold,
        )
    }

    private fun measureTextWidth(text: String, textSizePx: Float, bold: Boolean): Float {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = textSizePx
            typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        }
        return paint.measureText(text)
    }
}
