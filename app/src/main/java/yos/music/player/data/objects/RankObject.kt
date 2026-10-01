package yos.music.player.data.objects

import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateOf
import yos.music.player.data.repositories.KugouRank

/**
 * 榜单详情页间传参（沿用 OnlinePlaylistObject 的内存 holder 模式）：
 * 新发现页选中榜单后写入，榜单详情页读取。
 */
@Stable
object RankObject {
    @Stable
    private val selectedRank = mutableStateOf<KugouRank?>(null)

    fun setSelected(rank: KugouRank) {
        selectedRank.value = rank
    }

    fun getSelected(): KugouRank? {
        return selectedRank.value
    }
}
