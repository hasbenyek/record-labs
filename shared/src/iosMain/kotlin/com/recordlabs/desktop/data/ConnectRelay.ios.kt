@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.currentTimeMillis
import com.recordlabs.desktop.platform.digest
import com.recordlabs.desktop.platform.ktorEngine
import com.recordlabs.desktop.platform.toHex
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import platform.Foundation.NSData
import platform.Foundation.NSDataCompressionAlgorithmZlib
import platform.Foundation.NSRecursiveLock
import platform.Foundation.create
import platform.Foundation.decompressedDataUsingAlgorithm
import platform.posix.arc4random_buf
import platform.posix.memcpy
import kotlin.concurrent.Volatile
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlin.random.Random

/**
 * Record Labs Connect over the internet on the iPhone: the same relay, protocol and
 * encryption as the desktop and Android (jvmSharedMain ConnectRelay.kt), so an
 * iPhone and the account's other devices find each other on any network.
 *
 * The relay sees a room id (a hash of the account's secret id) and device ids, never
 * the account or the music: every message is sealed with AES-256-GCM under a key
 * from the same secret (the room id is authenticated data).
 */
internal object ConnectRelay {
    const val DEFAULT_URL = "wss://record-labs-connect-relay.onrender.com/connect"
    val url: String get() = DEFAULT_URL

    enum class Phase { Off, Connecting, Connected, Unreachable }

    @Volatile var phase: Phase = Phase.Off
        private set
    @Volatile var lastProblem: String? = null
        private set

    private val lock = NSRecursiveLock()
    private inline fun <T> locked(block: () -> T): T {
        lock.lock()
        try { return block() } finally { lock.unlock() }
    }

    private val peerSet = LinkedHashSet<String>()
    /** The account's other devices connected to the relay right now. */
    val peers: Set<String> get() = locked { peerSet.toSet() }
    private val presentAt = LinkedHashMap<String, Long>()

    /** [deviceId] is on the relay, or was until less than [graceMs] ago. */
    fun present(deviceId: String, graceMs: Long = LEFT_GRACE_MS): Boolean = locked {
        if (deviceId in peerSet) return@locked true
        val at = presentAt[deviceId] ?: return@locked false
        currentTimeMillis() - at < graceMs
    }

    val connected: Boolean get() = phase == Phase.Connected

    private val json = Json { ignoreUnknownKeys = true }
    /** Everything the relay does runs here, one thing at a time (like the JVM's scheduler thread). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val client: HttpClient by lazy { HttpClient(ktorEngine()) { install(WebSockets) } }

    private class Session(
        val room: String,
        val key: ByteArray,
        val deviceId: String,
        val onMessage: (raw: String, from: String) -> Unit,
        val onChanged: () -> Unit,
    )

    @Volatile private var session: Session? = null
    @Volatile private var socket: DefaultClientWebSocketSession? = null
    @Volatile private var socketJob: Job? = null
    @Volatile private var generation = 0
    @Volatile private var retryDelayMs = INITIAL_RETRY_MS
    @Volatile private var retry: Job? = null
    @Volatile private var refusal: String? = null
    @Volatile private var lastRxAt = 0L
    @Volatile private var connectingSince = 0L
    @Volatile private var lastForcedReconnectAt = 0L
    @Volatile private var keepAlive: Job? = null

    private class Pending(val raw: String, val to: String?, val at: Long)
    private val outbox = ArrayDeque<Pending>()

    private const val INITIAL_RETRY_MS = 1_000L
    private const val MAX_RETRY_MS = 15_000L
    private const val REFUSED_RETRY_MS = 5 * 60_000L
    private const val KEEP_ALIVE_MS = 15_000L
    private const val SILENT_SOCKET_MS = 40_000L
    private const val CONNECT_STUCK_MS = 75_000L
    private const val LEFT_GRACE_MS = 10_000L
    private const val OUTBOX_MAX = 32
    private const val OUTBOX_MAX_AGE_MS = 10_000L
    const val MAX_FRAME_CHARS = 80_000

    /** The account's secret id (YouTube Music's DATASYNC_ID): see the JVM version. */
    fun accountSecret(accountKeys: Collection<String>): String? =
        accountKeys.map(String::trim)
            .firstOrNull { it.startsWith("sync:") }
            ?.removePrefix("sync:")
            ?.substringBefore("||")
            ?.trim()
            ?.takeIf { it.length >= 6 }

    fun roomId(secret: String): String = sha256("record-labs-connect/room/v1:$secret").toHex()

    private fun keyOf(secret: String): ByteArray = sha256("record-labs-connect/key/v1:$secret")

    fun start(secret: String, deviceId: String, onMessage: (raw: String, from: String) -> Unit, onChanged: () -> Unit) {
        val room = roomId(secret)
        val current = session
        if (current != null && current.room == room && current.deviceId == deviceId && phase != Phase.Off) return
        stop()
        if (url.isBlank()) return
        session = Session(room, keyOf(secret), deviceId, onMessage, onChanged)
        retryDelayMs = INITIAL_RETRY_MS
        scope.launch { connect() }
        keepAlive = scope.launch {
            while (isActive) {
                delay(KEEP_ALIVE_MS)
                runCatching { tick() }
            }
        }
    }

    private fun tick() {
        if (session == null) return
        val now = currentTimeMillis()
        when (phase) {
            Phase.Connected -> {
                val ws = socket
                if (ws == null || now - lastRxAt > SILENT_SOCKET_MS) {
                    WindowsRecordLabsConnect.debugLog("relay silent for ${(now - lastRxAt) / 1000} s: reconnecting")
                    socketJob?.cancel()
                    lost(generation, "no answer from the relay")
                } else {
                    ws.outgoing.trySend(Frame.Text("{\"t\":\"ping\"}"))
                }
            }
            Phase.Connecting -> if (connectingSince > 0 && now - connectingSince > CONNECT_STUCK_MS) {
                socketJob?.cancel()
                lost(generation, "the relay did not answer")
            }
            else -> Unit
        }
        locked {
            val old = presentAt.filter { it.key !in peerSet && now - it.value > 10 * 60_000L }.keys
            old.forEach(presentAt::remove)
        }
    }

    fun stop() {
        generation++
        retry?.cancel()
        retry = null
        keepAlive?.cancel()
        keepAlive = null
        val ws = socket
        val job = socketJob
        socket = null
        socketJob = null
        if (ws != null) scope.launch {
            runCatching { ws.close(CloseReason(CloseReason.Codes.NORMAL, "bye")) }
            job?.cancel()
        } else job?.cancel()
        session = null
        locked {
            peerSet.clear()
            presentAt.clear()
            outbox.clear()
        }
        phase = Phase.Off
    }

    /** See the JVM version: sends one message to the account's other devices (or to [to] only). */
    fun send(raw: String, to: String? = null, important: Boolean = false, fallback: String? = null): Boolean {
        val active = session ?: return false
        val ws = socket
        if (ws == null || phase != Phase.Connected) {
            if (important) keep(raw, to)
            return important
        }
        val peersNow = peers
        if (peersNow.isEmpty()) {
            if (important) keep(raw, to)
            return important
        }
        if (to != null && to !in peersNow) {
            if (important && present(to)) keep(raw, to)
            return false
        }
        val frame = frameOf(active, raw, to)
            ?.takeIf { it.length <= MAX_FRAME_CHARS || fallback == null }
            ?: fallback?.let { frameOf(active, it, to) }
            ?: return false
        if (frame.length > MAX_FRAME_CHARS * 4) return false
        val sent = ws.outgoing.trySend(Frame.Text(frame)).isSuccess
        if (!sent && important) keep(raw, to)
        return sent
    }

    private fun frameOf(active: Session, raw: String, to: String?): String? {
        val data = runCatching { seal(active, raw) }.getOrNull() ?: return null
        return buildJsonObject {
            put("t", "send")
            put("data", data)
            if (to != null) put("to", to)
        }.toString()
    }

    private fun keep(raw: String, to: String?) {
        val now = currentTimeMillis()
        locked {
            outbox.addLast(Pending(raw, to, now))
            while (outbox.size > OUTBOX_MAX) outbox.removeFirst()
        }
    }

    private fun flushOutbox() {
        val now = currentTimeMillis()
        while (true) {
            val next = locked { outbox.removeFirstOrNull() } ?: break
            if (now - next.at > OUTBOX_MAX_AGE_MS) continue
            if (next.to != null && next.to !in peers) continue
            val active = session ?: return
            val frame = frameOf(active, next.raw, next.to) ?: continue
            socket?.outgoing?.trySend(Frame.Text(frame))
        }
    }

    private fun connect() {
        val active = session ?: return
        val myGeneration = ++generation
        socketJob?.cancel()
        socketJob = null
        socket = null
        phase = Phase.Connecting
        connectingSince = currentTimeMillis()
        active.onChanged()
        socketJob = scope.launch {
            val failure = runCatching {
                client.webSocket(urlString = url, request = {
                    header("User-Agent", "RecordLabs/${AppInfo.version} (iOS)")
                }) {
                    if (myGeneration != generation) return@webSocket
                    socket = this
                    outgoing.send(Frame.Text(buildJsonObject {
                        put("t", "hello")
                        put("v", 1)
                        put("room", active.room)
                        put("device", active.deviceId)
                    }.toString()))
                    for (frame in incoming) {
                        if (myGeneration != generation) break
                        if (frame is Frame.Text) {
                            lastRxAt = currentTimeMillis()
                            runCatching { onText(active, frame.readText()) }
                        }
                    }
                }
            }.exceptionOrNull()
            if (failure is kotlinx.coroutines.CancellationException) return@launch
            lost(myGeneration, failure?.message ?: "closed")
        }
    }

    private fun onText(active: Session, text: String) {
        val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        when (message["t"]?.jsonPrimitive?.contentOrNull) {
            "welcome" -> {
                val now = currentTimeMillis()
                locked {
                    peerSet.clear()
                    (message["peers"] as? JsonArray)?.mapNotNullTo(peerSet) { it.jsonPrimitive.contentOrNull }
                    peerSet.forEach { presentAt[it] = now }
                }
                phase = Phase.Connected
                connectingSince = 0L
                lastProblem = null
                refusal = null
                retryDelayMs = INITIAL_RETRY_MS
                WindowsRecordLabsConnect.debugLog("relay connected (${peers.size} other device(s) of this account online)")
                flushOutbox()
                active.onChanged()
            }
            "joined" -> message["device"]?.jsonPrimitive?.contentOrNull?.let {
                locked {
                    peerSet.add(it)
                    presentAt[it] = currentTimeMillis()
                }
                WindowsRecordLabsConnect.debugLog("relay: $it joined")
                flushOutbox()
                active.onChanged()
            }
            "left" -> message["device"]?.jsonPrimitive?.contentOrNull?.let {
                locked {
                    peerSet.remove(it)
                    presentAt[it] = currentTimeMillis()
                }
                WindowsRecordLabsConnect.debugLog("relay: $it left")
                active.onChanged()
            }
            "pong" -> Unit
            "msg" -> {
                val from = message["from"]?.jsonPrimitive?.contentOrNull ?: return
                val data = message["data"]?.jsonPrimitive?.contentOrNull ?: return
                val raw = runCatching { open(active, data) }.getOrNull() ?: return
                active.onMessage(raw, from)
            }
            "error" -> {
                val code = message["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                refusal = message["message"]?.jsonPrimitive?.contentOrNull ?: code
                if (code == "room_full" || code == "version" || code == "full") retryDelayMs = REFUSED_RETRY_MS
                WindowsRecordLabsConnect.debugLog("relay refused: $code")
            }
        }
    }

    private fun lost(myGeneration: Int, why: String) {
        if (myGeneration != generation) return
        val active = session ?: return
        generation++
        socket = null
        val now = currentTimeMillis()
        locked {
            peerSet.forEach { presentAt[it] = now }
            peerSet.clear()
        }
        phase = Phase.Unreachable
        connectingSince = 0L
        lastProblem = refusal ?: why
        WindowsRecordLabsConnect.debugLog("relay lost: ${lastProblem.orEmpty()}")
        active.onChanged()
        val base = retryDelayMs
        val wait = base + Random.nextLong(0, base / 4 + 1)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS).coerceAtLeast(retryDelayMs)
        retry?.cancel()
        val attempt = generation
        retry = scope.launch {
            delay(wait)
            if (attempt == generation && session === active) connect()
        }
    }

    /** The network changed (Wi-Fi to mobile data, another Wi-Fi): a new connection now. */
    fun networkChanged() {
        if (session == null || phase == Phase.Off) return
        val now = currentTimeMillis()
        if (now - lastForcedReconnectAt < 3_000L) return
        lastForcedReconnectAt = now
        retry?.cancel()
        retryDelayMs = INITIAL_RETRY_MS
        scope.launch { if (session != null) connect() }
    }

    // nonce (12 bytes) || AES-256-GCM(ciphertext || tag), the room id as authenticated data, base64.
    // Sent as plain JSON (the other devices take plain or gzip); received gzip is unpacked.
    @OptIn(ExperimentalEncodingApi::class)
    private fun seal(active: Session, text: String): String {
        val nonce = ByteArray(12).also { randomBytes(it) }
        val body = IosAesGcm.seal(active.key, nonce, active.room.encodeToByteArray(), text.encodeToByteArray())
        return Base64.encode(nonce + body)
    }

    @OptIn(ExperimentalEncodingApi::class)
    private fun open(active: Session, data: String): String {
        val blob = Base64.decode(data)
        require(blob.size > 12 + 16)
        val plain = IosAesGcm.open(active.key, blob.copyOfRange(0, 12), active.room.encodeToByteArray(), blob.copyOfRange(12, blob.size))
            ?: error("not this account's message")
        val bytes = if (plain.size > 2 && plain[0] == 0x1f.toByte() && plain[1] == 0x8b.toByte()) gunzip(plain) else plain
        return bytes.decodeToString()
    }

    private fun randomBytes(target: ByteArray) {
        target.usePinned { arc4random_buf(it.addressOf(0), target.size.convert()) }
    }

    /** A gzip member (RFC 1952) unpacked with Foundation's raw DEFLATE (RFC 1951). */
    private fun gunzip(bytes: ByteArray): ByteArray {
        require(bytes.size >= 18 && bytes[2] == 8.toByte())
        val flags = bytes[3].toInt() and 0xff
        var at = 10
        if (flags and 0x04 != 0) at += 2 + ((bytes[at].toInt() and 0xff) or ((bytes[at + 1].toInt() and 0xff) shl 8))
        if (flags and 0x08 != 0) { while (bytes[at] != 0.toByte()) at++; at++ }
        if (flags and 0x10 != 0) { while (bytes[at] != 0.toByte()) at++; at++ }
        if (flags and 0x02 != 0) at += 2
        val deflated = bytes.copyOfRange(at, bytes.size - 8)
        val size = (bytes[bytes.size - 4].toLong() and 0xff) or ((bytes[bytes.size - 3].toLong() and 0xff) shl 8) or
            ((bytes[bytes.size - 2].toLong() and 0xff) shl 16) or ((bytes[bytes.size - 1].toLong() and 0xff) shl 24)
        // A message never needs more (protects against a compression bomb).
        require(size <= 4L * 1024 * 1024)
        val input = deflated.usePinned { NSData.create(bytes = it.addressOf(0), length = deflated.size.convert()) }
        val output = input.decompressedDataUsingAlgorithm(NSDataCompressionAlgorithmZlib, null)
            ?: error("not gzip")
        val length = output.length.toInt()
        require(length <= 4 * 1024 * 1024)
        val result = ByteArray(length)
        if (length > 0) result.usePinned { memcpy(it.addressOf(0), output.bytes, output.length) }
        return result
    }

    private fun sha256(text: String): ByteArray = digest("sha-256", text.encodeToByteArray())
}
