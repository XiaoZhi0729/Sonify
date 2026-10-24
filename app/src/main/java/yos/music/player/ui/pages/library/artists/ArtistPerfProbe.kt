package yos.music.player.ui.pages.library.artists

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.layout.layout
import yos.music.player.BuildConfig

/**
 * 歌手页卡顿定位用的临时探针（**仅 debug 生效**，release 里是 no-op 直通）。
 *
 * 背景：平板横屏歌手页滑动时 p90 200–250ms，帧阶段拆解显示时间落在
 * 「PerformTraversalsStart → DrawStart」（组合 + 测量 + 布局），但不知道是哪一块。
 * 这两个探针把每块的测量与绘制耗时直接打成日志，避免继续靠猜。
 *
 * LazyColumn 的 item 组合发生在测量阶段内，所以 [measureProbe] 的耗时**包含**该块
 * 首次组合的成本——正是要抓的尖峰来源。
 *
 * 定位完成后应整体删除（连同调用点的 probeName 参数）。
 */
internal fun Modifier.measureProbe(name: String): Modifier =
    if (name.isEmpty() || !BuildConfig.DEBUG) this
    else this.layout { measurable, constraints ->
        val started = System.nanoTime()
        val placeable = measurable.measure(constraints)
        val ms = (System.nanoTime() - started) / 1_000_000.0
        if (ms >= MeasureWarnMs) {
            android.util.Log.w("ArtistPerf", "$name measure=${"%.1f".format(ms)}ms")
        }
        layout(placeable.width, placeable.height) { placeable.place(0, 0) }
    }

/** 绘制耗时探针，阈值见 [DrawWarnMs]。 */
internal fun Modifier.drawProbe(name: String): Modifier =
    if (name.isEmpty() || !BuildConfig.DEBUG) this
    else this.drawWithContent {
        val started = System.nanoTime()
        drawContent()
        val ms = (System.nanoTime() - started) / 1_000_000.0
        if (ms >= DrawWarnMs) {
            android.util.Log.w("ArtistPerf", "$name draw=${"%.1f".format(ms)}ms")
        }
    }

/** 单块测量超过这个值就打日志（60Hz 一帧预算 16.7ms，一块吃掉 4ms 已算贵）。 */
private const val MeasureWarnMs = 4.0

/** 单块绘制超过这个值就打日志。 */
private const val DrawWarnMs = 3.0
