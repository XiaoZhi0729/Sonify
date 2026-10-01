package yos.music.player.ui.widgets.basic

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
    val shadowAlphaPx = remember(dataLambda()) {
        with(density) { shadowAlpha.dp.toPx() }
    }
    val url = dataLambda()
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(data = url).crossfade(true)
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
            .build(),
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
                onDrawWithContent {
                    drawContent()
                    val outline = shape.createOutline(
                        Size(size.width, size.height),
                        LayoutDirection.Ltr,
                        density
                    )
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
    imageQuality: ImageQuality
) = YosWrapper {
    val shape = YosRoundedCornerShape(cornerRadius)
    val url = dataLambda()
    val density = LocalDensity.current
    AsyncImage(
        model = ImageRequest.Builder(LocalContext.current).data(data = url).crossfade(true)
            .error(R.drawable.placeholder_music_default_artwork)
            .placeholder(R.drawable.placeholder_music_default_artwork)
            .fallback(R.drawable.placeholder_music_default_artwork)
            .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
            .memoryCacheKey(coverCacheKey(url, imageQuality))
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
            .build(),
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
                onDrawWithContent {
                    drawContent()
                    val outline = shape.createOutline(
                        Size(size.width, size.height),
                        LayoutDirection.Ltr,
                        density
                    )
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