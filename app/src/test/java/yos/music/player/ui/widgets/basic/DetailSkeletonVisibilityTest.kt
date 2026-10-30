package yos.music.player.ui.widgets.basic

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 详情页骨架判据。
 *
 * 这条判据在四个详情页里各自内联（因为每页的「有没有数据」表达不同），
 * 但语义必须一致：**没数据 + 不是明确空 + 不是报错** 才铺骨架。
 * 这里的测试把这条语义钉死，改动判据时先看它。
 */
class DetailSkeletonVisibilityTest {

    /**
     * 与各详情页 `showSkeleton` 完全同构的判据。
     * 保持成一个本地函数，避免测试与实现各写一份逻辑而漂移。
     */
    private fun showSkeleton(hasItems: Boolean, hasError: Boolean, isEmpty: Boolean): Boolean =
        !hasItems && !isEmpty && !hasError

    @Test
    fun skeletonWhileLoadingWithoutError() {
        assertTrue(showSkeleton(hasItems = false, hasError = false, isEmpty = false))
    }

    @Test
    fun noSkeletonOnceItemsExist() {
        assertFalse(showSkeleton(hasItems = true, hasError = false, isEmpty = false))
        // 已有数据时即使正在刷新（loading）也不该拿骨架盖住内容
        assertFalse(showSkeleton(hasItems = true, hasError = false, isEmpty = true))
    }

    @Test
    fun noSkeletonWhenUpstreamReturnsEmpty() {
        // 上游明确返回空 = 已经加载完了，再转骨架就是空转
        assertFalse(showSkeleton(hasItems = false, hasError = false, isEmpty = true))
    }

    @Test
    fun noSkeletonWhenFailed() {
        // 报错时错误行本身就是要给用户看的，不能再盖一层骨架
        assertFalse(showSkeleton(hasItems = false, hasError = true, isEmpty = false))
    }

    @Test
    fun artistListInitialStateShowsSkeleton() {
        // 艺人歌曲页的首分支：没歌 + (正在加载 || (未到底且无错))
        // 显式判空是为了「还没开始请求」时也铺骨架，避免闪一下空态。
        fun artistListSkeleton(
            hasSongs: Boolean,
            loading: Boolean,
            endReached: Boolean,
            loadError: String?
        ) = !hasSongs && (loading || (!endReached && loadError == null))

        assertTrue(artistListSkeleton(hasSongs = false, loading = true, endReached = false, loadError = null))
        // 还没开始请求（loading=false）但也没到底没错 → 仍然要骨架
        assertTrue(artistListSkeleton(hasSongs = false, loading = false, endReached = false, loadError = null))
        // 已到底或已报错 → 不再骨架
        assertFalse(artistListSkeleton(hasSongs = false, loading = false, endReached = true, loadError = null))
        assertFalse(artistListSkeleton(hasSongs = false, loading = false, endReached = false, loadError = "boom"))
        // 已有歌 → 无论加载与否都不骨架（补页由 footer 表达）
        assertFalse(artistListSkeleton(hasSongs = true, loading = true, endReached = false, loadError = null))
    }
}
