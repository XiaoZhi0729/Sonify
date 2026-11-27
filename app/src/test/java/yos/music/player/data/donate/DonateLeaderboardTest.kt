package yos.music.player.data.donate

import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DonateLeaderboardTest {
    @Test
    fun parseDonorsJson_handlesRealSparsePayloadWithoutMessageOrDate() {
        val raw = """
            {"code":0,"data":[{"name":"test","amount":114514,"createdAt":1795701244336}]}
        """.trimIndent()

        val records = DonateRepository.parseDonorsJson(raw)
        assertEquals(1, records.size)
        val first = records.first()
        assertEquals("test", first.name)
        assertEquals(114514.0, first.amount, 0.0001)
        assertNull(first.message)
        assertNull(first.date)
        assertEquals(1795701244336L, first.createdAt)
    }

    @Test(expected = IllegalStateException::class)
    fun parseDonorsJson_rejectsNonZeroCode() {
        DonateRepository.parseDonorsJson("""{"code":500,"data":[]}""")
    }

    @Test(expected = IllegalStateException::class)
    fun parseDonorsJson_rejectsMalformedRoot() {
        DonateRepository.parseDonorsJson("""[]""")
    }

    @Test
    fun buildLeaderboard_aggregatesRepeatDonorsRanksTop3AndSortsRecent() {
        val records = listOf(
            DonorRecord("热心网友小王", 66.6, "支持开发，加油！", "2026-11-26", 1732629600000L),
            DonorRecord("林深见鹿", 100.0, "第二次支持", "2026-11-26", 1732631000000L),
            DonorRecord("林深见鹿", 88.8, "第一次支持", "2026-11-24", 1732453200000L),
            DonorRecord("Aiden", 100.0, "赞一个", "2026-11-25", 1732539600000L),
            DonorRecord("热心网友小王", 33.4, null, "2026-11-21", 1732194000000L),
            DonorRecord("   ", 12.0, null, null, 1732021200000L)
        )

        val board = DonateRepository.buildLeaderboard(
            records = records,
            anonymousName = "匿名支持者",
            timeZone = TimeZone.getTimeZone("Asia/Shanghai")
        )

        assertEquals(400.8, board.totalAmount, 0.001)
        assertEquals(4, board.supporterCount)
        assertEquals(6, board.recordCount)

        // Top 3: 林深见鹿 (188.8), 热心网友小王 (100.0, newer createdAt), Aiden (100.0, older createdAt)
        assertEquals(3, board.top3.size)
        assertEquals("林深见鹿", board.top3[0].name)
        assertEquals(188.8, board.top3[0].totalAmount, 0.001)
        assertEquals(2, board.top3[0].count)
        assertEquals("第二次支持", board.top3[0].latestMessage)

        assertEquals("热心网友小王", board.top3[1].name)
        assertEquals(100.0, board.top3[1].totalAmount, 0.001)
        assertEquals(2, board.top3[1].count)
        assertEquals("支持开发，加油！", board.top3[1].latestMessage)

        assertEquals("Aiden", board.top3[2].name)
        assertEquals(1, board.top3RankByName["林深见鹿"])
        assertEquals(2, board.top3RankByName["热心网友小王"])
        assertEquals(3, board.top3RankByName["Aiden"])
        assertNull(board.top3RankByName["匿名支持者"])

        // Recent sorted descending by createdAt
        assertEquals("林深见鹿", board.recent.first().name)
        assertEquals(1732631000000L, board.recent.first().createdAt)
        assertEquals("   ", board.recent.last().name)
    }

    @Test
    fun formatters_formatAmountDateAndInitialCleanly() {
        assertEquals("¥0", DonateRepository.formatAmount(0.0))
        assertEquals("¥100", DonateRepository.formatAmount(100.0))
        assertEquals("¥66.6", DonateRepository.formatAmount(66.6))
        assertEquals("¥18.88", DonateRepository.formatAmount(18.88))

        val shanghai = TimeZone.getTimeZone("Asia/Shanghai")
        assertEquals("2026-11-26", DonateRepository.resolveDate(" 2026-11-26 ", 0L, shanghai))
        assertEquals("2024-11-26", DonateRepository.resolveDate(null, 1732629600000L, shanghai))
        assertEquals("--", DonateRepository.resolveDate("", 0L, shanghai))

        assertEquals("热", DonateRepository.extractInitial(" 热心网友小王"))
        assertEquals("A", DonateRepository.extractInitial("aiden"))
        assertEquals("🎵", DonateRepository.extractInitial("🎵Music"))
        assertEquals("友", DonateRepository.extractInitial("   ", "友"))
    }

    @Test
    fun isCacheFresh_respectsTenMinuteTtl() {
        val now = 1_000_000_000L
        assertTrue(DonateRepository.isCacheFresh(now - 60_000L, now))
        assertFalse(DonateRepository.isCacheFresh(now - DonateRepository.CACHE_TTL_MS, now))
        assertFalse(DonateRepository.isCacheFresh(0L, now))
        assertFalse(DonateRepository.isCacheFresh(now + 10_000L, now))
    }
}
