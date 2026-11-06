package com.recordlabs.desktop.data

import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * Resolves current YouTube audio signatures with the bundled yt-dlp and Deno runtime.
 * Both executables ship inside the Record Labs installer; users do not need Python, Node, or Deno.
 */
object WindowsYtDlpExtractor {
    fun audioStream(videoId: String): String? = runCatching {
        val ytDlp = executable("yt-dlp.exe") ?: return null
        val deno = executable("deno.exe") ?: return null
        val process = ProcessBuilder(
            ytDlp.absolutePath,
            "--no-config",
            "--no-playlist",
            "--no-warnings",
            "--socket-timeout", "8",
            "--retries", "1",
            "--js-runtimes", "deno:${deno.absolutePath}",
            "-f", if (AppSettings.audioQuality.value == "low") "worstaudio[ext=webm]/worstaudio" else "bestaudio[ext=webm]/bestaudio",
            "--get-url",
            "https://www.youtube.com/watch?v=$videoId",
        )
            .directory(ytDlp.parentFile)
            .redirectErrorStream(true)
            .start()

        val lines = mutableListOf<String>()
        val outputThread = thread(isDaemon = true, name = "record-labs-ytdlp-output") {
            process.inputStream.bufferedReader().useLines { output ->
                output.forEach { line -> synchronized(lines) { lines += line } }
            }
        }
        if (!process.waitFor(15, TimeUnit.SECONDS)) {
            process.destroyForcibly()
            outputThread.join(500)
            return null
        }
        outputThread.join(500)
        if (process.exitValue() != 0) return null
        synchronized(lines) {
            lines.lastOrNull { it.startsWith("https://") || it.startsWith("http://") }
        }
    }.getOrNull()

    private fun executable(name: String): File? {
        val resourceDir = System.getProperty("compose.application.resources.dir")
        val candidates = buildList {
            if (!resourceDir.isNullOrBlank()) add(File(resourceDir, name))
            add(File("src/appResources/windows-x64/$name"))
            add(File("windows-recordlabs/src/appResources/windows-x64/$name"))
        }
        return candidates.map(File::getAbsoluteFile).firstOrNull(File::isFile)
    }
}
