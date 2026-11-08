package com.recordlabs.desktop

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import com.recordlabs.desktop.platform.AppPlatform
import com.recordlabs.desktop.platform.currentTimeMillis
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * iPhone (portrait): the pages as a stack, as in Apple Music.
 *
 * The page shown and the page Back returns to are both kept alive (the one under is not
 * drawn until it is needed), so going back shows that page at once, exactly as it was
 * left (scrolled where it was, nothing loading again), and a swipe from the left edge
 * moves the page with the finger, the page under it coming into view.
 *
 *  - a page opened from another slides in from the right over it (the page under it
 *    moves a little left and darkens); Back slides it out again;
 *  - an album, playlist or artist opened from its cover grows out of the cover on a soft
 *    spring; Back (or the swipe, which shrinks it under the finger) puts it back into it;
 *  - a tab of the dock: a quick cross fade.
 *
 * The dock, the player and the header of each page stay as they are; desktop, Android
 * and landscape change pages as before.
 */
internal object PageStack {
    enum class Kind { None, Fade, Push, Pop, ZoomOpen, ZoomClose }

    /** Where a page is in the stack while it is drawn. */
    enum class Role { Under, Top, Other }

    var enabled = false
    var scope: CoroutineScope? = null
    /** The player covers the pages (as of the last composition): pages change under it without moving. */
    var playerShown = false
    /** Back (for the edge swipe once it is far enough). */
    var onBack: (() -> Unit)? = null
    /** The page under the top one is where Back goes (so the edge swipe can show it). */
    var canSwipe = false
    /**
     * A tab's own page (Home, Library, Listen Together, Profile: each has its button in the
     * dock): nothing is under it, so it does not swipe back, and moving between two of them
     * cross fades.
     */
    var isRoot: (Any) -> Boolean = { false }
    /** The page shown is a tab's own page (no swipe back at all, not even the root's own). */
    var rootPage = false

    // The pages: the one shown, the one moving out (or, while one opens, the one under
    // it), and the one Back goes to.
    var top: Any? by mutableStateOf(null)
        private set
    var other: Any? by mutableStateOf(null)
        private set
    var under: Any? by mutableStateOf(null)
        private set
    var kind by mutableStateOf(Kind.None)
        private set
    /**
     * How far the move is (0..1, a little past 1 on a spring). Plain state, set at once
     * when the page changes: an Animatable could only be reset in its coroutine, a frame
     * later, so that first frame showed the new page already in place, then the move began.
     */
    var progressValue by mutableFloatStateOf(1f)
        private set
    private var job: Job? = null

    // The stack's place in the window (covers are in window coordinates).
    var origin = Offset.Zero
    var size = Size.Zero

    /** The cover each page opened from, to go back into. */
    private val zoomRects = HashMap<Any, Rect>()
    var zoomRect: Rect? = null
        private set

    /** Where a page shrinking into its cover starts from (scale, x, y; the swipe leaves it there). */
    private var zoomFrom = floatArrayOf(1f, 0f, 0f)

    // The edge swipe.
    var dragging by mutableStateOf(false)
        private set
    var dragFraction by mutableFloatStateOf(0f)
        private set
    var dragDy by mutableFloatStateOf(0f)
        private set
    private var commitFrom: Float? = null

    // What the coming change is (set by the navigation just before it).
    private var backAt = 0L
    private var tabAt = 0L
    private var pressedRect: Rect? = null
    private var pressedAt = 0L

    fun back() { backAt = currentTimeMillis() }
    fun tab() { tabAt = currentTimeMillis() }
    fun pressed(rect: Rect) {
        pressedRect = rect
        pressedAt = currentTimeMillis()
    }

    /** The root's own edge swipe gives way to the stack's (iPhone). */
    fun claimsEdgeSwipe(): Boolean = enabled && canSwipe && !rootPage && !playerShown && other == null

    /** A tab's own page: the edge swipe does nothing there (iPhone). */
    fun blocksEdgeSwipe(): Boolean = enabled && rootPage && !playerShown

    /** Whether [key] opened from a cover (it shrinks back into it rather than sliding). */
    fun zoomed(key: Any?): Boolean = key != null && zoomRects.containsKey(key)

    /** The page shown is [current]; Back goes to [back] (called while composing the stack). */
    fun update(current: Any, back: Any?) {
        val previous = top
        if (previous == null) {
            top = current
            under = back
            return
        }
        if (current == previous) {
            if (under != back) under = back
            return
        }
        val now = currentTimeMillis()
        val isBack = now - backAt < 1_500L || current == under
        val isTab = now - tabAt < 1_500L
        val cover = pressedRect.takeIf { now - pressedAt < 1_500L }
        backAt = 0L; tabAt = 0L; pressedRect = null
        val committed = commitFrom
        commitFrom = null
        val next = when {
            playerShown -> Kind.None
            // From one tab's page to another's (the dock): a cross fade.
            isRoot(previous) && isRoot(current) -> Kind.Fade
            isBack -> if (zoomRects.containsKey(previous)) Kind.ZoomClose else Kind.Pop
            isTab -> Kind.Fade
            cover != null -> Kind.ZoomOpen
            else -> Kind.Push
        }
        when (next) {
            Kind.ZoomOpen -> {
                zoomRects[current] = cover!!
                zoomRect = cover
            }
            Kind.ZoomClose -> {
                zoomRect = zoomRects[previous]
                if (committed == null) zoomFrom = floatArrayOf(1f, 0f, 0f)
            }
            else -> {}
        }
        if (isBack || isTab) zoomRects.remove(previous)
        if (zoomRects.size > 32) zoomRects.clear()
        top = current
        under = back
        other = if (next == Kind.None) null else previous
        kind = next
        dragging = false
        dragFraction = 0f
        job?.cancel()
        if (next == Kind.None) {
            progressValue = 1f
            return
        }
        val start = if (next == Kind.Pop && committed != null) committed else 0f
        progressValue = start
        job = scope?.launch {
            animate(
                start,
                1f,
                animationSpec = when (next) {
                    Kind.ZoomOpen -> spring(dampingRatio = 0.84f, stiffness = 240f)
                    // Into the cover as Apple Music: off at once (no slow spring start, which
                    // left the card drifting after the finger), easing into the cover.
                    Kind.ZoomClose -> tween(460, easing = CubicBezierEasing(0.22f, 0.9f, 0.3f, 1f))
                    Kind.Fade -> tween(240, easing = androidx.compose.animation.core.LinearEasing)
                    Kind.Pop -> if (committed != null) spring(dampingRatio = 1f, stiffness = 420f)
                        else tween(420, easing = IosEase)
                    else -> tween(460, easing = IosEase)
                },
            ) { value, _ -> progressValue = value }
            other = null
            kind = Kind.None
        } ?: run {
            other = null
            kind = Kind.None
            null
        }
    }

    // Edge swipe.
    fun dragStart() {
        dragging = true
        dragFraction = 0f
        dragDy = 0f
    }

    fun dragTo(fraction: Float, dy: Float) {
        dragFraction = fraction.coerceIn(0f, 1f)
        dragDy = dy
    }

    fun dragEnd(velocityX: Float) {
        val commit = dragFraction > 0.33f || (velocityX > 700f && dragFraction > 0.04f)
        if (commit) {
            commitFrom = dragFraction
            if (zoomed(top)) zoomFrom = dragTransform(dragFraction, dragDy)
            onBack?.invoke()
            // Back did not change the page (nothing to go back to): settle again.
            scope?.launch {
                delay(120)
                if (dragging) settle()
            }
        } else settle()
    }

    private fun settle() {
        commitFrom = null
        val from = dragFraction
        val fromDy = dragDy
        scope?.launch {
            animate(from, 0f, animationSpec = spring(dampingRatio = 1f, stiffness = 500f)) { value, _ ->
                dragFraction = value
                dragDy = if (from > 0f) fromDy * value / from else 0f
            }
            dragging = false
        } ?: run { dragging = false; dragFraction = 0f }
    }

    /** A page that opened from a cover, under the finger: shrinks and follows it (scale, x, y). */
    fun dragTransform(fraction: Float, dy: Float): FloatArray {
        val w = size.width
        val h = size.height
        val scale = 1f - 0.24f * fraction
        return floatArrayOf(scale, (1f - scale) * w / 2f + fraction * w * 0.2f, (1f - scale) * h / 2f + dy * 0.35f)
    }

    fun zoomStart(): FloatArray = zoomFrom
}

private val IosEase = CubicBezierEasing(0.2f, 0.9f, 0.22f, 1f)

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t

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
                if (bounds[0].width > 0f) PageStack.pressed(bounds[0])
            }
        }
}

/** The stack itself: its place, and the edge swipe back. */
internal fun Modifier.pageStackContainer(): Modifier = this
    .onGloballyPositioned {
        PageStack.origin = it.positionInWindow()
        PageStack.size = Size(it.size.width.toFloat(), it.size.height.toFloat())
    }
    .pointerInput(Unit) {
        val edge = 26.dp.toPx()
        val slop = 10.dp.toPx()
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.position.x > edge || !PageStack.claimsEdgeSwipe()) return@awaitEachGesture
            val tracker = VelocityTracker()
            tracker.addPosition(down.uptimeMillis, down.position)
            var started = false
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                tracker.addPosition(change.uptimeMillis, change.position)
                val dx = change.position.x - down.position.x
                val dy = change.position.y - down.position.y
                if (!change.pressed) break
                if (!started) {
                    if (abs(dy) > slop && abs(dy) > abs(dx)) return@awaitEachGesture
                    if (dx > slop) {
                        started = true
                        PageStack.dragStart()
                    }
                }
                if (started) {
                    change.consume()
                    PageStack.dragTo(dx / size.width.coerceAtLeast(1), dy)
                }
            }
            if (started) PageStack.dragEnd(tracker.calculateVelocity().x)
        }
    }

/**
 * One page of the stack: where it is drawn ([role]) for the move under way, and touches
 * kept to the page in front.
 */
internal fun Modifier.pageStackPage(key: Any, role: PageStack.Role): Modifier = this
    .graphicsLayer {
        val w = size.width
        val stack = PageStack
        val kind = stack.kind
        val p = stack.progressValue
        transformOrigin = TransformOrigin(0f, 0f)
        shape = RectangleShape
        clip = false
        alpha = 1f
        translationX = 0f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        // The page as a card: [scale] (from the top left, at x, y), cut to [height] (in the
        // page's own size: the full height, down to the cover's shape) with round corners.
        fun zoom(scale: Float, x: Float, y: Float, radius: Float, height: Float = size.height) {
            val s = scale.coerceAtLeast(0.05f)
            scaleX = s
            scaleY = s
            translationX = x
            translationY = y
            shape = CardClip(height, radius / s)
            clip = true
        }
        // The cover: scale (page width to cover width), x, y, and the cover's height in
        // the page's own size (a square cover: as tall as the page is wide).
        fun coverTransform(): FloatArray? {
            val rect = stack.zoomRect ?: return null
            if (w <= 0f || rect.width <= 0f) return null
            return floatArrayOf(rect.width / w, rect.left - stack.origin.x, rect.top - stack.origin.y, w * rect.height / rect.width)
        }
        val h = size.height
        val coverRadius = 12.dp.toPx()
        val cardRadius = 44.dp.toPx()
        when {
            // The edge swipe.
            kind == PageStack.Kind.None && stack.dragging -> {
                val d = stack.dragFraction
                if (role == PageStack.Role.Top) {
                    if (stack.zoomed(key)) {
                        val t = stack.dragTransform(d, stack.dragDy)
                        zoom(t[0], t[1], t[2], cardRadius * (d * 4f).coerceAtMost(1f))
                    } else translationX = w * d
                } else if (role == PageStack.Role.Under && !stack.zoomed(stack.top)) {
                    translationX = -0.3f * w * (1f - d)
                }
            }
            kind == PageStack.Kind.None -> if (role != PageStack.Role.Top) alpha = 0f
            kind == PageStack.Kind.Push -> when (role) {
                PageStack.Role.Top -> translationX = w * (1f - p)
                PageStack.Role.Other -> translationX = -0.3f * w * p
                PageStack.Role.Under -> alpha = 0f
            }
            kind == PageStack.Kind.Pop -> when (role) {
                PageStack.Role.Top -> translationX = -0.3f * w * (1f - p)
                PageStack.Role.Other -> translationX = w * p
                PageStack.Role.Under -> alpha = 0f
            }
            // The page left goes first, the new one comes in after it (never both at once
            // in full: two pages over each other read as a glitch).
            kind == PageStack.Kind.Fade -> when (role) {
                PageStack.Role.Other -> alpha = (1f - p / 0.5f).coerceIn(0f, 1f)
                PageStack.Role.Under -> alpha = 0f
                PageStack.Role.Top -> alpha = ((p - 0.3f) / 0.7f).coerceIn(0f, 1f)
            }
            kind == PageStack.Kind.ZoomOpen -> when (role) {
                PageStack.Role.Top -> {
                    val c = coverTransform()
                    if (c != null) {
                        // Out of the cover: a card of the cover's shape that grows into the page,
                        // taking the page's height as it goes (as Apple Music).
                        val t = 1f - p
                        val shapeT = 1f - (1f - t.coerceIn(0f, 1f)).let { it * it }
                        zoom(lerp(1f, c[0], t), c[1] * t, c[2] * t, lerp(cardRadius * 0.6f, coverRadius, t.coerceIn(0f, 1f)), lerp(h, c[3], shapeT))
                        alpha = (p / 0.12f).coerceIn(0f, 1f)
                    }
                }
                PageStack.Role.Under -> alpha = 0f
                PageStack.Role.Other -> {}
            }
            kind == PageStack.Kind.ZoomClose -> when (role) {
                PageStack.Role.Other -> {
                    val c = coverTransform()
                    if (c != null) {
                        // Into the cover (as Apple Music): the page shrinks to a card that takes
                        // the cover's shape on the way (its height goes first), lands on the
                        // cover and melts into it.
                        val from = stack.zoomStart()
                        val t = p
                        val shapeT = 1f - (1f - t.coerceIn(0f, 1f)).let { it * it }
                        zoom(
                            lerp(from[0], c[0], t),
                            lerp(from[1], c[1], t),
                            lerp(from[2], c[2], t),
                            lerp(cardRadius, coverRadius, t.coerceIn(0f, 1f)),
                            lerp(h, c[3], shapeT),
                        )
                        alpha = (1f - (t - 0.82f) / 0.18f).coerceIn(0f, 1f)
                    } else translationX = w * p
                }
                PageStack.Role.Under -> alpha = 0f
                PageStack.Role.Top -> {}
            }
        }
    }
    .drawWithContent {
        drawContent()
        // The page under a sliding one darkens a little; a sliding page casts a soft shadow
        // on the page under it.
        val stack = PageStack
        val kind = stack.kind
        val p = stack.progressValue
        val slide = !stack.zoomed(stack.top) && !(kind == PageStack.Kind.ZoomOpen || kind == PageStack.Kind.ZoomClose)
        val dim = when {
            kind == PageStack.Kind.None && stack.dragging && role == PageStack.Role.Under && slide -> 1f - stack.dragFraction
            kind == PageStack.Kind.Push && role == PageStack.Role.Other -> p
            kind == PageStack.Kind.Pop && role == PageStack.Role.Top -> 1f - p
            else -> 0f
        }
        if (dim > 0.005f) drawRect(Color.Black.copy(alpha = 0.22f * dim.coerceIn(0f, 1f)))
        val shadow = when {
            kind == PageStack.Kind.None && stack.dragging && role == PageStack.Role.Top && slide -> 1f - stack.dragFraction
            kind == PageStack.Kind.Push && role == PageStack.Role.Top -> 1f - p
            kind == PageStack.Kind.Pop && role == PageStack.Role.Other -> p.let { 1f - it }
            else -> 0f
        }
        if (shadow > 0.005f) {
            val width = 18.dp.toPx()
            drawRect(
                Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.3f * shadow.coerceIn(0f, 1f)), startX = -width, endX = 0f),
                topLeft = Offset(-width, 0f),
                size = Size(width, size.height),
            )
        }
    }
    // The page in front takes every touch on it (the pages under it are only hidden).
    .then(
        if (role == PageStack.Role.Top) Modifier.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Final) }
        } else Modifier,
    )

/** A page cut to a card: [height] px from its top, with round corners. */
private class CardClip(private val height: Float, private val radius: Float) : androidx.compose.ui.graphics.Shape {
    override fun createOutline(
        size: Size,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        density: androidx.compose.ui.unit.Density,
    ): androidx.compose.ui.graphics.Outline = androidx.compose.ui.graphics.Outline.Rounded(
        androidx.compose.ui.geometry.RoundRect(
            0f, 0f, size.width, height.coerceIn(1f, size.height.coerceAtLeast(1f)),
            androidx.compose.ui.geometry.CornerRadius(radius),
        ),
    )
    override fun equals(other: Any?): Boolean = other is CardClip && other.height == height && other.radius == radius
    override fun hashCode(): Int = height.hashCode() * 31 + radius.hashCode()
}
