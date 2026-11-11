package yos.music.player.ui.pages.discovery

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.blur
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test

/** 不依赖 FM 接口或封面加载，直接验证采样源 → 材质 → 内容的真实像素链。 */
class PersonalFmGlassStageTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun glassSamplesCoversBelowItsSurfaceAndText() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
        compose.setContent {
            val backdrop = rememberLayerBackdrop()
            val effects = remember<BackdropEffectScope.() -> Unit> {
                { blur(12.dp.toPx()) }
            }
            val surface = remember<DrawScope.() -> Unit> {
                { drawRect(Color.White.copy(alpha = 0.40f)) }
            }
            PersonalFmGlassStage(
                backdrop = backdrop,
                glassOn = true,
                shape = RoundedCornerShape(14.dp),
                glassEffects = effects,
                frostedEffects = effects,
                glassSurface = surface,
                frostedSurface = surface,
                modifier = Modifier.size(134.dp),
                glassModifier = Modifier.fillMaxSize().testTag("glass"),
                backdropContent = {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(
                                androidx.compose.ui.graphics.Brush.linearGradient(
                                    0f to Color.Red,
                                    0.49f to Color.Red,
                                    0.51f to Color.Blue,
                                    1f to Color.Blue,
                                    start = Offset.Zero,
                                    end = Offset.Infinite
                                )
                            )
                    )
                }
            ) {
                Text("电台", color = Color.Black)
            }
        }

        val image = compose.onNodeWithTag("glass").captureToImage()
        val center = image.toPixelMap()[image.width / 2, image.height / 2]
        assertTrue(
            "玻璃必须采样背后红蓝封面，而不是纯色或另一张图：$center",
            center.red > 0.15f && center.blue > 0.15f
        )
    }
}
