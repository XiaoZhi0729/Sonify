package yos.music.player.code.utils.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import yos.music.player.code.utils.others.YosDiagnostics

/**
 * 在线曲目连续失败时的**跳歌状态**：计数、锁存时刻、退避重试调度。判定规则在
 * [OnlineSkipPolicy]（纯逻辑、可单测），这里只负责记住和把动作发出去。
 *
 * 形状沿用 [CrossfadeExo]：进程内单例 + 由播放服务注入动作回调。之所以不做成播放服务的
 * 实例字段——"用户意图"（点歌、手动切歌）发生在 app 侧 object 里，那里拿不到服务实例；
 * 为一次复位去发自定义会话命令又太重。
 *
 * 历史上这里根本没有状态：计数挂在匿名 `Player.Listener` 的字段上，且只在 `STATE_READY`
 * 清零。source error 后播放器停在 IDLE，等不到 READY，计数就再也回不了零，于是每一次错误
 * 都直接落进"达上限"分支，而那个分支既不跳也不重备——真机表现就是"按下一首完全没反应"，
 * 日志特征是 `SKIP_LIMIT attempt=10` 反复出现而 attempt 永不变小。
 */
object OnlineSkipState {

    private val handler = Handler(Looper.getMainLooper())

    private var failures = 0
    private var retryRound = 0
    private var latchedAtElapsed = 0L
    private var pendingRearm: Runnable? = null
    private var rearmAction: (() -> Unit)? = null

    /** 当前连续失败数（诊断用）。 */
    fun failureCount(): Int = failures

    /** 已用掉的退避轮次（诊断用）。 */
    fun retryRounds(): Int = retryRound

    /**
     * 播放服务 onCreate 注入：锁存到期后该做什么（重备当前条目）。
     * 必须在主线程执行——里面要碰 player。
     */
    fun attach(onRearm: () -> Unit) {
        rearmAction = onRearm
    }

    /** 服务销毁：撤掉未执行的退避任务，避免回调打到已 release 的播放器上。 */
    fun detach() {
        cancelRearm()
        rearmAction = null
        reset("svc_destroy")
    }

    /**
     * 复位为一轮全新计数。调用点：真实开播（`STATE_READY`）、用户显式意图（点歌/手动切歌）。
     * [reason] 只进日志，便于事后判断"是谁把计数清掉的"。
     */
    fun reset(reason: String) {
        val changed = failures != 0 || retryRound != 0 || latchedAtElapsed != 0L
        failures = 0
        retryRound = 0
        latchedAtElapsed = 0L
        cancelRearm()
        if (changed) YosDiagnostics.log("SKIP_RESET", "why" to reason)
    }

    /**
     * 问一次判定，并把结论记进状态：[OnlineSkipPolicy.Decision.SKIP_NEXT] 累计一次失败数，
     * [OnlineSkipPolicy.Decision.HOLD_AND_RETRY] 记锁存时刻并按退避表挂好下一次重备。
     *
     * @param hasNext 队列里是否还有别的条目可跳（错误态下 `hasNextMediaItem()` 恒 false，
     *                调用方须按索引/循环模式自己算）
     * @param authWindowActive 仓库层的鉴权冷却窗是否开着
     */
    fun decide(
        kind: OnlineFailureKind,
        hasNext: Boolean,
        authWindowActive: Boolean,
    ): OnlineSkipPolicy.Decision {
        // 锁存过窗即当作新一轮：这是 IDLE 态下唯一还能把计数降下来的入口
        if (OnlineSkipPolicy.latchExpired(SystemClock.elapsedRealtime(), latchedAtElapsed)) {
            failures = 0
            retryRound = 0
            latchedAtElapsed = 0L
        }
        val decision = OnlineSkipPolicy.decide(kind, failures, hasNext, authWindowActive, retryRound)
        when (decision) {
            OnlineSkipPolicy.Decision.SKIP_NEXT -> failures++

            OnlineSkipPolicy.Decision.HOLD_AND_RETRY -> {
                failures++
                if (latchedAtElapsed == 0L) latchedAtElapsed = SystemClock.elapsedRealtime()
                retryRound++
                val wait = OnlineSkipPolicy.backoffMs(retryRound)
                if (wait != null) scheduleRearm(retryRound, wait, kind)
            }

            OnlineSkipPolicy.Decision.STOP -> {
                failures++
                if (latchedAtElapsed == 0L) latchedAtElapsed = SystemClock.elapsedRealtime()
            }
        }
        YosDiagnostics.log(
            "SKIP_KIND",
            "kind" to kind.name,
            "n" to failures,
            "dec" to decision.name,
            "round" to retryRound,
            "authwin" to authWindowActive
        )
        return decision
    }

    private fun scheduleRearm(round: Int, waitMs: Long, kind: OnlineFailureKind) {
        cancelRearm()
        val run = Runnable {
            pendingRearm = null
            YosDiagnostics.log("SKIP_REARM", "attempt" to round, "kind" to kind.name)
            runCatching { rearmAction?.invoke() }
                .onFailure { e -> YosDiagnostics.log("SKIP_REARM_ERR", "attempt" to round, "why" to e.toString()) }
        }
        pendingRearm = run
        handler.postDelayed(run, waitMs)
        YosDiagnostics.log("SKIP_HOLD", "kind" to kind.name, "wait" to waitMs, "round" to round)
    }

    private fun cancelRearm() {
        pendingRearm?.let { handler.removeCallbacks(it) }
        pendingRearm = null
    }
}
