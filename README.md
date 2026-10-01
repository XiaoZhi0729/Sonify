# Flamingo（FlamingoSank）

一个 Android 音乐播放器，单 Activity 架构，UI 全部使用 Jetpack Compose，
播放内核基于 AndroidX Media3，在线曲库来源为酷狗。

- Gradle 根工程名：`Flamingo`（见 `settings.gradle`）
- 应用模块：`:app`，`applicationId` 为 `yos.music.player.oss`（加 `.oss` 后缀用于与正式版对照安装，避免覆盖）
- 模块清单：`:app`（应用本体）与 `:overscroll_core`（回弹滚动库），均在 `settings.gradle` 中声明

## 核心模块与所有者

以下路径均相对于 `app/src/main/java/yos/music/player/`。

| 职责 | 入口 | 位置 |
|---|---|---|
| 媒体控制（播放/队列/音质切换的单一事实源） | `object MediaController`（基于 Media3 `MediaController`/`Player`） | `code/MediaController.kt` |
| 曲库仓库（酷狗在线搜索 → `YosMediaItem` → MediaController → Media3 播放） | `object KugouRepository` | `data/repositories/KugouRepository.kt` |
| 音质切换策略（纯函数决策：探测前 NOOP/PROBE、探测后 ROLLBACK/NO_RELOAD/COMMIT_RELOAD） | `object QualitySwitchPolicy` | `data/repositories/QualitySwitchPolicy.kt` |
| 主要 UI 页面 | `Home`、`NowPlaying`（另含横屏变体），以及 discovery / library / search / settings 子目录 | `ui/pages/` |

补充说明：

- `QualitySwitchPolicy` 是从 `MediaController` 协程中抽出的纯函数，使音质切换的每一条分支都能在 JVM 单测中表格驱动验证（对应测试 `app/src/test/java/yos/music/player/data/repositories/QualitySwitchPolicyTest.kt`）。
- 音质相关的数据层还有同目录下的 `KugouQuality.kt`、`QualityModel.kt`、`SongQualityCapability.kt`。
- 进程入口：`MainActivity.kt`（应用根）、后台播放服务 `YosPlaybackService`（Media3 `MediaSessionService`，类定义位于 `code/MediaController.kt`，清单中注册为 `.code.YosPlaybackService`）。

## 构建与测试

仓库自带 Gradle wrapper（Gradle 8.13），无需本机安装 Gradle。
Windows PowerShell 下把 `./gradlew` 换成 `.\gradlew.bat`，多命令用 `;` 分隔而非 `&&`。

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

说明：`release` 构建类型声明了混淆与资源收缩（`minifyEnabled`/`shrinkResources`），
但 `app/build.gradle` 中未配置签名；签名与真机安装流程属于本地发布环节，不在本文件范围内。

## 目录速览

- `app/` — 应用模块（源码在 `app/src/main/java/yos/music/player/`）
- `overscroll_core/` — 回弹滚动的独立库模块
- `app/src/test/` — JVM 单元测试
- `gradle/wrapper/` — Gradle wrapper（8.13）
- 仓库根目录下大量 `_` 前缀的 png/xml/txt/apk 为真机调试与实验的历史产物，非源码
