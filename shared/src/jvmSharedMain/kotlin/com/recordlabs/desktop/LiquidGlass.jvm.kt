package com.recordlabs.desktop

import androidx.compose.ui.graphics.RenderEffect

// Desktop and Android keep their own glass (see LiquidGlass.kt).
internal actual val liquidGlassSupported: Boolean get() = false

internal actual fun liquidGlassRenderEffect(
    width: Float, height: Float, margin: Float, density: Float,
    touchX: Float, touchY: Float, press: Float,
    validLeft: Float, validTop: Float, validRight: Float, validBottom: Float,
): RenderEffect? = null
