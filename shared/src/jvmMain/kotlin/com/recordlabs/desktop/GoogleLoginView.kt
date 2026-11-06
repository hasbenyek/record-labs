package com.recordlabs.desktop

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedButton
import androidx.compose.material.Surface
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.WindowsMusicRepository
import com.recordlabs.desktop.data.WindowsPreferences
import java.awt.Frame
import java.io.File
import java.net.ServerSocket
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import javax.swing.SwingUtilities

/** Real browser login using an isolated Record Labs browser profile. */
@Composable
internal fun DesktopGoogleLoginView(onAuthenticated: () -> Unit) {
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var loginJob by remember { mutableStateOf<Job?>(null) }
    var status by remember {
        mutableStateOf("Login dibuka di browser laptop dan kembali otomatis setelah YouTube Music siap.")
    }

    fun updateStatus(message: String) {
        SwingUtilities.invokeLater { status = message }
    }

    Column(
        // Centred in the space above the floating mini player.
        Modifier.fillMaxSize().padding(28.dp).padding(bottom = MiniPlayerClearance),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = Color(0xEE17191D),
            shape = RoundedCornerShape(28.dp),
            modifier = Modifier.fillMaxWidth(.72f),
        ) {
            Column(Modifier.padding(30.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Login with Google", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Text(status, color = Color.White.copy(alpha = .68f), fontSize = 14.sp)
                Spacer(Modifier.height(24.dp))
                if (running) {
                    CircularProgressIndicator(color = MaterialTheme.colors.primary)
                    Spacer(Modifier.height(16.dp))
                    GlassButton(onClick = {
                        loginJob?.cancel()
                        loginJob = null
                        running = false
                        status = "Login dibatalkan."
                    }) { Text("Cancel") }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        GlassButton(prominent = true, onClick = {
                            running = true
                            status = "Membuka browser default…"
                            loginJob = scope.launch(Dispatchers.IO) {
                                val result = BrowserGoogleLogin.login(::updateStatus)
                                if (result.isSuccess) {
                                    updateStatus("Memvalidasi sesi YouTube Music…")
                                    // A fresh sign-in can take a few seconds to be accepted everywhere
                                    // (first run especially): asked a few times before giving up. A
                                    // network problem is not a refused session: the session is kept.
                                    var valid = false
                                    var lastProblem: String? = null
                                    for (attempt in 1..4) {
                                        val (accepted, problem) = WindowsMusicRepository().use { repository ->
                                            val ok = runCatching { repository.validateGoogleSession() }.getOrDefault(false)
                                            if (ok) runCatching { repository.googleAccountProfile() }
                                            ok to repository.lastValidationProblem
                                        }
                                        if (accepted) { valid = true; break }
                                        lastProblem = problem
                                        if (attempt == 4 && problem?.startsWith("request failed") == true) { valid = true; break }
                                        if (attempt < 4) delay(2_500L)
                                    }
                                    SwingUtilities.invokeLater {
                                        if (valid) {
                                            focusRecordLabsWindow()
                                            onAuthenticated()
                                        }
                                        else {
                                            WindowsPreferences.clearGoogleSession()
                                            loginJob = null
                                            running = false
                                            // The reason (no account data) helps when it keeps happening.
                                            status = "Login Google selesai, tetapi sesi YouTube Music belum aktif. Buka YouTube Music di browser sampai halaman utama tampil lalu coba lagi." +
                                                (lastProblem?.let { "\n($it)" } ?: "")
                                        }
                                    }
                                } else {
                                    if (result.exceptionOrNull() is CancellationException) return@launch
                                    SwingUtilities.invokeLater {
                                        loginJob = null
                                        running = false
                                        status = result.exceptionOrNull()?.message ?: "Login gagal."
                                    }
                                }
                            }
                        }) { Text("Open browser") }
                        GlassButton(onClick = { running = false }) { Text("Cancel") }
                    }
                }
            }
        }
    }
}

internal object BrowserGoogleLogin {
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder().callTimeout(8, TimeUnit.SECONDS).build()

    /** Google's sign-in page, returning to YouTube Music (overridable for tests with -Drecordlabs.loginUrl). */
    private val startUrl: String
        get() = System.getProperty("recordlabs.loginUrl")?.takeIf(String::isNotBlank)
            ?: "https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com"

    /** The page that means "signed in"; follows the test page's origin when one is set. */
    private val musicPagePrefix: String
        get() = System.getProperty("recordlabs.loginUrl")?.takeIf(String::isNotBlank)
            ?.let { java.net.URI(it).let { uri -> "${uri.scheme}://${uri.host}" } }
            ?: "https://music.youtube.com"

    suspend fun login(onProgress: (String) -> Unit): Result<Unit> {
        if (!com.recordlabs.desktop.data.Platform.isWindows) {
            // Linux: the user's default browser first (Firefox included), like Windows.
            val browser = LinuxLoginBrowser.find()
                ?: return Result.failure(IllegalStateException("Tidak ada browser yang bisa dipakai untuk login. Pasang Firefox, Chrome, Chromium atau Brave."))
            onProgress("Membuka ${browser.name}…")
            return if (browser.engine == LinuxLoginBrowser.Engine.Firefox) {
                LinuxFirefoxLogin.login(browser, startUrl, onProgress)
            } else {
                chromiumLogin(browser.command, browser.newProfileDir(), onProgress)
            }
        }
        val browser = findBrowser() ?: return Result.failure(IllegalStateException("Browser berbasis Chromium tidak ditemukan."))
        // A unique non-default profile guarantees that Chromium does not hand
        // the URL to an already-running normal browser instance. When that
        // happened, the user could sign in successfully while Record Labs was
        // watching a different DevTools port forever.
        val profileRoot = File(
            System.getenv("LOCALAPPDATA") ?: System.getProperty("java.io.tmpdir"),
            "RecordLabs/GoogleLoginBrowser",
        ).apply { mkdirs() }
        val profile = File(profileRoot, "Attempt-${UUID.randomUUID()}").apply { mkdirs() }
        return chromiumLogin(listOf(browser.absolutePath), profile, onProgress)
    }

    /** Chromium-family login: isolated profile + DevTools port, cookies read over CDP. */
    private suspend fun chromiumLogin(command: List<String>, profile: File, onProgress: (String) -> Unit): Result<Unit> = runCatching {
        val port = ServerSocket(0).use { it.localPort }
        val process = ProcessBuilder(
            command + listOf(
            "--user-data-dir=${profile.absolutePath}",
            "--remote-debugging-port=$port",
            "--remote-debugging-address=127.0.0.1",
            "--remote-allow-origins=*",
            "--no-first-run",
            "--no-default-browser-check",
            "--new-window",
            // Match Android's login flow: Google owns the visible sign-in
            // page and redirects to YouTube Music only as the completion
            // handshake. Record Labs captures that session, closes this
            // isolated browser and returns focus to the desktop app.
            startUrl,
            ),
        ).start()

        try {
            val deadline = System.currentTimeMillis() + 5 * 60_000L
            var devToolsSeen = false
            var musicPageSeen = false
            var lastDevToolsResponseAt = 0L
            // Signed in, but YouTube Music did not take the session yet (a first sign-in on a
            // new browser profile can need a few more seconds): read it again while the
            // browser is still open, instead of closing it with a session that is refused.
            var sessionChecks = 0
            var firstSessionAt = 0L
            while (System.currentTimeMillis() < deadline) {
                val targets = runCatching { browserTargets(port) }.getOrDefault(emptyList())
                val browserSocket = runCatching { browserWebSocket(port) }.getOrNull()
                devToolsSeen = devToolsSeen || targets.isNotEmpty() || browserSocket != null
                if (targets.isNotEmpty() || browserSocket != null) {
                    lastDevToolsResponseAt = System.currentTimeMillis()
                }
                if (devToolsSeen && !musicPageSeen) {
                    onProgress("Browser siap. Selesaikan login Google lalu tunggu halaman utama YouTube Music tampil…")
                }
                val musicTarget = targets
                    .firstOrNull {
                        it["url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                            .startsWith(musicPagePrefix)
                    }
                musicPageSeen = musicPageSeen || musicTarget != null
                val webSocketUrl = musicTarget?.get("webSocketDebuggerUrl")?.jsonPrimitive?.contentOrNull
                if (webSocketUrl != null) {
                    onProgress("YouTube Music terbuka. Menyiapkan sesi akun…")
                    // Storage.getCookies is browser-scoped and survives target
                    // replacements during Google's account redirect. Keep the
                    // older page-scoped call as a compatibility fallback.
                    val cookieResult = browserSocket?.let {
                        devToolsCall(it, "Storage.getCookies")
                    } ?: devToolsCall(webSocketUrl, "Network.getAllCookies")
                    val cookieObjects = cookieResult?.get("cookies")?.jsonArray.orEmptyArray()
                        .mapNotNull { it as? JsonObject }
                        .filter {
                            val domain = it["domain"]?.jsonPrimitive?.contentOrNull.orEmpty()
                                .removePrefix(".").lowercase()
                            domain == "youtube.com" || domain.endsWith(".youtube.com")
                        }
                    val cookieNames = cookieObjects.mapNotNull {
                        it["name"]?.jsonPrimitive?.contentOrNull
                    }.toSet()
                    val cookies = cookieObjects.mapNotNull { cookie ->
                            val name = cookie["name"]?.jsonPrimitive?.contentOrNull
                            val value = cookie["value"]?.jsonPrimitive?.contentOrNull
                            if (name.isNullOrBlank() || value == null) null else "$name=$value"
                        }
                        .distinct()
                        .joinToString("; ")
                    // SAPISID is the credential used to sign YouTube Music API
                    // requests. Secure PAPISID alone is not interchangeable;
                    // accepting it previously produced a saved-but-invalid
                    // session that immediately returned to the login page.
                    if ("SAPISID" in cookieNames) {
                        if (firstSessionAt == 0L) firstSessionAt = System.currentTimeMillis()
                        val config = devToolsCall(
                            webSocketUrl,
                            "Runtime.evaluate",
                            buildJsonObject {
                                put(
                                    "expression",
                                    "JSON.stringify({visitor:(window.yt&&window.yt.config_&&window.yt.config_.VISITOR_DATA)||'',sync:((window.yt&&window.yt.config_&&window.yt.config_.DATASYNC_ID)||'').split('||')[0]})",
                                )
                                put("returnByValue", true)
                            },
                        )
                        val configText = config?.get("result")?.jsonObject
                            ?.get("value")?.jsonPrimitive?.contentOrNull
                        val configObject = runCatching {
                            configText?.let { json.parseToJsonElement(it).jsonObject }
                        }.getOrNull()
                        val visitor = configObject?.get("visitor")?.jsonPrimitive?.contentOrNull.orEmpty()
                        val sync = configObject?.get("sync")?.jsonPrimitive?.contentOrNull.orEmpty()
                        // The page sets its account settings a moment after the cookies on a
                        // first visit; without them the session can be refused. Wait a little.
                        val pageReady = visitor.isNotBlank() && sync.isNotBlank()
                        if (pageReady || System.currentTimeMillis() - firstSessionAt > 10_000L) {
                            WindowsPreferences.saveGoogleSession(cookies, visitor, sync)
                            onProgress("Memvalidasi sesi YouTube Music…")
                            val accepted = runCatching {
                                WindowsMusicRepository().use { it.validateGoogleSession() }
                            }.getOrDefault(false)
                            sessionChecks++
                            // The account's name and picture, read by the page itself (its own
                            // request, as the website makes it) while the browser is still open.
                            if (accepted) runCatching { readAccountFromPage(webSocketUrl) }
                            // Accepted, or still refused after ~30 s: hand over (the view asks once
                            // more and explains when it still fails).
                            if (accepted || sessionChecks >= 10) return@runCatching
                            onProgress("Login berhasil. Menunggu YouTube Music mengaktifkan sesi…")
                            delay(2_000L)
                        } else {
                            onProgress("Login berhasil. Menyiapkan YouTube Music…")
                        }
                    }
                }
                if (!process.isAlive && !devToolsSeen) {
                    error("Browser login gagal dimulai. Tutup jendela login lama lalu coba lagi.")
                }
                if (!process.isAlive && devToolsSeen &&
                    System.currentTimeMillis() - lastDevToolsResponseAt > 8_000L
                ) {
                    error("Browser ditutup sebelum sesi YouTube Music berhasil diterima.")
                }
                delay(900L)
            }
            error("Waktu login habis. Coba lagi.")
        } finally {
            // Close only the isolated login browser tree, never the user's
            // normal browser session.
            runCatching {
                process.descendants().toList().asReversed().forEach { child ->
                    if (child.isAlive) child.destroy()
                }
            }
            if (process.isAlive) process.destroy()
            // Snap/Flatpak browsers run outside this process tree.
            if (!com.recordlabs.desktop.data.Platform.isWindows) LinuxLoginBrowser.killProfileProcesses(profile)
            runCatching {
                profile.walkBottomUp().forEach { file ->
                    if (!file.delete()) file.deleteOnExit()
                }
            }
        }
    }

    private fun browserWebSocket(port: Int): String {
        val request = Request.Builder().url("http://127.0.0.1:$port/json/version").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Browser login belum siap")
            val version = json.parseToJsonElement(response.body?.string().orEmpty()).jsonObject
            version["webSocketDebuggerUrl"]?.jsonPrimitive?.contentOrNull
                ?: error("Browser DevTools belum siap")
        }
    }

    private fun browserTargets(port: Int): List<JsonObject> {
        val request = Request.Builder().url("http://127.0.0.1:$port/json/list").build()
        return client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) error("Browser login belum siap")
            json.parseToJsonElement(response.body?.string().orEmpty())
                .jsonArray.mapNotNull { it as? JsonObject }
        }
    }

    /**
     * Asks YouTube Music's account menu from inside the signed-in page (the website's own
     * request: its cookies and settings), and keeps the account's name and picture. The
     * avatar shown in the page's top bar is the fallback for the picture.
     */
    private fun readAccountFromPage(webSocketUrl: String) {
        val script = """
            (async () => {
              const read = (n) => (document.cookie.split('; ').find((c) => c.startsWith(n + '=')) || '').slice(n.length + 1);
              const ts = Math.floor(Date.now() / 1000);
              const hash = async (s) => [...new Uint8Array(await crypto.subtle.digest('SHA-1', new TextEncoder().encode(ts + ' ' + s + ' https://music.youtube.com')))].map((b) => b.toString(16).padStart(2, '0')).join('');
              let auth = 'SAPISIDHASH ' + ts + '_' + await hash(read('SAPISID'));
              if (read('__Secure-1PAPISID')) auth += ' SAPISID1PHASH ' + ts + '_' + await hash(read('__Secure-1PAPISID'));
              if (read('__Secure-3PAPISID')) auth += ' SAPISID3PHASH ' + ts + '_' + await hash(read('__Secure-3PAPISID'));
              const context = (window.ytcfg && ytcfg.get && ytcfg.get('INNERTUBE_CONTEXT')) || { client: { clientName: 'WEB_REMIX', clientVersion: (window.ytcfg && ytcfg.get && ytcfg.get('INNERTUBE_CLIENT_VERSION')) || '1.20250101.01.00' } };
              let menu = '';
              try {
                const r = await fetch('/youtubei/v1/account/account_menu?prettyPrint=false', {
                  method: 'POST', credentials: 'include',
                  headers: { 'Content-Type': 'application/json', 'Authorization': auth, 'X-Goog-AuthUser': '0', 'X-Origin': 'https://music.youtube.com' },
                  body: JSON.stringify({ context }),
                });
                menu = await r.text();
              } catch (e) {}
              const img = document.querySelector('ytmusic-settings-button img, #right-content img#img, tp-yt-paper-icon-button img');
              return JSON.stringify({ menu, avatar: (img && img.src) || '' });
            })()
        """.trimIndent()
        val result = devToolsCall(
            webSocketUrl,
            "Runtime.evaluate",
            buildJsonObject {
                put("expression", script)
                put("awaitPromise", true)
                put("returnByValue", true)
            },
        ) ?: return
        val text = result["result"]?.jsonObject?.get("value")?.jsonPrimitive?.contentOrNull ?: return
        val value = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return
        val menu = value["menu"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val avatar = value["avatar"]?.jsonPrimitive?.contentOrNull.orEmpty().takeIf { it.startsWith("http") }
        val profile = if (menu.isNotBlank()) WindowsMusicRepository().use { it.accountProfileFromMenu(menu) } else null
        when {
            profile != null -> WindowsPreferences.saveGoogleAccountProfile(profile.name, profile.photoUrl ?: avatar, profile.email, profile.channelHandle)
            avatar != null && WindowsPreferences.googleAccountPhoto().isBlank() ->
                WindowsPreferences.saveGoogleAccountProfile(WindowsPreferences.googleAccountName(), avatar)
        }
    }

    private fun devToolsCall(
        webSocketUrl: String,
        method: String,
        params: JsonObject? = null,
    ): JsonObject? {
        val latch = CountDownLatch(1)
        var payload: JsonObject? = null
        val socket = client.newWebSocket(
            Request.Builder().url(webSocketUrl).build(),
            object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) {
                    webSocket.send(buildJsonObject {
                        put("id", 1)
                        put("method", method)
                        params?.let { put("params", it) }
                    }.toString())
                }

                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = runCatching {
                        json.parseToJsonElement(text).jsonObject
                    }.getOrNull() ?: return
                    if (message["id"]?.jsonPrimitive?.contentOrNull == "1") {
                        payload = message["result"] as? JsonObject
                        latch.countDown()
                        webSocket.close(1000, null)
                    }
                }

                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                    latch.countDown()
                }
            },
        )
        latch.await(6, TimeUnit.SECONDS)
        socket.cancel()
        return payload
    }

    private fun findBrowser(): File? {
        findWindowsDefaultBrowser()?.takeIf(File::isFile)?.let { return it }
        val roots = listOfNotNull(
            System.getenv("ProgramFiles(x86)"),
            System.getenv("ProgramFiles"),
            System.getenv("LOCALAPPDATA"),
        )
        val relativePaths = listOf(
            "BraveSoftware/Brave-Browser/Application/brave.exe",
            "Microsoft/Edge/Application/msedge.exe",
            "Google/Chrome/Application/chrome.exe",
        )
        return roots.asSequence()
            .flatMap { root -> relativePaths.asSequence().map { File(root, it) } }
            .firstOrNull(File::isFile)
    }

    /** Resolve the Windows HTTPS association so login opens in the user's chosen browser. */
    private fun findWindowsDefaultBrowser(): File? = runCatching {
        fun regQuery(key: String, value: String? = null): String {
            val command = mutableListOf("reg", "query", key)
            if (value == null) command += "/ve" else command += listOf("/v", value)
            return ProcessBuilder(command).redirectErrorStream(true).start().let { process ->
                process.inputStream.bufferedReader().use { it.readText() }.also { process.waitFor() }
            }
        }
        val userChoice = regQuery(
            "HKCU\\Software\\Microsoft\\Windows\\Shell\\Associations\\UrlAssociations\\https\\UserChoice",
            "ProgId",
        )
        val progId = userChoice.lineSequence().map(String::trim)
            .firstOrNull { it.startsWith("ProgId", ignoreCase = true) }
            ?.substringAfter("REG_SZ")?.trim().orEmpty()
        if (progId.isBlank()) return@runCatching null
        val openCommand = regQuery("HKCR\\$progId\\shell\\open\\command")
        val commandText = openCommand.lineSequence().map(String::trim)
            .firstOrNull { it.contains("REG_SZ") }
            ?.substringAfter("REG_SZ")?.trim().orEmpty()
        val executable = Regex("^\\\"([^\\\"]+\\.exe)\\\"").find(commandText)?.groupValues?.getOrNull(1)
            ?: Regex("^([^\\s]+\\.exe)").find(commandText)?.groupValues?.getOrNull(1)
        executable?.let(::File)?.takeIf { file ->
            file.isFile && file.name.lowercase() in setOf("brave.exe", "chrome.exe", "msedge.exe", "vivaldi.exe", "opera.exe")
        }
    }.getOrNull()
}

private fun focusRecordLabsWindow() {
    val frame = Frame.getFrames().firstOrNull { it.isDisplayable && it.title == "Record Labs" }
        ?: Frame.getFrames().firstOrNull { it.isDisplayable }
        ?: return
    if ((frame.extendedState and Frame.ICONIFIED) != 0) {
        frame.extendedState = frame.extendedState and Frame.ICONIFIED.inv()
    }
    frame.isVisible = true
    frame.toFront()
    frame.requestFocus()
}

private fun JsonArray?.orEmptyArray(): JsonArray = this ?: JsonArray(emptyList())
