package yos.music.player.ui.widgets.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.renderscript.Allocation
import android.renderscript.Element
import android.renderscript.RenderScript
import android.renderscript.ScriptIntrinsicBlur
import android.widget.ImageView
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.applyCanvas
import androidx.core.graphics.drawable.toBitmap
import androidx.core.graphics.drawable.toDrawable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.imageLoader
import coil.request.ImageRequest
import kotlin.math.PI
import kotlin.math.sin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.code.utils.others.BitmapResolver
import yos.music.player.data.libraries.SettingsLibrary.NowplayingBackgroundEffect
import yos.music.player.ui.pages.NowPlayingPage
import yos.music.player.ui.widgets.basic.YosWrapper

@Composable
fun YosFloatingLight(
    modifier: Modifier,
    album: () -> Uri?,
    isPlaying: () -> Boolean,
    nowPage: () -> String,
    showMiniPlayer: Boolean
) {
    val drawable = remember {
        mutableStateOf<Drawable?>(null)
    }
    val albumUri = album()

    val context = LocalContext.current
    // 复用全局 ImageLoader（共享内存/磁盘缓存与线程池）；自建实例每首歌都冷缓存重拉原图
    val imageLoader = context.imageLoader
    YosWrapper {
        LaunchedEffect(albumUri) {
            if (albumUri == null) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                // 按最终目标尺寸直接解码（bitmapCompress 的目标 px），不再先解原图再压缩
                val targetPx = if (NowplayingBackgroundEffect) 96 else 32
                val request = ImageRequest.Builder(context)
                    .data(albumUri)
                    .size(targetPx)
                    .build()
                val thisBitmap = imageLoader.execute(request).drawable?.toBitmap()?.run {
                    BitmapResolver.bitmapCompress(this)
                }
                if (thisBitmap != null) {
                    val nextDrawable = imageResolve(context, thisBitmap, bakeDarkOverlay = true)
                        .toDrawable(context.resources)
                    // 仅在结果完整生成后替换，切歌期间保留上一张背景。
                    withContext(Dispatchers.Main) {
                        if (album() == albumUri) {
                            drawable.value = nextDrawable
                        }
                    }
                    thisBitmap.recycle()
                }
            }
        }
    }

    YosWrapper {
        val useBackground = remember("YosFloatingLight_useBackground") {
            derivedStateOf {
                album() == null
            }
        }

        AnimatedContent(
            targetState = drawable.value,
            transitionSpec = {
                fadeIn(animationSpec = tween(450)) togetherWith
                    fadeOut(
                        animationSpec = tween(
                            durationMillis = 1,
                            delayMillis = 450
                        )
                    )
            },
            modifier = modifier,
            contentKey = { it }
        ) { currentDrawable ->
            // 用户裁定：压暗效果在所有子页面（含歌词页）恒显示，不再随 nowPage 撤掉。
            // 原第三层全屏 OVERLAY ImageView 已烘焙进 imageResolve（bakeDarkOverlay），层序少一层全屏合成。
            BackgroundLayer(
                drawable = currentDrawable,
                useKenBurns = NowplayingBackgroundEffect,
                isPlaying = isPlaying,
                nowPage = nowPage,
                showMiniPlayer = showMiniPlayer,
                fallbackToBlack = useBackground.value
            )
        }
    }
}

@Composable
private fun BackgroundLayer(
    drawable: Drawable?,
    useKenBurns: Boolean,
    isPlaying: () -> Boolean,
    nowPage: () -> String,
    showMiniPlayer: Boolean,
    fallbackToBlack: Boolean
) {
    val context = LocalContext.current
    val layerDrawable = remember(drawable) {
        drawable?.constantState?.newDrawable(context.resources)?.mutate()
    }
    val lifecycleState =
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED) && !showMiniPlayer
    val playing = remember(isPlaying) { derivedStateOf(isPlaying) }.value
    val running = playing && active
    // 漂移相位：暂停/后台时 LaunchedEffect 取消，相位保持 → 画面冻结在当前帧；
    // 恢复后从原相位继续（与旧 KenBurnsView.pause/resume 语义一致）。
    val phaseSec = remember { mutableFloatStateOf(0f) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        var lastNanos = -1L
        var accMs = 0f
        while (true) {
            withFrameNanos { now ->
                if (lastNanos < 0L) {
                    lastNanos = now
                    return@withFrameNanos
                }
                val dtMs = (now - lastNanos) / 1_000_000f
                lastNanos = now
                accMs += dtMs
                // 30fps 节流：糊图慢漂移用不到更高帧率，其余帧不写状态、不触发合成
                if (accMs >= DRIFT_FRAME_MS) {
                    phaseSec.floatValue += accMs / 1000f
                    accMs = 0f
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(if (fallbackToBlack) Modifier.graphicsLayer { alpha = 1f } else Modifier)
    ) {
        if (fallbackToBlack) {
            androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black)
            }
        }

        if (layerDrawable != null) {
            // 唯一的全屏图像层。动态模式每帧只写图层变换参数（scale/translation），
            // 不重画内容、不重组；幅度上限 ≤ (最小 scale − 1)/2 保证任何相位都盖满全屏。
            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (useKenBurns) Modifier.graphicsLayer {
                            val t = phaseSec.floatValue
                            val scale = DRIFT_SCALE_BASE +
                                DRIFT_SCALE_AMP * sin(t * DRIFT_SCALE_FREQ)
                            scaleX = scale
                            scaleY = scale
                            translationX = DRIFT_PAN_AMP * size.width *
                                sin(t * DRIFT_PAN_X_FREQ + 1.3f)
                            translationY = DRIFT_PAN_AMP * size.height *
                                sin(t * DRIFT_PAN_Y_FREQ + 4.1f)
                        } else Modifier
                    )
            ) { view ->
                view.setImageDrawable(layerDrawable)
            }
        }
    }
}

// 慢漂移参数：缩放 1.075–1.125（26s 周期），位移双频正弦（31s/23s）模拟 Ken Burns 的游走感。
private const val DRIFT_SCALE_BASE = 1.10f
private const val DRIFT_SCALE_AMP = 0.025f
private const val DRIFT_SCALE_FREQ = (2.0 * PI / 26.0).toFloat()
private const val DRIFT_PAN_AMP = 0.018f
private const val DRIFT_PAN_X_FREQ = (2.0 * PI / 31.0).toFloat()
private const val DRIFT_PAN_Y_FREQ = (2.0 * PI / 23.0).toFloat()
private const val DRIFT_FRAME_MS = 1000f / 30f

/**
 * 封面 → 背景糊图管线：ARGB_8888 复制 → 饱和度×3 → 压暗 →（可选）烘焙压暗层 → 真高斯模糊。
 * [bakeDarkOverlay] 仅背景路径传 true：等价烘焙原第三层全屏 OVERLAY ImageView
 * （同图 OVERLAY 滤镜后按 0.618 alpha 叠回），首页取色遮罩等共享调用方保持默认 false 不变。
 */
fun imageResolve(
    context: Context,
    image: Bitmap,
    moreLight: Boolean = false,
    bakeDarkOverlay: Boolean = false
): Bitmap {
    var resizedBitmap = image.copy(Bitmap.Config.ARGB_8888, true)
    resizedBitmap.applyCanvas {
        val paint = Paint()
        paint.isAntiAlias = true
        paint.isFilterBitmap = true
        paint.isDither = true

        val saturationMatrix = ColorMatrix()
        saturationMatrix.setSaturation(3f)

        paint.colorFilter = ColorMatrixColorFilter(saturationMatrix)
        drawBitmap(resizedBitmap, 0f, 0f, paint)

        if (moreLight) {
            drawColor((0x1AFFFFFF).toInt())
            drawColor((0xFFFFFFFF).toInt(), PorterDuff.Mode.OVERLAY)
            drawColor((0x52FFFFFF).toInt())
            drawColor((0xBFFFFFFF).toInt(), PorterDuff.Mode.OVERLAY)
        } else {
            drawColor((0x33000000).toInt(), PorterDuff.Mode.OVERLAY)
            drawColor((0x40000000).toInt())

            if (bakeDarkOverlay) {
                val layerPaint = Paint().apply { alpha = (0.618f * 255f).toInt() }
                val layer = saveLayer(null, layerPaint)
                drawBitmap(
                    resizedBitmap, 0f, 0f,
                    Paint().apply {
                        isFilterBitmap = true
                        colorFilter = PorterDuffColorFilter(
                            (0x33000000).toInt(),
                            PorterDuff.Mode.OVERLAY
                        )
                    }
                )
                restoreToCount(layer)
            }
        }
    }
    // 真高斯模糊：与原 Toolkit.blur 同一套算法（框架自带 ScriptIntrinsicBlur，
    // 无需恢复 renderscript-toolkit aar 与 gradle 配置）。输出与输入同尺寸，
    // 放大铺满全屏不再有马赛克。切歌频率的一次性成本，毫秒级。
    val blurred = gaussianBlur(context, resizedBitmap)
    if (blurred !== resizedBitmap) {
        resizedBitmap.recycle()
    }
    return blurred
}

/**
 * 框架 RenderScript 高斯模糊（ScriptIntrinsicBlur，API 17+）。与已删除的
 * renderscript-toolkit-release.aar 的 Toolkit.blur 是同一套内核的移植，输出等价。
 * [source] 不被 recycle；返回值归调用方所有（除非 radius 无效直接返回原图）。
 */
@Suppress("DEPRECATION")
fun gaussianBlur(context: Context, source: Bitmap, radius: Float = 25f): Bitmap {
    if (radius <= 0f || source.width <= 0 || source.height <= 0) return source
    val rs = RenderScript.create(context)
    return try {
        val input = Allocation.createFromBitmap(rs, source)
        val output = Allocation.createTyped(rs, input.type)
        val script = ScriptIntrinsicBlur.create(rs, Element.U8_4(rs))
        script.setRadius(radius.coerceAtMost(25f))
        script.setInput(input)
        script.forEach(output)
        val result = Bitmap.createBitmap(source.width, source.height, source.config ?: Bitmap.Config.ARGB_8888)
        output.copyTo(result)
        result
    } finally {
        rs.destroy()
    }
}