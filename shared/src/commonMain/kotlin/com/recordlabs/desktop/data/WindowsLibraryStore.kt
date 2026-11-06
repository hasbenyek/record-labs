package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.*

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
@Serializable
data class WindowsPlaylist(
    val name: String,
    val tracks: List<Track> = emptyList(),
    val browseId: String? = null,
)

/**
 * One listen, recorded like the Android app's history event: when a play of a
 * track ends (next track, repeat, app closed) and it was heard for at least
 * [WindowsLibraryStore.MIN_LISTEN_MS]. [playTimeMs] is the time it really played.
 */
@Serializable
data class WindowsListenEvent(
    val trackId: String,
    val atMs: Long,
    val playTimeMs: Long,
)

/** A listened track with its all-time listening time (Android: song.totalPlayTime). */
@Serializable
data class WindowsListenedTrack(
    val track: Track,
    val totalPlayTimeMs: Long = 0L,
    val lastListenedMs: Long = 0L,
)

/** An album, playlist or artist saved to the library from its page (the ⋯ menu). */
@Serializable
data class WindowsSavedCollection(
    val title: String,
    /** "Album", "Playlist" or "Artist". */
    val kind: String,
    val browseId: String,
    val subtitle: String = "",
    val artworkUrl: String? = null,
    val savedAtMs: Long = 0L,
)

@Serializable
data class WindowsLibrarySnapshot(
    val liked: List<Track> = emptyList(),
    val history: List<Track> = emptyList(),
    val playlists: List<WindowsPlaylist> = emptyList(),
    val pinned: List<Track> = emptyList(),
    /** Listening history for "My Top 50", "Monthly Most" and "Weekly Most". */
    val listenedTracks: List<WindowsListenedTrack> = emptyList(),
    val listenEvents: List<WindowsListenEvent> = emptyList(),
    /** Albums, playlists and artists saved from their pages (newest first). */
    val savedCollections: List<WindowsSavedCollection> = emptyList(),
)

/**
 * Most listened tracks, ranked like the Android app: by the total time they
 * were really played. With [sinceMs], only listens after that moment count
 * (Weekly / Monthly Most); without it, all-time totals (My Top 50).
 */
fun WindowsLibrarySnapshot.mostPlayed(limit: Int = Int.MAX_VALUE, sinceMs: Long? = null): List<Track> {
    if (sinceMs == null) {
        return listenedTracks.asSequence()
            .filter { it.totalPlayTimeMs > 0L }
            .sortedWith(compareByDescending<WindowsListenedTrack> { it.totalPlayTimeMs }.thenByDescending { it.lastListenedMs })
            .take(limit)
            .map { it.track }
            .toList()
    }
    val tracks = listenedTracks.associateBy { it.track.id }
    data class Window(var playTimeMs: Long = 0L, var lastMs: Long = 0L)
    val windows = LinkedHashMap<String, Window>()
    for (event in listenEvents) {
        if (event.atMs <= sinceMs) continue
        val window = windows.getOrPut(event.trackId) { Window() }
        window.playTimeMs += event.playTimeMs
        window.lastMs = maxOf(window.lastMs, event.atMs)
    }
    return windows.entries.asSequence()
        .mapNotNull { (id, window) -> tracks[id]?.let { it.track to window } }
        .sortedWith(compareByDescending<Pair<Track, Window>> { it.second.playTimeMs }.thenByDescending { it.second.lastMs })
        .take(limit)
        .map { it.first }
        .toList()
}

/** Desktop library persistence storing data inside the app-owned %LOCALAPPDATA%\RecordLabs folder. */
class WindowsLibraryStore {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val file: File by lazy {
        File(AppStorage.directory, "library_snapshot.json")
    }

    private var state = load()

    @JvmSync
    fun snapshot(): WindowsLibrarySnapshot = state

    @JvmSync
    fun addToHistory(track: Track) {
        // Settings > Privacy > Pause listening history.
        if (AppSettings.pauseListenHistory.value) return
        state = state.copy(history = listOf(track) + state.history.filterNot { it.id == track.id }.take(99))
        persist()
    }

    /**
     * Records one listen of [track] that played for [playTimeMs] (ignored when
     * shorter than [MIN_LISTEN_MS], like the Android app's 30 s history
     * threshold). Listens older than [EVENT_RETENTION_MS] are dropped from the
     * event list (they stay in the all-time totals).
     */
    fun recordListen(track: Track, playTimeMs: Long, atMs: Long = currentTimeMillis(), writeNow: Boolean = false): Boolean {
        if (AppSettings.pauseListenHistory.value) return false
        // Settings > Player > "Count a listen after" (default 30 s, like Android).
        if (playTimeMs < AppSettings.historyMinSeconds.value * 1_000L || track.id.isBlank()) return false
        locked(this) {
            val previous = state.listenedTracks.firstOrNull { it.track.id == track.id }
            val updated = WindowsListenedTrack(
                track = track,
                totalPlayTimeMs = (previous?.totalPlayTimeMs ?: 0L) + playTimeMs,
                lastListenedMs = atMs,
            )
            val events = (state.listenEvents + WindowsListenEvent(track.id, atMs, playTimeMs))
                .filter { it.atMs > atMs - EVENT_RETENTION_MS }
                .takeLast(MAX_EVENTS)
            state = state.copy(
                listenedTracks = listOf(updated) + state.listenedTracks.filterNot { it.track.id == track.id },
                listenEvents = events,
            )
        }
        // Written outside the state lock: the writer takes its own lock first.
        if (writeNow) writeLatest() else persist()
        return true
    }

    /** Settings > Backup > Restore: the whole library from a backup. */
    fun replaceWith(snapshot: WindowsLibrarySnapshot) {
        locked(this) { state = snapshot }
        writeLatest()
    }

    /** The player's history list (Clear): only the recently played songs; the listening stats stay. */
    fun clearRecentlyPlayed() {
        locked(this) { state = state.copy(history = emptyList()) }
        writeLatest()
    }

    /** Settings > Privacy > Clear listening history: recently played and the listening stats (likes and playlists stay). */
    fun clearListenHistory() {
        locked(this) {
            state = state.copy(history = emptyList(), listenedTracks = emptyList(), listenEvents = emptyList())
        }
        writeLatest()
    }

    @JvmSync
    fun toggleLiked(track: Track): Boolean {
        val liked = if (state.liked.any { it.id == track.id }) {
            state.liked.filterNot { it.id == track.id }
        } else {
            listOf(track) + state.liked
        }
        state = state.copy(liked = liked)
        persist()
        val nowLiked = liked.any { it.id == track.id }
        // Settings > Integrations > Last.fm > Send likes.
        runCatching { LastFmScrobbler.onLikeChanged(track, nowLiked) }
        return nowLiked
    }

    @JvmSync
    fun toggleSavedCollection(item: WindowsSavedCollection): Boolean {
        val saved = state.savedCollections.any { it.browseId == item.browseId }
        state = state.copy(
            savedCollections = if (saved) state.savedCollections.filterNot { it.browseId == item.browseId }
            else listOf(item.copy(savedAtMs = currentTimeMillis())) + state.savedCollections,
        )
        persist()
        return !saved
    }

    @JvmSync
    fun togglePinned(track: Track): Boolean {
        val pinned = if (state.pinned.any { it.id == track.id }) state.pinned.filterNot { it.id == track.id }
        else listOf(track) + state.pinned
        state = state.copy(pinned = pinned)
        persist()
        return pinned.any { it.id == track.id }
    }

    @JvmSync
    fun createPlaylist(name: String): Boolean {
        val cleanName = name.trim()
        if (cleanName.isBlank() || state.playlists.any { it.name.equals(cleanName, ignoreCase = true) }) return false
        state = state.copy(playlists = state.playlists + WindowsPlaylist(cleanName))
        persist()
        return true
    }

    @JvmSync
    fun addToPlaylist(name: String, track: Track): Boolean {
        val playlist = state.playlists.firstOrNull { it.name == name } ?: return false
        val updated = playlist.copy(tracks = playlist.tracks + track)
        state = state.copy(playlists = state.playlists.map { if (it.name == name) updated else it })
        persist()
        return true
    }

    @JvmSync
    fun removePlaylist(name: String) {
        state = state.copy(playlists = state.playlists.filterNot { it.name == name })
        persist()
    }

    /** Merge the signed-in YouTube Music collection without discarding local-only data. */
    @JvmSync
    fun mergeRemote(liked: List<Track>, playlists: List<WindowsPlaylist>) {
        val mergedLiked = (liked + state.liked).distinctBy { it.id }
        val remoteIds = playlists.mapNotNull { it.browseId }.toSet()
        val localOnly = state.playlists.filter { it.browseId == null || it.browseId !in remoteIds }
        val mergedRemote = playlists.map { remote ->
            val existing = state.playlists.firstOrNull { it.browseId == remote.browseId }
            remote.copy(tracks = (remote.tracks + existing?.tracks.orEmpty()).distinctBy { it.id })
        }
        state = state.copy(liked = mergedLiked, playlists = localOnly + mergedRemote)
        persist()
    }

    private fun load(): WindowsLibrarySnapshot = runCatching {
        if (file.exists()) json.decodeFromString<WindowsLibrarySnapshot>(file.readText()) else WindowsLibrarySnapshot()
    }.getOrDefault(WindowsLibrarySnapshot())

    private val writeLock = Any()

    private fun persist() {
        CoroutineScope(IoDispatcher).launch { writeLatest() }
    }

    // One write at a time, through a temporary file, so two saves close
    // together (a counted listen and a like) can never leave a garbled file.
    // Each write saves the newest state, so their order does not matter.
    private fun writeLatest() {
        locked(writeLock) {
            val snapshot = snapshot()
            runCatching {
                val temp = File(file.parentFile, file.name + ".tmp")
                temp.writeText(json.encodeToString(snapshot))
                atomicReplace(temp, file)
            }.onFailure { runCatching { file.writeText(json.encodeToString(snapshot)) } }
        }
    }

    companion object {
        /** The Android app's default "history duration": 30 s. */
        const val MIN_LISTEN_MS = 30_000L
        /** Events are needed for "Monthly Most" (one month back) at most. */
        const val EVENT_RETENTION_MS = 62L * 24 * 60 * 60 * 1000
        const val MAX_EVENTS = 20_000
    }
}
