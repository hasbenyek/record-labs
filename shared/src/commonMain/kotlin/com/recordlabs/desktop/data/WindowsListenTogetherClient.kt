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

data class ListenTogetherGuestCommand(val action: String, val sequence: Long)

/** Something the room did that this device shows as a short notice (a suggestion arrived, was added...). */
data class ListenTogetherNotice(val text: String, val sequence: Long)

/** The desktop and Android: WindowsListenTogetherClient in jvmSharedMain; iOS: its own version (iosMain). */
expect object WindowsListenTogetherClient {
    val users: StateFlow<List<ListenTogetherUser>>
    fun transferHost(userId: String)
    fun suggestTrack(track: Track)
    fun sendGuestCommand(action: String)
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
