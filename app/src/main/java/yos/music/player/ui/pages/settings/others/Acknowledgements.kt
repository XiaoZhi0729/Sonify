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
    AckEntry("Lyric Getter API", "歌词获取接口", "https://github.com/xiaowine/Lyric-Getter-Api")
)

@Composable
fun Acknowledgements(navController: NavController) =
    SettingBackground {
        Title(title = stringResource(id = R.string.settings_others_about_acknowledgements),
            onBack = {
                navController.popBackStack()
            },
            titleHorizontalPadding = 32.dp,
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