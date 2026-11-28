package yos.music.player.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.data.objects.ArtistPresentationCache
import yos.music.player.ui.theme.isFlamingoInDarkMode
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 专辑封面取色（在线专辑详情页）。
 *
 * 与歌手页复用同一条调色板管线（[ArtistPresentationCache]：按字符串键缓存
 * `State<Int?>`、去重、LRU、跨进程持久化），只是采样窗口不同——歌手页取的是
 * hero 裁剪后的**底部 28%**（见 ArtistDetail.sampleArtistBottomColor），
 * 方形专辑封面要取**整幅**平均值才代表整页底色。
 *
 * 键按 albumId 而非封面 URL：列表封面（`imgurl`）与详情封面（`sizable_cover`）
 * 是同一张图的不同尺寸地址，按 id 建键可避免同一张专辑因 URL 变化重算两遍，
 * 也让三条入口（发现页 / 搜索 / 歌手页）命中同一个缓存色。
 */
internal fun albumPaletteKey(albumId: String): String =
    "album-cover:$albumId#square-avg-v1-128"

/**
 * 歌单封面取色键（在线歌单详情页）。
 *
 * 与 [albumPaletteKey] 必须用不同命名空间：歌单 id 与专辑 id 都是数字串，共用
 * `album-cover:` 前缀会让「专辑 123」和「歌单 123」读到同一个缓存色。
 * 来源（User/Recommend）与封面 URL 也进键：同一个 gcid/listid 在不同来源下可能是不同封面，
 * 且用户歌单增删曲目后封面 URL 会变化，必须按新封面重算取色。
 */
internal fun playlistPaletteKey(
    source: String,
    playlistId: String,
    coverUrl: String
): String = "playlist-cover:$source:$playlistId:$coverUrl#square-avg-v1-128"

/**
 * 整幅（去掉 4% 边框）的 alpha 加权平均色。
 *
 * 边框内缩是为跳过封面常见的圆角/描边/暗角伪影；权重只乘 alpha（透明像素不参与）。
 * 与歌手页的累加写法一致，但不做「越靠下权重越大」的窗口加权。
 */
internal fun sampleAlbumCoverColor(bitmap: Bitmap): Int? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val insetX = (width * 0.04f).toInt()
    val insetY = (height * 0.04f).toInt()
    val left = insetX
    val top = insetY
    val right = (width - insetX).coerceAtLeast(left + 1)
    val bottom = (height - insetY).coerceAtLeast(top + 1)

    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var total = 0.0
    for (y in top until bottom) {
        for (x in left until right) {
            val pixel = bitmap.getPixel(x, y)
            val weight = AndroidColor.alpha(pixel) / 255.0
            if (weight <= 0.0) continue
            red += AndroidColor.red(pixel) * weight
            green += AndroidColor.green(pixel) * weight
            blue += AndroidColor.blue(pixel) * weight
            total += weight
        }
    }
    if (total <= 0.0) return null
    return AndroidColor.rgb(
        (red / total).roundToInt(),
        (green / total).roundToInt(),
        (blue / total).roundToInt()
    )
}

/**
 * 底色之上的主文字色：按 WCAG 对比度在白 / `#161616` 之间取更高者。
 *
 * 为什么不直接复用 [ArtistArtworkColors.content] 的固定阈值（luminance > 0.179）：
 * 草绿/橄榄绿这类页面底色的亮度恰好落在 0.18 附近，固定阈值会在边界上把白字判成
 * 黑字；按对比度择优时这类中深色底一律判白字，浅底仍判黑字，翻转点更符合观感。
 * 歌手页的既有判定保持原样，本函数只服务专辑页。
 */
internal fun artworkContentColor(accent: Color): Color {
    val l = accent.luminance()
    val whiteContrast = 1.05f / (l + 0.05f)
    val darkContrast = (l + 0.05f) / (0.0075f + 0.05f)
    return if (whiteContrast >= darkContrast) Color.White else Color(0xFF161616)
}

/**
 * 记录同一实体（歌单/专辑）最近一次解析出的封面 RGB：
 * 当封面 URL 变化、新封面尚在解码取色期间，保持展示上一张封面的提取色，
 * 避免过渡期先闪回主题兜底色再切回新封面提取色。
 */
private val lastEntityPaletteRgb = ConcurrentHashMap<String, Int>()

/**
 * 读取（必要时计算）某张专辑的封面色。
 *
 * 计算走 [ArtistPresentationCache.loadPalette]：在缓存自身 scope 上跑，页面销毁不
 * 取消、按 key 去重、算完写回同一 `State` 并落盘，所以第二次进入（含冷启动后）
 * 首帧就是正确的底色。
 */
@Composable
internal fun rememberAlbumBackdropColors(albumId: String, coverUrl: String?): ArtistArtworkColors? {
    if (albumId.isBlank()) return null
    return rememberCoverBackdropColors(
        entityKey = "album:$albumId",
        paletteKey = remember(albumId) { albumPaletteKey(albumId) },
        memoryCacheId = "album-palette:$albumId",
        coverUrl = coverUrl
    )
}

/**
 * 读取（必要时计算）某个歌单的封面色（在线歌单详情页）。
 *
 * 与专辑页共用同一条管线（[rememberCoverBackdropColors]），只是键换成
 * [playlistPaletteKey]（`playlist-cover:` 命名空间）与独立的图片内存缓存键。
 */
@Composable
internal fun rememberPlaylistBackdropColors(
    source: String,
    playlistId: String,
    coverUrl: String?
): ArtistArtworkColors? {
    if (playlistId.isBlank()) return null
    val entityKey = "playlist:$source:$playlistId"
    if (coverUrl.isNullOrBlank()) {
        lastEntityPaletteRgb.remove(entityKey)
        return null
    }
    return rememberCoverBackdropColors(
        entityKey = entityKey,
        paletteKey = remember(source, playlistId, coverUrl) {
            playlistPaletteKey(source, playlistId, coverUrl)
        },
        memoryCacheId = "playlist-palette:$source:$playlistId:$coverUrl",
        coverUrl = coverUrl
    )
}

/**
 * 封面色底通用实现（专辑 / 歌单共用）。
 *
 * 计算走 [ArtistPresentationCache.loadPalette]：在缓存自身 scope 上跑，页面销毁不
 * 取消、按 key 去重、算完写回同一 `State` 并落盘，所以第二次进入（含冷启动后）
 * 首帧就是正确的底色。
 */
@Composable
private fun rememberCoverBackdropColors(
    entityKey: String,
    paletteKey: String,
    memoryCacheId: String,
    coverUrl: String?
): ArtistArtworkColors? {
    val context = LocalContext.current
    val isDark = isFlamingoInDarkMode()
    val paletteState = remember(paletteKey) { ArtistPresentationCache.palette(paletteKey) }
    val fallbackRgbState = remember(entityKey) { mutableStateOf(lastEntityPaletteRgb[entityKey]) }
    val currentPaletteRgb = paletteState.value
    SideEffect {
        if (currentPaletteRgb != null) {
            lastEntityPaletteRgb[entityKey] = currentPaletteRgb
            if (fallbackRgbState.value != currentPaletteRgb) {
                fallbackRgbState.value = currentPaletteRgb
            }
        }
    }
    val rgb = currentPaletteRgb ?: fallbackRgbState.value

    LaunchedEffect(paletteKey, coverUrl) {
        if (coverUrl.isNullOrBlank()) return@LaunchedEffect
        // 已有缓存色（本次或上次进程算的）就不再解码
        if (paletteState.value != null) return@LaunchedEffect
        val sampled = preloadCoverPalette(context, paletteKey, memoryCacheId, coverUrl)
        android.util.Log.d(
            "AlbumPalette",
            "key=$paletteKey rgb=${sampled?.let { Integer.toHexString(it) }}"
        )
    }

    return remember(rgb, isDark) { rgb?.let { ArtistArtworkColors.fromRgb(it, isDark) } }
}

/**
 * 预计算并缓存封面调色板，供列表页卡片在可见时或点击转场前提前把颜色落入
 * [ArtistPresentationCache]，使详情页首帧即可同步命中、直接以提取色开屏。
 *
 * 内部走 [ArtistPresentationCache.loadPalette]：按 [paletteKey] 去重，且在缓存自身
 * scope 上执行，调用方离开组合也不会取消计算。
 */
internal suspend fun preloadCoverPalette(
    context: Context,
    paletteKey: String,
    memoryCacheId: String,
    coverUrl: String?
): Int? {
    if (paletteKey.isBlank() || coverUrl.isNullOrBlank()) return null
    ArtistPresentationCache.palette(paletteKey).value?.let { return it }
    return runCatching {
        ArtistPresentationCache.loadPalette(paletteKey) {
            val bitmap = runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(coverUrl)
                        // 取色要读像素 → 必须软件位图
                        .allowHardware(false)
                        .size(256)
                        .memoryCacheKey(memoryCacheId)
                        .diskCacheKey(coverUrl)
                        .build()
                ).drawable?.toBitmap()
            }.getOrNull() ?: return@loadPalette null

            val sampleSrc = if (bitmap.width > 128) Bitmap.createScaledBitmap(
                bitmap,
                128,
                (128f * bitmap.height / bitmap.width).roundToInt().coerceAtLeast(1),
                false
            ) else bitmap

            withContext(Dispatchers.Default) { sampleAlbumCoverColor(sampleSrc) }
        }
    }.getOrNull()
}

internal suspend fun preloadPlaylistBackdropColor(
    context: Context,
    source: String,
    playlistId: String,
    coverUrl: String?
): Int? {
    if (playlistId.isBlank() || coverUrl.isNullOrBlank()) return null
    return preloadCoverPalette(
        context = context,
        paletteKey = playlistPaletteKey(source, playlistId, coverUrl),
        memoryCacheId = "playlist-palette:$source:$playlistId:$coverUrl",
        coverUrl = coverUrl
    )
}

internal suspend fun preloadAlbumBackdropColor(
    context: Context,
    albumId: String,
    coverUrl: String?
): Int? {
    if (albumId.isBlank() || coverUrl.isNullOrBlank()) return null
    return preloadCoverPalette(
        context = context,
        paletteKey = albumPaletteKey(albumId),
        memoryCacheId = "album-palette:$albumId",
        coverUrl = coverUrl
    )
}

internal fun zoneCardPaletteKey(zoneId: Long, iconUrl: String): String =
    "ip-zone-card:$zoneId:$iconUrl#top-avg-v2"

/**
 * 采样编辑精选专区封面顶部区域主色调。
 *
 * 酷狗 `/ip/zone` 图标底部 y>=72.5% 烤入了白色「XX专区」大字，故仅采样顶部 62% 画面，
 * 再经 [refineZoneTintColor] 调整明度与饱和度，保证左侧纯色渐变能自然托住白色标题。
 */
internal fun sampleZoneIconColor(bitmap: Bitmap): Int? {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return null
    val insetX = (width * 0.08f).toInt()
    val top = (height * 0.06f).toInt()
    val right = (width - insetX).coerceAtLeast(insetX + 1)
    val bottom = (height * 0.62f).roundToInt().coerceAtLeast(top + 1).coerceAtMost(height)

    var red = 0.0
    var green = 0.0
    var blue = 0.0
    var total = 0.0
    for (y in top until bottom) {
        for (x in insetX until right) {
            val pixel = bitmap.getPixel(x, y)
            val weight = AndroidColor.alpha(pixel) / 255.0
            if (weight <= 0.0) continue
            red += AndroidColor.red(pixel) * weight
            green += AndroidColor.green(pixel) * weight
            blue += AndroidColor.blue(pixel) * weight
            total += weight
        }
    }
    if (total <= 0.0) return null
    val rawRgb = ((red / total).roundToInt().coerceIn(0, 255) shl 16) or
        ((green / total).roundToInt().coerceIn(0, 255) shl 8) or
        (blue / total).roundToInt().coerceIn(0, 255)
    return refineZoneTintColor(rawRgb)
}

/**
 * 纯 Kotlin 精炼专区卡片底色（JVM 单测可直接调用，不依赖 android.graphics.Color）：
 * 保留原图色相，温和收敛过亮/过暗区域并适当提纯低饱和灰调，使白色小字在左侧渐变上始终清晰。
 */
internal fun refineZoneTintColor(rgb: Int): Int {
    val r0 = ((rgb ushr 16) and 0xFF) / 255f
    val g0 = ((rgb ushr 8) and 0xFF) / 255f
    val b0 = (rgb and 0xFF) / 255f

    val cMax = max(r0, max(g0, b0))
    val cMin = min(r0, min(g0, b0))
    val delta = cMax - cMin

    var h = 0f
    if (delta > 1e-5f) {
        h = when (cMax) {
            r0 -> ((g0 - b0) / delta) % 6f
            g0 -> (b0 - r0) / delta + 2f
            else -> (r0 - g0) / delta + 4f
        } / 6f
        if (h < 0f) h += 1f
    }

    var l = (cMax + cMin) * 0.5f
    var s = if (delta <= 1e-5f) 0f else delta / (1f - kotlin.math.abs(2f * l - 1f)).coerceAtLeast(1e-5f)

    if (s in 0.04f..0.24f) {
        s = (s * 1.25f).coerceAtMost(0.42f)
    }
    l = l.coerceIn(0.24f, 0.46f)

    fun hslToRgb(hue: Float, sat: Float, light: Float): Triple<Float, Float, Float> {
        if (sat <= 1e-5f) return Triple(light, light, light)
        val q = if (light < 0.5f) light * (1f + sat) else light + sat - light * sat
        val p = 2f * light - q
        fun hue2rgb(tIn: Float): Float {
            var t = tIn
            if (t < 0f) t += 1f
            if (t > 1f) t -= 1f
            return when {
                t < 1f / 6f -> p + (q - p) * 6f * t
                t < 0.5f -> q
                t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
                else -> p
            }
        }
        return Triple(hue2rgb(hue + 1f / 3f), hue2rgb(hue), hue2rgb(hue - 1f / 3f))
    }

    fun relativeLuminance(r: Float, g: Float, b: Float): Float {
        fun lin(v: Float): Float = if (v <= 0.03928f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
        return 0.2126f * lin(r) + 0.7152f * lin(g) + 0.0722f * lin(b)
    }

    var out = hslToRgb(h, s, l)
    var step = 0
    while (relativeLuminance(out.first, out.second, out.third) > 0.28f && step < 4) {
        l *= 0.85f
        out = hslToRgb(h, s, l)
        step++
    }

    val rr = (out.first * 255f).roundToInt().coerceIn(0, 255)
    val gg = (out.second * 255f).roundToInt().coerceIn(0, 255)
    val bb = (out.third * 255f).roundToInt().coerceIn(0, 255)
    return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
}

/** 读取或异步采样编辑精选专区卡片的左侧主色调，带平滑颜色渐变过渡。 */
@Composable
internal fun rememberZoneCardTintColor(
    zoneId: Long,
    iconUrl: String?,
    fallback: Color
): Color {
    val context = LocalContext.current
    val paletteKey = remember(zoneId, iconUrl) {
        iconUrl?.takeIf { it.isNotBlank() }?.let { zoneCardPaletteKey(zoneId, it) }
    }
    val paletteState = remember(paletteKey) {
        paletteKey?.let { ArtistPresentationCache.palette(it) }
    }

    LaunchedEffect(paletteKey, iconUrl) {
        if (paletteKey == null || iconUrl.isNullOrBlank()) return@LaunchedEffect
        if (paletteState?.value != null) return@LaunchedEffect
        runCatching {
            ArtistPresentationCache.loadPalette(paletteKey) {
                val bitmap = runCatching {
                    context.imageLoader.execute(
                        ImageRequest.Builder(context)
                            .data(iconUrl)
                            .allowHardware(false)
                            .size(96)
                            .memoryCacheKey("zone-palette:$zoneId:$iconUrl")
                            .diskCacheKey(iconUrl)
                            .build()
                    ).drawable?.toBitmap()
                }.getOrNull() ?: return@loadPalette null
                withContext(Dispatchers.Default) { sampleZoneIconColor(bitmap) }
            }
        }
    }

    val targetColor = remember(paletteState?.value, fallback) {
        paletteState?.value?.let { Color(it) } ?: fallback
    }
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 240),
        label = "zone-card-tint"
    )
    return animatedColor
}
