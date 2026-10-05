package yos.music.player.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistPageAppearanceTest {
    private fun staticTransition() = PageScrimTransition(
        progress = mutableStateOf(1f),
        transitioning = mutableStateOf(false)
    )

    @Test
    fun retainedPagesKeepIndependentColors() {
        val home = mutableStateOf(Color.Red)
        val library = mutableStateOf(Color.Blue)
        val homeRegistration = ArtistPageAppearance.register("test-home", home, staticTransition())
        val libraryRegistration = ArtistPageAppearance.register("test-library", library, staticTransition())
        try {
            assertEquals(Color.Red, ArtistPageAppearance.colorFor("test-home"))
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-library"))
            home.value = Color.Green
            assertEquals(Color.Green, ArtistPageAppearance.colorFor("test-home"))
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-library"))
            assertNull(ArtistPageAppearance.colorFor("test-unregistered"))
        } finally {
            ArtistPageAppearance.unregister("test-home", homeRegistration)
            ArtistPageAppearance.unregister("test-library", libraryRegistration)
        }
    }

    @Test
    fun departingCompositionCannotRemoveNewRegistration() {
        val old = mutableStateOf(Color.Red)
        val replacement = mutableStateOf(Color.Blue)
        val oldRegistration = ArtistPageAppearance.register("test-replaced", old, staticTransition())
        val replacementRegistration = ArtistPageAppearance.register("test-replaced", replacement, staticTransition())
        try {
            // 离场组合持旧句柄注销，不得移除同 id 的新注册（unregister 靠实例同一性判断）
            ArtistPageAppearance.unregister("test-replaced", oldRegistration)
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-replaced"))
        } finally {
            ArtistPageAppearance.unregister("test-replaced", replacementRegistration)
        }
        assertNull(ArtistPageAppearance.colorFor("test-replaced"))
    }
}
