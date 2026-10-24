# 冗余审计 v1（只读，未改任何代码）

证据：
- `perf/p0/aligned_s2a.pftrace`（4 秒对齐窗口：进入"中英平淡"歌单，41 帧）
- `perf/p0/s2*/rN_framestats.txt`（5 轮逐帧 25 列）
- 代码静态核对：`MusicList.kt`、`OnlinePlaylistDetail.kt`、`ShadowImage.kt`、`DropShadow.kt`

---

## 0. 先说一个方法学问题（会影响以后所有数据）

主线程 self-time 里有 **约 42.6ms/4s（≈1ms/帧）是 accessibility semantics**：

| slice | 次数 | self |
|---|---|---|
| `Compose:semantics:sendAccessibilitySemanticsStructureChangeEvents` | 25 | 16.4ms |
| `getAllUncoveredSemanticsNodesToIntObjectMap` | 25 | 13.4ms |
| `Compose:semantics:boundUpdates` | 6 | 5.2ms |
| `Compose:semantics:sendSemanticsPropertyChangeEvents` | 25 | 5.1ms |
| `Compose:semantics:updateSemanticsNodesCopyAndPanes` | 25 | 2.5ms |

这是 `uiautomator dump` 把无障碍服务挂上之后产生的，**真实用户没有这部分开销**。
以后采归因轨迹时，`dump` 只能在计时窗口之外做，最好在 trace 期间完全断开。

---

## 表 A：主线程 / 后台 self-time（4 秒，按 self 降序）

| slice | 次数 | self 合计 | 单次均值 | 判读 |
|---|---|---|---|---|
| `postAndWait` | 44 | **366.7ms** | 8.33ms | 等 RenderThread，不是计算 |
| `Recomposer:animation` | 154 | 52.1ms | 0.53ms | 动画帧回调 |
| `Choreographer#scheduleVsyncLocked` | 198 | 46.5ms | 0.235ms | 偏高，后续单独看 |
| `AndroidOwner:draw` | 40 | 36.7ms | 0.92ms | Compose 绘制入口 |
| `Compose:recompose` | 121 | 26.8ms | 0.22ms | 重组 |
| `AndroidOwner:measureAndLayout` | 63 | 21.0ms | 0.51ms | **量/布局合计 0.51ms/帧** |
| accessibility 五项 | — | ~42.6ms | — | 测量工具引入，见 §0 |
| `RenderProxy#copyHWBitmapInto readback` | 8 | 13.9ms | 1.73ms | 位图回读 |
| `uploadHardwareBitmap` | 4 | 8.9ms | 2.21ms | 位图上传 |
| `Recomposer:recompose` | 159 | 11.1ms | 0.28ms | 调度外壳 |
| `Compose:applyChanges` | 88 | 9.2ms | 0.11ms | 应用变更 |
| `Record View#draw()` | 40 | 4.7ms | 1.74ms | 录制显示列表 |
| `allocateHelper` | 4 | 4.0ms | 1.00ms | 位图内存分配 |
| `TextStringSimpleNode::measure` | 32 | 2.1ms | 0.11ms | 文本量算可忽略 |
| `Constructing StaticLayout` | 16 | 1.0ms | 0.06ms | 文本排版可忽略 |

**读法**：主线程真正在"算"的部分（重组 + 量布局 + 录制）合计约 **3ms/帧**；
其余 8.3ms/帧在 `postAndWait` 等渲染线程。位图回读/上传（readback+upload+alloc ≈ 27ms / 4s）是唯一明显的"重复 I/O"候选。

---

## 表 B：RenderThread 绘制操作（4 秒 / 41 帧）

| 操作 | 次数 | 合计 | 每帧 | 判读 |
|---|---|---|---|---|
| `Drawing 1280x2772` | 41 | 484.7ms | **11.8ms** | 每帧整窗绘制 |
| `renderFrame` | 47 | 383.8ms | 8.2ms | — |
| `flush layers` | 41 | 225.8ms | 5.5ms | 图层提交 |
| `OpsTask::onExecute` | 2623 | 178.9ms | 64 次/帧 | Skia op 执行 |
| `prepareTree` | 77 | 91.7ms | 1.9 | — |
| `Vulkan finish frame` | 47 | 89.5ms | 1.9 | — |
| `FillRectOp` | **9476** | 82.7ms | **231/帧** | 大量矩形 |
| `dispatchFrameCallbacks` | 69 | 60.8ms | 1.7 | — |
| `syncFrameState` | 46 | 51.2ms | 1.1 | — |
| `drawLayer 137x137` | 312 | 49.2ms | **7.6/帧** | 见 §C 待定位 |
| `TextureOp` | **3224** | 27.2ms | **79/帧** | 贴图 |
| `drawLayer 1280x2772` | 47 | 18.2ms | 1.1 | 整窗层 |
| `drawLayer 1190x189` | 82 | 14.5ms | 2.0 | 行尺寸层 |
| `drawLayer 1190x140` | 86 | 12.2ms | 2.1 | 行尺寸层 |

### 表 B-1：alpha 导致的 saveLayer（每帧约 22 次）

| slice | 次数 | 每帧 |
|---|---|---|
| `alpha caused saveLayer 1169x2` | 213 | 5.2 |
| `alpha caused saveLayer 323x66` | 182 | 4.4 |
| `alpha caused saveLayer 86x57` | 151 | 3.7 |
| `alpha caused saveLayer 99x57` | 110 | 2.7 |
| `alpha caused saveLayer 82x57` | 110 | 2.7 |
| `alpha caused saveLayer 128x57` | 110 | 2.7 |
| `alpha caused saveLayer 107x57` | 110 | 2.7 |

尺寸分布高度规律：`1169x2` 是分隔线、`*x57` 是每行的歌手名文本。**一行歌曲至少产生 2 个离屏层。**

### 表 B-2：重复纹理上传（最可疑）

| 纹理 | 尺寸 | 上传次数 | 说明 |
|---|---|---|---|
| `Texture upload(958054)` | 1190x140 | **69** | 同一 id 在 4s 内传了 69 次 |
| `Texture upload(958250)` | 1190x189 | **41** | 每帧一次 |
| `Texture upload(958123)` | 1198x189 | **41** | 每帧一次 |
| `Texture upload(958052)` | 1190x189 | **28** | — |

1190×189 RGBA ≈ 900KB/张。三张每帧重传 ≈ **2.7MB/帧**，转场 2 秒内约 200MB 的纯冗余传输。

---

## 表 C：Compose 重组（部分，缺 per-composable 计数）

现有可测部分：

| 指标 | 值 | 判读 |
|---|---|---|
| `Compose:recompose` self | 0.22ms/次 × 121 次 ≈ **0.65ms/帧** | 小 |
| `Recomposer:recompose` inclusive | 44.6ms / 159 次 | 小 |
| `Compose:applyChanges` | 9.2ms / 4s | 小 |
| `AndroidOwner:measureAndLayout` | **0.51ms/次** | 小 |
| `TextStringSimpleNode::measure` | 2.1ms / 32 次 | 可忽略 |

**缺的是"每个 composable 重组几次、能否跳过"。** 这需要一次构建改动（不改运行时行为）：
`composeCompiler { metricsDestination / reportsDestination }`，或在 debug 包用 Layout Inspector 看重组计数。
本轮没有做，因此**不对列表 key / state scope 下任何结论**。

---

## 代码级候选（按"零视觉影响"排序）

### 立即可改，视觉完全不变

1. **`MusicList.kt:166`** 歌手名 `Modifier.alpha(0.5f)` → `color = LocalContentColor.current.copy(alpha = 0.5f)`
   对应表 B-1 里 `*x57` 的 5–6 组 saveLayer（约 15/帧）。
2. **`OnlinePlaylistDetail.kt:708`** 分隔线 `.alpha(0.15f).height(0.5.dp).background(color)`
   → `.background(color.copy(alpha = 0.15f))`
   对应 `alpha caused saveLayer 1169x2`（5.2/帧）。
3. 两处都改完后，**每帧 saveLayer 预计从约 22 降到约 2–3**。

### 需要先验证视觉，不先动

4. **`ShadowImage.kt:107 / :182`** 每张封面都带
   `graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen; clip = true }`。
   `Offscreen` 强制每个封面一张离屏缓冲，对应 `drawLayer 137x137`（7.6/帧）与每帧纹理重传。
   但它同时承载 `BlendMode.Overlay` 的描边，去掉可能改变描边观感 → **先做 A/B 截图比对再决定**。
5. 每帧重传的 1190×140/189 三张纹理，尚未定位到具体图层（需要按 layer id 逐帧归属）。

### 已排除

- `dropShadow` 在 `shadowAlpha == 0f` 时已 early-return，行内封面没有阴影开销。
- 全仓除 `YosLyricView` 外没有 `rememberInfiniteTransition` / `infiniteRepeatable`。
- 歌单页静止 3 秒 **0 帧**，不存在"空闲也在重绘"。

---

## 建议的第一次改动与验证

只做候选 1 + 2（两行代码，视觉零变化），然后：

- 采集：同一脚本、同一入口、5 轮配对（改动前后各 5 轮，交错）
- 判据：`Drawing` 时间、`FillRectOp` 次数、`alpha caused saveLayer` 次数、`miss2`
- 预期：saveLayer 从约 22/帧 → 约 2–3/帧；`Drawing` 与 miss2 的改善幅度由实测决定，不预设

如果 saveLayer 降下来了但 `Drawing` 几乎不动，说明瓶颈在别处（大概率是 231 FillRectOp 与纹理重传），
那就转入候选 4 的截图 A/B。
