package com.recordlabs.desktop.data

import com.recordlabs.desktop.data.cipher.WindowsCipherDeobfuscator
import java.io.File

/** The desktop and Android: the existing media code, unchanged. */
actual object MediaPlatform {
    actual fun localFileUri(path: String): String? = File(path).takeIf { it.isFile }?.toURI()?.toASCIIString()
    actual fun cachedSongUri(token: String): String? = WindowsAudioCache.playableLocalFile(token)?.toURI()?.toString()
    actual fun prefetchLater(token: String, remoteUrl: String, delayMs: Long) = WindowsAudioCache.prefetchLater(token, remoteUrl, delayMs)
    actual fun preloadNext(trackId: String, remoteUrl: String) = Unit
    actual fun completePrefetchUri(token: String, remoteUrl: String): String? {
        val prefetch = WindowsAudioCache.getOrPrefetch(token, remoteUrl)
        return if (prefetch.isComplete && prefetch.errorMsg == null && prefetch.file.length() > 0L) prefetch.file.toURI().toString() else null
    }
    actual fun playerUrl(remoteUrl: String, userAgent: String): String = WindowsAudioProxy.register(remoteUrl, userAgent)
    actual fun canStartStream(remoteUrl: String, userAgent: String): Boolean = WindowsAudioProxy.canStartStream(remoteUrl, userAgent)
    actual fun newPipeAudioStream(videoId: String): String? = WindowsNewPipeExtractor.audioStream(videoId)
    actual fun newPipeRelatedTracks(videoId: String): List<Track> = WindowsNewPipeExtractor.relatedTracks(videoId)
    actual fun ytDlpAudioStream(videoId: String): String? = WindowsYtDlpExtractor.audioStream(videoId)
    actual fun recordTiming(videoId: String, reason: String, source: String, elapsedMs: Long) =
        PlaybackTimingLog.record(videoId, reason, source, elapsedMs)

    actual fun isDownloaded(token: String): Boolean = WindowsAudioCache.isDownloaded(token)
    actual fun promoteCachedToOffline(token: String): Boolean = WindowsAudioCache.promoteCachedToOffline(token)
    actual fun downloadOffline(track: Track, remoteUrl: String, userAgent: String?) {
        val target = WindowsAudioCache.offlineFileFor(track.id)
        OfflineDownloads.download(track, remoteUrl, userAgent, target)
        WindowsAudioCache.registerOffline(track.id, target, remoteUrl)
    }
    actual fun markSaved(track: Track) { OfflineDownloads.markSaved(track) }
    actual fun downloadOfflineArtwork(track: Track) { WindowsOfflineArtworkCache.download(track) }

    actual suspend fun deobfuscateStreamUrl(signatureCipher: String): String? = WindowsCipherDeobfuscator.deobfuscateStreamUrl(signatureCipher)
    actual suspend fun transformNParamInUrl(url: String): String = WindowsCipherDeobfuscator.transformNParamInUrl(url)

    actual val alignmentSampleRate: Int get() = AudioAlignment.SAMPLE_RATE
    actual fun align(song: ShortArray, video: ShortArray): List<CounterpartSegment>? = AudioAlignment.align(song, video)
    actual fun decodeWebmOpus(data: ByteArray): ShortArray = WebmOpusDecoder.decode(data)

    actual fun openMusicVideoStream(
        videoId: String, remoteUrl: String, userAgent: String, contentLength: Long, head: ByteArray,
        width: Int, height: Int, quality: Int,
    ): Any? = MusicVideoStream.parse(videoId, remoteUrl, userAgent, contentLength, head)?.also {
        it.pictureWidth = width
        it.pictureHeight = height
        it.pictureQuality = quality
    }
    actual fun musicVideoStreamUrl(stream: Any): String = (stream as MusicVideoStream).urlFrom(0)
}
