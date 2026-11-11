package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.recordlabs.desktop.BackgroundPreferenceWriter
import kotlin.reflect.KProperty

/**
 * Every user setting shown in Profile > Settings, as observable state.
 *
 * A setting is read once at start-up and then kept in Compose state, so every
 * screen that shows it (Settings, the player, the lyrics menus) always agrees
 * and updates at once. Changes are written to [WindowsPreferences] on the
 * background preference thread, never on the UI thread.
 */
object AppSettings {
    class Setting<T>(
        private val key: String,
        initial: T,
        private val normalize: (T) -> T = { it },
        private val write: (T) -> Unit,
    ) {
        private val state = mutableStateOf(normalize(initial))

        var value: T
            get() = state.value
            set(newValue) {
                val normalized = normalize(newValue)
                if (state.value == normalized) return
                state.value = normalized
                BackgroundPreferenceWriter.later("setting:$key") { write(normalized) }
            }

        operator fun getValue(thisRef: Any?, property: KProperty<*>): T = value
        operator fun setValue(thisRef: Any?, property: KProperty<*>, newValue: T) {
            value = newValue
        }
    }

    private fun bool(key: String, default: Boolean) =
        Setting(key, WindowsPreferences.getBoolean(key, default)) { WindowsPreferences.putBoolean(key, it) }

    private fun string(key: String, default: String, normalize: (String) -> String = { it }) =
        Setting(key, WindowsPreferences.getString(key, default), normalize) { WindowsPreferences.putString(key, it) }

    private fun int(key: String, default: Int, range: IntRange) =
        Setting(key, WindowsPreferences.getInt(key, default), { it.coerceIn(range) }) { WindowsPreferences.putInt(key, it) }

    private fun float(key: String, default: Float, range: ClosedFloatingPointRange<Float>) =
        Setting(key, WindowsPreferences.getFloat(key, default), { it.coerceIn(range) }) { WindowsPreferences.putFloat(key, it) }

    // ---- Appearance ----
    /** Same preference the player's motion button uses (default depends on the PC). */
    val animatedArtwork = Setting("animatedArtworkEnabled", WindowsPreferences.animatedArtworkEnabled()) {
        WindowsPreferences.setAnimatedArtworkEnabled(it)
    }
    /**
     * "accent": icons and highlights take the artwork's colour (as before);
     * "flat": neutral white, no colour behind the mini player and queue;
     * "flatMini": accent colours, only the mini player without colour.
     */
    val themeStyle = string("themeStyle", "accent")
    /**
     * The iPhone's liquid glass material on Android and the desktop: the navigation and
     * mini player, menus, dialogs, round buttons and the search suggestions. Off by
     * default: the glass redraws the page under it (heavier). The iPhone always has it.
     */
    val liquidGlassMaterial = bool("liquidGlassMaterial", false)
    /**
     * Desktop: while another window is in use (the app open on a second screen), the
     * music video, animated artwork and the moving background colours pause; the music
     * and lyrics go on. On by default (much less work for the graphics card).
     */
    val pauseVisualsInBackground = bool("pauseVisualsInBackground", true)
    /** Icons and highlights in the neutral colour. */
    val flatTheme: Boolean get() = themeStyle.value == "flat"
    val flatMiniPlayer: Boolean get() = themeStyle.value == "flat" || themeStyle.value == "flatMini"
    val flatQueue: Boolean get() = themeStyle.value == "flat"
    /** "pill" (round ends, as before) or "rounded" (a box with slightly rounded corners). */
    val miniPlayerShape = string("miniPlayerShape", "pill")
    /**
     * Shape of the song position bar and of the volume bar, chosen separately:
     * "modern" = thin rounded bar without a knob (Apple Music style, default);
     * "classic" = the earlier slider with a round knob.
     */
    val seekBarStyle = string("seekBarStyle", "modern") { if (it == "classic") "classic" else "modern" }
    val volumeBarStyle = string("volumeBarStyle", "modern") { if (it == "classic") "classic" else "modern" }
    /**
     * Lyrics typeface (lyrics page, player and floating lyrics), on every OS:
     * "display" = SF Pro Display when installed, else the bundled Inter Display (default);
     * "classic" = Segoe UI (Selawik on Linux without Segoe UI).
     */
    val lyricsFontStyle = string("lyricsFontStyle", "display") { if (it == "classic") "classic" else "display" }
    /**
     * What the lyrics show while waiting for the next line: "note" = a music note
     * filling with glowing white from the bottom up (default); "ring" = the progress ring.
     */
    val lyricsWaitStyle = string("lyricsWaitStyle", "note") { if (it == "ring") "ring" else "note" }
    val lyricsTextSizePortrait = Setting("lyricsTextSize", WindowsPreferences.lyricsTextSize(false), { it.coerceIn(20f, 68f) }) {
        WindowsPreferences.setLyricsTextSize(false, it)
    }
    val lyricsTextSizeLandscape = Setting("lyricsTextSizeLandscape", WindowsPreferences.lyricsTextSize(true), { it.coerceIn(20f, 68f) }) {
        WindowsPreferences.setLyricsTextSize(true, it)
    }
    val lyricsOffsetMs = Setting("lyricsOffsetMs", WindowsPreferences.lyricsOffsetMs(), { it.coerceIn(-5_000L, 5_000L) }) {
        WindowsPreferences.setLyricsOffsetMs(it)
    }
    val lyricsPronunciation = Setting("lyricsPronunciationEnabled", WindowsPreferences.lyricsPronunciationEnabled()) {
        WindowsPreferences.setLyricsPronunciationEnabled(it)
    }

    // ---- Listen Together ----
    val listenUsername = Setting(
        "listenUsername",
        WindowsPreferences.listenUsername(),
        { it.take(32) },
    ) { WindowsPreferences.setListenUsername(it) }
    val listenAutoApproveJoins = Setting("listenAutoApproveJoins", WindowsPreferences.listenAutoApproveJoins()) {
        WindowsPreferences.setListenAutoApproveJoins(it)
    }
    val listenAutoApproveSuggestions = Setting("listenAutoApproveSuggestions", WindowsPreferences.listenAutoApproveSuggestions()) {
        WindowsPreferences.setListenAutoApproveSuggestions(it)
    }
    val listenGuestControls = Setting("listenGuestControls", WindowsPreferences.listenGuestControls()) {
        WindowsPreferences.setListenGuestControls(it)
    }
    /** Listener names whose join requests are turned down automatically (one per line). */
    val listenBlockedUsers = string("listenBlockedUsers", "")

    fun blockedListeners(): List<String> =
        listenBlockedUsers.value.lines().map(String::trim).filter(String::isNotBlank).distinctBy { it.lowercase() }

    fun isListenerBlocked(username: String): Boolean =
        username.isNotBlank() && blockedListeners().any { it.equals(username.trim(), ignoreCase = true) }

    fun blockListener(username: String) {
        val name = username.trim().takeIf(String::isNotBlank) ?: return
        listenBlockedUsers.value = (blockedListeners() + name).distinctBy { it.lowercase() }.joinToString("\n")
    }

    fun unblockListener(username: String) {
        listenBlockedUsers.value = blockedListeners().filterNot { it.equals(username, ignoreCase = true) }.joinToString("\n")
    }

    // ---- Player ----
    /** After a song ends, play the next one (off: stop after every song). */
    val autoplay = bool("autoplay", true)
    /** Add similar songs (radio) when the queue is about to end. */
    val autoLoadMore = bool("autoLoadMore", true)
    val autoSkipOnError = bool("autoSkipNextOnError", false)
    // Off by default: the player starts as it always did (volume 100 %, normal speed, no shuffle/repeat).
    val rememberVolume = bool("rememberVolume", false)
    val rememberShuffleRepeat = bool("rememberShuffleAndRepeat", false)
    val rememberSpeedPitch = bool("rememberSpeedAndPitch", false)
    /** "high" (best audio, the earlier behaviour) or "low" (saves data). */
    val audioQuality = string("audioQuality", "high")
    val savedVolume = float("playerVolume", 1f, 0f..1f)
    val savedTempo = float("playerTempo", 1f, 0.5f..2f)
    val savedPitch = int("playerPitch", 0, -12..12)
    val savedShuffle = bool("playerShuffle", false)
    val savedRepeat = int("playerRepeat", 0, 0..2)

    // ---- Stream sources ----
    /** Audio sources turned off in Settings > Stream sources (names, comma separated). */
    val disabledStreamSources = string("disabledStreamSources", "")
    val streamSourceNames = listOf("VISIONOS", "WEB_REMIX", "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "ANDROID_VR", "IOS", "NEWPIPE", "YTDLP")

    fun isStreamSourceEnabled(name: String): Boolean {
        val disabled = disabledStreamSources.value.split(',').map(String::trim).filter(String::isNotBlank).toSet()
        // Never every source off: that would make every song unplayable.
        if (streamSourceNames.all { it in disabled }) return true
        return name !in disabled
    }

    fun setStreamSourceEnabled(name: String, enabled: Boolean) {
        val disabled = disabledStreamSources.value.split(',').map(String::trim).filter(String::isNotBlank).toMutableSet()
        if (enabled) disabled -= name else disabled += name
        if (streamSourceNames.all { it in disabled }) return
        disabledStreamSources.value = disabled.sorted().joinToString(",")
    }

    // ---- Content ----
    /** YouTube Music region (gl) for Home, search and browsing. The language stays English. */
    val contentRegion = string("contentRegion", "US")
    val hideExplicit = bool("hideExplicit", false)
    /** Lyrics providers turned off in Settings > Content (names, comma separated). */
    val disabledLyricsProviders = string("disabledLyricsProviders", "")

    fun isLyricsProviderEnabled(name: String): Boolean =
        name !in disabledLyricsProviders.value.split(',').map(String::trim).toSet()

    fun setLyricsProviderEnabled(name: String, enabled: Boolean) {
        val disabled = disabledLyricsProviders.value.split(',').map(String::trim).filter(String::isNotBlank).toMutableSet()
        if (enabled) disabled -= name else disabled += name
        // At least one provider stays on, or lyrics could never load.
        if (WindowsLyricsProviderRegistry.allProviders.all { it in disabled }) return
        disabledLyricsProviders.value = disabled.sorted().joinToString(",")
    }

    // ---- Privacy ----
    val pauseListenHistory = bool("pauseListenHistory", false)
    val pauseSearchHistory = bool("pauseSearchHistory", false)
    /** Seconds a song must really play before it counts as a listen (Android's "history duration"). */
    val historyMinSeconds = int("historyMinSeconds", 30, 1..100)

    // ---- Storage ----
    /** Largest size of the temporary playback cache in MB; -1 = no limit (the earlier behaviour). */
    val songCacheLimitMb = int("songCacheLimitMb", -1, -1..65_536)

    // ---- Last.fm ----
    val lastFmApiKey = string("lastFmApiKey", "") { it.trim() }
    val lastFmSecret = string("lastFmSecret", "") { it.trim() }
    val lastFmUsername = string("lastFmUsername", "")
    val lastFmSessionKey = string("lastFmSessionKey", "")
    val lastFmScrobbling = bool("lastFmScrobbling", false)
    val lastFmNowPlaying = bool("lastFmNowPlaying", false)
    val lastFmSendLikes = bool("lastFmSendLikes", false)
    /** Songs shorter than this (seconds) are never scrobbled. */
    val lastFmMinDuration = int("lastFmMinDuration", 30, 10..60)
    /** Part of the song that must play before it is scrobbled... */
    val lastFmDelayPercent = float("lastFmDelayPercent", 0.5f, 0.3f..0.95f)
    /** ...or this many seconds, whichever comes first. */
    val lastFmDelaySeconds = int("lastFmDelaySeconds", 180, 30..360)

    // ---- Discord Rich Presence ----
    /** Off until the user turns it on (it shares what you listen to). */
    val discordEnabled = bool("discordRpcEnabled", false)
    val discordAdvanced = bool("discordAdvancedMode", false)
    val discordDetailsTemplate = string("discordDetailsTemplate", DiscordDefaults.DETAILS) { it.take(200) }
    val discordStateTemplate = string("discordStateTemplate", DiscordDefaults.STATE) { it.take(200) }
    /** Off by default: the profile shows no link unless the user turns it on. */
    val discordButtonEnabled = bool("discordButtonShown", false)
    val discordButtonLabel = string("discordButton1Label", DiscordDefaults.BUTTON_LABEL) { it.take(32) }
    val discordButtonUrl = string("discordButton1Url", DiscordDefaults.BUTTON_URL) { it.take(512) }
    /** What Discord's member list shows next to your name: "name" (Record Labs), "state" (artist) or "details" (song). */
    val discordStatusDisplay = string("discordStatusDisplay", "state")

    // Generic builders kept for the later settings groups.
    internal fun booleanSetting(key: String, default: Boolean) = bool(key, default)
    internal fun stringSetting(key: String, default: String, normalize: (String) -> String = { it }) = string(key, default, normalize)
    internal fun intSetting(key: String, default: Int, range: IntRange) = int(key, default, range)
    internal fun floatSetting(key: String, default: Float, range: ClosedFloatingPointRange<Float>) = float(key, default, range)
}

/** Data actions started from Settings that the app window carries out (it owns the library and the player). */
sealed interface AppDataRequest {
    data object ClearListenHistory : AppDataRequest
    data object ClearSearchHistory : AppDataRequest
    data object DeleteDownloads : AppDataRequest
    data object ClearSongCache : AppDataRequest
    data object ApplySongCacheLimit : AppDataRequest
    /** Home must be loaded again (region, Hide explicit or More content changed). */
    data object ReloadHome : AppDataRequest
    /** A restored backup's library and recent searches (null = not in the backup). */
    data class RestoreLibrary(val library: WindowsLibrarySnapshot?, val searchHistory: List<String>?) : AppDataRequest
}

object AppDataEvents {
    val requests = kotlinx.coroutines.flow.MutableSharedFlow<AppDataRequest>(extraBufferCapacity = 16)
    /** Bumped after storage changed, so Settings > Storage measures again and Library refreshes downloads. */
    var storageGeneration by androidx.compose.runtime.mutableStateOf(0)

    fun request(request: AppDataRequest) {
        requests.tryEmit(request)
    }
}

/** The app's own version (from the installer), shown in Settings > About. */
object AppInfo {
    private const val BUILD_VERSION = "0.1.9"
    val version: String = systemProperty("jpackage.app-version")?.takeIf(String::isNotBlank) ?: systemProperty("recordlabs.app-version")?.takeIf(String::isNotBlank) ?: BUILD_VERSION
}

/** Default Discord Rich Presence texts (the desktop app shows them; kept here for the settings). */
object DiscordDefaults {
    const val DETAILS = "{song.name}"
    const val STATE = "{artist.name}"
    const val BUTTON_LABEL = "Listen on YouTube Music"
    const val BUTTON_URL = "https://music.youtube.com/watch?v={song.id}"
}
