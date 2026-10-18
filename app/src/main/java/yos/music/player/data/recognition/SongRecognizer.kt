package yos.music.player.data.recognition

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/**
 * 识曲链路常量（与参照实现 md3Music `song_recognition_page.dart` 1:1）。
 *
 * 为什么录 44.1kHz 而不是直接录 8kHz：指纹接口只吃 8kHz，但部分机型对 8kHz 录音
 * 走的是很差的硬件/软件重采样；先按原生 44.1kHz 录满 8 秒再自己均值降采样，
 * 命中率明显更稳。
 */
object RecognitionConfig {
    const val RECORD_SAMPLE_RATE = 44100
    const val TARGET_SAMPLE_RATE = 8000

    /** 每段录制时长（秒）。 */
    const val SEGMENT_SECONDS = 8

    /** 单次识别最多听多久（秒）：56s = 7 轮 × 8s。 */
    const val MAX_TOTAL_SECONDS = 56
    const val MAX_ATTEMPTS = MAX_TOTAL_SECONDS / SEGMENT_SECONDS

    /** 静音阈值：低于此最大振幅的段直接跳过（原始信号源振幅普遍偏小，30 是实测值）。 */
    const val SILENCE_THRESHOLD = 30

    /** 增益归一化目标振幅。 */
    const val TARGET_AMPLITUDE = 25000
}

/** 一段已前处理、可直接送 /audio/match 的音频。 */
data class ProcessedSegment(
    /** 8000Hz / 16bit 小端 / 单声道 PCM。 */
    val pcm: ByteArray,
    /** 归一化前的最大振幅（静音判定依据）。 */
    val maxAmplitude: Int
) {
    /** 静音段：跳过识别，继续录下一段。 */
    val isSilent: Boolean get() = maxAmplitude < RecognitionConfig.SILENCE_THRESHOLD
}

/** 原始 44.1kHz PCM → 8kHz + 静音判定 + 增益归一化。 */
fun prepareSegmentPcm(rawPcm: ByteArray): ProcessedSegment {
    val downsampled = PcmProcessor.downsample(
        rawPcm,
        RecognitionConfig.RECORD_SAMPLE_RATE,
        RecognitionConfig.TARGET_SAMPLE_RATE
    )
    val maxAmplitude = PcmProcessor.computeMaxAmplitude(downsampled)
    val pcm = if (maxAmplitude >= RecognitionConfig.SILENCE_THRESHOLD) {
        PcmProcessor.normalizeGain(downsampled)
    } else {
        downsampled
    }
    return ProcessedSegment(pcm = pcm, maxAmplitude = maxAmplitude)
}

/**
 * 麦克风录音器：一段一段地录（每段 [RecognitionConfig.SEGMENT_SECONDS] 秒），
 * 每段单独建/销毁 AudioRecord——与参照实现一致，避免长持有麦克风导致
 * 系统级音频焦点/省电策略介入。
 *
 * 线程模型：所有方法都要在协程里调用；[recordSegment] 内部切到 IO 并支持取消
 * （页面销毁时 cancel Job 即可，AudioRecord 在 finally 里必然 release）。
 */
class SongRecognizer {

    companion object {
        private const val TAG = "SongRecognizer"
    }

    @Volatile
    private var stopRequested = false

    /** 让正在进行的 [recordSegment] 提前收尾（用于用户点「停止」）。 */
    fun requestStop() {
        stopRequested = true
    }

    /**
     * 录一段 8 秒的 44.1kHz/16bit/单声道 PCM。
     * 返回 null 表示录音不可用（无麦克风权限、设备不支持、被系统打断）。
     */
    @SuppressLint("MissingPermission") // 调用方（页面）保证 RECORD_AUDIO 已授权
    suspend fun recordSegment(): ByteArray? = withContext(Dispatchers.IO) {
        stopRequested = false

        val minBuffer = AudioRecord.getMinBufferSize(
            RecognitionConfig.RECORD_SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.e(TAG, "getMinBufferSize failed: $minBuffer")
            return@withContext null
        }
        // 0.5 秒一块，够大不至于频繁唤醒，也够小保证 stop 响应及时
        val chunkBytes = maxOf(minBuffer, RecognitionConfig.RECORD_SAMPLE_RATE) // 44100 字节 ≈ 0.5s
        val recorder = createRecorder(chunkBytes)
        if (recorder == null) {
            Log.e(TAG, "AudioRecord create failed")
            return@withContext null
        }

        val totalSamples = RecognitionConfig.RECORD_SAMPLE_RATE * RecognitionConfig.SEGMENT_SECONDS
        val out = ByteArrayOutputStream(totalSamples * 2)
        val chunk = ShortArray(chunkBytes / 2)
        val startedAt = android.os.SystemClock.elapsedRealtime()

        try {
            recorder.startRecording()
            if (recorder.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "startRecording failed, state=${recorder.recordingState}")
                return@withContext null
            }

            var written = 0
            while (written < totalSamples) {
                coroutineContext.ensureActive()
                if (stopRequested) break
                val read = recorder.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    Log.e(TAG, "read returned $read")
                    break
                }
                for (i in 0 until read) {
                    val v = chunk[i].toInt()
                    out.write(v and 0xFF)
                    out.write((v shr 8) and 0xFF)
                }
                written += read
            }
            Log.d(
                TAG,
                "recordSegment samples=$written/${totalSamples} " +
                        "elapsed=${android.os.SystemClock.elapsedRealtime() - startedAt}ms"
            )
        } catch (error: Throwable) {
            if (error is kotlinx.coroutines.CancellationException) throw error
            Log.e(TAG, "recordSegment failed", error)
            return@withContext null
        } finally {
            runCatching { recorder.stop() }
            runCatching { recorder.release() }
        }

        val data = out.toByteArray()
        if (data.size < 2) return@withContext null

        // 原始电平（对比不同采集源用）：峰值 / RMS，均为满量程 32768 的绝对值
        var peak = 0
        var sumSq = 0.0
        val samples = data.size / 2
        var i = 0
        while (i < samples) {
            val lo = data[i * 2].toInt() and 0xFF
            val hi = data[i * 2 + 1].toInt()
            val v = (lo or (hi shl 8)).toShort().toInt()
            val abs = if (v < 0) -v else v
            if (abs > peak) peak = abs
            sumSq += v.toDouble() * v
            i++
        }
        Log.d(
            TAG,
            "recordSegment rawPeak=$peak rawRms=${Math.sqrt(sumSq / samples).toInt()}"
        )
        data
    }

    @SuppressLint("MissingPermission")
    private fun createRecorder(bufferBytes: Int): AudioRecord? {
        // 采集源优先级：MIC 在前。
        //
        // 为什么不再优先 UNPROCESSED：本机（HyperOS）上 UNPROCESSED 能正常初始化，
        // 但给出来的是"约 1% 满量程 + 几乎全在 300Hz 以下"的信号——量级对得上
        // md3Music 注释里的"正常放歌 ~167"，可指纹一次都认不出；换成标准 MIC
        // （系统正常增益 + 高通）后频谱才正常。UNPROCESSED 只作回退。
        val sources = buildList {
            add(MediaRecorder.AudioSource.MIC)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                add(MediaRecorder.AudioSource.UNPROCESSED)
            }
        }
        for (source in sources) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    RecognitionConfig.RECORD_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferBytes
                )
            }.getOrNull()
            if (recorder != null && recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "using audio source $source (${sourceName(source)})")
                return recorder
            }
            runCatching { recorder?.release() }
            Log.w(TAG, "audio source $source (${sourceName(source)}) unavailable")
        }
        return null
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        else -> source.toString()
    }
}
