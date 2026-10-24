# 顶栏采样修复试验结果：条带裁剪无效，已回退（2026-10-24）

## 0. 一句话结论

按批准方向（缩录制范围）实现的修复**没有移动四项判据中的任何一项**，按事先约定的纪律已全部回退。
失败原因已经定位：**行是 RenderNode，画布级 clipRect 不能把它们从录制 op 流里剔除**——
裁剪只省像素，不省 op，而我们已经证明成本是逐 op 的 CPU 开销。

## 1. 做了什么（实现是完整的，视觉也是对的）

- `rememberTitleBackdrop` 增加 `recordTopBand` 参数（430dp 条带），五个详情页接入；
- 补了必要的安全机制：`LiquidDropdownLayout` 展开期间登记 `TitleBackdropRecordDemand`，
  让 backdrop 临时放开为全页录制——因为**行尾 ⋮ 菜单在列表任意位置采样同一个 backdrop**
  （`AddToPlaylistMenu.kt:235`），纯条带会让列表中下部的菜单玻璃采空。
- 视觉验证通过：同锚点行菜单 A/B 截图**逐像素一致**（差异 424 像素全部在状态栏时钟区）。
- 修复包 sha256 `e4fee2b6c2b04834d2906c68e9c164c5d064afc2f4ace2798c95f4dedd5c8d58`。

## 2. 判据结果（对照：消融 C 曾给过 fills −34% / Drawing −16% 的上限）

**配对回归**（ABBA ×5 轮 = 20 次，A=基线 `1fccadfc…`，B=修复 `e4fee2b6…`，
会话温度 37.4–38.1°C，配对设计内互相抵消）：

| 轮 | Δp50 | Δmiss2 |
|---|---|---|
| 1 | +5.14 | +9.2 |
| 2 | +0.99 | +3.2 |
| 3 | −1.54 | −10.3 |
| 4 | −0.00 | +2.7 |
| 5 | +4.91 | +10.3 |

中位数 `Δp50=+0.99ms`、`Δmiss2=+3.2pp`，范围跨零 → 帧时间无变化。

**滚动态 trace 对比**（同手势、各 86-88 帧）：

| 判据 | 基线 | 修复 | 变化 |
|---|---|---|---|
| FillRectOp/帧 | 190.9 | 191.1 | **0** |
| 137×137 采样层/帧 | 4.0 | 4.0 | **0** |
| 重录层/帧 | 11.2 | 11.3 | 0 |
| saveLayer/帧 | 14.0 | 14.0 | 0 |
| 行纹理重传/帧 | 6.0 | 6.0 | 0 |

**四项判据全部纹丝不动。**

## 3. 为什么无效（已验证的机制）

对比 137×137 采样层的子节点：基线 176 个行内容 saveLayer，修复后 172 个——**无差别**。
也就是说条带裁剪并没有阻止行内容被录进采样层。

原因：Compose 里每个列表行是独立 RenderNode。`drawContent()` 的录制是把子 RenderNode
整体作为 `drawLayer` op 发出的，**画布 clipRect 不参与 op 发射的剔除**，只在光栅化阶段生效。
而我们已证明该场景的成本是"~190 个小 op × 每个 op 的 onPrepare/onExecute CPU 开销"，
所以裁剪像素不裁 op，等于没省。

消融 C 有效是因为它彻底不录内容（op 少了 34%），不是因为它"录得少"。

## 4. 已回退

- `Title.kt`、`LiquidDropdown.kt`、五个详情页全部还原，全仓 `recordTopBand/TitleBackdropBand`
  出现次数为 0，`assembleRelease` 通过（BUILD SUCCESSFUL in 1m57s）。
- 设备已装回基线 B（`1fccadfc…`）并重新 dexopt；修复 APK 已删除。

## 5. 下一步的真实选项（都需要重新拍板，不在本次授权范围内）

条带/降采样这条路已被证伪，能真正减少 op 的只剩：

1. **按需录制**：backdrop 只在"有消费者正在采样"时录内容（顶栏可见 或 任意弹层展开）。
   消融 C 证明上限是 fills −34% / Drawing −16%；按需录制能在**入场动画期间**（顶栏未出现，
   正是 miss2 100% 的最差场景）拿到全部收益，滚动期间（顶栏可见）拿不到。
   这是行为级改动（玻璃按钮的 adaptiveLuminance 在不录制期间的采样会退化为底色），需拍板。
2. **降低录制频率**（隔帧录制）：全面减半，但模糊内容会隔帧更新，可能可见闪烁，需视觉验证。
3. **换采样实现**（fork/patch com.kyant.backdrop，或改用 Haze 全家桶）——工作量大。

行纹理重传（6/帧）维持另案，本轮未动。

## 6. 产物

- `perf/p0/ab_alpha/run_fix.log`、`ab_results.jsonl`（覆盖为本次 20 轮）
- `perf/p0/ab_alpha/trace_fixbase.pftrace` / `trace_fixfix.pftrace`、`q137.sql`
- `perf/p0/ab_alpha/{B,F}_rowmenu.png`、`{B,F}fix_*.png`：视觉验证截图
