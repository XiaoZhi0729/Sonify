# LyricsBlossom 歌词「辉光 + 逐字动画」— 1:1 复刻规格（可据以编码）

> 来源：手机 `com.lyricsblossom.app` **8.1 (vc 80006)**，APK sha256 `28b1bcfc5ada69b3aadaa4b235c353d62eb152528fe39c3b6165d547a753b8f2`。
> 目标体：`lib/arm64-v8a/libmain.so`（55 MB，**已 strip**，SDL3 + 静态 Skia 原生 C++）。
> 手段：REA(adb) + 自建 RTTI/eh_frame/交叉引用工具 + **Ghidra 12.1.4 反编译**。
> 标记：`✅`=反编译直接读到；`🔶`=据调用点/常量推导；`❓`=未解。
> **本文只写可编码的最终数据。**

---

## 0. 命名对照（地址 → 语义）

| 地址(ELF vaddr) | 语义 | 证据 |
|---|---|---|
| `0x29e563c` | `LyricsRenderer::renderLine` 逐行渲染主函数（4636 B） | ✅ |
| `0x29e5100` | **辉光构造器** `drawWithGlow(σ,…)`（1340 B） | ✅ |
| `0x29e4954` | 行状态**快照/复位**（把目标值刷进当前值 + 重启所有 tween，932 B） | ✅ |
| `0x29ce60c` | **布局函数**：算每行/每字的目标值（14696 B） | ✅ |
| `0x29edfa0` | 逐行分发（走加密表） | ✅ |
| `0x29efd54` | **逐行文字绘制**（白字 + alpha + translate + scale） | ✅ |
| `0x29ef7b4` | 时间相位计算（含 `now()×10`） | ✅ |
| `0x29ee404` | 行总时长 = 该行逐字时长之和 | ✅ |
| `0x29f4068` | `setGlowTarget(v){ obj[0x1b4] = v }` | ✅ |
| `0x302a768` | `SkImageFilters::Blur(sx, sy, tileMode, input, crop)` | ✅ |
| `0x30bb9c4` | `SkPaint::setImageFilter` | ✅ |
| `0x30bbbfc` | `SkPaint::setColor` | ✅ |
| `0x30bbc30` | `SkPaint::setAlphaf` | ✅ |
| `0x3056614` / `0x305671c` | `SkCanvas::translate` / `scale` | ✅ |
| `0x3051f8c` / `0x3051fb0` | `SkCanvas::save` / `restore` | ✅ |
| `0x3081f5c` | 相位写入（clamp≥0，写 +8） | ✅ |
| **`0x2a24888`** | **逐行字形绘制**（表 tbl[0x380]） | ✅ |
| **`0x29c8f34`** | **逐字绘制 / 扫色主逻辑**（表 tbl[0x338]，2876 B） | ✅ |
| **`0x29c8040`** | **逐字标志：CJK 判定**（表 tbl[0x3a0]） | ✅ |
| `0x29c0ac8` / `0x29c1178` / `0x29ee7c4` | 文本模式 / 布局缓存重建 / 行调度 | ✅ |
| `0x2842314` / `0x28423c8` | `SpringTiming::value(t)` / `::duration()` | ✅ |
| `0x2842440` | `TimingFunction::reset(now)` | ✅ |
| `0x2a17354` | `CSSCubicBezierTiming::value(x)` | ✅ |

### 加密派发表（已静态破解）
- 机制：`table[disp] = KEY ^ (adr x9, #下一条)`，`adr` 目标处是 `b <真函数>` 跳板。
- KEY 为 **FNV-1a 64**（初始化器 `0x28b17fc`，常量 `0xcbf29ce484222325` + `0x100000001b3`）。
- ⇒ **真函数地址可直接静态读出，无需动态。** 上表 `0x2a24888/0x29c8f34/0x29c8040` 即由此还原。

---

## 1. 文字颜色与变换 ✅

```
SkPaint p;
p.antialias = true;
p.setColor(0xFFFFFFFF);            // ① 恒为纯白，已唱/未唱不改颜色
p.setAlphaf(clamp(alpha, 0, 1));   // ② 亮度只由 alpha 承载
canvas.save();
canvas.translate(0, yOffset);      // ③ 行垂直位移
k = state[0x1f4] / state[0x1fc];   // ④ 行缩放比
canvas.scale(k, k);
<drawGlyphs>( (baseY + dh)/k, 0, lineHeight/k, textObj, canvas, layout, p, modeFlag );
canvas.restore();
```

| # | 参数 | 值/来源 |
|---|---|---|
| 1 | 文字颜色 | `#FFFFFFFF` **恒定** |
| 2 | 亮度 | `setAlphaf(clamp(α,0,1))` |
| 3 | 字号 / 行高 | `state[0x238]` / `state[0x23c]`；**两者都 ≤0 时行高取 `600.0`** |
| 4 | 缩放 | `k = state[0x1f4] / state[0x1fc]` |
| 5 | 垂直居中模式 | `state[0x218] ∈ {0,1,2}`：`dh = max(0, fontSize − lineHeight)`；mode1 → 偏移 `dh*0.5`；mode2 → 不偏移 |

---

## 2. 辉光（外发光）✅

**本质 = 把该行内容用「带高斯模糊的 paint」再画一遍，然后合成。**

```
drawWithGlow(σ, ctx, canvas, rect, flag):
    k = 1.0
    if σ > 4.0: k = clamp(4.0/σ, 0.25, 1.0)          // 高分屏降采样
    if k < 1.0 or flag:
        N  = (int)(σ * 3.0)                          // 3σ 外扩
        x0 = rect.x - N;  y0 = rect.y - N
        w  = (int)((N + rect.w - x0) * k)
        h  = (int)((N + rect.h - y0) * k)
        离屏 = allocBitmap(w, h); c = 离屏.canvas()
        c.clear(透明); c.save(); c.scale(k,k); c.translate(-x0,-y0)
        SkPaint p; p.setImageFilter(SkImageFilters::Blur(σ, σ, kDecal /*=3*/, null, null))
        <用 p 把本行内容重画一遍>(c, rect, p)
        <合成回主画布>
        c.restore()
        主canvas.save(); 主canvas.translate(x0,y0); 主canvas.scale(1/k,1/k)
        主canvas.drawImage(离屏); 主canvas.restore()
    else:
        SkPaint p; p.setImageFilter(SkImageFilters::Blur(σ,σ,kDecal))
        <用 p 直接绘制并合成>
```

| # | 参数 | 值 |
|---|---|---|
| 1 | 模糊 | `SkImageFilters::Blur(σ, σ, kDecal)`（tileMode **3 = kDecal**） |
| 2 | 外扩 | `N = ceil(3σ)` |
| 3 | 降采样 | `σ > 4` → 按 `clamp(4/σ, 0.25, 1.0)` 缩放离屏 |
| 4 | **门限** | `σ ≤ 0.1` → **完全跳过辉光**（不画） |
| 5 | 调用点 | 每行 3 次（主行 / 译文行 / 第三层），σ 同值 |

### 2.1 σ 的取值 ✅
```
距离 d = |该行下标 − 当前行下标|
σ = renderer[0x4c0] × (max(d,1) − 1)          // = 单位值 × (d − 1)
if renderer[0x7f8] || flag:            σ = 0
if renderer[0x33c] || renderer[0x33d]: σ = 0
行对象[0x80] = (double)σ
```
- **单位值 `renderer[0x4c0]` = 设置对象 `+0x70`，默认 `0.05`** ✅
- ⇒ **σ = 0.05 × (行距离 − 1)**：当前行/相邻行 σ=0（清晰）。
  门限是 `σ <= 0.1 → 跳过`，故 **d≤3 全部被挡（d=3 时 σ=0.10 仍被跳过）**，**真正开始发光是 d≥4（σ=0.15）**。
- 配合"越远 alpha 越低"，形成截图里"越远越糊越暗"的观感。

### 2.2 设置对象默认值（全 float）✅
| 偏移 | 默认 | 偏移 | 默认 |
|---|---|---|---|
| +0x40 | 0.98 | +0x70 | **0.05**（辉光单位值） |
| +0x44 | 1.0 | +0x78 | 0.125 |
| +0x48 | 0.63 | +0x80 | 7.0 |
| +0x4c | 0.7 | +0x88 | 2.0 |
| +0x60 | 0.8 | +0xa0 | 0.25 |
| +0x68 | 26.0 | +0xb8 | 0.2 |

---

## 3. 逐字数据结构 ✅

### 3.1 逐字对象（步长 `0x3f0`）
| 偏移 | 含义 |
|---|---|
| +0x22 | 启用标志 |
| +0x58 | **起始时间** |
| +0x60 | **时长** |

- 行总时长 = 该行所有逐字 `+0x60` 之和（`0x29ee404`）。

### 3.2 逐字动画对象（步长 `0x38`）
| 偏移 | 含义 |
|---|---|
| +0x18 | 当前值 |
| +0x1c | 起始值 `from` |
| +0x20 | 目标值 `to` |
| +0x24 | 已累计时长 |
| +0x28 | 总时长 |
| +0x2c | 激活标志 |

更新（`0x29edfa0`）：
```
w[0x24] += dt
p = clamp(w[0x24] / w[0x28], 0, 1)
w[0x18] = w[0x1c] + p * (w[0x20] - w[0x1c])     // 线性补间
if p >= 1.0: w[0x2c] = 0
```

### 3.3 行对象（步长 `0x248`）
| 偏移 | 含义 |
|---|---|
| +0x14 | 结束时间 |
| +0x18 | 起始时间 |
| +0x1a0 / +0x1a2 | 标志 |
| +0x80 | σ（double） |

### 3.4 行级动画状态（`renderLine` 的 `LineRenderState`）
| 偏移 | 含义 |
|---|---|
| +0x1b4 / +0x1b8 | 辉光 **目标 / 当前** |
| +0x1c0 / +0x1c4 | 属性 **目标 / 当前** |
| +0x1c8 / +0x1cc | 同上 |
| +0x1e0 / +0x1e4 | 同上 |
| +0x1b0 | 置 1.0 的系数位 |

- 目标写入：`setGlowTarget`（`0x29f4068`），调用点仅当 `|新值−旧值| > 0.01`（阈值 `0x3c23d70a`）才写。
- 复位：`0x29e4954` 把 8 组目标→当前，并对每个 TimingFunction 调 `reset(now)`。

---

## 4. 动画曲线 ✅

### 4.1 `SpringTiming`（闭式物理弹簧）
对象 192 B，字段全 double：

| 偏移 | 含义 | 偏移 | 含义 |
|---|---|---|---|
| +0x20 | 质量 `m` | +0x90 | base（目标值） |
| +0x28 | 刚度 `k` | +0x98 | `A` |
| +0x30 | 阻尼 `c` | +0xa0 | `B` |
| +0x10 | 1.0 | +0xa8 | 指数衰减率 |
| +0x48 / +0x58 / +0x60 | 0.25 / −1.0 / 0.01 | +0xb0 | `ω` |
| +0x80 / +0x88 | int 状态 / 模式（0 静止、1 临界阻尼、2 欠阻尼） | +0xb8 | 振荡衰减率 |

```
ω0 = sqrt(k/m);  ζ = c/(2ω0);  ωd = ω0*sqrt(1-ζ²)
mode1（临界阻尼）: value(t) = base − (A + B·t)·exp(decay·t)
mode2（欠阻尼）  : value(t) = base − exp(decay·t)·(A·cos(ωt) + B·sin(ωt))
时长：c ≥ 2ω0 → duration = −2·m·ln(100)/c
      否则     → duration = ln(100) / min(ζ·ω0, ωd)      // ln(100)=4.605170185988091
```

### 4.2 本 App 实际使用的 4 组弹簧预设 ✅（`m` 恒为 1，`k=(2π/T)²`，`c=2ω0ζ`）
| T（自然周期） | k | c | ω0 | ζ | 1% 稳定时长 | 特性 |
|---|---|---|---|---|---|---|
| **0.35 s** | 322.2728 | 25.1327 (=8π) | 17.952 | **0.70** | 0.367 s | **唯一有回弹 →「跳起来」用它** |
| 0.30 s | 438.6491 | 41.8879 | 20.944 | 1.00 | 0.220 s | 无回弹 |
| 0.25 s | 631.6547 (=64π²) | 50.2655 (=16π) | 8π | 1.00 | 0.183 s | 最干脆 |
| 0.20 s | 986.9604 | 62.8319 (=20π) | 10π | 1.00 | 0.147 s | 最快 |

### 4.3 `CSSCubicBezierTiming` ✅
= WebKit/Blink `UnitBezier` 同构：Newton-Raphson **≤8 次**迭代，x 收敛阈值 **1e-7**，斜率阈值 **1e-12**，控制点取对象 `+0x08/+0x18`，x 上限 `+0x28`。
⇒ **可直接吃 CSS `cubic-bezier(x1,y1,x2,y2)`。**

### 4.4 `TimingFunction` 基类
统一接口 `reset(now) / value(t) / duration()`（reset 入口 `0x2842440`）。

---

## 5. 连续流动相位 ✅

`0x29ef7b4` 返回值含：
```
now() * -10.0  +  (now() * state[0x1f8]) / state[0x1fc]  +  now() * 10.0 * (1/state[0x1fc] − 1)
```
⇒ **每秒 10 单位**的连续推进项（行滚动 / 流动感来源）。
另有 `0x3081f5c`：`phase = rate × now()`（`rate = renderer[0x450]`），clamp ≥0。

---

## 6. 逐字 vs 整词：CJK 判定 ✅

`0x29c8040(text)` 逐 UTF-8 码点判断，命中即返回 1（= 逐字处理）：
- `U+4E00–U+9FFF`（CJK 统一表意）
- `U+3400–U+4DBF`（扩展 A）
- `U+3040–U+30FF`（平假名/片假名）

**⇒ 中日韩文字按「逐字」做波浪/扫色；拉丁字母按「整词」整体处理。**
这是 `Me ooh`（整词一起亮、一起浮起）与中文歌词（逐字波浪）行为不同的代码依据。

---

## 7. 复刻伪代码

```kotlin
// 每帧，对每行
fun renderLine(line: Line, canvas: Canvas, now: Double) {
    val d = abs(line.index - activeIndex)
    val sigma = 0.05f * max(0, d - 1)                 // ① σ
    val alpha = line.alpha                             // ② 由逐字/行补间算出

    // 逐字补间（每属性一个）
    for (w in line.tweens) {
        w.elapsed += dt
        val p = clamp(w.elapsed / w.duration, 0f, 1f)
        w.value = lerp(w.from, w.to, p)                // ③ 线性
        if (p >= 1f) w.active = false
    }

    val paint = Paint().apply {
        isAntiAlias = true
        color = Color(0xFFFFFFFF)                      // ④ 恒白
        alphaf = alpha.coerceIn(0f, 1f)
    }

    if (sigma > 0.1f) {                                // ⑤ 门限
        paint.imageFilter = ImageFilter.makeBlur(sigma, sigma, TileMode.DECAL)
    }

    canvas.save()
    canvas.translate(0f, line.yOffset)
    val k = line.scaleNum / line.scaleDen
    canvas.scale(k, k)
    drawGlyphs(canvas, paint, line, cjk = isCJK(line.text))   // ⑥ 逐字 or 整词
    canvas.restore()
}

// 行/属性动画推进（换行或状态变化时）
fun advance(t: Double) {
    for (fn in timingFunctions) fn.reset(t)            // ⑦ 重启所有 tween
}
```

---

## 8. 红线

1. **文字颜色恒 `#FFFFFF`**，已唱/未唱只改 alpha —— 不要用两套颜色。
2. **σ ≤ 0.1 必须跳过辉光**，否则每行都建离屏 → 掉帧。
3. **辉光是"重画一遍"，不是发光着色器** —— 不要另写 bloom shader。
4. **tileMode 必须是 kDecal(3)**；离屏必须外扩 `3σ`，否则辉光被裁。
5. **σ > 4 必须降采样**（`clamp(4/σ, 0.25, 1)`），否则离屏尺寸爆炸。
6. **要「起伏/回弹」只能用 T=0.35s / ζ=0.70 那组**；其余三组 ζ=1.0 无回弹。
7. **逐字 vs 整词由 CJK 判定决定**，不要对所有语言统一处理。
8. 逐字补间是**线性 lerp**，缓动由外层弹簧/贝塞尔提供 —— 不要给 lerp 本身加 easing。

---

## 9. 未解（不影响复刻主效果）

| 项 | 状态 |
|---|---|
| 逐字扫色的**逐像素**实现（`0x29c8f34` 内的具体 clip/渐变写法） | ❓ 反编译产物已在 `D:\rea\out_z\F_2ac8f34_*.c`，可继续读 |
| ~~「浮起」的 Y 位移具体数值与缩放~~ | ✅ **已解出，见第 10 节** |
| 密钥 FNV-1a 的**输入数据** | ❓ 不影响（跳板已直接给出真函数） |

---

## 附：证据与产物

- 完整逆向报告：`D:\rea\targets\lyricsblossom\REPORT_LyricsBlossom_歌词逆向.md`（附录 A/B/C）
- 反编译 C 代码：`D:\rea\out_y\`（27 个核心函数）、`D:\rea\out_z\`（7 个破解出的真函数）
- Ghidra 环境（可复用）：`D:\rea\ghidra` + 脚本 `D:\rea\ghidra_scripts\DecompileExact.java` + 工程 `D:\rea\ghidra_proj`
- 复现时的两个坑：① **Ghidra 地址 = ELF vaddr + 0x100000**；② Ghidra 函数边界比 eh_frame 粗，需用 `functions.txt` 强制切。

---

## 10. 逐字「浮起 + 缩放」的具体数值 ✅（`0x29c8f34`）

**驱动量 = 该字/词的演唱时长 `dur`（秒）** —— 长音/高声 = 长时长 ⇒ 触发浮起。

```c
// 时长变化时重算（obj[0x8c] 缓存上次 dur）
fVar25  = max(dur, 1.0);
obj[0x84] = (min(fVar25, 2.0) - 1.0) * 0.14 + 1.0;      // 缩放
obj[0x88] = 2.0 * (min(fVar25, 1.5) - 1.0) * 0.4;       // 位移系数
```

| # | 量 | 公式 | 取值 |
|---|---|---|---|
| 1 | **缩放** `obj[0x84]` | `1.0 + (clamp(dur,1,2) − 1.0) × 0.14` | **1.00 → 1.14**（最大 +14%） |
| 2 | **位移系数** `obj[0x88]` | `2 × (clamp(dur,1,1.5) − 1.0) × 0.4` | **0.00 → 0.40** |
| 3 | **实际 Y 位移** `obj[0x80]` | `T(t) × obj[0x88]`（`T` = 该属性的 TimingFunction 输出 0→1） | 0 → 0.40 |
| 4 | 每字形缩放 | `1 + (obj[0x84] − 1) × f` | `f` = 该字形进度 |
| 5 | 每字形横向展开 | `(obj[0x84] − 1) × (i − (n−1)/2) × 0.5 × f` | 以词中心为基准，越靠边位移越大 |
| 6 | 振幅基数 `obj[0x94]` | `min(CJK?0.8:0.4, dur/n × (CJK?0.8:0.4))` | **CJK 0.8 / 拉丁 0.4** |
| 7 | 速率 `obj[0x98]` | `dur / (n × 0.5)` | `n` = 字形数 |

- 阈值：`dur ≤ 1.0s` → 缩放=1.0、位移=0（**不浮起**）；`dur ≥ 2.0s` 缩放封顶 1.14；`dur ≥ 1.5s` 位移封顶 0.4。
- 特例：当 `obj[0x3c0]` 或 `obj[0x3c1]` 置位且文本为 CJK 时，`obj[0x84..0x88]` 被整体写成常量 **[1.0, 0.0]**（该模式下 CJK 不缩放/不浮起）。
- 逐字形触发条件（`i` 为字形序号）：`A × i ≤ t` 触发该字形进场（`A = obj[0x94]`）；`B + A × i ≤ t` 触发第二段（`B = obj[0x98]`）。

> 注：`obj[0xa0]` 是承载该属性的 TimingFunction 输出（复位在 `0x29e4954`：`obj[0xa0] = now`），所以第 3 行的 `T(t)` 即它的缓动量。
