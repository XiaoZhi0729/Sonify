package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * 能力表（按档位累积的证据）单测。
 *
 * 钉的是"置灰不随意图漂移"这条行为：请求 Hi-Res 被拒之后，即便后来的事实变成
 * CONFIRMED（用户改点无损），Hi-Res 也必须继续保持"已证明不可用"。
 * 持久层（MMKV）在 JVM 单测里不可用，已被逐次 runCatching 吞掉，这里验证的是内存路径。
 */
class SongQualityCapabilityStoreTest {

    private val hash = "cafe1234BEEF"

    @Before
    fun clean() {
        SongQualityCapabilityStore.clearForTest()
    }

    @Test
    fun downgradeMarksAskedTierAndEverythingAboveIt() {
        SongQualityCapabilityStore.recordDowngrade(hash, KugouQuality.HIRES, KugouQuality.LOSSLESS)

        val unavailable = SongQualityCapabilityStore.provenUnavailableOf(hash)
        assertEquals(setOf(KugouQuality.HIRES), unavailable)
        // 实际拿到的无损，以及更低的档，都不得被标成不可用
        assertFalse(KugouQuality.LOSSLESS in unavailable)
        assertFalse(KugouQuality.STANDARD in unavailable)
    }

    @Test
    fun downgradeFromLosslessAlsoGreysHiRes() {
        // 单调推断：请求无损只拿到 320 ⇒ 无损与 Hi-Res 都拿不到
        SongQualityCapabilityStore.recordDowngrade(hash, KugouQuality.LOSSLESS, KugouQuality.HIGH)

        val unavailable = SongQualityCapabilityStore.provenUnavailableOf(hash)
        assertTrue(KugouQuality.LOSSLESS in unavailable)
        assertTrue(KugouQuality.HIRES in unavailable)
        assertFalse(KugouQuality.HIGH in unavailable)
    }

    @Test
    fun evidenceAccumulatesAndConflictingProofFavorsAvailability() {
        // 先"请求 high 只拿到 320"（320 已证明可用），再"请求 320 只拿到 128"
        SongQualityCapabilityStore.recordDowngrade(hash, KugouQuality.HIRES, KugouQuality.HIGH)
        SongQualityCapabilityStore.recordDowngrade(hash, KugouQuality.HIGH, KugouQuality.STANDARD)

        val unavailable = SongQualityCapabilityStore.provenUnavailableOf(hash)
        // 无损与 Hi-Res 不可用；320 虽然第2次没拿到，但第1次真拿到过，
        // 矛盾证据按"已交付过就算可用"解，避免把偶尔波动写成长期置灰
        assertEquals(
            setOf(KugouQuality.HIRES, KugouQuality.LOSSLESS),
            unavailable
        )
    }

    @Test
    fun laterSuccessClearsTheLowerTiers() {
        // 先证明无损及以上拿不到，后来真拿到无损（会员开通/音源上架）→ 自愈
        SongQualityCapabilityStore.recordDowngrade(hash, KugouQuality.LOSSLESS, KugouQuality.HIGH)
        assertTrue(KugouQuality.LOSSLESS in SongQualityCapabilityStore.provenUnavailableOf(hash))

        SongQualityCapabilityStore.recordAvailable(hash, KugouQuality.LOSSLESS)

        val unavailable = SongQualityCapabilityStore.provenUnavailableOf(hash)
        assertFalse(KugouQuality.LOSSLESS in unavailable)
        assertFalse(KugouQuality.HIGH in unavailable)
        // 高于已证明可用档的 Hi-Res 仍是不可用（没被误清）
        assertTrue(KugouQuality.HIRES in unavailable)
    }

    @Test
    fun unknownHashHasNoEvidence() {
        assertTrue(SongQualityCapabilityStore.provenUnavailableOf("never-seen").isEmpty())
        assertTrue(SongQualityCapabilityStore.provenUnavailableOf(null).isEmpty())
        assertTrue(SongQualityCapabilityStore.provenUnavailableOf("").isEmpty())
    }

    @Test
    fun failureAndMissingStampRecordNothing() {
        // 解析失败/未回写档位不算证据：不能把一次抖动写成长期置灰
        SongQualityCapabilityStore.recordDowngrade(hash, null, KugouQuality.LOSSLESS)
        SongQualityCapabilityStore.recordDowngrade(hash, "not-a-tier", KugouQuality.LOSSLESS)
        SongQualityCapabilityStore.recordAvailable(hash, null)

        assertTrue(SongQualityCapabilityStore.provenUnavailableOf(hash).isEmpty())
    }

    @Test
    fun lookupIsCaseInsensitiveOnHash() {
        SongQualityCapabilityStore.recordDowngrade(hash.uppercase(), KugouQuality.HIRES, KugouQuality.STANDARD)

        assertTrue(
            KugouQuality.HIRES in SongQualityCapabilityStore.provenUnavailableOf(hash.lowercase())
        )
    }

    // ---------- 一次降级链 = 一次性标完 ----------

    @Test
    fun probeChainGreysEveryFailedTierAtOnce() {
        // 用户报的现象：这首歌真实上限 320K、偏好 Hi-Res。降级链会依次问
        // high→实得320、flac→实得320、320→命中。历史上只记"最终采用那一档"，无损就被
        // 留在"未知 = 可点"，用户点下去再吃一次亏、再开面板才看到它变灰。
        // 一趟探测已经得到的答案，不该要求用户用点击去换。
        SongQualityCapabilityStore.recordProbeChain(
            hash,
            listOf(
                KugouQuality.HIRES to KugouQuality.HIGH,
                KugouQuality.LOSSLESS to KugouQuality.HIGH,
                KugouQuality.HIGH to KugouQuality.HIGH
            )
        )

        assertEquals(
            setOf(KugouQuality.HIRES, KugouQuality.LOSSLESS),
            SongQualityCapabilityStore.provenUnavailableOf(hash)
        )
    }

    @Test
    fun probeChainRecordsNothingWithoutAStamp() {
        // 服务端未回写档位 = 不知道，不知道不能变成置灰
        SongQualityCapabilityStore.recordProbeChain(
            hash,
            listOf(KugouQuality.HIRES to null, KugouQuality.LOSSLESS to null)
        )

        assertTrue(SongQualityCapabilityStore.provenUnavailableOf(hash).isEmpty())
    }

    @Test
    fun probeChainClearsGreyWhenTheTierFinallyArrives() {
        SongQualityCapabilityStore.recordProbeChain(
            hash,
            listOf(
                KugouQuality.HIRES to KugouQuality.HIGH,
                KugouQuality.LOSSLESS to KugouQuality.HIGH
            )
        )

        // 音源上架/会员变化后真拿到无损：无损退灰，更高的 Hi-Res 保持置灰
        SongQualityCapabilityStore.recordProbeChain(
            hash,
            listOf(KugouQuality.LOSSLESS to KugouQuality.LOSSLESS)
        )

        val unavailable = SongQualityCapabilityStore.provenUnavailableOf(hash)
        assertFalse(KugouQuality.LOSSLESS in unavailable)
        assertTrue(KugouQuality.HIRES in unavailable)
    }

    // ---------- 播放即探测：只问缺证据的档，且从最高档开始 ----------

    @Test
    fun freshSongNeedsEveryTierProbedTopDown() {
        // 最高档优先不是实现方便，是信息量：一问就能定完四档
        assertEquals(
            listOf(KugouQuality.HIRES, KugouQuality.LOSSLESS, KugouQuality.HIGH, KugouQuality.STANDARD),
            SongQualityCapabilityStore.tiersNeedingProbe(hash)
        )
        assertTrue(SongQualityCapabilityStore.tiersNeedingProbe(null).isNotEmpty())
    }

    @Test
    fun oneTopAnswerSettlesTheWholeLadder() {
        // 请求 high 实得 flac：high 拿不到 + flac 及以下拿得到 ⇒ 四档全有结论，不必再问
        SongQualityCapabilityStore.recordProbeChain(
            hash,
            listOf(KugouQuality.HIRES to KugouQuality.LOSSLESS)
        )

        assertTrue(SongQualityCapabilityStore.tiersNeedingProbe(hash).isEmpty())
    }

    @Test
    fun middleGapStillNeedsOneProbe() {
        // 只证明过"请求 high 拿到 320"：中间的无损仍是未知，探测必须还愿意问它
        SongQualityCapabilityStore.recordDowngrade(hash, KugouQuality.HIRES, KugouQuality.HIGH)

        assertEquals(listOf(KugouQuality.LOSSLESS), SongQualityCapabilityStore.tiersNeedingProbe(hash))
    }

    @Test
    fun versionSignalsNewConclusionsOnly() {
        val before = SongQualityCapabilityStore.version.intValue
        SongQualityCapabilityStore.recordProbeChain(
            hash,
            listOf(KugouQuality.HIRES to KugouQuality.HIGH)
        )
        val after = SongQualityCapabilityStore.version.intValue
        assertTrue("新结论必须推动 UI 重组", after > before)

        // 服务端没回写档位 = 什么也没学到，不该惊动面板
        SongQualityCapabilityStore.recordProbeChain(
            "another-hash",
            listOf(KugouQuality.HIRES to null)
        )
        assertEquals(after, SongQualityCapabilityStore.version.intValue)
    }
}
