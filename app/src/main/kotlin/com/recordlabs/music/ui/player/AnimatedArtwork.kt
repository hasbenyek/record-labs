/**
 * Record Labs Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.recordlabs.music.ui.player

import android.app.ActivityManager
import android.media.MediaCodecList
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.PowerManager
import android.view.TextureView
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
import java.text.Normalizer
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

private const val ANIMATED_ARTWORK_ENDPOINT = "https://artwork.boidu.dev/"
private const val APPLE_MUSIC_ALBUM_ENDPOINT = "https://music.apple.com/us/album/"
private const val ALLOWED_ANIMATED_ART_HOST = "mvod.itunes.apple.com"
// Keep transient API retries short so the first animated frame is not delayed by several
// seconds when the service returns an incomplete response once.
private const val ANIMATED_ARTWORK_LOOKUP_ATTEMPTS = 3
private const val ANIMATED_ARTWORK_RETRY_DELAY_MS = 350L
private const val ANIMATED_ARTWORK_CACHE_SCHEMA = "v5"
private const val ANIMATED_ARTWORK_HD_SIZE = 768
private const val ANIMATED_ARTWORK_FHD_SIZE = 1080
private const val ANIMATED_ARTWORK_HD_AVC_MAX_EDGE = 1080
private const val ANIMATED_ARTWORK_FHD_AVC_MAX_EDGE = 1440
private const val ANIMATED_ARTWORK_FHD_MIN_BANDWIDTH_KBPS = 15_000
private const val ANIMATED_ARTWORK_FHD_METERED_MIN_BANDWIDTH_KBPS = 25_000

internal data class OfficialAppleArtwork(
    val album: String,
    val artist: String,
    val animatedUrls: List<String>,
)

private val appleAnimatedUrlRegex = Regex(
    """<amp-ambient-video\b[^>]*\bsrc="([^"]+)"""",
    RegexOption.IGNORE_CASE,
)
private val appleAlbumRegex = Regex(
    """data-testid="non-editable-product-title"[^>]*>.*?<span[^>]*>(.*?)</span>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val appleArtistRegex = Regex(
    """data-testid="product-subtitles"[^>]*>.*?<a[^>]*>(.*?)</a>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)

private fun decodeAppleHtmlText(value: String): String = value
    .replace(Regex("<[^>]+>"), " ")
    .replace("&amp;", "&")
    .replace("&#39;", "'")
    .replace("&quot;", "\"")
    .replace("&nbsp;", " ")
    .replace(Regex("\\s+"), " ")
    .trim()

internal fun parseOfficialAppleArtworkPage(body: String): OfficialAppleArtwork? {
    val album = appleAlbumRegex.find(body)?.groupValues?.getOrNull(1)?.let(::decodeAppleHtmlText)
        ?: return null
    val artist = appleArtistRegex.find(body)?.groupValues?.getOrNull(1)?.let(::decodeAppleHtmlText)
        ?: return null
    val animatedUrls = appleAnimatedUrlRegex.findAll(body)
        .mapNotNull { it.groupValues.getOrNull(1) }
        .map { it.replace("&amp;", "&") }
        .distinct()
        .toList()
    if (animatedUrls.isEmpty()) return null
    return OfficialAppleArtwork(album, artist, animatedUrls)
}

private val appleHlsResolutionRegex = Regex("""\bRESOLUTION=(\d+)x(\d+)""", RegexOption.IGNORE_CASE)

/** Picks a broadly supported AVC rendition while avoiding Apple's HEVC-only high resolution assets. */
internal fun selectCompatibleAppleAvcPlaylist(masterPlaylist: String): String? {
    val lines = masterPlaylist.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    return lines.mapIndexedNotNull { index, line ->
        if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) ||
            !line.contains("CODECS=\"avc1", ignoreCase = true)
        ) {
            return@mapIndexedNotNull null
        }
        val resolution = appleHlsResolutionRegex.find(line) ?: return@mapIndexedNotNull null
        val width = resolution.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
        val height = resolution.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
        val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") }
            ?: return@mapIndexedNotNull null
        Triple(width * height, maxOf(width, height), uri)
    }
        // 1080x1440 is sharp enough for the player while remaining reliable on Android/WSA.
        .filter { (_, longestEdge, _) -> longestEdge <= 1440 }
        .maxByOrNull { (pixelCount, _, _) -> pixelCount }
        ?.third
}

/** Selects the sharpest square HEVC playlist within the requested quality ceiling. */
internal fun selectCompatibleAppleHevcPlaylist(
    masterPlaylist: String,
    maximumSize: Int = ANIMATED_ARTWORK_HD_SIZE,
): String? {
    val lines = masterPlaylist.lineSequence().map(String::trim).filter(String::isNotEmpty).toList()
    return lines.mapIndexedNotNull { index, line ->
        if (!line.startsWith("#EXT-X-STREAM-INF:", ignoreCase = true) ||
            !line.contains("CODECS=\"hvc1", ignoreCase = true)
        ) {
            return@mapIndexedNotNull null
        }
        val resolution = appleHlsResolutionRegex.find(line) ?: return@mapIndexedNotNull null
        val width = resolution.groupValues[1].toIntOrNull() ?: return@mapIndexedNotNull null
        val height = resolution.groupValues[2].toIntOrNull() ?: return@mapIndexedNotNull null
        if (width != height || width > maximumSize) return@mapIndexedNotNull null
        val uri = lines.drop(index + 1).firstOrNull { !it.startsWith("#") }
            ?: return@mapIndexedNotNull null
        Triple(width * height, width, uri)
    }
        .maxByOrNull { (pixelCount, _, _) -> pixelCount }
        ?.third
}

internal fun firstAppleMp4Path(mediaPlaylist: String): String? = mediaPlaylist
    .lineSequence()
    .map(String::trim)
    .firstOrNull { it.isNotEmpty() && !it.startsWith("#") && it.substringBefore('?').endsWith(".mp4") }

private fun appleVideoAssetId(url: String): String? =
    Regex("P\\d+", RegexOption.IGNORE_CASE).find(url)?.value?.uppercase(Locale.ROOT)

private fun normalizedMetadataTokens(value: String): List<String> =
    Normalizer.normalize(value, Normalizer.Form.NFKD)
        .lowercase(Locale.ROOT)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
        .split(Regex("\\s+"))
        .filter { it.isNotBlank() }

private fun metadataFieldMatches(expected: String, actual: String): Boolean {
    val expectedTokens = normalizedMetadataTokens(expected)
    val actualTokens = normalizedMetadataTokens(actual)
    if (expectedTokens.isEmpty() || actualTokens.isEmpty()) return false
    if (expectedTokens == actualTokens) return true

    val expectedCompact = expectedTokens.joinToString("")
    val actualCompact = actualTokens.joinToString("")
    if (expectedCompact == actualCompact) return true

    // Accept harmless qualifiers such as "Remastered" or an additional featured artist, while
    // still rejecting an unrelated title/artist returned by a fuzzy upstream search.
    val shorterTokens = if (expectedTokens.size <= actualTokens.size) expectedTokens else actualTokens
    val longerTokens = if (expectedTokens.size <= actualTokens.size) actualTokens else expectedTokens
    if (shorterTokens.all { it in longerTokens }) return true

    val shorter = if (expectedCompact.length <= actualCompact.length) expectedCompact else actualCompact
    val longer = if (expectedCompact.length <= actualCompact.length) actualCompact else expectedCompact
    return shorter.length >= 4 &&
        longer.contains(shorter) &&
        shorter.length.toDouble() / longer.length >= 0.6
}

internal fun animatedArtworkMetadataMatches(
    expectedSong: String,
    expectedArtist: String,
    actualSong: String,
    actualArtist: String,
): Boolean = metadataFieldMatches(expectedSong, actualSong) &&
    metadataFieldMatches(expectedArtist, actualArtist)

private fun animatedArtworkLookupKey(
    song: String,
    artist: String,
    durationSeconds: Int,
    album: String,
) = "$ANIMATED_ARTWORK_CACHE_SCHEMA|$artist|$album|$song|$durationSeconds"

private fun animatedArtworkMediaIdKey(mediaId: String) =
    "$ANIMATED_ARTWORK_CACHE_SCHEMA|id:$mediaId"

internal fun animatedArtworkLookupUrl(
    song: String,
    artist: String,
    durationSeconds: Int,
    album: String,
) = ANIMATED_ARTWORK_ENDPOINT.toHttpUrl().newBuilder()
    .addQueryParameter("s", song)
    .addQueryParameter("a", artist)
    .apply {
        if (album.isNotBlank()) addQueryParameter("albumName", album)
        if (durationSeconds > 0) addQueryParameter("duration", durationSeconds.toString())
    }
    .build()

private data class AnimatedArtworkAssets(
    val videoUrl: String?,
    val staticUrl: String?,
    val savedAt: Long,
    val downloadUrl: String? = null,
    val fallbackVideoUrl: String? = null,
    val fhdVideoUrl: String? = null,
    val preferredVideoMimeType: String = MimeTypes.VIDEO_H264,
    val offlineMasterUrl: String? = null,
)

private data class AnimatedArtworkQualityProfile(
    val hevcMaximumSize: Int,
    val useFhd: Boolean,
    val avcMaximumSize: Int,
)

private fun hevcSquareDecoderMaximumSize(): Int = runCatching {
    MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
        .asSequence()
        .filter { info ->
            !info.isEncoder && info.supportedTypes.any { it.equals(MimeTypes.VIDEO_H265, true) }
        }
        .mapNotNull { info ->
            runCatching {
                val capabilities = info.getCapabilitiesForType(MimeTypes.VIDEO_H265).videoCapabilities
                    ?: return@runCatching null
                when {
                    capabilities.isSizeSupported(ANIMATED_ARTWORK_FHD_SIZE, ANIMATED_ARTWORK_FHD_SIZE) ->
                        ANIMATED_ARTWORK_FHD_SIZE
                    capabilities.isSizeSupported(ANIMATED_ARTWORK_HD_SIZE, ANIMATED_ARTWORK_HD_SIZE) ->
                        ANIMATED_ARTWORK_HD_SIZE
                    else -> null
                }
            }.getOrNull()
        }
        .maxOrNull() ?: 0
}.getOrDefault(0)

internal fun shouldUseFhdAnimatedArtwork(
    hevcMaximumSize: Int,
    memoryClassMb: Int,
    displayLongestEdge: Int,
    downstreamBandwidthKbps: Int,
    networkValidated: Boolean,
    networkMetered: Boolean,
    lowRamDevice: Boolean,
    powerSaveMode: Boolean,
): Boolean {
    val networkIsFastEnough = downstreamBandwidthKbps >= if (networkMetered) {
        ANIMATED_ARTWORK_FHD_METERED_MIN_BANDWIDTH_KBPS
    } else {
        ANIMATED_ARTWORK_FHD_MIN_BANDWIDTH_KBPS
    }
    return hevcMaximumSize >= ANIMATED_ARTWORK_FHD_SIZE &&
        memoryClassMb >= 384 &&
        displayLongestEdge >= ANIMATED_ARTWORK_FHD_SIZE &&
        networkValidated &&
        networkIsFastEnough &&
        !lowRamDevice &&
        !powerSaveMode
}

private fun animatedArtworkQualityProfile(context: android.content.Context): AnimatedArtworkQualityProfile {
    val activityManager = context.getSystemService(ActivityManager::class.java)
    val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    val networkCapabilities = connectivityManager
        ?.getNetworkCapabilities(connectivityManager.activeNetwork)
    val powerManager = context.getSystemService(PowerManager::class.java)
    val hevcMaximumSize = hevcSquareDecoderMaximumSize()
    val display = context.resources.displayMetrics
    val useFhd = shouldUseFhdAnimatedArtwork(
        hevcMaximumSize = hevcMaximumSize,
        memoryClassMb = activityManager?.memoryClass ?: 0,
        displayLongestEdge = maxOf(display.widthPixels, display.heightPixels),
        downstreamBandwidthKbps = networkCapabilities?.linkDownstreamBandwidthKbps ?: 0,
        networkValidated = networkCapabilities
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
        networkMetered = networkCapabilities
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true,
        lowRamDevice = activityManager?.isLowRamDevice ?: true,
        powerSaveMode = powerManager?.isPowerSaveMode ?: false,
    )
    return AnimatedArtworkQualityProfile(
        hevcMaximumSize = hevcMaximumSize,
        useFhd = useFhd,
        avcMaximumSize = if (useFhd) {
            ANIMATED_ARTWORK_FHD_AVC_MAX_EDGE
        } else {
            ANIMATED_ARTWORK_HD_AVC_MAX_EDGE
        },
    )
}

private object AnimatedArtworkRepository {
    private val client = OkHttpClient()
    private val cache = ConcurrentHashMap<String, AnimatedArtworkAssets>()
    private val officialArtworkCache = ConcurrentHashMap<String, OfficialAppleArtwork>()
    private val downloadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val videoDownloadJobs = ConcurrentHashMap<String, Deferred<Uri?>>()
    private const val PREFS_NAME = "recordlabs_animated_artwork"
    private const val STATIC_ARTWORK_PREFIX = "static:"

    private fun digest(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { byte -> "%02x".format(byte) }

    private fun validAnimatedUrl(candidate: String): String? = candidate.takeIf {
        runCatching {
            val parsed = it.toHttpUrl()
            parsed.scheme == "https" && parsed.host == ALLOWED_ANIMATED_ART_HOST
        }.getOrDefault(false)
    }

    private fun findOfficialAppleArtwork(
        albumId: String,
        expectedAlbum: String,
        expectedArtist: String,
    ): OfficialAppleArtwork? {
        if (albumId.isBlank() || !albumId.all { it.isDigit() }) return null
        officialArtworkCache[albumId]?.let { cached ->
            val albumMatches = expectedAlbum.isBlank() || metadataFieldMatches(expectedAlbum, cached.album)
            if (albumMatches && metadataFieldMatches(expectedArtist, cached.artist)) return cached
        }
        return runCatching {
            val request = okhttp3.Request.Builder()
                .url("$APPLE_MUSIC_ALBUM_ENDPOINT$albumId")
                .header("User-Agent", "Mozilla/5.0 RecordLabs/1.9")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val page = parseOfficialAppleArtworkPage(response.body?.string().orEmpty())
                    ?: return@use null
                if (expectedAlbum.isNotBlank() && !metadataFieldMatches(expectedAlbum, page.album)) {
                    Timber.w(
                        "Rejected Apple artwork album mismatch: expected=%s actual=%s albumId=%s",
                        expectedAlbum,
                        page.album,
                        albumId,
                    )
                    return@use null
                }
                if (!metadataFieldMatches(expectedArtist, page.artist)) {
                    Timber.w(
                        "Rejected Apple artwork artist mismatch: expected=%s actual=%s albumId=%s",
                        expectedArtist,
                        page.artist,
                        albumId,
                    )
                    return@use null
                }
                val verifiedUrls = page.animatedUrls.mapNotNull(::validAnimatedUrl)
                if (verifiedUrls.isEmpty()) return@use null
                page.copy(animatedUrls = verifiedUrls).also {
                    officialArtworkCache[albumId] = it
                }
            }
        }.onFailure {
            Timber.w(it, "Official Apple artwork verification failed for albumId=%s", albumId)
        }.getOrNull()
    }

    private fun resolveCompatibleAppleAvcMp4(masterUrl: String): String? = runCatching {
        val masterRequest = okhttp3.Request.Builder().url(masterUrl).build()
        val mediaPlaylistPath = client.newCall(masterRequest).execute().use { response ->
            if (!response.isSuccessful) return@use null
            selectCompatibleAppleAvcPlaylist(response.body?.string().orEmpty())
        } ?: return@runCatching null
        val mediaPlaylistUrl = masterUrl.toHttpUrl().resolve(mediaPlaylistPath)
            ?: return@runCatching null
        val mp4Path = client.newCall(okhttp3.Request.Builder().url(mediaPlaylistUrl).build())
            .execute().use { response ->
                if (!response.isSuccessful) return@use null
                firstAppleMp4Path(response.body?.string().orEmpty())
            } ?: return@runCatching null
        val mp4Url = mediaPlaylistUrl.resolve(mp4Path)?.toString() ?: return@runCatching null
        validAnimatedUrl(mp4Url)?.takeIf {
            appleVideoAssetId(it) == appleVideoAssetId(masterUrl)
        }
    }.onFailure {
        Timber.w(it, "Could not resolve compatible Apple AVC artwork: %s", masterUrl)
    }.getOrNull()

    private data class AppleHevcPlaylists(
        val hd: String?,
        val fhd: String?,
    )

    private fun resolveCompatibleAppleHevcPlaylists(masterUrl: String): AppleHevcPlaylists? = runCatching {
        val request = okhttp3.Request.Builder().url(masterUrl).build()
        val masterPlaylist = client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@use null
            response.body?.string()
        } ?: return@runCatching null

        fun resolve(maximumSize: Int): String? {
            val playlistPath = selectCompatibleAppleHevcPlaylist(masterPlaylist, maximumSize)
                ?: return null
            val playlistUrl = masterUrl.toHttpUrl().resolve(playlistPath)?.toString()
                ?: return null
            return validAnimatedUrl(playlistUrl)?.takeIf {
                appleVideoAssetId(it) == appleVideoAssetId(masterUrl)
            }
        }

        val hd = resolve(ANIMATED_ARTWORK_HD_SIZE)
        val fhd = resolve(ANIMATED_ARTWORK_FHD_SIZE)?.takeIf { it != hd }
        AppleHevcPlaylists(hd = hd, fhd = fhd).takeIf { it.hd != null }
    }.onFailure {
        Timber.w(it, "Could not resolve adaptive square Apple HEVC artwork: %s", masterUrl)
    }.getOrNull()

    suspend fun findAssets(
        song: String,
        artist: String,
        durationSeconds: Int,
        album: String,
    ): AnimatedArtworkAssets? = withContext(Dispatchers.IO) {
        if (song.isBlank() || artist.isBlank()) return@withContext null

        val key = animatedArtworkLookupKey(song, artist, durationSeconds, album)
        val cached = cache[key]
        // Never keep a transient empty/error response as a long-lived negative cache. The
        // artwork service can briefly fail or return an incomplete response even when Apple
        // Music has an animated asset, so an empty result must remain retryable.
        if (cached?.videoUrl != null) {
            return@withContext cached
        }

        val url = animatedArtworkLookupUrl(song, artist, durationSeconds, album)

        val assets = runCatching {
            client.newCall(okhttp3.Request.Builder().url(url).build()).execute().use { response ->
                if (!response.isSuccessful) return@use null
                val json = JSONObject(response.body?.string().orEmpty())
                val matchedSong = json.optString("name", "")
                val matchedArtist = json.optString("artist", "")
                if (!animatedArtworkMetadataMatches(song, artist, matchedSong, matchedArtist)) {
                    Timber.w(
                        "Rejected mismatched animated artwork: expected=%s/%s actual=%s/%s",
                        song,
                        artist,
                        matchedSong,
                        matchedArtist,
                    )
                    return@use null
                }
                val albumId = json.optString("albumId", "")
                val officialArtwork = findOfficialAppleArtwork(albumId, album, artist)
                if (officialArtwork == null) {
                    Timber.w(
                        "Animated artwork could not be verified with Apple Music: title=%s artist=%s albumId=%s",
                        song,
                        artist,
                        albumId,
                    )
                    return@use null
                }
                // The public album page does not always expose the vertical asset even though
                // Apple returns it for the same verified album through the artwork lookup. At
                // this point title, artist, album ID, album name, and artist have all matched;
                // only accept Apple's media host, then prefer that independently packaged asset.
                val squareHls = validAnimatedUrl(json.optString("animated", ""))
                val verticalHls = validAnimatedUrl(json.optString("animatedVertical", ""))
                // Apple's square AVC renditions can be stale even while its square HEVC and
                // vertical AVC assets are correct. Keep HD as the square default and expose FHD
                // separately so the UI can opt into it only on a capable device and fast network.
                val squareHevcPlaylists = squareHls?.let(::resolveCompatibleAppleHevcPlaylists)
                val squareHevcHd = squareHevcPlaylists?.hd
                val useSquareHevc = squareHevcHd != null
                val selectedHls = squareHevcHd ?: verticalHls ?: squareHls
                    ?: officialArtwork.animatedUrls.firstOrNull()
                    ?: return@use null
                val fallbackHls = verticalHls?.takeIf { useSquareHevc && it != selectedHls }
                val selectedAssetId = appleVideoAssetId(selectedHls)
                val staticCandidate = json.optString("static", "")
                val staticUrl = staticCandidate.takeIf {
                    runCatching { it.toHttpUrl().scheme == "https" }.getOrDefault(false)
                }
                Timber.d(
                    "Verified animated artwork: title=%s artist=%s album=%s albumId=%s asset=%s",
                    song,
                    artist,
                    officialArtwork.album,
                    albumId,
                    selectedAssetId,
                )
                AnimatedArtworkAssets(
                    videoUrl = selectedHls,
                    staticUrl = staticUrl,
                    savedAt = System.currentTimeMillis(),
                    fallbackVideoUrl = fallbackHls,
                    fhdVideoUrl = squareHevcPlaylists?.fhd?.takeIf { useSquareHevc },
                    preferredVideoMimeType = if (useSquareHevc) MimeTypes.VIDEO_H265 else MimeTypes.VIDEO_H264,
                    offlineMasterUrl = verticalHls ?: selectedHls,
                )
            }
        }.getOrNull()

        val result = assets ?: AnimatedArtworkAssets(null, null, System.currentTimeMillis())
            cache[key] = result
        Timber.d("Animated artwork lookup: title=%s artist=%s duration=%s found=%s", song, artist, durationSeconds, result.videoUrl != null)
        result
    }

    suspend fun compatibleDownloadUrl(masterUrl: String): String? = withContext(Dispatchers.IO) {
        resolveCompatibleAppleAvcMp4(masterUrl)
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
            mediaId?.takeIf { it.isNotBlank() }?.let { add(animatedArtworkMediaIdKey(it)) }
            add(animatedArtworkLookupKey(song, artist, durationSeconds, album))
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
    fun acquire(
        context: android.content.Context,
        mediaUri: Uri,
        preferredVideoMimeType: String,
        maximumVideoSize: Int,
    ): ExoPlayer {
        val key = "$preferredVideoMimeType|$maximumVideoSize|$mediaUri"
        if (currentKey == key && currentPlayer != null) return currentPlayer!!

        currentPlayer?.release()
        val trackSelector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    // Keep the selected codec inside the HD/FHD ceiling chosen for this device.
                    // Do not impose a minimum size on adaptive AVC fallback streams, allowing
                    // Media3 to start quickly and move upward without blocking the artwork UI.
                    .setMaxVideoSize(maximumVideoSize, maximumVideoSize)
                    .setPreferredVideoMimeType(preferredVideoMimeType)
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
    // Only a verified direct MP4 can be persisted. Saving an HLS manifest with an .mp4 suffix
    // would create an invalid offline file, so fall back to static artwork when no MP4 matches.
    val downloadUrl = assets?.downloadUrl ?: assets?.offlineMasterUrl?.let {
        AnimatedArtworkRepository.compatibleDownloadUrl(it)
    }
    val cachedUri = downloadUrl?.let {
        AnimatedArtworkRepository.cacheVideo(
            context,
            it,
            buildList {
                mediaId?.takeIf { id -> id.isNotBlank() }
                    ?.let { id -> add(animatedArtworkMediaIdKey(id)) }
                add(animatedArtworkLookupKey(song, artist, durationSeconds, album))
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
    val qualityProfile = remember(mediaId, song, artist) { animatedArtworkQualityProfile(context) }
    val durableStaticArtworkUri = remember(artworkUri, mediaId) {
        AnimatedArtworkRepository.cachedStaticArtwork(context, mediaId)
    }
    var artworkAssets by remember(mediaId, artworkUri, song, artist, durationSeconds, album, enabled) {
        mutableStateOf<AnimatedArtworkAssets?>(null)
    }

    LaunchedEffect(mediaId, artworkUri, song, artist, durationSeconds, album, enabled) {
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

    var useFallbackVideo by remember(mediaId, artworkAssets?.videoUrl) { mutableStateOf(false) }
    val requiresCompatibilityFallback = artworkAssets?.preferredVideoMimeType == MimeTypes.VIDEO_H265 &&
        qualityProfile.hevcMaximumSize < ANIMATED_ARTWORK_HD_SIZE
    val useEffectiveFallback = useFallbackVideo || requiresCompatibilityFallback
    val animatedUrl = if (useEffectiveFallback) {
        artworkAssets?.fallbackVideoUrl
    } else if (qualityProfile.useFhd) {
        artworkAssets?.fhdVideoUrl ?: artworkAssets?.videoUrl
    } else {
        artworkAssets?.videoUrl
    }
    val preferredVideoMimeType = if (useEffectiveFallback) {
        MimeTypes.VIDEO_H264
    } else {
        artworkAssets?.preferredVideoMimeType ?: MimeTypes.VIDEO_H264
    }
    val maximumVideoSize = if (preferredVideoMimeType == MimeTypes.VIDEO_H265) {
        if (qualityProfile.useFhd && artworkAssets?.fhdVideoUrl == animatedUrl) {
            ANIMATED_ARTWORK_FHD_SIZE
        } else {
            ANIMATED_ARTWORK_HD_SIZE
        }
    } else {
        qualityProfile.avcMaximumSize
    }
    val staticArtworkUri = durableStaticArtworkUri ?: artworkAssets?.staticUrl ?: artworkUri
    var localAnimatedUri by remember(mediaId, animatedUrl, song, artist, durationSeconds, album) {
        mutableStateOf<Uri?>(null)
    }

    LaunchedEffect(mediaId, animatedUrl, song, artist, durationSeconds, album, enabled) {
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
            val player = remember(playableAnimatedUri, preferredVideoMimeType, maximumVideoSize) {
                AnimatedArtworkPlayerStore.acquire(
                    context,
                    playableAnimatedUri,
                    preferredVideoMimeType,
                    maximumVideoSize,
                )
            }
            var videoReady by remember(playableAnimatedUri) {
                mutableStateOf(player.playbackState == Player.STATE_READY)
            }
            val textureViewHolder = remember(playableAnimatedUri) { arrayOfNulls<TextureView>(1) }

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
                        if (!useFallbackVideo && artworkAssets?.fallbackVideoUrl != null) {
                            useFallbackVideo = true
                        } else {
                            // Keep static artwork visible if neither verified rendition decodes.
                            videoReady = false
                        }
                    }
                }
                player.addListener(listener)
                onDispose {
                    player.removeListener(listener)
                    textureViewHolder[0]?.let { textureView ->
                        runCatching { player.clearVideoTextureView(textureView) }
                    }
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
                        textureViewHolder[0] = this
                        player.setVideoTextureView(this)
                    }
                },
                // PlayerView may expose a black surface before its first decoded frame.
                // Keep it transparent until then so the static artwork remains the fallback.
                modifier = Modifier
                    .fillMaxSize()
                    .alpha(if (videoReady) 1f else 0f)
                    .graphicsLayer {
                        // Correct TextureView's square stretching. Respect the same crop setting
                        // as static artwork: default Fit keeps a portrait animation fully visible,
                        // while the optional Crop setting deliberately fills the whole square.
                        if (cropArtwork) {
                            if (videoAspectRatio > 1f) {
                                scaleX = videoAspectRatio
                            } else if (videoAspectRatio in 0.01f..1f) {
                                scaleY = 1f / videoAspectRatio
                            }
                        } else {
                            if (videoAspectRatio > 1f) {
                                scaleY = 1f / videoAspectRatio
                            } else if (videoAspectRatio in 0.01f..1f) {
                                scaleX = videoAspectRatio
                            }
                        }
                    },
            )
        }
    }
}
