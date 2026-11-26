package yos.music.player.data.repositories

import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import yos.music.player.data.objects.KugouProfileState
import yos.music.player.data.objects.KugouUserDetail
import yos.music.player.native.KugouApiService
import yos.music.player.native.toPlainAny

/**
 * `/user/detail` 响应 → [KugouUserDetail]。
 *
 * 入参是 `toPlainAny` 之后的纯 stdlib 结构（org.json 在 JVM 单测里是 android.jar 桩，
 * 不能直接构造 JSONObject —— 与 [KugouRecommendPlaylistTest] 同款约定）。
 *
 * 结构：`{ status, error_code, data: { nickname, pic|avatar|img, userid, level, ... } }`
 *  - 未登录：`error_code=20010`（此时 data 为空）→ 返回 null，不能当成"用户没头像"，
 *    否则会把上一账号的头像抹掉。
 *  - 业务失败：`status` 存在且非 1 → 同上。
 *
 * 头像字段顺序对齐上游 Dart `KugouUserDetail.fromJson`
 * （`data['avatar'] ?? data['img'] ?? data['pic']`），`user_pic` 作为额外兜底
 * （评论/房间成员等模型用的就是它）。
 */
internal fun parseKugouUserDetail(root: Map<*, *>): KugouUserDetail? {
    fun IntOrNull(v: Any?): Int? = when (v) {
        is Number -> v.toInt()
        is String -> v.toIntOrNull()
        else -> null
    }

    val errorCode = IntOrNull(root["error_code"])
    if (errorCode != null && errorCode != 0) return null
    val status = IntOrNull(root["status"])
    if (status != null && status != 1) return null

    val data = (root["data"] as? Map<*, *>) ?: root

    fun text(vararg keys: String): String? {
        for (key in keys) {
            val s = when (val v = data[key]) {
                is String -> v
                is Number -> v.toLong().toString()
                else -> null
            }
            if (!s.isNullOrBlank()) return s
        }
        return null
    }

    val avatar = normalizeKugouImageUrl(
        text("avatar", "img", "pic", "user_pic")
    )
    val nickname = text("nickname", "username", "name")

    // data 里既没有昵称也没有头像，说明这不是一份有效资料（如未登录时的空 data）
    if (nickname == null && avatar == null) return null

    return KugouUserDetail(
        nickname = nickname,
        avatarUrl = avatar,
        userid = text("userid", "userId", "id"),
        level = IntOrNull(data["level"])
    )
}

/**
 * 酷狗用户资料（昵称 / 头像）仓库。
 *
 * 数据链：`/user/detail`（Rust handle_detail 转发 usercenter.kugou.com/v3/get_my_info）
 * → [parseKugouUserDetail] → [KugouProfileState]（可观察 + MMKV 按账号持久）。
 *
 * 拉取时机：
 *  - 登录成功：[KugouVipRepository.onLoggedIn] 顺带刷一次（扫码/手机号/引导页三条路径都汇到那里）
 *  - 进资料库：[ensureLoaded]，TTL 内不重复请求
 *  - 冷启动：直接从 [KugouProfileState] 的 MMKV 缓存取，不阻塞首帧
 */
object KugouProfileRepository {

    private const val TAG = "KugouProfileRepository"

    /** 资料变动极慢（昵称/头像），10 分钟内不重复请求。 */
    private const val TTL_MS = 10 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var loadedAt = 0L
    private var loadedUserid: String? = null
    private var loadJob: CompletableDeferred<Unit>? = null

    /**
     * 拉取并写入资料。失败返回 null（保留旧值，界面上头像是"旧但正确"好过"跳成字母"）。
     *
     * 网络与解析在 IO，状态/MMKV 写入切回 Main —— Compose 状态只从主线程改。
     */
    suspend fun refreshProfile(): KugouUserDetail? {
        val uid = KugouApiService.userid ?: return null
        val detail = withContext(Dispatchers.IO) {
            KugouApiService.getInstance().getUserDetail()
                .getOrNull()
                ?.let { json -> runCatching { parseKugouUserDetail(toPlainAny(json) as Map<*, *>) }.getOrNull() }
        }
        if (detail != null) {
            withContext(Dispatchers.Main) {
                KugouProfileState.update(detail)
                loadedAt = System.currentTimeMillis()
                loadedUserid = uid
            }
            Log.d(TAG, "profile loaded uid=$uid hasAvatar=${detail.avatarUrl != null} nickname=${detail.nickname}")
        } else {
            Log.d(TAG, "profile unavailable uid=$uid")
        }
        return detail
    }

    /**
     * 进页调用。TTL 内且同账号不重复请求；账号已切换时丢弃内存态强制重拉。
     * fire-and-forget，调用方无需 await。
     */
    fun ensureLoaded(force: Boolean = false) {
        if (!KugouApiService.isLoggedIn()) return
        val uid = KugouApiService.userid ?: return
        if (loadedUserid != null && loadedUserid != uid) {
            // 账号已切换：旧账号的内存资料作废（MMKV 里各自保留，不失）
            KugouProfileState.onAccountResolved(uid)
            loadedAt = 0
            loadedUserid = null
        }
        if (!force && loadedUserid == uid && System.currentTimeMillis() - loadedAt < TTL_MS) return
        synchronized(this) {
            // 并发调用方借同一个 deferred 去重
            loadJob ?: CompletableDeferred<Unit>().also { deferred ->
                loadJob = deferred
                scope.launch {
                    try {
                        refreshProfile()
                    } finally {
                        synchronized(this@KugouProfileRepository) { loadJob = null }
                        deferred.complete(Unit)
                    }
                }
            }
        }
    }

    /** 账号切换 / 登出时重置内存态（MMKV 按账号保留）。 */
    fun reset() {
        loadedAt = 0
        loadedUserid = null
    }
}
