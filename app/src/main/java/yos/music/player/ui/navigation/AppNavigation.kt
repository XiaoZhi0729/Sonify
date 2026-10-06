package yos.music.player.ui.navigation

import android.os.Bundle
import androidx.compose.runtime.Immutable
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.navArgument
import yos.music.player.ui.UI

@Immutable
data class PlaylistSelection(
    val source: Source,
    val id: String,
    val name: String,
    val cover: String,
    val songCount: Int,
    /** 歌单简介：仅精选歌单列表接口携带；null/空表示无简介（详情页不显示） */
    val intro: String? = null
) {
    enum class Source { User, Recommend }

    fun toRoute(): String = UI.onlinePlaylistDetailRoute(
        source = source.name,
        playlistId = id,
        name = name,
        cover = cover,
        count = songCount,
        intro = intro
    )

    fun toArguments(): Bundle = Bundle().apply {
        putString(UI.OnlinePlaylistSourceArg, source.name)
        putString(UI.OnlinePlaylistIdArg, id)
        putString(UI.OnlinePlaylistNameArg, name)
        putString(UI.OnlinePlaylistCoverArg, cover)
        putInt(UI.OnlinePlaylistCountArg, songCount)
        intro?.let { putString(UI.OnlinePlaylistIntroArg, it) }
    }

    companion object {
        fun fromRoute(route: String?): PlaylistSelection? {
            val query = route?.substringAfter('?', missingDelimiterValue = "") ?: return null
            if (query.isEmpty()) return null
            val values = query.split('&').mapNotNull { part ->
                val key = part.substringBefore('=', missingDelimiterValue = "")
                val value = part.substringAfter('=', missingDelimiterValue = "")
                if (key.isEmpty()) null else key to java.net.URLDecoder.decode(value, "UTF-8")
            }.toMap()
            return fromValues(
                source = values[UI.OnlinePlaylistSourceArg],
                id = values[UI.OnlinePlaylistIdArg],
                name = values[UI.OnlinePlaylistNameArg],
                cover = values[UI.OnlinePlaylistCoverArg],
                count = values[UI.OnlinePlaylistCountArg]?.toIntOrNull(),
                intro = values[UI.OnlinePlaylistIntroArg]
            )
        }

        fun fromArguments(arguments: Bundle?): PlaylistSelection? = arguments?.let {
            fromValues(
                source = it.getString(UI.OnlinePlaylistSourceArg),
                id = it.getString(UI.OnlinePlaylistIdArg),
                name = it.getString(UI.OnlinePlaylistNameArg),
                cover = it.getString(UI.OnlinePlaylistCoverArg),
                count = it.getInt(UI.OnlinePlaylistCountArg, 0),
                intro = it.getString(UI.OnlinePlaylistIntroArg)
            )
        }

        private fun fromValues(
            source: String?,
            id: String?,
            name: String?,
            cover: String?,
            count: Int?,
            intro: String? = null
        ): PlaylistSelection? {
            val parsedSource = source?.let { runCatching { Source.valueOf(it) }.getOrNull() }
            val parsedId = id.orEmpty()
            if (parsedSource == null || parsedId.isEmpty()) return null
            return PlaylistSelection(
                source = parsedSource,
                id = parsedId,
                name = name.orEmpty(),
                cover = cover.orEmpty(),
                songCount = count ?: 0,
                intro = intro?.takeIf { it.isNotBlank() }
            )
        }
    }
}

fun playlistArguments() = listOf(
    navArgument(UI.OnlinePlaylistSourceArg) { type = NavType.StringType },
    navArgument(UI.OnlinePlaylistIdArg) { type = NavType.StringType },
    navArgument(UI.OnlinePlaylistNameArg) { type = NavType.StringType },
    navArgument(UI.OnlinePlaylistCoverArg) { type = NavType.StringType },
    navArgument(UI.OnlinePlaylistCountArg) { type = NavType.IntType },
    // intro 必须声明为可选：toRoute() 在 intro 为 null（User 歌单）时不拼该参数，
    // 若视为必填，NavDeepLink 匹配会因"缺必填参数"失败并抛 destination cannot be found
    navArgument(UI.OnlinePlaylistIntroArg) {
        type = NavType.StringType
        nullable = true
        defaultValue = null
    }
)

fun NavBackStackEntry.playlistSelection(): PlaylistSelection? =
    PlaylistSelection.fromArguments(arguments)

/** 歌手详情路由参数：artistName 必带；artistId 可空（空 = 进页后按名字搜索解析）。 */
fun artistDetailArguments() = listOf(
    navArgument(UI.ArtistDetailIdArg) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
    },
    navArgument(UI.ArtistDetailNameArg) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
    }
)

/** 在线专辑详情路由参数：albumId 定位专辑；sourceArtistId 为来源歌手（可空 = 非艺人来源）。 */
fun onlineAlbumArguments() = listOf(
    navArgument(UI.OnlineAlbumIdArg) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
    },
    navArgument(UI.OnlineAlbumSourceArtistArg) {
        type = NavType.StringType
        nullable = true
        defaultValue = ""
    }
)

@Immutable
enum class HouseId(
    val rootRoute: String,
    val tabIndex: Int
) {
    Home(UI.HomePage, 0),
    Library(UI.Library, 1),
    Search(UI.Search, 2)
}

fun houseForTabIndex(index: Int): HouseId? = HouseId.entries.firstOrNull { it.tabIndex == index }

fun houseForRootRoute(route: String?): HouseId? =
    HouseId.entries.firstOrNull { it.rootRoute == route }

class AppNavigator(
    private val switchHouseAction: (HouseId) -> Unit,
    private val openSettingsAction: (HouseId) -> Unit,
    private val openLibrarySongsAction: () -> Unit
) {
    fun switchHouse(house: HouseId) = switchHouseAction(house)
    fun openSettings(house: HouseId) = openSettingsAction(house)
    fun openLibrarySongs() = openLibrarySongsAction()
}
