package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import yos.music.player.code.vocalsep.VocalSeparationController
import yos.music.player.data.libraries.SettingsLibrary

/**
 * 人声分离的 UI 状态（服务侧写、Compose 读）。
 *
 * 与 [PlaybackBlockState] 同构：只是播放器状态的投影，不持有任何音频资源——
 * 真正的处理器与引擎住在 [VocalSeparationController]。
 *
 * 线程约定：写点全部在播放服务/控制层（主线程），读点在组合。不加锁。
 */
@Stable
object VocalSeparationState {

    /**
     * 开启时的起点 ratio（原声含量）。
     *
     * **不能用 1f**：1 是「完全原声」= 等同关闭（UI 水位在最底），用户点开按钮会
     * 看不出变化、然后被「拖到底自动收起」立刻关掉。也不能用 0f（纯伴奏），
     * 那是极端值，一开始就只剩伴奏听感突兀。0.5 是"明显听得出变化但不极端"的起点。
     */
    const val FallbackOnRatio = 0.5f

    /**
     * ratio 的最低值。低于它认为「已经是完全原声」。
     *
     * ratio 是**原声含量**（0 = 完全原声 = 关闭、1 = 纯伴奏），与 UI 水位方向相反
     * （水位 = 1 - ratio）。这里的分界在 0 端，对应水位在**最底**。
     */
    const val RatioAtBottom = 0.005f

    /**
     * 判定「水位已到最底（该关闭）」的容差，值域是**水位**：`1f - ratio <= 该值`。
     *
     * 用独立的水位常量而不是复用 [RatioAtBottom]，是为了让调用点读起来不会搞反方向
     * ——比 ratio 的边界写错过一次，语义命名比复用同一个数更安全。
     */
    const val FillAtBottom = 0.005f

    /** 持久化值高于这个才认为"确实做了点什么"；否则开起来等于没开。 */
    private const val MinRatioForEffect = 0.005f

    enum class Phase {
        /** 未开启。 */
        Off,

        /** 已开启（含引擎预热期）。 */
        On,

        /** 引擎故障已自动退回原声。 */
        Failed
    }

    var phase: Phase by mutableStateOf(Phase.Off)
        private set

    /** 原声含量 0..1（0 = 纯伴奏，1 = 原声 = 等同关闭）。 */
    var ratio: Float by mutableFloatStateOf(FallbackOnRatio)
        private set

    /** 模型是否可用。false 时按钮置灰（开源构建不带模型）。 */
    var available: Boolean by mutableStateOf(false)
        private set

    /** 当前状态归属的曲目。切到别的歌要作废（同 [PlaybackBlockState.clearIfOtherTrack]）。 */
    private var trackId: String? = null

    val isOn: Boolean get() = phase == Phase.On

    /** 模型可用性由控制层推来（加载结果）。 */
    fun updateAvailability(value: Boolean) {
        available = value
        if (!value && phase != Phase.Off) {
            phase = Phase.Off
        }
    }

    fun toggle(mediaId: String?) {
        if (!available) return
        applyEnabled(phase != Phase.On, mediaId)
    }

    fun applyEnabled(enabled: Boolean, mediaId: String?) {
        if (enabled && !available) return
        trackId = mediaId
        if (enabled) {
            // 每次开启都用持久化的默认占比：用户上次拖到的位置就是他想要的起点。
            //
            // 持久化值的语义是「原声含量」：0 = 完全原声（= 关闭）、1 = 纯伴奏。
            // 注意与 UI 水位**方向相反**——水位是「人声抑制量」，拉得越高人声越少。
            // 这里若读到 0（= 原声 = 等同没开）就换成 50%，否则点开按钮会毫无反应。
            val persisted = SettingsLibrary.VocalSeparationDefaultRatio.coerceIn(0f, 1f)
            ratio = if (persisted > MinRatioForEffect) persisted else FallbackOnRatio
            VocalSeparationController.setRatio(ratio)
        }
        phase = if (enabled) Phase.On else Phase.Off
        VocalSeparationController.setEnabled(enabled)
    }

    fun updateRatio(value: Float) {
        ratio = value.coerceIn(0f, 1f)
        VocalSeparationController.setRatio(ratio)
    }

    /**
     * 水位拖到最底 = 完全原声 = 关闭。
     *
     * [restoreRatio] 必须是**本次拖动开始前**的占比，不能取「最后经过的非边界值」——
     * 拖到底的路上一定经过 1%，那样写回去下次开启几乎立刻又会被自动关掉。
     *
     * 关闭时把 [ratio] 也归位到 restore 值：否则它停在 0，
     * 下次开启时白色水位要从最底突然跳回中间，读起来像卡了一下。
     */
    fun disableByFullRatio(mediaId: String?, restoreRatio: Float) {
        val restore = restoreRatio
            .coerceIn(0f, 1f)
            .takeIf { it > MinRatioForEffect }
            ?: FallbackOnRatio
        SettingsLibrary.VocalSeparationDefaultRatio = restore
        ratio = restore
        applyEnabled(false, mediaId)
    }

    /** 引擎故障：回到原声并标记失败（UI 据此弹一次 Toast 并置灰）。 */
    fun onEngineFailed(reason: String) {
        if (phase != Phase.On) return
        phase = Phase.Failed
        available = false
        // 处理器自己已经关掉了开关，这里只同步全局态
        VocalSeparationController.setEnabled(false)
    }

    /** 用户看过失败提示后回到可点状态（模型可能仍在，允许重试）。 */
    fun acknowledgeFailure() {
        if (phase == Phase.Failed) phase = Phase.Off
    }

    /**
     * 曲目过渡：**只有真的换了另一首**才关闭。
     *
     * 不能无条件关：队列重建 / 重备同一首也会派发 transition，那会把用户在听的效果
     * 无端掐掉（与 [PlaybackBlockState.clearIfOtherTrack] 同一个教训）。
     */
    fun clearIfOtherTrack(mediaId: String?) {
        if (phase == Phase.Off) {
            trackId = mediaId
            return
        }
        val owner = trackId
        if (mediaId != null && owner != null && mediaId == owner) return
        phase = Phase.Off
        trackId = mediaId
        VocalSeparationController.resetAndDisable()
    }
}
