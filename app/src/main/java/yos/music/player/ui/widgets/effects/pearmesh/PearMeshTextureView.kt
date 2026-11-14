package yos.music.player.ui.widgets.effects.pearmesh

import android.app.ActivityManager
import android.content.Context
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicReference

/**
 * Whether this device can run the PearMesh renderer at all.
 *
 * PearMesh needs OpenGL ES 3.0 (`EgL_CONTEXT_CLIENT_VERSION = 3`). The manifest declares
 * the feature as *not* required so GLES 2.0 devices are still installable; they fall back
 * to the classic drifting-cover background at runtime.
 */
internal fun isPearMeshSupported(context: Context): Boolean {
    val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        ?: return false
    return activityManager.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
}

/**
 * GLES3 texture-backed host for [EglRenderThread].
 *
 * A TextureView (not a SurfaceView) is essential here: Sonify's liquid-glass panels sample
 * the player backdrop through `Modifier.layerBackdrop`, which records the Compose display
 * list. A SurfaceView is promoted to its own SurfaceFlinger layer and would be invisible to
 * that recording, so every glass surface on the playback page would degrade to a flat panel.
 * TextureView draws its content inside the normal View draw pass, so it stays sampleable.
 */
@Composable
internal fun PearMeshBackground(
    state: PearMeshState,
    modifier: Modifier = Modifier,
    onFirstFrameRendered: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val latestState = rememberUpdatedState(state)
    val latestFirstFrame = rememberUpdatedState(onFirstFrameRendered)
    val lifecycleOwner = LocalLifecycleOwner.current
    val renderThread = remember { AtomicReference<EglRenderThread?>(null) }

    val lifecycleState = lifecycleOwner.lifecycle.currentState
    val resumed = lifecycleState.isAtLeast(Lifecycle.State.RESUMED)

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, _ ->
            val enabled = lifecycleOwner.lifecycle.currentState
                .isAtLeast(Lifecycle.State.RESUMED)
            renderThread.get()?.setRenderingEnabled(enabled)
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            renderThread.get()?.setRenderingEnabled(false)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { viewContext ->
            TextureView(viewContext).apply {
                isOpaque = false
                var firstFrameNotified = false
                surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                    override fun onSurfaceTextureAvailable(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        firstFrameNotified = false
                        val surface = Surface(surfaceTexture)
                        val thread = EglRenderThread(
                            context = viewContext.applicationContext,
                            surface = surface,
                            initialWidth = width,
                            initialHeight = height,
                            stateProvider = { latestState.value.snapshot() },
                            initiallyRenderingEnabled = lifecycleOwner.lifecycle.currentState
                                .isAtLeast(Lifecycle.State.RESUMED),
                        )
                        renderThread.getAndSet(thread)?.stopAndJoin()
                        thread.start()
                    }

                    override fun onSurfaceTextureSizeChanged(
                        surfaceTexture: SurfaceTexture,
                        width: Int,
                        height: Int,
                    ) {
                        renderThread.get()?.resize(width, height)
                    }

                    override fun onSurfaceTextureDestroyed(
                        surfaceTexture: SurfaceTexture,
                    ): Boolean {
                        renderThread.getAndSet(null)?.let { thread ->
                            thread.stopAndJoin()
                            thread.releaseSurface()
                        }
                        // We released the Surface inside the block above; the TextureView
                        // is then free to reclaim the SurfaceTexture.
                        return true
                    }

                    override fun onSurfaceTextureUpdated(surfaceTexture: SurfaceTexture) {
                        if (!firstFrameNotified) {
                            firstFrameNotified = true
                            latestFirstFrame.value?.invoke()
                        }
                    }
                }
            }
        },
    )

    // Re-arm the render thread when the host comes back to the foreground.
    LaunchedEffect(resumed) {
        renderThread.get()?.setRenderingEnabled(resumed)
    }
}
