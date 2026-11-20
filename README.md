# Sonify

<p align="center">
  <img src="docs/screenshots/player_cover.jpg" width="360" alt="Sonify 播放页" />
</p>

**Sonify** 是一款 Android 音乐播放器：液态玻璃质感的 Compose 界面 + Media3 播放内核，
支持酷狗在线曲库与本地资料库，深度适配手机与平板两种形态。

![License](https://img.shields.io/badge/License-GPL--3.0-blue)
![Platform](https://img.shields.io/badge/Platform-Android%206.0%2B-green)
![UI](https://img.shields.io/badge/UI-Jetpack%20Compose-red)

## 特性

- **液态玻璃 UI** — 基于 Backdrop 的背景采样与模糊效果，迷你播放条、全屏播放页与底部导航采用玻璃质感，封面色彩随歌曲流动。
- **在线曲库** — 酷狗音源搜索、精选歌单、每日推荐与私人 FM；支持手机号验证码登录、在线艺人入口和我的歌单管理。
- **应用内更新** — 在「设置 → 关于」检查新版，应用内下载并交给系统安装器；下载后核对大小、SHA-256、包名、版本与签名身份。
- **多音质切换** — 标准到超高音质自由切换，切换过程带探测与失败自动回退，不中断播放。
- **歌词** — 逐行高亮歌词与翻译，支持状态栏歌词（Hook 方案）。
- **完整播放功能** — 倍速播放、睡眠定时器、播放队列拖拽编辑、最近播放。
- **手机 / 平板双形态** — 平板横屏分栏布局，一台设备两种体验。
- **后台播放** — 基于 Media3 / MediaSession，后台稳定播放，通知栏与蓝牙耳机线控齐全。

## 截图

| 主页 | 播放页 | 歌词 |
|---|---|---|
| ![主页](docs/screenshots/home_phone.jpg) | ![播放页](docs/screenshots/player_cover.jpg) | ![歌词](docs/screenshots/player_lyrics.jpg) |

<table>
  <tr>
    <td width="100%"><img src="docs/screenshots/home_tablet.jpg" alt="平板横屏" /></td>
  </tr>
  <tr>
    <td align="center"><sub>平板横屏分栏</sub></td>
  </tr>
</table>

## 下载

前往 [**Releases**](https://github.com/XiaoZhi0729/Sonify/releases) 页面下载最新 APK 安装包。

> 当前发布版本：**[Sonify v0.1.2](https://github.com/XiaoZhi0729/Sonify/releases/tag/v0.1.2)（测试版）**。重做在线艺人页与播放页过渡，修复在线拖动进度、返回手势与歌词控件触发范围问题。项目仍处于早期测试阶段，遇到问题欢迎提 [Issue](https://github.com/XiaoZhi0729/Sonify/issues) 反馈。

> 从 v0.1.0 升级需手动下载并覆盖安装一次；v0.1.1 起可在「设置 → 关于 → 检查更新」获取后续版本。更新仅在用户确认后下载和安装，不会静默安装。

> 需要 Android 6.0（API 23）及以上。小米/Redmi（HyperOS）设备建议在「省电策略」中设为无限制并允许自启动，否则息屏后播放可能被冻结。

## 致谢

本项目是以下两个开源项目的「缝合」产物，在此向两位上游作者致谢：

| 项目 | 说明 |
|---|---|
| [MD3 Music](https://github.com/zzyoxml/md3Music) | 基于 Material Design 3 的本地音乐播放器，本项目在线播放能力的上游 |
| [Flamingo (FlamingoSank)](https://github.com/Yos-X/FlamingoSank) | 本项目的上游基底 |

部分界面与效果是从下列项目**逐行移植**或改写的，在此向原作者致谢：

| 项目 | 说明 | 许可 |
|---|---|---|
| [Cresto / Glasense](https://github.com/nevodev) | 居中玻璃弹窗、材质与动效的设计及实现来源 | Apache-2.0 |
| [Pear Wall](https://github.com/Nevodev/Pear-Wall) | 流体网格播放背景（网格形变、多重模糊、镜头预设）。修改说明见 `assets/licenses/pear-wall-NOTICE.txt` | Apache-2.0 |
| [Lyricify Backgrounds](https://github.com/WXRIW/Lyricify-Backgrounds) | Pear Wall 的网格控制点预设与观感上游 | Apache-2.0 |
| [NexioSchedule](https://github.com/HaoZai000/NexioSchedule) | 顶栏玻璃圆钮、液态玻璃下拉弹层、渐进模糊顶栏 | AGPL-3.0 |
| [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) | 液态玻璃滑块、自适应亮度采样 | Apache-2.0 |
| [ComposeOverscroll](https://github.com/Cormor/ComposeOverscroll) | 列表越界回弹（`overscroll_core` 模块） | GPL-3.0 |
| [Tailwind CSS](https://github.com/tailwindlabs/tailwindcss) | 玻璃对话框的配色取值。见 `assets/licenses/cresto-THIRD_PARTY_NOTICES.md` | MIT |

同时感谢以下开源库：

[Jetpack Compose](https://developer.android.com/jetpack/compose) ·
[Media3 / ExoPlayer](https://github.com/androidx/media3) ·
[Media3 FFmpeg 扩展](https://github.com/androidx/media/tree/release/libraries/decoder_ffmpeg)（音频软解） ·
[Coil](https://github.com/coil-kt/coil) ·
[Accompanist](https://github.com/google/accompanist) ·
[Accompanist Lyrics](https://github.com/6xingyv/accompanist-lyrics-ui)（歌词渲染，本项目打补丁修改，见 `docs/LYRIC_LIB_VALIDATION.md`） ·
[Backdrop](https://github.com/Kyant0/backdrop)（液态玻璃背景模糊） ·
[Haze](https://github.com/chrisbanes/haze)（模糊玻璃材质） ·
[Shapes](https://github.com/Kyant0/Shapes)（连续曲率圆角） ·
[Cupertino](https://github.com/alexzhirkevich/cupertino) ·
[ComposeDataSaver](https://github.com/FunnySaltyFish/ComposeDataSaver) ·
[ConfettiKit](https://github.com/vinceglb/ConfettiKit) ·
[AndroidUtilCode](https://github.com/Blankj/AndroidUtilCode) ·
[AndroidX Palette](https://developer.android.com/jetpack/androidx/releases/palette) ·
[Material Color Utilities](https://github.com/evant/material-color-utilities) ·
[MMKV](https://github.com/Tencent/MMKV) ·
[OkHttp](https://github.com/square/okhttp) ·
[Gson](https://github.com/google/gson) ·
[TinyPinyin](https://github.com/promeG/TinyPinyin) ·
[TensorFlow Lite](https://github.com/tensorflow/tensorflow)（人声分离推理） ·
[TagLib](https://github.com/Kyant0/taglib) ·
[libPhonograph](https://github.com/FoedusProgramme/libPhonograph)（本地音乐库扫描） ·
[Lyric Getter API](https://github.com/xiaowine/Lyric-Getter-Api) ·
[mochi-compose](https://github.com/feice11/mochi-compose)（HDR 高光）

## 构建与测试

仓库自带 Gradle wrapper（Gradle 9.3.1），需要 **JDK 17+** 与
Android Studio（SDK 37）。Windows PowerShell 下把 `./gradlew` 换成 `.\gradlew.bat`，
多命令用 `;` 分隔而非 `&&`。

```powershell
# 运行全部 JVM 单元测试（app 与 overscroll_core 两个模块）
./gradlew test

# 只跑 app 模块的 debug 单元测试
./gradlew :app:testDebugUnitTest

# 组装调试包（产物在 app/build/outputs/apk/debug/）
./gradlew assembleDebug
```

单元测试位于 `app/src/test/java/yos/music/player/`，以数据层与纯逻辑为主
（音质解析链、歌词加载、导航、UI 几何等），可完全在 JVM 上运行，无需连接设备。

说明：`release` 构建类型已启用混淆与资源收缩（`minifyEnabled`/`shrinkResources`），
但仓库不包含签名配置；签名与发布流程属于本地发布环节。

## 架构速览

单 Activity 架构，UI 全部使用 Jetpack Compose，播放内核基于 AndroidX Media3。

- 应用模块 `:app`，`applicationId` 为 `com.sonify.music`；另有回弹滚动库 `:overscroll_core`
- Gradle 根工程名 `Flamingo` 为历史遗留内部标识，与对外名称 Sonify 不冲突

| 职责 | 入口 | 位置（`app/src/main/java/yos/music/player/` 下） |
|---|---|---|
| 媒体控制（播放/队列/音质切换的单一事实源） | `object MediaController` | `code/MediaController.kt` |
| 在线曲库仓库 | `object KugouRepository` | `data/repositories/KugouRepository.kt` |
| 音质切换策略（纯函数，表格驱动 JVM 单测覆盖） | `object QualitySwitchPolicy` | `data/repositories/QualitySwitchPolicy.kt` |
| 主要 UI 页面 | `Home`、`NowPlaying` 及 discovery / library / search / settings 子目录 | `ui/pages/` |

欢迎 Issue 与 PR。提交前请先运行 `./gradlew test` 确认单测通过。

## License 与免责声明

本项目基于 [GPL-3.0](LICENSE) 协议开源。

本项目与酷狗官方无任何关联；在线音源能力基于公开接口实现，仅供学习与技术交流使用，
请支持正版音乐。使用本项目产生的任何问题由使用者自行承担。
