package yos.music.player.ui.pages.library.artists

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 艺人页背景「整体揭示」门的契约。
 *
 * 背景由 hero 照片 + 提取色底 + 倒影/渐变模糊三部分组成，三者加载时序不同。
 * [artistBackdropReady] 决定英雄区是否揭示背景：门未开前英雄区只透出导航底衬，
 * 门开后三者用同一条 alpha 同帧渐入（消除「照片先出→颜色后到→倒影突现」的分批闪现）。
 *
 * 反例即回归点：把「取色就绪」误当「背景就绪」会让倒影再次突现；把无头像歌手
 * 误判为「等背景」会让英雄区永久空白。
 */
class ArtistBackdropRevealTest {

    @Test
    fun landscapeNeverWaits() {
        // 横屏是 54dp 顶 padding 的横向头部、无倒影，不介入揭示门。
        assertTrue(
            artistBackdropReady(
                immersiveBackdrop = false,
                avatarAbsent = false,
                contentResolved = false,
                bakeSettled = false
            )
        )
    }

    @Test
    fun artistWithoutAvatarRevealsImmediately() {
        // 无头像歌手没有背景元素可等；等待只会让英雄区永久空白。
        assertTrue(
            artistBackdropReady(
                immersiveBackdrop = true,
                avatarAbsent = true,
                contentResolved = false,
                bakeSettled = false
            )
        )
    }

    @Test
    fun waitsUntilExtractedColorLands() {
        // 取色未定 → 门关闭（照片与颜色必须同帧落位）。
        assertFalse(
            artistBackdropReady(
                immersiveBackdrop = true,
                avatarAbsent = false,
                contentResolved = false,
                bakeSettled = false
            )
        )
    }

    @Test
    fun stillWaitsForReflectionAfterColorLands() {
        // 取色已定但倒影尚未接管 → 门仍关闭：提前放行会让倒影随后突现，
        // 这正是本次修复要消除的第三段时间线。
        assertFalse(
            artistBackdropReady(
                immersiveBackdrop = true,
                avatarAbsent = false,
                contentResolved = true,
                bakeSettled = false
            )
        )
    }

    @Test
    fun revealsWhenPhotoReflectionAndColorAreAllReady() {
        assertTrue(
            artistBackdropReady(
                immersiveBackdrop = true,
                avatarAbsent = false,
                contentResolved = true,
                bakeSettled = true
            )
        )
    }

    @Test
    fun bakeFailureOrDecodeFailureStillReveals() {
        // 烘焙/解码失败时由调用方把 bakeSettled 兜底置真（退回锐利照片），
        // 否则门永久关闭。这里锁定「bakeSettled 单独成立即可放行取色已定的页」。
        assertTrue(
            artistBackdropReady(
                immersiveBackdrop = true,
                avatarAbsent = false,
                contentResolved = true,
                bakeSettled = true
            )
        )
    }
}
