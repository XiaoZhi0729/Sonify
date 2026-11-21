/*
 * Adapted from Cresto's GlasenseDialog and glasense-ui, Apache-2.0.
 * Modified for Sonify: overlay host, content slots, async actions, Backdrop 1.0.5.
 * License and attribution: assets/licenses/cresto-NOTICE.txt.
 */
package yos.music.player.ui.widgets.basic

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TileMode
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.highlight.Highlight
import com.kyant.capsule.ContinuousRoundedRectangle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import yos.music.player.data.libraries.SettingsLibrary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode
import yos.music.player.ui.widgets.liquid.GlassBoxShadowAlpha
import yos.music.player.ui.widgets.liquid.InteractiveHighlight
import yos.music.player.ui.widgets.liquid.applyGlassDragScale
import yos.music.player.ui.widgets.liquid.glassBlurScale
import yos.music.player.ui.widgets.liquid.glassBoxShadow

private class DialogOverlayHost {
    val entries = mutableStateMapOf<Any, @Composable () -> Unit>()
}

private val LocalDialogOverlayHost = staticCompositionLocalOf<DialogOverlayHost?> { null }

@Composable
fun hasSonifyDialog(): Boolean = LocalDialogOverlayHost.current?.entries?.isNotEmpty() == true

@Composable
fun SonifyDialogHost(content: @Composable () -> Unit) {
    val host = remember { DialogOverlayHost() }
    val background = rememberUpdatedState(MaterialTheme.colorScheme.background)
    val record: androidx.compose.ui.graphics.drawscope.ContentDrawScope.() -> Unit = remember {
        { drawRect(background.value); drawContent() }
    }
    val backdrop = rememberLayerBackdrop(onDraw = record)
    val modalOpen = host.entries.isNotEmpty()
    val focusManager = LocalFocusManager.current
    val modalFocus = remember { FocusRequester() }
    LaunchedEffect(modalOpen) {
        if (modalOpen) {
            focusManager.clearFocus(force = true)
            modalFocus.requestFocus()
        }
    }
    CompositionLocalProvider(LocalDialogOverlayHost provides host) {
        Box(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().then(
                if (modalOpen) Modifier.layerBackdrop(backdrop).clearAndSetSemantics {} else Modifier
            ).focusProperties {
                onEnter = { if (modalOpen) cancelFocusChange() }
            }.focusGroup()) { content() }
            if (modalOpen) {
                Box(Modifier.fillMaxSize().focusRequester(modalFocus).focusProperties {
                    onExit = { cancelFocusChange() }
                }.focusGroup().focusable()) {
                    CompositionLocalProvider(LocalTitlePageBackdrop provides backdrop) {
                        val (id, overlay) = host.entries.toList().last()
                        key(id) { overlay() }
                    }
                }
            }
        }
    }
}

@Composable
@NonSkippableComposable
fun SonifyDialog(
    title: String,
    onDismissRequest: () -> Unit,
    content: (@Composable () -> Unit)? = null,
    message: String? = null,
    icon: (@Composable () -> Unit)? = null,
    positiveContent: String,
    onPositive: () -> Unit,
    negativeContent: String? = null,
    onNegative: (() -> Unit)? = null,
    positiveEnabled: Boolean = true,
    negativeEnabled: Boolean = true,
    dismissEnabled: Boolean = true,
    destructive: Boolean = false,
    closeOnPositive: Boolean = true,
    wide: Boolean = false,
    cornerRadius: Dp? = null,
    dismissOnBackPress: Boolean = false,
    dismissOnClickOutside: Boolean = false,
    tertiaryContent: String? = null,
    onTertiary: (() -> Unit)? = null
) {
    val host = LocalDialogOverlayHost.current
    val callerContext = currentCompositionLocalContext
    val overlay: @Composable () -> Unit = {
        val modalBackdrop = LocalTitlePageBackdrop.current
        CompositionLocalProvider(callerContext) {
            CompositionLocalProvider(LocalTitlePageBackdrop provides modalBackdrop) {
                DialogPanel(
                    title, onDismissRequest, content, message, icon, positiveContent, onPositive,
                    negativeContent, onNegative, positiveEnabled, negativeEnabled, dismissEnabled,
                    destructive, closeOnPositive, wide, cornerRadius, dismissOnBackPress,
                    dismissOnClickOutside, tertiaryContent, onTertiary
                )
            }
        }
    }
    if (host == null) {
        overlay()
    } else {
        val id = remember { Any() }
        val latestOverlay = rememberUpdatedState(overlay)
        DisposableEffect(host, id) {
            host.entries[id] = { latestOverlay.value() }
            onDispose { host.entries.remove(id) }
        }
    }
}

@Composable
private fun DialogPanel(
    title: String,
    onDismissRequest: () -> Unit,
    content: (@Composable () -> Unit)?,
    message: String?,
    icon: (@Composable () -> Unit)?,
    positiveContent: String,
    onPositive: () -> Unit,
    negativeContent: String?,
    onNegative: (() -> Unit)?,
    positiveEnabled: Boolean,
    negativeEnabled: Boolean,
    dismissEnabled: Boolean,
    destructive: Boolean,
    closeOnPositive: Boolean,
    wide: Boolean,
    cornerRadius: Dp?,
    dismissOnBackPress: Boolean,
    dismissOnClickOutside: Boolean,
    tertiaryContent: String?,
    onTertiary: (() -> Unit)?
) {
    val backdrop = LocalTitlePageBackdrop.current
    val dark = isFlamingoInDarkMode()
    val liquidGlass = SettingsLibrary.BarBlurEffect
    val style = CrestoDialogStyle(
        liquid = liquidGlass,
        dark = dark,
        primaryColor = MaterialTheme.colorScheme.primary,
        onPrimaryColor = MaterialTheme.colorScheme.onPrimary,
    )
    val surface = style.surfaceColor
    val foreground = style.contentColor
    // MeiloX IosAlertSurface 同款连续圆角；调用方仍可用 cornerRadius 覆盖。
    val shape: Shape = cornerRadius?.let { RoundedRectangle(it) }
        ?: ContinuousRoundedRectangle(34.dp)
    val scale = remember { Animatable(1.15f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestPositive by rememberUpdatedState(onPositive)
    val latestNegative by rememberUpdatedState(onNegative ?: onDismissRequest)
    val latestTertiary by rememberUpdatedState(onTertiary ?: onDismissRequest)

    // 按压高光/形变：面板一份、三个按钮各一份。按在按钮上时只驱动该按钮，
    // 面板 pressProgress 保持 0 → 纹丝不动。按钮不存在时对应槽位不会被命中。
    val panelHighlight = remember { InteractiveHighlight(scope) }
    val buttonHighlights = remember {
        arrayOf(
            InteractiveHighlight(scope, radiusBaseDp = 48.dp),
            InteractiveHighlight(scope, radiusBaseDp = 48.dp),
            InteractiveHighlight(scope, radiusBaseDp = 48.dp),
        )
    }
    val gestureTargets = remember { DialogGestureTargets() }

    // 入场 spring 与按压形变共用同一 layerBlock：applyGlassDragScale 是乘算叠加，
    // 不能整体覆盖 scale.value，否则入场 1.15→1 会被顶掉。
    val panelLayerBlock: GraphicsLayerScope.() -> Unit = {
        scaleX = scale.value
        scaleY = scale.value
        this.alpha = alpha.value
        applyGlassDragScale(panelHighlight.pressProgress, panelHighlight.offset)
    }

    LaunchedEffect(closing) {
        if (closing) return@LaunchedEffect
        coroutineScope {
            launch { scale.animateTo(1f, spring(dampingRatio = 1f, stiffness = ((2 * kotlin.math.PI / 0.35).let { it * it }).toFloat(), visibilityThreshold = 0.0001f)) }
            launch { alpha.animateTo(1f, tween(300)) }
        }
    }
    fun finish(action: () -> Unit) {
        if (closing) return
        closing = true
        scope.launch {
            alpha.animateTo(0f, tween(200))
            action()
        }
    }
    BackHandler {
        if (dismissEnabled && dismissOnBackPress) finish { latestDismiss() }
    }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha.value }
                .background(Color.Black.copy(alpha = if (dark) 0.2f else 0.08f))
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    if (dismissEnabled && dismissOnClickOutside) finish { latestDismiss() }
                }
        )
        BoxWithConstraints(
            Modifier.fillMaxSize().imePadding().safeDrawingPadding(),
            contentAlignment = Alignment.Center
        ) {
            val width = (maxWidth - if (wide) 32.dp else 96.dp).coerceAtLeast(0.dp)
            Column(
                Modifier.widthIn(max = if (wide) 560.dp else Dp.Infinity).width(width)
                    .heightIn(max = maxHeight * 0.92f)
                    .onGloballyPositioned { gestureTargets.panelOrigin = it.positionInRoot() }
                    .crestoDialogShadow(shape, dark) { alpha.value }
                    // 实体外描边：侧面暗影 + 清晰外轮廓，只留玻璃外侧。
                    .glassBoxShadow({ shape }, GlassBoxShadowAlpha, panelLayerBlock)
                    .then(if (backdrop != null && Build.VERSION.SDK_INT >= 33) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                // padding 是离屏扩展量，必须跟着半径一起放大，
                                // 否则半径变大后模糊溢出被裁掉（圆角处露硬边）。
                                val radius = (if (dark) 8.dp else 16.dp).toPx() * glassBlurScale
                                padding = radius * 2
                                blur(radius, TileMode.Mirror)
                            },
                            // 均匀白环：Highlight.Plain 无 shader，配合外描边给玻璃一圈内高光。
                            highlight = { if (liquidGlass) Highlight.Plain else null },
                            shadow = { null },
                            innerShadow = { null },
                            layerBlock = panelLayerBlock,
                            onDrawSurface = {
                                // 多层 BlendMode 叠色，替代原先单层静态亮度映射：
                                // 让玻璃后面的内容透出层次，而不是压成一层灰雾。
                                val a = if (dark) 0.64f else 0.72f
                                if (!dark) {
                                    drawRect(
                                        Color.White.copy(alpha = 0.70f * a),
                                        blendMode = BlendMode.Lighten,
                                    )
                                    drawRect(
                                        Color(0x1ABFBFBF).copy(alpha = 0.10f * a),
                                        blendMode = BlendMode.Darken,
                                    )
                                } else {
                                    drawRect(
                                        Color(0xB31A1A1A).copy(alpha = 0.70f * a),
                                        blendMode = BlendMode.Luminosity,
                                    )
                                    drawRect(
                                        Color(0xE61A1A1A).copy(alpha = 0.90f * a),
                                        blendMode = BlendMode.Luminosity,
                                    )
                                    drawRect(
                                        Color(0xFF1A1A1A).copy(alpha = a),
                                        blendMode = BlendMode.Lighten,
                                    )
                                }
                            }
                        )
                    } else Modifier.graphicsLayer(panelLayerBlock).clip(shape).background(surface))
                    .then(if (!liquidGlass) Modifier.crestoHighlight(shape) else Modifier)
                    .then(panelHighlight.modifier)
                    // 单一手势源：按在按钮上只驱动该按钮，面板保持静止。
                    .dialogPressGesture(gestureTargets, panelHighlight, buttonHighlights)
                    .semantics { paneTitle = title; isTraversalGroup = true }
                    // 吃掉点击穿透（原实现红线，勿删）。
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CompositionLocalProvider(LocalContentColor provides foreground) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(8.dp))
                        icon?.invoke()
                        if (icon != null) Spacer(Modifier.height(8.dp))
                        Text(title, fontSize = 16.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                        message?.let {
                            Spacer(Modifier.height(8.dp))
                            Text(it, fontSize = 16.sp, lineHeight = 20.sp, color = foreground.copy(0.5f),
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp))
                        }
                        content?.let {
                            Spacer(Modifier.height(16.dp))
                            it()
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        DialogAction(
                            positiveContent, positiveEnabled, true, destructive, style,
                            gestureTargets, buttonHighlights[0]
                        ) {
                            if (closeOnPositive) finish { latestPositive() } else if (!closing) latestPositive()
                        }
                        negativeContent?.let {
                            DialogAction(
                                it, negativeEnabled, false, false, style,
                                gestureTargets, buttonHighlights[1]
                            ) { finish { latestNegative() } }
                        }
                        tertiaryContent?.let {
                            DialogAction(
                                it, dismissEnabled, false, false, style,
                                gestureTargets, buttonHighlights[2]
                            ) { finish { latestTertiary() } }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DialogAction(
    text: String,
    enabled: Boolean,
    primary: Boolean,
    destructive: Boolean,
    style: CrestoDialogStyle,
    targets: DialogGestureTargets,
    highlight: InteractiveHighlight,
    onClick: () -> Unit
) {
    val (background, foreground) = style.buttonColors(primary, destructive, enabled)
    val shape: Shape = if (style.liquid) Capsule() else RoundedRectangle(12.dp)
    val interaction = remember { MutableInteractionSource() }
    // 按钮自己的形变与高光由面板上的单一手势源驱动，这里不叠第二个消费型手势，
    // 避免与 clickable 抢 down（工程里反复踩过的坑）。
    val buttonLayer: GraphicsLayerScope.() -> Unit = {
        applyGlassDragScale(highlight.pressProgress, highlight.offset)
    }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .onGloballyPositioned { coords ->
                val origin = coords.positionInRoot()
                val rect = Rect(
                    origin.x, origin.y,
                    origin.x + coords.size.width, origin.y + coords.size.height,
                )
                targets.register(highlight, rect)
            }
            .graphicsLayer(buttonLayer)
            .clip(shape).background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = CrestoDimIndication(style.contentColor),
                role = Role.Button,
                onClick = onClick
            ).then(if (primary) Modifier.crestoHighlight(shape) else Modifier)
            .then(highlight.modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = foreground, fontSize = 16.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
    }
}

/**
 * 按钮命中几何登记表。三个槽位固定，未呈现的按钮不会调用 register，
 * 因此不会误命中。坐标统一为 root 坐标系（面板手势的 down 坐标也在 root 系）。
 */
private class DialogGestureTargets {
    var panelOrigin: Offset = Offset.Zero
    val rects = arrayOfNulls<Rect>(3)
    val owners = arrayOfNulls<InteractiveHighlight>(3)

    fun register(highlight: InteractiveHighlight, rect: Rect) {
        for (i in owners.indices) {
            if (owners[i] === highlight) {
                rects[i] = rect
                return
            }
        }
        for (i in owners.indices) {
            if (owners[i] == null) {
                owners[i] = highlight
                rects[i] = rect
                return
            }
        }
    }

    /** 返回 (按钮高光, 该按钮在 root 系的左上角)；按钮不存在时返回 null。 */
    fun hitTest(rootPoint: Offset): Pair<InteractiveHighlight, Offset>? {
        for (i in owners.indices) {
            val r = rects[i] ?: continue
            val owner = owners[i] ?: continue
            if (rootPoint.x >= r.left && rootPoint.x <= r.right &&
                rootPoint.y >= r.top && rootPoint.y <= r.bottom
            ) return owner to Offset(r.left, r.top)
        }
        return null
    }
}

/**
 * 面板上的单一手势源：按下时按落点分流。
 * - 落在按钮 → 只驱动该按钮的形变/高光，面板 pressProgress 恒 0（不动）。
 * - 落在非按钮区 → 只驱动面板，所有按钮不受影响。
 *
 * 全程不消费指针事件，按钮的 clickable 与外层的 dismiss 点击照常工作。
 */
private fun Modifier.dialogPressGesture(
    targets: DialogGestureTargets,
    panelHighlight: InteractiveHighlight,
    buttonHighlights: Array<InteractiveHighlight>,
): Modifier = pointerInput(targets, panelHighlight, buttonHighlights) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        val rootPoint = targets.panelOrigin + down.position
        val hit = targets.hitTest(rootPoint)
        // 命中按钮时把 root 坐标换算成按钮节点局部坐标，只驱动那一个按钮；
        // 未命中则驱动面板。两者互斥，面板不会跟着按钮一起动。
        val active = hit?.first ?: panelHighlight
        val origin = hit?.second ?: targets.panelOrigin
        val toLocal: (Offset) -> Offset = { p -> p + targets.panelOrigin - origin }
        active.externalDragStart(toLocal(down.position))

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (change.changedToUpIgnoreConsumed()) break
            active.externalDragMove(toLocal(change.position))
        }
        active.externalDragEnd()
    }
}

@Composable
fun SonifyDialogTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val foreground = LocalContentColor.current
    val primaryColor = CrestoDialogStyle(false, false).primaryColor
    val liquidGlass = SettingsLibrary.BarBlurEffect
    BasicTextField(
        value = value, onValueChange = onValueChange, singleLine = true, enabled = enabled,
        textStyle = TextStyle(color = foreground, fontSize = 16.sp, lineHeight = 20.sp),
        cursorBrush = SolidColor(primaryColor),
        modifier = modifier.clip(RoundedRectangle(if (liquidGlass) 16.dp else 12.dp)).background(foreground.copy(alpha = 0.05f))
            .padding(horizontal = 12.dp, vertical = 14.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(placeholder, color = foreground.copy(alpha = 0.45f), fontSize = 16.sp)
                inner()
            }
        }
    )
}
