package com.recordlabs.desktop.data

import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit

/**
 * Checks that a resolved stream URL really plays, and hands URLs to the
 * player. On desktop the player reads through a loopback bridge
 * ([AudioProxyServer], in the desktop app) that forwards byte ranges to
 * YouTube's CDN; on Android the player streams the URL itself.
 */
object WindowsAudioProxy {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    private const val CDN_CHUNK_SIZE = 512L * 1024L


    // Dedicated client for URL validation probes: shorter timeouts so a slow
    // CDN edge doesn't hold up playback, and a separate connection pool so
    // probes never compete with the active audio stream for connections.
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    // Thread pool for firing URL validation probes in parallel.
    private val probeExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "audio-probe").apply { isDaemon = true }
    }

    fun canStream(remoteUrl: String, userAgent: String = USER_AGENT): Boolean = runCatching {
        val parsedUrl = remoteUrl.toHttpUrlOrNull()
        val contentLength = parsedUrl?.queryParameter("clen")?.toLongOrNull()
        // Media Foundation reads the MP4 index near the end before playback. Some mobile URLs
        // serve the opening burst but return 403 for later ranges, so probe the whole timeline.
        val probePoints = if (contentLength != null && contentLength > 4L) {
            listOf(0L, contentLength / 3L, contentLength * 2L / 3L, contentLength - 2L)
        } else {
            listOf(0L, CDN_CHUNK_SIZE * 2L, CDN_CHUNK_SIZE * 4L)
        }.distinct()
        probePoints.all { probeRange(remoteUrl, userAgent, it) }
    }.getOrDefault(false)

    /**
     * Fast startup validation for a freshly resolved InnerTube URL. Media
     * Foundation needs the opening bytes and the MP4 index at the tail before
     * it can start; probing those two points is sufficient for the first play
     * while the full cache download continues in parallel.
     *
     * Both probe points are fired in parallel so total validation time is
     * max(probe_start, probe_end) instead of probe_start + probe_end,
     * saving ~200-400 ms per track load on a typical connection.
     */
    fun canStartStream(remoteUrl: String, userAgent: String = USER_AGENT): Boolean = runCatching {
        val contentLength = remoteUrl.toHttpUrlOrNull()?.queryParameter("clen")?.toLongOrNull()
        val probePoints = if (contentLength != null && contentLength > 4L) {
            listOf(0L, contentLength - 2L)
        } else {
            listOf(0L)
        }
        if (probePoints.size == 1) {
            return@runCatching probeRange(remoteUrl, userAgent, probePoints[0])
        }
        // Fire all probes in parallel — total time = max(probe1, probe2) not probe1+probe2.
        val futures: List<Future<Boolean>> = probePoints.map { start ->
            probeExecutor.submit<Boolean> { probeRange(remoteUrl, userAgent, start) }
        }
        futures.all { it.get() }
    }.getOrDefault(false)

    private fun probeRange(remoteUrl: String, userAgent: String, start: Long): Boolean {
        val request = Request.Builder()
            .url(remoteUrl)
            .header("User-Agent", userAgent)
            .header("Accept-Encoding", "identity")
            .header("Range", "bytes=$start-$start")
            .build()
        return probeClient.newCall(request).execute().use { response ->
            val playable = (response.code == 200 || response.code == 206) &&
                (response.body?.source()?.readByteArray(1)?.isNotEmpty() == true)
            playable
        }
    }

    /**
     * The loopback server the desktop player reads from (null on Android, where
     * the player streams the URL itself).
     */
    interface AudioProxyServer {
        fun register(remoteUrl: String, userAgent: String): String
        fun getRemoteUrl(localUrl: String): String
        fun stop()
    }

    private val server: AudioProxyServer? by lazy {
        runCatching {
            Class.forName("com.recordlabs.desktop.data.DesktopAudioProxyServer").getField("INSTANCE").get(null) as AudioProxyServer
        }.getOrNull()
    }

    /** A URL the player can open: through the desktop loopback server, or the CDN URL itself. */
    fun register(remoteUrl: String, userAgent: String = USER_AGENT): String {
        server?.let { return it.register(remoteUrl, userAgent) }
        // Without the loopback server (Android) the player opens the CDN URL
        // itself and must send the same User-Agent the URL was resolved with.
        synchronized(directUserAgents) { directUserAgents[remoteUrl] = userAgent }
        return remoteUrl
    }

    private val directUserAgents = object : LinkedHashMap<String, String>(32, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?) = size > 32
    }

    /** The User-Agent to stream [url] with (Android, where URLs are opened directly). */
    fun userAgentFor(url: String): String = synchronized(directUserAgents) { directUserAgents[url] } ?: USER_AGENT

    fun getRemoteUrl(localUrl: String): String = server?.getRemoteUrl(localUrl) ?: localUrl

    fun stop() {
        server?.stop()
    }
}
