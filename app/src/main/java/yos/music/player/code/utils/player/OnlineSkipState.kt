package yos.music.player.code.utils.player

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import yos.music.player.code.utils.others.YosDiagnostics

/**
 * 在线失败恢复的主线程宿主。纯状态与调度取消规则在 [OnlineSkipSession]，
 * 本对象只适配 Android Handler、播放服务回调与诊断日志。
 */
object OnlineSkipState {
    private val handler = Handler(Looper.getMainLooper())
    private var rearmAction: (() -> Unit)? = null
    private val session = OnlineSkipSession(
        nowElapsed = { SystemClock.elapsedRealtime() },
        schedule = { wait, action ->
            val run = Runnable { action() }
            handler.postDelayed(run, wait)
            val cancel: () -> Unit = { handler.removeCallbacks(run) }
            cancel
        },
        onRearm = {
            YosDiagnostics.log("SKIP_REARM", "attempt" to sessionRounds())
            runCatching { rearmAction?.invoke() }
                .onFailure { e ->
                    YosDiagnostics.log("SKIP_REARM_ERR", "why" to e.toString())
                }
        },
    )

    // 避免 session 初始化表达式中的回调递归参与 Kotlin 类型推断。
    private fun sessionRounds(): Int = session.retryRounds

    fun failureCount(): Int = session.failureCount

    fun retryRounds(): Int = session.retryRounds

    /** 播放服务 onCreate 注入；该回调始终由主线程 Handler 执行。 */
    fun attach(onRearm: () -> Unit) {
        rearmAction = onRearm
    }

    /** 服务销毁只撤旧任务，不因服务重建补回鉴权恢复预算。 */
    fun detach() {
        session.cancelRearm()
        rearmAction = null
    }

    /**
     * 仅在真实播放已推进时调用，不能以单独 STATE_READY 或用户操作代替播放成功。
     * [reason] 只用于诊断；用户点歌/手动切歌应调用 [onUserIntent]。
     */
    fun reset(reason: String) {
        session.reset()
        YosDiagnostics.log("SKIP_RESET", "why" to reason)
    }

    /** 取消旧曲目的任务并把退避轮次归零（轮次只记自动重试，不记用户点了几首）。主线程调用。 */
    fun onUserIntent(reason: String) {
        session.onUserIntent()
        YosDiagnostics.log("SKIP_USER_INTENT", "why" to reason, "round" to session.retryRounds)
    }

    /**
     * 登录/退出/切账号完成后的全清入口。API 层可能在 IO 线程调用，因此始终向主线程投递，
     * 账号变化和播放器侧 decide/回调取消在同一队列中串行处理。
     */
    fun onAccountChanged() {
        handler.post {
            session.onAccountChanged()
            YosDiagnostics.log("SKIP_RESET", "why" to "account_changed")
        }
    }

    /**
     * 主线程问一次判定并调度；STOP 会取消任何未执行重备，取消后的旧回调也不能生效。
     * [hasNext] 须由调用方按索引/循环模式计算，不能依赖错误态的 hasNextMediaItem()。
     */
    fun decide(
        kind: OnlineFailureKind,
        hasNext: Boolean,
        authWindowActive: Boolean,
    ): OnlineSkipPolicy.Decision {
        val decision = session.decide(kind, hasNext, authWindowActive)
        if (decision == OnlineSkipPolicy.Decision.HOLD_AND_RETRY) {
            YosDiagnostics.log(
                "SKIP_HOLD",
                "kind" to kind.name,
                "wait" to OnlineSkipPolicy.backoffMs(session.retryRounds),
                "round" to session.retryRounds,
            )
        }
        YosDiagnostics.log(
            "SKIP_KIND",
            "kind" to kind.name,
            "n" to session.failureCount,
            "dec" to decision.name,
            "round" to session.retryRounds,
            "authwin" to authWindowActive,
        )
        return decision
    }
}
