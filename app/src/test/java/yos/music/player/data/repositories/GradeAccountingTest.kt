package yos.music.player.data.repositories

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 听歌时长记账口径（[GradeAccounting]）。
 *
 * 这三组判断是「云端时长为什么不动 / 设置页为什么一直显示未上报」的根因所在：
 * - 只看 status 不看 error_code，会把「没记账」当成功并清掉 pending；
 * - 基准只升不降，会让服务器不认的增量永久留在本地（实测差 6121s）；
 * - 心跳被冻结后不做上限，会让时长凭空暴涨。
 */
class GradeAccountingTest {

    // ---------- 成功判定 ----------

    @Test
    fun `status 1 with zero error code counts as success`() {
        assertTrue(GradeAccounting.isSuccess(status = 1, errorCode = 0))
    }

    @Test
    fun `status 1 but non-zero error code is not success`() {
        // 上游会回 status=1 + error_code!=0（本次未记账）。旧实现只认 status，
        // 于是把它当成功、清零 pending —— 服务器时长一直不涨正是这个形态。
        assertFalse(GradeAccounting.isSuccess(status = 1, errorCode = 20028))
    }

    @Test
    fun `status 0 is not success even with zero error code`() {
        // 真机日志里大量 {"status":0,"errcode":0,...}：上游限流/风控回包
        assertFalse(GradeAccounting.isSuccess(status = 0, errorCode = 0))
    }

    // ---------- 记账判定 ----------

    @Test
    fun `server reaching expected value means credited`() {
        assertTrue(GradeAccounting.isCredited(serverDsec = 1000, expectedDsec = 1000))
    }

    @Test
    fun `tolerance absorbs rounding jitter`() {
        assertTrue(GradeAccounting.isCredited(serverDsec = 996, expectedDsec = 1000))
        assertFalse(GradeAccounting.isCredited(serverDsec = 994, expectedDsec = 1000))
    }

    @Test
    fun `missing server value is treated as credited`() {
        // data.d_sec 缺失时判不了，按已记账处理——否则会反复重发同一段增量
        assertTrue(GradeAccounting.isCredited(serverDsec = 0, expectedDsec = 1000))
    }

    // ---------- 基准推进 ----------

    @Test
    fun `normal report advances local base`() {
        val base = GradeAccounting.nextBase(
            base = 1000, diff = 60, serverDsec = 1060, uncreditedStreak = 0
        )
        assertEquals(1060, base)
    }

    @Test
    fun `server ahead wins`() {
        // 别的设备听过 / 服务端补记：不跟服务器就会被上游拒
        val base = GradeAccounting.nextBase(
            base = 1000, diff = 60, serverDsec = 2000, uncreditedStreak = 0
        )
        assertEquals(2000, base)
    }

    @Test
    fun `single uncredited report does not lower base`() {
        // 上游偶发返回更新延迟前的旧 d_sec，一次不算证据
        val base = GradeAccounting.nextBase(
            base = 1000, diff = 60, serverDsec = 1000, uncreditedStreak = 0
        )
        assertEquals(1060, base)
    }

    @Test
    fun `persistent uncredited lowers base to server`() {
        // 连续 3 轮「status=1 但服务器不涨」= 该账号不吃 diff_sec 记账。
        // 此时必须以下调收口，否则本地虚高永久固化、设置页「未上报」永远清不掉。
        val base = GradeAccounting.nextBase(
            base = 1000, diff = 60, serverDsec = 400, uncreditedStreak = 2
        )
        assertEquals(400, base)
    }

    @Test
    fun `missing server value never lowers base`() {
        val base = GradeAccounting.nextBase(
            base = 1000, diff = 60, serverDsec = 0, uncreditedStreak = 5
        )
        assertEquals(1060, base)
    }

    // ---------- 对账 ----------

    @Test
    fun `reconcile consumes diff when server already credited`() {
        // 超时但服务器其实记上了：这段增量要消费掉，不能当没发生
        val base = GradeAccounting.baseAfterReconcile(
            base = 1000, diff = 60, serverDsec = 1060, credited = true
        )
        assertEquals(1060, base)
    }

    @Test
    fun `reconcile keeps local base when server did not move`() {
        // 真的没记账：基准不倒退（pending 由调用方保留重试）
        val base = GradeAccounting.baseAfterReconcile(
            base = 1000, diff = 60, serverDsec = 1000, credited = false
        )
        assertEquals(1000, base)
    }

    @Test
    fun `reconcile raises base when server is ahead`() {
        val base = GradeAccounting.baseAfterReconcile(
            base = 1000, diff = 60, serverDsec = 1500, credited = false
        )
        assertEquals(1500, base)
    }

    @Test
    fun `reconcile keeps base when query failed`() {
        val base = GradeAccounting.baseAfterReconcile(
            base = 1000, diff = 60, serverDsec = null, credited = false
        )
        assertEquals(1000, base)
    }

    // ---------- 计时上限 ----------

    @Test
    fun `normal tick passes through`() {
        assertEquals(30_000L, GradeAccounting.capPlayedMs(30_000L, intervalSec = 30))
    }

    @Test
    fun `frozen process cannot credit more than two intervals`() {
        // 进程被冻结 10 分钟后恢复：差额不可信，不能一次性记上 10 分钟
        assertEquals(60_000L, GradeAccounting.capPlayedMs(600_000L, intervalSec = 30))
    }

    @Test
    fun `negative elapsed never credits negative time`() {
        assertEquals(0L, GradeAccounting.capPlayedMs(-500L, intervalSec = 30))
    }

    @Test
    fun `zero interval does not cap to zero`() {
        // intervalSec 传 0（误用）时不能把整拍抹成 0
        assertEquals(15_000L, GradeAccounting.capPlayedMs(15_000L, intervalSec = 0))
    }
}
