package yos.music.player.ui.navigation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import yos.music.player.ui.UI

class AppNavigationTest {
    @Test
    fun mapsTabIndexesToHouseRoots() {
        // 底栏四 Tab 顺序（MainActivity navItems 同源）：主页 / 喜爱 / 资料库 / 搜索。
        // Favorites 于 c3fce40 插入到 index 1，Library/Search 相应后移。
        assertEquals(HouseId.Home, houseForTabIndex(0))
        assertEquals(HouseId.Favorites, houseForTabIndex(1))
        assertEquals(HouseId.Library, houseForTabIndex(2))
        assertEquals(HouseId.Search, houseForTabIndex(3))
        assertNull(houseForTabIndex(-1))
        assertNull(houseForTabIndex(4))
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
    fun playlistSelectionPreservesExplicitReadonlyMetadata() {
        val original = PlaylistSelection(
            source = PlaylistSelection.Source.User,
            id = "account-list-42",
            name = "A + B & 夜歌",
            cover = "",
            songCount = 7,
            intro = "收藏 / 简介?",
            sourceGid = "collection_3_123_456_0",
            canEditTracks = false,
            ownerUserid = "123"
        )
        assertEquals(original, PlaylistSelection.fromRoute(original.toRoute()))
        assertEquals(false, PlaylistSelection.fromRoute(original.toRoute())?.canEditTracks)
    }

    @Test
    fun playlistSelectionKeepsUnknownPermissionsForLegacyRoutes() {
        val parsed = PlaylistSelection.fromRoute(
            "OnlinePlaylistDetail?source=User&playlistId=42&name=Legacy&cover="
        )
        assertNull(parsed?.sourceGid)
        assertNull(parsed?.canEditTracks)
        assertNull(parsed?.ownerUserid)
        assertEquals(parsed, PlaylistSelection.fromRoute(parsed?.toRoute()))
    }

    @Test
    fun playlistSelectionPreservesEditableMetadata() {
        val original = PlaylistSelection(
            source = PlaylistSelection.Source.User,
            id = "42",
            name = "Own",
            cover = "",
            songCount = 0,
            canEditTracks = true,
            ownerUserid = "789"
        )
        assertEquals(original, PlaylistSelection.fromRoute(original.toRoute()))
    }

    @Test
    fun playlistSelectionRejectsMissingIdentity() {
        assertNull(PlaylistSelection.fromRoute("OnlinePlaylistDetail?source=User"))
    }
}
