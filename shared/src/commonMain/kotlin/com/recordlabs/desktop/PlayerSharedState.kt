package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsPreferences

// Player state shared by the app UI and the desktop-only views (floating
// lyrics window, full-screen music video).

object FloatingLyricsState {
    var isEnabled by mutableStateOf(WindowsPreferences.floatingLyricsEnabled())
    var isPipMinimized by mutableStateOf(true)
    var currentTrack by mutableStateOf<Track?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var durationMs by mutableStateOf(0L)
    var onPlayPause: (() -> Unit)? = null
    var onPrevious: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var requestMinimizeDesktop: (() -> Unit)? = null
    var requestRestoreDesktop: (() -> Unit)? = null

    fun activatePip() {
        isEnabled = true
        WindowsPreferences.setFloatingLyricsEnabled(true)
        isPipMinimized = false
        requestMinimizeDesktop?.invoke()
    }

    fun togglePip() {
        if (!isEnabled) {
            activatePip()
        } else {
            setPipEnabled(false)
        }
    }

    fun setPipEnabled(enabled: Boolean) {
        if (enabled) {
            activatePip()
        } else {
            isEnabled = false
            WindowsPreferences.setFloatingLyricsEnabled(false)
            isPipMinimized = true
        }
    }
}

/**
 * The music video shown over the whole window (the artwork's full-screen
 * button). The artwork's own decoder keeps running and hands its frames here.
 */
internal object MusicVideoFullscreen {
    var trackId by mutableStateOf<String?>(null)
        private set
    var frame by mutableStateOf<ImageBitmap?>(null, androidx.compose.runtime.neverEqualPolicy())
    /**
     * Picture height the user picked in the full-screen quality menu (null =
     * Auto: sized to the artwork box, as before). Kept for the whole session.
     */
    var quality by mutableStateOf<Int?>(null)
        private set
    /** The app's current accent colour (the full-screen view sits outside the app theme). */
    var accent by mutableStateOf(Color(0xFFFFFFFF))
    /**
     * Subtitle language the user picked (null = off), and the track's name to
     * tell apart two tracks in one language. Kept for the session, so the next
     * video shows subtitles in that language too when it has them.
     */
    var captionLanguage by mutableStateOf<String?>(null)
    var captionName by mutableStateOf<String?>(null)

    fun selectQuality(height: Int?) {
        quality = height
    }

    /** Previous / Next in full screen: carry on with the new song's music video. */
    fun follow(id: String) {
        frame = null
        // Already full screen: the next video does not grow again.
        openedFrom = null
        trackId = id
    }

    /**
     * Where the video was on screen when full screen was opened (window
     * coordinates), so the full-screen view grows out of it. Null = no growth.
     */
    var openedFrom: androidx.compose.ui.geometry.Rect? = null
        private set

    fun open(id: String, current: ImageBitmap?, from: androidx.compose.ui.geometry.Rect? = null) {
        frame = current
        openedFrom = from
        trackId = id
    }

    /** Where the page's video box is now (window coordinates), for leaving full screen. */
    var videoBoxBounds by mutableStateOf<androidx.compose.ui.geometry.Rect?>(null)
    /** The last full-screen picture, shrinking back into the page's video box. */
    var exitFrame by mutableStateOf<ImageBitmap?>(null)
        private set

    fun exitDone() {
        exitFrame = null
    }

    fun close() {
        // Leaving full screen shrinks the last picture back into its box.
        if (trackId != null) exitFrame = frame
        trackId = null
        frame = null
    }
}

internal const val ARTWORK_SHAPE_DURATION_MS = 450

/**
 * Size of an artwork box with room for [edge] x [edge]: square for covers, and
 * the music video's own shape (fitted inside the same square) while one plays,
 * changing smoothly between the two.
 */
@Composable
internal fun artworkBoxSize(edge: Dp, musicVideoAspect: Float?): DpSize {
    // Only the shape animates; the edge follows the layout at once, as before.
    val aspect = animateFloatAsState(
        musicVideoAspect?.takeIf { it > 0f } ?: 1f,
        tween(ARTWORK_SHAPE_DURATION_MS, easing = FastOutSlowInEasing),
    ).value
    return if (aspect >= 1f) DpSize(edge, edge / aspect) else DpSize(edge * aspect, edge)
}

/**
 * Edge (long side) of the artwork box. A square cover uses [squareEdge]; a
 * playing music video (wider than tall) may grow into the room around it, up
 * to [maxWidth] wide and [maxHeight] tall, so it is not shown smaller than the
 * cover was. Changes animate together with the box's shape.
 */
@Composable
internal fun musicVideoEdge(squareEdge: Dp, musicVideoAspect: Float?, maxWidth: Dp, maxHeight: Dp): Dp {
    val aspect = musicVideoAspect?.takeIf { it > 1f }
    val target = if (aspect == null) squareEdge
    else minOf(maxWidth, maxHeight * aspect).coerceAtLeast(squareEdge)
    return androidx.compose.animation.core.animateDpAsState(
        target,
        tween(ARTWORK_SHAPE_DURATION_MS, easing = FastOutSlowInEasing),
        label = "music-video-edge",
    ).value
}
