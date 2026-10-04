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
import androidx.compose.ui.graphics.Color
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.kyant.backdrop.effects.effect
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.highlight.HighlightStyle
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import yos.music.player.data.libraries.SettingsLibrary
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import yos.music.player.ui.theme.isFlamingoInDarkMode

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
    dismissOnClickOutside: Boolean = false
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
                    dismissOnClickOutside
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
    dismissOnClickOutside: Boolean
) {
    val backdrop = LocalTitlePageBackdrop.current
    val dark = isFlamingoInDarkMode()
    val liquidGlass = SettingsLibrary.BarBlurEffect
    val style = CrestoDialogStyle(liquid = liquidGlass, dark = dark)
    val surface = style.surfaceColor
    val foreground = style.contentColor
    val shape = RoundedRectangle(cornerRadius ?: style.cornerRadius)
    val scale = remember { Animatable(1.15f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var closing by remember { mutableStateOf(false) }
    val material = rememberDialogMaterial(dark)
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    val latestPositive by rememberUpdatedState(onPositive)
    val latestNegative by rememberUpdatedState(onNegative ?: onDismissRequest)

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
                .background(Color.Black.copy(alpha = 0.4f))
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
                    .crestoDialogShadow(shape, dark) { alpha.value }
                    .then(if (backdrop != null && material != null && Build.VERSION.SDK_INT >= 33) {
                        Modifier.drawBackdrop(
                            backdrop = backdrop,
                            shape = { shape },
                            effects = {
                                padding = style.blurRadius.toPx() * 2
                                effect(material)
                                blur(style.blurRadius.toPx(), TileMode.Mirror)
                                if (liquidGlass) lens(48.dp.toPx(), 48.dp.toPx())
                            },
                            highlight = {
                                if (liquidGlass) Highlight.Default.copy(
                                    style = HighlightStyle.Default(angle = 90f)
                                ) else null
                            },
                            shadow = { null },
                            innerShadow = { null },
                            layerBlock = {
                                scaleX = scale.value
                                scaleY = scale.value
                                this.alpha = alpha.value
                            }
                        )
                    } else Modifier.graphicsLayer {
                        scaleX = scale.value
                        scaleY = scale.value
                        this.alpha = alpha.value
                    }.clip(shape).background(surface))
                    .then(if (!liquidGlass) Modifier.crestoHighlight(shape) else Modifier)
                    .semantics { paneTitle = title; isTraversalGroup = true }
                    .pointerInput(Unit) { detectTapGestures(onTap = {}) }
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CompositionLocalProvider(LocalContentColor provides foreground) {
                    Column(
                        Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
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
                        DialogAction(positiveContent, positiveEnabled, true, destructive, style) {
                            if (closeOnPositive) finish { latestPositive() } else if (!closing) latestPositive()
                        }
                        negativeContent?.let {
                            DialogAction(it, negativeEnabled, false, false, style) { finish { latestNegative() } }
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
    onClick: () -> Unit
) {
    val (background, foreground) = style.buttonColors(primary, destructive, enabled)
    val shape: Shape = if (style.liquid) Capsule() else RoundedRectangle(12.dp)
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .clip(shape).background(background)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = CrestoDimIndication(style.contentColor),
                role = Role.Button,
                onClick = onClick
            ).then(if (primary) Modifier.crestoHighlight(shape) else Modifier)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text, color = foreground, fontSize = 16.sp, lineHeight = 20.sp, textAlign = TextAlign.Center)
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
