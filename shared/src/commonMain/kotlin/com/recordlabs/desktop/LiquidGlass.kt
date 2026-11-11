package com.recordlabs.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign
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
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.RenderEffect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.positionOnScreen
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.ceil
import com.recordlabs.desktop.platform.AppPlatform

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
internal class GlassBackdrop(val layer: GraphicsLayer, val onDemand: Boolean = false) {
    /** The glass surfaces reading it now (an on-demand page is recorded only while there are some). */
    var users by mutableIntStateOf(0)
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
 * The whole app under its menus, dialogs and sheets (iPhone): they are drawn as liquid
 * glass over it. Recorded only while one of them is open.
 */
internal val LocalAppBackdrop = staticCompositionLocalOf<GlassBackdrop?> { null }

/**
 * The glass effect for a fully rounded shape (pill or circle) of [width] x [height] px,
 * reading a recording [margin] px larger on every side; null where the platform keeps
 * its own glass.
 */
internal expect fun liquidGlassRenderEffect(
    width: Float, height: Float, margin: Float, density: Float,
    touchX: Float, touchY: Float, press: Float,
    /** The part of the recorded area the page covers (by the screen's edge the rest is empty). */
    validLeft: Float, validTop: Float, validRight: Float, validBottom: Float,
    /** A panel's corner radius in px (menus, dialogs); below 0: fully rounded (pill or circle). */
    cornerRadius: Float = -1f,
    /** A panel: frosted (much more blur) and darker, so its text reads over any page. */
    frosted: Boolean = false,
    /** Clear glass: barely tinted (the player pages' corner buttons, over dark pages). */
    clear: Boolean = false,
): RenderEffect?

/**
 * A finger on a glass button (as in Telegram): it swells while held, stretches and
 * leans the way it is pulled (like rubber, less the further it goes), and its light
 * follows the finger; let go, it springs back.
 */
internal class GlassPressState {
    /** The finger (or the mouse pointer), in the button (px). */
    var touch by mutableStateOf(Offset.Zero)
    val press = Animatable(0f)
    val pull = Animatable(Offset.Zero, Offset.VectorConverter)
    /** The mouse pointer is over it: a softer light follows the pointer. */
    val hover = Animatable(0f)
    /**
     * Where it was pressed (px): it swells from there, so what is under the pointer
     * stays under it and the click lands on the button pressed (a swell from the
     * middle moved a wide bar's end buttons out from under the mouse).
     */
    var anchor by mutableStateOf(Offset.Unspecified)
}

/**
 * The stretch of [glassPress] (see [GlassPressState]). The finger is only watched,
 * never taken: taps, the tab highlight's drag and swipes work as before.
 */
@Composable
internal fun Modifier.glassPress(state: GlassPressState): Modifier {
    val scope = rememberCoroutineScope()
    return this
        // The mouse over it (desktop): the light follows the pointer.
        .pointerInput(state) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull() ?: continue
                    if (change.type != androidx.compose.ui.input.pointer.PointerType.Mouse) continue
                    when (event.type) {
                        androidx.compose.ui.input.pointer.PointerEventType.Enter,
                        androidx.compose.ui.input.pointer.PointerEventType.Move -> {
                            if (!change.pressed) state.touch = change.position
                            if (state.hover.targetValue < 1f) scope.launch { state.hover.animateTo(1f, tween(180)) }
                        }
                        androidx.compose.ui.input.pointer.PointerEventType.Exit ->
                            scope.launch { state.hover.animateTo(0f, tween(260)) }
                    }
                }
            }
        }
        .pointerInput(state) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                state.touch = down.position
                state.anchor = down.position
                scope.launch { state.press.animateTo(1f, spring(dampingRatio = 0.7f, stiffness = 700f)) }
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if (!change.pressed) break
                    state.touch = change.position
                    val moved = change.position - down.position
                    scope.launch { state.pull.snapTo(moved) }
                }
                scope.launch { state.press.animateTo(0f, spring(dampingRatio = 0.55f, stiffness = 320f)) }
                scope.launch { state.pull.animateTo(Offset.Zero, spring(dampingRatio = 0.45f, stiffness = 280f)) }
            }
        }
        .graphicsLayer {
            val width = size.width.coerceAtLeast(1f)
            val height = size.height.coerceAtLeast(1f)
            // Rubber: the further it is pulled, the less it follows.
            fun rubber(value: Float, limit: Float): Float {
                val amount = abs(value) / limit
                return sign(value) * limit * (1f - 1f / (amount * 0.6f + 1f))
            }
            val pull = state.pull.value
            val reach = minOf(width, height)
            val dx = rubber(pull.x, reach * 0.9f)
            val dy = rubber(pull.y, reach * 0.9f)
            val held = state.press.value
            val swell = 0.06f * held * (reach / maxOf(width, height)).coerceIn(0.25f, 1f)
            // Longer the way it is pulled, a little thinner across it.
            scaleX = 1f + swell + abs(dx) / width * 0.55f - abs(dy) / height * 0.10f
            scaleY = 1f + swell + abs(dy) / height * 0.55f - abs(dx) / width * 0.10f
            translationX = dx * 0.5f
            translationY = dy * 0.5f
            val anchor = state.anchor
            if (anchor.isSpecified) transformOrigin = androidx.compose.ui.graphics.TransformOrigin(
                (anchor.x / width).coerceIn(0f, 1f), (anchor.y / height).coerceIn(0f, 1f),
            )
        }
}

/** Whether this platform draws the liquid glass ([liquidGlassRenderEffect]). */
internal expect val liquidGlassSupported: Boolean

/**
 * The liquid glass material is in use: always on the iPhone; on Android (13+) and the
 * desktop with Settings > Appearance > Liquid Glass Material.
 */
internal val liquidGlassOn: Boolean
    get() = liquidGlassSupported && (AppPlatform.isIos || com.recordlabs.desktop.data.AppSettings.liquidGlassMaterial.value)

/** A recorder for the page under the glass, or null where the glass is not drawn. */
@Composable
internal fun rememberGlassBackdrop(onDemand: Boolean = false): GlassBackdrop? {
    // Android and the desktop: only with Settings > Appearance > Liquid Glass Material.
    if (!liquidGlassOn) return null
    val layer = rememberGraphicsLayer()
    val backdrop = remember(layer) { GlassBackdrop(layer, onDemand) }
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

/**
 * Where a glass surface or recorded page is. Android: on the screen, since a menu or
 * the search suggestions are their own window there (window positions of the two
 * would not line up); elsewhere in the window.
 */
private fun androidx.compose.ui.layout.LayoutCoordinates.glassOrigin(): Offset =
    if (AppPlatform.isAndroid) positionOnScreen() else positionInWindow()

/** Records the page this is on so the glass above it can show it (drawn as before). */
internal fun Modifier.glassBackdropSource(backdrop: GlassBackdrop?): Modifier =
    if (backdrop == null) this
    else this
        .nestedScroll(backdrop.scrollWatcher)
        .onGloballyPositioned { backdrop.originInWindow = it.glassOrigin() }
        .drawWithContent {
            if (backdrop.onDemand && backdrop.users == 0) {
                drawContent()
                return@drawWithContent
            }
            backdrop.layer.record { this@drawWithContent.drawContent() }
            drawLayer(backdrop.layer)
            backdrop.version++
        }

/** The liquid glass behind a pill or circle (the dock's surfaces), over the recorded page. */
@Composable
internal fun Modifier.liquidGlassBackground(
    backdrop: GlassBackdrop?,
    press: GlassPressState? = null,
    /** A panel's corners (menus, dialogs); null: fully rounded (pill or circle). */
    cornerRadius: androidx.compose.ui.unit.Dp? = null,
    /** A panel: frosted and darker (see [liquidGlassRenderEffect]). */
    frosted: Boolean = false,
    /** Clear, barely tinted glass (see [liquidGlassRenderEffect]). */
    clear: Boolean = false,
): Modifier {
    if (backdrop == null) return this
    androidx.compose.runtime.DisposableEffect(backdrop) {
        backdrop.users++
        onDispose { backdrop.users-- }
    }
    val glass = rememberGraphicsLayer()
    val state = remember { object { var origin = Offset.Zero; var effect: RenderEffect? = null; var effectKey = "" } }
    return this
        .onGloballyPositioned { state.origin = it.glassOrigin() }
        .drawBehind {
            // Redrawn whenever the page under it is recorded again.
            @Suppress("UNUSED_VARIABLE") val version = backdrop.version
            val marginPx = 24.dp.roundToPx()
            val margin = marginPx.toFloat()
            // Held: the light follows the finger (read here, so it redraws as it moves); the
            // mouse over it lights it more softly.
            val held = maxOf(press?.press?.value ?: 0f, (press?.hover?.value ?: 0f) * 0.4f)
            val touch = press?.touch ?: Offset.Zero
            val recordSize = IntSize(ceil(size.width + 2 * margin).toInt(), ceil(size.height + 2 * margin).toInt())
            val shift = backdrop.originInWindow - state.origin
            // Where the page is in the recorded area: by the screen's edge part of it is
            // empty, and the glass would bend that emptiness in (dark edges).
            val pageSize = backdrop.layer.size
            val validLeft = (margin + shift.x).coerceAtLeast(0f)
            val validTop = (margin + shift.y).coerceAtLeast(0f)
            val validRight = (margin + shift.x + pageSize.width).coerceAtMost(recordSize.width.toFloat())
            val validBottom = (margin + shift.y + pageSize.height).coerceAtMost(recordSize.height.toFloat())
            // (The corners are in the key too: a mini player changing shape redraws its glass.)
            val key = "${size.width}x${size.height}|${cornerRadius?.toPx()}|$clear|${validLeft.toInt()},${validTop.toInt()},${validRight.toInt()},${validBottom.toInt()}|" +
                if (held > 0.001f) "$held@${touch.x},${touch.y}" else "rest"
            if (state.effect == null || state.effectKey != key) {
                state.effect = liquidGlassRenderEffect(
                    size.width, size.height, margin, density, touch.x, touch.y, held,
                    validLeft, validTop, validRight, validBottom,
                    cornerRadius = cornerRadius?.toPx() ?: -1f,
                    frosted = frosted,
                    clear = clear,
                )
                state.effectKey = key
            }
            val effect = state.effect ?: return@drawBehind
            glass.renderEffect = effect
            glass.record(recordSize) {
                translate(margin + shift.x, margin + shift.y) { drawLayer(backdrop.layer) }
            }
            glass.topLeft = IntOffset(-marginPx, -marginPx)
            drawLayer(glass)
        }
}


/**
 * No press highlight: on the iPhone a glass button answers a touch by swelling and
 * stretching (glassPress); the usual highlight drew a white box past its round shape.
 */
internal object NoPressIndication : androidx.compose.foundation.IndicationNodeFactory {
    override fun create(interactionSource: androidx.compose.foundation.interaction.InteractionSource): androidx.compose.ui.node.DelegatableNode =
        object : Modifier.Node() {}
    override fun hashCode(): Int = -0x5eed
    override fun equals(other: Any?): Boolean = other === this
}

/**
 * A soft shadow all round a glass surface (iPhone), in place of Modifier.shadow.
 *
 * Compose's shadow on the iPhone (Skia) is lit from the top left of each element: it
 * falls to the right and below as a hard dark band, which under the see-through glass
 * read as black edges. This one is even on every side (a touch more below, as light from
 * above casts), fades out over [spread], and is never drawn under the glass itself.
 */
internal fun Modifier.glassShadow(
    shape: androidx.compose.ui.graphics.Shape,
    spread: androidx.compose.ui.unit.Dp = 10.dp,
    /** How dark it is right by the edge (0..1). */
    strength: Float = 0.16f,
    drop: androidx.compose.ui.unit.Dp = 2.dp,
): Modifier = this.drawBehind {
    if (size.width <= 0f || size.height <= 0f) return@drawBehind
    val steps = 8
    val spreadPx = spread.toPx()
    val dropPx = drop.toPx()
    // Each ring adds a little; by the edge all of them overlap ([strength] in all).
    val ring = 1f - kotlin.math.exp(kotlin.math.ln(1f - strength.coerceIn(0f, 0.95f)) / steps)
    val colour = androidx.compose.ui.graphics.Color.Black.copy(alpha = ring)
    val inner = androidx.compose.ui.graphics.Path().apply {
        addOutline(shape.createOutline(size, layoutDirection, this@drawBehind))
    }
    clipPath(inner, androidx.compose.ui.graphics.ClipOp.Difference) {
        for (i in steps downTo 1) {
            val grow = spreadPx * i / steps
            val outline = shape.createOutline(
                androidx.compose.ui.geometry.Size(size.width + 2 * grow, size.height + 2 * grow),
                layoutDirection,
                this@drawBehind,
            )
            translate(-grow, -grow + dropPx * i / steps) {
                drawOutline(outline, colour)
            }
        }
    }
}

/** The glass shader (Skia SkSL; Android's AGSL reads it as well). */
internal const val LIQUID_GLASS_SKSL = """
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
uniform float tintMul;     // how much of the page shows through (the dock: 0.60)
uniform float tintAdd;     // the glass's own grey
uniform float sat;         // colour boost of the page behind

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
    col.rgb = mix(half3(l), col.rgb, half(sat));
    col.rgb = col.rgb * half(tintMul) + half3(half(tintAdd));
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

/**
 * Liquid glass that stretches when pulled and lights up under the finger or the mouse,
 * as the iPhone's dock (see [glassPress]). Unchanged when [backdrop] is null.
 */
@Composable
internal fun Modifier.liquidGlassInteractive(
    backdrop: GlassBackdrop?,
    cornerRadius: androidx.compose.ui.unit.Dp? = null,
    clear: Boolean = false,
): Modifier {
    if (backdrop == null) return this
    val press = remember { GlassPressState() }
    return this.glassPress(press).liquidGlassBackground(backdrop, press, cornerRadius, clear = clear)
}
