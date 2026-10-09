package yos.music.player.ui.widgets.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.drawable.Drawable
import android.net.Uri
import android.view.animation.AccelerateDecelerateInterpolator
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import coil.ImageLoader
import coil.request.ImageRequest
import com.flaviofaria.kenburnsview.KenBurnsView
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.google.android.renderscript.Toolkit
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
    val imageLoader = remember(context) { ImageLoader(context) }
    YosWrapper {
        LaunchedEffect(albumUri) {
            if (albumUri == null) return@LaunchedEffect
            withContext(Dispatchers.IO) {
                val request = ImageRequest.Builder(context)
                    .data(albumUri)
                    .build()
                val thisBitmap = imageLoader.execute(request).drawable?.toBitmap()?.run {
                    BitmapResolver.bitmapCompress(this)
                }
                if (thisBitmap != null) {
                    val nextDrawable = imageResolve(thisBitmap)
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
        val lossEffect = remember("YosFloatingLight_lossEffect") {
            derivedStateOf {
                nowPage() != NowPlayingPage.Lyric
            }
        }

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
            BackgroundLayer(
                drawable = currentDrawable,
                useKenBurns = NowplayingBackgroundEffect,
                isPlaying = isPlaying,
                nowPage = nowPage,
                showMiniPlayer = showMiniPlayer,
                showDarkOverlay = lossEffect.value,
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
    showDarkOverlay: Boolean,
    fallbackToBlack: Boolean
) {
    val context = LocalContext.current
    val layerDrawable = remember(drawable) {
        drawable?.constantState?.newDrawable(context.resources)?.mutate()
    }
    val overlayDrawable = remember(drawable) {
        drawable?.constantState?.newDrawable(context.resources)?.mutate()
    }
    val baseDrawable = remember(drawable) {
        drawable?.constantState?.newDrawable(context.resources)?.mutate()
    }
    val lifecycleState =
        LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val active = lifecycleState.value.isAtLeast(Lifecycle.State.RESUMED) && !showMiniPlayer

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
            if (useKenBurns) {
                val lastPlaying = remember { mutableStateOf<Boolean?>(null) }
                // 垫静态层兜底动画控件尚未绘出首帧的空窗（如图未加载完）。
                AndroidView(
                    factory = {
                        ImageView(it).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { view ->
                    view.setImageDrawable(baseDrawable)
                }
                AndroidView(
                    factory = {
                        SafeKenBurnsView(it).apply {
                            setTransitionGenerator(
                                RandomTransitionGenerator(
                                    6000,
                                    AccelerateDecelerateInterpolator()
                                )
                            )
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { view ->
                    if (view.drawable !== layerDrawable) {
                        view.setImageDrawable(layerDrawable)
                    }
                    val shouldPause = !isPlaying() || !active
                    if (lastPlaying.value != shouldPause) {
                        if (shouldPause) view.pause() else view.resume()
                        lastPlaying.value = shouldPause
                    }
                }
            } else {
                AndroidView(
                    factory = {
                        ImageView(it).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                ) { view ->
                    view.setImageDrawable(layerDrawable)
                }
            }

            AndroidView(
                factory = {
                    ImageView(it).apply {
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        colorFilter = PorterDuffColorFilter(
                            android.graphics.Color.argb(0x33, 0, 0, 0),
                            PorterDuff.Mode.OVERLAY
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = if (showDarkOverlay) 0.618f else 0f }
            ) { view ->
                view.setImageDrawable(overlayDrawable)
                view.alpha = if (showDarkOverlay) 0.618f else 0f
            }
        }
    }
}

/**
 * kenburnsview 1.0.7 的 onDraw 在暂停时会跳过整段矩阵重算；从未运行动画的控件矩阵是单位矩阵，
 * 会把图按原始尺寸画在左上角。旋转走 configChanges 不重建控件，暂停态旋转后 imageMatrix 仍是
 * 按旧视口算出的非单位矩阵，旧构图画在新画布上会盖不满全屏、露出底下的静态兜底层。因此不能只修
 * 单位矩阵场景，改为：视口尺寸与上次计算 cover 矩阵时不一致（首绘 + 旋转等尺寸变化）就重算铺满
 * 全屏的 cover 矩阵；尺寸未变时不触发，不干扰 Ken Burns 动画自身的 setImageMatrix，暂停仍定格
 * 在动画当前帧，恢复播放时从原地继续。
 */
private class SafeKenBurnsView(context: Context) : KenBurnsView(context) {
    private var lastMatrixWidth = 0
    private var lastMatrixHeight = 0

    override fun onDraw(canvas: Canvas) {
        val d = drawable
        if (d != null && width > 0 && height > 0 &&
            d.intrinsicWidth > 0 && d.intrinsicHeight > 0 &&
            (width != lastMatrixWidth || height != lastMatrixHeight)
        ) {
            val scale = maxOf(
                width / d.intrinsicWidth.toFloat(),
                height / d.intrinsicHeight.toFloat()
            )
            setImageMatrix(
                Matrix().apply {
                    setScale(scale, scale)
                    postTranslate(
                        width / 2f - d.intrinsicWidth * scale / 2f,
                        height / 2f - d.intrinsicHeight * scale / 2f
                    )
                }
            )
            lastMatrixWidth = width
            lastMatrixHeight = height
        }
        super.onDraw(canvas)
    }
}

fun imageResolve(image: Bitmap, moreLight: Boolean = false): Bitmap {
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
        }
    }
    resizedBitmap = Toolkit.blur(resizedBitmap, 25)
    return resizedBitmap
}