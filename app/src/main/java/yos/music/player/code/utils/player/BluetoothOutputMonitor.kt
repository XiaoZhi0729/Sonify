package yos.music.player.code.utils.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper

/**
 * 蓝牙音频输出路由监听：供「车载蓝牙歌词」判断当前是否在往蓝牙设备出声。
 *
 * 只看输出路由，不看蓝牙配对/连接状态——只有 A2DP/SCO 真正成为当前输出时才改写歌词，
 * 否则插着蓝牙耳机但声音走扬声器时也会误改标题。读路由不需要任何权限。
 *
 * 回调统一派发到主线程：订阅方（会话元数据提交）要求主线程访问 MediaController。
 * [start] 幂等，重复调用不会重复注册。
 */
object BluetoothOutputMonitor {

    @Volatile
    var isActive: Boolean = false
        private set

    /** 路由变化回调（主线程）。参数为变化后的 [isActive]。 */
    var onChanged: ((Boolean) -> Unit)? = null

    private var audioManager: AudioManager? = null
    private var callback: AudioDeviceCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val deviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            refresh()
        }
    }

    fun start(context: Context) {
        if (callback != null) return
        val manager = context.applicationContext
            .getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        audioManager = manager
        callback = deviceCallback
        runCatching {
            manager.registerAudioDeviceCallback(deviceCallback, mainHandler)
        }
        // 服务启动时可能已经连着蓝牙：注册后立即算一次初值
        refresh()
    }

    fun stop() {
        val manager = audioManager
        val cb = callback
        callback = null
        audioManager = null
        if (manager != null && cb != null) {
            runCatching { manager.unregisterAudioDeviceCallback(cb) }
        }
        isActive = false
    }

    private fun refresh() {
        val manager = audioManager ?: return
        val active = runCatching {
            manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            }
        }.getOrDefault(false)
        if (active == isActive) return
        isActive = active
        onChanged?.invoke(active)
    }
}
