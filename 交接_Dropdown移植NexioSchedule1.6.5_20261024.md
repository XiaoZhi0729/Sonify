# 交接：Dropdown 移植 NexioSchedule 1.6.5（2026-10-24，已全量回退）

## 状态

- **代码已回退**到 1.6.1 引擎（HEAD=0471caa + 在途未提交工作：评论入口开关/歌词缓存/垃圾清扫批等，均保留），编译通过，已装 onyx。
- 本日四轮 1.6.5 移植尝试全部失败已撤销。失败原因见 §3。
- 用户要求：重开会话，**照抄原版**（组件+接线都不得自创）重新移植。

---

## 1. 原型探索结论（NexioSchedule 1.6.5）

原型路径 `D:\NexioSchedule-1.6.5-1015`（KMP 重构：`:miuix` 通用组件 / `ui-shared` 业务 UI / `backdrop` 是 **fork 本地化的 kyant backdrop**）。本机另有 1.3.2/1.4.0/1.5.0/1.5.3/1.6.1 快照可直接 diff。

### 1.1 动画体系：容器变换（替代 1.6.1 的 scale+裁剪揭示）

| 项 | 1.6.1（我们现在） | 1.6.5 |
|---|---|---|
| fraction 进场 | spring(0.78/240) | spring(**0.77/220**) |
| fraction 退场 | spring(0.78/400) | spring(**0.85/650**，过冲回弹) |
| 锚点迁移 origin | 进 spring(0.78/500)/退 tween(450) | 进 spring(**0.77/420**)/退 **tween(340,(0,0,0,1))** |
| 收尾 | 三轨并行 alpha 收尾 | **串行等 origin tween 跑完再 snapTo** |
| 面板尺寸 | graphicsLayer 缩放 0.24→1 + 42dp 裁剪揭示 + 圆角/scale 反向补偿 | **宽高每轴真实 lerp（收起态尺寸→自然尺寸），圆角恒 25dp 无补偿** |
| 锚点迁移公式 | transformOrigin 插值 | 位移 `k=0.5·p·(1−s)`（s=当前宽/面板宽）叠加矩形左上角 |
| 容器材质 alpha | 独立 alphaProgress tween(120/400) | **进 f×7 / 退 f×3.6**（isEntering 显式传入，禁用 f 阈值猜测） |
| 内容 alpha | 0.2+0.8f / f>0.5→1 | 进 (f−0.22)/(0.62−0.22)、退 (f−0.30)/(0.78−0.30) |
| 内容模糊 | 8dp×(1−f) | **驼峰 6dp×(1−|2f−1|)** |
| 收起态内容淡出 | 无此机制 | K：进 3.2（本体化菜单 2.5）/ 退 1.7 |
| 阴影 | 整块填充 BlurMaskFilter(16f) + 0.78/0.99 阈值 | **环形路径（外圈 CW−内圈 CCW）** blur=12×spread 外扩=2×spread alpha=(0x1A浅/0x2E深)×3.2，随 containerAlpha 衰减 |
| 定位补偿 | 27dp/31dp | **7dp/5dp**（弹窗贴近锚点） |
| 布局余量 | 阴影 24dp | 阴影 24dp + **过冲余量 overshootRoom 14dp**（maxHeight/测量/place 三处同步扣减 chrome=38dp） |

### 1.2 新增功能（1.6.1 没有）

1. **跟手滑选** `miuix/interactive/DropdownPanelDragSelect.kt`：面板 f≥0.98 才接管；竖向行程×0.6；长宽比惩罚 0.97；slop 12dp；换项震 TextHandleMove；命中高亮 tween(150)（浅 Black6%/深 White10%）；一屏装不下（`ListPopupColumn.onFitsOnScreen`，scrollState.maxValue≤1）退化点选。**菜单项不能再挂 clickable**（与面板手势层互抢），改 `Modifier.dropdownPanelEntry(enabled){action}` + semantics。
2. **按压高光+跟手形变** `miuix/interactive/InteractiveHighlight.kt`（升级版）：按压 spring(0.5/300)；整幅 8% Plus 提亮 + 径向光晕（半径=min(自身,150dp)×1.5×radiusScale，radiusScale=1−0.6f）；拖拽形变 `computeDragTransform`（按压缩放 4dp、拖向拉伸 0.045、tanh 阻尼位移）；dragOffset 独立回弹。
3. **退出位移回弹 settleBounce**（本体化菜单）：欠阻尼 spring(**0.52/220**) 沿收回方向越过 12dp 弹回。
4. **lens 折射**：`backdrop/effects/Lens.kt`，常量 lens(8dp,24dp)，作用边缘 SDF 不随面板变。
5. **模糊量化**：blur 8→24dp 按 8 档量化（防每帧重建 RenderEffect）。
6. **触发区硬切**：展开延后 2 帧隐藏、退场完成（onDismissFinished，**禁用 fraction 判断**——退场 spring 过冲振荡）压 3 帧恢复；触发节点只置 alpha=0 不摘除（collapseSize 靠它实测）。
7. **预测性返回**：`utils/NavigationBack.kt` expect/actual（Android=androidx.navigationevent），handler 与 backProgress 必须同一 state。
8. **collapseSize/collapseContent**：弹窗收起态=触发区实测尺寸（兜底 28dp），弹窗从触发内容原位长成面板。

### 1.3 引擎文件（照抄清单）

- `miuix/.../basic/ListPopup.kt`（1432 行）：ListPopupColumn / PopupPositionProvider / ListPopupDefaults / rememberListPopupLayoutInfo / **ListPopupContent**（SubcomposeLayout 先量内容再铺玻璃画布；AnimatedPanelRectShape 每帧换引用防 outline 缓存冻结；PopupMenuLayer/PopupGlassCanvas/PopupMaterialLayer/popupPanelShadow）
- `miuix/.../layout/ListPopupLayout.kt`（430 行）：动画状态机 + 定位 layout + chrome 扣减
- `miuix/.../overlay/OverlayListPopup.kt`：popupHost=MiuixPopupUtils.PopupLayout（enableWindowDim=false、无转场）
- `miuix/.../popup/LiquidOverlayDropdownPopup.kt`：条目组包装（点击触感/选后收起）
- `ui-shared/.../basic/LiquidGlassDropdownMenu.kt`（480 行）：**本体化菜单**（按钮=收起态，42dp→200dp×自然高容器变换）
- `ui-shared/.../basic/OverlayDropdownMenu.kt`（294 行）：设置行下拉包装
- `miuix/.../internal/PopupShadow.kt` + androidMain actual（drawBlurredRingShadow/drawBlurredPathShadow）

### 1.4 宿主接线（本轮翻车主因——原版调用方怎么接的）

**本体化菜单（顶栏「更多」）= CourseScheduleApp.kt:711-914 MorePopupMenus**：
- TopBar 里只有 **42dp 占位 Spacer**，`onGloballyPositioned` 上报槽顶 `positionInRoot().y`；
- 菜单本体住 **Scaffold popupHost 全屏层**：`Box(fillMaxSize, graphicsLayer{clip=false; translationY=槽顶−24dp(阴影留白)}, offset(x=9dp), TopEnd)`，收起态按钮就画在这层；
- **点外关闭=调用方手写全屏透明 clickable Box**（垫在菜单之下）；
- 槽顶未测量 fallback：statusBarsTop−20dp 或 17dp；
- materialAlpha/scheduleMaterialAlpha 来自顶栏材质透明度。

**弹窗变体（行尾图标）= HolidaySettingsScreen.kt:1708-1806 InfoDropdown**：
- 弹窗**组合在锚点节点的直接子布局内**（36dp 图标 Box 里）——ListPopupLayout 的零尺寸 Spacer 读 `parentLayoutCoordinates` 得锚点 bounds，**不传坐标**；
- collapseSize=`onSizeChanged` 实测图标尺寸；触发区硬切（2帧/3帧）；
- 锚点微调用 expandDirectionOffsetProvider 包一层 base provider。

**设置行下拉 = ui-shared/OverlayDropdownMenu.kt**：弹窗组合在 endActions 的触发 Row 内，collapseSize=「选中文字+箭头」Row 实测，collapseExtra 默认 DpSize(14,10)。

**弹窗宿主 = MiuixPopupUtils.PopupLayout**：不渲染只注册 PopupState(zIndex++)，MiuixPopupHost 在 Scaffold popup 槽（subcompose 最先测=最高 z）渲染；无 dim 模式垫全屏事件消费层；点外关闭在 ListPopupLayout 内部（全屏 Box detectTapGestures）。

### 1.5 移植边界（我们与原版的依赖差异）

- 我们用 backdrop **1.0.5 jar**，原版是 fork 模块。1.0.5：`drawBackdrop` 的 highlight/shadow 是 **provider 形参 `{ null }`** 且 **backdrop 非空**；无 `com.kyant.backdrop.edgelight` 包（原版 rememberDefaultEdgeLight=浅 White0.6/0.24dp 深 White0.2/0.34dp blur0.4dp）；无 `isRenderEffectSupported`；lens 1.0.5 已有（签名同）。
- 原版 `com.kyant.capsule.ContinuousRoundedRectangle`（kyant shapes 1.2.1）→ 我们可用 `YosRoundedCornerShape`（同为平滑圆角 CornerBasedShape，lens 可用）。
- 原版 InteractiveHighlight 绘制期读 AppThemeSnapshot → 我们主题是 @Composable，构造期传 darkTheme。
- 我们返回桥是 HouseBackDispatcherOwner（无手势进度通道）→ 预测性返回只能降级 BackHandler（backProgress 恒 null，跟手分支不触发）。

---

## 2. 我们的现状（回退后）

- 引擎=`app/.../ui/widgets/basic/LiquidDropdown.kt`（**1.6.1**：scale 0.24+裁剪揭示+反向补偿圆角；三轨 fraction/origin/alphaProgress；阴影阈值 0.78/0.99；定位 27/31dp）。
- 调用点（回退后全部恢复 1.6.1 用法：TitleBarIcon+followState 联动+anchorBounds 窗口坐标）：
  - 顶栏「更多」×4：HomeEditList / OnlineAlbumDetail / OnlinePlaylistDetail / NormalMusic（SongsSortMenu）
  - 弹窗变体：设置页 Item.kt 行尾下拉、MusicQualityIndicator 音质胶囊、AddToPlaylistMenu 歌曲行⋮、NowPlayingMoreMenu（一级+StackBelowAnchor 二级堆叠）、NowPlaying 队列排序菜单（M3 DropdownMenu 残留未迁）
- 顶栏槽=48dp 高（Title.kt rightBarIcon Row）；页面级弹层宿主=`LocalTitleOverlayHost`（Title 页根 Box 顶层，TitleOverlayHostSlot 渲染，slots=mutableStateMapOf HashMap 快照**无插入序保证**，多面板需 zIndex）；无宿主时退 androidx Popup 独立窗口。
- onyx=25053RT47C，**API 37**（无 API 门槛问题）；adb input tap 被拒（INJECT_EVENTS），只能装机+启动自检，交互验收靠用户手测。

## 3. 本日四轮失败记录（勿重蹈）

1. 第一轮：自创适配 1.6.5（改结构塞 48dp 槽、f>=0.5 猜进退场档）→ 按钮消失/透明+白圆。教训：**禁止自创结构、禁止 fraction 阈值猜 isEntering**。
2. 第二轮：组件逐字照抄但**接线自创**（内联 48dp 槽+wrapContentSize 溢出=按钮偏左 24dp；漏点外 scrim=关不掉；anchorBoundsOverride 传窗口坐标）→ 仍灾难。教训：**接线（§1.4）必须一起照抄**。
3. 第三轮：接线照抄（TitleBarGlassDropdownHost 全屏层+scrim；弹窗移进锚点子树；ListPopupLayout 完全逐字）→ 歌曲行⋮菜单**卡在收起态 f=0 不展开**（圆形+透明只剩图标+阴影），动画不跑。**根因未查明**——排除了 API 门槛（37）；怀疑方向：①弹窗组合在 LazyColumn 行内时 ListPopupLayout 的 LaunchedEffect 动画被行重组/回收打断；②DropdownPopupHost 宿主桥（host.set SideEffect 注册链）与动画作用域的交互；③1.0.5 jar drawBackdrop 在该场景抛错被吞导致整层不画。**下会话第一件事：在真机 logcat 复现抓异常 + 打 log 验证 fraction 是否动画**。
4. 所有引擎文件曾按原包名移植（top.yukonga.miuix.kmp.basic/layout/overlay/interactive/internal + com.kyant.backdrop.edgelight/capsule 桥），已全部删除；如需重做，移植清单与边界 diff 见 §1.3/§1.5，本次已验证过**编译可通过**的 shim 集：MiuixTheme(surfaceContainer=FAFAFA/242424)、NavigationBack(BackHandler 降级)、LocalPredictiveBackEnabled 恒 true、SinOutEasing、LocalChromeLensEnabled 恒 true、edgelight(ADD 描边等效)、isRenderEffectSupported(=API33+)、capsule(=YosRoundedCornerShape)。

## 4. 建议路线（下会话）

1. 先修 §3-3 的 f=0 卡死（logcat+打点定位），不要盲改；
2. 定位后按 §1.3 清单重新移植（文件内容本轮已验证可编译），接线严格按 §1.4；
3. 阴影常量 LiquidGlassDropdownMenu 与 ListPopup 两处必须同值（原版红线）；
4. `dragSelectEnabled`（混自定义行必关）与二级堆叠 StackBelowAnchor 是**本项目自有语义**（原版无对应物），保留在适配层。
