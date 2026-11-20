package yos.music.player.ui.navigation

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
