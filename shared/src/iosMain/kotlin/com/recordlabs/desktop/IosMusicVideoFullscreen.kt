package com.recordlabs.desktop

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.MusicVideoCaptionCue
import com.recordlabs.desktop.data.MusicVideoCaptionTrack
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsMusicRepository
import com.recordlabs.desktop.data.hasMusicVideo
import com.recordlabs.desktop.data.textAt
import com.recordlabs.desktop.platform.currentTimeMillis
import kotlinx.coroutines.delay

private object IosFullscreenSource {
    val repository by lazy { WindowsMusicRepository() }
}

/**
 * iPhone: the music video over the whole screen (the video's full-screen button), as on
 * Android. A wide video is turned on its side, like YouTube: hold the phone sideways
 * (its top to the left). A tap shows the seek bar, play controls and subtitles (when the
 * uploader made captions); they hide again after a moment. The exit button, or the
 * edge swipe back, returns to the player.
 */
@Composable
internal fun IosMusicVideoFullscreen() {
    val requested = MusicVideoFullscreen.trackId
    val currentTrack = FloatingLyricsState.currentTrack
    // Previous / Next while full screen: carry on with the next video, or close.
    LaunchedEffect(currentTrack?.id, requested) {
        if (requested != null && currentTrack?.id != requested) {
            if (currentTrack != null && currentTrack.hasMusicVideo) MusicVideoFullscreen.follow(currentTrack.id)
            else MusicVideoFullscreen.close()
        }
    }
    // No "shrink back" picture on the iPhone: nothing to finish.
    if (MusicVideoFullscreen.exitFrame != null) MusicVideoFullscreen.exitDone()

    var shownId by remember { mutableStateOf<String?>(null) }
    if (requested != null && shownId != requested) shownId = requested
    val appear = remember { Animatable(0f) }
    LaunchedEffect(requested != null) {
        if (requested != null) {
            appear.animateTo(1f, tween(340, easing = FastOutSlowInEasing))
        } else if (shownId != null) {
            appear.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            shownId = null
        }
    }
    val trackId = shownId ?: return
    val track = currentTrack?.takeIf { it.id == trackId } ?: Track(trackId, "", "")

    // Uploader captions for subtitles.
    var captionTracks by remember(trackId) { mutableStateOf<List<MusicVideoCaptionTrack>>(emptyList()) }
    LaunchedEffect(trackId) {
        captionTracks = runCatching { IosFullscreenSource.repository.musicVideoOptions(trackId).captions }
            .getOrDefault(emptyList())
    }
    val captionTrack = captionTracks.forCaptionLanguage(MusicVideoFullscreen.captionLanguage, MusicVideoFullscreen.captionName)
    var captionCues by remember(trackId) { mutableStateOf<List<MusicVideoCaptionCue>>(emptyList()) }
    LaunchedEffect(trackId, captionTrack) {
        captionCues = emptyList()
        if (captionTrack != null) {
            captionCues = runCatching { IosFullscreenSource.repository.musicVideoCaptions(captionTrack) }.getOrDefault(emptyList())
        }
    }

    // A tall (portrait) video stays upright; a wide one is turned on its side.
    var aspect by remember(trackId) { mutableStateOf<Float?>(null) }
    val sideways = (aspect ?: 16f / 9f) > 1.05f

    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableStateOf(currentTimeMillis()) }
    var controlsBusy by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouch, controlsBusy, controlsVisible) {
        if (!controlsVisible || controlsBusy) return@LaunchedEffect
        delay(3_000L)
        controlsVisible = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer {
                val p = appear.value
                alpha = p
                val scale = 0.94f + 0.06f * p
                scaleX = scale
                scaleY = scale
            }
            .background(Color.Black)
            // Touches stay here (nothing reaches the page under it).
            .clickable(enabled = requested != null, interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controlsVisible = !controlsVisible
                lastTouch = currentTimeMillis()
            },
    ) {
        Box(
            Modifier.fillMaxSize().then(if (sideways) Modifier.sideways() else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            IosMusicVideo(
                track = track,
                coverUrl = null,
                modifier = Modifier.fillMaxSize(),
                shape = RoundedCornerShape(0.dp),
                scale = ContentScale.Fit,
                onAspectRatio = { if (it != null) aspect = it },
                fullscreen = true,
            )
            if (captionTrack != null && captionCues.isNotEmpty()) {
                FullscreenCaption(captionCues, liftedAboveControls = controlsVisible, sidePadding = if (sideways) 44.dp else 0.dp)
            }
            AnimatedVisibility(
                visible = controlsVisible,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(300)),
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                MaterialTheme(
                    colors = darkColors(
                        primary = MusicVideoFullscreen.accent,
                        secondary = MusicVideoFullscreen.accent,
                        surface = Color(0xFF1A1C20),
                        onPrimary = Color.Black,
                    ),
                ) {
                    MusicVideoFullscreenControls(
                        onExit = { MusicVideoFullscreen.close() },
                        onBusyChange = {
                            controlsBusy = it
                            lastTouch = currentTimeMillis()
                        },
                        captionTracks = captionTracks,
                        selectedCaption = captionTrack,
                        onSelectCaption = {
                            MusicVideoFullscreen.captionLanguage = it?.languageCode
                            MusicVideoFullscreen.captionName = it?.name
                        },
                        showQuality = false,
                        // On its side the screen's rounded corners (and the island) are at the ends.
                        sidePadding = if (sideways) 30.dp else 0.dp,
                        // Upright: above the home indicator.
                        modifier = if (sideways) Modifier else Modifier.padding(bottom = 24.dp),
                    )
                }
            }
        }
    }
}

/**
 * Lays its content out on its side: as wide as the screen is tall, turned a quarter
 * clockwise (read with the phone's top to the left). Touches follow the turn.
 */
private fun Modifier.sideways(): Modifier = this.layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val placeable = measurable.measure(Constraints.fixed(height, width))
    layout(width, height) {
        placeable.placeWithLayer((width - placeable.width) / 2, (height - placeable.height) / 2) {
            rotationZ = 90f
        }
    }
}

/** The subtitle line at the bottom centre, moved up while the controls show (as on YouTube). */
@Composable
private fun BoxScope.FullscreenCaption(
    cues: List<MusicVideoCaptionCue>,
    liftedAboveControls: Boolean,
    sidePadding: androidx.compose.ui.unit.Dp,
) {
    val text = cues.textAt(FloatingLyricsState.positionMs)
    val bottom by animateDpAsState(if (liftedAboveControls) 130.dp else 36.dp, tween(250), label = "caption-lift")
    BoxWithConstraints(
        Modifier.align(Alignment.BottomCenter).fillMaxSize().padding(start = 40.dp + sidePadding, end = 40.dp + sidePadding, bottom = bottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (text.isNullOrBlank()) return@BoxWithConstraints
        val fontSize = (maxHeight.value * 0.05f).coerceIn(15f, 30f)
        Text(
            text,
            color = Color.White,
            fontSize = fontSize.sp,
            lineHeight = (fontSize * 1.25f).sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 5.dp),
        )
    }
}
