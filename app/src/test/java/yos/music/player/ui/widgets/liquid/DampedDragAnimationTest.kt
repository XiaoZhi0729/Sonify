package yos.music.player.ui.widgets.liquid

import androidx.compose.runtime.MonotonicFrameClock
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

private class ManualFrameClock : MonotonicFrameClock {
    private var frameTimeNanos = 0L
    private val waiters = mutableListOf<Pair<CompletableDeferred<Any?>, (Long) -> Any?>>()

    override suspend fun <R> withFrameNanos(onFrame: (Long) -> R): R {
        val deferred = CompletableDeferred<Any?>()
        synchronized(waiters) {
            @Suppress("UNCHECKED_CAST")
            waiters.add(deferred to (onFrame as (Long) -> Any?))
        }
        @Suppress("UNCHECKED_CAST")
        return deferred.await() as R
    }

    fun advanceFrame(stepNanos: Long = 16_000_000L): Boolean {
        frameTimeNanos += stepNanos
        val pending = synchronized(waiters) {
            if (waiters.isEmpty()) return false
            waiters.toList().also { waiters.clear() }
        }
        pending.forEach { (deferred, callback) ->
            try {
                deferred.complete(callback(frameTimeNanos))
            } catch (t: Throwable) {
                deferred.completeExceptionally(t)
            }
        }
        return true
    }

    fun hasWaiters(): Boolean = synchronized(waiters) { waiters.isNotEmpty() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DampedDragAnimationTest {

    private fun pumpFrames(clock: ManualFrameClock, count: Int) = runBlocking {
        repeat(count) {
            clock.advanceFrame()
            delay(2L)
        }
    }

    @Test
    fun dragThenReleaseResetsScalePressAndVelocityToRest() = runBlocking {
        val clock = ManualFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        try {
            val anim = DampedDragAnimation(
                animationScope = scope,
                initialValue = 0.2f,
                valueRange = 0f..2f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )

            anim.press()
            pumpFrames(clock, 12)

            // 模拟连续拖动，积累非零速度（曾导致松手后 velocity 不归零、白胶囊持续被压扁/拉宽）
            for (i in 1..6) {
                anim.dragTo(0.2f + i * 0.25f)
                delay(18L)
                clock.advanceFrame()
                delay(2L)
            }

            assertTrue("Expected non-zero pressProgress during drag", anim.pressProgress > 0.5f)
            assertTrue("Expected scaleX > 1 during drag", anim.scaleX > 1.05f)

            anim.release()
            pumpFrames(clock, 120)

            assertEquals(1.7f, anim.value, 0.002f)
            assertEquals("velocity must return to 0 after release", 0f, anim.velocity, 0.005f)
            assertEquals("scaleX must return to initialScale after release", 1f, anim.scaleX, 0.005f)
            assertEquals("scaleY must return to initialScale after release", 1f, anim.scaleY, 0.005f)
            assertEquals("pressProgress must return to 0 after release", 0f, anim.pressProgress, 0.005f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun pauseMidDragRelaxesVelocityWhilePressedThenReleaseRestoresScale() = runBlocking {
        val clock = ManualFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        try {
            val anim = DampedDragAnimation(
                animationScope = scope,
                initialValue = 0.5f,
                valueRange = 0f..2f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )

            anim.press()
            for (i in 1..5) {
                anim.dragTo(0.5f + i * 0.2f)
                delay(18L)
                clock.advanceFrame()
                delay(2L)
            }

            // 手指按住但停下超过 80ms，速度形变应回落到 0
            delay(120L)
            pumpFrames(clock, 90)
            assertEquals("velocity must decay to 0 during mid-drag pause", 0f, anim.velocity, 0.01f)
            assertTrue("pressProgress stays active while still held", anim.pressProgress > 0.9f)

            anim.release()
            pumpFrames(clock, 120)
            assertEquals(0f, anim.velocity, 0.005f)
            assertEquals(1f, anim.scaleX, 0.005f)
            assertEquals(1f, anim.scaleY, 0.005f)
            assertEquals(0f, anim.pressProgress, 0.005f)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun tapJumpUpdateValueThenReleaseSettlesCleanlyToRest() = runBlocking {
        val clock = ManualFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        try {
            val anim = DampedDragAnimation(
                animationScope = scope,
                initialValue = 0f,
                valueRange = 0f..2f,
                visibilityThreshold = 0.001f,
                initialScale = 1f,
                pressedScale = 1.5f,
                onDragStarted = {},
                onDragStopped = {},
                onDrag = { _, _ -> },
            )

            anim.press()
            anim.updateValue(1.8f)
            anim.release()

            pumpFrames(clock, 150)

            assertEquals(1.8f, anim.value, 0.01f)
            assertEquals(0f, anim.velocity, 0.005f)
            assertEquals(1f, anim.scaleX, 0.005f)
            assertEquals(1f, anim.scaleY, 0.005f)
            assertEquals(0f, anim.pressProgress, 0.005f)
        } finally {
            scope.cancel()
        }
    }
}
