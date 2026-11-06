package com.recordlabs.desktop

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.okhttp.OkHttpDataSource

/**
 * Animated artwork clips played straight from Apple while they download (no
 * wait for the whole file), kept in a cache so every loop and every replay
 * reads from the device.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object AnimatedArtworkStream {
    private const val CACHE_BYTES = 300L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    private fun cache(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: run {
            // The earlier whole-file copies are no longer used.
            runCatching { java.io.File(context.cacheDir, "animated-artwork").deleteRecursively() }
            SimpleCache(
                java.io.File(context.cacheDir, "animated-artwork-stream"),
                LeastRecentlyUsedCacheEvictor(CACHE_BYTES),
                StandaloneDatabaseProvider(context.applicationContext),
            ).also { cache = it }
        }
    }

    private val http by lazy { okhttp3.OkHttpClient() }

    /** Reads a clip through the cache, its fragment times moved so it starts at 0. */
    fun dataSourceFactory(context: Context): DataSource.Factory {
        val cached = CacheDataSource.Factory()
            .setCache(cache(context))
            .setUpstreamDataSourceFactory(OkHttpDataSource.Factory(http).setUserAgent("RecordLabs-Android"))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        return DataSource.Factory { StartAtZeroDataSource(cached.createDataSource()) }
    }

    /**
     * Whether a decoder here can play Apple's 10-bit HEVC (Main 10) clips; WSA and
     * many phones can't (the H.264 clip is used at once instead of trying).
     */
    val canDecodeHevcMain10: Boolean by lazy {
        runCatching {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
                !info.isEncoder && info.supportedTypes.any { it.equals("video/hevc", ignoreCase = true) } &&
                    info.getCapabilitiesForType("video/hevc").profileLevels.any { it.profile == MediaCodecInfo.CodecProfileLevel.HEVCProfileMain10 }
            }
        }.getOrDefault(false)
    }
}

/**
 * Apple's clips start their timeline at 10 s (the first fragment's decode time),
 * so the player held the first frame for 10 s before the animation ran, at
 * every loop. While the clip is read, each fragment's time ("tfdt") is moved so
 * the clip starts at 0. The clip is read from its start in one pass (a
 * fragmented MP4 without an index); reads that start elsewhere pass unchanged.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
private class StartAtZeroDataSource(private val upstream: DataSource) : DataSource {
    private var rewrite = false
    private var position = 0L
    private var boxEnd = 0L
    private var pending: ByteArray? = null
    private var pendingAt = 0
    private var base: Long? = null
    private var ended = false

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        rewrite = dataSpec.position == 0L
        position = dataSpec.position
        boxEnd = position
        pending = null
        pendingAt = 0
        base = null
        ended = false
        return upstream.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (!rewrite) return upstream.read(buffer, offset, length)
        pending?.let { bytes -> return servePending(bytes, buffer, offset, length) }
        if (position >= boxEnd) {
            if (ended) return C.RESULT_END_OF_INPUT
            val header = ByteArray(16)
            val got = readFully(header, 0, 8)
            if (got < 8) {
                ended = true
                if (got <= 0) return C.RESULT_END_OF_INPUT
                return servePending(header.copyOf(got), buffer, offset, length)
            }
            var size = ((header[0].toLong() and 0xFF) shl 24) or ((header[1].toLong() and 0xFF) shl 16) or
                ((header[2].toLong() and 0xFF) shl 8) or (header[3].toLong() and 0xFF)
            val type = String(header, 4, 4, Charsets.US_ASCII)
            var headerLength = 8
            if (size == 1L) {
                if (readFully(header, 8, 8) < 8) { ended = true; return servePending(header.copyOf(8), buffer, offset, length) }
                size = java.nio.ByteBuffer.wrap(header, 8, 8).long
                headerLength = 16
            }
            boxEnd = if (size == 0L || size < headerLength) Long.MAX_VALUE else position + size
            val bytes = if (type == "moof" && size in 16..(1L shl 20)) {
                // A fragment header (about 1.5 kB): read it whole and fix its times.
                val whole = ByteArray(size.toInt())
                System.arraycopy(header, 0, whole, 0, headerLength)
                val got2 = readFully(whole, headerLength, size.toInt() - headerLength)
                val box = if (got2 < size.toInt() - headerLength) whole.copyOf(headerLength + maxOf(got2, 0)) else whole
                patchTimes(box, headerLength, box.size)
                box
            } else header.copyOf(headerLength)
            return servePending(bytes, buffer, offset, length)
        }
        val wanted = minOf(length.toLong(), boxEnd - position).toInt()
        val read = upstream.read(buffer, offset, wanted)
        if (read == C.RESULT_END_OF_INPUT) { ended = true; return read }
        position += read
        return read
    }

    private fun servePending(bytes: ByteArray, buffer: ByteArray, offset: Int, length: Int): Int {
        val n = minOf(length, bytes.size - pendingAt)
        System.arraycopy(bytes, pendingAt, buffer, offset, n)
        pendingAt += n
        position += n
        if (pendingAt >= bytes.size) { pending = null; pendingAt = 0 } else pending = bytes
        return n
    }

    private fun readFully(target: ByteArray, offset: Int, length: Int): Int {
        var done = 0
        while (done < length) {
            val n = upstream.read(target, offset + done, length - done)
            if (n == C.RESULT_END_OF_INPUT) return if (done == 0) -1 else done
            done += n
        }
        return done
    }

    /** Moves the "tfdt" times inside a moof (bytes [start, end)) so the first is 0. */
    private fun patchTimes(box: ByteArray, start: Int, end: Int) {
        var at = start
        while (at + 8 <= end) {
            val size = java.nio.ByteBuffer.wrap(box, at, 4).int.toLong() and 0xFFFFFFFFL
            val type = String(box, at + 4, 4, Charsets.US_ASCII)
            if (size < 8 || at + size > end) return
            val body = at + 8
            when (type) {
                "traf" -> patchTimes(box, body, (at + size).toInt())
                "tfdt" -> {
                    val version = box[body].toInt()
                    val wrap = java.nio.ByteBuffer.wrap(box)
                    if (version == 1 && body + 12 <= end) {
                        val time = wrap.getLong(body + 4)
                        val first = base ?: time.also { base = it }
                        wrap.putLong(body + 4, time - first)
                    } else if (body + 8 <= end) {
                        val time = wrap.getInt(body + 4).toLong() and 0xFFFFFFFFL
                        val first = base ?: time.also { base = it }
                        wrap.putInt(body + 4, (time - first).toInt())
                    }
                }
            }
            at += size.toInt()
        }
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() = upstream.close()
}
