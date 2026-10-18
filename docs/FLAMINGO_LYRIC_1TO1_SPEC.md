# Flamingo 歌词系统 — 1:1 复刻规格（可据以编码）

> 来源：手机 `yos.music.player` 1.0.0-2608310056（R8 混淆）经 REA/JADX 反编译。
> 本文件目标是：**给一段歌词，按本文可实现与 Flamingo 行为一致的歌词渲染**。
> 标记：`✅实测`=方法体/字段直接读到；`🔶推断`=据调用点推导；`❓未知`=未反解。

---

## 0. 命名对照（混淆名 → 语义）

| 混淆 | 语义 | 文件/证据 |
|---|---|---|
| `Z6.D` | 歌词**行** | ✅ |
| `Z6.x` | 歌词**字/词片段** | ✅ |
| `U6.D` | 歌词**全局 Store**（StateFlow 总线，37 字段） | ✅ |
| `U6.r` | 歌词**状态机**（定位当前行） | ✅ |
| `U6.o` | **心跳协程**（每帧算当前行） | ✅ |
| `z7.e` | 歌词**渲染类**（21 方法） | ✅ |
| `z7.e.j` | **"WavyLazyColumn"** 自定义列表 | ✅（字符串） |
| `z7.B0` / `z7.o0` / `z7.q0` / `z7.r0` / `z7.i0` | 滚动动画状态机及协程 | ✅ |
| `z7.b0` | 单字段（布局+高光区间） | ✅ |
| `z7.p` | 一行绘制计划 | ✅ |
| `z0.q` | BlurEffect（`RenderEffect`） | ✅ |
| `y0.c` | 单字符边界框 | ✅ |
| `p1.c` / `p1.f` | Compose `Density` / `TextUnit` | ✅ |

---

## 1. 数据模型

```kotlin
// Z6.D — 行
class LyricLine {
    val text: String        // f13031l 该行原文
    val startMs: Long       // f13032m
    val endMs: Long         // f13033n
    val altText: String     // f13034o 备用文本（无逐字时用）
    val segments: List<Seg> // f13035p 逐字/逐词片段（原文）
    val translation: String // f13036q 译文
    val trSegments: List<Seg> // f13037r 译文片段（🔶推断）
    fun isSegmented(): Boolean // f() = segments 非空
    fun durationMs(): Long = endMs - startMs  // b()
}
// Z6.x — 字/词片段
class Seg(
    val startMs: Long,   // f13092l
    val endMs: Long,     // f13093m
    val text: String,    // f13094n
    val trailingSpace: Boolean  // f13095o 渲染时补空格
)
```

---

## 2. 后端时间轴逻辑

### 2.1 Store（`U6.D`）— UI 只订阅这里

| 字段 | 类型 | 用途 |
|---|---|---|
| `f11097f` | `StateFlow<List<LyricLine>>` | 行列表 |
| `f11098g` | `MutableStateFlow<Int>` | **当前行索引**（`-1`空行 / `-2`无歌词） |
| `f11090J` / `f11091K` | `String` | 当前行原文/译文（对外推送） |
| 其余 ~10 个 h0/e0 | StateFlow | 翻译开关、封面、偏移等 |

### 2.2 二分定位（`U6.r.a`）✅ 每帧调用

```kotlin
fun syncCurrentLine(r: State, pos: Long) {
    val list = store.lines.value
    if (list.isEmpty()) { r.setIndex(-2); return }
    val cur = r.index
    if (cur < 0 || cur >= list.size || !inLine(list[cur], pos, cur, list.lastIndex)) {
        // 二分：最后一个 startMs <= pos
        var lo = 0; var hi = list.lastIndex; var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (list[mid].startMs <= pos) { lo = mid + 1; best = mid } else hi = mid - 1
        }
        val idx = if (best == -1 || !inLine(list[best], pos, best, list.lastIndex)) -1 else best
        if (idx != r.index) r.setIndex(idx)
    }
}
// U6.r.b — 区间判定
fun inLine(d: LyricLine, pos: Long, idx: Int, last: Int): Boolean {
    val s = d.startMs; val e = d.endMs
    return if (s != e) pos > s + 1 && pos < e            // +1 边界防抖
           else idx == last && !d.isSegmented() && pos > d.startMs
}
```

### 2.3 切行（`U6.r.d`）✅

```kotlin
fun setIndex(i: Int) {
    if (i == index) return
    index = i
    store.currentIndex.value = i          // ★ 驱动 UI
    // 对外推送（状态栏/车机）仅在开关开启时：
    if (lyricGetterApi || superLyricApi || tickerLyric || mediaSessionLyric) {
        // 拼接当前行原文+译文，协程推送 cn.lyric.getter / com.hchen.superlyric
    }
}
```

### 2.4 心跳（`U6.o.invokeSuspend`）✅

```kotlin
while (isActive) {
    if (!player.isPlaying()) { player.awaitEvent(); continue }
    var pos = player.position() + Settings.lyricTimingOffsetMs   // 用户延迟
    val lyricon = Settings.lyriconEnabled
    syncCurrentLine(r, pos)
    val next = store.lines.value.firstOrNull { it.startMs + 1 >= pos }?.startMs
    val delay = if (lyricon) 50L else (next?.let { it + 2 - pos } ?: 50L)  // 增强=50ms定频
    delay(delay)
}
```

---

## 3. 从 LRC 到逐字（`z7.e.o`）✅ 关键

```kotlin
fun buildSegments(segments: List<Seg>, lineWidth: Float, ...): List<List<Seg>> {
    val out = ArrayList<Seg>()
    for (seg in segments) {
        val dur = seg.endMs - seg.startMs
        val txt = seg.text
        // 拆分单元：含空格→按词；否则→逐字
        val units = if (txt.contains(" ")) txt.split(" ") else txt.map { it.toString() }
        var t = seg.startMs
        val n = txt.length
        for (u in units) {
            if (measureWidth(u) > lineWidth) {
                for (ch in u) { out += Seg(t, t + dur/n, "$ch", false); t += dur/n }
            } else {
                out += Seg(t, t + u.length*dur/n, u, u == " "); t += u.length*dur/n
            }
        }
    }
    // 再用 BreakIterator.getLineInstance(Locale.ROOT) 按行边界换行（z7.e.n）
    return wrapLines(out, lineWidth)
}
```

**规则**：
- 普通 LRC（只有行时间）→ 该行时长 **按字数均分**。
- 逐字歌词（QRC/KRC，`seg.endMs-seg.startMs` 已是字级）→ 保留原始时间。
- 空格：`trailingSpace = (u == " ")`。

---

## 4. 单行渲染总流程（`z7.e.c`）✅

每帧对**每一行**：

```
1. now = currentTimeMs
2. lineStart = line.startMs ; lineDur = max(line.endMs - lineStart, 1)
3. p = clamp((now - lineStart) / lineDur, 0, 1)     // 行内进度 0..1
4. s = smoothstep(p) = (3 - 2p)·p²                   // 行级缓动
5. 计算「高光前沿 x」frontier（见 §5）
6. 构造 Brush（见 §5）
7. 若 (当前行 || 辉光行):
      逐段/逐字：画辉光(z7.e.p) + 画字(z7.e.q)，每字带起伏位移(§7)
   否则（非当前行）:
      整行上浮 s·alpha，先画未唱色、再按 alpha 叠画已唱色（§7.2）
```

---

## 5. 高光判定（核心）✅

### 5.1 前沿位置 `frontier`（x 坐标）

```kotlin
var acc = 0f
var frontier = 0f
for (seg in line.segments) {
    val xLeft  = acc + seg.hlLeft      // seg.f26346e = 墨迹左
    val xRight = acc + seg.hlRight     // seg.f26347f = 墨迹右
    if (now < seg.startMs) { frontier = xLeft; break }          // 未唱到 → 停在段首
    if (now <= seg.endMs) {                                     // 唱到该段内
        val t = (now - seg.startMs) / max(seg.endMs - seg.startMs, 1)
        frontier = lerp(xRight, xLeft, t)   // 源码原样：lerp(右, 左, t)
        break
    }
    frontier = max(frontier, xRight)        // 已唱完 → 推进
    acc += seg.advance                      // seg.f26344c = 段宽
}
```

> ⚠️ 源码 `h4.a.a(f37, f36, t, f36)` 即 `lerp(segRight, segLeft, t)`，**操作数顺序照抄**。
> `f26346e/f26347f` 来自 `z7.e.m`：对段内所有字符取 `min(left)/max(right)` 的墨迹边界。

### 5.2 渐变刷（`z0.r` / Brush）——⚠️ 本节作废，勿照抄

> **2026-10 订正**：本节的 `240f/frontier` 写法源自 `z7.e.c` 中一段
> **jadx 反编译失败的分支**（字节码里出现 `fA >= fA`、`f39 = fA/fA` 这类**自比较
> artifact**，是 jadx 的解包错误，Java 不可信）。**按此字面实现会导致整行瞬间变白**
> （`frontier` 小时 `240/frontier` 爆炸）—— 真机已复现并回退。
>
> **结论**：**不要**按本节实现。手机版确实有一条"整行级渐变刷 + 前沿扫过"的机制，
> 但其**精确几何无法从当前反编译结果中可信还原**（需要 smali 或换引擎重反编译）。
>
> **替代方案（2026-10 订正：先前结论有误）**：
> ~~本仓库已有的逐字 `Brush.horizontalGradient` 行为等价~~ —— **错**。
>
> ★★ **关键事实**：`DrawScope.drawText(textLayoutResult, topLeft, brush = …)` 里，
> **Brush 的坐标空间是「整个绘制面」（= 整行），不是单个字**。
> 因此：
> - 若按**字宽**构建渐变（`endX = 字宽`）→ 渐变被压到整行最左一小段 → 所有字都落在渐变之外、
>   全部取**最后一个 stop 色** → **整行都变成未唱色**（表现为"已唱完的部分也很淡"）。
> - 若**不传 `endX`**（默认 `∞`）→ 渐变按整行宽度铺开 → 每个字只落在极小一段 →
>   **"一个词只有前 1~2 个字母有色"**。
>
> **正确做法 = 手机版架构**：**整行一条渐变**，用**行坐标**构建：
> ```kotlin
> Brush.horizontalGradient(stops /* 位置均为「行宽」归一化 */, startX = 0f, endX = 整行宽)
> ```
> 然后所有字**共用同一个 brush**。（手机版则是把它画成一个 `drawRect(brush)` 矩形覆盖到前沿。）
>
> 前沿 = 逐字推进：已唱完的字取 `x+字宽`，第一个未唱完的字取 `x + percent×字宽`。
> 软边 = 以**前沿为中心、240px 宽**，颜色按 `EaseInQuart` 插值（见 `z7.e.r`）。

### 5.3 单字绘制 `z7.e.q`（订正：**不是高光交叉**）✅

```java
static void q(DrawScope scope, TextLayoutResult k, long color, float x, float y) {
    float iy = floor(y); float fy = y - iy;          // ★ y 的整数/小数部分
    if (fy <= 0.001f) drawText(scope, k, color, pack(x, iy), 0f);
    else {
        drawText(scope, k, color, pack(x, iy),     1f - fy);   // 纵向亚像素插值
        drawText(scope, k, color, pack(x, iy + 1), fy);
    }
}
```

- 参数是 **(x, y)**——不是"已高亮到第几个字"。它是**逐字纯色绘制**，用两次绘制做**纵向亚像素定位**。
- **关键结论**：手机版对**带辉光的字**调 `q()`（纯色、alpha=1）→ **永远实心高亮色，不会淡**。
  本仓库若对它用逐字渐变刷，未唱部分会渲染成 `unfocusedColor`（18% 白）→ 脉冲边缘**看起来像"淡入淡出"**。

---

## 6. 辉光（Glow）✅ `z7.e.p` + `z0.q`

```kotlin
// 【逐指令验证：dexdump 反汇编 z7.e.p（676 code units），寄存器 v41=DrawScope,
//   v42=b0, v43=nowMs, v45=segDurMs, v47=color, v49=riseAmp, v50=isCurrent,
//   v51=lineSmooth, v52=baseline, v53=layouts, v54=caches, v55=cacheOff, v56=flags】
fun drawGlowLine(scope, seg, nowMs, segDurMs, style, color,
                 riseAmp, isCurrent, lineSmooth, baseline, layouts, caches, cacheOff, flags) {
    val glowLayerOn  = (flags and 4096) != 0   // 0x000a..0x0011  v16
    val solidLayerOn = (flags and 8192) != 0   // 0x0014..0x001b  v17

    // ① 片段级曲线（segDurMs 是「段时长」，不是播放位置）
    val fT  = clamp((segDurMs - 1000f) / 2000f, 0f, 1f)   // 0x001d..0x002a
    val s   = (3f - 2f*fT) * fT * fT                       // 0x0036..0x0035  f14
    val f15 = s * 0.75f + 0.75f                            // 0x003a  v20
    val f16 = s * 0.06f + 1.06f                            // 0x0043  缩放上限
    val f17 = s * 5.0f  + 7.0f                             // 0x0049  ★模糊半径 px
    val f18 = s * 0.2f  + 0.42f                            // 0x0051  ★辉光 alpha

    val text = seg.text; val n = text.length
    if (n == 0) return                                     // 0x0060
    val f19 = segDurMs * 0.68f                             // 0x005a  ★单字窗口
    val f20 = if (n > 1) 1f/(n-1) else 0.5f                // 0x0068
    val f21 = (seg.endMs - f19) - seg.startMs              // 0x007a  = 0.32*segDurMs

    for (i in 0 until n) {                                 // 0x007e..
        // ② 逐字
        val fT2 = clamp((nowMs - ((i*f20)*f21 + seg.startMs)) / f19, 0f, 1f)  // 0x009e
        val f22 = (3f - 2f*fT2) * fT2 * fT2                                  // 0x00a8
        val pulse = sin(fT2 * PI).let { it * it }                            // 0x00b5  f24
        val dy = -((f15*pulse) + ((0.3f*lineSmooth + 0.7f*f22) * riseAmp)) * isCurrent  // 0x00c6  f25

        val charLayout = layouts[i] ?: continue                              // 0x00ca..0x00d0
        val charX   = seg.layout.getHorizontalPosition(i, true)              // 0x00e3 ★段布局取字 x
        val charTop = baseline - charLayout.d                                // 0x00e9 (d=ascent)
        val scale   = lerp(f16, 1.0f, pulse)   // = 1.0 + (f16-1.0)*pulse       // 0x00f1
        val cache   = caches[cacheOff + i]
        if (cache == null || pulse <= 0.001f) {                             // 0x00ff..0x0106
            if (solidLayerOn) drawChar(style, color, charX, charTop + dy)    // 0x028a (z7.e.q)
            continue
        }
        // ③ 重建两层（仅缓存未命中；缓存键 = 字/样式/尺寸/色/半径）
        val pad = 60                                                        // 0x0115 X5.a.O(60f)
        val layerSize = Offset(charW + 2*pad, charH + 2*pad)                // 0x013e ★
        cache.a.setCompositingStrategy(1); cache.a.setRenderEffect(z0.q(f17, f17)); cache.a.setClip(false)
        scope.record(layerSize, cache.a) { drawCharAt(style, color, pad, pad) }   // 0x0195 字画在(60,60)
        cache.b.setCompositingStrategy(1); cache.b.setRenderEffect(null); cache.b.setClip(false)
        scope.record(layerSize, cache.b) { drawCharAt(style, color, pad, pad) }   // 0x01b0
        // ④ 每帧变换
        //   translation = (charX - 60, charTop + dy - 60)                    // 0x01fd, 0x0201
        //   transformOrigin = pack(w + 60, h/2 + 60)                          // 0x01fc ⚠️见下
        //   scale = scale ; alpha: A = f18*pulse, B = 1.0                     // 0x0231, 0x0260
        if (glowLayerOn)  drawLayer(cache.a)                                  // 0x0265
        if (solidLayerOn) drawLayer(cache.b)                                  // 0x026c
    }
}
// z0.q.f() = RenderEffect.createBlurEffect(rx, ry, TileMode.Decal)  ← 半径是 **px**，无密度缩放
```

**`transformOrigin`（已钉死 ✅）**：
- `C0.c.k(long)` = **`setTransformOrigin`**（dex 内含字面量 `transformOrigin`，**无** `pivotOffset`）；
  转发到 `C0.e.U(long)`。smali：`if(!Offset.equals(v, newV)){ v = newV; impl.U(newV); }`
- packed 值 = **`packFloats(w/2 + 60, h + 60)`**（`IntSize` 惯例 width 在高位，与 `TransformOrigin` 的 x-高位一致）
  → 层内锚点 = **字形「水平中心 × 底部」**（层内字画在 (60,60)，字形占 `[60,60+w] × [60,60+h]`）
  → 缩放 `1.0 → 1.0+(f16-1)·pulse` 时，字形**向上 + 向左右**放大。

**辉光门控**（`z7.e.e` → `z14`）✅：
> 遍历该行片段，**存在** 片段满足 `2 <= trim(文本).length < 11` **且** `片段时长 >= 1000ms` → 开启辉光。

⚠️ **订正**：`e6.k.N0` 已确认 = **`trim()`**。此门控的前提是**片段为「词」级（2~10 字）**。
本仓库的 KRC 中文单元是**单字**（`wordNonSpaceChars == 1`），**永远过不了 `2..10`** →
必须改为**按单元时长**判定：`val isSlowWord = wordDurationMs >= 1000f`。

**绘制保证**：每字**两层** —— ① 带半径 `f17` 的模糊层（alpha = `f18·pulse`）= 辉光；② 实心层（alpha=1）= 本体。

**三条易踩的坑（已订正）**：
1. **不要「镂空字芯」**。手机版的辉光层是**一张模糊的整字**（芯是实心的），**没有** `BlendMode.Clear`。
   误加 Clear 会擦掉字芯 → 辉光与字之间出现**缝隙/光环**。
2. **脉冲缩放要作用在两层**（`cVar3.n/o(fA)` **和** `cVar4.n/o(fA)`）。
   只缩辉光层的话，实心字不动 → 脉冲**几乎看不出来**。
3. **阈值必须一致**：手机版只有一个门 `pulse > 0.001`（`f24 <= 0.001f` 才退化）。
   若辉光的门（如 `>0.01`）比缩略的门更早触发，脉冲**边缘**会出现"辉光已亮但字未放大"→ 视觉上**忽明忽暗**。
   实心层的缩放应**恒定施加**（`s==1` 时为 no-op），不要做 `if (s != 1f)` 分支。

---

## 7. 起伏 / 升降动画 ✅

### 7.1 逐字上浮（当前/辉光行，`z7.e.c`）

```kotlin
// 对每个字（或段内每字）：
val charStart = seg.startMs + charOffset                       // 逐字：segStart + i*size/len
val t0 = max(charStart - 180L, line.startMs)                    // ★ 提前 180ms 起坡
val win = 180f + charDurMs + 400f                               // ★ 上升窗口 = 180 + 字时长 + 400
val riseT = clamp((now - t0) / win, 0f, 1f)
val riseS = (3f - 2f*riseT) * riseT * riseT                     // smoothstep
val risePx = roundToInt(3.5.dp.toPx())                          // ★ 幅度真值（已反解，见下）
val dy = -(((lineSmooth * 0.3f) + (riseS * 0.7f)) * risePx * isCurrent)  // ★ 负=向上浮
drawChar(layout, x, baselineY = baseline - ascent + dy)
```

**幅度真值**（已从字节码反解）：手机版 `d1Var = remember { e7.j(17, density) }`，
tag 17 → `pswitch_1a`：`roundToInt(density.S(3.5f))` 即 **`roundToInt(3.5.dp.toPx())` ≈ 10px**（密度 2.75~3）。
❌ 早期文档写 `4f` **偏小 2.5 倍**（这就是"幅度几乎看不出来"的原因）。

**语义**：字被唱到的前 180ms 开始，向上浮一个量，幅度 = 行缓动×0.3 + 字缓动×0.7 的加权，再乘 `risePx` 与"是否当前行"。
（手机版乘的是 isCurrent(0/1)；本仓库用 `thisAlpha` ≈ 1 等价。）

### 7.2 非当前行 —— ⚠️ 订正：**无整行位移**

**订正（据 `z7.e.c` 字节码）**：非当前行分支的位移量是
`f58 = z24 ? (-f32) * isCurrent : 0`，而该分支里 `isCurrent == 0` →
**`f58 = 0`，即非当前行不做任何位移**。早前 §7.2 写的"整行上浮 + 双遍叠色"是**错的**。

该分支实际只做两件事：
```kotlin
if (!isCurrent && !glow) {
    drawWholeLine(color = unsungColor)       // 未唱色；若 alpha>0 再叠已唱色
    if (alpha > 0.001f) drawWholeLine(color = sungColor)
}
```
且颜色按行 alpha（**0.4**）整体淡化（见 §8）。

> 「升降」的真实来源 = **§7.1 的逐字位移** + **§10 的按行滚动**，不是整行上浮。

---

## 8. 行级 alpha / 进入动画 ✅

| 项 | 值 |
|---|---|
| 动画名 | `"LyricAlpha"` |
| 时长/缓动 | `animateFloatAsState(260ms, easing = EaseOutQuart)` |
| 目标值 | 当前行 `1.0`；非当前行 **`0.4`** |
| 逐字行 current 判定 | `z11 && !z10 && segments 非空` |

**缓动已钉死**（`t.w.<clinit>` 反汇编）：
`t.w.f23056e = CubicBezierEasing(0.25f, 1.0f, 0.5f, 1.0f)` = **`EaseOutQuart`**。
同表其余常量：`f23052a`=EaseOut, `f23053b`=EaseInCirc, `f23054c`=EaseInOutCirc, `f23055d`=EaseInQuart。

---

## 9. 译文（`z7.e.h`）✅

```kotlin
val visible = isCurrent && translation.isNotEmpty()
val v = animateFloatAsState(
    targetValue = if (visible) 1f else 0f,
    spec = tween(220, 0, easing = EaseOutQuart),   // "TranslationVisibility"
)
if (translation.isNotEmpty())
    Text(translation, modifier = Modifier.padding(start=20.dp, top=5.dp)
        .alpha(v), fontSize = subTextSize, ...)
```

---

## 10. 滚动 ✅

### 10.1 列表 = `"WavyLazyColumn"`（自研惰性布局引擎）✅

字符串证据：`"WavyLazyColumn item keys must be unique"`、`"WavyLazyColumn items="`、`"wavy.recompose"`。
**手机版不用 Compose 的 `LazyColumn`**（APK 里 `androidx.compose.foundation.lazy.*` 一个都没有）——它是一套**自研的惰性布局引擎**，位于 `x.*` 包（与 `z7` 同级的另一套混淆库）。

**调用管线**（`z7.e.j` → `x.*`）：

```
z7.e.j (歌词列表 composable, 即 "WavyLazyColumn")
  ├─ x.S.b(...)                          → remember(x.X 状态)          [L1927]
  ├─ x.S.a(modifier, X, y0, ...)         → Modifier                    [L2116]
  │     └─ new x.P(state, y0, ...)       → Modifier.NodeElement         (x.S.a)
  │           ├─ x.H.a  测量: 惰性 pump 子项 → MeasureResult            (x.A→x.H.a)
  │           ├─ x.H.b  滚动: scrollBy(delta)                          (x.B→x.H.b)
  │           ├─ x.H.c  滚动: scrollTo                                  (x.C→x.H.c)
  │           └─ D6.a.r 偏移步进: 每帧递减位移                           [x.H.a L206]
  └─ z7.E0 (项 lambda) / z7.C0 (项容器: ArrayList<z7.d0>)
```

**关键类**：

| 类 | 作用 | 证据 |
|---|---|---|
| `x.S` | 修饰符工厂（`a()` 建、`b()` remember、`c(long)`） | ✅ |
| `x.X` | 测量/布局状态接口 `a(v.u0, U5.e, M5.i): Object` | ✅ |
| `x.P` | `Modifier.NodeElement`（持 state/y0/flags） | ✅ |
| `x.H` | 布局引擎：`a`=测量+放置泵、`b`=scrollBy、`c`=scrollTo、`g`=可否滚、`h`=sp→px | ✅ |
| `x.A/B/C/D/E` | 各协程 lambda（`MeasureScope`,`V5.v` 等） | ✅ |
| `x.Q` | 协程（`invokeSuspend`） | ✅ |
| `x.y0` | 2 值枚举（滚动轴向，如 `f24580l`/`f24581m`） | ✅ |
| `D6.a` | **偏移步进器** | ✅ |
| `z7.d0` | 项包装 `{Object, int, boolean, Object, m0.f}` | ✅ |
| `z7.C0` | 项容器 `{a: ArrayList<z7.d0>}` | ✅ |

**布局流程（伪代码）**：

```kotlin
// 测量（x.H.a）：惰性 pump
fun measure(scrollBy: Long): Boolean {
    if (x.G1.c(measureScope)) return false        // 早退检查
    val state = ScrollState(constraints)
    val cursor = density.toPx(constraints.i)       // sp→px 起始偏移
    while (true) {
        val result = measureScope.g(childPlaceable)   // ★ 逐个 measure 子项
        // 找 constraints 变化 / 不可见的项，调整 state / cursor
        // ...
    }
}
// 滚动（x.H.b）：scrollBy(delta)
suspend fun scrollBy(delta: Long) {
    if (x.H.g(currentResult, delta)) return        // 已在边界
    val remain = V5.v(delta)
    while (true) {
        val r = measureScope.g(childPlaceable)
        // 找首个 constraints 变化的项 → r.g(placeable, true): IntOffset → 修正 remain
        // y0.b.b(l1, l2): Boolean 判断 remain 归零
    }
}
// 偏移步进（D6.a.r）：每帧
fun r(step: Float, w: Long, h: Long): Long {
    cur = Offset.plus(cur, Offset.pack(w, h))
    if ((axis == null ? Offset.getX(cur) : abs(anim(cur))) < step)
        return NaN_Offset                       // 0x7FC000007FC00000 → 停止
    // 否则返回 cur 减去 step（沿轴向），打包成 (x,y)
}
```

> **注意**：我**没有找到正弦/余弦意义的"波浪"排布**（全库无 `sin/cos`）。
> "Wavy" 应是作者对这套「**变高惰性列表 + 动画滚动偏移**」的自称，
> 而非数学意义上的波形。列表的视觉动效来自：**变高项布局** + **`z7.e.c` 的行/字级动画**（§7）+ **`z7.e.l` 的指数衰减滚动**（§10.2）。

### 10.2 滚动缓动（`z7.e.l`）✅ **指数衰减**

```kotlin
suspend fun decayScroll(deltaPx: Float, anim: ScrollAnim) {
    var v = deltaPx / 1000f
    var t0 = withFrameNanos { it }
    while (abs(v) > 0.05f) {                    // ★ 终止阈值
        val t1 = withFrameNanos { it }
        val rate = ((t1 - t0) / 1_000_000f).coerceIn(0f, 100f)  // ★ 单帧上限 100ms
        if (rate > 0) {
            val target = (v * rate + anim.value).coerceIn(anim.lo, anim.hi)
            if (target == anim.value) break
            anim.emit(target)
            v *= 0.95f.pow(rate / 16f)          // ★★★ 指数衰减
        }
        t0 = t1
    }
    anim.finished.value = false
}
```

- 等价连续形式：`v(t) = v₀·e^(-kt)`，`k = -ln(0.95)/0.016s ≈ 3.2/s`；60fps 约 **1 秒**到位。

### 10.3 滚动状态机 `z7.B0` ✅

字段：`j7.O`锚点、`z7.o0`目标区间、`Integer/Float`索引与目标、`StateFlow`位置/进行中、`AtomicInteger`并发计数、`z7.q0/r0/i0`协程。

### 10.4 触发链

```
pos → U6.o(心跳) → U6.r.a(二分) → U6.r.d(切行) → U6.D.f11098g(StateFlow)
   → UI 订阅 → z7.e.l(delta, z7.B0) 指数衰减滚动
```

---

## 11. 常量总表（可直接照抄）

| 名称 | 值 | 出处 |
|---|---|---|
| 行内进度 | `clamp((now-lineStart)/lineDur, 0, 1)` | z7.e.c |
| 行级 smoothstep | `(3-2p)p²` | z7.e.c |
| 高光渐变软边 | ~~`240f`~~ ⚠️ 源分支 jadx 反编译失败，**勿用**（见 §5.2） | z7.e.c |
| 辉光模糊半径 | `5s + 7` → **7..12，单位 px（无密度缩放）** | z7.e.p / z0.q |
| 辉光 alpha | `0.2s + 0.42` → **0.42..0.62** | z7.e.p |
| 辉光缩放 | `0.06s + 1.06` → **1.06..1.12**（脉冲峰值放大） | z7.e.p |
| 辉光脉冲 | `sin²(πp)` | z7.e.p |
| 辉光层尺寸 | 字尺寸 **+120px**，字画在层内 **(60,60)** | z7.e.p smali |
| 辉光垂直位移 | `-((0.75s+0.75)·pulse + (0.3·行缓动+0.7·字缓动)·riseAmp)·isCurrent` | z7.e.p smali |
| 逐字 x 坐标 | `段Layout.getHorizontalPosition(i, true)` | z7.e.p smali |
| 辉光两趟绘制 | 先全部辉光层(flags 4096)，再全部实心层(flags 8192) | z7.e.c smali |
| 辉光门控（手机） | 存在 片段 2..10 字(`trim`) 且 时长 ≥ 1000ms | z7.e.e |
| 辉光门控（**本仓库**） | **单元时长 ≥ 1000ms**（本仓库单元=单字） | 适配 |
| 起伏提前量 | `180ms` | z7.e.c |
| 起伏窗口 | `180 + 字时长 + 400` (ms) | z7.e.c |
| 起伏幅度 | `(lineSmooth·0.3 + riseSmooth·0.7) · 3.5dp→px(≈10px) · isCurrent` | z7.e.c |
| 行 alpha 动画 | `260ms` + **EaseOutQuart**，名 `"LyricAlpha"`，非当前 **0.4** | z7.e.e |
| 译文动画 | `220ms` + **EaseOutQuart**，名 `"TranslationVisibility"` | z7.e.h |
| 字号缩放 | `clamp(scale, 0.8, 1.2) * 5 + base` | z7.e.e |
| 行间距 | 当前 `2.5` / 其他 `3.5`（Dp，待核） | z7.e.e |
| 滚动衰减 | `v *= 0.95^(Δt/16ms)`，阈值 `0.05`，帧上限 `100ms` | z7.e.l |
| 心跳定频 | Lyricon 开=**50ms**，否则按下一行 | z7.o |

---

## 12. 可抄伪代码（每帧）

```kotlin
@Composable
fun LyricView(lines: List<LyricLine>, nowMs: Long, ...) {
    // 1) 定位当前行（二分）
    val current = lines.indexOfLastTime { it.startMs <= nowMs }
    // 2) 滚动（指数衰减）
    LaunchedEffect(current) { decayScroll(deltaOf(current), scrollAnim) }
    // 3) 逐行渲染
    for ((i, line) in lines.withIndex()) {
        val isCur  = i == current
        val alpha  = animateFloatAsState(if (isCur) 1f else 0.4f, tween(260))  // "LyricAlpha"
        val glowOn = line.hasGlowSegment()   // 2..10 字 && ≥1000ms
        Canvas {
            val p = clamp((nowMs - line.startMs).toFloat() / max(line.endMs-line.startMs,1), 0f,1f)
            val s = (3 - 2*p) * p * p
            val frontier = computeFrontier(line, nowMs)           // §5.1
            if (isCur || glowOn) {
                val brush = gradientBrush(frontier, sung, unsung) // §5.2
                for (seg in line.segments) for ((j,ch) in seg.text.withIndex()) {
                    val dy = riseOffset(line, seg, j, nowMs, s, alpha)     // §7.1
                    val pulse = glowPulse(seg, j, nowMs)                   // §6 sin²
                    if (glowOn) drawBlurredText(ch, radius = 7f + 5f*s, alpha = (0.42f+0.2f*s)*pulse)
                    drawText(ch, brush, y = baseline + dy)
                }
            } else {
                val dy = -s * alpha
                translate(0f, dy); drawLine(line, unsung); translate(0f,-dy)
                if (alpha > 0.001f) { translate(0f,dy); drawLine(line, sung); translate(0f,-dy) }
            }
            drawTranslation(line.translation, visible = isCur, animTween(220))
        }
    }
}
```

---

## 13. 已知 / 未知（诚实边界）

| 项 | 状态 |
|---|---|
| 数据模型、二分定位、心跳 | ✅ 完整方法体 |
| 高光前沿（`frontier`，左→右） | ✅ 方法体（`h4.a.a(a,b,t,base)=(a-b)t+base` 已证） |
| **整行渐变刷几何** | ❌ 源分支 jadx 反编译失败（`fA/fA` 等 artifacts）→ **不可从当前结果还原**；§5.2 已作废 |
| **辉光全链**（半径/alpha/缩放/脉冲/位移/层尺寸/pad） | ✅ **逐指令**（dexdump smali，676 code units） |
| 辉光模糊单位 | ✅ **px**（`z0.q.f()` → `RenderEffect.createBlurEffect` 无密度缩放） |
| 逐字 x / 幅度 / 门控 / `trim` | ✅ 反编译实证 |
| 缓动常量 | ✅ **EaseOutQuart**（`t.w.f23056e`）等，`t.w.<clinit>` |
| 行 alpha / 译文动画 | ✅ 260ms / 220ms + EaseOutQuart |
| 非当前行 | ✅ **无整行位移**（§7.2 已订正） |
| 滚动指数衰减 | ✅ 完整 smali |
| WavyLazyColumn 结构 | ✅ 管线/类/滚动/偏移步进已解（§10.1） |
| "波浪"是否为波形 | ✅ 全库无 sin/cos → 非波形，是"变高惰性列表+动画偏移" |
| 辉光 `transformOrigin` 打包顺序 | 🔶 解出 `(h/2+60)<<32\|(w+60)`，与 Compose 惯例冲突；建议按字心实现 |
| `d1.K.d` 字段语义 | 🔶 判为 ascent（未证） |
| 颜色具体值 | ❗ 由上层 `z7.e.e` 传入，未反解 |
| 帧率 | ✅ 50ms（Lyricon） |

> **方法学**：jadx Java **不可信**时（出现自比较/自除 artifacts）改用 `dexdump -d` 的 smali 核对；
> 常量一律以字节码为准。本轮 `z7.e.p` 已用 smali 逐指令核对。
