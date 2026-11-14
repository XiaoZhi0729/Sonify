package yos.music.player.ui.onboarding

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.material3.Icon
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.OutputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.data.objects.KugouAccountState
import yos.music.player.data.objects.KugouSyncCoordinator
import yos.music.player.data.repositories.KugouVipRepository
import yos.music.player.native.KugouApiService
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.widgets.basic.rawImeVisible
import yos.music.player.ui.widgets.mochi.MochiBlurText
import yos.music.player.ui.widgets.mochi.MochiButton
import yos.music.player.ui.widgets.mochi.MochiButtonColors
import yos.music.player.ui.widgets.mochi.MochiButtonDefaults
import yos.music.player.ui.widgets.mochi.MochiButtonStyle

private enum class QrPhase { LOADING, WAITING, SCANNED, EXPIRED, FAILED }

/**
 * 登录成功后的状态重建（VIP 详情 / 打卡记录 / 收藏·歌单·关注差分）。
 *
 * ★必须用**自己的**协程作用域，不能用 `rememberCoroutineScope()`：
 *   登录成功的同一拍就会前进到下一屏，本屏立刻离开组合，
 *   `rememberCoroutineScope` 随即被取消 —— 挂在它上面的 `refreshAll()` 会
 *   在第一个挂起点就死掉。表现是"登录成功了，但收藏/歌单/会员信息要等
 *   下次进设置页才出来"。
 */
private val loginFollowUpScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

internal fun afterLogin() {
    loginFollowUpScope.launch {
        runCatching { KugouVipRepository.onLoggedIn() }
        runCatching { KugouSyncCoordinator.refreshAll() }
    }
}

/**
 * 屏 1 · 连接酷狗曲库（手机号）。
 *
 * ── 与上一版的区别 ────────────────────────────────────────────────
 * 上一版是同屏「扫码 / 手机号」分段切换，两个表单叠加在同一块区域。
 * 这一版把扫码**收进底部弹层**，本屏只留一条路：手机号 → 验证码（独立屏）。
 *
 * 判据：一屏只有一个主动作。扫码是"另一种方式"，不是"另一半界面"——
 * 它应该是这屏的一个出口（弹层），而不是与主表单平级的一格。
 *
 * ── 为什么主键不在这个文件里 ──────────────────────────────────────
 * 主键全页唯一，住在帧层的 [Footer]。本屏只把 `mobile` 写进帧层的
 * [LoginState]（发码的点击与可用性由帧层读它判断），自己不画按钮。
 */
@Composable
internal fun LoginScreen(
    colors: OnboardingColors,
    state: LoginState,
    imeActive: Boolean,
    onSend: () -> Unit,
    onOpenQrSheet: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val availableH = maxHeight
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Ob.Gutter)
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = availableH),
                verticalArrangement = Arrangement.Bottom,
            ) {
                val context = LocalContext.current
                Spacer(Modifier.height(Ob.S16))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.flamingo_icon),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                    )

                    MochiButton(
                        onClick = {
                            Vibrator.click(context)
                            onOpenQrSheet()
                        },
                        style = MochiButtonStyle.Tonal,
                        colors = MochiButtonDefaults.colors(MochiButtonStyle.Tonal).copy(
                            container = colors.track,
                            content = colors.ink,
                            pressedContainer = Color.Transparent,
                            glow = Color.White.copy(alpha = if (colors.dark) 0.16f else 0.45f),
                        ),
                        minHeight = 36.dp,
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(
                            painter = painterResource(id = R.drawable.ic_onboarding_qr),
                            contentDescription = stringResource(id = R.string.onboarding_login_use_qr),
                            modifier = Modifier.size(16.dp),
                            tint = colors.ink,
                        )
                        Text(
                            text = stringResource(id = R.string.onboarding_login_use_qr),
                            fontSize = Ob.DescSize,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }

                Spacer(Modifier.height(Ob.S12))

                ScreenTitle(
                    title = stringResource(id = R.string.onboarding_login_title),
                    desc = stringResource(id = R.string.onboarding_login_desc),
                    colors = colors,
                )

                Spacer(Modifier.height(Ob.S24))

                // ── 手机号输入行 ──
                PhoneField(colors = colors, state = state, onSend = onSend)

                if (state.error != null) {
                    Spacer(Modifier.height(Ob.S8))
                    Text(
                        text = state.error!!,
                        color = ErrorRed,
                        fontSize = Ob.DescSize,
                        lineHeight = Ob.DescLine,
                    )
                }

                Spacer(Modifier.height(Ob.S20))
            }
        }
    }
}

/**
 * 手机号输入行：`+86` + 号码 + 清空。
 *
 * 56dp 高 / 20dp 圆角 / 卡片色 / 1.5dp 描边 —— 比"下划线输入"更重，
 * 因为它是本屏**唯一**要用户动手的地方，应该看起来像一个可以按的物件。
 */
@Composable
private fun PhoneField(
    colors: OnboardingColors,
    state: LoginState,
    onSend: () -> Unit,
) {
    val shape = YosRoundedCornerShape(Ob.FieldCorner)
    val focusRequester = remember { FocusRequester() }
    Row(
        Modifier
            .fillMaxWidth()
            .height(Ob.FieldHeight)
            .clip(shape)
            .background(colors.card)
            .border(1.5.dp, colors.cardStroke, shape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { focusRequester.requestFocus() }
            .padding(horizontal = Ob.S16),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "+86",
            color = colors.ink2,
            fontSize = Ob.RowSize,
            fontWeight = FontWeight.SemiBold,
        )

        Spacer(Modifier.width(Ob.S12))

        // 竖分隔：1dp 宽、18dp 高
        Box(
            Modifier
                .width(1.dp)
                .height(18.dp)
                .background(colors.hairline)
        )

        Spacer(Modifier.width(Ob.S12))

        Box(Modifier.weight(1f)) {
            if (state.mobile.isEmpty()) {
                Text(
                    text = stringResource(id = R.string.onboarding_login_phone_hint),
                    color = colors.ink3,
                    fontSize = 16.sp,
                )
            }
            BasicTextField(
                value = state.mobile,
                onValueChange = { input ->
                    state.mobile = input.filter(Char::isDigit).take(11)
                    // 改号就清掉上一次的错误：错误是针对"那个号"的，不是针对这行的。
                    if (state.error != null) state.error = null
                },
                singleLine = true,
                textStyle = TextStyle(
                    color = colors.ink,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(colors.brand),
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Phone,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(
                    onDone = { onSend() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester),
            )
        }

        if (state.mobile.isNotEmpty()) {
            Spacer(Modifier.width(Ob.S4))
            val context = LocalContext.current
            // ★复用搜索框的清空键（AppleSearchField 的同款配方）：
            //   30dp 触摸区 + 19dp 图标 + tint 55%。
            //   必须用 Icon 而不是 Image —— ic_search_clear.xml 内置 fillColor="#FFFFFF"，
            //   只有 Icon 的 tint 能把它染成当前文字色。
            Box(
                Modifier
                    .size(30.dp)
                    .clip(CircleShape)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) {
                        Vibrator.click(context)
                        state.mobile = ""
                        state.error = null
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_search_clear),
                    contentDescription = stringResource(id = R.string.search_clear_input),
                    modifier = Modifier.size(19.dp),
                    tint = colors.ink2.copy(alpha = 0.55f),
                )
            }
        }
    }
}

// ── 扫码底部弹层 ────────────────────────────────────────────────────────────

/**
 * 扫码弹层（页内自绘，不开新窗口）。
 *
 * ★**关掉就必须真停轮询。** 本函数只在 `open == true` 时进入组合（判断在调用点），
 *   关闭走两段式：先播退场动画 → 动画结束回调 `onDismiss()` 把状态置 false →
 *   节点离开组合 → `DisposableEffect` 的 `onDispose` 取消 `pollJob`。
 *   如果只把面板 `translationY` 移出屏幕而节点还在组合里，`LaunchedEffect(Unit)`
 *   早就跑过了，轮询会一直在后台每 1.5 秒打一次接口 —— 这是最典型的"看不见的泄漏"。
 */
@Composable
internal fun OnboardingQrSheet(
    colors: OnboardingColors,
    onDismiss: () -> Unit,
    onLoggedIn: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val api = remember { KugouApiService.getInstance() }

    // ── 进出场动画 ──────────────────────────────────────────────────
    // ★打开时必须有一个"先 false 再 true"的开关把动画推起来。
    //   写成 `animateFloatAsState(targetValue = if (closing) 1f else 0f)` 的话，
    //   打开时 closing==false → target 就是 0，初值 0、终值 0，**永远不会动** ——
    //   表现就是"弹层凭空出现、只有退出有动画"（用户实测）。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }

    var closing by remember { mutableStateOf(false) }
    // open = 1 表示"贴底可见"，0 表示"完全在屏幕外下方"。
    val open by animateFloatAsState(
        targetValue = if (entered && !closing) 1f else 0f,
        // 原型：transform 0.44s cubic-bezier(.22,1,.36,1) 进 / 260ms 出。
        animationSpec = tween(
            durationMillis = if (closing) 260 else 440,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f),
        ),
        label = "qrSheetOpen"
    )
    val scrimAlpha by animateFloatAsState(
        targetValue = if (entered && !closing) 1f else 0f,
        animationSpec = tween(if (closing) 220 else 320),
        label = "qrSheetScrim"
    )

    // ★面板高度实测，不能写死。
    //   上一版是 `translationY = slide * 480.dp` —— 两个错：
    //     ① **符号反了**：slide 打开时是 1，乘 480 反而把面板往下推出屏幕，
    //        所以观感是"打开时从下往上冒出来的是退出、关的时候才滑上来"（用户报"方向反了"）；
    //     ② 480dp 是拍的：字体放大 / 小屏 / 横屏时面板真高会变，硬编码会让关闭时
    //        只露出半截或提前消失。
    //   正解：把**实测高度**当行程，open=0 时正好整块移到屏幕外。
    var panelHeightPx by remember { mutableFloatStateOf(0f) }

    fun requestClose() {
        if (closing) return
        closing = true
    }

    LaunchedEffect(closing) {
        if (closing) {
            delay(280)
            onDismiss()
        }
    }

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var phase by remember { mutableStateOf(QrPhase.LOADING) }
    // ★失败原因是**一等公民**，不能丢。
    //   上一版用 `getOrNull()` / `onFailure { phase = FAILED }` 把 IOException
    //   连同 HTTP 码一起扔掉，UI 只剩一句兜底的"网络请求失败，请重试" ——
    //   用户看到这句话什么也做不了，我也无从判断是"本地服务没起""被风控"
    //   还是"JSON 结构变了"。
    var failReason by remember { mutableStateOf<String?>(null) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    var inFlight by remember { mutableStateOf(false) }
    var savedToast by remember { mutableStateOf(false) }

    fun poll(key: String) {
        pollJob?.cancel()
        pollJob = scope.launch {
            var attempts = 0
            // 连续失败计数：网络长时间不通时不要空转到 120 次才报超时
            // （那要 3 分钟，用户以为卡死）。连续 3 次就明确失败。
            var consecutiveFailures = 0
            // ★不判 `!KugouAccountState.isLoggedIn`：从「设置›关于›重新查看引导」进来时
            //   用户可能已处于登录态，照样需要完整轮询这次新生成的二维码。
            while (attempts < 120) {
                delay(1500)
                attempts++
                val result = api.loginQrCheck(key)
                val st = result.getOrNull()
                if (st == null) {
                    consecutiveFailures++
                    if (consecutiveFailures >= 3) {
                        failReason = result.exceptionOrNull()?.friendlyMessage()
                        phase = QrPhase.FAILED
                        return@launch
                    }
                    continue
                }
                consecutiveFailures = 0
                when (st) {
                    4 -> {
                        afterLogin()
                        onLoggedIn()
                        return@launch
                    }
                    2, 803 -> phase = QrPhase.SCANNED
                    800, 0, 402 -> {
                        phase = QrPhase.EXPIRED
                        return@launch
                    }
                }
            }
            phase = QrPhase.EXPIRED
        }
    }

    fun refresh() {
        // ★不能在这里判 `phase == LOADING` 就 return：
        // 初始 phase 本来就是 LOADING，而首次加载正是从 LOADING 出发的 ——
        // 那样写等于"第一次调用把自己挡回去"，一次网络请求都不会发。
        // 真正要防的是"正在请求中又被点一次"，用一个显式的 inFlight 标记。
        if (inFlight) return
        inFlight = true
        savedToast = false
        phase = QrPhase.LOADING
        bitmap = null
        failReason = null
        scope.launch {
            try {
                val keyResult = api.loginQrKey()
                val key = keyResult.getOrNull()
                if (key == null) {
                    failReason = keyResult.exceptionOrNull().friendlyMessage("取二维码失败")
                    phase = QrPhase.FAILED
                    return@launch
                }
                api.loginQrCreate(key).fold(
                    onSuccess = { dataUrl ->
                        val b64 = dataUrl.substringAfter(",", "")
                        val bytes = runCatching { Base64.decode(b64, Base64.DEFAULT) }.getOrNull()
                        val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        if (bmp == null) {
                            failReason = "二维码图片解码失败"
                            phase = QrPhase.FAILED
                            return@fold
                        }
                        bitmap = bmp
                        phase = QrPhase.WAITING
                        poll(key)
                    },
                    onFailure = { e ->
                        failReason = e.friendlyMessage("生成二维码失败")
                        phase = QrPhase.FAILED
                    }
                )
            } finally {
                // 无论成功失败都放开：失败态的点击重试必须能再进来一次。
                inFlight = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }
    // 第二道保险：即使调用点忘了摘节点，离开组合也一定停轮询。
    DisposableEffect(Unit) {
        onDispose {
            pollJob?.cancel()
            pollJob = null
        }
    }

    val status = when (phase) {
        QrPhase.LOADING -> stringResource(id = R.string.kugou_qr_loading)
        QrPhase.WAITING -> stringResource(id = R.string.kugou_qr_waiting)
        QrPhase.SCANNED -> stringResource(id = R.string.kugou_qr_scanned)
        QrPhase.EXPIRED -> stringResource(id = R.string.kugou_qr_timeout)
        QrPhase.FAILED -> stringResource(id = R.string.kugou_qr_failed)
    }
    val retryable = phase == QrPhase.EXPIRED || phase == QrPhase.FAILED
    val canSave = bitmap != null && !retryable

    Box(Modifier.fillMaxSize()) {
        // ── 遮罩 ──
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha }
                .background(Color.Black.copy(alpha = 0.38f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { requestClose() }
        )

        // ── 面板 ──
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // 实测高度 → 关闭时刚好整块移到屏幕外。首帧高度还没测到时按 0 处理
                // （面板不可见的那一帧，位移为 0 也无所谓，因为 alpha 也还没起来）。
                .onSizeChanged { panelHeightPx = it.height.toFloat() }
                .graphicsLayer {
                    // open=1 → 0 位移（贴底可见）；open=0 → +panelHeight（推到屏幕下方）。
                    translationY = (1f - open) * panelHeightPx
                }
                .clip(RoundedCornerShape(topStart = Ob.SheetTopCorner, topEnd = Ob.SheetTopCorner))
                .background(colors.card)
                .navigationBarsPadding()
                // 面板本身吃掉点击，避免点到面板上的空白处穿透到遮罩把弹层关了
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(top = Ob.S12, bottom = Ob.S20)
                .padding(horizontal = Ob.S24),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // 抓手
            Box(
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(CircleShape)
                    .background(colors.ink.copy(alpha = 0.25f))
            )

            Spacer(Modifier.height(Ob.S20))

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = stringResource(id = R.string.onboarding_qr_sheet_title),
                        color = colors.ink,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = (-0.4).sp,
                    )
                    Spacer(Modifier.height(Ob.S4))
                    Text(
                        text = stringResource(id = R.string.onboarding_qr_sheet_desc),
                        color = colors.ink2,
                        fontSize = Ob.DescSize,
                        lineHeight = Ob.DescLine,
                    )
                }
                Spacer(Modifier.width(Ob.S12))
                val context2 = LocalContext.current
                Box(
                    Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(colors.track)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            Vibrator.click(context2)
                            requestClose()
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // 关闭键也用搜索框那个清空图标：同一个"×"，同一个尺寸口径，
                    // 不要为同一个符号维护第二个自绘实现。
                    Icon(
                        painter = painterResource(id = R.drawable.ic_search_clear),
                        contentDescription = stringResource(id = R.string.onboarding_qr_sheet_title),
                        modifier = Modifier.size(18.dp),
                        tint = colors.ink2.copy(alpha = 0.55f),
                    )
                }
            }

            Spacer(Modifier.height(Ob.S20))

            // ── 二维码板 ──
            Box(contentAlignment = Alignment.Center) {
                // 柔光：把板面"托"在页面上（不是阴影，是"光在附近"）。
                // ★这里曾经是一个 240dp 的方形柔光 Box（radialGradient）。
                //   圆形渐变画在方形节点里，半径外的 Transparent 在四个角留下色阶硬边,
                //   于是二维码四周出现一个**粉色直角框**（用户实测"很难看"）。
                //   正解：删掉柔光，用一个真投影把白板托起来 —— 投影跟着圆角走，不会有直角。

                Box(
                    Modifier
                        .size(176.dp)
                        // ★shadow 必须在 clip/background **之前**：它自己按 shape 画在节点外侧，
                        //   写在后面会被 clip 裁掉，等于没画。
                        .shadow(
                            elevation = 18.dp,
                            shape = YosRoundedCornerShape(Ob.SheetCorner),
                            ambientColor = Color.Black.copy(alpha = 0.10f),
                            spotColor = Color.Black.copy(alpha = 0.18f),
                        )
                        .clip(YosRoundedCornerShape(Ob.SheetCorner))
                        // 板面恒为白：二维码必须有最高对比，深色下也不能跟着变深。
                        .background(Color.White)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            enabled = retryable,
                        ) { refresh() }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    val bmp = bitmap
                    when {
                        bmp != null && !retryable -> Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = stringResource(id = R.string.kugou_login_qr_desc),
                            modifier = Modifier.fillMaxSize(),
                        )

                        phase == QrPhase.LOADING -> CircularProgressIndicator(
                            modifier = Modifier.size(22.dp),
                            color = Color(0xFF8E8E93),
                            strokeWidth = 2.dp,
                        )

                        else -> Text(
                            text = stringResource(id = R.string.kugou_qr_regenerate),
                            color = Color(0xFF1D1D1F),
                            fontSize = Ob.DescSize,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
            }

            Spacer(Modifier.height(Ob.S16))

            Text(
                text = status,
                color = colors.ink,
                fontSize = Ob.RowSize,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.Center,
            )

            // ★把真实失败原因显示出来。
            //   兜底文案（"网络请求失败，请重试"）本身不含任何可行动信息，
            //   所以失败时在下面补一行 11sp 灰字说明是哪一环出了问题 ——
            //   用户知道该等还是该检查网络，我也能从截图直接定位。
            if (phase == QrPhase.FAILED && failReason != null) {
                Spacer(Modifier.height(Ob.S6))
                Text(
                    text = failReason!!,
                    color = colors.ink3,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = Ob.S8),
                )
            }

            Spacer(Modifier.height(Ob.S16))

            // ── 保存到相册 ──
            // API 29+ 走 MediaStore，无需任何权限；<29 需要 WRITE_EXTERNAL_STORAGE
            // （本工程未声明），所以那个分支只提示不支持、不弹权限框 —— 不要为了
            // 一个引导页里的次要功能去加一个已被 Google 废弃的存储权限。
            //
            // ★保存**不关闭弹层**：用户可能想再存一次、或者接着看轮询状态。
            //   只把文案切成"已保存"并在 1.6 秒后切回来（不清 toast 标志就再也点不动）。
            val saveActive = canSave && !savedToast
            MochiButton(
                onClick = {
                    val bmp = bitmap ?: return@MochiButton
                    Vibrator.click(context)
                    scope.launch {
                        val ok = saveQrToGallery(context, bmp)
                        if (!ok) {
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.kugou_qr_failed),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                            return@launch
                        }
                        savedToast = true
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.onboarding_qr_saved),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = saveActive,
                colors = MochiButtonColors(
                    container = colors.brand,
                    content = colors.onBrand,
                    outline = Color.Transparent,
                    pressedContainer = Color.Transparent,
                    glow = Color.White.copy(alpha = if (colors.dark) 0.22f else 0.35f),
                    disabledContainer = colors.track,
                    disabledContent = colors.ink3,
                ),
                minHeight = 50.dp,
            ) {
                DownloadGlyph(color = LocalContentColor.current)
                MochiBlurText(
                    text = stringResource(
                        id = if (savedToast) R.string.onboarding_qr_saved else R.string.onboarding_qr_save
                    ),
                    style = TextStyle(
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    ),
                    maxLines = 1,
                    contentAlignment = Alignment.Center,
                )
            }
        }
    }
}

/**
 * 把二维码写进系统相册的 `Pictures/Sonify`。
 *
 * API 29+ 用 MediaStore 的 `RELATIVE_PATH`，**不需要任何权限**（分区存储）。
 * API < 29 需要 `WRITE_EXTERNAL_STORAGE`，本工程没有声明，所以直接返回 false ——
 * 调用点据此提示"已保存"还是失败，不会崩。
 *
 * 返回 true 表示确实写成功了。
 */
private suspend fun saveQrToGallery(context: android.content.Context, bitmap: Bitmap): Boolean =
    withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return@withContext false
        runCatching {
            val name = "sonify_qr_${System.currentTimeMillis()}.png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, name)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/Sonify"
                )
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return@runCatching false
            var out: OutputStream? = null
            try {
                out = resolver.openOutputStream(uri)
                if (out == null) return@runCatching false
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            } finally {
                out?.close()
            }
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        }.getOrDefault(false)
    }

// ── 小图标（Canvas 画，不引资源）─────────────────────────────────────────────

/** 下载/保存：托盘 + 下箭头。16px 描边口径。 */
@Composable
private fun DownloadGlyph(color: Color) {
    androidx.compose.foundation.Canvas(Modifier.size(16.dp)) {
        val w = size.width
        val h = size.height
        val sw = 1.8.dp.toPx()
        val cap = androidx.compose.ui.graphics.StrokeCap.Round
        // 箭头杆
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.08f),
            androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.62f), strokeWidth = sw, cap = cap)
        // 箭头两撇
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.28f, h * 0.42f),
            androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), strokeWidth = sw, cap = cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.72f, h * 0.42f),
            androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.64f), strokeWidth = sw, cap = cap)
        // 托盘
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.14f, h * 0.78f),
            androidx.compose.ui.geometry.Offset(w * 0.14f, h * 0.92f), strokeWidth = sw, cap = cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.14f, h * 0.92f),
            androidx.compose.ui.geometry.Offset(w * 0.86f, h * 0.92f), strokeWidth = sw, cap = cap)
        drawLine(color, androidx.compose.ui.geometry.Offset(w * 0.86f, h * 0.92f),
            androidx.compose.ui.geometry.Offset(w * 0.86f, h * 0.78f), strokeWidth = sw, cap = cap)
    }
}

/**
 * 错误文字颜色。
 *
 * 刻意不用 `MaterialTheme.colorScheme.error`：引导页是黑白场域 + 单一品牌色，
 * 再来一个红会比主键更抢眼。取一个低饱和的提示红即可。
 */
internal val ErrorRed = Color(0xFFD9544D)

/**
 * 把底层异常折成一句用户能读、我也能诊断的话。
 *
 * ★判据：**永远不要把兜底文案当成唯一信息**。
 *   "网络请求失败，请重试"这句话对用户是废话（重试还是失败），对我也是废话
 *   （不知道是哪一环）。所以这里按异常类型分出可区分的原因 ——
 *   它同时出现在 UI 上（11sp 灰字）和 logcat / 诊断文件里。
 */
private fun Throwable?.friendlyMessage(fallback: String = "网络请求失败"): String {
    if (this == null) return fallback
    val msg = message.orEmpty()
    return when {
        this is java.net.SocketTimeoutException ||
            msg.contains("timeout", ignoreCase = true) -> "$fallback：连接超时"
        this is java.net.UnknownHostException -> "$fallback：无法解析地址"
        this is java.net.ConnectException ||
            msg.contains("Failed to connect", ignoreCase = true) -> "$fallback：本地服务未响应"
        msg.contains("本地服务未启动") -> "本地服务未启动，请稍后重试"
        msg.startsWith("HTTP ") -> "$fallback（$msg）"
        msg.isNotBlank() -> "$fallback：$msg"
        else -> fallback
    }
}
