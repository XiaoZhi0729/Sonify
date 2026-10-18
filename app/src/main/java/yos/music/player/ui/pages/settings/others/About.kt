package yos.music.player.ui.pages.settings.others

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import com.blankj.utilcode.util.AppUtils
import io.github.vinceglb.confettikit.compose.ConfettiKit
import io.github.vinceglb.confettikit.core.Angle
import io.github.vinceglb.confettikit.core.Party
import io.github.vinceglb.confettikit.core.Position
import io.github.vinceglb.confettikit.core.emitter.Emitter
import kotlinx.coroutines.delay
import kotlin.random.Random
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import yos.music.player.R
import yos.music.player.code.utils.others.Vibrator
import yos.music.player.ui.UI
import yos.music.player.ui.pages.settings.DefaultItem
import yos.music.player.ui.pages.settings.Divider
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.SwitchItem
import yos.music.player.ui.pages.settings.startWeb
import yos.music.player.ui.theme.YosRoundedCornerShape
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.widgets.effects.coverArtworkEffect
import yos.music.player.ui.widgets.effects.coverArtworkGesture
import yos.music.player.ui.widgets.effects.rememberCoverArtworkState
import yos.music.player.update.DeveloperSettings
import yos.music.player.update.UpdateSettingsItems

private val bannerCorner = YosRoundedCornerShape(9.dp)

// 连点横幅触发彩带的次数（与 Cresto 关于页一致）
private const val BannerConfettiTapCount = 10

@Composable
fun About(navController: NavController) {
    val context = LocalContext.current
    // 横幅交互动效：与播放页封面共用同一套驱动（按下 3D 倾斜/缩放 → 确认后涟漪 +
    // 白描边高光 + 触感）。状态必须建在页面这一层，不要放进会随滚动回收的 item 子树里。
    val bannerFx = rememberCoverArtworkState(feedback = { Vibrator.click(context) })
    var bannerTapCount by remember { mutableIntStateOf(0) }
    var confettiBurstKey by remember { mutableIntStateOf(0) }

    SettingBackground {
        Title(title = stringResource(id = R.string.settings_others_about),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
            content = {
                item("settings") {
                    Column(Modifier.fillMaxSize()) {
                        // 本地 State 镜像 MMKV：MMKV 写入不触发重组，开关勾选态靠它即时刷新
                        val devMode = remember { mutableStateOf(DeveloperSettings.fakeUpdateDialog) }

                        val coolapkLink = "https://www.coolapk.com/u/35925506"

                        // 头部横幅：圆角裁剪 + 与歌曲封面同款的双遍描边
                        // （12f 原始像素、外半被 Offscreen 裁剪，可见约 2dp；第二遍 Overlay
                        //  是让描边在浅底/深底上都能读出来的关键，勿改成 SrcOver）
                        //
                        // 交互层顺序即正确性：倾斜 graphicsLayer 必须**在裁剪层之外**，
                        // 否则旋转时四角会被那张固定矩形裁掉；涟漪/高光的裁剪面在
                        // coverArtworkEffect 内部自己按圆角裁，不受这里影响。
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.5.dp)
                                .aspectRatio(1376f / 768f)
                                .coverArtworkGesture(
                                    state = bannerFx,
                                    enabled = { true },
                                    onClick = {
                                        bannerTapCount += 1
                                        if (bannerTapCount >= BannerConfettiTapCount) {
                                            bannerTapCount = 0
                                            confettiBurstKey += 1
                                        }
                                    }
                                )
                                .coverArtworkEffect(
                                    state = bannerFx,
                                    cornerRadius = 9.dp,
                                    squareFace = false
                                )
                                .graphicsLayer {
                                    compositingStrategy = CompositingStrategy.Offscreen
                                    clip = true
                                    this.shape = bannerCorner
                                }
                                .drawWithCache {
                                    val outline = bannerCorner.createOutline(
                                        size = size,
                                        layoutDirection = LayoutDirection.Ltr,
                                        density = this
                                    )
                                    onDrawWithContent {
                                        drawContent()
                                        drawOutline(
                                            outline = outline,
                                            color = Color.Gray.copy(alpha = 0.1f),
                                            style = Stroke(width = 12f)
                                        )
                                        drawOutline(
                                            outline = outline,
                                            color = Color.Gray.copy(alpha = 0.5f),
                                            style = Stroke(width = 12f),
                                            blendMode = BlendMode.Overlay
                                        )
                                    }
                                }
                        ) {
                            Image(
                                painter = painterResource(
                                    id = if (isFlamingoInDarkMode()) R.drawable.sonify_banner_dark
                                    else R.drawable.sonify_banner_light
                                ),
                                contentDescription = "Sonify",
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        GroupSpacer()

                        ListHeader(content = stringResource(id = R.string.settings_others_about_info))
                        val appVersion = remember("About_appVersion") {
                            mutableStateOf(AppUtils.getAppVersionName())
                        }

                        RoundColumn {
                            DefaultItem(
                                title = stringResource(id = R.string.app_name),
                                onClick = null,
                                desc = appVersion.value
                            )
                            UpdateSettingsItems(developerModeEnabled = devMode.value)
                        }

                        GroupSpacer()

                        ListHeader(content = stringResource(id = R.string.settings_others_about_developers))
                        RoundColumn {
                            LabelItem(
                                title = "XiaoZhi",
                                desc = stringResource(id = R.string.settings_others_about_developers_yos_x),
                                leading = { XiaoZhiAvatar() }
                            ) {
                                startWeb(
                                    url = "https://github.com/XiaoZhi0729",
                                    context
                                )
                            }
                            Divider()
                            // 贡献者：头像随机轮播 10 张图（0.3s 一张，交叉淡化 + 描边闪动）。
                            // 不可点击，所以不留行尾箭头——点了没反应比没有箭头更糟。
                            LabelItem(
                                title = "你",
                                desc = stringResource(id = R.string.settings_others_about_developers_contributor),
                                showArrow = false,
                                leading = { ContributorAvatar() },
                                onClick = null
                            )
                        }

                        GroupSpacer()

                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_others_about_acknowledgements)
                            ) {
                                navController.navigate(UI.Settings.Acknowledgements)
                            }
                        }

                        GroupSpacer()

                        RoundColumn {
                            LabelItem(
                                title = stringResource(id = R.string.settings_others_about_contact_developer),
                                desc = stringResource(id = R.string.settings_others_about_developer_coolapk),
                                superLink = true
                            ) {
                                startWeb(
                                    url = coolapkLink,
                                    context
                                )
                            }
                        }

                        GroupSpacer()

                        RoundColumn {
                            SwitchItem(
                                title = stringResource(id = R.string.settings_others_about_developer_mode),
                                desc = stringResource(id = R.string.settings_others_about_developer_mode_desc),
                                checkedLambda = { devMode.value },
                                onClick = {
                                    DeveloperSettings.fakeUpdateDialog = !devMode.value
                                    devMode.value = !devMode.value
                                }
                            )
                        }

                        // POC Debug Entry
                        /*RoundColumn {
                            LabelItem(
                                title = "🧪 POC: Rust Native Online Playback",
                                desc = "Test md3Music's Rust server → ExoPlayer integration"
                            ) {
                                navController.navigate("poc_debug")
                            }
                        }*/
                    }
                }
            }
        )

        // 连点 10 次横幅：双侧对射彩带（Cresto 关于页同款）。key 让每次里程碑都重新起一场；
        // 彩带放完自动卸载，避免 ConfettiKit 常驻在页面里空转。
        if (confettiBurstKey > 0) {
            key(confettiBurstKey) {
                LaunchedEffect(Unit) {
                    repeat(10) {
                        Vibrator.click(context)
                        delay(Random.nextLong(50, 100).milliseconds)
                    }
                    delay(5.seconds)
                    confettiBurstKey = 0
                }
                ConfettiKit(
                    modifier = Modifier.fillMaxSize(),
                    parties = parade()
                )
            }
        }
    }
}

/** Cresto 关于页的双侧对射彩带，颜色换成同一组 400 档色值。 */
private fun parade(): List<Party> {
    val party = Party(
        speed = 10f,
        maxSpeed = 30f,
        damping = 0.91f,
        angle = Angle.RIGHT - 45,
        spread = 270,
        colors = listOf(
            Color(0xFFFFB900).toArgb(), // Amber400
            Color(0xFFC27AFF).toArgb(), // Purple400
            Color(0xFFFF637E).toArgb(), // Rose400
            Color(0xFFFB64B6).toArgb(), // Pink400
            Color(0xFF51A2FF).toArgb(), // Blue400
            Color(0xFFFF8904).toArgb(), // Orange400
            Color(0xFF05DF72).toArgb()  // Green400
        ),
        timeToLive = 4000L,
        emitter = Emitter(duration = 0.8.seconds).perSecond(150),
        position = Position.Relative(-0.1, 0.1)
    )

    return listOf(
        party,
        party.copy(
            angle = party.angle - 90, // 左右两侧各射一发
            position = Position.Relative(1.1, 0.1)
        )
    )
}