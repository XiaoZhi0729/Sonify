package yos.music.player.data.repositories

/**
 * 「流量下改用本机已缓存的高音源」的择优算法（纯函数，JVM 可穷举）。
 *
 * ## 要解决的问题
 *
 * 省流量的初衷是：在家连 Wi-Fi 时把高音质（Hi-Res）缓存下来，出门用流量时直接放本地那份，
 * 不必再下 128K。但旧实现只按网络形态取偏好档、从不回看本机有什么，于是：
 *
 * - 这首歌**只**在 Wi-Fi 下听过（本地只有 Hi-Res 字节、没有 128K 字节）→ 流量下仍按
 *   128K 重新下载，比直接放本地 Hi-Res 更费流量。这正是要消灭的反向浪费。
 *
 * ## 为什么不是"有缓存就升档"，也不是"缓存完整才升档"
 *
 * 两个极端都会浪费：
 * - 只看"有没有缓存"：只下到 10% 的 Hi-Res 也会被选中，剩下 90%（可能几十 MB）现下，
 *   远不如老老实实下一首 3MB 的 128K。
 * - 只看"是否整首缓存"：明明只剩 200KB 就能补完 Hi-Res（比整首 128K 还少），却偏要重下
 *   128K，把已有的高音质缓存白扔。
 *
 * 所以比较的是**还要下多少字节**（[Remaining]），取最少的那一档；平手时偏向更高音质
 * （同样的流量成本，听感更好）。只有当高音源确实比意图档更省时才改动播放档位，否则返回
 * null 表示"维持原样"——调用方据此完全走原路径，行为与改动前一致。
 *
 * ## 两类"金额"的性质必须分清
 *
 * - `cachedBytes` / `fullyCached` 来自 Media3 的缓存索引，是**事实**（精确）。
 * - `estimateTotal` 是**估算**，只能由标称码率乘时长得出（[KugouQuality.estimateTotalBytes]
 *   ），且刻意取码率上限，使估算偏大 ⇒ 已缓存比例偏小 ⇒ 决策偏向保守（见该函数注释）。
 *
 * 因此只有"事实层面够了"（[fullyCached]，即 span 精确证明整首齐了）才允许走零网络通道；
 * 估算只参与"哪一档更省"的相对比较。
 */
object CachedQualityPreference {

    /**
     * 一次决策的结果。
     *
     * @property tier 实际应该播放的档位（必定不低于是次意图档）
     * @property fullyCached 该档位是否已被**精确**证明整首在本地（可零网络播放）
     * @property remainingBytes 该档位还需下载的估算字节数（[fullyCached] 时为 0，
     *   时长未知且非完整缓存时为 [UNKNOWN_REMAINING]）
     */
    data class Choice(
        val tier: String,
        val fullyCached: Boolean,
        val remainingBytes: Long
    )

    /** 时长未知、又没被精确证明缓存完整：没法定量比较，只能参与"肯定更省"的判断。 */
    const val UNKNOWN_REMAINING = Long.MAX_VALUE

    /**
     * 挑选实际播放档位。
     *
     * @param intentTier 本次意图档（用户设置页偏好或本曲覆盖），候选集的下界
     * @param durationMs 本曲时长；<=0 表示未知，此时无法估算总大小
     * @param cachedBytes 查询某档位已缓存字节数（事实）
     * @param fullyCached 查询某档位是否整首已缓存（事实，由缓存 span 精确判定）
     * @param estimateTotal 估算某档位整首字节数（估算；返回 0 表示无法估算）
     * @return 需要改播更高档时返回 [Choice]；维持意图档时返回 null
     */
    fun choose(
        intentTier: String,
        durationMs: Long,
        cachedBytes: (tier: String) -> Long,
        fullyCached: (tier: String) -> Boolean,
        estimateTotal: (tier: String, durationMs: Long) -> Long
    ): Choice? {
        val intent = KugouQuality.parse(intentTier) ?: return null
        val intentRank = KugouQuality.rank(intent)
        // 候选 = 意图档本身 + 所有更高档（从不建议降档：那是另一个产品决策，
        // 用户明确选了哪一档就至少给到哪一档，省流量不能以偷偷降音质为代价）
        val candidates = KugouQuality.ALL.drop(intentRank)

        var best: Choice? = null
        for (tier in candidates) {
            val isFull = fullyCached(tier)
            val estimate = if (isFull) 0L else estimateTotal(tier, durationMs)
            val remaining = when {
                isFull -> 0L
                // 估算不出总大小时，唯一能确定的是"不完整 ⇒ 还要下 > 0"，
                // 用 UNKNOWN 参与比较：它永远不会赢过 0（完整缓存），
                // 也永远不会被当作"更省"去推翻一个已知的更小剩余
                estimate <= 0L -> UNKNOWN_REMAINING
                else -> (estimate - cachedBytes(tier).coerceAtLeast(0L)).coerceAtLeast(0L)
            }
            // 平手取更高档 = "取最后一个足够好的"：candidates 从低到高，用 <= 比较即可
            if (best == null || remaining <= best.remainingBytes) {
                best = Choice(tier, isFull, remaining)
            }
        }
        val chosen = best ?: return null
        if (chosen.tier == intent) return null

        // 意图档自己还要下多少：作为"要不要动"的基准。用同一组事实/估算求得，
        // 保证两者可比（不能拿估算值去和一个精确值比）。
        val intentIsFull = fullyCached(intent)
        val intentEstimate = if (intentIsFull) 0L else estimateTotal(intent, durationMs)
        val intentRemaining = when {
            intentIsFull -> 0L
            intentEstimate <= 0L -> UNKNOWN_REMAINING
            else -> (intentEstimate - cachedBytes(intent).coerceAtLeast(0L)).coerceAtLeast(0L)
        }

        // 只有确实更省才升档；相等不动（同样的流量下听感更好，但"不动"意味着零额外风险，
        // 且不会让徽标在用户没请求时跳档）。UNKNOWN 与 UNKNOWN 相等 ⇒ 也不动。
        return if (chosen.remainingBytes < intentRemaining) chosen else null
    }
}
