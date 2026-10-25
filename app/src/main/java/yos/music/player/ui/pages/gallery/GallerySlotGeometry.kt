package yos.music.player.ui.pages.gallery

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 单个槽位的几何：中心点相对舞台中心的偏移、缩放、不透明度与层级。
 *
 * [offsetX] / [offsetY] 的单位都是**舞台宽度 W 的比例**（不是像素、也不是高度比例）：
 * 卡片基准边长为 `W * BASE_SIZE_FRACTION`，槽位坐标也以 W 为标尺，横竖比因此与屏幕
 * 长宽比解耦——同一套槽位表在 20:9 手机与 4:3 平板上都能保持相同的相对布局。
 */
data class GallerySlot(
    val offsetX: Float,
    val offsetY: Float,
    val scale: Float,
    val z: Float
)

/**
 * 恒定 4 实体卡片的闭环轨道（No Add / No Remove）。
 *
 * **运动模型**：界面上永远只有 4 个卡片容器，没有任何卡片被创建或销毁。
 * 4 个固定物理槽位构成顺时针闭环：
 *
 * ```
 *                 Slot 0  右上主焦点  scale 1.00  z 100
 *                       ↖            ↗
 *        Slot 3 左中            Slot 1 右侧
 *        scale 0.64  z 80      scale 0.64  z 40
 *                       ↙            ↘
 *                 Slot 2  正下方    scale 0.64  z 60
 * ```
 *
 * 层级是**纵深序**（离主焦点越近越高），不是槽位号序：Slot3 正朝焦点走来所以
 * 压过 Slot2，Slot2 又压过刚离开焦点的 Slot1。运行期层级由 [zRank] 按实时尺寸
 * 现算，转场途中新主卡就会翻上来，不必等动画结束。
 * 向右滑动（[scrollIndex] 增大）时四张卡沿 `0 → 1 → 2 → 3 → 0` 顺畅闭环位移，
 * 右侧那张顺势滑到下方，不存在"从屏幕外飞入"或"飞出屏幕"。
 *
 * **内容映射（延后一拍换图）**：容器 `i` 在进度 `scroll` 时的连续环位置
 * `p = (i + scroll) mod 4`，整槽 `slot = floor(p)`、段号 `seg = floor(scroll)`。
 * 歌曲下标取
 *
 * ```
 * song(seg, slot) = seg - slot + 4 * (slot / 2)
 * ```
 *
 * 展开就是 `[seg, seg-1, seg+2, seg+1]`（当前 / 上一首 / 下下首 / 下一首）。
 * 这个式子的关键性质：**沿 `0→1→2→3→0` 走一圈，只有 `slot1 → slot2`
 * 那一段内容会跳变（+4）**，其余三段 `song(seg, slot) == song(seg+1, slot+1)`。
 * 于是封面换图恰好发生在「右侧 → 正下方」这一段——正是规范要求的
 * "绝不在一划走主焦点时就变图，而是到底下才悄悄换"。
 *
 * 换图由 [crossfadeAlpha] 给出 0→1 的进度，卡片内部双层图片交叉淡化实现。
 */
object GallerySlotGeometry {

    /** 卡片基准尺寸（`scale = 1.0` 时的边长）占舞台宽度 W 的比例。 */
    const val BASE_SIZE_FRACTION = 0.52f

    /** 闭环槽位数量：恒定 4 个。 */
    const val SLOT_COUNT = 4

    /** 向外扩散系数：槽位坐标整体乘以本系数（只放大位置，不放尺寸）。 */
    private const val SPREAD = 1.22f

    /**
     * 圆环枢轴（舞台比例，相对舞台中心）。
     *
     * 取值不是"卡片中心"而是整组卡环绕的**旋转轴**：以它为原点，四个槽位的极角
     * 依次是 -81.3° → 25.2° → 115.2° → 187.6°（闭合回 -81.3°+360°=278.7°），
     * 相邻角差 106.5° / 90.0° / 72.4° / 91.1° 全部为正且小于 180°，
     * 因此极坐标插值恒走最短弧、不会绕远路或反向。
     */
    private const val PIVOT_X = 0.055f
    private const val PIVOT_Y = 0.095f

    /** 四个固定槽位的基准坐标（未乘 SPREAD）。 */
    private val slotBase: List<Pair<Float, Float>> = listOf(
        0.08f to -0.15f,  // Slot 0 右上主焦点
        0.22f to 0.16f,   // Slot 1 右侧
        -0.05f to 0.28f,  // Slot 2 正下方
        -0.24f to 0.04f   // Slot 3 左中
    )

    /** 槽位缩放：主焦点 1.0，其余 0.64。 */
    private val slotScale: List<Float> = listOf(1.00f, 0.64f, 0.64f, 0.64f)

    /**
     * 槽位**静止态**层级，按槽位号索引。
     *
     * 顺序不是 0>1>2>3，而是按「离主焦点还有多远」的纵深序：
     * `Slot0 100 > Slot3 80 > Slot2 60 > Slot1 40`。Slot1 刚离开焦点、正在往后退，
     * 是环上离焦点最远的一张，所以垫底；Slot3 正朝焦点走来，所以仅次于焦点。
     *
     * 运行期**不用这张表**（用它就会复现本轮 bug），而是由 [zRank] 按实时尺寸序现算；
     * 这里只用于 [slot] 的静止态几何与测试断言。
     */
    private val slotZ: List<Float> = listOf(100f, 40f, 60f, 80f)

    /** 按**排名**索引的层级表：rank 0（最大）→ 100，rank 3（最小）→ 40。 */
    private val rankZ: List<Float> = listOf(100f, 80f, 60f, 40f)

    /**
     * 切线→横向混合的拐点（离枢轴的距离，单位：舞台宽度）。
     *
     * 内侧 `RAMP_INNER` 以内完全走横向（切线方向在小半径上不稳定，会抖）；
     * 外侧 `RAMP_OUTER` 以外完全走切线（跟手转圈）。
     * 正常握持时手指离枢轴约 0.2~0.5 W，都落在完全切线段。
     */
    private const val RAMP_INNER = 0.06f
    private const val RAMP_OUTER = 0.16f

    /**
     * 绘制顺序基线：远大于任何现实取值，保证 zIndex 恒为正。
     *
     * zIndex 为负的子节点会先于 zIndex=0 的兄弟绘制，而本页背景层（两层
     * YosFloatingLight + 顶部 scrim）都是 zIndex=0 且不透明——负值会让卡片
     * 直接被背景盖住（曾实机出现「所有图片都没了」）。
     */
    private const val Z_INDEX_BASE = 1_000_000f

    /** 槽位几何（整槽）。 */
    fun slot(slot: Int): GallerySlot {
        val i = wrapSlot(slot)
        val (bx, by) = slotBase[i]
        return GallerySlot(bx * SPREAD, by * SPREAD, slotScale[i], slotZ[i])
    }

    /** 把任意整数归一到 `[0, SLOT_COUNT)`。 */
    fun wrapSlot(slot: Int): Int = ((slot % SLOT_COUNT) + SLOT_COUNT) % SLOT_COUNT

    /** 段号：`floor(scrollIndex)`。 */
    fun segmentAt(scroll: Float): Int = floor(scroll).toInt()

    /**
     * 容器 `containerIndex` 的连续环位置，落在 `[0, SLOT_COUNT)`。
     *
     * `p = (i + scroll) mod 4`：向右滑（scroll 增大）时 p 增大，
     * 卡片沿 `0→1→2→3→0` 顺时针前进。
     */
    fun ringPosition(containerIndex: Int, scroll: Float): Float {
        val raw = (containerIndex + scroll) % SLOT_COUNT
        return if (raw < 0f) raw + SLOT_COUNT else raw
    }

    /** 容器当前所在的整槽（`floor(ringPosition)`，0..3）。 */
    fun slotAt(containerIndex: Int, scroll: Float): Int =
        floor(ringPosition(containerIndex, scroll)).toInt().coerceIn(0, SLOT_COUNT - 1)

    /**
     * 换图进度（**纯几何**，不看内容）：只在 `slot1 → slot2`（右侧 → 正下方）这一段
     * 从 0 涨到 1，其余三段恒为 0。
     *
     * 注意：真正决定要不要渐变的是带 `size` 的重载
     * [crossfadeAlpha]`(containerIndex, scroll, size)`——内容没变时必须返回 0，
     * 否则卡片会淡成空白（见该重载的注释）。
     */
    fun crossfadeAlpha(containerIndex: Int, scroll: Float): Float {
        val p = ringPosition(containerIndex, scroll)
        val slot = floor(p).toInt()
        if (slot != 1) return 0f
        return (p - slot).coerceIn(0f, 1f)
    }

    /**
     * 换图进度（**带内容判据**，页面必须用这个）：当这一段的前后内容其实是同一首时
     * 恒返回 0，完全不执行渐变。
     *
     * **为什么必须判内容**（实机 bug：卡片"逐渐消失 → 落位后又突然出现"，阴影还在）：
     * 渐变是"上层旧图 alpha 1→0、底层新图 alpha 0→1"的双层结构，而底层只有在
     * `newSong.artworkUrl != oldSong.artworkUrl` 时才会被绘制。于是当前后内容**相同**时：
     * 上层照样淡出、底层又没画，卡片就整段变成透明的空框；等这一段走完 `crossfade`
     * 归 0，上层 alpha 弹回 1，看起来就是"消失之后又突然冒出来"。
     *
     * 这个相同内容的情形在**冷启动第一段**必然出现：`scroll=0` 时 Slot1 的"上一首"
     * 是 `-1`，被 [displaySongIndex] 归一成第 4 首；而它滑到 Slot2 时本来也显示
     * 第 4 首。两者相同 → 该段不该有任何渐变。反向滑（从第 2 首退回第 1 首）会让
     * 同一张卡沿 Slot2→Slot1 反向经过这一段，于是看到"第三张闪一下消失再出现"。
     *
     * 阴影不受影响：它画在容器节点上（`drawWithCache`），与图片 alpha 无关——
     * 这正是实机里"图没了、阴影还在"的原因。
     */
    fun crossfadeAlpha(containerIndex: Int, scroll: Float, size: Int): Float {
        if (size <= 0) return 0f
        val pair = songPair(containerIndex, scroll, size)
        if (pair.first == pair.second) return 0f
        return crossfadeAlpha(containerIndex, scroll)
    }

    /**
     * 容器在给定进度下应当显示的两首歌（旧图, 新图）。两者相同表示这一段不换图。
     *
     * 返回的是**可直接用于 `songs[...]`** 的下标。
     */
    fun songPair(containerIndex: Int, scroll: Float, size: Int): Pair<Int, Int> {
        val seg = segmentAt(scroll)
        val slot = slotAt(containerIndex, scroll)
        val old = displaySongIndex(songAt(seg, slot), size)
        // 只有 slot1 这一段会在到达 slot2 时换图（+4）
        val next = if (slot == 1) displaySongIndex(songAt(seg + 1, 2), size) else old
        return old to next
    }

    /**
     * 槽位 → 歌曲下标（未取模）。
     *
     * `song(seg, slot) = seg - slot + 4 * (slot / 2)`，即
     * `[seg, seg-1, seg+2, seg+1]`。绕一圈只有 `slot1→slot2` 会变（+4），
     * 这是闭环轨道里"4 个容器循环复用 4 个槽位"的必然结果，也正是换图时机。
     */
    internal fun songAt(seg: Int, slot: Int): Int {
        val s = wrapSlot(slot)
        return seg - s + SLOT_COUNT * (s / 2)
    }

    /** 环形取模到 `[0, size)`；size<=0 时返回 0（调用方另有非空守卫）。 */
    fun wrapSong(index: Int, size: Int): Int {
        if (size <= 0) return 0
        return ((index % size) + size) % size
    }

    /**
     * 卡片封面的**显示用**下标：负下标在**四张卡的环窗口内**回绕，不回绕到队尾。
     *
     * 为什么需要这个特例（两个实机 bug 都出在这里）：
     *  Slot1（右侧）语义上是"上一首" `seg - 1`，而**冷启动时 `seg == 0`**，
     *  于是它算出来是 `-1` —— 一个不存在的"第 0 首的上一首"。怎么解释这个 -1，
     *  直接决定冷启动那一眼的观感：
     *
     *  - `wrapSong(-1, size)`（按队列长度回绕）→ 队尾第 20 首。屏幕上会凭空出现
     *    一张"最后一首"的封面，滑一下才变成第 4 首，观感是错的。
     *  - 夹到 0（曾经试过的修法）→ 与主焦点**同一张图**，肉眼立刻看出"两张一样的
     *    封面"，比闪烁更像 bug（用户实机反馈即此）。
     *  - **按环窗口回绕（本实现）**：`-1 ≡ 3 (mod 4)` → 第 4 首。这才是对的：
     *    闭环轨道上"焦点前一格"就是环上的第 4 格；而且它恰好等于这张卡滑到
     *    Slot2 时即将变成的内容，所以冷启动**不会**再触发一次换图。
     *
     * 正向下标（含越过队列末尾）仍按队列长度环形取模：画廊要能无限前进。
     */
    fun displaySongIndex(index: Int, size: Int): Int {
        if (size <= 0) return 0
        // 负下标只可能来自冷启动的 seg=0（-1）：在 4 槽环内回绕成 3（第 4 首）
        val normalized = if (index < 0) wrapSlot(index) else index % size
        return normalized.coerceIn(0, size - 1)
    }

    /**
     * 连续环位置的插值几何。
     *
     * 位置在**极坐标**上插值：以 [PIVOT_X]/[PIVOT_Y] 为圆心把两端槽位换成
     * `(radius, angle)`，角度走最短弧、半径线性，再换回直角坐标；角度与半径都用
     * [smoothStep] 缓动（`t*t*(3-2t)`），跨槽位交界处速度为 0，无速度折点。
     * 缩放/层级走线性。
     */
    fun geometry(ringPos: Float): GallerySlot {
        val p = if (ringPos < 0f) ringPos % SLOT_COUNT + SLOT_COUNT else ringPos % SLOT_COUNT
        val from = floor(p).toInt()
        val t = p - from
        val a = slot(from)
        if (t == 0f) return a
        val b = slot(from + 1)

        val eased = smoothStep(t)
        val radiusA = radiusOf(a)
        val radiusB = radiusOf(b)
        var delta = angleOf(b) - angleOf(a)
        while (delta > PI.toFloat()) delta -= (2f * PI).toFloat()
        while (delta < -PI.toFloat()) delta += (2f * PI).toFloat()
        val radius = radiusA + (radiusB - radiusA) * eased
        val angle = angleOf(a) + delta * eased

        return GallerySlot(
            offsetX = PIVOT_X + radius * cos(angle),
            offsetY = PIVOT_Y + radius * sin(angle),
            scale = a.scale + (b.scale - a.scale) * t,
            z = a.z + (b.z - a.z) * t
        )
    }

    /** 平滑缓动 `t*t*(3-2t)`：两端速度为 0，消除跨槽位交界处的速度折点。 */
    private fun smoothStep(t: Float): Float = t * t * (3f - 2f * t)

    /** 槽位到圆环枢轴的距离（舞台比例）。 */
    internal fun radiusOf(s: GallerySlot): Float =
        hypot(s.offsetX - PIVOT_X, s.offsetY - PIVOT_Y)

    /** 槽位相对圆环枢轴的极角（弧度）。 */
    internal fun angleOf(s: GallerySlot): Float =
        atan2(s.offsetY - PIVOT_Y, s.offsetX - PIVOT_X)

    /**
     * 把手指位移换算成**环位移**（单位：槽位）——这是「转着圈滑」的核心。
     *
     * 三种模型在拖动中的效果差异很大：
     *  - 纯横向（旧实现）：只取 `dx`。手指沿弧走、卡片沿弧走，但两者轨迹分叉，
     *    弧线上滑会"发飘"、上下滑完全没反应。
     *  - 纯圆盘：按绕枢轴的**角位移**换算。最像"转盘"，但圆环右侧横向滑动是
     *    半径方向，角位移≈0 → 怎么滑都没反应；越靠近圆心越敏感、容易抖。
     *  - **切线投影（本实现）**：把手指位移投影到当前卡片的圆弧切线方向。
     *    沿弧拖动跟手、上下滑动也能带动环转。
     *
     * 混合式：用 [radialBlend] 按"离枢轴多远"在**切线投影**与**纯横向**之间插值。
     * 离枢轴足够远（正常握持位置）时走切线，跟手且能转圈；靠近枢轴（半径→0，
     * 切线方向本身不稳定）时平滑退回纯横向，保证任何位置都不会"滑了没反应"。
     *
     * @param dx,dy 手指位移（像素，舞台坐标系，y 向下为正）
     * @param fingerX,fingerY 手指当前位置相对**舞台中心**的偏移（像素）
     * @param stageWidthPx 舞台宽度（像素）
     * @param triggerPx 横向口径：整条手势行程对应的像素（`TRIGGER_FRACTION * W`），
     *   横向段用它与旧手感保持一致
     * @return 环位移（正 = 向前/下一首，即 Slot0→1→2→3→0）
     */
    fun dragToRingDelta(
        dx: Float,
        dy: Float,
        fingerX: Float,
        fingerY: Float,
        stageWidthPx: Float,
        triggerPx: Float
    ): Float {
        if (stageWidthPx <= 0f || triggerPx <= 0f) return 0f
        // 手指相对枢轴的位置（枢轴是舞台比例坐标，先换算成像素）
        val px = fingerX - PIVOT_X * stageWidthPx
        val py = fingerY - PIVOT_Y * stageWidthPx
        val radius = hypot(px, py)

        // 切线方向（绕枢轴顺时针为正）：位置向量 (px,py) 的法向 (-py, px)。
        // 屏幕上 y 向下，因此 (-py, px) 指向顺时针切线——与"向右滑 = 下一首"同号。
        val tangentComponent = if (radius > 1e-3f) {
            (dx * -py + dy * px) / radius
        } else {
            0f
        }

        // 切线段：把"一段弧长"折算成槽位数，使沿弧拖动跟手度与半径无关。
        val slotArc = slotArcPx(radius, stageWidthPx)
        val tangentSlots = if (slotArc > 1e-3f) tangentComponent / slotArc else 0f

        // 横向段：沿用旧口径（整条 triggerPx 行程 = 1 槽位）。
        val horizontalSlots = dx / triggerPx

        val blend = radialBlend(radius, stageWidthPx)
        return tangentSlots * blend + horizontalSlots * (1f - blend)
    }

    /**
     * 一个槽位对应的**弧长**（像素）：用相邻槽位的极角差与当前半径折算。
     *
     * 半径越小弧长越短——这正是切线模型在圆心附近会失控的原因，
     * 所以它只在小半径以外使用（见 [radialBlend]）。
     */
    private fun slotArcPx(radiusPx: Float, stageWidthPx: Float): Float {
        // 取 4 个槽位极角差的平均值作为"一格"的张角
        var total = 0f
        for (i in 0 until SLOT_COUNT) {
            var d = angleOf(slot(i + 1)) - angleOf(slot(i))
            while (d > PI.toFloat()) d -= (2f * PI).toFloat()
            while (d < -PI.toFloat()) d += (2f * PI).toFloat()
            total += abs(d)
        }
        val slotAngle = total / SLOT_COUNT
        return radiusPx * slotAngle
    }

    /**
     * 切线/横向的混合权重：`1` = 完全切线（远离枢轴），`0` = 完全横向（贴近枢轴）。
     *
     * 用平滑过渡而不是硬切：权重突变会让拖动中途"手感跳变"。
     * 拐点在 `RAMP_INNER ~ RAMP_OUTER` 个舞台宽之间——正常握持手指离枢轴
     * 约 0.2~0.5 个舞台宽，都落在完全切线段；只有手指真的压到圆心附近
     * 才退回横向。
     */
    fun radialBlend(radiusPx: Float, stageWidthPx: Float): Float {
        if (stageWidthPx <= 0f) return 0f
        val r = radiusPx / stageWidthPx
        val t = ((r - RAMP_INNER) / (RAMP_OUTER - RAMP_INNER)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 卡片的 `Modifier.zIndex`（按整槽静态取值，仅用于静止态断言）。 */
    fun zIndexForSlot(slot: Int): Float = Z_INDEX_BASE + rankZ[wrapSlot(slot)]

    /**
     * 环位置 `p` 到「成为主焦点」还要走多少（单位：槽位，恒在 `[0, 4)`）。
     *
     * 这是纵深序的唯一判据：**越接近 0（主焦点）越靠前**。
     * 向右滑时卡片按 `0 → 1 → 2 → 3 → 4` 前进，`p = 4` 与 `p = 0` 等价，
     * 所以 `3.5` 的纵深距离是 `0.5`（即将接任焦点），而 `0.5` 是 `3.5`（刚卸任）。
     */
    fun forwardDistance(p: Float): Float {
        val wrapped = if (p < 0f) p % SLOT_COUNT + SLOT_COUNT else p % SLOT_COUNT
        return if (wrapped == 0f) 0f else SLOT_COUNT - wrapped
    }

    /**
     * 容器 `containerIndex` 在进度 `scroll` 时的**层级排名**：0 = 最靠前（最大）。
     *
     * 判定完全基于**实时尺寸**，即"大卡永远压小卡"：
     * 转场中接任卡 scale 从 0.64 涨向 1.0、卸任卡从 1.0 跌向 0.64，两者在
     * 进度 0.5 处相等，排名就在那一帧交换——**动画进行中**新主卡已经压在最上面。
     *
     * **本轮修复的 bug**：旧实现用 `slotAt()`（`floor(环位置)`）查静态槽位表。
     * 卡片从 Slot3 滑向 Slot0 的整段里 `floor(p)` 恒为 3，层级恒为最低的 40，
     * 要等环位置真正回绕成 0（= 弹簧落位结束那一帧）才跳到 100，实机表现正是
     * 「正在聚焦的卡片要等动画结束之后层级才跑到最上面」。
     *
     * 尺寸相等（进度恰好 0.5）时用 [forwardDistance] 决胜：离焦点更近的（接任方）
     * 胜出，保证交换方向永远是"新卡翻上来"而不是"旧卡赖着不走"。
     */
    fun zRank(containerIndex: Int, scroll: Float): Int {
        val mine = geometry(ringPosition(containerIndex, scroll))
        var rank = 0
        for (other in 0 until SLOT_COUNT) {
            if (other == containerIndex) continue
            val op = ringPosition(other, scroll)
            val o = geometry(op)
            val larger = when {
                o.scale > mine.scale -> true
                o.scale < mine.scale -> false
                // 尺寸相同：离焦点更近者在前
                else -> forwardDistance(op) < forwardDistance(ringPosition(containerIndex, scroll))
            }
            if (larger) rank++
        }
        return rank
    }

    /** 按**层级排名**取 zIndex：rank 0 → 100，rank 3 → 40。 */
    fun zIndexForRank(rank: Int): Float =
        Z_INDEX_BASE + rankZ[rank.coerceIn(0, SLOT_COUNT - 1)]

    /**
     * 越界橡皮筋：把越界量压成衰减位移（渐近 ~0.45 个槽位），边界处全体卡片
     * 原位弹性位移、松手弹回。拖拽写入与几何展示共用同一 [deflect] 配方。
     */
    fun rubberBand(raw: Float, min: Float, max: Float): Float = when {
        raw < min -> min - deflect(min - raw)
        raw > max -> max + deflect(raw - max)
        else -> raw
    }

    /** 越界阻尼：x 越大阻力越大，位移渐近上限 1/2.2 ≈ 0.45 槽位。 */
    fun deflect(x: Float): Float = x / (1f + x * 2.2f)

    /**
     * 文字连续槽位的不透明度：offset 0 在位；向 [-1,0] 上移淡出、[0,1] 从下方淡入。
     * 任意 offset 处新旧两层可见性之和恒为 1，无闪断空窗。
     */
    fun textAlpha(offset: Float): Float = 1f - abs(offset.coerceIn(-1f, 1f))

    /** 文字纵向位移（dp，正值在下方）：新文字从下方 +16dp 推入，旧文字向 -16dp 上移淡出。 */
    fun textTranslationYDp(offset: Float): Float = 16f * offset.coerceIn(-1f, 1f)
}
