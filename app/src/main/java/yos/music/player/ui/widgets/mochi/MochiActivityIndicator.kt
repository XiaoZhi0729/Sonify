// Ported from mochi-compose (Apache-2.0)

package yos.music.player.ui.widgets.mochi

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import yos.music.player.ui.theme.isFlamingoInDarkMode

/**
 * iOS 转圈加载（UIActivityIndicatorView）：8 根胶囊辐条，一道亮光顺时针平滑扫过。
 * 每根先很快亮起（周期的 10%），再慢慢暗回去（到 50%），之后保持底色，相邻辐条错开 1/8 周期。
 *
 * @param reveal 不为 null 时不转，按返回值（0..1）从顶上顺时针一根根显出辐条（下拉还没触发时）
 */
@Composable
fun MochiActivityIndicator(
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    color: Color = MochiProgressDefaults.activityColor(),
    reveal: (() -> Float)? = null,
) {
    val transition = rememberInfiniteTransition(label = "activity")
    val phase by transition.animateFloat(
        0f,
        1f,
        infiniteRepeatable(tween(CycleMillis, easing = LinearEasing), RepeatMode.Restart),
        label = "phase",
    )
    val tick = remember { G2PathCache() }
    Canvas(
        modifier
            .size(size)
            .semantics { progressBarRangeInfo = ProgressBarRangeInfo.Indeterminate },
    ) {
        val r = this.size.minDimension / 2f
        val length = r * 0.6f
        val width = r * 0.25f
        val rect = Rect(center.x + r - length, center.y - width / 2f, center.x + r, center.y + width / 2f)
        val shown = reveal?.invoke()?.let { (it.coerceIn(0f, 1f) * Spokes).toInt() }
        for (k in 0 until Spokes) {
            if (shown != null && k >= shown) break
            val a = if (shown != null) RevealAlpha else spokeAlpha((phase - k.toFloat() / Spokes + 1f) % 1f)
            rotate(-90f + k * 360f / Spokes, center) {
                drawG2Capsule(tick, rect, color, alpha = a)
            }
        }
    }
}

/** 一根辐条在周期内 t（0..1）时的亮度：0→10% 亮起，10%→50% 暗回，之后保持 [RestAlpha] */
internal fun spokeAlpha(t: Float): Float = when {
    t < 0.1f -> RestAlpha + (1f - RestAlpha) * (t / 0.1f)
    t < 0.5f -> 1f - (1f - RestAlpha) * ((t - 0.1f) / 0.4f)
    else -> RestAlpha
}

internal const val Spokes = 8
private const val CycleMillis = 1000
internal const val RestAlpha = 0.3f
private const val RevealAlpha = 0.55f

object MochiProgressDefaults {
    /** 浅色主题黑 60%，深色主题白 80% */
    @Composable
    fun activityColor(): Color =
        if (isFlamingoInDarkMode()) Color.White.copy(alpha = 0.8f) else Color.Black.copy(alpha = 0.6f)
}
