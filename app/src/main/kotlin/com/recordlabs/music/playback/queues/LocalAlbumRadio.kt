/**
 * Record Labs Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */

package com.recordlabs.music.playback.queues

import androidx.media3.common.MediaItem
import com.recordlabs.innertube.YouTube
import com.recordlabs.innertube.models.WatchEndpoint
import com.recordlabs.music.db.entities.AlbumWithSongs
import com.recordlabs.music.extensions.toMediaItem
import com.recordlabs.music.models.MediaMetadata
import com.recordlabs.music.models.toMediaMetadata
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext

class LocalAlbumRadio(
    private val albumWithSongs: AlbumWithSongs,
    private val startIndex: Int = 0,
) : Queue {
    // Identifies which song was actually tapped to start this queue (song at startIndex), the
    // same way YouTubeQueue.radio() sets preloadItem to the seed song. Needed so PlayerConnection
    // .playQueue() can detect "guest tapped a specific song" even for album-row taps, which build
    // a LocalAlbumRadio rather than a ListQueue.
    override val preloadItem: MediaMetadata? = albumWithSongs.songs.getOrNull(startIndex)?.toMediaMetadata()

    private lateinit var playlistId: String
    private val endpoint: WatchEndpoint
        get() = WatchEndpoint(
            playlistId = playlistId
        )

    private var continuation: String? = null
    private var firstTimeLoaded: Boolean = false

    override suspend fun getInitialStatus(): Queue.Status = withContext(IO) {
        Queue.Status(
            title = albumWithSongs.album.title,
            items = albumWithSongs.songs.map { it.toMediaItem() },
            mediaItemIndex = startIndex
        )
    }

    override fun hasNextPage(): Boolean = !firstTimeLoaded || continuation != null

    override suspend fun nextPage(): List<MediaItem> = withContext(IO) {
        if (!firstTimeLoaded) {
            playlistId = YouTube.album(albumWithSongs.album.id).getOrThrow().album.playlistId
            val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
            continuation = nextResult.continuation
            firstTimeLoaded = true
            return@withContext nextResult.items.subList(
                albumWithSongs.songs.size,
                nextResult.items.size
            ).map { it.toMediaItem() }
        }
        val nextResult = YouTube.next(endpoint, continuation).getOrThrow()
        continuation = nextResult.continuation
        nextResult.items.map { it.toMediaItem() }
    }
}
