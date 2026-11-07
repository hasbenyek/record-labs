package com.recordlabs.desktop

import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.RuntimeEffect
import org.jetbrains.skia.RuntimeShaderBuilder

// iPhone side of LiquidGlass.kt: the glass is a Skia shader (Metal) over the page
// behind, blurred a little first.

private const val LIQUID_GLASS_SKSL = """
// Liquid glass: the (blurred) page behind, bent at the rim like a glass lens, a little
// colour split at the edge, a soft tint, and a bright rim where the light falls.
uniform shader content;
uniform float2 origin;     // the glass's top-left inside the recorded area (px)
uniform float2 size;       // the glass's size (px)
uniform float radius;      // corner radius (px)
uniform float bezel;       // width of the bent rim (px)
uniform float bend;        // how far the rim bends the page (px)
uniform float dpx;         // one dp in px

float sdRound(float2 p, float2 c, float2 h, float r) {
    float2 q = abs(p - c) - (h - r);
    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
}

half4 main(float2 coord) {
    float2 c = origin + size * 0.5;
    float2 h = size * 0.5;
    float r = min(radius, min(h.x, h.y));
    float d = sdRound(coord, c, h, r);
    if (d > 0.5) return half4(0.0);
    // Outward normal of the edge (the shape's gradient).
    float e = 0.75;
    float2 n = float2(
        sdRound(coord + float2(e, 0.0), c, h, r) - sdRound(coord - float2(e, 0.0), c, h, r),
        sdRound(coord + float2(0.0, e), c, h, r) - sdRound(coord - float2(0.0, e), c, h, r));
    float nl = length(n);
    n = nl > 0.0001 ? n / nl : float2(0.0, -1.0);
    float inside = -d;                                  // px from the edge, inwards
    float x = clamp(1.0 - inside / bezel, 0.0, 1.0);    // 1 at the edge, 0 past the rim
    float lens = bend * (1.0 - sqrt(max(1.0 - x * x, 0.0)));   // a round lens edge
    // The rim shows what is just outside it, bent in; each colour a little apart.
    half4 col;
    col.r = content.eval(coord + n * lens * 1.00).r;
    col.g = content.eval(coord + n * lens * 1.10).g;
    col.b = content.eval(coord + n * lens * 1.22).b;
    col.a = 1.0;
    // A touch more colour, then the dark glass tint.
    half l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, 1.25);
    col.rgb = col.rgb * 0.78 + half3(0.075);
    // Light from the top left: a bright thin rim there and at the opposite corner,
    // a soft glow just inside the edge.
    float facing = dot(n, normalize(float2(-0.6, -0.8)));
    float rimBand = 1.0 - smoothstep(0.0, 1.6 * dpx, inside);
    float glowBand = 1.0 - smoothstep(0.0, 9.0 * dpx, inside);
    float rim = rimBand * (0.28 + 0.62 * pow(abs(facing), 2.0));
    float glow = glowBand * 0.10 * (0.5 + 0.5 * facing);
    col.rgb += half3(rim + glow);
    // Soft edge (anti-aliased).
    float cover = clamp(0.5 - d, 0.0, 1.0);
    return half4(clamp(col.rgb, 0.0, 1.0) * cover, cover);
}
"""

private val liquidGlassEffect: RuntimeEffect? by lazy {
    runCatching { RuntimeEffect.makeForShader(LIQUID_GLASS_SKSL) }
        .onFailure { println("Liquid glass shader unavailable: ${it.message}") }
        .getOrNull()
}

internal actual val liquidGlassSupported: Boolean get() = liquidGlassEffect != null

internal actual fun liquidGlassRenderEffect(width: Float, height: Float, margin: Float, density: Float): RenderEffect? {
    val effect = liquidGlassEffect ?: return null
    if (width <= 0f || height <= 0f) return null
    return runCatching {
        val builder = RuntimeShaderBuilder(effect)
        builder.uniform("origin", margin, margin)
        builder.uniform("size", width, height)
        // Pills and circles: fully rounded.
        builder.uniform("radius", minOf(width, height) / 2f)
        // The bent rim: about 18 dp wide, bending the page by up to 16 dp.
        builder.uniform("bezel", 18f * density)
        builder.uniform("bend", 16f * density)
        builder.uniform("dpx", density)
        val blur = ImageFilter.makeBlur(2f * density, 2f * density, FilterTileMode.CLAMP, null, null)
        ImageFilter.makeRuntimeShader(builder, arrayOf("content"), arrayOf(blur)).asComposeRenderEffect()
    }.onFailure { println("Liquid glass unavailable: ${it.message}") }.getOrNull()
}
