package yos.music.player.ui.navigation

import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ArtistPageAppearanceTest {
    @Test
    fun retainedPagesKeepIndependentColors() {
        val home = mutableStateOf(Color.Red)
        val library = mutableStateOf(Color.Blue)
        ArtistPageAppearance.register("test-home", home)
        ArtistPageAppearance.register("test-library", library)
        try {
            assertEquals(Color.Red, ArtistPageAppearance.colorFor("test-home"))
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-library"))
            home.value = Color.Green
            assertEquals(Color.Green, ArtistPageAppearance.colorFor("test-home"))
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-library"))
            assertNull(ArtistPageAppearance.colorFor("test-unregistered"))
        } finally {
            ArtistPageAppearance.unregister("test-home", home)
            ArtistPageAppearance.unregister("test-library", library)
        }
    }

    @Test
    fun departingCompositionCannotRemoveNewRegistration() {
        val old = mutableStateOf(Color.Red)
        val replacement = mutableStateOf(Color.Blue)
        ArtistPageAppearance.register("test-replaced", old)
        ArtistPageAppearance.register("test-replaced", replacement)
        try {
            ArtistPageAppearance.unregister("test-replaced", old)
            assertEquals(Color.Blue, ArtistPageAppearance.colorFor("test-replaced"))
        } finally {
            ArtistPageAppearance.unregister("test-replaced", replacement)
        }
        assertNull(ArtistPageAppearance.colorFor("test-replaced"))
    }
}
