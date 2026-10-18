package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.data.repositories.KugouCommentRepository.KugouComment

/**
 * 评论区纯逻辑单测（不触网、不依赖 org.json）：
 * 楼中楼树组装、引用后缀剥离、时间解析、本人评论判定、排序稳定性。
 */
class KugouCommentRepositoryTest {

    private fun comment(
        id: String,
        parentId: String? = null,
        time: Long = 0,
        likes: Int = 0,
        userId: String? = null,
        content: String = "c$id"
    ) = KugouComment(
        id = id,
        username = "u$id",
        avatar = null,
        content = content,
        timeSec = time,
        likes = likes,
        replyCount = 0,
        isHot = false,
        isStar = false,
        parentId = parentId,
        specialId = null,
        tid = null,
        userId = userId
    )

    @Test
    fun `reply tree nests children under parent and keeps parent first`() {
        val nodes = KugouCommentRepository.buildReplyTree(
            listOf(
                comment("root", parentId = "0", time = 10),
                comment("a", parentId = "root", time = 30),
                comment("b", parentId = "root", time = 20),
                comment("a1", parentId = "a", time = 40)
            )
        )
        assertEquals(listOf("root", "a", "a1", "b"), nodes.map { it.reply.id })
        assertEquals(listOf(0, 1, 2, 1), nodes.map { it.depth })
        assertTrue(nodes.none { it.isOrphan })
    }

    @Test
    fun `missing parent becomes orphan at depth 0`() {
        val nodes = KugouCommentRepository.buildReplyTree(
            listOf(comment("x", parentId = "not-loaded"))
        )
        assertEquals(1, nodes.size)
        assertEquals(0, nodes[0].depth)
        assertTrue(nodes[0].isOrphan)
    }

    @Test
    fun `cycle does not loop and nothing is dropped`() {
        val nodes = KugouCommentRepository.buildReplyTree(
            listOf(
                comment("p", parentId = "q"),
                comment("q", parentId = "p")
            )
        )
        assertEquals(2, nodes.size)
        assertEquals(setOf("p", "q"), nodes.map { it.reply.id }.toSet())
    }

    @Test
    fun `duplicate ids keep first occurrence`() {
        val nodes = KugouCommentRepository.buildReplyTree(
            listOf(comment("a", time = 1), comment("a", time = 2))
        )
        assertEquals(1, nodes.size)
        assertEquals(1L, nodes[0].reply.timeSec)
    }

    @Test
    fun `strip reply quote only removes trailing reference`() {
        assertEquals(
            "好聽",
            KugouCommentRepository.stripReplyQuote("好聽//@小明:原評論")
        )
        // 没有「用户名:」结构的不当引用
        assertEquals(
            "a//@b",
            KugouCommentRepository.stripReplyQuote("a//@b")
        )
        // 只剩引用时不截断
        assertEquals(
            "//@小明:原文",
            KugouCommentRepository.stripReplyQuote("//@小明:原文")
        )
        assertEquals("", KugouCommentRepository.stripReplyQuote(""))
    }

    @Test
    fun `parse comment time accepts seconds, millis and beijing datetime`() {
        assertEquals(1_700_000_000L, KugouCommentRepository.parseCommentTime(1_700_000_000L))
        assertEquals(1_700_000_000L, KugouCommentRepository.parseCommentTime("1700000000"))
        assertEquals(1_700_000_000L, KugouCommentRepository.parseCommentTime(1_700_000_000_000L))
        // 2023-11-15 06:13:20 +08:00 == 1700000000
        assertEquals(
            1_700_000_000L,
            KugouCommentRepository.parseCommentTime("2023-11-15 06:13:20")
        )
        assertEquals(0L, KugouCommentRepository.parseCommentTime(null))
        assertEquals(0L, KugouCommentRepository.parseCommentTime("not a date"))
    }

    @Test
    fun `own comment requires both ids non blank and equal`() {
        val c = comment("1", userId = "42")
        assertTrue(KugouCommentRepository.isOwnComment(c, "42"))
        assertTrue(KugouCommentRepository.isOwnComment(c, " 42 "))
        assertFalse(KugouCommentRepository.isOwnComment(c, null))
        assertFalse(KugouCommentRepository.isOwnComment(comment("2"), "42"))
    }

    @Test
    fun `sort by likes is stable for equal counts`() {
        val sorted = KugouCommentRepository.sortByLikes(
            listOf(comment("a", likes = 1), comment("b", likes = 5), comment("c", likes = 1))
        )
        assertEquals(listOf("b", "a", "c"), sorted.map { it.id })
    }

    @Test
    fun `sort by time desc is stable for equal times`() {
        val sorted = KugouCommentRepository.sortByTimeDesc(
            listOf(comment("a", time = 1), comment("b", time = 3), comment("c", time = 3))
        )
        assertEquals(listOf("b", "c", "a"), sorted.map { it.id })
    }

    @Test
    fun `like count formatting follows language`() {
        assertEquals("999", KugouCommentRepository.formatLikes(999, "zh"))
        assertEquals("1万", KugouCommentRepository.formatLikes(10_000, "zh"))
        assertEquals("12万", KugouCommentRepository.formatLikes(123_456, "zh"))
        assertEquals("1万", KugouCommentRepository.formatLikes(10_000, "ja"))
        assertEquals("10K", KugouCommentRepository.formatLikes(10_000, "en"))
        assertEquals("123K", KugouCommentRepository.formatLikes(123_456, "en"))
    }

    @Test
    fun `relative time formatting returns structured values`() {
        val now = 1_700_000_000L
        assertEquals(
            KugouCommentRepository.CommentTime.JustNow,
            KugouCommentRepository.commentTime(now - 10, now)
        )
        assertEquals(
            KugouCommentRepository.CommentTime.Minutes(5),
            KugouCommentRepository.commentTime(now - 300, now)
        )
        assertEquals(
            KugouCommentRepository.CommentTime.Hours(2),
            KugouCommentRepository.commentTime(now - 7200, now)
        )
        assertEquals(
            KugouCommentRepository.CommentTime.Days(3),
            KugouCommentRepository.commentTime(now - 3 * 86_400, now)
        )
        assertEquals(
            KugouCommentRepository.CommentTime.Date("2023-10-06"),
            KugouCommentRepository.commentTime(now - 40L * 86_400, now)
        )
        assertEquals(
            KugouCommentRepository.CommentTime.Date(""),
            KugouCommentRepository.commentTime(0, now)
        )
    }
}
