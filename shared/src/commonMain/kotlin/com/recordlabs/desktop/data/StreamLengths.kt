package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.concurrentMapOf

/**
 * Each song's real length, as YouTube sends it with the song's stream (approxDurationMs).
 *
 * The iPhone needs it: AVPlayer only guesses the length of YouTube's fragmented MP4
 * streams (a 2:32 song shown as 5:06, then silence up to the guess), and that guess
 * used to be copied into the song's details (history, last playback), so the song's
 * own length could not be trusted any more either. Known as soon as the stream is
 * found, so the time left shows before the song starts playing.
 */
object StreamLengths {
    private val lengths = concurrentMapOf<String, Long>()

    fun record(videoId: String, lengthMs: Long?) {
        if (lengthMs == null || lengthMs <= 0L) return
        if (lengths.size > 600) lengths.clear()
        lengths[videoId] = lengthMs
    }

    /** The real length of [videoId] (ms), when its stream has been found in this session. */
    fun of(videoId: String): Long? = lengths[videoId]
}
