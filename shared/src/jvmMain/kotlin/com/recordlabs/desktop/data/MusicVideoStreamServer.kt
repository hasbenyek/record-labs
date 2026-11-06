package com.recordlabs.desktop.data

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Loopback server for [MusicVideoStream] virtual files (separate from the audio proxy). */
object MusicVideoStreamServer : MusicVideoStream.Host {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "record-labs-music-video-stream").apply { isDaemon = true }
    }
    private val streams = object : LinkedHashMap<String, MusicVideoStream>(16, .75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MusicVideoStream>?) = size > 16
    }
    private val tokens = HashMap<MusicVideoStream, String>()

    @Volatile private var server: HttpServer? = null

    @Synchronized
    private fun ensureServer(): HttpServer = server ?: HttpServer.create(
        InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0,
    ).apply {
        createContext("/mv") { exchange -> serve(exchange) }
        executor = this@MusicVideoStreamServer.executor
        start()
        server = this
    }

    override fun urlFor(stream: MusicVideoStream, index: Int): String {
        val port = ensureServer().address.port
        val token = synchronized(streams) {
            tokens[stream]?.takeIf { streams.containsKey(it) } ?: UUID.randomUUID().toString().also { token ->
                tokens.entries.removeIf { !streams.containsKey(it.value) }
                tokens[stream] = token
                streams[token] = stream
            }
        }
        return "http://127.0.0.1:$port/mv/$token/$index.mp4"
    }

    /** The stream behind a URL returned by [urlFor], or null once it was dropped. */
    fun streamFor(url: String): MusicVideoStream? {
        val token = url.substringAfter("/mv/", "").substringBefore('/')
        return synchronized(streams) { streams[token] }
    }

    private fun serve(exchange: HttpExchange) {
        try {
            if (exchange.requestMethod != "GET" && exchange.requestMethod != "HEAD") {
                exchange.sendResponseHeaders(405, -1)
                return
            }
            val parts = exchange.requestURI.path.trim('/').split('/')
            val stream = parts.getOrNull(1)?.let { token -> synchronized(streams) { streams[token] } }
            val index = parts.getOrNull(2)?.substringBefore('.')?.toIntOrNull()
            if (stream == null || index == null || index !in stream.fragments.indices) {
                exchange.sendResponseHeaders(404, -1)
                return
            }
            val initSize = stream.init.size.toLong()
            val fragmentOffset = stream.fragments[index].offset
            val totalLength = initSize + (stream.contentLength - fragmentOffset)

            val range = exchange.requestHeaders.getFirst("Range")
                ?.takeIf { it.startsWith("bytes=") }?.removePrefix("bytes=")?.substringBefore(',')?.trim()
            // Suffix ranges ("bytes=-N") are not needed by VLC: refuse them.
            val start = if (range == null) 0L else range.substringBefore('-').toLongOrNull() ?: -1L
            val end = (range?.substringAfter('-', "")?.toLongOrNull() ?: (totalLength - 1L)).coerceAtMost(totalLength - 1L)
            if (start < 0L || start > end || start >= totalLength) {
                exchange.responseHeaders.set("Content-Range", "bytes */$totalLength")
                exchange.sendResponseHeaders(416, -1)
                return
            }
            val length = end - start + 1L
            exchange.responseHeaders.set("Content-Type", "video/mp4")
            exchange.responseHeaders.set("Accept-Ranges", "bytes")
            if (range != null) exchange.responseHeaders.set("Content-Range", "bytes $start-$end/$totalLength")
            val status = if (range != null) 206 else 200
            if (exchange.requestMethod == "HEAD") {
                exchange.responseHeaders.set("Content-Length", length.toString())
                exchange.sendResponseHeaders(status, -1)
                return
            }
            val initPart = if (start < initSize) minOf(initSize - 1L, end) - start + 1L else 0L
            val remoteStart = fragmentOffset + (start + initPart - initSize)
            val remoteEnd = fragmentOffset + (end - initSize)
            // Whole fragments downloaded before are served from memory first.
            val cachedParts = ArrayList<Triple<ByteArray, Int, Int>>()
            var remoteResume: Long? = null
            if (initPart < length) {
                var position = remoteStart
                while (position <= remoteEnd) {
                    val index = stream.fragmentIndexAtOffset(position)
                    val data = MusicVideoFragmentCache.get(stream, index) ?: break
                    val fragmentStart = stream.fragments[index].offset
                    val take = minOf(stream.fragmentEnd(index) - 1L, remoteEnd) - position + 1L
                    if (take <= 0L || data.size.toLong() < (position - fragmentStart) + take) break
                    cachedParts += Triple(data, (position - fragmentStart).toInt(), take.toInt())
                    position += take
                }
                if (position <= remoteEnd) remoteResume = position
            }
            fun openUpstream(from: Long): okhttp3.Response? {
                val request = Request.Builder()
                    .url(stream.remoteUrl)
                    .header("User-Agent", stream.userAgent)
                    .header("Accept-Encoding", "identity")
                    .header("Range", "bytes=$from-$remoteEnd")
                    .build()
                val response = client.newCall(request).execute()
                if (response.code != 206) {
                    response.close()
                    return null
                }
                return response
            }
            // When the answer starts with network data, the CDN range is opened
            // before answering, so an expired or refused URL reaches VLC as an
            // error (and the decoder re-fetches the stream) instead of an empty success.
            var upstream: okhttp3.Response? = null
            if (remoteResume != null && cachedParts.isEmpty()) {
                upstream = openUpstream(remoteResume) ?: run {
                    exchange.sendResponseHeaders(502, -1)
                    return
                }
            }
            try {
                exchange.sendResponseHeaders(status, length)
                exchange.responseBody.use { output ->
                    if (initPart > 0L) output.write(stream.init, start.toInt(), initPart.toInt())
                    for ((data, from, count) in cachedParts) output.write(data, from, count)
                    val resume = remoteResume
                    if (resume != null) {
                        val response = upstream ?: openUpstream(resume)?.also { upstream = it } ?: throw IOException("CDN refused the range")
                        val input = response.body?.byteStream() ?: throw IOException("empty body")
                        val buffer = ByteArray(64 * 1024)
                        // Whole fragments passing through are kept for later.
                        var position = resume
                        var index = stream.fragmentIndexAtOffset(position)
                        var collecting: java.io.ByteArrayOutputStream? =
                            if (position == stream.fragments[index].offset) java.io.ByteArrayOutputStream((stream.fragmentEnd(index) - position).toInt().coerceIn(0, 32 * 1024 * 1024)) else null
                        while (position <= remoteEnd) {
                            val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remoteEnd - position + 1L).toInt())
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            var consumed = 0
                            while (consumed < read) {
                                val fragmentEnd = stream.fragmentEnd(index)
                                val piece = minOf((read - consumed).toLong(), fragmentEnd - position).toInt()
                                collecting?.write(buffer, consumed, piece)
                                consumed += piece
                                position += piece
                                if (position >= fragmentEnd) {
                                    collecting?.let { MusicVideoFragmentCache.put(stream, index, it.toByteArray()) }
                                    if (index >= stream.fragments.lastIndex) break
                                    index++
                                    collecting = java.io.ByteArrayOutputStream((stream.fragmentEnd(index) - position).toInt().coerceIn(0, 32 * 1024 * 1024))
                                }
                            }
                        }
                    }
                    output.flush()
                }
            } finally {
                upstream?.close()
            }
        } catch (_: IOException) {
            // VLC dropped the connection (restart/track change) or the CDN failed;
            // the decoder notices missing pictures and restarts or gives up.
        } catch (error: Exception) {
            runCatching { exchange.sendResponseHeaders(502, -1) }
        } finally {
            exchange.close()
        }
    }
}
