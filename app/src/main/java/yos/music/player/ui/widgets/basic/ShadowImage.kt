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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.layout.onSizeChanged

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
    /**
     * 是否由本组件自己画投影。
     *
     * 关掉之后调用方必须在外层容器上用 [dropShadow] 补一份**同样参数**的阴影。
     * 切歌交叉淡化就是这种场景：那时屏幕上同时有两张封面，各自带一份半透明阴影会
     * 互相叠色、并随图层 alpha 一起被抹淡（[dropShadow] 是原生 Canvas +
     * `BlurMaskFilter` 的绘制，依赖所在图层 alpha 为 1），表现为"切歌时阴影消失、
     * 淡完才回来"。阴影属于**卡片**而不是图片，_应当_由容器画一次且全程恒定。
     */
    drawShadow: Boolean = true,
    /**
     * 主图按高清档（[PlaybackCoverSize]）加载，用于**大字面**位置（播放页大封面、
     * 专辑/歌单详情页大封面）。列表缩略图不要开：那只会白占内存。
     *
     * 与 [ShadowImageWithCache] 的同名参数语义一致：只改主图与主图的缓存键，
     * 占位槽仍按传进来的原 URL 查 `|LOW`。
     */
    hiresCover: Boolean = false,
    /**
     * 按**实际显示尺寸**解码（而不是固定的 [getSizeFromQuality] 档位）。
     *
     * 这是解决"同样的 HIGH 档，小缩略图够清楚、大卡片却糊"的正解：`HIGH` 恒解 400px，
     * 一旦显示宽度超过 400 物理像素（资料库专辑网格 543px、搜索网格 543px）就必然放大。
     * 开启后组件按自身测量到的像素宽取图，既不会不足，也不会像"全局提到 1080"那样
     * 给 169px 的小缩略图也塞一张 4.45MB 的位图。
     *
     * 与 [hiresCover] 互斥（同时开时以本参数为准）：本参数已经能覆盖显示尺寸。
     * RAW 档不受影响（它本来就是按源图原尺寸解码）。
     */
    matchDisplaySize: Boolean = false,
    onImageLoaded: (() -> Unit)? = null
) = YosWrapper {
    val shape = remember(cornerRadius) { YosRoundedCornerShape(cornerRadius) }
    val density = LocalDensity.current
    val url = dataLambda()
    // matchDisplaySize 走高清档 URL（源图侧要有 1080 可拿），再按自身像素宽解码；
    // hiresCover 是它的固定档位版本（源图只按 1080 请求、解码也按 1080 全解）。
    val loadUrl = if (hiresCover || matchDisplaySize) hiresCoverUrl(url) else url
    // 测量到的实际显示边长（px）。0 = 尚未测量，此时先用 1080 占位请求，
    // 测完立刻按真实尺寸重建请求（Compose 会在同一帧内完成，用户看不到中间态）。
    var displayPx by remember(loadUrl) { mutableIntStateOf(0) }
    val imgContext = LocalContext.current
    val request = remember(loadUrl, url, imageQuality, hiresCover, matchDisplaySize, displayPx, imgContext) {
        ImageRequest.Builder(imgContext).data(data = loadUrl).crossfade(false)
            .error(R.drawable.placeholder_music_default_artwork)
            .placeholder(R.drawable.placeholder_music_default_artwork)
            .fallback(R.drawable.placeholder_music_default_artwork)
            // 占位槽用**列表那张**的档位：开了 hiresCover 的调用点（详情页大封面）
            // 来源基本都是 HIGH 档网格卡片，用 HIGH 才能在转场第一帧就贴上同一张图。
            // 用 LOW（128px）会直接落空 → 转场期间露出通用占位图。
            .placeholderMemoryCacheKey(
                coverCacheKey(url, if (hiresCover) ImageQuality.HIGH else ImageQuality.LOW)
            )
            .memoryCacheKey(coverCacheKey(loadUrl, imageQuality))
            // 磁盘键必须跟**实际请求的 URL** 走。留着原 URL 会让 Coil 去磁盘取那张
            // 400 的旧图当成本次解码结果（列表行早就把它写进同一个键了），
            // 高清档等于白设——这是"改了还是糊"的隐蔽来源。
            .diskCacheKey(loadUrl.toString())
            .allowHardware(true)
            .apply {
                when {
                    imageQuality == ImageQuality.RAW -> {
                        // RAW = 按源图原尺寸解码（与 ShadowImageWithCache RAW 分支一致）：
                        // 尺寸确定 ⇒ 内存缓存重进必命中；若按画布尺寸请求，源图小于画布时
                        // 永远无法满足 Coil 的精确尺寸校验（详情页封面每次重新加载的根因）
                        this.precision(Precision.EXACT)
                        this.size(coil.size.Size.ORIGINAL)
                    }
                    matchDisplaySize -> {
                        // 按自身像素边长取图（尚未测量时先按源图右侧的高清档）。
                        // INEXACT：源图本身小于显示尺寸时也接受放大版本，不反复重试。
                        this.precision(Precision.INEXACT)
                        this.size(
                            if (displayPx > 0) displayPx else PlaybackCoverSize
                        )
                    }
                    else -> {
                        val size = getSizeFromQuality(imageQuality)
                        this.size(size)
                        if (imageQuality == ImageQuality.LOW) {
                            this.precision(Precision.INEXACT)
                        }
                    }
                }
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription.toString(),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            // 实测显示边长：决定"要多大一张图才算清楚"。只在需要时挂，
            // 其余调用点保持原样（多一层布局回调没有收益）。
            .then(
                if (matchDisplaySize) {
                    Modifier.onSizeChanged { displayPx = it.width.coerceAtLeast(0) }
                } else Modifier
            )
            .then(
                if (drawShadow) {
                    Modifier.dropShadow(shape, shadowAlpha, shadowType, shadowOverlay)
                } else Modifier
            )
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                this.shape = shape
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
                        color = Color.Gray.copy(alpha = 0.2f),
                        style = Stroke(width = 12f)
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
    hiresCover: Boolean = false,
    /**
     * 按**实际显示尺寸**解码。语义与 [ShadowImage] 的同名参数一致：
     * 固定 (400px) 档在显示宽度超过 400 物理像素的卡片上必然放大（网格 543px 即 1.36 倍）。
     */
    matchDisplaySize: Boolean = false,
    /**
     * 是否由本组件自己画投影。见 [ShadowImage] 的同名参数说明：
     * 切歌交叉淡化期间必须关掉，改由外层容器用 [dropShadow] 画一份恒定阴影。
     */
    drawShadow: Boolean = true
) = YosWrapper {
    val shape = remember(cornerRadius) { YosRoundedCornerShape(cornerRadius) }
    val url = dataLambda()
    val loadUrl = if (hiresCover || matchDisplaySize) hiresCoverUrl(url) else url
    var displayPx by remember(loadUrl) { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val imgContext = LocalContext.current
    val request = remember(loadUrl, url, imageQuality, hiresCover, matchDisplaySize, displayPx, imgContext) {
        ImageRequest.Builder(imgContext).data(data = loadUrl).crossfade(false)
            .error(R.drawable.placeholder_music_default_artwork)
            .placeholder(R.drawable.placeholder_music_default_artwork)
            .fallback(R.drawable.placeholder_music_default_artwork)
            .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
            .memoryCacheKey(coverCacheKey(loadUrl, imageQuality))
            .allowHardware(true)
            .apply {
                when {
                    imageQuality == ImageQuality.RAW -> {
                        this.precision(Precision.EXACT)
                        this.size(coil.size.Size.ORIGINAL)
                    }
                    matchDisplaySize -> {
                        this.precision(Precision.INEXACT)
                        this.size(if (displayPx > 0) displayPx else PlaybackCoverSize)
                    }
                    else -> {
                        val size = getSizeFromQuality(imageQuality)
                        this.size(size)
                        if (imageQuality == ImageQuality.LOW) {
                            this.precision(Precision.INEXACT)
                        }
                    }
                }
            }
            .build()
    }
    AsyncImage(
        model = request,
        contentDescription = contentDescription.toString(),
        contentScale = ContentScale.Crop,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .then(
                if (matchDisplaySize) {
                    Modifier.onSizeChanged { displayPx = it.width.coerceAtLeast(0) }
                } else Modifier
            )
            .then(
                if (drawShadow) {
                    Modifier.dropShadow(shape, shadowAlpha, shadowType, shadowOverlay)
                } else Modifier
            )
            .graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                clip = true
                this.shape = shape
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
                        color = Color.Gray.copy(alpha = 0.2f),
                        style = Stroke(width = 12f)
                    )
                }
            }

    )
}