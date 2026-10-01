package yos.music.player.data.repositories

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableIntStateOf

/** 网络计费形态。[UNKNOWN] 与 [UNMETERED] 严格区分——历史上取不到状态时静默按 WiFi 处理，
 *  会让用户在蜂窝网络上按高码率跑流量而毫不知情。 */
enum class NetworkKind { UNMETERED, METERED, UNKNOWN }

/**
 * 网络环境单一来源。
 *
 * 取代旧实现里每次现读 `isActiveNetworkMetered` 的写法（原 KugouQuality.requestedForNetwork）：
 * 1. 解析线程与 UI 读**同一份快照**（断言 A8：不再出现"UI 已改口、音频未跟随"）；
 * 2. 回调驱动 + Compose 版本号订阅，网络切换时 UI 会真的重组，而不是等别的状态带动；
 * 3. 读不到就返回 [NetworkKind.UNKNOWN] 并交由调用方署名，不做"猜成 WiFi"的静默决策。
 */
@Stable
object NetworkObserver {

    @Volatile
    private var cached: NetworkKind = NetworkKind.UNKNOWN

    @Volatile
    private var registered = false

    /** UI 订阅用：网络形态变更时自增，触发重组。 */
    @Stable
    var version = mutableIntStateOf(0)

    /** 当前网络形态（纯内存读，可在任意线程调用；未初始化时回落到一次性同步读）。 */
    fun kind(context: Context): NetworkKind {
        if (!registered) ensureRegistered(context)
        return cached
    }

    /** 同步读取一次（注册回调前的初值，也作为回调缺失/丢失时的兜底）。 */
    private fun readOnce(context: Context): NetworkKind {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkKind.UNKNOWN
        // 只用 API 21+ 的 activeNetwork + getNetworkCapabilities：旧接口 isActiveNetworkMetered
        // 在部分 ROM 上会返回过时的 false，把蜂窝误判成 WiFi（高码率跑流量）
        val network = runCatching { cm.activeNetwork }.getOrNull()
            ?: return NetworkKind.UNKNOWN
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull()
            ?: return NetworkKind.UNKNOWN
        return fromCapabilities(caps)
    }

    /** NOT_METERED 是唯一的计费能力位：没有它就按计费处理（宁可不升档不可错扣流量）。 */
    private fun fromCapabilities(caps: NetworkCapabilities): NetworkKind =
        if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
            NetworkKind.UNMETERED
        else NetworkKind.METERED

    private fun apply(kind: NetworkKind) {
        if (cached != kind) {
            cached = kind
            version.intValue++
            QualityTrace.log("NETWORK", "kind" to kind)
        }
    }

    /** 幂等初始化。Application.onCreate 调用一次；未调用时首次 [kind] 会自行补齐。 */
    fun init(context: Context) {
        ensureRegistered(context.applicationContext)
    }

    private fun ensureRegistered(appContext: Context) {
        synchronized(this) {
            if (registered) return
            registered = true
            apply(readOnce(appContext))
            val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
            runCatching {
                val request = NetworkRequest.Builder().build()
                cm.registerNetworkCallback(
                    request,
                    object : ConnectivityManager.NetworkCallback() {
                        override fun onAvailable(network: Network) {
                            refresh(cm, network)
                        }

                        override fun onCapabilitiesChanged(
                            network: Network,
                            networkCapabilities: NetworkCapabilities
                        ) {
                            apply(fromCapabilities(networkCapabilities))
                        }

                        override fun onLost(network: Network) {
                            // 网络丢失：没有可用连接，档位决策不再"假装在 WiFi"
                            apply(NetworkKind.UNKNOWN)
                        }
                    }
                )
            }
        }
    }

    private fun refresh(cm: ConnectivityManager, network: Network) {
        val caps = runCatching { cm.getNetworkCapabilities(network) }.getOrNull() ?: return
        apply(
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED))
                NetworkKind.UNMETERED
            else NetworkKind.METERED
        )
    }
}
