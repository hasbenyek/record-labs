@file:Suppress("FunctionName", "LocalVariableName")

package com.recordlabs.desktop.data

import org.freedesktop.dbus.DBusPath
import org.freedesktop.dbus.annotations.DBusInterfaceName
import org.freedesktop.dbus.connections.impl.DBusConnection
import org.freedesktop.dbus.connections.impl.DBusConnectionBuilder
import org.freedesktop.dbus.interfaces.DBusInterface
import org.freedesktop.dbus.interfaces.Properties
import org.freedesktop.dbus.types.Variant
import java.util.concurrent.Executors

/**
 * MPRIS on Linux: the D-Bus interface desktops use for media players.
 *
 * Linux desktops (COSMIC, GNOME, KDE...) send the keyboard's play/pause,
 * next, previous and stop keys to the active MPRIS player instead of to the
 * focused window, so this is what makes those keys work, also while Record
 * Labs is in the background. The desktop's media controls (panel/lock screen)
 * show the current song and control it too. Windows gets the same keys from
 * the native helper.
 */
object LinuxMpris {
    private const val BUS_NAME = "org.mpris.MediaPlayer2.recordlabs"
    private const val OBJECT_PATH = "/org/mpris/MediaPlayer2"
    private const val ROOT_INTERFACE = "org.mpris.MediaPlayer2"
    private const val PLAYER_INTERFACE = "org.mpris.MediaPlayer2.Player"

    /** "play_pause", "play", "pause", "next", "previous", "stop" (the native helper's command names). */
    @Volatile var onCommand: ((String) -> Unit)? = null
    @Volatile var onSeekTo: ((Long) -> Unit)? = null
    @Volatile var onRaise: (() -> Unit)? = null
    /** The live playback position in ms, read when a desktop asks for it. */
    @Volatile var positionProvider: (() -> Long)? = null

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "record-labs-mpris").apply { isDaemon = true } }
    @Volatile private var connection: DBusConnection? = null
    @Volatile private var track: Track? = null
    @Volatile private var durationMs = 0L
    @Volatile private var playing = false

    fun start() {
        if (Platform.isWindows || connection != null) return
        worker.execute {
            if (connection != null) return@execute
            runCatching {
                val bus = DBusConnectionBuilder.forSessionBus().build()
                bus.exportObject(OBJECT_PATH, Player)
                bus.requestBusName(BUS_NAME)
                connection = bus
            }.onFailure { println("[MPRIS] not available: ${it.message}") }
        }
    }

    fun stop() {
        worker.execute {
            runCatching { connection?.releaseBusName(BUS_NAME) }
            runCatching { connection?.close() }
            connection = null
        }
    }

    /** Called when the song, its length or play/pause changes; tells listening desktops. */
    fun update(track: Track?, isPlaying: Boolean, durationMs: Long) {
        val changedTrack = track?.id != this.track?.id || durationMs != this.durationMs
        val changedState = isPlaying != playing
        this.track = track
        this.durationMs = durationMs
        this.playing = isPlaying
        if (!changedTrack && !changedState) return
        worker.execute {
            val bus = connection ?: return@execute
            val changed = buildMap<String, Variant<*>> {
                put("PlaybackStatus", Variant(playbackStatus()))
                if (changedTrack) put("Metadata", Variant(metadata(), "a{sv}"))
                put("CanPlay", Variant(this@LinuxMpris.track != null))
                put("CanPause", Variant(this@LinuxMpris.track != null))
                put("CanSeek", Variant(this@LinuxMpris.track != null))
            }
            runCatching { bus.sendMessage(Properties.PropertiesChanged(OBJECT_PATH, PLAYER_INTERFACE, changed, emptyList())) }
        }
    }

    /** Tells desktops the song jumped (seek); they otherwise extrapolate the position themselves. */
    fun seeked(positionMs: Long) {
        worker.execute {
            val bus = connection ?: return@execute
            runCatching { bus.sendMessage(MprisPlayer.Seeked(OBJECT_PATH, positionMs * 1000L)) }
        }
    }

    private fun playbackStatus() = when {
        track == null -> "Stopped"
        playing -> "Playing"
        else -> "Paused"
    }

    private fun metadata(): Map<String, Variant<*>> {
        val current = track ?: return mapOf("mpris:trackid" to Variant(DBusPath("/org/mpris/MediaPlayer2/TrackList/NoTrack")))
        val safeId = current.id.replace(Regex("[^A-Za-z0-9_]"), "_").ifBlank { "track" }
        return buildMap {
            put("mpris:trackid", Variant(DBusPath("/com/recordlabs/track/$safeId")))
            put("xesam:title", Variant(current.title))
            put("xesam:artist", Variant(listOf(current.artist).filter(String::isNotBlank).ifEmpty { listOf("") }, "as"))
            if (current.album.isNotBlank()) put("xesam:album", Variant(current.album))
            val length = durationMs.takeIf { it > 0 } ?: current.durationMs
            if (length > 0) put("mpris:length", Variant(length * 1000L))
            current.artworkUrl?.takeIf(String::isNotBlank)?.let { put("mpris:artUrl", Variant(it)) }
        }
    }

    private fun rootProperties(): Map<String, Variant<*>> = mapOf(
        "CanQuit" to Variant(false),
        "CanRaise" to Variant(true),
        "HasTrackList" to Variant(false),
        "Identity" to Variant("Record Labs"),
        "DesktopEntry" to Variant("record-labs"),
        "SupportedUriSchemes" to Variant(emptyList<String>(), "as"),
        "SupportedMimeTypes" to Variant(emptyList<String>(), "as"),
    )

    private fun playerProperties(): Map<String, Variant<*>> {
        val hasTrack = track != null
        return mapOf(
            "PlaybackStatus" to Variant(playbackStatus()),
            "Rate" to Variant(1.0),
            "MinimumRate" to Variant(1.0),
            "MaximumRate" to Variant(1.0),
            "Metadata" to Variant(metadata(), "a{sv}"),
            "Volume" to Variant(1.0),
            "Position" to Variant((positionProvider?.invoke() ?: 0L) * 1000L),
            "CanGoNext" to Variant(hasTrack),
            "CanGoPrevious" to Variant(hasTrack),
            "CanPlay" to Variant(hasTrack),
            "CanPause" to Variant(hasTrack),
            "CanSeek" to Variant(hasTrack),
            "CanControl" to Variant(true),
        )
    }

    private fun command(name: String) {
        onCommand?.invoke(name)
    }

    /** The exported object: both MPRIS interfaces plus their properties. */
    private object Player : MprisRoot, MprisPlayer, Properties {
        override fun getObjectPath(): String = OBJECT_PATH
        override fun isRemote(): Boolean = false

        override fun Raise() { onRaise?.invoke() }
        override fun Quit() = Unit

        override fun Next() = command("next")
        override fun Previous() = command("previous")
        override fun Pause() = command("pause")
        override fun PlayPause() = command("play_pause")
        override fun Stop() = command("stop")
        override fun Play() = command("play")
        override fun Seek(Offset: Long) {
            val target = ((positionProvider?.invoke() ?: 0L) + Offset / 1000L).coerceAtLeast(0L)
            onSeekTo?.invoke(target)
            seeked(target)
        }
        override fun SetPosition(TrackId: DBusPath, Position: Long) {
            val target = (Position / 1000L).coerceAtLeast(0L)
            onSeekTo?.invoke(target)
            seeked(target)
        }
        override fun OpenUri(Uri: String) = Unit

        // Returned as the Variant itself so its D-Bus type ("as", "a{sv}"...) is kept.
        @Suppress("UNCHECKED_CAST")
        override fun <A : Any?> Get(interfaceName: String, propertyName: String): A =
            GetAll(interfaceName)[propertyName] as A

        override fun <A : Any?> Set(interfaceName: String, propertyName: String, value: A) = Unit

        override fun GetAll(interfaceName: String): Map<String, Variant<*>> = when (interfaceName) {
            ROOT_INTERFACE -> rootProperties()
            PLAYER_INTERFACE -> playerProperties()
            else -> emptyMap()
        }
    }
}

@DBusInterfaceName("org.mpris.MediaPlayer2")
interface MprisRoot : DBusInterface {
    fun Raise()
    fun Quit()
}

@DBusInterfaceName("org.mpris.MediaPlayer2.Player")
interface MprisPlayer : DBusInterface {
    fun Next()
    fun Previous()
    fun Pause()
    fun PlayPause()
    fun Stop()
    fun Play()
    fun Seek(Offset: Long)
    fun SetPosition(TrackId: DBusPath, Position: Long)
    fun OpenUri(Uri: String)

    class Seeked(path: String, val Position: Long) : org.freedesktop.dbus.messages.DBusSignal(path, Position)
}
