package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.AppStorage
import com.recordlabs.desktop.platform.File
import com.recordlabs.desktop.platform.HttpRequest
import com.recordlabs.desktop.platform.PlatformHttp
import com.recordlabs.desktop.platform.appendBytes

/** iOS: AVPlayer streams the YouTube URL itself; downloads go to the app's offline folder. */
actual object MediaPlatform {
    private val offlineDir get() = File(AppStorage.directory, "offline/audio").also { it.mkdirs() }
    private fun safe(token: String) = token.replace(Regex("[^A-Za-z0-9_-]"), "_")
    private fun offlineFile(token: String) = File(offlineDir, "${safe(token)}.m4a")

    actual fun localFileUri(path: String): String? = File(path).takeIf { it.isFile }?.let { "file://" + it.path }
    actual fun cachedSongUri(token: String): String? = offlineFile(token).takeIf { it.isFile && it.length() > 0L }?.let { "file://" + it.path }
    actual fun prefetchLater(token: String, remoteUrl: String, delayMs: Long) = Unit
    actual fun preloadNext(trackId: String, remoteUrl: String) {
        (createPlaybackEngine() as? IosPlaybackEngine)?.preload(trackId, remoteUrl)
    }
    actual fun completePrefetchUri(token: String, remoteUrl: String): String? = null
    actual fun playerUrl(remoteUrl: String, userAgent: String): String {
        IosStreamHeaders.remember(remoteUrl, userAgent)
        return remoteUrl
    }
    actual fun canStartStream(remoteUrl: String, userAgent: String): Boolean = runCatching {
        // The stream's index doubles as the check: one request finds that the stream
        // answers and gives the pieces for its HLS playlist (see StreamIndex).
        val format = StreamIndex.formatOf(remoteUrl)
        val range = if (format != null) "bytes=${format.indexStart}-${format.indexEnd}" else "bytes=0-1023"
        val response = PlatformHttp.execute(
            HttpRequest(remoteUrl, "GET", mapOf("User-Agent" to userAgent, "Range" to range), timeoutMs = 8_000L),
        )
        val ok = response.code == 200 || response.code == 206
        if (ok && format != null && response.code == 206) {
            StreamIndex.playlist(remoteUrl, format.initEnd, format.indexStart, response.body)
                ?.let { StreamIndex.putPlaylist(remoteUrl, it) }
        }
        ok
    }.getOrDefault(false)
    actual fun newPipeAudioStream(videoId: String): String? = null
    actual fun newPipeRelatedTracks(videoId: String): List<Track> = emptyList()
    actual fun ytDlpAudioStream(videoId: String): String? = null
    actual fun recordTiming(videoId: String, reason: String, source: String, elapsedMs: Long) = Unit

    actual fun isDownloaded(token: String): Boolean = offlineFile(token).let { it.isFile && it.length() > 0L }
    actual fun promoteCachedToOffline(token: String): Boolean = false
    actual fun downloadOffline(track: Track, remoteUrl: String, userAgent: String?) {
        val target = offlineFile(track.id)
        val temp = File(target.parentFile, target.name + ".part")
        val headers = userAgent?.let { mapOf("User-Agent" to it) } ?: emptyMap()
        val chunk = 1_048_576L
        var at = 0L
        temp.writeBytes(ByteArray(0))
        while (true) {
            val response = PlatformHttp.execute(
                HttpRequest(remoteUrl, "GET", headers + ("Range" to "bytes=$at-${at + chunk - 1}"), timeoutMs = 30_000L),
            )
            if (response.code != 206 && response.code != 200) error("Download failed (${response.code})")
            if (response.body.isEmpty()) break
            temp.appendBytes(response.body)
            at += response.body.size
            val total = response.header("content-range")?.substringAfter('/')?.toLongOrNull()
            if (response.code == 200 || (total != null && at >= total)) break
        }
        if (!temp.renameTo(target)) error("Could not save the song")
        markSaved(track)
    }
    actual fun markSaved(track: Track) = Unit
    actual fun downloadOfflineArtwork(track: Track) = Unit

    actual suspend fun deobfuscateStreamUrl(signatureCipher: String): String? = null
    actual suspend fun transformNParamInUrl(url: String): String = url

    actual val alignmentSampleRate: Int get() = 8_000
    actual fun align(song: ShortArray, video: ShortArray): List<CounterpartSegment>? = null
    actual fun decodeWebmOpus(data: ByteArray): ShortArray = ShortArray(0)

    /**
     * iPhone: AVPlayer reads the picture stream straight from YouTube (with its User-Agent),
     * as HLS pieces when its index is in [head] (as the songs: see [StreamIndex]). Given the
     * whole file, AVPlayer read on through it at YouTube's slow pace before it showed
     * anything: the picture took 8 s or more, or never came.
     */
    actual fun openMusicVideoStream(
        videoId: String, remoteUrl: String, userAgent: String, contentLength: Long, head: ByteArray,
        width: Int, height: Int, quality: Int,
    ): Any? = IosMusicVideoStream(remoteUrl, userAgent, videoIndexPlaylist(remoteUrl, head))
    actual fun musicVideoStreamUrl(stream: Any): String {
        val video = stream as IosMusicVideoStream
        IosStreamHeaders.remember(video.url, video.userAgent)
        video.playlist?.let { IosStreamHeaders.rememberPlaylist(video.url, it) }
        return video.url
    }

    /** The HLS playlist of a fragmented MP4 from its start ([head]: header boxes and the sidx index). */
    private fun videoIndexPlaylist(url: String, head: ByteArray): String? = runCatching {
        var at = 0
        while (at + 8 <= head.size) {
            val size = ((head[at].toLong() and 0xff) shl 24) or ((head[at + 1].toLong() and 0xff) shl 16) or
                ((head[at + 2].toLong() and 0xff) shl 8) or (head[at + 3].toLong() and 0xff)
            val type = head.decodeToString(at + 4, at + 8)
            if (type == "sidx") {
                // Everything before the index is the header (ftyp, moov).
                return@runCatching StreamIndex.playlist(url, (at - 1).toLong(), at.toLong(), head.copyOfRange(at, head.size))
            }
            if (size < 8L || size > Int.MAX_VALUE) return@runCatching null
            at += size.toInt()
        }
        null
    }.getOrNull()
}

/** A music video's picture stream on the iPhone: its address, the User-Agent it was made for, its HLS pieces. */
class IosMusicVideoStream(val url: String, val userAgent: String, val playlist: String? = null)

/** The User-Agent each stream URL was made for (AVPlayer sends it). */
object IosStreamHeaders {
    private val agents = HashMap<String, String>()
    fun remember(url: String, userAgent: String) = com.recordlabs.desktop.platform.locked(agents) {
        if (agents.size > 64) agents.clear()
        agents[url] = userAgent
    }
    fun userAgentFor(url: String): String? = com.recordlabs.desktop.platform.locked(agents) { agents[url] }

    /** Music videos' picture streams as HLS pieces (see openMusicVideoStream). */
    private val playlists = HashMap<String, String>()
    fun rememberPlaylist(url: String, playlist: String) = com.recordlabs.desktop.platform.locked(playlists) {
        if (playlists.size > 32) playlists.clear()
        playlists[url] = playlist
    }
    fun playlistFor(url: String): String? = com.recordlabs.desktop.platform.locked(playlists) { playlists[url] }
}
