package yos.music.player.code

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.Loader
import okhttp3.OkHttpClient
import yos.music.player.R
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.CacheSongIndex
import yos.music.player.data.repositories.CachedQualityPreference
import yos.music.player.data.repositories.IntentSource
import yos.music.player.data.repositories.KugouUpstreamError
import yos.music.player.data.repositories.KugouQuality
import yos.music.player.data.repositories.NetworkKind
import yos.music.player.data.repositories.NetworkObserver
import yos.music.player.data.repositories.QualityIntentResolver
import yos.music.player.data.repositories.QualityTrace
import yos.music.player.data.repositories.QualityVerdict
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.PlaybackRequestPurpose
import yos.music.player.data.repositories.KugouVerificationRequiredException
import yos.music.player.data.repositories.KugouSignatureRejectedException
import yos.music.player.data.repositories.KugouTokenRejectedException
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.net.Proxy
import java.net.ProxySelector
import java.net.URI
import java.util.concurrent.TimeUnit

/**
 * 在线歌曲惰性 URL 解析数据源（整列表播放的核心插桩点）。
 *
 * 队列中的在线歌曲 uri 为占位符（kugou://song/<hash>），入队时不请求 /song/url；
 * ExoPlayer 真正要读取该曲字节流时回调 [open]，此时按当前网络环境对应的音质档位
 * 阻塞解析真实 CDN URL（运行周期内命中 KugouRepository 内存缓存则零请求），
 * 随后重定向到真实 URL 读取。本地歌曲与其它 scheme 原样透传给上游数据源，行为不变。
 *
 * 解析失败抛 IOException → ExoPlayer 报播放错误 → 由 YosPlaybackService 的
 * onPlayerError 逻辑自动跳到下一首，不会崩溃或清空队列。
 */
@OptIn(UnstableApi::class)
class KugouResolvingDataSource(
    private val upstream: DataSource,
    /** 在线流专用的磁盘缓存通道；缓存层不可用时为 null，播放回退为直连。 */
    private val cachedUpstream: DataSource?,
    /** 绕过系统代理的直连通道（http(s) 专用）；系统代理不可用时兜底，见 [OnlineProxyHealth]。 */
    private val directUpstream: DataSource,
    /** 直连通道的磁盘缓存版本（与代理通道共用同一份 SimpleCache 与 cacheKey）。 */
    private val directCachedUpstream: DataSource?,
    /**
     * 「整首已缓存」专用通道：上游是必定抛错的桩，命中本地字节时零网络，
     * 未命中则立刻抛 IOException 交由调用方回退到联网解析。
     * Media3 1.4.0 没有 FLAG_CACHE_ONLY，这个桩就是它的等价实现。
     */
    private val cacheOnlyUpstream: DataSource?,
    private val appContext: Context,
    private val purpose: () -> PlaybackRequestPurpose = { PlaybackRequestPurpose.PLAYBACK }
) : DataSource {

    /**
     * open() 实际选用的数据源。read/close/getUri 必须跟着它走——各通道是彼此独立的实例，
     * open 走 A、read 走 B 会直接 NPE（DefaultDataSource 在未 open 的实例上 read，
     * 内部 mDataSource 为 null）。
     */
    private var active: DataSource = upstream

    /** active 是否为"绕过系统代理的直连"通道：read 中途断流时据此判断要不要标记代理故障。 */
    private var activeIsDirect = false

    /** active 是否是零网络的纯缓存通道：读失败与代理健康无关，不得标记代理故障。 */
    private var activeIsCacheOnly = false

    override fun open(dataSpec: DataSpec): Long {
        val openedAt = SystemClock.elapsedRealtime()
        if (dataSpec.uri.scheme != KugouRepository.PLACEHOLDER_SCHEME) {
            // 本地文件与其它直链：不解析、不进缓存（不把本地文件再抄一份进缓存）
            selectChannel(online = false, preferDirect = false, cacheOnly = false)
            return openedStep(dataSpec, null, openedAt)
        }
        val hash = dataSpec.uri.lastPathSegment
            ?: throw IOException("在线占位符 URI 缺少 hash: ${dataSpec.uri}")
        // 意图单一来源：与 UI 读同一个 QualityIntentResolver（本曲覆盖优先，否则按网络取偏好），
        // 不再在解析线程里各自现读全局偏好——那是"UI 显示 A 档、音频按 B 档解析"的根源
        val decision = QualityIntentResolver.resolve(hash, appContext)
        // 流量下本机已有更高档字节时改播它：省流量的初衷是"复用在家缓存的高音质"，
        // 而不是"看到流量就重下 128K"。只对被动偏好生效，用户为本曲显式选的档不覆盖。
        val upgrade = cachedUpgradeFor(hash, decision)
        // 零网络快路径：整首缓存已在本地，连 /song/url 都不请求。失败（被 LRU 淘汰、
        // 缓存文件损坏等）静默回退到下面的联网解析，用户侧不该感知到这次尝试。
        if (upgrade != null && upgrade.fullyCached) {
            tryOpenFromCacheOnly(hash, decision, upgrade)?.let { return it }
        }
        // 未整首缓存但有升级结论：按**升级后的档位**请求，剩余字节续写进同一份缓存，
        // 比整首重下 128K 更省（key 由实际档位决定，见 openResolved 里的 cacheKey）
        return openResolved(dataSpec, hash, decision, upgrade?.tier ?: decision.tier, openedAt)
    }

    /** 联网解析 + 真实 open（含代理失败回退直连）。 */
    private fun openResolved(
        dataSpec: DataSpec,
        hash: String,
        decision: QualityIntentResolver.Decision,
        requestedTier: String,
        openedAt: Long
    ): Long {
        val resolveStartedAt = SystemClock.elapsedRealtime()
        val realUrl = try {
            KugouRepository.resolvePlayUrlBlocking(hash, requestedTier, decision.source, purpose())
        } catch (e: IOException) {
            YosDiagnostics.resolve(hash, SystemClock.elapsedRealtime() - resolveStartedAt, false, e.toString())
            // 确定性失败（鉴权失效/版权付费拦截）不等退避重试：见 asFatalIfDeterministic
            throw asFatalIfDeterministic(e)
        } catch (e: kotlinx.coroutines.CancellationException) {
            // 切歌取消（Loader 线程被中断/协程被取消）不是解析失败：不上报 RESOLVE_FAIL，
            // 也不进负缓存链路；包成 IOException 交回 ExoPlayer——取消中的加载错误会被其吞掉
            throw IOException("在线歌曲解析被取消 hash=$hash", e)
        } catch (e: InterruptedException) {
            // 同上：runBlocking 在线程被中断时抛 InterruptedException（见 resolvePlayUrlBlocking）
            throw IOException("在线歌曲解析被取消 hash=$hash", e)
        } catch (e: Exception) {
            // 解析链的领域异常（无 URL、Server 未运行等）统一收敛为 IO 错误
            YosDiagnostics.resolve(hash, SystemClock.elapsedRealtime() - resolveStartedAt, false, e.toString())
            throw IOException("在线歌曲 URL 解析失败 hash=$hash: ${e.message}", e)
        }
        YosDiagnostics.resolve(hash, SystemClock.elapsedRealtime() - resolveStartedAt, true, null)
        notifyQualityDowngradeIfNeeded(hash, decision)
        println("惰性解析 hash=$hash quality=$requestedTier source=${decision.source} → $realUrl")
        // 缓存 key 与 CDN URL 解耦：签名 URL 30 分钟就轮换，拿 URL 当 key 缓存永不命中。
        // 用 "hash:实际档位"（可能低于请求档的降级结果）标识一份字节流，
        // seek 出缓冲的重开、重播、crossfade 预取才能落到同一份磁盘缓存上。
        val cacheKey = OnlineAudioCache.keyOf(hash, effectiveTierOf(hash, requestedTier))
        val spec = dataSpec.buildUpon().setUri(realUrl).setKey(cacheKey).build()
        val online = isHttpOrHttps(spec)
        // 代理最近刚失败 → 冷却窗内直接走直连，别为同一处故障反复付满超时。
        val preferDirect = online && OnlineProxyHealth.isBypassed()
        selectChannel(online, preferDirect, cacheOnly = false)
        // 解析成功不等于听得到声：真正卡住的是随后连 CDN。这两段必须分别计时，
        // 否则"熄屏后无声"永远分不清是酷狗 API 不放 URL、还是后台网络被限流。
        try {
            return openedStep(spec, hash, openedAt)
        } catch (e: IOException) {
            logOpenFail(spec, hash, openedAt, e)
            // 走系统代理失败、且系统确实为该 URL 配了代理 → 记冷却窗并立刻用直连重试。
            // 酷狗 CDN 直连可用，代理被后台冻结/断流时这条路能把歌救回来。
            val canFallback = online && !preferDirect && OnlineProxyHealth.usesSystemProxy(spec.uri)
            if (!canFallback) throw e
            OnlineProxyHealth.markProxyFailed()
            // media3 契约未定义"open 失败后 close"，但失败时无连接可泄，兜住异常即可。
            runCatching { active.close() }
            selectChannel(online, preferDirect = true, cacheOnly = false)
            try {
                return openedStep(spec, hash, openedAt)
            } catch (e2: IOException) {
                logOpenFail(spec, hash, openedAt, e2)
                throw e2
            }
        }
    }

    /**
     * 纯缓存打开：让 CacheDataSource 直接喂本地字节，上游桩一旦被碰到就说明缓存其实不完整。
     *
     * 返回读取到的长度；任何失败（未命中/文件损坏/桩被调用）都返回 null，并且**不留痕迹**——
     * 把 active 复位、关掉半开的实例，让调用方走正常联网解析，行为与没有本功能时一致。
     */
    private fun tryOpenFromCacheOnly(
        hash: String,
        decision: QualityIntentResolver.Decision,
        choice: CachedQualityPreference.Choice
    ): Long? {
        val source = cacheOnlyUpstream ?: return null
        val startedAt = SystemClock.elapsedRealtime()
        val cacheKey = OnlineAudioCache.keyOf(hash, choice.tier)
        val spec = DataSpec.Builder()
            .setUri(CACHE_ONLY_URI)
            .setKey(cacheKey)
            .build()
        selectChannel(online = true, preferDirect = false, cacheOnly = true)
        return try {
            val read = source.open(spec)
            // 整首在本地 ⇒ 零网络、零 /song/url。事实按缓存 key 的档位如实记录
            // （缓存字节本就按档位分 key 存，这是确切事实，不违反"不拿意图冒充实际"）
            KugouRepository.recordCachedPlayback(hash, decision.tier, decision.source, choice.tier)
            YosDiagnostics.log(
                "CACHE_ONLY", "hash" to hash, "intent" to decision.tier,
                "played" to choice.tier, "cost" to (SystemClock.elapsedRealtime() - startedAt)
            )
            QualityTrace.log(
                "RESOLVE", "path" to "cache_only", "hash" to hash.lowercase(),
                "intent" to decision.tier, "played" to choice.tier
            )
            read
        } catch (e: IOException) {
            logOpenFail(spec, hash, startedAt, e)
            runCatching { source.close() }
            resetChannel()
            null
        } catch (e: Exception) {
            runCatching { source.close() }
            resetChannel()
            null
        }
    }

    /** 把通道状态复位成"未打开"，供缓存快路径失败后回退使用。 */
    private fun resetChannel() {
        active = upstream
        activeIsDirect = false
        activeIsCacheOnly = false
    }

    /** 选通道：本地/非 http 走原 upstream；在线 http(s) 在代理与直连间二选一（各带缓存版本）。 */
    private fun selectChannel(online: Boolean, preferDirect: Boolean, cacheOnly: Boolean) {
        activeIsDirect = online && !cacheOnly && preferDirect
        activeIsCacheOnly = online && cacheOnly
        active = when {
            !online -> upstream
            cacheOnly -> cacheOnlyUpstream ?: upstream
            preferDirect -> directCachedUpstream ?: directUpstream
            else -> cachedUpstream ?: upstream
        }
    }

    /** 真正 open + OPEN_SLOW 计时；失败原样抛出，由 [open] 决定是否回退直连。 */
    private fun openedStep(spec: DataSpec, hash: String?, startedAt: Long): Long {
        val read = active.open(spec)
        val cost = SystemClock.elapsedRealtime() - startedAt
        if (cost > OPEN_SLOW_MS) {
            YosDiagnostics.log(
                "OPEN_SLOW", "hash" to hash, "cost" to cost,
                "online" to (hash != null), "via" to viaLabel()
            )
        }
        return read
    }

    private fun logOpenFail(spec: DataSpec, hash: String?, startedAt: Long, e: IOException) {
        YosDiagnostics.log(
            "OPEN_FAIL",
            "via" to viaLabel(),
            "hash" to hash,
            "host" to (spec.uri.host ?: "-"),
            "cost" to (SystemClock.elapsedRealtime() - startedAt),
            "why" to e.toString()
        )
    }

    private fun viaLabel(): String = if (activeIsCacheOnly) "cache" else if (activeIsDirect) "direct" else "proxy"

    /**
     * 流量下是否该改用本机已缓存的高音源。
     *
     * 三个前置条件，任一不满足就不介入（返回 null，调用方完全走原路径）：
     * 1. 网络是**计费**形态（Wi-Fi 下本来就在按偏好档拉流，不需要介入）；
     * 2. 意图来自**被动偏好**——用户为本曲显式选的档（[IntentSource.PER_SONG]）是强意图，
     *    拿"省流量"去覆盖它会让手选变成摆设；
     * 3. 择优算法认为高档位确实更省（见 [CachedQualityPreference.choose]）。
     */
    private fun cachedUpgradeFor(
        hash: String,
        decision: QualityIntentResolver.Decision
    ): CachedQualityPreference.Choice? {
        if (decision.source != IntentSource.MOBILE_PREF) return null
        if (NetworkObserver.kind(appContext) != NetworkKind.METERED) return null
        return runCatching {
            // 一次扫描取回四档事实：这是每首歌 open 路径上的成本，不做逐档重复查询
            val facts = OnlineAudioCache.cacheFactsOf(appContext, hash)
            val choice = CachedQualityPreference.choose(
                intentTier = decision.tier,
                durationMs = CacheSongIndex.durationOf(hash),
                cachedBytes = { tier -> facts.cachedBytes[tier] ?: 0L },
                fullyCached = { tier -> tier in facts.fullyCached },
                estimateTotal = KugouQuality::estimateTotalBytes
            )
            if (choice != null) {
                QualityTrace.log(
                    "CACHE_UPGRADE", "hash" to hash.lowercase(),
                    "intent" to decision.tier, "chosen" to choice.tier,
                    "full" to choice.fullyCached, "remaining" to choice.remainingBytes
                )
            }
            choice
        }.getOrNull()
    }

    /** 事实档位（服务端实际发放，可能低于请求档）优先；无事实时才落回请求档。 */
    private fun effectiveTierOf(hash: String, requestedTier: String): String =
        KugouRepository.factOf(hash)?.stampedTier ?: requestedTier

    /**
     * 把"重试也不会变好"的确定性失败标成不可重试。
     *
     * 为什么必须单独标：上游拒播（鉴权失效、版权/付费拦截）本身也是 IOException，Loader
     * 就照旧退避重试（实测 +1s、+2s 各一次，而且那两次都是本地负缓存命中、白等），
     * 于是一首坏曲要 3.4 秒才认输——真机一次鉴权爆发里连跳 10 首就是 34 秒的"看着卡住"。
     * 网络/超时不在这列：它们确实值得那一次退避。
     *
     * 手段用 [Loader.UnexpectedLoaderException]：本版本（media3 1.4.0）的
     * DefaultLoadErrorHandlingPolicy 把它列进"不重试"集合，而这版还**没有**
     * `PlaybackException.isRecoverable` 可用（构造参数里根本没有这个开关）。
     * cause 原样保留，服务侧分诊仍按因果链认得出它属于哪一类。
     */
    private fun asFatalIfDeterministic(e: IOException): IOException =
        if (KugouUpstreamError.hasAuthInvalidCause(e) || isBlockedByUpstream(e) ||
            generateSequence<Throwable>(e) { it.cause }.any {
                it is KugouVerificationRequiredException || it is KugouSignatureRejectedException ||
                    it is KugouTokenRejectedException
            }
        ) {
            Loader.UnexpectedLoaderException(e)
        } else {
            e
        }

    private fun isBlockedByUpstream(e: Throwable): Boolean =
        generateSequence(e) { it.cause }
            .any { it is KugouRepository.KugouPlayBlockedException }

    private fun isHttpOrHttps(spec: DataSpec): Boolean =
        spec.uri.scheme == "http" || spec.uri.scheme == "https"

    /**
     * 本曲拿不到请求档时提示；同一首同一降级路径 10 分钟内不重复提示。
     *
     * 只对 [QualityVerdict.DOWNGRADED]（服务端确实发了低档）发声：历史上这里比的是
     * "实际 vs 请求"，于是把"用户主动把流量档调低"也报成"当前音质不可用"——
     * 把用户的自主策略误诊成故障，用户会去反复重试一个本来正常的开关。
     * KEPT_HIGHER（沿用更高音源）与 UNKNOWN（无档位证据）都不弹，只在菜单里陈述。
     */
    private fun notifyQualityDowngradeIfNeeded(
        hash: String,
        decision: QualityIntentResolver.Decision
    ) {
        // 音质降级提示已下线：功能彻底禁用（代码保留），原开关读取为：
        // if (!SettingsLibrary.ShowQualityDowngradeToast) return
        if (true) return
        val fact = KugouRepository.factOf(hash) ?: return
        if (fact.verdict != QualityVerdict.DOWNGRADED) return
        val actualQuality = fact.stampedTier ?: return
        val key = "${hash.lowercase()}:${decision.tier}>$actualQuality"
        val now = System.currentTimeMillis()
        synchronized(lastDowngradeToastLock) {
            if (key == lastDowngradeToastKey && now - lastDowngradeToastAt < DOWNGRADE_TOAST_INTERVAL_MS) return
            lastDowngradeToastKey = key
            lastDowngradeToastAt = now
        }
        Handler(Looper.getMainLooper()).post {
            Toast.makeText(
                appContext,
                appContext.getString(
                    R.string.quality_downgrade_toast,
                    appContext.getString(qualityLabelResOf(actualQuality))
                ),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // 流中途断（读到一半代理挂了）：立刻标记代理故障，让紧随其后的下一次 open 直接走直连，
    // 不必再白等一轮连接超时。异常照旧上抛，由 ExoPlayer 的退避重试重新 open。
    // 纯缓存通道读失败与代理无关（它根本不联网），不能把这份故障记忆记到代理头上。
    override fun read(buffer: ByteArray, offset: Int, length: Int): Int = try {
        active.read(buffer, offset, length)
    } catch (e: IOException) {
        if (!activeIsDirect && !activeIsCacheOnly) OnlineProxyHealth.markProxyFailed()
        throw e
    }

    override fun close() {
        active.close()
        resetChannel()
    }

    override fun getUri(): android.net.Uri? = active.uri

    override fun addTransferListener(transferListener: TransferListener) {
        // open 前不知道会走哪个通道，全都要挂上（CacheDataSource 会转发给内部实例）
        upstream.addTransferListener(transferListener)
        cachedUpstream?.addTransferListener(transferListener)
        directUpstream.addTransferListener(transferListener)
        directCachedUpstream?.addTransferListener(transferListener)
        cacheOnlyUpstream?.addTransferListener(transferListener)
    }

    companion object {
        private const val DOWNGRADE_TOAST_INTERVAL_MS = 10 * 60 * 1000L

        /** open 总耗时超过此阈值就记一条：后台限流下解析/连 CDN 常呈 10s+ 长尾。 */
        private const val OPEN_SLOW_MS = 3_000L

        /**
         * 纯缓存通道用的假 URI。CacheDataSource 只把它当"要打开哪个 key"的载体，
         * 真身由 DataSpec.key 决定；即便上游桩被碰到也不需要它是可解析的地址。
         */
        private val CACHE_ONLY_URI: android.net.Uri = android.net.Uri.parse("cacheonly://song/cached")

        private val lastDowngradeToastLock = Any()
        private var lastDowngradeToastKey: String? = null
        private var lastDowngradeToastAt = 0L
    }
}

/**
 * 系统代理健康记忆：在线流经系统代理（典型是本地 VPN/Clash 的 127.0.0.1:7890）拉流时，
 * 代理进程被后台冻结或断流会让每一次 open/read 都白等满超时。这里记住"代理最近一次失败"，
 * 在冷却窗内优先直连，避免为同一处故障反复付超时代价；窗口到期自动回试代理（可能已恢复）。
 *
 * 只在 [usesSystemProxy] 为真（系统确实为该 URL 配了代理）时才起作用：没配代理的环境
 * 不存在"回退直连"这回事，行为与改动前完全一致。
 */
internal object OnlineProxyHealth {
    private const val BYPASS_WINDOW_MS = 60_000L

    @Volatile
    private var bypassUntilMs = 0L

    fun isBypassed(): Boolean = SystemClock.elapsedRealtime() < bypassUntilMs

    fun markProxyFailed() {
        bypassUntilMs = SystemClock.elapsedRealtime() + BYPASS_WINDOW_MS
    }

    /** 系统是否为这个 URL 配了真代理（DIRECT 不算）。 */
    fun usesSystemProxy(uri: android.net.Uri): Boolean {
        val selector = runCatching { ProxySelector.getDefault() }.getOrNull() ?: return false
        return runCatching {
            selector.select(URI(uri.toString())).any { it.type() != Proxy.Type.DIRECT }
        }.getOrDefault(false)
    }
}

/** 数据源工厂：占位符 scheme 走惰性解析，其余委托给上游（本地文件/http 等）。 */
@OptIn(UnstableApi::class)
class KugouResolvingDataSourceFactory(
    private val upstream: DataSource.Factory,
    appContext: Context,
    private val purpose: () -> PlaybackRequestPurpose = { PlaybackRequestPurpose.PLAYBACK }
) : DataSource.Factory {
    private val appContext = appContext.applicationContext

    /**
     * 绕过系统代理的直连上游：http(s) 交给 OkHttp(NO_PROXY)，其余 scheme 仍由
     * DefaultDataSource 处理。只有当系统确实配了代理、且代理通道失败时才会被用到
     * （见 [OnlineProxyHealth] 与 [KugouResolvingDataSource.open]）。
     */
    private val directFactory: DataSource.Factory = DefaultDataSource.Factory(
        appContext,
        OkHttpDataSource.Factory(
            OkHttpClient.Builder()
                .proxy(Proxy.NO_PROXY)
                // 直连是救火路径，超时给得比主通道宽松，避免"慢网也被判死"
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .retryOnConnectionFailure(true)
                .build()
        // 与主通道（HttpURLConnection 默认 UA）同源，避免 CDN 因 UA 不同而行为分叉
        ).setUserAgent(System.getProperty("http.agent"))
    )

    override fun createDataSource(): DataSource {
        // 五个通道（代理/直连 × 裸/缓存 + 纯缓存）是彼此独立的会话对象，各要独立实例
        val cache = OnlineAudioCache.get(appContext)
        fun cached(factory: DataSource.Factory): DataSource? = cache?.let {
            CacheDataSource.Factory()
                .setCache(it)
                .setUpstreamDataSourceFactory(factory)
                // key 已由 open() 写进 DataSpec.key（"hash:实际档位"）；这里只是兜底
                // 直链 scheme 走默认语义（key 缺省用 uri）
                .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
                // 读缓存出错时回源兜底，别让缓存层故障放大成播放失败
                .setFlags(
                    CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR or
                        CacheDataSource.FLAG_BLOCK_ON_CACHE
                )
                .createDataSource()
        }
        // 纯缓存通道：上游是必定抛错的桩。Media3 1.4.0 没有 FLAG_CACHE_ONLY，
        // 只要缓存完整就绝不会碰上游（零网络）；一旦被碰到即说明判定有误，
        // 立刻抛 IOException 让 open() 回退到正常联网解析，用户侧无感。
        val cacheOnly = cache?.let {
            CacheDataSource.Factory()
                .setCache(it)
                .setUpstreamDataSourceFactory { FailingDataSource }
                .setCacheKeyFactory { spec -> spec.key ?: spec.uri.toString() }
                .createDataSource()
        }
        return KugouResolvingDataSource(
            upstream.createDataSource(),
            cached(upstream),
            directFactory.createDataSource(),
            cached(directFactory),
            cacheOnly,
            appContext,
            purpose
        )
    }
}

/**
 * "缓存缺失就报错"的桩上游：纯缓存通道专用。
 *
 * 它永远不会成功——被调用本身就是"缓存不完整"的证据。抛 [FileNotFoundException]
 * （IOException 子类）而不是 RuntimeException：Media3 的缓存层与 Loader 对 IOException
 * 的处理是既定的（重试/回源语义明确），抛运行时异常会把一次正常的缓存未命中升级成崩溃。
 */
@OptIn(UnstableApi::class)
private object FailingDataSource : DataSource {
    override fun open(dataSpec: DataSpec): Long =
        throw FileNotFoundException("本地缓存不完整: ${dataSpec.key}")

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        throw FileNotFoundException("本地缓存不完整")

    override fun getUri(): android.net.Uri? = null

    override fun close() = Unit

    override fun addTransferListener(transferListener: TransferListener) = Unit
}

/**
 * 在线歌曲磁盘字节缓存——在线 seek"先暂停再播放"的根治层。
 *
 * 背景：ExoPlayer 默认 back buffer=0、前向缓冲最多 50s，拖进度条一出缓冲就要重开
 * 数据源回 CDN 拉流（新建 HTTPS + TLS 握手 + 回填 2.5s 音频），期间 BUFFERING 表现为
 * 先暂停再播放；本地文件重开毫秒级所以无感。有了磁盘缓存，重开直接命中本地字节，
 * 不再回源，近瞬时恢复；重播/复听也顺带省流量。
 *
 * 约束：同一文件夹进程内只允许一个 SimpleCache 实例（二次构造抛异常），故全局单例；
 * 1GB LRU 由 LeastRecentlyUsedCacheEvictor 淘汰，磁盘满不需要业务层操心。
 */
@OptIn(UnstableApi::class)
object OnlineAudioCache {
    private const val DIR_NAME = "kugou_audio_cache"
    private const val MB = 1024L * 1024

    /** 上限下限防呆：用户把上限调得过小也不至于让缓存形同虚设。 */
    private const val MIN_CAP_MB = 64

    private val lock = Any()

    @Volatile
    private var instance: SimpleCache? = null

    @Volatile
    private var created = false

    /** 构造失败（磁盘不可用等）返回 null 并记住结果：播放链路直连，不因缓存层反复重试。 */
    fun get(context: Context): SimpleCache? {
        if (created) return instance
        synchronized(lock) {
            if (created) return instance
            instance = try {
                SimpleCache(
                    // 落在 filesDir 而非 cacheDir：系统"清除缓存"/存储紧张不会连锅端，
                    // 已播过的歌重启/清缓存后仍可命中字节，不再回 CDN。
                    File(context.filesDir, DIR_NAME),
                    LeastRecentlyUsedCacheEvictor(capBytes()),
                    StandaloneDatabaseProvider(context)
                )
            } catch (e: Exception) {
                null
            }
            // 旧版本音频缓存落在 cacheDir，搬家后一次性清掉回收空间（best-effort）
            runCatching { File(context.cacheDir, DIR_NAME).deleteRecursively() }
            created = true
            return instance
        }
    }

    fun keyOf(hash: String, effectiveTier: String): String =
        "kugou:${hash.lowercase()}:$effectiveTier"

    /**
     * 一次扫描取回某曲**所有档位**的缓存事实：[档位] → 已缓存字节数，以及整首已缓存的档位集合。
     *
     * 为什么是批量而不是逐档查询：升档决策要同时看清四个档位（字节数 + 完整度），
     * 逐档查会把同一批 key 的元数据翻 8 次，而这发生在每首歌的 open 路径上。
     * 这里只遍历一次 `cache.keys`（进程内的 key 表，不碰磁盘）就够。
     *
     * 两点口径必须守住：
     * - 字节数用 `getCachedSpans` 里 isCached 的 span 求和，不能用 `sumOf { length }`：
     *   空洞(hole) span 的 length 是负数。
     * - "整首已缓存"要同时满足"登记过真实总长"（`ContentMetadata.EXO_LEN`，由
     *   CacheDataSource 首次下载时从响应头写入）与"[0, total) 全部命中"。总长未知时
     *   一律算不完整——"不知道"当作"已缓存"会让零网络通道在数据不全时打开，白等一次失败。
     */
    fun cacheFactsOf(context: Context, hash: String): CacheFacts {
        val cache = get(context) ?: return CacheFacts.EMPTY
        return runCatching {
            val prefix = "kugou:${hash.lowercase()}:"
            val bytes = HashMap<String, Long>(4)
            val full = HashSet<String>(4)
            cache.keys.forEach { key ->
                if (!key.startsWith(prefix)) return@forEach
                val tier = KugouQuality.parse(key.removePrefix(prefix)) ?: return@forEach
                val spans = runCatching { cache.getCachedSpans(key) }.getOrNull() ?: return@forEach
                bytes[tier] = spans.filter { it.isCached }.sumOf { it.length }
                val total = runCatching {
                    ContentMetadata.getContentLength(cache.getContentMetadata(key))
                }.getOrDefault(0L)
                if (total > 0L && runCatching { cache.isCached(key, 0L, total) }.getOrDefault(false)) {
                    full.add(tier)
                }
            }
            CacheFacts(bytes, full)
        }.getOrDefault(CacheFacts.EMPTY)
    }

    /** 某曲的缓存事实快照。两个集合都只含"已知"的档位，缺省即"没有缓存"。 */
    data class CacheFacts(
        val cachedBytes: Map<String, Long>,
        val fullyCached: Set<String>
    ) {
        companion object {
            val EMPTY = CacheFacts(emptyMap(), emptySet())
        }
    }

    /** 本机已**整首**缓存的全部档位（供音质面板的行尾 chip 说明"为什么在放这一档"）。 */
    fun cachedTiersOf(context: Context, hash: String): Set<String> =
        cacheFactsOf(context, hash).fullyCached

    /**
     * 用户可调的音频缓存上限（MB）。主线程启动时经 [applyCapMb] 注入一次（避免播放线程首次
     * 读 DataSaver 设置把状态建到非全局快照里），故「设置里改了上限」于下次启动生效
     * （LRU 初始化时会 trim 到新上限）。下限 [MIN_CAP_MB] 防呆。
     */
    @Volatile
    private var capMb: Int = 1024

    /** 主线程启动时按设置注入一次。 */
    fun applyCapMb(mb: Int) {
        capMb = mb.coerceAtLeast(MIN_CAP_MB)
    }

    fun capBytes(): Long = capMb.toLong() * MB

    /** 已用字节数（供设置页「数据与储存」展示）。 */
    fun cacheSpace(context: Context): Long =
        runCatching { get(context)?.cacheSpace ?: 0L }.getOrDefault(0L)

    /**
     * 每个歌曲 hash 已缓存的字节数（同一首歌可能有多档位 key，合并求和）。
     * 供设置页按曲清理列表。用 `getCachedSpans` 里 isCached 的 span 求和——
     * 不能用 `sumOf { length }`：空洞(hole) span 的 length 是负数。
     */
    fun cachedSongSizes(context: Context): Map<String, Long> {
        val cache = get(context) ?: return emptyMap()
        return runCatching {
            val out = HashMap<String, Long>()
            cache.keys.toList().forEach { key ->
                val hash = hashOfKey(key) ?: return@forEach
                val bytes = runCatching {
                    cache.getCachedSpans(key).filter { it.isCached }.sumOf { it.length }
                }.getOrDefault(0L)
                out[hash] = (out[hash] ?: 0L) + bytes
            }
            out
        }.getOrDefault(emptyMap())
    }

    /** 删除某首歌的所有档位字节缓存（走 removeResource，不删目录）。 */
    fun deleteSong(context: Context, hash: String) {
        val cache = get(context) ?: return
        val prefix = "kugou:${hash.lowercase()}:"
        runCatching {
            cache.keys.filter { it.startsWith(prefix) }.toList().forEach { cache.removeResource(it) }
        }
    }

    /** 从缓存 key（"kugou:&lt;hash&gt;:&lt;tier&gt;"）解析出 hash；非本前缀返回 null。 */
    private fun hashOfKey(key: String): String? {
        if (!key.startsWith("kugou:")) return null
        val rest = key.substring("kugou:".length)
        val idx = rest.indexOf(':')
        if (idx <= 0) return null
        return rest.substring(0, idx)
    }

    /** 清空字节缓存：走 SimpleCache 的 removeResource，避免直接删目录致缓存实例状态不一致。 */
    fun clearCache(context: Context) {
        val cache = get(context) ?: return
        runCatching { cache.keys.toList().forEach { cache.removeResource(it) } }
    }
}

/** 供 ExoPlayer.Builder 使用的媒体源工厂：默认媒体源 + 惰性解析数据源。 */
@OptIn(UnstableApi::class)
fun buildKugouMediaSourceFactory(
    context: Context,
    purpose: () -> PlaybackRequestPurpose = { PlaybackRequestPurpose.PLAYBACK }
): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(context).setDataSourceFactory(
        KugouResolvingDataSourceFactory(
            // 主通道（默认走系统代理，与改动前同一 HttpURLConnection）：连接超时 8s→5s，
            // 代理被后台冻结/断流时更快暴露、更快回退直连；read 超时保持默认，
            // 避免把"慢但活着"的网络误判成断流。
            DefaultDataSource.Factory(
                context,
                DefaultHttpDataSource.Factory().setConnectTimeoutMs(5_000)
            ),
            context,
            purpose
        )
    )

/**
 * 音质档位（字符串常量）→ 本地化标签（设置页、徽标、菜单与降级提示共用，断言 A3：全站一个词表）。
 *
 * 参数可空但**不**能拿它当"未知"的出口：未知必须走 R.string.quality_capsule_unknown，
 * 调用方在传入前先确认 [yos.music.player.data.repositories.KugouQuality.parse] 结果非空；
 * 这里的 else 仅作为"已知集合外的常量"的安全网（理论上不应发生）。
 */
@StringRes
fun qualityLabelResOf(quality: String?): Int = when (quality) {
    KugouQuality.HIGH -> R.string.quality_high
    KugouQuality.LOSSLESS -> R.string.quality_lossless
    KugouQuality.HIRES -> R.string.quality_hires
    else -> R.string.quality_standard
}
