package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.concurrentMapOf
import kotlin.math.ceil
import kotlin.math.roundToLong

/**
 * iPhone: YouTube's audio streams as HLS playlists the app writes itself.
 *
 * The streams are fragmented MP4 (one file in many small pieces, with an index of the
 * pieces near its start: the "sidx" box). AVPlayer does not read that index: given the
 * file, it reads on through it to find where it ends and where to seek, so a song took
 * seconds to start (longer when it starts part way, as after a Connect handoff), its
 * length came out wrong (twice as long), and a single long read is slowed by YouTube to
 * playback speed.
 *
 * Here the index is read (one small request) and turned into an HLS playlist of byte
 * ranges of the same file, which AVPlayer plays like any HLS stream: it fetches the
 * start and the first pieces only, knows the exact length, and seeks straight to the
 * right piece.
 */
object StreamIndex {
    /** Where a stream's header (init) and index (sidx) are, from the player answer. */
    class Format(val initEnd: Long, val indexStart: Long, val indexEnd: Long, val client: String)

    private val formats = concurrentMapOf<String, Format>()
    private val playlists = concurrentMapOf<String, String>()

    fun record(url: String, format: Format) {
        if (formats.size > 200) formats.clear()
        formats[url] = format
    }

    fun formatOf(url: String): Format? = formats[url]

    fun putPlaylist(url: String, playlist: String) {
        if (playlists.size > 40) playlists.clear()
        playlists[url] = playlist
    }

    /** The HLS playlist written for [url], if its index could be read. */
    fun playlistFor(url: String): String? = if (failures >= 2) null else playlists[url]

    @kotlin.concurrent.Volatile private var failures = 0

    /**
     * [url] did not play: when it was played as HLS pieces, the next try plays the whole
     * file; after two such failures the pieces are not used again until the app restarts.
     */
    fun reportFailure(url: String) {
        if (playlists.remove(url) == null) return
        failures += 1
        AppDiagnostics.log("playback", "HLS pieces did not play ($failures)" + if (failures >= 2) ": whole files from now on" else "")
    }

    private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xff) shl 8) or (b[i + 1].toInt() and 0xff)

    private fun u32(b: ByteArray, i: Int): Long =
        ((b[i].toLong() and 0xff) shl 24) or ((b[i + 1].toLong() and 0xff) shl 16) or
            ((b[i + 2].toLong() and 0xff) shl 8) or (b[i + 3].toLong() and 0xff)

    private fun u64(b: ByteArray, i: Int): Long = (u32(b, i) shl 32) or u32(b, i + 4)

    private fun seconds(value: Double): String {
        val ms = (value * 1000.0).roundToLong()
        return "${ms / 1000}.${(ms % 1000).toString().padStart(3, '0')}"
    }

    /**
     * An HLS media playlist for [url] from its [sidx] box (read at [indexStart]); the
     * header is bytes 0..[initEnd]. Null when the box is not a plain one-level index.
     */
    fun playlist(url: String, initEnd: Long, indexStart: Long, sidx: ByteArray): String? = runCatching {
        if (sidx.size < 32 || initEnd <= 0L) return@runCatching null
        val boxSize = u32(sidx, 0)
        if (sidx.decodeToString(4, 8) != "sidx" || boxSize < 32) return@runCatching null
        val version = sidx[8].toInt() and 0xff
        var p = 12 + 4 // version/flags, reference_ID
        val timescale = u32(sidx, p)
        p += 4
        val firstOffset: Long
        if (version == 0) {
            p += 4 // earliest_presentation_time
            firstOffset = u32(sidx, p)
            p += 4
        } else {
            p += 8
            firstOffset = u64(sidx, p)
            p += 8
        }
        p += 2 // reserved
        val count = u16(sidx, p)
        p += 2
        if (timescale <= 0L || count <= 0 || sidx.size < p + count * 12) return@runCatching null
        var offset = indexStart + boxSize + firstOffset
        var longest = 0.0
        val pieces = StringBuilder()
        repeat(count) {
            val reference = u32(sidx, p)
            // A reference to another index (a two-level index): not used by YouTube's audio.
            if (reference and 0x80000000L != 0L) return@runCatching null
            val length = reference and 0x7fffffffL
            val duration = u32(sidx, p + 4).toDouble() / timescale
            p += 12
            if (length <= 0L) return@runCatching null
            if (duration > longest) longest = duration
            pieces.append("#EXTINF:").append(seconds(duration)).append(",\n")
                .append("#EXT-X-BYTERANGE:").append(length).append('@').append(offset).append('\n')
                .append(url).append('\n')
            offset += length
        }
        buildString {
            append("#EXTM3U\n")
            append("#EXT-X-VERSION:7\n")
            append("#EXT-X-TARGETDURATION:").append(ceil(longest).toInt().coerceAtLeast(1)).append('\n')
            append("#EXT-X-MEDIA-SEQUENCE:0\n")
            append("#EXT-X-PLAYLIST-TYPE:VOD\n")
            append("#EXT-X-INDEPENDENT-SEGMENTS\n")
            append("#EXT-X-MAP:URI=\"").append(url).append("\",BYTERANGE=\"").append(initEnd + 1).append("@0\"\n")
            append(pieces)
            append("#EXT-X-ENDLIST\n")
        }
    }.getOrNull()
}
