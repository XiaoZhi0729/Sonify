package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

class LiveSortAudioAnalyzerTest {

    @Test
    fun `detects trailing silence accurately in 40s scan window`() {
        val sr = 22_050
        val activeSec = 28
        val silenceSec = 12
        val totalSamples = (activeSec + silenceSec) * sr
        val yTail = FloatArray(totalSamples) { i ->
            if (i < activeSec * sr) {
                (0.4f * sin(2.0 * PI * 440.0 * i / sr)).toFloat()
            } else {
                0f
            }
        }
        val rmsSeries = LiveSortAudioAnalyzer.computeFrameRmsSeries(yTail)
        val invalidTail = LiveSortAudioAnalyzer.estimateInvalidTailSecFromRmsSeries(rmsSeries, sr)
        assertTrue("expected ~12s invalid tail, got $invalidTail", invalidTail in 11.0f..13.0f)
    }

    @Test
    fun `active song tail without silence produces zero invalid tail`() {
        val sr = 22_050
        val yTail = FloatArray(20 * sr) { i ->
            (0.35f * sin(2.0 * PI * 330.0 * i / sr)).toFloat()
        }
        val rmsSeries = LiveSortAudioAnalyzer.computeFrameRmsSeries(yTail)
        val invalidTail = LiveSortAudioAnalyzer.estimateInvalidTailSecFromRmsSeries(rmsSeries, sr)
        assertEquals(0f, invalidTail, 1e-4f)
    }

    @Test
    fun `estimates 120 bpm from periodic beat impulses`() {
        val sr = 22_050
        val durationSec = 15
        val samples = FloatArray(durationSec * sr)
        val beatIntervalSamples = sr / 2 // 2 beats/sec = 120 BPM
        val pulseLen = (0.08f * sr).toInt()
        var beatPos = 0
        while (beatPos + pulseLen < samples.size) {
            for (k in 0 until pulseLen) {
                val env = 1f - (k.toFloat() / pulseLen)
                samples[beatPos + k] = (0.8f * env * sin(2.0 * PI * 120.0 * k / sr)).toFloat()
            }
            beatPos += beatIntervalSamples
        }
        val bpm = LiveSortAudioAnalyzer.estimateBpmFromSamples(samples, sr)
        assertTrue("expected ~120 BPM, got $bpm", bpm in 114f..126f)
    }

    @Test
    fun `brightness is higher for treble tone than bass tone`() {
        val sr = 22_050
        val n = sr * 4
        val bass = FloatArray(n) { i -> (0.5f * sin(2.0 * PI * 120.0 * i / sr)).toFloat() }
        val treble = FloatArray(n) { i -> (0.5f * sin(2.0 * PI * 4000.0 * i / sr)).toFloat() }
        val bassBright = LiveSortAudioAnalyzer.computeBrightnessFromSamples(bass, sr)
        val trebleBright = LiveSortAudioAnalyzer.computeBrightnessFromSamples(treble, sr)
        assertTrue("treble ($trebleBright) must be brighter than bass ($bassBright)", trebleBright > bassBright * 3f)
    }

    @Test
    fun `analyzePcmSegments populates end dynamic energy and adaptive entry`() {
        val sr = 22_050
        val yStart = FloatArray(sr * 15) { i ->
            (0.25f * sin(2.0 * PI * 440.0 * i / sr)).toFloat()
        }
        val yTailScan = FloatArray(sr * 30) { i ->
            if (i < sr * 22) (0.3f * sin(2.0 * PI * 440.0 * i / sr)).toFloat() else 0f
        }
        val features = LiveSortAudioAnalyzer.analyzePcmSegments(
            mediaId = "track-test-1",
            durationSec = 210f,
            sampleRate = sr,
            yStart = yStart,
            yTailScan = yTailScan,
        )
        assertTrue(features.hasTailAnalysis)
        assertTrue("invalidTailSec expected ~8s, got ${features.invalidTailSec}", features.invalidTailSec in 7.0f..9.0f)
        assertTrue("mixEntrySec expected ~12s, got ${features.mixEntrySec}", features.mixEntrySec in 11.0f..13.0f)
        assertTrue("endDynamicEnergy should reflect active region before silence", features.endDynamicEnergy > 0.1f)
    }
}
