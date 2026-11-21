package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSortTransitionPolicyTest {

    private fun sampleFeatures(
        id: String,
        bpm: Float = 120f,
        energy: Float = 0.08f,
        startDynamicEnergy: Float = energy,
        endDynamicEnergy: Float = energy,
        invalidTailSec: Float = 0f,
        endActivityRatio: Float = 1f,
        brightnessNorm: Float = 0.4f,
    ) = LiveSortTrackFeatures(
        mediaId = id,
        durationSec = 240f,
        bpm = bpm,
        energy = energy,
        brightnessNorm = brightnessNorm,
        startBpm = bpm,
        endBpm = bpm,
        startEnergy = startDynamicEnergy,
        endEnergy = endDynamicEnergy,
        start10sEnergy = startDynamicEnergy,
        end10sEnergy = endDynamicEnergy,
        startDynamicEnergy = startDynamicEnergy,
        endDynamicEnergy = endDynamicEnergy,
        tailSilenceSec = invalidTailSec,
        invalidTailSec = invalidTailSec,
        endActivityRatio = endActivityRatio,
        mixLeadSec = (9.1f + invalidTailSec * 0.62f).coerceIn(7.4f, 13.2f),
        mixBreathSec = (1.75f + invalidTailSec * 0.22f).coerceIn(1.35f, 3.2f),
        mixEffectStartSec = (10f + invalidTailSec).coerceIn(8f, 50f),
        mixEntrySec = (4f + invalidTailSec).coerceIn(4f, 44f),
        hasTailAnalysis = true,
    )

    @Test
    fun `dynamic profile attenuates much louder incoming intro`() {
        val outgoing = sampleFeatures("out", endDynamicEnergy = 0.04f)
        val incoming = sampleFeatures("in", startDynamicEnergy = 0.16f) // +12 dB
        val profile = LiveSortTransitionPolicy.getTransitionDynamicProfile(outgoing, incoming)
        assertTrue("loud incoming start gain should be attenuated: ${profile.incomingStartGain}", profile.incomingStartGain < 0.7f)
        assertEquals(0.92f, profile.totalMixCap, 1e-4f)
        assertTrue(profile.fxIntensity > 1.0f)
    }

    @Test
    fun `dynamic profile boosts quieter incoming intro`() {
        val outgoing = sampleFeatures("out", endDynamicEnergy = 0.16f)
        val incoming = sampleFeatures("in", startDynamicEnergy = 0.04f) // -12 dB
        val profile = LiveSortTransitionPolicy.getTransitionDynamicProfile(outgoing, incoming)
        assertTrue("quiet incoming start gain should be boosted: ${profile.incomingStartGain}", profile.incomingStartGain > 0.86f)
        assertEquals(0.96f, profile.totalMixCap, 1e-4f)
    }

    @Test
    fun `adaptive entry time moves earlier for long invalid tail and pulls later for bright energetic intro`() {
        val cleanOut = sampleFeatures("cleanOut", invalidTailSec = 0f)
        val silentTailOut = sampleFeatures("silentOut", invalidTailSec = 10f)
        val gentleIn = sampleFeatures("gentleIn", energy = 0.10f, startDynamicEnergy = 0.04f, brightnessNorm = 0.1f)
        val punchyIn = sampleFeatures("punchyIn", energy = 0.08f, startDynamicEnergy = 0.15f, brightnessNorm = 0.9f)

        val cleanEntry = LiveSortTransitionPolicy.getAdaptiveMixEntrySeconds(cleanOut, gentleIn)
        val silentEntry = LiveSortTransitionPolicy.getAdaptiveMixEntrySeconds(silentTailOut, gentleIn)
        val punchyEntry = LiveSortTransitionPolicy.getAdaptiveMixEntrySeconds(cleanOut, punchyIn)

        assertTrue("invalid tail ($silentEntry) should trigger much earlier than clean ($cleanEntry)", silentEntry > cleanEntry + 8f)
        assertTrue("punchy bright intro ($punchyEntry) should stay crisp in 2.8..3.6s", punchyEntry in 2.8f..3.6f)
        assertTrue("punchy bright intro ($punchyEntry) should shorten overlap vs gentle ($cleanEntry)", punchyEntry < cleanEntry)
    }

    @Test
    fun `smart window protects ultra short clips and respects ratio cap`() {
        val outgoing = sampleFeatures("out", invalidTailSec = 0f)
        val incoming = sampleFeatures("in")
        assertNull(LiveSortTransitionPolicy.smartWindowMs(0L, outgoing, incoming))
        assertNull(LiveSortTransitionPolicy.smartWindowMs(1_500L, outgoing, incoming))
        val normalWin = LiveSortTransitionPolicy.smartWindowMs(240_000L, outgoing, incoming)
        assertNotNull(normalWin)
        assertTrue(normalWin!! >= CrossfadePolicy.WINDOW_MIN_MS)
    }

    @Test
    fun `effect multiplier preserves 6_0 to 8_4s window by default and adapts for short overlaps`() {
        assertEquals(1f, LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(0f), 1e-5f)
        assertEquals(1f, LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(5.99f), 1e-5f)
        val mid = LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(7.2f)
        assertTrue("mid release should be between 0 and 1: $mid", mid in 0.1f..0.9f)
        assertEquals(0f, LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(8.4f), 1e-5f)
        assertEquals(0f, LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(10f), 1e-5f)

        // Short 3.2s adaptive overlap releases proportionally before finishing
        assertEquals(1f, LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(1.0f, overlapDurationSec = 3.2f), 1e-5f)
        val shortMid = LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(2.4f, overlapDurationSec = 3.2f)
        assertTrue("short overlap mid release should be in 0.1..0.9: $shortMid", shortMid in 0.1f..0.9f)
        assertEquals(0f, LiveSortTransitionPolicy.getCrossfadeEffectMultiplier(3.2f, overlapDurationSec = 3.2f), 1e-5f)
    }

    @Test
    fun `crossfade state enforces zero-start entryEnhance, mix cap, and clean zero endpoints`() {
        val profile = LiveSortTransitionPolicy.DynamicProfile.DEFAULT
        val breathRatio = 0.25f

        val startState = LiveSortTransitionPolicy.getCrossfadeState(
            ratio = 0f,
            baseVol = 1f,
            breathRatio = breathRatio,
            dynamicProfile = profile,
            effectMultiplier = 1f,
        )
        assertEquals(1f, startState.outgoingVolume, 1e-5f)
        assertEquals(0f, startState.outgoingDepth, 1e-5f)
        assertEquals(0f, startState.incomingDepth, 1e-5f)
        assertEquals(0f, startState.outgoingReverb, 1e-5f)
        assertEquals(0f, startState.incomingReverb, 1e-5f)

        val (preNearDepth, preNearRev) = LiveSortTransitionPolicy.getPreCrossfadeOutgoingEffects(
            remainingMs = 3_500L,
            windowMs = 3_000L,
        )
        assertEquals(0f, preNearDepth, 1e-5f)
        assertEquals(0f, preNearRev, 1e-5f)

        val midState = LiveSortTransitionPolicy.getCrossfadeState(
            ratio = 0.45f,
            baseVol = 1f,
            breathRatio = breathRatio,
            dynamicProfile = profile,
            effectMultiplier = 1f,
        )
        assertTrue(midState.outgoingVolume + midState.incomingVolume <= profile.totalMixCap + 1e-4f)
        assertTrue("mid outgoingDepth should build gently: ${midState.outgoingDepth}", midState.outgoingDepth > 0.2f)
        assertTrue("mid incomingDepth should build gently: ${midState.incomingDepth}", midState.incomingDepth > 0.2f)
        assertTrue("mid outgoingReverb should stay light: ${midState.outgoingReverb}", midState.outgoingReverb in 0.05f..0.24f)
        assertTrue("mid incomingReverb should stay light: ${midState.incomingReverb}", midState.incomingReverb in 0.05f..0.24f)

        val endState = LiveSortTransitionPolicy.getCrossfadeState(
            ratio = 1f,
            baseVol = 1f,
            breathRatio = breathRatio,
            dynamicProfile = profile,
            effectMultiplier = 0f,
        )
        assertEquals(0f, endState.outgoingVolume, 1e-5f)
        assertEquals(0f, endState.outgoingDepth, 1e-5f)
        assertEquals(0f, endState.incomingDepth, 1e-5f)
        assertEquals(0f, endState.outgoingReverb, 1e-5f)
        assertEquals(0f, endState.incomingReverb, 1e-5f)
        assertTrue(endState.incomingVolume > 0.85f)
    }
}
