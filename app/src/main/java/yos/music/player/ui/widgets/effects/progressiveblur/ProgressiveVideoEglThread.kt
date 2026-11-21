package yos.music.player.ui.widgets.effects.progressiveblur

import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dedicated event-driven EGL render thread for [ProgressiveVideoGlRenderer].
 *
 * Unlike a fixed-FPS loop, this thread stays parked at 0% GPU/CPU when nothing changes and wakes
 * only when a decoded video frame arrives on [SurfaceTexture], viewport size changes, or UI config
 * sliders update.
 */
internal class ProgressiveVideoEglThread(
    private val outputSurface: Surface,
    initialWidth: Int,
    initialHeight: Int,
    initialState: ProgressiveRenderState,
    private val onVideoSurfaceReady: (Surface) -> Unit,
    private val onFirstVideoFrameLatched: () -> Unit,
) {
    private val running = AtomicBoolean(false)
    private val renderingEnabled = AtomicBoolean(true)
    private val renderRequested = AtomicBoolean(true)
    private val videoFrameAvailable = AtomicBoolean(false)
    private val size = AtomicLong(packSize(initialWidth, initialHeight))
    private val resizeGeneration = AtomicLong(0L)
    private val stateRef = AtomicReference(initialState)

    private val lock = ReentrantLock()
    private val condition = lock.newCondition()
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::run, "ProgressiveBlur-EGL").also { it.start() }
    }

    fun updateState(state: ProgressiveRenderState) {
        stateRef.set(state)
        requestRender()
    }

    fun resize(width: Int, height: Int) {
        size.set(packSize(width, height))
        resizeGeneration.incrementAndGet()
        requestRender()
    }

    fun resizeAndUpdateState(width: Int, height: Int, state: ProgressiveRenderState) {
        stateRef.set(state)
        size.set(packSize(width, height))
        resizeGeneration.incrementAndGet()
        requestRender()
    }

    fun setRenderingEnabled(enabled: Boolean) {
        if (renderingEnabled.getAndSet(enabled) == enabled) return
        if (enabled) {
            requestRender()
        }
    }

    fun requestRender() {
        renderRequested.set(true)
        lock.withLock {
            condition.signalAll()
        }
    }

    fun stopAndJoin() {
        running.set(false)
        lock.withLock {
            condition.signalAll()
        }
        val active = thread ?: return
        active.interrupt()
        if (Thread.currentThread() !== active) {
            runCatching { active.join(1_000L) }
        }
        thread = null
    }

    /** Releases the caller-provided [TextureView] output [Surface]; invoke after [stopAndJoin]. */
    fun releaseOutputSurface() {
        runCatching { outputSurface.release() }
    }

    private fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var renderer: ProgressiveVideoGlRenderer? = null
        var videoSurfaceTexture: SurfaceTexture? = null
        var videoSurface: Surface? = null
        var frameCallbackThread: HandlerThread? = null

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) {
                "eglInitialize failed: 0x${EGL14.eglGetError().toString(16)}"
            }

            val config = chooseConfig(display)
            eglContext = createContextWithFallback(display, config)
            eglSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                outputSurface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(eglSurface !== EGL14.EGL_NO_SURFACE) {
                "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}"
            }
            check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
                "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}"
            }
            EGL14.eglSwapInterval(display, 1)

            val glRenderer = ProgressiveVideoGlRenderer()
            renderer = glRenderer

            val cbThread = HandlerThread("ProgressiveBlur-FrameCb").apply { start() }
            frameCallbackThread = cbThread
            val cbHandler = Handler(cbThread.looper)

            val surfaceTexture = SurfaceTexture(glRenderer.oesTextureId).apply {
                setOnFrameAvailableListener(
                    {
                        videoFrameAvailable.set(true)
                        requestRender()
                    },
                    cbHandler,
                )
            }
            videoSurfaceTexture = surfaceTexture
            val inputSurface = Surface(surfaceTexture)
            videoSurface = inputSurface

            if (running.get()) {
                onVideoSurfaceReady(inputSurface)
            }

            val texMatrix = FloatArray(16).apply {
                android.opengl.Matrix.setIdentityM(this, 0)
            }
            var hasLatchedVideoFrame = false
            var firstLatchNotified = false
            var frameErrorLogged = false
            val eglDim = IntArray(2)
            var observedResizeGen = resizeGeneration.get()
            var resizeSettleRemaining = 0
            var surfaceRecreatedForGen = -1L
            var unexpectedMismatchRetries = 0

            while (running.get()) {
                awaitNextTrigger()
                if (!running.get()) break

                renderRequested.set(false)
                var newVideoFrameThisPass = false
                if (videoFrameAvailable.compareAndSet(true, false)) {
                    runCatching {
                        surfaceTexture.updateTexImage()
                        surfaceTexture.getTransformMatrix(texMatrix)
                        hasLatchedVideoFrame = true
                        newVideoFrameThisPass = true
                        if (!firstLatchNotified) {
                            firstLatchNotified = true
                            onFirstVideoFrameLatched()
                        }
                    }.onFailure { err ->
                        Log.w(TAG, "updateTexImage failed: ${err.message}")
                    }
                }

                val packedSize = size.get()
                val width = unpackWidth(packedSize)
                val height = unpackHeight(packedSize)
                if (width <= 0 || height <= 0) continue

                val curResizeGen = resizeGeneration.get()
                if (curResizeGen != observedResizeGen) {
                    observedResizeGen = curResizeGen
                    resizeSettleRemaining = 2
                    unexpectedMismatchRetries = 0
                }

                querySurfaceSize(display, eglSurface, eglDim)
                val preW = eglDim[0]
                val preH = eglDim[1]

                val state = stateRef.get()
                try {
                    glRenderer.render(
                        width = width,
                        height = height,
                        state = state,
                        texMatrix = texMatrix,
                        hasVideoFrame = hasLatchedVideoFrame,
                        newVideoFrame = newVideoFrameThisPass,
                    )
                } catch (frameErr: Throwable) {
                    if (!frameErrorLogged) {
                        frameErrorLogged = true
                        Log.e(TAG, "ProgressiveBlur render frame failed", frameErr)
                    }
                    continue
                }

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    val err = EGL14.eglGetError()
                    if (err == EGL14.EGL_BAD_SURFACE || err == EGL14.EGL_BAD_NATIVE_WINDOW) {
                        break
                    }
                    Log.w(TAG, "eglSwapBuffers error: 0x${err.toString(16)}")
                }

                querySurfaceSize(display, eglSurface, eglDim)
                val postW = eglDim[0]
                val postH = eglDim[1]

                if (resizeSettleRemaining > 0) {
                    if (resizeSettleRemaining == 2 || postW != width || postH != height) {
                        Log.d(
                            TAG,
                            "resizeSettle gen=$observedResizeGen rem=$resizeSettleRemaining target=${width}x$height pre=${preW}x$preH post=${postW}x$postH",
                        )
                    }
                    resizeSettleRemaining--
                    if (resizeSettleRemaining > 0) {
                        renderRequested.set(true)
                    } else if (
                        postW > 0 && postH > 0 &&
                        (postW != width || postH != height) &&
                        surfaceRecreatedForGen != observedResizeGen
                    ) {
                        eglSurface = recreateWindowSurface(display, config, eglSurface, eglContext)
                        if (eglSurface === EGL14.EGL_NO_SURFACE) break
                        surfaceRecreatedForGen = observedResizeGen
                        renderRequested.set(true)
                    }
                } else if (preW > 0 && preH > 0 && (preW != width || preH != height) && unexpectedMismatchRetries < 2) {
                    unexpectedMismatchRetries++
                    renderRequested.set(true)
                } else if (preW == width && preH == height) {
                    unexpectedMismatchRetries = 0
                }
            }
        } catch (t: Throwable) {
            if (running.get()) {
                Log.e(TAG, "ProgressiveVideoEglThread stopped with error", t)
            }
        } finally {
            runCatching { videoSurfaceTexture?.setOnFrameAvailableListener(null) }
            runCatching { videoSurface?.release() }
            runCatching { videoSurfaceTexture?.release() }
            runCatching { frameCallbackThread?.quitSafely() }
            runCatching { renderer?.close() }
            if (display !== EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                    display,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT,
                )
                if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                    EGL14.eglDestroySurface(display, eglSurface)
                }
                if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, eglContext)
                }
                EGL14.eglTerminate(display)
            }
            running.set(false)
        }
    }

    private fun awaitNextTrigger() {
        lock.withLock {
            while (
                running.get() &&
                (!renderingEnabled.get() || (!renderRequested.get() && !videoFrameAvailable.get()))
            ) {
                try {
                    condition.await()
                } catch (_: InterruptedException) {
                    // Re-check loop condition.
                }
            }
        }
    }

    private fun querySurfaceSize(display: EGLDisplay, surface: EGLSurface, out: IntArray) {
        if (surface === EGL14.EGL_NO_SURFACE) {
            out[0] = 0
            out[1] = 0
            return
        }
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_WIDTH, out, 0)
        EGL14.eglQuerySurface(display, surface, EGL14.EGL_HEIGHT, out, 1)
    }

    private fun recreateWindowSurface(
        display: EGLDisplay,
        config: EGLConfig,
        oldSurface: EGLSurface,
        context: EGLContext,
    ): EGLSurface {
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        if (oldSurface !== EGL14.EGL_NO_SURFACE) {
            EGL14.eglDestroySurface(display, oldSurface)
        }
        if (!outputSurface.isValid) {
            return EGL14.EGL_NO_SURFACE
        }
        val newSurface = EGL14.eglCreateWindowSurface(
            display,
            config,
            outputSurface,
            intArrayOf(EGL14.EGL_NONE),
            0,
        )
        if (newSurface === EGL14.EGL_NO_SURFACE) {
            Log.w(TAG, "recreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}")
            return EGL14.EGL_NO_SURFACE
        }
        if (!EGL14.eglMakeCurrent(display, newSurface, newSurface, context)) {
            Log.w(TAG, "recreateWindowSurface makeCurrent failed: 0x${EGL14.eglGetError().toString(16)}")
            EGL14.eglDestroySurface(display, newSurface)
            return EGL14.EGL_NO_SURFACE
        }
        EGL14.eglSwapInterval(display, 1)
        return newSurface
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            "eglChooseConfig failed: 0x${EGL14.eglGetError().toString(16)}"
        }
        check(count[0] > 0) { "No ES2-compatible RGBA8 EGLConfig available" }
        return checkNotNull(configs[0])
    }

    private fun createContextWithFallback(display: EGLDisplay, config: EGLConfig): EGLContext {
        val es3 = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
            0,
        )
        if (es3 !== EGL14.EGL_NO_CONTEXT) return es3

        val es2 = EGL14.eglCreateContext(
            display,
            config,
            EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
            0,
        )
        check(es2 !== EGL14.EGL_NO_CONTEXT) {
            "eglCreateContext failed: 0x${EGL14.eglGetError().toString(16)}"
        }
        return es2
    }

    private companion object {
        const val TAG = "ProgressiveBlurEGL"

        fun packSize(width: Int, height: Int): Long =
            (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)

        fun unpackWidth(size: Long): Int = (size shr 32).toInt()
        fun unpackHeight(size: Long): Int = size.toInt()
    }
}
