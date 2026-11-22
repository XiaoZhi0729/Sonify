package yos.music.player.ui.widgets.liquid

import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import com.kyant.backdrop.highlight.Highlight

/**
 * ClassYaba / iOS 27 浅色模式玻璃按钮描边入口（保持历史符号兼容）。
 *
 * 原外层黑边（`classyabaBlackRim`）与内倒角白高光（`classyabaWhiteEdge`）已统一收口至
 * [Modifier.ios27GlassEdge]，在单个修饰节点内按严谨叠层绘制：
 * `纵向下表面明暗 → 内容 → 内缩 plusLighter 羽化白倒角 → 最外圈 plusDarker(SrcOver+ColorBurn) 暗环`。
 *
 * 因此 [classyabaBlackRim] 返回 null（避免在 `drawBackdrop.highlight` 里额外开离屏层重复描边），
 * 所有调用点保持既有链序即可自动获得 iOS 27 描边。
 */
val classyabaBlackRim: () -> Highlight? = { null }

/**
 * 浅色玻璃边缘高光与暗环组合层（委托给 [ios27GlassEdge]）。
 *
 * @param shape 描边轮廓，必须与该按钮材质层所用的形状完全一致。
 */
fun Modifier.classyabaWhiteEdge(shape: Shape): Modifier = classyabaWhiteEdge(shape = { shape })

/** [classyabaWhiteEdge] 的 lambda 形状重载（轮廓需在绘制期求值时用，如随展开进度变形）。 */
fun Modifier.classyabaWhiteEdge(shape: () -> Shape): Modifier =
    this.ios27GlassEdge(shape = shape, drawSurfaceShading = true)
