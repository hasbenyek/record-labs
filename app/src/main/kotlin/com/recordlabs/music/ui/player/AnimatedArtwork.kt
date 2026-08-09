/**
 * Record Labs Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.recordlabs.music.ui.player

import android.app.ActivityManager
import android.media.MediaCodecList
import android.net.Uri
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
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import coil3.compose.AsyncImage
import coil3.request.CachePolicy
import coil3.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import android.view.TextureView
import java.util.concurrent.ConcurrentHashMap

private const val ANIMATED_ARTWORK_ENDPOINT = "https://artwork.boidu.dev/"
private const val ALLOWED_ANIMATED_ART_HOST = "mvod.itunes.apple.com"
// Keep transient API retries short so the first animated frame is not delayed by several
// seconds when the service returns an incomplete response once.
private const val ANIMATED_ARTWORK_LOOKUP_ATTEMPTS = 3
private const val ANIMATED_ARTWORK_RETRY_DELAY_MS = 350L

private data class AnimatedArtworkAssets(
    val videoUrl: String?,
    val staticUrl: String?,
    val savedAt: Long,
    val downloadUrl: String? = null,
)

private fun adaptiveAnimatedArtworkMaxSize(context: android.content.Context): Int {
    val avcDecoderLimit = runCatching {
        MediaCodecList(MediaCodecList.ALL_CODECS).codecInfos
            .asSequence()
            .filter { !it.isEncoder && it.supportedTypes.any { type -> type.equals(MimeTypes.VIDEO_H264, true) } }
            .mapNotNull { info ->
                runCatching {
                    info.getCapabilitiesForType(MimeTypes.VIDEO_H264)
                        .videoCapabilities
                        ?.supportedWidths
                        ?.upper
                }.getOrNull()
            }
            .maxOrNull()
    }.getOrNull() ?: 768

    val memoryClass = (context.getSystemService(ActivityManager::class.java))?.memoryClass ?: 2048
    val memoryLimit = when {
        memoryClass < 2048 -> 768
        memoryClass < 4096 -> 1080
        else -> 2160
    }
    val display = context.resources.displayMetrics
    val displayLimit = maxOf(display.widthPixels, display.heightPixels)
        .coerceAtLeast(768)
    // Never select the softer 360p/408p/486p HLS variants. If the device cannot handle
    // 768p, ExoPlayer will fall back to the static artwork rather than lowering quality.
    return minOf(avcDecoderLimit, memoryLimit, displayLimit).coerceAtLeast(768)
}

private object AnimatedArtworkRepository {
    private val client = OkHttpClient()
    private val cache = ConcurrentHashMap<String, AnimatedArtworkAssets>()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoDownloadJobs = ConcurrentHashMap<String, Deferred<Uri?>>()
    private const val PREFS_NAME = "recordlabs_animated_artwork"
    private const val STATIC_ARTWORK_PREFIX = "static:"

    private fun lookupKey(song: String, artist: String, durationSeconds: Int, album: String) =
        "$artist|$album|$song|$durationSeconds"

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    suspend fun findAssets(
        song: String,
        artist: String,
        durationSeconds: Int,
        album: String,
    ): AnimatedArtworkAssets? = withContext(Dispatchers.IO) {
        if (song.isBlank() || artist.isBlank()) return@withContext null

        val key = lookupKey(song, artist, durationSeconds, album)
        val cached = cache[key]
        // Never keep a transient empty/error response as a long-lived negative cache. The
        // artwork service can briefly fail or return an incomplete response even when Apple
        // Music has an animated asset, so an empty result must remain retryable.
        if (cached?.videoUrl != null) {
            return@withContext cached
        }

        val urlBuilder = ANIMATED_ARTWORK_ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("s", song)
            .addQueryParameter("a", artist)
            .addQueryParameter("al", album)
        if (durationSeconds > 0) {
            urlBuilder.addQueryParameter("d", durationSeconds.toString())
        }
        val url = urlBuilder.build()

        val assets = runCatching {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val videoCandidate = json.optString("videoUrl", "")
                val animatedCandidate = json.optString("animated", "")
                fun validAnimatedUrl(candidate: String): String? = candidate.takeIf {
                    runCatching {
                        val parsed = it.toHttpUrl()
                        parsed.scheme == "https" && parsed.host == ALLOWED_ANIMATED_ART_HOST
                    }.getOrDefault(false)
                }
                // The MP4 returned by videoUrl is HEVC 10-bit at 2160p and fails on WSA's
                // decoder. The animated HLS master contains AVC variants (including 360p),
                // which Media3 can select safely on WSA and most Android phones.
                val hlsUrl = validAnimatedUrl(animatedCandidate)
                val mp4Url = validAnimatedUrl(videoCandidate)
                val videoUrl = hlsUrl ?: mp4Url
                val staticCandidate = json.optString("static", "")
                val staticUrl = staticCandidate.takeIf {
                    runCatching { it.toHttpUrl().scheme == "https" }.getOrDefault(false)
                }
                AnimatedArtworkAssets(videoUrl, staticUrl, System.currentTimeMillis(), mp4Url)
            }
        }.getOrNull()

        val result = assets ?: AnimatedArtworkAssets(null, null, System.currentTimeMillis())
            cache[key] = result
        Timber.d("Animated artwork lookup: title=%s artist=%s duration=%s found=%s", song, artist, durationSeconds, result.videoUrl != null)
        result
    }

    fun cachedVideo(
        context: android.content.Context,
        mediaId: String?,
        song: String,
        artist: String,
        durationSeconds: Int,
        album: String,
    ): Uri? {
        val preferences = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
        val mappingKeys = buildList {
            mediaId?.takeIf { it.isNotBlank() }?.let { add("id:$it") }
            add(lookupKey(song, artist, durationSeconds, album))
        }
        val fileName = mappingKeys.firstNotNullOfOrNull { preferences.getString(digest(it), null) }
            ?: return null
        val file = File(File(context.filesDir, "animated_artwork"), fileName)
        return file.takeIf { it.exists() && it.length() > 0L }?.let(Uri::fromFile)
    }

    fun cachedStaticArtwork(context: android.content.Context, mediaId: String?): Uri? {
        val id = mediaId?.takeIf { it.isNotBlank() } ?: return null
        val fileName = context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
            .getString(digest("$STATIC_ARTWORK_PREFIX$id"), null)
            ?: return null
        val file = File(File(context.filesDir, "artwork"), fileName)
        return file.takeIf { it.exists() && it.length() > 0L }?.let(Uri::fromFile)
    }

    suspend fun cacheStaticArtwork(
        context: android.content.Context,
        mediaId: String,
        artworkUrl: String,
    ): Uri? = withContext(Dispatchers.IO) {
        if (mediaId.isBlank() || artworkUrl.isBlank()) return@withContext null
        val directory = File(context.filesDir, "artwork").apply { mkdirs() }
        val file = File(directory, "recordlabs_static_${digest(mediaId)}.image")
        if (file.exists() && file.length() > 0L) return@withContext Uri.fromFile(file)

        val temporaryFile = File(directory, "${digest(mediaId)}.download")
        runCatching {
            client.newCall(okhttp3.Request.Builder().url(artworkUrl).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val body = response.body ?: return@use null
                temporaryFile.outputStream().use { output -> body.byteStream().use { input -> input.copyTo(output) } }
                if (temporaryFile.length() > 0L) temporaryFile.renameTo(file)
            }
        }.getOrNull()

        if (file.exists() && file.length() > 0L) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE)
                .edit()
                .putString(digest("$STATIC_ARTWORK_PREFIX$mediaId"), file.name)
                .apply()
            Uri.fromFile(file)
        } else {
            null
        }
    }

    suspend fun cacheVideo(
        context: android.content.Context,
        videoUrl: String,
        lookupKeys: List<String> = emptyList(),
    ): Uri? {
        val job = videoDownloadJobs.computeIfAbsent(videoUrl) {
            downloadScope.async {
                downloadVideo(context, videoUrl)
            }
        }
        val result = job.await()
        if (result != null) {
            context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().apply {
                lookupKeys.forEach { putString(digest(it), File(result.path!!).name) }
            }.apply()
        }
        if (job.isCompleted) videoDownloadJobs.remove(videoUrl, job)
        return result
    }

    private suspend fun downloadVideo(
        context: android.content.Context,
        videoUrl: String,
    ): Uri? =
        withContext(Dispatchers.IO) {
            val videoDigest = digest(videoUrl)
            // Keep downloaded animated artwork in durable app-private storage. cacheDir can be
            // evicted by Android, which would make artwork disappear after an offline download.
            val artworkDirectory = File(context.filesDir, "animated_artwork").apply { mkdirs() }
            val cachedFile = File(artworkDirectory, "recordlabs_animated_$videoDigest.mp4")
            if (cachedFile.exists() && cachedFile.length() > 0L) {
                return@withContext Uri.fromFile(cachedFile)
            }

            val temporaryFile = File(artworkDirectory, "$videoDigest.download")
            runCatching {
                client.newCall(okhttp3.Request.Builder().url(videoUrl).build()).execute().use { response ->
                    if (!response.isSuccessful) return@use null
                    val body = response.body ?: return@use null
                    temporaryFile.outputStream().use { output ->
                        body.byteStream().use { input -> input.copyTo(output) }
                    }
                    if (temporaryFile.length() > 0L) {
                        temporaryFile.renameTo(cachedFile)
                    }
                }
            }.getOrNull()

            cachedFile.takeIf { it.exists() && it.length() > 0L }?.let(Uri::fromFile)
        }
}

/** Keeps the prepared artwork player alive while the player/lyrics page is recreated. */
private object AnimatedArtworkPlayerStore {
    private var currentKey: String? = null
    private var currentPlayer: ExoPlayer? = null

    @Synchronized
    fun acquire(context: android.content.Context, mediaUri: Uri): ExoPlayer {
        val key = mediaUri.toString()
        if (currentKey == key && currentPlayer != null) return currentPlayer!!

        currentPlayer?.release()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Select the sharpest AVC HLS variant the current decoder, memory class,
                    // and display can reasonably handle. This avoids the incompatible HEVC
                    // 2160p fallback while allowing capable devices to use higher quality.
                    .setMaxVideoSize(adaptiveAnimatedArtworkMaxSize(context), adaptiveAnimatedArtworkMaxSize(context))
                    .setMinVideoSize(768, 768)
                    .setPreferredVideoMimeType(MimeTypes.VIDEO_H264)
                    .setMaxVideoFrameRate(24)
                    .build(),
            )
        }
        val player = ExoPlayer.Builder(
            context,
            DefaultRenderersFactory(context).setEnableDecoderFallback(true),
        ).setTrackSelector(trackSelector).build().apply {
            volume = 0f
            val artworkMediaItem = MediaItem.fromUri(mediaUri)
            // Use the direct media-item path for artwork endpoints. Some of them do not expose
            // their first video frame correctly when wrapped in a repeated playlist.
            repeatMode = Player.REPEAT_MODE_ONE
            setMediaItem(artworkMediaItem)
            prepare()
            playWhenReady = true
        }
        currentKey = key
        currentPlayer = player
        return player
    }
}

/** Resolve and persist animated artwork before a song is downloaded for offline playback. */
suspend fun prefetchAnimatedArtwork(
    context: android.content.Context,
    mediaId: String? = null,
    song: String,
    artist: String,
    durationSeconds: Int,
    album: String,
): Uri? {
    var assets: AnimatedArtworkAssets? = null
    for (attempt in 0 until ANIMATED_ARTWORK_LOOKUP_ATTEMPTS) {
        assets = AnimatedArtworkRepository.findAssets(song, artist, durationSeconds, album)
        if (assets?.videoUrl != null) break
        if (attempt < ANIMATED_ARTWORK_LOOKUP_ATTEMPTS - 1) {
            delay(ANIMATED_ARTWORK_RETRY_DELAY_MS)
        }
    }
    val cachedUri = (assets?.downloadUrl ?: assets?.videoUrl)?.let {
        AnimatedArtworkRepository.cacheVideo(
            context,
            it,
            buildList {
                mediaId?.takeIf { id -> id.isNotBlank() }?.let { id -> add("id:$id") }
                add("$artist|$album|$song|$durationSeconds")
            },
        )
    }
    Timber.d("Animated artwork prefetch: title=%s mediaId=%s saved=%s", song, mediaId, cachedUri != null)
    return cachedUri
}

/** Persist the static artwork as a durable offline fallback alongside the song. */
suspend fun prefetchStaticArtwork(
    context: android.content.Context,
    mediaId: String,
    artworkUrl: String?,
): Uri? = artworkUrl?.let { AnimatedArtworkRepository.cacheStaticArtwork(context, mediaId, it) }

/** Returns the durable static artwork file for offline backgrounds and thumbnails. */
fun offlineArtworkUri(context: android.content.Context, mediaId: String?): Uri? =
    AnimatedArtworkRepository.cachedStaticArtwork(context, mediaId)

@Composable
fun AnimatedArtwork(
    artworkUri: String?,
    mediaId: String? = null,
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
    val durableStaticArtworkUri = remember(artworkUri, mediaId) {
        AnimatedArtworkRepository.cachedStaticArtwork(context, mediaId)
    }
    var artworkAssets by remember(artworkUri, song, artist, durationSeconds, album, enabled) {
        mutableStateOf<AnimatedArtworkAssets?>(null)
    }

    LaunchedEffect(artworkUri, song, artist, durationSeconds, album, enabled) {
        artworkAssets = null
        if (enabled) {
            var resolvedAssets: AnimatedArtworkAssets? = null
            for (attempt in 0 until ANIMATED_ARTWORK_LOOKUP_ATTEMPTS) {
                resolvedAssets = AnimatedArtworkRepository.findAssets(
                    song = song,
                    artist = artist,
                    durationSeconds = durationSeconds,
                    album = album,
                )

                // Stop immediately once the animated URL is available. If the service has
                // definitively returned only a static asset, keep retrying until the final
                // attempt because the animated response may have failed transiently.
                if (resolvedAssets?.videoUrl != null) break
                if (attempt < ANIMATED_ARTWORK_LOOKUP_ATTEMPTS - 1) {
                    delay(ANIMATED_ARTWORK_RETRY_DELAY_MS)
                }
            }
            artworkAssets = resolvedAssets
        }
    }

    val animatedUrl = artworkAssets?.videoUrl
    val staticArtworkUri = durableStaticArtworkUri ?: artworkAssets?.staticUrl ?: artworkUri
    var localAnimatedUri by remember(animatedUrl) { mutableStateOf<Uri?>(null) }

    LaunchedEffect(animatedUrl, song, artist, durationSeconds, album, enabled) {
        if (!enabled) {
            localAnimatedUri = null
            return@LaunchedEffect
        }
        localAnimatedUri = AnimatedArtworkRepository.cachedVideo(
            context,
            mediaId,
            song,
            artist,
            durationSeconds,
            album,
        )
        // Permanent artwork downloading is handled explicitly by the Download action. Avoid
        // starting a second network transfer while the online animated stream is playing.
    }

    // Stream online for fast startup; use the durable file when no remote URL is available.
    val playableAnimatedUri = animatedUrl?.let(Uri::parse) ?: localAnimatedUri

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

        if (playableAnimatedUri != null) {
            var videoAspectRatio by remember(playableAnimatedUri) { mutableStateOf(1f) }
            val player = remember(playableAnimatedUri) {
                AnimatedArtworkPlayerStore.acquire(context, playableAnimatedUri)
            }
            var videoReady by remember(playableAnimatedUri) {
                mutableStateOf(player.playbackState == Player.STATE_READY)
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
                    // Keep the prepared player alive so returning from Home can reuse the
                    // already buffered artwork instead of starting another network load.
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
