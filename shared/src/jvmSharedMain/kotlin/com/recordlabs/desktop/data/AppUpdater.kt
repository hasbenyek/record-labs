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
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.security.MessageDigest
import java.time.Duration

/**
 * In-app updates from GitHub Releases.
 *
 * How the app knows there is an update: it reads the newest release of
 * [GITHUB_REPO] (`/releases/latest`, which skips drafts and pre-releases),
 * takes its tag (`v0.1.3` or `0.1.3`) as the version and compares it with
 * [AppInfo.version]. When the release is newer, the profile icon shows a "1".
 *
 * "Update now" downloads the release's .msi in the background (verified by
 * size and, when GitHub reports it, SHA-256). The ready installer then runs
 * either when Record Labs is closed, or straight away with "Restart to update",
 * which also opens Record Labs again afterwards. The installer is given the
 * current install folder, so the app (and its data) stay where they are.
 */
object AppUpdater {
    /**
     * The GitHub repository releases come from, as "owner/repo"
     * (for example "byonkiee/record-labs-windows"). The repository must be
     * public. Left empty, Record Labs never checks for updates.
     */
    const val GITHUB_REPO = "hasbenyek/record-labs-windows"

    data class Release(
        val version: String,
        val notes: String,
        val downloadUrl: String,
        val fileName: String,
        val size: Long,
        val sha256: String?,
        val pageUrl: String,
    )

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data class UpToDate(val checkedAt: Long) : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val progress: Float) : State
        data class Ready(val release: Release, val file: File) : State
        data class Failed(val message: String, val release: Release?) : State
    }

    val state = mutableStateOf<State>(State.Idle)

    /** Shown in About when the previous install did not finish. */
    val lastInstallProblem = mutableStateOf<String?>(null)

    // Overridable with -Drecordlabs.updateRepo=owner/repo (for trying a test repository).
    private val repository: String get() = (System.getProperty("recordlabs.updateRepo")?.takeIf(String::isNotBlank) ?: GITHUB_REPO).trim()
    private val apiBase: String get() = System.getProperty("recordlabs.updateApi")?.takeIf(String::isNotBlank)?.trimEnd('/') ?: "https://api.github.com"

    val configured: Boolean get() = repository.count { it == '/' } == 1

    /** The number on the profile icon: 1 while an update is waiting. */
    val badgeCount: Int
        get() = when (val s = state.value) {
            is State.Available, is State.Downloading, is State.Ready -> 1
            is State.Failed -> if (s.release != null) 1 else 0
            else -> 0
        }

    /** Only the installed app (not a development run) can install updates. */
    val canInstall: Boolean get() = appExecutable() != null

    /** Set by main(): closes Record Labs the normal way (saves state first). */
    @Volatile var exitApp: (() -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val checkLock = Mutex()
    @Volatile private var downloadJob: Job? = null
    @Volatile private var relaunchAfterInstall = false
    @Volatile private var installerLaunched = false

    private val http: HttpClient by lazy {
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .connectTimeout(Duration.ofSeconds(15))
            .build()
    }

    private val updatesDir: File by lazy { File(WindowsStorage.directory.parentFile, "updates").apply { mkdirs() } }
    private val resultFile: File get() = File(updatesDir, "last-install.txt")

    /** Startup: removes installers that are already installed and reads the last install's result. */
    fun cleanUp() {
        runCatching {
            val result = resultFile.takeIf(File::isFile)?.readText()?.trim().orEmpty()
            if (result.isNotEmpty()) {
                val target = result.substringBefore(' ')
                val code = result.substringAfter(' ', "").trim().toIntOrNull()
                // 0 = done, 3010 = done but Windows wants a restart.
                if (compareVersions(target, AppInfo.version) > 0 && code != null && code != 0 && code != 3010) {
                    lastInstallProblem.value = if (code == 1602 || code == 1223) {
                        "The update to $target was cancelled."
                    } else "The update to $target didn't finish (code $code). Try again from here."
                }
                resultFile.delete()
            }
            updatesDir.listFiles()?.forEach { file ->
                val version = versionOfFile(file.name)
                if (file.name.endsWith(".part") || (version != null && compareVersions(version, AppInfo.version) <= 0)) file.delete()
            }
        }
    }

    /** Looks for a newer release. [manual] shows "Checking…" and reports problems. */
    suspend fun check(manual: Boolean) {
        if (!configured) return
        checkLock.withLock {
            val before = state.value
            // A download in progress or a ready installer is left alone.
            if (before is State.Downloading || before is State.Ready) return
            if (manual) state.value = State.Checking
            val release = runCatching { withContext(Dispatchers.IO) { fetchLatest() } }
            release.onFailure {
                if (manual) state.value = State.Failed("Couldn't check for updates. Check your connection and try again.", null)
                else if (before is State.Checking) state.value = State.Idle
            }
            release.onSuccess { found ->
                state.value = when {
                    found == null -> State.UpToDate(System.currentTimeMillis())
                    compareVersions(found.version, AppInfo.version) <= 0 -> State.UpToDate(System.currentTimeMillis())
                    else -> existingDownload(found)?.let { State.Ready(found, it) } ?: State.Available(found)
                }
            }
        }
    }

    fun checkNow() {
        scope.launch { check(manual = true) }
    }

    /** "Update now": downloads in the background; the app stays usable. */
    fun download() {
        val release = when (val s = state.value) {
            is State.Available -> s.release
            is State.Failed -> s.release ?: return
            else -> return
        }
        if (downloadJob?.isActive == true) return
        state.value = State.Downloading(release, 0f)
        downloadJob = scope.launch {
            val part = File(updatesDir, release.fileName + ".part")
            try {
                val request = HttpRequest.newBuilder(URI(release.downloadUrl))
                    .header("User-Agent", "RecordLabs/${AppInfo.version}")
                    .header("Accept", "application/octet-stream")
                    .timeout(Duration.ofMinutes(30))
                    .GET()
                    .build()
                val response = http.send(request, HttpResponse.BodyHandlers.ofInputStream())
                if (response.statusCode() !in 200..299) {
                    response.body().close()
                    error("HTTP ${response.statusCode()}")
                }
                val total = response.headers().firstValueAsLong("Content-Length").orElse(release.size).takeIf { it > 0 } ?: release.size
                val digest = MessageDigest.getInstance("SHA-256")
                var received = 0L
                var shown = -1
                response.body().use { input ->
                    part.outputStream().buffered().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            received += read
                            if (total > 0) {
                                val percent = (received * 100 / total).toInt().coerceIn(0, 100)
                                if (percent != shown) {
                                    shown = percent
                                    state.value = State.Downloading(release, percent / 100f)
                                }
                            }
                        }
                    }
                }
                if (release.size > 0 && received != release.size) error("incomplete download")
                val hash = digest.digest().joinToString("") { "%02x".format(it) }
                if (release.sha256 != null && !hash.equals(release.sha256, ignoreCase = true)) error("the file didn't pass its check")
                val target = File(updatesDir, release.fileName)
                target.delete()
                if (!part.renameTo(target)) error("couldn't save the installer")
                runCatching { File(updatesDir, release.fileName + ".sha256").writeText(hash) }
                state.value = State.Ready(release, target)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                part.delete()
                state.value = State.Available(release)
                throw cancelled
            } catch (error: Throwable) {
                part.delete()
                state.value = State.Failed("The download failed (${error.message ?: "network error"}). Try again.", release)
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** "Restart to update": closes the app, installs, then opens it again. */
    fun restartToUpdate() {
        if (state.value !is State.Ready || !canInstall) return
        relaunchAfterInstall = true
        val exit = exitApp
        if (exit != null) exit() else {
            installOnExit()
            kotlin.system.exitProcess(0)
        }
    }

    /**
     * Called while Record Labs closes. A ready update starts installing as soon
     * as this process has ended; after "Restart to update" the app reopens.
     */
    fun installOnExit() {
        if (installerLaunched) return
        val ready = state.value as? State.Ready ?: return
        val exe = appExecutable() ?: return
        if (!ready.file.isFile) return
        installerLaunched = runCatching { launchInstaller(ready, exe, relaunchAfterInstall) }.getOrDefault(false)
    }

    // ---- internals ----

    fun currentPackageType(): String {
        System.getProperty("recordlabs.packageType")?.takeIf(String::isNotBlank)?.let { return it.lowercase() }
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) return "msi"
        if (System.getenv("APPIMAGE") != null) return "appimage"
        if (File("/etc/debian_version").isFile) return "deb"
        if (File("/etc/fedora-release").isFile || File("/etc/redhat-release").isFile || File("/etc/SuSE-release").isFile || File("/etc/zypp").isDirectory) return "rpm"
        return "rpm"
    }

    private fun fetchLatest(): Release? {
        val uri = if (apiBase.endsWith("/releases") || apiBase.endsWith("/releases/latest") || apiBase.endsWith("/")) {
            URI(apiBase)
        } else {
            URI("$apiBase/repos/$repository/releases")
        }
        val request = HttpRequest.newBuilder(uri)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "RecordLabs/${AppInfo.version}")
            .timeout(Duration.ofSeconds(20))
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        // 404: the repository has no published release yet.
        if (response.statusCode() == 404) return null
        if (response.statusCode() !in 200..299) error("HTTP ${response.statusCode()}")
        val jsonElement = Json.parseToJsonElement(response.body())
        val releasesArray = when (jsonElement) {
            is kotlinx.serialization.json.JsonArray -> jsonElement.map { it.jsonObject }
            is kotlinx.serialization.json.JsonObject -> listOf(jsonElement)
            else -> return null
        }

        val pkgType = currentPackageType()
        fun nameOf(asset: kotlinx.serialization.json.JsonObject) = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()

        for (json in releasesArray) {
            val isDraft = json["draft"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            val isPrerelease = json["prerelease"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false
            if (isDraft || isPrerelease) continue

            val tag = json["tag_name"]?.jsonPrimitive?.contentOrNull ?: continue
            val version = tag.trim().removePrefix("v").removePrefix("V")
            if (parseVersion(version).isEmpty()) continue

            val assets = json["assets"]?.jsonArray?.map { it.jsonObject }.orEmpty()

            val asset = when (pkgType) {
                "deb" -> assets.firstOrNull { nameOf(it).endsWith(".deb", ignoreCase = true) }
                "rpm" -> assets.firstOrNull { nameOf(it).endsWith(".rpm", ignoreCase = true) }
                "appimage" -> assets.firstOrNull { nameOf(it).endsWith(".appimage", ignoreCase = true) }
                else -> assets.firstOrNull { nameOf(it).endsWith(".msi", ignoreCase = true) }
                    ?: assets.firstOrNull { nameOf(it).endsWith(".exe", ignoreCase = true) }
            } ?: continue

            val url = asset["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: continue
            val originalName = nameOf(asset)
            val extension = if (originalName.endsWith(".AppImage", ignoreCase = true)) "AppImage"
                else originalName.substringAfterLast('.', pkgType).lowercase()

            return Release(
                version = version,
                notes = json["body"]?.jsonPrimitive?.contentOrNull.orEmpty().trim(),
                downloadUrl = url,
                fileName = "RecordLabs-$version.$extension",
                size = asset["size"]?.jsonPrimitive?.longOrNull ?: 0L,
                sha256 = asset["digest"]?.jsonPrimitive?.contentOrNull
                    ?.takeIf { it.startsWith("sha256:", ignoreCase = true) }?.substringAfter(':'),
                pageUrl = json["html_url"]?.jsonPrimitive?.contentOrNull ?: "https://github.com/$repository/releases",
            )
        }
        return null
    }

    /** An installer downloaded earlier (for example before a restart) that still checks out. */
    private fun existingDownload(release: Release): File? {
        val file = File(updatesDir, release.fileName)
        if (!file.isFile) return null
        if (release.size > 0 && file.length() != release.size) return null
        if (release.sha256 != null) {
            val saved = File(updatesDir, release.fileName + ".sha256").takeIf(File::isFile)?.readText()?.trim()
            val hash = saved ?: runCatching { sha256Of(file) }.getOrNull() ?: return null
            if (!hash.equals(release.sha256, ignoreCase = true)) return null
        }
        return file
    }

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun appExecutable(): File? {
        if (scriptOnly) return File("/fake/launcher")
        if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            return System.getProperty("jpackage.app-path")?.let(::File)
                ?.takeIf { it.isFile && it.name.endsWith(".exe", ignoreCase = true) }
        }
        System.getenv("APPIMAGE")?.let(::File)?.takeIf { it.isFile && it.canExecute() }?.let { return it }
        return System.getProperty("jpackage.app-path")?.let(::File)
            ?.takeIf { it.isFile && it.canExecute() }
    }

    private fun nullOutput() = File(if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) "NUL" else "/dev/null")

    private fun psQuote(value: String) = "'" + value.replace("'", "''") + "'"

    /** For tests: the PowerShell/shell script that would run the installer. */
    internal var scriptOnly = false

    private fun launchInstaller(ready: State.Ready, exe: File, relaunch: Boolean): Boolean {
        return if (System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)) {
            launchWindowsInstaller(ready, exe, relaunch)
        } else {
            launchLinuxInstaller(ready, exe, relaunch)
        }
    }

    private fun launchWindowsInstaller(ready: State.Ready, exe: File, relaunch: Boolean): Boolean {
        val installDir = exe.absoluteFile.parentFile.absolutePath.trimEnd('\\')
        val installer = ready.file.absolutePath
        val log = File(updatesDir, "install-${ready.release.version}.log").absolutePath
        val isMsi = installer.endsWith(".msi", ignoreCase = true)
        // msiexec reads its own command line; INSTALLDIR keeps the current
        // folder (Record Labs keys its data folder on it).
        val msiArgs = "/i \"$installer\" /passive /norestart INSTALLDIR=\"$installDir\" /l*v \"$log\""
        val script = buildString {
            appendLine("\$ErrorActionPreference = 'Continue'")
            appendLine("try { Wait-Process -Id ${ProcessHandle.current().pid()} -Timeout 120 -ErrorAction SilentlyContinue } catch {}")
            appendLine("Start-Sleep -Milliseconds 700")
            appendLine("\$code = -1")
            appendLine("try {")
            if (isMsi) {
                appendLine("  \$p = Start-Process -FilePath 'msiexec.exe' -ArgumentList ${psQuote(msiArgs)} -Wait -PassThru")
            } else {
                appendLine("  \$p = Start-Process -FilePath ${psQuote(installer)} -Wait -PassThru")
            }
            appendLine("  \$code = \$p.ExitCode")
            appendLine("} catch { \$code = 1223 }")
            appendLine("Set-Content -Path ${psQuote(resultFile.absolutePath)} -Value (${psQuote(ready.release.version)} + ' ' + \$code)")
            if (relaunch) appendLine("Start-Process -FilePath ${psQuote(exe.absolutePath)}")
        }
        val scriptFile = File(updatesDir, "install-update.ps1")
        // UTF-8 with BOM so Windows PowerShell 5 reads non-ASCII paths correctly.
        scriptFile.writeBytes(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + script.replace("\n", "\r\n").toByteArray(Charsets.UTF_8))
        if (scriptOnly) return true
        val command = "powershell.exe -NoProfile -NonInteractive -ExecutionPolicy Bypass -WindowStyle Hidden -File \"${scriptFile.absolutePath}\""
        return launchWithoutWindow(command) || runCatching {
            ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(nullOutput())
                .start()
            true
        }.getOrDefault(false)
    }

    private fun launchLinuxInstaller(ready: State.Ready, exe: File, relaunch: Boolean): Boolean {
        val scriptFile = File(updatesDir, "install-update.sh")
        val pid = ProcessHandle.current().pid()
        val pkg = currentPackageType()
        val isAppImage = pkg == "appimage" || ready.file.name.endsWith(".AppImage", ignoreCase = true)
        val isDeb = pkg == "deb" || ready.file.name.endsWith(".deb", ignoreCase = true)
        val isRpm = pkg == "rpm" || ready.file.name.endsWith(".rpm", ignoreCase = true)

        val script = buildString {
            appendLine("#!/bin/sh")
            appendLine("pid=$pid")
            appendLine("while kill -0 \"\$pid\" 2>/dev/null; do sleep 0.1; done")
            if (isAppImage) {
                appendLine("cp '${ready.file.absolutePath}' '${exe.absolutePath}'")
                appendLine("code=$?")
                appendLine("chmod +x '${exe.absolutePath}'")
            } else if (isDeb) {
                appendLine("pkexec sh -c \"dpkg -i '${ready.file.absolutePath}'\"")
                appendLine("code=$?")
            } else if (isRpm) {
                appendLine("pkexec sh -c \"rpm -Uvh --replacepkgs '${ready.file.absolutePath}'\"")
                appendLine("code=$?")
            } else {
                appendLine("code=1")
            }
            appendLine("echo '${ready.release.version}' \$code > '${resultFile.absolutePath}'")
            if (relaunch) {
                appendLine("if [ \$code -eq 0 ]; then")
                appendLine("  '${exe.absolutePath}' &")
                appendLine("fi")
            }
        }

        scriptFile.writeText(script)
        scriptFile.setExecutable(true)
        if (scriptOnly) return true

        return runCatching {
            ProcessBuilder("sh", scriptFile.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(nullOutput())
                .start()
            true
        }.getOrDefault(false)
    }

    /** Starts [commandLine] with no console window, independent of this process. */
    private fun launchWithoutWindow(commandLine: String): Boolean = launchWindowsProcessWithoutWindow(commandLine)

    private fun versionOfFile(name: String): String? =
        Regex("""^RecordLabs-([0-9][0-9A-Za-z.\-]*?)\.(msi|exe|deb|rpm|appimage)(\.part|\.sha256)?$""", RegexOption.IGNORE_CASE)
            .find(name)?.groupValues?.get(1)

    private fun parseVersion(version: String): List<Int> =
        version.trim().removePrefix("v").removePrefix("V").substringBefore('-').substringBefore('+')
            .split('.').map { part -> part.takeWhile(Char::isDigit) }
            .takeIf { parts -> parts.isNotEmpty() && parts.all { it.isNotEmpty() } }
            ?.map { it.toInt() }.orEmpty()

    /** Positive when [a] is newer than [b]. */
    fun compareVersions(a: String, b: String): Int {
        val left = parseVersion(a)
        val right = parseVersion(b)
        for (index in 0 until maxOf(left.size, right.size)) {
            val difference = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (difference != 0) return difference
        }
        return 0
    }
}

/** Windows: starts a process with no console window, independent of this one (false elsewhere). */
internal expect fun launchWindowsProcessWithoutWindow(commandLine: String): Boolean
