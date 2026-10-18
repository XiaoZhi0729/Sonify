package yos.music.player.ui.theme

import android.os.Build
import android.view.RoundedCorner
import android.view.View
import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import yos.music.player.data.libraries.SettingsLibrary

internal fun usesSystemScreenCorners(sdk: Int): Boolean = sdk >= 31

internal fun maximumScreenCornerRadius(radii: List<Int?>): Int =
    radii.filterNotNull().maxOrNull()?.coerceAtLeast(0) ?: 0

/**
 * 系统圆角是否"报了等于没报"。
 *
 * ColorOS/OPPO 平板（实测 OPD2405，SDK 36）把物理圆角屏在系统层声明成直角屏：
 * `WindowInsets.getRoundedCorner()` 返回 radius=0 的 RoundedCorner，framework-res 的
 * `rounded_corner_radius_top` 同样是 0dp。这里的 0 不是真实值而是"未知"，必须回落到
 * 手动校准值，否则整页直角且用户无从修正。
 */
internal fun systemCornerRadiusUnknown(sdk: Int, systemRadiusPx: Int?): Boolean =
    usesSystemScreenCorners(sdk) && systemRadiusPx != null && systemRadiusPx <= 0

internal fun manualScreenCornerRadiusDp(manual: String): Dp =
    (manual.toFloatOrNull()?.coerceIn(0f, 130f) ?: 30f).dp

internal fun screenCornerRadiusDp(sdk: Int, systemRadiusPx: Int?, density: Float, manual: String): Dp {
    val systemDp = systemRadiusPx
        ?.takeIf { usesSystemScreenCorners(sdk) && it > 0 }
        ?.let { (it / density).dp }
    return systemDp ?: manualScreenCornerRadiusDp(manual)
}

/**
 * 最近一次系统圆角采样值（px，null = 尚未采样）。
 *
 * 由根壳的 [rememberScreenCornerRadius] 写入；设置页据此决定是否放出校准入口，
 * 免得设置页自己再挂一份 insets 监听、也不会出现入口忽隐忽现。
 */
internal object SystemScreenCornerSample {
    private var sample by mutableStateOf<Int?>(null)

    /** 最近一次采样值（px），null = 尚未采样。 */
    val radiusPx: Int?
        get() = sample

    /** 系统明确报了 0：需要人工校准，入口不能藏。 */
    val reportsSquare: Boolean
        get() = usesSystemScreenCorners(Build.VERSION.SDK_INT) && (sample ?: 1) <= 0

    fun update(px: Int?) {
        if (sample != px) sample = px
    }
}

@Composable
internal fun rememberScreenCornerRadius(): Dp {
    val manual = SettingsLibrary.ScreenCorner
    if (!usesSystemScreenCorners(Build.VERSION.SDK_INT)) {
        return screenCornerRadiusDp(Build.VERSION.SDK_INT, null, 1f, manual)
    }
    val view = LocalView.current
    val density = LocalDensity.current
    var radiusPx by remember(view) { mutableStateOf<Int?>(null) }
    DisposableEffect(view) {
        fun sample() {
            val radius = readSystemScreenCornerPx(view) ?: return
            if (radiusPx != radius) radiusPx = radius
            SystemScreenCornerSample.update(radius)
        }
        // Additive observation preserves the Insets listeners installed by Compose/Accompanist.
        val observer = view.viewTreeObserver
        val listener = ViewTreeObserver.OnPreDrawListener { sample(); true }
        observer.addOnPreDrawListener(listener)
        sample()
        onDispose {
            val liveObserver = if (observer.isAlive) observer else view.viewTreeObserver
            if (liveObserver.isAlive) liveObserver.removeOnPreDrawListener(listener)
        }
    }
    return screenCornerRadiusDp(Build.VERSION.SDK_INT, radiusPx, density.density, manual)
}

private fun readSystemScreenCornerPx(view: View): Int? {
    val insets = view.rootWindowInsets ?: return null
    return maximumScreenCornerRadius(
        listOf(
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius,
            insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius,
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius,
            insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius
        )
    )
}
