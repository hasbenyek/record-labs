package com.recordlabs.desktop.data

/**
 * Measures how long the current track has really played, the way the Android
 * app's PlaybackStats do: time only runs while the track is playing (not paused
 * or loading). A play ends when another track starts, the track starts over
 * (repeat), or the app closes; it is then saved as one listen if it lasted at
 * least [WindowsLibraryStore.MIN_LISTEN_MS].
 */
object WindowsListeningTracker {
    private var store: WindowsLibraryStore? = null
    private var track: Track? = null
    private var playedMs = 0L

    @Synchronized
    fun attach(libraryStore: WindowsLibraryStore) {
        store = libraryStore
    }

    /** Ends the play in progress (saving it when long enough) and starts one for [next]. */
    @Synchronized
    fun begin(next: Track?): Boolean {
        val saved = finish(writeNow = false)
        track = next
        playedMs = 0L
        return saved
    }

    /** Adds real playing time to the play of [trackId] in progress. */
    @Synchronized
    fun addPlayed(trackId: String, ms: Long) {
        if (track?.id == trackId && ms > 0L) playedMs += ms
    }

    /** App closing: saves the play in progress right away. */
    @Synchronized
    fun flushOnExit() {
        finish(writeNow = true)
        track = null
        playedMs = 0L
    }

    private fun finish(writeNow: Boolean): Boolean {
        val current = track ?: return false
        val played = playedMs
        playedMs = 0L
        return runCatching { store?.recordListen(current, played, writeNow = writeNow) == true }.getOrDefault(false)
    }
}
