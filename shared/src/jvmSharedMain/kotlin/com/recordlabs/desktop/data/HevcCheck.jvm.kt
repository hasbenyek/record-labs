package com.recordlabs.desktop.data

import java.io.File

internal actual fun desktopFfmpegHasHevc(): Boolean {
    return runCatching {
        val ffmpeg = sequenceOf("/usr/bin/ffmpeg", "/usr/local/bin/ffmpeg", "ffmpeg")
            .firstOrNull { File(it).canExecute() }
            ?: return false
        val process = ProcessBuilder(ffmpeg, "-decoders").redirectErrorStream(true).start()
        val hasHevc = process.inputStream.bufferedReader().useLines { lines ->
            lines.any { line ->
                line.contains("hevc", ignoreCase = true) && !line.contains("amf") && !line.contains("qsv")
            }
        }
        process.waitFor()
        hasHevc
    }.getOrElse { false }
}
