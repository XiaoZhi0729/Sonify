# Flamingo (yos.music.player v1.0.0) 播放页歌词系统 — 完整复现报告

> 目标 APK: 手机 `yos.music.player` / versionName `1.0.0-2608310056` / versionCode 1
> SHA-256: `cc8b72e0f960472c38c8835a5e3987cb75e61d400bbd48d71c4a257de66a1643`
> 分析引擎: JADX headless 0.7.1 (REA provider `jadx`)
> 说明: 该 APK 经 R8 混淆，UI 层类名被重写为 `z7.*` / `U6.*` / `Z6.*` 等。
> 下文所有类/方法/常量均来自反编译方法体；**推导部分已显式标注**。

---

## 0. 结论速览

| 问题 | 结论 |
|---|---|
| 支持高光吗？ | **支持，逐字(karaoke)级**，非整行变色 |
| 高光实现方式 | 逐字**双层绘制**：底层用 `RenderEffect` 模糊做**辉光**，上层实心；进度在相邻字之间按比例交叉 |
| 辉光粒子效果 | 逐字 `sin²(π·progress)` **脉冲**；模糊半径 7→12、透明度 0.42→0.62、缩放 1.06→1.12 |
| 滚动动画 | **自定义指数衰减缓动**（`pos *= 0.95^(Δt/16ms)`），非 spring/tween，约 1s 内到位 |

---

## 1. 数据模型（`Z6` 包）— 复现的地基

### 1.1 `Z6.D` — 歌词「行」

```java
class Z6.D implements Parcelable {
    String f13031l;     // 该行文本（原文）
    long   f13032m;     // 行起始时间 (ms)
    long   f13033n;     // 行结束时间 (ms)
    String f13034o;     // 备用文本（无逐字时使用；见 d() 分支）
    List   f13035p;     // ★ 逐字/逐词片段 List<Z6.x>（原文）
    String f13036q;     // 译文文本
    List   f13037r;     // 逐字/逐词片段（译文，推测）
}
```

- `f()` → `boolean`：该行**是否为逐字模式**（true=有逐字片段，false=整行）
- `b()` → 返回 `f13033n - f13032m`（行时长）
- `c()` → 返回 `f13035p`

### 1.2 `Z6.x` — 歌词「字/词片段」（高光的最小单位）

```java
class Z6.x implements Parcelable {
    long    f13092l;    // ★ 片段起始时间 (ms)
    long    f13093m;    // ★ 片段结束时间 (ms)
    String  f13094n;    // 片段文本
    boolean f13095o;    // 是否需要在渲染时补一个空格
}
```

**复现要点**：高光的时间轴精度 = `Z6.x` 粒度。生成方法是关键（见 §3）。

### 1.3 `Z6.E` / `Z6.b` / `Z6.c` — 相关辅助类型（同包，未展开）

---

## 2. 时间轴驱动（`U6` 包）— 「现在唱到哪」

### 2.1 `U6.D` — 歌词全局状态总线（Store）

37 个字段，绝大多数是 `kotlinx.coroutines.flow` 的 `StateFlow`（混淆名 `e0.h0` / `e0.e0`）：

| 字段 | 类型 | 作用（据调用点推断） |
|---|---|---|
| `f11097f` | `StateFlow<List<Z6.D>>` | **完整歌词行列表** |
| `f11098g` | `MutableStateFlow<Integer>` | **当前行索引** |
| `f11090J` / `f11091K` | `String` | 当前行原文 / 译文（供外部 API 推送） |
| `f11081A` | `Uri` | 专辑图/封面 |
| 其余 ~10 个 `h0/e0` | StateFlow | 翻译开关、有无翻译、时间轴偏移、可见性等 |

> **复现要点**：UI **唯一**应订阅的状态源就是这里的 Flow。当前行索引 `f11098g` 一旦变化 → UI 重绘 + 滚动。

### 2.2 `U6.r` — 歌词状态机（当前行的解析）

17 个字段，含 `f11384g:int`（当前行索引）、`f11385h/i`（当前行原文/译文）、`f11388l/m/n:LongArray×3`、多个协程 `Job`。

#### `U6.r.b(Z6.D d, long pos, int idx, int last)` — **判定「pos 是否落在该行/段内」**
```java
static boolean b(Z6.D d8, long j8, int i8, int i9) {
    long start = d8.f13032m, end = d8.f13033n;
    if (start != end) return j8 > start + 1 && j8 < end;   // 有明确起止
    return i8 == i9 && !d8.f() && j8 > d8.f13032m;         // 兜底：行号相等且非逐字
}
```
- `+1` 是边界防抖。

#### `U6.r.a(U6.r r, long pos)` — **二分查找当前行**（每帧调用）
```java
static void a(r rVar, long j8) {
    List<Z6.D> list = D.f11097f.getValue();            // 行列表
    if (list.isEmpty()) { rVar.d(-2); return; }        // -2 = 无歌词
    int cur = rVar.f11384g;
    if (cur < 0 || cur >= size || !b(list.get(cur), j8, cur, lastIndex)) {
        // ★ 二分查找：找最后一个 start <= pos 的行
        int lo = 0, hi = last, best = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (list.get(mid).f13032m <= j8) { lo = mid + 1; best = mid; }
            else                             { hi = mid - 1; }
        }
        idx = (best == -1 || !b(list.get(best), j8, best, last)) ? -1 : best;
    } else idx = cur;
    if (idx == -1 || idx == cur) return;               // 没变 → 不动（性能优化）
    rVar.d(idx);
}
```

#### `U6.r.d(int idx)` — **切换当前行**（状态变更 + 对外推送）
```java
void d(int i8) {
    if (i8 == this.f11384g) return;
    this.f11384g = i8;
    D.f11098g.k(i8);                                   // ★ 更新 StateFlow → UI 响应
    // 以下仅当「对外歌词 API」开启时执行（状态栏/车机推送）：
    if (!LyricGetterAPIEnabled && !SuperLyricAPIEnabled
        && !TickerLyricEnabled && !MediaSessionLyricEnabled) return;
    ... 拼接当前行原文+译文，起协程推送给 cn.lyric.getter / com.hchen.superlyric
}
```
- 使用了 `SystemClock.elapsedRealtime()` + `AtomicLong` 做 **500ms 节流**的调试日志。
- `-1` = 空行，`-2` = 无歌词。

#### `U6.r.c()` — stop/清空（取消协程、`d(-1)`、置空所有字段）

### 2.3 `U6.o.invokeSuspend` — **每帧心跳循环**（歌词同步的节拍器）

```java
while (isActive) {
    if (!player.isPlaying()) { player.awaitEvent(c); continue; }
    long pos = player.position();                       // 当前播放位置
    int  off = SettingsLibrary.LyricTimingOffsetMs;     // ★ 用户「歌词延迟」设置
    pos = applyOffset(pos, off);
    boolean lyricon = SettingsLibrary.getLyriconEnabled();  // ★「歌词增强」开关
    U6.r.a(rVar, pos);                                  // ← 算当前行
    // 找下一个有时间的行
    long next = null;
    for (Z6.D d : U6.D.f11097f.getValue())
        if (d.f13032m + 1 >= pos) { next = d.f13032m; break; }
    // ★ 节拍
    long delay = lyricon ? 50 : (next + 2 - pos);       // 增强=50ms定频，否则精确到下一行
    delay(delay);
}
```
- **帧率**：Lyricon 开启时 **50ms/帧(20fps)**；否则按「下一行开始」唤醒（省电）。
- 异常日志 tag：`"lyric.timeline"`，消息 `"Lyric index resolution failed: "`。

---

## 3. 逐字时间轴的生成（`z7.e.o`）★ 复现关键

**这一步决定了「高光是否逐字」以及「时间怎么分」。**

```java
static List o(List<b0> segments, float lineWidth, boolean z, TextStyle style, Color color, int i) {
    for (b0 seg : segments) {
        Z6.x x = seg.f26342a;
        long dur   = x.f13093m - x.f13092l;         // 片段时长
        String txt = x.f13094n;

        // ★ 拆分为「单元」：
        //   - 含空格 → 按 " " 分词（词粒度）
        //   - 不含空格 → 逐字符拆（字粒度）
        List<String> units = txt.contains(" ")
            ? txt.split(" ", ...)                     // 词
            : perChar(txt);                           // 字

        long t = x.f13092l;
        int  n = txt.length();
        for (String u : units) {
            if (measuredWidth(u) > lineWidth) {       // 单元比行宽还宽 → 再逐字
                for (char ch : u.chars())
                    emit(new Z6.x(t, t += dur/n, String.valueOf(ch), ...), textLayout(ch));
            } else {
                emit(new Z6.x(t, t + (u.length()*dur)/n, u, u.equals(" ")), textLayout(u));
                t += u.length()*dur/n;
            }
        }
    }
    // 之后：用 BreakIterator.getLineInstance(Locale.ROOT) 按行边界换行
}
```

**关键推论（标注：推断，基于方法体）**：
- 若歌词源是**普通 LRC（只有行时间）** → 该行总时长被**按字数均分**，每个字得到 `dur/n`。
- 若歌词源是**逐字歌词（QRC/KRC 等，`f13093m - f13092l` 已是字级）** → 保留原始时间（走 `seg.f26344c <= lineWidth` 分支）。
- 空格处理：`Z6.x.f13095o = (u == " ")`，渲染时追加一个空格宽度。

---

## 4. 逐字高光 / 辉光渲染（`z7.e`）★ 复现核心

`z7.e` 是**播放页歌词的 Compose 渲染类**（21 个方法），混淆包 `z7`。

### 4.1 `z7.e.q` — **单字高光绘制**（基础逐字）

```java
static void q(DrawScope scope, TextStyle style, long color, float f8, float f9) {
    float whole = (float) Math.floor(f9);      // f9 = 「已高亮字数」浮点值，如 3.4
    float frac  = f9 - whole;                  // 小数部分 = 当前字的比例
    if (frac <= 0.001f) {
        drawText(scope, style, color, pack(f8, whole), 0.0f, ...);
    } else {
        drawText(scope, style, color, pack(f8, whole),     1.0f - frac, ...);  // 前一字剩余
        drawText(scope, style, color, pack(f8, whole + 1f), frac,       ...);  // 后一字已亮部分
    }
}
```
- `f9` 由「当前播放位置映射到字数」得到（见 §4.4 映射）。
- `pack(a,b)` = 把两个 float 位打包进一个 `long`（Compose 参数打包惯例）。

### 4.2 `z7.e.p` — **逐字辉光 + 脉冲（Glow）** ★★

```java
static void p(DrawScope scope, b0 b0Var, long t, float f8, Color baseColor,
              long hlColor, float f9, float f10, float f11, float f12,
              List<K> layouts, List<a0> caches, int cacheOff, int flags) {

    // ── ① 行级时间曲线（基于 f8，单位 ms）──
    float fT  = clamp((f8 - 1000f) / 2000f, 0f, 1f);   // 1000→3000ms 从 0 到 1
    float f14 = (3f - 2f*fT) * fT * fT;                // ★ smoothstep
    float f15 = f14 * 0.75f  + 0.75f;                   // 0.75 → 1.50
    float f16 = 0.060000062f*f14 + 1.06f;               // ★ 缩放 1.06 → 1.12
    float f17 = 5.0f * f14   + 7.0f;                    // ★ 辉光半径 7 → 12
    float f18 = f14 * 0.20000002f + 0.42f;              // ★ 透明度 0.42 → 0.62

    String str = b0Var.f26342a.f13094n;
    float  f19 = f8 * 0.68f;                            // 单字时间基准（全局字速）
    int    len = str.length();
    float  f20 = len > 1 ? 1.0f/(len-1) : 0.5f;         // 行内字进度步长
    Z6.x   x   = b0Var.f26342a;
    float  f21 = (x.f13093m - f19) - x.f13092l;

    int i13 = 0;
    for (int i12 = 0; i12 < str.length(); i12++) {
        char ch = str.charAt(i12);
        int  idx = i13 + 1;

        // ── ② 每个字的独立进度 ──
        float fT2 = clamp((t - ((long)((i13*f20)*f21 + x.f13092l))) / f19, 0f, 1f);
        float f22 = (3f - 2f*fT2) * fT2 * fT2;          // 字级 smoothstep
        float fSin = (float) Math.sin(fT2 * Math.PI);   // ★★ 脉冲
        float f24  = fSin * fSin;                        // ★★ sin²(π·progress)，峰值在中点
        float f25  = -( (f15*f24) + ((0.3f*f11 + f22*0.7f)*f9) ) * f10;  // 垂直位移

        K layout = layouts.get(i13);
        if (layout != null) {
            float fE = widthOf(layout, i13);
            float f26 = f12 - layout.f15440d;            // 基线/居中偏移
            float fA = lerp(f16, 1.0f, f24, 1.0f);       // 缩放（脉冲时放大）

            a0 cache = caches.get(cacheOff + i13);
            if (cache == null || f24 <= 0.001f) {
                if (glowEnabled) q(scope, layout, hlColor, fE, f26 + f25);
            } else {
                // ── ③ 辉光层缓存（BlurEffect）──
                if (cache invalid) {
                    C0.c glow = cache.f26288a;
                    glow.j(1);
                    glow.l(new z0.q(f17, f17));           // ★★★ RenderEffect 半径 f17
                    glow.i(false);
                    scope.record(radius, glow, dr -> drawText(dr, layout, hlColor, ...));
                    C0.c solid = cache.f26289b;
                    solid.j(1); solid.l(null); solid.i(false);
                    scope.record(radius, solid, dr -> drawText(dr, layout, hlColor, ...));
                }
                // ── ④ 两层绘制 ──
                C0.c glow = cache.f26288a;    // 模糊层（辉光）
                ...
                glow.h(f18 * f24);            // ★ 辉光强度 = f18 · sin²  （脉冲时最强）
                C0.c solid = cache.f26289b;   // 实心层
                solid.h(1.0f);
                if (glowEnabled) drawLayer(scope, glow);
                if (solidEnabled) drawLayer(scope, solid);
            }
        }
        i13 = idx;
    }
}
```

**辉光配方（实测常量，直接可用）**：

| 参数 | 公式 | 范围 |
|---|---|---|
| 时间曲线 | `fT = clamp((t_ms - 1000)/2000, 0, 1)` | — |
| 平滑 | `s = (3 - 2·fT)·fT²` (smoothstep) | 0→1 |
| 模糊半径 | `R = 5·s + 7` | **7 → 12 px** |
| 辉光透明度 | `A = 0.2·s + 0.42` | **0.42 → 0.62** |
| 缩放 | `S = 0.06·s + 1.06` | **1.06 → 1.12** |
| 字脉冲 | `pulse = sin²(π·progress_char)` | 0 → 1 → 0 |
| 垂直位移 | `dy = -( (0.75s+0.75)·pulse + ...)·amp` | 脉冲时上浮 |
| 辉光色 | 高亮色 `hlColor`（传入参数 `j9`） | — |

**辉光的物理本质**：`z0.q(x,y).f()` → **`android.graphics.RenderEffect`**（GPU 后处理模糊）。

```java
class z0.q {                       // BlurEffect 参数
    float f25402b, f25403c;        // x/y 半径
    RenderEffect f();              // ← 生成 RenderEffect.createBlurEffect(rx, ry, ...)
}
```

**绘制顺序**：每字画 **两层** —— ① 带半径 `R` 的模糊层（alpha = `A·pulse`）= **辉光**；② 实心层（alpha=1）= **本体**。

### 4.3 `z7.e.s` — **逐字可见性裁剪**
```java
static boolean s(Color c, float[] widths, float x0, int[] lineOf, float w, int viewW, int idx) {
    int line = c.get(idx);
    float x = (idx 越界 ? 0 : widths[idx]) + x0;    // 逐字宽度前缀和
    int l = lineOf[idx];
    if (l != -1) w = l;
    return x < viewW && x + w > 0;                  // 字区间与视口相交
}
```
- `widths[]` = **逐字宽度数组**；`lineOf[]` = 每个字的行归属。
- **复现要点**：需要先把每个字测量宽度并累加，用于裁剪与命中测试。

### 4.4 `z7.e.m` — 把片段转成绘制单元（含墨迹边界）
```java
static b0 m(Z6.x x, TextStyle layout) {
    float left=+inf, right=-inf;
    for (int i = 0; i < charCount; i++) {
        y0.c box = layout.b(i);                    // 第 i 字的边界框
        left  = min(left,  box.f25058a);
        right = max(right, box.f25060c);
    }
    return new b0(x, layout, (int)(...>>32), (int)(...&0xFFFFFFFF), left, right);
}
```
- `y0.c` = 单字符边界框（4 float：left/top/right/bottom）。
- `b0` = 渲染单元：`{Z6.x 片段, TextStyle 布局, 上行高, 下行高, 墨迹左, 墨迹右}`。

### 4.5 `z7.e.n` — 逐字按行宽换行
累加片段宽度，超过行宽 `f8` 就另起一组 `W(width, units)`。用途：**多行歌词的分行**。

### 4.6 `z7.e.d` — 逐字绘制入口（`DrawScope + z7.p + List + long`）
每个字调用 `q()` 或 `p()`（依是否启用辉光/脉冲）。

---

## 4.7 `z7.e.e` — 主 composable（组装 + 实测常量）★

把行渲染 `c()`、翻译 `h()` 组装起来，并包含**若干可直接复现的常量**：

```java
static void e(boolean isCurrent, String text, List<Z6.x> segments, boolean z9,
              boolean z10, boolean curLineFlag, boolean z12, ...) {

    // ① 行高（当前行更紧凑）
    float lineHeight = isCurrent ? 2.5f : 3.5f;      // ⭐ 传给 p1.f

    // ② 非当前行透明度 = 0.4，且用动画过渡（名为 "LyricAlpha"）
    float alpha = 1.0f;
    if (!curLineFlag) alpha = (z12) ? 0.4f : 1.0f;
    ...

    // ③ ★ 逐字高光启用条件（实测）
    boolean enableKaraoke = false;
    if (curLineFlag && !z10 && !segments.isEmpty()) {
        for (Z6.x x : segments) {
            int len = x.f13094n.length();
            if (len < 2 || len >= 11) continue;       // 片段长度 ∈ [2, 10]
            if (x.f13093m - x.f13092l >= 1000) {      // 且时长 ≥ 1000ms
                enableKaraoke = true; break;
            }
        }
    }

    // ④ 高亮文本样式：原样式色 + 字号按用户缩放放大
    TextStyle hl = o8.copy(color = j10,
        fontSize = clamp(SettingsLibrary.LyricFontScale, 0.8f, 1.2f) * 5 + f9);
    ...
}
```

**实测常量汇总**：

| 常量 | 值 | 含义 |
|---|---|---|
| 当前行行高 | `2.5f` | 紧凑 |
| 非当前行行高 | `3.5f` | 宽松 |
| 非当前行 alpha | `0.4f` | 变暗 |
| alpha 过渡 | 动画名 `"LyricAlpha"` | 平滑切换 |
| 逐字高光启用 | 片段长 ∈[2,10] 且时长 ≥1000ms | 太短/太长的词不做逐字 |
| 字号缩放 | `clamp(userFontScale, 0.8, 1.2) * 5 + 基准字号` | 用户可调 |
| 高亮色 | 传入参数 `j10` | 与普通色分开 |

---

## 5. 滚动动画（`z7.e.l` + `z7.B0`）★ 复现核心

### 5.1 `z7.e.l` — **自定义指数衰减缓动**（非 Compose 内置）

```java
static Object l(float delta, z7.B0 anim, ..., Continuation c) {
    float v = delta / 1000f;                          // 归一化剩余量
    long t0 = withFrameNanos();                       // 取当前帧时间
    float cur = v;

    while (Math.abs(cur) > 0.05f) {                   // ★ 终止阈值 0.05
        long t1 = withFrameNanos();                   // 等下一帧
        float dt   = (t1 - t0) / 1_000_000f;          // 帧间隔 ms
        float rate = clamp(dt, 0f, 100f);             // ★ 单帧上限 100ms（防掉帧跳变）

        if (rate > 0) {
            // 目标区间 [lo, hi]（来自 anim.f25721b 的约束）
            float val    = animValue();
            float target = clamp(cur * rate + val, lo, hi);
            if (target == val) break;                 // 到界即停
            emit(target);                              // 发布新滚动位置
            cur *= (float) Math.pow(0.95, rate / 16.0);  // ★★★ 指数衰减
        }
        t0 = t1;
    }
    anim.f25728i.setValue(false);                      // 标记动画结束
}
```

**缓动的数学本质**：
- 每帧剩余量乘以 `0.95^(Δt/16ms)`。
- 60fps（Δt≈16.7ms）时：每帧 ×0.948，**约 1 秒衰减到 4%**，再几帧触及 0.05 阈值停止。
- 等价连续形式：`v(t) = v₀ · e^(-k·t)`，`k = -ln(0.95)/0.016s ≈ 3.2 /s`。
- **这是标准 `ExponentialDecay`（指数衰减 / ease-out），非弹跳、非 spring、非线性。**

### 5.2 `z7.B0` — 滚动动画状态机（15 字段）

| 字段 | 类型 | 作用 |
|---|---|---|
| `f25720a` | `j7.O` | 目标/锚点 |
| `f25721b` | `z7.o0` | 滚动目标区间（提供 `[lo,hi]` 约束） |
| `f25722c/d/f` | int/Integer | 索引类参数 |
| `f25724e` | Float | 目标位置 |
| `f25726g` | float | 当前偏移 |
| `f25727h` / `f25728i` | `StateFlow` | 滚动位置 / 是否滚动中 |
| `f25729j` / `k` | boolean | 标志 |
| `f25730l` | `AtomicInteger` | **并发动画计数** |
| `f25731m/n/o` | `z7.q0/r0/i0` | 辅助 |

- `a(int,int,z7.D1)` = 启动一次滚动；`b()` = 取消回调；`c()` = 当前偏移。

### 5.3 触发链

```
播放进度 pos
  → U6.o.invokeSuspend  (每帧，50ms 或按下一行)
     → U6.r.a(pos)      二分找到当前行
        → U6.r.d(idx)   当前行变更 → U6.D.f11098g 发值
           → UI 订阅 StateFlow
              → z7.e.l(delta, z7.B0)  启动指数衰减滚动
```

---

## 6. 复现清单（可直接落地的规格）

### 6.1 数据结构
```kotlin
data class LyricLine(
    val text: String, val startMs: Long, val endMs: Long,
    val segments: List<LyricSegment>,   // 逐字/词
    val translation: String?, val trSegments: List<LyricSegment>?,
)
data class LyricSegment(val startMs: Long, val endMs: Long, val text: String, val trailingSpace: Boolean)
```

### 6.2 逐字时间分配（§3）
- 普通 LRC：`unitDur = (lineEnd - lineStart) / charCount`，按字符均分。
- 逐字歌词：直接用原始字级时间。
- 拆分规则：含空格→按词；否则→按字；超行宽的单单元→再逐字。

### 6.3 高光判定（§2.2 / §4.1）
- 每帧用 `pos` **二分查找** `last(line.start <= pos)`。
- 单字高亮比例：`highlightedChars = f(pos)`；在 `floor` 与 `floor+1` 两字间交叉。

### 6.4 辉光参数（§4.2，直接照搬）
```kotlin
val s = smoothstep(((tMs - 1000f) / 2000f).coerceIn(0f, 1f))   // (3-2x)x²
val blurRadius   = 5f * s + 7f        // 7..12 px  → RenderEffect.createBlurEffect
val glowAlpha    = 0.2f * s + 0.42f   // 0.42..0.62
val glowScale    = 0.06f * s + 1.06f  // 1.06..1.12
val pulse        = sin(PI * charProgress).let { it * it }       // 每字脉冲
// 每字两层：blur层 alpha = glowAlpha * pulse ；实心层 alpha = 1
// 垂直位移：dy = -((0.75*s+0.75)*pulse + (0.3f*f11 + (smoothstep2)*0.7f)*f9) * f10

// ── 启用条件（z7.e.e 实测）──
val enableKaraoke = isCurrentLine && segments.any {
    it.text.length in 2..10 && (it.endMs - it.startMs) >= 1000
}
// 行级样式（z7.e.e 实测）
val lineHeight = if (isCurrentLine) 2.5f else 3.5f
val lineAlpha  = if (isCurrentLine) 1.0f else 0.4f   // 用动画过渡，名 "LyricAlpha"
```

> ✅ 所有辉光公式常量（7/12、0.42/0.62、1.06/1.12、1000/2000、2.5/3.5、0.4）为**方法体实测**。
> 🔶 `f9/f10/f11`（脉冲垂直位移的幅度/系数）来自 `z7.e.e` 的调用实参，**其数值未逐位反解**；
> 复现时可先用 `dy ≈ -8f * pulse`（视觉等价），再按真机调校。

### 6.5 滚动缓动（§5.1，直接照搬）
```kotlin
suspend fun decayScroll(deltaPx: Float, anim: ScrollAnim) {
    var v = deltaPx / 1000f
    var t0 = withFrameNanos { it }
    while (abs(v) > 0.05f) {
        val t1 = withFrameNanos { it }
        val rate = ((t1 - t0) / 1_000_000f).coerceIn(0f, 100f)
        if (rate > 0) {
            val target = (v * rate + anim.value).coerceIn(anim.lo, anim.hi)
            if (target == anim.value) break
            anim.emit(target)
            v *= 0.95f.pow(rate / 16f)   // ← 核心指数衰减
        }
        t0 = t1
    }
    anim.finished.value = false
}
```

---

## 7. 已知 / 未定 边界（诚实说明）

| 项 | 状态 |
|---|---|
| `Z6.D/.x` 数据模型 | ✅ 实测字段与类型 |
| 二分定位 `U6.r.a` | ✅ 完整方法体 |
| 心跳 `U6.o` | ✅ smali 完整 |
| 逐字时间分配 `z7.e.o` | ✅ 主体；**拆分细节为推断** |
| 单字高光 `z7.e.q` | ✅ 完整方法体 |
| 辉光 `z7.e.p` + `z0.q` | ✅ 方法体 + `RenderEffect` 实证；**`f9/f10/f11` 语义未全解** |
| 主 composable `z7.e.e` | ✅ 行高 2.5/3.5、alpha 0.4、逐字启用条件、字号缩放 |
| 滚动缓动 `z7.e.l` | ✅ 完整 smali，衰减公式确定 |
| 滚动状态机 `z7.B0` | ✅ 字段；🔶 部分字段语义推断 |
| 颜色来源（高光色 vs 普通色） | ✅ 高光色=`z7.e.e` 参数 `j10`，普通色=`o8` 样式色 |
| 翻译渲染 `z7.e.h` | ✅ 存在独立方法；🔶 未展开 |
| 帧率 50ms | ✅ 来自 `U6.o` |
| 设置项 | ✅ `LyricTimingOffsetMs`、`getLyriconEnabled`、`getLyricFontScale`、4 个对外 API 开关 |

---

## 8. 关键类速查

| 混淆名 | 角色 |
|---|---|
| `Z6.D` | 歌词行 |
| `Z6.x` | 逐字/词片段 |
| `U6.D` | 歌词状态 Store（StateFlow 总线） |
| `U6.r` | 歌词状态机（定位/切行） |
| `U6.o` | 每帧心跳协程 |
| `z7.e` | 歌词 Compose 渲染（21 方法） |
| `z7.B0` | 滚动动画状态机 |
| `z7.b0` / `z7.p` | 渲染单元 / 绘制缓存 |
| `z0.q` | BlurEffect（`RenderEffect`） |
| `y0.c` | 单字符边界框 |
| `cn.lyric.getter.*` / `com.hchen.superlyricapi.*` | 对外歌词推送 SDK |

---

*证据：所有 `✅` 项均有 REA `inspect_android_method` 返回的方法体支撑，可回溯 evidence_id。*
