package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Test
import yos.music.player.data.repositories.QualitySwitchPolicy.After
import yos.music.player.data.repositories.QualitySwitchPolicy.Before

/**
 * 切换决策穷举（断言 A2/A3 的自动化护栏）。
 *
 * 这两条函数决定"点一下档位之后要不要重开正在播的歌"，历史上正是它没有早退，
 * 才让"选了个拿不到的高档"变成一次可听的卡顿、让重复点击累积成多次重开。
 */
class QualitySwitchPolicyTest {

    private val s128 = KugouQuality.STANDARD
    private val flac = KugouQuality.LOSSLESS
    private val high = KugouQuality.HIRES

    // ---------- before ----------

    @Test
    fun beforeNoopsOnlyWhenOverrideAlreadyEqualsTarget() {
        assertEquals(
            Before.NOOP_INTENT_UNCHANGED,
            QualitySwitchPolicy.before(high, pending = null, factIntent = high, override = high)
        )
        assertEquals(
            Before.NOOP_INTENT_UNCHANGED,
            QualitySwitchPolicy.before(high, pending = high, factIntent = flac, override = high)
        )
    }

    @Test
    fun beforeStillProbesWhenPreferenceEqualsTargetButOverrideMissing() {
        // 偏好恰好等于目标档：不写本曲覆盖的话，用户的选择会在下一首之后被偏好改掉
        assertEquals(
            Before.PROBE,
            QualitySwitchPolicy.before(high, pending = null, factIntent = high, override = null)
        )
    }

    @Test
    fun pendingTierShadowsTheLastFactIntent() {
        // 正在切向 flac（pending），此时点 high 必须真的去探测，不能被旧的 high 事实吞掉
        assertEquals(
            Before.PROBE,
            QualitySwitchPolicy.before(high, pending = flac, factIntent = high, override = high)
        )
    }

    @Test
    fun beforeProbesWhenOverrideIsAnotherTier() {
        assertEquals(
            Before.PROBE,
            QualitySwitchPolicy.before(high, pending = null, factIntent = high, override = flac)
        )
    }

    @Test
    fun beforeProbesWithNoPriorStateAtAll() {
        assertEquals(
            Before.PROBE,
            QualitySwitchPolicy.before(high, pending = null, factIntent = null, override = null)
        )
    }

    // ---------- after ----------

    @Test
    fun afterRollbacksOnProbeFailureEvenIfTiersWouldMatch() {
        assertEquals(After.ROLLBACK, QualitySwitchPolicy.after(probeFailed = true, obtained = flac, previousPlaying = flac))
        assertEquals(After.ROLLBACK, QualitySwitchPolicy.after(probeFailed = true, obtained = null, previousPlaying = null))
    }

    @Test
    fun afterSkipsReloadWhenDeliveredSpecIsUnchanged() {
        // A3：为"没有变化的选择"付出一次卡顿是不可接受的
        assertEquals(After.NO_RELOAD, QualitySwitchPolicy.after(false, flac, flac))
    }

    @Test
    fun afterCommitsWhenDeliveredSpecActuallyChanged() {
        assertEquals(After.COMMIT_RELOAD, QualitySwitchPolicy.after(false, s128, flac))
        assertEquals(After.COMMIT_RELOAD, QualitySwitchPolicy.after(false, flac, high))
    }

    @Test
    fun afterCommitsWhenEitherSideIsUnknown() {
        // 未知不等于"没变化"：宁可重开一次让音频按新意图重解析
        assertEquals(After.COMMIT_RELOAD, QualitySwitchPolicy.after(false, null, flac))
        assertEquals(After.COMMIT_RELOAD, QualitySwitchPolicy.after(false, flac, null))
        assertEquals(After.COMMIT_RELOAD, QualitySwitchPolicy.after(false, null, null))
    }

    @Test
    fun everyAfterBranchIsReachableAndMutuallyExclusive() {
        val outcomes = listOf(
            QualitySwitchPolicy.after(true, flac, flac),
            QualitySwitchPolicy.after(false, flac, flac),
            QualitySwitchPolicy.after(false, s128, flac)
        )
        assertEquals(setOf(After.ROLLBACK, After.NO_RELOAD, After.COMMIT_RELOAD), outcomes.toSet())
    }
}
