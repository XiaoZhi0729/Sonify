package yos.music.player.ui.widgets.audio

import androidx.annotation.StringRes
import yos.music.player.R
import yos.music.player.data.repositories.KugouQuality
import yos.music.player.data.repositories.NetworkKind
import yos.music.player.data.repositories.QualityFact
import yos.music.player.data.repositories.QualityTrace
import yos.music.player.data.repositories.QualityVerdict

/**
 * 音质 UI 决策（纯函数，可表格驱动单测——断言 A7）。
 *
 * 存在的理由：历史上这些判断散在 Composable 里，`hint` 用 `else` 兜底，于是
 * "凡实际低于偏好皆称本曲上限"，把网络抖动、粘性复用、失败兜底统统说成能力上限。
 * 现在全部判断收进 [QualityUiModel.build]，Composable 只渲染结果，不自行推断语义。
 *
 * 面板形态（按产品要求收敛）：**只有四档单选行**。分区标题、结论行、生效档署名、
 * 偏好摘要等说明文字一律不再渲染——可点与不可点靠"置灰 + 行尾 chip"表达，
 * 不靠额外文字解释。徽标侧的诚实读数与降级提示 toast 保持不变。
 */

/** 徽标文本状态：`tier == null` 时用 [fallbackRes]，**绝不允许整块不渲染**（断言 A6）。 */
data class QualityCapsuleUi(
    val tier: String?,
    @StringRes val fallbackRes: Int,
    val losslessBadge: Boolean,
    /** 切换进行中：保留上一读数并压暗，而不是清空成空窗（闪烁根因）。 */
    val dimmed: Boolean
)

data class QualityRowUi(
    val tier: String,
    /** 勾选 = 当前生效的**意图**（本曲覆盖优先，否则按网络取偏好）。 */
    val selected: Boolean,
    /** 仅当**已证明**本曲拿不到这一档才置灰（[QualityUiModel.isProvenUnavailable]）。 */
    val enabled: Boolean,
    /** 行尾 chip：贴在"已证明拿不到"的那一行上；能力未知时不出现。 */
    @StringRes val chipRes: Int?
)

data class QualityLocalSpecUi(
    val bitrateKbps: Int,
    val sampleRateHz: Int,
    val tier: String?
)

/** 在线档位单选面板内容：只有四行，没有别的文字。 */
data class QualitySheetUi(
    val rows: List<QualityRowUi>
)

data class QualityUiState(
    val isOnline: Boolean,
    val capsule: QualityCapsuleUi,
    val sheet: QualitySheetUi,
    val localSpec: QualityLocalSpecUi?
)

object QualityUiModel {

    fun build(
        isOnline: Boolean,
        fact: QualityFact?,
        pendingTier: String?,
        overrideTier: String?,
        wifiTier: String,
        mobileTier: String,
        networkKind: NetworkKind,
        localBitrateKbps: Int = 0,
        localSampleRateHz: Int = 0,
        /** 能力表（[yos.music.player.data.repositories.SongQualityCapabilityStore]）给出的已证明不可用集合 */
        provenUnavailableTiers: Set<String> = emptySet()
    ): QualityUiState {
        val capsule: QualityCapsuleUi
        val localTier: String?
        if (!isOnline) {
            localTier = KugouQuality.tierFromSpec(localBitrateKbps, localSampleRateHz)
            capsule = if (localTier != null) {
                QualityCapsuleUi(localTier, R.string.quality_capsule_unknown, localTier.isHighTier(), false)
            } else {
                // 规格还没测出来：显示兜底而不是拿上一首的码率凑一个读数
                QualityCapsuleUi(null, R.string.quality_capsule_loading, false, false)
            }
        } else {
            localTier = null
            val playing = fact?.playingTier
            capsule = when {
                // 有证据 → 显示证据；无证据（未解析）→ 兜底文案，不显示猜测值
                playing != null -> QualityCapsuleUi(
                    tier = playing,
                    fallbackRes = R.string.quality_capsule_unknown,
                    losslessBadge = playing.isHighTier(),
                    dimmed = pendingTier != null
                )

                fact != null && fact.verdict == QualityVerdict.UNKNOWN ->
                    QualityCapsuleUi(null, R.string.quality_capsule_unknown, false, pendingTier != null)

                else -> QualityCapsuleUi(null, R.string.quality_capsule_loading, false, false)
            }
        }
        QualityTrace.checkHonestDisplay("capsule", capsule.tier, fact)

        // 生效意图：本曲覆盖 > 上次解析记录的意图 > 按网络取偏好
        val intentTier = overrideTier ?: fact?.intentTier ?: when (networkKind) {
            NetworkKind.METERED -> mobileTier
            else -> wifiTier
        }

        val rows = KugouQuality.ALL.map { tier ->
            val provenUnavailable =
                isProvenUnavailable(tier, fact, provenUnavailableTiers)
            QualityRowUi(
                tier = tier,
                selected = tier == intentTier,
                enabled = !provenUnavailable,
                chipRes = if (provenUnavailable) R.string.quality_row_chip_no_source else null
            )
        }

        return QualityUiState(
            isOnline = isOnline,
            capsule = capsule,
            localSpec = if (!isOnline && (localBitrateKbps > 0 || localSampleRateHz > 0)) {
                QualityLocalSpecUi(localBitrateKbps, localSampleRateHz, localTier)
            } else {
                null
            },
            sheet = QualitySheetUi(rows = rows)
        )
    }

    /**
     * 这一档是否已被证明拿不到。
     *
     * 首选能力表（[yos.music.player.data.repositories.SongQualityCapabilityStore]：按档位
     * 累积、跳多次解析与重启保留）；未命中时退回"最后一次解析被降级"这条现用证据。
     * 两套规则同源：[QualityVerdict.DOWNGRADED]（请求 A 只拿到 O）意味着 A 及以上拿不到、
     * O 及以下拿得到（酷狗档位阶梯单调），介于中间的档位无直接证据 → 保持可点。
     *
     * CONFIRMED / KEPT_HIGHER / UNKNOWN / FAILED 不产生"不可用"证据——能力未知时
     * 一律不禁用（宁可点了才知道，也不假装知道）。
     */
    fun isProvenUnavailable(
        tier: String,
        fact: QualityFact?,
        provenUnavailableTiers: Set<String> = emptySet()
    ): Boolean {
        if (tier in provenUnavailableTiers) return true
        if (fact == null || fact.verdict != QualityVerdict.DOWNGRADED) return false
        val askedRank = KugouQuality.rankOrNull(fact.intentTier) ?: return false
        val thisRank = KugouQuality.rankOrNull(tier) ?: return false
        return thisRank >= askedRank
    }

    /** 无损角标只在无损及以上档位出现（历史上未知/未知值也参与判定，会出现文字与角标不符）。 */
    private fun String.isHighTier(): Boolean =
        this == KugouQuality.LOSSLESS || this == KugouQuality.HIRES
}
