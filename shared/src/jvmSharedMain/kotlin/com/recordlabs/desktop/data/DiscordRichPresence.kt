package com.recordlabs.desktop.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs





/**
 * Android: Rich Presence through Discord itself with the user's Discord login (there is
 * no Discord desktop app to talk to). Set by the Android app; the desktop uses the pipe.
 */
interface DiscordRemoteBackend {
    /** The connection as the settings show it (enabled: Rich Presence is on). */
    fun state(): DiscordConnectionState
    /** A Discord login is saved (whether connected now or not). */
    val loggedIn: Boolean
    /** Connects with the saved login (Rich Presence turned on). */
    fun start()
    /** Disconnects (Rich Presence turned off); the login is kept. */
    fun stop()
    /** Shows [content] (null clears it); false when not connected. */
    fun setActivity(content: DiscordActivityContent?, display: Int, trackId: String?, isPlaying: Boolean): Boolean
    /** Opens Discord's login page; the app connects once the user allows it. */
    fun login()
    /** Forgets the Discord login. */
    fun logout()
}

/**
 * Discord Rich Presence through the Discord desktop app's local RPC pipe
 * (`\\.\pipe\discord-ipc-N`), with the same Discord application as the
 * Android app. No Discord login is needed: Discord shows the activity on
 * the account signed in to its desktop app.
 *
 * One worker coroutine owns the connection; it sends at most one update every
 * [MIN_SEND_GAP_MS] (Discord allows about five per 20 s), always the latest
 * state, and reconnects by itself when Discord is started later.
 */
actual object DiscordRichPresence {
    const val APPLICATION_ID = "1525699798076887183"
    const val DEFAULT_DETAILS = DiscordDefaults.DETAILS
    const val DEFAULT_STATE = DiscordDefaults.STATE
    const val DEFAULT_BUTTON_LABEL = DiscordDefaults.BUTTON_LABEL
    const val DEFAULT_BUTTON_URL = DiscordDefaults.BUTTON_URL
    actual val PLACEHOLDERS: List<String> = listOf("{song.name}", "{artist.name}", "{album.name}", "{song.id}")

    private const val MIN_SEND_GAP_MS = 4_000L
    private const val PAUSE_CLEAR_MS = 60_000L
    private const val RECONNECT_MS = 15_000L
    private const val SEEK_TOLERANCE_MS = 2_500L

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<DiscordConnectionState>(DiscordConnectionState.Off)
    actual val state: StateFlow<DiscordConnectionState> = _state.asStateFlow()
    private val _playback = MutableStateFlow<DiscordPlayback?>(null)
    /** The latest playback the app reported (for the settings preview). */
    actual val playback: StateFlow<DiscordPlayback?> = _playback.asStateFlow()

    private val latest = AtomicReference<DiscordPlayback?>(null)
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false
    @Volatile private var resendRequested = false
    @Volatile private var retryFullActivity = false

    /** Android's Discord connection (see [DiscordRemoteBackend]); null on the desktop. */
    @Volatile var remoteBackend: DiscordRemoteBackend? = null

    /** Android: whether Rich Presence needs a Discord login in Record Labs. */
    actual val needsLogin: Boolean get() = remoteBackend != null

    actual fun login() { remoteBackend?.login() }

    actual val loggedIn: Boolean get() = remoteBackend?.loggedIn == true

    actual fun logout() {
        remoteBackend?.logout()
        remoteChanged()
    }

    /** The Android connection changed (logged in, connected, dropped): show it and send again. */
    fun remoteChanged() {
        resendRequested = true
        ensureStarted()
        wake.trySend(Unit)
    }

    actual fun report(playback: DiscordPlayback) {
        val previous = latest.getAndSet(playback)
        _playback.value = playback
        if (!AppSettings.discordEnabled.value && !started) return
        ensureStarted()
        if (previous?.track?.id != playback.track?.id || previous?.isPlaying != playback.isPlaying || previous?.speed != playback.speed) {
            wake.trySend(Unit)
        } else if (previous != null && playback.isPlaying && abs(expectedStart(previous) - expectedStart(playback)) > SEEK_TOLERANCE_MS) {
            wake.trySend(Unit) // a seek
        }
    }

    /** Settings changed (on/off, templates): send again at once. */
    actual fun settingsChanged() {
        resendRequested = true
        // A fixed template or button may be accepted again in the full form.
        retryFullActivity = true
        ensureStarted()
        wake.trySend(Unit)
    }

    private fun ensureStarted() {
        if (started) return
        synchronized(this) {
            if (started) return
            started = true
        }
        scope.launch {
            try {
                runWorker()
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                _state.value = DiscordConnectionState.Failed("Rich Presence stopped unexpectedly · turn it off and on again")
            } finally {
                // Lets the next report or settings change start a new worker.
                started = false
            }
        }
    }

    private fun expectedStart(playback: DiscordPlayback): Long =
        System.currentTimeMillis() - (playback.positionMs / playback.speed.coerceAtLeast(0.1f)).toLong()

    // ---- What to show ----

    private fun render(template: String, track: Track): String = template
        .replace("{song.name}", track.title)
        .replace("{song.id}", track.id)
        .replace("{artist.name}", track.artist.ifBlank { "Unknown Artist" })
        .replace("{album.name}", track.album.ifBlank { "Unknown Album" })

    /** Discord needs 2-128 characters; shorter text gets an invisible pad. */
    private fun fit(text: String, max: Int = 128): String? {
        val clean = text.replace(Regex("\\s+"), " ").trim()
        if (clean.isEmpty()) return null
        val cut = if (clean.length > max) clean.take(max - 1).trimEnd() + "…" else clean
        return if (cut.length < 2) cut + "⠀" else cut
    }

    actual fun content(playback: DiscordPlayback, nowMs: Long): DiscordActivityContent? {
        val track = playback.track ?: return null
        val advanced = AppSettings.discordAdvanced.value
        val detailsTemplate = if (advanced) AppSettings.discordDetailsTemplate.value.ifBlank { DEFAULT_DETAILS } else DEFAULT_DETAILS
        val stateTemplate = if (advanced) AppSettings.discordStateTemplate.value.ifBlank { DEFAULT_STATE } else DEFAULT_STATE
        val speedSuffix = if (abs(playback.speed - 1f) > 0.01f) " [${String.format(Locale.ROOT, "%.2fx", playback.speed)}]" else ""
        val details = fit(render(detailsTemplate, track) + speedSuffix) ?: fit(track.title) ?: return null
        val state = fit(render(stateTemplate, track)) ?: "Record Labs"
        val image = track.artworkUrl?.takeIf { (it.startsWith("https://") || it.startsWith("http://")) && it.length <= 256 }
        // A YouTube video id is needed for the "listen" link; local files have none.
        val hasOnlineId = track.localPath == null && track.id.isNotBlank()
        // Settings > Discord > Show a button (off by default, in either mode).
        val buttonWanted = hasOnlineId && AppSettings.discordButtonEnabled.value
        val label = if (advanced) AppSettings.discordButtonLabel.value.ifBlank { DEFAULT_BUTTON_LABEL } else DEFAULT_BUTTON_LABEL
        val url = if (advanced) AppSettings.discordButtonUrl.value.ifBlank { DEFAULT_BUTTON_URL } else DEFAULT_BUTTON_URL
        val renderedUrl = render(url, track).trim().takeIf { it.startsWith("https://") || it.startsWith("http://") }?.take(512)
        val speed = playback.speed.coerceAtLeast(0.1f)
        val start = if (playback.isPlaying) nowMs - (playback.positionMs / speed).toLong() else null
        val end = if (playback.isPlaying && playback.durationMs > 0) nowMs + ((playback.durationMs - playback.positionMs).coerceAtLeast(0L) / speed).toLong() else null
        return DiscordActivityContent(
            details = details,
            state = state,
            largeImage = image,
            largeText = track.album.takeIf(String::isNotBlank)?.let { fit(it) },
            buttonLabel = if (buttonWanted && renderedUrl != null) fit(render(label, track), 32) else null,
            buttonUrl = if (buttonWanted) renderedUrl else null,
            startMs = start,
            endMs = end,
        )
    }

    /** 0 = app name, 1 = state (artist), 2 = details (song) in Discord's member list. */
    private fun statusDisplayType(): Int = when (AppSettings.discordStatusDisplay.value) {
        "name" -> 0
        "details" -> 2
        else -> 1
    }

    private data class SentKey(val trackId: String?, val content: DiscordActivityContent?, val display: Int)

    // ---- Worker ----

    private suspend fun runWorker() {
        remoteBackend?.let { return runRemoteWorker(it) }
        var connection: DiscordIpcConnection? = null
        var sent: SentKey? = null
        var lastSendAt = 0L
        var pausedSince: Long? = null
        var simpleActivity = false
        while (true) {
            if (!AppSettings.discordEnabled.value) {
                connection?.let { runCatching { it.setActivity(null, simpleActivity) }; it.close() }
                connection = null
                sent = null
                _state.value = DiscordConnectionState.Off
                wake.receive()
                continue
            }
            if (connection == null) {
                _state.value = DiscordConnectionState.Connecting
                connection = runCatching { DiscordIpcConnection.open(APPLICATION_ID) }.getOrNull()
                if (connection == null) {
                    _state.value = DiscordConnectionState.DiscordNotRunning
                    withTimeoutOrNull(RECONNECT_MS) { wake.receive() }
                    continue
                }
                _state.value = DiscordConnectionState.Connected(connection.userName, connection.avatarUrl)
                sent = null
                simpleActivity = false
            }
            if (retryFullActivity) {
                retryFullActivity = false
                simpleActivity = false
            }
            val playback = latest.get()
            val now = System.currentTimeMillis()
            pausedSince = when {
                playback?.track == null -> null
                playback.isPlaying -> null
                else -> pausedSince ?: now
            }
            val cleared = pausedSince?.let { now - it >= PAUSE_CLEAR_MS } == true
            val activity = if (playback == null || cleared) null else content(playback, now)
            val display = statusDisplayType()
            val key = SentKey(playback?.track?.id, activity, display)
            val previous = sent
            val needsSend = resendRequested || previous == null || previous.trackId != key.trackId ||
                previous.display != display || !sameContent(previous.content, activity)
            try {
                if (needsSend) {
                    val wait = lastSendAt + MIN_SEND_GAP_MS - System.currentTimeMillis()
                    if (wait > 0) {
                        // Wait out the rate limit, then send whatever is newest by then.
                        delay(wait)
                        continue
                    }
                    resendRequested = false
                    // Counted before sending, so a failing pipe cannot make the loop resend without a pause.
                    lastSendAt = System.currentTimeMillis()
                    val ok = connection.setActivity(activity?.let { payloadFor(it, display, simpleActivity) }, simpleActivity)
                    if (!ok && !simpleActivity) {
                        // An older Discord build: retry once with the plainest activity.
                        simpleActivity = true
                        connection.setActivity(activity?.let { payloadFor(it, display, true) }, true)
                    }
                    lastSendAt = System.currentTimeMillis()
                    sent = key
                }
                connection.serviceIncoming()
                _state.value = DiscordConnectionState.Connected(connection.userName, connection.avatarUrl)
            } catch (_: IOException) {
                connection.close()
                connection = null
                _state.value = DiscordConnectionState.DiscordNotRunning
                delay(3_000L)
                continue
            }
            // Wake on the next change, or check again in a few seconds (pause timeout, Discord closing).
            withTimeoutOrNull(5_000L) { wake.receive() }
        }
    }

    /** Android: the same as [runWorker], through [DiscordRemoteBackend] instead of the pipe. */
    private suspend fun runRemoteWorker(backend: DiscordRemoteBackend) {
        var sent: SentKey? = null
        var lastSendAt = 0L
        var pausedSince: Long? = null
        var wasConnected = false
        var running = false
        while (true) {
            if (!AppSettings.discordEnabled.value) {
                if (wasConnected) runCatching { backend.setActivity(null, statusDisplayType(), null, false) }
                if (running) runCatching { backend.stop() }
                running = false
                wasConnected = false
                sent = null
                _state.value = DiscordConnectionState.Off
                wake.receive()
                continue
            }
            if (!running) {
                running = true
                runCatching { backend.start() }
            }
            val connectionState = runCatching { backend.state() }.getOrElse { DiscordConnectionState.Failed(it.message ?: "Discord error") }
            _state.value = connectionState
            if (connectionState !is DiscordConnectionState.Connected) {
                wasConnected = false
                sent = null
                withTimeoutOrNull(5_000L) { wake.receive() }
                continue
            }
            // Connected again (a new session shows nothing): everything goes again.
            if (!wasConnected) {
                wasConnected = true
                sent = null
            }
            val playback = latest.get()
            val now = System.currentTimeMillis()
            pausedSince = when {
                playback?.track == null -> null
                playback.isPlaying -> null
                else -> pausedSince ?: now
            }
            val cleared = pausedSince?.let { now - it >= PAUSE_CLEAR_MS } == true
            val activity = if (playback == null || cleared) null else content(playback, now)
            val display = statusDisplayType()
            val key = SentKey(playback?.track?.id, activity, display)
            val previous = sent
            val needsSend = resendRequested || previous == null || previous.trackId != key.trackId ||
                previous.display != display || !sameContent(previous.content, activity)
            if (needsSend) {
                val wait = lastSendAt + MIN_SEND_GAP_MS - System.currentTimeMillis()
                if (wait > 0) {
                    delay(wait)
                    continue
                }
                resendRequested = false
                lastSendAt = System.currentTimeMillis()
                val ok = runCatching { backend.setActivity(activity, display, playback?.track?.id, playback?.isPlaying == true) }.getOrDefault(false)
                sent = if (ok) key else null
            }
            withTimeoutOrNull(5_000L) { wake.receive() }
        }
    }

    /** Same texts, and timestamps within the seek tolerance (they shift by a few ms every report). */
    private fun sameContent(a: DiscordActivityContent?, b: DiscordActivityContent?): Boolean {
        if (a == null || b == null) return a == b
        fun close(x: Long?, y: Long?) = (x == null && y == null) || (x != null && y != null && abs(x - y) <= SEEK_TOLERANCE_MS)
        return a.copy(startMs = null, endMs = null) == b.copy(startMs = null, endMs = null) &&
            close(a.startMs, b.startMs) && close(a.endMs, b.endMs)
    }

    internal fun payloadFor(content: DiscordActivityContent, display: Int, simple: Boolean): JsonObject = buildJsonObject {
        if (!simple) {
            put("type", 2) // Listening
            put("status_display_type", display)
        }
        put("details", content.details)
        put("state", content.state)
        if (content.startMs != null || content.endMs != null) put("timestamps", buildJsonObject {
            content.startMs?.let { put("start", it) }
            content.endMs?.let { put("end", it) }
        })
        if (content.largeImage != null) put("assets", buildJsonObject {
            put("large_image", content.largeImage)
            content.largeText?.let { put("large_text", it) }
        })
        if (!simple && content.buttonLabel != null && content.buttonUrl != null) put("buttons", buildJsonArray {
            add(buildJsonObject {
                put("label", content.buttonLabel)
                put("url", content.buttonUrl)
            })
        })
    }

    internal fun decode(text: String): JsonObject? = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
}

/** The local Discord RPC connection: handshake, then SET_ACTIVITY commands. */
internal class DiscordIpcConnection private constructor(private val transport: DiscordIpcTransport) : Closeable {
    var userName: String? = null
        private set
    var avatarUrl: String? = null
        private set

    companion object {
        private const val OP_HANDSHAKE = 0
        private const val OP_FRAME = 1
        private const val OP_CLOSE = 2
        private const val OP_PING = 3
        private const val OP_PONG = 4

        fun open(clientId: String): DiscordIpcConnection? {
            for (transport in DiscordIpcTransport.candidates()) {
                val connection = DiscordIpcConnection(transport)
                val ready = runCatching { connection.handshake(clientId) }.getOrDefault(false)
                if (ready) return connection
                connection.close()
            }
            return null
        }
    }

    private fun handshake(clientId: String): Boolean {
        send(OP_HANDSHAKE, buildJsonObject {
            put("v", 1)
            put("client_id", clientId)
        }.toString())
        val deadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < deadline) {
            val (op, body) = readFrame(deadline - System.currentTimeMillis()) ?: return false
            if (op == OP_CLOSE) return false
            if (op == OP_PING) { send(OP_PONG, body); continue }
            val message = DiscordRichPresence.decode(body) ?: continue
            if (message["evt"]?.jsonPrimitive?.contentOrNull == "READY") {
                val user = message["data"]?.jsonObject?.get("user") as? JsonObject
                userName = user?.get("global_name")?.jsonPrimitive?.contentOrNull?.takeIf(String::isNotBlank)
                    ?: user?.get("username")?.jsonPrimitive?.contentOrNull
                val id = user?.get("id")?.jsonPrimitive?.contentOrNull
                val avatar = user?.get("avatar")?.jsonPrimitive?.contentOrNull
                avatarUrl = if (id != null && avatar != null) "https://cdn.discordapp.com/avatars/$id/$avatar.png?size=128" else null
                return true
            }
        }
        return false
    }

    /** Sends the activity (null clears it); false when Discord rejected it. */
    fun setActivity(activity: JsonObject?, @Suppress("UNUSED_PARAMETER") simple: Boolean): Boolean {
        val nonce = UUID.randomUUID().toString()
        send(OP_FRAME, buildJsonObject {
            put("cmd", "SET_ACTIVITY")
            put("nonce", nonce)
            put("args", buildJsonObject {
                put("pid", ProcessHandle.current().pid())
                if (activity != null) put("activity", activity) else put("activity", kotlinx.serialization.json.JsonNull)
            })
        }.toString())
        val deadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < deadline) {
            val (op, body) = readFrame(deadline - System.currentTimeMillis()) ?: return true
            when (op) {
                OP_CLOSE -> throw IOException("Discord closed the connection")
                OP_PING -> send(OP_PONG, body)
                OP_FRAME -> {
                    val message = DiscordRichPresence.decode(body) ?: continue
                    if (message["nonce"]?.jsonPrimitive?.contentOrNull != nonce) continue
                    return message["evt"]?.jsonPrimitive?.contentOrNull != "ERROR"
                }
            }
        }
        return true
    }

    /** Answers pings and notices Discord closing, without waiting. */
    fun serviceIncoming() {
        while (transport.available() >= 8) {
            val (op, body) = readFrame(500L) ?: return
            when (op) {
                OP_CLOSE -> throw IOException("Discord closed the connection")
                OP_PING -> send(OP_PONG, body)
            }
        }
    }

    private fun send(op: Int, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val frame = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        frame.putInt(op).putInt(payload.size).put(payload)
        transport.write(frame.array())
    }

    private fun readFrame(timeoutMs: Long): Pair<Int, String>? {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceAtLeast(0L)
        while (transport.available() < 8) {
            if (System.currentTimeMillis() >= deadline) return null
            Thread.sleep(15)
        }
        val header = ByteBuffer.wrap(transport.read(8)).order(ByteOrder.LITTLE_ENDIAN)
        val op = header.int
        val length = header.int
        if (length < 0 || length > 1_000_000) throw IOException("Bad Discord frame")
        return op to String(transport.read(length), Charsets.UTF_8)
    }

    override fun close() = transport.close()
}

/** A byte stream to the Discord app: the Windows named pipe, or a Unix socket elsewhere. */
internal interface DiscordIpcTransport : Closeable {
    fun write(bytes: ByteArray)
    /** Bytes ready to read without blocking (throws when the connection is gone). */
    fun available(): Int
    /** Reads exactly [count] bytes. */
    fun read(count: Int): ByteArray

    companion object {
        fun candidates(): Sequence<DiscordIpcTransport> = sequence {
            val windows = System.getProperty("os.name").orEmpty().lowercase(Locale.ROOT).contains("win")
            for (index in 0..9) {
                val transport = if (windows) {
                    openWindowsDiscordPipe("\\\\.\\pipe\\discord-ipc-$index")
                } else {
                    unixSocketPaths(index).firstNotNullOfOrNull { path -> runCatching { UnixSocketTransport(path) }.getOrNull() }
                }
                if (transport != null) yield(transport)
            }
        }

        private fun unixSocketPaths(index: Int): List<String> {
            val bases = listOfNotNull(
                System.getenv("XDG_RUNTIME_DIR"), System.getenv("TMPDIR"), System.getenv("TMP"), System.getenv("TEMP"), "/tmp",
            ).distinct()
            return bases.flatMap { base ->
                listOf("$base/discord-ipc-$index", "$base/app/com.discordapp.Discord/discord-ipc-$index", "$base/snap.discord/discord-ipc-$index")
            }.filter { java.io.File(it).exists() }
        }
    }
}

private class UnixSocketTransport(path: String) : DiscordIpcTransport {
    private val channel = java.nio.channels.SocketChannel.open(java.net.UnixDomainSocketAddress.of(path)).apply { configureBlocking(false) }
    private var buffered = ByteArray(0)

    private fun fill() {
        val buffer = ByteBuffer.allocate(16_384)
        while (true) {
            buffer.clear()
            val read = channel.read(buffer)
            if (read < 0) throw IOException("Discord socket closed")
            if (read == 0) return
            buffered += buffer.array().copyOf(read)
        }
    }

    override fun write(bytes: ByteArray) {
        val buffer = ByteBuffer.wrap(bytes)
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) Thread.sleep(5)
        }
    }

    override fun available(): Int {
        fill()
        return buffered.size
    }

    override fun read(count: Int): ByteArray {
        val deadline = System.currentTimeMillis() + 3_000L
        while (buffered.size < count) {
            fill()
            if (buffered.size >= count) break
            if (System.currentTimeMillis() > deadline) throw IOException("Discord socket read timed out")
            Thread.sleep(5)
        }
        val result = buffered.copyOf(count)
        buffered = buffered.copyOfRange(count, buffered.size)
        return result
    }

    override fun close() {
        runCatching { channel.close() }
    }
}

/** The Discord app's named pipe on Windows (null when it is not there, and on Android). */
internal expect fun openWindowsDiscordPipe(path: String): DiscordIpcTransport?
