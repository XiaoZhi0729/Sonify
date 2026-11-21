package yos.music.player.code.utils.player

import com.tencent.mmkv.MMKV
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * LiveSort 歌曲过渡特征快照。
 *
 * 字段语义与 `LiveSortApp/audio_analyzer.py` 及 `templates/index.html` 严格对齐，
 * 包含整体能量/亮度、首尾 15s BPM 与动态能量、以及基于尾段扫描窗（40s）识别出的
 * 静音尾段 [tailSilenceSec]、无效尾段 [invalidTailSec] 与建议过渡窗口参数。
 */
data class LiveSortTrackFeatures(
    val mediaId: String,
    val durationSec: Float = 0f,
    val bpm: Float = 120f,
    val energy: Float = 0.06f,
    val brightness: Float = 1500f,
    val brightnessNorm: Float = 0.5f,
    val startBpm: Float = 120f,
    val endBpm: Float = 120f,
    val startEnergy: Float = 0.06f,
    val endEnergy: Float = 0.06f,
    val start10sEnergy: Float = 0.06f,
    val end10sEnergy: Float = 0.06f,
    val startDynamicEnergy: Float = 0.06f,
    val endDynamicEnergy: Float = 0.06f,
    val dynamicWindowSec: Float = 10f,
    val tailSilenceSec: Float = 0f,
    val invalidTailSec: Float = 0f,
    val endActivityRatio: Float = 1f,
    val mixLeadSec: Float = 9.1f,
    val mixBreathSec: Float = 1.75f,
    val mixEffectStartSec: Float = 10.0f,
    val mixEntrySec: Float = 4.0f,
    /** true 表示已通过全曲首尾完整分析（含尾部静音扫描）；false 表示仅为播放期实时流式采样的半程数据。 */
    val hasTailAnalysis: Boolean = false,
) {
    fun encodeCompact(): String = listOf(
        durationSec,
        bpm,
        energy,
        brightness,
        brightnessNorm,
        startBpm,
        endBpm,
        startEnergy,
        endEnergy,
        start10sEnergy,
        end10sEnergy,
        startDynamicEnergy,
        endDynamicEnergy,
        dynamicWindowSec,
        tailSilenceSec,
        invalidTailSec,
        endActivityRatio,
        mixLeadSec,
        mixBreathSec,
        mixEffectStartSec,
        mixEntrySec,
        if (hasTailAnalysis) 1f else 0f,
    ).joinToString("|")

    companion object {
        fun decodeCompact(mediaId: String, raw: String?): LiveSortTrackFeatures? {
            if (raw.isNullOrBlank()) return null
            val parts = raw.split('|')
            if (parts.size < 22) return null
            val floats = FloatArray(22)
            for (i in 0 until 22) {
                val parsed = parts[i].toFloatOrNull() ?: return null
                if (!parsed.isFinite()) return null
                floats[i] = parsed
            }
            return LiveSortTrackFeatures(
                mediaId = mediaId,
                durationSec = floats[0],
                bpm = floats[1],
                energy = floats[2],
                brightness = floats[3],
                brightnessNorm = floats[4],
                startBpm = floats[5],
                endBpm = floats[6],
                startEnergy = floats[7],
                endEnergy = floats[8],
                start10sEnergy = floats[9],
                end10sEnergy = floats[10],
                startDynamicEnergy = floats[11],
                endDynamicEnergy = floats[12],
                dynamicWindowSec = floats[13],
                tailSilenceSec = floats[14],
                invalidTailSec = floats[15],
                endActivityRatio = floats[16],
                mixLeadSec = floats[17],
                mixBreathSec = floats[18],
                mixEffectStartSec = floats[19],
                mixEntrySec = floats[20],
                hasTailAnalysis = floats[21] >= 0.5f,
            )
        }
    }
}

/**
 * LiveSort 音频特征双层缓存（进程内存 + MMKV 磁盘持久化）。
 *
 * 在纯 JVM 单测环境下 MMKV 未初始化时自动降级为纯内存表，不影响测试执行。
 */
object LiveSortFeatureStore {
    private const val MMKV_ID = "yos_livesort_features_v2"
    private const val MAX_MEMORY_ITEMS = 512

    private val memory = ConcurrentHashMap<String, LiveSortTrackFeatures>()
    private val mmkv: MMKV? by lazy {
        runCatching { MMKV.mmkvWithID(MMKV_ID) }.getOrNull()
    }

    fun get(mediaId: String?): LiveSortTrackFeatures? {
        if (mediaId.isNullOrBlank()) return null
        memory[mediaId]?.let { return it }
        val encoded = runCatching { mmkv?.decodeString(mediaId, null) }.getOrNull()
        val decoded = LiveSortTrackFeatures.decodeCompact(mediaId, encoded) ?: return null
        putInMemory(decoded)
        return decoded
    }

    fun put(features: LiveSortTrackFeatures) {
        if (features.mediaId.isBlank()) return
        putInMemory(features)
        if (features.hasTailAnalysis) {
            runCatching { mmkv?.encode(features.mediaId, features.encodeCompact()) }
        }
    }

    /**
     * 实时流式播放补充曲首 15s 特征（当后台未完成离线分析时，用于在线歌曲开头动态平衡）。
     * 若该曲目已有完整尾部分析结果，则只保留完整结果不被降级覆盖。
     */
    fun updateStreamingStartFeatures(
        mediaId: String?,
        durationSec: Float,
        startBpm: Float,
        startEnergy: Float,
        start10sEnergy: Float,
        brightness: Float,
    ) {
        if (mediaId.isNullOrBlank()) return
        val existing = get(mediaId)
        if (existing != null && existing.hasTailAnalysis) return
        val safeBpm = if (startBpm > 0f) startBpm else (existing?.bpm ?: 120f)
        val safeEnergy = max(0.004f, startEnergy)
        val safeStart10s = max(0.004f, start10sEnergy)
        val safeBrightness = if (brightness > 0f) brightness else (existing?.brightness ?: 1500f)
        val brightnessNorm = (safeBrightness / 3000f).coerceIn(0f, 1f)
        val updated = (existing ?: LiveSortTrackFeatures(mediaId = mediaId)).copy(
            durationSec = if (durationSec > 0f) durationSec else (existing?.durationSec ?: 0f),
            bpm = if (existing != null && existing.bpm > 0f) existing.bpm else safeBpm,
            energy = if (existing != null && existing.energy > 0.008f) existing.energy else safeEnergy,
            brightness = safeBrightness,
            brightnessNorm = brightnessNorm,
            startBpm = safeBpm,
            startEnergy = safeEnergy,
            start10sEnergy = safeStart10s,
            startDynamicEnergy = safeStart10s,
        )
        putInMemory(updated)
    }

    /**
     * 实时流式播放补充曲尾滑窗特征（用于在线流未整首缓存时动态计算切入点与响度差）。
     */
    fun updateStreamingEndFeatures(
        mediaId: String?,
        endBpm: Float,
        endEnergy: Float,
        end10sEnergy: Float,
        endDynamicEnergy: Float,
        tailSilenceSec: Float,
        invalidTailSec: Float,
        endActivityRatio: Float,
    ) {
        if (mediaId.isNullOrBlank()) return
        val existing = get(mediaId)
        if (existing != null && existing.hasTailAnalysis) return
        val safeEndBpm = if (endBpm > 0f) endBpm else (existing?.endBpm ?: 120f)
        val mixLeadSec = (9.1f + tailSilenceSec * 0.62f).coerceIn(7.4f, 13.2f)
        val mixBreathSec = (1.75f + tailSilenceSec * 0.22f).coerceIn(1.35f, 3.2f)
        val mixEffectStartSec = (10.0f + invalidTailSec).coerceIn(8.0f, 50.0f)
        val mixEntrySec = (4.0f + invalidTailSec).coerceIn(4.0f, 44.0f)
        val dynamicWindowSec = (10.0f + invalidTailSec * 0.55f).coerceIn(8.0f, 24.0f)

        val updated = (existing ?: LiveSortTrackFeatures(mediaId = mediaId)).copy(
            endBpm = safeEndBpm,
            endEnergy = max(0.004f, endEnergy),
            end10sEnergy = max(0.004f, end10sEnergy),
            endDynamicEnergy = max(0.004f, endDynamicEnergy),
            dynamicWindowSec = dynamicWindowSec,
            tailSilenceSec = tailSilenceSec.coerceAtLeast(0f),
            invalidTailSec = invalidTailSec.coerceIn(0f, 40f),
            endActivityRatio = endActivityRatio.coerceIn(0f, 1f),
            mixLeadSec = mixLeadSec,
            mixBreathSec = mixBreathSec,
            mixEffectStartSec = mixEffectStartSec,
            mixEntrySec = mixEntrySec,
        )
        putInMemory(updated)
    }

    fun clear() {
        memory.clear()
    }

    private fun putInMemory(features: LiveSortTrackFeatures) {
        if (memory.size >= MAX_MEMORY_ITEMS && !memory.containsKey(features.mediaId)) {
            val firstKey = memory.keys.firstOrNull()
            if (firstKey != null) memory.remove(firstKey)
        }
        memory[features.mediaId] = features
    }
}
