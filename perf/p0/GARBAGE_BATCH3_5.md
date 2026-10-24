# 垃圾运算清扫 · 第三、五批结果（2026-10-24）

**最终包**：`perf/app-release-batch5.apk` sha256 `7f6e6f437f0852e6…`，**当前设备安装此包**。
包含第一、二批全部修改 + 第三批（loadTracks 在飞合并）+ 第五批（对账轮询事件化）。

## 第三批：loadTracks 在飞合并

`OnlinePlaylistDetail.kt`：`loadMutex = remember { Mutex() }`（复用仓库 `songUrlGate` 范式）。
- 静默/首拉：`tryLock` 失败即并入在跑那次（收掉本次指示状态后 return）；
- 下拉刷新：`lock()` 排队等待（用户显式操作不丢弃，指示器不会卡死）；
- 原拉取/落盘/状态逻辑逐字未动，只包了 try/finally。

**验证**：
- 我的自动检查误报"列表空"——**uiautomator 在动画期返回空 dump，本专项第三次踩坑**；
  用户实测歌曲正常显示，以下拉刷新收尾为准。
- 装回 batch2b 对照同样正常，网络因素排除。

## 第五批：PendingPlaylistStore 4s 轮询 → 事件驱动

**store 侧**（`PendingPlaylistStore.kt`）：`update()`/`delete()` 是全部变更路径的必经点
（register/retry/tryBind/flushOps/addSong/removeSong 都经它们），在这两处 `tryEmit(Change)`；
对外暴露 `changesFor(localId): Flow<Change>`（entry=null 表示删除）。

**页面侧**（`OnlinePlaylistDetail.kt`）：4s while 轮询换成 `changesFor(localId).first { … }`，
语义逐条对照原逻辑（绑定→等 ops 补发完→loadTracks→结束；删除→结束；30s/2s 的 ops
等待节奏保留，本来就是本地 MMKV 读）。

**收益**：挂起歌单详情页停留期间每 4 秒一次的 MMKV 全量读+反序列化归零；绑定事件的
响应从"最坏 4 秒后"变成即时。

**明示不做**（防止后人重查，结论已定）：
- `YosLyricView.kt:276` 每帧镜像播放位置——歌词库硬性要求 Compose 状态驱动，降频破歌词同步；
- `NowPlaying.kt:4011` 700ms 轮询——已有差分抑制，改回调风险大于收益。

## 待用户验收（当前包上做）

1. 歌单页**下拉刷新一两次**：指示器正常收起、列表不空（第三批合并逻辑的行为面）；
2. 正常进出歌单、列表完整；
3. （可选，第五批的主场景）**创建一个新歌单 → 立刻进详情加一首歌 → 等十几秒**：
   歌曲应在绑定后自动出现（原来靠 4s 轮询，现在靠事件）。

## 未做项（第四批，可选）

LiquidDropdown/LiquidTopBarButton 动画期对象创建、LiquidBottomTabs outline 缓存、
剩余 ~35 处冷门 alpha。价值低，待定。

## 现场包清单

`perf/app-release-perf.apk`(A基线)、`app-release-b.apk`(两行alpha)、`app-release-batch1.apk`、
`app-release-batch2b.apk`、`app-release-batch3.apk`、`app-release-batch5.apk`（现装）。
