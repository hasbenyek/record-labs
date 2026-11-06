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

/** What is playing, as the app reports it to Discord about once a second. */
data class DiscordPlayback(
    val track: Track?,
    val isPlaying: Boolean,
    val positionMs: Long,
    val durationMs: Long,
    val speed: Float,
)

sealed interface DiscordConnectionState {
    data object Off : DiscordConnectionState
    data object Connecting : DiscordConnectionState
    data object DiscordNotRunning : DiscordConnectionState
    /** Android: not logged in to Discord in Record Labs yet. */
    data object SignedOut : DiscordConnectionState
    data class Connected(val userName: String?, val avatarUrl: String?) : DiscordConnectionState
    data class Failed(val message: String) : DiscordConnectionState
}

/** The texts and pictures shown in Discord for a song (also used by the settings preview). */
data class DiscordActivityContent(
    val details: String,
    val state: String,
    val largeImage: String?,
    val largeText: String?,
    val buttonLabel: String?,
    val buttonUrl: String?,
    val startMs: Long?,
    val endMs: Long?,
)

/** The desktop and Android: DiscordRichPresence in jvmSharedMain; iOS: its own version (iosMain). */
expect object DiscordRichPresence {
    val state: StateFlow<DiscordConnectionState>
    fun settingsChanged()
    fun report(playback: DiscordPlayback)
    val playback: StateFlow<DiscordPlayback?>
    val needsLogin: Boolean
    fun logout()
    fun login()
    val loggedIn: Boolean
    fun content(playback: DiscordPlayback, nowMs: Long = com.recordlabs.desktop.platform.currentTimeMillis()) : DiscordActivityContent?
    val PLACEHOLDERS: List<String>
}
