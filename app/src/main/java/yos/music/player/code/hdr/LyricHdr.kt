package yos.music.player.code.hdr

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.Display
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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

    /** Display HDR 能力探测是否已完成；未完成前不提前把冷启设好的 HDR 窗口冲回默认 */
    var displayProbed: Boolean by mutableStateOf(false)
        private set

    /** 窗口是否真的切到了 HDR 颜色模式 */
    var windowActive: Boolean by mutableStateOf(false)
        private set

    /**
     * 当前窗口 colorMode 是否处于 HDR（建窗由 MainActivity.onCreate 预置，运行期由 LyricHdrWindowEffect 同步）。
     */
    var windowColorModeHdr: Boolean by mutableStateOf(false)
        private set

    /** 只给建窗/窗口模式路径写 */
    fun markWindowColorModeHdr(value: Boolean) {
        windowColorModeHdr = value
    }

    /**
     * 峰值最多 2.4 倍 SDR 白：三通道等比提亮时，「70% 白 + 30% 封面色」若推到 3× 容易在
     * 面板高光 Tone-Mapper 拐点处漂白，2.4× 既保留明显 HDR 通透感又不易丢失封面色相。
     */
    const val MaxBoost = 2.4f

    /**
     * 已向系统申请到的余量（API 35+ 的 `setDesiredHdrHeadroom` 生效时才有值）。
     * 0 表示还没申请 / 该版本没有这个 API。
     */
    private var requestedHeadroom: Float by mutableFloatStateOf(0f)

    /**
     * 按 0..1 强度映射目标 HDR 峰值倍率（1 = SDR 白，MaxBoost = 2.4× SDR 白）。
     */
    fun targetBoostFor(strength: Float): Float {
        val s = strength.coerceIn(0f, 1f)
        return if (s <= 0.001f) 1f else 1f + (MaxBoost - 1f) * s
    }

    /**
     * 当前是否应走真 HDR 扩展亮度直绘（此时必须绕开 8 位离屏 saveLayer 与 BlendMode.Plus）。
     */
    fun isExtendedHdrActive(enabled: Boolean, strength: Float): Boolean =
        enabled && boost(strength) > 1.001f

    /**
     * 根据设置强度与系统报告的屏幕当前余量计算实际提亮倍率。
     *
     * 当屏幕已报告有效余量（[headroom] > 1.05）时，按 `headroom * 0.92f` 封顶以避开面板
     * 高光压缩导致的「已唱部分漂白、扫色过渡带显色」断层；未报告前先按 [targetBoostFor]
     * 唤醒 HDR 图层，避免冷启「先有鸡还是先有蛋」死锁。
     */
    internal fun resolveBoost(strength: Float, headroom: Float): Float {
        val target = targetBoostFor(strength)
        if (target <= 1.001f) return 1f
        if (headroom > 1.05f) {
            val safeCeiling = maxOf(1f, headroom * 0.92f)
            return minOf(target, safeCeiling)
        }
        return target
    }

    /**
     * HDR 光效的峰值亮度（SDR 白 = 1，线性光）；未生效时恰好是 1。
     * [strength] 是 0..1 的强度（设置项）：用掉屏幕可用余量的多少。
     */
    fun boost(strength: Float): Float {
        val s = strength.coerceIn(0f, 1f)
        if (!windowActive || !supported || s <= 0.001f) return 1f
        return resolveBoost(s, headroom)
    }

    /** 只给建窗/窗口模式路径写 */
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

    internal fun markDisplayProbed(value: Boolean) {
        displayProbed = value
    }

    internal fun markWindowActive(value: Boolean) {
        windowActive = value
    }
}

/**
 * 跟踪屏幕亮度余量，并在 [active] 时把窗口切到 HDR 模式；API 35+ 下随 [strength] 同步余量请求。
 *
 * [active] 由调用方决定（本项目直接跟随「HDR 高光」设置开关）；关闭或离开时恢复默认颜色模式。
 * 必须挂在 Activity 内容的根组合上，且全程只应有一处调用。
 */
@Composable
fun LyricHdrWindowEffect(active: Boolean, strength: Float = 0.6f) {
    if (Build.VERSION.SDK_INT < 34) {
        // 低版本没有 COLOR_MODE_HDR：全部按 SDR 画。状态更新放在 effect 里，
        // 组合期写快照状态会触发反复重组。
        DisposableEffect(Unit) {
            LyricHdr.markDisplayProbed(true)
            LyricHdr.markSupported(false)
            LyricHdr.markWindowActive(false)
            LyricHdr.markWindowColorModeHdr(false)
            LyricHdr.markRequestedHeadroom(0f)
            onDispose { }
        }
        return
    }
    val view = LocalView.current
    val context = LocalContext.current
    if (view.isInEditMode) return

    DisposableEffect(view) {
        var unregister: (() -> Unit)? = null
        var attachListener: View.OnAttachStateChangeListener? = null

        fun probe(display: Display) {
            unregister?.invoke()
            unregister = null
            LyricHdr.markDisplayProbed(true)
            if (!display.isHdrSdrRatioAvailable) {
                LyricHdr.markSupported(false)
                LyricHdr.updateDisplay(1f)
                return
            }
            LyricHdr.markSupported(true)
            LyricHdr.updateDisplay(display.hdrSdrRatio)
            val listener = Consumer<Display> { LyricHdr.updateDisplay(it.hdrSdrRatio) }
            display.registerHdrSdrRatioChangedListener(context.mainExecutor, listener)
            unregister = { display.unregisterHdrSdrRatioChangedListener(listener) }
        }

        val initialDisplay = view.display ?: context.display
        if (initialDisplay != null) {
            probe(initialDisplay)
        } else {
            val listener = object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) {
                    val d = v.display ?: v.context.display ?: return
                    probe(d)
                    v.removeOnAttachStateChangeListener(this)
                    attachListener = null
                }

                override fun onViewDetachedFromWindow(v: View) = Unit
            }
            attachListener = listener
            view.addOnAttachStateChangeListener(listener)
        }

        onDispose {
            attachListener?.let { view.removeOnAttachStateChangeListener(it) }
            attachListener = null
            unregister?.invoke()
            unregister = null
        }
    }

    val probed = LyricHdr.displayProbed
    val on = active && LyricHdr.supported

    // 1. 窗口颜色模式 effect：开关或探测状态变更时切换 colorMode 并立即触发视图重绘。
    DisposableEffect(view, on, probed) {
        if (!probed) {
            return@DisposableEffect onDispose { }
        }
        val window = view.context.findActivity()?.window
        if (on && window != null) {
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            LyricHdr.markWindowColorModeHdr(true)
            LyricHdr.markWindowActive(true)
            view.invalidate()
        } else {
            if (window != null) {
                if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(0f)
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                view.invalidate()
            }
            LyricHdr.markWindowColorModeHdr(false)
            LyricHdr.markRequestedHeadroom(0f)
            LyricHdr.markWindowActive(false)
        }
        onDispose {
            if (on && window != null) {
                if (Build.VERSION.SDK_INT >= 35) window.setDesiredHdrHeadroom(0f)
                window.colorMode = ActivityInfo.COLOR_MODE_DEFAULT
                view.invalidate()
                LyricHdr.markWindowColorModeHdr(false)
                LyricHdr.markRequestedHeadroom(0f)
                LyricHdr.markWindowActive(false)
            }
        }
    }

    // 2. API 35+ 强度联动 effect：仅调节 desiredHdrHeadroom，不碰 window.colorMode 避免重建 Surface。
    LaunchedEffect(view, on, strength) {
        if (on && Build.VERSION.SDK_INT >= 35) {
            val window = view.context.findActivity()?.window ?: return@LaunchedEffect
            val desired = LyricHdr.targetBoostFor(strength)
            window.setDesiredHdrHeadroom(desired)
            LyricHdr.markRequestedHeadroom(desired)
            view.invalidate()
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * HDR 窗口门禁：把 [LyricHdrWindowEffect] 的开关与强度收到一个小节点里。
 *
 * 直接跟随 [SettingsLibrary.LyricHdrEffect] 与 [SettingsLibrary.LyricHdrStrength]，
 * 保证开启时底栏跟手高光、预览卡片与播放页歌词全程保持 HDR，关闭时立即切回默认颜色模式。
 * 单独成 composable 是为了让设置项变化只重组这里，不拖累 MainActivity 的大作用域。
 */
@Composable
fun LyricHdrWindowGate(@Suppress("UNUSED_PARAMETER") progress: () -> Float) {
    LyricHdrWindowEffect(
        active = SettingsLibrary.LyricHdrEffect,
        strength = SettingsLibrary.LyricHdrStrength,
    )
}
