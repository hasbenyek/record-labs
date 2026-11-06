package com.recordlabs.desktop.data

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Android app updates, shown as a banner in the app (never as a notification).
 *
 * The app reads the releases of [GITHUB_REPO] on GitHub: the newest release
 * (not a draft or pre-release) that carries an .apk. Its tag is the version
 * (`v2.1.0`, `2.1.0` or `android-v2.1.0`), compared with the app's own
 * versionName. When it is newer, the banner offers:
 *  - Update: downloads the .apk here and opens Android's installer;
 *  - Not now: hidden until the next day (asked again after that);
 *  - Always ignore: never shown again for this version (a newer one shows again).
 * Settings > About always shows an available update (also after "Not now" or
 * "Always ignore") with the same Update button, like the desktop app.
 */
object AndroidUpdates {
    /** Where Android releases are published, "owner/repo" (public). The original Android app's repository. */
    const val GITHUB_REPO = "hasbenyek/record-labs"

    /**
     * The application id of the releases on GitHub. A build with another id (a test build
     * made on a computer) cannot be updated by them: Android would install the release as
     * a second Record Labs app next to it, so no update is offered there.
     */
    const val RELEASE_PACKAGE = "com.recordlabs.music.debug"

    private val updatableHere: Boolean
        get() = Platform.androidPackageName.let { it == null || it == RELEASE_PACKAGE }

    private const val SNOOZE_MS = 24L * 60L * 60L * 1_000L
    private const val CHECK_EVERY_MS = 6L * 60L * 60L * 1_000L

    data class Release(
        val version: String,
        val notes: String,
        val apkUrl: String,
        val size: Long,
        val pageUrl: String,
    )

    sealed interface Banner {
        data object Hidden : Banner
        data class Available(val release: Release) : Banner
        data class Downloading(val release: Release, val progress: Float) : Banner
        data class Ready(val release: Release, val file: File) : Banner
        data class Failed(val release: Release, val message: String) : Banner
    }

    /**
     * The update and its download (Hidden: none). Settings > About always shows it;
     * the banner over the app only while [bannerDismissed] is false.
     */
    val banner = mutableStateOf<Banner>(Banner.Hidden)

    /** "Not now" / "Always ignore" pressed (or a version ignored before): no banner, About still offers it. */
    val bannerDismissed = mutableStateOf(false)

    /** Settings: the state of the last manual check ("Checking…", "Up to date"). */
    val manualStatus = mutableStateOf<String?>(null)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val lock = Mutex()
    @Volatile private var lastCheckAt = 0L
    @Volatile private var downloadJob: Job? = null

    private val prefs by lazy { java.util.prefs.Preferences.userRoot().node("recordlabs/android-updates") }
    private val json = Json { ignoreUnknownKeys = true }

    /** On launch and whenever the app comes back after a while: at most every few hours. */
    fun checkInBackground() {
        if (!Platform.isAndroid) return
        if (!updatableHere) return
        if (System.currentTimeMillis() - lastCheckAt < CHECK_EVERY_MS) return
        scope.launch { check(manual = false) }
    }

    /** Settings > Check for updates: shows the banner even for an ignored or postponed version. */
    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    private suspend fun check(manual: Boolean) = lock.withLock {
        if (!updatableHere) {
            if (manual) manualStatus.value = "This is a test build: install the release from GitHub to get updates in the app"
            return@withLock
        }
        val current = banner.value
        if (current is Banner.Downloading || current is Banner.Ready) return@withLock
        if (manual) manualStatus.value = "Checking…"
        lastCheckAt = System.currentTimeMillis()
        val found = runCatching { fetchLatest() }
        found.onFailure { if (manual) manualStatus.value = "Couldn't check for updates. Check your connection." }
        val release = found.getOrNull()
        if (release == null || compareVersions(release.version, AppInfo.version) <= 0) {
            if (found.isSuccess && manual) manualStatus.value = "You're on the latest version (${AppInfo.version})"
            return@withLock
        }
        if (manual) manualStatus.value = "Version ${release.version} is available"
        val ignored = prefs.get("ignoredVersion", "")
        val snoozedVersion = prefs.get("snoozedVersion", "")
        val snoozedUntil = prefs.getLong("snoozedUntil", 0L)
        val hiddenByUser = release.version == ignored ||
            (release.version == snoozedVersion && System.currentTimeMillis() < snoozedUntil)
        val wasShown = banner.value !is Banner.Hidden
        banner.value = existingDownload(release)?.let { Banner.Ready(release, it) } ?: Banner.Available(release)
        // Settings asked: answered there (About), the banner is left as it was. On its own:
        // the banner shows unless the user postponed or ignored this version.
        bannerDismissed.value = if (manual) (bannerDismissed.value || !wasShown) else hiddenByUser
    }

    /** "Not now": hidden until tomorrow (or until a newer version comes out). */
    fun notNow() {
        val release = releaseOf(banner.value) ?: return
        runCatching {
            prefs.put("snoozedVersion", release.version)
            prefs.putLong("snoozedUntil", System.currentTimeMillis() + SNOOZE_MS)
            prefs.flush()
        }
        // The update stays available in Settings > About (a download carries on).
        bannerDismissed.value = true
    }

    /** "Always ignore": this version is never offered again. */
    fun alwaysIgnore() {
        val release = releaseOf(banner.value) ?: return
        runCatching {
            prefs.put("ignoredVersion", release.version)
            prefs.flush()
        }
        // No banner for it again; Settings > About still offers it.
        bannerDismissed.value = true
    }

    /** Settings > About: stops a download started from there or from the banner. */
    fun cancelDownload() {
        val state = banner.value as? Banner.Downloading ?: return
        downloadJob?.cancel()
        banner.value = Banner.Available(state.release)
    }

    /** "Update": downloads the .apk (the app stays usable) and opens Android's installer. */
    fun update(openPage: (String) -> Unit) {
        when (val state = banner.value) {
            is Banner.Ready -> if (!install(state.file)) openPage(state.release.pageUrl)
            is Banner.Available -> download(state.release, openPage)
            is Banner.Failed -> download(state.release, openPage)
            else -> Unit
        }
    }

    private fun download(release: Release, openPage: (String) -> Unit) {
        // Without the installer hook (should not happen), the release page downloads it instead.
        if (Platform.apkInstaller == null) {
            openPage(release.pageUrl)
            return
        }
        if (downloadJob?.isActive == true) return
        banner.value = Banner.Downloading(release, 0f)
        downloadJob = scope.launch {
            val folder = File(Platform.cacheRoot, "updates").apply { mkdirs() }
            val target = File(folder, "RecordLabs-${release.version}.apk")
            val part = File(folder, target.name + ".part")
            try {
                val connection = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
                    instanceFollowRedirects = true
                    connectTimeout = 15_000
                    readTimeout = 30_000
                    setRequestProperty("User-Agent", "RecordLabs-Android/${AppInfo.version}")
                    setRequestProperty("Accept", "application/octet-stream")
                }
                if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
                val total = connection.contentLengthLong.takeIf { it > 0 } ?: release.size
                var received = 0L
                var shown = -1
                connection.inputStream.use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            received += read
                            if (total > 0) {
                                val percent = (received * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != shown) {
                                    shown = percent
                                    banner.value = Banner.Downloading(release, percent / 100f)
                                }
                            }
                        }
                    }
                }
                if (release.size > 0 && received != release.size) error("incomplete download")
                target.delete()
                if (!part.renameTo(target)) error("couldn't save the update")
                banner.value = Banner.Ready(release, target)
                if (!install(target)) openPage(release.pageUrl)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                part.delete()
                if (banner.value is Banner.Downloading) banner.value = Banner.Available(release)
                throw cancelled
            } catch (error: Throwable) {
                part.delete()
                banner.value = Banner.Failed(release, "The download failed (${error.message ?: "network error"}).")
            }
        }
    }

    private fun install(file: File): Boolean = file.isFile && runCatching { Platform.apkInstaller?.invoke(file) == true }.getOrDefault(false)

    private fun releaseOf(state: Banner): Release? = when (state) {
        is Banner.Available -> state.release
        is Banner.Downloading -> state.release
        is Banner.Ready -> state.release
        is Banner.Failed -> state.release
        Banner.Hidden -> null
    }

    private fun existingDownload(release: Release): File? =
        File(File(Platform.cacheRoot, "updates"), "RecordLabs-${release.version}.apk")
            .takeIf { it.isFile && (release.size <= 0 || it.length() == release.size) }

    private fun fetchLatest(): Release? {
        val connection = (URL("https://api.github.com/repos/$GITHUB_REPO/releases?per_page=20").openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "RecordLabs-Android/${AppInfo.version}")
        }
        // 404: no release published yet.
        if (connection.responseCode == 404) return null
        if (connection.responseCode !in 200..299) error("HTTP ${connection.responseCode}")
        val body = connection.inputStream.use { it.readBytes().decodeToString() }
        val releases = (json.parseToJsonElement(body) as? JsonArray)?.map { it.jsonObject } ?: return null
        for (release in releases) {
            if (release.flag("draft") || release.flag("prerelease")) continue
            val tag = release["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            val version = tag.trim().lowercase().removePrefix("android-").removePrefix("android").removePrefix("v").trim('-', '_')
            if (versionParts(version).isEmpty()) continue
            val apk = release["assets"]?.jsonArray?.map { it.jsonObject }
                ?.firstOrNull { it["name"]?.jsonPrimitive?.contentOrNull.orEmpty().endsWith(".apk", ignoreCase = true) }
                ?: continue
            val url = apk["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
            return Release(
                version = version,
                notes = release["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                apkUrl = url,
                size = apk["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                pageUrl = release["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$GITHUB_REPO/releases",
            )
        }
        return null
    }

    private fun JsonObject.flag(name: String): Boolean =
        this[name]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true

    /** "2.0.0", "1.9.9.c" -> [2, 0, 0], [1, 9, 9] (letters after the numbers are left out). */
    private fun versionParts(version: String): List<Int> =
        version.trim().substringBefore('-').substringBefore('+').split('.')
            .map { part -> part.takeWhile(Char::isDigit) }
            .takeWhile { it.isNotEmpty() }
            .map { it.toInt() }

    /** Positive when [a] is newer than [b]. */
    fun compareVersions(a: String, b: String): Int {
        val left = versionParts(a)
        val right = versionParts(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }
}
