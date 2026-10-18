package yos.music.player.data.recognition

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * 听歌识曲链路的 PCM 前处理（纯 Kotlin、无 Android 依赖，可直接跑 JVM 单测）。
 *
 * 与参照实现 md3Music 的 Dart 兜底版 `recognition_utils.dart` 逐行对齐：
 * 均值抗混叠降采样 → 静音判定 → 去 DC + 增益归一化。
 * 这些参数的取值直接影响酷狗指纹的命中率，改动前先看 [RecognitionConfig] 的注释。
 */
object PcmProcessor {

    /** 读一个 16bit 小端采样，符号扩展成 Int。 */
    fun sampleAt(pcm: ByteArray, sampleIndex: Int): Int {
        val byteIndex = sampleIndex * 2
        val lo = pcm[byteIndex].toInt() and 0xFF
        val hi = pcm[byteIndex + 1].toInt()
        return (lo or (hi shl 8)).toShort().toInt()
    }

    private fun writeSample(out: ByteArray, sampleIndex: Int, value: Int) {
        val v = value.coerceIn(-32768, 32767)
        out[sampleIndex * 2] = (v and 0xFF).toByte()
        out[sampleIndex * 2 + 1] = ((v shr 8) and 0xFF).toByte()
    }

    /** PCM 最大振幅（静音检测用）。 */
    fun computeMaxAmplitude(pcm: ByteArray): Int {
        var maxAmplitude = 0
        var i = 0
        val limit = pcm.size - 1
        while (i < limit) {
            val sample = sampleAt(pcm, i / 2)
            val abs = abs(sample)
            if (abs > maxAmplitude) maxAmplitude = abs
            i += 2
        }
        return maxAmplitude
    }

    /**
     * 均值抗混叠降采样（低通），防止高于 toHz/2 的频率混叠。
     *
     * 44100 → 8000 时 ratio≈5.51 → 窗口=6、半窗=3，每个输出点平均 7 个输入点。
     */
    fun downsample(input: ByteArray, fromHz: Int, toHz: Int): ByteArray {
        if (fromHz == toHz) return input
        val inputSamples = input.size / 2
        if (inputSamples == 0) return ByteArray(0)

        val ratio = fromHz.toDouble() / toHz.toDouble()
        // 必须用 Double 算：8 秒 44.1kHz 有 352800 个采样，352800 * 8000 会撑爆 Int
        // （2.82e9 > Int.MAX），溢出成负数后输出长度算成 0，整段 PCM 变空。
        val outputSamples = (inputSamples.toDouble() * toHz / fromHz.toDouble()).roundToInt()
        if (outputSamples <= 0) return ByteArray(0)

        val output = ByteArray(outputSamples * 2)
        val halfWindow = ceil(ratio).toInt() / 2

        for (i in 0 until outputSamples) {
            val centerSrcIdx = (i * ratio).toInt() // floor
            var sum = 0
            var count = 0
            for (j in -halfWindow..halfWindow) {
                val idx = centerSrcIdx + j
                if (idx in 0 until inputSamples) {
                    sum += sampleAt(input, idx)
                    count++
                }
            }
            val avg = if (count > 0) sum / count else 0
            writeSample(output, i, avg)
        }
        return output
    }

    /**
     * 去直流偏移后把峰值放大到 [targetAmplitude]（提升指纹识别率）。
     * 全静音（去 DC 后峰值为 0）时原样返回。
     */
    fun normalizeGain(input: ByteArray, targetAmplitude: Int = RecognitionConfig.TARGET_AMPLITUDE): ByteArray {
        val samples = input.size / 2
        if (samples == 0) return input

        var sum = 0L
        for (i in 0 until samples) sum += sampleAt(input, i)
        val dcOffset = (sum.toDouble() / samples).roundToInt()

        var adjustedMax = 0
        for (i in 0 until samples) {
            val v = abs(sampleAt(input, i) - dcOffset)
            if (v > adjustedMax) adjustedMax = v
        }
        if (adjustedMax == 0) return input

        val gain = targetAmplitude.toDouble() / adjustedMax
        // 与参照实现一致：已经够响（gain<=1）就原样返回，不做 DC 去除
        if (gain <= 1.0) return input
        val output = ByteArray(input.size)
        for (i in 0 until samples) {
            val v = sampleAt(input, i) - dcOffset
            writeSample(output, i, (v * gain).roundToInt())
        }
        return output
    }
}
