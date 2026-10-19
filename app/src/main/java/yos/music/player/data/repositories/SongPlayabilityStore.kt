package yos.music.player.data.repositories

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.NormalSaver
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouVipState
import yos.music.player.native.KugouApiService

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

    /** 同一账号/hash 的探测合并及短期无结论节流；不改账号级恢复预算。 */
    private val probes = SongPlayabilityProbeCoordinator<ProbeResult>()

    /** 全局并发闸：开列表时批量可见行各探一次，避免瞬间打爆请求。 */
    private val gate = Semaphore(4)

    /** 内存态当前所属账号；账号变化即整体作废，避免把上一个账号的结论套到新账号。 */
    private var syncedAccountKey: SongPlayabilityAccountKey? = null

    /** 进程内已确认"账号无会员权益"（付费歌提示语据此说清，避免重复请求）；null=未确认。 */
    @Volatile
    private var accountVipAbsent: Boolean? = null

    /** 纯读、无 IO：未知返回 UNKNOWN（首帧正常样式，探测返回后再变灰）。 */
    fun stateOf(hash: String?): SongAvailability {
        val h = normalize(hash) ?: return SongAvailability.UNKNOWN
        val state = states[h] ?: SongAvailability.UNKNOWN
        return if (syncedAccountKey == accountKey()) state else SongAvailability.UNKNOWN
    }

    fun markPlayable(hash: String?, expectedAuthVersion: Long = KugouApiService.authVersion()) {
        if (expectedAuthVersion != KugouApiService.authVersion()) return
        val account = syncAccount()
        if (expectedAuthVersion != KugouApiService.authVersion()) return
        commit(hash, SongAvailability(PlayState.PLAYABLE, null), account)
    }

    fun markBlocked(
        hash: String?, reason: String?, expectedAuthVersion: Long = KugouApiService.authVersion()
    ) {
        if (expectedAuthVersion != KugouApiService.authVersion()) return
        val account = syncAccount()
        if (expectedAuthVersion != KugouApiService.authVersion()) return
        val r = reason?.takeIf { it.isNotBlank() } ?: "无法播放"
        commit(hash, SongAvailability(PlayState.BLOCKED, r), account)
    }

    /**
     * 可见行首次组合时调用：内存/磁盘有新鲜结论直接落内存返回，否则限并发探测一次。
     * 只有 [ProbeResult.Blocked] 才落盘置灰，其余（网络/超时/鉴权/取消/认不出）不产生结论。
     */
    suspend fun ensureChecked(hash: String?) {
        val h = normalize(hash) ?: return
        val account = syncAccount()
        if (states.containsKey(h)) return
        readDisk(h, account)?.let {
            if (isCurrentAccount(account)) states[h] = it
            return
        }
        probes.run(requestKey(h, account), fresh = false, inconclusive = { it is ProbeResult.Inconclusive }) {
            gate.withPermit {
                // 排队期间可能已切账号；旧请求不得使用新凭据或写入新账号。
                if (!isCurrentAccount(account)) return@withPermit accountChanged()
                if (KugouRepository.isAuthInvalidWindow()) {
                    return@withPermit ProbeResult.Inconclusive(LOGIN_EXPIRED_REASON)
                }
                val result = probeViaPlaybackChain(h)
                currentCoroutineContext().ensureActive()
                if (!isCurrentAccount(account)) return@withPermit accountChanged()
                applyResult(h, result, account)
            }
        }
    }

    /** 点击复核使用完整降档链和总截止，绕歌曲缓存；同 hash 的短期点击合并。 */
    suspend fun recheck(hash: String?): ProbeResult {
        val h = normalize(hash) ?: return ProbeResult.Inconclusive(null)
        val account = syncAccount()
        val result = probes.run(
            requestKey(h, account), fresh = true, inconclusive = { it is ProbeResult.Inconclusive }
        ) {
            gate.withPermit {
                if (!isCurrentAccount(account)) return@withPermit accountChanged()
                invalidate(h, account)
                val result = probeFresh(h)
                currentCoroutineContext().ensureActive()
                if (!isCurrentAccount(account)) return@withPermit accountChanged()
                applyResult(h, result, account)
            }
        } ?: ProbeResult.Inconclusive(null)
        currentCoroutineContext().ensureActive()
        return if (isCurrentAccount(account)) result else accountChanged()
    }

    /** 作废某曲结论（内存 + 当前账号的磁盘项）。 */
    fun invalidate(hash: String?) {
        val h = normalize(hash) ?: return
        invalidate(h, syncAccount())
    }

    private fun invalidate(h: String, account: SongPlayabilityAccountKey) {
        if (!isCurrentAccount(account)) return
        states.remove(h)
        runCatching { NormalSaver.remove(storageKey(h, account)) }
    }

    /** 首次探测保留播放链缓存语义，使用后台探测预算。 */
    private suspend fun probeViaPlaybackChain(h: String): ProbeResult = resolveSongPlayabilityProbe {
        Result.success(runInterruptible(Dispatchers.IO) {
            KugouRepository.resolvePlayUrlBlocking(
                h, STANDARD_TIER, purpose = PlaybackRequestPurpose.VISIBLE_PROBE
            )
        })
    }

    /** 手动复核由 Repository 提供全降档、10 秒截止及歌曲缓存旁路。 */
    private suspend fun probeFresh(h: String): ProbeResult = withContext(Dispatchers.IO) {
        resolveSongPlayabilityProbe {
            KugouRepository.resolvePlayUrlFresh(
                h, STANDARD_TIER, purpose = PlaybackRequestPurpose.MANUAL_RECHECK
            )
        }
    }

    private suspend fun applyResult(h: String, result: ProbeResult, account: SongPlayabilityAccountKey): ProbeResult {
        when (result) {
            ProbeResult.Playable -> commit(h, SongAvailability(PlayState.PLAYABLE, null), account)
            is ProbeResult.Blocked -> {
                val reason = clarify(result.reason, account)
                currentCoroutineContext().ensureActive()
                if (!isCurrentAccount(account)) return accountChanged()
                commit(h, SongAvailability(PlayState.BLOCKED, reason), account)
                YosDiagnostics.log(
                    "GRAY_BLOCKED", "hash" to h.take(8), "reason" to reason,
                    "hasVipToken" to KugouAccountState.hasVipToken,
                    "accountIsVip" to (KugouVipState.vipDetail?.isVip ?: "unknown")
                )
                return ProbeResult.Blocked(reason)
            }
            is ProbeResult.Inconclusive -> Unit
        }
        return if (isCurrentAccount(account)) result else accountChanged()
    }

    private fun accountChanged() = ProbeResult.Inconclusive("账号已变更，请重试")

    /**
     * 付费类拒播时把原因说清：**账号没有会员权益 ≠ 这首歌没有音源**。
     *
     * 真实故障：账号会员到期后，上游对会员歌一律回 `status=3 pay_block_tpl=1`（无 url），
     * 提示只说"暂无可用音源"，用户看到的是"明明能听却提示没音源"，无从判断是没版权还是没会员。
     * 这里在付费类拒播且账号确实没有会员权益时改说人话；判定结果（BLOCKED）不变。
     * 结论按进程缓存，避免每首付费歌都多打一次上游。
     */
    private suspend fun clarify(reason: String, account: SongPlayabilityAccountKey): String {
        if (!reason.contains("付费")) return reason
        if (KugouAccountState.hasVipToken) return reason
        val absent = accountVipAbsent ?: try {
            val detail = KugouVipRepository.refreshVipDetail() ?: return reason
            currentCoroutineContext().ensureActive()
            if (!isCurrentAccount(account)) return reason
            (!detail.isVip).also { accountVipAbsent = it }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return reason
        }
        return if (absent) "暂无可用音源（当前账号无会员权益）" else reason
    }

    private fun commit(hash: String?, value: SongAvailability, account: SongPlayabilityAccountKey = syncAccount()) {
        val h = normalize(hash) ?: return
        if (!isCurrentAccount(account)) return
        states[h] = value
        runCatching { NormalSaver.saveData(storageKey(h, account), encode(value)) }
    }

    /** 读盘：按状态各自的 TTL 判过期，过期即弃（视为未知，触发重探）。 */
    private fun readDisk(hash: String, account: SongPlayabilityAccountKey): SongAvailability? = try {
        val raw = NormalSaver.readData(storageKey(hash, account), "")
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
    private fun accountKey() = SongPlayabilityAccountKey(
        userid = KugouAccountState.userid,
        vip = KugouAccountState.hasVipToken,
        authVersion = KugouApiService.authVersion()
    )

    /** 磁盘按稳定账号分键，避免进程重启后复用认证版本计数。 */
    private fun storageKey(hash: String, account: SongPlayabilityAccountKey): String =
        KEY_PREFIX + account.storageNamespace + "_" + hash

    private fun requestKey(hash: String, account: SongPlayabilityAccountKey): String =
        account.requestNamespace + "_" + hash

    private fun isCurrentAccount(account: SongPlayabilityAccountKey): Boolean = account == accountKey()

    /** 账号变化仅作废本表内存；不清 Repository 的账号级恢复预算。 */
    private fun syncAccount(): SongPlayabilityAccountKey {
        val key = accountKey()
        if (syncedAccountKey != key) {
            syncedAccountKey = key
            states.clear()
            accountVipAbsent = null
        }
        return key
    }

    private fun normalize(hash: String?): String? =
        hash?.lowercase()?.takeIf { it.isNotEmpty() }
}

/** Unwrap Result before declaring success; cancellation is never evidence about a song. */
internal suspend fun <T> resolveSongPlayabilityProbe(
    resolve: suspend () -> Result<T>
): SongPlayabilityStore.ProbeResult = try {
    resolve().getOrThrow()
    currentCoroutineContext().ensureActive()
    SongPlayabilityStore.ProbeResult.Playable
} catch (error: Exception) {
    // Inspect wrappers too; a transport IOException must not hide account evidence or cancellation.
    val causes = mutableListOf<Throwable>()
    var cause: Throwable? = error
    while (cause != null && causes.none { it === cause }) {
        causes += cause
        cause = cause.cause
    }
    causes.filterIsInstance<CancellationException>().firstOrNull()?.let { throw it }
    val reason = when {
        causes.any { it is KugouAuthInvalidException } -> SongPlayabilityStore.LOGIN_EXPIRED_REASON
        causes.any { it is KugouVerificationRequiredException } -> "请求需要安全验证，请稍后重试"
        causes.any { it is KugouSignatureRejectedException } -> "请求签名被拒绝，请稍后重试"
        causes.any { it is KugouTokenRejectedException } -> "登录凭据暂未被接受，请稍后重试"
        else -> null
    }
    if (reason != null) {
        SongPlayabilityStore.ProbeResult.Inconclusive(reason)
    } else {
        val blocked = causes.filterIsInstance<KugouRepository.KugouPlayBlockedException>().firstOrNull()
        if (blocked != null) SongPlayabilityStore.ProbeResult.Blocked(blocked.userReason)
        else SongPlayabilityStore.ProbeResult.Inconclusive(null)
    }
}
