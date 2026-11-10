package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.layout.layout
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.cbrt
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Background of the player and lyrics pages: a few colours taken from the
 * artwork (small but vivid areas get the same share as large ones), drifting
 * slowly into each other. Drawn directly by the graphics card in one pass,
 * with no picture and no blur.
 */
@Composable
internal fun AmbientArtworkBackground(artworkModel: Any?, baseColor: Color, modifier: Modifier = Modifier.fillMaxSize()) {
    val platformContext = LocalPlatformContext.current
    val cacheKey = artworkModel?.toString()
    // Colours of the 16 control points (OKLab), blended from [from] to [to].
    val start = remember { AmbientPalettes.cached(cacheKey) ?: AmbientPalettes.neutral }
    var from by remember { mutableStateOf(start) }
    var to by remember { mutableStateOf(start) }
    val blend = remember { Animatable(1f) }
    LaunchedEffect(cacheKey) {
        val palette = if (artworkModel == null) AmbientPalettes.neutral
        else AmbientPalettes.load(platformContext, artworkModel, cacheKey!!) ?: AmbientPalettes.neutral
        if (palette === to && blend.value >= 1f) return@LaunchedEffect
        from = AmbientPalettes.mix(from, to, blend.value)
        to = palette
        blend.snapTo(0f)
        blend.animateTo(1f, tween(AMBIENT_COLOR_CHANGE_MS, easing = LinearEasing))
    }
    // About 30 redraws a second while the main window is on screen.
    var frame by remember { mutableStateOf(0) }
    val windowShown = MainWindowActivity.isShown
    LaunchedEffect(windowShown) {
        if (!windowShown || !AmbientSwitch.enabled) return@LaunchedEffect
        while (true) {
            delay(AMBIENT_FRAME_MS)
            frame++
        }
    }
    val builder = remember { if (AmbientSwitch.enabled) AmbientShader.renderer() else null }
    DisposableEffect(builder) {
        onDispose { builder?.close() }
    }
    // Phones: the colours are worked out for a picture a third of the size and then
    // stretched (smoothly) over the screen. The gradients are so soft that it looks
    // the same, for 1/9 of the graphics work per frame (on slower phones this
    // full-screen shader alone took a large part of each frame). Not smaller: the
    // shader's fine grain against colour banding has to survive the stretch.
    val downscale = AMBIENT_DOWNSCALE
    androidx.compose.foundation.layout.Box(modifier.background(baseColor).clipToBounds()) {
        Spacer(
            Modifier
                // A third of the box, rounded up (scaled back it covers the box whole).
                .layout { measurable, constraints ->
                    val width = kotlin.math.ceil(constraints.maxWidth / downscale).toInt()
                    val height = kotlin.math.ceil(constraints.maxHeight / downscale).toInt()
                    val placeable = measurable.measure(androidx.compose.ui.unit.Constraints.fixed(width, height))
                    layout(constraints.maxWidth, constraints.maxHeight) { placeable.place(0, 0) }
                }
                // Own layer: a new frame redraws only the background, not the page on top.
                // Drawn small in its own buffer, then scaled up when composited.
                .graphicsLayer {
                    if (downscale > 1f) {
                        compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0f)
                        scaleX = downscale
                        scaleY = downscale
                    }
                }
                .drawBehind {
                    @Suppress("UNUSED_EXPRESSION")
                    frame // redraw on every tick
                    val colors = AmbientPalettes.mix(from, to, blend.value)
                    val brush = builder?.brush(size.width, size.height, AmbientClock.seconds(), AMBIENT_SPREAD, colors, AmbientShader.seeds)
                    if (brush == null) drawRect(AmbientPalettes.averageColor(colors)) else drawRect(brush)
                    drawRect(Color.Black.copy(alpha = AMBIENT_DIM))
                },
        )
    }
}

/** Phones draw the background at a third of the size (see above); `debug.recordlabs.ambientscale` overrides it. */
private val AMBIENT_DOWNSCALE: Float =
    AppPlatform.debugProp("ambientscale").toFloatOrNull()?.coerceIn(1f, 16f)
        // The desktop: half size (a quarter of the work); the gradients are soft, so it
        // looks the same and the graphics card has much less to do on large windows.
        ?: if (AppPlatform.isPhone) 3f else 2f

/** Measuring only: `adb shell setprop debug.recordlabs.ambient 0` keeps the background still (read at launch). */
private object AmbientSwitch {
    val enabled: Boolean = AppPlatform.debugProp("ambient") != "0"
}

// Android: fewer redraws (the full-screen colour shader competes with video and
// animated artwork for the GPU; the colours drift slowly either way).
// The desktop: 20 redraws a second (was 30), the colours drift slowly either way.
private val AMBIENT_FRAME_MS = if (AppPlatform.isPhone) 100L else 50L
private const val AMBIENT_COLOR_CHANGE_MS = 1_000
private const val AMBIENT_SPREAD = 0.06f
private const val AMBIENT_DIM = 0.18f

/** Animation time shared by both pages; it stands still while the main window is minimized. */
private object AmbientClock {
    private val startNanos = nanoTime()
    @kotlin.concurrent.Volatile private var pausedNanos = 0L
    @kotlin.concurrent.Volatile private var pausedAt = -1L

    init {
        MainWindowActivity.addListener { shown ->
            locked(this) {
                val now = nanoTime()
                if (!shown && pausedAt < 0L) pausedAt = now
                if (shown && pausedAt >= 0L) {
                    pausedNanos += now - pausedAt
                    pausedAt = -1L
                }
            }
        }
    }

    fun seconds(): Float {
        val now = if (pausedAt >= 0L) pausedAt else nanoTime()
        // Starts over once a day (a single jump) so the value stays precise in long sessions.
        return (((now - startNanos - pausedNanos) / 1_000_000L) % 86_400_000L) / 1000f
    }
}

private object AmbientShader {
    private const val SKSL = """
uniform float2 resolution;
uniform float time;
uniform float spread;
uniform float4 colors[16];   // OKLab in xyz
uniform float4 seeds[16];    // base x, base y, phase a, phase b

float hash(float2 p) { return fract(sin(dot(p, float2(12.9898, 78.233))) * 43758.5453); }

float3 oklabToLinear(float3 c) {
    float l_ = c.x + 0.3963377774 * c.y + 0.2158037573 * c.z;
    float m_ = c.x - 0.1055613458 * c.y - 0.0638541728 * c.z;
    float s_ = c.x - 0.0894841775 * c.y - 1.2914855480 * c.z;
    float l = l_ * l_ * l_; float m = m_ * m_ * m_; float s = s_ * s_ * s_;
    return float3(
        4.0767416621 * l - 3.3077115913 * m + 0.2309699292 * s,
        -1.2684380046 * l + 2.6097574011 * m - 0.3413193965 * s,
        -0.0041960863 * l - 0.7034186147 * m + 1.7076147010 * s);
}
float toSrgb(float c) { c = clamp(c, 0.0, 1.0); return c <= 0.0031308 ? c * 12.92 : 1.055 * pow(c, 1.0 / 2.4) - 0.055; }

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / resolution;
    float aspect = resolution.x / resolution.y;
    float t = time;
    float2 w = uv;
    w += 0.110 * float2(sin(uv.y * 2.4 + t * 0.23), cos(uv.x * 2.0 - t * 0.19));
    w += 0.050 * float2(sin((uv.x + uv.y) * 4.3 - t * 0.31), cos((uv.x - uv.y) * 3.7 + t * 0.27));
    float3 acc = float3(0.0);
    float wsum = 0.0;
    float chromaSum = 0.0;
    for (int i = 0; i < 16; i++) {
        float4 s = seeds[i];
        float2 p = s.xy + 0.17 * float2(
            sin(t * (0.10 + 0.05 * s.z) + s.w * 6.2831),
            cos(t * (0.08 + 0.05 * s.w) + s.z * 6.2831));
        float2 d = w - p;
        d.x *= aspect;
        float wt = exp(-dot(d, d) / spread);
        acc += colors[i].xyz * wt;
        chromaSum += length(colors[i].yz) * wt;
        wsum += wt;
    }
    float3 lab = acc / max(wsum, 1e-5);
    float chroma = length(lab.yz);
    float wanted = chromaSum / max(wsum, 1e-5);
    // (Faded out where the blend is nearly grey, so the hue never flips there.)
    if (chroma > 1e-4) lab.yz *= mix(1.0, wanted / chroma, 0.4 * smoothstep(0.0, 0.03, chroma));
    float3 lin = oklabToLinear(lab);
    float3 c = float3(toSrgb(lin.x), toSrgb(lin.y), toSrgb(lin.z));
    c += (hash(fragCoord + fract(t * 7.0)) - 0.5) * (2.0 / 255.0);
    return half4(half3(c), 1.0);
}
"""

    fun renderer(): AmbientShaderRenderer? = createAmbientShaderRenderer(SKSL)

    /** 4x4 control points spread over (and a little past) the page, with their own drift. */
    val seeds: FloatArray = FloatArray(64).also { seeds ->
        val random = JavaRandom(7L)
        for (row in 0 until 4) for (col in 0 until 4) {
            val i = (row * 4 + col) * 4
            seeds[i] = -0.08f + col * 0.387f + (random.nextFloat() - 0.5f) * 0.18f
            seeds[i + 1] = -0.08f + row * 0.387f + (random.nextFloat() - 0.5f) * 0.18f
            seeds[i + 2] = random.nextFloat()
            seeds[i + 3] = random.nextFloat()
        }
    }
}

/** Artwork colours as 16 control points (OKLab, 4 floats each). */
private object AmbientPalettes {
    private class Lab(val l: Float, val a: Float, val b: Float) {
        val chroma get() = sqrt(a * a + b * b)
        fun dist(o: Lab) = sqrt((l - o.l).pow(2) + (a - o.a).pow(2) + (b - o.b).pow(2))
    }

    private val cache = LruMap<String, FloatArray>(48)

    /** Dark neutral tones for a track without artwork (and until colours arrive). */
    val neutral: FloatArray = points(
        listOf(Lab(0.24f, 0.004f, -0.012f), Lab(0.30f, 0.008f, -0.02f), Lab(0.20f, 0.0f, -0.006f), Lab(0.27f, -0.004f, -0.016f)),
    )

    fun cached(key: String?): FloatArray? = key?.let { locked(cache) { cache[it] } }

    suspend fun load(context: PlatformContext, model: Any, key: String): FloatArray? {
        cached(key)?.let { return it }
        return withContext(IoDispatcher) {
            runCatching {
                val result = SingletonImageLoader.get(context).execute(
                    ImageRequest.Builder(context).data(model).size(SAMPLE).build(),
                ) as? SuccessResult ?: return@runCatching null
                // The whole picture at its decoded size (at most SAMPLE px a side).
                val argb = result.image.argbPixels()
                val pixels = ArrayList<Lab>(argb.size)
                for (c in argb) {
                    if ((c ushr 24) < 128) continue
                    pixels += rgbToOklab(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)
                }
                if (pixels.size < 16) return@runCatching null
                points(pick(pixels))
            }.getOrNull()?.also { palette -> locked(cache) { cache[key] = palette } }
        }
    }

    /** Point colours between two palettes, [t] = 0..1. */
    fun mix(from: FloatArray, to: FloatArray, t: Float): FloatArray {
        if (t >= 1f) return to
        if (t <= 0f) return from
        return FloatArray(to.size) { i -> from[i] + (to[i] - from[i]) * t }
    }

    fun averageColor(points: FloatArray): Color {
        var l = 0f; var a = 0f; var b = 0f
        for (i in 0 until 16) { l += points[i * 4]; a += points[i * 4 + 1]; b += points[i * 4 + 2] }
        val rgb = oklabToRgb(Lab(l / 16f, a / 16f, b / 16f))
        return Color(rgb[0], rgb[1], rgb[2])
    }

    /** k-means (k = 8) in OKLab, then up to five colours picked fairly. */
    private fun pick(px: List<Lab>): List<Lab> {
        val k = 8
        val centers = mutableListOf(px[px.size / 2])
        while (centers.size < k) centers += px.maxBy { p -> centers.minOf { it.dist(p) } }
        var assign = IntArray(px.size)
        repeat(12) {
            assign = IntArray(px.size) { i -> centers.indices.minBy { centers[it].dist(px[i]) } }
            for (c in 0 until k) {
                var n = 0; var l = 0f; var a = 0f; var b = 0f
                for (i in px.indices) if (assign[i] == c) { n++; l += px[i].l; a += px[i].a; b += px[i].b }
                if (n > 0) centers[c] = Lab(l / n, a / n, b / n)
            }
        }
        val weights = (0 until k).map { c -> assign.count { it == c } / px.size.toFloat() }
        // Grey-ish areas only count when the cover has little colour at all
        // (plus at most one large dark one, like black hair or a night sky).
        val colourful = (0 until k).filter { weights[it] > 0.012f && centers[it].chroma > 0.045f }
        val pool = if (colourful.size >= 3) {
            colourful + (0 until k).filter { weights[it] > 0.08f && centers[it].chroma <= 0.045f && centers[it].l < 0.45f }.take(1)
        } else {
            (0 until k).filter { weights[it] > 0.012f }
        }
        // Area counts only as a square root, colour counts too: a small vivid
        // area gets the same share as a large one.
        val scored = pool.sortedByDescending { sqrt(weights[it]) * (0.35f + centers[it].chroma * 4f) }
        val chosen = mutableListOf<Lab>()
        for (c in scored) {
            if (chosen.size == 5) break
            if (chosen.all { it.dist(centers[c]) > 0.09f }) chosen += centers[c]
        }
        if (chosen.isEmpty()) chosen += centers[0]
        var i = 0
        while (chosen.size < 4) {
            val base = chosen[i++ % chosen.size]
            chosen += Lab(base.l * if (i % 2 == 0) 0.8f else 1.2f, base.a, base.b)
        }
        // Readable under white text: lightness in a band, colours a little richer.
        return chosen.map { c ->
            val boost = if (c.chroma < 0.03f) 1f else 1.15f
            Lab(c.l.coerceIn(0.34f, 0.70f), c.a * boost, c.b * boost)
        }
    }

    /** Every colour gets an equal share of the 4x4 points and neighbours differ. */
    private fun points(palette: List<Lab>): FloatArray {
        val n = palette.size
        val out = FloatArray(64)
        for (row in 0 until 4) for (col in 0 until 4) {
            val c = palette[(col + row * 2) % n]
            val i = (row * 4 + col) * 4
            out[i] = c.l; out[i + 1] = c.a; out[i + 2] = c.b; out[i + 3] = 1f
        }
        return out
    }

    private fun srgbToLinear(c: Float) = if (c <= 0.04045f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
    private fun linearToSrgb(c: Float) = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1f / 2.4f) - 0.055f

    private fun rgbToOklab(r: Float, g: Float, b: Float): Lab {
        val lr = srgbToLinear(r); val lg = srgbToLinear(g); val lb = srgbToLinear(b)
        val l = cbrt(0.4122214708f * lr + 0.5363325363f * lg + 0.0514459929f * lb)
        val m = cbrt(0.2119034982f * lr + 0.6806995451f * lg + 0.1073969566f * lb)
        val s = cbrt(0.0883024619f * lr + 0.2817188376f * lg + 0.6299787005f * lb)
        return Lab(
            0.2104542553f * l + 0.7936177850f * m - 0.0040720468f * s,
            1.9779984951f * l - 2.4285922050f * m + 0.4505937099f * s,
            0.0259040371f * l + 0.7827717662f * m - 0.8086757660f * s,
        )
    }

    private fun oklabToRgb(c: Lab): FloatArray {
        val l_ = c.l + 0.3963377774f * c.a + 0.2158037573f * c.b
        val m_ = c.l - 0.1055613458f * c.a - 0.0638541728f * c.b
        val s_ = c.l - 0.0894841775f * c.a - 1.2914855480f * c.b
        val l = l_ * l_ * l_; val m = m_ * m_ * m_; val s = s_ * s_ * s_
        return floatArrayOf(
            linearToSrgb(4.0767416621f * l - 3.3077115913f * m + 0.2309699292f * s).coerceIn(0f, 1f),
            linearToSrgb(-1.2684380046f * l + 2.6097574011f * m - 0.3413193965f * s).coerceIn(0f, 1f),
            linearToSrgb(-0.0041960863f * l - 0.7034186147f * m + 1.7076147010f * s).coerceIn(0f, 1f),
        )
    }

    private const val SAMPLE = 48
}

/**
 * Draws the ambient shader (Skia SkSL on desktop, AGSL on Android 13+). The
 * brush of each frame is valid until the next call.
 */
internal interface AmbientShaderRenderer {
    fun brush(width: Float, height: Float, time: Float, spread: Float, colors: FloatArray, seeds: FloatArray): androidx.compose.ui.graphics.Brush?
    fun close()
}

/** Null when the device can't run the shader; the page then shows the average colour. */
internal expect fun createAmbientShaderRenderer(source: String): AmbientShaderRenderer?

/** The decoded picture's pixels as ARGB ints. */
internal expect fun coil3.Image.argbPixels(): IntArray
