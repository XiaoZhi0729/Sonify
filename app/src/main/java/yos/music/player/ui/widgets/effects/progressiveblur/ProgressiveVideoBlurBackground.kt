package yos.music.player.ui.widgets.effects.progressiveblur

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.drawable.toBitmap
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
import coil.imageLoader
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.objects.DynamicCoverState
import yos.music.player.ui.widgets.basic.hiresCoverUrl
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Single-ExoPlayer mirrored dynamic-cover background with true multi-level Gaussian progressive blur.
 *
 * Uses one muted [ExoPlayer] bound to a GLES `SurfaceTexture` (`GL_TEXTURE_EXTERNAL_OES`) and
 * renders into a single [TextureView] so the output remains fully compatible with Compose clips,
 * transforms, and liquid-glass `layerBackdrop` capture. Falls back seamlessly to [fallbackArtworkUrl]
 * when no dynamic cover video is active or before the first video frame arrives.
 */
@OptIn(UnstableApi::class)
@Composable
fun ProgressiveVideoBlurBackground(
    videoUri: Uri?,
    fallbackArtworkUrl: Any?,
    config: ProgressiveBlurConfig,
    isPlaying: Boolean,
    forceStaticCover: Boolean = false,
    coverOriginX: Float = 0f,
    pageBlurProgress: Float = 0f,
    alphaFadeMode: Boolean = false,
    morphTargetRect: Rect? = null,
    morphCornerPx: Float = 0f,
    onSourceInfoChanged: (usingVideo: Boolean, width: Int, height: Int) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    var firstFrameReady by remember { mutableStateOf(false) }
    var lifecycleStarted by remember {
        mutableStateOf(lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    var videoWidth by remember { mutableIntStateOf(0) }
    var videoHeight by remember { mutableIntStateOf(0) }
    var boundVideoSurface by remember { mutableStateOf<Surface?>(null) }

    val latestVideoUri by rememberUpdatedState(videoUri)
    val latestOnSourceInfoChanged by rememberUpdatedState(onSourceInfoChanged)

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val eglThreadRef = remember { AtomicReference<ProgressiveVideoEglThread?>(null) }
    val lastSyncedSize = remember { IntArray(2) }
    val disposed = remember { AtomicBoolean(false) }

    val resolvedCoverUrl = hiresCoverUrl(fallbackArtworkUrl)
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var coverGeneration by remember { mutableLongStateOf(0L) }

    LaunchedEffect(resolvedCoverUrl) {
        val loaded = withContext(Dispatchers.IO) {
            runCatching {
                context.imageLoader.execute(
                    ImageRequest.Builder(context)
                        .data(resolvedCoverUrl ?: R.drawable.placeholder_music_default_artwork)
                        .error(R.drawable.placeholder_music_default_artwork)
                        .fallback(R.drawable.placeholder_music_default_artwork)
                        .size(1080)
                        .bitmapConfig(Bitmap.Config.ARGB_8888)
                        .allowHardware(false)
                        .build(),
                ).drawable?.toBitmap()
            }.getOrNull()
        }
        coverBitmap = loaded
        coverGeneration += 1L
    }

    val exoPlayer = remember(context) {
        ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
        }
    }

    val usingVideo =
        videoUri != null && !forceStaticCover && firstFrameReady && videoWidth > 0 && videoHeight > 0

    val renderState = remember(
        config,
        usingVideo,
        videoWidth,
        videoHeight,
        coverBitmap,
        coverGeneration,
        coverOriginX,
        pageBlurProgress,
        alphaFadeMode,
        morphTargetRect,
        morphCornerPx,
    ) {
        ProgressiveRenderState(
            config = config,
            useVideo = usingVideo,
            videoWidth = videoWidth,
            videoHeight = videoHeight,
            coverBitmap = coverBitmap,
            coverGeneration = coverGeneration,
            coverOriginX = coverOriginX,
            pageBlurProgress = pageBlurProgress,
            alphaFadeMode = alphaFadeMode,
            morphTargetLeft = morphTargetRect?.left ?: 0f,
            morphTargetTop = morphTargetRect?.top ?: 0f,
            morphTargetWidth = morphTargetRect?.width ?: 0f,
            morphTargetHeight = morphTargetRect?.height ?: 0f,
            morphCornerPx = morphCornerPx,
        )
    }
    val latestRenderState by rememberUpdatedState(renderState)

    SideEffect {
        eglThreadRef.get()?.updateState(renderState)
    }

    LaunchedEffect(config.orientationMode, config.coverSize) {
        eglThreadRef.get()?.updateState(latestRenderState)
    }

    LaunchedEffect(usingVideo, videoWidth, videoHeight, coverBitmap) {
        val sourceW = if (usingVideo) videoWidth else (coverBitmap?.width ?: 0)
        val sourceH = if (usingVideo) videoHeight else (coverBitmap?.height ?: 0)
        latestOnSourceInfoChanged(usingVideo, sourceW, sourceH)
    }

    DisposableEffect(exoPlayer) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                firstFrameReady = true
                eglThreadRef.get()?.requestRender()
            }

            override fun onVideoSizeChanged(size: VideoSize) {
                videoWidth = size.width
                videoHeight = size.height
            }

            override fun onPlayerError(error: PlaybackException) {
                firstFrameReady = false
                DynamicCoverState.onPlaybackError(latestVideoUri)
            }
        }
        exoPlayer.addListener(listener)
        onDispose {
            disposed.set(true)
            val videoSurface = boundVideoSurface
            boundVideoSurface = null
            exoPlayer.clearVideoSurface(videoSurface)
            exoPlayer.removeListener(listener)
            exoPlayer.release()
            eglThreadRef.getAndSet(null)?.let { thread ->
                thread.stopAndJoin()
                thread.releaseOutputSurface()
            }
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    DisposableEffect(lifecycleOwner, exoPlayer) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lifecycleStarted = true
                    boundVideoSurface?.let { exoPlayer.setVideoSurface(it) }
                }
                Lifecycle.Event.ON_RESUME -> {
                    eglThreadRef.get()?.setRenderingEnabled(true)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    eglThreadRef.get()?.setRenderingEnabled(false)
                }
                Lifecycle.Event.ON_STOP -> {
                    lifecycleStarted = false
                    firstFrameReady = false
                    exoPlayer.pause()
                    exoPlayer.clearVideoSurface(boundVideoSurface)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(videoUri, forceStaticCover, exoPlayer) {
        firstFrameReady = false
        videoWidth = 0
        videoHeight = 0
        if (videoUri != null && !forceStaticCover) {
            exoPlayer.setMediaItem(MediaItem.fromUri(videoUri))
            exoPlayer.prepare()
        } else {
            exoPlayer.stop()
            exoPlayer.clearMediaItems()
        }
    }

    val shouldPlay = videoUri != null && !forceStaticCover && isPlaying && lifecycleStarted
    LaunchedEffect(shouldPlay, exoPlayer) {
        exoPlayer.playWhenReady = shouldPlay
        if (shouldPlay) {
            exoPlayer.play()
        } else {
            exoPlayer.pause()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isOpaque = !alphaFadeMode
                addOnLayoutChangeListener { v, left, top, right, bottom, _, _, _, _ ->
                    val textureView = v as TextureView
                    syncTextureViewResize(
                        textureView = textureView,
                        width = right - left,
                        height = bottom - top,
                        lastSyncedSize = lastSyncedSize,
                        eglThread = eglThreadRef.get(),
                        renderState = latestRenderState,
                    )
                }
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        lastSyncedSize[0] = width
                        lastSyncedSize[1] = height
                        if (width > 0 && height > 0) {
                            surfaceTexture.setDefaultBufferSize(width, height)
                        }
                        val outputSurface = Surface(surfaceTexture)
                        lateinit var createdThread: ProgressiveVideoEglThread
                        createdThread = ProgressiveVideoEglThread(
                            outputSurface = outputSurface,
                            initialWidth = width,
                            initialHeight = height,
                            initialState = latestRenderState,
                            onVideoSurfaceReady = { videoSurface ->
                                mainHandler.post {
                                    if (!disposed.get() && eglThreadRef.get() === createdThread) {
                                        boundVideoSurface = videoSurface
                                        if (lifecycleStarted) {
                                            exoPlayer.setVideoSurface(videoSurface)
                                        }
                                    }
                                }
                            },
                            onFirstVideoFrameLatched = {
                                mainHandler.post {
                                    if (!disposed.get() && eglThreadRef.get() === createdThread) {
                                        createdThread.requestRender()
                                    }
                                }
                            },
                        )
                        val old = eglThreadRef.getAndSet(createdThread)
                        if (old != null) {
                            val prevSurface = boundVideoSurface
                            boundVideoSurface = null
                            exoPlayer.clearVideoSurface(prevSurface)
                            old.stopAndJoin()
                            old.releaseOutputSurface()
                        }
                        createdThread.setRenderingEnabled(
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
                        )
                        createdThread.start()
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        syncTextureViewResize(
                            textureView = this@apply,
                            width = width,
                            height = height,
                            lastSyncedSize = lastSyncedSize,
                            eglThread = eglThreadRef.get(),
                            renderState = latestRenderState,
                            force = true,
                        )
                    }

                    override fun onSurfaceTextureDestroyed(
                        surfaceTexture: SurfaceTexture,
                    ): Boolean {
                        val videoSurface = boundVideoSurface
                        boundVideoSurface = null
                        exoPlayer.clearVideoSurface(videoSurface)
                        eglThreadRef.getAndSet(null)?.let { thread ->
                            thread.stopAndJoin()
                            thread.releaseOutputSurface()
                        }
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) = Unit
                }
            }
        },
        update = { textureView ->
            val shouldBeOpaque = !alphaFadeMode
            if (textureView.isOpaque != shouldBeOpaque) {
                textureView.isOpaque = shouldBeOpaque
                textureView.invalidate()
                eglThreadRef.get()?.requestRender()
            }
            syncTextureViewResize(
                textureView = textureView,
                width = textureView.width,
                height = textureView.height,
                lastSyncedSize = lastSyncedSize,
                eglThread = eglThreadRef.get(),
                renderState = latestRenderState,
            )
        },
    )
}

private fun syncTextureViewResize(
    textureView: TextureView,
    width: Int,
    height: Int,
    lastSyncedSize: IntArray,
    eglThread: ProgressiveVideoEglThread?,
    renderState: ProgressiveRenderState,
    force: Boolean = false,
) {
    if (width <= 0 || height <= 0) return
    if (!force && width == lastSyncedSize[0] && height == lastSyncedSize[1]) return
    lastSyncedSize[0] = width
    lastSyncedSize[1] = height
    textureView.surfaceTexture?.setDefaultBufferSize(width, height)
    eglThread?.resizeAndUpdateState(width, height, renderState)
    textureView.postInvalidateOnAnimation()
}
