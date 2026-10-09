package yos.music.player.ui.widgets.basic

import android.content.Context
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.data.repositories.PlayState
import yos.music.player.data.repositories.SongAvailability
import yos.music.player.data.repositories.SongPlayabilityStore

/**
 * 在线歌曲的酷狗 hash；本地歌曲返回 null（本地永不置灰）。
 * 复用播放链既有约定：在线 mediaId 形如 `kugou-online-<hash>`。
 */
fun YosMediaItem.kugouHash(): String? {
    val id = mediaId ?: return null
    if (!id.startsWith("kugou-online-")) return null
    return id.removePrefix("kugou-online-").lowercase().takeIf { it.isNotEmpty() }
}

/** 是否已被判定不可播（供行组件统一取用）。 */
val SongAvailability.isBlocked: Boolean get() = state == PlayState.BLOCKED

/**
 * 订阅本曲可播状态，并在首次可见时触发一次后台探测。
 * 读取 [SongPlayabilityStore.stateOf] 会订阅该 hash，状态变化时仅本行重组。
 */
@Composable
fun rememberSongAvailability(music: YosMediaItem): SongAvailability {
    val hash = music.kugouHash()
    LaunchedEffect(hash) { SongPlayabilityStore.ensureChecked(hash) }
    return SongPlayabilityStore.stateOf(hash)
}

/**
 * 点击置灰歌曲的统一提示：与现有“播放失败，已跳到下一首”提示同风格，
 * 只弹一句原因，不阻塞、不进播放、不切歌。
 */
fun showSongUnavailableToast(context: Context, music: YosMediaItem, reason: String?) {
    val r = reason?.takeIf { it.isNotBlank() } ?: "无法播放"
    val title = music.title?.takeIf { it.isNotEmpty() }
    val msg = if (title != null) "《$title》$r" else r
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
