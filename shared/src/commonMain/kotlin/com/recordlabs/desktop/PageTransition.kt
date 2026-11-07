package com.recordlabs.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import com.recordlabs.desktop.platform.AppPlatform
import com.recordlabs.desktop.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Moving between pages on the iPhone (portrait), as in Apple Music: a page opened from
 * another slides in from the right over it (the page under it moves a little to the
 * left and darkens), and slides back out to the right on Back; a tab of the dock fades
 * in; an album, playlist or artist opened from its cover grows out of the cover (and
 * shrinks back into it on Back), on a soft spring.
 *
 * Only one page is composed at a time (as before): the page being left is kept as a
 * picture for the length of the move. The dock, the header and the player stay where
 * they are; only the page under them moves. Elsewhere (desktop, Android, landscape)
 * pages change as before.
 */
internal object PageTransition {
    enum class Kind { None, Fade, Push, Pop, ZoomOpen, ZoomClose }

    /** iPhone in portrait (set by the app window). */
    var enabled = false
    /** The page as last drawn (recorded by [pageTransition]). */
    var layer: GraphicsLayer? = null
    var layerSize = IntSize.Zero
    /** For the animation: the app window's scope (outlives any one page). */
    var scope: CoroutineScope? = null
    /** The player covered the pages at the last composition: pages changed under it do not move. */
    var playerShown = false

    // What the coming change is (set by the navigation just before it).
    private var backAt = 0L
    private var tabAt = 0L
    private var pressedRect: Rect? = null
    private var pressedAt = 0L

    /** Cover a page was opened from (window coordinates), to shrink back into on Back. */
    private val zoomRects = HashMap<Any, Rect>()
    private var lastKey: Any? = null

    // The move under way.
    var kind by mutableStateOf(Kind.None)
        private set
    var snapshot: ImageBitmap? = null
        private set
    var rect: Rect? = null
        private set
    var pageOrigin = Offset.Zero
    val progress = Animatable(1f)
    private var job: Job? = null

    /** Back is under way (the page leaving slides out to the right, or shrinks into its cover). */
    fun back() { backAt = currentTimeMillis() }

    /** A tab of the dock (the pages fade). */
    fun tab() { tabAt = currentTimeMillis() }

    /** A cover was pressed (window coordinates): a page opened from it now grows out of it. */
    fun pressed(rect: Rect) {
        pressedRect = rect
        pressedAt = currentTimeMillis()
    }

    /** The page shown is now [key] (called while composing it). */
    fun onPage(key: Any, density: Density) {
        val previous = lastKey
        if (previous == key) return
        lastKey = key
        if (previous == null || !enabled) return
        val now = currentTimeMillis()
        val isBack = now - backAt < 1_500L
        val isTab = now - tabAt < 1_500L
        val cover = pressedRect.takeIf { now - pressedAt < 1_500L }
        backAt = 0L; tabAt = 0L; pressedRect = null
        if (playerShown) {
            stop()
            return
        }
        val next = when {
            isBack -> zoomRects.remove(previous)?.let { rect = it; Kind.ZoomClose } ?: Kind.Pop
            isTab -> Kind.Fade
            cover != null -> {
                zoomRects[key] = cover
                rect = cover
                Kind.ZoomOpen
            }
            else -> Kind.Push
        }
        if (zoomRects.size > 24) zoomRects.clear()
        val picture = capture(density) ?: run { stop(); return }
        snapshot = picture
        kind = next
        val scope = scope ?: run { stop(); return }
        job?.cancel()
        job = scope.launch {
            progress.snapTo(0f)
            progress.animateTo(
                1f,
                when (next) {
                    Kind.ZoomOpen -> spring(dampingRatio = 0.8f, stiffness = 300f)
                    Kind.ZoomClose -> spring(dampingRatio = 0.9f, stiffness = 340f)
                    Kind.Fade -> tween(200)
                    else -> tween(420, easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f))
                },
            )
            stop()
        }
    }

    private fun stop() {
        kind = Kind.None
        snapshot = null
    }

    /** The page as last drawn, as a picture (it is about to be replaced). */
    private fun capture(density: Density): ImageBitmap? {
        val layer = layer ?: return null
        val size = layerSize
        if (size.width <= 0 || size.height <= 0) return null
        return runCatching {
            val bitmap = ImageBitmap(size.width, size.height)
            val canvas = androidx.compose.ui.graphics.Canvas(bitmap)
            CanvasDrawScope().draw(density, LayoutDirection.Ltr, canvas, Size(size.width.toFloat(), size.height.toFloat())) {
                drawLayer(layer)
            }
            bitmap
        }.getOrNull()
    }
}

/** A cover that opens a page (album, playlist, artist): the page grows out of it (iPhone). */
@Composable
internal fun Modifier.pageZoomSource(): Modifier {
    if (!AppPlatform.isIos) return this
    val bounds = androidx.compose.runtime.remember { arrayOf(Rect.Zero) }
    return this
        .onGloballyPositioned { bounds[0] = it.boundsInWindow() }
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                if (bounds[0].width > 0f) PageTransition.pressed(bounds[0])
            }
        }
}

/**
 * The page under the dock and header ([key]: which page it is). Records the page as it
 * is drawn, and draws the move between pages (see [PageTransition]).
 */
@Composable
internal fun Modifier.pageTransition(key: Any): Modifier {
    if (!PageTransition.enabled) return this
    PageTransition.onPage(key, LocalDensity.current)
    return this
        .onGloballyPositioned { PageTransition.pageOrigin = it.positionInWindow() }
        .drawWithContent {
            val layer = PageTransition.layer
            if (layer == null) {
                drawContent()
                return@drawWithContent
            }
            layer.record { this@drawWithContent.drawContent() }
            PageTransition.layerSize = IntSize(size.width.toInt(), size.height.toInt())
            val kind = PageTransition.kind
            val picture = PageTransition.snapshot
            if (kind == PageTransition.Kind.None || picture == null) {
                drawLayer(layer)
                return@drawWithContent
            }
            val p = PageTransition.progress.value
            val w = size.width
            when (kind) {
                PageTransition.Kind.Fade -> {
                    drawLayer(layer)
                    drawImage(picture, alpha = (1f - p).coerceIn(0f, 1f))
                }
                PageTransition.Kind.Push -> {
                    translate(-0.28f * w * p) {
                        drawImage(picture)
                        drawRect(Color.Black.copy(alpha = 0.24f * p.coerceIn(0f, 1f)))
                    }
                    translate(w * (1f - p)) { pageEdgeShadow(1f - p); drawLayer(layer) }
                }
                PageTransition.Kind.Pop -> {
                    translate(-0.28f * w * (1f - p)) {
                        drawLayer(layer)
                        drawRect(Color.Black.copy(alpha = 0.24f * (1f - p).coerceIn(0f, 1f)))
                    }
                    translate(w * p) { pageEdgeShadow(p); drawImage(picture) }
                }
                PageTransition.Kind.ZoomOpen -> {
                    drawImage(picture)
                    zoomed(PageTransition.rect, 1f - p) {
                        layer.alpha = (p / 0.22f).coerceIn(0f, 1f)
                        drawLayer(layer)
                        layer.alpha = 1f
                    }
                }
                PageTransition.Kind.ZoomClose -> {
                    drawLayer(layer)
                    zoomed(PageTransition.rect, p) {
                        drawImage(picture, alpha = (1f - (p - 0.72f) / 0.28f).coerceIn(0f, 1f))
                    }
                }
                PageTransition.Kind.None -> drawLayer(layer)
            }
        }
}

/** A soft shadow along the left edge of a page sliding over another. */
private fun DrawScope.pageEdgeShadow(amount: Float) {
    if (amount <= 0.01f) return
    val width = 14.dp.toPx()
    drawRect(
        androidx.compose.ui.graphics.Brush.horizontalGradient(
            0f to Color.Transparent,
            1f to Color.Black.copy(alpha = 0.28f * amount.coerceIn(0f, 1f)),
            startX = -width,
            endX = 0f,
        ),
        topLeft = Offset(-width, 0f),
        size = Size(width, size.height),
    )
}

/**
 * Draws [content] (the whole page) shrunk toward [cover] by [toward] (0: the full page,
 * 1: the page as wide as the cover, at the cover), with rounded corners.
 */
private inline fun DrawScope.zoomed(cover: Rect?, toward: Float, content: DrawScope.() -> Unit) {
    val w = size.width
    if (cover == null || w <= 0f) {
        content()
        return
    }
    val origin = PageTransition.pageOrigin
    val target = cover.translate(-origin.x, -origin.y)
    val t = toward
    val scale = 1f + (target.width / w - 1f) * t
    val left = target.left * t
    val top = target.top * t
    val radius = (16.dp.toPx() * t + 0f) / scale.coerceAtLeast(0.05f)
    translate(left, top) {
        scale(scale, scale, pivot = Offset.Zero) {
            val clip = Path().apply { addRoundRect(RoundRect(0f, 0f, w, size.height, CornerRadius(radius))) }
            clipPath(clip) { content() }
        }
    }
}
