package yos.music.player.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.zIndex
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalOnBackPressedDispatcherOwner
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
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

    // ---------- 系统返回隔离（侧滑串栈 bug 的修复，详见 HouseBackDispatcherOwner） ----------
    // 三个 house 各持一个私有 NavigationEventDispatcher；house 内所有 BackHandler（含
    // NavHost 内置那条、弹层）都注册到自己的 dispatcher。系统返回只经下面这条 bridge
    // 转发进当前 house —— 隐藏 house 的栈再深也吃不到返回事件。
    val context = LocalContext.current
    val homeBackOwner = remember { HouseBackDispatcherOwner { (context as? ComponentActivity)?.finish() } }
    val libraryBackOwner = remember { HouseBackDispatcherOwner { (context as? ComponentActivity)?.finish() } }
    val searchBackOwner = remember { HouseBackDispatcherOwner { (context as? ComponentActivity)?.finish() } }
    val activeBackOwner = when (selectedHouse) {
        HouseId.Home -> homeBackOwner
        HouseId.Library -> libraryBackOwner
        HouseId.Search -> searchBackOwner
    }
    // bridge 常开：house 内有 enabled 回调（弹层开着或返回栈深>1）→ 转发；house 为空
    // （已在根且无弹层）→ owner 的 onBackCompletedFallback 兜底退出 Activity。
    // 常开不影响优先级更靠后的 activity 级回调（展开播放器、根 NavHost）——它们注册
    // 在本 bridge 之后，系统派发先于本 bridge。
    BackHandler(enabled = true) { activeBackOwner.dispatchBack() }

    Box(modifier) {
        HouseLayer(
            alpha = homeAlpha,
            active = selectedHouse == HouseId.Home,
            drawWhenHidden = probe.houseLayersDrawWhenHidden,
            modulateAlpha = probe.houseModulateAlpha,
            backOwner = homeBackOwner,
        ) {
            HomeNavHost(homeController, navigator)
        }
        HouseLayer(
            alpha = libraryAlpha,
            active = selectedHouse == HouseId.Library,
            drawWhenHidden = probe.houseLayersDrawWhenHidden,
            modulateAlpha = probe.houseModulateAlpha,
            backOwner = libraryBackOwner,
        ) {
            LibraryNavHost(libraryController, navigator)
        }
        HouseLayer(
            alpha = searchAlpha,
            active = selectedHouse == HouseId.Search,
            drawWhenHidden = probe.houseLayersDrawWhenHidden,
            modulateAlpha = probe.houseModulateAlpha,
            backOwner = searchBackOwner,
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
    backOwner: HouseBackDispatcherOwner,
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
        // 子树内所有 BackHandler 的注册目标从 activity 级根 dispatcher 换成 house 私有
        // dispatcher。activity-compose 1.13 的 BackHandler 优先读
        // LocalNavigationEventDispatcherOwner（ViewTree 回退永远命中根，故必须 provide
        // 新 local 才能截住），旧 local 一并 provide 作兜底；LocalLifecycleOwner 不动，
        // 仍是 activity，NavHost/页面里依赖它的生命周期观察不受影响。
        CompositionLocalProvider(
            androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner provides backOwner,
            LocalOnBackPressedDispatcherOwner provides backOwner
        ) {
            content()
        }
    }
}
