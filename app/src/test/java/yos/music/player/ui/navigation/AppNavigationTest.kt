package yos.music.player.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import yos.music.player.ui.UI

class AppNavigationTest {
    @Test
    fun mapsTabIndexesToHouseRoots() {
        assertEquals(HouseId.Home, houseForTabIndex(0))
        assertEquals(HouseId.Library, houseForTabIndex(1))
        assertEquals(HouseId.Search, houseForTabIndex(2))
        assertNull(houseForTabIndex(-1))
        assertNull(houseForTabIndex(3))
    }

    @Test
    fun mapsOnlyHouseRootsBackToHouses() {
        assertEquals(HouseId.Home, houseForRootRoute(UI.HomePage))
        assertEquals(HouseId.Library, houseForRootRoute(UI.Library))
        assertEquals(HouseId.Search, houseForRootRoute(UI.Search))
        assertNull(houseForRootRoute(UI.NormalMusic))
        assertNull(houseForRootRoute(UI.Settings.Main))
        assertNull(houseForRootRoute(null))
    }

    @Test
    fun playlistSelectionRoundTripsEncodedRoute() {
        val original = PlaylistSelection(
            source = PlaylistSelection.Source.Recommend,
            id = "gc/42?x=1",
            name = "A&B / 夜歌",
            cover = "https://img.example/a?size=400&x=1",
            songCount = 12
        )

        assertEquals(original, PlaylistSelection.fromRoute(original.toRoute()))
    }

    @Test
    fun playlistSelectionRoundTripsUnknownCount() {
        // songCount 已改为非空 Int（缺失时按 0），测试跟现行业契约
        val original = PlaylistSelection(
            source = PlaylistSelection.Source.Recommend,
            id = "gc-unknown",
            name = "Unknown",
            cover = "",
            songCount = 0
        )

        assertEquals(original, PlaylistSelection.fromRoute(original.toRoute()))
    }

    @Test
    fun playlistSelectionAcceptsLegacyRouteWithoutCount() {
        assertEquals(
            0,
            PlaylistSelection.fromRoute(
                "OnlinePlaylistDetail?source=Recommend&playlistId=gc-1&name=Unknown&cover="
            )?.songCount
        )
    }

    @Test
    fun playlistSelectionRejectsMissingIdentity() {
        assertNull(PlaylistSelection.fromRoute("OnlinePlaylistDetail?source=User"))
    }
}
