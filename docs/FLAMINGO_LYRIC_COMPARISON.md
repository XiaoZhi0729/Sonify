# Flamingo 歌词系统：手机新版 vs 当前仓库 —— 逐项对比

| | 手机版 Flamingo | 当前仓库 (FlamingoSank) |
|---|---|---|
| 版本 | `yos.music.player` 1.0.0-2608310056（混淆，未开源） | 活跃开发分支，git 持续迭代 |
| 来源 | REA/JADX 反编译 | 直接读源码 |
| 文件 | `U6.*`(逻辑) / `Z6.*`(数据) / `z7.*`(渲染) | `YosLyricView.kt`(1987行) 等 |

> ⚠️ 关键背景：仓库**不是"两年前旧版"**，而是**持续在对齐 Flamingo 新版**——
> `YosLyricView.kt` 里有 5 处注释明写「对齐 Flamingo」「对齐 MD3Music/AMLL」。
> 所以本对比的真实价值是：**已对齐什么、还差什么。**

---

## 一、总览：架构层面

| 维度 | 手机版 | 当前仓库 | 判定 |
|---|---|---|---|
| 歌词行模型 | `Z6.D` (text/start/end/逐字List/译文) | `LyricEntry(mainLyric: List<Pair<Float,String>>, translation, romanization)` | 🔶 **不同实现，等价语义** |
| 逐字模型 | `Z6.x` (start/end/text/空格标记) | `List<Pair<Float,String>>`（时间→词） | 🔶 **不同，仓库更简** |
| 行定位 | **二分查找** `U6.r.a` | `indexOfFirst { startTime > liveTime }` 线性 | ❌ **算法不同** |
| 逐字时间分配 | `z7.e.o` 按字/词切分+均摊 | `synthesizeSmartWbw` + 逐字 `averageTime` | ✅ **思路一致** |
| 状态传递 | `U6.D` 全局 StateFlow 总线 | `MainViewModelObject.syncLyricIndex` + lambda | 🔶 **不同** |
| 渲染 | 自定义 `DrawScope` 逐字绘制 | 自定义 `onDrawBehind` + `drawText` | ✅ **同路线** |
| 滚动 | **自定义逐帧指数衰减** `z7.e.l` | `LazyColumn` + `animateScrollBy/scrollToItem` | ❌ **架构级不同** |
| 对外推送 | `cn.lyric.getter` + `com.hchen.superlyric` | `statusBarLyric/LyricGetter.kt` | ✅ **都有** |

---

## 二、高光（逐字高亮）

| 细节 | 手机版 | 当前仓库 | 判定 |
|---|---|---|---|
| 是否逐字 | ✅ 是 | ✅ 是 | ✅ 一致 |
| 进度→字数映射 | `z7.e.q` 用「已高亮字数」浮点，floor+frac | `percent = (liveTime - wordStart)/wordDuration` | ✅ 等价 |
| 相邻字交叉 | `q()` 拆两字各画比例 | `Brush.horizontalGradient(before→after)` | ✅ **思路一致，实现不同** |
| 已唱字色 | 高亮色 `j10`（参数）| **白70% + 封面色30%** `lerp(White, CoverTint, 0.3)` | 🔶 **仓库更花哨** |
| 未唱字色 | 传入样式色 | `Color(0x2EFFFFFF)`（18%白）| 🔶 不同 |
| 逐字启用条件 | 片段长∈[2,10] 且时长≥1000ms | 逐字行且在窗口内 | ✅ **门控思路一致** |

## 三、辉光（Glow）★ 差异最大

| 细节 | 手机版 `z7.e.p` | 当前仓库 | 判定 |
|---|---|---|---|
| 物理手段 | `RenderEffect` 模糊 + 双层 | `Shadow(blurRadius)` + `BlendMode.Clear` 镂空 | ✅ **手段等价** |
| 模糊半径 | `5s+7` → **7~12** | 常数 **12** | 🔶 仓库固定，手机随进度变 |
| 辉光颜色 | 高亮色 | **纯白** `Color.White` | 🔶 不同 |
| 时间曲线 | `smoothstep((t-1000)/2000)` | 长度渐变窗 **180+时长+400** smoothstep | ❌ **公式不同** |
| 亮度包络 | **`sin²(π·progress)`**（单峰） | **自定义 cubic-bezier 海浪**（bezIn/bezOut 双段） | ❌ **完全不同的曲线** |
| 强度缩放 | `f18·sin²` | `durationScale = (dur-700)/(1500-700)` 开根 | 🔶 不同公式 |
| 时长门控 | ≥1000ms 才发光 | `MinNoteMs=700`~`FullNoteMs=1500` 线性渐强 | 🔶 阈值不同 |
| 逐字窗口 | 每字独立区间 | ✅ 每字 `[glowStart,glowEnd)` + **每帧只放行1字** | ✅ **仓库更严谨** |
| 时钟驱动 | 直接跟播放位置 | `withFrameNanos` 逐帧采样（防抖） | ✅ 一致 |

> **亮点**：仓库的「每帧只放行一个字发光」+「镂空字芯」是对手机版的**改进**（避免同词同形字一起亮）。

## 四、滚动动画 ★ 架构级不同

| 细节 | 手机版 `z7.e.l` | 当前仓库 | 判定 |
|---|---|---|---|
| 机制 | **自定义逐帧循环** `withFrameNanos` | Compose `LazyColumn` + `animateScrollBy` | ❌ **根本不同** |
| 缓动 | **指数衰减** `cur *= 0.95^(Δt/16)` | `tween(550ms, yosEasing)` | ❌ **不同曲线** |
| 缓动曲线 | 指数衰减（约1秒） | `CubicBezier(0.75,0,0.25,1)`（对称 S 曲线） | ❌ 不同 |
| 终止条件 | `|cur| < 0.05` | tween 到时长即止 | ❌ 不同 |
| 帧防抖 | `clamp(dt, 0, 100ms)` | 无 | 🔶 手机有 |
| 进入定位 | （反编译未展开） | `pendingEntryPositioning` 无动画首定位 | ✅ 思路都有关 |
| 用户滚动打断 | （未展开） | `isUserScrolling` + 1600ms 冷却 | ✅ 仓库有 |
| 末行居中 | （未展开） | `lastLineCenterScrollOffset` 精确居中 | ✅ 仓库有 |

> **关键差异**：手机版是**自绘滚动**（自己算位置、自己衰减），**没有 LazyColumn**；
> 仓库用 **Compose LazyColumn**，滚动交给框架，自己只调 `animateScrollBy`。
> 两者体感会不同：手机版是"匀速衰减停住"，仓库是"标准缓入缓出 550ms"。

## 五、行样式

| 细节 | 手机版 `z7.e.e` | 当前仓库 | 判定 |
|---|---|---|---|
| 当前行行高 | **2.5f** | 37.5sp 字号 / 47.5sp 行高 | ❌ 不同体系 |
| 非当前行行高 | **3.5f** | 同上（未按当前行区分行高）| ❌ **仓库无此特性** |
| 非当前行 alpha | **0.4f** + "LyricAlpha" 动画 | **0.4f** + tween 260ms | ✅ **数值一致！** |
| 字号缩放 | `clamp(userScale, 0.8, 1.2)·5+base` | `37.5·clamp(scale,0.8,1.2)` | ✅ **clamp 范围一致** |
| 行浮起 | `sin²` 脉冲位移 | 180ms 提前 + smoothstep，4px | 🔶 思路近似 |
| 翻译 alpha | — | 当前0.5 / 非当前0.14 | 🔶 仓库专有 |

---

## 六、仓库缺失 / 手机版独有的

| 特性 | 手机版 | 仓库 |
|---|---|---|
| **当前行行高 2.5 vs 其他 3.5** | ✅ | ❌ 无 |
| **按片段时长 ≥1000ms 的辉光门控** | ✅ | 🔶 有类似但阈值 700/1500 |
| **`sin²` 脉冲包络** | ✅ | ❌ 改用 bezier 海浪 |
| **二分查找定位** | ✅ | ❌ 线性 `indexOfFirst` |
| **歌曲延迟偏移** `LyricTimingOffsetMs` | ✅ | ✅ 有（`LyricSetting.kt`） |
| **Lyricon 50ms 定频** | ✅ | 🔶 有 10ms/逐帧采样 |

## 七、仓库独有的（手机版没看到 / 或更好）

| 特性 | 说明 |
|---|---|
| **封面提取色混入已唱字** | `Palette` 取封面主色，`lerp(White, tint, 0.3)` |
| **每帧只放行一个字发光** | 修复同词同形字并发光的 bug |
| **镂空字芯** `BlendMode.Clear` | 辉光内圈擦除，视觉更精致 |
| **罗马音支持** | `romanization` 字段（KRC `[language:]`） |
| **横竖屏滚动位置交接** | `YosLyricScrollState` 跨组合保留 |
| **末行精确居中 + 遮挡补偿** | `lastLineCenterScrollOffset` |
| **用户滚动冷却 1600ms** | `isUserScrolling` |

---

## 八、结论

**这块歌词引擎，仓库已经追得很近，但有三处"体质级"差异：**

1. **滚动机制**（最大）：
   - 手机版 = 自绘 + 指数衰减
   - 仓库 = LazyColumn + tween 550ms
   - → 体感不同，且**手机版没有用 LazyColumn**，等于自建了一套滚动。

2. **辉光包络曲线**：
   - 手机版 = `sin²(π·progress)` 单峰
   - 仓库 = 自定义 cubic-bezier 双段海浪
   - → **完全不同的曲线**，视觉上会不一样。且手机版模糊半径随进度 `7→12` 变化，仓库固定 12。

3. **行高区分**：
   - 手机版当前行 `2.5` / 其他 `3.5`（**当前行更紧凑**）
   - 仓库 `37.5sp/47.5sp` 不区分当前行
   - → **这是一个可见的布局差异**，仓库没做。

**已完全对齐的（数值级一致）**：
- ✅ 非当前行 alpha = **0.4**
- ✅ 字号 clamp 范围 **[0.8, 1.2]**
- ✅ 逐字高光（Brush 交叉）
- ✅ 辉光用模糊+双层、逐字窗口
- ✅ 对外歌词推送（LyricGetter + SuperLyric）

**仓库反而更强的**：封面取色、镂空字芯、单字发光放行、罗马音、末行居中、跨方向滚动状态。

---

*手机版结论来自 REA/JADX 反编译（可回溯 evidence_id）；仓库结论来自 `YosLyricView.kt` 等源码直读。*
