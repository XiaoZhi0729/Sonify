package yos.music.player.ui.pages.discovery

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.navigation.NavController
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.objects.DiscoveryObject
import yos.music.player.data.objects.RankObject
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.ui.UI
import yos.music.player.ui.pages.library.OnlineStatusItem
import yos.music.player.ui.toUI
import yos.music.player.ui.widgets.basic.TitleWithLazyVerticalGrid

/**
 * 排行榜大全页（主页「排行榜」Header 整行点击进入）。
 *
 * 数据链：/rank/list 无分页，一次返回全量榜单 —— 直接复用 DiscoveryObject.rankList
 * （主页已加载的数据，进入本页零请求）；holder 为空（进程重建直达本页）时自拉一次。
 * 卡片点击 → RankObject.setSelected → RankDetail（与主页榜单卡一致）。
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun RankListDetail(
    navController: NavController,
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null
) {
    val ranks = DiscoveryObject.rankList
    // 本页局部状态，不污染主页共享的 DiscoveryObject.status 状态机
    val status = remember { mutableStateOf(if (ranks.value.isEmpty()) "loading" else "ok") }
    val scope = rememberCoroutineScope()

    fun load() {
        if (status.value == "loading") return
        status.value = "loading"
        scope.launch {
            KugouRepository.getRankList()
                .onSuccess { list ->
                    ranks.value = list
                    status.value = if (list.isEmpty()) "empty" else "ok"
                }
                .onFailure { e ->
                    status.value = "error:${e.message}"
                }
        }
    }

    LaunchedEffect(Unit) {
        if (ranks.value.isEmpty()) load()
    }

    TitleWithLazyVerticalGrid(
        title = stringResource(id = R.string.discovery_rank_title),
        onBack = { navController.popBackStack() }
    ) {
        if (status.value != "ok") {
            item("Status", span = { GridItemSpan(maxLineSpan) }) {
                OnlineStatusItem(
                    status = status.value,
                    loadingText = stringResource(id = R.string.online_playlists_loading),
                    emptyText = stringResource(id = R.string.discovery_rank_empty),
                    // 失败时点击状态行重试（loading 态 load() 自带防重入）
                    modifier = Modifier.clickable { load() }
                )
            }
        }

        itemsIndexed(
            ranks.value,
            key = { _, rank -> rank.rankId }
        ) { _, rank ->
            RankCardItem(
                rank,
                Modifier.fillMaxWidth(),
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope
            ) {
                RankObject.setSelected(rank)
                navController.toUI(UI.RankDetail)
            }
        }
    }
}
