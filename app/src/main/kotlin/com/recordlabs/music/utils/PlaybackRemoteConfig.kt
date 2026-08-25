/**
 * Record Labs Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.recordlabs.music.utils

import android.content.Context
import androidx.core.content.edit
import com.recordlabs.innertube.models.YouTubeClient
import com.recordlabs.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import timber.log.Timber
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * A deliberately small, declarative overlay for playback request policy. It cannot load code,
 * change endpoints, or access credentials. Invalid/expired documents are ignored and the exact
 * bundled behaviour remains active.
 */
object PlaybackRemoteConfig {
    private const val TAG = "PlaybackRemoteConfig"
    private const val CONFIG_URL =
        "https://raw.githubusercontent.com/hasbenyek/record-labs/main/remote-config/playback.json"
    private const val PREFS_NAME = "record_labs_playback_remote_config"
    private const val CACHED_DOCUMENT_KEY = "cached_document"
    private const val ETAG_KEY = "etag"
    private const val LAST_CHECK_KEY = "last_check"
    private const val REFRESH_INTERVAL_MS = 6L * 60L * 60L * 1_000L
    private const val FAILURE_REFRESH_COOLDOWN_MS = 5L * 60L * 1_000L

    val defaultFallbackClientOrder = listOf(
        "VISIONOS",
        "WEB_CREATOR",
        "TVHTML5",
        "ANDROID_VR_1_43_32",
        "ANDROID_VR_1_61_48",
        "TVHTML5_SIMPLY_EMBEDDED_PLAYER",
        "IOS",
        "IPADOS",
        "ANDROID_CREATOR",
        "ANDROID_VR_NO_AUTH",
        "MOBILE",
        "WEB",
    )

    private val allowedClientIds = (defaultFallbackClientOrder + "WEB_REMIX").toSet()
    private val versionPattern = Regex("[0-9A-Za-z._-]{1,40}")
    private val revisionPattern = Regex("[0-9A-Za-z._-]{1,64}")
    private val json = Json { ignoreUnknownKeys = false }
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val refreshMutex = Mutex()
    private val initialized = AtomicBoolean(false)
    private val policy = AtomicReference(Policy.DEFAULT)

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var applicationScope: CoroutineScope? = null

    @Volatile
    private var lastFailureRefreshRequest = 0L

    @Serializable
    data class ClientOverride(
        val clientVersion: String? = null,
        val userAgent: String? = null,
    )

    @Serializable
    internal data class RemoteDocument(
        val schemaVersion: Int,
        val revision: String,
        val enabled: Boolean = true,
        val expiresAtEpochSeconds: Long = 0,
        val minimumAppVersion: String? = null,
        val maximumAppVersion: String? = null,
        val fallbackClientOrder: List<String> = emptyList(),
        val disabledClientIds: Set<String> = emptySet(),
        val forceAnonymousPublicPlayback: Boolean = true,
        val preferFallbackWhenLoggedIn: Boolean = true,
        val skipWebRemixHeadValidation: Boolean = true,
        val validateLastFallback: Boolean = false,
        val clientOverrides: Map<String, ClientOverride> = emptyMap(),
    )

    data class Policy(
        val revision: String,
        val fallbackClientOrder: List<String>,
        val disabledClientIds: Set<String>,
        val forceAnonymousPublicPlayback: Boolean,
        val preferFallbackWhenLoggedIn: Boolean,
        val skipWebRemixHeadValidation: Boolean,
        val validateLastFallback: Boolean,
        val clientOverrides: Map<String, ClientOverride>,
    ) {
        fun applyClientOverride(id: String, client: YouTubeClient): YouTubeClient {
            val override = clientOverrides[id] ?: return client
            return client.copy(
                clientVersion = override.clientVersion ?: client.clientVersion,
                userAgent = override.userAgent ?: client.userAgent,
            )
        }

        companion object {
            val DEFAULT = Policy(
                revision = "bundled-1",
                fallbackClientOrder = defaultFallbackClientOrder,
                disabledClientIds = emptySet(),
                forceAnonymousPublicPlayback = true,
                preferFallbackWhenLoggedIn = true,
                skipWebRemixHeadValidation = true,
                validateLastFallback = false,
                clientOverrides = emptyMap(),
            )
        }
    }

    fun initialize(context: Context, scope: CoroutineScope) {
        if (!initialized.compareAndSet(false, true)) return
        appContext = context.applicationContext
        applicationScope = scope
        loadCachedPolicy(context.applicationContext)
        scope.launch(Dispatchers.IO) {
            delay(8_000)
            refresh(force = false)
        }
    }

    fun currentPolicy(): Policy = policy.get()

    /** Refreshes in the background so a user retry can use a newly published policy. */
    fun requestRefreshAfterPlaybackFailure() {
        val now = System.currentTimeMillis()
        if (now - lastFailureRefreshRequest < FAILURE_REFRESH_COOLDOWN_MS) return
        lastFailureRefreshRequest = now
        applicationScope?.launch(Dispatchers.IO) { refresh(force = true) }
    }

    suspend fun refresh(force: Boolean) = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext
        refreshMutex.withLock {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            if (!force && now - prefs.getLong(LAST_CHECK_KEY, 0L) < REFRESH_INTERVAL_MS) {
                return@withLock
            }

            val request = Request.Builder()
                .url(CONFIG_URL)
                .header("Accept", "application/json")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "Record-Labs/${BuildConfig.VERSION_NAME}")
                .apply { prefs.getString(ETAG_KEY, null)?.let { header("If-None-Match", it) } }
                .build()

            runCatching {
                client.newCall(request).execute().use { response ->
                    prefs.edit { putLong(LAST_CHECK_KEY, now) }
                    if (response.code == 304) return@use
                    if (!response.isSuccessful) error("HTTP ${response.code}")
                    val body = response.body?.string() ?: error("Empty playback config")
                    val parsed = parseAndValidate(body, now / 1_000L, BuildConfig.VERSION_NAME)
                    policy.set(parsed)
                    prefs.edit {
                        putString(CACHED_DOCUMENT_KEY, body)
                        response.header("ETag")?.let { putString(ETAG_KEY, it) }
                            ?: remove(ETAG_KEY)
                    }
                    Timber.tag(TAG).i("Applied playback policy revision=${parsed.revision}")
                }
            }.onFailure {
                Timber.tag(TAG).w(it, "Remote playback policy refresh failed; keeping ${policy.get().revision}")
            }
        }
    }

    private fun loadCachedPolicy(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val cached = prefs.getString(CACHED_DOCUMENT_KEY, null) ?: return
        runCatching {
            parseAndValidate(cached, System.currentTimeMillis() / 1_000L, BuildConfig.VERSION_NAME)
        }.onSuccess {
            policy.set(it)
            Timber.tag(TAG).i("Loaded cached playback policy revision=${it.revision}")
        }.onFailure {
            prefs.edit {
                remove(CACHED_DOCUMENT_KEY)
                remove(ETAG_KEY)
            }
            Timber.tag(TAG).w(it, "Ignoring invalid cached playback policy")
        }
    }

    internal fun parseAndValidate(
        body: String,
        nowEpochSeconds: Long,
        appVersion: String,
    ): Policy {
        require(body.length <= 32_768) { "Playback config is too large" }
        val document = json.decodeFromString<RemoteDocument>(body)
        require(document.schemaVersion == 1) { "Unsupported schema version" }
        require(revisionPattern.matches(document.revision)) { "Invalid revision" }
        require(document.expiresAtEpochSeconds == 0L || document.expiresAtEpochSeconds > nowEpochSeconds) {
            "Playback config expired"
        }
        document.minimumAppVersion?.let {
            require(compareVersions(appVersion, it) >= 0) { "App version below policy minimum" }
        }
        document.maximumAppVersion?.let {
            require(compareVersions(appVersion, it) <= 0) { "App version above policy maximum" }
        }

        if (!document.enabled) return Policy.DEFAULT.copy(revision = document.revision)

        require(document.fallbackClientOrder.isNotEmpty()) { "Fallback order cannot be empty" }
        require(document.fallbackClientOrder.size <= defaultFallbackClientOrder.size) { "Too many fallback clients" }
        require(document.fallbackClientOrder.toSet().size == document.fallbackClientOrder.size) {
            "Duplicate fallback client"
        }
        require(document.fallbackClientOrder.all { it in allowedClientIds && it != "WEB_REMIX" }) {
            "Unknown fallback client"
        }
        require(document.disabledClientIds.all { it in allowedClientIds }) { "Unknown disabled client" }
        require(document.fallbackClientOrder.any { it !in document.disabledClientIds }) {
            "All fallback clients are disabled"
        }
        require(document.clientOverrides.keys.all { it in allowedClientIds }) { "Unknown client override" }
        document.clientOverrides.values.forEach { override ->
            override.clientVersion?.let {
                require(versionPattern.matches(it)) { "Invalid client version" }
            }
            override.userAgent?.let {
                require(it.length in 1..300 && it.all { char -> char.code in 32..126 }) {
                    "Invalid user agent"
                }
            }
        }

        return Policy(
            revision = document.revision,
            fallbackClientOrder = document.fallbackClientOrder.toList(),
            disabledClientIds = document.disabledClientIds.toSet(),
            forceAnonymousPublicPlayback = document.forceAnonymousPublicPlayback,
            preferFallbackWhenLoggedIn = document.preferFallbackWhenLoggedIn,
            skipWebRemixHeadValidation = document.skipWebRemixHeadValidation,
            validateLastFallback = document.validateLastFallback,
            clientOverrides = document.clientOverrides.toMap(),
        )
    }

    private fun compareVersions(left: String, right: String): Int {
        val leftParts = left.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val rightParts = right.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(leftParts.size, rightParts.size)) { index ->
            val result = (leftParts.getOrNull(index) ?: 0).compareTo(rightParts.getOrNull(index) ?: 0)
            if (result != 0) return result
        }
        return 0
    }
}
