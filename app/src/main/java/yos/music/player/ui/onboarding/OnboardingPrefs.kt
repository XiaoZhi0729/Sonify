package yos.music.player.ui.onboarding

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.funny.data_saver.core.mutableDataSaverStateOf
import yos.music.player.data.NormalSaver

/**
 * 首次启动引导的持久化状态。
 *
 * 刻意**放在 [NormalSaver]**（`yos_data_normal`）而不是 `SettingsSaver`：
 * 设置页的「恢复默认设置」会 `SettingsSaver.clearAll()` 把整库清空，
 * 若引导标记存在那里，用户恢复默认后下次启动会再次被引导拦住 —— 那次操作
 * 显然不该被理解为"我要重新看引导"。要重看请走设置›关于 的显式入口。
 */
@Stable
object OnboardingPrefs {

    /**
     * 本次进程内是否已经进入过引导（不论是否走完）。
     *
     * ★为什么需要它：MainActivity 判断"要不要弹引导"用的是 `savedInstanceState == null`
     *   （冷启动）。但用户在引导里旋转屏幕 / 切深浅色触发 Activity 重建时，
     *   savedInstanceState 非空 → 引导消失，而 `completed` 还是 false →
     *   用户被扔回主页，下次冷启动又被拦一次。观感是"引导自己凭空没了"。
     *
     *   改成：本次进程里出现过就继续出现。进程被杀即清零，符合"首次启动才引导"。
     */
    var startedThisProcess: Boolean = false
        private set

    fun markStarted() {
        startedThisProcess = true
    }

    /** 引导是否已走完（含跳过）。首次启动为 false。 */
    var completed by mutableDataSaverOf(
        key = "onboarding_completed",
        initialValue = false
    )

    /** 仅供调试/验收：把标记清掉，下次冷启动会重新进入引导。 */
    fun reset() {
        completed = false
        startedThisProcess = false
    }
}

/** `mutableDataSaverStateOf` 的薄封装，省掉每个字段重复写 dataSaverInterface。 */
private fun mutableDataSaverOf(key: String, initialValue: Boolean) =
    mutableDataSaverStateOf(dataSaverInterface = NormalSaver, key = key, initialValue = initialValue)
