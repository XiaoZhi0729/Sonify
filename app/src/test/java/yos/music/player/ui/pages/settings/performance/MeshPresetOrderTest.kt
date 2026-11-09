package yos.music.player.ui.pages.settings.performance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 「竖屏/横屏网格样式」下拉菜单的点选错位回归。
 *
 * 指纹：菜单里有且只有一项表现正常（两个方向都是「四角拉伸」），其余全部错位一格以上 ——
 * 因为 `ORDER[i] == i` 时"写入时查表"和"读取时没查表"恰好等价。
 */
class MeshPresetOrderTest {

    @Test
    fun portraitOrderIsAscendingByDisplacement() {
        // 平均顶点位移：idx2 0.0171 < idx0 0.0208 < idx1 0.0221 < idx3 0.0303
        assertEquals(listOf(2, 0, 1, 3), MeshPresetOrder.PORTRAIT)
    }

    @Test
    fun landscapeOrderIsAscendingByDisplacement() {
        // 平均顶点位移：idx2 0.1129 < idx4 0.1533 < idx0 0.1550 < idx3 0.1606 < idx1 0.1762
        assertEquals(listOf(2, 4, 0, 3, 1), MeshPresetOrder.LANDSCAPE)
    }

    @Test
    fun portraitRoundTripPreselectsTheSameLabelThatWasTapped() {
        val order = MeshPresetOrder.PORTRAIT
        for (uiIndex in order.indices) {
            val stored = MeshPresetOrder.presetForUiIndex(order, uiIndex)
            assertEquals(
                "竖屏点第 $uiIndex 项后，菜单应高亮回第 $uiIndex 项",
                uiIndex,
                MeshPresetOrder.uiIndexForPreset(order, stored),
            )
        }
    }

    @Test
    fun landscapeRoundTripPreselectsTheSameLabelThatWasTapped() {
        val order = MeshPresetOrder.LANDSCAPE
        for (uiIndex in order.indices) {
            val stored = MeshPresetOrder.presetForUiIndex(order, uiIndex)
            assertEquals(
                "横屏点第 $uiIndex 项后，菜单应高亮回第 $uiIndex 项",
                uiIndex,
                MeshPresetOrder.uiIndexForPreset(order, stored),
            )
        }
    }

    /** 用户实测的 5 条横屏错位：点第 N 项必须显示第 N 项的文案，不能跳到别的项。 */
    @Test
    fun landscapeTapsMapToReportedLabels() {
        val labels = listOf("平静", "柔性流动", "中心鼓起", "四角拉伸", "大幅扫动")
        val order = MeshPresetOrder.LANDSCAPE
        val expected = mapOf(
            "平静" to 2,
            "柔性流动" to 4,
            "中心鼓起" to 0,
            "四角拉伸" to 3,
            "大幅扫动" to 1,
        )
        for ((label, storedPreset) in expected) {
            val uiIndex = labels.indexOf(label)
            assertEquals(
                "点「$label」应存预设下标 $storedPreset",
                storedPreset,
                MeshPresetOrder.presetForUiIndex(order, uiIndex),
            )
            assertEquals(
                "点「$label」后菜单应仍显示「$label」",
                label,
                labels[MeshPresetOrder.uiIndexForPreset(order, storedPreset)],
            )
        }
    }

    @Test
    fun portraitTapsMapToReportedLabels() {
        val labels = listOf("平静", "柔性流动", "中心鼓起", "四角拉伸")
        val order = MeshPresetOrder.PORTRAIT
        val expected = mapOf(
            "平静" to 2,
            "柔性流动" to 0,
            "中心鼓起" to 1,
            "四角拉伸" to 3,
        )
        for ((label, storedPreset) in expected) {
            val uiIndex = labels.indexOf(label)
            assertEquals(
                "点「$label」应存预设下标 $storedPreset",
                storedPreset,
                MeshPresetOrder.presetForUiIndex(order, uiIndex),
            )
            assertEquals(
                "点「$label」后菜单应仍显示「$label」",
                label,
                labels[MeshPresetOrder.uiIndexForPreset(order, storedPreset)],
            )
        }
    }

    @Test
    fun staleOutOfRangePresetFallsBackInsideTheList() {
        // 历史脏数据：换过档位表 key 之前的旧值、或随机档写入的越界值
        assertEquals(3, MeshPresetOrder.uiIndexForPreset(MeshPresetOrder.PORTRAIT, 99))
        assertEquals(0, MeshPresetOrder.uiIndexForPreset(MeshPresetOrder.PORTRAIT, -5))
        assertEquals(4, MeshPresetOrder.uiIndexForPreset(MeshPresetOrder.LANDSCAPE, 99))
        assertEquals(0, MeshPresetOrder.uiIndexForPreset(MeshPresetOrder.LANDSCAPE, -5))
    }

    @Test
    fun orderTablesCoverEveryPresetExactlyOnce() {
        assertEquals(
            (0 until 4).toList(),
            MeshPresetOrder.PORTRAIT.sorted(),
        )
        assertEquals(
            (0 until 5).toList(),
            MeshPresetOrder.LANDSCAPE.sorted(),
        )
    }
}
