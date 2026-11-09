package yos.music.player.ui.widgets.basic

import android.net.Uri
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.compose.SubcomposeAsyncImage
import coil.compose.SubcomposeAsyncImageContent
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Precision
import yos.music.player.R
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.widgets.effects.ShadowType
import yos.music.player.ui.widgets.effects.dropShadow

@Stable
enum class ImageQuality {
    RAW, LOW, HIGH
}

private fun getSizeFromQuality(quality: ImageQuality): Int {
    return when (quality) {
        ImageQuality.RAW -> 0
        ImageQuality.LOW -> 128
        ImageQuality.HIGH -> 400
    }
}

/**
 * 封面内存缓存键：数据标识 + 质量档位限定。
 * 同一封面会以不同尺寸档位被多处加载（列表行 LOW 128px、卡片 HIGH 400px、
 * 详情/播放页大图 RAW 原图），共用一个键会互相覆盖且互相无法通过 Coil 的
 * "缓存位图与请求尺寸精确一致"校验，导致每次进页都走完整加载（占位图闪现）。
 * 分槽后各档位各自命中；preloadNextCover 的预解码键必须与此处 RAW 槽一致。
 */
internal fun coverCacheKey(data: Any?, quality: ImageQuality): String? =
    data?.let { "$it|${quality.name}" }

/**
 * 播放页高清封面档位。酷狗的预生成档位是 240/400/720/1080，超过 1080 由服务端现算放大：
 * 实测把 1500 档上采样后与 1080 档逐像素比对，PSNR 38.8dB、高频能量 3.77 vs 3.54——
 * **没有新增细节，只有被锐化过的放大痕迹**，白花三四倍流量。
 *
 * 反向的坑：1080 及以下的预生成档会**原样保留源图格式**（源图是 PNG 就返回无损 PNG），
 * 而 1200/1500/3000 一律转 JPEG。所以同一首歌 1080 档常有 1.6MB、1500 档只有 435KB，
 * 看着"更小"其实是格式差异，不是分辨率更低。
 */
internal const val PlaybackCoverSize = 1080

private fun isKugouImageHost(host: String): Boolean =
    host == "kgimg.com" || host.endsWith(".kgimg.com") ||
            host == "kugou.com" || host.endsWith(".kugou.com")

/**
 * 播放页专用：把封面 URL 的尺寸档位升到 [PlaybackCoverSize]。
 *
 * 数据层统一把 `{size}` 解析成 400（`KugouRepository.resolveArtworkUrl`）——列表行、卡片、
 * 迷你条都是缩略图尺寸，400 足够且省流量。但播放页大封面在 393dp 宽屏上要 ≈1137 物理像素，
 * 400 被放大 2.8 倍明显发虚，所以只在大封面、外壳 morph 层、全屏静态封面、下一首预加载
 * 这几个消费方把档位提上去，列表页流量一点不涨。
 *
 * 只改写成对酷狗图床、且路径形如 `<命名目录>/<档位>/<8 位日期>/<文件名>` 的 http(s) URL；
 * 本地 `content://` / `file://` 封面、以及不含尺寸段的 URL **原样返回**——宁可不清析，
 * 也不能因为改写把封面改成 404 变占位图。返回值同时用作 Coil 的内存/磁盘缓存键，所以
 * 改写必须发生在算键**之前**，预加载与实际显示才能命中同一份位图。
 *
 * 档位段的判定要求**前后都是确定的形状**（前一段是非数字的命名目录、后一段是 8 位日期），
 * 否则纯数字的年份目录会被误当成档位段（`.../2024/0417/` 里的 `2024`）。
 * 酷狗实测的四种路径形状全部满足：`stdmusic|mcommon|kugouicon/<size>/<date>/<file>`、
 * `custom/<size>/<date>/<file>`、`uploadpic/softhead/<size>/<date>/<file>`。
 */
internal fun hiresCoverUrl(data: Any?): Any? {
    val text = when (data) {
        null -> return null
        is Uri -> data.toString()
        is String -> data
        else -> return data
    }
    val schemeEnd = text.indexOf("://")
    if (schemeEnd <= 0) return data
    val scheme = text.substring(0, schemeEnd).lowercase()
    if (scheme != "http" && scheme != "https") return data
    val pathStart = text.indexOf('/', schemeEnd + 3)
    if (pathStart < 0) return data
    val host = text.substring(schemeEnd + 3, pathStart)
        .substringAfter('@')
        .substringBefore(':')
        .lowercase()
    if (!isKugouImageHost(host)) return data
    // segments[0] 是路径开头那个 "/" 拆出来的空串，末项是文件名；两者都不可能是档位段。
    val segments = text.substring(pathStart).split('/')
    for (i in 2 until segments.size - 1) {
        val segment = segments[i]
        if (segment.length !in 2..4 || !segment.all { it.isDigit() }) continue
        // 前一段必须是命名目录（数字段的上一段就是年份，不能动），后一段必须是 8 位日期。
        if (segments[i - 1].all { it.isDigit() }) continue
        val date = segments[i + 1]
        if (date.length != 8 || !date.all { it.isDigit() }) continue
        // 已经是目标档位就不再造一个等值新串：调用方会拿返回值当 remember 的键。
        if (segment.toIntOrNull() == PlaybackCoverSize) return data
        val replaced = segments.toMutableList().also { it[i] = PlaybackCoverSize.toString() }
        return text.substring(0, pathStart) + replaced.joinToString("/")
    }
    return data
}

/**
 * 播放页高清封面预热（切歌过渡的就绪闸门调用）。
 *
 * 请求参数必须与显示侧逐项一致（[ShadowImageWithCache] / 全屏静态封面 / 外壳 morph 层
 * 都是 `hiresCoverUrl` + `RAW` + `EXACT` + `ORIGINAL`，键为 `cover|RAW`）——
 * Coil 会校验缓存位图与请求尺寸是否精确一致，参数不同就退化成重新解码，
 * 闸门等到的位图也就用不上。`preloadNextCover` 走的是同一份参数，所以常规切歌是内存命中。
 */
internal suspend fun warmUpPlaybackCover(context: android.content.Context, data: Any?) {
    val url = hiresCoverUrl(data) ?: return
    context.imageLoader.execute(
        ImageRequest.Builder(context)
            .data(url)
            .size(coil.size.Size.ORIGINAL)
            .precision(Precision.EXACT)
            .memoryCacheKey(coverCacheKey(url, ImageQuality.RAW))
            .allowHardware(true)
            .build()
    )
}

@Composable
fun ShadowImage(
    dataLambda: () -> Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shadowAlpha: Float = 0.23f,
    shadowType: ShadowType = ShadowType.Large,
    shadowOverlay: Boolean = false,
    cornerRadius: Dp = 10.dp,
    imageQuality: ImageQuality,
    onImageLoaded: (() -> Unit)? = null
) = YosWrapper {
    val shape = YosRoundedCornerShape(cornerRadius)
    val density = LocalDensity.current
    val url = dataLambda()
        val imgContext = LocalContext.current
    AsyncImage(
        model =         ImageRequest.Builder(imgContext).data(data = url).crossfade(true)
                .error(R.drawable.placeholder_music_default_artwork)
                .placeholder(R.drawable.placeholder_music_default_artwork)
                .fallback(R.drawable.placeholder_music_default_artwork)
                .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
                .memoryCacheKey(coverCacheKey(url, imageQuality))
                .diskCacheKey(url.toString())
                .allowHardware(true)
                .crossfade(true)
                .apply {
                    if (imageQuality != ImageQuality.RAW) {
                        val size = getSizeFromQuality(imageQuality)
                        this.size(size)
                        if (imageQuality == ImageQuality.LOW) {
                            this.precision(Precision.INEXACT)
                        }
                    } else {
                        // RAW = 按源图原尺寸解码（与 ShadowImageWithCache RAW 分支一致）：
                        // 尺寸确定 ⇒ 内存缓存重进必命中；若按画布尺寸请求，源图小于画布时
                        // 永远无法满足 Coil 的精确尺寸校验（详情页封面每次重新加载的根因）
                        this.precision(Precision.EXACT)
                        this.size(coil.size.Size.ORIGINAL)
                    }
                }
                .build()
        ,
        contentDescription = contentDescription.toString(),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .dropShadow(shape, shadowAlpha, shadowType, shadowOverlay)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                this.shape = shape
                /*this.shape = shape
                shadowElevation = shadowAlphaPx
                spotShadowColor = Color.Black.copy(alpha = 0.8f)*/
            }
            .drawWithCache {
                val outline = shape.createOutline(
                    Size(size.width, size.height),
                    LayoutDirection.Ltr,
                    density
                )
                onDrawWithContent {
                    drawContent()
                    drawOutline(
                        outline = outline,
                        color = Color.Gray.copy(alpha = 0.1f),
                        style = Stroke(width = 12f)
                    )
                    drawOutline(
                        outline = outline,
                        color = Color.Gray.copy(alpha = 0.5f),
                        style = Stroke(width = 12f),
                        blendMode = BlendMode.Overlay
                    )
                }
            }

    )
}

@Composable
fun ShadowImageWithCache(
    dataLambda: () -> Any?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    shadowAlpha: Float = 0.23f,
    shadowType: ShadowType = ShadowType.Large,
    shadowOverlay: Boolean = false,
    cornerRadius: Dp = 8.dp,
    imageQuality: ImageQuality,
    /**
     * 主图按播放页高清档（[PlaybackCoverSize]）加载。
     *
     * **只影响主图与主图的缓存键**：占位槽仍按传进来的原 URL 查 `|LOW`（迷你条、列表行
     * 用原 URL 的 LOW 档预热过），所以切歌时仍会先显示那张低清图、而不是通用占位图。
     * 不能改成"调用方把 dataLambda 换成 hiresCoverUrl 的结果"——那样占位键也跟着变，
     * 低清预览就永远命不中。
     */
    hiresCover: Boolean = false
) = YosWrapper {
    val shape = YosRoundedCornerShape(cornerRadius)
    val url = dataLambda()
    val loadUrl = if (hiresCover) hiresCoverUrl(url) else url
    val density = LocalDensity.current
        val imgContext = LocalContext.current
    AsyncImage(
        model =         ImageRequest.Builder(imgContext).data(data = loadUrl).crossfade(true)
                .error(R.drawable.placeholder_music_default_artwork)
                .placeholder(R.drawable.placeholder_music_default_artwork)
                .fallback(R.drawable.placeholder_music_default_artwork)
                .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
                .memoryCacheKey(coverCacheKey(loadUrl, imageQuality))
                .allowHardware(true)
                .crossfade(true)
                .apply {
                    if (imageQuality != ImageQuality.RAW) {
                        val size = getSizeFromQuality(imageQuality)
                        this.size(size)
                        if (imageQuality == ImageQuality.LOW) {
                            this.precision(Precision.INEXACT)
                        }
                    } else {
                        this.precision(Precision.EXACT)
                        this.size(coil.size.Size.ORIGINAL)
                    }
                }
                .build()
        ,
        contentDescription = contentDescription.toString(),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .dropShadow(shape, shadowAlpha, shadowType, shadowOverlay)
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                this.shape = shape
                /*this.shape = shape
                shadowElevation = shadowAlphaPx
                spotShadowColor = Color.Black.copy(alpha = 0.8f)*/
            }
            .drawWithCache {
                val outline = shape.createOutline(
                    Size(size.width, size.height),
                    LayoutDirection.Ltr,
                    this
                )
                onDrawWithContent {
                    drawContent()
                    drawOutline(
                        outline = outline,
                        color = Color.Gray.copy(alpha = 0.1f),
                        style = Stroke(width = 12f)
                    )
                    drawOutline(
                        outline = outline,
                        color = Color.Gray.copy(alpha = 0.5f),
                        style = Stroke(width = 12f),
                        blendMode = BlendMode.Overlay
                    )
                }
            }

    )
}