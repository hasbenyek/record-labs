package com.recordlabs.desktop

import android.net.Uri
import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import coil3.compose.AsyncImage
import com.recordlabs.desktop.data.AndroidPlaybackEngine
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsMusicRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Music video picture streams (the same lookup the desktop app uses). */
internal object AndroidMusicVideoSource {
    val repository by lazy { WindowsMusicRepository() }
}

/** Picture height asked for, like the desktop artwork box (sharp, but light on a phone). */
// 480p: WSA (and slower phones) could not decode 720p in real time, so the picture
// kept falling behind and jumping back (it felt heavy). The full-screen view
// asks for more; the quality menu there picks any height.
internal const val MUSIC_VIDEO_HEIGHT = 480
/** The picture jumps to the sound only when it is further apart than this (else it catches up). */
private const val MUSIC_VIDEO_MAX_DRIFT_MS = 2_500L

/**
 * A music video in the artwork's place, as on desktop: the video's picture,
 * muted and kept in step with the song playing (the sound comes from the
 * playback engine). The cover stays on top until the first picture is shown,
 * then blurs and fades away; it comes back if the video can't be played.
 */
@Composable
internal fun AndroidMusicVideo(
    track: Track,
    coverUrl: String?,
    modifier: Modifier,
    scale: ContentScale,
    onAspectRatio: (Float?) -> Unit,
    /** Picture height asked for (the full-screen quality menu picks a bigger one). */
    maxHeight: Int = MUSIC_VIDEO_HEIGHT,
    /** This is the full-screen view (the page's own video pauses meanwhile). */
    fullscreen: Boolean = false,
    /** Portrait player: a button that opens the video full screen (landscape). */
    showFullscreenButton: Boolean = false,
) {
    val context = LocalContext.current
    var streamUrl by remember(track.id, maxHeight) { mutableStateOf<String?>(null) }
    // A decoder error is tried again (a fresh player) a couple of times.
    var attempt by remember(track.id, maxHeight) { mutableStateOf(0) }
    LaunchedEffect(track.id, maxHeight, attempt) {
        if (attempt > 0) delay(1_000L * attempt)
        var waitMs = 1_500L
        while (isActive && streamUrl == null && waitMs <= 6_000L) {
            // The original Android app's client first (VISIONOS), the desktop lookup as a fallback.
            streamUrl = runCatching { com.recordlabs.desktop.data.AndroidYouTubeEngine.musicVideoUrl(track.id, maxHeight) }.getOrNull()
                ?: runCatching { AndroidMusicVideoSource.repository.musicVideoStreamUrl(track.id, maxHeight) }.getOrNull()
            android.util.Log.i("RecordLabsVideo", "music video stream ${track.id} @$maxHeight: ${streamUrl?.take(80)}")
            if (streamUrl == null) {
                delay(waitMs)
                waitMs *= 2
            }
        }
    }
    var videoAspect by remember(track.id) { mutableStateOf<Float?>(null) }
    var pictureShown by remember(track.id, maxHeight) { mutableStateOf(false) }
    // A new player for the same stream (after a hardware decoder failed: software this time).
    var playerGeneration by remember(track.id, maxHeight) { mutableStateOf(0) }
    val latestAspect by rememberUpdatedState(onAspectRatio)
    LaunchedEffect(videoAspect, pictureShown) { latestAspect(videoAspect?.takeIf { pictureShown }) }
    DisposableEffect(track.id) { onDispose { latestAspect(null) } }

    val reveal = remember(track.id, maxHeight) { Animatable(0f) }
    LaunchedEffect(pictureShown) {
        reveal.animateTo(if (pictureShown) 1f else 0f, tween(1_000, easing = LinearEasing))
    }
    var boxAspect by remember { mutableStateOf(1f) }

    Box(modifier.onSizeChanged { if (it.height > 0) boxAspect = it.width.toFloat() / it.height }.background(Color.Black)) {
        streamUrl?.let { url ->
            // Every stream (a new one after an error, a retry) gets its own player AND its own
            // TextureView. AndroidView runs its factory only once, so without this key a
            // new player never got a surface: no first frame, and the cover stayed on top
            // for good.
            key(url, playerGeneration) {
                val player = remember(url, playerGeneration) {
                    val selector = DefaultTrackSelector(context).apply {
                        setParameters(
                            buildUponParameters()
                                .setMaxVideoSize(1920, 1080)
                                // Picture only: the sound is the song the engine plays.
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .build(),
                        )
                    }
                    val renderers = VideoDecoders.renderersFactory(context)
                    ExoPlayer.Builder(context, renderers).setTrackSelector(selector).build().apply {
                        volume = 0f
                        setMediaSource(
                            ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(okhttp3.OkHttpClient()))
                                .createMediaSource(MediaItem.fromUri(Uri.parse(url))),
                            AndroidPlaybackEngine.instance().positionMs,
                        )
                        prepare()
                    }
                }
                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            android.util.Log.i("RecordLabsVideo", "music video first frame: ${track.id}")
                            pictureShown = true
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.height > 0) {
                                val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                                videoAspect = (ratio * 100f).toInt() / 100f
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.w("RecordLabsVideo", "music video error ${track.id}: ${error.errorCodeName}", error)
                            pictureShown = false
                            if (VideoDecoders.switchToSoftwareAfter(error)) {
                                playerGeneration++
                                return
                            }
                            // No picture after all: the cover stays, like a song without a video.
                            streamUrl = null
                            if (attempt < 2) attempt++
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                        player.release()
                    }
                }
                // Kept in step with the song: play/pause with it, and jump to its position
                // when they drift apart (start, seek, a stall on either side).
                LaunchedEffect(player) {
                    val engine = AndroidPlaybackEngine.instance()
                    while (isActive) {
                        // While the video is full screen, only the full-screen view plays it.
                        val coveredByFullscreen = !fullscreen && MusicVideoFullscreen.trackId == track.id
                        val playing = engine.isPlaying && engine.currentTrack?.id == track.id && !coveredByFullscreen
                        if (player.playWhenReady != playing) player.playWhenReady = playing
                        val audioMs = engine.positionMs
                        val durationMs = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                        // Small drift: the picture runs a little faster or slower until it is
                        // back with the sound (a seek restarts decoding from a key frame and
                        // stutters, which is what made the video feel heavy). Only a big
                        // jump (start, the user seeking) seeks.
                        val drift = player.currentPosition - audioMs
                        var speed = 1f
                        if (playing && audioMs < durationMs) {
                            if (abs(drift) > MUSIC_VIDEO_MAX_DRIFT_MS) {
                                android.util.Log.i("RecordLabsVideo", "music video re-sync ${track.id}: drift ${drift} ms")
                                player.seekTo(audioMs)
                            } else if (abs(drift) > 80L) {
                                speed = (1f - drift / 1000f * 0.5f).coerceIn(0.85f, 1.15f)
                            }
                        }
                        if (abs(player.playbackParameters.speed - speed) > 0.01f) player.setPlaybackSpeed(speed)
                        delay(250)
                    }
                }
                AndroidView(
                    factory = { viewContext -> VideoDecoders.createVideoView(viewContext, player) },
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        // Never fully transparent: a view at alpha 0 is not drawn, and a
                        // TextureView gets its surface only when drawn (no frame ever came).
                        .alpha(if (pictureShown) 1f else 0.01f),
                    // The TextureView stretches the picture to the box: its transform undoes that.
                    update = { view -> VideoDecoders.fitVideo(view, videoAspect, crop = scale == ContentScale.Crop) },
                )
            }
        }
        // The cover on top until the picture runs (blur + fade, as on desktop).
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = if (fullscreen) ContentScale.Fit else ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = reveal.value
                    alpha = 1f - ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                    renderEffect = if (progress > 0f && progress < 1f && android.os.Build.VERSION.SDK_INT >= 31) {
                        val t = (progress / 0.6f).coerceIn(0f, 1f)
                        val radius = 22f * density * ((1.0 - kotlin.math.cos(Math.PI * t)) / 2.0).toFloat()
                        BlurEffect(radius, radius, TileMode.Clamp)
                    } else null
                },
        )
        if (showFullscreenButton && pictureShown && !fullscreen) {
            androidx.compose.foundation.layout.Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(36.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { MusicVideoFullscreen.open(track.id, null) },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material.Icon(
                    androidx.compose.material.icons.Icons.Default.Fullscreen,
                    "Full screen video",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}
