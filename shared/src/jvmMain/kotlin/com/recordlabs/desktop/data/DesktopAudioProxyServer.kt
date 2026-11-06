package com.recordlabs.desktop.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
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
 * Loopback bridge between Media Foundation / VLC and YouTube's CDN (desktop).
 * It forwards byte ranges immediately instead of downloading a second full copy first.
 * [WindowsAudioProxy] (shared code) finds this object by name and hands it the work.
 */
object DesktopAudioProxyServer : WindowsAudioProxy.AudioProxyServer {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    private const val CDN_CHUNK_SIZE = 512L * 1024L

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()



    private data class AudioSource(
        val url: String,
        val userAgent: String,
        val contentLength: Long?,
        val contentType: String,
        val extension: String,
    )

    private val sources = object : LinkedHashMap<String, AudioSource>(64, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AudioSource>?) = size > 64
    }
    private val executorService = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "record-labs-audio-proxy").apply { isDaemon = true }
    }

    @Volatile
    private var server: HttpServer? = null

    @Synchronized
    private fun ensureServer(): HttpServer {
        var s = server
        if (s == null) {
            s = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0).apply {
                createContext("/audio") { exchange -> serve(exchange) }
                executor = executorService
                start()
            }
            server = s
        }
        return s
    }

    override fun register(remoteUrl: String, userAgent: String): String {
        val s = ensureServer()
        val token = UUID.randomUUID().toString()
        val parsedUrl = remoteUrl.toHttpUrlOrNull()
        val contentLength = parsedUrl?.queryParameter("clen")?.toLongOrNull()
        val contentType = parsedUrl?.queryParameter("mime")?.takeIf { it.startsWith("audio/") } ?: "audio/mp4"
        val extension = if (contentType.contains("webm", ignoreCase = true)) "webm" else "m4a"
        synchronized(sources) {
            sources[token] = AudioSource(remoteUrl, userAgent, contentLength, contentType, extension)
        }
        return "http://127.0.0.1:${s.address.port}/audio/$token.$extension"
    }

    override fun getRemoteUrl(localUrl: String): String {
        if (!localUrl.contains("/audio/")) return localUrl
        val token = localUrl.substringAfterLast('/').substringBefore('.')
        return synchronized(sources) { sources[token]?.url } ?: localUrl
    }

    @Synchronized
    override fun stop() {
        runCatching { server?.stop(0) }
        server = null
    }

    private fun serve(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                return
            }

            val token = exchange.requestURI.path.substringAfterLast('/').substringBefore('.')
            val source = synchronized(sources) { sources[token] }
            if (source == null) {
                exchange.sendResponseHeaders(404, -1)
                return
            }

            val requestedRange = exchange.requestHeaders.getFirst("Range")
            // YouTube's CDN can reject a plain full-file GET while accepting byte ranges for the
            // same signed URL. Always speak ranges upstream, even when Media Foundation probes
            // with HEAD or an un-ranged GET.
            val requestedStart = requestedRange.rangeStart() ?: 0L
            val requestedEnd = requestedRange.rangeEnd()
            val firstChunkEnd = when {
                exchange.requestMethod == "HEAD" -> requestedStart + 1L
                requestedEnd != null -> requestedEnd
                source.contentLength != null -> source.contentLength - 1L
                else -> requestedStart + CDN_CHUNK_SIZE - 1L
            }
            val upstreamRange = "bytes=$requestedStart-$firstChunkEnd"
            val upstream = openUpstream(source, upstreamRange)
            upstream.use { response ->
                if (!response.isSuccessful) {
                    println("Audio proxy first range $upstreamRange failed with HTTP ${response.code}")
                    exchange.sendResponseHeaders(response.code, -1)
                    return
                }

                val contentRange = response.header("Content-Range")
                val totalLength = contentRange?.substringAfter('/')?.toLongOrNull()
                val responseStart = contentRange?.substringAfter("bytes ")?.substringBefore('-')?.toLongOrNull()
                    ?: requestedStart
                val responseLength = response.body?.contentLength()?.takeIf { it >= 0 }
                    ?: response.header("Content-Length")?.toLongOrNull()
                val downstreamEnd = requestedEnd?.let { end -> totalLength?.let { minOf(end, it - 1L) } ?: end }
                    ?: totalLength?.minus(1L)
                val expectedLength = downstreamEnd?.let { it - requestedStart + 1L }
                    ?: responseLength
                val downstreamStatus = if (requestedRange == null) 200 else response.code

                response.header("Content-Type")?.let { exchange.responseHeaders.set("Content-Type", it) }
                    ?: exchange.responseHeaders.set("Content-Type", source.contentType)
                exchange.responseHeaders.set("Accept-Ranges", "bytes")
                exchange.responseHeaders.set("Cache-Control", "private, max-age=300")
                if (requestedRange != null && downstreamEnd != null && totalLength != null) {
                    exchange.responseHeaders.set("Content-Range", "bytes $requestedStart-$downstreamEnd/$totalLength")
                }
                response.header("ETag")?.let { exchange.responseHeaders.set("ETag", it) }

                if (exchange.requestMethod == "HEAD") {
                    expectedLength?.let { exchange.responseHeaders.set("Content-Length", it.toString()) }
                    exchange.sendResponseHeaders(downstreamStatus, -1)
                    return
                }

                exchange.sendResponseHeaders(downstreamStatus, expectedLength ?: 0L)
                exchange.responseBody.use { output ->
                    copyResponseBody(response, output, expectedLength ?: Long.MAX_VALUE)
                    output.flush()
                }
            }
        } catch (_: IOException) {
            // Usually Media Foundation cancelled an obsolete range after a seek or track change.
        } catch (error: Exception) {
            runCatching { exchange.sendResponseHeaders(502, -1) }
            println("Audio proxy error: ${error.message}")
        } finally {
            exchange.close()
        }
    }

    private fun openUpstream(source: AudioSource, range: String?): Response {
        val request = Request.Builder()
            .url(source.url)
            .header("User-Agent", source.userAgent)
            .header("Accept", "*/*")
            .header("Accept-Encoding", "identity")
            .apply {
                if (!range.isNullOrBlank()) header("Range", range)
            }
            .build()
        return client.newCall(request).execute()
    }

    private fun copyResponseBody(response: Response, output: java.io.OutputStream, limit: Long = Long.MAX_VALUE): Long {
        val input = response.body?.byteStream() ?: return 0L
        val buffer = ByteArray(64 * 1024)
        var copied = 0L
        while (copied < limit) {
            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - copied).toInt())
            if (read < 0) break
            output.write(buffer, 0, read)
            copied += read
        }
        return copied
    }

    private fun String?.rangeStart(): Long? = this
        ?.takeIf { it.startsWith("bytes=") }
        ?.removePrefix("bytes=")
        ?.substringBefore('-')
        ?.toLongOrNull()

    private fun String?.rangeEnd(): Long? = this
        ?.takeIf { it.startsWith("bytes=") }
        ?.removePrefix("bytes=")
        ?.substringAfter('-', missingDelimiterValue = "")
        ?.toLongOrNull()
}
