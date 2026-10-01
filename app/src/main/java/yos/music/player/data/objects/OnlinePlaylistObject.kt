package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import yos.music.player.data.repositories.KugouPlaylist
import yos.music.player.data.repositories.KugouRecommendPlaylist

/**
 * 在线歌单页间传参（沿用 LibraryObject 的内存 holder 模式）：
 * 歌单列表页选中歌单后写入，歌单详情页读取。
 *
 * 支持两种来源，共用同一个 [OnlinePlaylistDetail] 详情页：
 *  - USER_PLAYLIST：用户在线歌单（Library 入口），取歌走 /playlist/track/all/new?listid=
 *  - RECOMMEND_PLAYLIST：精选歌单（Discovery 入口），取歌走 /playlist/track/all?global_collection_id=
 */
@Stable
object OnlinePlaylistObject {
    enum class Source { USER_PLAYLIST, RECOMMEND_PLAYLIST }

    @Stable
    private val source = mutableStateOf(Source.USER_PLAYLIST)

    @Stable
    private val selectedPlaylist = mutableStateOf<KugouPlaylist?>(null)

    @Stable
    private val recommendPlaylist = mutableStateOf<KugouRecommendPlaylist?>(null)

    fun getSource(): Source = source.value

    // ---------- 用户在线歌单（Library 在线歌单入口，保持原方法签名不破坏现有调用） ----------
    fun setSelected(playlist: KugouPlaylist) {
        source.value = Source.USER_PLAYLIST
        selectedPlaylist.value = playlist
        recommendPlaylist.value = null
    }

    fun getSelected(): KugouPlaylist? = selectedPlaylist.value

    // ---------- 精选歌单（Discovery 精选歌单入口） ----------
    fun setRecommendSelected(playlist: KugouRecommendPlaylist) {
        source.value = Source.RECOMMEND_PLAYLIST
        recommendPlaylist.value = playlist
        selectedPlaylist.value = null
    }

    fun getRecommendSelected(): KugouRecommendPlaylist? = recommendPlaylist.value
}
