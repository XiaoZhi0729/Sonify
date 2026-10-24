# Flamingo / Sonify 性能优化专项 · 测试计划

> 版本 v2（2026-10-23）。v2 相对初稿的修订见文末附录 A。

---

## 0. 总策略

全部测量在 **release 签名包**上做。

- **主判据**：`dumpsys gfxinfo <pkg> framestats` 的逐帧延迟（`FrameCompleted − IntendedVsync` 与 `DisplayPresentTime − IntendedVsync`），换算成 miss1 / miss2 / miss3。
- **辅助判据**：`SurfaceFlinger --latency` 的呈现时间、GPU 百分位。
- **归因**：Perfetto / simpleperf，且**与主指标那一轮分开跑**，避免探针自身扰动被测帧。
- **不使用** HWUI 摘要里的 "Janky frames %"：它按每帧自己的 deadline 判定，120Hz 下每帧 23ms 仍可能报约 1%（仓库 `tools/ref_player_frames.ps1:18-20` 已有实测记录）。
- **不使用** HISTOGRAM 分桶反算作为判据（1ms 粒度，仅作快速筛查）。

测试顺序按"最重 + 最高频"排；先出基线，再改码，每次改完回归同一套场景。

---

## 1. 被测物固定

### 1.1 构建与签名

```bash
./gradlew clean assembleRelease
# 产物：app/build/outputs/apk/release/app-release-unsigned.apk
# 用 ~/.android/debug.keystore（pass: android）apksigner 签名后安装
sha256sum app-release-signed.apk
```

- `env.txt` 记录：APK sha256、versionCode / versionName、构建时间、设备序列号。
- **整个专项固定同一份基线包**；换包必须重记基线，旧数据不与新数据混算。

### 1.2 唯一的代码改动：`<profileable>`

```xml
<application ...>
    <profileable android:shell="true" />
```

- `AndroidManifest.xml:3` 已声明 `xmlns:tools`，无需额外命名空间，也不需要 `tools:targetApi`。
- API 29+ 生效，minSdk 23 无副作用；不改变运行时代码路径。
- 作用：允许 Perfetto 应用级 atrace 标签、simpleperf、heapprofd。
- 说明：加了它之后被测包 ≠ 最终发布包（多一个 manifest 属性）。这是可接受的偏差，但必须记录 sha256 以示区分；发布前可移除。

### 1.3 dexopt 状态固定（必须做）

重装 APK 会重置 ART 编译产物，冷启动和首屏数据因此不可比。每次 `install` 后统一执行：

```bash
adb shell cmd package compile -m speed -f com.sonify.music
adb shell dumpsys package com.sonify.music | grep -A4 'Dexopt state'
```

把实际编译器过滤（`speed` / `speed-profile` / `verify`）写进 `env.txt`。**所有冷启动、冷进入数据只在同一种 dexopt 状态下比较。**

### 1.4 在线内容与账号固定

App 没有"关闭个性化推荐"的开关，能做到的上限就是固定账号 + 固定内容 ID：

- 固定测试账号（只记登录态，不记密码）。
- 场景只用固定 ID：1 个在线歌单（`playlistId`）、1 张在线专辑（`albumId`）、1 个艺人（`artistId`）、1 个搜索词。全部写进 `env.txt`。
- "从主页推荐卡进入"这条路径用的是动态内容：记录当次实际进入的 playlistId，该场景只在同一账号、同一天的连续会话内比较。
- 测量前手动浏览一遍目标页，让封面与调色板缓存进入稳态；`summary.csv` 记录 `cacheState=cold|warm`。

### 1.5 输入法固定

manifest 没有 `windowSoftInputMode`，App 也不主动管理 IME（只有 `SongCommentsPanel.kt:127` 读 ime inset）。

```bash
adb shell settings get secure default_input_method
```

- 整个专项不改该值，并记录进 `env.txt`。
- 文字输入统一用 `adb shell input text`（注入层，不依赖 IME 预测）；但**键盘实际高度依赖 IME**，所以评论页、搜索页的 insets 表现必须固定 IME 才可比。
- 键盘相关场景额外记录键盘高度。

### 1.6 环境锁定

| 项 | 做法 | 校验命令 |
|---|---|---|
| 刷新率 | 固定 120Hz | `dumpsys display \| grep mActiveSfDisplayMode` |
| 亮度 | 关自动亮度，固定约 50% | `settings get system screen_brightness` |
| 动画 | animator / window / transition scale 保持 **1.0**（动画是被测对象；慢放只在视觉核验时临时设 10） | `settings get global animator_duration_scale` |
| 电源 | 关省电与自适应电池，充满后拔线，开常亮 | `dumpsys battery \| grep -E 'level\|temperature'` |
| 网络 | 同一 Wi-Fi，同一路由 | — |
| 温度 | 每轮前记录；**冷却判据是温度回到基线 ±1°C，不用固定 20s** | `dumpsys thermalservice` |
| 控制变量 | `DiagLogEnabled` 默认 true 且 release 生效，保持默认（= 出厂态）；关闭只用于归因，收益不计入优化成绩 | 设置页 |

---

## 2. 断言体系（每次测量必须全过，否则该轮作废）

`mCurrentFocus` 只能证明"前台是本 App"，不能证明"停在正确页面"——播放页是 `MainActivity` 内的 overlay，Tab、滚动位置、弹窗、键盘都不会改变 focus。因此分三级：

### L1 前台进程

```bash
adb shell dumpsys window | grep -m1 mCurrentFocus
# 必须是 com.sonify.music；出现 "no-output" / "unparsed:" 直接中止
```

### L2 页面级（uiautomator dump + 关键节点）

当前源码里真实存在的锚点（`onyx_gfx.sh` 用的 `text="Thank You"` 已不在源码中，该判定已失效，不能沿用）：

| 状态 | 锚点 |
|---|---|
| 播放页已展开 | `content-desc="Max Volume"`（`NowPlaying.kt:4672`）或 `content-desc="Mute"`（`:4547`） |
| 迷你条折叠态 | **不存在** `Max Volume`，且存在 `content-desc="Play"`（`MainActivity.kt:3411`）与 `content-desc="Next"`（`:2146`） |
| 底部导航 | `content-desc="主页"` / `"喜爱"` / `"资料库"` / `"搜索"` |
| 详情页 | 页面标题文本 + 该页唯一控件文本（每个场景在参数表里登记） |

注意：`testTag` 不会出现在 uiautomator dump 里（App 没有 `testTagsAsResourceId`），不要依赖它。

**`uiautomator dump` 本身约 1s 开销，只在测量窗口之外执行**（reset 之前、dump 之后），绝不在计时区间内。

### L3 状态级

播放 / 暂停、起始 Tab、滚动位置必须与 §7 的前置表一致：

```bash
adb shell dumpsys media_session | grep -A3 com.sonify.music   # state=3 为播放
```

---

## 3. 手势参数化

### 3.1 基础信息（每次会话记录）

```bash
adb shell wm size        # 例：Physical size: 1080x2400
adb shell wm density
```

下表的坐标以 1080×2400 为模板。**首次执行每个场景时必须用 uiautomator dump 的 bounds 核准实际坐标，核准后的值替换模板并写入 `env.txt`，此后不再变动。**

滑动一律固定 **起点 / 终点 / 时长(ms)**。时长 120–180ms 才保留速度产生 fling；700ms 以上是慢速拖拽，不会触发惯性。

### 3.2 手势登记表（模板，坐标需核准一次）

| 名称 | 起点 | 终点 | 时长 | 用途 |
|---|---|---|---|---|
| `sw.expand` | 540,2400 | 540,700 | 180ms | mini 条上拖到全屏 |
| `sw.collapse` | 540,700 | 540,2400 | 220ms | 全屏下拖回 mini |
| `sw.collapse.slow` | 540,1200 | 540,2500 | 220ms | 仓库既有写法，用于状态复位 |
| `sw.fling.up` | 540,2000 | 540,400 | 120ms | 列表快速上甩 |
| `sw.fling.down` | 540,600 | 540,2200 | 120ms | 列表快速下甩 |
| `sw.back.gesture` | 6,1200 | 700,1200 | 300ms | 系统手势返回（左缘右滑） |
| `tap.tab.*` | 135/405/675/945, 2340 | — | — | 四个底部 Tab |

### 3.3 需要跟手 / 中途反向的场景

`input swipe` 是单向单调运动，无法表达"拖到一半停住再反向"。这类场景用：

```bash
adb shell input motionevent DOWN 540 1600
adb shell input motionevent MOVE 540 1200
adb shell input motionevent MOVE 540 800
adb shell input motionevent UP 540 800
```

**先验证该子命令在 onyx 上可用**；不可用时退化为单向长滑，并在结论里明确写"只测了单向跟手，未测反向"。

### 3.4 每轮三重验证

手势执行后必须同时满足，否则该轮作废：

1. `framestats` 帧数 > 0；
2. 页面确实发生变化（L2 锚点前后不同）；
3. 目标页锚点已出现。

---

## 4. 指标与统计口径

### 4.1 framestats 列映射（仓库目前没有逐帧解析器，需按此写）

`---PROFILEDATA---` 块内每行 CSV：

| 索引 | 列 | 用途 |
|---|---|---|
| 0 | `Flags` | 剔除 idle / 跳帧记录（现有 Python 脚本只做了数字校验，从未读该列） |
| 2 | `IntendedVsync` | 帧起点（t0） |
| 11 | `FrameInterval` | 当帧的 vsync 周期 |
| 18 | `FrameCompleted` | UI 线程完成 |
| 21 | `GpuCompleted` | GPU 完成 |
| 22 | `SwapBuffersCompleted` | 交换完成 |
| 23 | `DisplayPresentTime` | 上屏时间 |

计算：

- `uiLatency = FrameCompleted − IntendedVsync`
- `presentLatency = DisplayPresentTime − IntendedVsync`
- `missN` = `presentLatency > N × FrameInterval` 的帧占比（120Hz 下 1/2/3 分别约 8.33 / 16.7 / 25.0 ms）

**framestats 默认只保留最近约 120 帧**（首次执行时用实际行数确认）。因此一次 `reset → 手势 → dump` 只能覆盖一个手势；"连续 5 次 fling"必须拆成 5 个采集窗口，不能跑完再 dump 一次。

### 4.2 GPU

- 优先读摘要里的 `50th / 95th gpu percentile`。该行不是所有设备/版本都有；解析缺失时应返回"不可用"并记录，不能当成 0。
- 缺失时回退到 framestats 的 `GpuCompleted − IntendedVsync`。
- `SurfaceFlinger --latency` 是**呈现时间**，不是 GPU 耗时，两者分开记录、不要混称。

### 4.3 首帧可见（"及时"的量化）

- **计时**：Perfetto frametimeline + `--latency`。t0 = 输入 UP 事件时间戳（atrace `input` 类别），t1 = 其后首个 `DisplayPresentTime` 的帧。
- **视觉核验**：`settings put global animator_duration_scale 10` 慢放后 screencap 连拍，只判断"有没有空窗 / 闪白 / 内容缺失"，**不做毫秒计时**（每次 `screencap` 都是新进程，时间分辨率不够；`screenrecord` 在 OPD 上被 OEM 拒绝，本机也没有 ffmpeg）。
- 门槛统一用 vsync 倍数表达：转场中不得出现 > 4×vsync（120Hz 约 33ms）的单帧，避免"50ms"这类跨刷新率会歧义的说法。

### 4.4 内存

- 每 5 次进出采一点，画曲线；每次采集前静置 20s 让 GC 回落。
- 同时记录 Java Heap、Views、Activities 数。
- **判据看曲线形状（是否单调台阶），不看首尾差**（首尾差会掩盖中间峰值）。

### 4.5 统计与判定

- 有对照时用**配对交错** A,B,A,B…（仓库 `ref_player_frames.ps1:13-16` 的既有范式，注释记录了同一配置一小时漂移 23.3→27ms）。
- 每配置 ≥ 5 对；报告配对差的中位数与 min–max 范围。
- 判定"改善"：**所有配对同向**，且中位改善 > 基线轮间波动范围的一半。不使用孤立的"≥30%"。
- 单场景重复 ≥ 5 轮；首轮作预热丢弃——**仅限热场景**，见 §5。

---

## 5. 冷 / 热协议分离

初稿让所有场景都"预热 1 轮"，这与冷进入场景直接矛盾。v2 分开：

| | 冷场景 | 热场景 |
|---|---|---|
| 适用 | 冷启动、歌手页首进、详情页首进 | 滑动流畅度、转场、页内切换、Tab 切换 |
| 前置 | `force-stop` → 等 3s → `am start -W` | 已在目标页附近，缓存已暖 |
| 预热轮 | **禁止** | 1 轮丢弃 |
| 缓存 | 记录图片/调色板缓存是否为冷 | 保持暖 |
| 数据 | 单独成组，不与热场景混算 | — |

歌手页 hero 烘焙、详情页调色板、封面解码都必须"冷一次 + 热一次"成对记录，否则会把缓存命中的假象当成优化效果。

---

## 6. 场景清单（从重到轻）

排序依据：既有实测（歌手页 debug 期横屏 p90 200–250ms、播放页玻璃 23.3ms/帧、底栏玻璃容器 5.2ms/帧）+ 使用频次 + 代码路径复杂度。这些数字来自 debug 期，只用于排优先级，不作为 release 目标值。

### P0-1　播放页外壳（最重、最高频）

播放页不是路由，是 `MainActivity` 内的 overlay shell，含玻璃 / backdrop / blur / 封面 morph。所有子场景都在"已暂停"状态下测（播放中的 Ken Burns 背景会改变每窗口的帧数）。

| ID | 操作 | 手势 | 前置锚点 | 采集 |
|---|---|---|---|---|
| 1a | mini → 全屏 | `sw.expand` | 折叠态锚点 | framestats + frametimeline |
| 1b | 全屏 → mini | `sw.collapse` | `Max Volume` | 同上 |
| 1c | Album→Lyric→PlayingList→Comment→Album | 四个入口按钮 tap（坐标核准） | 每次切换后锚点不同 | 每个切换单独一个窗口 |
| 1d | 展开态跟手往返 | `motionevent` DOWN/MOVE/UP | `Max Volume` | 同上 |
| 1e | 切歌封面 morph | `input keyevent 87` | 播放中 | 同上 |

### P0-2　歌单 / 专辑详情页进入与退出（用户点名）

`OnlinePlaylistDetail` / `ArtistDetail` 在 **4 个 house NavHost 里各注册一份**，从不同 Tab 进入是不同 backstack 与不同缓存，必须分入口测，不能只测一个入口就下结论。

| ID | 操作 | 备注 |
|---|---|---|
| 2a | 主页推荐歌单卡 → 在线歌单详情 | shared-element + 调色板提取 + 全屏底图；记录本次 playlistId |
| 2b | 同页手势返回 | `sw.back.gesture` |
| 2c | 曲库 → 在线歌单 → 详情 | 另一个 house 入口，点击返回 |
| 2d | 收藏 Tab 直接落地"我喜欢" | `FavoriteSongsPage` → `OnlinePlaylistDetail` |
| 2e | 在线专辑详情 / 本地 AlbumInfo | 两条不同代码路径 |
| 2f | 详情页长列表 fling ×5 | 拆 5 个采集窗口，不能合并 |
| 2g | 顶部下拉刷新 | 单独窗口 |

### P0-3　歌手页（唯一有"不可接受"量化证据的页面）

| ID | 操作 | 备注 |
|---|---|---|
| 3a | 冷进入 | `force-stop` 后进入，禁止预热 |
| 3b | 热进入 | 紧接 3a 再一次，走缓存 |
| 3c | 竖滚 + 横向歌曲/专辑 pager 各 3 页 | 分窗口 |
| 3d | 手势退出 | `sw.back.gesture` |

### P1-1　主页信息流

整页纵滚 fling ×10（拆窗口）；推荐歌单 / 新专辑 / 榜单三个 `HorizontalPager` 各翻 5 页；滚到底触发分页。

### P1-2　其余列表与网格

顺序：曲库-歌曲 → 本地专辑网格 → 本地歌手 → 在线歌手 → 收藏专辑 → 我的在线歌单 → 搜索结果列表。每页一次 fling ×10 + 一次回顶。

### P1-3　Tab 切换（量化"4 个 house 常驻"）

从固定起始 Tab，Home→Favorites→Library→Search 循环 10 轮，再反向 10 轮。用
`settings put global flamingo_probe nohiddendraw` / `housemodalpha` 做消融对照（该 probe 进程启动时读一次，改动后必须 `force-stop` 再启动）。

### P2　歌词 / 评论 / 搜索 / 设置

- 歌词页自动滚动 60s（不碰屏）+ 手动上下滚
- 评论页滚动 + 输入框聚焦（键盘弹出触发 insets 整页重排）
- 搜索：输入固定词（250ms 去抖）→ 5 个结果 Tab 逐个切 → 滚动分页
- 设置：玻璃效果调节页拖滑块（实时 `drawBackdrop` + blur），这是最重的设置页

### P3　其余与横切

识别页、关于页；长稳：30 次"进入歌单页→退出"的 PSS 曲线、60 分钟滚动+切歌 soak 与温升。

---

## 7. 场景前置 / 隔离表（每场景执行前必查）

| 项 | 要求 |
|---|---|
| 起始 Tab | 固定并记录（不同 house 是不同 NavHost 实例） |
| 播放状态 | 明确到 `dumpsys media_session` 的 state；绝大多数场景要求 **暂停** |
| 登录态 | 在线场景必须已登录，且是固定测试账号 |
| 滚动位置 | 顶部 / 指定 item，进入前复位 |
| 进入方式 | 点击或手势，记录坐标 |
| 退出方式 | 手势返回 vs 点击返回，**两者的代码路径不同，分开测、分开记** |
| 场景间隔离 | 每轮回到起始 Tab 且滚动回顶；不跨 house 复用 backstack；状态污染时 `force-stop` 重置 |
| 缓存状态 | 记录 `cacheState=cold\|warm` |

---

## 8. 命令（统一在 Git Bash 执行）

```bash
export MSYS_NO_PATHCONV=1
S=<serial>
PKG=com.sonify.music
ACT=$PKG/yos.music.player.MainActivity
ADB=adb

# —— 帧（主指标）——
$ADB -s $S shell dumpsys gfxinfo $PKG reset
# …执行一个手势…
$ADB -s $S shell dumpsys gfxinfo $PKG framestats > raw/s1a_r1.txt

# —— 呈现时间（层名必须先人工核对）——
$ADB -s $S shell dumpsys SurfaceFlinger --list | grep -n $PKG
$ADB -s $S shell dumpsys SurfaceFlinger --latency "<核对后的精确层名>"
# 输出三列：desired present / actual present / frame ready；0x7fffffff 表示 pending
# 注意：Splash、多个 window layer 都可能命中同一个包名，不能 grep -m1 了事

# —— Perfetto（归因，单独跑）——
cat onyx_cfg_atrace.pbtxt | $ADB -s $S shell perfetto --txt -c - \
  -o /data/misc/perfetto-traces/perf.pftrace --background
# 轮询确认输出文件已创建再执行手势；时长按场景设（转场 1.5–2.5s，滚动 5–10s）
$ADB -s $S pull /data/misc/perfetto-traces/perf.pftrace

# —— CPU 热点 ——
$ADB -s $S shell simpleperf record -p $($ADB -s $S shell pidof $PKG) \
  -g -f 1000 --duration 10 -o /data/local/tmp/p.data

# —— 内存 / CPU / 温度 / 启动 ——
$ADB -s $S shell dumpsys meminfo $PKG
$ADB -s $S shell dumpsys cpuinfo | grep $PKG
$ADB -s $S shell dumpsys battery | grep temperature
$ADB -s $S shell am force-stop $PKG
$ADB -s $S shell am start -W -n $ACT
```

离线分析脚本：`tools/trace_selftime.ps1`（主线程 self-time 分摊）、`tools/trace_counts.ps1`（每帧 saveLayer / FillRect / Texture 结构计数）、根目录 `anim_fps.py` / `anim_phase.py`（framestats 逐帧解析，可复用其列索引）。

---

## 9. 工具脚本审计（不能直接跑）

| 脚本 | 问题 |
|---|---|
| `diagnostics/glass_*.sh`、`onyx_*.sh` | 全部写死 `PKG=yos.music.player.oss` |
| `tools/ref_player_frames.ps1:25` | 默认参数写死旧包名（可用 `-Pkg` 覆盖） |
| `tools/quality_case_runner.ps1:23` | 同上 |
| `tools/diag_playback_interrupt.ps1:11` | 写死旧包名且无参数 |
| `tools/glass_trace.cfg:19` | `atrace_apps: "yos.music.player.oss"` |
| `tools/trace_selftime.ps1:18` | 正则假设进程 comm 为 `sic.player.oss`；新包名下**静默匹配不到**主线程，必须改 |
| `onyx_gfx.sh:15` | 用 `text="Thank You"` 判折叠态，该字符串已不在源码中，判定恒为 unknown |
| `tools/glass_perf_compare.ps1` | 唯一把 `-Pkg` 做成必填的脚本，可直接复用其 gfxinfo 采集 |
| （缺失） | 仓库没有任何 `SurfaceFlinger --latency` 解析器，需新写，并处理 `0x7fffffff` pending |

---

## 10. 已知坑

1. **包名**：见 §9，写错时 gfxinfo 返回 0 帧且脚本不一定报错。
2. **debug 包不可用**：未混淆、日志未剥离、Compose 未优化，`MainActivity` 里还有逐帧临时代码，与 release 差一个数量级。
3. **不用 "Janky frames %"**：120Hz 下严重失真。
4. **热漂移**：必须交错配对 + 回到温度基线；固定 20s 不够。
5. **网络抖动**：固定 ID + 预热缓存 + 记录 cacheState。
6. **注入失败可能不报错**：靠 §3.4 的三重验证。
7. **gfxinfo 只统计 App 自身渲染**，不含 SurfaceFlinger 合成与系统动画；转场若 SysUI 参与，要结合 `--latency` 与 frametimeline 看。
8. **4 个 house 常驻**：任何页面测量时后台还有 3 个在组合/布局，这是全局成本，不要算成单页问题。
9. **冷/热、首进/二次进必须分开**。
10. **`DiagLogEnabled` 是控制变量**：`log()` 只在调用线程格式化 + 入队，写盘在独立线程 `YosDiagWriter`（`YosDiagnostics.kt:234-256`、`:157`），不是主线程 I/O 风险，但仍占 CPU 与内存分配。
11. **framestats 约 120 帧上限**：长序列必须拆窗口。

---

## 11. 执行节奏与回归判据

- 阶段 0（校准）约半天 → P0 约 1 天 → P1 约 1 天 → P2/P3 约半天。
- 每个优化项：测量 → 归因 → 改 → 同一场景配对回归。
- 回归判据见 §4.5；**专项最大的风险是"修一个坏两个"**，所以每次改动都要把 P0 三个场景整体重跑一遍，确认没有退化。

---

## 附录 A：v2 修订记录（对评审意见的核实结果）

### 采纳（16 条）

1. screencap 连拍不能用于计时 —— 属实；且仓库记录 OPD 的 `screenrecord` 被 OEM 拒绝、本机无 ffmpeg。改为 Perfetto frametimeline + `--latency` 计时，screencap 只做视觉核验。
2. `mCurrentFocus` 不足以证明页面正确 —— 属实，补 L2 / L3 断言（§2）。
3. 通用"预热 1 轮"与冷场景矛盾 —— 属实，已拆分（§5）。
4. ART/dexopt 状态未固定 —— 属实，补 `cmd package compile`（§1.3）。
5. 在线内容 / 账号 / 缓存未固定 —— 属实，补固定 ID 与 cacheState（§1.4）。
6. 手势未参数化 —— 属实，补坐标/时长登记表与 `input motionevent`（§3）。
7. `SurfaceFlinger --latency` 层选择不可靠 —— 属实，且仓库根本没有解析器（§8、§9）。
8. PowerShell 与 bash 混用 —— 属实，统一 Git Bash + `MSYS_NO_PATHCONV=1`（§8）。
9. N=3~5 + 30% 抗噪不足 —— 属实，改为配对交错 + 配对差中位数 + 同向判定（§4.5）。
10. p90 近似、miss 必须逐帧、vsync 口径要统一 —— 属实，补列映射与统一倍数表达（§4.1、§4.3）。
11. GPU 指标口径不统一 —— 属实，补 fallback 与"呈现 ≠ GPU"的区分（§4.2）。
12. "无 >50ms 帧"与 miss3 口径冲突 —— 属实，统一为 vsync 倍数。
13. 内存首尾差会掩盖峰值 —— 属实，改为曲线（§4.4）。
14. 场景 setup/teardown、冷启动屏状态、输入法未固定 —— 属实，补 §1.5、§7。
15. 工具脚本写死旧包名 —— 属实，且范围比评审更大（含 `trace_selftime.ps1` 的进程名正则、`glass_trace.cfg`）。
16. Perfetto 时长与启动时机不稳 —— 属实，改为 `--background` + 轮询确认 + 按场景设时长（§8）。

### 未采纳 / 已纠正（4 条）

1. "YosDiagnostics 直写文件会污染所有数据" —— **不属实**。`YosDiagnostics.kt:234-256` 的 `log()` 只在调用线程格式化、入内存环、`offer` 到有界队列，写盘在独立线程 `YosDiagWriter`（`:157`），主线程无磁盘 I/O。仍记录该开关状态。
2. "profileable 补丁缺 `xmlns:tools`" —— **不属实**，`AndroidManifest.xml:3` 已声明。但"会改变 APK、需重新签名并记录 sha256"属实，已采纳（§1.2）。
3. "HISTOGRAM 是 500µs 分桶" —— **不属实**，是 1ms 分桶（仓库解析 `(\d+)ms=(\d+)`），HWUI 摘要百分位也是整数毫秒。但"p90 粗糙、miss 必须逐帧"的结论成立，已采纳。
4. "改用高速相机 / 高帧率录屏抽帧" —— **不采用**：本机无 ffmpeg，OPD 的 `screenrecord` 被 OEM 禁止，不能作为常规手段；改用 Perfetto 计时 + 慢放视觉核验。
