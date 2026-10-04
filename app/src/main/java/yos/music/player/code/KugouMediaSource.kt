package yos.music.player.code

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import androidx.annotation.OptIn
import androidx.annotation.StringRes
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import yos.music.player.R
import yos.music.player.code.utils.others.YosDiagnostics
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.repositories.KugouQuality
import yos.music.player.data.repositories.QualityIntentResolver
import yos.music.player.data.repositories.QualityVerdict
import yos.music.player.data.repositories.KugouRepository
import java.io.IOException

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
    private val appContext: Context
) : DataSource {

    override fun open(dataSpec: DataSpec): Long {
        val openedAt = SystemClock.elapsedRealtime()
        var resolveHash: String? = null
        var resolveStartedAt = openedAt
        val spec = if (dataSpec.uri.scheme == KugouRepository.PLACEHOLDER_SCHEME) {
            val hash = dataSpec.uri.lastPathSegment
                ?: throw IOException("在线占位符 URI 缺少 hash: ${dataSpec.uri}")
            resolveHash = hash
            // 意图单一来源：与 UI 读同一个 QualityIntentResolver（本曲覆盖优先，否则按网络取偏好），
            // 不再在解析线程里各自现读全局偏好——那是"UI 显示 A 档、音频按 B 档解析"的根源
            val decision = QualityIntentResolver.resolve(hash, appContext)
            resolveStartedAt = SystemClock.elapsedRealtime()
            val realUrl = try {
                KugouRepository.resolvePlayUrlBlocking(hash, decision.tier, decision.source)
            } catch (e: IOException) {
                YosDiagnostics.resolve(hash, SystemClock.elapsedRealtime() - resolveStartedAt, false, e.toString())
                throw e
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
            println("惰性解析 hash=$hash quality=${decision.tier} source=${decision.source} → $realUrl")
            dataSpec.buildUpon().setUri(realUrl).build()
        } else {
            dataSpec
        }
        // 解析成功不等于听得到声：真正卡住的是随后连 CDN。这两段必须分别计时，
        // 否则"熄屏后无声"永远分不清是酷狗 API 不放 URL、还是后台网络被限流。
        val read = try {
            upstream.open(spec)
        } catch (e: IOException) {
            YosDiagnostics.log(
                "OPEN_FAIL",
                "hash" to resolveHash,
                "host" to (spec.uri.host ?: "-"),
                "cost" to (SystemClock.elapsedRealtime() - openedAt),
                "why" to e.toString()
            )
            throw e
        }
        val cost = SystemClock.elapsedRealtime() - openedAt
        if (cost > OPEN_SLOW_MS) {
            YosDiagnostics.log(
                "OPEN_SLOW", "hash" to resolveHash, "cost" to cost,
                "online" to (resolveHash != null)
            )
        }
        return read
    }

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
        if (!SettingsLibrary.ShowQualityDowngradeToast) return
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

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        upstream.read(buffer, offset, length)

    override fun close() = upstream.close()

    override fun getUri(): android.net.Uri? = upstream.uri

    override fun addTransferListener(transferListener: TransferListener) =
        upstream.addTransferListener(transferListener)

    companion object {
        private const val DOWNGRADE_TOAST_INTERVAL_MS = 10 * 60 * 1000L

        /** open 总耗时超过此阈值就记一条：后台限流下解析/连 CDN 常呈 10s+ 长尾。 */
        private const val OPEN_SLOW_MS = 3_000L

        private val lastDowngradeToastLock = Any()
        private var lastDowngradeToastKey: String? = null
        private var lastDowngradeToastAt = 0L
    }
}

/** 数据源工厂：占位符 scheme 走惰性解析，其余委托给上游（本地文件/http 等）。 */
@OptIn(UnstableApi::class)
class KugouResolvingDataSourceFactory(
    private val upstream: DataSource.Factory,
    appContext: Context
) : DataSource.Factory {
    private val appContext = appContext.applicationContext

    override fun createDataSource(): DataSource =
        KugouResolvingDataSource(upstream.createDataSource(), appContext)
}

/** 供 ExoPlayer.Builder 使用的媒体源工厂：默认媒体源 + 惰性解析数据源。 */
@OptIn(UnstableApi::class)
fun buildKugouMediaSourceFactory(context: Context): DefaultMediaSourceFactory =
    DefaultMediaSourceFactory(context).setDataSourceFactory(
        KugouResolvingDataSourceFactory(DefaultDataSource.Factory(context), context)
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
