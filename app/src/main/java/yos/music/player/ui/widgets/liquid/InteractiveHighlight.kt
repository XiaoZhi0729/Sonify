package yos.music.player.ui.widgets.liquid

import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.VisibilityThreshold
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.util.fastCoerceIn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class InteractiveHighlight(
    private val animationScope: CoroutineScope,
    private val position: (Size, Offset) -> Offset = { _, offset -> offset },
    // 手势层叠在被选中 Tab 上方，普通点击不会穿透到底下的 Tab 按钮；
    // "按下后未拖动即抬起"视为点按当前选中 Tab（iOS 再点回根部语义）
    private val onTap: () -> Unit = {}
) {
    private val press = Animatable(0f, 0.001f)
    private val location = Animatable(Offset.Zero, Offset.VectorConverter, Offset.VisibilityThreshold)
    private var start = Offset.Zero
    private var dragDistance = 0f

    // LiquidTopBarButton 需要在绘制期读取按压进度（layerBlock 缩放）与按压位移（向手指偏移）
    val pressProgress: Float get() = press.value
    val offset: Offset get() = location.value - start

    // 弥散高光：官方 demo 同款 AGSL，径向 smoothstep 渐隐 + Plus 混合；
    // 之前简化成 drawCircle 纯色圆，边缘生硬，视觉上退化为半透明遮罩
    private val shader =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            RuntimeShader(
                """
uniform float2 size;
layout(color) uniform half4 color;
uniform float radius;
uniform float2 position;

half4 main(float2 coord) {
    float dist = distance(coord, position);
    float intensity = smoothstep(radius, radius * 0.5, dist);
    return color * intensity;
}"""
            )
        } else {
            null
        }

    val modifier = Modifier.drawWithContent {
        val progress = press.value
        if (progress > 0f) {
            val center = position(size, location.value)
            if (shader != null) {
                drawRect(
                    Color.White.copy(0.08f * progress),
                    blendMode = BlendMode.Plus
                )
                shader.apply {
                    setFloatUniform("size", size.width, size.height)
                    setColorUniform("color", Color.White.copy(0.15f * progress).toArgb())
                    setFloatUniform("radius", size.minDimension * 1.5f)
                    setFloatUniform(
                        "position",
                        center.x.fastCoerceIn(0f, size.width),
                        center.y.fastCoerceIn(0f, size.height)
                    )
                }
                drawRect(
                    ShaderBrush(shader),
                    blendMode = BlendMode.Plus
                )
            } else {
                // API < 33 无 AGSL：径向渐变模拟弥散，避免纯色圆的遮罩感
                val radius = size.minDimension * 1.2f
                drawCircle(
                    Brush.radialGradient(
                        listOf(Color.White.copy(0.15f * progress), Color.Transparent),
                        center = center,
                        radius = radius
                    ),
                    radius = radius,
                    center = center,
                    blendMode = BlendMode.Plus
                )
            }
        }
        drawContent()
    }
    val gestureModifier = Modifier.pointerInput(animationScope) {
        inspectDragGestures(
            onDragStart = { down ->
                start = down.position
                dragDistance = 0f
                animationScope.launch {
                    launch { press.animateTo(1f, spring(0.5f, 300f, 0.001f)) }
                    launch { location.snapTo(start) }
                }
            },
            onDragEnd = {
                animationScope.launch {
                    launch { press.animateTo(0f, spring(0.5f, 300f, 0.001f)) }
                    launch { location.animateTo(start, spring(0.5f, 300f, Offset.VisibilityThreshold)) }
                }
                if (dragDistance < viewConfiguration.touchSlop) {
                    onTap()
                }
            },
            onDragCancel = {
                animationScope.launch {
                    press.animateTo(0f, spring(0.5f, 300f, 0.001f))
                    location.animateTo(start, spring(0.5f, 300f, Offset.VisibilityThreshold))
                }
            }
        ) { change, _ ->
            dragDistance += change.positionChange().getDistance()
            animationScope.launch { location.snapTo(change.position) }
        }
    }

    // 仅按压版：不消费指针事件、不跟手拖动，用 clickable 的按钮仍能正常收到点击。
    // 按下时把 location 吸附到按压点，弥散高光从手指位置绽开（Nexio 原版 pressOnly
    // 不更新 position，高光会固定在 (0,0) 角落，这里修正）。
    val pressOnlyModifier: Modifier =
        Modifier.pointerInput(animationScope) {
            awaitEachGesture {
                val down = awaitFirstDown()
                start = down.position
                animationScope.launch {
                    location.snapTo(down.position)
                    press.animateTo(1f, spring(0.5f, 300f, 0.001f))
                }
                // 等待所有手指释放再回落，避免多指场景提前熄灭
                do {
                    val event = awaitPointerEvent(PointerEventPass.Final)
                } while (event.changes.any { it.pressed })
                animationScope.launch {
                    press.animateTo(0f, spring(0.5f, 300f, 0.001f))
                }
            }
        }
}
