package yos.music.player.ui.navigation

import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
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
    favoritesController: NavHostController,
    navigator: AppNavigator,
    modifier: Modifier = Modifier,
    probe: GlassProbe = GlassProbe.Default,
    // 封面取色渐变源：由外壳算好传入，四个 Tab 根页各自 opt-in（见 PageGradientRoot）。
    // 只在根页生效，二级页（艺人页/专辑页/歌单页）继续用各自的提取色。
    trackGradient: TrackBackdropGradient? = null,
) {
    val homeAlphaState = animateFloatAsState(
        targetValue = if (selectedHouse == HouseId.Home) 1f else 0f,
        animationSpec = tween(200),
        label = "home-house-alpha"
    )
    val libraryAlphaState = animateFloatAsState(
        targetValue = if (selectedHouse == HouseId.Library) 1f else 0f,
        animationSpec = tween(200),
        label = "library-house-alpha"
    )
    val searchAlphaState = animateFloatAsState(
        targetValue = if (selectedHouse == HouseId.Search) 1f else 0f,
        animationSpec = tween(200),
        label = "search-house-alpha"
    )
    val favoritesAlphaState = animateFloatAsState(
        targetValue = if (selectedHouse == HouseId.Favorites) 1f else 0f,
        animationSpec = tween(200),
        label = "favorites-house-alpha"
    )

    // ---------- 系统返回隔离（侧滑串栈 bug 的修复，详见 HouseBackDispatcherOwner） ----------
    // 三个 house 各持一个私有 NavigationEventDispatcher；house 内所有 BackHandler（含
    // NavHost 内置那条、弹层）都注册到自己的 dispatcher。系统返回只经下面这条 bridge
    // 转发进当前 house —— 隐藏 house 的栈再深也吃不到返回事件。
    val context = LocalContext.current
    val homeBackOwner = remember { HouseBackDispatcherOwner { (context as? ComponentActivity)?.finish() } }
    val libraryBackOwner = remember { HouseBackDispatcherOwner { (context as? ComponentActivity)?.finish() } }
    val searchBackOwner = remember { HouseBackDispatcherOwner { (context as? ComponentActivity)?.finish() } }
    val favoritesBackOwner = remember { HouseBackDispatcherOwner { (context as? ComponentActivity)?.finish() } }
    val activeBackOwner = when (selectedHouse) {
        HouseId.Home -> homeBackOwner
        HouseId.Library -> libraryBackOwner
        HouseId.Search -> searchBackOwner
        HouseId.Favorites -> favoritesBackOwner
    }
    // bridge 常开：house 内有 enabled 回调（弹层开着或返回栈深>1）→ 转发；house 为空
    // （已在根且无弹层）→ owner 的 onBackCompletedFallback 兜底退出 Activity。
    // 常开不影响优先级更靠后的 activity 级回调（展开播放器、根 NavHost）——它们注册
    // 在本 bridge 之后，系统派发先于本 bridge。
    BackHandler(enabled = true) { activeBackOwner.dispatchBack() }

    Box(modifier) {
        CompositionLocalProvider(LocalTrackGradientSource provides trackGradient) {
            HouseLayer(
                alphaState = homeAlphaState,
                active = selectedHouse == HouseId.Home,
                drawWhenHidden = probe.houseLayersDrawWhenHidden,
                modulateAlpha = probe.houseModulateAlpha,
                backOwner = homeBackOwner,
            ) {
                CompositionLocalProvider(
                    LocalArtistNavigator provides rememberArtistNavigator(homeController)
                ) {
                    HomeNavHost(homeController, navigator)
                }
            }
            HouseLayer(
                alphaState = libraryAlphaState,
                active = selectedHouse == HouseId.Library,
                drawWhenHidden = probe.houseLayersDrawWhenHidden,
                modulateAlpha = probe.houseModulateAlpha,
                backOwner = libraryBackOwner,
            ) {
                CompositionLocalProvider(
                    LocalArtistNavigator provides rememberArtistNavigator(libraryController)
                ) {
                    LibraryNavHost(libraryController, navigator)
                }
            }
            HouseLayer(
                alphaState = searchAlphaState,
                active = selectedHouse == HouseId.Search,
                drawWhenHidden = probe.houseLayersDrawWhenHidden,
                modulateAlpha = probe.houseModulateAlpha,
                backOwner = searchBackOwner,
            ) {
                CompositionLocalProvider(
                    LocalArtistNavigator provides rememberArtistNavigator(searchController)
                ) {
                    SearchNavHost(searchController, navigator)
                }
            }
            HouseLayer(
                alphaState = favoritesAlphaState,
                active = selectedHouse == HouseId.Favorites,
                drawWhenHidden = probe.houseLayersDrawWhenHidden,
                modulateAlpha = probe.houseModulateAlpha,
                backOwner = favoritesBackOwner,
            ) {
                CompositionLocalProvider(
                    LocalArtistNavigator provides rememberArtistNavigator(favoritesController)
                ) {
                    FavoritesNavHost(favoritesController, navigator)
                }
            }
        }
    }
}

@Composable
private fun HouseLayer(
    alphaState: State<Float>,
    active: Boolean,
    drawWhenHidden: Boolean,
    modulateAlpha: Boolean,
    backOwner: HouseBackDispatcherOwner,
    content: @Composable () -> Unit
) {
    // 驱动底栏遮罩与页内页面同帧同步：必须共享同一个 animateFloatAsState 状态，
    // targetAlphaState 在组合期同步更新，使切换首帧（alphaState 尚未推进前）
    // transitioning 即刻为 true，避免旧页提前退出混合或新页提前跳色。
    val targetAlphaState = rememberUpdatedState(if (active) 1f else 0f)
    val houseTransitioning = remember(alphaState, targetAlphaState) {
        derivedStateOf { alphaState.value != targetAlphaState.value }
    }
    val houseScrimTransition = remember(alphaState, houseTransitioning) {
        PageScrimTransition(progress = alphaState, transitioning = houseTransitioning)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(if (active) 1f else 0f)
            .graphicsLayer {
                this.alpha = alphaState.value
                // Auto 下“图层带 alpha”会走一次离屏合成（atrace 里的 alpha caused saveLayer）；
                // ModulateAlpha 把 alpha 乘进每条绘制指令，不再开离屏缓冲。
                if (modulateAlpha) compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            // Compose 不会因为图层全透就跳过子树绘制：alpha==0 的隐藏页仍然逐帧把整屏内容
            // 画一遍。四个 house 同时只有一个 alpha==1，于是每帧有三整屏是纯白工。
            //
            // **默认走 drawWithContent 拦截**（drawWhenHidden=false）：alpha==0 时一行
            // drawContent() 都不调用，子树完全不产出绘制指令。
            // 这条拦截是恒等变换——alpha 为 0 的图层本来就不贡献任何可见像素。
            // `alphaState.value` 在绘制期读取，淡入淡出过程只失效重绘而不触发重组，
            // 真正落定到 0 后便不再重绘。
            //
            // ★不要改成「按隐藏状态挂/卸 Modifier 节点」：那会改 Modifier 链结构，
            // 正是 perf-demand-gating-ablation-20261024 里 63fps→18fps 的死法。
            // 常量拦截器挂不挂由 `drawWhenHidden` 决定，运行期不变。
            .then(
                if (drawWhenHidden) Modifier
                else Modifier.drawWithContent {
                    if (alphaState.value > 0f) drawContent()
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
            LocalOnBackPressedDispatcherOwner provides backOwner,
            LocalHouseScrimTransition provides houseScrimTransition
        ) {
            content()
        }
    }
}
