package com.recordlabs.desktop.data

import kotlinx.coroutines.flow.StateFlow

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ListenTogetherConnection { Disconnected, Connecting, Connected, InRoom, Error }

data class ListenTogetherJoinRequest(val userId: String, val username: String)

data class ListenTogetherPlaybackSync(
    val action: String,
    val track: Track? = null,
    val trackId: String? = null,
    val positionMs: Long = 0L,
    val serverTimeMs: Long = 0L,
    val queue: List<Track> = emptyList(),
    val queueTitle: String? = null,
    val sequence: Long = 0L,
)

data class ListenTogetherTrackSuggestion(val suggestionId: String, val username: String, val track: Track)

data class ListenTogetherUser(val userId: String, val username: String, val isHost: Boolean, val isConnected: Boolean = true)

/** A listener's command; [track]: the song it is about (play it now, play it next). */
data class ListenTogetherGuestCommand(val action: String, val sequence: Long, val track: Track? = null)

/**
 * Ends the host's queue title when it lets listeners control playback (Settings >
 * Listen Together > Guest playback controls): listeners then play and arrange songs
 * as the host does, instead of only suggesting them. The title is not shown anywhere.
 */
internal const val GUEST_CONTROLS_MARK = " · guest controls"

internal fun hostQueueTitle(title: String?, guestControls: Boolean): String =
    (title?.takeIf(String::isNotBlank) ?: "Record Labs queue").removeSuffix(GUEST_CONTROLS_MARK) +
        if (guestControls) GUEST_CONTROLS_MARK else ""

/** Commands that carry a song: GUEST_CMD:<action>:<song id>, with the song's title, artist… */
internal val GUEST_TRACK_COMMANDS = listOf("PLAY_NOW", "QUEUE_NEXT")

internal fun guestTrackCommandTrack(action: String, track: Track): Track = Track(
    id = "GUEST_CMD:$action:${track.id}",
    title = track.title,
    artist = track.artist,
    album = track.album,
    artworkUrl = track.artworkUrl,
    durationMs = track.durationMs,
)

/** The command a listener sent (see [guestTrackCommandTrack]), with its song when it has one. */
internal fun guestCommandOf(sent: Track, sequence: Long): ListenTogetherGuestCommand {
    val action = sent.id.removePrefix("GUEST_CMD:")
    val name = action.substringBefore(':')
    val songId = action.substringAfter(':', "")
    val song = if (name in GUEST_TRACK_COMMANDS && songId.isNotBlank()) {
        sent.copy(id = songId, isOfficial = true)
    } else null
    return ListenTogetherGuestCommand(if (song != null) name else action, sequence, song)
}

/** Something the room did that this device shows as a short notice (a suggestion arrived, was added...). */
data class ListenTogetherNotice(val text: String, val sequence: Long)

/** The desktop and Android: WindowsListenTogetherClient in jvmSharedMain; iOS: its own version (iosMain). */
expect object WindowsListenTogetherClient {
    val users: StateFlow<List<ListenTogetherUser>>
    fun transferHost(userId: String)
    fun suggestTrack(track: Track)
    fun sendGuestCommand(action: String)
    /** A listener's command about [track] (see [GUEST_TRACK_COMMANDS]). */
    fun sendGuestTrackCommand(action: String, track: Track)
    /** A listener: the host lets listeners control playback (see [GUEST_CONTROLS_MARK]). */
    val guestControlsAllowed: StateFlow<Boolean>
    fun sendBufferReady(trackId: String)
    val roomCode: StateFlow<String?>
    fun requestSync()
    fun rejectSuggestion(suggestionId: String)
    fun rejectJoin(userId: String)
    val playbackSync: StateFlow<ListenTogetherPlaybackSync?>
    val pendingSuggestions: StateFlow<List<ListenTogetherTrackSuggestion>>
    val pendingJoinRequests: StateFlow<List<ListenTogetherJoinRequest>>
    val notice: StateFlow<ListenTogetherNotice?>
    val message: StateFlow<String>
    fun kickUser(userId: String)
    fun joinRoom(roomCode: String, username: String)
    val isHost: StateFlow<Boolean>
    fun isGuestNow() : Boolean
    var hostAlreadyHas: ((Track) -> String?)?
    /** Host: a suggestion it approved, to put in its own queue (after the songs added before). */
    var hostApproved: ((Track) -> Unit)?
    val guestCommand: StateFlow<ListenTogetherGuestCommand?>
    fun createRoom(username: String)
    val connection: StateFlow<ListenTogetherConnection>
    val bufferingUsers: StateFlow<List<String>>
    fun broadcastPlayback(
        action: String,
        track: Track? = null,
        positionMs: Long = 0L,
        queue: List<Track> = emptyList(),
        queueTitle: String? = null,
    )
    fun approveSuggestion(suggestionId: String)
    fun approveJoin(userId: String)
    fun leave()
}
