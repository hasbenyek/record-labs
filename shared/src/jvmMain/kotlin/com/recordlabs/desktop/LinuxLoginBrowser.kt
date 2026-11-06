package com.recordlabs.desktop

import com.recordlabs.desktop.data.Platform
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit

/**
 * Finds the browser Google login opens on Linux, the way Windows opens the
 * user's default browser.
 *
 * Order: the desktop's default web browser (xdg-settings / xdg-mime), then
 * installed Chromium-family browsers, then Firefox. Native, Snap and Flatpak
 * installs are supported; each gets a temporary profile in a folder its
 * sandbox can write to.
 */
internal object LinuxLoginBrowser {
    enum class Engine { Chromium, Firefox }
    enum class Sandbox { Native, Snap, Flatpak }

    data class Browser(
        val name: String,
        /** Program and fixed arguments (for Flatpak: flatpak run <id>). */
        val command: List<String>,
        val engine: Engine,
        val sandbox: Sandbox,
        val sandboxId: String? = null,
        val isDefault: Boolean = false,
    ) {
        /** A fresh, empty profile folder this browser is allowed to use. */
        fun newProfileDir(): File {
            val home = System.getProperty("user.home")
            val root = when (sandbox) {
                Sandbox.Snap -> File(home, "snap/${sandboxId ?: "firefox"}/common/record-labs-login")
                Sandbox.Flatpak -> File(home, ".var/app/${sandboxId ?: "org.mozilla.firefox"}/cache/record-labs-login")
                Sandbox.Native -> File(Platform.cacheRoot, "GoogleLoginBrowser")
            }
            return File(root, "Attempt-${UUID.randomUUID()}").apply { mkdirs() }
        }
    }

    private val chromiumWords = listOf("chrome", "chromium", "brave", "edge", "vivaldi", "opera", "thorium")
    private val firefoxWords = listOf("firefox", "librewolf", "waterfox", "floorp", "zen-browser", "zen_browser", "mullvad-browser")

    fun find(): Browser? = (listOfNotNull(defaultBrowser()) + installedBrowsers()).firstOrNull()

    /** Every browser login could use, default first (for diagnostics). */
    fun all(): List<Browser> = (listOfNotNull(defaultBrowser()) + installedBrowsers()).distinctBy { it.command }

    // ---- default browser -----------------------------------------------------

    private fun defaultBrowser(): Browser? {
        val desktopId = run("xdg-settings", "get", "default-web-browser")
            ?: run("xdg-mime", "query", "default", "x-scheme-handler/https")
            ?: return null
        val file = desktopFile(desktopId.trim()) ?: return null
        return browserFromDesktopFile(file, desktopId.trim())?.copy(isDefault = true)
    }

    private fun desktopFile(id: String): File? {
        if (!id.endsWith(".desktop")) return null
        val home = System.getProperty("user.home")
        val dataHome = System.getenv("XDG_DATA_HOME")?.takeIf(String::isNotBlank) ?: "$home/.local/share"
        val dataDirs = (System.getenv("XDG_DATA_DIRS")?.takeIf(String::isNotBlank) ?: "/usr/local/share:/usr/share").split(':')
        val dirs = listOf(dataHome) + dataDirs + listOf(
            "/var/lib/flatpak/exports/share", "$home/.local/share/flatpak/exports/share", "/var/lib/snapd/desktop",
        )
        return dirs.distinct().map { File(it, "applications/$id") }.firstOrNull(File::isFile)
    }

    private fun browserFromDesktopFile(file: File, id: String): Browser? {
        var inEntry = false
        var exec: String? = null
        var name: String? = null
        for (raw in file.readLines()) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry) continue
            if (exec == null && line.startsWith("Exec=")) exec = line.removePrefix("Exec=")
            if (name == null && line.startsWith("Name=")) name = line.removePrefix("Name=")
        }
        val words = splitExec(exec ?: return null).filterNot { it.startsWith("%") }
        if (words.isEmpty()) return null
        val label = name ?: id.removeSuffix(".desktop")
        val haystack = (id + " " + words.joinToString(" ")).lowercase()
        val engine = when {
            firefoxWords.any { it in haystack } -> Engine.Firefox
            chromiumWords.any { it in haystack } -> Engine.Chromium
            else -> return null
        }
        // flatpak run [options] <app-id> [args]: keep "flatpak run <id>", drop the app's own args.
        val runIndex = words.indexOf("run")
        if (File(words[0]).name == "flatpak" && runIndex >= 0) {
            val appId = words.drop(runIndex + 1).firstOrNull { !it.startsWith("-") } ?: return null
            val options = words.subList(runIndex + 1, words.indexOf(appId)).filter { it.startsWith("--branch") || it.startsWith("--arch") }
            return Browser(label, listOf(words[0], "run") + options + appId, engine, Sandbox.Flatpak, appId)
        }
        val program = words.firstOrNull { !it.startsWith("env") && !it.contains('=') } ?: return null
        val snapName = when {
            program.startsWith("/snap/bin/") -> program.removePrefix("/snap/bin/").substringBefore('.')
            id.contains('_') && File("/snap/bin/${id.substringBefore('_')}").exists() -> id.substringBefore('_')
            else -> null
        }
        val resolved = if (program.contains('/')) File(program) else onPath(program) ?: return null
        if (!resolved.canExecute()) return null
        val sandbox = if (snapName != null || resolved.canonicalPath.startsWith("/snap/")) Sandbox.Snap else Sandbox.Native
        return Browser(label, listOf(resolved.absolutePath), engine, sandbox, snapName ?: resolved.name.takeIf { sandbox == Sandbox.Snap })
    }

    /** Desktop-entry Exec quoting: double quotes group words, backslash escapes. */
    private fun splitExec(exec: String): List<String> {
        val words = mutableListOf<String>()
        val current = StringBuilder()
        var quoted = false
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            when {
                c == '\\' && i + 1 < exec.length -> { current.append(exec[i + 1]); i++ }
                c == '"' -> quoted = !quoted
                c.isWhitespace() && !quoted -> if (current.isNotEmpty()) { words += current.toString(); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) words += current.toString()
        return words
    }

    // ---- installed browsers ---------------------------------------------------

    private fun installedBrowsers(): List<Browser> {
        val home = System.getProperty("user.home")
        val found = mutableListOf<Browser>()
        fun native(name: String, engine: Engine, vararg programs: String) {
            programs.asSequence()
                .mapNotNull { if (it.startsWith("/")) File(it).takeIf(File::canExecute) else onPath(it) }
                .firstOrNull()
                ?.let { file ->
                    val snap = file.canonicalPath.startsWith("/snap/") || file.absolutePath.startsWith("/snap/bin/")
                    found += Browser(name, listOf(file.absolutePath), engine, if (snap) Sandbox.Snap else Sandbox.Native, file.name.takeIf { snap })
                }
        }
        fun flatpak(name: String, engine: Engine, appId: String) {
            val exported = listOf("/var/lib/flatpak/exports/bin/$appId", "$home/.local/share/flatpak/exports/bin/$appId").any { File(it).exists() }
            val flatpakBin = onPath("flatpak")
            if (exported && flatpakBin != null) found += Browser(name, listOf(flatpakBin.absolutePath, "run", appId), engine, Sandbox.Flatpak, appId)
        }
        native("Google Chrome", Engine.Chromium, "google-chrome", "google-chrome-stable", "/opt/google/chrome/google-chrome")
        native("Brave", Engine.Chromium, "brave-browser", "brave", "/opt/brave.com/brave/brave-browser")
        native("Microsoft Edge", Engine.Chromium, "microsoft-edge", "microsoft-edge-stable", "/opt/microsoft/msedge/microsoft-edge")
        native("Vivaldi", Engine.Chromium, "vivaldi", "vivaldi-stable")
        native("Chromium", Engine.Chromium, "chromium", "chromium-browser")
        flatpak("Google Chrome", Engine.Chromium, "com.google.Chrome")
        flatpak("Brave", Engine.Chromium, "com.brave.Browser")
        flatpak("Microsoft Edge", Engine.Chromium, "com.microsoft.Edge")
        flatpak("Chromium", Engine.Chromium, "org.chromium.Chromium")
        native("Firefox", Engine.Firefox, "firefox", "firefox-esr", "/usr/lib/firefox/firefox")
        flatpak("Firefox", Engine.Firefox, "org.mozilla.firefox")
        native("LibreWolf", Engine.Firefox, "librewolf")
        return found
    }

    private fun onPath(program: String): File? = System.getenv("PATH").orEmpty().split(File.pathSeparator)
        .filter(String::isNotBlank).map { File(it, program) }.firstOrNull { it.isFile && it.canExecute() }

    private fun run(vararg command: String): String? = runCatching {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        if (!process.waitFor(3, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            return@runCatching null
        }
        process.inputStream.bufferedReader().readText().trim().takeIf { process.exitValue() == 0 && it.isNotBlank() }
    }.getOrNull()

    /** Ends every process that uses [profile] (also catches Snap/Flatpak children the launcher doesn't own). */
    fun killProfileProcesses(profile: File) {
        val marker = profile.absolutePath
        runCatching {
            ProcessHandle.allProcesses()
                .filter { handle -> handle.info().commandLine().orElse("").contains(marker) }
                .forEach { handle -> handle.destroy() }
        }
    }
}
