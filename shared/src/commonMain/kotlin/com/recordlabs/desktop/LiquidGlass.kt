package com.recordlabs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil

/**
 * Liquid glass for the floating navigation and mini player (iPhone).
 *
 * Apple's own Liquid Glass material only exists from iOS 26, and it is a UIKit view: it
 * cannot sit between the page and the navigation's icons, which Compose draws in one
 * layer. So the glass is drawn here, the same on iOS 15 to 26 (as Telegram does on
 * older iPhones): the page behind is recorded ([glassBackdropSource]), blurred, bent at
 * the rim like a glass lens with a little colour split, tinted, and lit along its edge
 * (the shader is in LiquidGlass.ios.kt).
 *
 * The desktop and Android keep their glass as it was ([liquidGlassRenderEffect] is null there).
 */
internal class GlassBackdrop(val layer: GraphicsLayer) {
    /** Where the recorded page is, in the window. */
    var originInWindow by mutableStateOf(Offset.Zero)
    /** Changes each time the page is recorded again, so the glass over it is redrawn. */
    var version by mutableIntStateOf(0)

    /**
     * A list scrolling inside the page redraws only itself, not the page (whose
     * recording shows the list live): every scroll step redraws the glass too.
     */
    val scrollWatcher = object : NestedScrollConnection {
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            if (consumed != Offset.Zero) version++
            return Offset.Zero
        }
    }
}

internal val LocalGlassBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/**
 * The glass effect for a fully rounded shape (pill or circle) of [width] x [height] px,
 * reading a recording [margin] px larger on every side; null where the platform keeps
 * its own glass.
 */
internal expect fun liquidGlassRenderEffect(width: Float, height: Float, margin: Float, density: Float): RenderEffect?

/** Whether this platform draws the liquid glass ([liquidGlassRenderEffect]). */
internal expect val liquidGlassSupported: Boolean

/** A recorder for the page under the glass, or null where the glass is not drawn. */
@Composable
internal fun rememberGlassBackdrop(): GlassBackdrop? {
    if (!liquidGlassSupported) return null
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { GlassBackdrop(layer) }
    // Other changes under the glass (a picture loading, an animation) show within a
    // moment: only the glass is redrawn, not the page.
    LaunchedEffect(backdrop) {
        while (isActive) {
            delay(400)
            backdrop.version++
        }
    }
    return backdrop
}

/** Records the page this is on so the glass above it can show it (drawn as before). */
internal fun Modifier.glassBackdropSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else this
        .nestedScroll(backdrop.scrollWatcher)
        .onGloballyPositioned { backdrop.originInWindow = it.positionInWindow() }
        .drawWithContent {
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
            backdrop.version++
        }

/** The liquid glass behind a pill or circle (the dock's surfaces), over the recorded page. */
@Composable
internal fun Modifier.liquidGlassBackground(backdrop: GlassBackdrop?): Modifier {
    if (backdrop == null) return this
    val glass = rememberGraphicsLayer()
    val state = remember { object { var origin = Offset.Zero; var effect: RenderEffect? = null; var effectKey = 0L } }
    return this
        .onGloballyPositioned { state.origin = it.positionInWindow() }
        .drawBehind {
            // Redrawn whenever the page under it is recorded again.
            @Suppress("UNUSED_VARIABLE") val version = backdrop.version
            val marginPx = 24.dp.roundToPx()
            val margin = marginPx.toFloat()
            val key = (size.width.toLong() shl 32) or size.height.toLong()
            if (state.effect == null || state.effectKey != key) {
                state.effect = liquidGlassRenderEffect(size.width, size.height, margin, density)
                state.effectKey = key
            }
            val effect = state.effect ?: return@drawBehind
            glass.renderEffect = effect
            val recordSize = IntSize(ceil(size.width + 2 * margin).toInt(), ceil(size.height + 2 * margin).toInt())
            val shift = backdrop.originInWindow - state.origin
            glass.record(recordSize) {
                translate(margin + shift.x, margin + shift.y) { drawLayer(backdrop.layer) }
            }
            glass.topLeft = IntOffset(-marginPx, -marginPx)
            drawLayer(glass)
        }
}
