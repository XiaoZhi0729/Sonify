package yos.music.player.code.hdr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import yos.music.player.data.libraries.SettingsLibrary
import java.util.function.Consumer

/**
 * 歌词页与全局液态玻璃的真 HDR 高光：开启设置开关时把窗口切到 HDR 模式，
 * 正在唱的那一句与底栏等交互高光可突破 SDR 白的亮度限制。
 *
 * 改写自 mochi-compose（Apache-2.0）的 `MochiHdr` / `MochiHdrWindowEffect`。
 * 需要 Android 14（API 34）以上和支持 HDR 的屏幕；其他情况 `supported == false`、
 * `boost` 恒为 1，整条链路退回原来的 SDR 画法，外观与关闭时完全一样。
 *
 * 开关开启期间窗口保持 HDR（见 [LyricHdrWindowGate]），关闭时立即切回默认颜色模式。
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

    /**
     * Activity 建窗时是否已把 colorMode 设成 HDR（见 MainActivity.onCreate）。
     * 缓冲格式在那一刻就定了，所以这是「HDR 到底可不可能生效」的真实判据；
     * [windowActive] 只反映歌词页此刻有没有把余量申请打开。
     */
    var windowColorModeHdr: Boolean by mutableStateOf(false)
        private set

    /** 只给建窗路径写（MainActivity.onCreate） */
    fun markWindowColorModeHdr(value: Boolean) {
        windowColorModeHdr = value
    }

    /** 峰值最多 3 倍 SDR 白：再亮只会刺眼、更耗电，彩色光也更容易顶到屏幕上限 */
    const val MaxBoost = 3f

    /**
     * 是否让「正在唱的那一句」用超过 SDR 白的扩展亮度绘制。
     *
     * **当前为 false**，原因是库侧的取舍：HDR 行必须绕开 8 位离屏图层才能保住扩展亮度，
     * 而离屏图层同时承载着逐字扫色遮罩。绕开的结果是焦点行整行常亮——
     * 逐字歌词退化成逐行，这是每句都看得见的损失。
     *
     * 反过来的代价小得多：扩展亮度经过离屏层被钳回 SDR 白（等于这条链路不起作用），
     * 但逐字扫色、加色泛光、上下渐隐全部正常。
     *
     * 所以在「扩展亮度在这台设备上肉眼只差约 10%」的前提下，选后者。
     * 打开这个开关之前，先解决「扩展亮度 + 逐字扫色」共存的问题。
     */
    private const val EnableExtendedBoost = false

    /**
     * 已向系统申请到的余量（API 35+ 的 `setDesiredHdrHeadroom` 生效时才有值）。
     * 0 表示还没申请 / 该版本没有这个 API。
     */
    private var requestedHeadroom: Float by mutableFloatStateOf(0f)

    /**
     * HDR 光效的峰值亮度（SDR 白 = 1，线性光）；未生效时恰好是 1。
     * [strength] 是 0..1 的强度（设置项）：用掉屏幕可用余量的多少。
     * 所有 HDR 画法都保证每个通道都不超过它，不会因为某个通道顶到屏幕上限被截断而发白。
     */
    fun boost(strength: Float): Float {
        // **扩展亮度直绘目前关闭**（见下方 EnableExtendedBoost 的说明）。
        // 这个设置项现在只控制加色泛光（BlendMode.Plus），不再改变文字本身的亮度。
        if (!EnableExtendedBoost) return 1f
        if (!windowActive || !supported) return 1f
        // 乘数必须来自「向系统申请到的余量」，不能来自 headroom 读数。
        //
        // headroom 是显示器**正在呈现**的 HDR/SDR 比，只有画面上真的画出超过 SDR 白的像素
        // 之后才会涨上去。拿它当乘数就是先有鸡还是先有蛋：第一帧恒为 1 → boost=1 →
        // 不画 HDR → 余量永不涨（真机实测：窗口已是 COLOR_MODE_HDR + desiredHdrHeadroom=3.0，
        // dumpsys display 仍恒为 hdrSdrRatio 1.0、mIsHdrLayerPresent=false）。
        //
        // 反过来「测到 >1 就跟随它」也不行：实测只升到 1.0458，跟随之后高光立刻变暗，
        // 余量又掉回 1.0——向下自激，观感是闪一下就没了。
        //
        // 目标取申请值；只有在没申请过（API 34 没有该 API）时才退回实测余量。
        val target = when {
            requestedHeadroom > 1f -> requestedHeadroom.coerceAtMost(MaxBoost)
            headroom > 1.001f -> headroom.coerceAtMost(MaxBoost)
            // API 34 上无法申请、也测不到余量：保持 SDR，至少不做超出屏幕的过驱动。
            else -> 1f
        }
        if (target <= 1f) return 1f
        return 1f + (target - 1f) * strength.coerceIn(0f, 1f)
    }

    /** 只给 [LyricHdrWindowEffect] 写 */
    internal fun markRequestedHeadroom(value: Float) {
        requestedHeadroom = value
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
 * [active] 由调用方决定（本项目直接跟随「HDR 高光」设置开关）；关闭或离开时恢复默认颜色模式。
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
            // 只有 35+ 能主动申请余量；34 上只能等系统按内容自己升。
            if (Build.VERSION.SDK_INT >= 35) {
                window.setDesiredHdrHeadroom(LyricHdr.MaxBoost)
                LyricHdr.markRequestedHeadroom(LyricHdr.MaxBoost)
            }
            LyricHdr.markWindowActive(true)
        } else {
            if (window != null) {
                if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(0f)
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
            LyricHdr.markRequestedHeadroom(0f)
            LyricHdr.markWindowActive(false)
        }
        onDispose {
            if (window != null) {
                if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(0f)
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
            }
            LyricHdr.markRequestedHeadroom(0f)
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
 * HDR 窗口门禁：把 [LyricHdrWindowEffect] 的开关收到一个小节点里。
 *
 * 直接跟随 [SettingsLibrary.LyricHdrEffect]，保证开启时底栏跟手高光与播放页歌词
 * 全程保持 HDR，关闭时立即切回默认颜色模式。单独成 composable 是为了让设置项变化
 * 只重组这里，不拖累 MainActivity 的大作用域。
 */
@Composable
fun LyricHdrWindowGate(@Suppress("UNUSED_PARAMETER") progress: () -> Float) {
    LyricHdrWindowEffect(active = SettingsLibrary.LyricHdrEffect)
}

