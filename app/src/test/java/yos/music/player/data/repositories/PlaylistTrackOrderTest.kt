package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 顺序回归：用真实抓包数据锁死「显示顺序 = 服务端 sort 升序」这条规则。
 *
 * 数据来源：`_recog_all.log` 10-18 12:13:16 的 `/playlist/track/all/new`
 * （userid=2167347319, listid=2, count=100），按服务端返回顺序逐条摘录
 * （collecttime, sort）。服务端数组是 collecttime 升序，sort 是 0..99 的独立排列。
 */
class PlaylistTrackOrderTest {

    private companion object {
        /** 服务端返回顺序下的收藏时间（unix 秒），严格递增。 */
        val CAPTURED_COLLECT_TIME = longArrayOf(
            1715352877L, 1715352946L, 1715353033L, 1717771262L, 1717907575L, 1717920925L,
            1719327013L, 1719412495L, 1725454894L, 1728223728L, 1728903872L, 1730908478L,
            1730983749L, 1730983908L, 1730984063L, 1731165448L, 1733755799L, 1733838793L,
            1733838891L, 1734097096L, 1734097458L, 1734241463L, 1734525082L, 1734525362L,
            1734525394L, 1734532642L, 1734612233L, 1734881943L, 1734948725L, 1735208134L,
            1735454575L, 1735605323L, 1735794388L, 1740664691L, 1740664705L, 1742135154L,
            1742216643L, 1742641951L, 1746285413L, 1749721416L, 1749871976L, 1750078288L,
            1750252420L, 1750578493L, 1750578681L, 1750579244L, 1750853039L, 1752596644L,
            1754746941L, 1755008231L, 1756993662L, 1757135605L, 1757136064L, 1757333334L,
            1758549951L, 1758550096L, 1759140029L, 1759235128L, 1761217659L, 1761562988L,
            1763309274L, 1763309281L, 1763701614L, 1764000857L, 1764170340L, 1764170503L,
            1764259480L, 1765607963L, 1767323104L, 1767712902L, 1769275622L, 1770178627L,
            1772351610L, 1772469266L, 1772640346L, 1772905382L, 1774278567L, 1776240515L,
            1776957813L, 1778168494L, 1778772280L, 1778834839L, 1779957423L, 1780652221L,
            1781199187L, 1782555819L, 1782839877L, 1783083505L, 1783598932L, 1784476909L,
            1784734031L, 1784736096L, 1784892838L, 1789229319L, 1790327088L, 1790444430L,
            1790489644L, 1790489798L, 1790831151L, 1791127769L
        )

        /** 同一批曲目的 sort 字段：0..99 的排列，0 = 最新/最上。 */
        val CAPTURED_SORT = intArrayOf(
            99, 98, 97, 96, 95, 94, 93, 92, 89, 90, 88, 91, 86, 87, 85, 84, 83, 82, 81, 80,
            79, 78, 77, 76, 75, 74, 73, 72, 71, 70, 69, 68, 67, 66, 65, 64, 63, 62, 61, 60,
            59, 58, 57, 55, 54, 53, 52, 51, 50, 49, 48, 47, 46, 45, 44, 43, 42, 41, 40, 39,
            38, 37, 34, 36, 33, 35, 31, 19, 30, 29, 27, 26, 24, 25, 21, 20, 22, 18, 17, 16,
            15, 14, 23, 32, 13, 28, 56, 12, 11, 10, 9, 8, 7, 6, 5, 4, 3, 2, 1, 0
        )

        /** 服务端返回顺序（collecttime 升序）→ 领域模型。 */
        val SERVER_ORDER: List<KugouPlaylistTrack> = CAPTURED_COLLECT_TIME.indices.map { i ->
            KugouPlaylistTrack(
                hash = "hash$i",
                name = "song$i",
                artist = "artist$i",
                album = null,
                durationMs = 0L,
                coverUrl = null,
                sortOrder = CAPTURED_SORT[i],
                collectTime = CAPTURED_COLLECT_TIME[i]
            )
        }
    }

    @Test
    fun `display order is server sort ascending`() {
        val ordered = PlaylistTrackOrder.byServerSort(SERVER_ORDER)!!

        assertEquals(SERVER_ORDER.size, ordered.size)
        assertEquals((0 until 100).toList(), ordered.map { it.sortOrder })
        // 新的在前：collectTime 严格递减（抓包里没有并列）
        assertEquals(1791127769L, ordered.first().collectTime)
        assertEquals(1715352877L, ordered.last().collectTime)
    }

    @Test
    fun `reverse of server array differs from real order at 48 of 100 positions`() {
        val ordered = PlaylistTrackOrder.byServerSort(SERVER_ORDER)!!
        val reversed = SERVER_ORDER.asReversed()

        val differing = ordered.indices.count { ordered[it].collectTime != reversed[it].collectTime }
        assertEquals(48, differing)
    }

    @Test
    fun `newest entries agree with reversal so only the tail looks wrong`() {
        val ordered = PlaylistTrackOrder.byServerSort(SERVER_ORDER)!!
        val reversed = SERVER_ORDER.asReversed()

        // 前 13 首（最新收藏的一批）两种口径完全一致，从第 14 首开始错位——
        // 这正是「有些顺序对、有些顺序错」的来源，不是随机乱序。
        val firstMismatch = ordered.indices.first { ordered[it].collectTime != reversed[it].collectTime }
        assertEquals(13, firstMismatch)
    }

    @Test
    fun `any track without sort means no ordering information`() {
        val legacy = SERVER_ORDER.mapIndexed { i, t ->
            if (i == 42) t.copy(sortOrder = PLAYLIST_SORT_MISSING) else t
        }

        assertNull(PlaylistTrackOrder.byServerSort(legacy))
    }

    @Test
    fun `missing sort on a single track is caught even if all others have it`() {
        assertNull(PlaylistTrackOrder.byServerSort(listOf(SERVER_ORDER.first().copy(sortOrder = PLAYLIST_SORT_MISSING))))
    }

    @Test
    fun `ties on sort prefer the more recently collected song`() {
        val older = SERVER_ORDER[0].copy(hash = "older", sortOrder = 7, collectTime = 100L)
        val newer = SERVER_ORDER[1].copy(hash = "newer", sortOrder = 7, collectTime = 200L)
        val first = SERVER_ORDER[2].copy(hash = "first", sortOrder = 3, collectTime = 50L)

        val ordered = PlaylistTrackOrder.byServerSort(listOf(older, newer, first))!!

        assertEquals(listOf("first", "newer", "older"), ordered.map { it.hash })
    }

    @Test
    fun `empty list has nothing to order but is not an error`() {
        assertEquals(emptyList<KugouPlaylistTrack>(), PlaylistTrackOrder.byServerSort(emptyList()))
    }
}
