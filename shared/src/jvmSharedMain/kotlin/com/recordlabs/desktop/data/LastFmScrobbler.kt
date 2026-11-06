package com.recordlabs.desktop.data

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.parameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * Last.fm scrobbling, like the Android app: "now playing" when a song starts,
 * a scrobble once it has really played for half its length (or the set
 * number of seconds, whichever comes first), and likes sent as loves.
 *
 * Last.fm needs an API account: the user enters their own API key and shared
 * secret (free at last.fm/api/account/create), then signs in with their
 * Last.fm username and password; only the session key is kept.
 */
object LastFmScrobbler {
    private const val API_URL = "https://ws.audioscrobbler.com/2.0/"
    private const val USER_AGENT = "Record Labs Windows"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private val client by lazy {
        HttpClient(CIO) {
            install(HttpTimeout) {
                requestTimeoutMillis = 15_000
                connectTimeoutMillis = 10_000
            }
            expectSuccess = false
        }
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _lastResult = MutableStateFlow<String?>(null)
    /** The last scrobble outcome, for the settings page. */
    val lastResult: StateFlow<String?> = _lastResult.asStateFlow()

    val isSignedIn: Boolean get() = AppSettings.lastFmSessionKey.value.isNotBlank() && hasApiAccount

    private val hasApiAccount: Boolean
        get() = AppSettings.lastFmApiKey.value.isNotBlank() && AppSettings.lastFmSecret.value.isNotBlank()

    private fun signature(params: Map<String, String>, secret: String): String {
        val text = params.toSortedMap().entries.joinToString("") { it.key + it.value } + secret
        return MessageDigest.getInstance("MD5").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    /** One signed Last.fm call; returns the JSON reply (Last.fm puts errors in it too). */
    private suspend fun call(method: String, extra: Map<String, String>, withSession: Boolean): JsonObject {
        val apiKey = AppSettings.lastFmApiKey.value.trim()
        val secret = AppSettings.lastFmSecret.value.trim()
        val params = buildMap {
            put("method", method)
            put("api_key", apiKey)
            if (withSession) put("sk", AppSettings.lastFmSessionKey.value)
            putAll(extra)
        }
        val sig = signature(params, secret)
        val text = client.submitForm(
            API_URL,
            formParameters = parameters {
                params.forEach { (key, value) -> append(key, value) }
                append("api_sig", sig)
                append("format", "json")
            },
        ) { header("User-Agent", USER_AGENT) }.bodyAsText()
        return json.parseToJsonElement(text).jsonObject
    }

    private fun JsonObject.errorMessage(): String? {
        val code = this["error"]?.jsonPrimitive?.intOrNull ?: return null
        val message = this["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        return when (code) {
            4 -> "Wrong username or password"
            6 -> "Missing or invalid details"
            9 -> "Session expired · sign in again"
            10 -> "Invalid API key"
            13 -> "Invalid API secret"
            26 -> "This API key was suspended"
            29 -> "Too many requests · try again later"
            else -> message.ifBlank { "Last.fm error $code" }
        }
    }

    /** Signs in; returns null when it worked, otherwise what went wrong. */
    suspend fun signIn(username: String, password: String): String? = runCatching {
        if (!hasApiAccount) return "Enter your Last.fm API key and shared secret first"
        val reply = call("auth.getMobileSession", mapOf("username" to username.trim(), "password" to password), withSession = false)
        reply.errorMessage()?.let { return it }
        val session = reply["session"]?.jsonObject ?: return "Unexpected answer from Last.fm"
        AppSettings.lastFmSessionKey.value = session["key"]?.jsonPrimitive?.contentOrNull.orEmpty()
        AppSettings.lastFmUsername.value = session["name"]?.jsonPrimitive?.contentOrNull ?: username.trim()
        null
    }.getOrElse { "Could not reach Last.fm: ${it.message ?: it.javaClass.simpleName}" }

    fun signOut() {
        AppSettings.lastFmSessionKey.value = ""
        AppSettings.lastFmUsername.value = ""
        _lastResult.value = null
    }

    // ---- Playback ----

    private var playId: String? = null
    private var playStartedSec = 0L
    private var playedMs = 0L
    private var lastReportAt = 0L
    private var lastPositionMs = 0L
    private var nowPlayingSent = false
    private var scrobbled = false

    /** Called about once a second with what is playing. */
    @Synchronized
    fun report(track: Track?, isPlaying: Boolean, positionMs: Long, durationMs: Long) {
        val now = System.currentTimeMillis()
        if (track == null || track.id.isBlank() || track.id.startsWith("pending:")) {
            playId = null
            lastReportAt = now
            return
        }
        // A new song, or the same song started over (repeat).
        val restarted = track.id == playId && positionMs < 3_000L && lastPositionMs > 10_000L
        if (track.id != playId || restarted) {
            playId = track.id
            playStartedSec = now / 1000L
            playedMs = 0L
            nowPlayingSent = false
            scrobbled = false
        } else if (isPlaying) {
            playedMs += (now - lastReportAt).coerceIn(0L, 2_000L)
        }
        lastReportAt = now
        lastPositionMs = positionMs
        if (!isSignedIn || !AppSettings.lastFmScrobbling.value) return

        val durationSec = (durationMs.takeIf { it > 0 } ?: track.durationMs) / 1000L
        if (isPlaying && !nowPlayingSent && AppSettings.lastFmNowPlaying.value) {
            nowPlayingSent = true
            send("track.updateNowPlaying", trackParams(track, durationSec, null), reportOutcome = false)
        }
        if (scrobbled || durationSec < AppSettings.lastFmMinDuration.value) return
        val neededMs = minOf(
            (durationSec * 1000L * AppSettings.lastFmDelayPercent.value).toLong(),
            AppSettings.lastFmDelaySeconds.value * 1000L,
        )
        if (playedMs >= neededMs) {
            scrobbled = true
            val id = track.id
            val startedSec = playStartedSec
            send("track.scrobble", trackParams(track, durationSec, playStartedSec), reportOutcome = true) {
                // No connection: try again on a later report of the same play.
                synchronized(this) { if (playId == id && playStartedSec == startedSec) scrobbled = false }
            }
        }
    }

    /** A song was liked or unliked in Record Labs. */
    fun onLikeChanged(track: Track, liked: Boolean) {
        if (!isSignedIn || !AppSettings.lastFmSendLikes.value) return
        send(if (liked) "track.love" else "track.unlove", mapOf("artist" to mainArtist(track), "track" to track.title), reportOutcome = false)
    }

    private fun mainArtist(track: Track): String = track.artist.ifBlank { "Unknown Artist" }

    private fun trackParams(track: Track, durationSec: Long, timestampSec: Long?): Map<String, String> {
        val suffix = if (timestampSec != null) "[0]" else ""
        return buildMap {
            put("artist$suffix", mainArtist(track))
            put("track$suffix", track.title)
            timestampSec?.let { put("timestamp$suffix", it.toString()) }
            track.album.takeIf(String::isNotBlank)?.let { put("album$suffix", it) }
            if (durationSec > 0) put("duration$suffix", durationSec.toString())
        }
    }

    private fun send(method: String, params: Map<String, String>, reportOutcome: Boolean, onNetworkFailure: () -> Unit = {}) {
        scope.launch {
            val outcome = runCatching { call(method, params, withSession = true) }
            if (outcome.isFailure) onNetworkFailure()
            if (!reportOutcome) return@launch
            _lastResult.value = outcome.fold(
                onSuccess = { reply -> reply.errorMessage()?.let { "Last scrobble failed: $it" } ?: "Last scrobble: ${params["track[0]"].orEmpty()}" },
                onFailure = { "Last scrobble failed: no connection" },
            )
        }
    }
}
