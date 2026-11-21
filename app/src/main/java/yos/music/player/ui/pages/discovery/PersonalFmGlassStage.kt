package yos.music.player.ui.pages.discovery

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import com.kyant.backdrop.BackdropEffectScope
import com.kyant.backdrop.backdrops.LayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.highlight.Highlight
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.liquid.classyabaBlackRim
import yos.music.player.ui.widgets.liquid.classyabaWhiteEdge

/**
 * 板块私有玻璃舞台。
 *
 * ## 结构（自下而上）
 * 1. **采样源**：整块板块矩形，`layerBackdrop` 录制。里面是**底色 + 提取色色渍 + Pager 封面**。
 * 2. **材质**：采样源的**兄弟**节点，左上内缩后按 [FmGlassWidth] / [FmGlassHeight] 定尺寸。
 * 3. **内容**：文字与播放钮，画在材质之上。
 *
 * ## ★★底色层必须挂（最容易漏的一层）
 * 玻璃采的是「这一块背后有什么像素」。**没有底色层的采样源 = 一块透明底**，
 * 封面没盖到的地方采出来就是全透明，叠上表面纱后就是一块发虚的白雾
 * （真机两次踩到：一次是"下半截虚"，一次是整块虚）。
 *
 * 但底色**只进录制层、不进屏幕**：板块是无界的（用户红线：不要卡片）。
 * 做法就是把底色画在 `rememberLayerBackdrop(onDraw = ...)` 的 **onDraw** 里——
 * 库检测到自定义 `onDraw` 后 `contentOnlyCapture` 为 false，
 * 上屏退回 `drawContent()`，于是底色只参与采样、不会变成一张卡片底。
 *
 * ## ★关掉「液态玻璃效果」≠ 关掉模糊
 * 用户口径：关玻璃应当退化成**磨砂**（单纯模糊），不是变成一块不透明色板。
 * 所以两个分支都走同一条采样管线，区别只在效果链与表面色。
 *
 * ## 三条红线
 * - **材质绝不能被录进采样源**：`RenderNode` 自引用，会秒崩（ArtistDetail / SongComments 记过）。
 * - **绝不用页面级 `navBackdrop`**：本板块在 `LazyColumn` 里滚动，整页采样既错位又自引用。
 * - **`key(glassOn)` 不能删**：库的 `DrawBackdropElement` 在运行时翻转开关时会原地复用
 *   同一个节点、同一条录制管线，模糊层从此不再出图（底栏踩过：`LiquidBottomTabs` 同款规避）。
 *
 * ## 尺寸只有一个来源
 * 玻璃的宽高在这里由 [FmGlassWidth] / [FmGlassHeight] **唯一定义**。
 * 历史 bug：调用点写过 `.height(FmStageHeight - inset*2)` 又传 `padding(vertical = inset)`，
 * Modifier 链扣了两遍 → 玻璃短 16dp、上偏 8dp、底部空一截。
 * 因此 [glassModifier] 只允许放测试/语义类 modifier，**不得包含尺寸或纵向 padding**。
 */
@Composable
internal fun PersonalFmGlassStage(
    backdrop: LayerBackdrop,
    glassOn: Boolean,
    shape: Shape,
    glassEffects: BackdropEffectScope.() -> Unit,
    frostedEffects: BackdropEffectScope.() -> Unit,
    glassSurface: DrawScope.() -> Unit,
    frostedSurface: DrawScope.() -> Unit,
    modifier: Modifier = Modifier,
    glassModifier: Modifier = Modifier,
    recordKey: Any? = null,
    mustRecord: (() -> Boolean)? = null,
    backdropContent: @Composable BoxScope.() -> Unit,
    content: @Composable BoxScope.() -> Unit
) {
    val isDark = isFlamingoInDarkMode()
    val edgeAlpha = if (isDark) 0.32f else 0.65f
    val darkRimHighlight = remember(edgeAlpha) {
        { Highlight.Ambient.copy(alpha = edgeAlpha) }
    }
    val glassHighlight: (() -> Highlight?)? = when {
        !glassOn -> null
        !isDark -> classyabaBlackRim
        else -> darkRimHighlight
    }

    Box(modifier) {
        // ---- 1. 采样源 ----
        // 底色层由调用方注入到 `rememberLayerBackdrop(onDraw = ...)` 里
        // （见 PersonalFmSection 的 stageOnDraw）：只进录制层、不上屏。
        // ★绝不能用 `.drawBehind { 底色 }` 挂在下面这个节点上——
        //   默认 onDraw 时 contentOnlyCapture=true，录制完会整层 blit 上屏，
        //   底色就变成一张卡片底（用户红线：板块必须无界）。
        // 尺寸必须是确定的（fillMaxSize 于已定尺寸的父节点）：录制坐标与玻璃坐标
        // 一旦错开，表现就是「只有表面、没有玻璃」。
        // 磨砂态也要录：模糊必须有东西可采。
        Box(
            Modifier
                .fillMaxSize()
                .layerBackdrop(backdrop, recordKey = recordKey, mustRecord = mustRecord),
            content = backdropContent
        )

        // ---- 2. 材质 ----
        // 水平缩进只给 FmRailStart（用户要求"左边保持不变"= 与其它板块标题同一条左缘），
        // 垂直缩进只给 top；尺寸交给 size()，三者相加不互相扣减。
        // size() 必须排在 glassModifier **之前**：尺寸是权威的，
        // glassModifier 只允许挂测试标记/语义，不得再改尺寸或加纵向 padding。
        key(glassOn) {
            Box(
                Modifier
                    .align(Alignment.TopStart)
                    .padding(start = FmRailStart, top = FmGlassInsetY)
                    .size(width = FmGlassWidth, height = FmGlassHeight)
                    .then(glassModifier)
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = if (glassOn) glassEffects else frostedEffects,
                        // 液态玻璃边缘：浅色模式采用与按钮一致的「左右暗、上下亮」ClassYaba 描边
                        // （classyabaBlackRim + classyabaWhiteEdge）；深色模式保持 Ambient 柔光。
                        // 磨砂态不加边缘高光。
                        highlight = glassHighlight,
                        shadow = null,
                        onDrawSurface = if (glassOn) glassSurface else frostedSurface
                    )
                    .then(
                        if (glassOn && !isDark) Modifier.classyabaWhiteEdge(shape) else Modifier
                    ),
                content = content
            )
        }
    }
}
