package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.gestures.snapping.SnapPosition
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import yos.music.player.code.MediaController
import yos.music.player.data.libraries.YosMediaItem
import yos.music.player.ui.pages.RecommendCardItem

internal fun recentSongKey(song: YosMediaItem): String =
    song.mediaId?.takeIf { it.isNotBlank() }
        ?: song.uri?.toString()?.takeIf { it.isNotBlank() }
        ?: listOf(song.title.orEmpty(), song.artists.orEmpty(), song.duration).joinToString("|")

@Composable
internal fun RecentListeningRail(
    songs: List<YosMediaItem>,
    onSongClick: (YosMediaItem, List<YosMediaItem>) -> Unit
) {
    val listState = rememberLazyListState()
    val displayedSongs = remember { mutableStateOf(songs) }
    val updates = remember { Channel<List<YosMediaItem>>(Channel.CONFLATED) }
    val followHead = remember { mutableStateOf(true) }
    val autoScrollActive = remember { mutableStateOf(false) }

    LaunchedEffect(songs) {
        updates.trySend(songs)
    }

    // 只有用户主动拖离队首时才停止跟随；自动动画过程中的中断不会杀死更新循环。
    LaunchedEffect(listState) {
        snapshotFlow {
            Triple(
                listState.isScrollInProgress,
                listState.firstVisibleItemIndex,
                listState.firstVisibleItemScrollOffset
            )
        }.collect { (scrolling, index, offset) ->
            if (scrolling && !autoScrollActive.value && (index != 0 || offset != 0)) {
                followHead.value = false
            }
        }
    }

    LaunchedEffect(Unit) {
        supervisorScope {
            for (nextSongs in updates) {
                val previousSongs = displayedSongs.value
                if (nextSongs == previousSongs) continue

                val atStart = listState.firstVisibleItemIndex == 0 &&
                    listState.firstVisibleItemScrollOffset == 0
                val plan = planRecentListeningUpdate(
                    previousKeys = previousSongs.map(::recentSongKey),
                    nextKeys = nextSongs.map(::recentSongKey),
                    wasAtStart = followHead.value || atStart
                )

                displayedSongs.value = nextSongs
                withFrameNanos { }

                when (plan) {
                    RecentListeningUpdatePlan.KeepViewport -> Unit
                    is RecentListeningUpdatePlan.RevealHead -> {
                        followHead.value = true
                        // 独立子任务承载滚动：手势/下一次滚动取消它时，主循环继续消费后续歌曲。
                        val animation = launch {
                            autoScrollActive.value = true
                            try {
                                listState.scrollToItem(plan.oldHeadIndex)
                                listState.animateScrollToItem(0)
                            } catch (_: CancellationException) {
                                // 用户接管列表时保留主循环；下一次历史更新会重新同步。
                            } catch (_: IllegalArgumentException) {
                                // 数据在快速切歌期间再次变化时，旧下标可能已失效，跳过这一帧。
                            } finally {
                                autoScrollActive.value = false
                            }
                        }
                        animation.join()
                    }
                }
            }
        }
    }

    LazyRow(
        state = listState,
        flingBehavior = rememberSnapFlingBehavior(
            lazyListState = listState,
            snapPosition = SnapPosition.Start
        ),
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 20.dp, end = 136.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        items(
            items = displayedSongs.value,
            key = ::recentSongKey
        ) { music ->
            Box(Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null)) {
                RecommendCardItem(
                    subTitle = "",
                    music = music,
                    onClick = { onSongClick(music, displayedSongs.value) }
                )
            }
        }
    }
}
