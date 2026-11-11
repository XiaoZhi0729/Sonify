package yos.music.player.ui.pages.discovery

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.data.objects.ArtistPresentationCache
import yos.music.player.ui.navigation.TrackBackdropGradient
import yos.music.player.ui.navigation.TrackGradientPalette
import yos.music.player.ui.theme.isFlamingoInDarkMode
import kotlin.math.roundToInt

/**
 * 主页「私人电台」板块的取色。
 *
 * **为什么不复用 `TrackBackdrop.rememberGradient()`**：那个函数读的是
 * `MediaViewModelObject.bitmap`——**当前正在播放**的那首歌的封面。本板块的颜色必须跟随
 * **用户聚焦的那一台**，与「谁在播」无关（用户完全可以没播放就左右浏览）。
 *
 * 所以这里只复用它的**算法**：[TrackGradientPalette.sample] 是纯函数，输入任意像素，
 * 输出同一条 `TrackBackdropGradient`。色相跟封面走、明度走固定档位、色度锁死在 10，
 * 于是哪怕封面是花衬衫，底色也永远是「一种淡雅的颜色」而不会脏。
 *
 * 缓存走 [ArtistPresentationCache]（按 key 去重 + 落盘），所以第二次进主页首帧就是
 * 正确颜色，不会先闪一帧默认色。
 */

/** 取色采样边长。色彩分布不需要分辨率，96px 足够，量化前再大只是多花解码。 */
private const val SAMPLE_SIZE = 96

/**
 * 解码边长 = 采样边长：直接让 Coil 解成 96px 软件位图，
 * 省掉上版「256px 解码 → createScaledBitmap 到 128」的第二次分配与拷贝。
 */
private const val DECODE_SIZE = SAMPLE_SIZE

/**
 * 读取（必要时计算）某张封面的板块渐变。
 *
 * @param coverUrl 该台歌曲的封面地址。空值返回 null，调用方沿用上一台的颜色。
 */
@Composable
internal fun rememberFmGradient(coverUrl: String?): TrackBackdropGradient? {
    if (coverUrl.isNullOrBlank()) return null
    val isDark = isFlamingoInDarkMode()
    val key = remember(coverUrl, isDark) { fmPaletteKey(coverUrl, isDark) }

    // 缓存命中时首帧就是正确颜色（滚回主页 / 切 Tab 回来不闪）
    val cached: State<Int?> = remember(key) { ArtistPresentationCache.palette(key) }
    val packed = cached.value

    val context = LocalContext.current
    LaunchedEffect(key, coverUrl) {
        if (cached.value != null) return@LaunchedEffect
        val sampled = runCatching {
            withContext(Dispatchers.IO) {
                val bitmap = context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(coverUrl)
                        // 取色要读像素 → 必须软件位图
                        .allowHardware(false)
                        .size(DECODE_SIZE)
                        .memoryCacheKey("fm-palette-src:$coverUrl")
                        .diskCacheKey(coverUrl)
                        .build()
                ).drawable?.toBitmap() ?: return@withContext null
                val w = bitmap.width
                val h = bitmap.height
                if (w <= 0 || h <= 0) return@withContext null

                // Coil 已经按 DECODE_SIZE=96 解码，不再做第二次缩放
                val pixels = IntArray(w * h)
                bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
                val gradient = TrackGradientPalette.sample(pixels, w, h, isDark)
                    ?: return@withContext null
                // 只存色相：整条渐变由 hue + 深浅唯一确定（明度档位是常量、色度锁死 10）
                val mid = gradient.stops[2]
                val hue = hueOf(androidx.compose.ui.graphics.Color(mid.red, mid.green, mid.blue))
                val packed = encodePalette(hue, isDark)
                // ★写盘必须留在 IO 线程：seedPalette → persist() 会 Gson 序列化整个
                // 快照并写 MMKV。上版把它放在 withContext 外面，颜色解析出来的那一帧
                // 正好在主线程上做序列化，是可见的一帧卡顿。
                ArtistPresentationCache.seedPalette(key, packed)
                packed
            }
        }.getOrNull() ?: return@LaunchedEffect
    }

    val hue = remember(packed) { decodePalette(packed)?.first }
    return remember(hue, isDark) {
        if (hue == null) null else gradientFromHue(hue, isDark)
    }
}

/**
 * 从 HCT 色相反推整条渐变。
 *
 * 直接调 `TonalPalette.fromHueAndChroma(hue, 10)` + 明度档位——与
 * [TrackGradientPalette.sample] 内部的产出一字不差，只是跳过了「解码封面 + 量化」那一段。
 */
private fun gradientFromHue(hue: Float, isDark: Boolean): TrackBackdropGradient {
    val palette = me.tatarka.google.material.palettes.TonalPalette.fromHueAndChroma(
        hue.toDouble(),
        10.0
    )
    val tones = if (isDark) intArrayOf(16, 14, 12, 10, 8) else intArrayOf(97, 96, 95, 94, 93)
    return TrackBackdropGradient(
        stops = tones.map { Color(palette.tone(it)) },
        positions = TrackGradientPalette.POSITIONS
    )
}

/** HCT 色相（0..360）。用官方 Hct 而不是自算 HSV：与取色管线同一个色彩空间。 */
private fun hueOf(color: Color): Float = me.tatarka.google.material.hct.Hct.fromInt(
    android.graphics.Color.argb(
        (color.alpha * 255).toInt(),
        (color.red * 255).toInt(),
        (color.green * 255).toInt(),
        (color.blue * 255).toInt()
    )
).hue.toFloat()

/** 调色板缓存的键。带深浅模式：同一张封面浅色要 97 档、深色要 16 档，颜色不同。 */
internal fun fmPaletteKey(coverUrl: String, isDark: Boolean): String =
    "fm-cover:$coverUrl#" + if (isDark) "dark" else "light"

/**
 * 把整条渐变压进一个 Int。
 *
 * **位预算必须先算清**：Int 只有 32 bit，5 个色标 × 3 通道 = 15 个分量，
 * 每通道最多 2 bit（15×2=30）才装得下——那样精度完全不够（4 级灰阶）。
 *
 * 所以改成**只存色相和明度档位**，不存完整 RGB：
 *  `TrackGradientPalette` 的产出已经由 `TonalPalette.fromHueAndChroma(hue, 10)` 决定，
 *  整条渐变只由 **一个色相**（0..359，9 bit）与 **深浅模式**（1 bit）唯一确定。
 *  明度档位是常量（LIGHT_TONES / DARK_TONES），色度锁死 10，5 个色标全部可重算。
 *
 * 这比"压 5 个 RGB"信息量小得多，但**恰好无损**——因为上游本来就只有一个自由度。
 */
internal fun encodePalette(hue: Float, isDark: Boolean): Int {
    val h = hue.roundToInt().coerceIn(0, 359)
    return h or (if (isDark) 1 shl 9 else 0)
}

/** [encodePalette] 的逆运算：色相 + 深浅。 */
internal fun decodePalette(packed: Int?): Pair<Float, Boolean>? {
    if (packed == null) return null
    val hue = (packed and 0x1FF).toFloat()
    val isDark = (packed shr 9) and 1 == 1
    return hue to isDark
}

/**
 * 「提取色在玻璃上抹的那一撇」的颜色。
 *
 * 取渐变的**中间色标**（0.55 处，最能代表封面身份的那一档），向高饱和方向推：
 * 底色是 chroma=10 的高级灰，色渍必须明显更艳，否则压在玻璃里根本看不见。
 *
 * 推饱和的做法是**围绕该色自身的明度**拉开通道（而不是围绕 0.5 缩放）：
 * chroma=10 的色标三个通道彼此极近，围绕 0.5 缩放只能把差异等比放大，
 * 结果永远小于 `spread * k` 的期望值，实测会退化成 0（单测抓到过）。
 * 正确做法是把「最低通道」和「最高通道」直接拉到目标跨度，中间通道按比例插值。
 */
internal fun smearColorOf(gradient: TrackBackdropGradient?, isDark: Boolean): Color? {
    if (gradient == null) return null
    val mid = gradient.stops.getOrNull(2) ?: gradient.stops.last()
    val r = mid.red
    val g = mid.green
    val b = mid.blue
    val maxC = maxOf(r, g, b)
    val minC = minOf(r, g, b)
    val spread = maxC - minC
    // 目标跨度：深色模式要更艳才压得住暗底
    val target = if (isDark) 0.34f else 0.40f
    // 中心保持在原色标的平均明度附近，只把跨度拉开
    val center = (maxC + minC) / 2f
    val lo = (center - target / 2f).coerceIn(0f, 1f)
    val hi = (center + target / 2f).coerceIn(0f, 1f)
    // 输入几乎无跨度（< 1/255）时没有方向可用，直接给一个中性方向的差值，
    // 避免"推饱和"退化成同一色
    val ratio = if (spread < 0.004f) 1f else (target / spread)
    fun project(c: Float): Float = (lo + (c - minC) * ratio).coerceIn(lo, hi)
    return Color(project(r), project(g), project(b))
}

/**
 * 强调色：歌手名文字、播放药丸填充。
 *
 * 基色取 `bottom`（渐变最暗的一档）——它比中间色更接近封面的「重色」。
 * 浅色模式往黑里压保证白字压得住，深色模式往白里提保证在暗底上跳得出来。
 */
internal fun accentColorOf(gradient: TrackBackdropGradient?, isDark: Boolean): Color? {
    if (gradient == null) return null
    val base = gradient.stops.lastOrNull() ?: return null
    return if (isDark) lerp(base, Color.White, 0.34f) else lerp(base, Color.Black, 0.30f)
}

/**
 * 玻璃上文字的颜色。
 *
 * 阈值 0.179 与项目 `ArtistArtworkColors.content` 同一个数值——「什么时候翻白字」
 * 在整个 App 里必须是同一套标准，否则同一个色域在不同页面会给出不同的文字色。
 */
internal fun onGlassColorOf(accent: Color?, isDark: Boolean): Color = when {
    isDark -> Color.White.copy(alpha = 0.96f)
    accent == null -> Color(0xFF161616)
    accent.luminance() > 0.179f -> Color(0xFF161616)
    else -> Color.White
}
