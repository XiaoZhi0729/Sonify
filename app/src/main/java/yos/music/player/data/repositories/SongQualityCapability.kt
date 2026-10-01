package yos.music.player.data.repositories

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.ConcurrentHashMap
import yos.music.player.data.NormalSaver

/**
 * 本曲音质能力表：记录"这一首被证明拿不到哪些档、拿到过哪些档"。
 *
 * 为什么需要它：能力证据只存在于"最近一次解析的事实"里时，置灰状态会随意图漂移——
 * 用户请求 Hi-Res 被拒（证据：Hi-Res 不可用）后改点无损，最后一次解析变成
 * CONFIRMED，Hi-Res 的置灰就消失了，下一次点它又要重跑一遍已知无果的解析。
 * 本表把证据从"单条事实"升级为"按档位累积的集合"，置灰因此稳定且可提前呈现。
 *
 * 单调推断（酷狗档位阶梯：高档可用必然低档也可用）：
 * - 请求 A 只拿到 O ⇒ 档位 ≥ A 全部不可用，档位 ≤ O 全部可用；
 * - 真拿到 T ⇒ 档位 ≤ T 全部可用，并从"不可用"集合里移除（会员/音源变化时自愈）。
 *
 * 两条入口：[recordDowngrade]/[recordAvailable] 记单次结论，[recordProbeChain] 一次记整条
 * 降级链（链上每一档都被实测过，不分档记下来就会让用户不得不逐档去试）。
 *
 * 只记**有证据**的结论：解析失败、网络不可达、服务端未回写档位都不产生任何记录，
 * 避免把一次抖动写成长期置灰。
 */
object SongQualityCapabilityStore {

    /** 证据保留 24 小时：会员状态、音源上架都会让"不可用"过期。 */
    private const val TTL_MS = 24 * 60 * 60 * 1000L

    /** 持久化键前缀（MMKV yos_data_normal）。 */
    private const val KEY_PREFIX = "quality_capability_"

    private class Capability {
        val provenUnavailable = mutableSetOf<String>()
        var provenAvailableTier: String? = null
        var updatedAt: Long = 0L
    }

    private val byHash = ConcurrentHashMap<String, Capability>()

    /**
     * 结论版本号。任何一次写入都 +1，UI 读它才能在探测结果到达时重组——
     * 否则“播放即探测”拿到的新结论要等到下一次解析才会反映到面板上。
     */
    @Stable
    val version = mutableIntStateOf(0)

    /** 请求 [askedTier] 只拿到 [obtainedTier]：记 asked 及以上不可用、obtained 及以下可用。 */
    fun recordDowngrade(hash: String?, askedTier: String?, obtainedTier: String?) {
        val askedRank = KugouQuality.rankOrNull(askedTier) ?: return
        val cap = capability(hash ?: return, write = true)
        obtainedTier?.let { raiseAvailable(cap, it) }
        KugouQuality.ALL.forEach { tier ->
            if ((KugouQuality.rankOrNull(tier) ?: -1) >= askedRank) cap.provenUnavailable.add(tier)
        }
        reconcile(cap)
        persist(hash, cap)
    }

    /** 真拿到了 [tier]：它及更低档转为可用（自愈）。 */
    fun recordAvailable(hash: String?, tier: String?) {
        val cap = capability(hash ?: return, write = true)
        raiseAvailable(cap, tier ?: return)
        reconcile(cap)
        persist(hash, cap)
    }

    /**
     * 一次记录整条降级链的全部探测结论：[probes] 每项是（请求档位 → 服务端实发档位）。
     *
     * 为什么必须有它：降级链本来就会把 `请求档 → … → 标准` 逐档问一遍，也就是说
     * 链上每一档都被**实测过**了；但历史上只记住最终采用的那一档，中间结论全部丢弃，
     * 于是面板只能“用户点一档、才知道一档”：Hi-Res 被拒后无损仍然可点，点下去又悄悄
     * 退回 320K，用户得连吃两次同样的亏才知道这首歌只到 320。一次解析就能确定的事，
     * 不该让用户用点击去试。
     *
     * 只记有证据的结论：实发档位为空（服务端未回写 / 值不可识别）或解析失败都不产生
     * 任何记录，避免把一次网络抖动写成长期置灰。
     */
    fun recordProbeChain(hash: String?, probes: List<Pair<String, String?>>) {
        val lower = hash?.lowercase() ?: return
        val cap = capability(lower, write = true)
        var changed = false
        for ((askedRaw, stampedRaw) in probes) {
            val asked = KugouQuality.parse(askedRaw) ?: continue
            val obtained = KugouQuality.parse(stampedRaw) ?: continue
            val askedRank = KugouQuality.rank(asked)
            if (KugouQuality.rank(obtained) < askedRank) {
                // 请求这一档却拿到更低的一档 = 这一档及以上都拿不到
                KugouQuality.ALL.forEach { tier ->
                    if (KugouQuality.rank(tier) >= askedRank) cap.provenUnavailable.add(tier)
                }
            }
            // 能拿到实发这一档（以及更低）：自愈路径，会员开通/音源上架后置灰会退去
            raiseAvailable(cap, obtained)
            changed = true
        }
        if (!changed) return
        reconcile(cap)
        persist(lower, cap)
    }

    /** 已证明拿不到的档位集合（无记录时为空集 = 能力未知，不置灰任何档）。 */
    fun provenUnavailableOf(hash: String?): Set<String> {
        if (hash.isNullOrEmpty()) return emptySet()
        val cap = memoryOrDisk(hash) ?: return emptySet()
        synchronized(cap) { return cap.provenUnavailable.toSet() }
    }

    /**
     * 哪些档位还没有结论，**从最高档往下**排。
     *
     * 顶部优先不是实现方便，是信息量：阶梯单调，一次“请求 high 实得 flac”就同时
     * 定了 high 拿不到与 flac/320/128 拿得到，四个档位一问就完；从低往高问则最多要四问。
     * 已有结论的（在不可用集合里，或不高于已证明可用档）不再重复消耗请求。
     */
    fun tiersNeedingProbe(hash: String?): List<String> {
        if (hash.isNullOrEmpty()) return KugouQuality.ALL.reversed()
        val cap = memoryOrDisk(hash) ?: return KugouQuality.ALL.reversed()
        val availableRank = synchronized(cap) {
            cap.provenAvailableTier?.let { KugouQuality.rankOrNull(it) }
        }
        return KugouQuality.ALL.reversed().filter { tier ->
            val rank = KugouQuality.rank(tier)
            if (rank <= (availableRank ?: -1)) return@filter false
            synchronized(cap) { tier !in cap.provenUnavailable }
        }
    }

    /** 测试专用：清空能力表。 */
    internal fun clearForTest() {
        byHash.clear()
    }

    private fun raiseAvailable(cap: Capability, tier: String) {
        val rank = KugouQuality.rankOrNull(tier) ?: return
        val currentRank = cap.provenAvailableTier?.let { KugouQuality.rankOrNull(it) } ?: -1
        if (rank > currentRank) cap.provenAvailableTier = tier
    }

    /** 一致性收口：已证明可用的档不得同时出现在"不可用"集合里。 */
    private fun reconcile(cap: Capability) {
        val availableRank = cap.provenAvailableTier?.let { KugouQuality.rankOrNull(it) }
        if (availableRank != null) {
            cap.provenUnavailable.removeAll { (KugouQuality.rankOrNull(it) ?: -1) <= availableRank }
        }
    }

    private fun capability(hash: String, write: Boolean): Capability =
        memoryOrDisk(hash) ?: Capability().also {
            val lower = hash.lowercase()
            if (write) byHash[lower] = it
        }

    /** 内存优先；未命中时回读持久层（过期即弃）。 */
    private fun memoryOrDisk(hash: String): Capability? {
        val lower = hash.lowercase()
        byHash[lower]?.let { return it }
        val raw = runCatching { NormalSaver.readData(KEY_PREFIX + lower, "") }.getOrNull()
            ?.takeIf { it.isNotEmpty() } ?: return null
        val parsed = parse(raw) ?: return null
        byHash[lower] = parsed
        return parsed
    }

    private fun persist(hash: String, cap: Capability) {
        cap.updatedAt = System.currentTimeMillis()
        // 面板订阅本字段以即时反映新结论：放在写盘之前，保证存储异常也不影响通知送达
        version.intValue++
        val value = buildString {
            append(cap.provenUnavailable.sorted().joinToString(","))
            append(';')
            append(cap.provenAvailableTier ?: "")
            append(';')
            append(cap.updatedAt)
        }
        runCatching { NormalSaver.saveData(KEY_PREFIX + hash.lowercase(), value) }
    }

    private fun parse(raw: String): Capability? = runCatching {
        val parts = raw.split(';')
        if (parts.size < 3) return null
        val updatedAt = parts[2].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - updatedAt > TTL_MS) return null
        Capability().apply {
            parts[0].split(',').mapNotNull { KugouQuality.parse(it) }.forEach { provenUnavailable.add(it) }
            provenAvailableTier = KugouQuality.parse(parts[1])
            this.updatedAt = updatedAt
        }
    }.getOrNull()
}
