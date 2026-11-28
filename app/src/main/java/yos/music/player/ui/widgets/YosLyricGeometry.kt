package yos.music.player.ui.widgets

import kotlin.math.roundToInt

// 顶部遮罩与歌词库共用几何，当前行必须落在渐隐终点下方。
internal const val LYRIC_ACTIVE_ANCHOR_FRACTION = 0.12f
internal const val LYRIC_TOP_FADE_INSET_DP = 32f

/**
 * 顶部渐隐 inset 的有效值：不得超过锚点距离的一半。
 *
 * 手机横屏的歌词面板只有约 267dp 高，`0.12 × 267 ≈ 32dp` 与常量 inset 32dp 相减归零，
 * 渐隐带退化成 0 高度 = 硬边（底部渐隐走 Fraction，线性于视口高，所以短视口不会这样塌）。
 * 视口 ≥ 4 × inset（约 533dp，竖屏与平板横屏都满足）时上限不起作用，结果与旧公式逐像素一致。
 */
internal fun lyricTopFadeInsetPx(viewportHeightPx: Float, insetPx: Float): Float {
    val anchorPx = viewportHeightPx.coerceAtLeast(0f) * LYRIC_ACTIVE_ANCHOR_FRACTION
    return insetPx.coerceIn(0f, anchorPx * 0.5f)
}

internal fun lyricTopMaskEndPx(viewportHeightPx: Float, insetPx: Float): Float =
    (viewportHeightPx.coerceAtLeast(0f) * LYRIC_ACTIVE_ANCHOR_FRACTION -
        lyricTopFadeInsetPx(viewportHeightPx, insetPx))
        .coerceAtLeast(0f)

/**
 * 竖屏控件可见时，歌词可视区域占可用高度的比例。
 *
 * 与 NowPlaying 竖屏控件层的 fillMaxHeight(0.437f) 互补（1 - 0.437 ≈ 0.56）；
 * "无歌词"占位分支也复用同一数值，保证两处对"控件挡了多少"的理解一致。
 * 纯几何常量，独立成文件以便 JVM 单测（不依赖 SettingsLibrary / Android 环境）。
 */
internal const val LYRIC_AREA_FRACTION_CONTROLS_VISIBLE = 0.56f

/**
 * 末句固定落点使用的"底部遮挡"比例：恒取竖屏控件可见时的遮挡高度（1 - 0.56 ≈ 0.44）。
 *
 * 末句不做随控件显隐的上下移动，而是固定停在两种状态下都可见的高度——即按控件可见时
 * 被压缩后的可视区居中。竖屏调用方恒传此值；横屏歌词面板没有底部控件遮挡，传 0（默认），
 * 末句仍在整个面板居中。
 */
internal const val LAST_LINE_OBSTRUCTION_FRACTION: Float =
    1f - LYRIC_AREA_FRACTION_CONTROLS_VISIBLE

/**
 * 末句落点：末句 item 顶部相对视口顶部的像素偏移。
 *
 * 默认（无遮挡）落在歌词可视区的 Y 轴中线上，即 viewportHeight / 2 - 末句高 / 2。
 * 竖屏控件出现时会遮挡视口底部 [obstructionFraction] 的高度，这里改为在"未被遮挡的
 * 可视区"内居中，落点随之抬高，末句不会被控件盖住；控件隐藏时 obstruction 为 0，
 * 退化成与旧逻辑逐像素一致的结果。
 */
internal fun lastLineCenterScrollOffset(
    viewportHeight: Int,
    lastLineHeight: Int,
    obstructionFraction: Float
): Int {
    val visibleHeight = (viewportHeight * (1f - obstructionFraction)).roundToInt()
    return (visibleHeight / 2 - lastLineHeight / 2).coerceAtLeast(0)
}

/**
 * 末句尾部余量（px）：让末句有足够内容滚到 [lastLineCenterScrollOffset] 给出的落点。
 *
 * 落点越高、需要的尾部余量越大；遮挡出现时落点上移 viewportHeight * obstruction / 2，
 * 尾部余量同步追加相同量。obstruction 为 0 时与旧公式逐像素一致。
 */
internal fun lyricTrailingExtraPx(
    viewportHeight: Int,
    lastLineHeight: Int,
    blankHeightPx: Int,
    contentPaddingPx: Int,
    obstructionFraction: Float
): Int {
    if (viewportHeight <= 0) return 0
    val base = (viewportHeight - lastLineHeight) / 2 - blankHeightPx - contentPaddingPx
    val obstruction = (viewportHeight * obstructionFraction / 2f).roundToInt()
    return (base + obstruction).coerceAtLeast(0)
}

/**
 * 手动滑到歌词末尾时，末句底边在「未被底部控件遮挡的可视区」内停留的高度比例。
 *
 * - 竖屏（obstruction ≈ 0.44，visibleH ≈ 0.56H）：0.72 × 0.56H ≈ 0.403H，
 *   恰好位于控件可见时底部渐隐起点（7/17 ≈ 0.412H）上方，
 *   既保留最后约 3~4 句清晰可见，又避免控件显隐切换时发生跳动。
 * - 横屏（obstruction = 0，visibleH = H）：0.72H，位于底部渐隐起点（15/19 ≈ 0.789H）上方，
 *   平板与手机横屏均能保留末尾数句并留出适宜底部余白。
 */
internal const val LYRIC_END_RETAINED_VISIBLE_FRACTION = 0.72f

/**
 * 歌词列表底部留白（px）：用于传给 KaraokeLyricsView 的 `bottomContentPadding`，
 * 让手动滚到最底时末句底边停在 `viewport - bottomPadding = retainedBottomY` 处，
 * 而不是像库默认值 `viewport + keepAliveZone` 那样把整首歌完全划出屏外。
 */
internal fun lyricBottomContentPaddingPx(
    viewportHeightPx: Float,
    obstructionFraction: Float,
): Float {
    val safeViewport = viewportHeightPx.coerceAtLeast(0f)
    if (safeViewport == 0f) return 0f
    val clampedObstruction = obstructionFraction.coerceIn(0f, 1f)
    val visibleHeight = safeViewport * (1f - clampedObstruction)
    val retainedBottomY = (visibleHeight * LYRIC_END_RETAINED_VISIBLE_FRACTION)
        .coerceIn(0f, safeViewport)
    return (safeViewport - retainedBottomY).coerceAtLeast(0f)
}
