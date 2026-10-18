package yos.music.player.ui.pages.library.artists

import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.ui.pages.discovery.DiscoveryRailSnapDistance

/**
 * 艺人专辑横排改用 HorizontalPager 后的吸附几何契约。
 *
 * 吸附等式：Pager 每个 Page 占 [ArtistAlbumRailPageSize]，卡固定贴 Page 左缘（Page 宽即卡宽），
 * 右侧余量构成与下一张卡的间距。只有 pageSize == 卡宽 + 卡间距 时，卡间距才与旧 LazyRow 的
 * `Arrangement.spacedBy(12.dp)` 逐像素一致；若误写成 PageSize.Fixed(卡宽)，相邻两张卡的
 * 间距会退化为 0（卡片粘连），而每页吸附后卡左缘仍落在 contentPadding.start = 20dp 上，
 * 视觉上只有「卡片贴在一起」这一处会暴露错误。
 */
class ArtistAlbumRailGeometryTest {

    @Test
    fun pageSizeCoversCardAndGap() {
        assertEquals(
            ArtistAlbumRailCardWidth + ArtistAlbumRailCardGap,
            ArtistAlbumRailPageSize
        )
    }

    @Test
    fun adjacentCardsKeepTheOriginalTwelveDpGap() {
        // Pager 内容坐标：第 N 张卡占据 [N*pageSize, N*pageSize + cardWidth]。
        val cardLeft = { page: Int -> ArtistAlbumRailPageSize * page }
        val leftOfSecond = cardLeft(1)
        val rightOfFirst = cardLeft(0) + ArtistAlbumRailCardWidth
        assertEquals(ArtistAlbumRailCardGap, leftOfSecond - rightOfFirst)
    }

    @Test
    fun pageWidthIsNeverNarrowerThanItsCard() {
        // 卡宽 = Page 宽是「卡贴左缘」这一布局假设的前提，负数/缩水会让卡片被裁切。
        assertTrue(ArtistAlbumRailPageSize >= ArtistAlbumRailCardWidth)
    }

    @Test
    fun snapDistanceIsSharedWithHomeRails() {
        // 手感要求与主页「精选歌单 / 本周新发行」逐字一致：同一个策略对象，主页改动自动跟随。
        val sameInstance = ArtistAlbumRailSnapDistance === DiscoveryRailSnapDistance
        assertTrue("snap distance must be reused from home rails", sameInstance)
    }
}
