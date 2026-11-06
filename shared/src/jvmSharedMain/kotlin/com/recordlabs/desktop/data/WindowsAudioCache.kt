package com.recordlabs.desktop.data

import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Proactive Audio Pre-fetching & Disk Cache System.
 * Pre-buffers audio tracks into %LOCALAPPDATA%\RecordLabs\cache\ so local HTTP range requests
 * hit local disk bytes with 0ms latency, eliminating playback rebuffering stalls.
 */
object WindowsAudioCache {
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131 Safari/537.36"
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val cacheDir = File(WindowsStorage.directory, "cache").apply { mkdirs() }
    private val offlineDir = File(WindowsStorage.directory, "offline/audio").apply { mkdirs() }
    private val activePrefetches = ConcurrentHashMap<String, PrefetchTask>()
    /** Songs waiting to be copied into downloads: the cache trim keeps their files. */
    private val pendingDownloads = ConcurrentHashMap.newKeySet<String>()
    /** Bumped by "Remove all downloads", so a download still waiting is not written back afterwards. */
    private val downloadsGeneration = java.util.concurrent.atomic.AtomicInteger()
    private val executor = Executors.newCachedThreadPool { runnable ->
        Thread(runnable, "record-labs-audio-prefetch").apply { isDaemon = true }
    }

    class PrefetchTask(val file: File, val remoteUrl: String, completedFile: Boolean = false) {
        @Volatile var totalLength: Long = -1L
        @Volatile var isComplete: Boolean = false
        @Volatile var errorMsg: String? = null

        init {
            if (completedFile) {
                totalLength = file.length()
                isComplete = true
            } else {
                // Extract clen parameter from YouTube URL if present
                val urlClen = remoteUrl.substringAfter("clen=", "").substringBefore("&").toLongOrNull()
                if (urlClen != null && urlClen > 0) {
                    totalLength = urlClen
                }

                executor.submit {
                try {
                    val raf = RandomAccessFile(file, "rw")
                    var currentLength = raf.length()

                    // Loop with Range headers to seamlessly resume download if YouTube CDN drops connection after burst
                    while (totalLength <= 0 || currentLength < totalLength) {
                        val request = Request.Builder()
                            .url(remoteUrl)
                            .header("User-Agent", USER_AGENT)
                            .header("Range", "bytes=$currentLength-")
                            .build()

                        val response = client.newCall(request).execute()
                        if (!response.isSuccessful && response.code != 416) {
                            if (currentLength > 0) {
                                isComplete = true
                            } else {
                                errorMsg = "HTTP ${response.code}"
                            }
                            response.close()
                            break
                        }

                        if (totalLength <= 0) {
                            val clen = response.header("Content-Length")
                                ?: response.header("content-length")
                                ?: response.header("content-range")?.substringAfter("/")
                            val parsedLen = clen?.trim()?.toLongOrNull() ?: -1L
                            if (parsedLen > 0) {
                                totalLength = if (response.code == 206) currentLength + parsedLen else parsedLen
                            }
                        }

                        val input = response.body?.byteStream()
                        if (input == null) {
                            response.close()
                            break
                        }

                        var bytesReadThisChunk = 0
                        val buffer = ByteArray(65536)
                        var read: Int
                        try {
                            while (input.read(buffer).also { read = it } != -1) {
                                synchronized(raf) {
                                    raf.seek(raf.length())
                                    raf.write(buffer, 0, read)
                                }
                                bytesReadThisChunk += read
                            }
                        } catch (e: Exception) {
                            // Connection reset by CDN - loop will resume withRange bytes=raf.length()-
                        } finally {
                            response.close()
                        }

                        currentLength = raf.length()
                        if (bytesReadThisChunk == 0) {
                            isComplete = true
                            break
                        }
                        if (totalLength > 0 && currentLength >= totalLength) {
                            isComplete = true
                            break
                        }
                    }
                    raf.close()
                    isComplete = true
                } catch (e: Exception) {
                    errorMsg = e.message
                }
                }
            }
        }

        fun awaitLength(): Long {
            if (totalLength > 0) return totalLength
            val deadline = System.currentTimeMillis() + 3000L
            while (totalLength <= 0 && errorMsg == null && !isComplete && System.currentTimeMillis() < deadline) {
                Thread.sleep(30L)
            }
            return totalLength
        }
    }

    fun getOrPrefetch(token: String, remoteUrl: String): PrefetchTask {
        val safeToken = token.replace(Regex("[^a-zA-Z0-9_-]"), "")
        return activePrefetches.computeIfAbsent(safeToken) {
            val offlineFile = File(offlineDir, "$safeToken.m4a")
            if (offlineFile.isFile && offlineFile.length() > 0L) {
                return@computeIfAbsent PrefetchTask(offlineFile, remoteUrl, completedFile = true)
            }
            val cacheFile = File(cacheDir, "$safeToken.m4a")
            PrefetchTask(cacheFile, remoteUrl)
        }
    }

    private val delayedStarter = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "record-labs-audio-prefetch-delay").apply { isDaemon = true }
    }

    /**
     * Starts the same background disk-cache download as [getOrPrefetch], but
     * only after [delayMs], so the first audio bytes streamed through the proxy
     * get the full connection. A task that already exists is left untouched.
     */
    fun prefetchLater(token: String, remoteUrl: String, delayMs: Long) {
        val safeToken = token.replace(Regex("[^a-zA-Z0-9_-]"), "")
        if (activePrefetches.containsKey(safeToken)) return
        runCatching {
            delayedStarter.schedule({ runCatching { getOrPrefetch(token, remoteUrl) } }, delayMs, TimeUnit.MILLISECONDS)
        }.onFailure { runCatching { getOrPrefetch(token, remoteUrl) } }
    }

    fun playableLocalFile(token: String): File? {
        val safeToken = token.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val offline = File(offlineDir, "$safeToken.m4a")
        if (offline.isFile && offline.length() > 0L) return offline
        return activePrefetches[safeToken]
            ?.takeIf { it.isComplete && it.errorMsg == null && it.file.length() > 0L }
            ?.file
    }

    fun downloadOffline(token: String, remoteUrl: String): PrefetchTask {
        val safeToken = token.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val task = getOrPrefetch(safeToken, remoteUrl)
        val target = File(offlineDir, "$safeToken.m4a")
        if (task.file.absoluteFile == target.absoluteFile) return task
        val generation = downloadsGeneration.get()
        pendingDownloads += safeToken
        executor.submit {
            try {
                val deadline = System.currentTimeMillis() + 15 * 60_000L
                while (!task.isComplete && task.errorMsg == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(120L)
                }
                if (task.isComplete && task.errorMsg == null && task.file.length() > 0L && generation == downloadsGeneration.get()) {
                    Files.copy(task.file.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    activePrefetches[safeToken] = PrefetchTask(target, remoteUrl, completedFile = true)
                }
            } finally {
                pendingDownloads -= safeToken
            }
        }
        return task
    }

    fun promoteCachedToOffline(token: String): Boolean {
        val safeToken = token.replace(Regex("[^a-zA-Z0-9_-]"), "")
        val source = activePrefetches[safeToken]
            ?.takeIf { it.isComplete && it.errorMsg == null && it.file.length() > 0L }
            ?.file ?: return false
        val target = File(offlineDir, "$safeToken.m4a")
        if (source.absoluteFile != target.absoluteFile) {
            Files.copy(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
            activePrefetches[safeToken] = PrefetchTask(target, source.toURI().toString(), completedFile = true)
        }
        return true
    }

    fun isDownloaded(token: String): Boolean = playableLocalFile(token)?.parentFile == offlineDir

    /** Where a saved song's file goes. */
    fun offlineFileFor(token: String): File = File(offlineDir, "${safe(token)}.m4a")

    /** A song just saved by [OfflineDownloads]: play it from its file from now on. */
    fun registerOffline(token: String, file: File, remoteUrl: String) {
        activePrefetches[safe(token)] = PrefetchTask(file, remoteUrl, completedFile = true)
        runCatching { AppDataEvents.storageGeneration++ }
    }

    /** Audio kept in the temporary playback cache (not a saved download). */
    fun isCached(token: String): Boolean {
        val safeToken = token.replace(Regex("[^a-zA-Z0-9_-]"), "")
        if (safeToken.isBlank() || isDownloaded(token)) return false
        activePrefetches[safeToken]?.let { task ->
            return task.isComplete && task.errorMsg == null && task.file.length() > 0L && task.file.parentFile == cacheDir
        }
        // From an earlier session: a file this small is an interrupted download.
        return File(cacheDir, "$safeToken.m4a").let { it.isFile && it.length() > 256 * 1024L }
    }

    private fun safe(token: String) = token.replace(Regex("[^a-zA-Z0-9_-]"), "")

    fun cacheBytes(): Long = cacheDir.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
    fun downloadBytes(): Long = offlineDir.listFiles()?.filter(File::isFile)?.sumOf(File::length) ?: 0L
    fun downloadCount(): Int = offlineDir.listFiles()?.count { it.isFile && it.length() > 0L } ?: 0

    /**
     * Keeps the temporary playback cache under [maxBytes], removing the oldest
     * songs first. Songs still downloading and the [keep] ones (the song playing
     * and the next one) are never removed. Returns the bytes freed.
     */
    fun trimCache(maxBytes: Long, keep: Set<String> = emptySet()): Long {
        val kept = keep.map(::safe).toSet()
        val files = cacheDir.listFiles()?.filter(File::isFile)?.sortedBy(File::lastModified) ?: return 0L
        var total = files.sumOf(File::length)
        var freed = 0L
        for (file in files) {
            if (total <= maxBytes) break
            val token = file.nameWithoutExtension
            if (token in kept || token in pendingDownloads) continue
            val task = activePrefetches[token]
            if (task != null && task.file.absoluteFile == file.absoluteFile && !task.isComplete && task.errorMsg == null) continue
            val length = file.length()
            if (file.delete()) {
                activePrefetches.remove(token, task)
                total -= length
                freed += length
            }
        }
        return freed
    }

    /** Removes every saved download except the [keep] songs (the one playing). Returns how many were removed. */
    fun deleteAllDownloads(keep: Set<String> = emptySet()): Int {
        val kept = keep.map(::safe).toSet()
        downloadsGeneration.incrementAndGet()
        var removed = 0
        offlineDir.listFiles()?.filter(File::isFile)?.forEach { file ->
            val token = file.nameWithoutExtension
            if (token in kept) return@forEach
            if (file.delete()) {
                removed++
                activePrefetches[token]?.takeIf { it.file.absoluteFile == file.absoluteFile }?.let { activePrefetches.remove(token, it) }
                runCatching { WindowsOfflineArtworkCache.file(token).delete() }
            }
        }
        return removed
    }

    fun clearOldCache() {
        runCatching {
            val files = cacheDir.listFiles() ?: return@runCatching
            val cutoff = System.currentTimeMillis() - 86400000L * 3 // 3 days
            files.filter { it.lastModified() < cutoff }.forEach { it.delete() }
        }
    }
}
