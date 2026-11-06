package com.recordlabs.desktop.data

import androidx.compose.ui.graphics.Color
import com.recordlabs.desktop.platform.AppStorage
import com.recordlabs.desktop.platform.File

/** iOS: no music folders of its own (Files app access comes later). */
actual object LocalMusicBridge {
    actual fun folders(): List<File> = emptyList()
    actual fun addFolder(folder: File) = Unit
    actual fun removeFolder(folder: File) = Unit
    actual fun scan(): List<Track> = emptyList()
    actual fun parseLocalArtworkFile(url: String): File? = null
    actual fun resolveArtworkModel(track: Track): Any? = track.artworkUrl
    actual fun cachedEmbeddedArtwork(file: File): String? = null
}

/** iOS: songs saved for offline listening live in the app's offline folder (MediaPlatform). */
actual object AudioCacheBridge {
    private val offlineDir get() = File(AppStorage.directory, "offline/audio")
    private fun files() = offlineDir.listFiles()?.filter { it.isFile && it.length() > 0L && !it.name.endsWith(".part") }.orEmpty()
    actual fun isDownloaded(token: String): Boolean = MediaPlatform.isDownloaded(token)
    actual fun isCached(token: String): Boolean = isDownloaded(token)
    actual fun cacheBytes(): Long = 0L
    actual fun downloadBytes(): Long = files().sumOf { it.length() }
    actual fun downloadCount(): Int = files().size
    actual fun trimCache(maxBytes: Long, keep: Set<String>): Long = 0L
    actual fun deleteAllDownloads(keep: Set<String>): Int {
        var removed = 0
        files().forEach { file ->
            if (file.name.substringBeforeLast('.') !in keep && file.delete()) removed++
        }
        return removed
    }
}

actual object BackupBridge {
    actual fun defaultFileName(): String = "record-labs-backup.zip"
    actual fun export(target: File, includeAccounts: Boolean) { error("Backups are not available on iPhone yet") }
    actual fun restore(source: File): RestoredBackup = error("Backups are not available on iPhone yet")
}

actual object OfflineBridge {
    actual fun downloadArtwork(track: Track): File? = null
    actual fun downloadedTracks(): List<Track> = emptyList()
    actual fun colorCacheBytes(): Long = 0L
    actual fun clearColorCaches() = Unit
    actual suspend fun extractAccentColor(artworkUrl: String?): Color = Color(0xFF8A8A8E)
}
