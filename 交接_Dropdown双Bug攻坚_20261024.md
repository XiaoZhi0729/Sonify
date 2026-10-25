# 交接：Dropdown 1.6.5 移植·双 Bug 攻坚（2026-10-24 21:30）

## 现状

- 引擎已全量落地（第五轮），编译通过，包已装 onyx（21:07 构建）。
- **两个 bug 均未修复**，用户实测否决：
  1. 主页右上角按钮收起态圆形遮罩在正上/正下/正左/正右鼓三角形；
  2. 歌曲行⋮ / 播放页「更多」/ 播放页音质胶囊的弹窗**没有玻璃背景**（空白透明）。
- 中途修过两版都被否（详见 §3 勿重蹈）。**另外两个会崩的点已修**：lens 只认 CornerBasedShape（已改）；backdrop 1.0.5 无内建 clip（已加显式 clip+实色兜底）——但用户反馈问题 2 依旧，说明问题 2 根因还没找对。

## 文件地图（引擎全部在 `app/src/main/java/yos/music/player/ui/widgets/dropdown/`）

| 文件 | 内容 |
|---|---|
| ListPopup.kt | 弹窗引擎（逐字移植 1.6.5）：ListPopupContent/PopupMenuLayer/**PopupGlassCanvas(:1244)**/**PopupMaterialLayer(:1320，玻璃层)**/AnimatedPanelRectShape/PopupPanelStyle |
| ListPopupLayout.kt | 动画状态机（fraction/origin/dim 三个 Animatable + LaunchedEffect(show) 在**调用方作用域**）+ 定位 layout |
| LiquidDropdownLayout.kt | 入口：DropdownPopupHost（宿主桥）+ 三个 positionProvider + LiquidOverlayDropdownPopup |
| LiquidGlassDropdownMenu.kt | 顶栏本体化菜单（按钮=收起态，容器变换）；:351 effects 含 lens |
| TitleBarGlassMenu.kt | 顶栏接线：42dp 占位槽 + 全屏层（graphicsLayer 平移）+ scrim |
| DropdownCompat.kt | shim：squircle()（CornerBasedShape，钳半径）/edgeLight/返回桥/主题色 |
| DropdownTrigger.kt | 触发区硬切状态（2帧藏/3帧显）+ collapseSize 实测 |
| basic/LiquidDropdown.kt | 只剩 LiquidDropdownRow / LiquidDropdownColumn / LocalBottomOverlayHeight |

原版参考：`D:\NexioSchedule-1.6.5-1015\miuix\...\basic\ListPopup.kt`、`...\layout\ListPopupLayout.kt`、`ui-shared\...\LiquidGlassDropdownMenu.kt`、`backdrop\`（fork 源码）。

## Bug 2（优先）：弹窗无玻璃背景——已核实的事实与未核实假设

**已核实（javap 反编译两版对比）**：
- 原版 fork 的 `DrawBackdropNode.draw()` 会按 shape outline **clipPath 整个卡片**（fork `DrawBackdropModifier.kt:361-391`）；
- backdrop 1.0.5 的同函数**完全没有 clip**（draw()=onDrawBehind→drawBackdropLayer→onDrawSurface→drawContent，无任何 clip 调用）；
- 1.0.5 `lens()` 只认 RoundedRectangularShape/CornerBasedShape（其它 Shape 抛异常，真机崩过已修）。

**当前 ListPopup.kt 材质层（:1320 附近）已经加了** `.clip(shape)` + 无玻璃时 `drawBehind{drawRect(surfaceColor)}` 兜底——**仍无效**。所以剩下的怀疑方向（按可能性排序）：
1. **f 卡死在 0（交接 §3-3 的老问题可能仍在）**：fractionProgress.animateTo 没跑或没被读到。f=0 时 containerAlpha=0（玻璃层全透明）、contentAlpha=0（行也看不见）、只显示 collapseContent（歌单菜单没传 collapseContent→纯空白）。**取证法**：`am start -n com.sonify.music/yos.music.player.debug.DropdownLabActivity --e case popup`（debug 包实验室，600ms 自动展开，不用点屏幕）→ screencap 看是"整块面板在但无底"还是"只有小圆/空白"。
2. `.clip(shape)` 的 outline 在绘制期为空/退化（AnimatedPanelRectShape 的 rectProvider 首帧崩溃值？打 log 验证 rect[2],rect[3]）。
3. drawBackdrop 在 1.0.5 + 本场景采样/绘制路径整体没跑（可在 onDrawSurface 里临时 `android.util.Log.i` 验证是否执行）。

## Bug 1：收起态圆钮四方向三角——矛盾点

- 三角=平滑圆角半径(25dp)超过半边长(21dp)时路径控制点越界（YosRoundedCornerShape 不钳制半径）。
- 已修：`squircle()`（CornerBasedShape 子类，createOutline 钳到 size.minDimension/2），替换了 ListPopup 的 AnimatedPanelRectShape 与 LiquidGlassDropdownMenu 的三处 25dp 形状。
- **矛盾**：我在 onyx 截图（_dd_fix2.png）实测收起态已是干净正圆（外凸像素检测四个正方向无系统鼓包），但用户说没修好。**怀疑用户看到的是另一个场景**：展开过程中途的三角形？别的按钮（返回钮 LiquidTopBarButton 用 CircleShape 不可能）？弹窗收起态小圆？——需要用户截图确认，或用 DropdownLabActivity `case=glass` 展开态截图对比。

## 已否决/勿重蹈

1. 第一版修 bug1：把 squircle 包成普通 `Shape` → lens 崩溃（已改 CornerBasedShape）。
2. 第一版修 bug2：只加 clip+兜底 → 用户实测仍无背景（根因未找对，别停在 clip 上）。
3. Espresso/`input tap`/instrument 测试在 onyx 全被系统拦（InputManager 不可反射、INJECT_EVENTS），且 connectedAndroidTest 失败会**卸载主应用**——取证只能用 debug 实验室 Activity + `am start` + screencap。
4. 1.6.1 旧引擎、anchorBounds 传坐标、follow 联动已全部退役，勿恢复。

## 构建装机

`./gradlew :app:assembleDebug` → `adb install -r app/build/outputs/apk/debug/app-debug.apk`（默认装 debug，勿动 release）。验收=用户手测。debug 实验室：`adb shell am start -n com.sonify.music/yos.music.player.debug.DropdownLabActivity --e case popup`（或 `case=glass`）。
