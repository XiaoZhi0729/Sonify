package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 音质解析与缓存的事实层测试（断言 A1/A2/A6 的数据层护栏）。
 *
 * 全部走"缓存已就绪"的路径，不触网：诊断报告里最伤信任的现象——同一首歌第一次
 * 显示无损、二次播放显示 Hi-Res——正是缓存命中路径拿**键档位**当实际档位上报造成的，
 * 这里就是钉死它的回归用例。
 */
class KugouQualityResolutionTest {

    private val hash = "ABC123hash"

    @Before
    fun cleanState() {
        KugouRepository.resetQualityStateForTest()
        PerSongQualityIntent.clearAll()
    }

    @Test
    fun cacheHitReportsStampedTierNotRequestedTier() {
        // "high" 键里装的其实是一条 FLAC 流（降级链在低档精确命中后的历史留痕）
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.HIRES, "https://cdn/flac-stream", KugouQuality.LOSSLESS)

        val url = KugouRepository.resolvePlayUrlBlocking(hash, KugouQuality.HIRES)

        assertEquals("https://cdn/flac-stream", url)
        val fact = KugouRepository.factOf(hash)
        assertNotNull(fact)
        // 断言 A1：读数只能是实际档位，绝不能是请求档位
        assertEquals(KugouQuality.LOSSLESS, fact!!.stampedTier)
        assertEquals(KugouQuality.LOSSLESS, KugouRepository.actualQualityOf(hash))
        assertEquals(KugouQuality.HIRES, fact.intentTier)
        assertEquals(QualityVerdict.DOWNGRADED, fact.verdict)
    }

    @Test
    fun cacheHitWithoutStampIsUnknownInsteadOfGuessing() {
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.HIRES, "https://cdn/x", null)

        KugouRepository.resolvePlayUrlBlocking(hash, KugouQuality.HIRES)

        val fact = KugouRepository.factOf(hash)!!
        assertNull(fact.stampedTier)
        assertNull(KugouRepository.actualQualityOf(hash))
        assertEquals(QualityVerdict.UNKNOWN, fact.verdict)
    }

    @Test
    fun stickyReusePicksHighestStampedCacheAndLabelsItKept() {
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.LOSSLESS, "https://cdn/flac", KugouQuality.LOSSLESS)

        KugouRepository.resolvePlayUrlBlocking(hash, KugouQuality.STANDARD)

        val fact = KugouRepository.factOf(hash)!!
        assertEquals(KugouQuality.STANDARD, fact.intentTier)
        assertEquals(KugouQuality.LOSSLESS, fact.stampedTier)
        // 高于意图不是"本曲最高"，而是"沿用了更高音源"——诊断里被误标成上限的那一支
        assertEquals(QualityVerdict.KEPT_HIGHER, fact.verdict)
    }

    @Test
    fun explicitSwitchKeepsFactsAndMarksPending() {
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.LOSSLESS, "https://cdn/flac", KugouQuality.LOSSLESS)
        KugouRepository.resolvePlayUrlBlocking(hash, KugouQuality.LOSSLESS)
        val before = KugouRepository.actualQualityOf(hash)

        KugouRepository.prepareForExplicitSwitch(hash, KugouQuality.HIRES)

        // 断言 A6：切换期间事实不被抹掉（旧实现 remove 掉实际档位 → 徽标整块消失再弹回）
        assertEquals(before, KugouRepository.actualQualityOf(hash))
        assertEquals(KugouQuality.HIRES, KugouRepository.pendingTierOf(hash))
    }

    @Test
    fun explicitLoweringDropsHigherCachedSource() {
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.LOSSLESS, "https://cdn/flac", KugouQuality.LOSSLESS)
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.STANDARD, "https://cdn/128", KugouQuality.STANDARD)

        KugouRepository.prepareForExplicitSwitch(hash, KugouQuality.STANDARD)

        // 显式要标准：高于目标的缓存必须让路，否则"省流量"会被 30 分钟 TTL 内的粘性推翻
        assertNull(KugouRepository.stampedOfCachedUrl(hash, KugouQuality.LOSSLESS))
        assertEquals(KugouQuality.STANDARD, KugouRepository.stampedOfCachedUrl(hash, KugouQuality.STANDARD))
    }

    @Test
    fun explicitSwitchToSameTierKeepsEqualTierCache() {
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.HIRES, "https://cdn/flac-under-high-key", KugouQuality.LOSSLESS)

        KugouRepository.prepareForExplicitSwitch(hash, KugouQuality.LOSSLESS)

        // "high 键装 FLAC 流"的实际档位等于目标档 → 该留，避免白白多发一次请求
        assertEquals(KugouQuality.LOSSLESS, KugouRepository.stampedOfCachedUrl(hash, KugouQuality.HIRES))
    }

    @Test
    fun failedSwitchKeepsPreviousReadingAndLabelsFailed() {
        KugouRepository.seedCachedUrlForTest(hash, KugouQuality.LOSSLESS, "https://cdn/flac", KugouQuality.LOSSLESS)
        KugouRepository.resolvePlayUrlBlocking(hash, KugouQuality.LOSSLESS)

        KugouRepository.markSwitchFailed(
            hash,
            QualityIntentResolver.Decision(KugouQuality.HIRES, IntentSource.PER_SONG)
        )

        val fact = KugouRepository.factOf(hash)!!
        // 现场未动：读数仍是无损，但结论必须说清"这次切换没成"
        assertEquals(KugouQuality.LOSSLESS, fact.playingTier)
        assertEquals(QualityVerdict.FAILED, fact.verdict)
        assertNull(KugouRepository.pendingTierOf(hash))
    }

    @Test
    fun perSongOverrideIsPreferredOverPreference() {
        val preference = QualityIntentResolver.Decision(
            KugouQuality.STANDARD,
            IntentSource.MOBILE_PREF
        )

        val withOverride = QualityIntentResolver.decide(hash, KugouQuality.LOSSLESS, preference)
        val withoutOverride = QualityIntentResolver.decide(hash, null, preference)

        // 仲裁：本曲覆盖优先；无覆盖时原样透传偏好及其来源（不能把 MOBILE 洗成 WIFI）
        assertEquals(KugouQuality.LOSSLESS, withOverride.tier)
        assertEquals(IntentSource.PER_SONG, withOverride.source)
        assertEquals(preference, withoutOverride)
    }

    @Test
    fun storeKeepsOverridePerHashOnly() {
        PerSongQualityIntent.setOverride(hash, KugouQuality.HIRES)
        assertEquals(KugouQuality.HIRES, PerSongQualityIntent.overrideOf(hash.uppercase()))
        assertNull(PerSongQualityIntent.overrideOf("other"))

        PerSongQualityIntent.clearOverride(hash)
        assertNull(PerSongQualityIntent.overrideOf(hash))
    }

    @Test
    fun overrideRejectsTierThatCannotBeNamed() {
        // 认不出的档位串一律不写：写进去会让"本曲覆盖"变成一个谁也对不上的档位，
        // 面板勾不住、解析又拿不到，只剩一个说不清的读数
        PerSongQualityIntent.setOverride(hash, "ultra")

        assertNull(PerSongQualityIntent.overrideOf(hash))
        assertTrue(PerSongQualityIntent.isEmpty)
    }

    @Test
    fun blobRoundTripDropsUnknownTierAndExpiredEntries() {
        val now = 1_800_000_000_000L
        val dayMs = 24L * 60 * 60 * 1000
        val blob = listOf(
            "$hash|flac|$now",
            "OTHER|high|${now - 5 * dayMs}",
            // 过期项（>30 天）、认不出的档位、缺字段的残行都必须被丢掉
            "stale|128|${now - 40 * dayMs}",
            "badtier|ultra|$now",
            "truncated|320"
        ).joinToString(";")

        val parsed = PerSongQualityIntent.parseBlobForTest(blob, now)

        assertEquals(
            mapOf(hash.lowercase() to KugouQuality.LOSSLESS, "other" to KugouQuality.HIRES),
            parsed
        )
    }

    @Test
    fun clearingOneSongDoesNotTouchOthers() {
        val other = "deadbeef"
        PerSongQualityIntent.setOverride(hash, KugouQuality.LOSSLESS)
        PerSongQualityIntent.setOverride(other, KugouQuality.STANDARD)

        PerSongQualityIntent.clearOverride(hash)

        assertNull(PerSongQualityIntent.overrideOf(hash))
        // 撤销一首不得连带抹掉另一首：本曲覆盖的作用域就是这一首
        assertEquals(KugouQuality.STANDARD, PerSongQualityIntent.overrideOf(other))
    }
}
