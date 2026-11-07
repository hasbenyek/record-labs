package com.recordlabs.desktop

import androidx.compose.ui.graphics.RenderEffect

// Desktop and Android keep their own glass (see LiquidGlass.kt).
internal actual val liquidGlassSupported: Boolean get() = false

internal actual fun liquidGlassRenderEffect(
    width: Float, height: Float, margin: Float, density: Float,
    touchX: Float, touchY: Float, press: Float,
    validLeft: Float, validTop: Float, validRight: Float, validBottom: Float,
    cornerRadius: Float,
    frosted: Boolean,
): RenderEffect? = null

/** San Francisco is the iPhone's; the desktop and Android keep their own fonts. */
internal actual object AppleSystemFont {
    actual val available: Boolean get() = false
    actual val hasOpticalSizes: Boolean get() = false
    actual fun family(weight: Int, display: Boolean): androidx.compose.ui.text.font.FontFamily? = null
}
