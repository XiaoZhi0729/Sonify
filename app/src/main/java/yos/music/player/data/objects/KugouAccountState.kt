package yos.music.player.data.objects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 酷狗账号全局可观察状态（唯一观察源）。
 *
 * 真实数据链：MMKV(kugou_auth) → KugouApiService.restoreAuth/applyLogin/clearLogin
 * → 本对象 → Compose UI 重组。
 *
 * 持久层始终是 MMKV，本对象只是它的可观察镜像——登录写点仅
 * KugouApiService 三处（restoreAuth/applyLogin/clearLogin），每处同步刷新这里，
 * 因此 Settings / 登录页 / 其它页面共享同一份状态，不存在第二份 token/userid。
 *
 * 启动恢复为同步 MMKV 内存读（YosBasicApplication.onCreate 顺序执行），
 * 任何页面组合前状态即已就绪，无需 loading/restoring 中间态。
 */
object KugouAccountState {

    var isLoggedIn by mutableStateOf(false)
        private set

    var userid by mutableStateOf<String?>(null)
        private set

    var hasVipToken by mutableStateOf(false)
        private set

    /** 由 KugouApiService 在登录态写入点调用（恢复/登录成功）。 */
    fun apply(userid: String?, hasVipToken: Boolean) {
        this.isLoggedIn = userid != null
        this.userid = userid
        this.hasVipToken = hasVipToken
    }

    /** 由 KugouApiService.clearLogin 调用（退出登录）。 */
    fun clear() {
        isLoggedIn = false
        userid = null
        hasVipToken = false
    }
}
