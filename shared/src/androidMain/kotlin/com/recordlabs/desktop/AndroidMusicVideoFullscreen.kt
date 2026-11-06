package com.recordlabs.desktop

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.darkColors
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.recordlabs.desktop.data.AndroidYouTubeEngine
import com.recordlabs.desktop.data.MusicVideoCaptionCue
import com.recordlabs.desktop.data.MusicVideoCaptionTrack
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.hasMusicVideo
import com.recordlabs.desktop.data.textAt
import kotlinx.coroutines.delay

/** Picture height of "Auto" in full screen (720p plays smoothly on WSA / most phones; higher is in the menu). */
private const val FULLSCREEN_AUTO_HEIGHT = 720

private fun Context.findActivity(): Activity? {
    var context: Context? = this
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/**
 * The music video over the whole screen, turned landscape like YouTube: the
 * picture fitted on black, with a tap showing the seek bar, play controls,
 * subtitles (when the uploader made captions) and the picture quality.
 * Back, or the exit button, returns to the portrait player.
 */
@Composable
internal fun AndroidMusicVideoFullscreen() {
    val trackId = MusicVideoFullscreen.trackId
    val currentTrack = FloatingLyricsState.currentTrack
    // Previous / Next while full screen: carry on with the next video, or close.
    LaunchedEffect(currentTrack?.id, trackId) {
        if (trackId != null && currentTrack?.id != trackId) {
            if (currentTrack != null && currentTrack.hasMusicVideo) MusicVideoFullscreen.follow(currentTrack.id)
            else MusicVideoFullscreen.close()
        }
    }
    if (trackId == null) {
        // Android draws no "shrink back" picture: nothing to finish.
        if (MusicVideoFullscreen.exitFrame != null) MusicVideoFullscreen.exitDone()
        return
    }
    val activity = LocalContext.current.findActivity()
    DisposableEffect(activity) {
        val previousOrientation = activity?.requestedOrientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        val controller = activity?.window?.let { WindowCompat.getInsetsController(it, it.decorView) }
        controller?.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            activity?.requestedOrientation = previousOrientation
            controller?.show(WindowInsetsCompat.Type.systemBars())
        }
    }
    BackHandler { MusicVideoFullscreen.close() }

    val track = currentTrack?.takeIf { it.id == trackId } ?: Track(trackId, "", "")
    // What this video offers: heights for the quality menu, uploader captions for subtitles.
    var heights by remember(trackId) { mutableStateOf<List<Int>>(emptyList()) }
    var captionTracks by remember(trackId) { mutableStateOf<List<MusicVideoCaptionTrack>>(emptyList()) }
    LaunchedEffect(trackId) {
        heights = runCatching { AndroidYouTubeEngine.musicVideoHeights(trackId) }.getOrDefault(emptyList())
    }
    LaunchedEffect(trackId) {
        captionTracks = runCatching { AndroidMusicVideoSource.repository.musicVideoOptions(trackId).captions }
            .getOrDefault(emptyList())
    }
    val captionTrack = captionTracks.forCaptionLanguage(MusicVideoFullscreen.captionLanguage, MusicVideoFullscreen.captionName)
    var captionCues by remember(trackId) { mutableStateOf<List<MusicVideoCaptionCue>>(emptyList()) }
    LaunchedEffect(trackId, captionTrack) {
        captionCues = emptyList()
        if (captionTrack != null) {
            captionCues = runCatching { AndroidMusicVideoSource.repository.musicVideoCaptions(captionTrack) }.getOrDefault(emptyList())
        }
    }
    val height = effectiveMusicVideoQuality(MusicVideoFullscreen.quality, heights)
        ?: heights.firstOrNull { it <= FULLSCREEN_AUTO_HEIGHT }
        ?: FULLSCREEN_AUTO_HEIGHT

    // A tap shows the controls; they hide again after a moment (not while seeking
    // or with a menu open).
    var controlsVisible by remember { mutableStateOf(true) }
    var lastTouch by remember { mutableStateOf(System.nanoTime()) }
    var controlsBusy by remember { mutableStateOf(false) }
    LaunchedEffect(lastTouch, controlsBusy, controlsVisible) {
        if (!controlsVisible || controlsBusy) return@LaunchedEffect
        delay(3_000L)
        controlsVisible = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                controlsVisible = !controlsVisible
                lastTouch = System.nanoTime()
            },
        contentAlignment = Alignment.Center,
    ) {
        AndroidMusicVideo(
            track = track,
            coverUrl = track.artworkUrl?.highQualityArtworkUrl(),
            modifier = Modifier.fillMaxSize(),
            scale = ContentScale.Fit,
            onAspectRatio = {},
            maxHeight = height,
            fullscreen = true,
        )
        if (captionTrack != null && captionCues.isNotEmpty()) {
            FullscreenCaption(captionCues, liftedAboveControls = controlsVisible)
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
                        lastTouch = System.nanoTime()
                    },
                    availableHeights = heights,
                    captionTracks = captionTracks,
                    selectedCaption = captionTrack,
                    onSelectCaption = {
                        MusicVideoFullscreen.captionLanguage = it?.languageCode
                        MusicVideoFullscreen.captionName = it?.name
                    },
                )
            }
        }
    }
}

/** The subtitle line at the bottom centre, moved up while the controls show (as on YouTube). */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.FullscreenCaption(
    cues: List<MusicVideoCaptionCue>,
    liftedAboveControls: Boolean,
) {
    val text = cues.textAt(FloatingLyricsState.positionMs)
    val bottom by animateDpAsState(if (liftedAboveControls) 130.dp else 36.dp, tween(250), label = "caption-lift")
    BoxWithConstraints(
        Modifier.align(Alignment.BottomCenter).fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = bottom),
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
