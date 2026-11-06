package com.recordlabs.desktop

import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ShaderBrush
import coil3.toBitmap
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

// Desktop side of AmbientArtworkBackground.kt: the shader runs in Skia.

private class SkiaAmbientShaderRenderer(private val builder: RuntimeShaderBuilder) : AmbientShaderRenderer {
    // The shader of the frame on screen; the one before it is released.
    private var lastShader: org.jetbrains.skia.Shader? = null

    override fun brush(width: Float, height: Float, time: Float, spread: Float, colors: FloatArray, seeds: FloatArray): Brush {
        builder.uniform("resolution", width, height)
        builder.uniform("time", time)
        builder.uniform("spread", spread)
        builder.uniform("colors", colors)
        builder.uniform("seeds", seeds)
        val shader = builder.makeShader()
        lastShader?.close()
        lastShader = shader
        return ShaderBrush(shader)
    }

    override fun close() {
        lastShader?.close()
        lastShader = null
        builder.close()
    }
}

private val ambientEffects = HashMap<String, RuntimeEffect?>()

internal actual fun createAmbientShaderRenderer(source: String): AmbientShaderRenderer? {
    val effect = com.recordlabs.desktop.platform.locked(ambientEffects) {
        ambientEffects.getOrPut(source) {
            runCatching { RuntimeEffect.makeForShader(source) }
                .onFailure { println("Ambient background shader unavailable: ${it.message}") }
                .getOrNull()
        }
    } ?: return null
    return runCatching { SkiaAmbientShaderRenderer(RuntimeShaderBuilder(effect)) }.getOrNull()
}

internal actual fun coil3.Image.argbPixels(): IntArray {
    val bitmap = toBitmap()
    return IntArray(bitmap.width * bitmap.height) { i -> bitmap.getColor(i % bitmap.width, i / bitmap.width) }
}
