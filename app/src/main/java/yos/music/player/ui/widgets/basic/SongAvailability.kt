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
 * 点击"已判定不可播"的行：**先复核再决定**，不拿缓存结论硬拦播放。
 *
 * 为什么必须复核：置灰结论是缓存的（内存/磁盘，最长 2 小时），而它可能来自早已不成立的
 * 情境——另一个账号、登录态失效期、旧的单档探测。直接拒绝就是"能播的歌被锁死"（真机事故：
 * 付费歌被写成 24h 置灰）。复核一次通常 ~150ms（绕开缓存直问上游，最坏 10s 截止），
 * 复核通过就正常进播放；仍然不可播才弹原因。
 */
suspend fun handleBlockedClick(context: Context, music: YosMediaItem, onPlay: () -> Unit) {
    when (val result = SongPlayabilityStore.recheck(music.kugouHash())) {
        SongPlayabilityStore.ProbeResult.Playable -> onPlay()
        is SongPlayabilityStore.ProbeResult.Blocked ->
            showSongUnavailableToast(context, music, result.reason)

        is SongPlayabilityStore.ProbeResult.Inconclusive ->
            showSongUnavailableToast(context, music, result.reason ?: "无法播放")
    }
}

/**
 * 点击置灰歌曲的统一提示：与现有"播放失败，已跳到下一首"提示同风格，
 * 只弹一句原因，不阻塞、不进播放、不切歌。
 */
fun showSongUnavailableToast(context: Context, music: YosMediaItem, reason: String?) {
    val r = reason?.takeIf { it.isNotBlank() } ?: "无法播放"
    val title = music.title?.takeIf { it.isNotEmpty() }
    val msg = if (title != null) "《$title》$r" else r
    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
}
