package yos.music.player.code.utils.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * LiveSort 智能无感过渡策略（核心数学与状态机）。
 *
 * 严格对齐 `D:\LiveSort-main\LiveSortApp\main.py` 与 `templates\index.html` 的过渡参数与曲线：
 * - 自适应切入点：[getAdaptiveMixEntrySeconds] 综合 BPM 差、动态响度差、尾段活跃度与前奏保护；
 * - 动态响度平衡：[getTransitionDynamicProfile] 按首尾能量 dB 差三档压制/提振，并限制叠加总量；
 * - 呼吸留白与下潜混响状态机：[getCrossfadeState] 输出各时刻主/副音量、下潜深度与混响湿度；
 * - 平滑效果释放：[getCrossfadeEffectMultiplier] 在新曲进入 6.0s~8.4s 间平滑褪去滤波与混响。
 */
object LiveSortTransitionPolicy {
    const val DEFAULT_CROSSFADE_SEC = 10f
    const val MIX_ENTRY_BEFORE_END_SEC = 3.0f
    const val EFFECT_START_BEFORE_END_SEC = 10.0f
    const val PRE_EFFECT_LEAD_MS = 0L
    const val EFFECT_RELEASE_START_INCOMING_SEC = 6.0f
    const val EFFECT_RELEASE_END_INCOMING_SEC = 8.4f
    const val EFFECT_MIN_MULTIPLIER = 0f
    const val EFFECT_ENTRY_SMOOTH_RATIO = 0.3f

    /** 用户可调的「音色与空间混响强度」档位倍率：0=柔和, 1=标准, 2=浓郁。 */
    fun userFxScaleForLevel(level: Int): Float = when (level) {
        0 -> 0.45f
        2 -> 1.12f
        else -> 0.78f
    }

    data class DynamicProfile(
        val incomingStartGain: Float,
        val incomingEndGain: Float,
        val outgoingShape: Float,
        val incomingMaxGain: Float,
        val totalMixCap: Float,
        val fxIntensity: Float,
    ) {
        companion object {
            val DEFAULT = DynamicProfile(
                incomingStartGain = 0.84f,
                incomingEndGain = 0.91f,
                outgoingShape = 1.05f,
                incomingMaxGain = 0.93f,
                totalMixCap = 0.94f,
                fxIntensity = 0.9f,
            )
        }
    }

    data class CrossfadeState(
        val outgoingVolume: Float,
        val incomingVolume: Float,
        val outgoingDepth: Float,
        val incomingDepth: Float,
        val outgoingReverb: Float,
        val incomingReverb: Float,
    )

    fun smoothStep(value: Float): Float {
        val safe = value.coerceIn(0f, 1f)
        return safe * safe * (3f - 2f * safe)
    }

    fun easeInOutCubic(value: Float): Float {
        val safe = value.coerceIn(0f, 1f)
        return if (safe < 0.5f) {
            4f * safe * safe * safe
        } else {
            1f - ((-2f * safe + 2f).toDouble().pow(3.0).toFloat() / 2f)
        }
    }

    fun getFxIntensityFromLoudnessDiff(loudnessDiffDb: Float): Float {
        val normalized = min(1f, abs(loudnessDiffDb) / 8.5f)
        return 0.78f + normalized * 0.54f
    }

    fun getTransitionDynamicProfile(
        outgoing: LiveSortTrackFeatures?,
        incoming: LiveSortTrackFeatures?,
    ): DynamicProfile {
        if (outgoing == null && incoming == null) return DynamicProfile.DEFAULT
        val outgoingEnergy = max(
            0.008f,
            outgoing?.let {
                positiveOrNull(it.endDynamicEnergy)
                    ?: positiveOrNull(it.end10sEnergy)
                    ?: positiveOrNull(it.endEnergy)
                    ?: positiveOrNull(it.energy)
            } ?: 0.06f,
        )
        val incomingEnergy = max(
            0.008f,
            incoming?.let {
                positiveOrNull(it.startDynamicEnergy)
                    ?: positiveOrNull(it.start10sEnergy)
                    ?: positiveOrNull(it.startEnergy)
                    ?: positiveOrNull(it.energy)
            } ?: 0.06f,
        )
        val loudnessDiffDb = (20.0 * log10((incomingEnergy / outgoingEnergy).toDouble())).toFloat()
        val baseFxIntensity = getFxIntensityFromLoudnessDiff(loudnessDiffDb)

        if (loudnessDiffDb >= 1.5f) {
            val attenuationDb = min(10.0f, (loudnessDiffDb - 1.5f) * 0.92f)
            val incomingStartGain = (10.0.pow((-attenuationDb / 20.0))).toFloat().coerceIn(0.52f, 0.9f)
            val incomingEndGain = max(incomingStartGain, 0.92f)
            return DynamicProfile(
                incomingStartGain = incomingStartGain,
                incomingEndGain = incomingEndGain,
                outgoingShape = 1.07f,
                incomingMaxGain = 0.90f,
                totalMixCap = 0.92f,
                fxIntensity = min(1.4f, baseFxIntensity * 1.06f),
            )
        }

        if (loudnessDiffDb <= -1.5f) {
            val boost = min(0.06f, (-1.5f - loudnessDiffDb) * 0.010f)
            return DynamicProfile(
                incomingStartGain = 0.86f + boost * 0.5f,
                incomingEndGain = 0.92f + boost,
                outgoingShape = 1.03f,
                incomingMaxGain = 0.95f,
                totalMixCap = 0.96f,
                fxIntensity = min(1.4f, baseFxIntensity * 1.05f),
            )
        }

        val neutralTrim = min(0.05f, abs(loudnessDiffDb) * 0.012f)
        return DynamicProfile(
            incomingStartGain = 0.84f - neutralTrim * 0.35f,
            incomingEndGain = 0.91f - neutralTrim,
            outgoingShape = 1.05f,
            incomingMaxGain = 0.93f,
            totalMixCap = 0.94f,
            fxIntensity = max(0.74f, baseFxIntensity * 0.92f),
        )
    }

    fun getMixLeadSeconds(outgoing: LiveSortTrackFeatures?): Float {
        val lead = outgoing?.mixLeadSec?.takeIf { it.isFinite() && it > 0f } ?: DEFAULT_CROSSFADE_SEC
        return lead.coerceIn(7.4f, 10.2f)
    }

    fun getMixBreathSeconds(outgoing: LiveSortTrackFeatures?): Float {
        val breath = outgoing?.mixBreathSec?.takeIf { it.isFinite() && it > 0f } ?: 2.0f
        return breath.coerceIn(1.4f, 3.4f)
    }

    fun getMixDurationSeconds(outgoing: LiveSortTrackFeatures?): Float {
        val mixLead = getMixLeadSeconds(outgoing)
        return (mixLead + 0.05f).coerceIn(9.0f, 10.2f)
    }

    fun getBreathRatio(outgoing: LiveSortTrackFeatures?, durationSec: Float): Float {
        val mixBreath = getMixBreathSeconds(outgoing)
        return (mixBreath / max(durationSec, 1e-6f)).coerceIn(0.2f, 0.46f)
    }

    fun getInvalidTailSeconds(outgoing: LiveSortTrackFeatures?): Float {
        val invalidTail = outgoing?.invalidTailSec?.takeIf { it.isFinite() && it >= 0f } ?: 0f
        return invalidTail.coerceIn(0f, 40f)
    }

    fun getEffectStartSeconds(outgoing: LiveSortTrackFeatures?): Float {
        val dynamicStart = outgoing?.mixEffectStartSec?.takeIf { it.isFinite() && it > 0f }
        if (dynamicStart != null) return dynamicStart.coerceIn(8f, 50f)
        return (EFFECT_START_BEFORE_END_SEC + getInvalidTailSeconds(outgoing)).coerceIn(8f, 50f)
    }

    fun getMixEntrySeconds(outgoing: LiveSortTrackFeatures?): Float {
        if (outgoing == null) return MIX_ENTRY_BEFORE_END_SEC
        val dynamicEntry = outgoing.mixEntrySec.takeIf { it.isFinite() && it > 0f }
        if (dynamicEntry != null) return dynamicEntry.coerceIn(4f, 44f)
        return (MIX_ENTRY_BEFORE_END_SEC + getInvalidTailSeconds(outgoing)).coerceIn(4f, 44f)
    }

    fun getAdaptiveMixEntrySeconds(
        outgoing: LiveSortTrackFeatures?,
        incoming: LiveSortTrackFeatures?,
    ): Float {
        val baseEntry = getMixEntrySeconds(outgoing)
        if (outgoing == null || incoming == null) return baseEntry

        val outgoingEndBpm = positiveOrNull(outgoing.endBpm) ?: positiveOrNull(outgoing.bpm) ?: 120f
        val incomingStartBpm = positiveOrNull(incoming.startBpm) ?: positiveOrNull(incoming.bpm) ?: 120f
        val bpmGap = abs(incomingStartBpm - outgoingEndBpm)
        val bpmDifficulty = min(1f, bpmGap / 36f)

        val outgoingEnergy = max(
            0.008f,
            positiveOrNull(outgoing.endDynamicEnergy)
                ?: positiveOrNull(outgoing.end10sEnergy)
                ?: positiveOrNull(outgoing.endEnergy)
                ?: positiveOrNull(outgoing.energy)
                ?: 0.06f,
        )
        val incomingEnergy = max(
            0.008f,
            positiveOrNull(incoming.startDynamicEnergy)
                ?: positiveOrNull(incoming.start10sEnergy)
                ?: positiveOrNull(incoming.startEnergy)
                ?: positiveOrNull(incoming.energy)
                ?: 0.06f,
        )
        val loudnessDiffDb = abs((20.0 * log10((incomingEnergy / outgoingEnergy).toDouble())).toFloat())
        val loudnessDifficulty = min(1f, loudnessDiffDb / 8.5f)

        val endActivityRatio = outgoing.endActivityRatio.coerceIn(0f, 1f)
        val tailWeakDifficulty = ((0.55f - endActivityRatio) / 0.55f).coerceIn(0f, 1f)

        val incomingBaseEnergy = max(0.008f, positiveOrNull(incoming.energy) ?: 0.06f)
        val incomingIntroEnergy = max(
            0.008f,
            positiveOrNull(incoming.startDynamicEnergy)
                ?: positiveOrNull(incoming.start10sEnergy)
                ?: positiveOrNull(incoming.startEnergy)
                ?: incomingBaseEnergy,
        )
        val introEnergyRatio = incomingIntroEnergy / incomingBaseEnergy
        val introEnergyProtect = ((introEnergyRatio - 0.9f) / 0.9f).coerceIn(0f, 1f)
        val introBrightnessProtect = incoming.brightnessNorm.coerceIn(0f, 1f)
        val introProtect = introEnergyProtect * 0.65f + introBrightnessProtect * 0.35f

        val bridgeNeed = bpmDifficulty * 0.42f + loudnessDifficulty * 0.38f + tailWeakDifficulty * 0.2f
        val adaptiveOffset = bridgeNeed * 1.48f - introProtect * 2.25f - 1.2f
        val effectStartCap = max(2.8f, getEffectStartSeconds(outgoing) - 0.9f)
        return min(44f, min(effectStartCap, max(2.8f, baseEntry + adaptiveOffset)))
    }

    /**
     * 计算智能过渡的切入窗口（毫秒）。
     *
     * 为避免极短曲目（如间奏/铃声）被过渡吞没，仍遵循 [CrossfadePolicy.WINDOW_MAX_RATIO]
     * （最多占当前曲总长的 1/3）与 [CrossfadePolicy.WINDOW_MIN_MS] 下限保护；
     * 若当前曲尾部检测出长静音段（`invalidTailSec`），允许切入点最多扩展至总长的 1/2
     * 以便跳过冗长空白。
     */
    fun smartWindowMs(
        durationMs: Long,
        outgoing: LiveSortTrackFeatures?,
        incoming: LiveSortTrackFeatures?,
    ): Long? {
        if (durationMs <= 0L) return null
        val entrySec = getAdaptiveMixEntrySeconds(outgoing, incoming)
        val wantMs = (entrySec * 1000f).toLong()
        val maxRatio = if ((outgoing?.invalidTailSec ?: 0f) >= 6f) 0.5f else CrossfadePolicy.WINDOW_MAX_RATIO
        val cap = (durationMs * maxRatio).toLong()
        return min(wantMs, cap).takeIf { it >= CrossfadePolicy.WINDOW_MIN_MS }
    }

    /** 双播放器同时出声的物理重叠时长（受当前曲剩余时长上限约束）。 */
    fun smartOverlapMs(remainingWallMs: Long, outgoing: LiveSortTrackFeatures?): Long {
        val fullMixMs = (getMixDurationSeconds(outgoing) * 1000f).toLong()
        return min(remainingWallMs, fullMixMs).coerceAtLeast(CrossfadePolicy.MIN_RAMP_MS)
    }

    /** LiveSort 完整过渡时间轴时长（9.0s~10.2s）。 */
    fun smartFullMixDurationMs(outgoing: LiveSortTrackFeatures?): Long =
        (getMixDurationSeconds(outgoing) * 1000f).toLong()

    fun getCrossfadeEffectMultiplier(
        incomingTimeSec: Float,
        overlapDurationSec: Float = DEFAULT_CROSSFADE_SEC,
    ): Float {
        val safeIncomingTime = max(0f, incomingTimeSec)
        val safeOverlap = max(1.5f, overlapDurationSec)
        val releaseStart = if (safeOverlap >= 9.0f) {
            EFFECT_RELEASE_START_INCOMING_SEC
        } else {
            min(EFFECT_RELEASE_START_INCOMING_SEC, safeOverlap * 0.58f)
        }
        val releaseEnd = if (safeOverlap >= 9.0f) {
            EFFECT_RELEASE_END_INCOMING_SEC
        } else {
            min(EFFECT_RELEASE_END_INCOMING_SEC, safeOverlap * 0.94f)
                .coerceAtLeast(releaseStart + 0.35f)
        }
        if (safeIncomingTime < releaseStart) return 1f
        if (safeIncomingTime >= releaseEnd) return EFFECT_MIN_MULTIPLIER
        val releaseRatio = (safeIncomingTime - releaseStart) / (releaseEnd - releaseStart)
        val smoothRelease = easeInOutCubic(releaseRatio)
        val softTailRelease = 1f - (1f - releaseRatio).toDouble().pow(2.6).toFloat()
        val releaseCurve = smoothRelease * 0.45f + softTailRelease * 0.55f
        return 1f - (1f - EFFECT_MIN_MULTIPLIER) * releaseCurve
    }

    /**
     * 对齐 LiveSort 原版 `applyPreCrossfadeEffect`：切入前不提前变闷或加混响，保持原声清晰。
     */
    @Suppress("UNUSED_PARAMETER")
    fun getPreCrossfadeOutgoingEffects(
        remainingMs: Long,
        windowMs: Long,
        dynamicProfile: DynamicProfile? = null,
        userFxScale: Float = 0.78f,
    ): Pair<Float, Float> = 0f to 0f

    /**
     * 计算过渡状态快照。
     *
     * 对齐 LiveSort 原版 `entryEnhance` 零点柔化包络：过渡开始瞬间双方均从零效果
     * 平滑切入，经由短时轻柔高频柔化与空气感混响自然融合，并在叠加末段平滑归零。
     */
    fun getCrossfadeState(
        ratio: Float,
        baseVol: Float = 1f,
        breathRatio: Float = 0.2f,
        dynamicProfile: DynamicProfile? = null,
        effectMultiplier: Float = 1f,
        outgoingRatio: Float = ratio,
        incomingRatio: Float = ratio,
        userFxScale: Float = 0.78f,
    ): CrossfadeState {
        val safeOutRatio = outgoingRatio.coerceIn(0f, 1f)
        val safeInTimelineRatio = incomingRatio.coerceIn(0f, 1f)
        val safeBreath = breathRatio.coerceIn(0.2f, 0.46f)
        val inRatio = if (safeInTimelineRatio <= safeBreath) {
            0f
        } else {
            (safeInTimelineRatio - safeBreath) / (1f - safeBreath)
        }
        val safeEffect = effectMultiplier.coerceIn(0f, 1f)
        val safeUserScale = userFxScale.coerceIn(0.3f, 1.5f)

        val entryEnhance = easeInOutCubic(min(1f, safeOutRatio / EFFECT_ENTRY_SMOOTH_RATIO))

        val fadeOutCore = cos(safeOutRatio * 0.5 * PI).toFloat()
        val fadeOutDuck = 1f - (0.14f * safeOutRatio) - (0.12f * safeOutRatio.toDouble().pow(2.2).toFloat())

        val outgoingDepthRaw = min(1f, 0.28f + safeOutRatio.toDouble().pow(1.16).toFloat())
        val incomingDepthLinear = max(0.14f, 0.86f - 0.68f * inRatio)
        val incomingDepthCurve = max(0.14f, 0.86f - 0.72f * inRatio.toDouble().pow(0.68).toFloat())
        val incomingDepthRaw = incomingDepthLinear * 0.42f + incomingDepthCurve * 0.58f

        val outgoingReverbRaw = 0.10f + safeOutRatio.toDouble().pow(1.2).toFloat() * 0.26f
        val incomingReverbRaw = max(0.10f, 0.30f - inRatio * 0.17f)

        val profile = dynamicProfile ?: DynamicProfile.DEFAULT
        val fxIntensity = profile.fxIntensity.coerceIn(0.72f, 1.4f)
        val fxDepthScale = (0.72f + fxIntensity * 0.42f) * safeUserScale
        val fxReverbScale = (0.7f + fxIntensity * 0.5f) * safeUserScale

        val depthEntryFloorCurve = entryEnhance.toDouble().pow(1.28).toFloat()
        val outgoingDepthShaped = outgoingDepthRaw * 0.82f + (0.74f * depthEntryFloorCurve) * 0.18f
        val incomingDepthShaped = incomingDepthRaw * 0.84f + (0.76f * depthEntryFloorCurve) * 0.16f
        val outgoingDepth = min(1f, min(1f, outgoingDepthShaped * safeEffect * fxDepthScale) * entryEnhance)
        val incomingDepth = min(1f, min(1f, incomingDepthShaped * safeEffect * fxDepthScale) * entryEnhance)

        val outgoingReverbTarget = min(
            0.40f,
            max(outgoingReverbRaw, 0.30f * entryEnhance) * safeEffect * fxReverbScale,
        )
        val incomingReverbTarget = min(
            0.40f,
            max(incomingReverbRaw, 0.30f * entryEnhance) * safeEffect * fxReverbScale,
        )
        val outgoingReverb = min(0.24f, outgoingReverbTarget * entryEnhance)
        val incomingReverb = min(0.24f, incomingReverbTarget * entryEnhance)

        val incomingGain = profile.incomingStartGain +
            (profile.incomingEndGain - profile.incomingStartGain) * inRatio.toDouble().pow(0.9).toFloat()
        val shapedOutgoing = max(0f, fadeOutCore * max(0f, fadeOutDuck))
            .toDouble().pow(profile.outgoingShape.coerceAtLeast(0.8f).toDouble()).toFloat()

        val outgoingVolumeRaw = min(1f, baseVol * shapedOutgoing)
        val incomingVolumeRaw = min(1f, baseVol * incomingGain)
        val incomingMaxVolume = min(1f, baseVol * profile.incomingMaxGain.coerceIn(0.72f, 1.05f))
        val totalMixCapVolume = min(1f, baseVol * profile.totalMixCap.coerceIn(0.84f, 1.25f))

        val outgoingVolume = outgoingVolumeRaw.coerceIn(0f, 1f)
        val incomingVolumeCapped = min(incomingVolumeRaw, incomingMaxVolume)
        val incomingVolume = max(0f, min(incomingVolumeCapped, totalMixCapVolume - outgoingVolume))

        return CrossfadeState(
            outgoingVolume = outgoingVolume,
            incomingVolume = incomingVolume,
            outgoingDepth = outgoingDepth.coerceIn(0f, 1f),
            incomingDepth = incomingDepth.coerceIn(0f, 1f),
            outgoingReverb = outgoingReverb.coerceIn(0f, 0.24f),
            incomingReverb = incomingReverb.coerceIn(0f, 0.24f),
        )
    }

    private fun positiveOrNull(value: Float): Float? =
        value.takeIf { it.isFinite() && it > 0f }
}
