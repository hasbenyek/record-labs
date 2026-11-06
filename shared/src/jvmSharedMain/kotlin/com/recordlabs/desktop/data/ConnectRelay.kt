package com.recordlabs.desktop.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Record Labs Connect over the internet: the devices of one account reach each
 * other through the Connect relay (a small WebSocket server, separate from Listen
 * Together's) when they are not on the same Wi-Fi. On the same network they keep
 * talking directly; [WindowsRecordLabsConnect] only uses this path for devices it
 * does not hear on the LAN.
 *
 * Privacy: the relay sees a room id (a hash) and device ids, never the account or
 * the music. Both come from the account's secret id ([accountSecret], YouTube's
 * DATASYNC_ID, which only the signed-in apps know): the room id is one hash of it,
 * and every message is encrypted with AES-256-GCM under another (the room id is
 * authenticated data). The relay cannot read, forge or alter a message, and a
 * device of another account cannot join the room without the secret.
 *
 * The same protocol as the server (record-labs-connect-server, protocol 1):
 * hello -> welcome / joined / left / msg.
 */
object ConnectRelay {
    /**
     * The relay's WebSocket address. Override with -Drecordlabs.connectRelay=wss://…
     * (desktop) for a test server; an empty value turns the relay off.
     */
    const val DEFAULT_URL = "wss://record-labs-connect-relay.onrender.com/connect"

    val url: String
        get() = System.getProperty("recordlabs.connectRelay")?.trim() ?: DEFAULT_URL

    /** Shown in the Connect diagnostics line. */
    enum class Phase { Off, Connecting, Connected, Unreachable }

    @Volatile var phase: Phase = Phase.Off
        private set
    @Volatile var lastProblem: String? = null
        private set

    /** The account's other devices connected to the relay right now. */
    val peers: Set<String> get() = peerSet
    private val peerSet: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * When each device was last known to be on the relay (in [peers], or until it left).
     * A device reconnecting (a phone switching networks, the server restarting) is gone
     * from [peers] for a moment; [present] keeps it for a short grace so it does not
     * vanish from the device list and back.
     */
    private val presentAt = ConcurrentHashMap<String, Long>()

    /** [deviceId] is on the relay, or was until less than [graceMs] ago. */
    fun present(deviceId: String, graceMs: Long = LEFT_GRACE_MS): Boolean {
        if (deviceId in peerSet) return true
        val at = presentAt[deviceId] ?: return false
        return System.currentTimeMillis() - at < graceMs
    }

    val connected: Boolean get() = phase == Phase.Connected

    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()
    private val scheduler = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "record-labs-connect-relay").apply { isDaemon = true }
    }
    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // A free server can take up to a minute to wake up (Render).
            .connectTimeout(20, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)
            // A dead connection (a phone that lost its network without noticing) is
            // found within ~20 s instead of minutes: OkHttp fails it when a ping
            // goes unanswered.
            .pingInterval(10, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }

    private class Session(
        val room: String,
        val key: SecretKeySpec,
        val deviceId: String,
        val onMessage: (raw: String, from: String) -> Unit,
        val onChanged: () -> Unit,
    )

    @Volatile private var session: Session? = null
    @Volatile private var socket: WebSocket? = null
    @Volatile private var generation = 0
    @Volatile private var retryDelayMs = INITIAL_RETRY_MS
    @Volatile private var retry: ScheduledFuture<*>? = null
    @Volatile private var refusal: String? = null
    @Volatile private var lastRxAt = 0L
    @Volatile private var connectingSince = 0L
    @Volatile private var lastForcedReconnectAt = 0L
    @Volatile private var keepAlive: ScheduledFuture<*>? = null

    /** Commands sent while the connection was down for a moment: sent once it is back. */
    private class Pending(val raw: String, val to: String?, val at: Long)
    private val outbox = java.util.concurrent.ConcurrentLinkedDeque<Pending>()

    private const val INITIAL_RETRY_MS = 1_000L
    // Short: a server that restarted or woke up is back in seconds, and every
    // second without the relay is a second the other devices are not shown.
    private const val MAX_RETRY_MS = 15_000L
    private const val REFUSED_RETRY_MS = 5 * 60_000L
    private const val KEEP_ALIVE_MS = 15_000L
    /** Nothing at all from the server for this long (not even the keep-alive answer): reconnect. */
    private const val SILENT_SOCKET_MS = 40_000L
    /** Waking a free server can take this long; then the attempt is started again. */
    private const val CONNECT_STUCK_MS = 75_000L
    private const val LEFT_GRACE_MS = 10_000L
    private const val OUTBOX_MAX = 32
    private const val OUTBOX_MAX_AGE_MS = 10_000L
    /** The server's own limit is larger (512 KB); bigger messages fall back to the short form. */
    const val MAX_FRAME_CHARS = 80_000

    /**
     * The account's secret id: YouTube Music's DATASYNC_ID ("sync:…" in Connect's
     * account keys), the part before "||" (Android and the desktop store it
     * differently). Null without it (signed out, or not known yet): no relay.
     */
    fun accountSecret(accountKeys: Collection<String>): String? =
        accountKeys.map(String::trim)
            .firstOrNull { it.startsWith("sync:") }
            ?.removePrefix("sync:")
            ?.substringBefore("||")
            ?.trim()
            ?.takeIf { it.length >= 6 }

    fun roomId(secret: String): String = sha256("record-labs-connect/room/v1:$secret").toHex()

    private fun keyOf(secret: String) = SecretKeySpec(sha256("record-labs-connect/key/v1:$secret"), "AES")

    /** Connects (and stays connected, reconnecting after network changes) for this account and device. */
    fun start(secret: String, deviceId: String, onMessage: (raw: String, from: String) -> Unit, onChanged: () -> Unit) {
        val room = roomId(secret)
        val current = session
        if (current != null && current.room == room && current.deviceId == deviceId && phase != Phase.Off) return
        stop()
        if (url.isBlank()) return
        session = Session(room, keyOf(secret), deviceId, onMessage, onChanged)
        retryDelayMs = INITIAL_RETRY_MS
        connect()
        keepAlive = scheduler.scheduleAtFixedRate(::tick, KEEP_ALIVE_MS, KEEP_ALIVE_MS, TimeUnit.MILLISECONDS)
    }

    /**
     * Every few seconds: an application ping (the server answers, which also keeps a
     * free server awake and proxies from closing an idle connection), and a check
     * that the connection still delivers anything at all.
     */
    private fun tick() {
        if (session == null) return
        val now = System.currentTimeMillis()
        when (phase) {
            Phase.Connected -> {
                val ws = socket
                if (ws == null || now - lastRxAt > SILENT_SOCKET_MS) {
                    WindowsRecordLabsConnect.debugLog("relay silent for ${(now - lastRxAt) / 1000} s: reconnecting")
                    ws?.cancel()
                    lost(generation, "no answer from the relay")
                } else {
                    ws.send("{\"t\":\"ping\"}")
                }
            }
            Phase.Connecting -> if (connectingSince > 0 && now - connectingSince > CONNECT_STUCK_MS) {
                socket?.cancel()
                lost(generation, "the relay did not answer")
            }
            else -> Unit
        }
        // Old presence entries.
        presentAt.entries.removeIf { it.key !in peerSet && now - it.value > 10 * 60_000L }
    }

    fun stop() {
        generation++
        retry?.cancel(false)
        retry = null
        keepAlive?.cancel(false)
        keepAlive = null
        socket?.close(1000, "bye")
        socket = null
        session = null
        peerSet.clear()
        presentAt.clear()
        outbox.clear()
        phase = Phase.Off
    }

    /**
     * Sends one Connect message to the account's other devices (or to [to] only).
     * [important] (a command, a handoff): when the connection is down for a moment
     * it is kept and sent once it is back (heartbeats are not, the next one follows).
     * [fallback]: a shorter form sent instead when [raw] is too large for the relay.
     * False when it was not sent (nor kept).
     */
    fun send(raw: String, to: String? = null, important: Boolean = false, fallback: String? = null): Boolean {
        val active = session ?: return false
        val ws = socket
        if (ws == null || phase != Phase.Connected) {
            if (important) keep(raw, to)
            return important
        }
        if (peerSet.isEmpty()) {
            // The others are reconnecting: kept until one is back (see flushOutbox).
            if (important) keep(raw, to)
            return important
        }
        if (to != null && to !in peerSet) {
            // The device is reconnecting: send it when it is back.
            if (important && present(to)) keep(raw, to)
            return false
        }
        val frame = frameOf(active, raw, to)
            ?.takeIf { it.length <= MAX_FRAME_CHARS || fallback == null }
            ?: fallback?.let { frameOf(active, it, to) }
            ?: return false
        if (frame.length > MAX_FRAME_CHARS * 4) return false
        val sent = ws.send(frame)
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
        val now = System.currentTimeMillis()
        outbox.addLast(Pending(raw, to, now))
        while (outbox.size > OUTBOX_MAX) outbox.pollFirst()
    }

    /** Connected again: what could not be sent meanwhile goes out now (in order). */
    private fun flushOutbox() {
        val now = System.currentTimeMillis()
        while (true) {
            val next = outbox.pollFirst() ?: break
            if (now - next.at > OUTBOX_MAX_AGE_MS) continue
            if (next.to != null && next.to !in peerSet) continue
            val active = session ?: return
            val frame = frameOf(active, next.raw, next.to) ?: continue
            socket?.send(frame)
        }
    }

    private fun connect() {
        val active = session ?: return
        val myGeneration = ++generation
        // Never two connections at once (the server would keep replacing one with the other).
        socket?.cancel()
        socket = null
        phase = Phase.Connecting
        connectingSince = System.currentTimeMillis()
        active.onChanged()
        val request = runCatching {
            Request.Builder().url(url).header("User-Agent", "RecordLabs/${AppInfo.version} (${Platform.os})").build()
        }.getOrElse {
            phase = Phase.Unreachable
            lastProblem = "invalid relay address"
            return
        }
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (myGeneration != generation) return webSocket.cancel()
                webSocket.send(buildJsonObject {
                    put("t", "hello")
                    put("v", 1)
                    put("room", active.room)
                    put("device", active.deviceId)
                }.toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (myGeneration != generation) return
                lastRxAt = System.currentTimeMillis()
                val message = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
                when (message["t"]?.jsonPrimitive?.contentOrNull) {
                    "welcome" -> {
                        val now = System.currentTimeMillis()
                        peerSet.clear()
                        (message["peers"] as? JsonArray)?.mapNotNullTo(peerSet) { it.jsonPrimitive.contentOrNull }
                        peerSet.forEach { presentAt[it] = now }
                        phase = Phase.Connected
                        connectingSince = 0L
                        lastProblem = null
                        refusal = null
                        retryDelayMs = INITIAL_RETRY_MS
                        WindowsRecordLabsConnect.debugLog("relay connected (${peerSet.size} other device(s) of this account online)")
                        flushOutbox()
                        active.onChanged()
                    }
                    "joined" -> message["device"]?.jsonPrimitive?.contentOrNull?.let {
                        peerSet.add(it)
                        presentAt[it] = System.currentTimeMillis()
                        WindowsRecordLabsConnect.debugLog("relay: $it joined")
                        flushOutbox()
                        active.onChanged()
                    }
                    "left" -> message["device"]?.jsonPrimitive?.contentOrNull?.let {
                        peerSet.remove(it)
                        // Kept a moment longer (see present): usually it is reconnecting.
                        presentAt[it] = System.currentTimeMillis()
                        WindowsRecordLabsConnect.debugLog("relay: $it left")
                        active.onChanged()
                    }
                    "pong" -> Unit
                    "msg" -> {
                        val from = message["from"]?.jsonPrimitive?.contentOrNull ?: return
                        val data = message["data"]?.jsonPrimitive?.contentOrNull ?: return
                        // Not from this account's key (or altered): dropped.
                        val raw = runCatching { open(active, data) }.getOrNull() ?: return
                        active.onMessage(raw, from)
                    }
                    "error" -> {
                        val code = message["code"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        refusal = message["message"]?.jsonPrimitive?.contentOrNull ?: code
                        // Refused for good reasons (too many devices, old protocol): ask again much later.
                        if (code == "room_full" || code == "version" || code == "full") retryDelayMs = REFUSED_RETRY_MS
                        WindowsRecordLabsConnect.debugLog("relay refused: $code")
                    }
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = lost(myGeneration, "closed ($code)")

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) =
                lost(myGeneration, response?.let { "HTTP ${it.code}" } ?: (t.message ?: t.javaClass.simpleName))
        })
    }

    /** The connection dropped (network change, server restart): try again, waiting longer each time. */
    private fun lost(myGeneration: Int, why: String) {
        if (myGeneration != generation) return
        val active = session ?: return
        // Not counted as a new attempt twice (the watchdog and OkHttp can both report it).
        generation++
        socket = null
        val now = System.currentTimeMillis()
        // The others are probably still there: kept for a moment (see present).
        peerSet.forEach { presentAt[it] = now }
        peerSet.clear()
        phase = Phase.Unreachable
        connectingSince = 0L
        // The server's own reason (it said why before closing) is the useful one.
        lastProblem = refusal ?: why
        WindowsRecordLabsConnect.debugLog("relay lost: ${lastProblem.orEmpty()}")
        active.onChanged()
        // A little randomness, so the devices of an account do not all retry together.
        val base = retryDelayMs
        val wait = base + ThreadLocalRandom.current().nextLong(0, base / 4 + 1)
        retryDelayMs = (retryDelayMs * 2).coerceAtMost(MAX_RETRY_MS).coerceAtLeast(retryDelayMs)
        retry?.cancel(false)
        val attempt = generation
        retry = scheduler.schedule({ if (attempt == generation && session === active) connect() }, wait, TimeUnit.MILLISECONDS)
    }

    /**
     * Called when the device's network changes (Wi-Fi to mobile data, another Wi-Fi):
     * the old connection is usually dead without knowing it yet, so a new one is made
     * now rather than after the back-off or a missed ping.
     */
    fun networkChanged() {
        if (session == null || phase == Phase.Off) return
        val now = System.currentTimeMillis()
        if (now - lastForcedReconnectAt < 3_000L) return
        lastForcedReconnectAt = now
        retry?.cancel(false)
        retryDelayMs = INITIAL_RETRY_MS
        scheduler.execute { if (session != null) connect() }
    }

    // nonce (12 bytes) || AES-256-GCM(ciphertext || tag), the room id as authenticated data, base64.
    private fun seal(active: Session, text: String): String {
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, active.key, GCMParameterSpec(128, nonce))
        cipher.updateAAD(active.room.toByteArray(Charsets.UTF_8))
        // Compressed first: a heartbeat with the queue is several times smaller (less to
        // send on mobile data, and well under the server's message limit).
        val body = cipher.doFinal(gzip(text.toByteArray(Charsets.UTF_8)))
        return Base64.getEncoder().encodeToString(nonce + body)
    }

    private fun open(active: Session, data: String): String {
        val blob = Base64.getDecoder().decode(data)
        require(blob.size > 12 + 16)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, active.key, GCMParameterSpec(128, blob, 0, 12))
        cipher.updateAAD(active.room.toByteArray(Charsets.UTF_8))
        val plain = cipher.doFinal(blob, 12, blob.size - 12)
        // Compressed (gzip magic) or plain JSON (it starts with "{").
        val bytes = if (plain.size > 2 && plain[0] == 0x1f.toByte() && plain[1] == 0x8b.toByte()) gunzip(plain) else plain
        return bytes.toString(Charsets.UTF_8)
    }

    private fun gzip(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(bytes.size / 3 + 64)
        GZIPOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun gunzip(bytes: ByteArray): ByteArray = GZIPInputStream(ByteArrayInputStream(bytes)).use { input ->
        val out = ByteArrayOutputStream(bytes.size * 4)
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            // A message never needs more (protects against a compression bomb).
            require(total <= 4 * 1024 * 1024)
            out.write(buffer, 0, read)
        }
        out.toByteArray()
    }

    private fun sha256(text: String): ByteArray = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
}
