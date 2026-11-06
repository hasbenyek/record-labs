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

data class ConnectDevice(
    val id: String,
    val name: String,
    val platform: String,
    val lastSeenMs: Long,
    val isPlaybackDevice: Boolean,
    /** Heard only through the Connect relay (not on this network): "over the internet". */
    val overInternet: Boolean = false,
)

data class WindowsConnectDiscoveryStatus(
    val running: Boolean = false,
    val listenerReady: Boolean = false,
    val accountIdentityCount: Int = 0,
    val ipv4InterfaceCount: Int = 0,
    val sentPackets: Long = 0,
    val receivedPackets: Long = 0,
    val rejectedAccountPackets: Long = 0,
    val lastError: String? = null,
    /** The Connect relay (devices on other networks): "off", "connecting", "connected (n)" or why not. */
    val relay: String = "off",
)

data class ConnectPlaybackState(
    val ownerId: String,
    val track: Track?,
    val positionMs: Long,
    val durationMs: Long,
    val isPlaying: Boolean,
    val sentAtMs: Long,
    val queue: List<Track> = emptyList(),
    val queueIndex: Int = 0,
    val isReady: Boolean = false,
)

/** The desktop and Android: WindowsRecordLabsConnect in jvmSharedMain; iOS: its own version (iosMain). */
expect object WindowsRecordLabsConnect {
    fun takePlaybackOwnership(requestedTerm: Long? = null)
    fun takePlaybackOwnership(userStarted: Boolean)
    fun stop()
    fun start(
        accountKeys: Collection<String>,
        deviceName: String,
        playbackProvider: () -> ConnectPlaybackState?,
        onRemoteState: (ConnectPlaybackState) -> Unit,
        onCommand: (String, Long?, Track?) -> Unit,
        onLocalOwnership: ((handedTrackId: String?, handedPositionMs: Long?) -> Unit)? = null,
        onTransferStalled: ((String) -> Unit)? = null,
        onTakeoverPrepare: ((ConnectPlaybackState) -> Unit)? = null,
    )
    fun sendTrackReady(ownerId: String, trackId: String)
    fun sendQueueNext(track: Track) : Boolean
    fun sendQueueMove(trackId: String, toIndex: Int) : Boolean
    fun sendPlayTrack(track: Track) : Boolean
    fun sendCommand(command: String, positionMs: Long? = null) : Boolean
    fun selectPlaybackDevice(deviceId: String) : Boolean
    val remoteOwnerId: StateFlow<String?>
    fun notifyPlayerReady()
    val localDeviceId: String
    fun hasRemotePlaybackDevice() : Boolean
    val discoveryStatus: StateFlow<WindowsConnectDiscoveryStatus>
    val devices: StateFlow<List<ConnectDevice>>
    fun debugLog(message: String)
    fun configureBluetooth(sender: ((String) -> Unit)?)
    fun claimForLocalPlay()
    fun receiveBluetooth(raw: String)
}
