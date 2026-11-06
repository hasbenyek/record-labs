
package com.recordlabs.desktop

import org.jetbrains.skia.*
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.RenderEffect

fun testRuntimeShaderBuilder(density: Float): RenderEffect? {
    val normalFilter = ImageFilter.makeOffset(0f, 0f, null, null)
    val blurFilter = ImageFilter.makeBlur(30f, 30f, FilterTileMode.CLAMP, null, null)
    return blurFilter.asComposeRenderEffect()
}
