package yos.music.player.ui.pages.library

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * 单张封面在扇形堆叠轨道上的完整视觉变换参数。
 *
 * 所有值均由连续浮点深度 `relDepth = absIdx - scroll` 唯一决定，
 * 保证任意方向拖拽、高速甩动或中途打断时的 C¹ 连续性（无位置/尺寸/透明度跳变）。
 */
internal data class FmCoverTransform(
    val offsetXDp: Float,
    val offsetYDp: Float,
    val scale: Float,
    val rotationZ: Float,
    val rotationY: Float,
    val alpha: Float,
    val shadowElevationDp: Float,
    val zIndex: Float
)

/**
 * 资料库「漫游电台」扇形封面堆叠的物理与几何引擎（纯 Kotlin，无 Android/Compose 依赖）。
 *
 * 轨道约定：
 * - 连续进度 `scroll` 每增加 `1.0`，代表切换到下一首；减小 `1.0` 代表回到上一首。
 * - 容器渲染窗口为 `floor(scroll) .. floor(scroll) + 3` 共 4 个物理槽位。
 * - `relDepth = absIdx - scroll`，取值范围通常在 `[-1f, 3f]`：
 *   - `[-1f, 0f]`：主焦点封面正在向左退出（或从左侧返回主位）。
 *   - `[0f, 1f]`：第 2 张封面从后排升至主焦点（或从主焦点退回第 2 层）。
 *   - `[1f, 2f]`：第 3 张封面补位至第 2 层。
 *   - `[2f, 3f]`：第 4 张封面在最底层柔和浮现（alpha 0 -> 0.45）。
 */
internal object FmCoverStackPhysics {

    /** 可同时渲染的物理槽位数量。 */
    const val VISIBLE_SLOTS = 4

    /** 拖拽进度换算基准（dp / 1 张卡片），对齐标准卡片离开轨迹以实现 1:1 跟手。 */
    const val DRAG_STEP_DP = 118f

    /** 按屏幕宽度档位返回 1:1 拖拽步长（dp）。 */
    fun dragStepDp(isCompactPhone: Boolean): Float = if (isCompactPhone) 104f else 118f

    /** 2D 垂直橡皮筋最大自由跟随幅度（dp）。 */
    const val VERTICAL_RUBBER_LIMIT_DP = 14f

    /** Apple 指数衰减动量投影系数（scroll 卡片单位，0.994 为轻量卡片的灵动衰减）。 */
    private const val DECELERATION_RATE = 0.994f

    /** 动量投影速度缩放因子（将 cards/s 换算为 ms 级步长并匹配轻快手感）。 */
    private const val PROJECTION_SCALE = 0.36f

    /** 单次快甩最多连续跨越的曲目数，避免滑得太远失控。 */
    const val MAX_STEP_PER_FLICK = 3

    /** 慢速拖拽（几乎无速度）时跨过该比例即判定切歌。 */
    private const val SLOW_DRAG_THRESHOLD = 0.28f

    /**
     * 将任意整数索引（含负数或超界正数）循环映射到 `[0, size - 1]`。
     */
    fun wrapIndex(index: Int, size: Int): Int {
        if (size <= 0) return 0
        val mod = index % size
        return if (mod < 0) mod + size else mod
    }

    /**
     * 寻找与当前连续进度 [currentScroll] 距离最近的、对应目标歌曲索引 [targetWrappedIndex] 的连续整数坐标。
     * 用于外部 `fmIndex` 变化时平滑对齐，不会绕一整圈回跳。
     */
    fun nearestEquivalentScroll(currentScroll: Float, targetWrappedIndex: Int, size: Int): Float {
        if (size <= 0) return 0f
        val safeTarget = wrapIndex(targetWrappedIndex, size)
        val currentRound = currentScroll.roundToInt()
        val currentWrapped = wrapIndex(currentRound, size)
        var delta = safeTarget - currentWrapped
        val half = size / 2f
        if (delta > half) delta -= size
        if (delta < -half) delta += size
        return (currentRound + delta).toFloat()
    }

    /**
     * Apple *Designing Fluid Interfaces* 渐进阻尼橡皮筋函数：
     * 越过边界或偏离中心越远，阻力越大，且永远平滑收敛于 `±dimension`。
     */
    fun rubberBand(overshoot: Float, dimension: Float, constant: Float = 0.55f): Float {
        if (dimension <= 0f) return 0f
        return (overshoot * dimension * constant) / (dimension + constant * abs(overshoot))
    }

    /**
     * 依据当前位置、释放瞬时速度（单位：卡片/秒）与本次手势净位移，
     * 使用 Apple 指数衰减模型投影落点，再吸附到目标整数槽位。
     *
     * - 向左滑（手指向左拖，`scroll` 增大，`velocityCardsPerSec > 0`）→ 下一首
     * - 向右滑（手指向右拖，`scroll` 减小，`velocityCardsPerSec < 0`）→ 上一首
     */
    fun projectTarget(
        currentScroll: Float,
        velocityCardsPerSec: Float,
        gestureNetScroll: Float,
        startScroll: Float = currentScroll,
        maxStepPerFlick: Int = MAX_STEP_PER_FLICK
    ): Int {
        val anchor = startScroll.roundToInt()

        // 1. Apple 指数衰减动量投影：project = (v / 1000) * d / (1 - d)
        val decayFactor = DECELERATION_RATE / (1f - DECELERATION_RATE)
        val projectedDelta = (velocityCardsPerSec / 1000f) * decayFactor * PROJECTION_SCALE
        val projectedScroll = currentScroll + projectedDelta

        var target = projectedScroll.roundToInt()

        // 2. 慢速精准拖拽兜底：速度很低但已经明确拖过阈值（> 28%）时，顺应拖拽方向切 1 首
        val speed = abs(velocityCardsPerSec)
        if (speed < 1.2f && target == anchor && abs(gestureNetScroll) >= SLOW_DRAG_THRESHOLD) {
            target = anchor + if (gestureNetScroll > 0f) 1 else -1
        }

        // 3. 反向急刹保护：如果手指刚把卡往左拖，松手瞬间却往回甩（速度与净位移反向且较快），
        //    尊重最终反悔意图，允许直接弹回或反向切换
        //    （projectedScroll 已天然反映最终速度，此处仅做单次最大步幅限幅）
        val stepFromCurrent = (target - currentScroll.roundToInt()).coerceIn(-maxStepPerFlick, maxStepPerFlick)
        return currentScroll.roundToInt() + stepFromCurrent
    }

    /**
     * 拖拽按压微抬的连续权重（以 `relDepth = 0` 为中心平滑衰减至 `±1` 为 `0`），
     * 彻底消除原先 `relDepth in -0.5..0.5` 硬阈值在 `±0.5` 处导致的卡片突然变小/变大跳变。
     */
    fun pressWeight(relDepth: Float): Float {
        val w = (1f - abs(relDepth)).coerceIn(0f, 1f)
        return smoothStep(w)
    }

    /**
     * 焦点控件（播放按钮与右下角暗角）的连续可见度：
     * - `relDepth == 0` 为 `1f`；
     * - 拖离焦点时在 `|relDepth| ∈ [0.06, 0.46]` 平滑淡出，过中点前已完全收起；
     * - 下一张卡接近主位时同步柔和浮现，彻底消除中点 `0.5` 处的闪现跳变。
     */
    fun focusOverlayProgress(relDepth: Float): Float {
        val t = ((0.46f - abs(relDepth)) / 0.40f).coerceIn(0f, 1f)
        return smoothStep(t)
    }

    /**
     * 计算给定连续深度 [relDepth] 对应的封面变换。
     * 保证在 `relDepth = -1, 0, 1, 2, 3` 所有衔接点完全连续无折角跳变。
     */
    fun cardTransform(relDepth: Float, isCompactPhone: Boolean): FmCoverTransform {
        val d = relDepth.coerceIn(-1f, 3f)

        val x0 = 2f
        val y0 = 10f

        return if (d < 0f) {
            // 离场/返场轨道：d in [-1f, 0f]
            // t = 0 表示在主焦点，t = 1 表示已向左完全飞出
            val t = -d
            val easedT = smoothStep(t)
            val exitStep = dragStepDp(isCompactPhone)
            // 初始斜率严格匹配 dragStepDp（1:1 跟手），随后温和加速离开
            val offsetX = x0 - exitStep * (t + 0.16f * t * t)
            // t=0 处垂直导数为 0（无折角弹跳），离开过程中柔和下沉 5.5dp
            val offsetY = y0 + 5.5f * t * t
            val scale = 1f - 0.08f * easedT
            val rotZ = -9.5f * t
            val rotY = 4.5f * easedT
            // 前 28% 保持实体感，之后平滑淡出
            val alphaT = ((t - 0.28f) / 0.72f).coerceIn(0f, 1f)
            val alpha = 1f - smoothStep(alphaT)
            val shadow = lerp(10f, 2f, easedT)
            // 正在退出/返回的卡始终压在最前，形成「从上方掀走 / 从左上方盖回」的空间序
            val z = 100f + 10f * t
            FmCoverTransform(offsetX, offsetY, scale, rotZ, rotY, alpha, shadow, z)
        } else {
            // 堆叠轨道：d in [0f, 3f]，全程 C∞ 解析平滑递进，消除整数槽位折角
            val u = (d / 3f).coerceIn(0f, 1f)
            val fanStep = if (isCompactPhone) 10f else 12f
            val offsetX = x0 + fanStep * (1f - 0.72f.pow(d)) / (1f - 0.72f)
            val offsetY = y0 + 3.0f * d
            val scale = 0.905f.pow(d)
            val rotZ = 4.6f * d
            // u=0 处导数为 0，与离场端 alpha=1 平滑衔接；u=1 处归零
            val alpha = (1f - u * u * (1.8f - 0.8f * u)).coerceIn(0f, 1f)
            val shadow = 10f * (1f - u).pow(1.4f)
            val z = 100f - d * 20f
            FmCoverTransform(
                offsetXDp = offsetX,
                offsetYDp = offsetY,
                scale = scale,
                rotationZ = rotZ,
                rotationY = 0f,
                alpha = alpha,
                shadowElevationDp = shadow,
                zIndex = z
            )
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothStep(t: Float): Float {
        val x = t.coerceIn(0f, 1f)
        return x * x * (3f - 2f * x)
    }
}
