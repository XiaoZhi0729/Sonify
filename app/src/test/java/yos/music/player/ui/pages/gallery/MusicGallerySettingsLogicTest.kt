package yos.music.player.ui.pages.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.widgets.mochi.MochiSheetDetent

class MusicGallerySettingsLogicTest {

    // ---------- 背景模式解析与音乐联动 ----------

    @Test
    fun followPlaybackMapsStaticDriftAndMesh() {
        assertEquals(
            SettingsLibrary.GALLERY_BG_STATIC,
            resolveGalleryBackgroundMode(
                followPlayback = true,
                customGalleryMode = SettingsLibrary.GALLERY_BG_MESH,
                playbackEffectEnabled = false,
                playbackMode = SettingsLibrary.BACKGROUND_MODE_MESH,
                meshSupported = true,
            ),
        )
        assertEquals(
            SettingsLibrary.GALLERY_BG_DRIFT,
            resolveGalleryBackgroundMode(
                followPlayback = true,
                customGalleryMode = SettingsLibrary.GALLERY_BG_STATIC,
                playbackEffectEnabled = true,
                playbackMode = SettingsLibrary.BACKGROUND_MODE_DRIFT,
                meshSupported = true,
            ),
        )
        assertEquals(
            SettingsLibrary.GALLERY_BG_MESH,
            resolveGalleryBackgroundMode(
                followPlayback = true,
                customGalleryMode = SettingsLibrary.GALLERY_BG_STATIC,
                playbackEffectEnabled = true,
                playbackMode = SettingsLibrary.BACKGROUND_MODE_MESH,
                meshSupported = true,
            ),
        )
    }

    @Test
    fun customModeIgnoresPlaybackSettings() {
        for (mode in listOf(
            SettingsLibrary.GALLERY_BG_STATIC,
            SettingsLibrary.GALLERY_BG_DRIFT,
            SettingsLibrary.GALLERY_BG_MESH,
        )) {
            assertEquals(
                mode,
                resolveGalleryBackgroundMode(
                    followPlayback = false,
                    customGalleryMode = mode,
                    playbackEffectEnabled = false,
                    playbackMode = SettingsLibrary.BACKGROUND_MODE_DRIFT,
                    meshSupported = true,
                ),
            )
        }
    }

    @Test
    fun unsupportedMeshFallsBackToDriftInBothFollowAndCustomModes() {
        assertEquals(
            SettingsLibrary.GALLERY_BG_DRIFT,
            resolveGalleryBackgroundMode(
                followPlayback = true,
                customGalleryMode = SettingsLibrary.GALLERY_BG_STATIC,
                playbackEffectEnabled = true,
                playbackMode = SettingsLibrary.BACKGROUND_MODE_MESH,
                meshSupported = false,
            ),
        )
        assertEquals(
            SettingsLibrary.GALLERY_BG_DRIFT,
            resolveGalleryBackgroundMode(
                followPlayback = false,
                customGalleryMode = SettingsLibrary.GALLERY_BG_MESH,
                playbackEffectEnabled = true,
                playbackMode = SettingsLibrary.BACKGROUND_MODE_MESH,
                meshSupported = false,
            ),
        )
    }

    @Test
    fun outOfRangeCustomModeClampsSafely() {
        assertEquals(
            SettingsLibrary.GALLERY_BG_STATIC,
            resolveGalleryBackgroundMode(
                followPlayback = false,
                customGalleryMode = -3,
                playbackEffectEnabled = true,
                playbackMode = SettingsLibrary.BACKGROUND_MODE_DRIFT,
                meshSupported = true,
            ),
        )
        assertEquals(
            SettingsLibrary.GALLERY_BG_MESH,
            resolveGalleryBackgroundMode(
                followPlayback = false,
                customGalleryMode = 99,
                playbackEffectEnabled = false,
                playbackMode = SettingsLibrary.BACKGROUND_MODE_DRIFT,
                meshSupported = true,
            ),
        )
    }

    // ---------- 半屏 / 全屏面板裁定 ----------

    @Test
    fun compactContentOnPortraitPhoneAndTabletUsesMediumSheet() {
        for (windowHeightDp in listOf(760f, 800f, 915f, 1100f)) {
            val spec = resolveGallerySettingsSheetSpec(
                contentHeightEstimateDp = GallerySettingsEstimatedHeightDp,
                windowHeightDp = windowHeightDp,
            )
            assertEquals("window=$windowHeightDp 应裁定为半屏", listOf(MochiSheetDetent.Medium), spec.detents)
            assertEquals(MochiSheetDetent.Medium, spec.initial)
            assertTrue(
                "mediumFraction=${spec.mediumFraction} 应落在 [0.46f, 0.56f]",
                spec.mediumFraction in 0.46f..0.56f,
            )
        }
    }

    @Test
    fun shortOrSplitWindowPromotesToLargeSheet() {
        for (windowHeightDp in listOf(400f, 520f)) {
            val spec = resolveGallerySettingsSheetSpec(
                contentHeightEstimateDp = GallerySettingsEstimatedHeightDp,
                windowHeightDp = windowHeightDp,
            )
            assertEquals("window=$windowHeightDp 应裁定为全屏", listOf(MochiSheetDetent.Large), spec.detents)
            assertEquals(MochiSheetDetent.Large, spec.initial)
        }
    }

    @Test
    fun longContentPromotesToLargeSheet() {
        val spec = resolveGallerySettingsSheetSpec(
            contentHeightEstimateDp = 620f,
            windowHeightDp = 900f,
        )
        assertEquals(listOf(MochiSheetDetent.Large), spec.detents)
        assertEquals(MochiSheetDetent.Large, spec.initial)
    }
}
