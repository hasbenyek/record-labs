package com.recordlabs.desktop

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

// Desktop side of LiquidGlass.kt: the same Skia shader as on the iPhone (Settings >
// Appearance > Liquid glass navigation turns it on).

// The shader (LIQUID_GLASS_SKSL) is in LiquidGlass.kt, shared with the desktop and Android.

private val liquidGlassEffect: RuntimeEffect? by lazy {
    runCatching { RuntimeEffect.makeForShader(LIQUID_GLASS_SKSL) }
        .onFailure { println("Liquid glass shader unavailable: ${it.message}") }
        .getOrNull()
}

internal actual val liquidGlassSupported: Boolean get() = liquidGlassEffect != null

internal actual fun liquidGlassRenderEffect(
    width: Float, height: Float, margin: Float, density: Float,
    touchX: Float, touchY: Float, press: Float,
    validLeft: Float, validTop: Float, validRight: Float, validBottom: Float,
    cornerRadius: Float,
    frosted: Boolean,
    clear: Boolean,
): RenderEffect? {
    val effect = liquidGlassEffect ?: return null
    if (width <= 0f || height <= 0f) return null
    return runCatching {
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("origin", margin, margin)
        builder.uniform("size", width, height)
        // Pills and circles: fully rounded; panels (menus, dialogs): their own corners.
        builder.uniform("radius", if (cornerRadius >= 0f) cornerRadius else minOf(width, height) / 2f)
        // The bent rim: about 18 dp wide, bending the page by up to 16 dp (a panel's rim
        // is narrower: its text sits close to the edge).
        builder.uniform("bezel", (if (frosted) 12f else 18f) * density)
        builder.uniform("bend", (if (frosted) 10f else 16f) * density)
        // A panel is frosted and darker, so its text reads over any page.
        builder.uniform("tintMul", if (frosted) 0.42f else if (clear) 0.94f else 0.60f)
        builder.uniform("tintAdd", if (frosted) 0.055f else if (clear) 0.012f else 0.035f)
        builder.uniform("sat", if (frosted) 1.55f else if (clear) 1.12f else 1.35f)
        builder.uniform("dpx", density)
        builder.uniform("touch", touchX + margin, touchY + margin)
        builder.uniform("press", press.coerceIn(0f, 1f))
        // Only the page: the blur and the bent rim stretch its edge, never the empty side.
        builder.uniform("validMin", validLeft + 0.5f, validTop + 0.5f)
        builder.uniform("validMax", maxOf(validLeft + 1f, validRight - 0.5f), maxOf(validTop + 1f, validBottom - 0.5f))
        val crop = org.jetbrains.skia.IRect.makeLTRB(validLeft.toInt(), validTop.toInt(), validRight.toInt(), validBottom.toInt())
        val sigma = (if (frosted) 16f else 2f) * density
        val blur = ImageFilter.makeBlur(sigma, sigma, FilterTileMode.CLAMP, null, crop)
        ImageFilter.makeRuntimeShader(builder, arrayOf("content"), arrayOf(blur)).asComposeRenderEffect()
    }.onFailure { println("Liquid glass unavailable: ${it.message}") }.getOrNull()
}
