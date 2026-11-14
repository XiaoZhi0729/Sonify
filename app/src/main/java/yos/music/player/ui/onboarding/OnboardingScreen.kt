package yos.music.player.ui.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import yos.music.player.R
import yos.music.player.data.libraries.MusicLibrary
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.ui.pages.settings.performance.userinterface.ColorModeOptions
import yos.music.player.ui.theme.HelloFontFamily
import yos.music.player.ui.theme.HelloTextFeatures
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.liquid.YosSwitch

/*
 * 七屏内容。骨架在 [OnboardingFrame]（舞台：顶栏 + 内容区 + 固定底栏），
 * 本文件只负责每一段的排版与"这一屏想让人记住的一句话"。
 *
 * ── 每一屏存在的理由（写下来，防止将来又长出"纯粹仪式"的屏）──────
 *   0 引言：告诉人这是什么、为什么值得多花 20 秒。用产品的笔迹说话。
 *   1 登录：一个可跳过的动作，入口只有手机号（扫码收进弹层）。
 *   2 验证码：把"等一条短信"变成一屏——6 个格子，输满就走。
 *   3 外观：浅色 / 深色 / 自动独立一屏，带主页缩略舞台即时预览明暗效果。
 *   4 歌词：两个开关（动态背景、歌词字号），每个都当场作用在上面的歌词舞台上。
 *   5 玻璃：一个开关配不上这个 App 最有辨识度的材质，所以给它一屏。
 *   6 收束：讲清"你现在拥有什么"，顺手把本机音乐接进来。
 */

// ── 屏 0 · 引言 ─────────────────────────────────────────────────────────────

/**
 * 屏 0：品牌图标 + 两行手写大字 + 一句导语。
 *
 * 字体是 Apple hello 屏那款笔迹的重建版（[HelloFontFamily]）。CJK 只覆盖
 * 「欢迎使用」「你好」，所以说明文字用系统字体，只有大字用它。
 *
 * ── 入场：手写"落笔"的层次 ──────────────────────────────────────
 * 图标先淡入上浮 → 「欢迎使用」→「Sonify」→ 导语只淡入（不上浮）。
 * 全部由**同一个时钟**驱动（[Ob.EnterMs]），各行错峰由各自延迟派生 ——
 * 两条独立时间轴必然错位，本工程已踩过。
 *
 * ★★三条硬约束（违反任一条中文就会退化成「四条斜杠」）：
 *   ① 文本必须**整串**是「欢迎使用」—— 真字形是 GSUB 连字产物，
 *      拆字/插空格/插 ZWNJ 都会让整形器拿不到连字；
 *   ② 必须显式开 `ccmp`（[HelloTextFeatures]）；
 *   ③ ★**动画里绝对不能动 letterSpacing**。逐帧改字距会让整形器每帧重新整形，
 *      连字在字距非零的那几帧里消失 —— 表现就是开头闪过四条斜杠。
 */
@Composable
internal fun HelloScreen(colors: OnboardingColors) {
    val context = LocalContext.current
    val animate = remember {
        runCatching {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            )
        }.getOrDefault(1f) > 0f
    }

    // ★用 Animatable 而不是手写 `withFrameNanos` 循环驱动入场时钟。
    //
    //   手写循环有两个坑（真机上"整屏空白、只有底栏按钮"就是它们）：
    //   ① 第一帧 `withFrameNanos` 可能因组合/布局尚未稳定而不推进，
    //      配上 `while(true)` 的写法一旦被异常打断就永远停在 0；
    //   ② 它绕过了 Compose 的动画时钟，动画时长缩放（开发者选项）对它无效。
    //   Animatable 走标准动画时钟，`tween` 自带 duration scale，且不会卡 0。
    val clock = remember { Animatable(if (animate) 0f else 1f, Float.VectorConverter) }
    LaunchedEffect(animate) {
        if (!animate) {
            clock.snapTo(1f)
            return@LaunchedEffect
        }
        clock.animateTo(
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = Ob.EnterMs.toInt(),
                easing = androidx.compose.animation.core.LinearEasing
            )
        )
    }
    val c = clock.value

    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Ob.Gutter)
    ) {
        Spacer(Modifier.weight(1f))

        Column(
            Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // ── 品牌图标 ──
            // 80dp / 圆角 22dp。它是这一屏唯一一张位图，所以必须由它开头。
            val iconIn = enter(c, 0f)
            HeroIcon(
                glowAlpha = iconIn,
                imageAlpha = iconIn,
                modifier = Modifier
                    .graphicsLayer {
                        clip = false
                        translationY = (1f - iconIn) * 16.dp.toPx()
                        val s = 0.94f + 0.06f * iconIn
                        scaleX = s
                        scaleY = s
                    }
            )

            Spacer(Modifier.height(Ob.S28))

            // 「欢迎使用」：比品牌名小一号，是"问候"不是"标题"。
            HandwriteLine(
                text = stringResource(id = R.string.onboarding_hello_greeting),
                size = Ob.HelloCjkSize,
                color = colors.ink,
                progress = enter(c, 0.12f),
                liftDp = 14f,
            )

            Spacer(Modifier.height(Ob.S4))

            // 「Sonify」后跟，字号更大 —— 主标题在后，视线落点是它。
            HandwriteLine(
                text = stringResource(id = R.string.onboarding_hello_brand),
                size = Ob.HelloLatinSize,
                color = colors.ink,
                progress = enter(c, 0.26f),
                liftDp = 14f,
            )
        }

        Spacer(Modifier.weight(1f))
    }
}

/**
 * 品牌图标：80dp、圆角 22dp、品牌色柔光投影。
 *
 * 投影不是"阴影"（阴影是拟物），是"这枚红色方块在发光"——
 * 所以用品牌色而不是黑色，且很大很淡。
 */
@Composable
internal fun HeroIcon(
    glowAlpha: Float = 1f,
    imageAlpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val brand = if (isFlamingoInDarkMode()) Color(0xFFE64366) else Color(0xFFF54047)
    Box(
        modifier
            .size(Ob.HeroIcon)
            .drawBehind {
                val r = size.width * 0.95f
                val center = Offset(size.width / 2f, size.height / 2f)
                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(brand.copy(alpha = 0.28f * glowAlpha), Color.Transparent),
                        center = center,
                        radius = r,
                    ),
                    radius = r,
                    center = center,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(id = R.drawable.sonify_icon),
            contentDescription = stringResource(id = R.string.onboarding_hello_brand),
            alpha = imageAlpha,
            modifier = Modifier
                .size(Ob.HeroIcon)
                .clip(YosRoundedCornerShape(Ob.HeroIconCorner)),
        )
    }
}

/**
 * 把统一时钟 + 延迟折成 0..1（已缓动）。
 *
 * 用 `FastOutSlowInEasing`：起步快、收尾极慢 —— 这是"落定"，不是"飘进来"。
 */
private fun enterInternal(clock: Float, delayFraction: Float): Float {
    val span = (1f - delayFraction).coerceAtLeast(0.01f)
    val u = ((clock - delayFraction) / span).coerceIn(0f, 1f)
    return FastOutSlowInEasing.transform(u)
}

/** [enterInternal] 的包内可见别名 —— 过渡层复用同一条入场曲线。 */
internal fun enter(clock: Float, delayFraction: Float): Float = enterInternal(clock, delayFraction)

/**
 * 一行手写大字：淡入 + 上浮 + 极轻微放大 + 从模糊收敛。
 *
 * ★★★三条硬约束（违反任一条都会"只看到半个字"或"看不到字"）：
 *
 *   ① **`overflow` 必须显式设成 [TextOverflow.Visible]。**
 *      这是「Sonify 下半部分被一个 box 截断」的**真根因**，前面两版都修错了地方。
 *      `Text` 默认 `overflow = TextOverflow.Clip`，裁剪发生在**文字排版内部**、
 *      按 `maxLines` 的单行布局框裁 —— 这款手写体的降部（"y" 的尾巴）与连字外溢
 *      都画在布局框之外，于是从第一帧起就被切掉，和动画无关。
 *      外层再怎么加 `padding` 都没用：padding 撑大的是父布局，裁不到 Text 里面。
 *      （判据：字在任何 progress 下都缺同一块 = 排版期裁剪；只在 alpha<1 时缺 =
 *        离屏层裁剪。上一版把后者当唯一原因，所以一直没修好。）
 *
 *   ② **必须把 `graphicsLayer` 排在最外层，`padding` 排在内层。**
 *      `graphicsLayer { alpha < 1 }` 建的离屏层尺寸 = **它自己这个节点的布局边界**。
 *      写成 `.padding().graphicsLayer()`（旧版）时 padding 在外、层在内的尺寸仍是
 *      Text 自己的排版框 → 层照样按排版框裁，padding 白加（这正是"加了 padding
 *      还是被切"的原因）。反过来写 `.graphicsLayer().padding()`，层就把 padding
 *      一起包进去，墨迹有地方画。
 *
 *   ③ **模糊必须在 padding 之内、graphicsLayer 之外**。
 *      顺序 graphicsLayer → padding → blur → Text：
 *      层包住整个外扩框，blur 的 `Unbounded` 让模糊本身不受边界限制。
 *      API<31 不加模糊（与第 4 屏玻璃同一套降级口径：那里也不建 RenderEffect）。
 *
 * `scale` 从 0.985 起：手写字的落笔是"最后一下压下去"，所以收尾时略大一点点。
 * 幅度必须极小（1.5%），大了就变成"弹出"。
 */
@Composable
internal fun HandwriteLine(
    text: String,
    size: androidx.compose.ui.unit.TextUnit,
    color: Color,
    progress: Float,
    liftDp: Float,
    fontWeight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text,
        color = color.copy(alpha = color.alpha * progress.coerceIn(0f, 1f)),
        fontSize = size,
        fontWeight = fontWeight,
        fontFamily = HelloFontFamily,
        style = TextStyle(
            fontFeatureSettings = HelloTextFeatures,
            // ★必须恒为 0：不能随动画变化，否则连字退化成四条斜杠。
            letterSpacing = 0.sp,
        ),
        // 防折行（中文连字不能断），但**绝不允许按布局框裁墨迹** —— 见 ①。
        maxLines = 1,
        overflow = TextOverflow.Visible,
        softWrap = false,
        modifier = Modifier
            // 零号外层：折叠水平/垂直墨迹补偿，同时保持外扩绘制边界（48dp/36dp）不被裁切
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
            // 不再在 graphicsLayer 设 alpha：否则 Android RenderNode 会建离屏层并将内容硬裁成盒。
            // 透明度已直接传入 Text(color = color.copy(alpha = progress))。
            .graphicsLayer {
                clip = false
                translationY = (1f - progress) * liftDp * density
                val s = 0.985f + 0.015f * progress
                scaleX = s
                scaleY = s
            }
            // ② 模糊必须在 padding 之外：让 RenderNode 包含完整外扩边界，避免裁剪手写体连字外溢。
            .handwriteBlur(progress)
            // ③ 在内层撑开绘制边界，给手写体连字外溢、降部和入场模糊留足空间
            .padding(vertical = Ob.HandwriteVPad, horizontal = Ob.HandwriteHPad),
    )
}

/**
 * 手写体入场模糊：`progress` 从 0 → 1 时半径 6dp → 0。
 *
 * `TileMode.Decal` 让模糊在边缘取样时用"边缘外延"而不是 clamp 到透明黑，
 * 否则字的四周会出现一圈发暗的光晕。
 */
private fun Modifier.handwriteBlur(progress: Float): Modifier =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val radius = (Ob.HandwriteBlur * (1f - progress.coerceIn(0f, 1f)))
        if (radius > 0.01.dp) {
            this.blur(radius, edgeTreatment = BlurredEdgeTreatment.Unbounded)
        } else {
            this
        }
    } else {
        this
    }

// ── 屏 3 · 外观选择（复用设置页深浅色控件）────────────────────────────────

/**
 * 屏 3：独立外观选择页，直接复用设置页同款 [ColorModeOptions]（两张迷你手机预览图 + 单选点）
 * 与「自动跟随系统」开关，让引导页与设置页保持完全一致的交互与视觉样式。
 */
@Composable
internal fun ThemeScreen(colors: OnboardingColors) {
    val theme = SettingsLibrary.CustomTheme
    val systemDark = isSystemInDarkTheme()
    val scrollState = rememberScrollState()

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Ob.Gutter)
    ) {
        Spacer(Modifier.height(Ob.S8))

        ScreenTitle(
            title = stringResource(id = R.string.onboarding_theme_title),
            desc = stringResource(id = R.string.onboarding_theme_desc),
            colors = colors,
        )

        Spacer(Modifier.height(Ob.S24))

        ControlCard(colors = colors) {
            CompositionLocalProvider(LocalContentColor provides colors.ink) {
                ColorModeOptions(
                    currentModeProvider = { SettingsLibrary.CustomTheme },
                    systemInDarkTheme = systemDark,
                    onModeChange = { SettingsLibrary.CustomTheme = it },
                )
            }

            Spacer(Modifier.height(Ob.S8))
            Hairline(colors = colors)
            Spacer(Modifier.height(Ob.S8))

            ControlRow(
                colors = colors,
                title = stringResource(id = R.string.settings_performance_ui_theme_auto),
            ) {
                YosSwitch(
                    checked = theme == "Auto",
                    onCheckedChange = { on ->
                        SettingsLibrary.CustomTheme =
                            if (on) "Auto"
                            else if (systemDark) "Dark" else "Light"
                    },
                    checkedState = { SettingsLibrary.CustomTheme == "Auto" },
                    contentBackdrop = null,
                )
            }
        }

        if (theme == "Auto") {
            Spacer(Modifier.height(Ob.S10))
            val currentLabel = stringResource(
                id = if (systemDark) R.string.settings_performance_ui_theme_dark
                else R.string.settings_performance_ui_theme_light
            )
            StageFootnote(
                text = stringResource(id = R.string.onboarding_theme_auto_footnote, currentLabel),
                colors = colors,
            )
        }

        Spacer(Modifier.height(Ob.S40))
    }
}

// ── 屏 4 · 歌词与动态背景（实时预览）────────────────────────────────────────

/**
 * 屏 4：一块歌词舞台 + 两个偏好（背景动效、歌词字号）。
 *
 * ── 为什么这一屏聚焦歌词与动效 ──────────────────────────────────────
 * 预览舞台模拟的是恒深色的全屏播放页，只展示背景流光动效与逐字扫色字号变化：
 *
 *   背景动效 → 舞台里那层色晕流动 / 立即冻结
 *   歌词字号 → 舞台里当前那句歌词真的变大变小
 *
 * 两个偏好全部读写 `SettingsLibrary` 的**真实值**，所以：
 *   · 进来时显示的就是当前生效值（不是"推荐值"）
 *   · 用户什么都不点也能直接过
 */
@Composable
internal fun SettingsScreen(colors: OnboardingColors) {
    val backgroundEffect = SettingsLibrary.NowplayingBackgroundEffect
    val fontScale = SettingsLibrary.LyricFontScale.coerceIn(0.8f, 1.2f)

    val scrollState = rememberScrollState()

    // ★用 BoxWithConstraints 拿"可用高度"，把舞台高度算成"一屏能放下"的尺寸。
    //   判据：一屏里有一个"必须被看见才能起作用"的实时预览时，
    //   预览的高度只能由剩余空间决定，不能由固定比例决定 ——
    //   否则小屏/横屏下用户必须滚动才能看到控件，而滚动本身会遮住预览。
    BoxWithConstraints(Modifier.fillMaxSize()) {
        // ★必须先把 maxHeight 取到局部变量。
        // BoxWithConstraints 的 maxHeight 是 receiver 上的属性，而下面
        // LyricStage(...) 的实参表达式里还套着另一个 receiver（ColumnScope），
        // Kotlin 会判定"隐式 receiver 歧义"而拒绝编译。
        val availableH = maxHeight

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = Ob.Gutter)
        ) {
            Spacer(Modifier.height(Ob.S8))

            ScreenTitle(
                title = stringResource(id = R.string.onboarding_settings_title),
                desc = stringResource(id = R.string.onboarding_settings_desc),
                colors = colors,
            )

            Spacer(Modifier.height(Ob.S16))

            LyricStage(
                colors = colors,
                fontScale = fontScale,
                availableHeight = availableH,
            )

            Spacer(Modifier.height(Ob.S20))

            // ── 偏好 1：背景动态效果 ──
            ControlCard(colors = colors) {
                ControlRow(
                    colors = colors,
                    title = stringResource(id = R.string.settings_performance_ui_nowplaying_background_effect),
                    subtitle = stringResource(id = R.string.onboarding_settings_motion_desc),
                ) {
                    YosSwitch(
                        checked = backgroundEffect,
                        onCheckedChange = { on ->
                            SettingsLibrary.NowplayingBackgroundEffect = on
                        },
                        checkedState = { SettingsLibrary.NowplayingBackgroundEffect },
                        // 引导页里开关身后是纯底色，给一个平面采样源兜底。
                        contentBackdrop = null,
                    )
                }
            }

            Spacer(Modifier.height(Ob.S12))

            // ── 偏好 3：歌词字号 ──
            ControlCard(colors = colors) {
                ControlRow(
                    colors = colors,
                    title = stringResource(id = R.string.settings_performance_lyric_style_font_size),
                ) {
                    OnboardingTextSegmented(
                        options = listOf(
                            stringResource(id = R.string.onboarding_settings_font_small),
                            stringResource(id = R.string.onboarding_settings_font_medium),
                            stringResource(id = R.string.onboarding_settings_font_large),
                        ),
                        selected = when {
                            fontScale < 0.9f -> 0
                            fontScale > 1.1f -> 2
                            else -> 1
                        },
                        colors = colors,
                        onSelect = { index ->
                            SettingsLibrary.LyricFontScale = when (index) {
                                0 -> 0.8f
                                2 -> 1.2f
                                else -> 1.0f
                            }
                        },
                        modifier = Modifier.width(184.dp),
                    )
                }
            }

            Spacer(Modifier.height(Ob.S40))
        }
    }
}

// ── 屏 5 · 收束 ─────────────────────────────────────────────────────────────

/**
 * 屏 5：讲清"你现在拥有什么"，顺手把本机音乐接进来。
 *
 * 两项能力清单里的每一条都已在前五屏里演过（双音源、逐字歌词），
 * 所以它是**回声**而不是新信息。
 *
 * 本机扫描卡不是"又一个设置项"：它是这一屏唯一一件**用户此刻就能做的事**，
 * 而且它必须在引导结束前做 —— 结束之后用户就进主页了，不会想起来去授权。
 */
@Composable
internal fun DoneScreen(colors: OnboardingColors) {
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
                verticalArrangement = Arrangement.Center,
            ) {
                Spacer(Modifier.height(Ob.S12))

                // 手写体品牌名：全页最后一处手写字，位置是"出品人落款"。
                // ★必须显式声明 overflow = Visible & softWrap = false，并给外扩边界后折叠，否则 "y"/"f" 降部或左右连笔会被裁剪。
                Text(
                    text = stringResource(id = R.string.onboarding_hello_brand),
                    color = colors.brand,
                    fontSize = Ob.SignatureSize,
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
                        .padding(vertical = Ob.HandwriteVPad, horizontal = Ob.HandwriteHPad),
                )

                Spacer(Modifier.height(Ob.S12))

                Text(
                    text = stringResource(id = R.string.onboarding_done_title),
                    color = colors.ink,
                    fontSize = Ob.TitleSize,
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
                        .padding(vertical = Ob.HandwriteVPad, horizontal = Ob.HandwriteHPad),
                )

                Spacer(Modifier.height(Ob.S12))

                Text(
                    text = stringResource(id = R.string.onboarding_done_desc),
                    color = colors.ink3,
                    fontSize = Ob.LeadSize,
                    lineHeight = Ob.LeadLine,
                )

                Spacer(Modifier.height(Ob.S28))

                // ── 两行能力清单（图标 + 文字，无序号、无底色遮罩）──
                CapabilityLine(
                    colors = colors,
                    icon = { MusicNoteGlyphBig(color = colors.brand) },
                    title = stringResource(id = R.string.onboarding_done_cap_1),
                    desc = stringResource(id = R.string.onboarding_done_cap_1_desc),
                )
                Spacer(Modifier.height(Ob.S20))
                CapabilityLine(
                    colors = colors,
                    icon = { MicGlyph(color = colors.brand) },
                    title = stringResource(id = R.string.onboarding_done_cap_2),
                    desc = stringResource(id = R.string.onboarding_done_cap_2_desc),
                )

                Spacer(Modifier.height(Ob.S24))

                // ── 本机扫描卡 ──
                LocalScanCard(colors = colors)

                Spacer(Modifier.height(Ob.S16))
            }
        }
    }
}

/**
 * 一条能力：左侧纯品牌色描边图标（**没有圆角矩形底色**），右侧标题 + 说明。
 *
 * 上一版是「01/02/03 + 一句长文案」，序号是装饰、文案是一行读不完的长句。
 * 这一版给每一条一个图标 + 一句标题 + 一句说明：图标承担"这是什么"，
 * 标题承担"有什么用"，说明承担"具体怎么做"。
 */
@Composable
private fun CapabilityLine(
    colors: OnboardingColors,
    icon: @Composable () -> Unit,
    title: String,
    desc: String,
) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .width(24.dp)
                .padding(top = 2.dp),
            contentAlignment = Alignment.TopStart,
        ) { icon() }
        Spacer(Modifier.width(Ob.S16))
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                color = colors.ink,
                fontSize = Ob.RowSize,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(Ob.S2))
            Text(
                text = desc,
                color = colors.ink3,
                fontSize = Ob.DescSize,
                lineHeight = Ob.DescLine,
            )
        }
    }
}

/**
 * 本机音频扫描卡。
 *
 * ── 状态机 ────────────────────────────────────────────────────────
 *   IDLE（未授权/未扫）→ 点「立即扫描」→ 申请权限 → SCANNING → DONE
 *   进屏时**先读现状**：已经有歌或已授权就直接显示已导入数量。
 *   启动时 `CheckAndRequestPermission()` 可能已经扫过一遍，不能让人重复扫。
 *
 * ── 权限 ──────────────────────────────────────────────────────────
 * Android 13+ 用 `READ_MEDIA_AUDIO`，12- 用 `READ_EXTERNAL_STORAGE`。
 * 两者 manifest 都已声明，这里只是按版本选一个去要。
 */
@Composable
private fun LocalScanCard(colors: OnboardingColors) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var scanning by remember { mutableStateOf(false) }
    // 初始就带上已有数量，并随曲库外部扫描完成实时同步
    val liveCount = MusicLibrary.songs.size
    var imported by remember { mutableIntStateOf(liveCount) }
    var lossless by remember { mutableIntStateOf(countLossless()) }
    var denied by remember { mutableStateOf(false) }

    LaunchedEffect(liveCount) {
        imported = liveCount
        lossless = countLossless()
    }

    fun hasPermission(): Boolean {
        val perm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_AUDIO
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        return androidx.core.content.ContextCompat.checkSelfPermission(
            context, perm
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun rescan() {
        if (scanning) return
        scanning = true
        denied = false
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { MusicLibrary.scanMedia(context) } }
            imported = MusicLibrary.songs.size
            lossless = countLossless()
            scanning = false
        }
    }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            rescan()
        } else {
            denied = true
        }
    }

    OnboardingCard(colors = colors, corner = Ob.PlateCorner) {
        Row(
            Modifier
                .fillMaxWidth()
                .padding(Ob.S16),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 40dp 图标块
            Box(
                Modifier
                    .size(40.dp)
                    .clip(YosRoundedCornerShape(12.dp))
                    .background(colors.track),
                contentAlignment = Alignment.Center,
            ) {
                FolderGlyph(color = colors.brand)
            }

            Spacer(Modifier.width(Ob.S12))

            Column(Modifier.weight(1f)) {
                Text(
                    text = when {
                        scanning -> stringResource(id = R.string.onboarding_scan_running)
                        imported > 0 -> stringResource(id = R.string.onboarding_scan_done_title, imported)
                        else -> stringResource(id = R.string.onboarding_scan_title)
                    },
                    color = colors.ink,
                    fontSize = Ob.RowSize,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
                Spacer(Modifier.height(Ob.S2))
                Text(
                    text = when {
                        scanning -> stringResource(id = R.string.onboarding_scan_running_sub)
                        denied -> stringResource(id = R.string.onboarding_scan_denied)
                        imported > 0 -> stringResource(id = R.string.onboarding_scan_done_sub, lossless)
                        else -> stringResource(id = R.string.onboarding_scan_sub)
                    },
                    color = if (denied) ErrorRed else colors.ink3,
                    fontSize = Ob.DescSize,
                    lineHeight = Ob.DescLine,
                    maxLines = 2,
                )
            }

            Spacer(Modifier.width(Ob.S12))

            // 「立即扫描」胶囊：扫完之后变成空心（不再抢注意力）
            val canScan = imported == 0 || denied
            if (canScan && !scanning) {
                Text(
                    text = stringResource(id = R.string.onboarding_scan_action),
                    color = colors.brand,
                    fontSize = Ob.DescSize,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(colors.accentSoft)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) {
                            if (hasPermission()) rescan() else launcher.launch(
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                    Manifest.permission.READ_MEDIA_AUDIO
                                } else {
                                    Manifest.permission.READ_EXTERNAL_STORAGE
                                }
                            )
                        }
                        .padding(horizontal = Ob.S14, vertical = Ob.S8),
                )
            }
        }
    }
}

/** FLAC/ALAC/WAV 计数，给"含 N 首无损"那一行用。 */
private fun countLossless(): Int = MusicLibrary.songs.count { song ->
    val mime = song.mimeType?.lowercase() ?: ""
    mime.contains("flac") || mime.contains("alac") || mime.contains("wav") ||
        mime.contains("x-flac") || mime.contains("ape")
}

// ── 共用 ────────────────────────────────────────────────────────────────────

/**
 * 屏标题 + 导语。
 *
 * 六屏共用同一套尺寸 → 切屏时标题不会跳。
 * `letterSpacing` 负值 + 行高 1.12 = iOS large title 的字面观感（字紧、行不挤）。
 */
@Composable
internal fun ScreenTitle(
    title: String,
    desc: String?,
    colors: OnboardingColors,
) {
    Column(Modifier.fillMaxWidth()) {
        Text(
            text = title,
            color = colors.ink,
            fontSize = Ob.TitleSize,
            fontWeight = FontWeight.Bold,
            letterSpacing = Ob.TitleTracking,
            lineHeight = (Ob.TitleSize.value * 1.12f).sp,
        )
        if (desc != null) {
            Spacer(Modifier.height(Ob.S10))
            Text(
                text = desc,
                color = colors.ink3,
                fontSize = Ob.LeadSize,
                lineHeight = Ob.LeadLine,
            )
        }
    }
}

// ── 能力清单的图标（Canvas，不引资源）───────────────────────────────────────

/** 音符（24dp 描边口径），能力清单第一条。 */
@Composable
private fun MusicNoteGlyphBig(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.width
        val sw = 1.8.dp.toPx()
        drawOval(
            color = color,
            topLeft = Offset(s * 0.10f, s * 0.60f),
            size = androidx.compose.ui.geometry.Size(s * 0.36f, s * 0.28f),
            style = Stroke(sw),
        )
        drawLine(
            color, Offset(s * 0.46f, s * 0.74f), Offset(s * 0.46f, s * 0.10f),
            strokeWidth = sw, cap = StrokeCap.Round
        )
        drawLine(
            color, Offset(s * 0.46f, s * 0.10f), Offset(s * 0.86f, s * 0.20f),
            strokeWidth = sw, cap = StrokeCap.Round
        )
    }
}

/** 麦克风（24dp 描边口径），能力清单第二条。 */
@Composable
private fun MicGlyph(color: Color) {
    Canvas(Modifier.size(24.dp)) {
        val s = size.width
        val sw = 1.8.dp.toPx()
        // 话筒头：胶囊
        drawRoundRect(
            color = color,
            topLeft = Offset(s * 0.34f, s * 0.08f),
            size = androidx.compose.ui.geometry.Size(s * 0.32f, s * 0.50f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.16f),
            style = Stroke(sw),
        )
        // 支架弧
        val p = Path().apply {
            moveTo(s * 0.20f, s * 0.48f)
            cubicTo(s * 0.20f, s * 0.86f, s * 0.80f, s * 0.86f, s * 0.80f, s * 0.48f)
        }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
        // 底座
        drawLine(
            color, Offset(s * 0.50f, s * 0.80f), Offset(s * 0.50f, s * 0.94f),
            strokeWidth = sw, cap = StrokeCap.Round
        )
    }
}

/** 文件夹（20dp 描边口径），扫描卡左侧。 */
@Composable
private fun FolderGlyph(color: Color) {
    Canvas(Modifier.size(20.dp)) {
        val s = size.width
        val sw = 1.7.dp.toPx()
        val p = Path().apply {
            moveTo(s * 0.08f, s * 0.30f)
            lineTo(s * 0.08f, s * 0.82f)
            lineTo(s * 0.92f, s * 0.82f)
            lineTo(s * 0.92f, s * 0.30f)
            lineTo(s * 0.50f, s * 0.30f)
            lineTo(s * 0.40f, s * 0.18f)
            lineTo(s * 0.08f, s * 0.18f)
            close()
        }
        drawPath(p, color, style = Stroke(sw, cap = StrokeCap.Round))
    }
}
