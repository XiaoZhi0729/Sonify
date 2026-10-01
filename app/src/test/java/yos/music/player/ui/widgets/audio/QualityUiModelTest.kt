package yos.music.player.ui.widgets.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.data.repositories.IntentSource
import yos.music.player.data.repositories.KugouQuality
import yos.music.player.data.repositories.NetworkKind
import yos.music.player.data.repositories.QualityFact
import yos.music.player.data.repositories.QualityVerdict

/**
 * 音质 UI 决策表（断言 A1/A3/A6/A7 的自动化护栏）。
 *
 * 每个"回归用例"都对应诊断报告里一个真实可感知的现象：
 * - [phantomHiResFromDowngradedSong]：现象二/三（勾选 Hi-Res 却把标记挪到无损行、
 *   二次播放显示 Hi-Res）
 * - [unknownQualityStringIsNeverRenderedAsStandard]：未知值被当成"标准 128K"显示
 *
 * 面板已收敛为"只有四档单选行"，因此这里只钉三件事：彽标读数是否诚实、
 * 勾选落在哪一行、以及哪些档位被证明不可用（置灰 + chip）。
 */
class QualityUiModelTest {

    private fun fact(
        intent: String,
        stamped: String?,
        observed: String? = null,
        verdictOverride: QualityVerdict? = null
    ) = (QualityFact.of(
        intentTier = intent,
        intentSource = IntentSource.WIFI_PREF,
        stampedTier = stamped,
        stampedRaw = stamped,
        observedTier = observed
    )).let { base ->
        verdictOverride?.let { base.copy(verdict = it) } ?: base
    }

    private fun build(
        intent: String,
        stamped: String?,
        overrideTier: String? = null,
        wifiTier: String = KugouQuality.LOSSLESS,
        mobileTier: String = KugouQuality.STANDARD,
        networkKind: NetworkKind = NetworkKind.UNMETERED,
        pendingTier: String? = null,
        isOnline: Boolean = true,
        observed: String? = null
    ) = QualityUiModel.build(
        isOnline = isOnline,
        fact = if (isOnline) fact(intent, stamped, observed) else null,
        pendingTier = pendingTier,
        overrideTier = overrideTier,
        wifiTier = wifiTier,
        mobileTier = mobileTier,
        networkKind = networkKind
    )

    private fun row(state: QualityUiState, tier: String) = state.sheet.rows.first { it.tier == tier }

    private fun selectedRow(state: QualityUiState) = state.sheet.rows.first { it.selected }

    // ---------- A1：读数只来自证据，意图永不冒充事实 ----------

    @Test
    fun phantomHiResFromDowngradedSong() {
        // 用户选了 Hi-Res，服务端只给到无损：读数必须是无损，且 Hi-Res 行被证明不可用
        val state = build(intent = KugouQuality.HIRES, stamped = KugouQuality.LOSSLESS)

        assertEquals(KugouQuality.LOSSLESS, state.capsule.tier)
        assertEquals(KugouQuality.HIRES, selectedRow(state).tier)
        assertFalse(row(state, KugouQuality.HIRES).enabled)
        assertNotNull(row(state, KugouQuality.HIRES).chipRes)
        assertTrue(row(state, KugouQuality.LOSSLESS).enabled)
        assertNull(row(state, KugouQuality.LOSSLESS).chipRes)
    }

    @Test
    fun capsuleNeverShowsIntentWithoutEvidence() {
        // 遍历：凡"意图高于实际"，胶囊绝不能等于意图
        for (intent in KugouQuality.ALL) {
            for (stamped in KugouQuality.ALL) {
                val state = build(intent = intent, stamped = stamped)
                val shown = state.capsule.tier
                assertTrue(
                    "intent=$intent stamped=$stamped 显示=$shown",
                    shown == stamped && shown != intent || stamped == intent
                )
            }
        }
    }

    @Test
    fun missingStampYieldsUnknownInsteadOfGuessing() {
        val state = build(intent = KugouQuality.HIRES, stamped = null)

        assertNull(state.capsule.tier)
        // 没有"降级"证据就不许置灰任何档位
        assertTrue(state.sheet.rows.all { it.enabled })
        assertTrue(state.sheet.rows.all { it.chipRes == null })
    }

    @Test
    fun unknownQualityStringIsNeverRenderedAsStandard() {
        // 诊断：rank(未知) = -1 会让下游把一切都渲染成"标准 128K"并宣称"本曲最高支持标准 128K"
        assertNull(KugouQuality.parse("flac-24bit-unknown"))
        assertNull(KugouQuality.parse(""))
        assertNull(KugouQuality.parse(null))
        assertEquals(KugouQuality.LOSSLESS, KugouQuality.parse(" FLAC "))
        assertEquals(KugouQuality.HIRES, KugouQuality.parse("hires"))
    }

    @Test
    fun keptHigherSourceIsNotReportedAsCeiling() {
        // 请求 320、实际沿用无损：不是"本曲最高"，也不该置灰任何东西
        val state = build(intent = KugouQuality.HIGH, stamped = KugouQuality.LOSSLESS)

        assertEquals(KugouQuality.LOSSLESS, state.capsule.tier)
        assertTrue(state.sheet.rows.all { it.enabled })
        assertTrue(state.sheet.rows.all { it.chipRes == null })
    }

    // ---------- 二、置灰只认"已证明拿不到" ----------

    @Test
    fun downgradeDisablesThatTierAndEverythingAboveIt() {
        // 请求无损只拿到 320 → 无损与 Hi-Res 都被证明拿不到（档位阶梯单调）
        val state = build(intent = KugouQuality.LOSSLESS, stamped = KugouQuality.HIGH)

        assertFalse(row(state, KugouQuality.LOSSLESS).enabled)
        assertFalse(row(state, KugouQuality.HIRES).enabled)
        assertTrue(row(state, KugouQuality.HIGH).enabled)
        assertTrue(row(state, KugouQuality.STANDARD).enabled)
        assertNotNull(row(state, KugouQuality.LOSSLESS).chipRes)
        assertNotNull(row(state, KugouQuality.HIRES).chipRes)
    }

    @Test
    fun confirmedFactDisablesNothing() {
        // 无损确认可用 ≠ 更高档不可用：没有证据就不置灰
        val state = build(intent = KugouQuality.LOSSLESS, stamped = KugouQuality.LOSSLESS)

        assertTrue(state.sheet.rows.all { it.enabled })
        assertTrue(state.sheet.rows.all { it.chipRes == null })
    }

    @Test
    fun failedSwitchKeepsOldReadingWithoutBlamingAnyTier() {
        val state = QualityUiModel.build(
            isOnline = true,
            fact = fact(
                KugouQuality.LOSSLESS, KugouQuality.LOSSLESS,
                verdictOverride = QualityVerdict.FAILED
            ),
            pendingTier = null,
            overrideTier = KugouQuality.LOSSLESS,
            wifiTier = KugouQuality.HIRES,
            mobileTier = KugouQuality.STANDARD,
            networkKind = NetworkKind.UNMETERED
        )

        assertEquals(KugouQuality.LOSSLESS, state.capsule.tier)
        assertFalse(state.capsule.dimmed)
        assertTrue(state.sheet.rows.all { it.enabled })
    }

    @Test
    fun capabilityKeepsTierGreyedAfterIntentChanges() {
        // 事实已变成"请求无损、拿到无损"（CONFIRMED），但能力表记得 Hi-Res 曾被拒：
        // 置灰不得随最后一次解析的意图漂移（旧行为：改点无损后 Hi-Res 又能点）
        val state = QualityUiModel.build(
            isOnline = true,
            fact = fact(KugouQuality.LOSSLESS, KugouQuality.LOSSLESS),
            pendingTier = null,
            overrideTier = KugouQuality.LOSSLESS,
            wifiTier = KugouQuality.LOSSLESS,
            mobileTier = KugouQuality.STANDARD,
            networkKind = NetworkKind.UNMETERED,
            provenUnavailableTiers = setOf(KugouQuality.HIRES)
        )

        assertFalse(row(state, KugouQuality.HIRES).enabled)
        assertNotNull(row(state, KugouQuality.HIRES).chipRes)
        assertTrue(row(state, KugouQuality.LOSSLESS).enabled)
        assertEquals(KugouQuality.LOSSLESS, state.capsule.tier)
    }

    @Test
    fun isProvenUnavailableHandlesMissingEvidence() {
        assertFalse(QualityUiModel.isProvenUnavailable(KugouQuality.HIRES, null))
        assertFalse(
            QualityUiModel.isProvenUnavailable(
                KugouQuality.HIRES,
                fact(KugouQuality.HIRES, KugouQuality.HIRES)
            )
        )
        assertTrue(
            QualityUiModel.isProvenUnavailable(
                KugouQuality.HIRES,
                fact(KugouQuality.HIRES, KugouQuality.LOSSLESS)
            )
        )
    }

    // ---------- A6：读数缺失也不让胶囊蒸发 ----------

    @Test
    fun loadingStateStillProducesACapsule() {
        val state = QualityUiModel.build(
            isOnline = true,
            fact = null,
            pendingTier = null,
            overrideTier = null,
            wifiTier = KugouQuality.LOSSLESS,
            mobileTier = KugouQuality.STANDARD,
            networkKind = NetworkKind.UNMETERED
        )

        assertNull(state.capsule.tier)
        assertFalse(state.capsule.dimmed)
        // 面板只有四档行，没有任何附加行
        assertEquals(4, state.sheet.rows.size)
    }

    @Test
    fun pendingSwitchKeepsPreviousReadingAndDims() {
        val state = build(
            intent = KugouQuality.HIRES,
            stamped = KugouQuality.LOSSLESS,
            pendingTier = KugouQuality.HIRES
        )

        assertEquals(KugouQuality.LOSSLESS, state.capsule.tier)
        assertTrue(state.capsule.dimmed)
    }

    // ---------- 意图仲裁：本曲覆盖 > 上次解析意图 > 偏好 ----------

    @Test
    fun perSongOverrideWinsOverPreference() {
        val state = build(
            intent = KugouQuality.HIRES,
            stamped = KugouQuality.HIRES,
            overrideTier = KugouQuality.HIRES,
            wifiTier = KugouQuality.STANDARD
        )

        assertEquals(KugouQuality.HIRES, selectedRow(state).tier)
    }

    @Test
    fun networkSwitchKeepsCurrentSongReading() {
        // 本曲是按 WiFi 档（无损）解析的；现在人在流量下，流量档=标准
        val state = build(
            intent = KugouQuality.LOSSLESS,
            stamped = KugouQuality.LOSSLESS,
            wifiTier = KugouQuality.LOSSLESS,
            mobileTier = KugouQuality.STANDARD,
            networkKind = NetworkKind.METERED
        )

        // 勾选停在"正在播"的那一档，不擅自改口（面板里没有解释性文字，勾就必须是对的）
        assertEquals(KugouQuality.LOSSLESS, selectedRow(state).tier)
    }

    // ---------- 本地曲：不显示在线档位菜单 ----------

    @Test
    fun localFileReadsFromMeasuredSpec() {
        val state = QualityUiModel.build(
            isOnline = false,
            fact = null,
            pendingTier = null,
            overrideTier = null,
            wifiTier = KugouQuality.STANDARD,
            mobileTier = KugouQuality.STANDARD,
            networkKind = NetworkKind.UNMETERED,
            localBitrateKbps = 2400,
            localSampleRateHz = 96000
        )

        assertEquals(KugouQuality.HIRES, state.capsule.tier)
        assertTrue(state.capsule.losslessBadge)
        assertNotNull(state.localSpec)
    }

    @Test
    fun localFileWithoutSpecShowsLoadingInsteadOfStaleValue() {
        val state = QualityUiModel.build(
            isOnline = false,
            fact = null,
            pendingTier = null,
            overrideTier = null,
            wifiTier = KugouQuality.STANDARD,
            mobileTier = KugouQuality.STANDARD,
            networkKind = NetworkKind.UNMETERED,
            localBitrateKbps = 0,
            localSampleRateHz = 0
        )

        assertNull(state.capsule.tier)
        assertNull(state.localSpec)
    }

    // ---------- 档位工具 ----------

    @Test
    fun specThresholdsMatchTiers() {
        assertEquals(KugouQuality.STANDARD, KugouQuality.tierFromSpec(128, 44100))
        assertEquals(KugouQuality.HIGH, KugouQuality.tierFromSpec(320, 44100))
        assertEquals(KugouQuality.LOSSLESS, KugouQuality.tierFromSpec(900, 44100))
        assertEquals(KugouQuality.HIRES, KugouQuality.tierFromSpec(2400, 96000))
        // FLAC 的码率实测常为 -1：不许拿它硬凑一个档位出来
        assertNull(KugouQuality.tierFromSpec(-1, 96000))
    }

    @Test
    fun downgradeChainAndRankStayConsistent() {
        assertEquals(
            listOf(KugouQuality.LOSSLESS, KugouQuality.HIGH, KugouQuality.STANDARD),
            KugouQuality.downgradeChain(KugouQuality.LOSSLESS)
        )
        assertTrue(KugouQuality.rank(KugouQuality.HIRES) > KugouQuality.rank(KugouQuality.LOSSLESS))
    }
}
