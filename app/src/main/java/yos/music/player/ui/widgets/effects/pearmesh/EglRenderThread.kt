/*
 * Adapted from Pear Wall (https://github.com/Nevodev/Pear-Wall) v1.5,
 * Copyright Nevodev, licensed under the Apache License 2.0.
 * See assets/licenses/pear-wall-NOTICE.txt for the porting changes.
 *
 * Sonify adaptation: the frame-capture hook and the AOD diagnostics logging are
 * removed. The frame clock, the pause/idle parking and the animation-time freeze
 * are unchanged from upstream.
 */

package yos.music.player.ui.widgets.effects.pearmesh

import android.content.Context
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * Dedicated EGL render thread with its own frame clock.
 *
 * It deliberately does *not* ride Compose's `withFrameNanos`: the background is a slow
 * 30fps drift, and hooking it to the composition clock would drag the main thread up to
 * the display refresh rate for no visual gain.
 */
internal class EglRenderThread(
    private val context: Context,
    private val surface: Surface,
    initialWidth: Int,
    initialHeight: Int,
    private val stateProvider: () -> RendererState,
    initiallyRenderingEnabled: Boolean = true,
) {
    private val running = AtomicBoolean(false)
    private val renderingEnabled = AtomicBoolean(initiallyRenderingEnabled)
    private val idle = AtomicBoolean(false)
    private val size = AtomicLong(packSize(initialWidth, initialHeight))
    private val renderSessionGeneration = AtomicLong(0L)
    private val invalidationGen = AtomicLong(0L)
    private val renderingLock = ReentrantLock()
    private val renderingCondition = renderingLock.newCondition()
    private var thread: Thread? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        thread = Thread(::run, "PearMesh-EGL").also { it.start() }
    }

    fun resize(width: Int, height: Int) {
        size.set(packSize(width, height))
        wakeFromIdle()
    }

    fun setRenderingEnabled(enabled: Boolean) {
        if (renderingEnabled.getAndSet(enabled) == enabled) return
        if (enabled) {
            invalidationGen.incrementAndGet()
            renderingLock.withLock {
                if (idle.getAndSet(false)) {
                    renderSessionGeneration.incrementAndGet()
                }
                renderingCondition.signalAll()
            }
        } else {
            wakeRenderer()
        }
    }

    fun wakeFromIdle() {
        invalidationGen.incrementAndGet()
        if (!renderingEnabled.get()) return
        var shouldWake = false
        renderingLock.withLock {
            if (idle.getAndSet(false)) {
                renderSessionGeneration.incrementAndGet()
                renderingCondition.signalAll()
                shouldWake = true
            }
        }
        if (shouldWake) {
            thread?.interrupt()
        }
    }

    private fun wakeRenderer() {
        renderingLock.withLock {
            renderingCondition.signalAll()
        }
        thread?.interrupt()
    }

    fun stopAndJoin() {
        running.set(false)
        renderingLock.withLock {
            renderingCondition.signalAll()
        }
        val activeThread = thread ?: return
        activeThread.interrupt()
        if (Thread.currentThread() !== activeThread) {
            runCatching { activeThread.join(1_000L) }
        }
        thread = null
    }

    /** Frees the Java [Surface] the caller handed in; call after [stopAndJoin]. */
    fun releaseSurface() {
        runCatching { surface.release() }
    }

    private fun run() {
        var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
        var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
        var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
        var renderer: PearMeshGlRenderer? = null

        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display !== EGL14.EGL_NO_DISPLAY) { "eglGetDisplay failed" }
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 1)) {
                "eglInitialize failed: 0x${EGL14.eglGetError().toString(16)}"
            }

            val config = chooseConfig(display)
            eglContext = EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE),
                0,
            )
            check(eglContext !== EGL14.EGL_NO_CONTEXT) {
                "eglCreateContext failed: 0x${EGL14.eglGetError().toString(16)}"
            }

            eglSurface = EGL14.eglCreateWindowSurface(
                display,
                config,
                surface,
                intArrayOf(EGL14.EGL_NONE),
                0,
            )
            check(eglSurface !== EGL14.EGL_NO_SURFACE) {
                "eglCreateWindowSurface failed: 0x${EGL14.eglGetError().toString(16)}"
            }
            check(
                EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)
            ) { "eglMakeCurrent failed: 0x${EGL14.eglGetError().toString(16)}" }
            EGL14.eglSwapInterval(display, 1)

            renderer = PearMeshGlRenderer(context, stateProvider())
            var nextFrame = System.nanoTime()
            var previousFrameStart = nextFrame
            var animationNanos = 0L
            var observedSessionGeneration = renderSessionGeneration.get()
            var frameErrorLogged = false
            while (running.get()) {
                val resumedAfterPause = awaitRenderingEnabled()
                if (!running.get()) break

                val genBeforeFrame = invalidationGen.get()
                val frameStart = System.nanoTime()
                val state = stateProvider()
                val sessionGeneration = renderSessionGeneration.get()
                if (resumedAfterPause || sessionGeneration != observedSessionGeneration) {
                    previousFrameStart = frameStart
                    nextFrame = frameStart
                    observedSessionGeneration = sessionGeneration
                } else {
                    // Pausing playback freezes the animation clock, so the flow stops on
                    // the current frame instead of snapping back to the start.
                    if (state.isPlaybackPlaying || !state.pauseFlowEnabled) {
                        animationNanos += (frameStart - previousFrameStart).coerceAtLeast(0L)
                    }
                    previousFrameStart = frameStart
                }
                val packedSize = size.get()
                val width = unpackWidth(packedSize)
                val height = unpackHeight(packedSize)
                if (width > 0 && height > 0) {
                    try {
                        renderer.render(
                            width = width,
                            height = height,
                            state = state,
                            time = animationNanos / 1_000_000_000.0,
                        )
                    } catch (frameError: Throwable) {
                        // A single bad frame (e.g. an un-uploadable bitmap slipping in)
                        // must not kill the whole chain and leave the surface blank.
                        if (!frameErrorLogged) {
                            frameErrorLogged = true
                            android.util.Log.e(TAG, "PearMesh frame failed", frameError)
                        }
                    }
                    if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                        val error = EGL14.eglGetError()
                        if (error == EGL14.EGL_BAD_SURFACE || error == EGL14.EGL_BAD_NATIVE_WINDOW) {
                            break
                        }
                        error("eglSwapBuffers failed: 0x${error.toString(16)}")
                    }
                }

                // 暂停且无进行中的切歌渐变或状态失效时进入 idle 等待，消除后台 GL/CPU 空转。
                if (!state.isPlaybackPlaying &&
                    state.pauseFlowEnabled &&
                    !renderer.hasPendingWork() &&
                    invalidationGen.get() == genBeforeFrame
                ) {
                    val enteredIdle = renderingLock.withLock {
                        if (invalidationGen.get() == genBeforeFrame && renderingEnabled.get()) {
                            idle.set(true)
                            true
                        } else {
                            false
                        }
                    }
                    if (enteredIdle) {
                        continue
                    }
                }

                val frameNanos = 1_000_000_000L / state.targetFrameRate
                nextFrame += frameNanos
                val remaining = nextFrame - System.nanoTime()
                if (remaining > 0L) {
                    try {
                        Thread.sleep(remaining / 1_000_000L, (remaining % 1_000_000L).toInt())
                    } catch (_: InterruptedException) {
                        // Re-check running before the next frame.
                    }
                } else if (remaining < -frameNanos * 2L) {
                    nextFrame = System.nanoTime()
                }
            }
        } catch (throwable: Throwable) {
            // Do not swallow this. A shader link error or an EGL failure leaves the
            // surface blank, and a blank background is indistinguishable from "the
            // feature is off" — log it so the cause is visible in logcat.
            if (running.get()) {
                android.util.Log.e(TAG, "PearMesh renderer stopped", throwable)
            }
        } finally {
            renderer?.close()
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

    private fun awaitRenderingEnabled(): Boolean {
        var waited = false
        renderingLock.withLock {
            while (running.get() && (!renderingEnabled.get() || idle.get())) {
                waited = true
                try {
                    renderingCondition.await()
                } catch (_: InterruptedException) {
                    // Re-check both flags after lifecycle or shutdown changes.
                }
            }
        }
        return waited
    }

    private fun chooseConfig(display: EGLDisplay): EGLConfig {
        val attributes = intArrayOf(
            EGL14.EGL_RED_SIZE, 8,
            EGL14.EGL_GREEN_SIZE, 8,
            EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
            EGL14.EGL_NONE,
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)) {
            "eglChooseConfig failed: 0x${EGL14.eglGetError().toString(16)}"
        }
        check(count[0] > 0) { "No GLES 3 window EGLConfig is available" }
        return checkNotNull(configs[0])
    }

    private companion object {
        const val TAG = "PearMesh"

        fun packSize(width: Int, height: Int): Long =
            (width.toLong() shl 32) or (height.toLong() and 0xffffffffL)

        fun unpackWidth(size: Long): Int = (size shr 32).toInt()
        fun unpackHeight(size: Long): Int = size.toInt()
    }
}
