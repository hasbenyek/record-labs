package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Icon
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.PhoneIphone
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import com.recordlabs.desktop.data.LyricsTranslationSettings
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.ui.Heroicons
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/*
 * Portrait (phone-shaped window) parts of the music player and lyrics pages,
 * laid out like Apple Music on iPhone: title row with round glass buttons, a
 * thin seek bar with the remaining time, large transport buttons, the volume
 * bar below them, and Lyrics / Devices / Queue along the bottom. The queue
 * replaces the artwork (shuffle, repeat and autoplay at its top, the songs
 * that come next below), and pulling it down shows the songs played before.
 *
 * Kept free of desktop-only APIs where possible so it can move to a shared
 * Kotlin Multiplatform module for Android and iOS later.
 */

private val PortraitDim = Color.White.copy(alpha = 0.58f)
private val GlassFill = Color.White.copy(alpha = 0.16f)
private val SelectedFill = Color.White.copy(alpha = 0.82f)
private val SelectedGlyph = Color(0xFF2A2522)

/** Which of the bottom buttons is lit. */
internal enum class PortraitTab { None, Lyrics, Queue }

/** Round see-through button (like / more) of the title rows. */
@Composable
internal fun GlassCircleButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 34.dp,
    iconSize: Dp = 18.dp,
    tint: Color = Color.White,
) {
    BouncyIconButton(onClick = onClick, modifier = modifier.size(size + 8.dp)) {
        Box(Modifier.size(size).clip(CircleShape).background(GlassFill), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription, tint = tint, modifier = Modifier.size(iconSize))
        }
    }
}

/** Like + more buttons, shared by every title row. */
@Composable
private fun TitleRowButtons(isLiked: Boolean, onToggleLiked: () -> Unit, onMore: () -> Unit, extra: (@Composable () -> Unit)? = null) {
    extra?.invoke()
    GlassCircleButton(
        if (isLiked) Heroicons.HeartSolid else Heroicons.Heart,
        if (isLiked) "Remove from liked songs" else "Like",
        onToggleLiked,
    )
    GlassCircleButton(Icons.Default.MoreHoriz, "More", onMore, iconSize = 20.dp)
}

/** Big title row under the artwork (music player page). */
@Composable
internal fun PortraitTitleRow(
    track: Track,
    isLiked: Boolean,
    onToggleLiked: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    extra: (@Composable () -> Unit)? = null,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            TrackTitleText(track, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, marquee = true)
            ExplicitArtistText(track, color = PortraitDim, fontSize = 19.sp)
        }
        TitleRowButtons(isLiked, onToggleLiked, onMore, extra)
    }
}

/** Small artwork + title row at the top of the queue and lyrics views. */
@Composable
internal fun PortraitMiniHeader(
    track: Track,
    isLiked: Boolean,
    onToggleLiked: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
    onArtworkClick: (() -> Unit)? = null,
    /** The lyrics page's artwork: where the player's artwork flies to and from. */
    flightSlot: Boolean = false,
) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        val artModifier = Modifier.size(64.dp)
            // No drop shadow here (nor on the flying copy): the small artwork sits flat on the page.
            .let { if (flightSlot) it.artworkFlightSlot(big = false) else it }
        Artwork(
            track.artworkUrl,
            if (onArtworkClick != null) artModifier.clickable(onClick = onArtworkClick) else artModifier,
            RoundedCornerShape(8.dp),
        )
        Column(Modifier.padding(start = 14.dp).weight(1f)) {
            // The lyrics page's title and artist fly to and from the mini player's.
            TrackTitleText(
                track, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, marquee = true,
                modifier = if (flightSlot) Modifier.sheetFlightPart(SheetPart.LyricsTitle) else Modifier,
            )
            ExplicitArtistText(
                track, color = PortraitDim, fontSize = 16.sp,
                modifier = if (flightSlot) Modifier.sheetFlightPart(SheetPart.LyricsArtist) else Modifier,
            )
        }
        TitleRowButtons(isLiked, onToggleLiked, onMore)
    }
}

/**
 * Thin rounded bar used for the song position and the volume: press or drag
 * anywhere on it; it thickens a little while hovered or dragged.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun PortraitSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onValueChangeFinished: () -> Unit = {},
    activeColor: Color = Color.White.copy(alpha = 0.88f),
    inactiveColor: Color = Color.White.copy(alpha = 0.22f),
    barHeight: Dp = 7.dp,
) {
    var hovered by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    val height by animateDpAsState(if (hovered || dragging) barHeight + 4.dp else barHeight, tween(140), label = "portrait-slider-height")
    val change by rememberUpdatedState(onValueChange)
    val finished by rememberUpdatedState(onValueChangeFinished)
    Box(
        modifier
            .height(26.dp)
            .onPointerEvent(PointerEventType.Enter) { hovered = true }
            .onPointerEvent(PointerEventType.Exit) { hovered = false }
            .pointerInput(Unit) {
                fun valueAt(x: Float) = (x / size.width.coerceAtLeast(1)).coerceIn(0f, 1f)
                awaitEachGesture {
                    val down = awaitFirstDown()
                    down.consume()
                    dragging = true
                    change(valueAt(down.position.x))
                    while (true) {
                        val event = awaitPointerEvent()
                        val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!pointer.pressed) break
                        if (pointer.positionChange() != Offset.Zero) change(valueAt(pointer.position.x))
                        pointer.consume()
                    }
                    dragging = false
                    finished()
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val radius = CornerRadius(size.height / 2f)
            drawRoundRect(inactiveColor, cornerRadius = radius)
            val filled = size.width * value.coerceIn(0f, 1f)
            if (filled > 0f) drawRoundRect(activeColor, size = Size(filled.coerceAtLeast(size.height), size.height), cornerRadius = radius)
        }
    }
}

/** Seek bar with the elapsed time at the left and the remaining time at the right. */
@Composable
internal fun PortraitSeekBar(track: Track, positionMs: Long, durationMs: Long, onSeek: (Long) -> Unit) {
    val safeDuration = durationMs.coerceAtLeast(1L)
    var seeking by remember(track.id) { mutableStateOf(false) }
    var pending by remember(track.id) { mutableStateOf(0f) }
    LaunchedEffect(track.id, positionMs, safeDuration, seeking) {
        if (!seeking) pending = (positionMs.toFloat() / safeDuration).coerceIn(0f, 1f)
    }
    Column(Modifier.fillMaxWidth()) {
        if (com.recordlabs.desktop.data.AppSettings.seekBarStyle.value == "classic") {
            // The earlier slider with a round knob (Settings > Appearance > Sliders).
            androidx.compose.material.Slider(
                value = pending,
                onValueChange = { seeking = true; pending = it },
                onValueChangeFinished = { onSeek((pending * safeDuration).toLong()); seeking = false },
                colors = androidx.compose.material.SliderDefaults.colors(
                    thumbColor = androidx.compose.material.MaterialTheme.colors.primary,
                    activeTrackColor = androidx.compose.material.MaterialTheme.colors.primary,
                    inactiveTrackColor = Color.White.copy(alpha = 0.3f),
                ),
                modifier = Modifier.fillMaxWidth().height(32.dp),
            )
        } else PortraitSlider(
            value = pending,
            onValueChange = { seeking = true; pending = it },
            onValueChangeFinished = { onSeek((pending * safeDuration).toLong()); seeking = false },
            modifier = Modifier.fillMaxWidth(),
        )
        val shownPosition = if (seeking) (pending * safeDuration).toLong() else positionMs
        Box(Modifier.fillMaxWidth()) {
            Text(shownPosition.toClock(), color = PortraitDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterStart))
            if (track.isLocalLossless()) {
                Text("Lossless", color = Color.White.copy(alpha = 0.8f), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.align(Alignment.Center))
            }
            val remaining = (durationMs - shownPosition).coerceAtLeast(0L)
            Text("-" + remaining.toClock(), color = PortraitDim, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.align(Alignment.CenterEnd))
        }
    }
}

/** Previous / play-pause / next, large and evenly spaced. */
@Composable
internal fun PortraitTransportRow(
    isPlaying: Boolean,
    isLoading: Boolean,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    scale: Float = 1f,
) {
    Row(
        modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(34.dp * scale, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BouncyIconButton(onClick = onPrevious, keyPulse = PlaybackKeyAction.Previous, modifier = Modifier.size(64.dp * scale)) {
            Icon(Heroicons.Backward, "Previous", tint = Color.White, modifier = Modifier.size(42.dp * scale))
        }
        BouncyIconButton(onClick = onPlayPause, keyPulse = PlaybackKeyAction.PlayPause, modifier = Modifier.size(76.dp * scale), enabled = !isLoading || isPlaying) {
            if (isLoading) {
                androidx.compose.material.CircularProgressIndicator(Modifier.size(40.dp * scale), color = Color.White, strokeWidth = 3.dp)
            } else {
                Icon(if (isPlaying) Heroicons.PauseSolid else Heroicons.PlaySolid, if (isPlaying) "Pause" else "Play", tint = Color.White, modifier = Modifier.size(58.dp * scale))
            }
        }
        BouncyIconButton(onClick = onNext, keyPulse = PlaybackKeyAction.Next, modifier = Modifier.size(64.dp * scale)) {
            Icon(Heroicons.Forward, "Next", tint = Color.White, modifier = Modifier.size(42.dp * scale))
        }
    }
}

/** Volume bar between a small and a loud speaker. */
@Composable
internal fun PortraitVolumeRow(volume: Float, onVolume: (Float) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (volume <= 0f) Icons.Default.VolumeMute else Icons.Default.VolumeDown, null, tint = PortraitDim, modifier = Modifier.size(18.dp))
        if (com.recordlabs.desktop.data.AppSettings.volumeBarStyle.value == "classic") {
            androidx.compose.material.Slider(
                value = volume,
                onValueChange = onVolume,
                colors = androidx.compose.material.SliderDefaults.colors(
                    thumbColor = Color.White,
                    activeTrackColor = Color.White,
                    inactiveTrackColor = Color.White.copy(alpha = 0.25f),
                ),
                modifier = Modifier.weight(1f).padding(horizontal = 4.dp).height(30.dp),
            )
        } else PortraitSlider(volume, onVolume, Modifier.weight(1f).padding(horizontal = 10.dp), activeColor = Color.White.copy(alpha = 0.72f))
        Icon(Icons.Default.VolumeUp, null, tint = PortraitDim, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun PortraitTabButton(selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val fill by animateFloatAsState(if (selected) 1f else 0f, tween(200), label = "portrait-tab-fill")
    Box(modifier, contentAlignment = Alignment.Center) {
        BouncyIconButton(onClick = onClick, modifier = Modifier.size(54.dp)) {
            // Selected: a soft frosted square behind the icon, like Apple Music.
            Box(
                Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.22f * fill)),
                contentAlignment = Alignment.Center,
            ) { content() }
        }
    }
}

/** Lyrics, devices and queue along the bottom of the portrait pages. */
@Composable
internal fun PortraitBottomBar(
    tab: PortraitTab,
    onLyrics: () -> Unit,
    onDevices: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val tint = { selected: Boolean -> if (selected) Color.White else Color.White.copy(alpha = 0.78f) }
    Row(modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        PortraitTabButton(tab == PortraitTab.Lyrics, onLyrics, Modifier.weight(1f)) {
            Icon(appPainter("lyrics_icon.png"), "Lyrics", tint = tint(tab == PortraitTab.Lyrics), modifier = Modifier.size(24.dp))
        }
        val remote = ConnectOutput.remoteName.takeIf { AppPlatform.isIos }
        if (remote != null) {
            // Playing on another device (Connect), as Apple Music shows AirPlay: a wide
            // capsule with this device on the left and the one playing on the right.
            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                BouncyIconButton(onClick = onDevices, modifier = Modifier.width(112.dp).height(54.dp)) {
                    Row(
                        Modifier
                            .width(104.dp)
                            .height(36.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.White.copy(alpha = 0.16f)),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.16f)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(Icons.Default.PhoneIphone, "This iPhone", tint = Color.White, modifier = Modifier.size(19.dp))
                        }
                        Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Icon(
                                if (ConnectOutput.remoteIsPhone) Icons.Default.PhoneIphone else Icons.Default.Computer,
                                "Playing on $remote",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }
        } else {
            PortraitTabButton(false, onDevices, Modifier.weight(1f)) {
                Icon(appPainter("record_labs_connect.svg"), "Connected devices", tint = tint(false), modifier = Modifier.size(25.dp))
            }
        }
        PortraitTabButton(tab == PortraitTab.Queue, onQueue, Modifier.weight(1f)) {
            Icon(Icons.Default.FormatListBulleted, "Queue", tint = tint(tab == PortraitTab.Queue), modifier = Modifier.size(26.dp))
        }
    }
    val remote = ConnectOutput.remoteName.takeIf { AppPlatform.isIos }
    androidx.compose.animation.AnimatedVisibility(
        visible = remote != null,
        enter = androidx.compose.animation.fadeIn(tween(220)) + androidx.compose.animation.expandVertically(tween(220)),
        exit = androidx.compose.animation.fadeOut(tween(180)) + androidx.compose.animation.shrinkVertically(tween(180)),
    ) {
        Text(
            "${AppPlatform.deviceName.ifBlank { "iPhone" }} → ${remote.orEmpty()}",
            color = Color.White.copy(alpha = 0.62f),
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
        )
    }
}

/** The device playing instead of this one (Record Labs Connect), or null; set by the app window. */
internal object ConnectOutput {
    var remoteName by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The device playing is a phone (iPhone, Android), not a computer. */
    var remoteIsPhone by androidx.compose.runtime.mutableStateOf(false)
}

/** Rounded square above the seek bar on the lyrics page: lyrics translation on / off. */
@Composable
internal fun PortraitTranslateButton(modifier: Modifier = Modifier) {
    val enabled = LyricsTranslationSettings.enabled
    val fill by animateFloatAsState(if (enabled) 1f else 0f, tween(200), label = "translate-fill")
    BouncyIconButton(onClick = { LyricsTranslationSettings.toggle() }, modifier = modifier.size(52.dp)) {
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(lerpColor(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.86f), fill)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Default.Translate,
                if (enabled) "Turn off lyrics translation" else "Translate lyrics",
                tint = lerpColor(Color.White, SelectedGlyph, fill),
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Everything below the title row: seek bar, transport, volume and the bottom buttons. */
@Composable
internal fun PortraitControls(
    track: Track,
    isPlaying: Boolean,
    isLoading: Boolean,
    positionMs: Long,
    durationMs: Long,
    volume: Float,
    tab: PortraitTab,
    onSeek: (Long) -> Unit,
    onPrevious: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onVolume: (Float) -> Unit,
    onLyrics: () -> Unit,
    onDevices: () -> Unit,
    onQueue: () -> Unit,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    Column(modifier.fillMaxWidth()) {
        PortraitSeekBar(track, positionMs, durationMs, onSeek)
        Spacer(Modifier.height(if (compact) 6.dp else 16.dp))
        PortraitTransportRow(isPlaying, isLoading, onPrevious, onPlayPause, onNext, scale = if (compact) 0.86f else 1f)
        Spacer(Modifier.height(if (compact) 6.dp else 18.dp))
        PortraitVolumeRow(volume, onVolume)
        Spacer(Modifier.height(if (compact) 4.dp else 14.dp))
        PortraitBottomBar(tab, onLyrics, onDevices, onQueue)
    }
}

// ---- queue ------------------------------------------------------------------

/** One of the three wide buttons (shuffle / repeat / autoplay) at the top of the queue. */
@Composable
private fun QueueModeButton(
    active: Boolean,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier,
    icon: @Composable (Color) -> Unit,
) {
    val fill by animateFloatAsState(if (active) 1f else 0f, tween(180), label = "queue-mode-fill")
    Box(
        modifier
            .height(42.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(lerpColor(GlassFill, SelectedFill, fill))
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        icon(if (active) SelectedGlyph else Color.White)
    }
}

private fun lerpColor(a: Color, b: Color, t: Float) = androidx.compose.ui.graphics.lerp(a, b, t.coerceIn(0f, 1f))

@Composable
private fun QueueSongRow(track: Track, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Artwork(track.artworkUrl, Modifier.size(48.dp), RoundedCornerShape(6.dp))
        // Queue rows follow the usual rules (no album/artist links on Android), also in the player.
        androidx.compose.runtime.CompositionLocalProvider(LocalTrackLinks provides null) {
            Column(Modifier.padding(start = 14.dp).weight(1f)) {
                TrackTitleText(track, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Normal, maxLines = 1)
                ExplicitArtistText(track, color = PortraitDim, fontSize = 14.sp)
            }
        }
        trailing?.invoke()
    }
}

/** Three short lines: the grip for moving a song. */
@Composable
private fun ReorderGrip() {
    Canvas(Modifier.size(width = 26.dp, height = 18.dp)) {
        val stroke = 1.6.dp.toPx()
        listOf(0.2f, 0.5f, 0.8f).forEach { f ->
            drawLine(Color.White.copy(alpha = 0.45f), Offset(0f, size.height * f), Offset(size.width, size.height * f), stroke)
        }
    }
}

/** Fades the last rows of a list out instead of cutting them. */
private fun Modifier.bottomFade(fraction: Float = 0.16f) = this
    .graphicsLayer { alpha = 0.99f }
    .drawWithContent {
        drawContent()
        drawRect(
            brush = Brush.verticalGradient(
                0f to Color.Black,
                (1f - fraction) to Color.Black,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * The queue view of the portrait player, one continuous list like Apple Music:
 * the songs played before (oldest first, under a "History" header), then the song playing with shuffle /
 * repeat / autoplay, then the songs that come next (drag the grip to move one,
 * drag it sideways to remove it). It opens at the song playing; scrolling down
 * from there (pulling the list down) brings the history in above it.
 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun PortraitQueuePanel(
    track: Track,
    queue: List<Track>,
    activeIndex: Int,
    history: List<Track>,
    isLiked: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: Int,
    autoplayEnabled: Boolean,
    onToggleLiked: () -> Unit,
    onMore: () -> Unit,
    onShuffle: () -> Unit,
    onRepeat: () -> Unit,
    onAutoplay: () -> Unit,
    onSelect: (Int) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
    onPlayHistory: (Track) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // History arrives newest first; shown oldest first so the latest sits right above the song playing.
    val played = remember(history, track.id) { history.filter { it.id != track.id }.reversed() }
    // Index of the song playing in the list: the history header, its songs, then this.
    val nowIndex = if (played.isEmpty()) 0 else played.size + 1
    val list = rememberLazyListState(initialFirstVisibleItemIndex = nowIndex)
    val reorder = remember { ReorderState() }
    val upcoming = remember(queue, activeIndex) { queue.drop((activeIndex + 1).coerceAtLeast(0)) }
    val visual = remember { mutableStateOf(upcoming) }
    LaunchedEffect(upcoming) { if (reorder.draggedId == null) visual.value = upcoming }
    val scope = rememberCoroutineScope()
    // A new song (or a history entry played): back to the song playing.
    LaunchedEffect(track.id) { list.scrollToItem(nowIndex) }

    Box(
        modifier
            .then(
                if (AppPlatform.isPhone) Modifier
                // Desktop: mouse drags scroll the list too (desktop lists only follow the wheel).
                else Modifier.pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        var dragging = false
                        var totalY = 0f
                        var totalX = 0f
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!change.pressed) break
                            if (reorder.active) return@awaitEachGesture
                            val delta = change.positionChange()
                            if (!dragging) {
                                totalY += delta.y
                                totalX += delta.x
                                if (abs(totalY) > viewConfiguration.touchSlop && abs(totalY) > abs(totalX)) dragging = true else continue
                            }
                            change.consume()
                            list.dispatchRawDelta(-delta.y)
                        }
                    }
                },
            ),
    ) {
        LazyColumn(
            Modifier.fillMaxSize().bottomFade(),
            state = list,
            userScrollEnabled = !reorder.active,
            contentPadding = PaddingValues(bottom = 24.dp),
        ) {
            if (played.isNotEmpty()) {
                item(key = "history-header") {
                    Row(
                        Modifier.fillMaxWidth().background(Color.Transparent).padding(bottom = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("History", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text(
                            "Clear",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 16.sp,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClearHistory).padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                items(played, key = { "h:" + it.id }) { item ->
                    QueueSongRow(item, Modifier.clip(RoundedCornerShape(8.dp)).clickable { onPlayHistory(item) })
                }
            }
            item(key = "now-playing") {
                Column(Modifier.fillMaxWidth().padding(top = if (played.isEmpty()) 0.dp else 14.dp)) {
                    PortraitMiniHeader(track, isLiked, onToggleLiked, onMore)
                    Spacer(Modifier.height(16.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        QueueModeButton(shuffleEnabled, "Shuffle", onShuffle, Modifier.weight(1f)) { tint ->
                            Icon(appPainter("shuffle_icon.png"), null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        QueueModeButton(repeatMode > 0, if (repeatMode == 2) "Repeat one" else "Repeat", onRepeat, Modifier.weight(1f)) { tint ->
                            Icon(appPainter(if (repeatMode == 2) "repeat_one_icon.png" else "repeat_icon.png"), null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        QueueModeButton(autoplayEnabled, "Autoplay", onAutoplay, Modifier.weight(1f)) { tint ->
                            Icon(Icons.Default.AllInclusive, null, tint = tint, modifier = Modifier.size(24.dp))
                        }
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("Continue Playing", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    if (autoplayEnabled) Text("Autoplaying similar music", color = PortraitDim, fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                }
            }
            if (visual.value.isEmpty()) {
                item(key = "nothing-next") {
                    Box(Modifier.fillMaxWidth().padding(vertical = 32.dp), contentAlignment = Alignment.Center) {
                        Text("Nothing up next", color = PortraitDim, fontSize = 14.sp)
                    }
                }
            } else {
                upNextItems(queue, activeIndex, upcoming, visual, list, reorder, scope, onSelect, onMove, onRemove)
            }
        }
    }
}

/** Shared by the rows and the panel: a song is being moved, so the panel must not scroll. */
private class ReorderState {
    var active by mutableStateOf(false)
    var draggedId by mutableStateOf<String?>(null)
    var offsetY by mutableStateOf(0f)
    var offsetX by mutableStateOf(0f)
}

/** The songs after the one playing, as rows of the queue list (see [PortraitQueuePanel]). */
private fun androidx.compose.foundation.lazy.LazyListScope.upNextItems(
    queue: List<Track>,
    activeIndex: Int,
    upcoming: List<Track>,
    visualState: androidx.compose.runtime.MutableState<List<Track>>,
    state: LazyListState,
    reorder: ReorderState,
    scope: kotlinx.coroutines.CoroutineScope,
    onSelect: (Int) -> Unit,
    onMove: (String, Int) -> Unit,
    onRemove: (String) -> Unit,
) {
    var visual by visualState
        items(visual, key = Track::id) { item ->
            val density = androidx.compose.ui.platform.LocalDensity.current
            val removeDistance = with(density) { 96.dp.toPx() }
            val gripWidth = with(density) { 64.dp.toPx() }
            val dragged = reorder.draggedId == item.id
            Box(
                Modifier
                    .fillMaxWidth()
                    .zIndex(if (dragged) 1f else 0f)
                    .then(if (dragged) Modifier else Modifier.animateItem(fadeInSpec = null, fadeOutSpec = null))
                    .graphicsLayer {
                        if (dragged) {
                            translationY = reorder.offsetY
                            translationX = reorder.offsetX.coerceAtMost(0f)
                            alpha = (1f - abs(reorder.offsetX) / (removeDistance * 2.2f)).coerceIn(0.3f, 1f)
                            scaleX = 1.02f
                            scaleY = 1.02f
                        }
                    }
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (dragged) Color.White.copy(alpha = 0.10f) else Color.Transparent)
                    .pointerInput(item.id) {
                        // Only the grip (right edge) moves a song; the rest of the row
                        // plays it or scrolls the list.
                        awaitEachGesture {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            if (down.position.x < size.width - gripWidth) return@awaitEachGesture
                            down.consume()
                            reorder.active = true
                            reorder.draggedId = item.id
                            reorder.offsetX = 0f
                            reorder.offsetY = 0f
                            var axis = 0 // 0 undecided, 1 vertical, 2 sideways
                            var pendingX = 0f
                            var pendingY = 0f
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) break
                                val delta = change.positionChange()
                                change.consume()
                                if (axis == 0) {
                                    pendingX += delta.x
                                    pendingY += delta.y
                                    if (abs(pendingX) + abs(pendingY) < viewConfiguration.touchSlop) continue
                                    axis = if (abs(pendingX) > abs(pendingY)) 2 else 1
                                    if (axis == 2) reorder.offsetX = pendingX else reorder.offsetY = pendingY
                                    continue
                                }
                                if (axis == 2) {
                                    reorder.offsetX += delta.x
                                    continue
                                }
                                reorder.offsetY += delta.y
                                // Swap with the row under the dragged row's centre.
                                val items = state.layoutInfo.visibleItemsInfo
                                val me = items.firstOrNull { it.key == item.id } ?: continue
                                val centre = me.offset + reorder.offsetY + me.size / 2f
                                val target = items.firstOrNull { it.key != item.id && centre >= it.offset && centre <= it.offset + it.size }
                                if (target != null) {
                                    val from = visual.indexOfFirst { it.id == item.id }
                                    val to = visual.indexOfFirst { it.id == target.key }
                                    if (from >= 0 && to >= 0) {
                                        visual = visual.toMutableList().apply { add(to, removeAt(from)) }
                                        reorder.offsetY += (me.offset - target.offset).toFloat()
                                    }
                                }
                                // Near the edges the list scrolls along.
                                val viewport = state.layoutInfo.viewportEndOffset
                                val edge = me.size * 0.8f
                                when {
                                    centre < edge -> scope.launch { state.scrollBy(-24f) }
                                    centre > viewport - edge -> scope.launch { state.scrollBy(24f) }
                                }
                            }
                            when {
                                axis == 2 && reorder.offsetX <= -removeDistance -> onRemove(item.id)
                                axis == 1 -> {
                                    val newIndex = visual.indexOfFirst { it.id == item.id }
                                    if (newIndex >= 0 && newIndex != upcoming.indexOfFirst { it.id == item.id }) {
                                        onMove(item.id, activeIndex + 1 + newIndex)
                                    }
                                }
                            }
                            if (axis != 1) visual = upcoming
                            reorder.offsetX = 0f
                            reorder.offsetY = 0f
                            reorder.draggedId = null
                            reorder.active = false
                        }
                    }
                    .clickable(enabled = reorder.draggedId == null) {
                        queue.indexOfFirst { it.id == item.id }.takeIf { it >= 0 }?.let(onSelect)
                    },
            ) {
                QueueSongRow(item, Modifier.padding(start = 2.dp)) {
                    Box(Modifier.width(56.dp).height(48.dp), contentAlignment = Alignment.Center) { ReorderGrip() }
                }
            }
        }
}
