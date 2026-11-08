package yos.music.player.ui.widgets.basic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住封面滚动动效的进度与曲线算式。
 *
 * 这些是纯函数，动效的全部「手感」都压在这里；页面只是把它们接到绘制上。
 * 参数来自原型逐帧调定的结果（最大模糊 40dp / 下沉 120dp / 终态缩放 0.94 /
 * 模糊跑满 0.58 / 幂 2.00、1.15、1.65），改动参数时应同步更新这里的期望值。
 */
class CoverScrollProgressTest {

    private val eps = 0.001f

    // ---------- 进度 ----------

    @Test
    fun `progress is zero at top`() {
        assertEquals(0f, coverScrollProgress(offset = 0, heightPx = 1000), eps)
    }

    /** 负偏移 = 顶部过度滚动（overscroll），必须钳到 0，否则封面会先向上窜一下。 */
    @Test
    fun `progress clamps negative overscroll to zero`() {
        assertEquals(0f, coverScrollProgress(offset = -300, heightPx = 1000), eps)
    }

    @Test
    fun `progress is one after scrolling a full cover height`() {
        assertEquals(1f, coverScrollProgress(offset = 1000, heightPx = 1000), eps)
    }

    /** 滚过自身高度之后进度不再增长（模糊/下沉都停在终态，不会继续加速）。 */
    @Test
    fun `progress clamps beyond one cover height`() {
        assertEquals(1f, coverScrollProgress(offset = 5000, heightPx = 1000), eps)
    }

    /** 高度还没测出来时返回 0：避免除零把封面瞬间抹成全透明。 */
    @Test
    fun `progress is zero when height not measured`() {
        assertEquals(0f, coverScrollProgress(offset = 500, heightPx = 0), eps)
        assertEquals(0f, coverScrollProgress(offset = 500, heightPx = -1), eps)
    }

    @Test
    fun `progress is linear in offset`() {
        assertEquals(0.25f, coverScrollProgress(offset = 250, heightPx = 1000), eps)
        assertEquals(0.5f, coverScrollProgress(offset = 500, heightPx = 1000), eps)
    }

    // ---------- 模糊 ----------

    @Test
    fun `blur starts at zero and reaches max at blur span`() {
        assertEquals(0f, coverBlurFraction(0f), eps)
        // 跑满位置 0.58：到这儿就已是最大值
        assertEquals(1f, coverBlurFraction(0.58f), eps)
        // 之后保持满值，不会继续变糊
        assertEquals(1f, coverBlurFraction(1f), eps)
    }

    /** 幂 2.00 = 前慢后快：进度一半时模糊只有最大值的四分之一左右。 */
    @Test
    fun `blur is slow in the first half`() {
        val halfOfSpan = 0.29f
        assertEquals(0.25f, coverBlurFraction(halfOfSpan), 0.01f)
        assertTrue("半程时应远未达到满值", coverBlurFraction(0.29f) < 0.5f)
    }

    @Test
    fun `blur fraction is monotonically non-decreasing`() {
        var previous = -1f
        var p = 0f
        while (p <= 1f) {
            val current = coverBlurFraction(p)
            assertTrue("p=$p 处模糊倒退", current >= previous - eps)
            previous = current
            p += 0.01f
        }
    }

    // ---------- 不透明度 ----------

    @Test
    fun `alpha starts opaque and ends fully transparent`() {
        assertEquals(1f, coverAlpha(0f), eps)
        assertEquals(0f, coverAlpha(1f), eps)
    }

    /** 幂 1.15 略大于 1 = 前慢后快：过半时仍有约 55% 不透明度，不会早早消失。 */
    @Test
    fun `alpha decays slowly at first`() {
        val atHalf = coverAlpha(0.5f)
        assertEquals(0.549f, atHalf, 0.01f)
        assertTrue("半程应仍保留可观的不透明度，实际 $atHalf", atHalf > 0.5f)
    }

    @Test
    fun `alpha is monotonically non-increasing`() {
        var previous = 2f
        var p = 0f
        while (p <= 1f) {
            val current = coverAlpha(p)
            assertTrue("p=$p 处不透明度回升", current <= previous + eps)
            previous = current
            p += 0.01f
        }
    }

    // ---------- 下沉 ----------

    @Test
    fun `sink starts at zero and reaches max at end`() {
        assertEquals(0f, coverSinkFraction(0f), eps)
        assertEquals(1f, coverSinkFraction(1f), eps)
    }

    /** 幂 1.65 > 1 = 前慢后快：下沉集中在中后段（p=0.5 时只走完约 32%）。 */
    @Test
    fun `sink back-loads its travel`() {
        assertEquals(0.319f, coverSinkFraction(0.5f), 0.01f)
        assertTrue("半程时应只走完约三分之一", coverSinkFraction(0.5f) < 0.4f)
    }

    @Test
    fun `sink is monotonically non-decreasing`() {
        var previous = -1f
        var p = 0f
        while (p <= 1f) {
            val current = coverSinkFraction(p)
            assertTrue("p=$p 处下沉倒退", current >= previous - eps)
            previous = current
            p += 0.01f
        }
    }

    // ---------- 缩放 ----------

    @Test
    fun `scale starts at one and ends at end scale`() {
        assertEquals(1f, coverScale(0f), eps)
        assertEquals(0.94f, coverScale(1f), eps)
    }

    /** 缩放与下沉同曲线：两者一起「退远」，不会一个先到终点。 */
    @Test
    fun `scale shares the sink curve`() {
        val p = 0.37f
        val expected = 1f - (1f - 0.94f) * coverSinkFraction(p)
        assertEquals(expected, coverScale(p), eps)
    }

    @Test
    fun `scale is monotonically non-increasing`() {
        var previous = 2f
        var p = 0f
        while (p <= 1f) {
            val current = coverScale(p)
            assertTrue("p=$p 处缩放变大", current <= previous + eps)
            previous = current
            p += 0.01f
        }
    }

    // ---------- 标题淡出（比封面晚得多） ----------

    @Test
    fun `title stays fully opaque until fade start`() {
        assertEquals(1f, coverTitleAlpha(0f), eps)
        assertEquals(1f, coverTitleAlpha(0.3f), eps)
        assertEquals(1f, coverTitleAlpha(0.54f), eps)
        // 起点处仍是不透明的
        assertEquals(1f, coverTitleAlpha(CoverScrollMotion.TitleFadeStart), eps)
    }

    @Test
    fun `title is fully gone at end`() {
        assertEquals(0f, coverTitleAlpha(1f), eps)
    }

    /**
     * 这是本次修复的核心不变式：封面已经开始糊、已经明显变淡时，
     * 标题还必须是**完全不透明**的。若两者同步淡，整块头部会一起塌，
     * 观感变成「页面在消失」而不是「封面在让位」。
     */
    @Test
    fun `title is still opaque when cover has already faded a lot`() {
        val p = 0.45f
        val cover = coverAlpha(p)
        val title = coverTitleAlpha(p)

        assertTrue("封面此时应已明显变淡，实际 $cover", cover < 0.75f)
        assertEquals("标题此时必须仍是全不透明", 1f, title, eps)
        assertTrue("标题必须比封面晚退", title > cover)
    }

    @Test
    fun `title fades strictly later than cover across the whole range`() {
        var p = 0f
        while (p < 1f) {
            val cover = coverAlpha(p)
            val title = coverTitleAlpha(p)
            // 中途允许相等（两端都是 1 或都是 0），但不允许标题比封面更早消失
            assertTrue("p=$p 处标题比封面先消失（title=$title cover=$cover）", title >= cover - eps)
            p += 0.01f
        }
    }

    @Test
    fun `title alpha is monotonically non-increasing`() {
        var previous = 2f
        var p = 0f
        while (p <= 1f) {
            val current = coverTitleAlpha(p)
            assertTrue("p=$p 处标题不透明度回升", current <= previous + eps)
            previous = current
            p += 0.01f
        }
    }

    // ---------- 静止态门控 ----------

    /**
     * 静止（进度 0）时模糊半径必须低于门控阈值，让 Haze 走内部短路、
     * 不建离屏图层。这是「恒定挂载修饰符也不产生静态成本」的前提。
     */
    @Test
    fun `blur radius at rest is below the enable threshold`() {
        val radiusAtRest = CoverScrollMotion.MaxBlur.value * coverBlurFraction(0f)
        assertEquals("静止态半径应为 0", 0f, radiusAtRest, eps)
        assertTrue(
            "静止态半径必须低于门控阈值 0.5dp，实际 $radiusAtRest",
            radiusAtRest < 0.5f
        )
    }

    /** 一旦开始滚动（进度刚过 0），半径必须很快越过门控阈值，避免「滑了却没反应」。 */
    @Test
    fun `blur radius exceeds threshold shortly after scrolling starts`() {
        // 进度 0.1 时：0.1/0.58 = 0.172，^2 = 0.0298，×40dp ≈ 1.19dp > 0.5dp
        val radius = CoverScrollMotion.MaxBlur.value * coverBlurFraction(0.1f)
        assertTrue("进度 0.1 时半径应已越过门控，实际 $radius", radius > 0.5f)
    }
}
