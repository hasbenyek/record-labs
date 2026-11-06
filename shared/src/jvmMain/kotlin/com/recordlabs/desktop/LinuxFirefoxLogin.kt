package com.recordlabs.desktop

import com.recordlabs.desktop.data.Platform
import com.recordlabs.desktop.data.WindowsPreferences
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.sql.DriverManager
import java.util.concurrent.TimeUnit

/**
 * Google login in Firefox (the default browser of Pop!_OS, Ubuntu, Fedora...).
 *
 * Firefox is opened normally with a fresh temporary profile. It is NOT put in
 * remote-control mode: Firefox then reports navigator.webdriver = true and
 * Google refuses to sign in ("this browser may not be secure"). Instead the
 * app reads the profile's cookie database (cookies.sqlite) until YouTube's
 * SAPISID sign-in cookie appears, saves the YouTube cookies like the Chromium
 * flow does, closes that Firefox window and deletes the temporary profile.
 */
internal object LinuxFirefoxLogin {
    private val http = OkHttpClient.Builder().callTimeout(12, TimeUnit.SECONDS).build()

    suspend fun login(browser: LinuxLoginBrowser.Browser, startUrl: String, onProgress: (String) -> Unit): Result<Unit> = runCatching {
        val profile = browser.newProfileDir()
        writePrefs(profile)
        val command = browser.command + listOf("--new-instance", "--no-remote", "--profile", profile.absolutePath, startUrl)
        val process = ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .start()
        // Test pages (-Drecordlabs.loginUrl) can't be checked against YouTube Music.
        val testMode = !System.getProperty("recordlabs.loginUrl").isNullOrBlank()
        log("login started: ${browser.name} sandbox=${browser.sandbox} default=${browser.isDefault}")
        try {
            onProgress("${browser.name} terbuka. Login dengan akun Google di sana, lalu tunggu YouTube Music tampil…")
            val started = System.currentTimeMillis()
            val deadline = started + 5 * 60_000L
            // SAPISID appears in the middle of Google's sign-in redirects, before
            // YouTube Music has loaded and before Google has set the rest of the
            // session (__Secure-*PSIDTS, LOGIN_INFO...). Saving right then gave a
            // session YouTube Music did not accept. So: wait until the cookies
            // stop changing, check the session against YouTube Music while the
            // browser is still open, and keep trying until it is accepted.
            var signedInAt = 0L
            var lastCookies = emptyList<Pair<String, String>>()
            var unchangedSince = 0L
            var lastCheckAt = 0L
            var checks = 0
            while (System.currentTimeMillis() < deadline) {
                val now = System.currentTimeMillis()
                val cookies = runCatching { youtubeCookies(profile) }.getOrDefault(emptyList())
                if (cookies.any { it.first == "SAPISID" }) {
                    if (signedInAt == 0L) {
                        signedInAt = now
                        onProgress("Login Google terdeteksi. Menunggu YouTube Music selesai dimuat di ${browser.name}…")
                        log("signed-in cookie seen")
                    }
                    if (cookies != lastCookies) {
                        lastCookies = cookies
                        unchangedSince = now
                    }
                    val settled = now - unchangedSince >= SETTLE_MS && now - signedInAt >= MIN_WAIT_AFTER_SIGN_IN_MS
                    val forced = now - signedInAt >= MAX_WAIT_AFTER_SIGN_IN_MS
                    if ((settled || forced) && now - lastCheckAt >= RECHECK_MS) {
                        lastCheckAt = now
                        checks++
                        if (saveAndCheck(cookies, testMode, checks)) {
                            onProgress("Login berhasil. Menyiapkan sesi YouTube Music…")
                            return@runCatching
                        }
                        onProgress("Menunggu YouTube Music menerima sesi akun… Biarkan ${browser.name} terbuka sampai halaman utama YouTube Music tampil.")
                    }
                    if (now - signedInAt > GIVE_UP_AFTER_SIGN_IN_MS) {
                        error("Login Google berhasil, tetapi YouTube Music belum menerima sesinya. Detail untuk pengecekan: $LOG_PATH")
                    }
                }
                val browserOpen = process.isAlive || anyProcessUses(profile)
                if (!browserOpen && now - started > 4_000L) {
                    // Closed right after signing in: one last check with what was saved.
                    if (signedInAt != 0L) {
                        val finalCookies = runCatching { youtubeCookies(profile) }.getOrDefault(lastCookies)
                        if (saveAndCheck(finalCookies, testMode, ++checks)) return@runCatching
                    }
                    error("${browser.name} ditutup sebelum login selesai.")
                }
                delay(1_000L)
            }
            error("Waktu login habis. Coba lagi.")
        } finally {
            runCatching { process.descendants().toList().asReversed().forEach { if (it.isAlive) it.destroy() } }
            if (process.isAlive) process.destroy()
            LinuxLoginBrowser.killProfileProcesses(profile)
            Thread.sleep(500)
            runCatching { profile.deleteRecursively() }
        }
    }

    private const val SETTLE_MS = 2_500L
    private const val MIN_WAIT_AFTER_SIGN_IN_MS = 4_000L
    private const val MAX_WAIT_AFTER_SIGN_IN_MS = 20_000L
    private const val RECHECK_MS = 4_000L
    private const val GIVE_UP_AFTER_SIGN_IN_MS = 120_000L

    /**
     * Saves the browser's YouTube cookies as the Google session and asks
     * YouTube Music whether it accepts them. The page settings that go with
     * the cookies (visitor id, account page id) are tried with and without,
     * because a wrong page id alone makes YouTube Music refuse good cookies.
     * A refused session is removed again.
     */
    private suspend fun saveAndCheck(cookies: List<Pair<String, String>>, testMode: Boolean, attempt: Int): Boolean {
        if (cookies.none { it.first == "SAPISID" }) return false
        val header = cookies.joinToString("; ") { "${it.first}=${it.second}" }
        val page = youtubeConfig(header)
        if (testMode) {
            WindowsPreferences.saveGoogleSession(header, page.visitor, page.dataSync)
            return true
        }
        val variants = listOf(
            "page" to Pair(page.visitor, page.dataSync),
            "noPageId" to Pair(page.visitor, ""),
            "bare" to Pair("", ""),
        ).distinctBy { it.second }
        val outcomes = mutableListOf<String>()
        for ((name, values) in variants) {
            WindowsPreferences.saveGoogleSession(header, values.first, values.second)
            val (accepted, problem) = runCatching {
                com.recordlabs.desktop.data.WindowsMusicRepository().use { repository ->
                    repository.validateGoogleSession() to repository.lastValidationProblem
                }
            }.getOrElse { false to "${it.javaClass.simpleName}: ${it.message.orEmpty().take(160)}" }
            outcomes += if (accepted) "$name=accepted" else "$name=refused($problem)"
            if (accepted) {
                log("attempt=$attempt ACCEPTED ${outcomes.joinToString("; ")} | ${page.summary} | cookies=${cookies.map { it.first }}")
                return true
            }
        }
        log("attempt=$attempt refused ${outcomes.joinToString("; ")} | ${page.summary} | cookies=${cookies.map { it.first }}")
        WindowsPreferences.clearGoogleSession()
        return false
    }

    private val LOG_PATH: String get() = File(Platform.cacheRoot, "google-login.log").absolutePath

    /**
     * Login troubleshooting log: ~/.cache/record-labs/google-login.log (hidden
     * folder). Cookie names and outcomes only, never cookie values.
     */
    private fun log(line: String) {
        runCatching {
            val file = File(Platform.cacheRoot.apply { mkdirs() }, "google-login.log")
            if (file.length() > 128 * 1024) file.delete()
            file.appendText("${java.time.LocalDateTime.now()} $line\n")
        }
    }

    /** A quiet, private login window: no welcome pages, default-browser prompt or saved passwords. */
    private fun writePrefs(profile: File) {
        File(profile, "user.js").writeText(
            listOf(
                "browser.shell.checkDefaultBrowser" to "false",
                "browser.aboutwelcome.enabled" to "false",
                "browser.startup.homepage_override.mstone" to "\"ignore\"",
                "startup.homepage_welcome_url" to "\"\"",
                "startup.homepage_welcome_url.additional" to "\"\"",
                "datareporting.policy.dataSubmissionPolicyBypassNotification" to "true",
                "toolkit.telemetry.reportingpolicy.firstRun" to "false",
                "browser.tabs.warnOnClose" to "false",
                "browser.warnOnQuitShortcut" to "false",
                "browser.sessionstore.resume_from_crash" to "false",
                "signon.rememberSignons" to "false",
                "extensions.pocket.enabled" to "false",
            ).joinToString("\n", postfix = "\n") { (key, value) -> "user_pref(\"$key\", $value);" },
        )
    }

    /**
     * (name, value) of the youtube.com cookies in the profile. The database is
     * copied first (with its write-ahead log) so Firefox's lock never blocks it.
     */
    internal fun youtubeCookies(profile: File): List<Pair<String, String>> {
        val database = File(profile, "cookies.sqlite")
        if (!database.isFile) return emptyList()
        val copyDir = Files.createTempDirectory(Platform.cacheRoot.apply { mkdirs() }.toPath(), "cookies").toFile()
        try {
            Files.copy(database.toPath(), File(copyDir, "cookies.sqlite").toPath(), StandardCopyOption.REPLACE_EXISTING)
            File(profile, "cookies.sqlite-wal").takeIf(File::isFile)?.let {
                Files.copy(it.toPath(), File(copyDir, "cookies.sqlite-wal").toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            // The SQLite driver unpacks a native library; keep it out of a possibly noexec /tmp.
            System.setProperty("org.sqlite.tmpdir", Platform.cacheRoot.absolutePath)
            Class.forName("org.sqlite.JDBC")
            DriverManager.getConnection("jdbc:sqlite:" + File(copyDir, "cookies.sqlite").absolutePath).use { connection ->
                connection.createStatement().use { statement ->
                    // Only first-party cookies: Firefox also keeps "partitioned" copies
                    // (set inside other sites' frames, originAttributes ^partitionKey=…)
                    // whose values are not the signed-in session. The site-wide
                    // .youtube.com cookie wins over a host-only one of the same name,
                    // then the newest.
                    statement.executeQuery(
                        "SELECT name, value FROM moz_cookies " +
                            "WHERE (host = 'youtube.com' OR host LIKE '%.youtube.com') " +
                            "AND originAttributes NOT LIKE '%partitionKey%' " +
                            "ORDER BY CASE WHEN host = '.youtube.com' THEN 0 ELSE 1 END, lastAccessed DESC",
                    ).use { rows ->
                        val result = mutableListOf<Pair<String, String>>()
                        while (rows.next()) {
                            val name = rows.getString(1).orEmpty()
                            if (name.isNotBlank()) result += name to rows.getString(2).orEmpty()
                        }
                        return result.distinctBy { it.first }.sortedBy { it.first }
                    }
                }
            }
        } finally {
            copyDir.deleteRecursively()
        }
    }

    private data class PageConfig(val visitor: String, val dataSync: String, val summary: String)

    /**
     * VISITOR_DATA and DATASYNC_ID from YouTube Music's page config (the
     * Chromium flow reads them from the open page). DATASYNC_ID is only kept
     * when the page itself is signed in: a signed-out page carries a visitor's
     * id there ("V…"), and sending that as the account page id makes YouTube
     * Music refuse the session.
     */
    private fun youtubeConfig(cookieHeader: String): PageConfig = runCatching {
        val request = Request.Builder()
            .url("https://music.youtube.com/")
            .header("Cookie", cookieHeader)
            .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0")
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.9")
            .build()
        val (code, html) = http.newCall(request).execute().use { it.code to it.body?.string().orEmpty() }
        val loggedIn = Regex("\"LOGGED_IN\"\\s*:\\s*(true|false)").find(html)?.groupValues?.get(1)
        val visitor = Regex("\"VISITOR_DATA\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val rawDataSync = Regex("\"DATASYNC_ID\"\\s*:\\s*\"([^\"]+)\"").find(html)?.groupValues?.get(1).orEmpty()
        val dataSync = if (loggedIn == "true") rawDataSync.substringBefore("||") else ""
        PageConfig(
            visitor,
            dataSync,
            "page http=$code loggedIn=${loggedIn ?: "?"} visitor=${visitor.isNotBlank()} pageId=${if (dataSync.isNotBlank()) "account" else if (rawDataSync.isNotBlank()) "visitor-only" else "none"}",
        )
    }.getOrElse { PageConfig("", "", "page fetch failed: ${it.javaClass.simpleName}") }

    private fun anyProcessUses(profile: File): Boolean = runCatching {
        val marker = profile.absolutePath
        ProcessHandle.allProcesses().anyMatch { it.isAlive && it.info().commandLine().orElse("").contains(marker) }
    }.getOrDefault(false)
}
