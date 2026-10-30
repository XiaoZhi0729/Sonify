package yos.music.player.ui.pages.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 骨架显示判据。
 *
 * 这条判据同时决定「骨架是否出现」与「扫光动画是否创建」，所以它必须把
 * 「上游明确返回空」和「报错」都排除掉——否则一个返回空列表的板块会让
 * 无限动画永远空转（本项目对掉帧极其敏感）。
 */
class HomeSkeletonVisibilityTest {

    @Test
    fun pendingWhileLoadingOrIdle() {
        assertTrue(sectionShowsSkeleton(hasData = false, status = "idle"))
        assertTrue(sectionShowsSkeleton(hasData = false, status = "loading"))
    }

    @Test
    fun notPendingOnceDataArrives() {
        assertFalse(sectionShowsSkeleton(hasData = true, status = "ok"))
        // 有数据时即使状态是 loading（下拉刷新中）也不该闪骨架盖住内容
        assertFalse(sectionShowsSkeleton(hasData = true, status = "loading"))
    }

    @Test
    fun emptyUpstreamIsNotPending() {
        // 上游明确返回空 = 骨架已经"加载完"了，再转下去就是空转
        assertFalse(sectionShowsSkeleton(hasData = false, status = "empty"))
    }

    @Test
    fun errorIsNotPending() {
        assertFalse(sectionShowsSkeleton(hasData = false, status = "error:timeout"))
        assertFalse(sectionShowsSkeleton(hasData = false, status = "error:"))
    }

    @Test
    fun listOverloadMatchesBooleanOverload() {
        assertTrue(sectionShowsSkeleton(items = emptyList<Any>(), status = "loading"))
        assertFalse(sectionShowsSkeleton(items = listOf("x"), status = "loading"))
        assertFalse(sectionShowsSkeleton(items = emptyList<Any>(), status = "empty"))
        assertFalse(sectionShowsSkeleton(items = emptyList<Any>(), status = "error:boom"))
    }
}
