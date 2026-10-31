package yos.music.player.ui.pages.library.playlists

/**
 * 操作行「挤压铺满」的动画曲线（纯函数，零 Compose 依赖，可进 JVM 单测）。
 *
 * 一个总进度 [fraction]（0 = 三控件常态，1 = 搜索框铺满整行）驱动所有变化，
 * 但每个量各占不同区间 —— 全部同时动会糊成一团，错开才有层次：
 *
 * - 胶囊**先被压扁**（宽度是主线索，跟手感来自宽度本身）
 * - 胶囊里的图标文字**更早消失**（压到一半时就没了，避免文字被压变形时穿帮）
 * - 随机钮**后撤**（贴向左缘并缩小），比胶囊晚一拍
 * - 两者的模糊**在前半段快速起雾**（退到焦外），后半段只剩淡出
 * - 输入框内容**最后进场**（从失焦处对焦进来）
 */
internal object PlayActionMotion {

    /** 胶囊宽度区间：在这段里从满宽压到 0。 */
    private const val PILL_SQUEEZE_START = 0.12f
    private const val PILL_SQUEEZE_END = 0.86f

    /** 胶囊左缘位移区间：跟着宽度一起向左收，看起来像被搜索框「推出去」。 */
    private const val PILL_SLIDE_START = 0f
    private const val PILL_SLIDE_END = 0.82f

    /** 胶囊内部（图标 + 文字）消失区间：按压缩量算，压到 55% 就没了。 */
    private const val PILL_CONTENT_FADE_AT = 0.55f

    /** 胶囊整体淡出区间：尾段。 */
    private const val PILL_ALPHA_START = 0.55f
    private const val PILL_ALPHA_END = 0.92f

    /**
     * 随机钮：滑出 + 缩放 + 淡出，统一在同一条区间（它是一颗圆，缩和淡同步最自然）。
     *
     * 起点必须早于搜索框覆盖到它的位置 —— 搜索框从右向左扩张，约在 f≈0.35 时盖住随机钮，
     * 所以它要在那之前就明显动起来，否则看起来是「纹丝不动、然后被盖住」。
     */
    private const val SHUFFLE_START = 0.10f
    private const val SHUFFLE_END = 0.80f

    /** 退场按钮的模糊：前半段起雾，之后维持到淡出结束。 */
    private const val OUT_BLUR_START = 0f
    private const val OUT_BLUR_END = 0.75f

    /** 输入框内容：对焦进场。 */
    private const val IN_START = 0.32f
    private const val IN_END = 0.88f

    /**
     * 退场模糊的最大半径（dp）。
     *
     * 16dp 在实机上偏重（退场方式变成"糊成一团"，而不是"退到焦外"），收到 11dp：
     * 足以读作失焦，又不至于盖住挤压与位移本身。
     */
    const val OUT_BLUR_DP = 11f

    /** 进场模糊的最大半径（dp）：与退场同量级，两端观感才对称。 */
    const val IN_BLUR_DP = 11f

    /**
     * 模糊半径量化档数。
     *
     * `Modifier.blur(radius)` 的 radius 要在组合期给定，逐帧改会让整棵子树逐帧重组。
     * 把它量化成有限档位：整个过渡最多重组这么多次，而人眼对 2dp 级别的模糊差察觉不到。
     */
    const val BLUR_STEPS = 8

    private fun clamp01(v: Float) = if (v < 0f) 0f else if (v > 1f) 1f else v

    /** 把 [fraction] 在 [start, end] 区间上重映射到 0..1（区间外钳位）。 */
    private fun remap(fraction: Float, start: Float, end: Float): Float {
        if (end <= start) return if (fraction >= end) 1f else 0f
        return clamp01((fraction - start) / (end - start))
    }

    /** 胶囊宽度占满宽的比例（1 → 0）。 */
    fun pillWidthFraction(fraction: Float): Float =
        1f - remap(fraction, PILL_SQUEEZE_START, PILL_SQUEEZE_END)

    /** 胶囊左缘朝左收的比例（0 → 1）。 */
    fun pillSlideFraction(fraction: Float): Float =
        remap(fraction, PILL_SLIDE_START, PILL_SLIDE_END)

    /** 胶囊内部图标 + 文字的可见度（先于胶囊本身消失）。 */
    fun pillContentAlpha(fraction: Float): Float {
        // 按「已压缩掉多少」算：压到 PILL_CONTENT_FADE_AT 时正好为 0
        val squeezed = 1f - pillWidthFraction(fraction)
        return 1f - clamp01(squeezed / PILL_CONTENT_FADE_AT)
    }

    /** 胶囊整体透明度。 */
    fun pillAlpha(fraction: Float): Float =
        1f - remap(fraction, PILL_ALPHA_START, PILL_ALPHA_END)

    /** 随机钮的缩放：1 → [SHUFFLE_MIN_SCALE]。 */
    const val SHUFFLE_MIN_SCALE = 0.08f

    fun shuffleScale(fraction: Float): Float {
        val k = remap(fraction, SHUFFLE_START, SHUFFLE_END)
        return 1f + (SHUFFLE_MIN_SCALE - 1f) * k
    }

    /** 随机钮贴向左缘的比例（0 → 1）。 */
    fun shuffleSlideFraction(fraction: Float): Float =
        remap(fraction, SHUFFLE_START, SHUFFLE_END)

    /** 随机钮透明度。 */
    fun shuffleAlpha(fraction: Float): Float = 1f - remap(fraction, SHUFFLE_START, SHUFFLE_END)

    /** 退场按钮（胶囊 / 随机钮共用）的模糊半径（dp）：前段起雾，之后维持。 */
    fun outBlurDp(fraction: Float): Float =
        OUT_BLUR_DP * remap(fraction, OUT_BLUR_START, OUT_BLUR_END)

    /** 输入框内容的透明度：对焦进场。 */
    fun inputAlpha(fraction: Float): Float = remap(fraction, IN_START, IN_END)

    /** 输入框内容的模糊半径（dp）：从失焦处对焦到清晰。 */
    fun inputBlurDp(fraction: Float): Float =
        IN_BLUR_DP * (1f - remap(fraction, IN_START, IN_END))

    /**
     * 把连续模糊半径量化成 [BLUR_STEPS] 档，供组合期读取（避免逐帧重组）。
     *
     * 返回的是**档位序号**而不是半径，调用方再乘步长：这样整数相等时 Compose 会跳过重组。
     */
    fun blurStep(radiusDp: Float, maxDp: Float): Int {
        if (maxDp <= 0f) return 0
        val k = clamp01(radiusDp / maxDp)
        return (k * BLUR_STEPS + 0.5f).toInt().coerceIn(0, BLUR_STEPS)
    }

    /** 档位序号 → 实际半径（dp）。 */
    fun stepToDp(step: Int, maxDp: Float): Float =
        maxDp * step.coerceIn(0, BLUR_STEPS) / BLUR_STEPS
}
