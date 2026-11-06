package com.recordlabs.desktop.data

import java.util.concurrent.atomic.AtomicReference

/**
 * Latest audio playback position, shared with the muted music-video decoder
 * so the picture can follow the audio that is actually heard.
 *
 * Updated by [WindowsMediaPlayer] each time it publishes state (every
 * 60–250 ms); [read] extrapolates between those updates. Each update is one
 * immutable snapshot, so a reader never mixes fields of two updates.
 */
object MusicVideoClock {
    data class Reading(
        val trackId: String?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val rate: Float,
        val durationMs: Long,
    )

    private class Snapshot(
        val trackId: String?,
        val isPlaying: Boolean,
        val positionMs: Long,
        val nanos: Long,
        val rate: Float,
        val durationMs: Long,
    )

    private val latest = AtomicReference(Snapshot(null, false, 0L, System.nanoTime(), 1f, 0L))

    fun publish(trackId: String?, isPlaying: Boolean, positionMs: Long, rate: Float, durationMs: Long = 0L) {
        latest.set(
            Snapshot(
                trackId = trackId,
                isPlaying = isPlaying,
                positionMs = positionMs.coerceAtLeast(0L),
                nanos = System.nanoTime(),
                rate = rate.takeIf { it > 0f } ?: 1f,
                durationMs = durationMs.coerceAtLeast(0L),
            ),
        )
    }

    fun read(): Reading {
        val snapshot = latest.get()
        val elapsedMs = if (snapshot.isPlaying) ((System.nanoTime() - snapshot.nanos) / 1_000_000L * snapshot.rate).toLong() else 0L
        // Never extrapolate more than one second past the last real report.
        return Reading(
            snapshot.trackId,
            snapshot.isPlaying,
            snapshot.positionMs + elapsedMs.coerceIn(0L, 1_000L),
            snapshot.rate,
            snapshot.durationMs,
        )
    }
}
