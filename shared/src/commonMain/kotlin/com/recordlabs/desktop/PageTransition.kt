package com.recordlabs.desktop

import androidx.compose.animation.core.Animatable
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
    val progress = Animatable(1f)
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
    fun claimsEdgeSwipe(): Boolean = enabled && canSwipe && !playerShown && other == null

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
        if (next == Kind.None) return
        val start = if (next == Kind.Pop && committed != null) committed else 0f
        job = scope?.launch {
            progress.snapTo(start)
            progress.animateTo(
                1f,
                when (next) {
                    Kind.ZoomOpen -> spring(dampingRatio = 0.82f, stiffness = 260f)
                    Kind.ZoomClose -> spring(dampingRatio = 0.9f, stiffness = 300f)
                    Kind.Fade -> tween(170)
                    Kind.Pop -> if (committed != null) spring(dampingRatio = 1f, stiffness = 420f)
                        else tween(420, easing = IosEase)
                    else -> tween(460, easing = IosEase)
                },
            )
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
        return floatArrayOf(scale, (1f - scale) * w / 2f + fraction * w * 0.32f, (1f - scale) * h / 2f + dy * 0.4f)
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
        val p = stack.progress.value
        transformOrigin = TransformOrigin(0f, 0f)
        shape = RectangleShape
        clip = false
        alpha = 1f
        translationX = 0f
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
        fun zoom(scale: Float, x: Float, y: Float, radius: Float) {
            scaleX = scale
            scaleY = scale
            translationX = x
            translationY = y
            shape = RoundedCornerShape(radius / scale.coerceAtLeast(0.05f))
            clip = true
        }
        fun coverTransform(): FloatArray? {
            val rect = stack.zoomRect ?: return null
            if (w <= 0f) return null
            return floatArrayOf(rect.width / w, rect.left - stack.origin.x, rect.top - stack.origin.y)
        }
        when {
            // The edge swipe.
            kind == PageStack.Kind.None && stack.dragging -> {
                val d = stack.dragFraction
                if (role == PageStack.Role.Top) {
                    if (stack.zoomed(key)) {
                        val t = stack.dragTransform(d, stack.dragDy)
                        zoom(t[0], t[1], t[2], 44.dp.toPx() * (d * 4f).coerceAtMost(1f))
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
            kind == PageStack.Kind.Fade -> when (role) {
                PageStack.Role.Other -> alpha = (1f - p).coerceIn(0f, 1f)
                PageStack.Role.Under -> alpha = 0f
                PageStack.Role.Top -> {}
            }
            kind == PageStack.Kind.ZoomOpen -> when (role) {
                PageStack.Role.Top -> {
                    val c = coverTransform()
                    if (c != null) {
                        val t = 1f - p
                        zoom(lerp(1f, c[0], t), c[1] * t, c[2] * t, 16.dp.toPx() * t.coerceAtLeast(0f) + 44.dp.toPx() * (t * (1f - t) * 2f).coerceAtLeast(0f))
                        alpha = (p / 0.16f).coerceIn(0f, 1f)
                    }
                }
                PageStack.Role.Under -> alpha = 0f
                PageStack.Role.Other -> {}
            }
            kind == PageStack.Kind.ZoomClose -> when (role) {
                PageStack.Role.Other -> {
                    val c = coverTransform()
                    if (c != null) {
                        val from = stack.zoomStart()
                        val t = p
                        zoom(lerp(from[0], c[0], t), lerp(from[1], c[1], t), lerp(from[2], c[2], t), lerp(44.dp.toPx(), 16.dp.toPx(), t.coerceIn(0f, 1f)))
                        alpha = (1f - (t - 0.78f) / 0.22f).coerceIn(0f, 1f)
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
        val p = stack.progress.value
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
