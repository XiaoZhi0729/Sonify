package yos.music.player.data.repositories

/**
 * 音质切换决策（纯函数）。
 *
 * 为什么单独抽出来：这段判断决定"用户点一下档位之后，正在播的歌要不要重开"，
 * 是 A2（永不跳歌）与 A3（无变化零中断）的落点。它原先长在 MediaController 的
 * 协程里，依赖播放器实例，无法在 JVM 里穷举；抽出来后每条分支都能表格驱动测。
 *
 * 两个时点各判一次：
 * - [before]：探测前——意图没变就什么都不做（不发解析、不重建媒体周期）；
 * - [after] ：探测后——失败回滚 / 听感相同不重开 / 确有变化才重开一次。
 */
object QualitySwitchPolicy {

    enum class Before { NOOP_INTENT_UNCHANGED, PROBE }

    enum class After { ROLLBACK, NO_RELOAD, COMMIT_RELOAD }

    /**
     * 探测前判定。"意图未变"要求同时满足：目标档等于当前生效（或正在切换中）的意图档，
     * **且**本曲覆盖已经就是它——只满足前者可能是"偏好恰好等于目标档"，此时仍需要写下
     * 本曲覆盖，否则用户的选择会在下一首之后被偏好改掉。
     */
    fun before(target: String, pending: String?, factIntent: String?, override: String?): Before {
        val effective = pending ?: factIntent
        return if (target == effective && override == target) {
            Before.NOOP_INTENT_UNCHANGED
        } else {
            Before.PROBE
        }
    }

    /**
     * 探测后判定。
     *
     * - ROLLBACK：探测失败。现场未动，且要把用户刚写入的意图撤回——留着它会让这一首
     *   此后每次播放都重复撞同一堵墙（甚至因负缓存直接跳歌）。
     * - NO_RELOAD：拿到的规格与切换前完全相同。用户不该为"没有变化的选择"听到一次卡顿。
     * - COMMIT_RELOAD：确有变化，恰好一次重开（进度与播放态由提交路径保持）。
     *
     * 任一侧规格未知（null）时一律走 COMMIT_RELOAD：宁可重开一次让音频按新意图重解析，
     * 也不要在"不知道有没有变化"的前提下假装没变化。
     */
    fun after(probeFailed: Boolean, obtained: String?, previousPlaying: String?): After = when {
        probeFailed -> After.ROLLBACK
        obtained != null && previousPlaying != null && obtained == previousPlaying -> After.NO_RELOAD
        else -> After.COMMIT_RELOAD
    }
}
