package com.recordlabs.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/** iOS (for now): Record Labs Connect is not available yet; nothing is sent or found. */
actual object WindowsRecordLabsConnect {
    actual fun takePlaybackOwnership(requestedTerm: Long?) {}
    actual fun takePlaybackOwnership(userStarted: Boolean) {}
    actual fun stop() {}
    actual fun start(
        accountKeys: Collection<String>,
        deviceName: String,
        playbackProvider: () -> ConnectPlaybackState?,
        onRemoteState: (ConnectPlaybackState) -> Unit,
        onCommand: (String, Long?, Track?) -> Unit,
        onLocalOwnership: ((handedTrackId: String?, handedPositionMs: Long?) -> Unit)?,
        onTransferStalled: ((String) -> Unit)?,
        onTakeoverPrepare: ((ConnectPlaybackState) -> Unit)?,
    ) {}
    actual fun sendTrackReady(ownerId: String, trackId: String) {}
    actual fun sendQueueNext(track: Track) : Boolean = false
    actual fun sendQueueMove(trackId: String, toIndex: Int) : Boolean = false
    actual fun sendPlayTrack(track: Track) : Boolean = false
    actual fun sendCommand(command: String, positionMs: Long?) : Boolean = false
    actual fun selectPlaybackDevice(deviceId: String) : Boolean = false
    actual val remoteOwnerId: StateFlow<String?> = MutableStateFlow(null)
    actual fun notifyPlayerReady() {}
    actual val localDeviceId: String get() = WindowsPreferences.connectDeviceId()
    actual fun hasRemotePlaybackDevice() : Boolean = false
    actual val discoveryStatus: StateFlow<WindowsConnectDiscoveryStatus> = MutableStateFlow(WindowsConnectDiscoveryStatus())
    actual val devices: StateFlow<List<ConnectDevice>> = MutableStateFlow(emptyList())
    actual fun debugLog(message: String) {}
    actual fun configureBluetooth(sender: ((String) -> Unit)?) {}
    actual fun claimForLocalPlay() {}
    actual fun receiveBluetooth(raw: String) {}
}
