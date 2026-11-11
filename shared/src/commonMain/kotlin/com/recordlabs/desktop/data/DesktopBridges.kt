package com.recordlabs.desktop.data

import androidx.compose.ui.graphics.Color
import com.recordlabs.desktop.platform.File

/*
 * Desktop features the shared screens call, through small bridges with the app's own
 * File type: the desktop and Android hand them to their existing objects (unchanged);
 * iOS answers with what it has (no local music folders, its own downloads, ...).
 */

/** WindowsLocalMusicLibrary: the user's music folders on this computer. */
expect object LocalMusicBridge {
    fun folders(): List<File>
    fun addFolder(folder: File)
    fun removeFolder(folder: File)
    fun scan(): List<Track>
    fun parseLocalArtworkFile(url: String): File?
    fun resolveArtworkModel(track: Track): Any?
    fun cachedEmbeddedArtwork(file: File): String?
}

/** WindowsAudioCache: songs cached for playback and saved for offline listening. */
expect object AudioCacheBridge {
    fun isDownloaded(token: String): Boolean
    fun isCached(token: String): Boolean
    fun cacheBytes(): Long
    fun downloadBytes(): Long
    fun downloadCount(): Int
    fun trimCache(maxBytes: Long, keep: Set<String> = emptySet()): Long
    fun deleteAllDownloads(keep: Set<String> = emptySet()): Int
}

/** A restored backup (AppBackup.Restored). */
class RestoredBackup(val library: WindowsLibrarySnapshot?, val searchHistory: List<String>?, val settingsCount: Int)

/** AppBackup: Settings > Backup. */
expect object BackupBridge {
    fun defaultFileName(): String
    fun export(target: File, includeAccounts: Boolean)
    fun restore(source: File): RestoredBackup
}

/** WindowsOfflineArtworkCache, OfflineDownloads and PlayerColorExtractor. */
expect object OfflineBridge {
    fun downloadArtwork(track: Track): File?
    fun downloadedTracks(): List<Track>
    fun colorCacheBytes(): Long
    fun clearColorCaches()
    suspend fun extractAccentColor(artworkUrl: String?): Color
}
