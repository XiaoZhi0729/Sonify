package yos.music.player.ui.onboarding

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.native.KugouApiService
import yos.music.player.native.KugouLoginAccountCandidate
import yos.music.player.native.KugouLoginException
import yos.music.player.native.PhoneLoginResult
import yos.music.player.ui.theme.YosRoundedCornerShape

/**
 * 屏 2 · 输入验证码。
 *
 * ── 为什么是独立一屏 ─────────────────────────────────────────────
 * 上一版把"手机号 + 验证码"塞在同一屏的两行下划线里。那样有两个后果：
 * ① 两行都填完之前，用户不知道自己在哪一步；② 手机号键盘和数字键盘
 * 是同一条 IME，切换时整屏会跳一次。
 *
 * 独立成屏之后，这一屏只有一个动作，键盘从进屏那一刻就调起来，输满就走。
 *
 * ── 6 个格子其实只有一个输入框 ────────────────────────────────────
 * 六个格子是**画出来的**，真正的输入是一个铺满整片区域的透明 `BasicTextField`。
 * 这样：
 *   · 系统短信自动填充（`autocomplete` 语义）能整串灌进来
 *   · 删除/粘贴/光标移动都不用自己实现
 *   · 无障碍树里是一个"6 位验证码输入框"，而不是六个各管一位的怪控件
 */
private enum class OtpPhase { IDLE, VERIFYING, SUCCESS, ERROR }

@Composable
internal fun OtpScreen(
    colors: OnboardingColors,
    state: LoginState,
    onVerified: () -> Unit,
    onResend: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val api = remember { KugouApiService.getInstance() }
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }

    var code by remember { mutableStateOf("") }
    var phase by remember { mutableStateOf(OtpPhase.IDLE) }
    // 记住已提交过的码值：避免重组期间重复请求。
    var submittedFor by remember { mutableStateOf<String?>(null) }
    var candidates by remember { mutableStateOf<List<KugouLoginAccountCandidate>?>(null) }
    var localError by remember { mutableStateOf<String?>(null) }

    val shakeX = remember { Animatable(0f, Float.VectorConverter) }
    val errorDrop = remember { Animatable(0f, Float.VectorConverter) }

    // 进屏后默认直接调起系统数字键盘
    LaunchedEffect(Unit) {
        delay(220)
        focusRequester.requestFocus()
        keyboard?.show()
    }

    // 失败震动 + 数字掉下清空，随后复位并重新聚焦键盘（单段 keyframes，graphicsLayer 零重组）
    LaunchedEffect(phase) {
        if (phase == OtpPhase.ERROR) {
            errorDrop.snapTo(0f)
            shakeX.snapTo(0f)
            shakeX.animateTo(
                targetValue = 0f,
                animationSpec = keyframes {
                    durationMillis = 320
                    0f at 0
                    -11f at 45
                    11f at 95
                    -8f at 145
                    8f at 195
                    -4f at 245
                    3f at 285
                    0f at 320
                },
            )
            errorDrop.animateTo(
                targetValue = 1f,
                animationSpec = tween(220, easing = CubicBezierEasing(0.4f, 0f, 1f, 1f)),
            )
            code = ""
            withFrameNanos { }
            errorDrop.snapTo(0f)
            submittedFor = null
            phase = OtpPhase.IDLE
            delay(40)
            focusRequester.requestFocus()
            keyboard?.show()
        } else {
            if (phase != OtpPhase.VERIFYING) {
                shakeX.snapTo(0f)
            }
            errorDrop.snapTo(0f)
        }
    }

    // 满 6 位自动核验：先进入黑色/深色核验加载态，再按真实结果走绿色通过或红色震落。
    LaunchedEffect(code) {
        if (code.length != 6) return@LaunchedEffect
        if (phase == OtpPhase.VERIFYING || phase == OtpPhase.SUCCESS || candidates != null) return@LaunchedEffect
        if (submittedFor == code) return@LaunchedEffect

        submittedFor = code
        phase = OtpPhase.VERIFYING
        localError = null
        keyboard?.hide()
        val verifyStartedAt = System.currentTimeMillis()
        val minLoadingMs = 280L

        api.loginByCellphone(state.mobile, code, null).fold(
            onSuccess = { result ->
                val elapsed = System.currentTimeMillis() - verifyStartedAt
                if (elapsed < minLoadingMs) delay(minLoadingMs - elapsed)
                when (result) {
                    is PhoneLoginResult.Success -> {
                        phase = OtpPhase.SUCCESS
                        Vibrator.longClick(context)
                        afterLogin()
                        delay(Ob.OtpWaveMs)
                        onVerified()
                    }

                    is PhoneLoginResult.NeedChooseAccount -> {
                        candidates = result.candidates
                    }

                    is PhoneLoginResult.Failed -> {
                        localError = result.errorMsg?.takeIf { it.isNotBlank() }
                            ?: context.getString(R.string.onboarding_otp_wrong)
                        Vibrator.longClick(context)
                        phase = OtpPhase.ERROR
                    }
                }
            },
            onFailure = { e ->
                val elapsed = System.currentTimeMillis() - verifyStartedAt
                if (elapsed < minLoadingMs) delay(minLoadingMs - elapsed)
                localError = (e as? KugouLoginException)?.errorMsg?.takeIf { it.isNotBlank() }
                    ?: context.getString(R.string.onboarding_otp_wrong)
                Vibrator.longClick(context)
                phase = OtpPhase.ERROR
            },
        )
    }

    fun pickAccount(userid: String) {
        val current = code
        candidates = null
        phase = OtpPhase.VERIFYING
        localError = null
        scope.launch {
            api.loginByCellphone(state.mobile, current, userid).fold(
                onSuccess = { result ->
                    when (result) {
                        is PhoneLoginResult.Success -> {
                            phase = OtpPhase.SUCCESS
                            Vibrator.longClick(context)
                            afterLogin()
                            delay(Ob.OtpWaveMs)
                            onVerified()
                        }

                        is PhoneLoginResult.NeedChooseAccount -> candidates = result.candidates
                        is PhoneLoginResult.Failed -> {
                            localError = result.errorMsg?.takeIf { it.isNotBlank() }
                                ?: context.getString(R.string.kugou_phone_login_failed)
                            Vibrator.longClick(context)
                            phase = OtpPhase.ERROR
                        }
                    }
                },
                onFailure = { e ->
                    localError = (e as? KugouLoginException)?.errorMsg?.takeIf { it.isNotBlank() }
                        ?: context.getString(R.string.kugou_qr_failed)
                    Vibrator.longClick(context)
                    phase = OtpPhase.ERROR
                },
            )
        }
    }

    fun dismissCandidates() {
        candidates = null
        phase = OtpPhase.IDLE
        submittedFor = null
        focusRequester.requestFocus()
        keyboard?.show()
    }

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
                    .heightIn(min = availableH)
            ) {
                Spacer(Modifier.height(Ob.S8))

                ScreenTitle(
                    title = stringResource(id = R.string.onboarding_otp_title),
                    desc = stringResource(
                        id = R.string.onboarding_otp_desc,
                        maskPhone(state.mobile)
                    ),
                    colors = colors,
                )

                // 直接排在标题下方（靠上布局，不再居中悬空）
                Spacer(Modifier.height(Ob.S28))

                // ── 6 个格子 + 底层透明输入框 ──
                OtpCells(
                    colors = colors,
                    code = code,
                    phase = phase,
                    shakeOffsetProvider = { shakeX.value },
                    errorDropProvider = { errorDrop.value },
                    focusRequester = focusRequester,
                    onValueChange = { input ->
                        if (phase == OtpPhase.VERIFYING || phase == OtpPhase.SUCCESS || phase == OtpPhase.ERROR) {
                            return@OtpCells
                        }
                        val digits = input.filter(Char::isDigit).take(6)
                        if (digits != code) {
                            submittedFor = null
                            if (localError != null) localError = null
                        }
                        code = digits
                    },
                )

                Spacer(Modifier.height(Ob.S16))

                // 核验态或错误提示（紧跟输入框下方，样式克制）
                val err = localError ?: state.error
                if (phase == OtpPhase.VERIFYING) {
                    Text(
                        text = stringResource(id = R.string.onboarding_otp_verifying),
                        color = colors.ink2,
                        fontSize = Ob.DescSize,
                        lineHeight = Ob.DescLine,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Ob.S12))
                } else if (err != null) {
                    Text(
                        text = err,
                        color = ErrorRed.copy(alpha = 0.88f),
                        fontSize = Ob.DescSize,
                        lineHeight = Ob.DescLine,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(Ob.S12))
                }

                // 重新发送放在验证码框正下方，紧随状态区
                val resendEnabled = state.countdown == 0 && !state.sending &&
                    phase != OtpPhase.VERIFYING && phase != OtpPhase.SUCCESS
                Box(
                    Modifier.fillMaxWidth(),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    Text(
                        text = if (state.countdown > 0) {
                            stringResource(id = R.string.onboarding_otp_resend_countdown, state.countdown)
                        } else {
                            stringResource(id = R.string.onboarding_otp_resend)
                        },
                        color = if (resendEnabled) colors.brand else colors.ink3,
                        fontSize = Ob.DescSize,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                enabled = resendEnabled,
                            ) {
                                Vibrator.click(context)
                                onResend()
                            }
                            .padding(vertical = Ob.S8),
                    )
                }

                Spacer(Modifier.weight(1f))
            }
        }
    }

    candidates?.let { list ->
        AccountPickerDialog(
            colors = colors,
            accounts = list,
            onPick = { account -> pickAccount(account.userid) },
            onDismiss = { dismissCandidates() },
        )
    }
}

/**
 * 六个格子 + 底层透明输入。
 *
 * 为波浪跳起、数字弹入（blur+scale）与出错抖落预留充足冗余边界，绝不裁切。
 */
@Composable
private fun OtpCells(
    colors: OnboardingColors,
    code: String,
    phase: OtpPhase,
    shakeOffsetProvider: () -> Float,
    errorDropProvider: () -> Float,
    focusRequester: FocusRequester,
    onValueChange: (String) -> Unit,
) {
    var blink by remember { mutableStateOf(1f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        while (true) {
            val now = withFrameNanos { it }
            val t = ((now - start) / 1_000_000_000f) % 1.0f
            blink = if (t < 0.5f) 1f else 0.2f
        }
    }

    val waveStarted = phase == OtpPhase.SUCCESS
    val verifying = phase == OtpPhase.VERIFYING
    val isError = phase == OtpPhase.ERROR

    Box(
        Modifier
            .fillMaxWidth()
            .layout { measurable, constraints ->
                val vPad = Ob.S20.roundToPx()
                val placeable = measurable.measure(constraints)
                val h = (placeable.height - vPad * 2).coerceAtLeast(0)
                layout(placeable.width, h) {
                    placeable.placeRelative(0, -vPad)
                }
            }
            .padding(vertical = Ob.S20)
            .graphicsLayer {
                clip = false
                translationX = shakeOffsetProvider().dp.toPx()
            }
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Ob.OtpGap),
        ) {
            repeat(6) { i ->
                val ch = code.getOrNull(i)
                val isFilled = ch != null
                val isCursor = !isFilled && i == code.length && phase == OtpPhase.IDLE

                // 单独追踪每个槽位字符的进入/删除动画（对齐原型 digitPop，支持退格柔和退出）
                var shownChar by remember(i) { mutableStateOf(ch) }
                val digitAnim = remember(i) { Animatable(if (ch != null) 1f else 0f, Float.VectorConverter) }
                LaunchedEffect(ch) {
                    if (ch != null) {
                        val changed = shownChar != ch
                        shownChar = ch
                        if (changed) {
                            digitAnim.snapTo(0f)
                            digitAnim.animateTo(
                                1f,
                                spring(dampingRatio = 0.52f, stiffness = 760f, visibilityThreshold = 0.001f)
                            )
                        } else {
                            digitAnim.snapTo(1f)
                        }
                    } else if (shownChar != null) {
                        if (phase == OtpPhase.ERROR || errorDropProvider() > 0.5f) {
                            digitAnim.snapTo(0f)
                            shownChar = null
                        } else {
                            digitAnim.animateTo(0f, tween(110, easing = FastOutSlowInEasing))
                            shownChar = null
                        }
                    }
                }

                // 核验成功波浪：满格通过后按 55ms 错峰跳起再落回原位，上方留有 20dp 冗余绝不裁切
                val wave = remember(i) { Animatable(0f, Float.VectorConverter) }
                LaunchedEffect(waveStarted, i) {
                    if (!waveStarted) {
                        wave.snapTo(0f)
                        return@LaunchedEffect
                    }
                    delay(i * 55L)
                    wave.animateTo(1f, tween(160, easing = FastOutSlowInEasing))
                    wave.animateTo(0f, spring(dampingRatio = 0.45f, stiffness = 520f))
                }

                val targetStroke = when {
                    waveStarted -> colors.success
                    isError -> ErrorRed
                    verifying -> colors.ink
                    isFilled -> colors.brand.copy(alpha = 0.42f)
                    isCursor -> colors.brand
                    else -> colors.cardStroke
                }
                val strokeColor by animateColorAsState(targetStroke, tween(160), label = "otpStroke$i")

                Box(
                    Modifier
                        .weight(1f)
                        .height(Ob.OtpCellHeight)
                        .graphicsLayer {
                            clip = false
                            val waveScale = 1f + 0.06f * wave.value
                            scaleX = waveScale
                            scaleY = waveScale
                            translationY = -wave.value * 11.dp.toPx() - if (isCursor) 1.5.dp.toPx() else 0f
                        }
                        .clip(YosRoundedCornerShape(Ob.OtpCellCorner))
                        .background(colors.card)
                        .border(
                            width = if (isCursor || waveStarted || verifying || isError) 1.8.dp else 1.2.dp,
                            color = strokeColor,
                            shape = YosRoundedCornerShape(Ob.OtpCellCorner),
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    when {
                        shownChar != null -> {
                            val p = digitAnim.value.coerceIn(0f, 1f)
                            val digitBlur = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                val r = (4.dp * (1f - p))
                                if (r > 0.05.dp) Modifier.blur(r, BlurredEdgeTreatment.Unbounded) else Modifier
                            } else {
                                Modifier
                            }
                            Text(
                                text = shownChar.toString(),
                                color = when {
                                    waveStarted -> colors.success
                                    isError -> ErrorRed
                                    else -> colors.ink
                                },
                                fontSize = Ob.OtpCellText,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .graphicsLayer {
                                        clip = false
                                        val drop = errorDropProvider().coerceIn(0f, 1f)
                                        alpha = p * (1f - drop)
                                        val s = (0.55f + 0.45f * p) * (1f - 0.22f * drop)
                                        scaleX = s
                                        scaleY = s
                                        translationY = (1f - p) * 10.dp.toPx() + drop * 18.dp.toPx()
                                    }
                                    .then(digitBlur),
                            )
                        }

                        isCursor -> Box(
                            Modifier
                                .width(2.5.dp)
                                .height(24.dp)
                                .clip(CircleShape)
                                .graphicsLayer { alpha = blink }
                                .background(colors.brand)
                        )
                    }
                }
            }
        }

        BasicTextField(
            value = code,
            onValueChange = onValueChange,
            singleLine = true,
            readOnly = phase != OtpPhase.IDLE,
            textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
            cursorBrush = SolidColor(Color.Transparent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            modifier = Modifier
                .matchParentSize()
                .focusRequester(focusRequester),
        )
    }
}

/**
 * 多账号选择（一个手机号绑定了多个酷狗账号，error_code=34175）。
 *
 * 抽成公共组件：引导页的验证码屏与设置页的登录页都是同一条流程。
 *
 * ★取消（点遮罩 / 按返回）必须走调用点的 `onDismiss`，让调用点把
 *   `verifying` / `submittedFor` 复位 —— 否则整个登录流程会锁在"验证中"。
 */
@Composable
internal fun AccountPickerDialog(
    colors: OnboardingColors,
    accounts: List<KugouLoginAccountCandidate>,
    onPick: (KugouLoginAccountCandidate) -> Unit,
    onDismiss: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(colors.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = Ob.S20)
                .clip(YosRoundedCornerShape(Ob.SheetCorner))
                .background(colors.card)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(Ob.S20),
        ) {
            Text(
                text = stringResource(id = R.string.kugou_phone_choose_account_title),
                color = colors.ink,
                fontSize = Ob.RowSize,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Ob.S4))
            Text(
                text = stringResource(id = R.string.kugou_phone_choose_account_subtitle),
                color = colors.ink3,
                fontSize = Ob.DescSize,
                lineHeight = Ob.DescLine,
            )
            Spacer(Modifier.height(Ob.S16))
            accounts.forEach { account ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(YosRoundedCornerShape(Ob.S12))
                        .background(colors.track)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onPick(account) }
                        .padding(horizontal = Ob.S14, vertical = Ob.S12),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = account.nickname?.takeIf { it.isNotBlank() }
                                ?: stringResource(id = R.string.kugou_login_userid, account.userid),
                            color = colors.ink,
                            fontSize = Ob.RowSize,
                        )
                        if (!account.nickname.isNullOrBlank()) {
                            Spacer(Modifier.height(Ob.S2))
                            Text(
                                text = stringResource(id = R.string.kugou_login_userid, account.userid),
                                color = colors.ink3,
                                fontSize = Ob.DescSize,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(Ob.S8))
            }
        }
    }
}

/**
 * 手机号脱敏：`+86 138 •••• 8888`。
 *
 * 位数不够时退化为原文（比如回退到上一屏改号再进来）。
 * 判据：这一行是给用户**确认自己没输错号**用的，所以首尾要真、中间打码。
 */
private fun maskPhone(mobile: String): String =
    if (mobile.length == 11) "+86 ${mobile.take(3)} •••• ${mobile.takeLast(4)}"
    else "+86 $mobile"
