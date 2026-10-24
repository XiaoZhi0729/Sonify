package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 播放被上游风控（20028「本次请求需要验证」）拦下的全局状态。
 *
 * 与 [MediaViewModelObject] 同级：服务侧写、Compose 读。区别是它承载的是**一次待处理事件**
 * （拦截 + 有没有验证入口），而不是播放遥测——所以单独一个对象，避免把歌词/码率那套搅在一起。
 *
 * 为什么需要它：此前 20028 只有一条 Toast + 盲目退避，用户既看不到原因也没有任何可做的动作。
 * 现在由这里驱动「说明弹窗 + 播放页常驻胶囊」两个 UI，两者都只是这个状态的投影。
 *
 * 线程约定：写点全部在播放服务的监听回调（主线程），读点在 Compose 组合，不加锁。
 */
@Stable
object PlaybackBlockState {

    /** 上游给的验证入口类型。真机 /song/url 的 20028 常常只有 edt（没有 eventid）。 */
    enum class Challenge {
        /** 响应里带 eventid / ssaCode：可以直接换验证码格式并弹滑块。 */
        EventId,

        /** 只有 edt：拿它去试 /get/verify/info，能换出 txappid 才弹滑块。 */
        EdtFallback,

        /** 两个都没有：没有可完成的验证入口，只能重新登录或重试。 */
        None
    }

    /** 说明框上的按钮（顺序即展示顺序，纯函数 [actionsOf] 决定，可单测）。 */
    enum class Action { Verify, Retry, Login }

    data class Block(
        val mediaId: String?,
        val title: String?,
        val challenge: Challenge,
        val eventId: String?,
        val edt: String?,
        val raisedAt: Long
    ) {
        /** 拿去问 /get/verify/info 的凭据：eventid 优先，其次 edt。 */
        val challengeToken: String?
            get() = when (challenge) {
                Challenge.EventId -> eventId
                Challenge.EdtFallback -> edt
                Challenge.None -> null
            }
    }

    /**
     * 同一次拦截的自动弹窗冷却：10 分钟内同曲同类型不再自动弹（胶囊仍常驻，可手动打开）。
     * 取值与服务的退避节奏无关——它只防"每次 onPlayerError 都盖一层弹窗"。
     */
    const val PROMPT_COOLDOWN_MS = 10 * 60 * 1000L

    var block: Block? by mutableStateOf(null)
        private set

    /** 是否该展示说明框。弹窗只读这一个开关，不自己缓存"显示过没有"。 */
    var promptRequested: Boolean by mutableStateOf(false)
        private set

    private var lastPromptKey: String? = null
    private var lastPromptAt: Long = 0L

    /** 纯函数：有验证入口才给「去验证」，否则只能「重新登录」+「重试」。 */
    fun actionsOf(challenge: Challenge): List<Action> = when (challenge) {
        Challenge.EventId, Challenge.EdtFallback -> listOf(Action.Verify, Action.Retry)
        Challenge.None -> listOf(Action.Login, Action.Retry)
    }

    /**
     * 记一次拦截。返回是否应该自动弹说明框（false = 冷却中，只更新胶囊）。
     * [now] 由调用方传入（服务用 elapsedRealtime），测试传固定值。
     */
    fun raise(
        mediaId: String?,
        title: String?,
        challenge: Challenge,
        eventId: String?,
        edt: String?,
        now: Long
    ): Boolean {
        block = Block(mediaId, title, challenge, eventId, edt, now)
        val key = "${mediaId.orEmpty()}|${challenge.name}"
        val shouldPrompt = key != lastPromptKey || now - lastPromptAt >= PROMPT_COOLDOWN_MS
        if (shouldPrompt) {
            lastPromptKey = key
            lastPromptAt = now
            promptRequested = true
        }
        return shouldPrompt
    }

    /** 用户主动打开（点胶囊）。 */
    fun requestPrompt() {
        if (block != null) promptRequested = true
    }

    /** 说明框关闭：不再自动弹，但胶囊保留。 */
    fun dismissPrompt() {
        promptRequested = false
    }

    /** 真实播放推进 / 切歌 / 换账号：整条状态作废（含冷却窗口，下次是新问题）。 */
    fun clear() {
        block = null
        promptRequested = false
        lastPromptKey = null
        lastPromptAt = 0L
    }

    /**
     * 曲目过渡时调用：**只有真的换了另一首**才作废。
     *
     * 为什么不能无条件 clear：`setMediaItems`（启动恢复队列、重建播放列表）与重备同一曲
     * 都会派发 onMediaItemTransition，但它们指向同一首。旧实现无条件清理，导致说明框
     * 刚弹出就被启动恢复的 transition 收掉，表现为"闪一下就没了"（真机已复现）。
     *
     * [mediaId] 为 null 表示队列被清空/当前曲目未知 → 作废；block 归属未知（调试钩子、
     * 事件早于曲目落地）→ 保留，交给播放成功或用户操作结束。
     */
    fun clearIfOtherTrack(mediaId: String?) {
        val current = block ?: return
        val blocked = current.mediaId ?: return
        if (mediaId != null && mediaId == blocked) return
        clear()
    }

    /**
     * 验证入口用不了（edt 也换不出 txappid / 类型不支持）：降级成"没有入口"，
     * 下次打开说明框就显示「重新登录 / 重试」，而不是继续给一个点了没用的「去验证」。
     */
    fun downgradeToNoChallenge() {
        block = block?.copy(challenge = Challenge.None, eventId = null, edt = null)
    }
}
