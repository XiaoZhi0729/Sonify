package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.code.utils.player.OnlineSkipPolicy.Decision

/**
 * 在线曲目失败分诊的判定表。这些用例就是本次反馈的复盘：
 * 旧实现"只在 STATE_READY 清零 + 达上限后什么都不做"，等价于
 * 一旦数满 10 首就永久锁死（真机 attempt 恒为 10、按下一首无任何反应）。
 */
class OnlineSkipPolicyTest {

    private fun decide(
        kind: OnlineFailureKind,
        failures: Int,
        hasNext: Boolean = true,
        authWindow: Boolean = false,
        round: Int = 0,
    ): Decision = OnlineSkipPolicy.decide(kind, failures, hasNext, authWindow, round)

    // ---------- 逐首跳的预算 ----------

    @Test
    fun blockedSongsAreSkippedThroughNotHalted() {
        // 收藏夹里连着十几首付费拦截：跳过去是对的，逐首拦截不该提前判死整个队列
        assertEquals(Decision.SKIP_NEXT, decide(OnlineFailureKind.BLOCKED, 0))
        assertEquals(Decision.SKIP_NEXT, decide(OnlineFailureKind.BLOCKED, 7))
        assertEquals(Decision.SKIP_NEXT, decide(OnlineFailureKind.BLOCKED, 8))
        // failures 是"本次之前"的数：failures=8 是第 9 首，9 是第 10 首——用完预算才转挂起
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.BLOCKED, 9))
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.BLOCKED, 10))
    }

    @Test
    fun networkFailuresKeepTheHistoricalBudget() {
        assertEquals(Decision.SKIP_NEXT, decide(OnlineFailureKind.NETWORK, 5))
        assertEquals(Decision.SKIP_NEXT, decide(OnlineFailureKind.NETWORK, 8))
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.NETWORK, 9))
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.TIMEOUT, 9))
        // 认不出来的失败走同一套保守预算，不因分类缺失而变成"永久锁死"
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.OTHER, 9))
    }

    // ---------- 鉴权失效：第二首就是证据，不必刷满 ----------

    @Test
    fun authInvalidHoldsAfterTwoStrikes() {
        // 第一首仍然先跳（可能是单首问题，也需要给分诊留一次样本）
        assertEquals(Decision.SKIP_NEXT, decide(OnlineFailureKind.AUTH_INVALID, 0))
        // 第二首：全账号失败的证据成立，跳与不跳结果一样 → 挂起退避
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.AUTH_INVALID, 1))
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.AUTH_INVALID, 7))
    }

    @Test
    fun authCooldownWindowSkipsEvenWithoutEvidence() {
        // 仓库层已经知道整账号在冷却：一次请求都不该再发，也不该继续逐首跳
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.NETWORK, 0, authWindow = true))
        // 队末也值得等：鉴权恢复后同一首就能播（真机 5 分钟后自愈）
        assertEquals(Decision.HOLD_AND_RETRY, decide(OnlineFailureKind.OTHER, 0, hasNext = false, authWindow = true))
    }

    // ---------- 没有可跳目标时不自转 ----------

    @Test
    fun endOfQueueStopsInsteadOfSpinning() {
        // 队末 + repeat OFF，以及"队列只有这一首"（hasNext 由调用方按索引算）都走这里
        assertEquals(Decision.STOP, decide(OnlineFailureKind.NETWORK, 0, hasNext = false))
        assertEquals(Decision.STOP, decide(OnlineFailureKind.BLOCKED, 3, hasNext = false))
    }

    // ---------- 退避有上限，用完就停，不无限发请求 ----------

    @Test
    fun backoffGrowsAndStopsAfterTheLastRound() {
        val rounds = OnlineSkipPolicy.RETRY_BACKOFF_MS.size
        assertEquals(30_000L, OnlineSkipPolicy.backoffMs(1)!!)
        assertEquals(60_000L, OnlineSkipPolicy.backoffMs(2)!!)
        assertEquals(120_000L, OnlineSkipPolicy.backoffMs(3)!!)
        assertEquals(OnlineSkipPolicy.RETRY_BACKOFF_MS[rounds - 1], OnlineSkipPolicy.backoffMs(rounds)!!)
        assertNull(OnlineSkipPolicy.backoffMs(rounds + 1))
        // 轮次用尽：从"等一会儿再试"降级成"停住"，用户的手动操作仍然能重新起一轮
        assertEquals(Decision.STOP, decide(OnlineFailureKind.AUTH_INVALID, 1, authWindow = true, round = rounds))
    }

    // ---------- 锁存窗复位：IDLE 态下唯一能把计数降下来的入口 ----------

    @Test
    fun latchResetsAfterTheWindowEvenWithoutReady() {
        val latched = 1_000_000L
        assertFalse(OnlineSkipPolicy.latchExpired(latched + 30_000L, latched))
        assertTrue(OnlineSkipPolicy.latchExpired(latched + OnlineSkipPolicy.LATCH_RESET_MS, latched))
        // 没锁存过就永远算"过窗"（第一轮计数从 0 起）
        assertTrue(OnlineSkipPolicy.latchExpired(latched, 0L))
    }

    @Test
    fun limitsAreSane() {
        assertTrue(OnlineSkipPolicy.AUTH_LIMIT <= OnlineSkipPolicy.TRANSIENT_LIMIT)
        assertTrue(OnlineSkipPolicy.TRANSIENT_LIMIT > 0)
    }
}
