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

/** iOS: Discord Rich Presence is not available (no Discord app connection on iPhone). */
actual object DiscordRichPresence {
    actual val state: StateFlow<DiscordConnectionState> = MutableStateFlow(DiscordConnectionState.Off)
    actual fun settingsChanged() {}
    actual fun report(playback: DiscordPlayback) {}
    actual val playback: StateFlow<DiscordPlayback?> = MutableStateFlow(null)
    actual val needsLogin: Boolean get() = false
    actual fun logout() {}
    actual fun login() {}
    actual val loggedIn: Boolean get() = false
    actual fun content(playback: DiscordPlayback, nowMs: Long) : DiscordActivityContent? = null
    actual val PLACEHOLDERS: List<String> = listOf("{song.name}", "{artist.name}", "{album.name}", "{song.id}")
}
