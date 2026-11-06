package com.recordlabs.desktop.data

import androidx.compose.ui.graphics.Color
import com.recordlabs.desktop.platform.File
import com.recordlabs.desktop.platform.common

actual object LocalMusicBridge {
    actual fun folders(): List<File> = WindowsLocalMusicLibrary.folders().map { it.common() }
    actual fun addFolder(folder: File) = WindowsLocalMusicLibrary.addFolder(folder.javaFile)
    actual fun removeFolder(folder: File) = WindowsLocalMusicLibrary.removeFolder(folder.javaFile)
    actual fun scan(): List<Track> = WindowsLocalMusicLibrary.scan()
    actual fun parseLocalArtworkFile(url: String): File? = WindowsLocalMusicLibrary.parseLocalArtworkFile(url)?.common()
    actual fun resolveArtworkModel(track: Track): Any? = WindowsLocalMusicLibrary.resolveArtworkModel(track)
    actual fun cachedEmbeddedArtwork(file: File): String? = WindowsLocalMusicLibrary.cachedEmbeddedArtwork(file.javaFile)
}

actual object AudioCacheBridge {
    actual fun isDownloaded(token: String): Boolean = WindowsAudioCache.isDownloaded(token)
    actual fun isCached(token: String): Boolean = WindowsAudioCache.isCached(token)
    actual fun cacheBytes(): Long = WindowsAudioCache.cacheBytes()
    actual fun downloadBytes(): Long = WindowsAudioCache.downloadBytes()
    actual fun downloadCount(): Int = WindowsAudioCache.downloadCount()
    actual fun trimCache(maxBytes: Long, keep: Set<String>): Long = WindowsAudioCache.trimCache(maxBytes, keep)
    actual fun deleteAllDownloads(keep: Set<String>): Int = WindowsAudioCache.deleteAllDownloads(keep)
}

actual object BackupBridge {
    actual fun defaultFileName(): String = AppBackup.defaultFileName()
    actual fun export(target: File, includeAccounts: Boolean) = AppBackup.export(target.javaFile, includeAccounts)
    actual fun restore(source: File): RestoredBackup =
        AppBackup.restore(source.javaFile).let { RestoredBackup(it.library, it.searchHistory, it.settingsCount) }
}

actual object OfflineBridge {
    actual fun downloadArtwork(track: Track): File? = WindowsOfflineArtworkCache.download(track)?.common()
    actual fun downloadedTracks(): List<Track> = OfflineDownloads.tracks()
    actual fun colorCacheBytes(): Long = PlayerColorExtractor.diskCacheBytes()
    actual fun clearColorCaches() = PlayerColorExtractor.clearDiskCaches()
    actual suspend fun extractAccentColor(artworkUrl: String?): Color = PlayerColorExtractor.extractAccentColor(artworkUrl)
}
