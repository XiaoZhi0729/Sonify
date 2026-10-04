package yos.music.player.update

import org.junit.Assert.*
import org.junit.Test

class UpdateCheckPolicyTest {
    private val now = 10L * UpdateCheckPolicy.SUCCESS_INTERVAL_MS

    @Test fun firstAttemptIsAllowed() {
        assertTrue(UpdateCheckPolicy.shouldCheck(true, now, 0, 0, 0))
    }

    @Test fun successHasTwentyFourHourIntervalIncludingBoundary() {
        val interval = UpdateCheckPolicy.SUCCESS_INTERVAL_MS
        assertFalse(UpdateCheckPolicy.shouldCheck(true, now, now - interval + 1, 0, 0))
        assertTrue(UpdateCheckPolicy.shouldCheck(true, now, now - interval, 0, 0))
    }

    @Test fun failureHasFifteenMinuteIntervalIncludingBoundary() {
        val interval = UpdateCheckPolicy.FAILURE_INTERVAL_MS
        assertFalse(UpdateCheckPolicy.shouldCheck(true, now, 0, now - interval + 1, 1))
        assertTrue(UpdateCheckPolicy.shouldCheck(true, now, 0, now - interval, 1))
    }

    @Test fun bothIntervalsMustHaveElapsed() {
        assertFalse(UpdateCheckPolicy.shouldCheck(true, now, now - 1000, now - UpdateCheckPolicy.FAILURE_INTERVAL_MS, 1))
    }

    @Test fun sessionCapAndOptOutAreRespected() {
        assertFalse(UpdateCheckPolicy.shouldCheck(false, now, 0, 0, 0))
        assertFalse(UpdateCheckPolicy.shouldCheck(true, now, 0, 0, UpdateCheckPolicy.MAX_SESSION_CHECKS))
        assertFalse(UpdateCheckPolicy.shouldCheck(true, now, 0, 0, -1))
    }

    @Test fun clockRollbackCannotTriggerTightRetryLoop() {
        assertFalse(UpdateCheckPolicy.shouldCheck(true, now, now + 1, 0, 0))
        assertFalse(UpdateCheckPolicy.shouldCheck(true, now, 0, now + 1, 0))
        assertFalse(UpdateCheckPolicy.shouldCheck(true, -1, 0, 0, 0))
    }
}
