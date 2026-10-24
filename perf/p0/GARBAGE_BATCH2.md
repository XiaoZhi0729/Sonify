# 垃圾运算清扫 · 第二批结果（2026-10-24）

**包**：`perf/app-release-batch2b.apk` sha256 `e44119df16af6b0a…`（batch2 出过一次回归后修成 2b，见下）
基于 batch1（`2821af42…`）。**当前设备安装此包。**

## 实际落地的修改

| 项 | 内容 | 状态 |
|---|---|---|
| ① | `YosFloatingLightView`：自建 ImageLoader → 全局单例；请求加 `.size(96/32)` 按目标尺寸解码，不再每首歌解原图 | ✅ |
| ②a | ImageRequest remember 化 ×**10 站点**：MainActivity painter（迷你条封面）、Home×2、SongComments 头像、ArtistDetail hero+行内×2、LocalArtists、OnlineArtists、SearchPage、Recognition、FullScreenStaticCover | ✅ |
| ②b | 排序 remember 化 ×3（PlayLists / Overview / AlbumInfo） | ✅ |
| ②c | ShadowImage 死代码 `shadowAlphaPx` 删除（连带键错位） | ✅ |
| ~~②d~~ | ~~ShadowImage / ShadowImageWithCache 两处 remember 化~~ | ❌ **实施后回退**，见下 |

## 过程中发现并处理的回归（重要）

**现象**：ShadowImage 两处 remember 化后，列表行封面"先正常加载、几十秒后变回占位图"
（同一区域 std 67.8 → 1.8）。播放页大封面（RAW 槽）不受影响。

**处置**：按预登记的中风险项直接回退这两处（恢复内联 Builder），其余 10 站点保留。
回退后验证：行封面早/晚 45 秒 **std 恒 67.8**，播放页封面 45 秒像素级不变。

**未解之谜（记录在案）**：remember 稳定请求实例本应更安全，实际却出现"重组后重置为占位"。
机制未查明，**ShadowImage 是全 app 复用最多的组件，在搞清前不再碰它的请求构建**。
其余 10 站点已 45 秒稳定性验证（播放页封面/迷你条），但**歌手页、搜索页头像未做长时间验证**——
交用户验收时重点看。

## 验证结果

- **视觉**：行封面稳定、播放页封面稳定（早/晚像素一致）——自动验证通过；
  四页截图与 batch1 差异集中在已知动态内容区（首页信息流、账号行 VIP 状态）。
- **内存（切歌 10 次，单轮）**：方向不一致，**不宣称收益**：
  - Graphics 增长 +11.5MB（batch1 为 +16.4MB）✓ 方向对（原图解码变小）
  - Java Heap 增长 +44MB（batch1 为 +32MB）✗
  - 起点 PSS 257.9MB vs 261.6MB，略低 ✓
  - 单轮、两次切的歌不同，噪声大；要下结论需 5 轮配对，未做（用户要求快）。
- **帧时间**：未测（本批改动不在帧路径上：请求构建在重组期、解码在 IO 线程）。

## 待用户验收

1. **歌手页头像**（本地/在线列表的小圆图）进入后停留一分钟以上，看是否消失；
2. **搜索页**歌手头像同样看一眼；
3. 播放页切几首歌，封面和背景光效正常；
4. 专辑详情页（AlbumInfo，排序 remember 化）歌曲顺序正常、滚动正常。

任何一项异常 → 报"页面+操作+现象"，对应站点单独回退（每处独立）。

## 产物

`perf/p0/gb1/`：截图（pl_early/late、player_early/late、row_zoom*）、内存数据
（mem_b1_*/mem_b2_*）、run_ab.log。中间废包 batch2.apk 已删。
