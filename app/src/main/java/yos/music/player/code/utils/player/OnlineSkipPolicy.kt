package yos.music.player.code.utils.player

/**
 * 在线曲目播放失败的分类。分类决定"跳到下一首"有没有意义：
 * 全账号失效时跳多少首都一样，逐首拦截时跳恰恰是对的。
 */
enum class OnlineFailureKind {
    /** 上游鉴权失效（illegal_key）：整账号都拿不到 URL。 */
    AUTH_INVALID,

    /** 版权/付费拦截：只有这一首拿不到，下一首可能正常 → 跳。 */
    BLOCKED,

    /** 网络失败：值得退避重试，也值得跳（下一首可能已在缓存里）。 */
    NETWORK,

    /** 解析/加载超时。 */
    TIMEOUT,

    /** 认不出来的失败：按最保守的历史行为处理。 */
    OTHER
}

/**
 * 纯逻辑：在线曲目连续解析/加载失败时该跳、该停、还是该等一会儿再试。
 * 没有 Android / media3 依赖，规则全部可在 [OnlineSkipPolicyTest] 里钉死
 * （与 [CrossfadePolicy] 同一分法：规则在这，副作用在 OnlineSkipState）。
 */
object OnlineSkipPolicy {

    enum class Decision {
        /** 跳到下一首（并重新 prepare，错误态下光 seek 不会触发加载）。 */
        SKIP_NEXT,

        /** 原地挂起：保留队列与索引，退避后重备当前条目。 */
        HOLD_AND_RETRY,

        /** 停住：没有可跳的目标，或退避轮次已用尽。 */
        STOP
    }

    /**
     * 逐首跳的预算。历史上是 10，保持不变：URL 解析改成惰性后，一次真机实测里
     * 收藏夹连着十几首付费拦截曲，10 首预算正是"从拦截段里穿出去"所需的空间
     * （3.4 秒/首时它会变成 34 秒卡顿——那条已由快速失败修掉，不是靠调小预算）。
     */
    const val TRANSIENT_LIMIT = 10

    /**
     * 鉴权失效的预算：全账号失败，第二首再失败就已经是证据而不是猜测，继续跳只是把
     * 同一个 502 多刷几遍。
     */
    const val AUTH_LIMIT = 2

    /** 锁存后过这么久即当作"新一轮"，计数复位（旧实现只在 STATE_READY 复位，见下）。 */
    const val LATCH_RESET_MS = 60_000L

    /**
     * 退避节奏：30 s → 60 s → 2 min → 4 min → 4 min，用完即转 [Decision.STOP]。
     * 长度即最大轮次；上限之外不再自动重试，避免弱网/无账号时后台无限发请求。
     */
    val RETRY_BACKOFF_MS = longArrayOf(30_000L, 60_000L, 120_000L, 240_000L, 240_000L)

    /** 第 [round] 轮（从 1 开始）该等多久；超出轮次上限返回 null。 */
    fun backoffMs(round: Int): Long? = RETRY_BACKOFF_MS.getOrNull(round - 1)

    /** 退避轮次是否已用尽。 */
    fun roundsExhausted(round: Int): Boolean = round > RETRY_BACKOFF_MS.size

    /**
     * 锁存是否已过复位窗。[latchedAtElapsed] 为 0 表示当前没锁存。
     *
     * 这一条是本次修复的核心之一：旧实现把计数挂在匿名 `Player.Listener` 的字段上，
     * 且**只在 `STATE_READY` 清零**。source error 后播放器停在 IDLE，永远等不到 READY，
     * 计数就再也回不了零——于是每次错误都直接走"达上限"分支，而该分支既不跳也不重备，
     * 用户看到的就是"按下一首完全没反应"（真机：`SKIP_LIMIT attempt=10` 连出 4 次，
     * attempt 始终是 10，中间无任何 TRANSITION）。
     */
    fun latchExpired(nowElapsed: Long, latchedAtElapsed: Long): Boolean =
        latchedAtElapsed == 0L || nowElapsed - latchedAtElapsed >= LATCH_RESET_MS

    /**
     * @param failures 本次之前的**连续失败数**（0 起，不含本次）
     * @param authWindowActive 仓库层的鉴权冷却窗是否开着（窗口内一次请求都不该再发）
     * @param retryRound 已经用掉的退避轮次（0 起）
     */
    fun decide(
        kind: OnlineFailureKind,
        failures: Int,
        hasNext: Boolean,
        authWindowActive: Boolean,
        retryRound: Int,
    ): Decision = when {
        // 冷却窗里/鉴权失效攒够证据：只能等，跳和重试都没用；轮次用完就停
        authWindowActive || (kind == OnlineFailureKind.AUTH_INVALID && failures + 1 >= AUTH_LIMIT) ->
            if (roundsExhausted(retryRound + 1)) Decision.STOP else Decision.HOLD_AND_RETRY

        // 没有可跳的目标：停在原地等用户操作，不做无意义的自转
        !hasNext -> Decision.STOP

        // 其余各类（含逐首拦截）：先跳出去，跳满预算再考虑退避重试
        failures + 1 >= TRANSIENT_LIMIT ->
            if (roundsExhausted(retryRound + 1)) Decision.STOP else Decision.HOLD_AND_RETRY

        else -> Decision.SKIP_NEXT
    }
}
