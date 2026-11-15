package yos.music.player.ui.widgets.basic

import android.graphics.Matrix
import android.net.Uri
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import yos.music.player.data.objects.DynamicCoverState

/**
 * 专辑动态封面视频叠加层（对齐 md3Music `DynamicCoverView`）。
 *
 * - 仅在 [videoUri] 非空时挂载 `ExoPlayer` + `TextureView`，无动态封面的歌曲零内存/解码开销。
 * - 使用 `TextureView` 而非 `SurfaceView`，确保与 Compose 圆角裁剪、3D 按压倾斜矩阵及全屏封面的
 *   `BlendMode.DstIn` 底部渐隐遮罩完全兼容。
 * - 静音且不申请音频焦点（`handleAudioFocus = false`），首帧渲染后以 400ms 缓动淡入覆盖静态封面。
 */
@OptIn(UnstableApi::class)
@Composable
fun DynamicCoverVideoOverlay(
    videoUri: Uri?,
    isPlaying: () -> Boolean,
    active: Boolean,
    modifier: Modifier = Modifier,
    suppressed: () -> Boolean = { false }
) {
    if (videoUri == null) return

    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var firstFrameReady by remember { mutableStateOf(false) }
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var textureViewRef by remember { mutableStateOf<TextureView?>(null) }
    val latestVideoUri by rememberUpdatedState(videoUri)

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
        }
    }

    fun applyCenterCropTransform(tv: TextureView, vw: Int, vh: Int) {
        val viewW = tv.width.toFloat()
        val viewH = tv.height.toFloat()
        if (viewW <= 0f || viewH <= 0f || vw <= 0 || vh <= 0) return
        val viewAspect = viewW / viewH
        val videoAspect = vw.toFloat() / vh.toFloat()
        val (scaleX, scaleY) = if (videoAspect > viewAspect) {
            (videoAspect / viewAspect) to 1f
        } else {
            1f to (viewAspect / videoAspect)
        }
        val matrix = Matrix().apply {
            setScale(scaleX, scaleY, viewW / 2f, viewH / 2f)
        }
        tv.setTransform(matrix)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameReady = true
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoWidth = size.width
                videoHeight = size.height
                textureViewRef?.let { applyCenterCropTransform(it, size.width, size.height) }
            }

            override fun onPlayerError(error: PlaybackException) {
                firstFrameReady = false
                DynamicCoverState.onPlaybackError(latestVideoUri)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            exoPlayer.removeListener(listener)
            exoPlayer.clearVideoTextureView(textureViewRef)
            exoPlayer.release()
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lifecycleStarted = true
                    textureViewRef?.let { exoPlayer.setVideoTextureView(it) }
                }
                Lifecycle.Event.ON_STOP -> {
                    lifecycleStarted = false
                    firstFrameReady = false
                    exoPlayer.pause()
                    exoPlayer.clearVideoTextureView(textureViewRef)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(videoUri, exoPlayer) {
        firstFrameReady = false
        videoWidth = 0
        videoHeight = 0
        exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
        exoPlayer.prepare()
    }

    val musicPlaying = isPlaying()
    val shouldPlay = active && musicPlaying && lifecycleStarted && !suppressed()
    LaunchedEffect(shouldPlay, exoPlayer) {
        exoPlayer.playWhenReady = shouldPlay
        if (shouldPlay) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    val targetAlpha = if (firstFrameReady && !suppressed()) 1f else 0f
    val overlayAlpha by animateFloatAsState(
        targetValue = targetAlpha,
        animationSpec = tween(durationMillis = 400),
        label = "dynamicCoverAlpha"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { alpha = overlayAlpha }
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { ctx ->
                TextureView(ctx).apply {
                    isOpaque = false
                    addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
                        applyCenterCropTransform(this, videoWidth, videoHeight)
                    }
                    textureViewRef = this
                    if (lifecycleStarted) {
                        exoPlayer.setVideoTextureView(this)
                    }
                }
            },
            update = { tv ->
                textureViewRef = tv
                applyCenterCropTransform(tv, videoWidth, videoHeight)
            }
        )
    }
}
