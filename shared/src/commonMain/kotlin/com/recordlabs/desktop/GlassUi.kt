package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.LocalContentColor
import androidx.compose.material.MaterialTheme
import androidx.compose.material.ProvideTextStyle
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layoutId
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.recordlabs.desktop.data.WindowsPreferences

/*
 * Minimal iOS-style "liquid glass" surfaces shared by every platform: the
 * dialogs (alerts and forms such as Search lyrics / Available lyrics), their
 * text fields, and the resizable queue panel.
 */

private val GlassDialogShape = RoundedCornerShape(30.dp)
private val GlassPillShape = RoundedCornerShape(50)
private val GlassFieldShape = RoundedCornerShape(14.dp)

/** The glass card: dark tinted glass, a light sheen at the top and a bright rim. */
internal fun Modifier.liquidGlass(shape: androidx.compose.ui.graphics.Shape, elevation: Dp = 40.dp): Modifier = this
    .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = .45f), spotColor = Color.Black.copy(alpha = .6f))
    .clip(shape)
    .background(Brush.verticalGradient(listOf(Color(0xEB303137), Color(0xF21C1D22))))
    .drawBehind {
        // The sheen of glass catching the light at its top edge.
        drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = .085f), .38f to Color.Transparent))
    }
    .border(
        1.dp,
        Brush.verticalGradient(listOf(Color.White.copy(alpha = .30f), Color.White.copy(alpha = .06f), Color.White.copy(alpha = .14f))),
        shape,
    )

/**
 * iPhone: the glass card as real liquid glass over the app ([LocalAppBackdrop]),
 * frosted so its text reads over anything; elsewhere [liquidGlass] as before.
 */
@Composable
internal fun Modifier.liquidGlassPanel(radius: Dp, elevation: Dp = 40.dp): Modifier {
    val shape = RoundedCornerShape(radius)
    val backdrop = LocalAppBackdrop.current ?: return liquidGlass(shape, elevation)
    return this
        .shadow(elevation, shape, ambientColor = Color.Black.copy(alpha = .40f), spotColor = Color.Black.copy(alpha = .55f))
        .clip(shape)
        .liquidGlassBackground(backdrop, cornerRadius = radius, frosted = true)
        .drawBehind {
            drawRect(Brush.verticalGradient(0f to Color.White.copy(alpha = .06f), .35f to Color.Transparent))
        }
        .border(
            0.8.dp,
            Brush.verticalGradient(listOf(Color.White.copy(alpha = .28f), Color.White.copy(alpha = .05f), Color.White.copy(alpha = .12f))),
            shape,
        )
}

/**
 * The dialog of the whole app (desktop and Android, every orientation), as a
 * minimal iOS alert: a liquid-glass card, the title centred, the content, and
 * the buttons as two equal pills at the bottom (Cancel in glass, the main
 * action in white). It zooms in softly like an iOS alert.
 *
 * Same parameters as Material's AlertDialog, so every dialog of the app uses it.
 */
@Composable
internal fun GlassDialog(
    onDismissRequest: () -> Unit,
    confirmButton: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    dismissButton: (@Composable () -> Unit)? = null,
    title: (@Composable () -> Unit)? = null,
    text: (@Composable () -> Unit)? = null,
) {
    var entered by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entered = true }
    val entrance by animateFloatAsState(
        targetValue = if (entered) 1f else 0f,
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 520f),
        label = "glass-dialog-entrance",
    )
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val density = LocalDensity.current
    val windowWidth = with(density) { windowSize.width.toDp() }
    val maxCardWidth = (windowWidth - 32.dp).coerceIn(260.dp, 600.dp)
    Dialog(onDismissRequest = onDismissRequest, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            Layout(
                modifier = modifier
                    .graphicsLayer {
                        alpha = entrance.coerceIn(0f, 1f)
                        val scale = 1.08f - 0.08f * entrance
                        scaleX = scale
                        scaleY = scale
                    }
                    .liquidGlassPanel(30.dp),
                content = {
                    if (title != null) Box(Modifier.layoutId("title").fillMaxWidth(), contentAlignment = Alignment.Center) {
                        ProvideTextStyle(TextStyle(color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)) { title() }
                    }
                    if (text != null) Box(Modifier.layoutId("text")) {
                        ProvideTextStyle(TextStyle(color = Color.White.copy(alpha = .78f), fontSize = 14.sp, lineHeight = 19.sp)) { text() }
                    }
                    Row(Modifier.layoutId("buttons").fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        if (dismissButton != null) {
                            DialogPill(prominent = false, modifier = Modifier.weight(1f)) { dismissButton() }
                            DialogPill(prominent = true, modifier = Modifier.weight(1f)) { confirmButton() }
                        } else {
                            DialogPill(prominent = true, modifier = Modifier.weight(1f)) { confirmButton() }
                        }
                    }
                },
            ) { measurables, constraints ->
                val pad = 22.dp.roundToPx()
                val top = 22.dp.roundToPx()
                val bottom = 18.dp.roundToPx()
                val titleGap = 12.dp.roundToPx()
                val buttonsGap = 20.dp.roundToPx()
                val maxWidth = minOf(constraints.maxWidth, maxCardWidth.roundToPx())
                val minWidth = minOf(300.dp.roundToPx(), maxWidth)
                val innerMax = (maxWidth - pad * 2).coerceAtLeast(0)
                val maxHeight = constraints.maxHeight
                // The content decides the width (forms are narrow, the lyrics list is wide).
                val textPlaceable = measurables.firstOrNull { it.layoutId == "text" }?.measure(
                    Constraints(maxWidth = innerMax, maxHeight = if (maxHeight == Constraints.Infinity) Constraints.Infinity else (maxHeight - 190.dp.roundToPx()).coerceAtLeast(80.dp.roundToPx())),
                )
                val width = maxOf(minWidth, (textPlaceable?.width ?: 0) + pad * 2).coerceAtMost(maxWidth)
                val inner = (width - pad * 2).coerceAtLeast(0)
                val titlePlaceable = measurables.firstOrNull { it.layoutId == "title" }?.measure(Constraints(minWidth = inner, maxWidth = inner))
                val buttonsPlaceable = measurables.first { it.layoutId == "buttons" }.measure(Constraints(minWidth = inner, maxWidth = inner))
                var height = top
                if (titlePlaceable != null) height += titlePlaceable.height
                if (titlePlaceable != null && textPlaceable != null) height += titleGap
                if (textPlaceable != null) height += textPlaceable.height
                height += buttonsGap + buttonsPlaceable.height + bottom
                layout(width, height) {
                    var y = top
                    titlePlaceable?.let { it.place(pad, y); y += it.height }
                    if (titlePlaceable != null && textPlaceable != null) y += titleGap
                    textPlaceable?.let { it.place(pad, y); y += it.height }
                    y += buttonsGap
                    buttonsPlaceable.place(pad, y)
                }
            }
        }
    }
}

/**
 * One button of a [GlassDialog] as a pill. The dialog's buttons are plain
 * TextButtons: the pill gives them its colours and stretches them over its
 * whole area, so the entire pill is the button.
 */
@Composable
private fun DialogPill(prominent: Boolean, modifier: Modifier, content: @Composable () -> Unit) {
    val foreground = if (prominent) Color(0xFF111114) else Color.White
    Box(
        modifier
            .height(46.dp)
            .clip(GlassPillShape)
            .background(if (prominent) Color.White.copy(alpha = .94f) else Color.White.copy(alpha = .11f))
            .border(0.6.dp, Color.White.copy(alpha = if (prominent) 0f else .12f), GlassPillShape),
    ) {
        MaterialTheme(
            colors = MaterialTheme.colors.copy(primary = foreground, onSurface = foreground),
            typography = MaterialTheme.typography.copy(button = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp)),
        ) {
            CompositionLocalProvider(LocalContentColor provides foreground) {
                Layout(content = content) { measurables, constraints ->
                    val w = constraints.maxWidth
                    val h = constraints.maxHeight
                    val placeables = measurables.map { it.measure(Constraints.fixed(w, h)) }
                    layout(w, h) { placeables.forEach { it.place(0, 0) } }
                }
            }
        }
    }
}

/** A text field in the glass style: a soft rounded well, the label small above the text. */
@Composable
internal fun GlassTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null,
    placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    // Enter, or the keyboard's search key, runs this (null: the key does nothing special).
    onSearch: (() -> Unit)? = null,
) {
    var focused by remember { mutableStateOf(false) }
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = singleLine,
        keyboardOptions = if (onSearch != null) androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search) else androidx.compose.foundation.text.KeyboardOptions.Default,
        keyboardActions = if (onSearch != null) androidx.compose.foundation.text.KeyboardActions(onSearch = { onSearch() }, onDone = { onSearch() }) else androidx.compose.foundation.text.KeyboardActions.Default,
        visualTransformation = visualTransformation,
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(Color.White),
        modifier = modifier
            // At least as wide as Material's fields (280 dp) unless the caller says otherwise.
            .widthIn(min = 280.dp)
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { inner ->
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 52.dp)
                    .clip(GlassFieldShape)
                    .background(Color.White.copy(alpha = if (focused) .13f else .08f))
                    .border(1.dp, Color.White.copy(alpha = if (focused) .30f else .10f), GlassFieldShape)
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                if (label != null) {
                    ProvideTextStyle(TextStyle(color = Color.White.copy(alpha = .55f), fontSize = 11.5.sp, fontWeight = FontWeight.Medium)) { label() }
                }
                Box {
                    if (value.isEmpty() && placeholder != null) {
                        ProvideTextStyle(TextStyle(color = Color.White.copy(alpha = .38f), fontSize = 16.sp)) { placeholder() }
                    }
                    inner()
                }
            }
        },
    )
}

/**
 * The side queue panel's width, shared by every queue (home, player, lyrics)
 * on every platform. It can be dragged narrower; the original size is the
 * widest. Kept between launches.
 */
internal object QueuePanel {
    val MaxWidth = 368.dp
    val MinWidth = 260.dp
    var width by mutableStateOf(MaxWidth)
        private set
    /** True while the edge is being dragged (animations follow the finger at once). */
    var resizing by mutableStateOf(false)
        private set

    init {
        WindowsPreferences.queuePanelWidth()?.let { width = it.dp.coerceIn(MinWidth, MaxWidth) }
    }

    fun resizeBy(delta: Dp) {
        width = (width + delta).coerceIn(MinWidth, MaxWidth)
    }

    fun startResize() { resizing = true }

    fun endResize() {
        resizing = false
        WindowsPreferences.setQueuePanelWidth(width.value)
    }
}

/**
 * The grip on the queue panel's inner edge: drag it to make the queue narrower
 * (or wide again). [panelOnRight] is true for a panel at the right of the window,
 * whose grip is on its left edge.
 */
@Composable
internal fun QueueResizeHandle(modifier: Modifier, panelOnRight: Boolean) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val active = hovered || QueuePanel.resizing
    val gripAlpha by animateFloatAsState(if (active) .6f else .2f, label = "queue-grip")
    Box(
        modifier
            .fillMaxHeight()
            .width(16.dp)
            .hoverable(interaction)
            .pointerHoverIcon(PointerIcon.Hand)
            .pointerInput(panelOnRight) {
                detectHorizontalDragGestures(
                    onDragStart = { QueuePanel.startResize() },
                    onDragEnd = { QueuePanel.endResize() },
                    onDragCancel = { QueuePanel.endResize() },
                ) { change, dragAmount ->
                    change.consume()
                    val delta = dragAmount.toDp()
                    QueuePanel.resizeBy(if (panelOnRight) -delta else delta)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.width(4.dp).height(44.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = gripAlpha)))
    }
}
