package yos.music.player.ui.widgets.audio

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint as AndroidPaint
import android.graphics.Path as AndroidPath
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFontFamilyResolver
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick as onSemanticsClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.exp
import kotlin.math.max
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.MediaController.musicPlaying
import yos.music.player.code.OnlineAudioCache
import yos.music.player.code.qualityLabelResOf
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
import yos.music.player.data.objects.TransitionDisplayMode
import yos.music.player.data.repositories.KugouRepository
import yos.music.player.data.repositories.NetworkObserver
import yos.music.player.data.repositories.PerSongQualityIntent
import yos.music.player.data.repositories.SongQualityCapabilityStore
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.widgets.basic.LiquidDropdownColumn
import yos.music.player.ui.widgets.basic.LiquidDropdownLayout
import yos.music.player.ui.widgets.basic.LiquidDropdownProgress
import yos.music.player.ui.widgets.basic.LiquidDropdownRow
import yos.music.player.ui.widgets.basic.LocalTitlePageBackdrop
import yos.music.player.ui.widgets.basic.YosWrapper
import yos.music.player.ui.widgets.basic.liquidDropdownAnchorFollow
import yos.music.player.ui.widgets.basic.rememberLiquidDropdownFollowState
import yos.music.player.ui.widgets.effects.overlayEffect
import yos.music.player.ui.widgets.dropdown.DropdownPanelDragSelectState
import yos.music.player.ui.widgets.dropdown.dropdownTriggerGesture
import yos.music.player.ui.widgets.dropdown.rememberDropdownTriggerGestureState

/** 断言 A6 的测试锚点：胶囊节点必须常驻组合树。 */
const val QUALITY_CAPSULE_TEST_TAG = "quality_capsule"

/**
 * 音质徽标（常显）：位于进度条时间行中央，同时是音质选择入口。
 *
 * 显示的是**事实**（服务端回写的实际档位；本地文件为解码器实测规格），不是偏好——
 * 用户耳朵里的东西只能由证据决定。所有判断收在 [QualityUiModel] 里，这里只渲染。
 *
 * 与旧实现的两处关键差别：
 * 1. 读数缺失时显示兜底文案而不是整块消失（旧版 `labelText == null` 会让胶囊连同
 *    22dp 热区一起蒸发，解析完成时"凭空弹出"并挤动两侧时间数字）；
 * 2. 选择档位**只作用于本首**（写 [PerSongQualityIntent]），不再顺手改写设置页的
 *    WiFi/流量两档偏好；全局改动请去设置页，那里是唯一能写偏好的地方。
 */
@Composable
fun MusicQualityIndicator(onExpandedChanged: (Boolean) -> Unit = {}) {
    val music = musicPlaying
    val context = LocalContext.current
    val bitrate = MediaViewModelObject.bitrate.intValue
    val samplingRate = MediaViewModelObject.samplingRate.intValue
    val isDolby = MediaViewModelObject.isDolby.value
    val transitionMode = MediaViewModelObject.activeTransitionMode.value
    val isTransitioning = transitionMode != null

    // 订阅四个变化源：解析完成 / 本曲覆盖变更 / 网络形态变更 / 能力表新结论
    //（最后一项让"播放即探测"的结果一到就反映到面板上，不等下一次解析）
    KugouRepository.actualQualityVersion.intValue
    PerSongQualityIntent.version.intValue
    NetworkObserver.version.intValue
    SongQualityCapabilityStore.version.intValue

    val uri = music.value?.uri
    val isOnline = uri?.scheme == KugouRepository.PLACEHOLDER_SCHEME
    val hash = uri?.lastPathSegment?.takeIf { isOnline }

    // 本机已整体缓存的档位：组合期绝不查磁盘（getCachedSpans 会碰缓存索引），
    // 换曲时异步取一次。失败/未就绪时保持空集 —— 只影响面板上的行尾 chip，
    // 不影响任何播放决策（决策在数据源线程里自己查，见 KugouResolvingDataSource）。
    var cachedTiers by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(hash) {
        cachedTiers = if (hash == null) emptySet() else withContext(Dispatchers.IO) {
            OnlineAudioCache.cachedTiersOf(context, hash)
        }
    }

    val ui = QualityUiModel.build(
        isOnline = isOnline,
        fact = hash?.let { KugouRepository.factOf(it) },
        pendingTier = KugouRepository.pendingTierOf(hash),
        overrideTier = PerSongQualityIntent.overrideOf(hash ?: ""),
        wifiTier = SettingsLibrary.OnlineQualityWifi,
        mobileTier = SettingsLibrary.OnlineQualityMobile,
        networkKind = NetworkObserver.kind(context),
        localBitrateKbps = bitrate,
        localSampleRateHz = samplingRate,
        // 能力表让"已证明拿不到"不随最后一次解析的意图漂移（也不会被重启忘掉）
        provenUnavailableTiers = SongQualityCapabilityStore.provenUnavailableOf(hash),
        // 流量下改播了本机缓存的高音源时，用户需要看到"为什么现在不是 128K"
        cachedTiers = cachedTiers
    )

    var menuOpen by remember { mutableStateOf(false) }

    // 过渡态开始时自动收起已展开的音质面板
    LaunchedEffect(isTransitioning) {
        if (isTransitioning && menuOpen) {
            menuOpen = false
        }
    }

    // 面板展开态回传宿主：播放页据此暂停"2.5s 自动隐藏控件"，避免面板还开着、
    // 下方控件却先淡出，只剩一个孤零零的下拉悬在屏上。内部各条关闭路径
    // （选中/点外部 onDismissRequest）都会汇到这里。
    LaunchedEffect(menuOpen) { onExpandedChanged(menuOpen) }
    DisposableEffect(Unit) { onDispose { onExpandedChanged(false) } }

    // 打开面板时补一次阶梯探测：切歌事件已经探过的话，函数内部会跳过全部已有结论的档
    // （零额外请求）；而"恢复上次播放"这类不走 onMediaItemTransition 的入口靠这里兜住。
    // 结论一到，version 变化会让面板重算置灰。
    LaunchedEffect(menuOpen, hash) {
        if (menuOpen && hash != null) KugouRepository.probeAllQualities(hash)
    }
    // 锚点边界（窗口坐标系）：必须是**被按下的胶囊本体**而非整行——LiquidDropdown
    // 按锚点右缘对齐（内收 27dp）+ 缩放原点取菜单最近锚点角，锚点给整行会让
    // 菜单落到行右端、动画出发点脱离控件（设置页锚点本来就是整行所以无此问题）
    // 并且必须是**未做联动的**这层节点：胶囊本体会随面板下沉/收缩（见 capsuleFollow），
    // 锚点若跟着走，面板会反算回锚点位置，形成逐帧放大的抖动回环
    var anchorBounds by remember { mutableStateOf(IntRect.Zero) }
    val anchorCoordsRef = remember {
        object {
            var coords: androidx.compose.ui.layout.LayoutCoordinates? = null
        }
    }
    fun updateAnchorBoundsFromCoords(coords: androidx.compose.ui.layout.LayoutCoordinates?) {
        if (coords == null || !coords.isAttached) return
        val pos = coords.positionInWindow()
        val size = coords.size
        val next = IntRect(
            left = pos.x.toInt(),
            top = pos.y.toInt(),
            right = pos.x.toInt() + size.width,
            bottom = pos.y.toInt() + size.height
        )
        if (next != anchorBounds) {
            anchorBounds = next
        }
    }

    // 胶囊本体随面板揭示进度的联动：面板从胶囊顶边下方长出并盖住它（可见顶边压在锚点上方
    // 7dp），胶囊跟着同向下沉 6dp、缩到 0.9、淡出；与「更多」按钮用的是同一套参数
    val capsuleFollow = rememberLiquidDropdownFollowState()

    // 跟手滑选：胶囊与面板共享同一份状态 —— 长按胶囊展开后不停手就能直接滑选档位
    val qualityDragSelect = remember { DropdownPanelDragSelectState() }
    val qualityTriggerGesture = rememberDropdownTriggerGestureState(qualityDragSelect)
    val openQualityMenu: () -> Unit = {
        if (!isTransitioning) {
            updateAnchorBoundsFromCoords(anchorCoordsRef.coords)
            menuOpen = true
        }
    }

    fun captureAnchor(coords: androidx.compose.ui.layout.LayoutCoordinates) {
        anchorCoordsRef.coords = coords
        if (menuOpen) {
            updateAnchorBoundsFromCoords(coords)
        }
    }

    // 播放壳层常驻，无曲目时也要占住这 22dp：否则展开/收起播放器会带动时间行跳动
    // 手势**不能**挂在这一层：它 fillMaxWidth 会盖住左右两侧的时间数字，点时间也弹面板；
    // 热区必须落在被按下的胶囊本体上（与锚点同一节点，见下）
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(22.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .onGloballyPositioned(::captureAnchor)
                .testTag(QUALITY_CAPSULE_TEST_TAG)
                .then(
                    if (music.value != null && !isTransitioning) {
                        // 点按与长按由同一个手势仲裁（长按 0.5s 展开后可不停手直接滑选档位）。
                        // 不能再叠 clickable：它会无条件消费 down，且在 up 时先 consume 再
                        // 无条件 onClick，长按抬手会被二次触发一次点击；过渡期间屏蔽触发音质弹窗。
                        Modifier
                            .dropdownTriggerGesture(
                                state = qualityTriggerGesture,
                                expanded = { menuOpen },
                                onTap = openQualityMenu,
                                onExpand = openQualityMenu,
                            )
                            .semantics {
                                role = Role.Button
                                onSemanticsClick {
                                    openQualityMenu()
                                    true
                                }
                            }
                    } else {
                        Modifier
                    }
                )
        ) {
            val glowProgressState: State<Float>? = if (isTransitioning) {
                rememberInfiniteTransition(label = "transitionGlowSweep").animateFloat(
                    initialValue = 0f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(durationMillis = 2400, easing = LinearEasing),
                        repeatMode = RepeatMode.Restart,
                    ),
                    label = "transitionGlowProgress",
                )
            } else {
                null
            }

            // 无曲目时不画胶囊（外壳 22dp 行高固定，不会引起布局跳动）；
            // 但有曲目而读数未知时**照旧画胶囊**，只把文字换成兜底文案——
            // 旧版在 labelText == null 时把节点整块从组合树抽走，解析完成又弹回来（断言 A6）
            Crossfade(
                targetState = isDolby && !isTransitioning,
                modifier = Modifier.liquidDropdownAnchorFollow(capsuleFollow)
            ) { dolby ->
                if (dolby) {
                    Icon(
                        painter = painterResource(id = R.drawable.ic_nowplaying_dolby_atmos),
                        contentDescription = "dolby_atmos",
                        modifier = Modifier
                            .height(19.dp)
                            .overlayEffect()
                            .alpha(0.45f)
                    )
                } else if (music.value != null) {
                    // 过渡态只保留无底座纯文字并逐字柔波浮起与扫光辉光；非过渡态恢复标准音质胶囊底框
                    val label = when (transitionMode) {
                        TransitionDisplayMode.SMART ->
                            stringResource(id = R.string.settings_play_crossfade_mode_smart)
                        TransitionDisplayMode.SMOOTH ->
                            stringResource(id = R.string.settings_play_crossfade_mode_smooth)
                        null -> ui.capsule.tier?.let { stringResource(id = qualityLabelResOf(it)) }
                            ?: stringResource(id = ui.capsule.fallbackRes)
                    }
                    val showLosslessBadge = ui.capsule.losslessBadge && !isTransitioning
                    Box(
                        modifier = Modifier
                            .height(20.dp)
                            .widthIn(min = 62.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        YosWrapper {
                            AnimatedContent(
                                targetState = Triple(label, showLosslessBadge, isTransitioning),
                                transitionSpec = {
                                    (fadeIn(tween(240, easing = FastOutSlowInEasing)) togetherWith
                                        fadeOut(tween(180))) using SizeTransform(clip = false)
                                },
                                label = "QualityCapsuleContent",
                            ) { (stateLabel, stateLosslessBadge, stateIsTransitioning) ->
                                if (stateIsTransitioning) {
                                    TransitionWaveGlowText(
                                        text = stateLabel,
                                        sweepProgress = glowProgressState,
                                        modifier = Modifier.overlayEffect(),
                                    )
                                } else {
                                    Box(
                                        modifier = Modifier
                                            .overlayEffect()
                                            .background(
                                                color = Color(0x14FFFFFF),
                                                shape = YosRoundedCornerShape(5.dp)
                                            )
                                            .height(20.dp)
                                            .widthIn(min = 62.dp)
                                            .padding(horizontal = 7.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Row(
                                            horizontalArrangement = Arrangement.Center,
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.alpha(if (ui.capsule.dimmed) 0.3f else 0.45f)
                                        ) {
                                            if (stateLosslessBadge) {
                                                Icon(
                                                    painter = painterResource(id = R.drawable.ic_quality_lossless),
                                                    contentDescription = "quality_lossless",
                                                    modifier = Modifier
                                                        .height(9.dp)
                                                        .alpha(0.45f)
                                                )
                                                Spacer(modifier = Modifier.width(5.dp))
                                            }

                                            Text(
                                                text = stateLabel,
                                                fontSize = 11.sp,
                                                lineHeight = 11.sp
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        QualitySheet(
            uiModel = ui,
            expanded = menuOpen && !isTransitioning,
            anchorBounds = anchorBounds,
            onFractionProgress = capsuleFollow::onProgress,
            onDismiss = { menuOpen = false },
            dragSelectState = qualityDragSelect,
            onSelect = { tier ->
                // 在线选档：只改本首。已证明不可用的行不会走到这里（置灰行不响应点击）
                MediaController.requestQualityForCurrentSong(tier)
            }
        )
    }
}

/**
 * 音质选择玻璃下拉：**只有四档单选行**，与播放页「更多」菜单同属一套
 * [LiquidDropdownLayout] 引擎——进入/退出弹簧（0.78/240 与 0.78/400）、alpha tween
 * (120/400)、缩放 0.24→1、独立锚点动画、从胶囊方块生长的裁剪揭示、反向补偿圆角
 * 25dp、vibrancy + blur(24dp)、表面 0.6 全部同源同值，不再维护第二套弹层。
 *
 * 与「更多」菜单因几何而不同的参数：这里胶囊在时间行居中，所以面板跟着
 * 居中摆位（[alignCenterHorizontally]），裁剪从中心长出而不是从角落。
 *
 * [onFractionProgress] 逐帧回传给胶囊本体，驱动它的下沉/收缩/淡出（与「更多」按钮同机制）。
 *
 * 置灰 = 已证明本曲拿不到这一档（行尾 chip 说明原因），点击无效；其余一律可点。
 * 分区标题、结论行、生效档署名、偏好摘要等说明文字已按要求移除——诚实读数仍在
 * 徽标上，降级告知仍在 toast 里。本曲覆盖不设单独的撤销入口：在设置页改一次偏好
 * 会一并清掉所有本曲覆盖（见 OnlineQualitySettings）。
 */
@Composable
private fun QualitySheet(
    uiModel: QualityUiState,
    expanded: Boolean,
    anchorBounds: IntRect,
    onFractionProgress: (LiquidDropdownProgress) -> Unit,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    /** 与胶囊触发区共享的滑选状态（长按展开后不停手滑选）：在线四档纯行菜单才接 */
    dragSelectState: DropdownPanelDragSelectState,
) {
    // 跟手滑选（在线四档纯行菜单）：面板手势层统一命中+执行，行自动切登记模式。
    // 状态由调用方持有并与胶囊共享，长按展开后同一个手势可以接着滑选。
    val dragSelect = dragSelectState
    LiquidDropdownLayout(
        expanded = expanded,
        anchorBounds = anchorBounds,
        alignCenterHorizontally = true,
        onDismissRequest = onDismiss,
        backdrop = LocalTitlePageBackdrop.current,
        onFractionProgress = onFractionProgress,
        dragSelectState = if (uiModel.isOnline) dragSelect else null
    ) {
        // 这里**不**套「更多」菜单那套高度封顶（liquidDropdownHeightCap）：四档面板的自然高
        // 约 184dp，而胶囊住在进度条下方的时间行（靠屏底），按锚点余量封顶会把最后一行
        // 挤成内滚；短窗口（多窗口/横屏）下引擎在 measure 阶段已经用"窗口高 - 阴影外扩"
        // 硬卡过一道，不会超出去。封顶留给需要贴锚点行堆叠的多行面板
        LiquidDropdownColumn(
            onFitsOnScreen = { fits -> dragSelect.fitsOnScreen = fits }
        ) {
            if (!uiModel.isOnline) {
                LocalSpecSection(uiModel)
            } else {
                uiModel.sheet.rows.forEachIndexed { index, row ->
                    LiquidDropdownRow(
                        text = stringResource(id = qualityLabelResOf(row.tier)),
                        selected = row.selected,
                        enabled = row.enabled,
                        chip = row.chipRes?.let { stringResource(id = it) },
                        // 选完即收：单选菜单停在屏幕上会让用户以为没生效（重构前就是这个行为，
                        // 收敛面板时误删，现补回）；置灰行由 LiquidDropdownRow 自身不响应点击
                        onClick = {
                            onSelect(row.tier)
                            onDismiss()
                        },
                        isFirst = index == 0,
                        isLast = index == uiModel.sheet.rows.lastIndex
                    )
                }
            }
        }
    }
}

/** 段标题（10.5sp 置灰）+ 可选右侧 chip：仅本地文件规格卡在用。 */
@Composable
private fun SectionHeader(text: String, chip: String?, isFirst: Boolean = true) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                start = 21.dp,
                end = 21.dp,
                top = if (isFirst) 9.dp else 4.dp,
                bottom = 2.dp
            ),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = text,
            fontSize = 10.5.sp,
            lineHeight = 12.sp,
            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.45f)
        )
        if (chip != null) {
            Box(
                modifier = Modifier
                    .clip(YosRoundedCornerShape(4.dp))
                    .background(MaterialTheme.colorScheme.onBackground.copy(alpha = 0.08f))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    text = chip,
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.6f)
                )
            }
        }
    }
}

/** 本地歌曲：只报实测规格，不显示在线四档（点了也改不到本地文件的任何东西）。 */
@Composable
private fun LocalSpecSection(uiModel: QualityUiState) {
    val spec = uiModel.localSpec
    SectionHeader(
        text = stringResource(id = R.string.quality_section_local),
        chip = spec?.tier?.let { stringResource(id = qualityLabelResOf(it)) }
            ?: stringResource(id = R.string.quality_capsule_unknown)
    )
    val lines = if (spec == null) {
        listOf(stringResource(id = R.string.quality_capsule_loading))
    } else {
        buildList {
            if (spec.sampleRateHz > 0) {
                add(stringResource(id = R.string.quality_local_samplerate, spec.sampleRateHz))
            }
            if (spec.bitrateKbps > 0) {
                add(stringResource(id = R.string.quality_local_bitrate, spec.bitrateKbps))
            }
            if (isEmpty()) add(stringResource(id = R.string.quality_capsule_unknown))
        }
    }
    lines.forEach { line ->
        Text(
            text = line,
            fontSize = 13.sp,
            lineHeight = 17.sp,
            modifier = Modifier
                .padding(horizontal = 17.dp, vertical = 4.dp)
                .alpha(0.75f)
        )
    }
    Text(
        text = stringResource(id = R.string.quality_local_note),
        fontSize = 11.5.sp,
        lineHeight = 15.sp,
        modifier = Modifier
            .padding(horizontal = 17.dp)
            .padding(top = 4.dp, bottom = 10.dp)
            .alpha(0.55f)
    )
}

private class TransitionCharGlyph(
    val text: String,
    val startX: Float,
    val centerU: Float,
    val path: AndroidPath,
)

private class TransitionGlyphLayout(
    val glyphs: List<TransitionCharGlyph>,
    val totalWidthPx: Float,
    val baselineOffsetPx: Float,
    val hasVectorPaths: Boolean,
)

/**
 * 亚像素矢量路径柔波与字形限定扫光组件：
 * 1. 通过 [AndroidPaint.getTextPath] 提取矢量字形轮廓与真实字形步进位置（字距与普通文案 100% 一致）；
 * 2. 在单层 `Canvas` 中直接以浮点坐标驱动高斯海浪起伏，彻底消除 Compose Text 整数像素基线吸附带来的卡顿；
 * 3. 利用 `LinearGradient` Shader 仅着色文字路径本身（配合微小 `BlurMaskFilter` 字形外沿柔和光晕），光带绝不外溢背景。
 */
@Composable
private fun TransitionWaveGlowText(
    text: String,
    sweepProgress: State<Float>?,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontFamilyResolver = LocalFontFamilyResolver.current
    val localStyle = LocalTextStyle.current
    val resolvedTypefaceState = remember(fontFamilyResolver, localStyle) {
        fontFamilyResolver.resolve(
            fontFamily = localStyle.fontFamily,
            fontWeight = localStyle.fontWeight ?: FontWeight.Normal,
            fontStyle = localStyle.fontStyle ?: FontStyle.Normal,
            fontSynthesis = localStyle.fontSynthesis ?: FontSynthesis.All,
        )
    }
    val typeface = (resolvedTypefaceState.value as? Typeface) ?: Typeface.DEFAULT

    val textSizePx = with(density) { 11.sp.toPx() }
    val waveAmpPx = with(density) { 1.35.dp.toPx() }
    val glowBlurPx = with(density) { 3.5.dp.toPx() }
    val minBandHalfPx = with(density) { 20.dp.toPx() }

    val layout = remember(text, textSizePx, typeface) {
        val measurePaint = AndroidPaint(
            AndroidPaint.ANTI_ALIAS_FLAG or
                AndroidPaint.SUBPIXEL_TEXT_FLAG or
                AndroidPaint.LINEAR_TEXT_FLAG,
        ).apply {
            this.textSize = textSizePx
            this.typeface = typeface
            hinting = AndroidPaint.HINTING_OFF
        }
        val totalWidth = measurePaint.measureText(text).coerceAtLeast(1f)
        val fm = measurePaint.fontMetrics
        val baselineOffset = -(fm.ascent + fm.descent) * 0.5f

        val glyphs = ArrayList<TransitionCharGlyph>(text.length)
        var i = 0
        while (i < text.length) {
            val end = if (Character.isHighSurrogate(text[i]) && i + 1 < text.length) i + 2 else i + 1
            val startX = measurePaint.measureText(text, 0, i)
            val advance = measurePaint.measureText(text, i, end)
            val path = AndroidPath()
            measurePaint.getTextPath(text, i, end, startX, 0f, path)
            val centerU = (startX + advance * 0.5f) / totalWidth
            glyphs.add(
                TransitionCharGlyph(
                    text = text.substring(i, end),
                    startX = startX,
                    centerU = centerU,
                    path = path,
                ),
            )
            i = end
        }
        TransitionGlyphLayout(
            glyphs = glyphs,
            totalWidthPx = totalWidth,
            baselineOffsetPx = baselineOffset,
            hasVectorPaths = glyphs.any { !it.path.isEmpty },
        )
    }

    val fillPaint = remember(textSizePx, typeface) {
        AndroidPaint(
            AndroidPaint.ANTI_ALIAS_FLAG or
                AndroidPaint.SUBPIXEL_TEXT_FLAG or
                AndroidPaint.LINEAR_TEXT_FLAG,
        ).apply {
            style = AndroidPaint.Style.FILL
            textSize = textSizePx
            this.typeface = typeface
            hinting = AndroidPaint.HINTING_OFF
        }
    }
    val glowPaint = remember(textSizePx, typeface, glowBlurPx) {
        AndroidPaint(
            AndroidPaint.ANTI_ALIAS_FLAG or
                AndroidPaint.SUBPIXEL_TEXT_FLAG or
                AndroidPaint.LINEAR_TEXT_FLAG,
        ).apply {
            style = AndroidPaint.Style.FILL
            textSize = textSizePx
            this.typeface = typeface
            hinting = AndroidPaint.HINTING_OFF
            maskFilter = BlurMaskFilter(glowBlurPx, BlurMaskFilter.Blur.NORMAL)
        }
    }

    val glowColors = remember {
        intArrayOf(0x00FFFFFF, 0x00FFFFFF, 0x6EFFFFFF, 0x00FFFFFF, 0x00FFFFFF)
    }
    val glowStops = remember { floatArrayOf(0f, 0.25f, 0.5f, 0.75f, 1f) }
    val fillColors = remember {
        intArrayOf(0x6EFFFFFF, 0x8AFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x8AFFFFFF.toInt(), 0x6EFFFFFF)
    }
    val fillStops = remember { floatArrayOf(0f, 0.22f, 0.5f, 0.78f, 1f) }
    val bandHalfPx = max(layout.totalWidthPx.coerceAtLeast(1f) * 0.48f, minBandHalfPx)
    val glowGradient = remember(bandHalfPx) {
        LinearGradient(-bandHalfPx, 0f, bandHalfPx, 0f, glowColors, glowStops, Shader.TileMode.CLAMP)
    }
    val fillGradient = remember(bandHalfPx) {
        LinearGradient(-bandHalfPx, 0f, bandHalfPx, 0f, fillColors, fillStops, Shader.TileMode.CLAMP)
    }
    val shaderMatrix = remember { Matrix() }

    val widthDp = with(density) { layout.totalWidthPx.toDp() }.coerceAtLeast(1.dp)
    Canvas(modifier = modifier.size(width = widthDp, height = 20.dp)) {
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val totalW = layout.totalWidthPx.coerceAtLeast(1f)
        val startX = (size.width - totalW) * 0.5f
        val baselineY = size.height * 0.5f + layout.baselineOffsetPx
        val progress = sweepProgress?.value

        if (progress == null) {
            fillPaint.shader = null
            fillPaint.color = 0x70FFFFFF
            drawWaveGlyphPass(
                canvas = nativeCanvas,
                layout = layout,
                paint = fillPaint,
                startX = startX,
                baselineY = baselineY,
                crestU = null,
                waveAmpPx = 0f,
            )
            return@Canvas
        }

        val crestU = -0.70f + progress * 2.40f
        val localCenterX = crestU * totalW
        shaderMatrix.setTranslate(localCenterX, 0f)
        glowGradient.setLocalMatrix(shaderMatrix)
        fillGradient.setLocalMatrix(shaderMatrix)

        // Pass 1：仅在光带中心附着字形外沿柔和辉光
        glowPaint.shader = glowGradient
        drawWaveGlyphPass(
            canvas = nativeCanvas,
            layout = layout,
            paint = glowPaint,
            startX = startX,
            baselineY = baselineY,
            crestU = crestU,
            waveAmpPx = waveAmpPx,
        )

        // Pass 2：高光带顺滑扫过清晰矢量笔画本身
        fillPaint.shader = fillGradient
        drawWaveGlyphPass(
            canvas = nativeCanvas,
            layout = layout,
            paint = fillPaint,
            startX = startX,
            baselineY = baselineY,
            crestU = crestU,
            waveAmpPx = waveAmpPx,
        )
    }
}

private fun drawWaveGlyphPass(
    canvas: android.graphics.Canvas,
    layout: TransitionGlyphLayout,
    paint: AndroidPaint,
    startX: Float,
    baselineY: Float,
    crestU: Float?,
    waveAmpPx: Float,
) {
    val glyphs = layout.glyphs
    for (idx in glyphs.indices) {
        val glyph = glyphs[idx]
        if (layout.hasVectorPaths && glyph.path.isEmpty) continue
        val intensity = if (crestU != null) gaussianWaveIntensity(glyph.centerU, crestU) else 0f
        canvas.save()
        canvas.translate(startX, baselineY - waveAmpPx * intensity)
        if (layout.hasVectorPaths) {
            canvas.drawPath(glyph.path, paint)
        } else {
            canvas.drawText(glyph.text, glyph.startX, 0f, paint)
        }
        canvas.restore()
    }
}

private fun gaussianWaveIntensity(charU: Float, crestU: Float): Float {
    val d = (crestU - charU) / 0.28f
    val sq = d * d
    return if (sq > 9f) 0f else exp(-sq.toDouble()).toFloat()
}
