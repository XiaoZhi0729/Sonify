package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import yos.music.player.data.repositories.KugouNewAlbum

/**
 * 在线专辑详情页间传参（沿用 RankObject / OnlinePlaylistObject 的内存 holder 模式）：
 * 新发现页选中新专辑后写入，在线专辑详情页读取。
 */
@Stable
object OnlineAlbumObject {
    @Stable
    private val selectedAlbum = mutableStateOf<KugouNewAlbum?>(null)

    fun setSelected(album: KugouNewAlbum) {
        selectedAlbum.value = album
    }

    fun getSelected(): KugouNewAlbum? {
        return selectedAlbum.value
    }
}
