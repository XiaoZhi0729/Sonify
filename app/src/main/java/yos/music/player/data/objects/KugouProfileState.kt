package yos.music.player.data.objects

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import android.util.Log
import com.tencent.mmkv.MMKV

/** `/user/detail` 解析结果（对齐 md3Music KugouUserDetail 的子集）。 */
data class KugouUserDetail(
    val nickname: String?,
    /** 头像绝对 URL（已归一化：{size}→400、http→https）。为空表示上游没有头像。 */
    val avatarUrl: String?,
    val userid: String?,
    /** 用户等级（上游 data.level），暂未上 UI，留着给后续徽章用。 */
    val level: Int?
)

/**
 * 酷狗用户资料（昵称 / 头像）全局可观察状态。
 *
 * 分层与 [KugouAccountState] / [KugouVipState] 相同：本对象只是可观察镜像，
 * 真实网络在 KugouProfileRepository，MMKV 持久化 key 挂在 kugou_auth 实例。
 *
 * 持久化按账号隔离（`_userid` 后缀）：登出不清数据，重登同一账号立刻恢复上次头像，
 * 不必等一次网络往返（冷启动/离线时右上角也不会先闪字母再跳成照片）。
 * 不同账号各自读写自己的 key，互不污染。
 *
 * 线程约定：mutableStateOf 写入只发生在主线程调用点，与项目内其它全局 object 一致。
 */
object KugouProfileState {

    private const val K_AVATAR = "kugou_profile_avatar"
    private const val K_NICKNAME = "kugou_profile_nickname"

    private val store: MMKV? by lazy {
        runCatching { MMKV.mmkvWithID("kugou_auth") }.getOrNull()
    }

    /** 当前账号后缀；账号未知时落到 _anon（空命名空间）。 */
    private var keySuffix: String = "_anon"

    /** 头像 URL（已归一化）。null = 无头像或尚未拉取，UI 回落字母块。 */
    var avatarUrl: String? by mutableStateOf(null)
        private set

    /** 昵称。/user/detail 的昵称比 /user/vip/detail 更可靠（后者缺字段时为空）。 */
    var nickname: String? by mutableStateOf(null)
        private set

    /** 用户等级（data.level），暂未上 UI。 */
    var level: Int? by mutableStateOf(null)
        private set

    /**
     * 由 KugouApiService 在登录态写入点调用（restoreAuth/applyLogin→userid，clearLogin→null）：
     * 切换 MMKV key 并重载对应账号的缓存。必须在 [KugouVipState.onAccountResolved] 之后调用，
     * 两者互不依赖。
     */
    fun onAccountResolved(userid: String?) {
        keySuffix = if (userid != null) "_$userid" else "_anon"
        avatarUrl = store?.decodeString("$K_AVATAR$keySuffix")?.takeIf { it.isNotBlank() }
        nickname = store?.decodeString("$K_NICKNAME$keySuffix")?.takeIf { it.isNotBlank() }
        // level 只来自网络响应，不持久化
        level = null
        Log.d("KugouProfileState", "onAccountResolved uid=$userid cachedAvatar=${avatarUrl != null}")
    }

    /** 写入新拉取到的资料（含 MMKV 持久化）。 */
    fun update(detail: KugouUserDetail) {
        nickname = detail.nickname?.takeIf { it.isNotBlank() }
        avatarUrl = detail.avatarUrl?.takeIf { it.isNotBlank() }
        level = detail.level
        runCatching {
            val s = store ?: return@runCatching
            nickname?.let { s.encode("$K_NICKNAME$keySuffix", it) }
            avatarUrl?.let { s.encode("$K_AVATAR$keySuffix", it) }
        }
    }

    /** 退出登录：只清内存；MMKV 按账号保留（重登同一账号立即恢复）。 */
    fun clear() {
        avatarUrl = null
        nickname = null
        level = null
        keySuffix = "_anon"
    }
}
