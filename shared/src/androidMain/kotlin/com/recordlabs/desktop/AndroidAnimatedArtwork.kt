package com.recordlabs.desktop

import android.view.TextureView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
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
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import coil3.compose.AsyncImage
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsAnimatedArtworkRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/** Copies of the clip queued back to back for a seamless loop. */
private const val ARTWORK_LOOP_COPIES = 64

/**
 * Apple Music animated artwork, found exactly like on desktop (the same
 * lookup and checks: title, artist, then the square H.264 1080 x 1080 clip),
 * so every platform shows the same, right artwork. The clip loops muted under
 * the cover, which blurs and fades away once the first picture is shown; the
 * cover stays when the song has no animated artwork.
 */
@Composable
internal fun AndroidAnimatedArtwork(
    track: Track,
    coverUrl: String?,
    modifier: Modifier,
    scale: ContentScale,
    onAspectRatio: (Float?) -> Unit,
) {
    val context = LocalContext.current
    var streamUrl by remember(track.id) { mutableStateOf<String?>(null) }
    var attempt by remember(track.id) { mutableStateOf(0) }
    LaunchedEffect(track.id, track.title, track.artist, attempt) {
        if (attempt > 0) {
            WindowsAnimatedArtworkRepository.invalidate(track)
            delay(1_500L * attempt)
        }
        var waitMs = 2_500L
        while (isActive && streamUrl == null) {
            val found = runCatching { WindowsAnimatedArtworkRepository.find(track) }.getOrNull()
            if (found != null) {
                // No decoder for Apple's 10-bit HEVC here: the H.264 clip at once.
                val alternate = WindowsAnimatedArtworkRepository.alternateStreamUrl(found.streamUrl)
                val chosen = if (alternate != null && !AnimatedArtworkStream.canDecodeHevcMain10) alternate else found.streamUrl
                android.util.Log.i("RecordLabsVideo", "animated artwork ${track.title} / ${track.artist}: $chosen")
                streamUrl = chosen
                break
            }
            if (WindowsAnimatedArtworkRepository.isDefinitiveMiss(track) || waitMs > 12_000L) break
            delay(waitMs)
            waitMs = waitMs * 3 / 2
        }
    }
    var videoAspect by remember(track.id) { mutableStateOf<Float?>(null) }
    var pictureShown by remember(track.id) { mutableStateOf(false) }
    // A new player for the same clip (after a hardware decoder failed: software this time).
    var playerGeneration by remember(track.id) { mutableStateOf(0) }
    val latestAspect by rememberUpdatedState(onAspectRatio)
    // Artwork stays square: the (usually vertical) clip is centre-cropped into the
    // artwork's box instead of changing its shape.
    LaunchedEffect(Unit) { latestAspect(null) }
    DisposableEffect(track.id) { onDispose { latestAspect(null) } }
    val reveal = remember(track.id) { Animatable(0f) }
    LaunchedEffect(pictureShown) {
        reveal.animateTo(if (pictureShown) 1f else 0f, tween(1_000, easing = LinearEasing))
    }
    var boxAspect by remember { mutableStateOf(1f) }
    val windowShown = MainWindowActivity.isShown

    Box(modifier.onSizeChanged { if (it.height > 0) boxAspect = it.width.toFloat() / it.height }) {
        streamUrl?.let { url ->
            // Every stream (the H.264 fallback, a retry) gets its own player AND its own
            // TextureView. AndroidView runs its factory only once, so without this key a
            // new player never got a surface: no first frame, and the cover stayed on top
            // for good.
            key(url, playerGeneration) {
                val player = remember(url, playerGeneration) {
                    val selector = DefaultTrackSelector(context).apply {
                        setParameters(
                            buildUponParameters()
                                // Picture only (some clips carry a silent audio track).
                                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                                .build(),
                        )
                    }
                    val renderers = VideoDecoders.renderersFactory(context)
                    ExoPlayer.Builder(context, renderers).setTrackSelector(selector).build().apply {
                        volume = 0f
                        // Seamless loop: the clip queued many times over (and that list
                        // repeated). The player prepares the next copy while the current one
                        // plays and goes straight on, like a playlist without gaps; repeating
                        // a single item instead restarts it, with a visible pause every loop.
                        // The first copy plays while it downloads; the others read the cache.
                        val sources = ProgressiveMediaSource.Factory(AnimatedArtworkStream.dataSourceFactory(context))
                        val item = MediaItem.fromUri(url)
                        repeatMode = Player.REPEAT_MODE_ALL
                        setMediaSources(List(ARTWORK_LOOP_COPIES) { sources.createMediaSource(item) })
                        prepare()
                        playWhenReady = true
                    }
                }
                DisposableEffect(player) {
                    val listener = object : Player.Listener {
                        override fun onRenderedFirstFrame() {
                            pictureShown = true
                        }

                        override fun onVideoSizeChanged(videoSize: VideoSize) {
                            if (videoSize.height > 0) {
                                videoAspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                            }
                        }

                        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                            android.util.Log.w("RecordLabsVideo", "animated artwork error ${track.title}: ${error.errorCodeName}", error)
                            pictureShown = false
                            if (VideoDecoders.switchToSoftwareAfter(error)) {
                                playerGeneration++
                                return
                            }
                            val alternate = WindowsAnimatedArtworkRepository.alternateStreamUrl(url)
                            if (alternate != null && alternate != url) {
                                // The HEVC square clip does not decode here: the H.264 one.
                                android.util.Log.i("RecordLabsVideo", "animated artwork ${track.title}: falling back to $alternate")
                                streamUrl = alternate
                            } else {
                                streamUrl = null
                                if (attempt < 2) attempt++
                            }
                        }
                    }
                    player.addListener(listener)
                    onDispose {
                        player.removeListener(listener)
                        player.release()
                    }
                }
                // The HEVC try that shows nothing within a few seconds (no decoder that
                // can do it, or far too slow) gives way to the H.264 clip.
                LaunchedEffect(player) {
                    val alternate = WindowsAnimatedArtworkRepository.alternateStreamUrl(url) ?: return@LaunchedEffect
                    delay(8_000L)
                    if (!pictureShown && alternate != url) {
                        android.util.Log.i("RecordLabsVideo", "animated artwork ${track.title}: no picture from HEVC, using $alternate")
                        streamUrl = alternate
                    }
                }
                // Paused while the app is in the background.
                LaunchedEffect(player, windowShown) { player.playWhenReady = windowShown }
                AndroidView(
                    factory = { viewContext -> VideoDecoders.createVideoView(viewContext, player) },
                    // Always cropped: the square box is filled like the cover.
                    update = { view -> VideoDecoders.fitVideo(view, videoAspect, crop = true) },
                    modifier = Modifier
                        .fillMaxSize()
                        .clipToBounds()
                        // Never fully transparent: a TextureView gets its surface only when drawn.
                        .alpha(if (pictureShown) 1f else 0.01f),
                )
            }
        }
        // The cover on top until the clip runs (blur + fade, as on desktop).
        AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = if (scale == ContentScale.Crop) ContentScale.Crop else ContentScale.Fit,
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
    }
}
