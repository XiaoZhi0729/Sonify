package yos.music.player.data.repositories

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf
import java.util.concurrent.ConcurrentHashMap
import yos.music.player.data.NormalSaver
import yos.music.player.data.libraries.SettingsLibrary

/**
 * 在线音质的状态模型（重构核心）：把三件历史上被混为一谈的事彻底分开。
 *
 * - **意图（intent）**：用户想要哪一档。来源见 [IntentSource]——播放页只写"本曲覆盖"，
 *   设置页只写 WiFi/流量两档偏好。二者互不越权（断言 A4/A5）。
 * - **事实（fact）**：这一档实际拿到了什么。由解析器唯一写入，携带服务端回写的
 *   [QualityFact.stampedTier] 与解码器观测出的 [QualityFact.observedTier]。
 * - **结论（verdict）**：意图与事实比较后的诚实判定，UI 只显示结论，
 *   **永远不得拿意图档位冒充实际档位**（断言 A1，历史上"假 Hi-Res"的根因）。
 */

/** 意图来源：决定 UI 上"生效档署名"chip 的文案，也决定网络切换时是否跟随。 */
enum class IntentSource {
    /** 播放页为这一首单独选的档（跨重启保留 30 天，可随时撤销） */
    PER_SONG,

    /** 跟随设置页 WiFi 档 */
    WIFI_PREF,

    /** 跟随设置页流量档 */
    MOBILE_PREF,

    /** 网络状态取不到，按 WiFi 档兜底（必须对用户可见，不得静默按高码率跑流量） */
    FALLBACK_WIFI_UNKNOWN
}

/** 一次解析的结论。UI 文案与降级提示全部据此分支，不再用 else 兜底猜测。 */
enum class QualityVerdict {
    /** 拿到的就是请求档 */
    CONFIRMED,

    /** 低于请求档：服务端发放了更低档位（本曲上限/权益限制） */
    DOWNGRADED,

    /** 高于请求档：复用了本会话内已缓存的更高音源 */
    KEPT_HIGHER,

    /** 服务端未回写或回写值不可识别：不许猜测，显示"未知" */
    UNKNOWN,

    /** 用户切换失败，仍在此前音源上播放（现场未动） */
    FAILED
}

/**
 * 音质事实。
 * [stampedTier]/[observedTier] 为 null 表示"不知道"，与"知道且是最低档"严格区分。
 */
@Stable
data class QualityFact(
    val intentTier: String,
    val intentSource: IntentSource,
    val stampedTier: String?,
    val stampedRaw: String?,
    val observedTier: String?,
    val verdict: QualityVerdict,
    val resolvedAt: Long
) {
    /**
     * 用户此刻真正在听什么：优先服务端声明档，其次解码器实测档；两者都没证据时
     * 返回 null（UI 显示兜底文案，不显示猜测值）。**永远不会返回意图档**。
     */
    val playingTier: String? get() = stampedTier ?: observedTier

    companion object {
        /**
         * 由意图与声明对比推出结论。
         *
         * 故意**不**让观测值推翻声明：在线流的 `Format.bitrate` 实测常为 -1/不准，
         * 拿它翻转结论会制造大量假"未知"。观测值只进 [QualityFact.observedTier] 做
         * 交叉校验指标（[QualityTrace.declaredVsObservedMismatch]），不参与判定。
         */
        fun of(
            intentTier: String,
            intentSource: IntentSource,
            stampedTier: String?,
            stampedRaw: String?,
            observedTier: String?,
            resolvedAt: Long = System.currentTimeMillis()
        ): QualityFact {
            val verdict = if (stampedTier == null) {
                QualityVerdict.UNKNOWN
            } else when {
                stampedTier == intentTier -> QualityVerdict.CONFIRMED
                KugouQuality.rank(stampedTier) < KugouQuality.rank(intentTier) ->
                    QualityVerdict.DOWNGRADED

                else -> QualityVerdict.KEPT_HIGHER
            }
            return QualityFact(
                intentTier, intentSource, stampedTier ?: observedTier, stampedRaw,
                observedTier, verdict, resolvedAt
            )
        }
    }
}

/**
 * 本曲音质覆盖表（播放页专用）。
 *
 * 存在意义：让"我只想把这一首换好一点的"这个意图有处可去，而不必像历史上那样
 * 顺手改写设置页的 WiFi/流量两档偏好（不可撤销，用户感知为"设置被偷偷改了"）。
 *
 * 为什么必须持久化：只存内存时，重启 App 会让用户亲手为某首歌选的档位静默消失——
 * 重新打开这首歌，勾选跑回偏好档、实际播放也回到偏好档，没有任何一句说明。能力表
 * （[SongQualityCapabilityStore]）跨重启保留而意图不保留，两者放一起更矛盾：置灰还在、
 * 选择没了。现在两者同生同灭（30 天后一起过期，届时"跟随设置"本就是更合理的默认）。
 *
 * 存储形态：MMKV(yos_data_normal) 里**一个键**装整张表（`hash|tier|ts` 以 ';' 连接）。
 * 只在用户点选时写一次，比每曲一键更好清理，也不会留下一堆无人回收的散键。
 */
@Stable
object PerSongQualityIntent {

    private const val STORE_KEY = "quality_per_song_intent"

    /** 上限：超出按最旧一次写入淘汰（用户手选档位是低频行为，200 条足够宽）。 */
    private const val MAX_ENTRIES = 200

    /** 30 天未再被写过就回到"跟随设置"：会员状态与音源都会变，长期记忆不该永久。 */
    private const val TTL_MS = 30L * 60 * 60 * 1000L * 24

    private class Entry(val tier: String, var updatedAt: Long)

    /** hash（小写）→ 用户为本曲单独选的档位（含写入时间，供淘汰与过期判定）。 */
    private val overrides = ConcurrentHashMap<String, Entry>()

    @Volatile
    private var loaded = false

    /** map 非快照状态，UI 用版本号订阅变更以触发重组。 */
    @Stable
    var version = mutableIntStateOf(0)

    fun overrideOf(hash: String?): String? {
        if (hash.isNullOrEmpty()) return null
        preload()
        val lower = hash.lowercase()
        val entry = overrides[lower] ?: return null
        if (System.currentTimeMillis() - entry.updatedAt > TTL_MS) {
            // 过期就地丢弃：不丢的话这条选择会一直躲在表里，读得到却永远不会再生效
            dropAndPersist(lower)
            return null
        }
        return entry.tier
    }

    fun setOverride(hash: String, tier: String) {
        val known = KugouQuality.parse(tier) ?: return
        preload()
        overrides[hash.lowercase()] = Entry(known, System.currentTimeMillis())
        evictIfNeeded()
        persist()
        version.intValue++
    }

    /** 撤销本曲覆盖，恢复"跟随设置页分设"（菜单里的"恢复跟随设置"）。 */
    fun clearOverride(hash: String) {
        preload()
        if (dropAndPersist(hash.lowercase())) version.intValue++
    }

    fun clearAll() {
        preload()
        if (overrides.isNotEmpty()) {
            overrides.clear()
            persist()
            version.intValue++
        }
    }

    val isEmpty: Boolean
        get() {
            preload()
            return overrides.isEmpty()
        }

    /**
     * 主线程预热：把表从 MMKV 读进内存，避开在组合过程中第一次读到磁盘 IO。
     * 读失败只退回"没有本曲覆盖"（偏好仍然生效），不影响播放。
     */
    fun preload() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            runCatching { NormalSaver.readData(STORE_KEY, "") }.getOrNull()?.let { blob ->
                val now = System.currentTimeMillis()
                blob.split(';').forEach { item ->
                    val parts = item.split('|')
                    if (parts.size < 3) return@forEach
                    val tier = KugouQuality.parse(parts[1]) ?: return@forEach
                    val ts = parts[2].toLongOrNull() ?: return@forEach
                    if (now - ts > TTL_MS) return@forEach
                    overrides[parts[0].lowercase()] = Entry(tier, ts)
                }
            }
            loaded = true
        }
    }

    /** 测试专用：绕开"内存表 + 持久层"两层，直接验序列化。 */
    internal fun parseBlobForTest(blob: String, now: Long): Map<String, String> =
        blob.split(';').mapNotNull { item ->
            val parts = item.split('|')
            if (parts.size < 3) return@mapNotNull null
            val tier = KugouQuality.parse(parts[1]) ?: return@mapNotNull null
            val ts = parts[2].toLongOrNull() ?: return@mapNotNull null
            if (now - ts > TTL_MS) return@mapNotNull null
            parts[0].lowercase() to tier
        }.toMap()

    /** 测试专用：当前表内容。 */
    internal fun snapshotForTest(): Map<String, String> {
        preload()
        return overrides.mapValues { it.value.tier }
    }

    /** 内存与持久层一起删；返回是否真的删掉了东西。 */
    private fun dropAndPersist(lower: String): Boolean {
        if (overrides.remove(lower) == null) return false
        persist()
        return true
    }

    /** 超容时淘汰最旧写入（线性扫一遍即可：表最多 200 条）。 */
    private fun evictIfNeeded() {
        while (overrides.size > MAX_ENTRIES) {
            val oldest = overrides.entries.minByOrNull { it.value.updatedAt } ?: return
            overrides.remove(oldest.key)
        }
    }

    private fun persist() {
        val blob = overrides.entries.joinToString(";") { "${it.key}|${it.value.tier}|${it.value.updatedAt}" }
        runCatching {
            if (blob.isEmpty()) NormalSaver.remove(STORE_KEY) else NormalSaver.saveData(STORE_KEY, blob)
        }
    }
}

/**
 * 意图解析的唯一入口：UI 与解析线程**必须**都走这里，杜绝两处各自现读全局状态
 * 造成的"UI 已改口、音频未跟随"错位（断言 A8）。
 */
object QualityIntentResolver {

    data class Decision(val tier: String, val source: IntentSource)

    /**
     * 仲裁核心（纯函数，JVM 可测）：**本曲覆盖 > 偏好**。
     * 全链只有一处意图判定规则，UI 与解析线程都只能走它。
     */
    fun decide(hash: String?, overrideTier: String?, preference: Decision): Decision =
        overrideTier?.let { Decision(it, IntentSource.PER_SONG) } ?: preference

    fun resolve(hash: String?, context: Context): Decision =
        decide(hash, PerSongQualityIntent.overrideOf(hash ?: ""), fromPreference(context))

    /** 不含本曲覆盖的"纯偏好"档：设置页与署名 chip 用它判断当前生效的是哪一格。 */
    fun fromPreference(context: Context): Decision =
        when (NetworkObserver.kind(context)) {
            NetworkKind.METERED -> Decision(SettingsLibrary.OnlineQualityMobile, IntentSource.MOBILE_PREF)
            NetworkKind.UNMETERED -> Decision(SettingsLibrary.OnlineQualityWifi, IntentSource.WIFI_PREF)
            // 取不到网络状态：按 WiFi 档兜底（失败由解析链承担），但必须署名，不得伪装成"确认在 WiFi"
            NetworkKind.UNKNOWN -> Decision(SettingsLibrary.OnlineQualityWifi, IntentSource.FALLBACK_WIFI_UNKNOWN)
        }
}

/**
 * 音质链路观测（重构期间的证据来源，也是发布门槛的数据来源）。
 *
 * 单行结构化输出，便于 `adb logcat -s QualityTrace` 直接核对每个分支：
 * 决策路径（cache/sticky/fallback/network）、声明档、观测档、结论。
 */
object QualityTrace {

    const val TAG = "QualityTrace"

    /** debug 构建下由 Application 置 true：开启不变量断言（宁可崩在开发期）。 */
    @Volatile
    var strict = false

    /** 累计计数：发布门槛用（mismatch / switch_caused_skip 必须为 0）。 */
    @Volatile
    var declaredVsObservedMismatch = 0

    @Volatile
    var userSwitchCount = 0

    @Volatile
    var userSwitchCausedReload = 0

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        val sb = StringBuilder(event)
        fields.forEach { (k, v) -> sb.append(" | ").append(k).append('=').append(v) }
        Log.d(TAG, sb.toString())
    }

    /** 断言 A1：任何对外显示的档位必须来自声明或观测，绝不能来自"请求档冒充"。 */
    fun checkHonestDisplay(where: String, shown: String?, evidence: QualityFact?) {
        if (!strict) return
        val ok = shown == null || evidence == null ||
                shown == evidence.stampedTier || shown == evidence.observedTier
        if (!ok) {
            declaredVsObservedMismatch++
            log("INVARIANT_VIOLATION_A1", "where" to where, "shown" to shown, "fact" to evidence)
        }
    }
}
