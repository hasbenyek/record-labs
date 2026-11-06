package com.recordlabs.desktop

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.recordlabs.desktop.platform.AppPlatform

/**
 * iPhone: the app fills the whole screen, under the status bar and the home
 * indicator (like the iPhone's own apps). The pages' backgrounds and the player's
 * artwork colours reach the screen's edges; what can be touched or read stays clear
 * of the status bar, the notch and the home indicator.
 *
 * The desktop and Android keep the whole app inside the safe area, as before: there
 * every value here is zero and every modifier leaves the layout as it was.
 */
@Immutable
internal data class EdgeInsets(
    /** The status bar's height (the app draws under it). */
    val top: Dp = 0.dp,
    /** The home indicator's height (the app draws under it). */
    val bottom: Dp = 0.dp,
)

internal val LocalEdgeInsets = staticCompositionLocalOf { EdgeInsets() }

/** The status bar and home indicator the app draws under (iPhone; zero elsewhere). */
@Composable
internal fun rememberEdgeInsets(): EdgeInsets {
    if (!AppPlatform.isIos) return EdgeInsets()
    val density = LocalDensity.current
    val top = WindowInsets.statusBars.getTop(density)
    val bottom = WindowInsets.navigationBars.getBottom(density)
    return with(density) { EdgeInsets(top.toDp(), bottom.toDp()) }
}

/**
 * The app's root: inside the safe area on the desktop and Android (as before). iPhone:
 * only the sides (the notch in landscape) and the keyboard; the status bar and home
 * indicator are left to [LocalEdgeInsets].
 */
@Composable
internal fun Modifier.appSafeArea(): Modifier =
    if (!AppPlatform.isIos) safeDrawingPadding()
    else this
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
        // The keyboard, less the home indicator it covers (the pages already leave room for that).
        .windowInsetsPadding(WindowInsets.ime.exclude(WindowInsets.navigationBars).only(WindowInsetsSides.Bottom))

/**
 * A page (home, library, album, profile...) under the status bar: it starts below it.
 * Portrait pages run on to the bottom of the screen (the floating navigation keeps
 * clear of the home indicator); landscape pages stop above it.
 */
@Composable
internal fun Modifier.pageEdgePadding(compact: Boolean): Modifier {
    val edge = LocalEdgeInsets.current
    if (edge.top == 0.dp && edge.bottom == 0.dp) return this
    return padding(top = edge.top, bottom = if (compact) 0.dp else edge.bottom)
}

/** The player and lyrics pages: their content stays clear of the status bar and home indicator. */
@Composable
internal fun Modifier.playerEdgePadding(): Modifier {
    val edge = LocalEdgeInsets.current
    if (edge.top == 0.dp && edge.bottom == 0.dp) return this
    return padding(top = edge.top, bottom = edge.bottom)
}

/**
 * A background drawn past its box, up under the status bar ([top]) and down under the
 * home indicator ([bottom]), while the page's content stays inside the box.
 */
internal fun Modifier.bleedEdges(top: Dp, bottom: Dp): Modifier =
    if (top <= 0.dp && bottom <= 0.dp) this
    else layout { measurable, constraints ->
        if (!constraints.hasBoundedHeight || !constraints.hasBoundedWidth) {
            val placeable = measurable.measure(constraints)
            return@layout layout(placeable.width, placeable.height) { placeable.place(0, 0) }
        }
        val t = top.roundToPx().coerceAtLeast(0)
        val b = bottom.roundToPx().coerceAtLeast(0)
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val placeable = measurable.measure(Constraints.fixed(width, height + t + b))
        layout(width, height) { placeable.place(0, -t) }
    }

/** [bleedEdges] by the player's own padding ([playerEdgePadding]). */
@Composable
internal fun Modifier.playerBackgroundBleed(): Modifier {
    val edge = LocalEdgeInsets.current
    return bleedEdges(edge.top, edge.bottom)
}
