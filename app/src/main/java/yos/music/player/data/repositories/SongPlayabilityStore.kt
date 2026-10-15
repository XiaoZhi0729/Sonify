package yos.music.player.data.repositories

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.NormalSaver
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouVipState
import java.util.concurrent.ConcurrentHashMap

/** 单曲可播状态。UNKNOWN = 尚未有结论（按正常样式渲染，绝不置灰）。 */
enum class PlayState { UNKNOWN, PLAYABLE, BLOCKED }

data class SongAvailability(val state: PlayState, val reason: String?) {
    companion object {
        val UNKNOWN = SongAvailability(PlayState.UNKNOWN, null)
    }
}

/**
 * 歌曲可播性表：记录"这一首能不能播、不能播的原因"。
 *
 * 为什么需要它：列表接口（搜索/歌单/榜单/每日推荐…）**不下发任何版权或可播字段**，
 * 可播性只在解析 `/song/url` 时才暴露（酷狗 `status=3` 版权/付费拦截 →
 * [KugouRepository.KugouPlayBlockedException]）。要在"点之前"就把版权歌置灰，
 * 只能主动逐条探测，把结论集中存放在这里。
 *
 * **只把"确凿的拒播"写成 BLOCKED**：网络/超时/登录态失效/认不出的响应一律不产生结论。
 * 这条约束是被真机事故换来的——历史实现把"HTTP 200 但没有 url"整体当成拒播，于是
 * 登录态一失效，凡是被探过的歌全部被写成 24h 置灰（酷狗/MD3 里同一首歌明明能播）。
 * 判读已下沉到 [interpretSongUrlBody]，这里只负责"什么时候问、结论算不算数、存哪"。
 *
 * 探测走**播放同款链路**（[KugouRepository.resolvePlayUrlBlocking]：会员协议优先 + 降档链 +
 * urlCache/lastGood + 10s 总截止），而不是"单档 128 一票否决"——探测结论必须与播放结果
 * 同源，否则又会出现"灰着但其实能播"。
 *
 * 与 [SongQualityCapabilityStore] 同一范式：MMKV 持久化 + TTL + 订阅式内存态，
 * 只有本曲状态变化时对应行才重组。
 */
object SongPlayabilityStore {

    /** 「可播」证据保留 24 小时：会员开通、音源上架都会让旧结论过期。 */
    private const val PLAYABLE_TTL_MS = 24L * 60 * 60 * 1000

    /**
     * 「不可播」证据只留 2 小时。它比"可播"更容易被账号与权益变化推翻（换账号、开会员、
     * 上游调整限制），长存等于误灰一整天。结论本身还按账号隔离，换号即失效。
     */
    private const val BLOCKED_TTL_MS = 2L * 60 * 60 * 1000

    /**
     * 持久化键前缀（MMKV yos_data_normal）；键中含账号标识，见 [storageKey]。
     * 末尾的 2 是一次**判定口径变更**的版本位：解析链补上 `ppage_id` 收藏解锁后，
     * 旧版写下的"付费拦截"结论全部作废（同一首歌现在能拿到地址），换前缀即整批重探。
     */
    private const val KEY_PREFIX = "song_playability2_"

    /** 探测档位：最低档（128）。整曲不可播时它必然也拿不到，请求最省。 */
    private const val STANDARD_TIER = KugouQuality.STANDARD

    /** 登录态失效时给用户的一句话（探测侧与播放侧文案一致）。 */
    const val LOGIN_EXPIRED_REASON = "登录态失效，请重新登录"

    /** 一次探测的结论，供首探测与点击复核共用。 */
    sealed interface ProbeResult {
        data object Playable : ProbeResult

        /** 确凿拒播（版权/付费硬拦）——唯一允许置灰的情形。 */
        data class Blocked(val reason: String) : ProbeResult

        /** 没有结论（网络/超时/登录态失效）：不置灰、不改写已有结论。 */
        data class Inconclusive(val reason: String?) : ProbeResult
    }

    /** 状态表：按 hash 订阅，只有本曲变化才触发对应行重组。 */
    private val states = mutableStateMapOf<String, SongAvailability>()

    /** 正在探测中的 hash：同一首不并发重复请求。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 全局并发闸：开列表时批量可见行各探一次，避免瞬间打爆请求。 */
    private val gate = Semaphore(4)

    /** 内存态当前所属账号；账号变化即整体作废，避免把上一个账号的结论套到新账号。 */
    private var syncedAccountKey: String? = null

    /** 进程内已确认"账号无会员权益"（付费歌提示语据此说清，避免重复请求）；null=未确认。 */
    @Volatile
    private var accountVipAbsent: Boolean? = null

    /** 纯读、无 IO：未知返回 UNKNOWN（首帧正常样式，探测返回后再变灰）。 */
    fun stateOf(hash: String?): SongAvailability {
        val h = normalize(hash) ?: return SongAvailability.UNKNOWN
        return states[h] ?: SongAvailability.UNKNOWN
    }

    fun markPlayable(hash: String?) {
        commit(hash, SongAvailability(PlayState.PLAYABLE, null))
    }

    fun markBlocked(hash: String?, reason: String?) {
        val r = reason?.takeIf { it.isNotBlank() } ?: "无法播放"
        commit(hash, SongAvailability(PlayState.BLOCKED, r))
    }

    /**
     * 可见行首次组合时调用：内存/磁盘有新鲜结论直接落内存返回，否则限并发探测一次。
     * 只有 [ProbeResult.Blocked] 才落盘置灰，其余（网络/超时/鉴权/取消/认不出）不产生结论。
     */
    suspend fun ensureChecked(hash: String?) {
        val h = normalize(hash) ?: return
        syncAccount()
        if (states.containsKey(h)) return
        readDisk(h)?.let { states[h] = it; return }
        if (!inFlight.add(h)) return
        try {
            // 鉴权失效窗口内整账号都不准：此时探测只会刷失败，不产生结论
            if (KugouRepository.isAuthInvalidWindow()) return
            gate.withPermit { applyResult(h, probeViaPlaybackChain(h)) }
        } finally {
            inFlight.remove(h)
        }
    }

    /**
     * 点击已判定不可播的行时复核：作废旧结论、**绕开所有缓存**问一次上游（证据最新，
     * 也不会被 30s 负缓存牵连）。返回最新结论，由调用方决定"直接播"还是"弹原因"。
     */
    suspend fun recheck(hash: String?): ProbeResult {
        val h = normalize(hash) ?: return ProbeResult.Inconclusive(null)
        invalidate(h)
        if (KugouRepository.isAuthInvalidWindow()) {
            return ProbeResult.Inconclusive(LOGIN_EXPIRED_REASON)
        }
        return gate.withPermit {
            val result = probeFresh(h)
            applyResult(h, result)
            result
        }
    }

    /** 作废某曲结论（内存 + 当前账号的磁盘项）。 */
    fun invalidate(hash: String?) {
        val h = normalize(hash) ?: return
        states.remove(h)
        runCatching { NormalSaver.remove(storageKey(h)) }
    }

    /** 首次探测：与播放同源（含 urlCache/负缓存/lastGood 与 10s 总截止）。 */
    private suspend fun probeViaPlaybackChain(h: String): ProbeResult = withContext(Dispatchers.IO) {
        // resolvePlayUrlBlocking 是阻塞实现（内部 runBlocking），必须离开主线程
        runCatching { KugouRepository.resolvePlayUrlBlocking(h, STANDARD_TIER) }.fold(
            onSuccess = { ProbeResult.Playable },
            onFailure = { classify(it) }
        )
    }

    /** 点击复核：绕缓存直问上游（[KugouRepository.resolvePlayUrl] 不做任何缓存命中）。 */
    private suspend fun probeFresh(h: String): ProbeResult = withContext(Dispatchers.IO) {
        runCatching { KugouRepository.resolvePlayUrl(h, STANDARD_TIER) }.fold(
            onSuccess = { ProbeResult.Playable },
            onFailure = { classify(it) }
        )
    }

    private fun classify(error: Throwable): ProbeResult = when {
        // 取消不是失败证据：原样抛出，保持结构化并发（切页取消探测不留下误判）
        error is kotlinx.coroutines.CancellationException -> throw error
        error is KugouRepository.KugouPlayBlockedException -> ProbeResult.Blocked(error.userReason)
        KugouUpstreamError.hasAuthInvalidCause(error) -> ProbeResult.Inconclusive(LOGIN_EXPIRED_REASON)
        else -> ProbeResult.Inconclusive(null)
    }

    private suspend fun applyResult(h: String, result: ProbeResult) {
        when (result) {
            ProbeResult.Playable -> commit(h, SongAvailability(PlayState.PLAYABLE, null))
            is ProbeResult.Blocked -> {
                val reason = clarify(result.reason)
                commit(h, SongAvailability(PlayState.BLOCKED, reason))
                // 持久化留证：被判不可播时，同时记下"当前有没有会员凭据 / 账号是否会员"，
                // 便于区分"没版权"与"会员权益没被服务端认出来"（logcat 会被刷掉）。
                YosDiagnostics.log(
                    "GRAY_BLOCKED", "hash" to h.take(8), "reason" to reason,
                    "hasVipToken" to KugouAccountState.hasVipToken,
                    "accountIsVip" to (KugouVipState.vipDetail?.isVip ?: "unknown")
                )
            }
            // 无结论：不落盘、不置灰，已有结论也不改写（避免一次抖动抹掉可用判据）
            is ProbeResult.Inconclusive -> Unit
        }
    }

    /**
     * 付费类拒播时把原因说清：**账号没有会员权益 ≠ 这首歌没有音源**。
     *
     * 真实故障：账号会员到期后，上游对会员歌一律回 `status=3 pay_block_tpl=1`（无 url），
     * 提示只说"暂无可用音源"，用户看到的是"明明能听却提示没音源"，无从判断是没版权还是没会员。
     * 这里在付费类拒播且账号确实没有会员权益时改说人话；判定结果（BLOCKED）不变。
     * 结论按进程缓存，避免每首付费歌都多打一次上游。
     */
    private suspend fun clarify(reason: String): String {
        if (!reason.contains("付费")) return reason
        if (KugouAccountState.hasVipToken) return reason
        val absent = accountVipAbsent ?: runCatching { KugouVipRepository.refreshVipDetail() }
            .getOrNull()
            ?.let { !it.isVip }
            ?.also { accountVipAbsent = it }
            ?: return reason
        return if (absent) "暂无可用音源（当前账号无会员权益）" else reason
    }

    private fun commit(hash: String?, value: SongAvailability) {
        val h = normalize(hash) ?: return
        states[h] = value
        runCatching { NormalSaver.saveData(storageKey(h), encode(value)) }
    }

    /** 读盘：按状态各自的 TTL 判过期，过期即弃（视为未知，触发重探）。 */
    private fun readDisk(hash: String): SongAvailability? = try {
        val raw = NormalSaver.readData(storageKey(hash), "")
        val p = raw.split('|')
        val at = p.getOrNull(2)?.toLongOrNull()
        if (p.size >= 3 && at != null) {
            val ttl = if (p[0] == "B") BLOCKED_TTL_MS else PLAYABLE_TTL_MS
            if (System.currentTimeMillis() - at <= ttl) {
                when (p[0]) {
                    "B" -> SongAvailability(PlayState.BLOCKED, p[1].ifEmpty { null })
                    "P" -> SongAvailability(PlayState.PLAYABLE, null)
                    else -> null
                }
            } else null
        } else null
    } catch (_: Exception) {
        null
    }

    private fun encode(v: SongAvailability): String {
        val flag = if (v.state == PlayState.BLOCKED) "B" else "P"
        return "$flag|${v.reason.orEmpty()}|${System.currentTimeMillis()}"
    }

    /**
     * 结论按账号隔离：会员凭据有无会直接改变"付费歌能不能播"，换号/退出登录后
     * 旧结论不得复用到新账号（历史实现按 hash 单键存，登录态一坏，整份歌单的
     * 误判结论会在换号后继续生效）。
     */
    private fun accountKey(): String {
        val uid = KugouAccountState.userid ?: "anon"
        return if (KugouAccountState.hasVipToken) "$uid.v" else uid
    }

    private fun storageKey(hash: String): String = KEY_PREFIX + accountKey() + "_" + hash

    /** 账号变化时作废内存态（磁盘项本就按账号分键，无需清理）。 */
    private fun syncAccount() {
        val key = accountKey()
        if (syncedAccountKey != key) {
            syncedAccountKey = key
            states.clear()
            accountVipAbsent = null
        }
    }

    private fun normalize(hash: String?): String? =
        hash?.lowercase()?.takeIf { it.isNotEmpty() }
}
