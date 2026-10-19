package yos.music.player.data.repositories

import org.junit.Assert.*
import org.junit.Test

class PlaybackAuthCooldownTest {
    @Test fun backgroundFailuresCannotBlockPlayback() {
        var now = 10L
        val cooldown = PlaybackAuthCooldown { now }
        for (purpose in listOf(PlaybackRequestPurpose.VISIBLE_PROBE, PlaybackRequestPurpose.QUALITY_PROBE, PlaybackRequestPurpose.CROSSFADE_PREFETCH)) {
            assertFalse(cooldown.confirm(purpose, 1, 1, 30_000))
        }
        assertFalse(cooldown.isActive(1))
    }

    @Test fun onlyCurrentAuthenticationCanOpenWindow() {
        val cooldown = PlaybackAuthCooldown { 10L }
        assertFalse(cooldown.confirm(PlaybackRequestPurpose.PLAYBACK, 1, 2, 30_000))
        assertTrue(cooldown.confirm(PlaybackRequestPurpose.PLAYBACK, 2, 2, 30_000))
        assertTrue(cooldown.isActive(2))
        assertFalse(cooldown.isActive(3))
    }

    @Test fun repeatedLocalFailuresDoNotExtendWindow() {
        var now = 10L
        val cooldown = PlaybackAuthCooldown { now }
        assertTrue(cooldown.confirm(PlaybackRequestPurpose.PLAYBACK, 1, 1, 30_000))
        now += 20_000
        assertFalse(cooldown.confirm(PlaybackRequestPurpose.PLAYBACK, 1, 1, 30_000))
        now += 10_000
        assertFalse(cooldown.isActive(1))
    }
}
