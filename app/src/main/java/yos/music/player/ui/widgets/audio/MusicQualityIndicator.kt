package yos.music.player.ui.widgets.audio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import yos.music.player.R
import yos.music.player.code.MediaController
import yos.music.player.code.MediaController.musicPlaying
import yos.music.player.code.qualityLabelResOf
import yos.music.player.data.libraries.SettingsLibrary
import yos.music.player.data.objects.MediaViewModelObject
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

    // 订阅四个变化源：解析完成 / 本曲覆盖变更 / 网络形态变更 / 能力表新结论
    //（最后一项让"播放即探测"的结果一到就反映到面板上，不等下一次解析）
    KugouRepository.actualQualityVersion.intValue
    PerSongQualityIntent.version.intValue
    NetworkObserver.version.intValue
    SongQualityCapabilityStore.version.intValue

    val uri = music.value?.uri
    val isOnline = uri?.scheme == KugouRepository.PLACEHOLDER_SCHEME
    val hash = uri?.lastPathSegment?.takeIf { isOnline }

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
        provenUnavailableTiers = SongQualityCapabilityStore.provenUnavailableOf(hash)
    )

    var menuOpen by remember { mutableStateOf(false) }

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

    // 胶囊本体随面板揭示进度的联动：面板从胶囊顶边下方长出并盖住它（可见顶边压在锚点上方
    // 7dp），胶囊跟着同向下沉 6dp、缩到 0.9、淡出；与「更多」按钮用的是同一套参数
    val capsuleFollow = rememberLiquidDropdownFollowState()

    fun captureAnchor(coords: androidx.compose.ui.layout.LayoutCoordinates) {
        val pos = coords.positionInWindow()
        val size = coords.size
        anchorBounds = IntRect(
            left = pos.x.toInt(),
            top = pos.y.toInt(),
            right = pos.x.toInt() + size.width,
            bottom = pos.y.toInt() + size.height
        )
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
                    if (music.value != null) {
                        // 涟漪从胶囊方框里漫出来（浅色玻璃上尤其脏），且这枚徽标本身就是
                        // 控件本体、按下即弹面板，不需要水波确认；关掉 indication 只留点击
                        Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null
                        ) { menuOpen = true }
                    } else {
                        Modifier
                    }
                )
        ) {
            // 无曲目时不画胶囊（外壳 22dp 行高固定，不会引起布局跳动）；
            // 但有曲目而读数未知时**照旧画胶囊**，只把文字换成兜底文案——
            // 旧版在 labelText == null 时把节点整块从组合树抽走，解析完成又弹回来（断言 A6）
            Crossfade(
                targetState = isDolby,
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
                    // 档位文本变化只做文字层 crossfade，胶囊尺寸恒定（"无损 FLAC" 与
                    // "Hi-Res" 宽度不同，不锁最小宽度就会把左右时间数字推着走）
                    val label = ui.capsule.tier?.let { stringResource(id = qualityLabelResOf(it)) }
                        ?: stringResource(id = ui.capsule.fallbackRes)
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
                            if (ui.capsule.losslessBadge) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_quality_lossless),
                                    contentDescription = "quality_lossless",
                                    modifier = Modifier
                                        .height(9.dp)
                                        .alpha(0.45f)
                                )
                                Spacer(modifier = Modifier.width(5.dp))
                            }

                            YosWrapper {
                                AnimatedContent(
                                    targetState = label,
                                    transitionSpec = {
                                        fadeIn(tween(160)) togetherWith fadeOut(tween(120))
                                    }
                                ) { thisText ->
                                    Text(
                                        text = thisText,
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

        QualitySheet(
            uiModel = ui,
            expanded = menuOpen,
            anchorBounds = anchorBounds,
            onFractionProgress = capsuleFollow::onProgress,
            onDismiss = { menuOpen = false },
            onSelect = { tier ->
                // 在线选档：只改本首。已证明不可用的行不会走到这里（置灰行不响应点击）
                MediaController.requestQualityForCurrentSong(tier)
            }
        )
    }
}

/**
 * 音质选择玻璃下拉：**只有四档单选行**，与播放页「更多」菜单同属一套
 * [LiquidDropdownLayout] 引擎——进入/退出弹簧（0.78/232 与 0.78/400）、alpha tween
 * (120/320)、缩放 0.24→1、反向补偿圆角 25dp、vibrancy + blur(24dp)、表面 0.6 全部
 * 同源同值，不再维护第二套弹层。
 *
 * 与「更多」菜单只有两处因几何而不同的参数：这里胶囊在时间行居中，所以面板跟着
 * 居中摆位（[alignCenterHorizontally]）；四行比两行长，按 Nexio 的"条目超过两行就
 * 把揭示裁剪压到两行高"规则给了 [revealLimitHeight]（本组件行高下两行≈ 96dp，
 * 不同于设置页 Miuix 度量的 112dp），面板从胶囊处向下展开而不是纯缩放。
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
    onSelect: (String) -> Unit
) {
    LiquidDropdownLayout(
        expanded = expanded,
        anchorBounds = anchorBounds,
        alignCenterHorizontally = true,
        // 播放页专属透明度：深浅色统一 0.6（与「更多」菜单同值；设置页保持 0.72/0.8 不变）
        surfaceAlpha = 0.6f,
        onDismissRequest = onDismiss,
        revealLimitHeight = 96.dp,
        backdrop = LocalTitlePageBackdrop.current,
        onFractionProgress = onFractionProgress
    ) {
        // 这里**不**套「更多」菜单那套高度封顶（liquidDropdownHeightCap）：四档面板的自然高
        // 约 184dp，而胶囊住在进度条下方的时间行（靠屏底），按锚点余量封顶会把最后一行
        // 挤成内滚；短窗口（多窗口/横屏）下引擎在 measure 阶段已经用"窗口高 - 阴影外扩"
        // 硬卡过一道，不会超出去。封顶留给需要贴锚点行堆叠的多行面板
        LiquidDropdownColumn {
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
