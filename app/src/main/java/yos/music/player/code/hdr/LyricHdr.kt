package yos.music.player.code.hdr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import yos.music.player.data.libraries.SettingsLibrary
import java.util.function.Consumer
import kotlin.math.min

/**
 * 歌词页的真 HDR 高光：把窗口切到 HDR 模式，正在唱的那一句用超过 SDR 白的亮度画。
 *
 * 改写自 mochi-compose（Apache-2.0）的 `MochiHdr` / `MochiHdrWindowEffect`。
 * 需要 Android 14（API 34）以上和支持 HDR 的屏幕；其他情况 `supported == false`、
 * `boost` 恒为 1，整条链路退回原来的 SDR 画法，外观与关闭时完全一样。
 *
 * HDR 模式下屏幕会提高背光、比 SDR 更耗电，所以只在播放页展开时打开（见 [LyricHdrWindowEffect]）。
 */
object LyricHdr {
    /** 屏幕当前的 HDR / SDR 亮度比（1 = 没有余量）；系统按环境光和亮度实时调整 */
    var headroom: Float by mutableFloatStateOf(1f)
        private set

    /** 系统和屏幕是否支持 HDR 界面 */
    var supported: Boolean by mutableStateOf(false)
        private set

    /** 窗口是否真的切到了 HDR 颜色模式 */
    var windowActive: Boolean by mutableStateOf(false)
        private set

    /** 峰值最多 3 倍 SDR 白：再亮只会刺眼、更耗电，彩色光也更容易顶到屏幕上限 */
    const val MaxBoost = 3f

    /**
     * HDR 光效的峰值亮度（SDR 白 = 1，线性光）；未生效时恰好是 1。
     * [strength] 是 0..1 的强度（设置项）：用掉屏幕可用余量的多少。
     * 所有 HDR 画法都保证每个通道都不超过它，不会因为某个通道顶到屏幕上限被截断而发白。
     */
    fun boost(strength: Float): Float =
        if (windowActive && supported) {
            1f + (min(headroom, MaxBoost) - 1f).coerceAtLeast(0f) * strength.coerceIn(0f, 1f)
        } else {
            1f
        }

    /** 只给 [LyricHdrWindowEffect] 写 */
    internal fun updateDisplay(ratio: Float) {
        headroom = ratio
    }

    internal fun markSupported(value: Boolean) {
        supported = value
    }

    internal fun markWindowActive(value: Boolean) {
        windowActive = value
    }
}

/**
 * 跟踪屏幕亮度余量，并在 [active] 时把窗口切到 HDR 模式。
 *
 * [active] 由调用方决定（本项目是「播放页已展开」）；关掉或离开时恢复默认颜色模式。
 * 必须挂在 Activity 内容的根组合上，且全程只应有一处调用。
 */
@Composable
fun LyricHdrWindowEffect(active: Boolean) {
    if (Build.VERSION.SDK_INT < 34) {
        // 低版本没有 COLOR_MODE_HDR：全部按 SDR 画。状态更新放在 effect 里，
        // 组合期写快照状态会触发反复重组。
        DisposableEffect(Unit) {
            LyricHdr.markSupported(false)
            LyricHdr.markWindowActive(false)
            onDispose { }
        }
        return
    }
    val view = LocalView.current
    val context = LocalContext.current
    if (view.isInEditMode) return

    DisposableEffect(view) {
        val display: Display? = view.display
        if (display == null || !display.isHdrSdrRatioAvailable) {
            LyricHdr.markSupported(false)
            return@DisposableEffect onDispose { }
        }
        LyricHdr.markSupported(true)
        LyricHdr.updateDisplay(display.hdrSdrRatio)
        val listener = Consumer<Display> { LyricHdr.updateDisplay(it.hdrSdrRatio) }
        display.registerHdrSdrRatioChangedListener(context.mainExecutor, listener)
        onDispose { display.unregisterHdrSdrRatioChangedListener(listener) }
    }

    val on = active && LyricHdr.supported
    DisposableEffect(view, on) {
        val window = view.context.findActivity()?.window
        if (on && window != null) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(LyricHdr.MaxBoost)
            LyricHdr.markWindowActive(true)
        } else {
            LyricHdr.markWindowActive(false)
        }
        onDispose {
            if (on && window != null) {
                if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(0f)
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
            LyricHdr.markWindowActive(false)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * 播放壳展开门禁：把 [LyricHdrWindowEffect] 的开关收到一个小节点里。
 *
 * 阈值带滞回（0.55 进 / 0.45 出）：拖到 0.55 附近反复抖动时不会来回切颜色模式。
 * 单独成 composable 是为了让状态只重组这里——直接写在 MainActivity 的大作用域里，
 * 每次跨界都会让整块外壳重组一次。
 */
@Composable
fun LyricHdrWindowGate(progress: () -> Float) {
    val currentProgress by rememberUpdatedState(progress)
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        snapshotFlow { currentProgress() }.collect { p ->
            expanded = if (expanded) p > 0.45f else p > 0.55f
        }
    }
    val wanted = SettingsLibrary.LyricHdrEffect && expanded
    LyricHdrWindowEffect(active = wanted)
}

