package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.code.utils.player.OnlineSkipPolicy.Decision

class OnlineSkipStateTest {
    private class FakeRuntime {
        var now = 1_000L
        var rearmed = 0
        val waits = mutableListOf<Long>()
        val tasks = mutableListOf<Task>()

        class Task(val due: Long, val action: () -> Unit, var cancelled: Boolean = false)

        val session = OnlineSkipSession(
            nowElapsed = { now },
            schedule = { wait, action ->
                waits += wait
                val task = Task(now + wait, action)
                tasks += task
                val cancel: () -> Unit = { task.cancelled = true }
                cancel
            },
            onRearm = { rearmed++ },
        )

        fun advanceBy(ms: Long) {
            now += ms
            val due = tasks.filter { !it.cancelled && it.due <= now }
            due.forEach { it.cancelled = true; it.action() }
        }
    }

    @Test
    fun stopCancelsPendingAndInvalidatesEvenAnAlreadyDequeuedCallback() {
        val runtime = FakeRuntime()
        repeat(5) {
            assertEquals(Decision.HOLD_AND_RETRY,
                runtime.session.decide(OnlineFailureKind.TOKEN_REJECTED, true, false))
        }
        val pending = runtime.tasks.last()
        assertFalse(pending.cancelled)
        assertEquals(Decision.STOP,
            runtime.session.decide(OnlineFailureKind.TOKEN_REJECTED, true, false))
        assertTrue(pending.cancelled)
        pending.action()
        runtime.advanceBy(600_000L)
        assertEquals(0, runtime.rearmed)
    }

    @Test
    fun sixtySecondsNeverReplenishesTheOrdinaryRetryBudget() {
        val runtime = FakeRuntime()
        repeat(OnlineSkipPolicy.TRANSIENT_LIMIT) {
            runtime.session.decide(OnlineFailureKind.NETWORK, true, false)
        }
        assertEquals(1, runtime.session.retryRounds)
        runtime.advanceBy(60_000L)
        assertEquals(Decision.SKIP_NEXT,
            runtime.session.decide(OnlineFailureKind.NETWORK, true, false))
        assertEquals(1, runtime.session.retryRounds)
        assertEquals(1, runtime.session.failureCount)
        repeat(OnlineSkipPolicy.TRANSIENT_LIMIT - 1) {
            runtime.session.decide(OnlineFailureKind.NETWORK, true, false)
        }
        assertEquals(2, runtime.session.retryRounds)
        assertEquals(60_000L, runtime.waits.last())
    }

    @Test
    fun userIntentCancelsOldTaskAndRestartsAccountBackoff() {
        val runtime = FakeRuntime()
        repeat(5) {
            assertEquals(Decision.HOLD_AND_RETRY,
                runtime.session.decide(OnlineFailureKind.SIGNATURE_REJECTED, true, false))
            // 每次失败都从 30s 这一档开始：轮次不会被前几次点歌累积
            assertEquals(1, runtime.session.retryRounds)
            assertEquals(30_000L, runtime.waits.last())
            val stale = runtime.tasks.last()
            val rearmedBefore = runtime.rearmed
            runtime.session.onUserIntent()
            assertEquals(0, runtime.session.retryRounds)
            assertTrue(stale.cancelled)
            stale.action()
            runtime.advanceBy(60_000L)
            // 被取消的旧任务不得生效
            assertEquals(rearmedBefore, runtime.rearmed)
        }
        // 点歌不会把预算烧到 STOP：账号类失败仍然原地退避，用户也可以继续点
        assertEquals(Decision.HOLD_AND_RETRY,
            runtime.session.decide(OnlineFailureKind.SIGNATURE_REJECTED, true, false))
    }

    @Test
    fun ordinaryUserIntentRestoresTheHistoricalFreshStart() {
        val runtime = FakeRuntime()
        repeat(OnlineSkipPolicy.TRANSIENT_LIMIT) {
            runtime.session.decide(OnlineFailureKind.NETWORK, true, false)
        }
        val stale = runtime.tasks.last()
        runtime.session.onUserIntent()
        assertTrue(stale.cancelled)
        stale.action()
        assertEquals(0, runtime.rearmed)
        assertEquals(0, runtime.session.retryRounds)
        assertEquals(0, runtime.session.failureCount)
        assertEquals(Decision.SKIP_NEXT,
            runtime.session.decide(OnlineFailureKind.NETWORK, true, false))
    }

    @Test
    fun realPlaybackAndAccountChangeEachResetBudgetAndCancelOldTasks() {
        val runtime = FakeRuntime()
        for (clear in listOf<() -> Unit>(
            { runtime.session.reset() },
            { runtime.session.onAccountChanged() },
        )) {
            repeat(5) { runtime.session.decide(OnlineFailureKind.AUTH_INVALID, true, false) }
            val stale = runtime.tasks.last()
            clear()
            assertTrue(stale.cancelled)
            stale.action()
            assertEquals(0, runtime.rearmed)
            assertEquals(0, runtime.session.retryRounds)
            assertEquals(0, runtime.session.failureCount)
            assertEquals(Decision.SKIP_NEXT,
                runtime.session.decide(OnlineFailureKind.NETWORK, true, false))
            assertEquals(Decision.HOLD_AND_RETRY,
                runtime.session.decide(OnlineFailureKind.AUTH_INVALID, true, false))
            assertEquals(30_000L, runtime.waits.last())
            runtime.session.reset()
        }
    }

    @Test
    fun authWindowKeepsAccountRecoveryStickyButUserIntentRestartsBackoff() {
        val runtime = FakeRuntime()
        assertEquals(Decision.HOLD_AND_RETRY,
            runtime.session.decide(OnlineFailureKind.NETWORK, true, true))
        runtime.session.onUserIntent()
        runtime.advanceBy(60_000L)
        // 冷却窗关闭后账号恢复标记仍生效（NETWORK 也走原地退避），但轮次已按点歌重置
        assertEquals(Decision.HOLD_AND_RETRY,
            runtime.session.decide(OnlineFailureKind.OTHER, true, false))
        assertEquals(1, runtime.session.retryRounds)
        assertEquals(30_000L, runtime.waits.last())
    }

    @Test
    fun serviceDetachCancelsTaskWithoutReplenishingAccountBudget() {
        val runtime = FakeRuntime()
        runtime.session.decide(OnlineFailureKind.VERIFICATION_REQUIRED, true, false)
        val stale = runtime.tasks.last()
        runtime.session.cancelRearm()
        stale.action()
        assertEquals(0, runtime.rearmed)
        assertEquals(1, runtime.session.retryRounds)
        assertEquals(Decision.HOLD_AND_RETRY,
            runtime.session.decide(OnlineFailureKind.VERIFICATION_REQUIRED, true, false))
        assertEquals(60_000L, runtime.waits.last())
    }

    @Test
    fun zeroClockOriginStillExpiresOnlyTheOrdinaryLatch() {
        val runtime = FakeRuntime()
        runtime.now = 0L
        repeat(OnlineSkipPolicy.TRANSIENT_LIMIT) {
            runtime.session.decide(OnlineFailureKind.NETWORK, true, false)
        }
        runtime.advanceBy(60_000L)
        assertEquals(Decision.SKIP_NEXT,
            runtime.session.decide(OnlineFailureKind.NETWORK, true, false))
        assertEquals(1, runtime.session.retryRounds)
    }

    @Test
    fun everyAccountFailureUsesFullBackoffThenStopsEvenAcrossExpiryWindows() {
        for (kind in listOf(
            OnlineFailureKind.VERIFICATION_REQUIRED,
            OnlineFailureKind.SIGNATURE_REJECTED,
            OnlineFailureKind.TOKEN_REJECTED,
            OnlineFailureKind.AUTH_INVALID,
        )) {
            val runtime = FakeRuntime()
            val session = runtime.session
            val waits = listOf(30_000L, 60_000L, 120_000L, 240_000L, 240_000L)
            for ((index, wait) in waits.withIndex()) {
                assertEquals(Decision.HOLD_AND_RETRY, session.decide(kind, false, false))
                assertEquals(index + 1, session.retryRounds)
                assertEquals(wait, runtime.waits.last())
                runtime.advanceBy(wait - 1)
                assertEquals(index, runtime.rearmed)
                runtime.advanceBy(1)
                assertEquals(index + 1, runtime.rearmed)
            }
            assertEquals(Decision.STOP, session.decide(kind, true, false))
            runtime.advanceBy(600_000L)
            assertEquals(Decision.STOP, session.decide(kind, true, false))
            assertEquals(waits, runtime.waits)
            assertEquals(5, runtime.rearmed)
        }
    }
}
