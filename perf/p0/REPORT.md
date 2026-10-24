# P0 基线报告 v2（release · onyx · 120Hz）

日期：2026-10-23
被测包：`com.sonify.music` vc7 / 0.2.0，release（R8 minify + shrinkResources）
APK sha256 `53c02c29bbc47fb9428bf62e18b90a0421e2c842be9d1485f52f9928f592400f`
设备：onyx 25053RT47C，Android 17，1280×2772 @520dpi，120Hz
协议：`docs/PERF_TEST_PLAN.md`；环境记录：`perf/p0/env.txt`

> v2 修订：撤回"不是 Compose 布局问题"这一未证实结论；补 Perfetto 对齐归因；指标公式写死；
> 补每轮离散度；标注 s3 全部为替代入口；区分转场窗口与稳态滚动。

---

## 1. 结论与证据强度

### 已证实（有对齐的 Perfetto + FrameTimeline 支撑）

1. **进入在线歌单是稳定的低帧率场景**：5/5 轮 p50 28.3–30.2ms，miss2 97.9–100%，不是偶发。
2. **该场景的主要成本在 RenderThread 绘制与 GPU 提交，不在 Compose 重组**：
   - `Recomposer:recompose` 4 秒内 159 次、合计 44.6ms → **约 0.28ms/帧**
   - `Record View#draw()` 40 帧合计 69.5ms → **1.74ms/帧**
   - `RenderThread "Drawing 1280x2772"` 41 帧合计 484.7ms → **11.8ms/帧（max 22.1）**
   - `flush layers` 41 帧 225.8ms → 5.5ms/帧；`FillRectOp` 9476 次 / 41 帧 → **约 231 个填充矩形/帧**
3. **App 侧确实在等渲染管线**：主线程 `traversal` 40 次共 437.5ms（10.9ms/帧），其中 `draw-VRI` 433.5ms、
   内部 `postAndWait` 366.7ms（9.2ms/帧）。即 UI 线程大部分时间在等 RenderThread。
4. **FrameTimeline 的 jank 归类**（4 秒窗口共 86 帧）：
   | jank_type | 帧数 | 平均 | 最大 |
   |---|---|---|---|
   | None | 41 | 9.9ms | 10.2ms |
   | Buffer Stuffing | 20 | 26.1ms | 33.0ms |
   | SurfaceFlinger Scheduling | 11 | 23.8ms | 34.4ms |
   | App Deadline Missed | 8 | 27.6ms | 33.7ms |
   | Dropped Frame | 1 | 10.3ms | 10.3ms |

   多数落在 "Buffer Stuffing"（App 产帧慢于屏幕）与 "App Deadline Missed"，另有 11 帧是 SF 调度。
   所以：**App 侧产帧能力是主因，SF 合成侧是次因**，两者都存在。

### 强证据但未闭环

5. **播放页展开（s1a）** 5/5 轮 p50 27.6–35.3ms、miss2 59.1–62.7%，稳定。
6. **进入歌手页（s3a）** 4/5 轮 p50 24.6–27.7ms、miss2 60.3–64.2%（另有 1 轮 14.5ms/41.7%，见 §6 入口问题）。

### 仅提示，不能下结论

7. **歌词页切换（s1c_lyric）**：miss2 在 5 轮里是 10.2 / 14.1 / 78.4 / 100 / 100，**双峰**。中位数毫无意义，
   只能说"有时很顺、有时持续 21ms 以上"，需要单独复现。
8. **播放页收起（s1b）**：p50 11.6–14.1ms 但 miss2 44.7–49.0%，**约一半帧超 2 个 vsync**，是重尾分布，
   不是"某几帧卡死"。（v1 的措辞错误，已改。）
9. **歌手页滚动（s3c）**：只有 1 轮有效，**不作为结论**，仅记录。

---

## 2. 指标口径（写死，避免误读）

| 量 | 定义 |
|---|---|
| `frameDuration` | `FrameCompleted − IntendedVsync`（ns，除以 1e6 得 ms） |
| `miss1/2/3` | `frameDuration > N × 8.333ms` 的帧占比（120Hz 下 1/2/3 个 vsync） |
| `appSide` | `SyncQueued → SwapBuffers`，UI 线程做 sync/draw/swap 的时间，**包含等待 RenderThread** |
| `waitAfterSwap` | `SwapBuffers → FrameCompleted` |
| `traversal→drawRecord` | `PerformTraversalsStart → DrawStart`。**这不是纯 measure/layout**，只是 traversal 到 draw 录制的间隔，v1 把它叫作"布局"是错的 |

三条必须一起读：

- `frameDuration` **包含等待 vsync 与管线排队**，所以 120Hz 下 p50 28ms 表示"持续约 34fps"，
  不等于"每帧都卡死"；miss2=100% 只说明没有一帧落进 2 个 vsync 内。
- `miss2` 高 + `appSide` 高 ⇒ App 侧产帧慢；`miss2` 高但 `appSide` 正常 ⇒ 要看 SF/显示侧。
- `DisplayPresentTime` 在本机多数帧上没有有效值（0 或早于 IntendedVsync 的陈旧值），
  因此本轮**不报告 presentLatency**，改用 SF FrameTimeline 判断合成侧。

---

## 3. 帧数据（每场景 5 轮，全部轮次列出，不只看中位数）

单位 ms。`miss2` 给的是 5 轮各自的数值，便于看离散度。

| 场景 | 有效 N | p50（各轮） | p90 | max | miss2（各轮） |
|---|---|---|---|---|---|
| s1a 播放页展开 | 5 | 30.5 / 32.6 / 35.3 / 33.5 / 27.6 | 56.7 | 74.5 | 62.7 / 59.1 / 61.5 / 60.3 / 61.2 |
| s1b 播放页收起 | 5 | 14.1 / 13.3 / 11.6 / 13.1 / 13.5 | 59.1 | 85.5 | 45.8 / 46.9 / 45.7 / 49.0 / 44.7 |
| s1c 切到歌词页 | 5 | 13.3 / 13.8 / 20.3 / 21.8 / 21.6 | 23.4 | 85.2 | **14.1 / 10.2 / 78.4 / 100 / 100** |
| s1c 切到播放列表页 | 5 | 13.6 / 21.1 / 13.7 / 13.6 / 13.7 | 17.2 | 80.3 | 12.8 / **64.6** / 13.8 / 9.7 / 9.3 |
| s2a 进入在线歌单 | 5 | 30.2 / 29.5 / 28.8 / 28.3 / 28.9 | 34.6 | 47.4 | 98.0 / 97.9 / 100 / 100 / 100 |
| s2b 退出在线歌单 | 5 | 12.4 / 15.1 / 14.4 / 15.4 / 12.9 | 23.7 | 64.8 | 22.0 / 38.7 / 20.3 / 36.7 / 25.8 |
| s2c 歌单内快速甩动 | 5 | 14.2 / 14.6 / **31.2** / 14.8 / 14.0 | 22.9 | 49.5 | 25.0 / 28.3 / **100** / 33.6 / 30.8 |
| s3a 进入歌手页（替代入口） | 5 | **14.5** / 27.3 / 24.6 / 27.7 / 27.5 | 50.3 | 86.1 | **41.7** / 62.1 / 64.2 / 60.3 / 63.1 |
| s3b 退出歌手页（替代入口） | 5 | 15.4 / 14.1 / 15.5 / 14.4 / 15.1 | 37.6 | 57.6 | 42.9 / 35.7 / 46.3 / 44.7 / 45.2 |
| s3c 歌手页滚动（替代入口，N=1） | **1** | 14.3 | 37.1 | 60.9 | 46.7 |

`s2c` 第 3 轮只有 18 帧（列表已到边界，甩动没产生运动），该轮不可比，已在 §6 说明。

---

## 4. 归因（对齐的 Perfetto，s2a 单次进入）

采集：`duration_ms=4000`，`input/gfx/view/sched` + `surfaceflinger.frametimeline`，
确认 perfetto 进程已启动后再点歌单，覆盖"手势前约 0.3s → 转场 → 首屏加载后约 2s"。

| 线程 | 切片 | 次数 | 合计 | 单次均值 | 最大 |
|---|---|---|---|---|---|
| main | `traversal` | 40 | 437.5ms | 10.9ms | 15.9ms |
| main | `draw-VRI[MainActivity]` | 40 | 433.5ms | 10.8ms | 15.8ms |
| main | `postAndWait` | 44 | 366.7ms | 8.3ms | 14.0ms |
| main | `Record View#draw()` | 40 | 69.5ms | **1.74ms** | 13.8ms |
| main | `Recomposer:recompose` | 159 | 44.6ms | **0.28ms** | 11.4ms |
| main | `animation` | 160 | 139.3ms | 0.87ms | 11.5ms |
| RenderThread | `Drawing 1280x2772` | 41 | 484.7ms | **11.8ms** | 22.1ms |
| RenderThread | `renderFrame` | 47 | 383.8ms | 8.2ms | 17.5ms |
| RenderThread | `flush layers` | 41 | 225.8ms | 5.5ms | 9.8ms |
| RenderThread | `FillRectOp` | 9476 | 82.7ms | — | 0.3ms |
| RenderThread | `Vulkan finish frame` | 47 | 89.5ms | 1.9ms | 3.1ms |

读法：

- **重组不是瓶颈**（0.28ms/帧），**draw 命令录制也不大**（1.74ms/帧）。
- UI 线程的 10.9ms 里，9.2ms 花在 `postAndWait`——等 RenderThread。
- RenderThread 每帧 `Drawing` 11.8ms，超过 120Hz 的 8.33ms 预算，因此必然掉到 2 个 vsync。
- 每帧约 231 个 `FillRectOp`，指向**大量的矩形/背景/阴影/遮罩绘制**，这是下一步要拆的对象。
- 注意：`Drawing` 是 Skia 在 RenderThread 上执行显示列表 + 提交，**不能简单等同于 GPU 时间**；
  GPU 侧本轮没有硬件计数器，只能说"RenderThread + GPU 提交链路"。

---

## 5. 转场 vs 稳态（不要混为一谈）

| 页面 | 转场/进入 | 稳态滚动 |
|---|---|---|
| 在线歌单 | s2a：p50 28.9ms，miss2 97.9–100% | s2c：p50 14.0–14.8ms（3/5 轮），miss2 25.0–33.6% |
| 歌手页 | s3a：p50 24.6–27.7ms，miss2 60–64% | s3c：N=1，不可用 |

结论：在线歌单**两个环节都不达标**（稳态 p90 22.9ms > 16.7ms 门槛，miss2 25–34% > 5% 门槛），
不能只修进入动画。转场是主战场，稳态是第二战场。

---

## 6. 本轮的限制（会直接影响可比性）

**环境**

- 亮度未锁定（系统自动亮度），未记录亮度值。
- 温度未冷却到基线：5 轮内电池温度 32.8–35.7°C，s1a 从 34.9 漂到 35.7°C。
- `DiagLogEnabled` 未确认当前值；测试账号、playlistId（中英平淡）、albumId、artistId 未写入 env.txt。
- 因此本轮只适合判断"量级与方向"，不适合判定"改善 ≥30%"。

**自动化**

- uiautomator 会 latch 到陈旧窗口（应用在前台却 dump 出 `com.miui.home`），也会在持续动画时返回空 XML。
  已加入"根节点 package 校验 + 冷重启"修复（`tools/perf_p0.py:dump_pkg`），但本轮数据是修复前采的。
- s3c 5 轮里 4 轮因自动化打开歌手页失败作废。

**场景有效性**

- **s3a/s3b/s3c 全部走"播放页 → 点歌手名"这一替代入口**，不是计划里"从列表冷进入"，
  带有播放页 backstack 与已加载封面，**与冷进入不等价**。
- s3c 仅 1 轮。
- `s2c` 第 3 轮帧数只有 18，不可比。

**工具**

- `simpleperf` 在正式包上被 `security.perf_harden` 拒绝，本轮无 CPU 采样。
- 没有 GPU 硬件计数器；Perfetto 的 `Drawing` 只能说明 RenderThread 执行显示列表的耗时。
- HWUI framestats 的 `FLAGS` 列在本机恒为 0x20（快帧慢帧都有），**不能当作 buffer stuffing 指标**，
  本报告未使用它；§1 的 "Buffer Stuffing" 来自 `android.surfaceflinger.frametimeline`，是另一套数据。

---

## 7. 下一步（按证据缺口排序）

1. **在线歌单消融**（数据最稳、收益最大）：全屏底图 / 调色板提取 / 大封面阴影 / 共享元素 / backdrop 逐个关，
   A/B 配对，每配置 ≥5 对。目标是把 §4 里的 231 个 FillRectOp 和 11.8ms `Drawing` 分解到具体元素。
2. **稳态滚动也要测**：s2c 的 miss2 25–34% 已超门槛，与转场分开回归。
3. **播放页收起抓单次重尾**：对 85ms 帧单独抓 Perfetto，看是否首帧 inflate/图片解码/输入事件排队。
4. **修 s3 入口**：改回"从在线歌单冷进入歌手页"，冷/热分开，N≥3 后再下结论。
5. **锁环境**：固定亮度、每轮冷却到基线 ±1°C、把 DiagLogEnabled/账号/内容 ID 写进 env.txt，
   并把每轮温度汇总进 summary。

---

## 8. 产物

- `perf/p0/run_all5b.log`：逐轮原始输出
- `perf/p0/summary_20261023_221212.json` / `_report.md`
- `perf/p0/aligned_s2a.pftrace`（5.5MB，4s 对齐窗口）
- `perf/p0/cfg_aligned.pbtxt`、`q2.sql`、`q3.sql`：可复跑的采集与分析查询
- `perf/p0/<scenario>/rN_framestats.txt`：逐帧 25 列原始数据
- `tools/perf_p0.py`、`tools/perf_summary.py`
