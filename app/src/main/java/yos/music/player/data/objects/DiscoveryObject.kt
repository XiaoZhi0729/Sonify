package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import yos.music.player.data.repositories.KugouNewAlbum
import yos.music.player.data.repositories.KugouNewSong
import yos.music.player.data.repositories.KugouRank
import yos.music.player.data.repositories.KugouRecommendPlaylist

/**
 * 新发现一级 Tab 的页面级状态 holder（SearchObject 同款模式）。
 *
 * 切换一级 Tab 会销毁页面 composition，本地 remember 状态丢失；
 * 排行榜/新歌/新专辑/精选歌单列表与状态放 object，切 Tab 返回后原样恢复，不重新请求接口。
 */
@Stable
object DiscoveryObject {
    @Stable
    val rankList = mutableStateOf<List<KugouRank>>(emptyList())

    @Stable
    val newSongs = mutableStateOf<List<KugouNewSong>>(emptyList())

    @Stable
    val newAlbums = mutableStateOf<List<KugouNewAlbum>>(emptyList())

    @Stable
    val recommendPlaylists = mutableStateOf<List<KugouRecommendPlaylist>>(emptyList())

    @Stable
    val everydayRecommendSongs = mutableStateOf<List<KugouNewSong>>(emptyList())

    /** Successful detail totals keyed by global collection id; values may legitimately be zero. */
    @Stable
    val recommendPlaylistSongCounts = mutableStateMapOf<String, Int>()

    /** IDs whose detail count request is currently in flight. */
    @Stable
    val recommendPlaylistSongCountRequests = mutableStateMapOf<String, Boolean>()

    // "idle" | "loading" | "empty" | "ok" | "error:<msg>"
    @Stable
    val status = mutableStateOf("idle")

    @Stable
    val newSongsStatus = mutableStateOf("idle")

    @Stable
    val newAlbumsStatus = mutableStateOf("idle")

    @Stable
    val recommendPlaylistsStatus = mutableStateOf("idle")

    @Stable
    val everydayRecommendSongsStatus = mutableStateOf("idle")
}
