package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.ExperimentalAnimationApi
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.LocalPlatformContext
import coil3.compose.asPainter
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.ui.Heroicons
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Portrait: the player opens and closes like Apple Music's. The mini player
 * grows into the player as a card (rounded corners, the page behind dimming),
 * and the player shrinks back into the mini player the same way: by its white
 * pill or by pulling it down (from wherever the finger let go). On the way the
 * artwork travels between the page and the mini player's artwork; from the
 * lyrics page the title and the artist travel too. Near the mini player end
 * the card shows the mini player's own line (title, play, next), so it turns
 * into the mini player rather than vanishing over it.
 *
 * One number drives it all: [progress], 1 with the player open, 0 when it is
 * the mini player. The page, the sheet and the mini player report where their
 * parts are ([sheetFlightPart], [artworkFlightSlot]); everything moves in the
 * drawing pass only.
 */
internal object SheetFlight {
    /** The card is growing or shrinking. */
    var moving by mutableStateOf(false)

    // These three are read where the real parts are hidden while their copies fly
    // (graphicsLayer blocks). As plain fields a change did not redraw those: the copy
    // became ready a frame after the card started moving, the real artwork and title
    // stayed visible, and the lyrics page showed them twice while it opened.

    /** Portrait (the only layout with the card). */
    var enabled by mutableStateOf(false)

    /** The sheet shows the lyrics page (otherwise the player page). */
    var lyrics by mutableStateOf(false)

    /** The artwork copy can be drawn (its picture is decoded). */
    var canFly by mutableStateOf(false)

    /** 1 = the player is open, 0 = it is the mini player (springs a little past 0 when closing). */
    var progress by mutableStateOf<State<Float>?>(null)

    /** How far the finger has pulled each page down (pixels). */
    var playerDrag by mutableStateOf<State<Float>?>(null)
    var lyricsDrag by mutableStateOf<State<Float>?>(null)

    /** The player layer (the card is drawn inside it). */
    var sheetRoot: LayoutCoordinates? = null

    var bigArt: LayoutCoordinates? = null
    var smallArt: LayoutCoordinates? = null
    var lyricsTitle: LayoutCoordinates? = null
    var lyricsArtist: LayoutCoordinates? = null
    var dockBar: LayoutCoordinates? = null
    var dockArt: LayoutCoordinates? = null
    var dockTitle: LayoutCoordinates? = null
    var dockArtist: LayoutCoordinates? = null

    val p: Float get() = progress?.value ?: 1f

    fun drag(): Float = ((if (lyrics) lyricsDrag else playerDrag)?.value ?: 0f).coerceAtLeast(0f)

    fun register(part: SheetPart, coordinates: LayoutCoordinates) {
        when (part) {
            SheetPart.BigArt -> bigArt = coordinates
            SheetPart.SmallArt -> smallArt = coordinates
            SheetPart.LyricsTitle -> lyricsTitle = coordinates
            SheetPart.LyricsArtist -> lyricsArtist = coordinates
            SheetPart.DockBar -> dockBar = coordinates
            SheetPart.DockArt -> dockArt = coordinates
            SheetPart.DockTitle -> dockTitle = coordinates
            SheetPart.DockArtist -> dockArtist = coordinates
        }
    }

    private fun LayoutCoordinates?.attached() = this?.isAttached == true

    /** The real part is hidden while its copy flies. */
    fun hides(part: SheetPart): Boolean {
        if (!enabled || !moving || !canFly) return false
        return when (part) {
            SheetPart.BigArt -> !lyrics
            SheetPart.SmallArt, SheetPart.LyricsTitle, SheetPart.LyricsArtist -> lyrics
            SheetPart.DockBar -> false
            SheetPart.DockArt -> true
            SheetPart.DockTitle -> lyrics && lyricsTitle.attached()
            SheetPart.DockArtist -> lyrics && lyricsArtist.attached()
        }
    }

    /** The card itself: opaque except at the very mini player end, where the real one shows through. */
    fun cardAlpha(p: Float) = (p / 0.15f).coerceIn(0f, 1f)

    /** The page's own content (title, controls, lyrics) shows only near the open end. */
    fun contentAlpha(p: Float) = ((p - 0.55f) / 0.4f).coerceIn(0f, 1f)

    /** The mini player's line on top of the card, near the mini player end. */
    fun labelAlpha(p: Float) = (1f - p / 0.45f).coerceIn(0f, 1f) * cardAlpha(p)

    /** The card in the player layer's (= the window's) coordinates, and its corner radius. */
    fun card(width: Float, height: Float, p: Float, drag: Float, dock: Rect?, density: Density): Pair<Rect, Float> = with(density) {
        val mini = dock ?: Rect(14.dp.toPx(), height - 150.dp.toPx(), width - 14.dp.toPx(), height - 94.dp.toPx())
        val full = Rect(0f, drag, width, height + drag)
        val t = p.coerceAtLeast(0f)
        val rect = lerp(mini, full, t)
        // Square on the open player (edge to edge), rounded as soon as it moves or is pulled.
        val open = min(1f, (1f - t) * 10f + drag / 16.dp.toPx()).coerceAtLeast(0f)
        val radius = lerp(mini.height / 2f, 24.dp.toPx() * open, t.coerceAtMost(1f))
        rect to radius
    }

    /** The mini player dips a little and settles as the card lands in it. */
    fun dockBounce(barHeight: Float): Float {
        if (!enabled || !moving) return 0f
        val p = this.p
        return if (p < 0f) -p * barHeight * 6f else 0f
    }
}

internal enum class SheetPart { BigArt, SmallArt, LyricsTitle, LyricsArtist, DockBar, DockArt, DockTitle, DockArtist }

/** Reports where this part is, and hides it while its copy flies. */
internal fun Modifier.sheetFlightPart(part: SheetPart): Modifier = this
    .onPlaced { SheetFlight.register(part, it) }
    .graphicsLayer {
        alpha = if (SheetFlight.hides(part)) 0f else 1f
        if (part == SheetPart.DockBar) translationY = SheetFlight.dockBounce(size.height)
    }

private class CardShape(private val rect: Rect, private val radius: Float) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Rounded(RoundRect(rect, CornerRadius(radius)))
}

/** The player layer as a card: cut to the card's shape while it grows, shrinks or is pulled down. */
internal fun Modifier.sheetCard(): Modifier = this
    .onPlaced { SheetFlight.sheetRoot = it }
    .graphicsLayer {
        if (!SheetFlight.enabled) return@graphicsLayer
        val p = SheetFlight.p
        val drag = SheetFlight.drag()
        alpha = SheetFlight.cardAlpha(p)
        if (p < 0.999f || drag > 0.5f) {
            val root = SheetFlight.sheetRoot?.takeIf { it.isAttached }
            val dock = SheetFlight.dockBar?.takeIf { it.isAttached && root != null }?.let { root!!.localBoundingBoxOf(it, clipBounds = false) }
            val (rect, radius) = SheetFlight.card(size.width, size.height, p, drag, dock, this)
            shape = CardShape(rect, radius)
            clip = true
        } else {
            shape = RectangleShape
            clip = false
        }
    }

/**
 * The page under the player (home, library...) while the portrait player card covers
 * all of it (open, not moving, not pulled down): not drawn, as nothing of it shows.
 * The graphics card drew the whole page and then the player on top on every frame,
 * which on a phone took about as long as the player itself. It stays composed (scroll
 * positions, loads and animations carry on) and is drawn again as soon as the card
 * moves or is pulled.
 */
internal fun Modifier.hiddenUnderOpenSheet(): Modifier = graphicsLayer {
    val progress = SheetFlight.progress
    val covered = SheetFlight.enabled && progress != null && !SheetFlight.moving &&
        progress.value >= 0.999f && SheetFlight.drag() < 0.5f
    alpha = if (covered) 0f else 1f
}

/** The pages' content inside the card: it fades out early when closing and in late when opening. */
internal fun Modifier.sheetCardContent(): Modifier = graphicsLayer {
    if (SheetFlight.enabled) alpha = SheetFlight.contentAlpha(SheetFlight.p)
}

/** The page's own background stands aside while the card draws one for the whole card. */
internal val sheetCardDrawsBackground: Boolean get() = SheetFlight.enabled && SheetFlight.moving

/** The page behind the card dims as the card grows (and lightens as it is pulled away). */
@Composable
internal fun SheetScrim() {
    Spacer(
        Modifier.fillMaxSize().drawBehind {
            if (!SheetFlight.enabled) return@drawBehind
            val progress = SheetFlight.progress ?: return@drawBehind
            val p = progress.value.coerceIn(0f, 1f)
            val pulled = (SheetFlight.drag() / size.height).coerceIn(0f, 1f)
            val alpha = 0.5f * p * (1f - 0.6f * pulled)
            if (alpha > 0.005f) drawRect(Color.Black.copy(alpha = alpha))
        },
    )
}

/** Tells the card when it moves and which page it shows; drives [SheetFlight.progress]. Inside the sheet. */
@OptIn(ExperimentalAnimationApi::class)
@Composable
internal fun AnimatedVisibilityScope.SheetFlightMotion(enabled: Boolean, lyrics: Boolean) {
    val progress = transition.animateFloat(
        transitionSpec = {
            val spec: FiniteAnimationSpec<Float> = when {
                !enabled -> snap()
                // Opening: a smooth glide into place. Closing: a little landing bounce.
                targetState == EnterExitState.Visible -> spring(dampingRatio = 1f, stiffness = 240f, visibilityThreshold = 0.001f)
                else -> spring(dampingRatio = 0.74f, stiffness = 280f, visibilityThreshold = 0.001f)
            }
            spec
        },
        label = "player-card",
    ) { if (it == EnterExitState.Visible) 1f else 0f }
    val moving = transition.currentState != transition.targetState ||
        transition.targetState != EnterExitState.Visible
    // Known before the first frame is laid out, so the card never shows at full size
    // for a frame before it starts growing from the mini player.
    if (SheetFlight.progress !== progress) SheetFlight.progress = progress
    SideEffect {
        SheetFlight.lyrics = lyrics
        SheetFlight.moving = enabled && moving
    }
    DisposableEffect(Unit) {
        onDispose {
            SheetFlight.moving = false
            SheetFlight.progress = null
        }
    }
}

private fun lerp(a: Float, b: Float, t: Float) = a + (b - a) * t
private fun lerp(a: Rect, b: Rect, t: Float) = Rect(lerp(a.left, b.left, t), lerp(a.top, b.top, t), lerp(a.right, b.right, t), lerp(a.bottom, b.bottom, t))

/** The flying copies and the mini player's line, drawn over the card and the mini player (fills the window). */
@Composable
internal fun SheetFlightOverlay(track: Track?, isPlaying: Boolean) {
    val moving = SheetFlight.moving
    val context = LocalPlatformContext.current
    val artworkUrl = track?.artworkUrl
    val painter: Painter? = remember(moving, artworkUrl) {
        if (!moving) null
        else ArtworkFlight.preparedImage
            ?.takeIf { artworkUrl != null && ArtworkFlight.preparedUrl == artworkUrl }
            ?.asPainter(context)
    }
    val ready = moving && track != null && painter != null && SheetFlight.enabled
    SideEffect { SheetFlight.canFly = ready }
    if (!ready || track == null || painter == null) return

    val measurer = rememberTextMeasurer(cacheSize = 16)
    val baseStyle = androidx.compose.material.LocalTextStyle.current
    val playIcon = rememberVectorPainter(if (isPlaying) Heroicons.PauseSolid else Heroicons.PlaySolid)
    val nextIcon = rememberVectorPainter(Heroicons.Forward)
    val self = remember { arrayOfNulls<LayoutCoordinates>(1) }
    val path = remember { Path() }
    Spacer(
        Modifier
            .fillMaxSize()
            .onPlaced { self[0] = it }
            // Own layer, so the bottom fade cuts only the copy.
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .drawBehind {
                val o = self[0]?.takeIf { it.isAttached } ?: return@drawBehind
                fun place(c: LayoutCoordinates?): Rect? =
                    c?.takeIf { it.isAttached }?.let { o.localBoundingBoxOf(it, clipBounds = false) }
                val p = SheetFlight.p
                val t = p.coerceIn(0f, 1f)
                val lyrics = SheetFlight.lyrics
                val bar = place(SheetFlight.dockBar)
                val dockArt = place(SheetFlight.dockArt) ?: return@drawBehind
                val (card, _) = SheetFlight.card(size.width, size.height, p, SheetFlight.drag(), bar, this)

                // The mini player's line at the top of the card (it becomes the real one).
                val label = SheetFlight.labelAlpha(p)
                if (label > 0.01f && bar != null) {
                    val iconSize = 26.dp.toPx()
                    val centerY = card.top + bar.height / 2f
                    val nextX = card.right - 6.dp.toPx() - 22.dp.toPx()
                    val playX = nextX - 44.dp.toPx()
                    for ((icon, x) in listOf(playIcon to playX, nextIcon to nextX)) {
                        translate(x - iconSize / 2f, centerY - iconSize / 2f) {
                            with(icon) { draw(Size(iconSize, iconSize), alpha = label, colorFilter = ColorFilter.tint(Color.White)) }
                        }
                    }
                    // From the player page the title and artist are written there too (from the
                    // lyrics page they fly there instead).
                    if (!lyrics) {
                        val textRight = playX - 22.dp.toPx()
                        place(SheetFlight.dockTitle)?.let { r ->
                            val at = Offset(card.left + (r.left - bar.left), card.top + (r.top - bar.top))
                            val layout = measurer.measure(
                                track.title, baseStyle.merge(TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)),
                                overflow = TextOverflow.Clip, softWrap = false, maxLines = 1,
                            )
                            clipRect(at.x, at.y, max(at.x, min(textRight, at.x + r.width)), at.y + r.height * 2f) {
                                drawText(layout, alpha = label, topLeft = at)
                            }
                        }
                        place(SheetFlight.dockArtist)?.let { r ->
                            val at = Offset(card.left + (r.left - bar.left), card.top + (r.top - bar.top))
                            drawFlyingLine(
                                measurer.measure(
                                    track.artist, baseStyle.merge(TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)),
                                    overflow = TextOverflow.Ellipsis, softWrap = false, maxLines = 1,
                                    constraints = Constraints(maxWidth = (r.width - if (track.explicit) 21.dp.toPx() else 0f).roundToInt().coerceAtLeast(1)),
                                ),
                                track.explicit, Color.White.copy(alpha = 0.7f), at, 1f, label,
                            )
                        }
                    }
                }

                // The artwork, between the page's and the mini player's.
                val artC = (if (lyrics) SheetFlight.smallArt else SheetFlight.bigArt)?.takeIf { it.isAttached }
                val live = artC?.let { o.localBoundingBoxOf(it, clipBounds = false) }
                val artAlpha = if (live != null) 1f else (1f - p / 0.2f).coerceIn(0f, 1f)
                if (artAlpha > 0.01f) {
                    val art = if (live != null) lerp(dockArt, live, t) else dockArt
                    // Square corners on the big one (edge to edge), 8 dp on the lyrics page's,
                    // 9 dp in the mini player.
                    val fromRadius = if (lyrics) 8.dp.toPx() else 0f
                    val radius = lerp(9.dp.toPx(), fromRadius, t)
                    path.reset()
                    path.addRoundRect(RoundRect(art, CornerRadius(radius)))
                    clipPath(path) {
                        val intrinsic = painter.intrinsicSize
                        val iw = if (intrinsic.width > 0f) intrinsic.width else art.width
                        val ih = if (intrinsic.height > 0f) intrinsic.height else art.height
                        val scale = max(art.width / iw, art.height / ih)
                        val dw = iw * scale
                        val dh = ih * scale
                        translate(art.left + (art.width - dw) / 2f, art.top + (art.height - dh) / 2f) {
                            with(painter) { draw(Size(dw, dh), alpha = artAlpha) }
                        }
                    }
                    // The player's big artwork fades into the page at its bottom: so does the
                    // copy, losing the fade as it becomes the mini player's.
                    val fade = if (lyrics || live == null) 0f else t
                    if (fade > 0.01f) drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(Color.Black, Color.Black.copy(alpha = 1f - 0.2f * fade), Color.Black.copy(alpha = 1f - fade)),
                            startY = art.top + art.height * 0.4f,
                            endY = art.bottom,
                        ),
                        topLeft = art.topLeft,
                        size = art.size,
                        blendMode = BlendMode.DstIn,
                    )
                }

                // Lyrics page: the title and the artist travel into the mini player's.
                if (!lyrics) return@drawBehind
                val q = 1f - t
                val titleStyleFrom = baseStyle.merge(TextStyle(color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold))
                val titleStyleTo = baseStyle.merge(TextStyle(color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold))
                place(SheetFlight.lyricsTitle)?.let { from ->
                    flyText(measurer, track.title, explicit = false, ellipsis = false, from = from, to = place(SheetFlight.dockTitle),
                        styleFrom = titleStyleFrom, styleTo = titleStyleTo, q = q)
                }
                val artistStyleFrom = baseStyle.merge(TextStyle(color = Color.White.copy(alpha = 0.58f), fontSize = 16.sp))
                val artistStyleTo = baseStyle.merge(TextStyle(color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp))
                place(SheetFlight.lyricsArtist)?.let { from ->
                    flyText(measurer, track.artist, explicit = track.explicit, ellipsis = true, from = from, to = place(SheetFlight.dockArtist),
                        styleFrom = artistStyleFrom, styleTo = artistStyleTo, q = q)
                }
            },
    )
}

/**
 * One line of text between the lyrics page ([from], where it is on the page now)
 * and the mini player ([to]); [q] is 0 on the page and 1 in the mini player. It
 * is drawn both ways, as on the page and as in the mini player, scaled to the
 * size in between, and the two cross-fade on the way, so it matches exactly at
 * both ends.
 */
private fun DrawScope.flyText(
    measurer: TextMeasurer,
    text: String,
    explicit: Boolean,
    ellipsis: Boolean,
    from: Rect,
    to: Rect?,
    styleFrom: TextStyle,
    styleTo: TextStyle,
    q: Float,
) {
    val badge = if (explicit) 21.dp.toPx() else 0f
    fun measure(style: TextStyle, width: Float): TextLayoutResult = measurer.measure(
        text,
        style,
        overflow = if (ellipsis) TextOverflow.Ellipsis else TextOverflow.Clip,
        softWrap = false,
        maxLines = 1,
        constraints = if (ellipsis) Constraints(maxWidth = (width - badge).roundToInt().coerceAtLeast(1)) else Constraints(),
    )
    val sizeFrom = styleFrom.fontSize.value
    val sizeTo = styleTo.fontSize.value
    // Without the mini player's line (a notice shows there), the line just fades.
    if (to == null) {
        if (q >= 1f) return
        clipRect(from.left, from.top - 2.dp.toPx(), from.right, from.bottom + from.height) {
            drawFlyingLine(measure(styleFrom, from.width), explicit, styleFrom.color, from.topLeft, 1f, 1f - q)
        }
        return
    }
    val at = Offset(lerp(from.left, to.left, q), lerp(from.top, to.top, q))
    val width = lerp(from.width, to.width, q)
    // The mini player's look takes over in the second half of the way.
    val swap = ((q - 0.35f) / 0.5f).coerceIn(0f, 1f)
    clipRect(at.x - 1f, at.y - 2.dp.toPx(), at.x + width + 1f, at.y + max(from.height, to.height) * 1.5f) {
        if (swap < 1f) drawFlyingLine(measure(styleFrom, from.width), explicit, styleFrom.color, at, lerp(1f, sizeTo / sizeFrom, q), 1f - swap)
        if (swap > 0f) drawFlyingLine(measure(styleTo, to.width), explicit, styleTo.color, at, lerp(sizeFrom / sizeTo, 1f, q), swap)
    }
}

private fun DrawScope.drawFlyingLine(layout: TextLayoutResult, explicit: Boolean, color: Color, at: Offset, scale: Float, alpha: Float) {
    if (alpha <= 0.01f) return
    withTransform({
        translate(at.x, at.y)
        scale(scale, scale, Offset.Zero)
    }) {
        val textHeight = layout.size.height.toFloat()
        if (explicit) {
            // The "E" badge in front of the artist, as on the page.
            val box = 15.dp.toPx()
            val rowHeight = max(textHeight, box)
            val top = (rowHeight - box) / 2f
            drawRoundRect(
                color.copy(alpha = color.alpha * 0.6f * alpha),
                topLeft = Offset(0f, top),
                size = Size(box, box),
                cornerRadius = CornerRadius(2.dp.toPx()),
                style = Stroke(2.dp.toPx()),
            )
            drawText(layout, alpha = alpha, topLeft = Offset(21.dp.toPx(), (rowHeight - textHeight) / 2f))
        } else {
            drawText(layout, alpha = alpha, topLeft = Offset.Zero)
        }
    }
}
