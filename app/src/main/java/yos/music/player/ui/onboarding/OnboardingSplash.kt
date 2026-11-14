package yos.music.player.ui.onboarding

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import yos.music.player.R
import yos.music.player.ui.theme.YosRoundedCornerShape

/**
 * 收尾过渡层：欢迎使用 Sonify → 淡入主页。
 *
 * ── 为什么要它 ────────────────────────────────────────────────────
 * 上一版点「开始听歌」是硬切：引导页直接消失，主页"啪"地出现。
 * 那一下把整个引导的所有精心布置（渐变、手写、错峰入场）全部否掉了 ——
 * 用户记住的是最后一帧，而最后一帧是"闪"。
 *
 * 这一版把它做成一个**真正的收束**：再打一次品牌签名（图标 + 两行手写），
 * 停 1.5 秒让人读完，然后整层淡出。因为主页已经在它下面了，
 * 淡出本身就是交叉淡入 —— 不需要再做第二个动画。
 *
 * ── 时间轴（四段，不许合并）──────────────────────────────────────
 *   ① 0ms      层铺上（不透明 bgTop，挡住引导页）
 *   ② 0~700ms  图标 + 两行手写错峰上浮（同一条时钟，[Ob.EnterMs] 口径）
 *   ③ 700ms+   停 [Ob.SplashHoldMs]（1500ms）
 *   ④ 之后     整层 alpha 1→0（450ms）→ 回调 onDone()
 *
 * ★返回键由调用点用 `BackHandler(enabled = splashActive) {}` 吞掉 ——
 *   这一层不该有"取消"的语义，用户已经点了开始，就该走完。
 *   同时这一层自己 clickable 兜底，吃掉所有落空的点击。
 */
@Composable
internal fun OnboardingTransitionSplash(
    colors: OnboardingColors,
    onDone: () -> Unit,
) {
    // 层本身的淡入：0 → 1。
    // ★不能写 `animateFloatAsState(targetValue = 1f)` 了事：它在首次组合时
    //   直接取 target 作为初值，等于"没有淡入"—— 过渡层会瞬间出现，
    //   与引导内容那 450ms 的淡出叠加就成了硬闪。
    //   必须用一个先 false 后 true 的开关把它推起来。
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val layerAlpha by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = tween(380),
        label = "splashLayer"
    )

    // 入场时钟：与首屏同一套 enter()，所以是"又见到一次同样的落笔"。
    // ★用 Animatable 而不是手写 `withFrameNanos` 循环（那个会卡在 0，见 HelloScreen 注释）。
    val clock = remember { Animatable(0f, Float.VectorConverter) }
    var leaving by remember { mutableStateOf(false) }
    val exitAlpha by animateFloatAsState(
        targetValue = if (leaving) 0f else 1f,
        animationSpec = tween(450, easing = FastOutSlowInEasing),
        label = "splashExit"
    )

    LaunchedEffect(Unit) {
        clock.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = Ob.EnterMs.toInt(),
                easing = androidx.compose.animation.core.LinearEasing
            )
        )
    }
    val c = clock.value

    // ② 停留计时**从本层出现那一刻起算**，不与入场串联。
    //    ★串起来（等入场跑完再 delay）总时长会变成 620 + 1500 + 470 ≈ 2.6s，
    //      比规范里的"停留约 1.5 秒"长了近一倍 —— 用户会觉得卡在那里。
    //      正确口径：出现后 1.5 秒开始淡出，入场动画在这段时间里自己跑完。
    LaunchedEffect(Unit) {
        delay(Ob.SplashHoldMs)
        leaving = true
        delay(470)
        onDone()
    }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = layerAlpha * exitAlpha }
            // 吃掉所有落空点击：这一层在的时候底下的引导页不该再收到触摸。
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { }
            // ★不透明：它要挡住引导页与主页之间的所有内容。
            .background(colors.bgTop),
        contentAlignment = Alignment.Center,
    ) {
        // 一层极淡的品牌色光晕，和引导页背景同一套语言
        Canvas(Modifier.fillMaxSize()) {
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(
                        colors.brand.copy(alpha = colors.auraStrong * 0.8f),
                        Color.Transparent
                    ),
                    center = Offset(size.width * 0.5f, size.height * 0.34f),
                    radius = size.width * 1.1f
                ),
                radius = size.width * 1.1f,
                center = Offset(size.width * 0.5f, size.height * 0.34f)
            )
        }

        Column(
            Modifier.padding(horizontal = Ob.Gutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            val iconIn = enter(c, 0f)
            HeroIcon(
                glowAlpha = iconIn,
                imageAlpha = iconIn,
                modifier = Modifier
                    .graphicsLayer {
                        clip = false
                        translationY = (1f - iconIn) * 14.dp.toPx()
                        val s = 0.94f + 0.06f * iconIn
                        scaleX = s
                        scaleY = s
                    }
            )

            Spacer(Modifier.height(Ob.S24))

            HandwriteLine(
                text = stringResource(id = R.string.onboarding_hello_greeting),
                size = 40.sp,
                color = colors.ink,
                progress = enter(c, 0.16f),
                liftDp = 14f,
            )

            Spacer(Modifier.height(Ob.S4))

            HandwriteLine(
                text = stringResource(id = R.string.onboarding_hello_brand),
                size = 48.sp,
                color = colors.ink,
                progress = enter(c, 0.30f),
                liftDp = 14f,
            )
        }
    }
}
