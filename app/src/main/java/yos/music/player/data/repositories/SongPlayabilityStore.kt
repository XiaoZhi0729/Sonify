package yos.music.player.data.repositories

import androidx.compose.runtime.mutableStateMapOf
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import yos.music.player.data.NormalSaver
import java.util.concurrent.ConcurrentHashMap

/** 单曲可播状态。UNKNOWN = 尚未有结论（按正常样式渲染，绝不置灰）。 */
enum class PlayState { UNKNOWN, PLAYABLE, BLOCKED }

data class SongAvailability(val state: PlayState, val reason: String?) {
    companion object {
        val UNKNOWN = SongAvailability(PlayState.UNKNOWN, null)
    }
}

/**
 * 歌曲可播性表：记录“这一首能不能播、不能播的原因”。
 *
 * 为什么需要它：列表接口（搜索/歌单/榜单/每日推荐…）**不下发任何版权或可播字段**，
 * 可播性只在解析 `/song/url` 时才暴露（酷狗 `status=3` 版权/付费拦截 →
 * [KugouRepository.KugouPlayBlockedException]）。要在“点之前”就把版权歌置灰，
 * 只能主动逐条探测最低档（最低档都被拒 = 整曲不可播），把结论集中存放在这里。
 *
 * 与 [SongQualityCapabilityStore] 同一范式：MMKV 持久化 + 24h TTL + 订阅式内存态。
 * 内存态用 Compose 的 [mutableStateMapOf]，只有本曲状态变化时对应行才重组。
 *
 * 只把“确凿的拒播”写成 BLOCKED：网络/超时/鉴权失效都不产生结论，
 * 避免把一次抖动写成长期置灰（下轮可见时会自动重探）。
 */
object SongPlayabilityStore {

    /** 证据保留 24 小时：会员开通、音源上架都会让“不可播”过期。 */
    private const val TTL_MS = 24L * 60 * 60 * 1000

    /** 持久化键前缀（MMKV yos_data_normal）。 */
    private const val KEY_PREFIX = "song_playability_"

    /** 探测档位：用最低档（128）。最低档被拒 = 整曲不可播，请求最省。 */
    private const val STANDARD_TIER = KugouQuality.STANDARD

    /** 状态表：按 hash 订阅，只有本曲变化才触发对应行重组。 */
    private val states = mutableStateMapOf<String, SongAvailability>()

    /** 正在探测中的 hash：同一首不并发重复请求。 */
    private val inFlight = ConcurrentHashMap.newKeySet<String>()

    /** 全局并发闸：开列表时批量可见行各探一次，避免瞬间打爆请求。 */
    private val gate = Semaphore(4)

    /** 纯读、无 IO：未知返回 UNKNOWN（首帧正常样式，探测返回后再变灰）。 */
    fun stateOf(hash: String?): SongAvailability {
        val h = hash?.lowercase()?.takeIf { it.isNotEmpty() } ?: return SongAvailability.UNKNOWN
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
     * 探测失败只在“确凿拒播”时写 BLOCKED，其余（网络/超时/鉴权/取消）不产生结论。
     */
    suspend fun ensureChecked(hash: String?) {
        val h = hash?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        if (states.containsKey(h)) return
        readDisk(h)?.let { states[h] = it; return }
        if (!inFlight.add(h)) return
        try {
            // 鉴权失效窗口内整账号都不准：此时探测只会刷失败，不产生结论
            if (KugouRepository.isAuthInvalidWindow()) return
            gate.withPermit {
                val outcome = runCatching { KugouRepository.resolvePlayUrl(h, STANDARD_TIER) }
                val e = outcome.exceptionOrNull()
                // 取消不是失败证据：原样抛出，保持结构化并发（切页取消探测不留下误判）
                if (e is kotlinx.coroutines.CancellationException) throw e
                outcome.fold(
                    onSuccess = { markPlayable(h) },
                    onFailure = { err ->
                        (err as? KugouRepository.KugouPlayBlockedException)
                            ?.let { markBlocked(h, it.userReason) }
                    }
                )
            }
        } finally {
            inFlight.remove(h)
        }
    }

    private fun commit(hash: String?, value: SongAvailability) {
        val h = hash?.lowercase()?.takeIf { it.isNotEmpty() } ?: return
        states[h] = value
        runCatching { NormalSaver.saveData(KEY_PREFIX + h, encode(value)) }
    }

    /** 读盘：过期即弃（视为未知，触发重探）。 */
    private fun readDisk(hash: String): SongAvailability? = try {
        val raw = NormalSaver.readData(KEY_PREFIX + hash, "")
        val p = raw.split('|')
        val at = p.getOrNull(2)?.toLongOrNull()
        if (p.size >= 3 && at != null && System.currentTimeMillis() - at <= TTL_MS) {
            when (p[0]) {
                "B" -> SongAvailability(PlayState.BLOCKED, p[1].ifEmpty { null })
                "P" -> SongAvailability(PlayState.PLAYABLE, null)
                else -> null
            }
        } else null
    } catch (_: Exception) {
        null
    }

    private fun encode(v: SongAvailability): String {
        val flag = if (v.state == PlayState.BLOCKED) "B" else "P"
        return "$flag|${v.reason.orEmpty()}|${System.currentTimeMillis()}"
    }
}
