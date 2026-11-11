@file:OptIn(kotlin.uuid.ExperimentalUuidApi::class)

package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.*

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

object WindowsPreferences {
    private val preferencesRoot = prefsNode("com/recordlabs/desktop")
    private val preferences = preferencesRoot.node(AppStorage.installId)
    private val json = Json { ignoreUnknownKeys = true }

    init {
        migrateTimestampScopedPreferences()
    }

    private fun migrateTimestampScopedPreferences() {
        val currentCookie = preferences.get("googleCookie", "")
        if (currentCookie.contains("SAPISID=") && preferences.get("googleAccountChannelHandle", "").isNotBlank()) {
            return
        }
        // Copy another installation's settings only once, and never after the
        // user signed out here: otherwise every launch brought the old account
        // back and rolled this installation's settings back to that copy.
        if (preferences.getBoolean("googleSignedOut", false) || preferences.getBoolean("preferencesMigrated", false)) {
            return
        }
        val candidate = runCatching {
            preferencesRoot.childrenNames()
                .filter { it != AppStorage.installId }
                .map { name -> name to preferencesRoot.node(name) }
                .filter { (_, node) -> node.get("googleCookie", "").contains("SAPISID=") }
                .maxWithOrNull(
                    compareBy<Pair<String, PrefsNode>> { (_, node) ->
                        if (node.get("googleAccountChannelHandle", "").isNotBlank()) 2 else 1
                    }.thenBy { (name, _) ->
                        name.substringAfterLast('-').toLongOrNull() ?: 0L
                    }
                )
        }.getOrNull() ?: return
        runCatching {
            candidate.second.keys().forEach { key ->
                candidate.second.get(key, null)?.let { value -> preferences.put(key, value) }
            }
            preferences.putBoolean("preferencesMigrated", true)
            preferences.flush()
        }
    }

    @Serializable
    data class LastPlayback(val track: Track, val positionMs: Long)

    data class WindowSession(
        val placement: String,
        val width: Float,
        val height: Float,
    )

    fun windowSession(): WindowSession? {
        if (!preferences.getBoolean("windowSessionSaved", false)) return null
        return WindowSession(
            placement = preferences.get("windowPlacement", "Maximized"),
            width = preferences.getFloat("windowWidth", 1180f).coerceAtLeast(720f),
            height = preferences.getFloat("windowHeight", 760f).coerceAtLeast(520f),
        )
    }

    fun saveWindowSession(placement: String, width: Float, height: Float) {
        if (placement == "Fullscreen") return
        preferences.putBoolean("windowSessionSaved", true)
        preferences.put("windowPlacement", placement)
        if (placement == "Floating") {
            preferences.putFloat("windowWidth", width.coerceAtLeast(720f))
            preferences.putFloat("windowHeight", height.coerceAtLeast(520f))
        }
    }

    fun lyricsTextSize(landscape: Boolean): Float =
        preferences.getFloat(if (landscape) "lyricsTextSizeLandscape" else "lyricsTextSize", defaultLyricsTextSize())
            .coerceIn(20f, 68f)

    private fun defaultLyricsTextSize(): Float = if (AppPlatform.isPhone) 30f else runCatching {
        when (screenLongSidePixels() ?: error("unknown screen")) {
            in 0..1_599 -> 36f // typical 14-inch HD
            in 1_600..2_199 -> 46f // typical 15.6-inch FHD
            else -> 53f // 27-inch 2K and above
        }
    }.getOrDefault(46f)

    fun setLyricsTextSize(landscape: Boolean, size: Float) {
        preferences.putFloat(if (landscape) "lyricsTextSizeLandscape" else "lyricsTextSize", size.coerceIn(20f, 68f))
    }

    fun lyricsOffsetMs(): Long = preferences.getLong("lyricsOffsetMs", 0L).coerceIn(-5_000L, 5_000L)

    fun setLyricsOffsetMs(value: Long) {
        preferences.putLong("lyricsOffsetMs", value.coerceIn(-5_000L, 5_000L))
    }

    fun lyricsPronunciationEnabled(): Boolean = preferences.getBoolean("lyricsPronunciationEnabled", true)

    fun setLyricsPronunciationEnabled(value: Boolean) {
        preferences.putBoolean("lyricsPronunciationEnabled", value)
    }

    fun lyricsTranslationEnabled(): Boolean = preferences.getBoolean("lyricsTranslationEnabled", false)

    fun setLyricsTranslationEnabled(value: Boolean) {
        preferences.putBoolean("lyricsTranslationEnabled", value)
    }

    fun lyricsTranslationLanguage(default: String): String = preferences.get("lyricsTranslationLanguage", default)

    fun setLyricsTranslationLanguage(value: String) {
        preferences.put("lyricsTranslationLanguage", value)
    }

    /** Width of the side queue panel in dp (null: never resized, the widest). */
    fun queuePanelWidth(): Float? = preferences.get("queuePanelWidth", null)?.toFloatOrNull()

    fun setQueuePanelWidth(value: Float) {
        preferences.put("queuePanelWidth", value.toString())
        // Saved at once: the app can be closed right after the queue was resized.
        runCatching { preferences.flush() }
    }

    fun animatedArtworkEnabled(): Boolean {
        val stored = preferences.get("animatedArtworkEnabled", null)
        return stored?.toBooleanStrictOrNull() ?: defaultAnimatedArtworkEnabled()
    }

    fun setAnimatedArtworkEnabled(value: Boolean) {
        preferences.putBoolean("animatedArtworkEnabled", value)
    }

    private fun defaultAnimatedArtworkEnabled(): Boolean {
        // Phones: on unless turned off (the heap check below is for PCs; an Android
        // app's heap is far below 2 GB even on a high-end phone, so it was always off).
        if (AppPlatform.isAndroid) return true
        if (AppPlatform.isIos) return true
        val memoryBytes = maxMemoryBytes()
        return processorCount() >= 6 && memoryBytes >= 2L * 1024L * 1024L * 1024L
    }

    fun lastPlayback(): LastPlayback? = runCatching {
        preferences.get("lastPlayback", null)?.let { json.decodeFromString<LastPlayback>(it) }
    }.getOrNull()

    fun saveLastPlayback(track: Track, positionMs: Long) {
        runCatching {
            preferences.put(
                "lastPlayback",
                json.encodeToString(LastPlayback(track, positionMs.coerceAtLeast(0L))),
            )
        }
    }

    data class GoogleSession(val cookie: String, val visitorData: String, val dataSyncId: String)

    fun googleSession(): GoogleSession? {
        val cookie = preferences.get("googleCookie", "")
        if (!cookie.contains("SAPISID=")) return null
        return GoogleSession(
            cookie,
            preferences.get("googleVisitorData", ""),
            preferences.get("googleDataSyncId", ""),
        )
    }

    fun saveGoogleSession(cookie: String, visitorData: String, dataSyncId: String) {
        preferences.remove("googleSignedOut")
        preferences.put("googleCookie", cookie)
        preferences.put("googleVisitorData", visitorData)
        preferences.put("googleDataSyncId", dataSyncId)
        // A new sign-in tries the page id again.
        preferences.remove("googleUsePageId")
        preferences.putBoolean("moreContent", true)
        preferences.putBoolean("autoSync", true)
        runCatching { preferences.flush() }
    }

    /** Whether requests carry the account's page id (X-Goog-PageId); turned off when YouTube Music refuses it. */
    fun googleUsePageId(): Boolean = preferences.getBoolean("googleUsePageId", true)
    fun setGoogleUsePageId(value: Boolean) = preferences.putBoolean("googleUsePageId", value)

    fun googleAccountName(): String = preferences.get("googleAccountName", "")
    fun googleAccountEmail(): String = preferences.get("googleAccountEmail", "")
    fun googleAccountChannelHandle(): String = preferences.get("googleAccountChannelHandle", "")
    fun googleAccountPhoto(): String = preferences.get("googleAccountPhoto", "")
    fun saveGoogleAccountProfile(
        name: String,
        photoUrl: String?,
        email: String? = null,
        channelHandle: String? = null,
    ) {
        preferences.put("googleAccountName", name)
        preferences.put("googleAccountEmail", email.orEmpty())
        preferences.put("googleAccountChannelHandle", channelHandle.orEmpty())
        preferences.put("googleAccountPhoto", photoUrl.orEmpty())
    }
    fun moreContent(): Boolean = preferences.getBoolean("moreContent", googleSession() != null)
    fun setMoreContent(value: Boolean) = preferences.putBoolean("moreContent", value)
    fun autoSync(): Boolean = preferences.getBoolean("autoSync", googleSession() != null)
    fun setAutoSync(value: Boolean) = preferences.putBoolean("autoSync", value)

    fun clearGoogleSession() {
        preferences.remove("googleCookie")
        preferences.remove("googleVisitorData")
        preferences.remove("googleDataSyncId")
        preferences.remove("googleAccountName")
        preferences.remove("googleAccountEmail")
        preferences.remove("googleAccountChannelHandle")
        preferences.remove("googleAccountPhoto")
    }

    /** The user pressed Logout: clear the session and do not bring it back from another installation. */
    fun signOutGoogle() {
        clearGoogleSession()
        preferences.putBoolean("googleSignedOut", true)
        runCatching { preferences.flush() }
    }

    fun adoptLatestSession(): GoogleSession? {
        val candidate = runCatching {
            preferencesRoot.childrenNames()
                .filter { it != AppStorage.installId }
                .map { name -> name to preferencesRoot.node(name) }
                .filter { (_, node) -> node.get("googleCookie", "").contains("SAPISID=") }
                .maxWithOrNull(
                    compareBy<Pair<String, PrefsNode>> { (_, node) ->
                        if (node.get("googleAccountChannelHandle", "").isNotBlank()) 2 else 1
                    }.thenBy { (name, _) ->
                        name.substringAfterLast('-').toLongOrNull() ?: 0L
                    }
                )
        }.getOrNull() ?: return null

        val node = candidate.second
        val cookie = node.get("googleCookie", "")
        if (!cookie.contains("SAPISID=")) return null
        val visitorData = node.get("googleVisitorData", "")
        val dataSyncId = node.get("googleDataSyncId", "")

        preferences.put("googleCookie", cookie)
        preferences.put("googleVisitorData", visitorData)
        preferences.put("googleDataSyncId", dataSyncId)
        preferences.putBoolean("moreContent", true)
        preferences.putBoolean("autoSync", true)
        node.get("googleAccountName", null)?.let { preferences.put("googleAccountName", it) }
        node.get("googleAccountPhoto", null)?.let { preferences.put("googleAccountPhoto", it) }
        node.get("googleAccountEmail", null)?.let { preferences.put("googleAccountEmail", it) }
        node.get("googleAccountChannelHandle", null)?.let { preferences.put("googleAccountChannelHandle", it) }
        runCatching { preferences.flush() }

        return GoogleSession(cookie, visitorData, dataSyncId)
    }

    fun listenUsername(): String = preferences.get("listenUsername", AppPlatform.deviceName).let { if (it == "Windows listener") AppPlatform.deviceName else it }
    fun setListenUsername(value: String) = preferences.put("listenUsername", value.trim().ifBlank { AppPlatform.deviceName })
    fun listenAutoApproveJoins(): Boolean = preferences.getBoolean("listenAutoApproveJoins", false)
    fun setListenAutoApproveJoins(value: Boolean) = preferences.putBoolean("listenAutoApproveJoins", value)
    fun listenAutoApproveSuggestions(): Boolean = preferences.getBoolean("listenAutoApproveSuggestions", false)
    fun setListenAutoApproveSuggestions(value: Boolean) = preferences.putBoolean("listenAutoApproveSuggestions", value)
    fun listenGuestControls(): Boolean = preferences.getBoolean("listenGuestControls", true)
    fun setListenGuestControls(value: Boolean) = preferences.putBoolean("listenGuestControls", value)

    fun connectDeviceId(): String = preferences.get("connectDeviceId", "").ifBlank {
        kotlin.uuid.Uuid.random().toString().also { preferences.put("connectDeviceId", it) }
    }

    /** The side queue panel (not the player's): open by default, then as the user last left it. */
    fun globalQueueOpen(): Boolean = preferences.getBoolean("globalQueueOpen", true)
    fun setGlobalQueueOpen(value: Boolean) {
        preferences.putBoolean("globalQueueOpen", value)
    }

    /** Every saved value of this installation (Settings > Backup). */
    fun allEntries(): Map<String, String> = runCatching {
        preferences.keys().associateWith { key -> preferences.get(key, "") }
    }.getOrDefault(emptyMap())

    /** Restores values from a backup, then saves them at once. */
    fun restoreEntries(entries: Map<String, String>) {
        entries.forEach { (key, value) -> preferences.put(key, value) }
        runCatching { preferences.flush() }
    }

    // Plain typed access for the Settings pages (see AppSettings).
    fun getBoolean(key: String, default: Boolean): Boolean = preferences.getBoolean(key, default)
    fun putBoolean(key: String, value: Boolean) = preferences.putBoolean(key, value)
    fun getString(key: String, default: String): String = preferences.get(key, default)
    fun putString(key: String, value: String) = preferences.put(key, value)
    fun getInt(key: String, default: Int): Int = preferences.getInt(key, default)
    fun putInt(key: String, value: Int) = preferences.putInt(key, value)
    fun getFloat(key: String, default: Float): Float = preferences.getFloat(key, default)
    fun putFloat(key: String, value: Float) = preferences.putFloat(key, value)

    fun floatingLyricsEnabled(): Boolean = preferences.getBoolean("floatingLyricsEnabled", false)
    fun setFloatingLyricsEnabled(value: Boolean) {
        preferences.putBoolean("floatingLyricsEnabled", value)
    }

    fun floatingLyricsPosition(): Pair<Float, Float>? {
        if (!preferences.getBoolean("floatingLyricsPosSaved", false)) return null
        return preferences.getFloat("floatingLyricsPosX", 0f) to preferences.getFloat("floatingLyricsPosY", 0f)
    }

    fun saveFloatingLyricsPosition(x: Float, y: Float) {
        preferences.putBoolean("floatingLyricsPosSaved", true)
        preferences.putFloat("floatingLyricsPosX", x)
        preferences.putFloat("floatingLyricsPosY", y)
    }

    fun floatingLyricsSize(): Pair<Float, Float>? {
        if (!preferences.getBoolean("floatingLyricsSizeSaved", false)) return null
        return preferences.getFloat("floatingLyricsWidth", 680f) to preferences.getFloat("floatingLyricsHeight", 150f)
    }

    fun saveFloatingLyricsSize(width: Float, height: Float) {
        preferences.putBoolean("floatingLyricsSizeSaved", true)
        preferences.putFloat("floatingLyricsWidth", width)
        preferences.putFloat("floatingLyricsHeight", height)
    }
}
