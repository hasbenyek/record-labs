package com.recordlabs.desktop

import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

// Android side of LiquidGlass.kt: the iPhone's glass shader as an AGSL RuntimeShader
// (Android 13+), turned on with Settings > Appearance > Liquid glass navigation.

/** The shader compiles (checked once); older Android versions keep their own glass. */
private val liquidGlassCompiles: Boolean by lazy {
    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
        runCatching { RuntimeShader(LIQUID_GLASS_SKSL) }
            .onFailure { println("Liquid glass shader unavailable: ${it.message}") }
            .isSuccess
}

internal actual val liquidGlassSupported: Boolean get() = liquidGlassCompiles

internal actual fun liquidGlassRenderEffect(
    width: Float, height: Float, margin: Float, density: Float,
    touchX: Float, touchY: Float, press: Float,
    validLeft: Float, validTop: Float, validRight: Float, validBottom: Float,
    cornerRadius: Float,
    frosted: Boolean,
    clear: Boolean,
): RenderEffect? {
    if (!liquidGlassCompiles || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return null
    if (width <= 0f || height <= 0f) return null
    return runCatching {
        val shader = RuntimeShader(LIQUID_GLASS_SKSL)
        // The same values as the iPhone's (LiquidGlass.ios.kt).
        shader.setFloatUniform("origin", margin, margin)
        shader.setFloatUniform("size", width, height)
        shader.setFloatUniform("radius", if (cornerRadius >= 0f) cornerRadius else minOf(width, height) / 2f)
        shader.setFloatUniform("bezel", (if (frosted) 12f else 18f) * density)
        shader.setFloatUniform("bend", (if (frosted) 10f else 16f) * density)
        shader.setFloatUniform("tintMul", if (frosted) 0.42f else if (clear) 0.94f else 0.60f)
        shader.setFloatUniform("tintAdd", if (frosted) 0.055f else if (clear) 0.012f else 0.035f)
        shader.setFloatUniform("sat", if (frosted) 1.55f else if (clear) 1.12f else 1.35f)
        shader.setFloatUniform("seeThrough", if (clear) 1f else 0f)
        shader.setFloatUniform("dpx", density)
        shader.setFloatUniform("touch", touchX + margin, touchY + margin)
        shader.setFloatUniform("press", press.coerceIn(0f, 1f))
        shader.setFloatUniform("validMin", validLeft + 0.5f, validTop + 0.5f)
        shader.setFloatUniform("validMax", maxOf(validLeft + 1f, validRight - 0.5f), maxOf(validTop + 1f, validBottom - 0.5f))
        val sigma = (if (frosted) 16f else 2f) * density
        val blur = android.graphics.RenderEffect.createBlurEffect(sigma, sigma, Shader.TileMode.CLAMP)
        val glass = android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content")
        // The blur first, then the glass over it.
        android.graphics.RenderEffect.createChainEffect(glass, blur).asComposeRenderEffect()
    }.onFailure { println("Liquid glass unavailable: ${it.message}") }.getOrNull()
}
