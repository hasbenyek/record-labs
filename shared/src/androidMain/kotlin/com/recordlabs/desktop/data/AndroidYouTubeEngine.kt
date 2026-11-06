package com.recordlabs.desktop.data

import android.content.Context
import android.net.ConnectivityManager
import com.recordlabs.innertube.YouTube
import com.recordlabs.innertube.models.YouTubeLocale
import com.recordlabs.music.constants.AudioQuality
import com.recordlabs.music.utils.PlaybackRemoteConfig
import com.recordlabs.music.utils.YTPlayerUtils
import com.recordlabs.music.utils.cipher.CipherDeobfuscator
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Stream resolution of the original Record Labs Android app (folder 3): its
 * InnerTube client with the WEB_REMIX main client, the fallback client list,
 * poToken (BotGuard web view), cipher web view and the remote playback policy.
 * The shared app's Google session (the same login as desktop) is handed to it.
 */
object AndroidYouTubeEngine {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var appContext: Context
    @Volatile private var initialized = false

    /** Resolved stream URLs per video id, until they expire (like the original app's songUrlCache). */
    private val urlCache = ConcurrentHashMap<String, Pair<String, Long>>()
    /** The client that produced each video's current URL (to mark it failed on a 403). */
    private val clientOf = ConcurrentHashMap<String, String>()

    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        PlaybackRemoteConfig.initialize(appContext, scope)
        CipherDeobfuscator.initialize(appContext)
        scope.launch {
            val locale = Locale.getDefault()
            YouTube.locale = YouTubeLocale(
                gl = locale.country.takeIf { it.length == 2 } ?: "US",
                hl = locale.language.takeIf { it.isNotBlank() } ?: "en",
            )
            syncSession()
            // Same warm-ups as the original app, off the first-play path.
            launch {
                delay(1_500)
                runCatching { CipherDeobfuscator.prewarm() }
            }
            launch {
                delay(2_500)
                var waited = 0
                while (YouTube.visitorData == null && waited < 12_000) {
                    delay(500)
                    waited += 500
                }
                runCatching { YTPlayerUtils.prewarmPoToken() }
            }
        }
    }

    /** Follows sign-in / sign-out in the shared app. */
    private suspend fun syncSession() {
        val session = runCatching { WindowsPreferences.googleSession() }.getOrNull()
        val cookie = session?.cookie
        if (YouTube.cookie != cookie) runCatching { YouTube.cookie = cookie }
        YouTube.dataSyncId = session?.dataSyncId?.takeIf { it.isNotBlank() }?.let {
            it.takeIf { !it.contains("||") } ?: it.substringBefore("||")
        }
        val visitor = session?.visitorData?.takeIf { it.isNotBlank() && it != "null" }
        if (visitor != null) {
            YouTube.visitorData = visitor
        } else if (YouTube.visitorData == null) {
            YouTube.visitorData = YouTube.visitorData().getOrNull()
        }
    }

    /** The signed-in account, like the original app's account screen (null when signed out or on error). */
    suspend fun accountProfile(): GoogleAccountProfile? {
        syncSession()
        if (YouTube.cookie.isNullOrBlank()) return null
        val info = YouTube.accountInfo().onFailure {
            System.err.println("Record Labs: account info failed: ${it.message}")
        }.getOrNull() ?: return null
        return GoogleAccountProfile(info.name, info.thumbnailUrl, info.email, info.channelHandle)
    }

    /**
     * Whether the saved session still signs in: true, false only when YouTube
     * answered without an account (the original app's accountInfo() then has no
     * account header), null when that could not be checked (offline, timeout).
     */
    suspend fun validateSession(): Boolean? {
        syncSession()
        if (YouTube.cookie.isNullOrBlank()) return false
        val result = YouTube.accountInfo()
        result.getOrNull()?.let { info ->
            WindowsPreferences.saveGoogleAccountProfile(info.name, info.thumbnailUrl, info.email, info.channelHandle)
            return true
        }
        val error = result.exceptionOrNull()
        System.err.println("Record Labs: session check failed: $error")
        return when (error) {
            is NullPointerException -> false
            is io.ktor.client.plugins.ClientRequestException ->
                if (error.response.status.value == 401 || error.response.status.value == 403) false else null
            else -> null
        }
    }

    /** The picture streams (H.264 MP4, video only) of each music video, until they expire. */
    private val videoFormatCache = ConcurrentHashMap<String, Pair<List<Pair<Int, String>>, Long>>()

    /** Height -> URL of a music video's H.264 picture streams, lowest first. */
    private suspend fun musicVideoFormats(videoId: String): List<Pair<Int, String>> {
        videoFormatCache[videoId]?.takeIf { it.second > System.currentTimeMillis() }?.let { return it.first }
        syncSession()
        val response = YouTube.player(
            videoId,
            client = com.recordlabs.innertube.models.YouTubeClient.VISIONOS,
            setLogin = false,
        ).onFailure { System.err.println("Record Labs: video player request failed: ${it.message}") }
            .getOrNull() ?: return emptyList()
        val formats = response.streamingData?.adaptiveFormats.orEmpty()
            .filter { !it.isAudio && it.url != null && it.mimeType.startsWith("video/mp4") && it.mimeType.contains("avc1") }
            .mapNotNull { format -> format.url?.let { (format.height ?: 0) to it } }
            .filter { it.first > 0 }
            .groupBy { it.first }.map { it.value.first() }
            .sortedBy { it.first }
        if (formats.isNotEmpty()) videoFormatCache[videoId] = formats to System.currentTimeMillis() + 60 * 60_000L
        return formats
    }

    /**
     * A music video's picture stream (video only, H.264 MP4, up to [maxHeight]),
     * from the VISIONOS client the original app's playback also falls back to:
     * its URLs need no poToken or cipher and are not throttled.
     */
    suspend fun musicVideoUrl(videoId: String, maxHeight: Int): String? {
        val formats = musicVideoFormats(videoId)
        return (formats.lastOrNull { it.first <= maxHeight } ?: formats.firstOrNull())?.second
    }

    /** Picture heights a music video can be shown at here (highest first), for the quality menu. */
    suspend fun musicVideoHeights(videoId: String): List<Int> =
        musicVideoFormats(videoId).map { it.first }.filter { it >= 144 }.sortedDescending()

    fun cachedUrl(videoId: String): String? =
        urlCache[videoId]?.takeIf { it.second > System.currentTimeMillis() }?.first

    /** A playable stream URL for [videoId], or null. Blocking callers run this on an IO thread. */
    suspend fun resolve(videoId: String): String? {
        cachedUrl(videoId)?.let { return it }
        syncSession()
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val data = YTPlayerUtils.playerResponseForPlayback(
            videoId,
            audioQuality = AudioQuality.AUTO,
            connectivityManager = connectivity,
        ).onFailure { System.err.println("Record Labs: stream for $videoId failed: ${it.message}") }
            .getOrNull() ?: return null
        urlCache[videoId] = data.streamUrl to System.currentTimeMillis() + data.streamExpiresInSeconds * 1000L
        clientOf[videoId] = data.streamClient
        return data.streamUrl
    }

    /** The URL stopped working (403, expired): resolve again, without the client that made it. */
    fun invalidate(videoId: String, rejectClient: Boolean) {
        urlCache.remove(videoId)
        val client = clientOf.remove(videoId)
        if (rejectClient) YTPlayerUtils.markStreamClientFailed(videoId, client)
    }
}
