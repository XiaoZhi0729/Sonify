package yos.music.player.code.utils.others

import android.os.SystemClock
import android.util.Log

/** Low-volume diagnostics for glass branch and backdrop lifecycle investigation. */
object GlassDiagnostics {
    const val TAG = "SonifyGlassDiag"
    private const val TickIntervalMs = 500L

    private val lastValues = HashMap<String, String>()
    private val ticks = HashMap<String, TickState>()

    private data class TickState(
        var count: Long = 0L,
        var emittedAt: Long = Long.MIN_VALUE,
        var emittedCount: Long = 0L
    )

    @Synchronized
    fun state(key: String, value: String) {
        if (lastValues[key] == value) return
        lastValues[key] = value
        Log.i(TAG, "$key=$value")
    }

    @Synchronized
    fun tick(key: String, details: String = "") {
        val now = SystemClock.uptimeMillis()
        val tick = ticks.getOrPut(key) { TickState() }
        tick.count++
        if (tick.emittedAt == Long.MIN_VALUE || now - tick.emittedAt >= TickIntervalMs) {
            val elapsed = if (tick.emittedAt == Long.MIN_VALUE) 0L else now - tick.emittedAt
            val delta = tick.count - tick.emittedCount
            tick.emittedAt = now
            tick.emittedCount = tick.count
            Log.i(
                TAG,
                "$key count=${tick.count} delta=$delta elapsedMs=$elapsed" +
                        if (details.isEmpty()) "" else " $details"
            )
        }
    }

    fun event(name: String, details: String = "") {
        Log.i(TAG, if (details.isEmpty()) name else "$name $details")
    }

    fun warning(name: String, details: String, error: Throwable? = null) {
        Log.w(TAG, "$name $details", error)
    }
}
