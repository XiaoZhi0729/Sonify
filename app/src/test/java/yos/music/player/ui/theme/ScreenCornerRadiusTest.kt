package yos.music.player.ui.theme

import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Test

class ScreenCornerRadiusTest {
    @Test fun automaticStartsAtAndroid12() {
        assertFalse(usesSystemScreenCorners(30))
        assertTrue(usesSystemScreenCorners(31))
        assertTrue(usesSystemScreenCorners(37))
    }
    @Test fun systemPixelsAreConvertedNotTreatedAsDp() {
        assertEquals(48.dp, screenCornerRadiusDp(31, 156, 3.25f, "12"))
        assertEquals(78.dp, screenCornerRadiusDp(31, 156, 2f, "12"))
    }
    @Test fun systemValueWinsButZeroFallsBackToManualValue() {
        // 0 是 ColorOS 平板那种"物理圆角、系统声明直角"的未知值，不能当真实直角
        assertEquals(130.dp, screenCornerRadiusDp(31, 0, 3f, "130"))
        assertEquals(130.dp, screenCornerRadiusDp(31, null, 3f, "130"))
        assertEquals(40.dp, screenCornerRadiusDp(31, 120, 3f, "130"))
    }
    @Test fun zeroFromSystemNeedsManualCalibration() {
        assertTrue(systemCornerRadiusUnknown(31, 0))
        assertFalse(systemCornerRadiusUnknown(31, null))
        assertFalse(systemCornerRadiusUnknown(31, 120))
        assertFalse(systemCornerRadiusUnknown(30, 0))
    }
    @Test fun asymmetricCornersUseMaximumAndAbsentCornersAreZero() {
        assertEquals(160, maximumScreenCornerRadius(listOf(null, 120, 156, 160)))
        assertEquals(0, maximumScreenCornerRadius(listOf(null, null, null, null)))
        assertEquals(0, maximumScreenCornerRadius(listOf(0, 0, 0, 0)))
    }
    @Test fun legacyManualValuesRemainReactiveAndZeroIsValid() {
        assertEquals(20.dp, screenCornerRadiusDp(30, null, 3f, "20"))
        assertEquals(70.dp, screenCornerRadiusDp(30, null, 3f, "70"))
        assertEquals(0.dp, screenCornerRadiusDp(30, null, 3f, "0"))
        assertEquals(30.dp, screenCornerRadiusDp(30, null, 3f, "invalid"))
    }
}
