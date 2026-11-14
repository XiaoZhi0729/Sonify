package yos.music.player.ui.onboarding

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.native.KugouApiService
import yos.music.player.native.KugouLoginException
import com.kyant.backdrop.backdrops.rememberCanvasBackdrop
import yos.music.player.ui.theme.HelloFontFamily
import yos.music.player.ui.theme.HelloTextFeatures
import yos.music.player.ui.widgets.basic.LiquidTopBarButton
import yos.music.player.ui.widgets.basic.navigationBarsPadding
import yos.music.player.ui.widgets.basic.rawImeBottomPx
import yos.music.player.ui.widgets.basic.statusBarsPadding

/**
 * 首次启动引导（六屏）。
 *
 * ══ 骨架 ══════════════════════════════════════════════════════════════════
 *
 *   ┌─ 顶栏：返回键（屏 >0 才出现）  ·  章节记号（右侧，5 根）
 *   │
 *   ├─ 内容区（六段，横向视差推进；内容自入场，逐行错峰）
 *   │
 *   └─ 底栏：主键 + 次键槽位 ← ★永远不动，只有文字在换
 *
 * 六屏（括号里是章节记号序号，登录与验证码共用第 2 根 —— 它们是同一章的
 * 两步，不是两章）：
 *
 *   0 欢迎(-)   1 手机号(1)   2 验证码(1)   3 外观与歌词(2)
 *   4 玻璃材质(3)   5 准备好了(4)
 *
 * 三条让它"像一个 App 而不是六张图"的约束：
 *
 *   ① **主键外壳全页唯一**。切屏时不重排、不位移、不换尺寸，只换文字。
 *   ② **次键槽位常驻** 40dp。验证码屏没有次键也留空位，否则主键会上下跳。
 *   ③ **品牌光晕跟着步数走**。同一条光晕在六屏之间缓慢移动 —— 是"同一个
 *      场景在变"而不是"换了一个场景"。
 *
 * ══ 分层（这层不能改回去）══════════════════════════════════════════════════
 *
 *   Box
 *   ├─ 背景（渐变 + 光晕 + 深色顶光）   ← 纯绘制，不吃触摸
 *   ├─ 主列 Column（顶栏 + 内容 + 底栏）
 *   ├─ 扫码弹层（按需）                 ← 页内自绘，不开新窗口
 *   └─ 收尾过渡层（按需，最上层）        ← 全屏不透明，铺满整个引导
 *
 * ★扫码弹层与过渡层都必须在**根 Box** 里，不能塞进内容区的 AnimatedContent：
 *   前者要盖住底栏，后者要盖住整页（含顶栏与底栏）。
 */
/**
 * @param forceLogin 强制走完整流程，**即使已登录也不跳过登录屏**。
 *
 * 两个入口的口径不同：
 *  · **首次启动**（forceLogin = false）：已登录的用户看不到登录与验证码屏 ——
 *    那两屏对他全是既成事实。
 *  · **设置›关于›重新查看引导**（forceLogin = true）：完整重放一遍，
 *    登录屏必须出现，否则这条入口永远验不到登录那一屏在做什么。
 */
@Composable
fun OnboardingOverlay(onFinish: () -> Unit, forceLogin: Boolean = false) {
    val colors = onboardingColors()
    val context = LocalContext.current
    val density = LocalDensity.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()
    val api = remember { KugouApiService.getInstance() }

    val rawImePx = rawImeBottomPx()
    val rawImeDp = with(density) { rawImePx.toDp() }
    val imeLiftDp by animateDpAsState(
        targetValue = rawImeDp,
        animationSpec = tween(
            durationMillis = 260,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
        ),
        label = "onboardingImeLift",
    )
    var navBarPx by remember { mutableIntStateOf(0) }
    val navBarDp = with(density) { navBarPx.toDp() }

    var step by rememberSaveable { mutableIntStateOf(HELLO_STEP) }
    val backStack = remember { mutableStateListOf(HELLO_STEP) }
    var forward by remember { mutableStateOf(true) }
    var qrSheetOpen by remember { mutableStateOf(false) }
    var splashActive by remember { mutableStateOf(false) }

    // ★手机号 / 倒计时 / 发码状态**必须活在帧层**，不能留在登录屏里。
    //   AnimatedContent 切屏会销毁离开的那一屏，把 mobile 存在屏内的话，
    //   进到验证码屏时手机号就没了 —— 而验证码屏正是要用它去登录。
    val login = remember { LoginState() }

    /** 只有"首次启动 + 已登录"才跳登录与验证码两屏。 */
    val maySkipLogin = !forceLogin && KugouAccountState.isLoggedIn
    val skip: Boolean = maySkipLogin && !DEBUG_SHOW_LOGIN

    /** 被自动越过的屏在**两个方向**上都要跳过，否则后退会死在死循环里。 */
    fun skipped(t: Int): Boolean = skip && (t == LOGIN_STEP || t == OTP_STEP)

    fun go(target: Int) {
        // 不在这里震：所有可点控件自己已经有一下，再加就是"点一下震两下"。
        val resolved = if (skipped(target)) THEME_STEP else target
        if (resolved != OTP_STEP) {
            focusManager.clearFocus()
            keyboardController?.hide()
        }
        if (resolved != step) {
            // 如果是从登录页「先不登录，跳过/下一步」进设置页，把未完成的 OTP 从栈里清掉，
            // 并压入真实的跳转路径，确保从设置页返回时回到登录页而非验证码页。
            if (backStack.lastOrNull() != resolved) {
                backStack.add(resolved)
            }
            forward = resolved >= step
            step = resolved
        }
    }

    fun back() {
        if (backStack.size > 1) {
            backStack.removeAt(backStack.lastIndex)
            val prev = backStack.last()
            if (prev != OTP_STEP) {
                focusManager.clearFocus()
                keyboardController?.hide()
            }
            forward = false
            step = prev
        }
    }

    // 倒计时放在帧层：用户发完码立刻进验证码屏，倒计时必须继续走。
    LaunchedEffect(login.countdown) {
        if (login.countdown > 0) {
            delay(1000)
            login.countdown--
        }
    }

    fun sendCode() {
        if (login.sending || login.countdown > 0) return
        if (!Regex("^1[3-9]\\d{9}$").matches(login.mobile)) {
            login.error = context.getString(R.string.onboarding_phone_format_error)
            return
        }
        login.sending = true
        login.error = null
        // ★用帧层的 scope：发码后马上就跳屏了，挂在屏上的协程会被取消。
        scope.launch {
            api.sendLoginCaptcha(login.mobile).fold(
                onSuccess = {
                    login.sending = false
                    login.countdown = 60
                    go(OTP_STEP)
                },
                onFailure = { e ->
                    login.sending = false
                    Log.e(
                        "Onboarding",
                        "sendLoginCaptcha failed: mobile=${login.mobile} " +
                            "type=${e::class.java.simpleName} msg=${e.message}",
                        e
                    )
                    login.error = (e as? KugouLoginException)?.errorMsg?.takeIf { m -> m.isNotBlank() }
                        ?: context.getString(R.string.kugou_qr_failed)
                },
            )
        }
    }

    BackHandler(enabled = step == HELLO_STEP && !qrSheetOpen && !splashActive && !forceLogin && !OnboardingPrefs.completed) {
        (context as? android.app.Activity)?.moveTaskToBack(true)
    }
    BackHandler(enabled = step > HELLO_STEP && !qrSheetOpen && !splashActive) { back() }
    // 扫码弹层：返回键先关弹层，不许穿透到"上一屏"。
    BackHandler(enabled = qrSheetOpen) { qrSheetOpen = false }
    // ★收尾过渡那 ~2.5 秒里吞掉返回键。否则用户误按会退回「准备好了」，
    //   而过渡层已经在播退场，回来会看见一个半透的、状态错位的页面。
    BackHandler(enabled = splashActive) { }

    Box(
        Modifier
            .fillMaxSize()
            // ★全屏接管层必须吃掉**落空**的点击。
            // `background()` 只绘制、不参与命中测试，点空白处时事件会落到
            // 下面的兄弟层（底栏 Tab / 迷你条 / 播放壳），表现为
            // "点着欢迎页把底下的播放页拖出来了"。
            // 用 clickable 兜底，而不是 pointerInput 在 Initial 阶段消费
            // （后者会把子节点的按钮一起吞掉）。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
    ) {
        // ★★`imePadding()` 绝不能挂在**根 Box** 上（上一版就是，这是"页面抬起时下面
        //   是透明的、中间空一段"的真根因）。
        //
        //   根 Box 加了 imePadding 之后它的 measured 高度就被扣掉键盘高度，
        //   而背景那一层是 `fillMaxSize()` —— 于是背景跟着一起缩，
        //   键盘后面露出来的是**主页**（引导页是 zIndex 110 的覆盖层）。
        //   同时中间的空档来自 Column 里的 weight 弹簧被压缩。
        //
        //   正解：背景只受最外层窗口约束（恒满屏），`imePadding()` 只加在
        //   **内容 Column** 上，让顶栏/内容/底栏整体让位。
        // ★引导内容 + 背景的 alpha：收尾过渡期间整页淡出。
        //
        // ══ 为什么连背景一起淡（这里最容易写错）═══════════════════════════
        // 引导页是 MainActivity 里 zIndex 110 的覆盖层，主页在它**下面**。
        // 过渡层又是引导页内部最上面的一层。所以过渡层淡出时，露出来的
        // 是"引导页自己"（不透明背景渐变 + 第 5 屏），**不是主页** ——
        // 只淡内容不淡背景的话，用户会看到"闪一下『准备好了』再闪到主页"。
        //
        // 正解：splashActive 把**整个覆盖层**（背景 + 顶栏 + 内容 + 底栏）
        // 一起淡到 0；过渡层是同一次淡出的一部分。淡完之后底下就是主页
        // = 真交叉淡入，不需要第二个动画。
        //
        // 时序：内容与背景 450ms 淡出（过渡层 380ms 淡入盖住它）→
        // 停 1.5s → 过渡层 450ms 淡出 → 底下已是主页 → onFinish()。
        val contentAlpha by animateFloatAsState(
            targetValue = if (splashActive) 0f else 1f,
            animationSpec = tween(450, easing = FastOutSlowInEasing),
            label = "onboardingContentAlpha"
        )

        Box(Modifier.fillMaxSize().graphicsLayer { alpha = contentAlpha }) {
            OnboardingBackground(colors = colors, step = step)
        }

        // 仅用于测量底部导航栏高度，保持底层"先不登录"固定在安全区域内不随键盘抬高。
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .navigationBarsPadding()
                .onSizeChanged { navBarPx = it.height }
        )

        // 登录屏次键：始终锚定在底部，不随键盘抬起（键盘盖住它，只抬高输入框和发送验证码）。
        AnimatedVisibility(
            visible = step == LOGIN_STEP && !splashActive,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150)),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer { alpha = contentAlpha }
                .navigationBarsPadding()
                .padding(bottom = Ob.S20),
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(Ob.FooterLinkHeight),
                contentAlignment = Alignment.Center,
            ) {
                TextLink(
                    text = stringResource(
                        id = if (forceLogin) R.string.onboarding_login_next
                        else R.string.onboarding_login_skip
                    ),
                    colors = colors,
                    onClick = { go(THEME_STEP) },
                )
            }
        }

        Column(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = contentAlpha }
                // ★必须用本工程自己的 raw insets：HyperOS 给窗口派发的
                // foundation insets 恒为 0，症状是记号钻到状态栏底下。
                .statusBarsPadding()
        ) {
            TopBar(
                colors = colors,
                step = step,
                showBack = step > LOGIN_STEP,
                showBrand = step == LOGIN_STEP,
                onBack = { back() },
            )

            AnimatedContent(
                targetState = step,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                transitionSpec = {
                    val dir = if (forward) 1 else -1
                    (
                        fadeIn(tween(Ob.StepInMs, easing = FastOutSlowInEasing)) +
                            slideInHorizontally(
                                tween(Ob.StepInMs, easing = FastOutSlowInEasing)
                            ) { dir * it / 8 } +
                            scaleIn(
                                tween(Ob.StepInMs, easing = FastOutSlowInEasing),
                                initialScale = 0.965f
                            )
                        ).togetherWith(
                        fadeOut(tween(Ob.StepOutMs)) +
                            slideOutHorizontally(
                                tween(Ob.StepOutMs, easing = FastOutSlowInEasing)
                            ) { -dir * it / 14 } +
                            scaleOut(tween(Ob.StepOutMs), targetScale = 0.985f)
                    ) using SizeTransform(clip = false)
                },
                label = "onboardingStep"
            ) { index ->
                when (index) {
                    HELLO_STEP -> HelloScreen(colors = colors)

                    LOGIN_STEP -> LoginScreen(
                        colors = colors,
                        state = login,
                        imeActive = rawImePx > 0,
                        onSend = { sendCode() },
                        onOpenQrSheet = { qrSheetOpen = true },
                    )

                    OTP_STEP -> OtpScreen(
                        colors = colors,
                        state = login,
                        onVerified = { go(THEME_STEP) },
                        onResend = { if (login.canSend) sendCode() },
                    )

                    THEME_STEP -> ThemeScreen(colors = colors)
                    SETTINGS_STEP -> SettingsScreen(colors = colors)
                    GLASS_STEP -> GlassSettingsScreen(colors = colors)
                    else -> DoneScreen(colors = colors)
                }
            }

            Footer(
                colors = colors,
                step = step,
                skip = skip,
                forceLogin = forceLogin,
                login = login,
                navBarDp = navBarDp,
                imeLiftDp = imeLiftDp,
                onAdvance = { target -> go(target) },
                onSendCode = { sendCode() },
                onFinish = { splashActive = true },
            )
        }

        // ── 扫码弹层 ────────────────────────────────────────────────
        // ★只在 qrSheetOpen 为真时**进入组合**。弹层内部先播退场动画再回调
        //   这里置 false —— 不能只靠 translationY 把它移出屏幕，那样节点还在
        //   组合里，轮询会一直在后台打接口。
        if (qrSheetOpen) {
            OnboardingQrSheet(
                colors = colors,
                onDismiss = { qrSheetOpen = false },
                onLoggedIn = {
                    qrSheetOpen = false
                    go(THEME_STEP)
                },
            )
        }

        // ── 收尾过渡层 ──────────────────────────────────────────────
        if (splashActive) {
            OnboardingTransitionSplash(
                colors = colors,
                onDone = {
                    splashActive = false
                    onFinish()
                },
            )
        }
    }
}

/**
 * 调试开关：已登录时也停在登录屏，用来肉眼确认该屏排版。
 *
 * **必须保持 false**。true 只用于"我已登录但想看看登录屏"的验收，
 * 真机上会表现为「明明登录了还让我登录」。
 */
private const val DEBUG_SHOW_LOGIN = false
private const val HELLO_STEP = 0
private const val LOGIN_STEP = 1
private const val OTP_STEP = 2
private const val THEME_STEP = 3
private const val SETTINGS_STEP = 4
private const val GLASS_STEP = 5
private const val DONE_STEP = 6

/** 章节记号数量。比屏数少一根：登录与验证码共用同一根。 */
private const val STEP_COUNT = 6

/** 屏 → 章节记号。两张屏共用一根，是为了让"登录"读起来是一章两步。 */
private val StepBarMap = intArrayOf(0, 1, 1, 2, 3, 4, 5)

// ── 跨屏登录状态 ────────────────────────────────────────────────────────────

/**
 * 登录流程的跨屏状态。
 *
 * 为什么不是各屏自己 `remember`：手机号在屏 1 输入、在屏 2 使用。
 * `AnimatedContent` 切屏会把离开的那一屏从组合里摘掉，屏内的 `remember`
 * 随之销毁。把这三样提到帧层，两屏共用同一份，切屏/旋屏都不丢。
 *
 * 不用全局单例：它由 `OnboardingOverlay` 用 `remember` 创建，引导页关闭即消失。
 */
internal class LoginState {
    var mobile by mutableStateOf("")
    var sending by mutableStateOf(false)
    var countdown by mutableIntStateOf(0)
    var error by mutableStateOf<String?>(null)

    /** 手机号 11 位、没在发送、不在倒计时里 —— 三个条件缺一不可。 */
    val canSend: Boolean
        get() = mobile.length == 11 && !sending && countdown == 0
}

// ── 背景 ────────────────────────────────────────────────────────────────────

/**
 * 整页背景：一层极淡的纵向渐变 + 一个随步数移动的主题色光晕（+ 深色顶光）。
 *
 * 为什么不是纯白：纯白页面上任何"接近白"的层次都看不见。
 * 有 2~3% 的渐变之后，卡片的边缘、发丝线、玻璃的边缘光才有东西可以对照。
 *
 * 光晕跟着 `step` 在屏幕中上部横向移动一个短距离 —— 是**同一盏灯在跟着走**，
 * 不是每屏换一张背景。
 */
@Composable
private fun OnboardingBackground(colors: OnboardingColors, step: Int) {
    val drift by animateFloatAsState(
        targetValue = when (step) {
            HELLO_STEP -> -0.22f
            LOGIN_STEP -> 0.0f
            OTP_STEP -> 0.10f
            THEME_STEP -> 0.18f
            SETTINGS_STEP -> 0.25f
            GLASS_STEP -> 0.32f
            else -> 0f
        },
        animationSpec = tween(Ob.AuraMs, easing = FastOutSlowInEasing),
        label = "aura"
    )
    val haloCyFraction by animateFloatAsState(
        targetValue = if (step == LOGIN_STEP) 0.24f else 0.30f,
        animationSpec = tween(Ob.AuraMs, easing = FastOutSlowInEasing),
        label = "auraCy"
    )
    val haloRadiusScale by animateFloatAsState(
        targetValue = if (step == LOGIN_STEP) 1.35f else 1.25f,
        animationSpec = tween(Ob.AuraMs, easing = FastOutSlowInEasing),
        label = "auraR"
    )

    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                // ① 底色渐变
                drawRect(
                    brush = Brush.verticalGradient(
                        0f to colors.bgTop,
                        1f to colors.bgBottom
                    )
                )

                // ② 主题色光晕（大、极淡，只在屏幕中上部）
                val cx = size.width * (0.5f + drift * 0.8f)
                val cy = size.height * haloCyFraction
                val r = size.width * haloRadiusScale
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            colors.brand.copy(alpha = colors.auraStrong),
                            colors.brand.copy(alpha = colors.auraWeak * 0.5f),
                            Color.Transparent
                        ),
                        center = Offset(cx, cy),
                        radius = r
                    ),
                    radius = r,
                    center = Offset(cx, cy)
                )

                // ③ 深色顶光：黑底上需要一点冷光，否则像"关机"
                if (colors.dark) {
                    drawCircle(
                        brush = Brush.radialGradient(
                            colors = listOf(
                                Color.White.copy(alpha = 0.05f),
                                Color.Transparent
                            ),
                            center = Offset(size.width * 0.5f, -size.height * 0.12f),
                            radius = size.width
                        ),
                        radius = size.width,
                        center = Offset(size.width * 0.5f, -size.height * 0.12f)
                    )
                }
            }
    )
}

// ── 顶栏 ────────────────────────────────────────────────────────────────────

/**
 * 顶栏：左侧按步态展示玻璃返回钮或手写体 `Sonify`（屏 0 隐藏），右侧章节记号。
 *
 * 为什么不是左上角若干圆点：那是网页轮播的语汇。
 * iOS 原生引导用**页眉里的细章节记号**，且它与"当前在哪一章"是同一条信息。
 */
@Composable
private fun TopBar(
    colors: OnboardingColors,
    step: Int,
    showBack: Boolean,
    showBrand: Boolean,
    onBack: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(Ob.TopBarHeight)
            .padding(horizontal = Ob.Gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val backAlpha by animateFloatAsState(
            targetValue = if (showBack) 1f else 0f,
            animationSpec = tween(240),
            label = "backAlpha"
        )
        val brandAlpha by animateFloatAsState(
            targetValue = if (showBrand) 1f else 0f,
            animationSpec = tween(260),
            label = "brandAlpha"
        )

        val backBackdrop = rememberCanvasBackdrop(
            remember(colors.bgTop) { { drawRect(colors.bgTop) } }
        )
        Box(
            Modifier.height(Ob.TopBarHeight),
            contentAlignment = Alignment.CenterStart,
        ) {
            if (showBrand || brandAlpha > 0.01f) {
                Text(
                    text = stringResource(id = R.string.onboarding_hello_brand),
                    color = colors.ink.copy(alpha = brandAlpha),
                    fontSize = 26.sp,
                    fontFamily = HelloFontFamily,
                    style = TextStyle(
                        fontFeatureSettings = HelloTextFeatures,
                        letterSpacing = 0.sp,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                    softWrap = false,
                    modifier = Modifier
                        .layout { measurable, constraints ->
                            val placeable = measurable.measure(
                                constraints.copy(maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
                            )
                            val vPadPx = Ob.HandwriteVPad.roundToPx()
                            val hPadPx = Ob.HandwriteHPad.roundToPx()
                            val w = (placeable.width - hPadPx * 2).coerceAtLeast(0)
                            val h = (placeable.height - vPadPx * 2).coerceAtLeast(0)
                            layout(w, h) {
                                placeable.placeRelative(-hPadPx, -vPadPx)
                            }
                        }
                        .graphicsLayer {
                            clip = false
                            translationX = (1f - brandAlpha) * -6.dp.toPx()
                        }
                        .padding(vertical = Ob.HandwriteVPad, horizontal = Ob.HandwriteHPad),
                )
            }

            if (showBack || backAlpha > 0.01f) {
                Box(
                    Modifier
                        .size(Ob.TopBarHeight)
                        .graphicsLayer {
                            alpha = backAlpha
                            translationX = (1f - backAlpha) * -6.dp.toPx()
                        },
                    contentAlignment = Alignment.CenterStart,
                ) {
                    LiquidTopBarButton(
                        onClick = { if (showBack) onBack() },
                        backdrop = backBackdrop,
                        icon = painterResource(id = R.drawable.ic_back),
                        contentDescription = null,
                        iconSize = 18.dp,
                        iconOffset = androidx.compose.ui.unit.DpOffset((-2).dp, 0.dp),
                        buttonHeight = Ob.TopBarHeight,
                        backdropAlpha = { backAlpha },
                        shadowAlpha = { backAlpha },
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        StepMarks(colors = colors, current = StepBarMap[step.coerceIn(0, StepBarMap.size - 1)])
    }
}

/**
 * 章节记号：五根竖细线。当前那根最长并染成品牌色，已经过的中等高度。
 *
 * 不用圆点，因为圆点在"五个"的时候读起来像轮播图；细线更像章节。
 * 高度动画（8 → 18dp）比颜色变化更容易被余光捕捉到。
 */
@Composable
private fun StepMarks(colors: OnboardingColors, current: Int) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(Ob.MarkGap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(STEP_COUNT) { i ->
            val active = i == current
            val passed = i < current
            val h by animateFloatAsState(
                targetValue = when {
                    active -> Ob.MarkHeightActive.value
                    passed -> Ob.MarkHeightPassed.value
                    else -> Ob.MarkHeightIdle.value
                },
                animationSpec = Ob.SlideSpring,
                label = "markH$i"
            )
            val c by animateColorAsState(
                targetValue = when {
                    active -> colors.brand
                    passed -> colors.ink.copy(alpha = 0.34f)
                    else -> colors.ink.copy(alpha = 0.16f)
                },
                animationSpec = tween(260),
                label = "markC$i"
            )
            Box(
                Modifier
                    .width(Ob.MarkWidth)
                    .height(h.dp)
                    .clip(CircleShape)
                    .background(c)
            )
        }
    }
}

// ── 底栏 ────────────────────────────────────────────────────────────────────

/**
 * 底栏：主键 + 次键槽位。
 *
 * ★★这是整套代码里最容易被改坏的地方。三条硬约束：
 *   ① 主键节点**必须**在六屏之间保持同一个（不要 `when(step)` 里各建一个）。
 *      它的文字通过 `Crossfade` 换，尺寸/位置/材质一律不变。
 *   ② 次键槽位**必须**永远占据 [Ob.FooterLinkHeight]，即使没有次键。
 *   ③ 验证码屏的主键**隐形但占位**（alpha 0 + 不可点）—— 那一屏的动作是
 *      "输满 6 位自动提交"，主键不该出现；但它仍然占着位置，否则从登录屏
 *      切过去时下面的次键槽会往上跳 54dp。
 */
@Composable
private fun Footer(
    colors: OnboardingColors,
    step: Int,
    skip: Boolean,
    forceLogin: Boolean,
    login: LoginState,
    navBarDp: Dp,
    imeLiftDp: Dp,
    onAdvance: (Int) -> Unit,
    onSendCode: () -> Unit,
    onFinish: () -> Unit,
) {
    val primaryText: String
    val primaryEnabled: Boolean
    val primaryVisible: Boolean
    val onPrimary: () -> Unit

    when (step) {
        HELLO_STEP -> {
            primaryText = stringResource(id = R.string.onboarding_hello_start)
            primaryEnabled = true
            primaryVisible = true
            onPrimary = { onAdvance(LOGIN_STEP) }
        }

        LOGIN_STEP -> {
            primaryText = when {
                login.sending -> stringResource(id = R.string.kugou_phone_sending)
                login.countdown > 0 -> stringResource(
                    id = R.string.onboarding_otp_resend_countdown,
                    login.countdown
                )
                else -> stringResource(id = R.string.onboarding_login_send_code)
            }
            primaryEnabled = login.mobile.isNotEmpty() && !login.sending && login.countdown == 0
            primaryVisible = true
            onPrimary = onSendCode
        }

        OTP_STEP -> {
            // 隐形但占位：验证码屏没有主键动作。
            primaryText = stringResource(id = R.string.onboarding_otp_verifying)
            primaryEnabled = false
            primaryVisible = false
            onPrimary = {}
        }

        THEME_STEP -> {
            primaryText = stringResource(id = R.string.onboarding_next)
            primaryEnabled = true
            primaryVisible = true
            onPrimary = { onAdvance(SETTINGS_STEP) }
        }

        SETTINGS_STEP -> {
            primaryText = stringResource(id = R.string.onboarding_next)
            primaryEnabled = true
            primaryVisible = true
            onPrimary = { onAdvance(GLASS_STEP) }
        }

        GLASS_STEP -> {
            primaryText = stringResource(id = R.string.onboarding_settings_confirm)
            primaryEnabled = true
            primaryVisible = true
            onPrimary = { onAdvance(DONE_STEP) }
        }

        else -> {
            primaryText = stringResource(id = R.string.onboarding_done_start)
            primaryEnabled = true
            primaryVisible = true
            onPrimary = onFinish
        }
    }

    // 统一所有页底部红色主按钮高度，与登录页「发送验证码」保持完全一致
    val restingBottomDp = navBarDp + Ob.S20 + Ob.FooterGap + Ob.FooterLinkHeight
    val bottomClearance = if (step == LOGIN_STEP) {
        maxOf(restingBottomDp, imeLiftDp + Ob.S12)
    } else {
        restingBottomDp
    }

    AnimatedVisibility(
        visible = step != OTP_STEP,
        enter = fadeIn(tween(220)) + expandVertically(tween(260), expandFrom = Alignment.Bottom),
        exit = fadeOut(tween(160)) + shrinkVertically(tween(220), shrinkTowards = Alignment.Bottom),
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(top = Ob.S12, bottom = bottomClearance),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            PrimaryButton(
                // 文案走 MochiBlurText 模糊交叉替换：切屏时旧字模糊缩小淡出、新字清晰放大淡入。
                text = primaryText,
                colors = colors,
                onClick = onPrimary,
                enabled = primaryEnabled && primaryVisible,
            )
        }
    }
}
