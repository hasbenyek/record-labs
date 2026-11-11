package com.recordlabs.desktop.data

/**
 * What WindowsMusicRepository needs from the platform's own media code: the
 * desktop's audio cache, local proxy, NewPipe / yt-dlp / player.js fallbacks,
 * downloads, timing log and music video stream reader (Android uses the same; iOS
 * plays YouTube streams itself and leaves most of these out).
 */
expect object MediaPlatform {
    /** file:// URI of a song file on this device, when it exists. */
    fun localFileUri(path: String): String?
    /** file:// URI of the song when it is in the playback cache or downloaded. */
    fun cachedSongUri(token: String): String?
    fun prefetchLater(token: String, remoteUrl: String, delayMs: Long)
    /** iPhone: starts loading the next song's stream in the player, so it starts at once (no-op elsewhere). */
    fun preloadNext(trackId: String, remoteUrl: String)
    /** Starts (or joins) the download into the cache; the file's URI when it is already complete. */
    fun completePrefetchUri(token: String, remoteUrl: String): String?
    /** URL the player opens for [remoteUrl] (the desktop's local proxy). */
    fun playerUrl(remoteUrl: String, userAgent: String): String
    fun canStartStream(remoteUrl: String, userAgent: String): Boolean
    fun newPipeAudioStream(videoId: String): String?
    fun newPipeRelatedTracks(videoId: String): List<Track>
    fun ytDlpAudioStream(videoId: String): String?
    fun recordTiming(videoId: String, reason: String, source: String, elapsedMs: Long)

    fun isDownloaded(token: String): Boolean
    fun promoteCachedToOffline(token: String): Boolean
    /** Downloads the whole song for offline listening (throws when it could not). */
    fun downloadOffline(track: Track, remoteUrl: String, userAgent: String?)
    fun markSaved(track: Track)
    fun downloadOfflineArtwork(track: Track)

    suspend fun deobfuscateStreamUrl(signatureCipher: String): String?
    suspend fun transformNParamInUrl(url: String): String

    val alignmentSampleRate: Int
    fun align(song: ShortArray, video: ShortArray): List<CounterpartSegment>?
    fun decodeWebmOpus(data: ByteArray): ShortArray

    /** Opens a music video's picture stream from its first bytes (null when not supported). */
    fun openMusicVideoStream(
        videoId: String, remoteUrl: String, userAgent: String, contentLength: Long, head: ByteArray,
        width: Int, height: Int, quality: Int,
    ): Any?
    /** The URL the video player opens for a stream from [openMusicVideoStream]. */
    fun musicVideoStreamUrl(stream: Any): String
}
