# 垃圾运算审计与第一批清扫结果（2026-10-24）

三路只读审计（绘制期 / 组合期 / IO·网络·解码）+ 第一批修复与验证。
原则：只删纯浪费，不动有真实消费方的视觉成本（模糊、离屏描边等保留）。

---

## 一、审计清单（全量）

### A. 绘制期每帧重建不变对象（零视觉）

| # | 位置 | 问题 | 状态 |
|---|---|---|---|
| A1 | `DropShadow.kt:52` | `BlurMaskFilter` 每次绘制新建（应进 drawWithCache 缓存块） | ✅ 已修 |
| A2 | `ShadowImage.kt:118,193`、`Home.kt`、`LocalArtists.kt`、`OnlineArtists.kt`、`PlayLists.kt ×2`、`SearchPage.kt`、`DeveloperAvatar.kt` 共 9 处 | `createOutline()` 写在 `onDrawWithContent` 内每帧重建 | ✅ 已修（上移 cache 块） |
| A3 | `AddToPlaylistMenu.kt:866` | 弹层顶部渐隐每帧新建 `Brush.verticalGradient` | ✅ 已修 |
| A4 | `LiquidDropdown.kt:628`、`LiquidTopBarButton.kt:137` | 动画期每帧 new `Path`+`Paint`+`BlurMaskFilter` | 未做（仅动画期间） |
| A5 | `LiquidBottomTabs.kt:524` | 每帧 `Capsule().createOutline()`（底栏常驻） | 未做 |

### B. `Modifier.alpha` 套纯色/文本/细线（零视觉，全仓 40+ 处）

| 已修（本批） | 位置 |
|---|---|
| 歌单详情分割线 | `OnlinePlaylistDetail.kt` OnlineDetailDivider（trace 实证的 1169x2 残留源） |
| 本地歌曲列表分割线 ×2 | `NormalMusic.kt:274,397` |
| 设置页每行描述文字 + 行尾图标 | `settings/Item.kt` |

未动的其余 ~35 处（Discovery/DetailPageParts/各详情页头部文本等）为静态低频元素，
收益小，留待顺手改。**红线**：带 blendMode/drawWithCache 叠加的不改。

### C. 组合期重复计算（零视觉，未做）

| # | 位置 | 问题 |
|---|---|---|
| C1 | `ShadowImage.kt:76,155` 等 12 处 | `ImageRequest.Builder` 链（~15 setter + 字符串拼接）每次重组重建 |
| C2 | `PlayLists.kt:62`、`Overview.kt:66`、`AlbumInfo.kt:119` | 排序裸写在 composable body（AlbumInfo 可达数百曲） |
| C3 | `YosFloatingLightView.kt:66` | 自建独立 ImageLoader（不共享缓存/线程池）+ 无 size 原图解码再压到 96px，每首歌一次，内存峰值=原图 |
| C4 | `ShadowImage.kt:71` | `remember(dataLambda())` 键错位（正确性问题） |

### D. 轮询（行为相邻，未做）

- `YosLyricView.kt:276` 每帧（withFrameNanos）镜像播放位置（歌词页前台常驻）。
- `NowPlaying.kt:4011` 700ms binder 轮询（已有差分抑制）。
- `OnlinePlaylistDetail.kt:398` 4s 轮询 PendingPlaylistStore。

### E. 网络/IO（未做）

- `OnlinePlaylistDetail.loadTracks()` 无在飞合并：可并发两次完整歌单拉取（内部逐页 HTTP，重复=数页流量）。
- `getRankSongs/getAlbumSongs` 无缓存 + Discovery 每次回前台强制全量重拉。
- 启动路径 `scanMedia` 潜在双触发。
- `KugouApiService` 20+ 处把整个响应体按 3.5KB 分片打进 logcat（debug 构建；release 被剥）。

### 健康项（审计确认无问题，列出防止重查）

调色板管线（AlbumArtworkPalette 按 key 缓存+落盘）、HeroBakeCache 三级缓存、
搜索 250ms 防抖、FM 补货 in-flight 守卫、songUrl Mutex 合并、derivedStateOf 用法、
Gson 缓存读取路径。

---

## 二、第一批修复与验证结果

**包**：`perf/app-release-batch1.apk`，sha256 `2821af42025e7fa6c62cf8e1d2e265150fd5c77ca2cdb07c51063fe6c0eeeb67`，
基于基线 B（`1fccadfc…`），共 12 处修改（A1 + A2×9 + A3 + B×4 文件）。

**视觉**：
- 自动截图比对（主页/歌单/设置三页静态态）：差异集中在"酷狗账号"一行——同包连拍复现，
  确认为**动态内容**（VIP 状态刷新），非渲染回归；其余区域 ≤2 灰度级舍入差。
- 用户人工验收：**全部正常**（封面描边、阴影、⋮ 菜单渐隐、分隔线、设置页文字/图标）。

**trace 判据（进入歌单，各 121-123 帧）**：

| 每帧 | 基线 | 第一批 | 变化 |
|---|---|---|---|
| FillRectOp | 187.2 | 185.9 | −0.7% |
| saveLayer | 29.8 | 27.7 | −7% |
| 重录层 / 纹理 / 137×137 / Drawing | — | — | 全部持平 |

符合预期：这批修的是**每帧对象分配**（GC 压力），不是 op 数量，trace 计数本来就不该大动。

**帧时间（ABBA，完成 2 个完整配对即按用户指示停止）**：
Δp50 = −1.29 / −1.23 ms（两轮皆改善），Δmiss2 ≈ −1.6pp，Δmiss3 ≈ −1.7pp。
n=2 置信度低，方向为正，至少**确认无退化**。

**结论：第一批通过。** 当前设备安装的就是此包。

---

## 三、后续（未实施，按优先级）

1. C3（独立 ImageLoader + 原图解码）——内存收益最明确
2. C1（ImageRequest 重建 ×12）+ C2（排序裸写 ×3）
3. E 的 loadTracks 在飞合并——流量/IO 收益
4. A4/A5（动画期对象创建）、B 剩余 ~35 处静态站点
5. D 轮询改造（行为风险最高，最后做）
