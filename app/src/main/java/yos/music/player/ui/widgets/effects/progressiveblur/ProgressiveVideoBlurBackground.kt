package yos.music.player.ui.widgets.effects.progressiveblur

import android.content.Context
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.TextureView
import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import coil.memory.MemoryCache
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.objects.DynamicCoverState
import yos.music.player.ui.widgets.basic.hiresCoverUrl
import yos.music.player.ui.widgets.effects.TrackArtworkCrossfadeSpec
import yos.music.player.ui.widgets.effects.TrackArtworkTransition
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal const val PROGRESSIVE_COVER_MAX_PX = 1080
internal const val PROGRESSIVE_VIDEO_FADE_MS = 400

internal fun progressiveBlurCoverCacheKey(resolvedUrl: Any?): String =
    "progressive|${resolvedUrl ?: "default"}|$PROGRESSIVE_COVER_MAX_PX"

internal fun buildProgressiveCoverImageRequest(
    context: Context,
    resolvedUrl: Any?,
    cacheKey: String = progressiveBlurCoverCacheKey(resolvedUrl),
): ImageRequest = ImageRequest.Builder(context)
    .data(resolvedUrl ?: R.drawable.placeholder_music_default_artwork)
    .error(R.drawable.placeholder_music_default_artwork)
    .fallback(R.drawable.placeholder_music_default_artwork)
    .size(PROGRESSIVE_COVER_MAX_PX)
    .bitmapConfig(Bitmap.Config.ARGB_8888)
    .allowHardware(false)
    .memoryCacheKey(cacheKey)
    .build()

internal fun peekProgressiveBlurCover(context: Context, cacheKey: String): Bitmap? {
    val cached = context.imageLoader.memoryCache?.get(MemoryCache.Key(cacheKey))?.bitmap ?: return null
    return cached.takeIf { !it.isRecycled && it.config == Bitmap.Config.ARGB_8888 }
}

internal suspend fun loadProgressiveBlurCover(
    context: Context,
    resolvedUrl: Any?,
    cacheKey: String = progressiveBlurCoverCacheKey(resolvedUrl),
): Bitmap? {
    peekProgressiveBlurCover(context, cacheKey)?.let { return it }
    return withContext(Dispatchers.IO) {
        peekProgressiveBlurCover(context, cacheKey)?.let { return@withContext it }
        runCatching {
            val decoded = context.imageLoader.execute(
                buildProgressiveCoverImageRequest(context, resolvedUrl, cacheKey),
            ).drawable?.toBitmap() ?: return@runCatching null
            val uploadable = if (decoded.config == Bitmap.Config.ARGB_8888) {
                decoded
            } else {
                decoded.copy(Bitmap.Config.ARGB_8888, false)
            } ?: return@runCatching null
            context.imageLoader.memoryCache?.set(
                MemoryCache.Key(cacheKey),
                MemoryCache.Value(uploadable),
            )
            uploadable
        }.getOrNull()
    }
}

internal suspend fun warmUpProgressiveBlurCover(context: Context, data: Any?) {
    loadProgressiveBlurCover(context, hiresCoverUrl(data))
}

private data class ProgressiveLiveShaderValues(
    val originX: Float,
    val blurProg: Float,
    val crossfadeProg: Float,
    val videoProg: Float,
)

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
    renderingActive: Boolean = true,
    coverOriginXProvider: (() -> Float)? = null,
    pageBlurProgressProvider: (() -> Float)? = null,
    artworkTransition: TrackArtworkTransition? = null,
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
    val latestRenderingActive by rememberUpdatedState(renderingActive)

    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    val eglThreadRef = remember { AtomicReference<ProgressiveVideoEglThread?>(null) }
    val lastSyncedSize = remember { IntArray(2) }
    val disposed = remember { AtomicBoolean(false) }

    val transitionLayers = artworkTransition?.layers
    val targetArtworkUrl = if (transitionLayers != null && transitionLayers.isNotEmpty()) {
        transitionLayers.last()
    } else {
        fallbackArtworkUrl
    }
    val resolvedCoverUrl = hiresCoverUrl(targetArtworkUrl)
    val targetCoverKey = progressiveBlurCoverCacheKey(resolvedCoverUrl)

    val cachedHit = remember(targetCoverKey) { peekProgressiveBlurCover(context, targetCoverKey) }
    var asyncLoaded by remember { mutableStateOf<Pair<String, Bitmap?>?>(null) }
    LaunchedEffect(targetCoverKey) {
        if (cachedHit != null) {
            asyncLoaded = targetCoverKey to cachedHit
            return@LaunchedEffect
        }
        val loaded = loadProgressiveBlurCover(context, resolvedCoverUrl, targetCoverKey)
        asyncLoaded = targetCoverKey to loaded
    }
    val readyEntry: Pair<String, Bitmap?>? = when {
        cachedHit != null -> targetCoverKey to cachedHit
        asyncLoaded?.first == targetCoverKey -> asyncLoaded
        else -> null
    }

    var committedKey by remember { mutableStateOf<String?>(null) }
    var coverBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var coverGeneration by remember { mutableLongStateOf(0L) }
    var crossfadeEpoch by remember { mutableLongStateOf(0L) }
    val localCrossfadeAnim = remember { Animatable(1f) }
    var useLocalCrossfade by remember { mutableStateOf(false) }
    val latestArtworkTransition by rememberUpdatedState(artworkTransition)

    LaunchedEffect(readyEntry?.first, readyEntry?.second) {
        val entry = readyEntry ?: return@LaunchedEffect
        val (nextKey, nextBitmap) = entry
        if (nextKey == committedKey && nextBitmap === coverBitmap) {
            return@LaunchedEffect
        }
        val hadPreviousCover = committedKey != null
        val keyChanged = nextKey != committedKey
        committedKey = nextKey
        coverBitmap = nextBitmap
        coverGeneration += 1L
        if (!hadPreviousCover || !keyChanged) {
            useLocalCrossfade = false
            localCrossfadeAnim.snapTo(1f)
            return@LaunchedEffect
        }
        crossfadeEpoch += 1L
        val shared = latestArtworkTransition
        val canFollowShared = shared != null &&
            shared.layers.size >= 2 &&
            shared.progress.value < 0.25f
        if (canFollowShared) {
            useLocalCrossfade = false
            localCrossfadeAnim.snapTo(1f)
        } else {
            localCrossfadeAnim.snapTo(0f)
            useLocalCrossfade = true
            try {
                localCrossfadeAnim.animateTo(1f, TrackArtworkCrossfadeSpec)
            } finally {
                useLocalCrossfade = false
            }
        }
    }

    val needsVideoPlayer = videoUri != null && !forceStaticCover
    var exoPlayer by remember(context) { mutableStateOf<ExoPlayer?>(null) }

    val usingVideo =
        videoUri != null && !forceStaticCover && firstFrameReady && videoWidth > 0 && videoHeight > 0

    val videoFadeAnim = remember { Animatable(if (usingVideo) 1f else 0f) }
    LaunchedEffect(usingVideo, videoUri) {
        if (usingVideo) {
            videoFadeAnim.snapTo(0f)
            videoFadeAnim.animateTo(
                targetValue = 1f,
                animationSpec = tween(
                    durationMillis = PROGRESSIVE_VIDEO_FADE_MS,
                    easing = FastOutSlowInEasing,
                ),
            )
        } else {
            videoFadeAnim.snapTo(0f)
        }
    }

    val baseRenderState = remember(
        config,
        usingVideo,
        videoWidth,
        videoHeight,
        coverBitmap,
        coverGeneration,
        crossfadeEpoch,
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
            crossfadeEpoch = crossfadeEpoch,
        )
    }
    val latestBaseRenderState by rememberUpdatedState(baseRenderState)
    val latestOriginProvider by rememberUpdatedState(coverOriginXProvider)
    val latestPageBlurProvider by rememberUpdatedState(pageBlurProgressProvider)

    fun resolveCrossfadeProgress(): Float {
        val shared = latestArtworkTransition
        return when {
            useLocalCrossfade -> localCrossfadeAnim.value
            shared != null && shared.layers.size >= 2 -> shared.progress.value
            else -> 1f
        }
    }

    fun resolveLiveRenderState(base: ProgressiveRenderState = latestBaseRenderState): ProgressiveRenderState {
        val originX = latestOriginProvider?.invoke() ?: base.coverOriginX
        val blurProg = latestPageBlurProvider?.invoke() ?: base.pageBlurProgress
        val crossfadeProg = resolveCrossfadeProgress()
        val videoProg = videoFadeAnim.value
        return if (
            originX == base.coverOriginX &&
            blurProg == base.pageBlurProgress &&
            crossfadeProg == base.crossfadeProgress &&
            videoProg == base.videoFadeProgress
        ) {
            base
        } else {
            base.copy(
                coverOriginX = originX,
                pageBlurProgress = blurProg,
                crossfadeProgress = crossfadeProg,
                videoFadeProgress = videoProg,
            )
        }
    }

    LaunchedEffect(baseRenderState) {
        eglThreadRef.get()?.updateState(resolveLiveRenderState(baseRenderState))
    }

    LaunchedEffect(Unit) {
        snapshotFlow {
            val base = latestBaseRenderState
            val originX = latestOriginProvider?.invoke() ?: base.coverOriginX
            val blurProg = latestPageBlurProvider?.invoke() ?: base.pageBlurProgress
            val crossfadeProg = resolveCrossfadeProgress()
            val videoProg = videoFadeAnim.value
            ProgressiveLiveShaderValues(
                originX = originX,
                blurProg = blurProg,
                crossfadeProg = crossfadeProg,
                videoProg = videoProg,
            )
        }.collect { (originX, blurProg, crossfadeProg, videoProg) ->
            val base = latestBaseRenderState
            val updated = if (
                originX == base.coverOriginX &&
                blurProg == base.pageBlurProgress &&
                crossfadeProg == base.crossfadeProgress &&
                videoProg == base.videoFadeProgress
            ) {
                base
            } else {
                base.copy(
                    coverOriginX = originX,
                    pageBlurProgress = blurProg,
                    crossfadeProgress = crossfadeProg,
                    videoFadeProgress = videoProg,
                )
            }
            eglThreadRef.get()?.updateState(updated)
        }
    }

    LaunchedEffect(config.orientationMode, config.coverSize) {
        eglThreadRef.get()?.updateState(resolveLiveRenderState())
    }

    LaunchedEffect(usingVideo, videoWidth, videoHeight, coverBitmap) {
        val sourceW = if (usingVideo) videoWidth else (coverBitmap?.width ?: 0)
        val sourceH = if (usingVideo) videoHeight else (coverBitmap?.height ?: 0)
        latestOnSourceInfoChanged(usingVideo, sourceW, sourceH)
    }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            boundVideoSurface = null
            eglThreadRef.getAndSet(null)?.let { thread ->
                thread.stopAndJoin()
                thread.releaseOutputSurface()
            }
            mainHandler.removeCallbacksAndMessages(null)
        }
    }

    DisposableEffect(needsVideoPlayer, context) {
        if (!needsVideoPlayer) {
            return@DisposableEffect onDispose {}
        }
        val player = ExoPlayer.Builder(context).build().apply {
            volume = 0f
            repeatMode = Player.REPEAT_MODE_ONE
            setAudioAttributes(AudioAttributes.DEFAULT, /* handleAudioFocus = */ false)
        }
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
        player.addListener(listener)
        if (lifecycleStarted) {
            boundVideoSurface?.let { player.setVideoSurface(it) }
        }
        exoPlayer = player
        onDispose {
            if (exoPlayer === player) {
                exoPlayer = null
            }
            firstFrameReady = false
            videoWidth = 0
            videoHeight = 0
            player.clearVideoSurface(boundVideoSurface)
            player.removeListener(listener)
            player.release()
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    lifecycleStarted = true
                    boundVideoSurface?.let { surface -> exoPlayer?.setVideoSurface(surface) }
                }
                Lifecycle.Event.ON_RESUME -> {
                    eglThreadRef.get()?.setRenderingEnabled(latestRenderingActive)
                }
                Lifecycle.Event.ON_PAUSE -> {
                    eglThreadRef.get()?.setRenderingEnabled(false)
                }
                Lifecycle.Event.ON_STOP -> {
                    lifecycleStarted = false
                    firstFrameReady = false
                    exoPlayer?.pause()
                    exoPlayer?.clearVideoSurface(boundVideoSurface)
                }
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(renderingActive) {
        val enabled = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && renderingActive
        eglThreadRef.get()?.setRenderingEnabled(enabled)
    }

    LaunchedEffect(videoUri, forceStaticCover, exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        firstFrameReady = false
        videoWidth = 0
        videoHeight = 0
        if (videoUri != null && !forceStaticCover) {
            player.setMediaItem(MediaItem.fromUri(videoUri))
            player.prepare()
        } else {
            player.stop()
            player.clearMediaItems()
        }
    }

    val shouldPlay = videoUri != null && !forceStaticCover && isPlaying && lifecycleStarted
    LaunchedEffect(shouldPlay, exoPlayer) {
        val player = exoPlayer ?: return@LaunchedEffect
        player.playWhenReady = shouldPlay
        if (shouldPlay) {
            player.play()
        } else {
            player.pause()
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
                        renderState = resolveLiveRenderState(),
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
                            initialState = resolveLiveRenderState(),
                            onVideoSurfaceReady = { videoSurface ->
                                mainHandler.post {
                                    if (!disposed.get() && eglThreadRef.get() === createdThread) {
                                        boundVideoSurface = videoSurface
                                        if (lifecycleStarted) {
                                            exoPlayer?.setVideoSurface(videoSurface)
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
                            exoPlayer?.clearVideoSurface(prevSurface)
                            old.stopAndJoin()
                            old.releaseOutputSurface()
                        }
                        createdThread.setRenderingEnabled(
                            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                                latestRenderingActive,
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
                            renderState = resolveLiveRenderState(),
                            force = true,
                        )
                    }

                    override fun onSurfaceTextureDestroyed(
                        surfaceTexture: SurfaceTexture,
                    ): Boolean {
                        val videoSurface = boundVideoSurface
                        boundVideoSurface = null
                        exoPlayer?.clearVideoSurface(videoSurface)
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
                renderState = resolveLiveRenderState(),
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
