package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import yos.music.player.data.repositories.KugouNewAlbum

/**
 * 在线专辑详情页间传参。
 *
 * 关键：专辑详情页会**多层堆叠**（歌手→专辑→另一歌手→另一专辑…），若只用一个
 * 全局「当前选中」槽，后进的会覆盖先进的，返回时所有详情页都变成最新那张
 * （用户实测的「返回后专辑全变成刚点的那个」）。故这里按 albumId 建缓存，
 * 详情页从自己路由里的 albumId 取，互不干扰；[selectedAlbum] 仅作无 id 时的兼容兜底。
 */
@Stable
object OnlineAlbumObject {
    @Stable
    private val selectedAlbum = mutableStateOf<KugouNewAlbum?>(null)
    private val byId = mutableMapOf<String, KugouNewAlbum>()

    /**
     * 共享封面转场的来源 key（按 albumId 记）：入口点击时写入，专辑详情页读取它以与
     * 「这次点的那张源封面」精确配对。同一张专辑可能同时出现在多处（艺人页推荐卡 /
     * 专辑横排 / 主页新专辑卡），共享元素要求 key 在同屏内唯一，故各来源用不同 key；
     * 若两处复用同一个 key，只要其中一处仍在屏就会把 key 占住，点另一处也会被吸到它上面。
     *
     * 必须按 albumId 存而非全局单值：专辑详情会多层堆叠，全局单值会被后进的覆盖，
     * 导致先前的详情页在返回转场时与错误的源封面配对。
     */
    private val coverKeys = mutableMapOf<String, String>()

    fun setSharedCoverKey(albumId: String, key: String) {
        if (albumId.isBlank()) return
        synchronized(coverKeys) {
            coverKeys.remove(albumId)
            coverKeys[albumId] = key
            while (coverKeys.size > 64) coverKeys.remove(coverKeys.keys.firstOrNull { it != albumId })
        }
    }

    fun coverKeyFor(albumId: String?): String? =
        albumId?.let { synchronized(coverKeys) { coverKeys[it] } }

    fun setSelected(album: KugouNewAlbum) {
        selectedAlbum.value = album
        if (album.albumId.isNotBlank()) {
            synchronized(byId) {
                byId.remove(album.albumId)
                byId[album.albumId] = album
                while (byId.size > 64) {
                    byId.remove(byId.keys.firstOrNull { it != album.albumId })
                }
            }
        }
    }

    fun getSelected(): KugouNewAlbum? = selectedAlbum.value

    /** 按 albumId 取详情页所需的专辑快照（无则回退当前选中）。 */
    fun album(albumId: String?): KugouNewAlbum? {
        if (albumId.isNullOrBlank()) return selectedAlbum.value
        return synchronized(byId) { byId[albumId] } ?: selectedAlbum.value
    }
}

