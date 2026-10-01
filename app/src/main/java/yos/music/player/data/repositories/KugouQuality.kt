package yos.music.player.data.repositories

import android.content.Context

/**
 * 在线音质档位与降级链（对齐上游 md3Music 的 KugouQuality）。
 *
 * 后端 /song/url 的 quality 参数是字符串（'128'/'320'/'flac'/'high'），
 * 必须保持字符串传递：数值解析会把 'flac'/'high' 打回 '128'，导致永远最低音质。
 * 服务端对不可用的高档位常静默发放更低档位的链接，并在 data.quality 里回写
 * 按码率反推的实际音质（song_url.rs stamp_quality）——降级校验以此字段为准。
 *
 * 档位值仍是字符串（与持久化、后端参数兼容），但**读服务端回写值必须经 [parse]**：
 * 识别不了返回 null（= 不知道）。历史上未知值经 indexOf 得到 -1，被下游当成
 * "最低档"渲染并配一句"本曲最高支持标准 128K"；缺字段时又默认等于请求档，
 * 等于"无证据即判无罪"。两者都是假读数，从此处堵死。
 */
object KugouQuality {

    const val STANDARD = "128"
    const val HIGH = "320"
    const val LOSSLESS = "flac"
    const val HIRES = "high"

    /** 档位从低到高；降级链与实际音质排名都以此为基准。 */
    val ALL = listOf(STANDARD, HIGH, LOSSLESS, HIRES)

    /** 服务端回写值别名（大小写与首尾空格宽容）；表外的值视为未知，不猜。 */
    private val ALIASES = mapOf(
        "128" to STANDARD, "128k" to STANDARD, "standard" to STANDARD,
        "320" to HIGH, "320k" to HIGH, "hq" to HIGH,
        "flac" to LOSSLESS, "lossless" to LOSSLESS, "mflac" to LOSSLESS,
        "high" to HIRES, "hi-res" to HIRES, "hires" to HIRES, "flac24bit" to HIRES
    )

    /** 归一化任意来源的档位串：null = 不可识别（未知），绝不回落到最低档。 */
    fun parse(raw: String?): String? {
        val key = raw?.trim()?.lowercase()?.ifEmpty { null } ?: return null
        ALIASES[key]?.let { return it }
        return if (ALL.contains(key)) key else null
    }

    /** 档位排名（低→高）；未知档位视为最低。只在两侧都是已知档位时用于比较。 */
    fun rank(quality: String): Int = ALL.indexOf(quality)

    /** 可空排名：未知或 null 返回 null，强制调用方处理"无法比较"。 */
    fun rankOrNull(quality: String?): Int? = parse(quality)?.let { ALL.indexOf(it) }

    /**
     * 由解码器实测规格反推档位（本地文件的读数来源，也是在线声明档的交叉校验证据）。
     *
     * 任一值缺失就不下结论（返回 null）：FLAC 流的 bitrate 实测为 -1（帧头不声明码率），
     * 拿它参与阈值判定会把无损误判成未知，所以宁可说"不知道"。
     * 阈值与音质徽标历史上一致（无损 ≥700kbps@44.1kHz、Hi-Res ≥2000kbps@96kHz）。
     */
    fun tierFromSpec(bitrateKbps: Int, sampleRate: Int): String? {
        if (bitrateKbps <= 0 || sampleRate <= 0) return null
        return when {
            bitrateKbps >= 2000 && sampleRate >= 96000 -> HIRES
            bitrateKbps >= 700 && sampleRate >= 44100 -> LOSSLESS
            bitrateKbps >= 250 -> HIGH
            else -> STANDARD
        }
    }

    /** 降级链：从请求档位一路降到标准音质，如 flac → [flac, 320, 128]。 */
    fun downgradeChain(requested: String): List<String> {
        val index = rank(requested)
        if (index <= 0) return listOf(STANDARD)
        return ALL.subList(0, index + 1).reversed()
    }

    /**
     * 按当前网络环境读取**偏好**档位与来源署名（设置页、"生效档"chip 共用）。
     *
     * 注意：只含偏好、不含播放页的本曲覆盖。需要完整意图的调用方（解析线程、
     * 徽标、下拉菜单）一律走 [QualityIntentResolver.resolve]，否则会出现
     * "UI 显示 A 档、音频按 B 档解析"的错位（断言 A8）。
     * 网络状态走 [NetworkObserver] 快照（回调驱动，UI 可订阅），取不到时按 WiFi 档
     * 兜底，但来源被署名成 FALLBACK_WIFI_UNKNOWN，不再静默伪装成"确认在 WiFi"。
     */
    fun preferenceForNetwork(context: Context): QualityIntentResolver.Decision =
        QualityIntentResolver.fromPreference(context)
}
