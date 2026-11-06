package com.recordlabs.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** Permanent full-resolution artwork cache used by explicit offline downloads. */
object WindowsOfflineArtworkCache {
    private val directory = File(WindowsStorage.directory, "offline/artwork").apply { mkdirs() }
    private val client = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    fun file(trackId: String): File = File(directory, "${safe(trackId)}.image")

    fun download(track: Track): File? {
        val url = track.artworkUrl?.takeIf(String::isNotBlank) ?: return null
        val target = file(track.id)
        if (target.isFile && target.length() > 0L) return target
        return runCatching {
            val highResolutionUrl = url
                .replace(Regex("=w\\d+-h\\d+.*$"), "=w1200-h1200-l90-rj")
                .replace(Regex("-w\\d+-h\\d+.*$"), "-w1200-h1200-l90-rj")
            client.newCall(Request.Builder().url(highResolutionUrl).build()).execute().use { response ->
                check(response.isSuccessful) { "Artwork HTTP ${response.code}" }
                val bytes = response.body?.bytes() ?: error("Artwork kosong")
                check(bytes.size >= 8_192) { "Artwork belum full resolution" }
                target.writeBytes(bytes)
            }
            target
        }.getOrNull()
    }

    private fun safe(value: String) = value.replace(Regex("[^a-zA-Z0-9_-]"), "")
}
