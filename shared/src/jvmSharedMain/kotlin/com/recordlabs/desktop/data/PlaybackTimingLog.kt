package com.recordlabs.desktop.data

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Small diagnostic log of how long it took to find a playable stream and
 * which source won (VISIONOS, ANDROID_VR, NewPipe, yt-dlp, ...).
 *
 * Written to `<app data>\logs\playback-timing.log` so slow loads can be
 * compared before/after changes without a debugger. Never throws and never
 * blocks playback; the file is reset once it grows past 512 KB.
 */
object PlaybackTimingLog {
    private const val MAX_BYTES = 512L * 1024L
    private val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val lock = Any()

    private val file: File? by lazy {
        runCatching { File(WindowsStorage.directory, "logs").apply { mkdirs() }.resolve("playback-timing.log") }.getOrNull()
    }

    fun record(videoId: String, reason: String, source: String, elapsedMs: Long) {
        val line = "${LocalDateTime.now().format(formatter)} video=$videoId reason=$reason source=$source elapsedMs=$elapsedMs"
        println("[PlaybackTiming] $line")
        runCatching {
            val target = file ?: return
            synchronized(lock) {
                if (target.length() > MAX_BYTES) target.writeText("")
                target.appendText(line + System.lineSeparator())
            }
        }
    }
}
