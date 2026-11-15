package com.kyant.backdrop.backdrops

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.kyant.backdrop.internal.recordLayer
import kotlin.math.roundToInt

/**
 * @param recordKey 被录制内容的指纹（壁纸 bitmap / 缩放 / 偏移 / 亮度 / 主题色等）。
 *   传了它且值没变时，说明这一帧录出来的内容和上一帧逐像素相同，可以整段跳过 ——
 *   滑动课表时壁纸是静止的，每帧重录一次全屏层纯属白烧。
 *   传 null（默认）保持原行为：每帧录制。
 *   注意：指纹必须覆盖所有能改变被录制内容的因素，否则会用到过期采样。
 * @param mustRecord draw 阶段强制录制谓词。返回 true 时即使 recordKey 未变也重录。
 *   用于「结构稳定但滚动/动画仍在改像素」的主内容 liquidGlass：谓词里读 ScrollState/Animatable，
 *   不进组合，滚动帧只多一次比较。引用须 remember 稳定，否则每次重组 update→markNeedsRecord，跳过失效。
 */
fun Modifier.layerBackdrop(
    backdrop: LayerBackdrop,
    recordKey: Any? = null,
    mustRecord: (() -> Boolean)? = null,
    enabled: (() -> Boolean)? = null,
    clipRect: (DrawScope.() -> Rect?)? = null
): Modifier =
    this then LayerBackdropElement(backdrop, recordKey, mustRecord, enabled, clipRect)

private class LayerBackdropElement(
    val backdrop: LayerBackdrop,
    val recordKey: Any? = null,
    val mustRecord: (() -> Boolean)? = null,
    val enabled: (() -> Boolean)? = null,
    val clipRect: (DrawScope.() -> Rect?)? = null
) : ModifierNodeElement<LayerBackdropNode>() {

    override fun create(): LayerBackdropNode {
        return LayerBackdropNode(backdrop, recordKey, mustRecord, enabled, clipRect)
    }

    override fun update(node: LayerBackdropNode) {
        if (node.backdrop != backdrop) {
            node.backdrop.layerCoordinates = null
            node.backdrop = backdrop
        }
        node.recordKey = recordKey
        node.mustRecord = mustRecord
        node.enabled = enabled
        node.clipRect = clipRect
        node.markNeedsRecord()
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "layerBackdrop"
        properties["backdrop"] = backdrop
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is LayerBackdropElement) return false

        if (backdrop != other.backdrop) return false
        if (recordKey != other.recordKey) return false
        // mustRecord / enabled / clipRect 按引用比较：remember 出来的稳定 lambda 才能让 equals 为 true
        if (mustRecord !== other.mustRecord) return false
        if (enabled !== other.enabled) return false
        if (clipRect !== other.clipRect) return false

        return true
    }

    override fun hashCode(): Int {
        var result = backdrop.hashCode()
        result = 31 * result + (recordKey?.hashCode() ?: 0)
        result = 31 * result + (mustRecord?.hashCode() ?: 0)
        result = 31 * result + (enabled?.hashCode() ?: 0)
        result = 31 * result + (clipRect?.hashCode() ?: 0)
        return result
    }
}

private var activeParentRecordDepth = 0

private class LayerBackdropNode(
    var backdrop: LayerBackdrop,
    var recordKey: Any? = null,
    var mustRecord: (() -> Boolean)? = null,
    var enabled: (() -> Boolean)? = null,
    var clipRect: (DrawScope.() -> Rect?)? = null
) : DrawModifierNode, GlobalPositionAwareModifierNode, Modifier.Node() {

    private var needsRecord = true
    private var recordedW = 0
    private var recordedH = 0

    fun markNeedsRecord() { needsRecord = true }

    override fun ContentDrawScope.draw() {
        if (enabled?.invoke() == false) {
            if (backdrop.contentOnlyCapture) {
                backdrop.onDraw(this@draw)
            } else {
                drawContent()
            }
            return
        }
        if (activeParentRecordDepth > 0) {
            if (backdrop.contentOnlyCapture) {
                backdrop.onDraw(this@draw)
            } else {
                drawContent()
            }
            return
        }
        val w = size.width.roundToInt()
        val h = size.height.roundToInt()
        val force = mustRecord?.invoke() == true
        val shouldRecord = recordKey == null || force || needsRecord || recordedW != w || recordedH != h
        val clip = if (shouldRecord) clipRect?.invoke(this@draw) else null
        if (shouldRecord) {
            needsRecord = false
            recordedW = w
            recordedH = h
            activeParentRecordDepth++
            val recorded = try {
                recordLayer(this@LayerBackdropNode, backdrop.graphicsLayer) {
                    if (clip != null) {
                        clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                            this@draw.clipRect(clip.left, clip.top, clip.right, clip.bottom) {
                                backdrop.onDraw(this@draw)
                            }
                        }
                    } else {
                        backdrop.onDraw(this@draw)
                    }
                }
            } finally {
                activeParentRecordDepth--
            }
            if (recorded) {
                backdrop.contentVersion++
                if (backdrop.contentOnlyCapture && clip == null) {
                    drawLayer(backdrop.graphicsLayer)
                    return
                }
            }
        }
        if (backdrop.contentOnlyCapture) {
            backdrop.onDraw(this@draw)
        } else {
            drawContent()
        }
    }

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        if (coordinates.isAttached) {
            val prev = backdrop.layerCoordinates
            // LayoutCoordinates 通常按实例比较 equals。位置/尺寸未变时不要换实例，
            // 否则下游读 layerCoordinates 的 draw 节点会被无意义地整批 invalidate。
            val changed = prev == null ||
                prev.isAttached != coordinates.isAttached ||
                prev.positionInWindow() != coordinates.positionInWindow() ||
                prev.size != coordinates.size
            if (changed) {
                backdrop.layerCoordinates = coordinates
            }
        }
    }

    override fun onDetach() {
        backdrop.layerCoordinates = null
    }
}
