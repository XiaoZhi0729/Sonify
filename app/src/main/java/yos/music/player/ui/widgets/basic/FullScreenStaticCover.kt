package yos.music.player.ui.widgets.basic

import android.graphics.Bitmap
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.rememberAsyncImagePainter
import coil.imageLoader
import coil.request.ImageRequest
import com.google.android.renderscript.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.ui.widgets.effects.CoverArtworkState
import yos.music.player.ui.widgets.effects.coverArtworkEffect
import yos.music.player.ui.widgets.effects.coverArtworkGesture
import kotlin.math.roundToInt

/**
 * 全屏静态封面层（竖屏 Album 页专用，对应 Flamingo 的「全屏静态封面」）。
 *
 * 屏幕上的三段（从上到下）与各自的负责人：
 *  1. **封面**：本层的高清方图（方框边长 1.12×层宽、**顶边贴屏幕边缘**），ContentScale.Crop 语义；
 *     底边再**向下延伸** [FullScreenCoverBottomExtensionDp]（≈10dp）——延伸段画的是同一张方图
 *     最底部的像素（整张下移一段、只露出底边以下那一条），不额外加层、层级与封面完全一致；
 *     底边那条 DstIn 竖直渐变**长度不变**（[FullScreenCoverCoverFadeFraction]×方图边长），
 *     随封面底边一起下移，延伸段因此落在渐变末端被淡掉；
 *  2. **模糊副本**：本层可选的一段（[FullScreenCoverShowBlurLayer]，当前 **false**）；
 *  3. **外壳播放页背景**：`YosFloatingLight`（32px 上采样 + 饱和度×3 的"提取色"），
 *     不在本层内 —— 它常驻在播放页之下，第 1/2 段不画的地方就露出它。
 *
 * 当前组合（第二段关闭）= 封面（底边 10dp 延伸 + 淡出）→ 外壳背景；
 * 即"背景从封面下方渐变顶上来"，没有硬边、没有独立模糊段，**也没有任何暗色覆盖**
 * （参考实现那条"封面下方压暗"的 scrim 已按用户要求删除，数据保留在 [fullScreenCoverScrim]）。
 *
 * 与外壳封面 morph 的关系（红线）：
 *  - 高清封面必须受 [suppressed]（= 外壳 `coverLayerActive`）门禁，否则与外壳单封面层
 *    同时画出两张封面；
 *  - 背景（模糊副本/渐隐）**不进门禁**——它们属于页面背景，随页面转场一起淡出；若一起停画，
 *    开合动画结束那一帧背景会"啪"地出现；
 *  - [coordsOnChanged] 必须继续上报高清方框节点坐标，否则外壳 `coverGeometry` 丢 album
 *    端点、开合 morph 退化为顶栏 69dp 落点。**上报的是外层正方形节点**（1.12×层宽）：
 *    底边延伸段只加在内层画布上，端点因此不会被那 10dp 拉成非正方形。
 *
 * 高清封面刻意与外壳 morph 层**完全同参**加载（`coverCacheKey(url, RAW)` + EXACT + ORIGINAL），
 * 命中同一份位图：交接瞬间逐像素一致，也不额外解码。
 *
 * @param albumUrl 封面数据源（与 `YosMediaItem.thumb` 同一份 Uri）。
 * @param suppressed 外壳封面 morph 进行中时为 true（只停画高清封面）。
 * @param coordsOnChanged 高清方框节点的坐标上报（外壳 morph 的 album 端点）。
 * @param coverState 封面按压动效状态（与顶栏小封面共用，宿主常驻那份）。
 * @param coverEnabled 按压/点击闸门（在按下那一刻求值）。
 */
@Composable
internal fun FullScreenStaticCover(
    albumUrl: () -> Any?,
    suppressed: () -> Boolean,
    coordsOnChanged: (LayoutCoordinates) -> Unit,
    coverState: CoverArtworkState,
    coverEnabled: () -> Boolean,
    onCoverClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val url = albumUrl()
    val blurred = rememberBlurredCover(url, enabled = FullScreenCoverShowBlurLayer)
    val painter = rememberAsyncImagePainter(
        model = ImageRequest.Builder(context)
            .data(url)
            // 静态封面没有进度驱动，crossfade 只会让切歌时多一层淡入。
            .crossfade(false)
            .error(R.drawable.placeholder_music_default_artwork)
            .fallback(R.drawable.placeholder_music_default_artwork)
            .placeholder(R.drawable.placeholder_music_default_artwork)
            // 与外壳 morph 层同缓存槽（cover|RAW）→ 命中同一份位图
            .memoryCacheKey(coverCacheKey(url, ImageQuality.RAW))
            .placeholderMemoryCacheKey(coverCacheKey(url, ImageQuality.LOW))
            .allowHardware(true)
            .precision(coil.size.Precision.EXACT)
            .size(coil.size.Size.ORIGINAL)
            .build()
    )

    BoxWithConstraints(
        modifier
            .fillMaxSize()
            // 方框四边溢出，必须裁在页面内，否则会画到下方控件带上
            .clipToBounds()
    ) {
        // 顶边贴屏幕边缘锚定（不向上溢出）：图片整体下移，顶部不再被裁
        val sharpSide = fullScreenCoverSharpSide(maxWidth.value).dp
        // 画布高 = 方图边长 + 底边延伸段：多出来的那一截就是"封面最底部的像素向下延伸"，
        // 它自带由上到下的淡出（见 FullScreenCoverBottomExtensionDp）。
        val sharpCanvasHeight = fullScreenCoverSharpHeight(maxWidth.value).dp

        // 1) 背景：模糊副本（第二段，当前开关关闭）+ 一道暗渐变。
        //    不随 morph 门禁隐藏，见类注释。
        Spacer(
            Modifier
                .fillMaxSize()
                .drawBehind { drawFullScreenCoverBackdrop(blurred, size.width) }
        )

        // 2) 高清封面。节点尺寸必须 requiredSize：size() 会被父约束钳到层宽，
        //    方框就退回 1.0×层宽，横向溢出消失。
        Box(
            Modifier
                .requiredSize(sharpSide)
                .align(Alignment.TopCenter)
                // 坐标上报与手势挂在**未变换**的外层节点：morph 落点读的是布局坐标，
                // 变换层若在外层，按压尚未回位就切页会把倾斜/缩放渗进 morph 源矩形。
                // 外层保持 1.12×层宽的**正方形**（底边延伸段只加在内层画布上），
                // 外壳 morph 的 album 端点因此不受延伸段影响。
                .onGloballyPositioned { coordsOnChanged(it) }
                .coverArtworkGesture(
                    state = coverState,
                    enabled = coverEnabled,
                    onClick = onCoverClick
                )
        ) {
            Spacer(
                Modifier
                    // 内层画布比方图高 10dp：方图按 ContentScale.Crop 铺满整块画布，
                    // 底边因此多出这一段，再由 drawFullScreenCoverBottomFade 淡掉。
                    // 它向下溢出外层方框 10dp（Box 不裁剪），页面级 clipToBounds 兜底。
                    .requiredSize(sharpSide, sharpCanvasHeight)
                    .align(Alignment.TopCenter)
                    .graphicsLayer {
                        // Offscreen 是封面底边淡出（DstIn）的必需图层作用域
                        compositingStrategy = CompositingStrategy.Offscreen
                        // 外壳单封面层接管期间隐藏本节点：读 State 发生在 layer 阶段，
                        // 只失效图层、不触发重组，也不改布局占位。
                        alpha = if (suppressed()) 0f else 1f
                    }
                    // 按压反馈沿用大封面那一套（同角半径语义：全屏封面无圆角）。
                    // rimHighlight = false：全屏封面是满屏出血的图，不该有那圈半透明白描边
                    // （描边是给带圆角的卡片封面用的）；涟漪/倾斜/缩放保留。
                    .coverArtworkEffect(
                        state = coverState,
                        cornerRadius = 0.dp,
                        rimHighlight = false
                    )
                    .drawWithContent {
                        drawFullScreenCoverSharp(painter)
                        // 底边延伸段（画在方图之下、同层）：封面最底部的像素向下多露 10dp
                        drawFullScreenCoverBottomExtension(painter)
                        // 底边淡出 → 下面的背景（第三段）向上渐变顶上来
                        drawFullScreenCoverBottomFade()
                    }
            )
        }
    }
}

// ---------------------------------------------------------------------------
// 绘制
// ---------------------------------------------------------------------------

/**
 * 背景层：只有可选的模糊副本（[FullScreenCoverShowBlurLayer]，当前关闭）。
 * 原参考实现那条"封面下方压暗"的 scrim 已按用户要求**整条删除**（真机上它表现为
 * 封面下方一块黑色半透明遮挡）；这里不再有任何暗色覆盖。
 */
private fun DrawScope.drawFullScreenCoverBackdrop(blurred: ImageBitmap?, layerWidth: Float) {
    if (layerWidth <= 0f) return

    if (blurred != null) {
        val box = fullScreenCoverBox(layerWidth, FullScreenCoverBlurScale)
        drawImage(
            image = blurred,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(blurred.width, blurred.height),
            dstOffset = IntOffset(box.left.roundToInt(), box.top.roundToInt()),
            dstSize = IntSize(box.side.roundToInt(), box.side.roundToInt()),
            // 源图只有 128px，放大到 1.72×层宽；双线性足够，且更便宜
            filterQuality = FilterQuality.Low
        )
    }
}

/**
 * 高清封面：ContentScale.Crop 语义把 Painter 铺满**封面方图**（1.12×层宽的正方形）。
 *
 * 方图本身的缩放/裁切与加延伸段之前**逐像素一致**：延伸段不复用它去撑高画布，
 * 而是另画一遍（见 [drawFullScreenCoverBottomExtension]）。
 */
private fun DrawScope.drawFullScreenCoverSharp(painter: Painter) {
    val side = size.width
    if (side <= 0f) return
    drawCroppedCover(painter, width = side, height = side, top = 0f)
}

/**
 * 底边延伸段：把封面方图**最底下那一条像素**（[FullScreenCoverBottomExtensionSourcePx]）纵向拉伸，
 * 铺满方图底边以下那 [FullScreenCoverBottomExtensionDp] —— 即"封面最底部的像素向下延伸"。
 *
 * 注意**不是**把底部 10dp 裁出来复制一份贴在下面（那样会看到一段重复的画面）：
 * 这里只取最底下一行像素，把它拉长成整条延伸段。
 *
 * 做法：整张方图按 `ext/row` 纵向拉伸，支点选在"让那一行的底边正好落在条带底边"处，
 * 于是只有那一行会落进 [side, side+ext]（其余都被裁掉）。它和封面同层，落在底边渐变的
 * 末端上，随封面底边一起被淡掉。
 */
private fun DrawScope.drawFullScreenCoverBottomExtension(painter: Painter) {
    val side = size.width
    val ext = size.height - side
    val row = FullScreenCoverBottomExtensionSourcePx
    if (side <= 0f || ext <= 0f || row <= 0f) return
    val k = ext / row
    // 源行底边(y=side) → 条带底边(y=side+ext)
    val pivot = fullScreenCoverBottomExtensionPivot(side, ext, row)
    clipRect(0f, side, side, size.height) {
        scale(scaleX = 1f, scaleY = k, pivot = Offset(0f, pivot)) {
            drawCroppedCover(painter, width = side, height = side, top = 0f)
        }
    }
}

/**
 * ContentScale.Crop：把 Painter 铺满 (0,top)-(width,top+height)，溢出显式裁掉
 * （图层是否裁到节点边界不是能依赖的语义，铺满后超出的部分会画到别处去）。
 */
private fun DrawScope.drawCroppedCover(painter: Painter, width: Float, height: Float, top: Float) {
    if (width <= 0f || height <= 0f) return
    val intrinsic = painter.intrinsicSize
    clipRect(0f, top, width, top + height) {
        if (!intrinsic.isSpecified || intrinsic.width <= 0f || intrinsic.height <= 0f) {
            translate(0f, top) { with(painter) { draw(Size(width, height)) } }
        } else {
            val k = maxOf(width / intrinsic.width, height / intrinsic.height)
            val dw = intrinsic.width * k
            val dh = intrinsic.height * k
            translate((width - dw) / 2f, top + (height - dh) / 2f) {
                with(painter) { draw(Size(dw, dh)) }
            }
        }
    }
}

/**
 * 封面底边淡出：用 DstIn 渐变把画布**最后一段**擦掉，让下面的背景透上来。
 *
 * 渐变**长度恒定** = [FullScreenCoverCoverFadeFraction]×封面方图边长，末端永远落在画布底边；
 * 加了底边延伸段之后整条渐变跟着下移同样多（长度不变、位置下移 = 封面底边往下长了 10dp）。
 *
 * 必须挂在 `compositingStrategy = Offscreen` 的节点上（DstIn 需要一个图层作用域，
 * 否则会擦到整页画布上）。
 */
private fun DrawScope.drawFullScreenCoverBottomFade() {
    val nodeHeight = size.height
    val startY = fullScreenCoverBottomFadeStartY(
        coverSide = size.width,
        nodeHeight = nodeHeight,
        fraction = FullScreenCoverCoverFadeFraction
    )
    if (startY >= nodeHeight) return
    drawRect(
        brush = Brush.verticalGradient(
            colorStops = arrayOf(
                0f to Color.Black,
                (startY / nodeHeight) to Color.Black,
                1f to Color.Transparent
            ),
            startY = 0f,
            endY = nodeHeight
        ),
        blendMode = BlendMode.DstIn
    )
}

// ---------------------------------------------------------------------------
// 模糊副本：按 URL 缓存（容量与 Flamingo 同为 3）
// ---------------------------------------------------------------------------

private const val FullScreenCoverBlurCacheSize = 3

private object FullScreenCoverBlurCache {
    private val mem = object : LinkedHashMap<String, ImageBitmap>(4, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, ImageBitmap>
        ): Boolean = size > FullScreenCoverBlurCacheSize
    }

    fun get(key: String): ImageBitmap? = synchronized(this) { mem[key] }

    fun put(key: String, bitmap: ImageBitmap) {
        synchronized(this) { mem[key] = bitmap }
    }
}

/**
 * 模糊副本：同图 128px 载入 → `Toolkit.blur(radius = 25)`（与播放页背景同一套 RenderScript
 * Toolkit，但**不做**饱和度/压暗——压暗由两道渐隐负责）。结果按 URL 缓存，切歌只付一次。
 * 任何一步失败都返回 null：那一帧只画渐隐，不阻塞封面。
 */
@Composable
private fun rememberBlurredCover(url: Any?, enabled: Boolean): ImageBitmap? {
    val context = LocalContext.current
    val key = url?.toString()
    var blurred by remember(key, enabled) {
        mutableStateOf(if (enabled) key?.let { FullScreenCoverBlurCache.get(it) } else null)
    }
    LaunchedEffect(key, enabled) {
        if (!enabled || key == null) {
            blurred = null
            return@LaunchedEffect
        }
        if (FullScreenCoverBlurCache.get(key) != null) {
            blurred = FullScreenCoverBlurCache.get(key)
            return@LaunchedEffect
        }
        val baked = withContext(Dispatchers.IO) {
            runCatching {
                val source = context.imageLoader
                    .execute(
                        ImageRequest.Builder(context)
                            .data(url)
                            .size(FullScreenCoverBlurSourcePx)
                            // Toolkit.blur 只吃 ARGB_8888，且不吃硬件位图
                            .bitmapConfig(Bitmap.Config.ARGB_8888)
                            .allowHardware(false)
                            .memoryCacheKey("$url|fullscreen-blur-source")
                            .build()
                    )
                    .drawable
                    ?.toBitmap()
                    ?: return@runCatching null
                // 注意：source 是 Coil 内存缓存持有的那份位图（toBitmap 不复制），
                // 绝不能 recycle —— 只交给 GC；只有 Toolkit 新产出的这张才归我们。
                Toolkit.blur(source, FullScreenCoverBlurRadius).asImageBitmap()
            }.getOrNull()
        }
        if (baked != null) FullScreenCoverBlurCache.put(key, baked)
        blurred = baked
    }
    return blurred
}

// ---------------------------------------------------------------------------
// 几何（纯函数，单测见 FullScreenCoverGeometryTest）
// ---------------------------------------------------------------------------

/**
 * 第二段（模糊副本）开关。
 *
 * `false`（当前）= 不画模糊副本，封面下方直接是**外壳那张播放页背景**（`YosFloatingLight`，
 * 32px 上采样 + 饱和度×3 的"提取色"），两段由封面底边那 10dp 延伸段的淡出接起来
 * （[FullScreenCoverBottomExtensionDp]）。
 * `true` = 恢复参考实现那层 1.72×层宽的模糊副本（需要 [FullScreenCoverBlurRadius] 那套）。
 */
internal const val FullScreenCoverShowBlurLayer = false

/**
 * 封面底边**向下延伸**的长度（dp）。
 *
 * 画布比封面方图（1.12×层宽的正方形）高这么多：方图照旧画在顶部正方形里，多出来的那一条
 * 由 [drawFullScreenCoverBottomExtension] 画上"方图最底部的像素"（同一张图下移一段、只露出
 * 底边以下的部分）——即"封面最底部的像素向下延伸 10DP"。它和封面同层，落在底边渐变的末端上。
 * 用户第七轮要求："封面最底部的像素向下延伸大概 10DP、由上到下逐渐变透明"。
 */
internal const val FullScreenCoverBottomExtensionDp = 10f

/**
 * 底边延伸段的**取源高度**（px）：只取封面方图最底下这一条像素去拉伸。
 * 1px = "最底部的像素"本身；调大就等于把底部更多像素一起拉长（会带上一点纵向渐变）。
 */
internal const val FullScreenCoverBottomExtensionSourcePx = 1f

/**
 * 高清封面**底边淡出**比例（相对封面方图边长）——**渐变长度**就是这个值。
 *
 * 0.25 = 用户第三轮"加大渐变力度"后定下的长度（本轮用户要求"渐变长度不变"，已还原）。
 * 渐变的**位置**随 [FullScreenCoverBottomExtensionDp] 一起下移（末端永远在画布底边），
 * 所以加延伸段只把整条渐变往下推，不会把它拉长或截短。
 */
internal const val FullScreenCoverCoverFadeFraction = 0.25f

/**
 * 高清封面方框边长 = [FullScreenCoverSharpScale]×层宽。
 * **顶边贴屏幕边缘锚定**（节点无 y 偏移）→ 封面可见底边 = 方框边长 = scale×层宽；
 * 参考实现是"四边溢出居中"（顶裁 0.1×层宽、可见底边 1.1×层宽），这里按用户要求改成贴顶。
 */
internal fun fullScreenCoverSharpSide(layerWidth: Float): Float =
    layerWidth * FullScreenCoverSharpScale

/**
 * 高清封面**画布**高度 = 方框边长 + [FullScreenCoverBottomExtensionDp]（单位同
 * [fullScreenCoverSharpSide]，调用点按 dp 用）。比方图高的那一截就是底边延伸段。
 */
internal fun fullScreenCoverSharpHeight(layerWidth: Float): Float =
    fullScreenCoverSharpSide(layerWidth) + FullScreenCoverBottomExtensionDp

/**
 * 底边延伸段的拉伸支点：整张方图按 `ext/row` 纵向拉伸时，让"最底下 row 高的一条像素"的
 * **底边正好落在条带底边**（y = side + ext）的那个支点 → 只有这一条会落进延伸段。
 * 推导：支点 p 满足 `p·(1−k) + side·k = side + ext`（k = ext/row）。
 */
internal fun fullScreenCoverBottomExtensionPivot(side: Float, ext: Float, row: Float): Float =
    side + ext / (1f - ext / row)

/**
 * 底边淡出的起点 y（画布内坐标）：**从画布底边往上量** [fraction]×封面方图边长。
 *
 * 这样渐变的**长度**恒为 `fraction × coverSide`（与有没有延伸段无关），末端永远贴在画布底边
 * ——加了延伸段就整条往下挪，即"封面底边往下长了 10dp，渐变本身没变"。
 * `fraction = 0` → 返回画布底边（起点即终点 = 不淡出，硬边）。
 */
internal fun fullScreenCoverBottomFadeStartY(
    coverSide: Float,
    nodeHeight: Float,
    fraction: Float
): Float = nodeHeight - coverSide * fraction.coerceIn(0f, 1f)

/** 高清封面方框边长倍率。1.2（参考实现）= 放大偏大，用户要求收敛。 */
internal const val FullScreenCoverSharpScale = 1.12f

/** 模糊副本方框边长 = 1.72×层宽（四边各溢出 0.36×层宽）。 */
internal const val FullScreenCoverBlurScale = 1.72f

/** 模糊半径与模糊源边长（与 Flamingo 逐项一致；Toolkit.blur 的半径是 Int）。 */
internal const val FullScreenCoverBlurRadius = 25
internal const val FullScreenCoverBlurSourcePx = 128

/** 一个"四边溢出居中"的方框：以层左上角为原点。 */
internal data class FullScreenCoverBox(val side: Float, val left: Float, val top: Float)

internal fun fullScreenCoverBox(layerWidth: Float, scale: Float): FullScreenCoverBox {
    val side = layerWidth * scale
    val over = (side - layerWidth) / 2f
    return FullScreenCoverBox(side = side, left = -over, top = -over)
}

/** ContentScale.Crop 的缩放系数：铺满 box×box（超出部分居中裁掉）。 */
internal fun fullScreenCoverCropScale(imageWidth: Float, imageHeight: Float, box: Float): Float =
    if (imageWidth <= 0f || imageHeight <= 0f) 1f
    else maxOf(box / imageWidth, box / imageHeight)

/**
 * 一条平滑的黑色渐隐（位置, 黑度），y 轴 [0, 1.72×层宽]。
 *
 * **当前未启用**：用户第三轮反馈"封面下方那块黑色半透明遮挡"即本渐变，要求删掉，
 * 故 [drawFullScreenCoverBackdrop] 不再画它（保留数据是为了随时能一行加回）。
 * 标定参考：参考实现真机在封面下方（1.08~1.35×层宽）只比其下方模糊区暗约 19%，
 * 峰值取 0.25 即与之对齐（反编译出的 stop 表是 100% 黑平台，照抄会压出黑板）。
 */
internal val fullScreenCoverScrim: List<Pair<Float, Float>> = listOf(
    0f to 0f,                                // 封面区域（被高清封面盖住）
    0.55f to 0f,                             // 0.95×层宽：封面底边之前不压暗
    0.64f to FullScreenCoverScrimMaxBlack,   // 1.10×层宽：封面可见底边处
    0.78f to FullScreenCoverScrimMaxBlack,   // 1.34×层宽：实测最低点（与真机同位置）
    0.92f to 0.1f,                           // 1.58×层宽
    1f to 0f                                 // 1.72×层宽：回到不压暗
)

/** 渐隐峰值黑度（标定值；当前未启用，见 [fullScreenCoverScrim]）。 */
internal const val FullScreenCoverScrimMaxBlack = 0.25f

/** 渐隐的 y 轴长度（相对层宽；当前未启用，见 [fullScreenCoverScrim]）。 */
internal const val FullScreenCoverScrimLength = 1.72f

// Brush 只吃 vararg 重载（本项目 Compose 无 List 版），统一在这里展开。
internal fun fullScreenCoverScrimStops(): Array<Pair<Float, Color>> =
    fullScreenCoverScrim
        .map { (position, black) -> position to Color.Black.copy(alpha = black) }
        .toTypedArray()
