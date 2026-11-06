package com.recordlabs.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** iOS (for now): Listen Together is not available yet. */
actual object WindowsListenTogetherClient {
    actual val users: StateFlow<List<ListenTogetherUser>> = MutableStateFlow(emptyList())
    actual fun transferHost(userId: String) {}
    actual fun suggestTrack(track: Track) {}
    actual fun sendGuestCommand(action: String) {}
    actual fun sendBufferReady(trackId: String) {}
    actual val roomCode: StateFlow<String?> = MutableStateFlow(null)
    actual fun requestSync() {}
    actual fun rejectSuggestion(suggestionId: String) {}
    actual fun rejectJoin(userId: String) {}
    actual val playbackSync: StateFlow<ListenTogetherPlaybackSync?> = MutableStateFlow(null)
    actual val pendingSuggestions: StateFlow<List<ListenTogetherTrackSuggestion>> = MutableStateFlow(emptyList())
    actual val pendingJoinRequests: StateFlow<List<ListenTogetherJoinRequest>> = MutableStateFlow(emptyList())
    actual val notice: StateFlow<ListenTogetherNotice?> = MutableStateFlow(null)
    actual val message: StateFlow<String> = MutableStateFlow("Listen Together is not available on iPhone yet")
    actual fun kickUser(userId: String) {}
    actual fun joinRoom(roomCode: String, username: String) {}
    actual val isHost: StateFlow<Boolean> = MutableStateFlow(false)
    actual fun isGuestNow() : Boolean = false
    actual var hostAlreadyHas: ((Track) -> String?)? = null
    actual val guestCommand: StateFlow<ListenTogetherGuestCommand?> = MutableStateFlow(null)
    actual fun createRoom(username: String) {}
    actual val connection: StateFlow<ListenTogetherConnection> = MutableStateFlow(ListenTogetherConnection.Disconnected)
    actual val bufferingUsers: StateFlow<List<String>> = MutableStateFlow(emptyList())
    actual fun broadcastPlayback(
        action: String,
        track: Track?,
        positionMs: Long,
        queue: List<Track>,
        queueTitle: String?,
    ) {}
    actual fun approveSuggestion(suggestionId: String) {}
    actual fun approveJoin(userId: String) {}
    actual fun leave() {}
}
