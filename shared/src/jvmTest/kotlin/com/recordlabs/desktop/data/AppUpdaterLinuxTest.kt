package com.recordlabs.desktop.data

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The updater reads several releases and takes the newest one that carries a
 * file for this system: a Windows-only release must not hide a Linux update.
 */
class AppUpdaterLinuxTest {
    @Test
    fun linuxDebInstallPicksNewestReleaseWithADeb() {
        if (Platform.isWindows) return
        val releases = """
            [
              {"tag_name":"v9.0.1","draft":false,"prerelease":true,"html_url":"https://example.invalid/pre","body":"",
               "assets":[{"name":"record-labs_9.0.1_amd64.deb","browser_download_url":"https://example.invalid/pre.deb","size":1}]},
              {"tag_name":"v9.0.0","draft":false,"prerelease":false,"html_url":"https://example.invalid/win","body":"",
               "assets":[{"name":"Record Labs-9.0.0.msi","browser_download_url":"https://example.invalid/a.msi","size":10}]},
              {"tag_name":"v8.5.0","draft":false,"prerelease":false,"html_url":"https://example.invalid/linux","body":"Linux fixes",
               "assets":[
                 {"name":"Record Labs-8.5.0.msi","browser_download_url":"https://example.invalid/b.msi","size":10},
                 {"name":"record-labs_8.5.0_amd64.deb","browser_download_url":"https://example.invalid/c.deb","size":20,"digest":"sha256:abc"},
                 {"name":"record-labs-8.5.0-1.x86_64.rpm","browser_download_url":"https://example.invalid/d.rpm","size":30},
                 {"name":"RecordLabs-8.5.0-x86_64.AppImage","browser_download_url":"https://example.invalid/e.AppImage","size":40}
               ]}
            ]
        """.trimIndent().toByteArray()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { exchange ->
                exchange.sendResponseHeaders(200, releases.size.toLong())
                exchange.responseBody.use { it.write(releases) }
            }
            start()
        }
        System.setProperty("recordlabs.packageType", "deb")
        System.setProperty("recordlabs.updateApi", "http://127.0.0.1:${server.address.port}")
        try {
            runBlocking { AppUpdater.check(manual = true) }
            val state = AppUpdater.state.value
            assertTrue(state is AppUpdater.State.Available, "update found: $state")
            assertEquals("8.5.0", state.release.version)
            assertEquals("https://example.invalid/c.deb", state.release.downloadUrl)
            assertEquals("RecordLabs-8.5.0.deb", state.release.fileName)
            assertEquals("abc", state.release.sha256)
        } finally {
            server.stop(0)
            System.clearProperty("recordlabs.updateApi")
            System.clearProperty("recordlabs.packageType")
        }
    }

    /** The AppImage update script swaps the file in place and records the result. */
    @Test
    fun appImageUpdateScriptReplacesTheAppImage() {
        if (Platform.isWindows) return
        val dir = kotlin.io.path.createTempDirectory("rl-appimage").toFile()
        val current = File(dir, "Record Labs.AppImage").apply { writeText("old"); setExecutable(true) }
        val downloaded = File(dir, "RecordLabs-8.5.0.AppImage").apply { writeText("new") }
        val release = AppUpdater.Release("8.5.0", "", "https://example.invalid/e.AppImage", downloaded.name, 3, null, "https://example.invalid")
        System.setProperty("recordlabs.packageType", "appimage")
        try {
            val method = AppUpdater::class.java.getDeclaredMethod(
                "launchLinuxInstaller", AppUpdater.State.Ready::class.java, File::class.java, Boolean::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            AppUpdater.scriptOnly = true
            assertTrue(method.invoke(AppUpdater, AppUpdater.State.Ready(release, downloaded), current, false) as Boolean)
            val updatesDir = File(WindowsStorage.directory.parentFile, "updates")
            val script = File(updatesDir, "install-update.sh")
            // Run it as Record Labs would after exiting, with an already-finished pid.
            val text = script.readText().replace(Regex("""^pid=\d+$""", RegexOption.MULTILINE), "pid=999999999")
            val runnable = File(dir, "install-update.sh").apply { writeText(text) }
            val process = ProcessBuilder("sh", runnable.absolutePath).redirectErrorStream(true).start()
            val output = process.inputStream.bufferedReader().readText()
            assertEquals(0, process.waitFor(), output)
            assertEquals("new", current.readText(), "AppImage replaced")
            assertTrue(current.canExecute(), "still executable")
            assertEquals("8.5.0 0", File(updatesDir, "last-install.txt").readText().trim())
            File(updatesDir, "last-install.txt").delete()
        } finally {
            AppUpdater.scriptOnly = false
            System.clearProperty("recordlabs.packageType")
            dir.deleteRecursively()
        }
    }

    /** The .deb/.rpm install scripts (run through pkexec) are valid shell. */
    @Test
    fun packageUpdateScriptsAreValidShell() {
        if (Platform.isWindows) return
        val dir = kotlin.io.path.createTempDirectory("rl-pkg").toFile()
        val launcher = File(dir, "record-labs").apply { writeText("#!/bin/sh\n"); setExecutable(true) }
        val method = AppUpdater::class.java.getDeclaredMethod(
            "launchLinuxInstaller", AppUpdater.State.Ready::class.java, File::class.java, Boolean::class.javaPrimitiveType,
        ).apply { isAccessible = true }
        try {
            AppUpdater.scriptOnly = true
            for (kind in listOf("deb", "rpm")) {
                System.setProperty("recordlabs.packageType", kind)
                val file = File(dir, "RecordLabs-8.5.0.$kind").apply { writeText("x") }
                val release = AppUpdater.Release("8.5.0", "", "https://example.invalid/f", file.name, 1, null, "https://example.invalid")
                assertTrue(method.invoke(AppUpdater, AppUpdater.State.Ready(release, file), launcher, true) as Boolean)
                val script = File(File(WindowsStorage.directory.parentFile, "updates"), "install-update.sh")
                val text = script.readText()
                assertTrue(text.contains("pkexec sh -c"), text)
                assertTrue(text.contains(file.absolutePath), text)
                val check = ProcessBuilder("sh", "-n", script.absolutePath).redirectErrorStream(true).start()
                val output = check.inputStream.bufferedReader().readText()
                assertEquals(0, check.waitFor(), "$kind script syntax: $output\n$text")
            }
        } finally {
            AppUpdater.scriptOnly = false
            System.clearProperty("recordlabs.packageType")
            dir.deleteRecursively()
        }
    }
}
