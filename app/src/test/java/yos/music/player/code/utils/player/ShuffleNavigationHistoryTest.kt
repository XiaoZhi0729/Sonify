package yos.music.player.code.utils.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 随机手动切歌的轮次语义：每一轮队列歌曲各出现一次、相邻轮次首尾不重复、
 * 反向沿真实走过的路径回退并可继续扩展。
 */
class ShuffleNavigationHistoryTest {

    private val ids = listOf("a", "b", "c", "d", "e")

    /** 固定排列，便于断言轮次内容；真实播放用随机源。 */
    private fun history(shuffle: (List<String>) -> List<String> = { it.reversed() }) =
        ShuffleNavigationHistory(shuffle)

    private fun forward(h: ShuffleNavigationHistory, queue: List<String>, from: String, times: Int): List<String> {
        val visited = mutableListOf<String>()
        var current = from
        repeat(times) {
            val next = h.next(queue, current) ?: return visited
            visited += next
            current = next
        }
        return visited
    }

    private fun backward(h: ShuffleNavigationHistory, queue: List<String>, from: String, times: Int): List<String> {
        val visited = mutableListOf<String>()
        var current = from
        repeat(times) {
            val prev = h.previous(queue, current) ?: return visited
            visited += prev
            current = prev
        }
        return visited
    }

    @Test
    fun oneRoundVisitsEverySongExactlyOnce() {
        val h = history()
        val visited = forward(h, ids, "a", ids.size - 1)
        assertEquals(ids.size, visited.size + 1)
        assertEquals(ids.toSet(), (visited + "a").toSet())
        assertEquals(ids.size, (visited + "a").distinct().size)
    }

    @Test
    fun consecutiveRoundsEachHaveNoDuplicates() {
        val h = history()
        // 含起点共 4 个完整轮次
        val visited = listOf("a") + forward(h, ids, "a", ids.size * 4 - 1)
        assertEquals(ids.size * 4, visited.size)
        visited.chunked(ids.size).forEach { round ->
            assertEquals("round must contain every song once: $round", ids.size, round.distinct().size)
            assertEquals(ids.toSet(), round.toSet())
        }
    }

    @Test
    fun roundBoundaryNeverRepeatsTheSameSong() {
        val h = history()
        val visited = forward(h, ids, "c", ids.size * 3)
        var previous = "c"
        visited.forEach { next ->
            assertNotEquals("boundary repeat: $previous -> $next", previous, next)
            previous = next
        }
    }

    @Test
    fun boundaryRepeatIsBrokenEvenWithARepeatingPermutation() {
        // 固定排列下第二轮首曲天然等于第一轮末曲，必须交换掉
        val two = listOf("a", "b")
        val h = history { it }
        val visited = forward(h, two, "b", 3)
        var previous = "b"
        visited.forEach { next ->
            assertNotEquals("boundary repeat: $previous -> $next", previous, next)
            previous = next
        }
    }

    @Test
    fun previousWalksBackTheSamePath() {
        val h = history()
        val forwardPath = listOf("a") + forward(h, ids, "a", 3)
        val back = backward(h, ids, forwardPath.last(), 3)
        assertEquals(forwardPath.reversed().drop(1), back)
    }

    @Test
    fun crossingForwardThenBackwardKeepsOneContinuousPath() {
        val h = history()
        val forwardPath = listOf("a") + forward(h, ids, "a", ids.size + 1)
        val current = forwardPath.last()
        val back = backward(h, ids, current, ids.size)
        val again = forward(h, ids, back.last(), ids.size)
        // 回到原路径上，不应重新随机出别的前进序列
        assertEquals(forwardPath.drop(2).take(ids.size), again)
        assertEquals(current, again.last())
    }

    @Test
    fun backwardPastTheStartExtendsAnEarlierRound() {
        val h = history()
        // 反向走满两轮：反向序列本身按轮次对齐，每块应各含一次全部歌曲
        val back = backward(h, ids, "c", ids.size * 2)
        assertEquals(ids.size * 2, back.size)
        back.chunked(ids.size).forEach { round ->
            assertEquals("round must contain every song once: $round", ids.size, round.distinct().size)
            assertEquals(ids.toSet(), round.toSet())
        }
        var previous = "c"
        back.forEach { next ->
            assertNotEquals(previous, next)
            previous = next
        }
    }

    @Test
    fun changingQueueMembersRebuildsAroundCurrentSong() {
        val h = history()
        forward(h, ids, "a", 2)
        val smaller = listOf("a", "b", "c")
        val next = h.next(smaller, "b")
        assertTrue(next != null && next in smaller)
        // 旧队列的歌曲不能出现在新轮次里
        val visited = forward(h, smaller, next!!, smaller.size * 2)
        assertTrue((visited + next).all { it in smaller })
    }

    @Test
    fun reorderingTheSameMembersKeepsHistory() {
        val h = history()
        val first = h.next(ids, "a")!!
        // 成员不变、只是顺序不同：历史仍以 mediaId 为准，上一首应回到起点
        assertEquals("a", h.previous(ids.reversed(), first))
    }

    @Test
    fun clearedHistoryStartsOverFromCurrentSong() {
        val h = history()
        forward(h, ids, "a", 3)
        h.clear()
        val next = h.next(ids, "d")
        assertTrue(next != null && next != "d" && next in ids)
    }

    @Test
    fun singleSongQueueHasNowhereToGo() {
        val h = history()
        assertNull(h.next(listOf("a"), "a"))
        assertNull(h.previous(listOf("a"), "a"))
    }

    @Test
    fun observeFollowsExpectedTransitionWithoutLosingHistory() {
        val h = history()
        val next = h.next(ids, "a")!!
        h.observe(ids, next)
        // 已确认转场后再前进，仍应是原路径的下一首而不是重新随机
        val second = h.next(ids, next)!!
        val fresh = history().let { forward(it, ids, "a", 2) }
        assertEquals(fresh, listOf(next, second))
    }

    @Test
    fun observeWithUnknownSongRebuildsFromIt() {
        val h = history()
        forward(h, ids, "a", 2)
        h.observe(ids, "e")
        val next = h.next(ids, "e")
        assertTrue(next != null && next != "e" && next in ids)
    }
}
