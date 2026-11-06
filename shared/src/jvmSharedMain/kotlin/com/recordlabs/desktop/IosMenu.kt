package com.recordlabs.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.scaleIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.draw.scale
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Icon
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.composed
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
import androidx.compose.ui.window.PopupProperties
import com.recordlabs.desktop.data.Track

/*
 * Menus in the style of iOS context menus: one rounded card, rows with the
 * label on the left and a thin line icon on the right, hairlines between the
 * rows of a group and a darker band between groups.
 */

// Like the frosted iOS menu: the page behind only tints it, it never shows through sharply.
private val MenuCardColor = Color(0xFF2C2C30).copy(alpha = 0.97f)
private val MenuGroupGap = Color.Black.copy(alpha = 0.28f)
private val MenuHairline = Color.White.copy(alpha = 0.10f)
private val MenuSecondary = Color.White.copy(alpha = 0.55f)
private val MenuMargin = 8.dp

/**
 * Sizes of the menu. The ⋯ menu is the same everywhere (desktop and Android,
 * portrait and landscape), measured from Apple Music on iPhone: 250 wide,
 * 44-high rows, 17 sp labels, 20 dp icons, 13 dp corners, an 8 dp band
 * between groups. Every menu now uses the compact (dense) sizes of the right-click menu.
 */
private class MenuStyle(val dense: Boolean) {
    val width = if (dense) 214.dp else 250.dp
    val shape = RoundedCornerShape(if (dense) 9.dp else 13.dp)
    val gap = if (dense) 4.dp else 8.dp
    val rowMinHeight = if (dense) 32.dp else 44.dp
    val rowPadH = if (dense) 11.dp else 16.dp
    val rowPadV = if (dense) 6.dp else 11.dp
    val label = if (dense) 13.5.sp else 17.sp
    val labelLine = if (dense) 17.sp else 21.sp
    val subtitle = if (dense) 11.sp else 12.sp
    val value = if (dense) 12.5.sp else 14.sp
    val icon = if (dense) 16.dp else 20.dp
    val tileHeight = if (dense) 54.dp else 70.dp
    val tileIcon = if (dense) 18.dp else 22.dp
    val tileText = if (dense) 11.sp else 12.sp
    val card = if (dense) Color(0xFF2A2A2D).copy(alpha = 0.94f) else MenuCardColor
}

private val LocalMenuStyle = androidx.compose.runtime.compositionLocalOf { MenuStyle(false) }

/**
 * Where the last press in the app window was (window pixels): the menu opens
 * next to the button that opened it, as iOS menus do.
 */
internal object MenuAnchor {
    var lastPress: Offset? = null
    /** The last press was a right click (a computer context menu opens at the pointer). */
    var lastPressSecondary: Boolean = false
}

/** Records presses for [MenuAnchor] (on the app's root, without taking them). */
internal fun Modifier.menuAnchorTracker(): Modifier = composed {
    val origin = remember { floatArrayOf(0f, 0f) }
    this
        .onGloballyPositioned { val p = it.positionInWindow(); origin[0] = p.x; origin[1] = p.y }
        .pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Press) {
                        event.changes.firstOrNull()?.position?.let { MenuAnchor.lastPress = Offset(it.x + origin[0], it.y + origin[1]) }
                        MenuAnchor.lastPressSecondary = event.buttons.isSecondaryPressed
                    }
                }
            }
        }
}

/** Where the menu ended up, so it grows out of (and shrinks back into) the button. */
private class MenuPlacement {
    @Volatile var origin: TransformOrigin = TransformOrigin(0.9f, 0f)
}

/**
 * Puts the menu next to the ⋯ button that opened it, as Apple Music does: its
 * right edge just past the button (so a button at the screen edge gives a menu
 * [marginPx] from that edge), its top a little under the button; above the
 * button when there is no room below. A right click on a computer opens it at
 * the pointer instead. Always inside the window.
 */
private class AnchoredMenuPosition(
    private val anchor: Offset?,
    private val gapPx: Int,
    private val rightOverhangPx: Int,
    private val marginPx: Int,
    private val atPointer: Boolean,
    private val placement: MenuPlacement,
) : PopupPositionProvider {
    override fun calculatePosition(anchorBounds: IntRect, windowSize: IntSize, layoutDirection: LayoutDirection, popupContentSize: IntSize): IntOffset {
        val w = popupContentSize.width
        val h = popupContentSize.height
        fun clampX(x: Int) = x.coerceIn(marginPx, (windowSize.width - marginPx - w).coerceAtLeast(marginPx))
        fun clampY(y: Int) = y.coerceIn(marginPx, (windowSize.height - marginPx - h).coerceAtLeast(marginPx))
        if (anchor == null) {
            placement.origin = TransformOrigin.Center
            return IntOffset(clampX((windowSize.width - w) / 2), clampY((windowSize.height - h) / 2))
        }
        val x = clampX(if (atPointer) anchor.x.toInt() else anchor.x.toInt() + rightOverhangPx - w)
        val below = anchor.y.toInt() + gapPx
        val above = anchor.y.toInt() - gapPx - h
        val opensAbove: Boolean
        val y = when {
            below + h <= windowSize.height - marginPx -> { opensAbove = false; below }
            above >= marginPx -> { opensAbove = true; above }
            else -> { opensAbove = anchor.y > windowSize.height / 2f; clampY(windowSize.height - marginPx - h) }
        }
        // Grows out of the button: the button's place across the menu, its top or bottom edge.
        val originX = if (w > 0) ((anchor.x - x) / w).coerceIn(0f, 1f) else 0.9f
        val originY = if (h > 0) ((anchor.y - y) / h).coerceIn(0f, 1f) else 0f
        placement.origin = TransformOrigin(originX, if (opensAbove) originY.coerceAtLeast(0.98f) else originY.coerceAtMost(0.02f))
        return IntOffset(x, y)
    }
}

/**
 * The menu, opened next to the button that was pressed: one rounded card with
 * the groups ([IosMenuGroup]) and a darker band between them, an optional
 * [header] (the song) as its first group. It grows out of the button with a
 * soft spring and fades in, as iOS menus do; tapping anywhere else shrinks it
 * back into the button before it closes. Long menus scroll.
 */
@Composable
internal fun IosMenuDialog(
    onDismiss: () -> Unit,
    header: (@Composable () -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    val windowSize = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
    val windowWidth = with(density) { windowSize.width.toDp() }
    val windowHeight = with(density) { windowSize.height.toDp() }
    val anchor = remember { MenuAnchor.lastPress }
    // One menu everywhere (desktop and Android, portrait and landscape): the compact
    // layout of the right-click menu. Only where it opens differs: a right click opens it
    // at the pointer, the ⋯ button next to the button (growing out of it, as in Apple Music).
    val atPointer = remember { MenuAnchor.lastPressSecondary }
    val dense = true
    val style = remember { MenuStyle(dense = true) }
    val placement = remember { MenuPlacement() }
    val position = remember(anchor, density, atPointer) {
        with(density) {
            AnchoredMenuPosition(
                anchor = anchor,
                gapPx = (if (atPointer) 4.dp else 20.dp).roundToPx(),
                rightOverhangPx = 22.dp.roundToPx(),
                marginPx = MenuMargin.roundToPx(),
                atPointer = atPointer,
                placement = placement,
            )
        }
    }
    val progress = remember { androidx.compose.animation.core.Animatable(0f) }
    var closing by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
    val latestDismiss by androidx.compose.runtime.rememberUpdatedState(onDismiss)
    // Timing measured from Apple Music (60 fps recording): it opens in about 180 ms with
    // a slight bounce at the end, and closes faster, in about 110 ms.
    androidx.compose.runtime.LaunchedEffect(closing) {
        if (!closing) {
            progress.animateTo(1f, spring(dampingRatio = 0.72f, stiffness = 900f))
        } else {
            progress.animateTo(0f, tween(110, easing = androidx.compose.animation.core.FastOutLinearInEasing))
            latestDismiss()
        }
    }
    Popup(popupPositionProvider = position, onDismissRequest = { closing = true }, properties = PopupProperties(focusable = true)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalMenuStyle provides style) {
            Column(
                Modifier
                    .graphicsLayer {
                        // As in Apple Music: the menu unfolds out of the button, more in height
                        // (from ~40 %) than in width (from ~70 %), see-through and soft at first,
                        // then solid and sharp; a slight overshoot of the spring settles back.
                        // Closing runs the same way back into the button, fading quickly.
                        val p = progress.value
                        transformOrigin = placement.origin
                        val startX = if (atPointer) 0.9f else 0.7f
                        val startY = if (atPointer) 0.85f else 0.4f
                        scaleX = startX + (1f - startX) * p
                        scaleY = startY + (1f - startY) * p
                        alpha = if (closing) ((p - 0.15f) / 0.85f).coerceIn(0f, 1f) else (p / 0.45f).coerceIn(0f, 1f)
                        val softness = (1f - p).coerceIn(0f, 1f)
                        renderEffect = if (!atPointer && softness > 0.02f) {
                            val radius = 10.dp.toPx() * softness
                            androidx.compose.ui.graphics.BlurEffect(radius, radius, androidx.compose.ui.graphics.TileMode.Decal)
                        } else null
                    }
                    .width(style.width.coerceAtMost((windowWidth - MenuMargin * 2).coerceAtLeast(160.dp)))
                    .heightIn(max = (windowHeight - MenuMargin * 2).coerceAtLeast(200.dp))
                    .shadow(if (dense) 14.dp else 28.dp, style.shape, ambientColor = Color.Black.copy(alpha = 0.4f), spotColor = Color.Black.copy(alpha = 0.5f))
                    .clip(style.shape)
                    .background(style.card)
                    .border(0.6.dp, Color.White.copy(alpha = if (dense) 0.14f else 0.06f), style.shape)
                    .verticalScroll(rememberScrollState()),
            ) {
                // Every group starts with a band; the first one is pulled out of sight.
                Column(
                    Modifier.layout { measurable, constraints ->
                        val gap = style.gap.roundToPx()
                        val placeable = measurable.measure(constraints)
                        layout(placeable.width, (placeable.height - gap).coerceAtLeast(0)) { placeable.place(0, -gap) }
                    },
                ) {
                    if (header != null) {
                        MenuGap()
                        Box(Modifier.fillMaxWidth().padding(horizontal = style.rowPadH, vertical = if (dense) 7.dp else 10.dp)) { header() }
                    }
                    content()
                }
            }
        }
    }
}

/** The darker band between two groups. */
@Composable
private fun MenuGap() {
    Box(Modifier.fillMaxWidth().height(LocalMenuStyle.current.gap).background(MenuGroupGap))
}

/** The song as the first group of a menu (song lists): artwork, title and artist. */
@Composable
internal fun IosMenuTrackHeader(track: Track) {
    val dense = LocalMenuStyle.current.dense
    Row(verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUrl, Modifier.size(if (dense) 32.dp else 40.dp), RoundedCornerShape(if (dense) 4.dp else 6.dp))
        Column(Modifier.padding(start = 10.dp).weight(1f)) {
            TrackTitleText(track, color = Color.White, fontSize = if (dense) 13.sp else 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            ExplicitArtistText(track, color = MenuSecondary, fontSize = if (dense) 11.sp else 13.sp)
        }
    }
}

/** A group of rows (after a darker band), with hairlines between them. */
@Composable
internal fun IosMenuGroup(content: @Composable () -> Unit) {
    MenuGap()
    val bounds = remember { ArrayList<Int>() }
    Layout(
        content = content,
        modifier = Modifier
            .fillMaxWidth()
            .drawWithContent {
                drawContent()
                val line = 0.6.dp.toPx().coerceAtLeast(1f)
                for (y in bounds) drawRect(MenuHairline, Offset(0f, y - line / 2f), Size(size.width, line))
            },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints.copy(minHeight = 0)) }
        val width = constraints.maxWidth.takeIf { it != Constraints.Infinity } ?: (placeables.maxOfOrNull { it.width } ?: 0)
        val height = placeables.sumOf { it.height }
        layout(width, height) {
            bounds.clear()
            var y = 0
            placeables.forEachIndexed { index, placeable ->
                if (index > 0 && placeable.height > 0) bounds += y
                placeable.place(0, y)
                y += placeable.height
            }
        }
    }
}

/**
 * One menu row: the label (and a smaller line under it) on the left, an
 * optional value, then the icon on the right.
 */
@Composable
internal fun IosMenuRow(
    label: String,
    icon: ImageVector?,
    onClick: () -> Unit,
    subtitle: String? = null,
    value: String? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val color = if (destructive) Color(0xFFFF453A) else Color.White
    val style = LocalMenuStyle.current
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = style.rowMinHeight)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = style.rowPadH, vertical = style.rowPadV),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(label, color = color.copy(alpha = if (enabled) 1f else 0.4f), fontSize = style.label, lineHeight = style.labelLine, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) Text(subtitle, color = MenuSecondary, fontSize = style.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) {
            Text(value, color = MenuSecondary, fontSize = style.value, maxLines = 1, modifier = Modifier.padding(start = 10.dp))
            Spacer(Modifier.width(if (style.dense) 6.dp else 8.dp))
        }
        if (trailing != null) {
            Spacer(Modifier.width(10.dp))
            trailing()
        } else if (icon != null) {
            Spacer(Modifier.width(if (style.dense) 10.dp else 12.dp))
            Icon(icon, null, tint = color, modifier = Modifier.size(style.icon))
        }
    }
}

/** A row with an on/off switch instead of an icon. */
@Composable
internal fun IosMenuToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit, subtitle: String? = null) {
    val dense = LocalMenuStyle.current.dense
    IosMenuRow(label, null, onClick = { onCheckedChange(!checked) }, subtitle = subtitle) {
        // Smaller in the compact menu (drawn smaller, laid out in a small box).
        Box(if (dense) Modifier.size(34.dp, 20.dp) else Modifier, contentAlignment = Alignment.Center) {
        androidx.compose.material.Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = if (dense) Modifier.wrapContentSize(unbounded = true).scale(0.68f) else Modifier,
            colors = androidx.compose.material.SwitchDefaults.colors(
                checkedThumbColor = Color.White,
                checkedTrackColor = Color(0xFF34C759),
                checkedTrackAlpha = 1f,
                uncheckedThumbColor = Color.White,
                uncheckedTrackColor = Color.White.copy(alpha = 0.3f),
            ),
        )
        }
    }
}

/**
 * Small buttons side by side (icon over a short label), like the top of an iOS
 * menu: soft rounded glass chips with a little room between them, which sink
 * and light up while pressed.
 */
@Composable
internal fun IosMenuTiles(vararg tiles: Triple<String, ImageVector, () -> Unit>) {
    MenuGap()
    val style = LocalMenuStyle.current
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        tiles.forEach { (label, icon, action) ->
            MenuTile(label, icon, action, Modifier.weight(1f).height(style.tileHeight - 8.dp))
        }
    }
}

@Composable
private fun MenuTile(label: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier) {
    val style = LocalMenuStyle.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val press by animateFloatAsState(
        if (pressed) 1f else 0f,
        spring(dampingRatio = 0.62f, stiffness = 700f),
        label = "menu-tile-press",
    )
    val shape = RoundedCornerShape(if (style.dense) 10.dp else 12.dp)
    Column(
        modifier
            .graphicsLayer {
                val scale = 1f - 0.06f * press
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(Color.White.copy(alpha = 0.075f + 0.10f * press))
            .border(0.6.dp, Color.White.copy(alpha = 0.07f), shape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Icon and label close together, as one small unit.
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(style.tileIcon))
        Spacer(Modifier.height(if (style.dense) 1.dp else 2.dp))
        Text(
            label,
            color = Color.White.copy(alpha = 0.86f),
            fontSize = style.tileText,
            lineHeight = style.tileText,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A line of small grey text inside a group (a section title or a hint). */
@Composable
internal fun IosMenuCaption(text: String) {
    val style = LocalMenuStyle.current
    Text(
        text,
        color = MenuSecondary,
        fontSize = style.subtitle,
        fontWeight = FontWeight.Medium,
        modifier = Modifier.fillMaxWidth().padding(start = style.rowPadH, end = style.rowPadH, top = if (style.dense) 6.dp else 10.dp, bottom = 2.dp),
    )
}
