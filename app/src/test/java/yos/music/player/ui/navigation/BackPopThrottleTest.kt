package yos.music.player.ui.navigation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 返回连点节流的单元测试。
 *
 * 这里只测纯 Kotlin 的 [BackPopThrottle]，时间由测试显式注入，不触碰
 * `SystemClock`（JVM 单测下是 stub，会抛 "not mocked"）。栈底守卫那半
 * （`previousBackStackEntry == null` 时不 pop）依赖 NavController，不在此覆盖。
 */
class BackPopThrottleTest {

    @Test
    fun firstAcquireAlwaysAllowed() {
        val throttle = BackPopThrottle(windowMs = 350L)
        assertTrue("首次返回必须放行", throttle.tryAcquire(now = 1_000L))
    }

    @Test
    fun secondPressInsideWindowIsRejected() {
        val throttle = BackPopThrottle(windowMs = 350L)
        assertTrue(throttle.tryAcquire(now = 1_000L))
        assertFalse("窗口内连点应被拦下", throttle.tryAcquire(now = 1_200L))
        assertFalse("窗口内的第三次同样拦下", throttle.tryAcquire(now = 1_349L))
    }

    @Test
    fun pressAfterWindowIsAllowed() {
        val throttle = BackPopThrottle(windowMs = 350L)
        assertTrue(throttle.tryAcquire(now = 1_000L))
        assertTrue("窗口过后应恢复放行", throttle.tryAcquire(now = 1_350L))
    }

    @Test
    fun windowIsMeasuredFromLastAcceptedPress() {
        val throttle = BackPopThrottle(windowMs = 350L)
        assertTrue(throttle.tryAcquire(now = 1_000L))
        // 被拒的那次不应推进计时起点
        assertFalse(throttle.tryAcquire(now = 1_340L))
        assertTrue(throttle.tryAcquire(now = 1_350L))
    }
}
