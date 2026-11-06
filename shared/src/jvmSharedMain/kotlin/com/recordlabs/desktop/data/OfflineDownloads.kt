package com.recordlabs.desktop.data

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Saves songs for offline listening: the whole audio file, fetched in ranges
 * with the User-Agent its stream URL was made for (YouTube rejects other ones,
 * and slows down long single requests), into the downloads folder. The songs
 * are also listed here, so Library > Downloaded shows every saved song (not
 * only the ones that are also liked, in a playlist or in the history).
 */
object OfflineDownloads {
    private const val CHUNK = 1L shl 20 // 1 MB
    private val json = Json { ignoreUnknownKeys = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
    private val indexFile get() = File(WindowsStorage.directory, "offline/downloads.json")
    private val lock = Any()

    /** Songs saved for offline listening (newest first), as remembered. */
    fun tracks(): List<Track> = synchronized(lock) {
        runCatching { json.decodeFromString(ListSerializer(Track.serializer()), indexFile.readText()) }.getOrDefault(emptyList())
    }

    /** Lists [track] among the saved songs (its file is already in the downloads folder). */
    fun markSaved(track: Track) = remember(track)

    private fun remember(track: Track) = synchronized(lock) {
        val list = listOf(track) + tracks().filterNot { it.id == track.id }
        runCatching {
            indexFile.parentFile?.mkdirs()
            indexFile.writeText(json.encodeToString(ListSerializer(Track.serializer()), list))
        }
    }

    /**
     * Downloads [remoteUrl] to [target] (blocking). [userAgent] null = the HTTP
     * client's own (what the Android playback engine streams with).
     */
    fun download(track: Track, remoteUrl: String, userAgent: String?, target: File) {
        target.parentFile?.mkdirs()
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        RandomAccessFile(part, "rw").use { out ->
            var position = 0L
            var total = remoteUrl.substringAfter("clen=", "").substringBefore("&").toLongOrNull() ?: -1L
            var failures = 0
            while (total < 0 || position < total) {
                val end = position + CHUNK - 1
                val request = Request.Builder().url(remoteUrl)
                    .header("Range", "bytes=$position-$end")
                    .apply { if (userAgent != null) header("User-Agent", userAgent) }
                    .build()
                val ok = runCatching {
                    client.newCall(request).execute().use { response ->
                        if (response.code == 416 && total < 0) { total = position; return@use true }
                        if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                        if (total < 0) {
                            total = response.header("Content-Range")?.substringAfter('/')?.trim()?.toLongOrNull()
                                ?: if (response.code == 200) (response.body?.contentLength() ?: -1L) else -1L
                        }
                        val body = response.body ?: throw IOException("Empty answer")
                        var read = 0L
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.seek(position + read)
                                out.write(buffer, 0, n)
                                read += n
                            }
                        }
                        if (read == 0L) {
                            if (total < 0) total = position
                            true
                        } else {
                            position += read
                            // A server that ignores ranges sends the whole file at once.
                            if (response.code == 200) total = position
                            true
                        }
                    }
                }.getOrElse { error ->
                    if (++failures >= 4) throw error
                    Thread.sleep(600L * failures)
                    false
                }
                if (ok) failures = 0
            }
        }
        if (part.length() <= 0L) throw IOException("Nothing was downloaded")
        if (target.exists()) target.delete()
        if (!part.renameTo(target)) {
            part.copyTo(target, overwrite = true)
            part.delete()
        }
        remember(track)
    }
}
