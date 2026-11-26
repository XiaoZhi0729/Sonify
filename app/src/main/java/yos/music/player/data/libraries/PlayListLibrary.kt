package yos.music.player.data.libraries

import android.net.Uri
import android.os.Parcelable
import androidx.compose.runtime.Stable
import com.funny.data_saver.core.mutableDataSaverListStateOf
import kotlinx.parcelize.Parcelize
import yos.music.player.data.PlayListSaver
import java.util.UUID

@Stable
@Parcelize
data class PlayList(
    val listID: String,
    val name: String,
    val songDataList: List<Uri>
) : Parcelable

@Stable
object PlayListLibrary {

    var playList by mutableDataSaverListStateOf(
        dataSaverInterface = PlayListSaver,
        key = "yos_play_list",
        initialValue = listOf<PlayList>()
    )
        private set

    fun PlayList.addMusic(music: YosMediaItem) {
        val targetUri = music.uri ?: return
        playList = playList.map { item ->
            if (item.listID == listID) {
                item.copy(songDataList = item.songDataList + targetUri)
            } else {
                item
            }
        }
    }

    fun PlayList.removeMusic(music: YosMediaItem) {
        removeMusicById(listID, music)
    }

    /**
     * 向指定本地歌单追加一首歌；若歌单不存在、歌曲缺少 URI 或已在歌单中则返回 false。
     */
    fun addMusicIfAbsent(playlistId: String, music: YosMediaItem): Boolean {
        val targetUri = music.uri ?: return false
        val target = playList.firstOrNull { it.listID == playlistId } ?: return false
        if (targetUri in target.songDataList) return false
        playList = playList.map { item ->
            if (item.listID == playlistId) {
                item.copy(songDataList = item.songDataList + targetUri)
            } else {
                item
            }
        }
        return true
    }

    /**
     * 按歌单 ID 从本地歌单移除指定歌曲（保持歌单原有排序不变）。
     */
    fun removeMusicById(playlistId: String, music: YosMediaItem) {
        val targetUri = music.uri ?: return
        playList = playList.map { item ->
            if (item.listID == playlistId) {
                val updated = item.songDataList.toMutableList().apply {
                    remove(targetUri)
                }
                item.copy(songDataList = updated)
            } else {
                item
            }
        }
    }

    fun PlayList.rename(name: String) {
        playList = playList.map { item ->
            if (item.listID == listID) item.copy(name = name) else item
        }
    }

    fun create(name: String) {
        createAndReturn(name)
    }

    /**
     * 创建本地歌单并返回新歌单；名称为空白或重名时返回 null。
     */
    fun createAndReturn(name: String): PlayList? {
        val trimmed = name.trim()
        if (trimmed.isEmpty() || playList.any { it.name == trimmed }) return null
        val created = PlayList(UUID.randomUUID().toString(), trimmed, listOf())
        playList += created
        return created
    }

    fun remove(list: PlayList) {
        playList -= list
    }
}

@Stable
object FavPlayListLibrary {
    var favPlayList by mutableDataSaverListStateOf(
        dataSaverInterface = PlayListSaver,
        key = "yos_fav_play_list",
        initialValue = listOf<YosMediaItem>()
    )
        private set

    fun addMusic(music: YosMediaItem) {
        if (!favPlayList.any { it.uri == music.uri }) {
            favPlayList += music
        }
    }

    fun removeMusic(music: YosMediaItem) {
        val targetUri = music.uri
        favPlayList = if (targetUri != null) {
            favPlayList.filterNot { it.uri == targetUri }
        } else {
            favPlayList - music
        }
    }

    fun isFavorite(music: YosMediaItem): Boolean = favPlayList.any { it.uri == music.uri }
}