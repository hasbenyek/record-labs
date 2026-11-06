@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.AppPlatform
import com.recordlabs.desktop.platform.IoDispatcher
import com.recordlabs.desktop.platform.currentTimeMillis
import com.recordlabs.desktop.platform.digest
import com.recordlabs.desktop.platform.toHex
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import platform.Foundation.NSRecursiveLock
import kotlin.concurrent.Volatile

/**
 * Record Labs Connect on the iPhone: the same protocol, messages and decisions as the
 * desktop and Android (jvmSharedMain WindowsRecordLabsConnect.kt, ported here line
 * by line), so the iPhone lists the account's other devices, follows the one playing,
 * controls it and takes the sound over (or hands it on).
 *
 * Only the ways of reaching the other devices differ:
 *  - Same Wi-Fi: the TCP links (port 45923) the other devices already open to each
 *    other. The iPhone dials the Wi-Fi's addresses (and the others dial it): an
 *    iPhone app may not use the multicast and broadcast datagrams they also send
 *    (Apple gives that entitlement on request only), so there is no UDP here.
 *  - Elsewhere: the Connect relay over the internet (ConnectRelay.ios.kt).
 *
 * Threads: the JVM's executors become coroutine workers with the same ordering (the
 * packet handler and the sender each run one task at a time, in order).
 */
actual object WindowsRecordLabsConnect {
    private const val PORT = 45_922

    /** Messages that came through the Connect relay (no address, not this network). */
    private const val RELAY_PORT = -1

    /** A device heard on this network this recently is reached directly, not through the relay. */
    private const val LAN_FRESH_MS = 5_000L

    /** A device on another network, still on the relay, stays listed this long without a heartbeat. */
    private const val RELAY_PEER_GRACE_MS = 30_000L

    /** The song queue goes through the relay this often (or when it changed). */
    private const val RELAY_QUEUE_EVERY_MS = 10_000L

    /** A handoff to a device on another network is asked again this often until it takes over. */
    private const val RELAY_TRANSFER_RETRY_MS = 2_000L
    private const val TCP_PORT = 45_923
    private const val PROTOCOL = 3
    private val json = Json { ignoreUnknownKeys = true }

    /** One lock for Connect's shared lists (the JVM version locks each list on its own). */
    private val connectLock = NSRecursiveLock()
    @Suppress("UNUSED_PARAMETER", "NOTHING_TO_INLINE")
    private inline fun <T> locked(on: Any, block: () -> T): T {
        connectLock.lock()
        try { return block() } finally { connectLock.unlock() }
    }

    /** A JVM executor as coroutines: [execute] runs a task, [schedule] later, [scheduleAtFixedRate] again and again. */
    private class Worker(dispatcher: CoroutineDispatcher) {
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        fun execute(block: () -> Unit) { scope.launch { runCatching { block() } } }
        fun schedule(delayMs: Long, block: () -> Unit): Job = scope.launch {
            delay(delayMs)
            runCatching { block() }
        }
        fun scheduleAtFixedRate(initialMs: Long, periodMs: Long, block: () -> Unit): Job = scope.launch {
            delay(initialMs)
            while (isActive) {
                runCatching { block() }
                delay(periodMs)
            }
        }
        fun shutdown() = scope.cancel()
    }

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun limited(parallelism: Int): CoroutineDispatcher = IoDispatcher.limitedParallelism(parallelism)

    /** Small tasks and timers (the JVM's scheduled pool). */
    private val executor = Worker(limited(4))
    /** Each TCP link's reader, and the listener (they wait on the network). */
    private val linkExecutor = Worker(limited(24))
    /** The Wi-Fi scan: up to 16 short connects at once. */
    private val scanExecutor = Worker(limited(16))
    private val sendExecutor = Worker(limited(1))
    /** Every message is handled here, one at a time, in arrival order. */
    private val handleExecutor = Worker(limited(1))

    @Volatile private var running = false

    /** See the JVM version: the same message can arrive by TCP and through the relay. */
    private val recentPackets = LinkedHashMap<String, Long>()
    private const val DUPLICATE_WINDOW_MS = 20_000L
    private const val OWNER_GRACE_MS = 15_000L
    private const val PEER_GRACE_MS = 8_000L
    private const val SILENT_OWNER_MS = 4_000L
    private const val TAKEOVER_WAIT_MS = 8_000L
    private const val TAKEOVER_LOAD_WAIT_MS = 20_000L
    private const val TAKEOVER_RELOAD_AFTER_MS = 7_000L
    private const val TRANSFER_TIMEOUT_MS = 12_000L
    private const val HANDOFF_ANNOUNCE_MS = 10_000L
    private const val PICK_RETRY_MS = 1_000L
    private const val PICK_RETRIES = 3
    private const val PICK_GIVE_UP_MS = 4_500L
    private const val PICK_FAST_MS = 1_200L
    private const val LINK_FRESH_MS = 6_000L
    private const val LINK_DEAD_MS = 25_000L
    private const val LINK_WRITE_STALL_MS = 3_000L
    private const val TCP_RETRY_MS = 10_000L
    /** The Wi-Fi is scanned this often while a device of the account is not linked yet. */
    private const val SCAN_EVERY_MS = 20_000L
    @Volatile private var tcpServer: IosTcp.Server? = null
    private val tcpLinks = LockedLinkSet()
    private var heartbeat: Job? = null
    private var subnetScan: Job? = null
    private var accountFingerprints = emptySet<String>()
    private var deviceName = AppPlatform.deviceName
    /**
     * What this device announces itself as: "android" is the protocol's name for a
     * phone (PCs say "windows"); the device list shows [localOs].
     */
    private val localPlatform: String get() = "android"
    /** The real system, for the device list ("platform" stays the protocol's name). */
    private val localOs: String get() = "ios"
    /** A copy larger than this is sent without the queue (see the JVM version). */
    private const val MAX_UDP_PAYLOAD = 1_200
    @Volatile private var lastNetworkSignature = ""
    private fun helloPayload(): String = buildJsonObject {
        envelope("hello")
        put("name", deviceName)
        put("platform", localPlatform)
        put("os", localOs)
    }.toString()
    private val localId = WindowsPreferences.connectDeviceId()
    @Volatile private var ownerTerm = 1L
    /** The highest owner term any other device has announced. */
    @Volatile private var highestSeenTerm = 0L
    @Volatile private var localOwnerActive = false
    @Volatile private var localPlaybackInProgress = false
    @Volatile private var pendingTakeoverTerm: Long? = null
    @Volatile private var pendingTakeoverSince = 0L
    /**
     * A takeover of the song this player already has, but not ready yet (still buffering,
     * or paused before it finished loading): waited for a moment before loading it all
     * over again, which took seconds each time (finding the stream again, buffering).
     */
    @Volatile private var pendingTakeoverReload: ConnectPlaybackState? = null
    /** The song the followed output plays (its last state): a pick here takes over at once only on it. */
    @Volatile private var lastOwnerTrackId: String? = null
    @Volatile private var pendingTakeoverReloadAt = 0L
    /** The song a pending takeover waits for (a handed song being loaded here). */
    @Volatile private var pendingTakeoverTrackId: String? = null
    /** The output that asked this PC to take over (the takeover is for it only). */
    @Volatile private var pendingTakeoverFrom: String? = null
    @Volatile private var pendingTransferTarget: String? = null
    @Volatile private var pendingTransferSince = 0L
    @Volatile private var transferStallReported = false
    /** Highest term offered in a handoff: every new pick offers a higher one, so the last pick wins. */
    @Volatile private var lastOfferedTerm = 0L

    /**
     * This device was picked as the output on this device while another one plays: the
     * request to that device is repeated until it hands over (a lost packet left the
     * sound stuck there), and in the end this device takes the sound itself.
     */
    @Volatile private var pickedHereFrom: String? = null
    @Volatile private var pickedHereSince = 0L
    @Volatile private var pickedHereAttempts = 0

    /**
     * Until then, this device's heartbeats say it was picked as the output (a handoff),
     * so a device that missed the claim itself still gives the sound up when it hears
     * one of them, instead of both playing (or the sound staying where it was).
     */
    @Volatile private var handoffUntil = 0L

    /** When each device was last heard on this network (not through the relay). */
    private val lanHeardAt = LockedLongMap()
    @Volatile private var lastRelayQueueAt = 0L
    @Volatile private var lastRelayQueueSignature = 0
    /** The handoff request to a device on another network, repeated until it takes over (see [announce]). */
    @Volatile private var pendingTransferRequest: Pair<String, String>? = null
    @Volatile private var pendingTransferSentAt = 0L
    @Volatile private var relaySecretMissing = false

    private val tcpConnecting = LockedStringSet()
    private val tcpLastAttempt = LockedLongMap()
    /** This device became the output because another device asked it to (prepare_takeover / transfer). */
    private var localOwnershipHandler: ((String?, Long?) -> Unit)? = null
    /** Where the device handing the sound over was (its song and position) when it asked, by this clock. */
    private class HandedPosition(val trackId: String, val positionMs: Long, val playing: Boolean, val receivedAt: Long)
    @Volatile private var handedPosition: HandedPosition? = null
    /** A handoff to this device names a song this player is not on: the app loads it (silently). */
    private var takeoverPrepareHandler: ((ConnectPlaybackState) -> Unit)? = null
    /** A handoff this device started did not complete; the argument is the target's name. */
    private var transferStalledHandler: ((String) -> Unit)? = null

    /** A short diary of Connect's decisions (who owns the sound, handoffs): the Xcode console. */
    actual fun debugLog(message: String) {
        println("RLConnect [${deviceName}] $message")
    }

    private fun peerName(id: String?): String = id?.let { locked(seen) { seen[it]?.name } ?: it.take(8) } ?: "-"

    /** Called by the app once its player is prepared, so a deferred takeover can run. */
    actual fun notifyPlayerReady() {
        if (pendingTakeoverTerm == null) return
        handleExecutor.execute { completePendingTakeover(force = false) }
        // The player's "ready" can reach the app a moment after this call: checked again
        // shortly, instead of at the next heartbeat (up to 1.5 s later).
        for (delayMs in longArrayOf(200L, 600L)) {
            runCatching {
                executor.schedule(delayMs) {
                    if (pendingTakeoverTerm != null) handleExecutor.execute { completePendingTakeover(force = false) }
                }
            }
        }
    }

    private fun completePendingTakeover(force: Boolean) {
        val term = pendingTakeoverTerm ?: return
        // Only for the output that asked; if the sound has moved on since, drop it.
        val from = pendingTakeoverFrom
        if (from != null && _remoteOwnerId.value != null && _remoteOwnerId.value != from) {
            pendingTakeoverTerm = null
            return
        }
        val local = playbackProvider?.invoke()
        // Loading a handed song: only that song counts as ready (not the one before it).
        val onExpectedSong = pendingTakeoverTrackId == null || local?.track?.id == pendingTakeoverTrackId
        if (!force && (local?.isReady != true || !onExpectedSong)) return
        debugLog("takeover now (force=$force, ready=${local?.isReady}, song='${local?.track?.title}') term=$term")
        pendingTakeoverTerm = null
        pendingTakeoverTrackId = null
        takeRequestedOwnership(term)
    }

    /** Takes the output because a peer asked for it, and lets the app start sound here. */
    private fun takeRequestedOwnership(requestedTerm: Long?) {
        pendingTakeoverTerm = null
        pendingTakeoverTrackId = null
        takePlaybackOwnership(requestedTerm, handoff = true)
        // The silent mirror runs a little behind the device it follows (up to about a
        // second): the app jumps to where that device is now, so the music carries on
        // from the same moment instead of repeating the last second.
        val handed = handedPosition?.takeIf { currentTimeMillis() - it.receivedAt < 30_000L }
        handedPosition = null
        val estimate = handed?.let { if (it.playing) it.positionMs + (currentTimeMillis() - it.receivedAt) else it.positionMs }
        localOwnershipHandler?.invoke(handed?.trackId, estimate)
    }

    private fun requestTakeover(requestedTerm: Long?, from: String, expectedTrackId: String? = null) {
        val currentOwner = _remoteOwnerId.value
        debugLog("takeover requested by ${peerName(from)} term=$requestedTerm currentOwner=${peerName(currentOwner)} ready=${playbackProvider?.invoke()?.isReady}")
        if (currentOwner != null && currentOwner != from) {
            // Asked by a device that is itself only a mirror (it picked this PC from
            // its list). Taking the sound here would leave the real output playing
            // too, so ask the real output to hand it over instead.
            requestTransferFrom(currentOwner, localId)
            return
        }
        val local = playbackProvider?.invoke()
        if (expectedTrackId == null && local?.isReady == true) {
            takeRequestedOwnership(requestedTerm)
        } else {
            // Not ready, or the handed song is still to be loaded here: take over once
            // it is (notifyPlayerReady), or after a while in any case.
            pendingTakeoverTerm = requestedTerm ?: (ownerTerm + 1L)
            pendingTakeoverSince = currentTimeMillis()
            pendingTakeoverFrom = from
            pendingTakeoverTrackId = expectedTrackId
        }
    }

    private var playbackProvider: (() -> ConnectPlaybackState?)? = null
    private var remoteStateHandler: ((ConnectPlaybackState) -> Unit)? = null
    private var commandHandler: ((String, Long?, Track?) -> Unit)? = null
    private var bluetoothSender: ((String) -> Unit)? = null
    private val seen = linkedMapOf<String, ConnectDevice>()
    private val _devices = MutableStateFlow<List<ConnectDevice>>(emptyList())
    actual val devices: StateFlow<List<ConnectDevice>> = _devices.asStateFlow()
    @Volatile private var sentPackets = 0L
    @Volatile private var receivedPackets = 0L
    @Volatile private var rejectedAccountPackets = 0L
    private val _discoveryStatus = MutableStateFlow(WindowsConnectDiscoveryStatus())
    actual val discoveryStatus: StateFlow<WindowsConnectDiscoveryStatus> = _discoveryStatus.asStateFlow()
    private val _remoteOwnerId = MutableStateFlow<String?>(null)
    actual val remoteOwnerId: StateFlow<String?> = _remoteOwnerId.asStateFlow()
    actual val localDeviceId: String get() = localId

    actual fun start(
        accountKeys: Collection<String>,
        deviceName: String,
        playbackProvider: () -> ConnectPlaybackState?,
        onRemoteState: (ConnectPlaybackState) -> Unit,
        onCommand: (String, Long?, Track?) -> Unit,
        onLocalOwnership: ((handedTrackId: String?, handedPositionMs: Long?) -> Unit)?,
        onTransferStalled: ((String) -> Unit)?,
        onTakeoverPrepare: ((ConnectPlaybackState) -> Unit)?,
    ) {
        val fingerprints = accountKeys.map(String::trim).filter(String::isNotBlank)
            .map(::fingerprint).toSet()
        if (fingerprints.isEmpty()) {
            stop()
            _discoveryStatus.value = WindowsConnectDiscoveryStatus(
                lastError = "Google account identity is unavailable",
            )
            return
        }
        this.playbackProvider = playbackProvider
        this.remoteStateHandler = onRemoteState
        this.commandHandler = onCommand
        this.localOwnershipHandler = onLocalOwnership
        this.transferStalledHandler = onTransferStalled
        this.takeoverPrepareHandler = onTakeoverPrepare
        this.deviceName = deviceName.ifBlank { AppPlatform.deviceName }
        if (running && fingerprints == accountFingerprints) return
        stop()
        accountFingerprints = fingerprints
        running = true
        sentPackets = 0
        receivedPackets = 0
        rejectedAccountPackets = 0
        _discoveryStatus.value = WindowsConnectDiscoveryStatus(
            running = true,
            accountIdentityCount = accountFingerprints.size,
            ipv4InterfaceCount = IosTcp.localIpv4().size,
        )
        lastNetworkSignature = networkSignature()
        linkExecutor.execute(::tcpAcceptLoop)
        // Devices of this account on other networks, through the Connect relay.
        val relaySecret = ConnectRelay.accountSecret(accountKeys)
        relaySecretMissing = relaySecret == null
        if (relaySecret != null) {
            ConnectRelay.start(
                relaySecret,
                localId,
                onMessage = { raw, _ -> dispatch(raw, null, RELAY_PORT) },
                onChanged = {
                    publishPacketCounts()
                    // A device just came online there: say hello now (with the queue).
                    lastRelayQueueAt = 0L
                    if (ConnectRelay.connected && running) runCatching { executor.execute(::announce) }
                },
            )
        } else {
            ConnectRelay.stop()
        }
        heartbeat = executor.scheduleAtFixedRate(0L, 1_500L, ::announce)
        // The Wi-Fi's other devices (no multicast on an iPhone): found by their TCP port,
        // soon after the start and then while one of the account's devices is not linked.
        subnetScan = executor.scheduleAtFixedRate(1_000L, SCAN_EVERY_MS, ::doSubnetScan)
    }

    actual fun stop() {
        running = false
        ConnectRelay.stop()
        lanHeardAt.clear()
        heartbeat?.cancel()
        heartbeat = null
        subnetScan?.cancel()
        subnetScan = null
        tcpServer?.close()
        tcpServer = null
        tcpLinks.toList().forEach(TcpLink::close)
        tcpLinks.clear()
        locked(seen) {
            seen.clear()
        }
        _devices.value = emptyList()
        _remoteOwnerId.value = null
        localOwnerActive = false
        pendingTakeoverTerm = null
        pendingTakeoverFrom = null
        pendingTransferTarget = null
        tcpLastAttempt.clear()
        locked(recentPackets) { recentPackets.clear() }
        _discoveryStatus.value = _discoveryStatus.value.copy(running = false, listenerReady = false)
    }

    actual fun takePlaybackOwnership(requestedTerm: Long?) = takePlaybackOwnership(requestedTerm, handoff = false)

    /**
     * A song started on this device. [userStarted] (it plays at once, picked by the
     * user): the claim is a handoff, so another device playing on its own goes quiet
     * and follows this one (one output at a time, like Spotify Connect). A song only
     * loaded paused (restored at launch) never takes the sound from another device.
     */
    actual fun takePlaybackOwnership(userStarted: Boolean) {
        if (!running) return
        handleExecutor.execute { takePlaybackOwnership(null, handoff = userStarted) }
    }

    /** Play pressed on this device while it is not following another one: it becomes the only output. */
    actual fun claimForLocalPlay() {
        if (!running || _remoteOwnerId.value != null) return
        handleExecutor.execute {
            if (_remoteOwnerId.value == null) takePlaybackOwnership(null, handoff = true)
        }
    }

    private fun takePlaybackOwnership(requestedTerm: Long?, handoff: Boolean) {
        // Above every term heard from any device: each device counts its own terms, and a
        // claim below another device's count was refused there (both kept playing).
        ownerTerm = maxOf(requestedTerm ?: 0L, ownerTerm + 1L, highestSeenTerm + 1L)
        pickedHereFrom = null
        if (handoff) handoffUntil = currentTimeMillis() + HANDOFF_ANNOUNCE_MS
        debugLog("this device is the output now (term=$ownerTerm handoff=$handoff, was following ${peerName(_remoteOwnerId.value)})")
        _remoteOwnerId.value = null
        localOwnerActive = true
        pendingTakeoverTerm = null
        pendingTransferTarget = null
        sendOwnershipClaim(handoff)
        announce()
    }

    /**
     * [handoff]: the claim answers a pick of this device as the output. Such a
     * claim with a newer term wins even over a device that is playing (that
     * device was the previous pick), so the user's last pick always ends up as
     * the only output. Claims without it (an app just opened) never take sound
     * away from a playing device.
     */
    private fun sendOwnershipClaim(handoff: Boolean = false) {
        debugLog("sending ownership claim term=$ownerTerm handoff=$handoff over ${tcpLinks.size} TCP link(s) + UDP")
        send(buildJsonObject {
            envelope("ownership")
            put("owner", localId)
            put("ownerTerm", ownerTerm)
            if (handoff) put("handoff", true)
        }.toString())
    }

    actual fun selectPlaybackDevice(deviceId: String): Boolean {
        debugLog("picked ${if (deviceId == localId) "this device" else peerName(deviceId)} as output; current output ${peerName(_remoteOwnerId.value) .takeIf { _remoteOwnerId.value != null } ?: "this device"}")
        pickedHereFrom = null
        if (deviceId == localId) {
            // Let the current output initiate the handoff. A local ownership
            // claim from the controller is intentionally rejected while the
            // host is playing, otherwise both devices can remain audible.
            // The output may also be one this device lost track of for a moment
            // (heartbeats late): the device the list shows playing.
            val currentOwner = _remoteOwnerId.value ?: locked(seen) {
                seen.values.filter { it.id != localId && it.isPlaybackDevice }.maxByOrNull { it.lastSeenMs }?.id
            }
            if (currentOwner != null) {
                pickedHereFrom = currentOwner
                pickedHereSince = currentTimeMillis()
                pickedHereAttempts = 1
                requestTransferFrom(currentOwner, deviceId)
                // Checked again soon, not only with the next heartbeat (see retryPickedHere).
                runCatching {
                    executor.schedule(PICK_FAST_MS + 100L) { pickedHereFrom?.let(::retryPickedHere) }
                }
                return true
            }
            // Picked by the user: wins over a device playing on its own.
            handleExecutor.execute { takePlaybackOwnership(null, handoff = true) }
            return true
        }
        locked(seen) { if (seen[deviceId] == null) return false }
        val currentOwner = _remoteOwnerId.value
        if (currentOwner != null) {
            // This PC is only a mirror: the device playing the sound must make the
            // handoff, otherwise it keeps playing next to the new output.
            if (currentOwner != deviceId) requestTransferFrom(currentOwner, deviceId)
            return true
        }
        return handOverTo(deviceId)
    }

    /** This PC plays the sound and asks [deviceId] to take it over. */
    private fun handOverTo(deviceId: String): Boolean {
        // Do not abdicate immediately (deferred handoff).
        // Request the target to take over with a strictly greater term
        // so we gracefully abdicate when they broadcast their new state.
        val requestedTerm = maxOf(ownerTerm, lastOfferedTerm) + 1L
        lastOfferedTerm = requestedTerm
        debugLog("handing over to ${peerName(deviceId)} term=$requestedTerm")
        pendingTransferTarget = deviceId
        pendingTransferSince = currentTimeMillis()
        transferStallReported = false
        // Defer handoff: Wait until the target device acknowledges it is ready.
        // The request carries the song, position and queue: the target may not be
        // mirroring this song (it was playing something else, or was just opened),
        // and then loads it itself instead of taking over with nothing to play.
        val state = playbackProvider?.invoke()
        val fullRequest = buildJsonObject {
            envelope("command")
            put("target", deviceId)
            put("command", "prepare_takeover")
            put("requestedOwnerTerm", requestedTerm)
            if (state != null) putPlayback(state, includeQueue = true)
        }
        val full = fullRequest.toString()
        // The short copy is the same message (same sentAt) without the queue, so the
        // receiver sees both as one request.
        val short = if (full.length > MAX_UDP_PAYLOAD) {
            JsonObject(fullRequest.filterKeys { it != "queue" && it != "queueIndex" }).toString()
        } else full
        sendTo(deviceId, full, udpPayload = short)
        // Over the internet a request can be lost in a reconnect: it is repeated (the
        // very same message, so a copy that did arrive is not applied twice).
        pendingTransferRequest = full to short
        pendingTransferSentAt = currentTimeMillis()
        return true
    }

    /** See [pickedHereFrom]: called with every heartbeat until the sound is here. */
    private fun retryPickedHere(from: String) {
        locked(this) { retryPickedHereLocked(from) }
    }

    private fun retryPickedHereLocked(from: String) {
        val owner = _remoteOwnerId.value
        // Handed over (this device plays now), or the sound went elsewhere meanwhile.
        if ((owner == null && localOwnerActive) || (owner != null && owner != from)) {
            pickedHereFrom = null
            return
        }
        // The playing device answered: the takeover is under way here.
        if (pendingTakeoverTerm != null) return
        val waited = currentTimeMillis() - pickedHereSince
        // A handoff takes a fraction of a second when both devices hear each other. Not
        // answered by then while this device already mirrors the song, ready to play: it
        // takes the sound at once. The playing device often hears this one less well than
        // the other way round (a PC firewall, WSA), and the switch to the phone took ~8 s.
        val local = playbackProvider?.invoke()
        if (waited >= PICK_FAST_MS && local?.isReady == true && local.track != null &&
            local.track.id == lastOwnerTrackId
        ) {
            debugLog("${peerName(from)} has not answered yet; '${local.track.title}' is ready here: taking the sound here")
            pickedHereFrom = null
            takeRequestedOwnership(null)
            return
        }
        if (pickedHereAttempts < PICK_RETRIES) {
            if (waited >= pickedHereAttempts * PICK_RETRY_MS) {
                pickedHereAttempts += 1
                debugLog("${peerName(from)} has not handed over yet: asking again (${pickedHereAttempts}/$PICK_RETRIES)")
                requestTransferFrom(from, localId)
            }
            return
        }
        if (waited < PICK_GIVE_UP_MS) return
        debugLog("${peerName(from)} never handed over: taking the sound here (picked by the user)")
        pickedHereFrom = null
        takeRequestedOwnership(null)
    }

    /** Requests an atomic handoff from [currentOwner] to [deviceId]. */
    private fun requestTransferFrom(currentOwner: String, deviceId: String) {
        debugLog("asking ${peerName(currentOwner)} to hand over to ${if (deviceId == localId) "this device" else peerName(deviceId)}")
        sendTo(currentOwner, buildJsonObject {
            envelope("command")
            put("target", currentOwner)
            put("command", "transfer_playback")
            put("outputDevice", deviceId)
        }.toString())
    }

    actual fun configureBluetooth(sender: ((String) -> Unit)?) {
        bluetoothSender = sender
    }

    actual fun receiveBluetooth(raw: String) { dispatch(raw, null, PORT) }

    actual fun sendCommand(command: String, positionMs: Long?): Boolean {
        val target = _remoteOwnerId.value ?: return false
        sendTo(target, buildJsonObject {
            envelope("command")
            put("target", target)
            put("command", command)
            positionMs?.let { put("positionMs", it) }
        }.toString())
        return true
    }

    actual fun hasRemotePlaybackDevice(): Boolean = _remoteOwnerId.value != null

    actual fun sendPlayTrack(track: Track): Boolean {
        val target = _remoteOwnerId.value ?: return false
        sendTo(target, buildJsonObject {
            envelope("command")
            put("target", target)
            put("command", "play_track")
            put("trackId", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("artwork", track.artworkUrl.orEmpty())
            put("durationMs", track.durationMs)
        }.toString())
        return true
    }

    /** Adds a song immediately after the host's current queue item. */
    actual fun sendQueueNext(track: Track): Boolean {
        val target = _remoteOwnerId.value ?: return false
        sendTo(target, buildJsonObject {
            envelope("command")
            put("target", target)
            put("command", "queue_next")
            put("trackId", track.id)
            put("title", track.title)
            put("artist", track.artist)
            put("album", track.album)
            put("artwork", track.artworkUrl.orEmpty())
            put("durationMs", track.durationMs)
        }.toString())
        return true
    }

    fun sendPlayQueueIndex(index: Int): Boolean {
        val target = _remoteOwnerId.value ?: return false
        sendTo(target, buildJsonObject {
            envelope("command")
            put("target", target)
            put("command", "play_queue_index")
            put("positionMs", index.toLong())
        }.toString())
        return true
    }

    /** Reorders the active output queue using a stable track id. */
    actual fun sendQueueMove(trackId: String, toIndex: Int): Boolean {
        val target = _remoteOwnerId.value ?: return false
        sendTo(target, buildJsonObject {
            envelope("command")
            put("target", target)
            put("command", "queue_move")
            put("trackId", trackId)
            put("positionMs", toIndex.toLong())
        }.toString())
        return true
    }

    /** Acknowledges that this silent controller has prepared the host track. */
    actual fun sendTrackReady(ownerId: String, trackId: String) {
        if (ownerId.isBlank() || trackId.isBlank()) return
        sendTo(ownerId, buildJsonObject {
            envelope("command")
            put("target", ownerId)
            put("command", "track_ready")
            put("trackId", trackId)
        }.toString())
    }

    /** The heartbeat runs on the packet-handling thread, so ownership is only ever changed there. */
    private fun announce() {
        if (!running) return
        runCatching { handleExecutor.execute(::announceNow) }
    }

    private fun announceNow() {
        if (!running) return
        // Wi-Fi came up, reconnected or changed address (phones do this often):
        // join the discovery group again and use a fresh outbound socket.
        val signature = networkSignature()
        if (signature != lastNetworkSignature) {
            lastNetworkSignature = signature
            _discoveryStatus.value = _discoveryStatus.value.copy(ipv4InterfaceCount = IosTcp.localIpv4().size)
            // A new network (Wi-Fi to mobile data, another Wi-Fi): reach the relay again now,
            // and look for the devices on this Wi-Fi.
            ConnectRelay.networkChanged()
            executor.execute(::doSubnetScan)
        }
        // Picked as the output here, but the playing device has not handed over yet:
        // ask again (the request or its answer may have been lost), then take it.
        pickedHereFrom?.let { from -> retryPickedHere(from) }
        // Deferred takeover (requested while this player was still loading).
        if (pendingTakeoverTerm != null) {
            pendingTakeoverReload?.let { handed ->
                if (currentTimeMillis() >= pendingTakeoverReloadAt) {
                    pendingTakeoverReload = null
                    if (playbackProvider?.invoke()?.isReady != true) {
                        debugLog("still not ready: loading '${handed.track?.title}' again for the takeover")
                        takeoverPrepareHandler?.invoke(handed)
                    }
                }
            }
            val waited = currentTimeMillis() - pendingTakeoverSince
            // Loading a handed song from scratch (stream lookup and buffering) takes longer.
            val limit = if (pendingTakeoverTrackId != null) TAKEOVER_LOAD_WAIT_MS else TAKEOVER_WAIT_MS
            completePendingTakeover(force = waited >= limit)
        }
        pendingTransferTarget?.let { target ->
            // Report a slow handoff once, but keep accepting the target's claim:
            // it may still become ready (and must then not be refused while this
            // PC keeps playing).
            if (!transferStallReported && currentTimeMillis() - pendingTransferSince >= TRANSFER_TIMEOUT_MS) {
                transferStallReported = true
                val name = locked(seen) { seen[target]?.name } ?: "the device"
                transferStalledHandler?.invoke(name)
            }
            val request = pendingTransferRequest
            val now = currentTimeMillis()
            val overInternet = locked(seen) { seen[target]?.overInternet == true }
            if (request != null && overInternet && now - pendingTransferSince < TRANSFER_TIMEOUT_MS &&
                now - pendingTransferSentAt >= RELAY_TRANSFER_RETRY_MS
            ) {
                pendingTransferSentAt = now
                sendTo(target, request.first, udpPayload = request.second)
            }
        } ?: run { pendingTransferRequest = null }
        // Links that went quiet (peer gone, Wi-Fi switched) or stopped reading are dropped.
        val nowMs = currentTimeMillis()
        tcpLinks.toList().forEach { link ->
            val stalledWrite = link.writeStartedAt.let { it > 0L && nowMs - it > LINK_WRITE_STALL_MS }
            if (stalledWrite || nowMs - link.lastRxAt > LINK_DEAD_MS) link.close()
        }
        val state = playbackProvider?.invoke()
        if (state?.isPlaying == true && _remoteOwnerId.value == null && !localOwnerActive) {
            ownerTerm += 1L
            localOwnerActive = true
        }
        val localOwnsPlayback = state?.track != null && _remoteOwnerId.value == null && localOwnerActive
        fun payload(includeQueue: Boolean) = buildJsonObject {
            envelope(if (localOwnsPlayback) "state" else "hello")
            put("name", deviceName)
            put("platform", localPlatform)
            put("os", localOs)
            if (state != null && localOwnsPlayback) {
                put("owner", localId)
                put("ownerTerm", ownerTerm)
                if (currentTimeMillis() < handoffUntil) put("handoff", true)
                put("trackId", state.track?.id.orEmpty())
                put("title", state.track?.title.orEmpty())
                put("artist", state.track?.artist.orEmpty())
                put("album", state.track?.album.orEmpty())
                put("artwork", state.track?.artworkUrl.orEmpty())
                put("positionMs", state.positionMs)
                put("durationMs", state.durationMs)
                put("playing", state.isPlaying)
                if (includeQueue) {
                    put("queueIndex", state.queueIndex)
                    put("queue", JsonArray(state.queue.take(100).map { track ->
                        buildJsonObject {
                            put("trackId", track.id)
                            put("title", track.title)
                            put("artist", track.artist)
                            put("album", track.album)
                            put("artwork", track.artworkUrl.orEmpty())
                            put("durationMs", if (track.id == state.track?.id) state.durationMs else track.durationMs)
                        }
                    }))
                }
            }
        }.toString()
        // The queue can be tens of KB: as one broadcast datagram it was cut at the
        // receiver's buffer or lost to Wi-Fi fragmentation. TCP links get everything;
        // the UDP copy only the song and position (the receiver keeps its queue).
        val full = payload(includeQueue = true)
        val short = if (full.length > MAX_UDP_PAYLOAD) payload(includeQueue = false) else full
        // Over the internet the queue goes when it changed and every few seconds; the
        // heartbeats between are a fraction of the size (mobile data, the relay's limits).
        val queueSignature = state?.takeIf { localOwnsPlayback }?.let { it.queue.map(Track::id).hashCode() * 31 + it.queueIndex } ?: 0
        val nowMs2 = currentTimeMillis()
        val relayFull = queueSignature != lastRelayQueueSignature || nowMs2 - lastRelayQueueAt >= RELAY_QUEUE_EVERY_MS
        if (relayFull) {
            lastRelayQueueSignature = queueSignature
            lastRelayQueueAt = nowMs2
        }
        send(full, udpPayload = short, relayPayload = if (relayFull) full else short, heartbeat = true)
        val cutoff = currentTimeMillis() - PEER_GRACE_MS
        val relayCutoff = currentTimeMillis() - RELAY_PEER_GRACE_MS
        // The device playing the sound gets longer: a phone's Wi-Fi power saving
        // can hold its heartbeats for several seconds, and dropping the owner
        // too early made both devices audible.
        val ownerCutoff = currentTimeMillis() - OWNER_GRACE_MS
        val currentOwner = _remoteOwnerId.value
        var expiredOwner: String? = null
        locked(seen) {
            val connectedTcpPeers = tcpLinks.mapNotNullTo(mutableSetOf()) { it.peerId }
            // Once the first account-validated packet has identified a TCP
            // link, the open connection is a stronger liveness signal than a
            // lossy UDP heartbeat. Keep that peer visible until the socket is
            // actually closed.
            seen.entries.toList().filter {
                // A device on another network that is still on the relay (or just
                // reconnecting to it) is kept longer; one that left goes as before.
                val viaRelay = it.value.overInternet && ConnectRelay.present(it.key)
                val limit = when {
                    viaRelay -> minOf(relayCutoff, if (it.key == currentOwner) ownerCutoff else cutoff)
                    it.key == currentOwner -> ownerCutoff
                    else -> cutoff
                }
                it.key !in connectedTcpPeers && it.value.lastSeenMs < limit
            }.forEach { seen.remove(it.key) }
            _remoteOwnerId.value?.takeIf { it !in seen }?.let { expiredOwner = it }
            _devices.value = stableOrder(seen.values)
        }
        expiredOwner?.let(::clearRemoteOwnership)
    }

    /**
     * The device list in a fixed order: by name, then platform and id. It was sorted
     * by the last heartbeat, so with three or more devices the rows swapped places
     * every second (each heartbeat moved its device to the top) and a tap could land
     * on another device than the one aimed at. The same order on every device and
     * every launch; the selected one is marked, not moved.
     */
    private fun stableOrder(devices: Collection<ConnectDevice>): List<ConnectDevice> =
        devices.sortedWith(
            compareBy<ConnectDevice>(
                { it.name.trim().lowercase() },
                { it.platform.lowercase() },
                { it.id },
            ),
        )

    private fun kotlinx.serialization.json.JsonObjectBuilder.envelope(type: String) {
        put("v", PROTOCOL)
        put("type", type)
        put("account", accountFingerprints.firstOrNull().orEmpty())
        put("accounts", JsonArray(accountFingerprints.sorted().map(::JsonPrimitive)))
        put("device", localId)
        put("sentAt", currentTimeMillis())
    }

    /**
     * [relayPayload]: what goes through the relay (heartbeats: the queue only now and
     * then); [heartbeat]: a heartbeat, not kept for later when the relay is down for a
     * moment (commands and handoffs are, see [ConnectRelay.send]).
     */
    private fun send(payload: String, udpPayload: String = payload, relayPayload: String = payload, heartbeat: Boolean = false) {
        if (!running) return
        sendExecutor.execute {
            if (!running) return@execute
            // The Wi-Fi's devices: their TCP links (the whole message).
            tcpLinks.toList().forEach { if (it.send(payload)) sentPackets += 1 }
            // The account's devices on other networks (the relay).
            runCatching { relayIfNeeded(relayPayload, important = !heartbeat, fallback = udpPayload) }
            bluetoothSender?.invoke(udpPayload)
            publishPacketCounts()
        }
    }

    /**
     * A message meant for one peer (commands, track_ready, takeover) goes over
     * that peer's TCP link when there is one: a single reliable copy. Without a
     * link it falls back to every transport like other messages.
     */
    private fun sendTo(target: String, payload: String, udpPayload: String = payload) {
        if (!running) return
        // The most recently active link to that peer; a stale one (half-open after a
        // Wi-Fi change) would swallow the message, so then every transport is used.
        val now = currentTimeMillis()
        val link = tcpLinks.filter { it.peerId == target && now - it.lastRxAt <= LINK_FRESH_MS }
            .maxByOrNull { it.lastRxAt }
        if (link == null || !link.send(payload)) send(payload, udpPayload)
    }

    /**
     * The Wi-Fi's other devices dial this port (as they dial each other): each one that
     * connects gets a link like the ones this iPhone opens (see [doSubnetScan]).
     */
    private fun tcpAcceptLoop() {
        var server: IosTcp.Server? = null
        for (attempt in 0 until 10) {
            if (!running) return
            server = IosTcp.listen(TCP_PORT)
            if (server != null) break
            platform.posix.usleep(500_000u)
        }
        val bound = server ?: run {
            _discoveryStatus.value = _discoveryStatus.value.copy(lastError = "Connect TCP listener failed")
            return
        }
        if (!running) {
            bound.close()
            return
        }
        tcpServer?.takeIf { it !== bound }?.close()
        tcpServer = bound
        _discoveryStatus.value = _discoveryStatus.value.copy(listenerReady = true, lastError = null)
        while (running && !bound.isClosed) {
            val client = bound.accept(1_000) ?: continue
            if (!running) {
                client.close()
                break
            }
            val link = TcpLink(client)
            tcpLinks.add(link)
            link.start()
        }
    }

    /**
     * Finds the account's devices on this Wi-Fi: every address of its /24 is tried on
     * the TCP port (as the desktop and Android do when datagrams do not get through).
     * A device that answers gets a hello at once (it checks the account and lists this
     * iPhone) and keeps the link. Runs while a device is unknown or not linked yet.
     */
    @Volatile private var lastFullScanAt = 0L

    private fun doSubnetScan() {
        if (!running) return
        val linkedPeers = tcpLinks.toList().mapNotNullTo(mutableSetOf()) { it.peerId }
        val now = currentTimeMillis()
        val (lanUnlinked, internetOnly) = locked(seen) {
            val unlinked = seen.values.filter { it.id !in linkedPeers }
            (seen.isEmpty() || unlinked.any { !it.overInternet }) to unlinked.any { it.overInternet }
        }
        // A device heard only through the relay may still be on this Wi-Fi: looked for
        // now and then (a scan is 254 short connects), not every time.
        if (!lanUnlinked && !(internetOnly && now - lastFullScanAt > 90_000L)) return
        lastFullScanAt = now
        val linkedHosts = tcpLinks.toList().mapTo(mutableSetOf()) { it.remoteHost }
        val own = IosTcp.localIpv4()
        val selfAddresses = own.mapTo(mutableSetOf()) { it.address }
        val targets = own
            .filter { !it.address.endsWith(".1") }
            .flatMap { mine ->
                val prefix = mine.address.substringBeforeLast('.')
                (1..254).map { "$prefix.$it" }
            }
            .distinct()
            .filter { it !in selfAddresses && it !in linkedHosts }
        targets.forEach { target ->
            scanExecutor.execute {
                if (!running) return@execute
                if (tcpLinks.toList().any { it.remoteHost == target }) return@execute
                val socket = IosTcp.connect(target, TCP_PORT, 400) ?: return@execute
                if (!running) {
                    socket.close()
                    return@execute
                }
                val link = TcpLink(socket)
                tcpLinks.add(link)
                // Hello right away, so the other device checks the account and lists this one.
                link.send(helloPayload())
                link.start()
            }
        }
    }

    /** One TCP link: a line of JSON per message, each way (see the JVM version). */
    private class TcpLink(private val socket: IosTcp.Link, knownPeer: String? = null) {
        val remoteHost: String = socket.remoteHost
        // Writes run on the link's own queue: a peer that stops reading blocks only this link.
        private val writeQueue = Worker(limited(1))
        @Volatile private var closed = false
        @Volatile var peerId: String? = knownPeer
            private set
        @Volatile var lastRxAt: Long = currentTimeMillis()
            private set
        @Volatile var writeStartedAt: Long = 0L
            private set

        fun start() {
            linkExecutor.execute {
                try {
                    while (running && !socket.isClosed) {
                        val line = socket.readLine() ?: break
                        lastRxAt = currentTimeMillis()
                        if (line.isNotBlank()) {
                            val candidateId = runCatching {
                                json.parseToJsonElement(line).jsonObject["device"]
                                    ?.jsonPrimitive?.contentOrNull
                            }.getOrNull()
                            handleExecutor.execute {
                                handle(line, remoteHost, TCP_PORT)
                                if (peerId == null && !candidateId.isNullOrBlank() && locked(seen) { candidateId in seen }) {
                                    peerId = candidateId
                                }
                            }
                        }
                    }
                } catch (_: Throwable) {
                } finally {
                    close()
                }
            }
        }

        /** Queues [payload]; false when the link is already closed. */
        fun send(payload: String): Boolean {
            if (closed) return false
            writeQueue.execute {
                if (closed) return@execute
                writeStartedAt = currentTimeMillis()
                try {
                    if (!socket.writeLine(payload)) close()
                } finally {
                    writeStartedAt = 0L
                }
            }
            return true
        }

        fun close() {
            if (closed) return
            closed = true
            tcpLinks.remove(this)
            socket.close()
            writeQueue.shutdown()
        }
    }

    private fun dispatch(raw: String, sourceAddress: String?, sourcePort: Int) {
        if (!running) return
        handleExecutor.execute { handle(raw, sourceAddress, sourcePort) }
    }

    /** True the first time this exact message is seen (see [recentPackets]). */
    private fun firstCopy(raw: String): Boolean {
        val now = currentTimeMillis()
        val key = "${raw.length}:${raw.hashCode()}:${raw.takeLast(48)}"
        locked(recentPackets) {
            val iterator = recentPackets.entries.iterator()
            while (iterator.hasNext()) {
                val entry = iterator.next()
                if (now - entry.value > DUPLICATE_WINDOW_MS || recentPackets.size > 1_024) iterator.remove() else break
            }
            if (recentPackets.containsKey(key)) return false
            recentPackets[key] = now
            return true
        }
    }

    private fun handle(raw: String, sourceAddress: String? = null, sourcePort: Int = PORT) {
        val value = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return
        if (value["v"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() != PROTOCOL) return
        val sender = value["device"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (sender.isBlank() || sender == localId) return
        receivedPackets += 1
        publishPacketCounts()
        val remoteFingerprints = buildSet {
            value["account"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)?.let(::add)
            (value["accounts"] as? JsonArray)?.mapNotNullTo(this) { it.jsonPrimitive.contentOrNull }
        }
        if (remoteFingerprints.none(accountFingerprints::contains)) {
            rejectedAccountPackets += 1
            publishPacketCounts()
            return
        }
        val type = value["type"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val now = currentTimeMillis()
        value["ownerTerm"]?.jsonPrimitive?.contentOrNull?.toLongOrNull()?.let { term ->
            if (term > highestSeenTerm) highestSeenTerm = term
        }
        val owner = value["owner"]?.jsonPrimitive?.contentOrNull
        if (sourcePort != RELAY_PORT) lanHeardAt[sender] = now
        val overInternet = sourcePort == RELAY_PORT && now - (lanHeardAt[sender] ?: 0L) > LAN_FRESH_MS
        val isNewPeer = locked(seen) {
            val newPeer = sender !in seen
            // Commands and ownership claims carry no name: keep the one the
            // device announced instead of flashing "Record Labs device".
            val known = seen[sender]
            seen[sender] = ConnectDevice(
                id = sender,
                name = value["name"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: known?.name ?: "Record Labs device",
                platform = (value["os"] ?: value["platform"])?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: known?.platform.orEmpty(),
                lastSeenMs = now,
                isPlaybackDevice = owner == sender,
                overInternet = overInternet,
            )
            _devices.value = stableOrder(seen.values)
            newPeer
        }
        if (isNewPeer) executor.execute(::announce)
        // Presence and addresses above are updated for every copy; the message
        // itself is applied once.
        if (!firstCopy(raw)) return
        // One command can come as two different texts: the full one (TCP, relay) and a
        // short one without the queue (a UDP datagram). Now that commands also go
        // through the relay on the same network, both arrived, and a takeover was
        // started twice: the second start reloaded the song the phone was still
        // loading, and the sound stayed on the PC.
        if (type == "command") {
            val commandKey = "command:$sender:${value["command"]?.jsonPrimitive?.contentOrNull}:${value["sentAt"]?.jsonPrimitive?.contentOrNull}"
            if (!firstCopy(commandKey)) return
        }
        when (type) {
            // Presence packets can arrive after ownership packets because UDP,
            // TCP, and Bluetooth are independent transports. A delayed hello is
            // not an ownership release; only an explicit handoff or peer expiry
            // may change the selected output.
            "hello" -> Unit
            "ownership" -> if (owner == sender) {
                val incomingTerm = value["ownerTerm"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: return
                val handoff = value["handoff"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val wins = ownerWins(sender, incomingTerm, handoff = handoff)
                debugLog("ownership claim from ${peerName(sender)} term=$incomingTerm handoff=$handoff local term=$ownerTerm -> ${if (wins) "follow it (mute here)" else "ignored"}")
                if (wins) {
                    ownerTerm = incomingTerm
                    _remoteOwnerId.value = sender
                    pendingTransferTarget = null
                    // A device claimed the sound: any takeover this PC was waiting
                    // for is outdated (the user picked another output meanwhile).
                    pendingTakeoverTerm = null
                    localOwnerActive = false
                    remoteStateHandler?.invoke(
                        ConnectPlaybackState(sender, null, 0L, 0L, false, now),
                    )
                }
            }
            "state" -> {
                if (owner != sender) return
                val incomingTerm = value["ownerTerm"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
                val claimantPlaying = value["playing"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
                // Just picked as the output (a handoff): wins with a newer term even here,
                // where the sound may still be playing because the claim itself was missed.
                val stateHandoff = value["handoff"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true
                val follows = ownerWins(sender, incomingTerm, claimantPlaying, handoff = stateHandoff)
                if (follows != (_remoteOwnerId.value == sender)) {
                    debugLog("state from ${peerName(sender)} term=$incomingTerm playing=$claimantPlaying local term=$ownerTerm localOwner=$localOwnerActive -> ${if (follows) "follow it" else "keep own playback"}")
                }
                if (!follows) {
                    // This PC keeps its own playback. Tell an Android output not to
                    // hold its next song for us (its track-ready barrier).
                    value["trackId"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                        ?.let { sendTrackReady(sender, it) }
                    return
                }
                ownerTerm = incomingTerm
                _remoteOwnerId.value = sender
                lastOwnerTrackId = value["trackId"]?.jsonPrimitive?.contentOrNull
                pendingTransferTarget = null
                // Following a different output than the one that asked: drop that takeover.
                if (pendingTakeoverFrom != sender) pendingTakeoverTerm = null
                localOwnerActive = false
                remoteStateHandler?.invoke(parsePlaybackState(value, sender, now))
            }
            "command" -> if (value["target"]?.jsonPrimitive?.contentOrNull == localId) {
                val command = value["command"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (command != "track_ready") debugLog("command '$command' from ${peerName(sender)}")
                if (command == "transfer_playback") {
                    val outputDevice = value["outputDevice"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    if (outputDevice.isNotBlank() && outputDevice != localId) {
                        // Only the device playing the sound hands it over; a stale
                        // request reaching a mirror is ignored (no forwarding loops).
                        val known = locked(seen) { outputDevice in seen }
                        if (_remoteOwnerId.value == null && known) handOverTo(outputDevice)
                        else debugLog("not handing over to ${peerName(outputDevice)}: ${if (!known) "unknown device" else "this device only follows ${peerName(_remoteOwnerId.value)}"}")
                    } else if (outputDevice == localId) {
                        requestTakeover(value["requestedOwnerTerm"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(), sender)
                    }
                    return
                }
                if (command == "take_ownership") {
                    val currentOwner = _remoteOwnerId.value
                    if (currentOwner != null && currentOwner != sender) {
                        requestTransferFrom(currentOwner, localId)
                    } else {
                        takeRequestedOwnership(value["requestedOwnerTerm"]?.jsonPrimitive?.contentOrNull?.toLongOrNull())
                    }
                    return
                }
                if (command == "prepare_takeover") {
                    // The song to take over, when this player is not on it yet: load it
                    // (silently) first. The takeover then waits until it is ready.
                    val handed = parsePlaybackState(value, sender, now)
                    handedPosition = handed.track?.let { HandedPosition(it.id, handed.positionMs, handed.isPlaying, currentTimeMillis()) }
                    val local = playbackProvider?.invoke()
                    val sameSong = handed.track != null && local?.track?.id == handed.track.id
                    val loadHere = handed.track != null && takeoverPrepareHandler != null &&
                        (!sameSong || local?.isReady != true)
                    pendingTakeoverReload = null
                    if (loadHere && sameSong) {
                        // On that song already, just not ready: it usually is within a moment.
                        debugLog("takeover waits for '${handed.track?.title}' to be ready here (reloads it if it is not soon)")
                        pendingTakeoverReload = handed
                        pendingTakeoverReloadAt = currentTimeMillis() + TAKEOVER_RELOAD_AFTER_MS
                    } else if (loadHere) {
                        debugLog("loading '${handed.track?.title}' at ${handed.positionMs} ms for the takeover (was on '${local?.track?.title}')")
                        takeoverPrepareHandler?.invoke(handed)
                    }
                    // Become the output as soon as this player is ready (on the handed song).
                    requestTakeover(
                        value["requestedOwnerTerm"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                        sender,
                        expectedTrackId = if (loadHere) handed.track?.id else null,
                    )
                    return
                }
                val commandTrack = if (command == "play_track" || command == "queue_next" || command == "queue_move") {
                    value["trackId"]?.jsonPrimitive?.contentOrNull
                        ?.takeIf(String::isNotBlank)
                        ?.let { trackId ->
                            Track(
                                id = trackId,
                                title = value["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                artist = value["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                album = value["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                artworkUrl = value["artwork"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                                durationMs = value["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                                isOfficial = true,
                            )
                        }
                } else null
                commandHandler?.invoke(
                    command,
                    value["positionMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull(),
                    commandTrack,
                )
            }
        }
    }

    /** The song, position and queue carried by a "state" packet or a handoff request. */
    private fun parsePlaybackState(value: JsonObject, sender: String, now: Long): ConnectPlaybackState {
        val trackId = value["trackId"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val track = trackId.takeIf(String::isNotBlank)?.let {
            Track(
                id = it,
                title = value["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artist = value["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                album = value["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                artworkUrl = value["artwork"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                durationMs = value["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }
        val queue = (value["queue"] as? JsonArray).orEmpty().mapNotNull { entry ->
            val item = runCatching { entry.jsonObject }.getOrNull() ?: return@mapNotNull null
            val id = item["trackId"]?.jsonPrimitive?.contentOrNull.orEmpty()
            id.takeIf(String::isNotBlank)?.let {
                Track(
                    id = it,
                    title = item["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    artist = item["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    album = item["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                    artworkUrl = item["artwork"]?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank),
                    durationMs = item["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                )
            }
        }
        return ConnectPlaybackState(
            ownerId = sender,
            track = track,
            positionMs = value["positionMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            durationMs = value["durationMs"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            isPlaying = value["playing"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
            sentAtMs = value["sentAt"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: now,
            queue = queue,
            queueIndex = value["queueIndex"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
        )
    }

    /** Puts this device's song, position and queue into a packet (see [parsePlaybackState]). */
    private fun kotlinx.serialization.json.JsonObjectBuilder.putPlayback(state: ConnectPlaybackState, includeQueue: Boolean) {
        val track = state.track ?: return
        put("trackId", track.id)
        put("title", track.title)
        put("artist", track.artist)
        put("album", track.album)
        put("artwork", track.artworkUrl.orEmpty())
        put("positionMs", state.positionMs)
        put("durationMs", state.durationMs)
        put("playing", state.isPlaying)
        if (includeQueue) {
            put("queueIndex", state.queueIndex)
            put("queue", JsonArray(state.queue.take(100).map { item ->
                buildJsonObject {
                    put("trackId", item.id)
                    put("title", item.title)
                    put("artist", item.artist)
                    put("album", item.album)
                    put("artwork", item.artworkUrl.orEmpty())
                    put("durationMs", if (item.id == track.id) state.durationMs else item.durationMs)
                }
            }))
        }
    }

    private fun ownerWins(sender: String, incomingTerm: Long, claimantPlaying: Boolean? = null, handoff: Boolean = false): Boolean {
        val currentRemote = _remoteOwnerId.value
        // The current output's own updates always count. Its term restarts at 1
        // when its app restarts; requiring a higher term froze this controller
        // on a stale picture of that device.
        if (currentRemote == sender) return true
        if (pendingTransferTarget == sender) return incomingTerm >= ownerTerm
        // The answer to a newer pick of that device as the output.
        if (handoff && incomingTerm > ownerTerm) return true
        // The output this PC mirrors has gone quiet (app closed, reinstalled with a
        // new id, phone asleep) while another device claims the sound: follow it
        // now instead of waiting for the old one to expire.
        if (currentRemote != null) {
            val heardAt = locked(seen) { seen[currentRemote]?.lastSeenMs } ?: 0L
            val claimIsReal = claimantPlaying == true || (claimantPlaying == null && incomingTerm > ownerTerm)
            if (currentTimeMillis() - heardAt > SILENT_OWNER_MS && claimIsReal) return true
        }
        // Opening Android can briefly expose a paused queue restored from its previous process.
        // An unsolicited state/claim must not replace Windows audio that is already progressing.
        // Explicit Windows -> Android transfer sets currentRemote first and is accepted above.
        if (currentRemote == null && playbackProvider?.invoke()?.isPlaying == true) return false
        val localHasTrack = localOwnerActive
        if (currentRemote == null && !localHasTrack) return true
        val currentOwner = currentRemote ?: localId
        return incomingTerm > ownerTerm || (incomingTerm == ownerTerm && sender > currentOwner)
    }

    private fun clearRemoteOwnership(owner: String) {
        if (_remoteOwnerId.value != owner) return
        debugLog("output ${peerName(owner)} is gone (not heard for a while)")
        _remoteOwnerId.value = null
        localOwnerActive = false
        remoteStateHandler?.invoke(
            ConnectPlaybackState(owner, null, 0L, 0L, false, currentTimeMillis()),
        )
    }

    private fun fingerprint(accountKey: String): String =
        digest("sha-256", "record-labs-connect-v2:${accountKey.trim().lowercase()}".encodeToByteArray()).toHex()

    private fun networkSignature(): String = IosTcp.localIpv4()
        .map { "${it.interfaceName}/${it.address}" }
        .sorted()
        .joinToString(",")

    private fun publishPacketCounts() {
        _discoveryStatus.value = _discoveryStatus.value.copy(
            sentPackets = sentPackets,
            receivedPackets = receivedPackets,
            rejectedAccountPackets = rejectedAccountPackets,
            relay = relayStatus(),
        )
    }

    private fun relayStatus(): String = when (ConnectRelay.phase) {
        ConnectRelay.Phase.Off -> if (relaySecretMissing) "off (account id not known yet)" else "off"
        ConnectRelay.Phase.Connecting -> "connecting"
        ConnectRelay.Phase.Connected -> "connected (${ConnectRelay.peers.size})"
        ConnectRelay.Phase.Unreachable -> "unreachable: ${ConnectRelay.lastProblem.orEmpty().take(60)}"
    }

    /**
     * Sends a message through the relay too when one of the account's devices online
     * there is not heard on this network (another Wi-Fi, mobile data). Devices that
     * also get it here drop the second copy ([firstCopy]).
     */
    private fun relayIfNeeded(payload: String, important: Boolean, fallback: String) {
        val now = currentTimeMillis()
        val short = fallback.takeIf { it != payload }
        // Commands and handoffs also go through the relay when it is up, even to devices
        // heard on this network: hearing a device does not mean it hears this one (a PC's
        // firewall letting its announcements out but not a phone's packets in, WSA). A
        // phone asking the PC to hand the sound back was never answered, and the switch
        // waited ~8 s for the give-up. A copy arriving both ways is applied once.
        if (ConnectRelay.connected && (important && ConnectRelay.peers.isNotEmpty() ||
                ConnectRelay.peers.any { now - (lanHeardAt[it] ?: 0L) > LAN_FRESH_MS })
        ) {
            ConnectRelay.send(payload, important = important, fallback = short)
            return
        }
        // The relay (or the other device) is reconnecting while a device is only reachable
        // through it: a command or handoff waits for it (a few seconds) instead of being lost.
        if (important && locked(seen) { seen.values.any { it.overInternet } }) {
            ConnectRelay.send(payload, important = true, fallback = short)
        }
    }

    /** A string-to-time map behind Connect's lock (the JVM's ConcurrentHashMap). */
    private class LockedLongMap {
        private val map = LinkedHashMap<String, Long>()
        operator fun get(key: String): Long? = locked(this) { map[key] }
        operator fun set(key: String, value: Long) { locked(this) { map[key] = value } }
        fun clear() = locked(this) { map.clear() }
    }

    /** A set of strings behind Connect's lock (the JVM's ConcurrentHashMap.newKeySet). */
    private class LockedStringSet {
        private val set = LinkedHashSet<String>()
        fun add(value: String): Boolean = locked(this) { set.add(value) }
        fun remove(value: String): Boolean = locked(this) { set.remove(value) }
    }

    /** The open TCP links, behind Connect's lock. */
    private class LockedLinkSet {
        private val set = LinkedHashSet<TcpLink>()
        fun add(link: TcpLink) = locked(this) { set.add(link) }
        fun remove(link: TcpLink) = locked(this) { set.remove(link) }
        fun clear() = locked(this) { set.clear() }
        fun toList(): List<TcpLink> = locked(this) { set.toList() }
        val size: Int get() = locked(this) { set.size }
        fun any(predicate: (TcpLink) -> Boolean): Boolean = toList().any(predicate)
        fun filter(predicate: (TcpLink) -> Boolean): List<TcpLink> = toList().filter(predicate)
        fun mapNotNullTo(target: MutableSet<String>, transform: (TcpLink) -> String?): MutableSet<String> = toList().mapNotNullTo(target, transform)
    }
}
