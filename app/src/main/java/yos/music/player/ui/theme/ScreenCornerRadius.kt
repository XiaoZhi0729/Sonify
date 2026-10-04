package yos.music.player.ui.theme

import android.os.Build
import android.view.RoundedCorner
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

internal fun screenCornerRadiusDp(sdk: Int, systemRadiusPx: Int?, density: Float, manual: String): Dp =
    if (usesSystemScreenCorners(sdk)) {
        if (systemRadiusPx == null) 30.dp else (systemRadiusPx.coerceAtLeast(0) / density).dp
    } else {
        (manual.toFloatOrNull()?.coerceIn(0f, 130f) ?: 30f).dp
    }

@Composable
internal fun rememberScreenCornerRadius(): Dp {
    if (!usesSystemScreenCorners(Build.VERSION.SDK_INT)) {
        return screenCornerRadiusDp(Build.VERSION.SDK_INT, null, 1f, SettingsLibrary.ScreenCorner)
    }
    val view = LocalView.current
    val density = LocalDensity.current
    var radiusPx by remember(view) { mutableStateOf<Int?>(null) }
    DisposableEffect(view) {
        fun sample() {
            val insets = view.rootWindowInsets ?: return
            val radius = maximumScreenCornerRadius(listOf(
                insets.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius,
                insets.getRoundedCorner(RoundedCorner.POSITION_TOP_RIGHT)?.radius,
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_RIGHT)?.radius,
                insets.getRoundedCorner(RoundedCorner.POSITION_BOTTOM_LEFT)?.radius
            ))
            if (radiusPx != radius) radiusPx = radius
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
    return screenCornerRadiusDp(Build.VERSION.SDK_INT, radiusPx, density.density, "30")
}
