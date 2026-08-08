/**
 * Record Labs Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.recordlabs.music.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import android.view.TextureView
import java.util.concurrent.ConcurrentHashMap

private const val ANIMATED_ARTWORK_ENDPOINT = "https://artwork.boidu.dev/"
private const val ALLOWED_ANIMATED_ART_HOST = "mvod.itunes.apple.com"
private const val NOT_FOUND_CACHE_MS = 7L * 24L * 60L * 60L * 1000L

private data class AnimatedArtworkAssets(
    val videoUrl: String?,
    val staticUrl: String?,
    val savedAt: Long,
)

private object AnimatedArtworkRepository {
    private val client = OkHttpClient()
    private val cache = ConcurrentHashMap<String, AnimatedArtworkAssets>()

    suspend fun findAssets(
        song: String,
        artist: String,
        durationSeconds: Int,
        album: String,
    ): AnimatedArtworkAssets? = withContext(Dispatchers.IO) {
        if (song.isBlank() || artist.isBlank() || durationSeconds <= 0) return@withContext null

        val key = "$artist|$album|$song|$durationSeconds"
        val cached = cache[key]
        if (cached != null && (cached.videoUrl != null || cached.staticUrl != null || System.currentTimeMillis() - cached.savedAt < NOT_FOUND_CACHE_MS)) {
            return@withContext cached
        }

        val url = ANIMATED_ARTWORK_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("s", song)
            .addQueryParameter("a", artist)
            .addQueryParameter("d", durationSeconds.toString())
            .addQueryParameter("al", album)
            .build()

        val assets = runCatching {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val videoCandidate = json.optString("videoUrl", "")
                val videoUrl = videoCandidate.takeIf {
                    runCatching {
                        val parsed = it.toHttpUrl()
                        parsed.scheme == "https" && parsed.host == ALLOWED_ANIMATED_ART_HOST
                    }.getOrDefault(false)
                }
                val staticCandidate = json.optString("static", "")
                val staticUrl = staticCandidate.takeIf {
                    runCatching { it.toHttpUrl().scheme == "https" }.getOrDefault(false)
                }
                AnimatedArtworkAssets(videoUrl, staticUrl, System.currentTimeMillis())
            }
        }.getOrNull()

        val result = assets ?: AnimatedArtworkAssets(null, null, System.currentTimeMillis())
        cache[key] = result
        result
    }
}

@Composable
fun AnimatedArtwork(
    artworkUri: String?,
    song: String,
    artist: String,
    durationSeconds: Int,
    album: String,
    cropArtwork: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onAspectRatioChanged: (Float) -> Unit = {},
) {
    val context = LocalContext.current
    var artworkAssets by remember(artworkUri, song, artist, durationSeconds, album, enabled) {
        mutableStateOf<AnimatedArtworkAssets?>(null)
    }

    LaunchedEffect(artworkUri, song, artist, durationSeconds, album, enabled) {
        artworkAssets = if (enabled) {
            AnimatedArtworkRepository.findAssets(song, artist, durationSeconds, album)
        } else {
            null
        }
    }

    val animatedUrl = artworkAssets?.videoUrl
    val staticArtworkUri = artworkAssets?.staticUrl ?: artworkUri

    Box(modifier = modifier.fillMaxSize()) {
        AsyncImage(
            model = ImageRequest.Builder(context)
                .data(staticArtworkUri)
                .memoryCachePolicy(CachePolicy.ENABLED)
                .diskCachePolicy(CachePolicy.ENABLED)
                .networkCachePolicy(CachePolicy.ENABLED)
                .build(),
            contentDescription = null,
            contentScale = if (cropArtwork) ContentScale.Crop else ContentScale.Fit,
            onState = { state ->
                val size = (state as? coil3.compose.AsyncImagePainter.State.Success)
                    ?.painter
                    ?.intrinsicSize
                if (size != null && size.width > 0f && size.height > 0f) {
                    onAspectRatioChanged(size.width / size.height)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        if (animatedUrl != null) {
            var videoReady by remember(animatedUrl) { mutableStateOf(false) }
            var videoAspectRatio by remember(animatedUrl) { mutableStateOf(1f) }
            val trackSelector = remember(context) {
                DefaultTrackSelector(context).apply {
                    setParameters(
                        buildUponParameters()
                            .setMaxVideoSize(720, 720)
                            .setMaxVideoFrameRate(30)
                            .build(),
                    )
                }
            }
            val player = remember(animatedUrl) {
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector)
                    .build()
                    .apply {
                    // Loop manually before the media item reaches its end. Letting the
                    // player enter ENDED and re-prepare the video is what creates the
                    // noticeable pause between animated-artwork loops.
                    repeatMode = Player.REPEAT_MODE_OFF
                    volume = 0f
                    val artworkMediaItem = MediaItem.fromUri(animatedUrl!!)
                    setMediaItem(artworkMediaItem)
                    prepare()
                    playWhenReady = true
                }
            }

            LaunchedEffect(player) {
                while (isActive) {
                    val duration = player.duration
                    val position = player.currentPosition
                    // Seek before the decoder reaches the end boundary so playback never
                    // enters the end/rebuffer transition. The small lead-in is intentional:
                    // a seamless loop is preferable to displaying a frozen final frame.
                    if (duration > 0L && position >= duration - 320L) {
                        player.seekTo(0L)
                        player.playWhenReady = true
                    }
                    delay(40L)
                }
            }

            DisposableEffect(player) {
                val listener = object : Player.Listener {
                    override fun onRenderedFirstFrame() {
                        videoReady = true
                    }

                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        if (videoSize.height > 0) {
                            videoAspectRatio =
                                (videoSize.width * videoSize.pixelWidthHeightRatio) / videoSize.height.toFloat()
                        }
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        // Keep the static artwork visible if the remote video cannot be decoded.
                        videoReady = false
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    player.release()
                }
            }

            AndroidView(
                factory = {
                    TextureView(it).apply {
                        // SurfaceView can punch an independent black layer over Compose.
                        // TextureView stays in the Compose hierarchy, so the static artwork
                        // remains visible until the first animation frame is ready.
                        isOpaque = false
                        player.setVideoTextureView(this)
                    }
                },
                // PlayerView may expose a black surface before its first decoded frame.
                // Keep it transparent until then so the static artwork remains the fallback.
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (videoReady) 1f else 0f)
                    .graphicsLayer {
                        // The TextureView fills a square by default, which can stretch a
                        // landscape/portrait animated artwork. Correct its aspect ratio and
                        // crop the excess instead of distorting the artwork.
                        if (videoAspectRatio > 1f) {
                            scaleX = videoAspectRatio
                        } else if (videoAspectRatio in 0.01f..1f) {
                            scaleY = 1f / videoAspectRatio
                        }
                    },
            )
        }
    }
}
