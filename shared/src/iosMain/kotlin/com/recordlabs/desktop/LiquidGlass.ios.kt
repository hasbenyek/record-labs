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
// Liquid glass (dark, like Telegram's): the (blurred) page behind, bent at the rim like a
// glass lens, a little colour split at the edge, a dark tint, a light rim, and a soft
// light where the finger holds the glass (it moves with the finger).
uniform shader content;
uniform float2 origin;     // the glass's top-left inside the recorded area (px)
uniform float2 size;       // the glass's size (px)
uniform float radius;      // corner radius (px)
uniform float bezel;       // width of the bent rim (px)
uniform float bend;        // how far the rim bends the page (px)
uniform float dpx;         // one dp in px
uniform float2 touch;      // the finger, in the recorded area (px)
uniform float press;       // 0 (not held) .. 1 (held)
uniform float2 validMin;   // the page's part of the recorded area (outside it: nothing)
uniform float2 validMax;

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
    // A round lens edge; held, the glass bends a little more (it swells).
    float lens = bend * (1.0 + 0.35 * press) * (1.0 - sqrt(max(1.0 - x * x, 0.0)));
    // The rim shows what is just outside it, bent in; each colour a little apart.
    half4 col;
    col.r = content.eval(clamp(coord + n * lens * 1.00, validMin, validMax)).r;
    col.g = content.eval(clamp(coord + n * lens * 1.12, validMin, validMax)).g;
    col.b = content.eval(clamp(coord + n * lens * 1.26, validMin, validMax)).b;
    col.a = 1.0;
    // More colour, then the dark glass tint (the page still shows through).
    half l = dot(col.rgb, half3(0.299, 0.587, 0.114));
    col.rgb = mix(half3(l), col.rgb, 1.35);
    col.rgb = col.rgb * 0.60 + half3(0.035);
    // Light from the top left, or from the finger while it holds the glass.
    float2 toTouch = touch - coord;
    float2 lightDir = normalize(mix(float2(-0.6, -0.8), length(toTouch) > 1.0 ? normalize(toTouch) : float2(-0.6, -0.8), press));
    float facing = dot(n, lightDir);
    float rimBand = 1.0 - smoothstep(0.0, 1.5 * dpx, inside);
    float glowBand = 1.0 - smoothstep(0.0, 10.0 * dpx, inside);
    float rim = rimBand * (0.22 + 0.60 * pow(max(facing, 0.0), 1.5) + 0.18 * pow(max(-facing, 0.0), 2.0));
    float glow = glowBand * 0.08 * (0.4 + 0.6 * max(facing, 0.0));
    // The finger's light: a soft spot under it while held.
    float spot = press * 0.16 * exp(-dot(toTouch, toTouch) / (2.0 * (34.0 * dpx) * (34.0 * dpx)));
    col.rgb += half3(rim + glow + spot);
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

internal actual fun liquidGlassRenderEffect(
    width: Float, height: Float, margin: Float, density: Float,
    touchX: Float, touchY: Float, press: Float,
    validLeft: Float, validTop: Float, validRight: Float, validBottom: Float,
): RenderEffect? {
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
        builder.uniform("touch", touchX + margin, touchY + margin)
        builder.uniform("press", press.coerceIn(0f, 1f))
        // Only the page: the blur and the bent rim stretch its edge, never the empty side.
        builder.uniform("validMin", validLeft + 0.5f, validTop + 0.5f)
        builder.uniform("validMax", maxOf(validLeft + 1f, validRight - 0.5f), maxOf(validTop + 1f, validBottom - 0.5f))
        val crop = org.jetbrains.skia.IRect.makeLTRB(validLeft.toInt(), validTop.toInt(), validRight.toInt(), validBottom.toInt())
        val blur = ImageFilter.makeBlur(2f * density, 2f * density, FilterTileMode.CLAMP, null, crop)
        ImageFilter.makeRuntimeShader(builder, arrayOf("content"), arrayOf(blur)).asComposeRenderEffect()
    }.onFailure { println("Liquid glass unavailable: ${it.message}") }.getOrNull()
}
