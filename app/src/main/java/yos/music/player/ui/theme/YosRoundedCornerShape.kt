package yos.music.player.ui.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.runtime.Stable
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.toRect
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlin.math.sqrt

// Yos-X tips: By Kyant

fun YosRoundedCornerShape(
    roundSize: Dp,
    n: Float = 0.5f,
    mSize: Size? = null
) = YosRoundedCornerShape(CornerSize(roundSize), n, mSize)

fun YosRoundedCornerShape(
    corner: CornerSize,
    n: Float = 0.5f,
    mSize: Size? = null
) = YosRoundedCornerShape(corner, corner, corner, corner, n, mSize)

fun YosRoundedCornerShape(
    topStart: Dp = 0.dp,
    topEnd: Dp = 0.dp,
    bottomEnd: Dp = 0.dp,
    bottomStart: Dp = 0.dp,
    n: Float = 0.5f,
    mSize: Size? = null
) = YosRoundedCornerShape(
    topStart = CornerSize(topStart),
    topEnd = CornerSize(topEnd),
    bottomEnd = CornerSize(bottomEnd),
    bottomStart = CornerSize(bottomStart),
    n = n,
    mSize = mSize
)

@Stable
class YosRoundedCornerShape(
    topStart: CornerSize,
    topEnd: CornerSize,
    bottomEnd: CornerSize,
    bottomStart: CornerSize,
    private val n: Float = 0.5f,
    private val mSize: Size? = null
) : CornerBasedShape(
    topStart = topStart,
    topEnd = topEnd,
    bottomStart = bottomStart,
    bottomEnd = bottomEnd
) {
    override fun createOutline(
        size: Size,
        topStart: Float,
        topEnd: Float,
        bottomEnd: Float,
        bottomStart: Float,
        layoutDirection: LayoutDirection
    ) = if (topStart + topEnd + bottomEnd + bottomStart == 0f) Outline.Rectangle(size.toRect())
    else Outline.Generic(
        Path().apply {
            val width = mSize?.width ?: size.width
            val height = mSize?.height ?: size.height
            val (aa1, bb1, cc1, dd1, ee1, ff1, gg1) = calcCoordinates(topStart)
            val (aa2, bb2, cc2, dd2, ee2, ff2, gg2) = calcCoordinates(bottomStart)
            val (aa3, bb3, cc3, dd3, ee3, ff3, gg3) = calcCoordinates(bottomEnd)
            val (aa4, bb4, cc4, dd4, ee4, ff4, gg4) = calcCoordinates(topEnd)

            moveTo(aa1, 0f)
            if (topStart != 0f) {
                cubicTo(bb1, 0f, cc1, 0f, dd1, ee1)
                if (n != 1f) {
                    cubicTo(gg1, ff1, ff1, gg1, ee1, dd1)
                }
                cubicTo(0f, cc1, 0f, bb1, 0f, aa1)
            }
            lineTo(0f, height - aa2)
            if (bottomStart != 0f) {
                cubicTo(0f, height - bb2, 0f, height - cc2, ee2, height - dd2)
                if (n != 1f) {
                    cubicTo(ff2, height - gg2, gg2, height - ff2, dd2, height - ee2)
                }
                cubicTo(cc2, height, bb2, height, aa2, height)
            }
            lineTo(width - aa3, height)
            if (bottomEnd != 0f) {
                cubicTo(width - bb3, height, width - cc3, height, width - dd3, height - ee3)
                if (n != 1f) {
                    cubicTo(
                        width - gg3,
                        height - ff3,
                        width - ff3,
                        height - gg3,
                        width - ee3,
                        height - dd3
                    )
                }
                cubicTo(width, height - cc3, width, height - bb3, width, height - aa3)
            }
            lineTo(width, aa4)
            if (topEnd != 0f) {
                cubicTo(width, bb4, width, cc4, width - ee4, dd4)
                if (n != 1f) {
                    cubicTo(width - ff4, gg4, width - gg4, ff4, width - dd4, ee4) // circle part
                }
                cubicTo(width - cc4, 0f, width - bb4, 0f, width - aa4, 0f)
            }
            lineTo(aa1, 0f)
            close()
        }
    )

    private fun calcCoordinates(rad: Float): FloatArray {
        val x = n * rad * 2f / 3f
        val sq = sqrt(1f + n * n)
        val p1 = sq * x
        val p2 = n * x / sq
        val p3 = p2 * n
        val p4 = p2 + rad / sq
        val p5 = p4 * (1f - n)
        val p7 = p5 + p1 * 4f
        val p8 = p5 + p1 * 2f
        val p9 = p5 + p1
        val p10 = p5 + p3
        val dx = rad / sq * (1f - n)
        val d = sqrt(rad * rad - dx * dx / 2f)
        val handle = (rad - d) / dx * sqrt(2f) * 4f / 3f
        val p11 = rad / sq * n
        val p12 = rad / sq
        val p13 = p2 + p11 * handle
        val p14 = p10 - p12 * handle
        return floatArrayOf(p7, p8, p9, p10, p2, p13, p14)
    }

    override fun copy(
        topStart: CornerSize,
        topEnd: CornerSize,
        bottomEnd: CornerSize,
        bottomStart: CornerSize
    ) = YosRoundedCornerShape(
        topStart = topStart,
        topEnd = topEnd,
        bottomStart = bottomStart,
        bottomEnd = bottomEnd
    )
}

operator fun FloatArray.component6(): Float = this[5]
operator fun FloatArray.component7(): Float = this[6]
