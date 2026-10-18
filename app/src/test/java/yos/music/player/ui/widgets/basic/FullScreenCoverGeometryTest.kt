package yos.music.player.ui.widgets.basic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 全屏静态封面的几何：封面/背景方框、封面底边淡出与（当前未启用的）渐隐 stop 表。
 * 这些常量直接决定观感（封面**顶边贴屏幕**、可见底边 = 1.12×层宽、底边淡出 25%），改动必须过这里。
 */
class FullScreenCoverGeometryTest {

    @Test
    fun sharpCoverIsTopAnchoredAndLessZoomed() {
        // 顶边贴屏幕边缘锚定（节点无 y 偏移）→ 可见底边 = 方框边长 = scale×层宽
        assertEquals(1209.6f, fullScreenCoverSharpSide(1080f), 0.01f)
        assertTrue("缩放必须 ≤1.2（1.2 观感偏大）", FullScreenCoverSharpScale <= 1.2f)
        assertTrue("缩放必须 ≥1.0（<1 左右会露底）", FullScreenCoverSharpScale >= 1.0f)
    }

    @Test
    fun blurBoxOverflowsThirtySixPercentOnEverySide() {
        val box = fullScreenCoverBox(layerWidth = 1080f, scale = FullScreenCoverBlurScale)
        assertEquals(1857.6f, box.side, 0.01f)
        assertEquals(-388.8f, box.left, 0.01f)
        assertEquals(-388.8f, box.top, 0.01f)
    }

    @Test
    fun boxIsCenteredOnTheLayer() {
        val box = fullScreenCoverBox(layerWidth = 1000f, scale = FullScreenCoverBlurScale)
        // 溢出对称 = 方框水平居中于层
        assertEquals(-box.left, box.left + box.side - 1000f, 0.01f)
    }

    @Test
    fun cropScaleFillsTheBox() {
        // 方图：缩放后正好等于方框边长
        assertEquals(1.296f, fullScreenCoverCropScale(1000f, 1000f, 1296f), 0.001f)
        // 宽图：高度受限（max 语义 = ContentScale.Crop）
        assertEquals(1f, fullScreenCoverCropScale(2000f, 1000f, 1000f), 0.001f)
        // 高图：宽度受限
        assertEquals(1f, fullScreenCoverCropScale(1000f, 2000f, 1000f), 0.001f)
    }

    @Test
    fun cropScaleFallsBackOnInvalidIntrinsicSize() {
        assertEquals(1f, fullScreenCoverCropScale(0f, 1000f, 1000f), 0.001f)
        assertEquals(1f, fullScreenCoverCropScale(1000f, 0f, 1000f), 0.001f)
    }

    @Test
    fun bottomExtensionLengthensTheCanvasByAboutTenDp() {
        // 画布高 = 方图边长 + 底边延伸段；延伸段"大概 10DP 左右"
        val side = fullScreenCoverSharpSide(1080f)
        assertEquals(side + FullScreenCoverBottomExtensionDp, fullScreenCoverSharpHeight(1080f), 0.01f)
        assertTrue(
            "延伸段必须在 5~16dp（用户要求 10dp 左右），当前 ${FullScreenCoverBottomExtensionDp}",
            FullScreenCoverBottomExtensionDp in 5f..16f
        )
    }

    @Test
    fun bottomExtensionStretchesTheBottomRowOverTheWholeStrip() {
        // 延伸段 = 最底下一行像素纵向拉伸，不是"把底部 10dp 复制一份"。
        val side = fullScreenCoverSharpSide(1080f)
        val ext = FullScreenCoverBottomExtensionDp
        val row = FullScreenCoverBottomExtensionSourcePx
        val k = ext / row
        val pivot = fullScreenCoverBottomExtensionPivot(side, ext, row)
        fun map(y: Float) = pivot + (y - pivot) * k
        // 这一行的底边 → 条带底边；这一行的顶边 → 条带顶边（= 封面方图底边）
        assertEquals(side + ext, map(side), 0.01f)
        assertEquals(side, map(side - row), 0.01f)
        // 方图其它内容一律落在条带之外（被裁掉）
        assertTrue("方图更靠上的内容不能落进延伸段", map(side - row * 2f) < side)
    }

    @Test
    fun bottomFadeLengthIsUnchangedAndRidesTheExtensionDown() {
        val side = fullScreenCoverSharpSide(1080f)
        val height = fullScreenCoverSharpHeight(1080f)
        val ext = height - side
        val start = fullScreenCoverBottomFadeStartY(side, height, FullScreenCoverCoverFadeFraction)
        val startWithoutExtension =
            fullScreenCoverBottomFadeStartY(side, side, FullScreenCoverCoverFadeFraction)
        // 渐变**长度**恒定 = fraction×方图边长：加延伸段不许把它拉长或截短（用户第七轮红线）
        assertEquals(side * FullScreenCoverCoverFadeFraction, height - start, 0.01f)
        // 整条渐变随封面底边一起下移了"延伸段"那么多
        assertEquals(startWithoutExtension + ext, start, 0.01f)
        // fraction = 0 → 不淡出（起点即底边 = 硬边）
        assertEquals(height, fullScreenCoverBottomFadeStartY(side, height, 0f), 0.01f)
    }

    @Test
    fun bottomFadeStaysInSaneRange() {
        // ≥0.3 = 封面下半截都糊掉；0 = 硬边（没有渐变）。
        assertTrue(
            "淡出比例必须在 [0, 0.3) 之间，当前 ${FullScreenCoverCoverFadeFraction}",
            FullScreenCoverCoverFadeFraction >= 0f && FullScreenCoverCoverFadeFraction < 0.3f
        )
    }

    @Test
    fun scrimStopsAreOrderedAndInRange() {
        assertEquals(0f, fullScreenCoverScrim.first().first, 0f)
        assertEquals(1f, fullScreenCoverScrim.last().first, 0f)
        fullScreenCoverScrim.zipWithNext { a, b ->
            assertTrue("stop 位置必须单调递增", b.first > a.first)
        }
        fullScreenCoverScrim.forEach { (_, black) ->
            assertTrue("黑度必须在 0..1", black in 0f..1f)
        }
    }

    @Test
    fun scrimNeverReachesPureBlack() {
        // 回归（真机实测）：参考实现那两条渐隐叠乘会在封面下方压出一段纯黑平台
        // （亮度 0~3/255，亮封面时就是"中间一块黑"）。本实现按真机像素标定，
        // 峰值必须落在"看得见压暗、但绝不成块"的窄区间里。
        val peak = fullScreenCoverScrim.maxOf { it.second }
        assertTrue("渐隐峰值不得达到纯黑（实测回归）", peak <= FullScreenCoverScrimMaxBlack)
        assertTrue("压得太浅就白压了（真机实测约 0.19）", peak >= 0.1f)
    }

    @Test
    fun scrimPeakSitsWhereFlamingoActuallyDips() {
        // 标定依据：参考实现真机在 1.08~1.35×层宽出现最低点（亮度 73 vs 下方 90）。
        val peakY = fullScreenCoverScrim.maxByOrNull { it.second }!!.first * FullScreenCoverScrimLength
        assertTrue("峰值 y=$peakY 必须落在真机实测的凹陷区间 1.05~1.45×层宽", peakY in 1.05f..1.45f)
        assertTrue("峰值必须在高清封面可见底边(1.1×层宽)之下，别压到封面本身", peakY >= 1.1f)
    }
}
