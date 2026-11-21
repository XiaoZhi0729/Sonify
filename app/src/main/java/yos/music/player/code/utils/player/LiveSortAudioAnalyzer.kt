package yos.music.player.code.utils.player

import android.content.Context
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import yos.music.player.code.OnlineAudioCache
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.repositories.KugouRepository
import java.io.FileNotFoundException
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * LiveSort 音频首尾特征分析器。
 *
 * 1:1 移植 `D:\LiveSort-main\LiveSortApp\audio_analyzer.py` 与 `templates\index.html` 的特征提取数学：
 * - 帧级 RMS（2048 窗口 / 512 步长）、5 点移动平均平滑、40% 分位噪底；
 * - 曲末无效静音/极弱尾段扫描（最后 40s 窗，识别 [LiveSortTrackFeatures.invalidTailSec]）；
 * - 曲首/曲尾 15s 节拍自相关估算（55~210 BPM）；
 * - 一阶差分频谱亮度估算与动态能量窗提取。
 *
 * Android 端采用“首尾跳读解码”策略：通过 `MediaExtractor` + `MediaCodec` 只解码开头 15s
 * 与结尾 40s（单声道降采样至 22050Hz），兼顾低内存与极快响应；对未整首缓存的在线歌曲，
 * 配合 [LiveStreamFeatureTracker] 在音频流经播放器时实时采集。
 */
object LiveSortAudioAnalyzer {

    const val ANALYSIS_SAMPLE_RATE = 22_050
    private const val FRAME_SIZE = 2048
    private const val HOP_SIZE = 512
    private const val START_WINDOW_SEC = 15f
    private const val TAIL_SCAN_WINDOW_SEC = 40f
    private const val DECODE_WALL_BUDGET_MS = 3_500L

    @kotlin.OptIn(ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))
    private val inFlightIds = Collections.newSetFromMap(ConcurrentHashMap<String, Boolean>())

    // ----------------------------------------------------------------
    // 纯算法核心（1:1 对齐 LiveSort，不依赖 Android Framework，单测可直接校验）
    // ----------------------------------------------------------------

    fun computeFrameRmsSeries(
        samples: FloatArray,
        length: Int = samples.size,
        frameSize: Int = FRAME_SIZE,
        hopSize: Int = HOP_SIZE,
    ): FloatArray {
        val safeFrame = max(32, frameSize)
        val safeHop = max(16, hopSize)
        val safeLen = length.coerceIn(0, samples.size)
        if (safeLen < safeFrame) return FloatArray(0)
        val count = ((safeLen - safeFrame) / safeHop) + 1
        val series = FloatArray(count)
        var idx = 0
        var start = 0
        while (start + safeFrame <= safeLen && idx < count) {
            var sumSq = 0.0
            for (i in 0 until safeFrame) {
                val v = samples[start + i].toDouble()
                sumSq += v * v
            }
            series[idx++] = sqrt(sumSq / safeFrame).toFloat()
            start += safeHop
        }
        return series
    }

    fun smoothSeriesMovingAverage(values: FloatArray, kernelSize: Int = 5): FloatArray {
        if (values.isEmpty()) return FloatArray(0)
        val safeKernel = max(1, kernelSize)
        if (safeKernel <= 1 || values.size <= 2) return values.copyOf()
        val half = safeKernel / 2
        val out = FloatArray(values.size)
        for (i in values.indices) {
            var acc = 0.0
            var count = 0
            for (k in -half..half) {
                val idx = i + k
                if (idx !in values.indices) continue
                acc += values[idx].toDouble()
                count++
            }
            out[i] = if (count > 0) (acc / count).toFloat() else values[i]
        }
        return out
    }

    fun percentile(values: FloatArray, p: Float): Float {
        if (values.isEmpty()) return 0f
        val valid = values.filter { it.isFinite() }.sorted()
        if (valid.isEmpty()) return 0f
        val rank = (p.coerceIn(0f, 100f) / 100f) * (valid.size - 1)
        val low = floor(rank).toInt()
        val high = kotlin.math.ceil(rank).toInt()
        if (low == high) return valid[low]
        val t = rank - low
        return valid[low] * (1f - t) + valid[high] * t
    }

    fun estimateInvalidTailSecFromRmsSeries(
        rmsSeries: FloatArray,
        sampleRate: Int,
        hopSize: Int = HOP_SIZE,
    ): Float {
        if (rmsSeries.isEmpty() || sampleRate <= 0 || hopSize <= 0) return 0f
        val smoothed = smoothSeriesMovingAverage(rmsSeries, 5)
        val peakRms = rmsSeries.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val tailLenSec = (rmsSeries.size.toLong() * hopSize).toFloat() / sampleRate.toFloat()
        if (peakRms <= 1e-7f) return tailLenSec.coerceIn(0f, 40f)
        val floorRms = percentile(smoothed, 40f)
        val activeThreshold = maxOf(peakRms * 0.065f, floorRms * 0.62f, 4e-5f)
        var lastActiveIndex = -1
        for (i in smoothed.indices) {
            if (smoothed[i] >= activeThreshold) lastActiveIndex = i
        }
        val invalidTail = if (lastActiveIndex < 0) {
            tailLenSec
        } else {
            val lastActiveSec = (lastActiveIndex.toLong() * hopSize).toFloat() / sampleRate.toFloat()
            max(0f, tailLenSec - lastActiveSec)
        }
        if (invalidTail < 0.4f) return 0f
        return min(40f, invalidTail)
    }

    fun estimateBpmFromSamples(
        samples: FloatArray,
        sampleRate: Int,
        length: Int = samples.size,
    ): Float {
        val frameSize = 1024
        val hopSize = 512
        val safeLen = length.coerceIn(0, samples.size)
        if (safeLen < frameSize * 8 || sampleRate <= 0) return 0f
        val env = ArrayList<Float>(safeLen / hopSize + 1)
        var start = 0
        while (start + frameSize < safeLen) {
            var sum = 0.0
            for (i in 0 until frameSize) {
                sum += abs(samples[start + i].toDouble())
            }
            env.add((sum / frameSize).toFloat())
            start += hopSize
        }
        if (env.size < 32) return 0f
        val mean = (env.sumOf { it.toDouble() } / env.size).toFloat()
        var varianceAcc = 0.0
        for (i in env.indices) {
            val centered = max(0f, env[i] - mean)
            env[i] = centered
            varianceAcc += centered.toDouble() * centered.toDouble()
        }
        val variance = varianceAcc / max(1, env.size)
        if (variance < 1e-6) return 0f

        val envRate = sampleRate.toFloat() / hopSize.toFloat()
        val minLag = floor((60f * envRate) / 180f).toInt().coerceAtLeast(1)
        val maxLag = floor((60f * envRate) / 70f).toInt().coerceAtLeast(minLag + 1)
        var bestLag = 0
        var bestScore = Double.NEGATIVE_INFINITY
        for (lag in minLag..maxLag) {
            var score = 0.0
            for (i in 0 until env.size - lag) {
                score += env[i].toDouble() * env[i + lag].toDouble()
            }
            if (score > bestScore) {
                bestScore = score
                bestLag = lag
            }
        }
        if (bestLag <= 0) return 0f
        val bpm = (60f * envRate) / bestLag.toFloat()
        if (!bpm.isFinite()) return 0f
        return bpm.coerceIn(55f, 210f)
    }

    fun computeEnergyFromSamples(samples: FloatArray, length: Int = samples.size): Float {
        val rmsSeries = computeFrameRmsSeries(samples, length, FRAME_SIZE, HOP_SIZE)
        if (rmsSeries.isEmpty()) return 0f
        val mean = (rmsSeries.sumOf { it.toDouble() } / rmsSeries.size).toFloat()
        return if (mean.isFinite()) mean else 0f
    }

    fun computeBrightnessFromSamples(
        samples: FloatArray,
        sampleRate: Int,
        length: Int = samples.size,
    ): Float {
        val safeLen = length.coerceIn(0, samples.size)
        if (safeLen < 512 || sampleRate <= 0) return 0f
        val frameSize = 2048
        val hopSize = 1024
        var weighted = 0.0
        var sumWeight = 0
        var start = 0
        while (start + frameSize < safeLen) {
            var last = samples[start]
            var diffAcc = 0.0
            var absAcc = abs(last.toDouble())
            for (i in 1 until frameSize) {
                val cur = samples[start + i]
                diffAcc += abs((cur - last).toDouble())
                absAcc += abs(cur.toDouble())
                last = cur
            }
            val norm = if (absAcc > 1e-9) diffAcc / absAcc else 0.0
            weighted += norm
            sumWeight++
            start += hopSize
        }
        val normalized = if (sumWeight > 0) (weighted / sumWeight).toFloat() else 0f
        return (normalized * sampleRate * 0.14f).coerceIn(0f, 6000f)
    }

    /**
     * 对齐 `index.html:5415-5486`，根据曲首/曲尾解码的单声道 PCM 片段生成完整的 [LiveSortTrackFeatures]。
     */
    fun analyzePcmSegments(
        mediaId: String,
        durationSec: Float,
        sampleRate: Int,
        yStart: FloatArray,
        yTailScan: FloatArray,
    ): LiveSortTrackFeatures {
        val safeSr = sampleRate.coerceAtLeast(8000)
        val startWindowSamples = min(yStart.size, (safeSr * START_WINDOW_SEC).toInt())
        val endWindowSamples = min(yTailScan.size, (safeSr * START_WINDOW_SEC).toInt())
        val yEnd = if (yTailScan.size > endWindowSamples) {
            yTailScan.copyOfRange(yTailScan.size - endWindowSamples, yTailScan.size)
        } else {
            yTailScan
        }

        val frameRms = computeFrameRmsSeries(yEnd, yEnd.size, FRAME_SIZE, HOP_SIZE)
        val peakRms = frameRms.maxOrNull()?.coerceAtLeast(0f) ?: 0f
        val activeThreshold = max(peakRms * 0.2f, 1e-4f)
        var lastActiveIndex = -1
        for (i in frameRms.indices) {
            if (frameRms[i] >= activeThreshold) lastActiveIndex = i
        }
        val endLenSec = if (yEnd.isNotEmpty()) yEnd.size.toFloat() / safeSr else 0f
        val lastActiveSec = if (lastActiveIndex >= 0) (lastActiveIndex.toLong() * HOP_SIZE).toFloat() / safeSr else 0f
        val tailSilenceSec = max(0f, endLenSec - lastActiveSec)
        val endActivityRatio = if (endLenSec > 0f) {
            ((endLenSec - tailSilenceSec) / endLenSec).coerceIn(0f, 1f)
        } else {
            1f
        }
        val mixLeadSec = (9.1f + tailSilenceSec * 0.62f).coerceIn(7.4f, 13.2f)
        val mixBreathSec = (1.75f + tailSilenceSec * 0.22f).coerceIn(1.35f, 3.2f)

        val tailRmsSeries = computeFrameRmsSeries(yTailScan, yTailScan.size, FRAME_SIZE, HOP_SIZE)
        val invalidTailSec = estimateInvalidTailSecFromRmsSeries(tailRmsSeries, safeSr, HOP_SIZE)
        val mixEffectStartSec = (10.0f + invalidTailSec).coerceIn(8.0f, 50.0f)
        val mixEntrySec = (4.0f + invalidTailSec).coerceIn(4.0f, 44.0f)
        val dynamicWindowSec = (10.0f + invalidTailSec * 0.55f).coerceIn(8.0f, 24.0f)

        val dynamicWindowSamples = (safeSr * dynamicWindowSec).toInt()
        val effectiveTailSamples = (safeSr * invalidTailSec).toInt()
        val endDynamicEnd = if (effectiveTailSamples in 1 until yTailScan.size) {
            yTailScan.size - effectiveTailSamples
        } else {
            yTailScan.size
        }
        val endDynamicStart = if (dynamicWindowSamples > 0) {
            max(0, endDynamicEnd - dynamicWindowSamples)
        } else {
            0
        }
        val yEndDynamic = if (endDynamicEnd > endDynamicStart) {
            yTailScan.copyOfRange(endDynamicStart, endDynamicEnd)
        } else {
            yTailScan
        }
        val yStartDynamic = if (dynamicWindowSamples in 1 until yStart.size) {
            yStart.copyOfRange(0, dynamicWindowSamples)
        } else {
            yStart
        }

        val mixWindowSamples = safeSr * 10
        val yStartMix = if (mixWindowSamples in 1 until yStart.size) {
            yStart.copyOfRange(0, mixWindowSamples)
        } else {
            yStart
        }
        val yEndMix = if (mixWindowSamples in 1 until yEnd.size) {
            yEnd.copyOfRange(yEnd.size - mixWindowSamples, yEnd.size)
        } else {
            yEnd
        }

        val startBpm = estimateBpmFromSamples(yStart, safeSr, startWindowSamples)
        val endBpm = estimateBpmFromSamples(yEnd, safeSr, yEnd.size)
        val mainBpm = when {
            startBpm > 0f && endBpm > 0f -> (startBpm + endBpm) * 0.5f
            startBpm > 0f -> startBpm
            else -> endBpm
        }.let { if (it > 0f) it else 120f }

        val startEnergy = computeEnergyFromSamples(yStart, startWindowSamples)
        val endEnergy = computeEnergyFromSamples(yEnd, yEnd.size)
        val mainEnergy = max(0.004f, (startEnergy + endEnergy) * 0.5f)
        val start10sEnergy = computeEnergyFromSamples(yStartMix, yStartMix.size)
        val end10sEnergy = computeEnergyFromSamples(yEndMix, yEndMix.size)
        val startDynamicEnergy = computeEnergyFromSamples(yStartDynamic, yStartDynamic.size)
        val endDynamicEnergy = computeEnergyFromSamples(yEndDynamic, yEndDynamic.size)
        val brightness = computeBrightnessFromSamples(yStart, safeSr, startWindowSamples)
        val brightnessNorm = (brightness / 3000f).coerceIn(0f, 1f)

        return LiveSortTrackFeatures(
            mediaId = mediaId,
            durationSec = durationSec,
            bpm = mainBpm,
            energy = mainEnergy,
            brightness = brightness,
            brightnessNorm = brightnessNorm,
            startBpm = if (startBpm > 0f) startBpm else mainBpm,
            endBpm = if (endBpm > 0f) endBpm else mainBpm,
            startEnergy = startEnergy,
            endEnergy = endEnergy,
            start10sEnergy = start10sEnergy,
            end10sEnergy = end10sEnergy,
            startDynamicEnergy = startDynamicEnergy,
            endDynamicEnergy = endDynamicEnergy,
            dynamicWindowSec = dynamicWindowSec,
            tailSilenceSec = tailSilenceSec,
            invalidTailSec = invalidTailSec,
            endActivityRatio = endActivityRatio,
            mixLeadSec = mixLeadSec,
            mixBreathSec = mixBreathSec,
            mixEffectStartSec = mixEffectStartSec,
            mixEntrySec = mixEntrySec,
            hasTailAnalysis = true,
        )
    }

    // ----------------------------------------------------------------
    // 后台首尾快速解码分析（支持本地音频与整首已缓存的在线音频）
    // ----------------------------------------------------------------

    fun ensureAnalyzedAsync(context: Context?, mediaItem: MediaItem?) {
        val appContext = context?.applicationContext ?: return
        val item = mediaItem ?: return
        val mediaId = item.mediaId
        if (mediaId.isBlank()) return
        if (LiveSortFeatureStore.get(mediaId)?.hasTailAnalysis == true) return
        if (!inFlightIds.add(mediaId)) return

        scope.launch {
            try {
                val analyzed = analyzeMediaItem(appContext, item)
                if (analyzed != null) {
                    LiveSortFeatureStore.put(analyzed)
                    YosDiagnostics.log(
                        "LIVESORT_ANA",
                        "id" to mediaId,
                        "dur" to "%.1f".format(analyzed.durationSec),
                        "bpm" to "%.1f".format(analyzed.bpm),
                        "invTail" to "%.2f".format(analyzed.invalidTailSec),
                        "entry" to "%.2f".format(analyzed.mixEntrySec),
                    )
                }
            } catch (t: Throwable) {
                YosDiagnostics.log(
                    "LIVESORT_ANA_ERR",
                    "id" to mediaId,
                    "err" to (t.message ?: t.javaClass.simpleName),
                )
            } finally {
                inFlightIds.remove(mediaId)
            }
        }
    }

    @OptIn(UnstableApi::class)
    private fun analyzeMediaItem(context: Context, item: MediaItem): LiveSortTrackFeatures? {
        val uri = item.localConfiguration?.uri ?: return null
        val mediaId = item.mediaId
        return when (uri.scheme) {
            KugouRepository.PLACEHOLDER_SCHEME -> {
                val hash = uri.lastPathSegment ?: return null
                analyzeCachedOnlineSong(context, mediaId, hash)
            }
            "file", "content", null -> {
                analyzeExtractorSource(mediaId) { extractor ->
                    if (uri.scheme == null) {
                        extractor.setDataSource(uri.toString())
                    } else {
                        extractor.setDataSource(context, uri, null)
                    }
                }
            }
            else -> null
        }
    }

    @OptIn(UnstableApi::class)
    private fun analyzeCachedOnlineSong(
        context: Context,
        mediaId: String,
        hash: String,
    ): LiveSortTrackFeatures? {
        val facts = OnlineAudioCache.cacheFactsOf(context, hash)
        val tier = facts.fullyCached.firstOrNull() ?: return null
        val cache = OnlineAudioCache.get(context) ?: return null
        val cacheKey = OnlineAudioCache.keyOf(hash, tier)
        val totalLen = runCatching {
            ContentMetadata.getContentLength(cache.getContentMetadata(cacheKey))
        }.getOrDefault(C.LENGTH_UNSET.toLong())
        if (totalLen <= 0L) return null

        val cacheDataSource = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory {
                object : DataSource {
                    override fun open(dataSpec: DataSpec): Long =
                        throw FileNotFoundException("Cache miss in LiveSort analyzer")
                    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = 0
                    override fun getUri(): Uri? = null
                    override fun close() = Unit
                    override fun addTransferListener(transferListener: TransferListener) = Unit
                }
            }
            .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
            .createDataSource()

        val mediaDataSource = object : MediaDataSource() {
            private val fakeUri = Uri.parse("cacheonly://livesort/$hash")
            override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
                if (position >= totalLen) return -1
                val want = min(size.toLong(), totalLen - position).toInt()
                if (want <= 0) return 0
                val spec = DataSpec.Builder()
                    .setUri(fakeUri)
                    .setKey(cacheKey)
                    .setPosition(position)
                    .setLength(want.toLong())
                    .build()
                var totalRead = 0
                return try {
                    cacheDataSource.open(spec)
                    while (totalRead < want) {
                        val n = cacheDataSource.read(buffer, offset + totalRead, want - totalRead)
                        if (n <= 0) break
                        totalRead += n
                    }
                    if (totalRead == 0) -1 else totalRead
                } catch (_: Throwable) {
                    -1
                } finally {
                    runCatching { cacheDataSource.close() }
                }
            }

            override fun getSize(): Long = totalLen
            override fun close() = Unit
        }

        return mediaDataSource.use { ds ->
            analyzeExtractorSource(mediaId) { extractor ->
                extractor.setDataSource(ds)
            }
        }
    }

    private inline fun analyzeExtractorSource(
        mediaId: String,
        configureSource: (MediaExtractor) -> Unit,
    ): LiveSortTrackFeatures? {
        val extractor = MediaExtractor()
        return try {
            configureSource(extractor)
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val fmt = extractor.getTrackFormat(i)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = fmt
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) return null
            extractor.selectTrack(trackIndex)

            val durationUs = if (trackFormat.containsKey(MediaFormat.KEY_DURATION)) {
                trackFormat.getLong(MediaFormat.KEY_DURATION)
            } else {
                0L
            }
            if (durationUs <= 0L) return null
            val durationSec = durationUs / 1_000_000f

            val yStart = decodeWindowMono(
                extractor = extractor,
                format = trackFormat,
                seekToUs = 0L,
                maxDurationUs = (START_WINDOW_SEC * 1_000_000f).toLong(),
            )
            if (yStart.isEmpty()) return null

            val tailWindowUs = (TAIL_SCAN_WINDOW_SEC * 1_000_000f).toLong()
            val tailSeekUs = max(0L, durationUs - tailWindowUs)
            val yTailScan = if (tailSeekUs <= 2_000_000L && durationSec <= TAIL_SCAN_WINDOW_SEC) {
                yStart
            } else {
                decodeWindowMono(
                    extractor = extractor,
                    format = trackFormat,
                    seekToUs = tailSeekUs,
                    maxDurationUs = tailWindowUs + 5_000_000L,
                    stopAtEndOfStream = true,
                )
            }
            val safeTail = if (yTailScan.isNotEmpty()) yTailScan else yStart

            analyzePcmSegments(
                mediaId = mediaId,
                durationSec = durationSec,
                sampleRate = ANALYSIS_SAMPLE_RATE,
                yStart = yStart,
                yTailScan = safeTail,
            )
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun decodeWindowMono(
        extractor: MediaExtractor,
        format: MediaFormat,
        seekToUs: Long,
        maxDurationUs: Long,
        stopAtEndOfStream: Boolean = false,
    ): FloatArray {
        val mime = format.getString(MediaFormat.KEY_MIME) ?: return FloatArray(0)
        val codec = runCatching { MediaCodec.createDecoderByType(mime) }.getOrNull() ?: return FloatArray(0)
        val outputCapacity = ((maxDurationUs / 1_000_000.0) * ANALYSIS_SAMPLE_RATE).toInt()
            .coerceIn(1024, ANALYSIS_SAMPLE_RATE * 46)
        val monoOut = FloatArray(outputCapacity)
        var outCount = 0

        try {
            codec.configure(format, null, null, 0)
            codec.start()
            extractor.seekTo(seekToUs, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var currentSampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
            var currentChannels = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                2
            }
            var currentPcmEncoding = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                format.containsKey(MediaFormat.KEY_PCM_ENCODING)
            ) {
                format.getInteger(MediaFormat.KEY_PCM_ENCODING)
            } else {
                android.media.AudioFormat.ENCODING_PCM_16BIT
            }

            var resampleAccum = 0.0
            var firstDecodedPtsUs = -1L
            val deadlineMs = SystemClock.elapsedRealtime() + DECODE_WALL_BUDGET_MS

            while (!outputDone && outCount < monoOut.size && SystemClock.elapsedRealtime() < deadlineMs) {
                if (!inputDone) {
                    val inIdx = codec.dequeueInputBuffer(5_000)
                    if (inIdx >= 0) {
                        val inputBuf = codec.getInputBuffer(inIdx)
                        if (inputBuf != null) {
                            val sampleSize = extractor.readSampleData(inputBuf, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(
                                    inIdx, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM
                                )
                                inputDone = true
                            } else {
                                val pts = extractor.sampleTime
                                codec.queueInputBuffer(inIdx, 0, sampleSize, pts, 0)
                                extractor.advance()
                                val anchor = if (firstDecodedPtsUs >= 0L) firstDecodedPtsUs else seekToUs
                                if (!stopAtEndOfStream && pts > anchor + maxDurationUs + 1_000_000L) {
                                    inputDone = true
                                }
                            }
                        }
                    }
                }

                val outIdx = codec.dequeueOutputBuffer(bufferInfo, 5_000)
                when {
                    outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val outFmt = codec.outputFormat
                        if (outFmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                            currentSampleRate = outFmt.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        }
                        if (outFmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                            currentChannels = outFmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
                        }
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N &&
                            outFmt.containsKey(MediaFormat.KEY_PCM_ENCODING)
                        ) {
                            currentPcmEncoding = outFmt.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        }
                    }

                    outIdx >= 0 -> {
                        if (bufferInfo.size > 0) {
                            val outBuf = codec.getOutputBuffer(outIdx)
                            if (outBuf != null) {
                                if (firstDecodedPtsUs < 0L) firstDecodedPtsUs = bufferInfo.presentationTimeUs
                                // 丢弃 seek 同步帧前过多冗余数据（只在尾段或正常播放位置附近采样）
                                val accept = bufferInfo.presentationTimeUs >= seekToUs - 500_000L
                                if (accept) {
                                    outBuf.position(bufferInfo.offset)
                                    outBuf.limit(bufferInfo.offset + bufferInfo.size)
                                    outBuf.order(ByteOrder.LITTLE_ENDIAN)
                                    val step = currentSampleRate.toDouble() / ANALYSIS_SAMPLE_RATE.toDouble()
                                    val isFloat = currentPcmEncoding == android.media.AudioFormat.ENCODING_PCM_FLOAT
                                    val bytesPerSample = if (isFloat) 4 else 2
                                    val frameCount = bufferInfo.size / (bytesPerSample * currentChannels)
                                    var frameIdx = 0
                                    if (isFloat) {
                                        val fb = outBuf.asFloatBuffer()
                                        while (frameIdx < frameCount && outCount < monoOut.size) {
                                            var sum = 0f
                                            val base = frameIdx * currentChannels
                                            for (ch in 0 until currentChannels) {
                                                sum += fb.get(base + ch)
                                            }
                                            val mono = sum / currentChannels
                                            if (resampleAccum <= 0.0) {
                                                monoOut[outCount++] = mono
                                                resampleAccum += step
                                            }
                                            resampleAccum -= 1.0
                                            frameIdx++
                                        }
                                    } else {
                                        val sb = outBuf.asShortBuffer()
                                        while (frameIdx < frameCount && outCount < monoOut.size) {
                                            var sum = 0f
                                            val base = frameIdx * currentChannels
                                            for (ch in 0 until currentChannels) {
                                                sum += sb.get(base + ch) / 32768f
                                            }
                                            val mono = sum / currentChannels
                                            if (resampleAccum <= 0.0) {
                                                monoOut[outCount++] = mono
                                                resampleAccum += step
                                            }
                                            resampleAccum -= 1.0
                                            frameIdx++
                                        }
                                    }
                                }
                            }
                        }
                        codec.releaseOutputBuffer(outIdx, false)
                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            outputDone = true
                        } else if (!stopAtEndOfStream &&
                            firstDecodedPtsUs >= 0L &&
                            bufferInfo.presentationTimeUs - firstDecodedPtsUs >= maxDurationUs
                        ) {
                            outputDone = true
                        }
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
        }
        return if (outCount == monoOut.size) monoOut else monoOut.copyOf(outCount)
    }
}
