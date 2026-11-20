package yos.music.player.ui.pages.settings.others

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.navigation.NavController
import yos.music.player.R
import yos.music.player.ui.pages.settings.GroupSpacer
import yos.music.player.ui.pages.settings.LabelItem
import yos.music.player.ui.pages.settings.ListHeader
import yos.music.player.ui.pages.settings.SettingBackground
import yos.music.player.ui.pages.settings.startWeb
import yos.music.player.ui.widgets.basic.RoundColumn
import yos.music.player.ui.widgets.basic.Title
import yos.music.player.ui.navigation.popBackStackSafely

private data class AckEntry(val name: String, val desc: String, val url: String)

// 前两项为项目级上游，必须保持第一、第二的顺序
private val ackProjects = listOf(
    AckEntry(
        name = "MD3 Music",
        desc = "基于 Material Design 3 的本地音乐播放器，本项目在线播放能力的上游",
        url = "https://github.com/zzyoxml/md3Music"
    ),
    AckEntry(
        name = "Flamingo (FlamingoSank)",
        desc = "本项目的上游基底",
        url = "https://github.com/Yos-X/FlamingoSank"
    ),
    AckEntry(
        name = "Cresto / Glasense",
        desc = "居中玻璃弹窗、材质与动效的设计及实现来源（Apache-2.0）",
        url = "https://github.com/nevodev"
    ),
    AckEntry(
        name = "Pear Wall",
        desc = "流体网格播放背景（网格形变、多重模糊与镜头预设）的移植来源，由 Nevodev 开发（Apache-2.0）",
        url = "https://github.com/Nevodev/Pear-Wall"
    ),
    AckEntry(
        name = "Lyricify Backgrounds",
        desc = "Pear Wall 的网格控制点预设与观感上游（Apache-2.0）",
        url = "https://github.com/WXRIW/Lyricify-Backgrounds"
    ),
    AckEntry(
        name = "NexioSchedule",
        desc = "顶栏玻璃圆钮、液态玻璃下拉弹层与渐进模糊顶栏的移植来源，由 HaoZai000 开发（AGPL-3.0）",
        url = "https://github.com/HaoZai000/NexioSchedule"
    ),
    AckEntry(
        name = "AndroidLiquidGlass",
        desc = "液态玻璃滑块与自适应亮度采样的移植来源，由 Kyant 开发（Apache-2.0）",
        url = "https://github.com/Kyant0/AndroidLiquidGlass"
    ),
    AckEntry(
        name = "ComposeOverscroll",
        desc = "列表越界回弹效果（overscroll_core 模块），由 cormor 开发（GPL-3.0）",
        url = "https://github.com/Cormor/ComposeOverscroll"
    )
)

private val ackLibraries = listOf(
    AckEntry("Jetpack Compose", "Android 声明式 UI 框架", "https://developer.android.com/jetpack/compose"),
    AckEntry("Media3 / ExoPlayer", "音频播放内核", "https://github.com/androidx/media3"),
    AckEntry("Coil", "图片加载与缓存", "https://github.com/coil-kt/coil"),
    AckEntry("Accompanist", "Compose 工具库（Insets、Pager 等）", "https://github.com/google/accompanist"),
    AckEntry("Backdrop", "液态玻璃背景模糊效果", "https://github.com/Kyant0/backdrop"),
    AckEntry("Haze", "模糊玻璃材质", "https://github.com/chrisbanes/haze"),
    AckEntry("Cupertino", "iOS 风格 Compose 组件", "https://github.com/alexzhirkevich/cupertino"),
    AckEntry("ComposeDataSaver", "Compose 数据持久化", "https://github.com/FunnySaltyFish/ComposeDataSaver"),
    AckEntry("AndroidUtilCode", "Android 工具类库", "https://github.com/Blankj/AndroidUtilCode"),
    AckEntry("MMKV", "高性能键值存储", "https://github.com/Tencent/MMKV"),
    AckEntry("OkHttp", "网络请求", "https://github.com/square/okhttp"),
    AckEntry("Gson", "JSON 解析", "https://github.com/google/gson"),
    AckEntry("TinyPinyin", "拼音索引", "https://github.com/promeG/TinyPinyin"),
    AckEntry("TagLib", "音频元数据解析", "https://github.com/Kyant0/taglib"),
    AckEntry("Lyric Getter API", "歌词获取接口", "https://github.com/xiaowine/Lyric-Getter-Api"),
    AckEntry("mochi-compose", "HDR 高光与分段选择器 / 滑块动画来源（Apache-2.0）", "https://github.com/feice11/mochi-compose"),
    AckEntry(
        "Accompanist Lyrics",
        "歌词渲染组件（lyrics-ui / lyrics-core），本项目在其基础上打补丁修改，详见 docs/LYRIC_LIB_VALIDATION.md（Apache-2.0）",
        "https://github.com/6xingyv/accompanist-lyrics-ui"
    ),
    AckEntry("Shapes", "胶囊与连续曲率圆角图形（Apache-2.0）", "https://github.com/Kyant0/Shapes"),
    AckEntry("ConfettiKit", "关于页横幅连点彩带动效（MIT）", "https://github.com/vinceglb/ConfettiKit"),
    AckEntry(
        "AndroidX Palette",
        "艺人页与歌词页封面取色",
        "https://developer.android.com/jetpack/androidx/releases/palette"
    ),
    AckEntry(
        "Material Color Utilities",
        "HCT 色彩空间、封面量化与同色系明度阶梯（Apache-2.0）",
        "https://github.com/evant/material-color-utilities"
    ),
    AckEntry(
        "TensorFlow Lite",
        "人声分离的本地实时推理（Apache-2.0）",
        "https://github.com/tensorflow/tensorflow"
    ),
    AckEntry(
        "libPhonograph",
        "本地音乐库扫描与 MediaStore 读取，由 FoedusProgramme 开发（GPL-3.0）",
        "https://github.com/FoedusProgramme/libPhonograph"
    ),
    AckEntry(
        "Media3 FFmpeg 扩展",
        "音频软解解码器（Apache-2.0，内含 FFmpeg / libavcodec）",
        "https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg"
    ),
    AckEntry(
        "Tailwind CSS",
        "玻璃对话框的配色取值（MIT）",
        "https://github.com/tailwindlabs/tailwindcss"
    )
)

@Composable
fun Acknowledgements(navController: NavController) =
    SettingBackground {
        Title(title = stringResource(id = R.string.settings_others_about_acknowledgements),
            onBack = {
                navController.popBackStackSafely()
            },
            titleHorizontalPadding = 34.5.dp,
            content = {
                item("projects") {
                    Column(Modifier.fillMaxSize()) {
                        val context = LocalContext.current

                        ListHeader(content = stringResource(id = R.string.settings_others_about_ack_projects))
                        RoundColumn {
                            ackProjects.forEach { entry ->
                                LabelItem(title = entry.name, desc = entry.desc) {
                                    startWeb(url = entry.url, context)
                                }
                            }
                        }
                    }
                }

                item("libraries") {
                    Column(Modifier.fillMaxSize()) {
                        val context = LocalContext.current

                        GroupSpacer()
                        ListHeader(content = stringResource(id = R.string.settings_others_about_ack_libraries))
                        RoundColumn {
                            ackLibraries.forEach { entry ->
                                LabelItem(title = entry.name, desc = entry.desc) {
                                    startWeb(url = entry.url, context)
                                }
                            }
                        }
                    }
                }
            }
        )
    }