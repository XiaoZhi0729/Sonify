package yos.music.player.ui.theme

import android.content.Context
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun DeviceRoundedCornerShape(
    context: Context,
    topStart: Dp? = null,
    topEnd: Dp? = null,
    bottomStart: Dp? = null,
    bottomEnd: Dp? = null
): Shape {
    val corner = getCornerRadiusTop(context).dp
    return RoundedCornerShape(
        topStart = topStart ?: corner,
        topEnd = topEnd ?: corner,
        bottomStart = bottomStart ?: corner,
        bottomEnd = bottomEnd ?: corner
    )
}

fun getCornerRadiusTop(context: Context): Float {
    val resourceId =
        context.resources.getIdentifier("rounded_corner_radius_top", "dimen", "android")
    return if (resourceId > 0) {
        context.resources.getDimension(resourceId)
    } else {
        0f
    }
}


