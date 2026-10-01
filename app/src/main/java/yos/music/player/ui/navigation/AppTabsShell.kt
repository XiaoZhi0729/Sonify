package yos.music.player.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.navigation.NavHostController
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import yos.music.player.code.utils.others.GlassProbe

@Composable
fun AppTabsShell(
    selectedHouse: HouseId,
    homeController: NavHostController,
    libraryController: NavHostController,
    searchController: NavHostController,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    probe: GlassProbe = GlassProbe.Default,
) {
    val homeAlpha by animateFloatAsState(
        targetValue = if (selectedHouse == HouseId.Home) 1f else 0f,
        animationSpec = tween(200),
        label = "home-house-alpha"
    )
    val libraryAlpha by animateFloatAsState(
        targetValue = if (selectedHouse == HouseId.Library) 1f else 0f,
        animationSpec = tween(200),
        label = "library-house-alpha"
    )
    val searchAlpha by animateFloatAsState(
        targetValue = if (selectedHouse == HouseId.Search) 1f else 0f,
        animationSpec = tween(200),
        label = "search-house-alpha"
    )

    LaunchedEffect(selectedHouse) {
        homeController.enableOnBackPressed(selectedHouse == HouseId.Home)
        libraryController.enableOnBackPressed(selectedHouse == HouseId.Library)
        searchController.enableOnBackPressed(selectedHouse == HouseId.Search)
    }

    Box(modifier) {
        HouseLayer(
            alpha = homeAlpha,
            active = selectedHouse == HouseId.Home,
            drawWhenHidden = probe.houseLayersDrawWhenHidden,
            modulateAlpha = probe.houseModulateAlpha,
        ) {
            HomeNavHost(homeController, navigator)
        }
        HouseLayer(
            alpha = libraryAlpha,
            active = selectedHouse == HouseId.Library,
            drawWhenHidden = probe.houseLayersDrawWhenHidden,
            modulateAlpha = probe.houseModulateAlpha,
        ) {
            LibraryNavHost(libraryController, navigator)
        }
        HouseLayer(
            alpha = searchAlpha,
            active = selectedHouse == HouseId.Search,
            drawWhenHidden = probe.houseLayersDrawWhenHidden,
            modulateAlpha = probe.houseModulateAlpha,
        ) {
            SearchNavHost(searchController, navigator)
        }
    }
}

@Composable
private fun HouseLayer(
    alpha: Float,
    active: Boolean,
    drawWhenHidden: Boolean,
    modulateAlpha: Boolean,
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer {
                this.alpha = alpha
                // Auto 下“图层带 alpha”会走一次离屏合成（atrace 里的 alpha caused saveLayer）；
                // ModulateAlpha 把 alpha 乘进每条绘制指令，不再开离屏缓冲。
                if (modulateAlpha) compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // Compose 不会因为图层全透就跳过子树绘制：alpha==0 的隐藏页仍然逐帧把整屏内容
            // 画一遍。三页同时存在时，每帧有两屏是完全看不见的白工。
            //
            // 默认（drawWhenHidden=true）必须一个节点都不挂，而不是挂一个无条件透传的
            // drawWithContent：多一个 DrawModifierNode 会改变图层合并与脏区传播的拓扑，
            // 即使画面逐像素相同也不该在默认路径上拿它赌观感。
            .then(
                if (drawWhenHidden) Modifier
                else Modifier.drawWithContent {
                    if (alpha > 0f) drawContent()
                }
            )
            .then(
                if (active) Modifier else Modifier.pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
            )
            .semantics { if (!active) invisibleToUser() }
    ) {
        content()
    }
}
