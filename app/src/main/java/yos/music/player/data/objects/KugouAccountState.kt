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

    var authVersion: Long by mutableStateOf(0L)
        private set

    fun updateAuthVersion(version: Long) {
        authVersion = version
    }

    var isLoggedIn by mutableStateOf(false)
        private set

    var userid by mutableStateOf<String?>(null)
        private set

    var hasVipToken by mutableStateOf(false)
        private set

    /**
     * 业务接口（`/song/url`）已明确拒绝当前 token（20017–20020 / token api error）。
     *
     * 与 [isLoggedIn] 分开：token 还在本地、界面仍显示已登录，但服务端不认了。
     * 账号页据此给出重登入口；播放侧据此把"需要验证"的误报纠正成"请重新登录"。
     *
     * 注意：**不要**用 `/login/token` 的 Rejected 置位——本移植版缺 t1 cookie，
     * 它对正常账号也回 20018，曾导致正常账号被连续误报"凭据已失效"。
     */
    var credentialsRejected by mutableStateOf(false)
        private set

    /** 由 KugouApiService 在恢复流程确证 token 被业务接口拒绝时调用。 */
    fun onCredentialsRejected() {
        credentialsRejected = true
    }

    /** 真实播放已推进 = 凭据显然有效：撤掉可能来自瞬时判定的误报。 */
    fun onPlaybackSucceeded() {
        if (credentialsRejected) credentialsRejected = false
    }

    /** 由 KugouApiService 在登录态写入点调用（恢复/登录成功）。 */
    fun apply(userid: String?, hasVipToken: Boolean) {
        this.isLoggedIn = userid != null
        this.userid = userid
        this.hasVipToken = hasVipToken
        if (userid != null) credentialsRejected = false
    }

    /** 由 KugouApiService.clearLogin 调用（退出登录）。 */
    fun clear() {
        isLoggedIn = false
        userid = null
        hasVipToken = false
        credentialsRejected = false
    }
}
