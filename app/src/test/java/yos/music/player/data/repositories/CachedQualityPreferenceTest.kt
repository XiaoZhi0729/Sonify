package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「流量下改用本机已缓存高音源」的择优决策穷举。
 *
 * 这套判定直接决定用多少流量，所以每个分支都要有测试盯着：
 * - 该升档时不升 → 用户明明有本地 Hi-Res 却又下了一遍 128K（本功能存在的理由）；
 * - 不该升档时升了 → 放着一首没下完的大文件去烧流量（比原行为更糟）。
 *
 * 所有用例都走公开 API [CachedQualityPreference.choose]，不碰缓存实现。
 */
class CachedQualityPreferenceTest {

    private val s128 = KugouQuality.STANDARD
    private val h320 = KugouQuality.HIGH
    private val flac = KugouQuality.LOSSLESS
    private val hires = KugouQuality.HIRES

    /** 5 分钟：128K ≈ 4.8MB、FLAC ≈ 41MB、Hi-Res ≈ 97MB（按 [KugouQuality.nominalKbps]）。 */
    private val durationMs = 5 * 60 * 1000L

    private fun total(tier: String) = KugouQuality.estimateTotalBytes(tier, durationMs)

    /**
     * 构造一次决策。默认所有档位都没有缓存、且都不完整，只覆盖用例关心的那几档。
     *
     * @param cached 档位 → 已缓存字节
     * @param full 被精确证明整首已缓存的档位
     */
    private fun choose(
        intent: String,
        cached: Map<String, Long> = emptyMap(),
        full: Set<String> = emptySet(),
        duration: Long = durationMs
    ) = CachedQualityPreference.choose(
        intentTier = intent,
        durationMs = duration,
        cachedBytes = { tier -> cached[tier] ?: 0L },
        fullyCached = { tier -> tier in full },
        estimateTotal = KugouQuality::estimateTotalBytes
    )

    // ---------- 核心场景：这就是本功能要解决的问题 ----------

    @Test
    fun upgradesWhenHighTierIsFullyCachedAndIntentTierIsNot() {
        // 只在家听过这首歌（本地只有 Hi-Res），出门用流量：应当直接放本地的 Hi-Res，
        // 而不是重新下载 128K —— 后者比放本地高音质更费流量
        val choice = choose(intent = s128, full = setOf(hires))

        assertNotNull(choice)
        assertEquals(hires, choice!!.tier)
        assertTrue("整首在本地才能走零网络通道", choice.fullyCached)
        assertEquals(0L, choice.remainingBytes)
    }

    @Test
    fun doesNotUpgradeWhenHigherTierNeedsMoreBytesThanIntentTier() {
        // Hi-Res 剩 40MB 没下，而整首 128K 才 4.8MB：这时候升档是明确的浪费。
        // 这是"按剩余字节择优"相对"有缓存就升档"的核心价值
        val choice = choose(
            intent = s128,
            cached = mapOf(hires to total(hires) - 40L * 1024 * 1024)
        )

        assertNull("高音源还需要更多字节时不能动", choice)
    }

    @Test
    fun upgradesWhenHigherTierOnlyMissesAFewBytes() {
        // Hi-Res 只剩 1MB 没下，比整首 128K（4.8MB）还少：补完它更省流量
        val choice = choose(
            intent = s128,
            cached = mapOf(hires to total(hires) - 1L * 1024 * 1024)
        )

        assertNotNull(choice)
        assertEquals(hires, choice!!.tier)
        // 没整首缓存 ⇒ 不能走零网络通道，但仍按 Hi-Res 请求（续写同一份缓存）
        assertEquals(false, choice.fullyCached)
        assertEquals(1024L * 1024, choice.remainingBytes)
    }

    // ---------- 不该介入的情形 ----------

    @Test
    fun neverDowngrades() {
        // 用户要 Hi-Res：即便 128K 整首缓存着，也不能偷偷降到 128K
        val choice = choose(intent = hires, full = setOf(s128))
        assertNull(choice)
    }

    @Test
    fun returnsNullWhenIntentIsAlreadyTheHighestTier() {
        assertNull(choose(intent = hires, full = setOf(hires, flac)))
    }

    @Test
    fun returnsNullWhenNothingIsCachedAtAll() {
        assertNull(choose(intent = s128))
    }

    @Test
    fun returnsNullWhenIntentTierIsItselfFullyCached() {
        // 本地已有整首 128K：本来就不花流量，没有升档的理由
        assertNull(choose(intent = s128, full = setOf(s128)))
    }

    @Test
    fun returnsNullForUnknownTier() {
        assertNull(
            CachedQualityPreference.choose(
                intentTier = "not-a-tier",
                durationMs = durationMs,
                cachedBytes = { 0L },
                fullyCached = { true },
                estimateTotal = KugouQuality::estimateTotalBytes
            )
        )
    }

    // ---------- 时长未知时退化为"只认完整缓存" ----------

    @Test
    fun withoutDurationOnlyFullyCachedHigherTiersCanWin() {
        // 没时长就没法估算，任何"部分缓存"的高档都无从判断是否更省
        assertNull(
            choose(
                intent = s128,
                cached = mapOf(hires to total(hires) - 1024L),
                duration = 0L
            )
        )
        // 但"整首已缓存"是精确事实，不依赖时长
        val choice = choose(intent = s128, full = setOf(flac), duration = 0L)
        assertNotNull(choice)
        assertEquals(flac, choice!!.tier)
        assertTrue(choice.fullyCached)
    }

    // ---------- 多档竞争与平手 ----------

    @Test
    fun picksTheTierWithLeastRemainingAmongAllCandidates() {
        // Hi-Res 剩 30MB、FLAC 整首已缓存 ⇒ 选 FLAC（剩余 0）
        val choice = choose(
            intent = s128,
            cached = mapOf(hires to total(hires) - 30L * 1024 * 1024),
            full = setOf(flac)
        )
        assertNotNull(choice)
        assertEquals(flac, choice!!.tier)
        assertTrue(choice.fullyCached)
    }

    @Test
    fun tieBreaksTowardHigherQuality() {
        // 两档剩余字节完全相同时取更高一档：同样的流量成本，听感更好
        val choice = choose(intent = s128, full = setOf(h320, flac, hires))
        assertNotNull(choice)
        assertEquals(hires, choice!!.tier)
    }

    @Test
    fun fullyCachedIsPreferredOverEqualRemainingEstimate() {
        // 精确的"整首已缓存"（剩余 0）永远优先于任何估算出的剩余量
        val choice = choose(
            intent = s128,
            cached = mapOf(h320 to 0L),
            full = setOf(h320)
        )
        assertNotNull(choice)
        assertEquals(h320, choice!!.tier)
        assertTrue(choice.fullyCached)
    }

    @Test
    fun equalRemainingBytesDoesNotTriggerUpgrade() {
        // 两档剩余完全相同 ⇒ 严格小于才动，故维持原状。
        // 构造：Hi-Res 已缓存 (total(hires) - total(s128))，于是 Hi-Res 剩余恰等于
        // 整首 128K 的估算大小（= 128K 档未缓存时的剩余），两者相等。
        val choice = choose(
            intent = s128,
            cached = mapOf(hires to total(hires) - total(s128))
        )
        assertNull("剩余相等时不应升档", choice)
    }

    @Test
    fun oneByteLessRemainingIsEnoughToUpgrade() {
        // 比"相等"再少 1 字节就应当升档：说明比较是严格的，边界方向正确
        val choice = choose(
            intent = s128,
            cached = mapOf(hires to total(hires) - total(s128) + 1L)
        )
        assertNotNull(choice)
        assertEquals(hires, choice!!.tier)
    }

    // ---------- 估算本身的守恒性质 ----------

    @Test
    fun nominalKbpsIncreasesMonotonicallyWithTier() {
        val ordered = KugouQuality.ALL.map { KugouQuality.nominalKbps(it) }
        assertEquals(ordered.sorted(), ordered)
        assertEquals(ordered.size, ordered.toSet().size)
    }

    @Test
    fun estimateIsZeroForUnknownDurationAndPositiveOtherwise() {
        assertEquals(0L, KugouQuality.estimateTotalBytes(hires, 0L))
        assertEquals(0L, KugouQuality.estimateTotalBytes(hires, -1L))
        assertTrue(KugouQuality.estimateTotalBytes(hires, durationMs) > 0L)
    }

    @Test
    fun estimateGrowsWithTierForSameDuration() {
        val values = KugouQuality.ALL.map { KugouQuality.estimateTotalBytes(it, durationMs) }
        assertEquals(values.sorted(), values)
    }

    @Test
    fun estimateIsNotWildlyOffFromReality() {
        // 5 分钟 128K 实际约 4.8MB：估算必须落在同一量级，否则"剩余字节"比较失去意义
        val bytes = KugouQuality.estimateTotalBytes(s128, durationMs)
        val mb = bytes / (1024.0 * 1024.0)
        assertTrue("128K 5min 估算应在 2~8MB 之间，实际 ${mb}MB", mb in 2.0..8.0)
    }
}
