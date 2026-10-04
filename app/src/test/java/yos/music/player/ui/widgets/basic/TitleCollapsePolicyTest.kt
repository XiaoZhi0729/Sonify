package yos.music.player.ui.widgets.basic

import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCollapsePolicyTest {
    @Test
    fun startsExpandedBeforeFirstMeasurement() {
        assertEquals(CollapseUi(1f, false, false), collapse(null, layoutReady = false))
    }

    @Test
    fun firstMeasurementAtTopDoesNotToggleSmallTitle() {
        val beforeMeasurement = collapse(null, layoutReady = false)
        val measuredAtTop = collapse(0 to 200)

        assertEquals(measuredAtTop, beforeMeasurement)
    }

    @Test
    fun showsSmallTitleWhenMeasuredTitleHasScrolledOffscreen() {
        assertEquals(CollapseUi(0f, true, true), collapse(null))
    }

    @Test
    fun staysExpandedAtTopAndDuringOverscroll() {
        assertEquals(CollapseUi(1f, false, false), collapse(0 to 200))
        assertEquals(CollapseUi(1f, false, false), collapse(20 to 200))
    }

    @Test
    fun onlyShowsSmallTitleAtFullCollapse() {
        val halfway = collapse(-70 to 200)
        assertEquals(CollapseUi(0.5f, true, false), halfway)
        assertEquals(CollapseUi(0f, true, true), collapse(-140 to 200))
    }

    @Test
    fun preservesExtraTopPaddingBeforeTitleTouchesBar() {
        assertEquals(CollapseUi(1f, true, false), collapse(-40 to 200, extraPx = 60))
        assertEquals(CollapseUi(0.5f, true, false), collapse(-130 to 200, extraPx = 60))
        assertEquals(CollapseUi(0f, true, true), collapse(-200 to 200, extraPx = 60))
    }

    @Test
    fun handlesInitialZeroInsetsAndUpdatedInsetsAtTop() {
        assertEquals(CollapseUi(1f, false, false), collapse(null, layoutReady = false, statusPx = 0))
        assertEquals(CollapseUi(1f, false, false), collapse(0 to 200, statusPx = 0))
        assertEquals(CollapseUi(1f, false, false), collapse(0 to 200, statusPx = 60))
    }

    private fun collapse(
        info: Pair<Int, Int>?,
        layoutReady: Boolean = true,
        extraPx: Int = 0,
        statusPx: Int = 60
    ): CollapseUi = calculateTitleCollapse(info, layoutReady, extraPx, statusPx)
}
