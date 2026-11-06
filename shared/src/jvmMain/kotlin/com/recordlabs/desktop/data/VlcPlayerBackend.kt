package com.recordlabs.desktop.data

import com.recordlabs.desktop.ensureJavaFxMediaNatives
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.base.State
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

/**
 * The audio engine used where the Windows media helper isn't available (Linux).
 *
 * [WindowsMediaPlayer] talks to the Windows helper with small commands
 * ("play", "pause", "seek", "status"...) and reads a status JSON back. This
 * backend answers exactly the same commands with the same JSON, in-process,
 * using libVLC. That keeps all of WindowsMediaPlayer's playback logic (loading
 * state, retries, end-of-track handling) identical on both systems.
 */
internal class VlcPlayerBackend {
    private val gate = Any()

    @Volatile private var factory: MediaPlayerFactory? = null
    @Volatile private var player: MediaPlayer? = null
    @Volatile private var factoryPitch = 0

    // Mirrors the Windows helper's NativeState.
    private var isPlaying = false
    private var isReady = false
    private var positionMs = 0L
    private var durationMs = 0L
    private var error: String? = null
    private var ended = false
    private var externalCommand: String? = null
    private val externalCommandSequence = AtomicLong()
    private var lastExternalCommandAt = 0L

    @Volatile private var wantsToPlay = false
    @Volatile private var desiredTempo = 1.0
    @Volatile private var desiredPitch = 0
    @Volatile private var desiredVolume = 1.0
    @Volatile private var currentUrl: String? = null

    /** Packets for Record Labs Connect over Bluetooth (filled once the Linux Bluetooth link exists). */
    val bluetoothPackets = ConcurrentLinkedQueue<String>()

    val isStarted: Boolean get() = player != null

    /** Loads libVLC and creates the player. Returns false when VLC can't be loaded. */
    fun start(): Boolean = synchronized(gate) {
        if (player != null) return true
        return runCatching {
            ensureJavaFxMediaNatives()
            createPlayer(desiredPitch)
            true
        }.getOrElse {
            error = "VLC tidak bisa dimuat: ${it.message ?: it.javaClass.simpleName}"
            println("VlcPlayerBackend: ${it.stackTraceToString()}")
            false
        }
    }

    private fun createPlayer(pitch: Int) {
        useSteadyAudioOutput()
        val args = mutableListOf(
            "--intf=dummy", "--no-video", "--no-osd", "--no-stats", "--quiet",
            // A little more read-ahead before the first note, so a slow first
            // second from the network no longer cuts out right after the start.
            "--network-caching=1500", "--file-caching=500", "--http-reconnect",
            // Tempo changes keep the original pitch (like the Windows helper).
            "--audio-time-stretch",
        )
        args += audioOutputArgs()
        if (pitch != 0) {
            // Pitch moves in semitones without changing tempo.
            args += "--audio-filter=scaletempo_pitch"
            args += "--pitch-shift=$pitch"
        }
        val newFactory = MediaPlayerFactory(args)
        val newPlayer = newFactory.mediaPlayers().newMediaPlayer()
        newPlayer.events().addMediaPlayerEventListener(events)
        factory = newFactory
        player = newPlayer
        factoryPitch = pitch
    }

    /**
     * Linux sound servers (PipeWire, PulseAudio) start VLC's stream with a very
     * small buffer; the first second of a song then came out silent, then in
     * bursts with a dropout before playing smoothly. A 60 ms buffer (still
     * unnoticeable) avoids that. Read by the sound library when a stream opens;
     * a value the user already set is kept.
     */
    private fun useSteadyAudioOutput() {
        if (Platform.isWindows || !System.getenv("PULSE_LATENCY_MSEC").isNullOrBlank()) return
        runCatching { uk.co.caprica.vlcj.binding.lib.LibC.INSTANCE.setenv("PULSE_LATENCY_MSEC", "60", 0) }
    }

    /**
     * Which VLC audio output to use on Linux.
     *
     * Fedora, Debian and most current desktops run PipeWire. VLC's "pulse"
     * output starts a song by scheduling it to the exact millisecond over a
     * thin (~120 ms) buffer; when the sound card has just woken up (PipeWire
     * suspends it after a few idle seconds) that schedule is missed, VLC then
     * drops / resamples audio to catch up and the first seconds stutter. VLC's
     * "alsa" output starts at once with a deep buffer and still plays through
     * PipeWire (pipewire-alsa routes ALSA's default device into it), so it is
     * used whenever that route is set up; "pulse" stays the fallback if ALSA
     * can't be opened. Plain PulseAudio systems keep the pulse output.
     * RECORDLABS_AOUT=<module> forces an output (for troubleshooting).
     */
    private fun audioOutputArgs(): List<String> {
        if (Platform.isWindows) return emptyList()
        System.getenv("RECORDLABS_AOUT")?.trim()?.takeIf(String::isNotEmpty)?.let { return listOf("--aout=$it") }
        return if (pipeWireAlsaDefault()) listOf("--aout=alsa,pulse") else emptyList()
    }

    /** PipeWire is running and ALSA's default device goes to it (the pipewire-alsa package). */
    private fun pipeWireAlsaDefault(): Boolean = runCatching {
        val runtimeDir = System.getenv("XDG_RUNTIME_DIR")?.takeIf(String::isNotBlank) ?: return@runCatching false
        val pipeWireRunning = java.io.File(runtimeDir, "pipewire-0").exists()
        // alsa-lib reads both folders; a (symlinked) 99-pipewire-default.conf makes PipeWire the default device.
        val alsaDefaultIsPipeWire = listOf(
            "/etc/alsa/conf.d/99-pipewire-default.conf",
            "/usr/share/alsa/alsa.conf.d/99-pipewire-default.conf",
        ).any { java.io.File(it).exists() }
        pipeWireRunning && alsaDefaultIsPipeWire
    }.getOrDefault(false)

    private val events = object : MediaPlayerEventAdapter() {
        override fun playing(mediaPlayer: MediaPlayer) = update(playing = wantsToPlay, ready = true, ended = false)
        override fun paused(mediaPlayer: MediaPlayer) = update(playing = false)
        override fun stopped(mediaPlayer: MediaPlayer) = update(playing = false)
        override fun finished(mediaPlayer: MediaPlayer) {
            wantsToPlay = false
            update(playing = false, ended = true)
        }
        override fun error(mediaPlayer: MediaPlayer) {
            wantsToPlay = false
            update(playing = false, ready = false, error = "VLC gagal memutar sumber audio ini.", ended = false)
        }
        override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) {
            if (newLength > 0) update(duration = newLength)
        }
        override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) = update(position = newTime)
    }

    /**
     * Handles one command with the Windows helper's parameters and returns its
     * JSON reply. Throws on failure, like an HTTP 500 from the helper.
     */
    fun handle(path: String, parameters: Map<String, String>): String {
        val player = player ?: throw IllegalStateException(error ?: "Player VLC belum siap")
        try {
            return when (path.trim('/').lowercase(Locale.ROOT)) {
                "play" -> {
                    val url = parameters["url"]?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("Missing url")
                    val startMs = parameters["positionMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    wantsToPlay = true
                    currentUrl = url
                    update(position = startMs, duration = 0, playing = true, ready = false, error = null, ended = false)
                    val options = if (startMs > 0) arrayOf(":start-time=${startMs / 1000.0}") else emptyArray()
                    if (!player.media().play(playableMrl(url), *options)) throw IllegalStateException("VLC tidak bisa membuka sumber audio")
                    applyVolume(player)
                    applyTempo(player)
                    ok()
                }
                "pause" -> {
                    wantsToPlay = false
                    player.controls().setPause(true)
                    update(playing = false)
                    ok()
                }
                "resume" -> {
                    synchronized(gate) {
                        if (!error.isNullOrBlank()) throw IllegalStateException("Reload the track after a playback failure.")
                    }
                    wantsToPlay = true
                    if (player.status().state() == State.ENDED || player.status().state() == State.STOPPED) {
                        player.controls().play()
                    } else {
                        player.controls().setPause(false)
                    }
                    update(playing = true, ended = false)
                    ok()
                }
                "seek" -> {
                    val position = parameters["positionMs"]?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
                    player.controls().setTime(position)
                    update(position = position)
                    ok()
                }
                "rate" -> {
                    desiredTempo = (parameters["value"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.5, 2.0)
                    retune(0)
                    ok()
                }
                "tuning" -> {
                    desiredTempo = (parameters["tempo"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.5, 2.0)
                    retune((parameters["pitch"]?.toIntOrNull() ?: 0).coerceIn(-12, 12))
                    ok()
                }
                "volume" -> {
                    desiredVolume = (parameters["value"]?.toDoubleOrNull() ?: 1.0).coerceIn(0.0, 1.0)
                    applyVolume(player)
                    ok()
                }
                "stop" -> {
                    wantsToPlay = false
                    currentUrl = null
                    player.controls().stop()
                    update(position = 0, duration = 0, playing = false, ready = false, ended = false)
                    ok()
                }
                "exit", "shutdown" -> {
                    shutdown()
                    ok()
                }
                "status" -> status(player)
                "connect-bluetooth-send" -> ok() // Linux Bluetooth link: later phase.
                else -> throw IllegalArgumentException("Unknown command")
            }
        } catch (failure: Exception) {
            update(playing = false, error = failure.message ?: failure.javaClass.simpleName)
            throw failure
        }
    }

    /** Media keys / desktop controls (MPRIS) report here, like the Windows helper's ExternalCommand. */
    fun emitExternalCommand(command: String) {
        synchronized(gate) {
            val now = System.currentTimeMillis()
            if (externalCommand == command && now - lastExternalCommandAt < 280) return
            externalCommand = command
            lastExternalCommandAt = now
            externalCommandSequence.incrementAndGet()
        }
    }

    fun shutdown(): Unit = synchronized(gate) {
        val oldPlayer = player
        val oldFactory = factory
        player = null
        factory = null
        runCatching { oldPlayer?.controls()?.stop() }
        runCatching { oldPlayer?.release() }
        runCatching { oldFactory?.release() }
    }

    private fun status(player: MediaPlayer): String {
        // Like the helper's RefreshPosition: read the live clock and state.
        val state = player.status().state()
        val active = state == State.PLAYING || state == State.BUFFERING || state == State.OPENING
        val time = player.status().time().takeIf { it >= 0 }
        val length = player.status().length().takeIf { it > 0 }
        update(position = time, duration = length, playing = wantsToPlay && active)
        val packets = buildList { while (true) add(bluetoothPackets.poll() ?: break) }
        return synchronized(gate) {
            buildJsonObject {
                put("IsPlaying", isPlaying)
                put("IsReady", isReady)
                put("PositionMs", positionMs)
                put("DurationMs", durationMs)
                put("Error", error?.let(::JsonPrimitive) ?: JsonNull)
                put("Ended", ended)
                put("ExternalCommand", externalCommand?.let(::JsonPrimitive) ?: JsonNull)
                put("ExternalCommandSequence", externalCommandSequence.get())
                put("BluetoothPackets", JsonArray(packets.map(::JsonPrimitive)))
            }.toString()
        }
    }

    private fun applyVolume(player: MediaPlayer) {
        player.audio().setVolume((desiredVolume * 100).roundToInt().coerceIn(0, 100))
    }

    private fun applyTempo(player: MediaPlayer) {
        player.controls().setRate(desiredTempo.toFloat())
    }

    /**
     * Tempo changes apply at once. libVLC reads the pitch shift only when the
     * player is created, so a new pitch rebuilds it and continues the current
     * song from the same position.
     */
    private fun retune(pitch: Int) {
        desiredPitch = pitch
        val current = player ?: return
        if (pitch == factoryPitch) {
            applyTempo(current)
            return
        }
        val url = currentUrl
        val resumeAt = current.status().time().coerceAtLeast(0L)
        val keepPlaying = wantsToPlay
        synchronized(gate) {
            runCatching { current.controls().stop() }
            runCatching { current.release() }
            runCatching { factory?.release() }
            player = null
            factory = null
            createPlayer(pitch)
        }
        val rebuilt = player ?: return
        if (url != null) {
            val options = if (resumeAt > 0) arrayOf(":start-time=${resumeAt / 1000.0}") else emptyArray()
            rebuilt.media().play(playableMrl(url), *options)
            if (!keepPlaying) rebuilt.controls().setPause(true)
        }
        applyVolume(rebuilt)
        applyTempo(rebuilt)
    }

    private fun update(
        position: Long? = null,
        duration: Long? = null,
        playing: Boolean? = null,
        ready: Boolean? = null,
        error: String? = null,
        ended: Boolean? = null,
    ) {
        synchronized(gate) {
            if (position != null) positionMs = position
            if (duration != null) durationMs = duration
            if (playing != null) isPlaying = playing
            if (ready != null) isReady = ready
            // Same rule as the helper: an error or an ended flag replaces the error text.
            if (error != null || ended != null) this.error = error
            if (ended != null) this.ended = ended
        }
    }

    private fun ok(): String = """{"ok":true}"""

    /**
     * Java's File.toURI() gives "file:/home/me/a.mp3" (one slash) and may keep
     * non-ASCII letters unescaped; the Windows helper accepts that, libVLC
     * doesn't. Local files become "file:///..." with every special character
     * percent-encoded, which VLC always decodes as UTF-8 (independent of the
     * system locale). http(s) URLs pass through unchanged.
     */
    private fun playableMrl(url: String): String {
        if (!url.startsWith("file:", ignoreCase = true)) return url
        return runCatching {
            val path = java.nio.file.Paths.get(java.net.URI(url)).toAbsolutePath()
            val encoded = path.toUri().toASCIIString() // file:///... with %XX escapes
            if (encoded.startsWith("file:///")) encoded else "file://" + encoded.removePrefix("file:")
        }.getOrElse { url }
    }
}
