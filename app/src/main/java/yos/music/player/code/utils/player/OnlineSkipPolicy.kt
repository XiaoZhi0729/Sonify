package yos.music.player.code.utils.player

/**
 * 在线曲目播放失败的分类。分类决定"跳到下一首"有没有意义：
 * 全账号失效时跳多少首都一样，逐首拦截时跳恰恰是对的。
 */
enum class OnlineFailureKind {
    /** 账号恢复后仍确认鉴权无效，不能靠切歌恢复。 */
    AUTH_INVALID,

    /** 需要完成验证，原地有限退避。 */
    VERIFICATION_REQUIRED,

    /** 请求签名被拒绝，不把它当作账号失效或逐首拦截。 */
    SIGNATURE_REJECTED,

    /** 临时 token 拒绝，尚未经过账号恢复确认。 */
    TOKEN_REJECTED,

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

    /** 普通逐首失败的锁存复位窗；到期只清连续失败数，不补回退避轮次。 */
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
     * 锁存是否已过复位窗。[latchedAtElapsed] 为 0 表示当前没锁存，此时**不算过窗**
     * （返回 false）——否则 [OnlineSkipState.decide] 每次进来都会把连续失败计数清零：
     * `SKIP_NEXT` 分支从不写 `latchedAtElapsed`，计数永远停在 1，`TRANSIENT_LIMIT` 的
     * 跳歌预算形同虚设（真机：`SKIP_TO_NEXT attempt=1` 连出十几条，永不停）。
     *
     * 另一层历史教训：旧实现把计数挂在匿名 `Player.Listener` 的字段上，且**只在
     * `STATE_READY` 清零**。source error 后播放器停在 IDLE，永远等不到 READY，
     * 计数就再也回不了零——于是每次错误都直接走"达上限"分支（`SKIP_LIMIT attempt=10`）。
     * 所以这里保留"锁存过窗即当新一轮"的复位入口，只是它必须**真的锁存过**才生效。
     */
    fun latchExpired(nowElapsed: Long, latchedAtElapsed: Long): Boolean =
        latchedAtElapsed != 0L && nowElapsed - latchedAtElapsed >= LATCH_RESET_MS

    /** 这些失败切歌无益，用户切歌也不能补回鉴权类退避预算。 */
    fun requiresAccountRecovery(kind: OnlineFailureKind): Boolean = when (kind) {
        OnlineFailureKind.AUTH_INVALID,
        OnlineFailureKind.VERIFICATION_REQUIRED,
        OnlineFailureKind.SIGNATURE_REJECTED,
        OnlineFailureKind.TOKEN_REJECTED -> true
        else -> false
    }

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
        // 验证、签名和未确认 token 拒绝也只能原地退避，轮次用尽就停。
        authWindowActive || requiresAccountRecovery(kind) ->
            if (roundsExhausted(retryRound + 1)) Decision.STOP else Decision.HOLD_AND_RETRY

        // 没有可跳的目标：停在原地等用户操作，不做无意义的自转
        !hasNext -> Decision.STOP

        // 其余各类（含逐首拦截）：先跳出去，跳满预算再考虑退避重试
        failures + 1 >= TRANSIENT_LIMIT ->
            if (roundsExhausted(retryRound + 1)) Decision.STOP else Decision.HOLD_AND_RETRY

        else -> Decision.SKIP_NEXT
    }
}

/**
 * 无 Android 依赖的恢复会话。宿主串行调用，时钟与延迟调度可在测试中替换。
 * 时间流逝只释放普通逐首失败锁存，不补回已用的退避预算。
 */
internal class OnlineSkipSession(
    private val nowElapsed: () -> Long,
    private val schedule: (Long, () -> Unit) -> (() -> Unit),
    private val onRearm: () -> Unit,
) {
    var failureCount: Int = 0
        private set
    var retryRounds: Int = 0
        private set
    private var latchedAt: Long? = null
    private var accountRecoveryActive = false
    private var cancelPending: (() -> Unit)? = null
    private var generation = 0L

    fun decide(
        kind: OnlineFailureKind,
        hasNext: Boolean,
        authWindowActive: Boolean,
    ): OnlineSkipPolicy.Decision {
        val now = nowElapsed()
        val latch = latchedAt
        if (!accountRecoveryActive && latch != null && now - latch >= OnlineSkipPolicy.LATCH_RESET_MS) {
            failureCount = 0
            latchedAt = null
        }
        accountRecoveryActive = accountRecoveryActive || authWindowActive ||
            OnlineSkipPolicy.requiresAccountRecovery(kind)
        val decision = OnlineSkipPolicy.decide(
            kind, failureCount, hasNext, authWindowActive || accountRecoveryActive, retryRounds
        )
        failureCount++
        when (decision) {
            OnlineSkipPolicy.Decision.SKIP_NEXT -> Unit
            OnlineSkipPolicy.Decision.HOLD_AND_RETRY -> {
                if (latchedAt == null) latchedAt = now
                retryRounds++
                val wait = requireNotNull(OnlineSkipPolicy.backoffMs(retryRounds))
                cancelRearm()
                val token = generation
                cancelPending = schedule(wait) {
                    if (token == generation) {
                        cancelPending = null
                        generation++
                        onRearm()
                    }
                }
            }
            OnlineSkipPolicy.Decision.STOP -> {
                if (latchedAt == null) latchedAt = now
                cancelRearm()
            }
        }
        return decision
    }

    fun cancelRearm() {
        generation++
        cancelPending?.invoke()
        cancelPending = null
    }

    /** 手动意图总会取消旧任务；鉴权类的已用轮次不因点歌而补回。 */
    fun onUserIntent() {
        if (accountRecoveryActive) {
            cancelRearm()
            failureCount = 0
            latchedAt = null
        } else {
            reset()
        }
    }

    /** 真实播放已推进，或账号发生变化，才允许清除鉴权恢复预算。 */
    fun reset() {
        cancelRearm()
        failureCount = 0
        retryRounds = 0
        latchedAt = null
        accountRecoveryActive = false
    }

    fun onAccountChanged() = reset()
}
