package com.recordlabs.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import kotlin.math.max

/**
 * Portrait: the artwork flies between the player and the lyrics page. Opening
 * the lyrics, the big artwork shrinks into the small one beside the title;
 * going back, the small one grows into the big one. Both pages report where
 * their artwork is ([artworkFlightSlot]); while it flies, a copy is drawn on
 * top ([ArtworkFlightOverlay]) and the two real ones are hidden.
 *
 * The copy's picture is decoded ahead of time ([ArtworkFlightPreload]) so it
 * is on screen from the very first frame (no dark box while it loads), and it
 * moves in the drawing pass only: nothing is measured or laid out again while
 * it flies.
 */
internal object ArtworkFlight {
    /** Where the player's big artwork and the lyrics page's small one are (window pixels). */
    var bigBounds: Rect? = null
    var smallBounds: Rect? = null

    class Flight(val id: Long, val toSmall: Boolean, val artworkUrl: String?, val from: Rect)

    var flight by mutableStateOf<Flight?>(null)
        private set
    private var nextId = 0L

    /** Only the portrait layout has the two artworks (set by the window). */
    var portrait = false

    /** The playing song's artwork, decoded and ready for the copy. */
    var preparedUrl: String? = null
        private set
    var preparedImage: coil3.Image? = null
        private set

    fun prepared(url: String?, image: coil3.Image?) {
        preparedUrl = url
        preparedImage = image
    }

    /** Starts the flight from the artwork on screen now to the other page's. */
    fun start(toSmall: Boolean, artworkUrl: String?) {
        if (!portrait) return
        val from = (if (toSmall) bigBounds else smallBounds)?.takeIf { it.width > 1f && it.height > 1f } ?: return
        // The other page reports its own artwork once it is laid out.
        if (toSmall) smallBounds = null else bigBounds = null
        flight = Flight(++nextId, toSmall, artworkUrl, from)
    }

    fun land(id: Long) {
        if (flight?.id == id) flight = null
    }
}

/** Decodes the playing song's artwork for the flight while the player shows it (portrait). */
@Composable
internal fun ArtworkFlightPreload(artworkUrl: String?) {
    val context = LocalPlatformContext.current
    LaunchedEffect(artworkUrl) {
        if (artworkUrl == null || ArtworkFlight.preparedUrl == artworkUrl) return@LaunchedEffect
        val model = resolveArtworkModel(artworkUrl, highQuality = true) ?: return@LaunchedEffect
        val image = runCatching {
            (SingletonImageLoader.get(context).execute(
                ImageRequest.Builder(context).data(model).size(900).build(),
            ) as? SuccessResult)?.image
        }.getOrNull()
        if (image != null) ArtworkFlight.prepared(artworkUrl, image)
    }
}

/** Marks the player's big artwork ([big]) or the lyrics page's small one; hidden while the copy flies. */
internal fun Modifier.artworkFlightSlot(big: Boolean): Modifier = this
    .onGloballyPositioned {
        val bounds = it.boundsInWindow()
        if (big) ArtworkFlight.bigBounds = bounds else ArtworkFlight.smallBounds = bounds
        // Also where the sheet's artwork flies to and from the mini player.
        SheetFlight.register(if (big) SheetPart.BigArt else SheetPart.SmallArt, it)
    }
    .graphicsLayer {
        val part = if (big) SheetPart.BigArt else SheetPart.SmallArt
        alpha = if (ArtworkFlight.flight != null || SheetFlight.hides(part)) 0f else 1f
    }

/**
 * A soft spring with no bounce, like iOS moving a view: quick to leave, long
 * gentle landing, and it ends exactly in place (no last-pixel jump).
 */
private val FlightSpring = spring<Float>(dampingRatio = 1f, stiffness = 240f, visibilityThreshold = 0.0015f)

/** The flying artwork, drawn over both pages (fills the player area). */
@Composable
internal fun ArtworkFlightOverlay() {
    val flight = ArtworkFlight.flight ?: return
    val context = LocalPlatformContext.current
    val painter: Painter? = remember(flight.id) {
        ArtworkFlight.preparedImage
            ?.takeIf { flight.artworkUrl != null && ArtworkFlight.preparedUrl == flight.artworkUrl }
            ?.asPainter(context)
    }
    // Not decoded yet (rare: the flight started before the picture arrived): no copy,
    // the two pages simply cross-fade.
    if (painter == null) {
        LaunchedEffect(flight.id) { ArtworkFlight.land(flight.id) }
        return
    }
    var origin by remember { mutableStateOf<Offset?>(null) }
    var target by remember(flight.id) { mutableStateOf<Rect?>(null) }
    val progress = remember(flight.id) { Animatable(0f, visibilityThreshold = 0.0015f) }
    LaunchedEffect(flight.id) {
        // The page it flies to is laid out within a frame or two; until then the
        // copy stays where the artwork was.
        var frames = 0
        while (frames < 30) {
            val bounds = if (flight.toSmall) ArtworkFlight.smallBounds else ArtworkFlight.bigBounds
            if (bounds != null && bounds.width > 1f) {
                target = bounds
                break
            }
            withFrameNanos { }
            frames++
        }
        if (target == null) {
            ArtworkFlight.land(flight.id)
            return@LaunchedEffect
        }
        progress.animateTo(1f, FlightSpring)
        ArtworkFlight.land(flight.id)
    }
    val path = remember { Path() }
    Spacer(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInWindow() }
            // Own layer, so the bottom fade cuts only the copy.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                val o = origin ?: return@drawBehind
                val from = flight.from
                val to = target ?: from
                val p = progress.value
                fun mix(a: Float, b: Float) = a + (b - a) * p
                val left = mix(from.left, to.left) - o.x
                val top = mix(from.top, to.top) - o.y
                val width = mix(from.width, to.width)
                val height = mix(from.height, to.height)
                // 0 = the big artwork, 1 = the small one.
                val small = if (flight.toSmall) p else 1f - p
                // Square corners on the big artwork (edge to edge), 8 dp on the small one.
                val radius = 8.dp.toPx() * small
                val rect = Rect(left, top, left + width, top + height)

                path.reset()
                path.addRoundRect(RoundRect(rect, CornerRadius(radius)))
                clipPath(path) {
                    // Cropped to fill, centred, like the artwork on both pages.
                    val intrinsic = painter.intrinsicSize
                    val iw = if (intrinsic.width > 0f) intrinsic.width else width
                    val ih = if (intrinsic.height > 0f) intrinsic.height else height
                    val scale = max(width / iw, height / ih)
                    val dw = iw * scale
                    val dh = ih * scale
                    translate(left + (width - dw) / 2f, top + (height - dh) / 2f) {
                        with(painter) { draw(Size(dw, dh)) }
                    }
                }
                // The big artwork fades into the page at its bottom: the copy fades the
                // same way there and loses the fade as it becomes the small one.
                val fade = 1f - small
                if (fade > 0.01f) drawRect(
                    brush = Brush.verticalGradient(
                        colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - 0.2f * fade), Color.Black.copy(alpha = 1f - fade)),
                        startY = rect.top + rect.height * 0.4f,
                        endY = rect.bottom,
                    ),
                    topLeft = rect.topLeft,
                    size = rect.size,
                    blendMode = BlendMode.DstIn,
                )
            },
    )
}

/**
 * Set while the player and lyrics pages swap: one shared background is drawn
 * behind both and the pages' own backgrounds stand aside, so only the content
 * cross-fades (the page underneath never shows through, and one colour layer
 * is drawn instead of three).
 */
internal object PlayerPageSwap {
    var active by mutableStateOf(false)
}

internal const val PLAYER_PAGE_SWAP_MS = 700L
