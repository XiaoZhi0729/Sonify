package yos.music.player.data.repositories

/**
 * 听歌时长记账的纯决策逻辑（[ListeningGradeTracker] 与单测共用，勿在两边各写一份）。
 *
 * 这些判断是「云端时长为什么不涨」这类问题的核心，历史上有过两次写错的形态：
 * 1. 只认 `status==1` 而不看 `error_code` → 服务器没记账却被当成功，pending 被清零；
 * 2. 基准只升不降 → 服务器不认的增量永久留在本地，设置页「未上报」永远清不掉。
 */
object GradeAccounting {

    /** 服务器 d_sec 与本地推论的容差（秒）：上游取整/抖动。 */
    const val CREDIT_TOLERANCE_SEC = 5L

    /**
     * 连续几次「status=1 但服务器值不涨」后，本地基准改为跟随服务器（可降）。
     * 1 次不算：上游偶发返回更新延迟前的旧 d_sec。
     */
    const val UNCREDITED_BASE_RESET = 3

    /** 上报是否被服务器记账：`status==1` 且 `error_code==0`，两者缺一不可。 */
    fun isSuccess(status: Int, errorCode: Int): Boolean = status == 1 && errorCode == 0

    /**
     * 服务器是否真的把本次增量记上了。
     *
     * @param serverDsec 回包里的 `data.d_sec`；<=0 表示没带（判不了，按已记账处理，
     *   不能因为字段缺失就反复重发）。
     * @param expectedDsec 本次上报的 `d_sec`（本地基准+增量）。
     */
    fun isCredited(serverDsec: Long, expectedDsec: Long): Boolean =
        serverDsec <= 0 || serverDsec + CREDIT_TOLERANCE_SEC >= expectedDsec

    /**
     * 上报成功后新的本地基准。
     *
     * @param base 上报前的基准
     * @param diff 本次增量（秒）
     * @param serverDsec 回包 `data.d_sec`（<=0 = 未带）
     * @param uncreditedStreak 本次之前连续「未记账」的次数
     *
     * 三种情形：
     * - 服务器领先（别的设备听过 / 服务端补记）→ 取服务器值，否则下轮 d_sec 会被拒；
     * - 服务器连续多轮不涨 → **以服务器为准下调**（清掉本地虚高）；
     * - 正常 → 本地推进 `base+diff`。
     */
    fun nextBase(base: Long, diff: Long, serverDsec: Long, uncreditedStreak: Int): Long = when {
        serverDsec <= 0 -> base + diff
        !isCredited(serverDsec, base + diff) && uncreditedStreak + 1 >= UNCREDITED_BASE_RESET -> serverDsec
        else -> maxOf(base + diff, serverDsec)
    }

    /**
     * 对账后（连败 3 拍查了服务器）新的本地基准。
     *
     * @param credited 服务器已推进到 `base+diff` 附近 = 之前的失败其实记上了
     */
    fun baseAfterReconcile(base: Long, diff: Long, serverDsec: Long?, credited: Boolean): Long = when {
        serverDsec == null -> base
        // 已记账：把这截增量算进去（服务器值可能还没追上，但不能丢）
        credited -> maxOf(base + diff, serverDsec)
        // 未记账：只在服务器领先时抬（本地记账不因一次失败倒退）
        serverDsec > base -> serverDsec
        else -> base
    }

    /**
     * 单拍可计入的最大毫秒：心跳被冻结/挂起后 `elapsedRealtime` 差值会远超一拍，
     * 超出 [intervalSec]×2 的部分不可信（会让时长凭空暴涨）。
     *
     * `intervalSec<=0` 视为「没有周期信息」→ 不设上限（把整拍抹成 2 秒比不设上限更糟：
     * 每拍都少记，累积起来是系统性欠账）。
     */
    fun capPlayedMs(rawMs: Long, intervalSec: Int): Long {
        if (rawMs <= 0L) return 0L
        if (intervalSec <= 0) return rawMs
        val cap = intervalSec * 2_000L
        return rawMs.coerceAtMost(cap)
    }

    /** pending 上限（毫秒）：攒过 30 分钟不再加，防长时间离线后一次性虚记。 */
    const val MAX_PENDING_MS = 30 * 60 * 1000L
}
