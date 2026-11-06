package com.recordlabs.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * A music video's picture stream (YouTube DASH, fragmented MP4) prepared so the
 * VLC decoder can start it at any point.
 *
 * VLC 3's MP4 demuxer cannot seek in fragmented MP4 that only carries a `sidx`
 * index (YouTube's format): `:start-time` and `setTime()` silently play from
 * the beginning. Instead of seeking, [urlFrom] serves a virtual file made of
 * the init segment (ftyp + moov) followed by the stream from fragment N on,
 * which VLC plays linearly with the fragment's real timestamps. The fragment
 * table comes from the `sidx` box, so the start time of every fragment and the
 * exact video length are known without trusting VLC's own clock.
 */
class MusicVideoStream(
    val videoId: String,
    val remoteUrl: String,
    val userAgent: String,
    val contentLength: Long,
    val init: ByteArray,
    /** Fragment start times (ms) and byte offsets, in order. */
    val fragments: List<Fragment>,
    /** Length of the picture stream itself (may differ slightly from the audio). */
    val durationMs: Long,
) {
    data class Fragment(val startMs: Long, val offset: Long)

    /**
     * Real picture size from the format list (0 = unknown). VLC can report a
     * padded size for some codecs (AV1 frames are aligned to 128 pixels), so
     * the decoder takes the shape from here when it is known.
     */
    @Volatile var pictureWidth: Int = 0
    @Volatile var pictureHeight: Int = 0
    /** YouTube's quality step of the picture ("2160" for 4K), 0 = unknown. */
    @Volatile var pictureQuality: Int = 0

    /** Last fragment starting at or before [positionMs]. */
    fun fragmentIndexFor(positionMs: Long): Int {
        var low = 0
        var high = fragments.lastIndex
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (fragments[mid].startMs <= positionMs) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Remote byte offset where fragment [index] ends (exclusive). */
    fun fragmentEnd(index: Int): Long = if (index < fragments.lastIndex) fragments[index + 1].offset else contentLength

    /** Fragment holding remote byte [offset]. */
    fun fragmentIndexAtOffset(offset: Long): Int {
        var low = 0
        var high = fragments.lastIndex
        var found = 0
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (fragments[mid].offset <= offset) {
                found = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return found
    }

    /** Loopback URL of the virtual file starting with fragment [index]. */
    fun urlFrom(index: Int): String = host?.urlFor(this, index.coerceIn(0, fragments.lastIndex)) ?: remoteUrl

    /** Serves the virtual files (the desktop app's loopback server; none on Android). */
    interface Host {
        fun urlFor(stream: MusicVideoStream, index: Int): String
    }

    companion object {
        private val host: Host? by lazy {
            runCatching { Class.forName("com.recordlabs.desktop.data.MusicVideoStreamServer").getField("INSTANCE").get(null) as Host }.getOrNull()
        }

        /**
         * Builds the stream from the file's first bytes (init segment + sidx,
         * i.e. byte 0 up to the end of YouTube's `indexRange`). Returns null for
         * anything that is not a single-track fragmented MP4 with a sidx index.
         */
        fun parse(
            videoId: String,
            remoteUrl: String,
            userAgent: String,
            contentLength: Long,
            head: ByteArray,
        ): MusicVideoStream? = runCatching {
            val buffer = ByteBuffer.wrap(head)
            var position = 0
            var moovEnd = -1
            var sidxStart = -1
            var sidxSize = 0
            while (position + 8 <= head.size) {
                var size = buffer.getInt(position).toLong() and 0xffffffffL
                val type = String(head, position + 4, 4, Charsets.US_ASCII)
                var headerSize = 8
                if (size == 1L && position + 16 <= head.size) {
                    size = buffer.getLong(position + 8)
                    headerSize = 16
                }
                if (size < headerSize) break
                when (type) {
                    "moov" -> moovEnd = (position + size).toInt()
                    "sidx" -> {
                        sidxStart = position
                        sidxSize = size.toInt()
                    }
                }
                if (type == "sidx") break
                position += size.toInt()
            }
            if (moovEnd <= 0 || sidxStart < moovEnd || sidxStart + sidxSize > head.size) return@runCatching null

            val sidx = ByteBuffer.wrap(head, sidxStart, sidxSize).slice()
            val version = sidx.get(8).toInt() and 0xff
            val timescale = sidx.getInt(16).toLong() and 0xffffffffL
            if (timescale <= 0L) return@runCatching null
            var cursor: Int
            val earliest: Long
            val firstOffset: Long
            if (version == 0) {
                earliest = sidx.getInt(20).toLong() and 0xffffffffL
                firstOffset = sidx.getInt(24).toLong() and 0xffffffffL
                cursor = 28
            } else {
                earliest = sidx.getLong(20)
                firstOffset = sidx.getLong(28)
                cursor = 36
            }
            val referenceCount = sidx.getShort(cursor + 2).toInt() and 0xffff
            cursor += 4
            if (referenceCount == 0) return@runCatching null
            var offset = sidxStart.toLong() + sidxSize + firstOffset
            var time = earliest
            val fragments = ArrayList<Fragment>(referenceCount)
            repeat(referenceCount) {
                val sizeWord = sidx.getInt(cursor).toLong() and 0xffffffffL
                val duration = sidx.getInt(cursor + 4).toLong() and 0xffffffffL
                cursor += 12
                // A set reference-type bit points at another sidx (not used by YouTube).
                if (sizeWord and 0x80000000L != 0L) return@runCatching null
                fragments += Fragment(startMs = time * 1000L / timescale, offset = offset)
                offset += sizeWord and 0x7fffffffL
                time += duration
            }
            if (fragments.last().offset >= contentLength) return@runCatching null
            MusicVideoStream(
                videoId = videoId,
                remoteUrl = remoteUrl,
                userAgent = userAgent,
                contentLength = contentLength,
                init = head.copyOfRange(0, moovEnd),
                fragments = fragments,
                durationMs = time * 1000L / timescale,
            )
        }.getOrNull()
    }
}

/**
 * Picture fragments already downloaded once (whole fragments only), so a seek
 * back, or a restart of the picture, starts from memory at once instead of
 * waiting for the network again. Least recently used fragments go first.
 */
object MusicVideoFragmentCache {
    private const val BUDGET_BYTES = 160L * 1024 * 1024
    private val entries = LinkedHashMap<Pair<String, Int>, ByteArray>(64, .75f, true)
    private var totalBytes = 0L

    @Synchronized
    fun get(stream: MusicVideoStream, index: Int): ByteArray? = entries[stream.remoteUrl to index]

    @Synchronized
    fun put(stream: MusicVideoStream, index: Int, data: ByteArray) {
        val key = stream.remoteUrl to index
        entries.put(key, data)?.let { totalBytes -= it.size }
        totalBytes += data.size
        val iterator = entries.entries.iterator()
        while (totalBytes > BUDGET_BYTES && iterator.hasNext()) {
            val eldest = iterator.next()
            if (eldest.key == key) continue
            totalBytes -= eldest.value.size
            iterator.remove()
        }
    }
}

