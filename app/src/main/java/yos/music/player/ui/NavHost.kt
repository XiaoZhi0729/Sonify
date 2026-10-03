package yos.music.player.ui

import androidx.compose.runtime.Stable
import androidx.navigation.NavController

/*object UI {
    const val HomePage = "HomePage"

    const val Settings = "Settings"

    object Settings {
        const val LyricGetter = "LyricGetter"
    }
}*/

@Stable
interface UI {
    companion object {
        // 一级 Tab（三 Tab 导航）：主页 / 资料库 / 搜索
        const val HomePage = "HomePage"
        const val Library = "Library"
        const val Search = "Search"

        const val NormalMusic = "NormalMusic"
        const val PlayLists = "PlayLists"
        const val LocalArtists = "LocalArtists"
        const val LocalAlbums = "LocalAlbums"

        // 在线歌单（酷狗 Rust 链路，第二阶段；入口在 Library）
        const val OnlinePlaylists = "OnlinePlaylists"
        const val OnlinePlaylistDetail = "OnlinePlaylistDetail"
        const val OnlinePlaylistSourceArg = "source"
        const val OnlinePlaylistIdArg = "playlistId"
        const val OnlinePlaylistNameArg = "name"
        const val OnlinePlaylistCoverArg = "cover"
        const val OnlinePlaylistCountArg = "count"
        const val OnlinePlaylistIntroArg = "intro"
        const val OnlinePlaylistDetailPattern =
            "$OnlinePlaylistDetail?$OnlinePlaylistSourceArg={$OnlinePlaylistSourceArg}" +
                    "&$OnlinePlaylistIdArg={$OnlinePlaylistIdArg}" +
                    "&$OnlinePlaylistNameArg={$OnlinePlaylistNameArg}" +
                    "&$OnlinePlaylistCoverArg={$OnlinePlaylistCoverArg}" +
                    "&$OnlinePlaylistCountArg={$OnlinePlaylistCountArg}" +
                    "&$OnlinePlaylistIntroArg={$OnlinePlaylistIntroArg}"

        fun onlinePlaylistDetailRoute(
            source: String,
            playlistId: String,
            name: String,
            cover: String,
            count: Int?,
            intro: String? = null
        ): String = buildString {
            append(OnlinePlaylistDetail)
            append("?")
            append(OnlinePlaylistSourceArg).append("=").append(java.net.URLEncoder.encode(source, "UTF-8"))
            append("&").append(OnlinePlaylistIdArg).append("=").append(java.net.URLEncoder.encode(playlistId, "UTF-8"))
            append("&").append(OnlinePlaylistNameArg).append("=").append(java.net.URLEncoder.encode(name, "UTF-8"))
            append("&").append(OnlinePlaylistCoverArg).append("=").append(java.net.URLEncoder.encode(cover, "UTF-8"))
            count?.let {
                append("&").append(OnlinePlaylistCountArg).append("=").append(it)
            }
            intro?.let {
                append("&").append(OnlinePlaylistIntroArg).append("=").append(java.net.URLEncoder.encode(it, "UTF-8"))
            }
        }

        // 榜单详情（Discovery 排行榜；入口在主页 Tab）
        const val RankDetail = "RankDetail"

        // 排行榜大全（主页「排行榜」标题/箭头 → 查看全部）
        const val RankListDetail = "RankListDetail"

        // 新专辑大全（主页「本周新发行」标题/箭头 → 查看全部，滚动分页）
        const val NewAlbumsDetail = "NewAlbumsDetail"

        // 精选歌单大全（主页「精选歌单」标题/箭头 → 查看全部，滚动分页）
        const val RecommendPlaylistsDetail = "RecommendPlaylistsDetail"

        // 在线专辑详情（Discovery 新专辑；入口在新发现 Tab）
        const val OnlineAlbumDetail = "OnlineAlbumDetail"

        // 新歌精选完整列表（Discovery「新歌精选」查看全部；入口在主页 Tab）
        const val NewSongsDetail = "NewSongsDetail"

        // 最近播放完整列表（主页「最近播放」查看全部；入口在主页 Tab）
        const val RecentPlayedDetail = "RecentPlayedDetail"

        // 每日推荐（主页顶部入口卡；登录可见，未登录走通用推荐）
        const val EverydayRecommendDetail = "EverydayRecommendDetail"

        // 私人FM（主页顶部入口卡；需登录）
        const val PersonalFmDetail = "PersonalFmDetail"

        // 歌手详情（本地歌手列表点击 / 在线场景入口；artistId 可空，为空时按 artistName 搜索解析）
        const val ArtistDetail = "ArtistDetail"
        const val ArtistDetailIdArg = "artistId"
        const val ArtistDetailNameArg = "artistName"
        const val ArtistDetailPattern =
            "$ArtistDetail?$ArtistDetailIdArg={$ArtistDetailIdArg}" +
                    "&$ArtistDetailNameArg={$ArtistDetailNameArg}"

        fun artistDetailRoute(artistId: String?, artistName: String): String = buildString {
            append(ArtistDetail)
            append("?")
            append(ArtistDetailIdArg).append("=")
                .append(java.net.URLEncoder.encode(artistId ?: "", "UTF-8"))
            append("&").append(ArtistDetailNameArg).append("=")
                .append(java.net.URLEncoder.encode(artistName, "UTF-8"))
        }

        const val AlbumInfo = "AlbumInfo"
    }

    @Stable
    interface Settings {
        companion object {
            const val Main = "Main"
            const val LibraryOverview = "LibraryOverview"

            const val LyricGetter = "LyricGetter"
            const val ExoplayerSetting = "ExoplayerSetting"
            const val OnlineQualitySetting = "OnlineQualitySetting"
            const val About = "About"
            const val Acknowledgements = "Acknowledgements"
            const val MediaCodec = "MediaCodec"

            const val LyricSetting = "LyricSetting"
            const val UserInterfaceSetting = "UserInterfaceSetting"
            const val NotificationSetting = "NotificationSetting"
            const val KugouLogin = "KugouLogin"
            const val KugouVipSignIn = "KugouVipSignIn"
        }
    }
}


fun NavController.toUI(route: String, data: String? = null) {
    if (data.isNullOrEmpty()) {
        this.navigate(route)
    } else {
        this.navigate(getNavUri(route, data))
    }
}

fun getNavUri(route: String, data: String? = null): String {
    return if (data == null) {
        route
    } else {
        "$route/$data"
    }
}