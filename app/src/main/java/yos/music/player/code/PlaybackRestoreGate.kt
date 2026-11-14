package yos.music.player.code

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 进程冷启动后「上次播放队列恢复」完成闸门。
 *
 * [yos.music.player.YosBasicApplication.onCreate] 会在后台连接 MediaSession 并以 `play = false`
 * 恢复队列与上次进度。桌面小组件在冷进程下收到点击广播时，若不等恢复完成就直接调用
 * `fadePlay()` / `prepare()`，会与恢复协程的 `setMediaItems()` 竞争导致「先播又跳回」。
 */
object PlaybackRestoreGate {
    private val readySignal = CompletableDeferred<Unit>()

    val isReady: Boolean
        get() = readySignal.isCompleted

    fun markReady() {
        readySignal.complete(Unit)
    }

    suspend fun awaitReady(timeoutMs: Long = 8_000L): Boolean {
        if (readySignal.isCompleted) return true
        return withTimeoutOrNull(timeoutMs) {
            readySignal.await()
        } != null
    }
}
