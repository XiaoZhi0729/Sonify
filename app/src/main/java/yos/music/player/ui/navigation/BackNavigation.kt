package yos.music.player.ui.navigation

import android.os.SystemClock
import androidx.navigation.NavController
import java.util.WeakHashMap

/**
 * 返回连点节流器（不依赖 Android，时间由调用方注入，便于 JVM 单测）。
 *
 * 与 [NavGuard] 的 450ms 导航防抖分开：那边防的是「双击穿透到新页面」，这边防的是
 * 「同一次返回意图被连点执行两次」。共用一个计时器会让导航与返回互相吞事件。
 */
class BackPopThrottle(private val windowMs: Long = 350L) {
    private var lastAt = 0L

    /** 窗口外的首次调用返回 true 并记录时间；窗口内的重复调用返回 false。 */
    fun tryAcquire(now: Long): Boolean {
        if (lastAt != 0L && now - lastAt < windowMs) return false
        lastAt = now
        return true
    }
}

private val throttles = WeakHashMap<NavController, BackPopThrottle>()

/**
 * 带回退守卫的 popBackStack，替换页面顶栏里的裸 `navController.popBackStack()`。
 *
 * 根因（连点两次返回退到纯色页）：无参 `popBackStack()` 等价于
 * `popBackStack(当前目的地, inclusive = true)`。二级页第一次点，弹出二级页回到一级页；
 * 第二次点，当前目的地已是一级页，这次 inclusive 弹出把**起始目的地也一并弹掉**，
 * 返回栈变空，NavHost 没有可绘制的条目，只剩 Activity 主题色底 —— 用户看到的纯色页面。
 * 系统返回键不受影响，因为 NavHost 内置 BackHandler 的开关条件是栈深 > 1。
 *
 * 守卫两层：[previousBackStackEntry] 为空（已在一级页，无可返回目标）时直接拒绝，
 * 这是消除纯色页的决定性条件；再加一层连点窗口，避免三级页被一次双击连退两级。
 *
 * @return 实际发生 pop 返回 true，被守卫拦下返回 false。
 */
fun NavController.popBackStackSafely(now: Long = SystemClock.uptimeMillis()): Boolean {
    val throttle = synchronized(throttles) {
        throttles.getOrPut(this) { BackPopThrottle() }
    }
    if (!throttle.tryAcquire(now)) return false
    if (previousBackStackEntry == null) return false
    return popBackStack()
}
