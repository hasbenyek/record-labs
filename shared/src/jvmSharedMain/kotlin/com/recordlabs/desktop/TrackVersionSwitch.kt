package com.recordlabs.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.TrackCounterpart
import com.recordlabs.desktop.data.hasMusicVideo
import kotlinx.coroutines.launch

/**
 * The Song / Video switch of YouTube Music: the playing song's other version
 * (its official music video, or a music video's song) and the action that
 * switches to it at the same moment of the music. Set by the app window.
 */
internal object TrackVersions {
    /** The song [counterpart] belongs to. */
    var forTrackId by mutableStateOf<String?>(null)
        private set
    var counterpart by mutableStateOf<TrackCounterpart?>(null)
        private set
    var onSwitch: (() -> Unit)? = null

    fun set(trackId: String?, value: TrackCounterpart?) {
        forTrackId = trackId
        counterpart = value
    }

    /** The other version of [track], when it has one. */
    fun counterpartOf(track: Track): TrackCounterpart? = counterpart?.takeIf { forTrackId == track.id }
}

/** Swiped further than this (in pixels), the artwork switches between song and video. */
private const val VERSION_SWIPE_THRESHOLD = 110f

/**
 * Swiping the artwork from right to left switches the song to its music
 * video, and from left to right back to the song (when it has the other
 * version). The artwork follows the finger a little while dragging.
 */
internal fun Modifier.trackVersionSwipe(track: Track): Modifier = composed {
    val counterpart = TrackVersions.counterpartOf(track) ?: return@composed this
    val latestTrack by rememberUpdatedState(track)
    val scope = rememberCoroutineScope()
    val offset = remember(track.id) { Animatable(0f) }
    val toVideo = counterpart.track.hasMusicVideo && !track.hasMusicVideo
    this
        // A plain offset (no extra drawing layer over the video / animated artwork).
        .offset { androidx.compose.ui.unit.IntOffset(offset.value.toInt(), 0) }
        .pointerInput(track.id, toVideo) {
            var total = 0f
            detectHorizontalDragGestures(
                onDragStart = { total = 0f },
                onDragEnd = {
                    // To the video: right to left. Back to the song: left to right.
                    val switch = if (toVideo) total < -VERSION_SWIPE_THRESHOLD else total > VERSION_SWIPE_THRESHOLD
                    if (switch && TrackVersions.counterpartOf(latestTrack) != null) TrackVersions.onSwitch?.invoke()
                    scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 380f)) }
                },
                onDragCancel = { scope.launch { offset.animateTo(0f, spring(dampingRatio = 0.7f, stiffness = 380f)) } },
                onHorizontalDrag = { change, amount ->
                    change.consume()
                    total += amount
                    // Only the way that switches follows the finger fully.
                    val wanted = if ((toVideo && total < 0f) || (!toVideo && total > 0f)) total * 0.4f else total * 0.12f
                    scope.launch { offset.snapTo(wanted) }
                },
            )
        }
}

/** Pointer activity over the artwork, which keeps the Song / Video pill showing. */
internal class TrackVersionToggleState {
    var lastActivity by mutableStateOf(System.nanoTime())
    var hovered by mutableStateOf(false)
}

@Composable
internal fun rememberTrackVersionToggleState(): TrackVersionToggleState = remember { TrackVersionToggleState() }

/** Seconds without any touch, move or hover over the artwork before the pill hides. */
private const val TOGGLE_IDLE_MS = 3_000L

/**
 * Watches the artwork area (without taking any touches from it): moving,
 * hovering or touching it shows the Song / Video pill again.
 */
internal fun Modifier.trackVersionActivity(state: TrackVersionToggleState): Modifier =
    pointerInput(state) {
        awaitPointerEventScope {
            while (true) {
                val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                when (event.type) {
                    androidx.compose.ui.input.pointer.PointerEventType.Enter -> state.hovered = true
                    androidx.compose.ui.input.pointer.PointerEventType.Exit -> state.hovered = false
                }
                state.lastActivity = System.nanoTime()
            }
        }
    }

/**
 * "Song | Video" pill over the artwork, like YouTube Music's switch: shown
 * only when the playing song has the other version; tapping the other side
 * switches to it. It hides after [TOGGLE_IDLE_MS] without activity over the
 * artwork (and stays while the mouse rests on it).
 */
@Composable
internal fun TrackVersionToggle(track: Track, state: TrackVersionToggleState, modifier: Modifier = Modifier) {
    val counterpart = TrackVersions.counterpartOf(track)
    var recentlyActive by remember { mutableStateOf(true) }
    // A new song (or its other version just found) shows the pill again.
    androidx.compose.runtime.LaunchedEffect(state.lastActivity, track.id, counterpart != null) {
        recentlyActive = true
        kotlinx.coroutines.delay(TOGGLE_IDLE_MS)
        recentlyActive = false
    }
    AnimatedVisibility(
        visible = counterpart != null && (recentlyActive || state.hovered),
        enter = fadeIn(tween(300)),
        exit = fadeOut(tween(200)),
        modifier = modifier,
    ) {
        val isVideo = track.hasMusicVideo
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .background(Color.Black.copy(alpha = 0.42f))
                .border(1.dp, Color.White.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Icons instead of words (smaller): a note for the song, a film for the video.
            listOf(
                false to androidx.compose.material.icons.Icons.Default.MusicNote,
                true to androidx.compose.material.icons.Icons.Default.Movie,
            ).forEach { (videoSide, icon) ->
                val selected = videoSide == isVideo
                Box(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) Color.White.copy(alpha = 0.9f) else Color.Transparent)
                        .clickable(enabled = !selected) { TrackVersions.onSwitch?.invoke() }
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material.Icon(
                        icon,
                        contentDescription = if (videoSide) "Video" else "Song",
                        tint = if (selected) Color.Black else Color.White,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
        }
    }
}

/**
 * Whose lyrics show for the playing [track]: a music video shows the lyrics of its
 * song (its Song version), the same lyrics as in audio mode, instead of looking up
 * lyrics for the video on its own (often other, unsynced or missing ones). The
 * video's position is carried over to the song's timeline (a video often has a
 * longer intro, see [TrackCounterpart.segments]), and a tap on a line seeks back
 * into the video. Every other track shows its own lyrics.
 */
internal class LyricsSource(
    val track: Track,
    private val toSong: com.recordlabs.desktop.data.TrackCounterpart? = null,
    private val toVideo: com.recordlabs.desktop.data.TrackCounterpart? = null,
) {
    /** The lyrics' time for [playbackMs] of the playing version. */
    fun lyricsPosition(playbackMs: Long): Long = toSong?.mapPosition(playbackMs)?.coerceAtLeast(0L) ?: playbackMs

    /** Where a lyric line's time [lyricsMs] plays in the playing version. */
    fun playbackPosition(lyricsMs: Long): Long = toVideo?.mapPosition(lyricsMs)?.coerceAtLeast(0L) ?: lyricsMs
}

@Composable
internal fun rememberLyricsSource(track: Track): LyricsSource {
    // Found (and lined up) shortly after the video starts; until then its own lyrics show.
    val counterpart = TrackVersions.counterpartOf(track)
    return remember(track, counterpart) {
        val song = counterpart?.takeIf { track.hasMusicVideo && !it.track.hasMusicVideo }
        if (song == null) LyricsSource(track) else LyricsSource(song.track, song, song.reversed(track))
    }
}
