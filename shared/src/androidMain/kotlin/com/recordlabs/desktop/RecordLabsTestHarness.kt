package com.recordlabs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsMusicRepository

/** Test-only entry: the lyrics page for [track] (used by the Android app's Robolectric test). */
@Composable
fun RecordLabsLyricsTestHarness(track: Track, positionMs: Long) {
    val repository = remember { WindowsMusicRepository() }
    LyricsPage(
        track = track,
        repository = repository,
        isPlaying = true,
        positionMs = { positionMs },
        durationMs = 200_000L,
        animatedArtworkEnabled = true,
        onToggleAnimatedArtwork = {},
        onBack = {},
        onPlayPause = {},
        isLiked = false,
        onToggleLiked = {},
        onShare = {},
        queue = listOf(track),
        queueIndex = 0,
        onQueueTrack = {},
        onQueueMove = { _, _ -> },
        onQueueRemove = {},
        volume = 1f,
        onVolume = {},
        isListenTogetherGuest = false,
        onToggleMute = {},
        shuffleEnabled = false,
        onToggleShuffle = {},
        repeatMode = 0,
        onCycleRepeat = {},
        sleepTimerActive = false,
        onToggleSleepTimer = {},
        onStartRadio = {},
        playbackTempo = 1f,
        playbackPitch = 0,
        onPlaybackTuning = { _, _ -> },
        isFullscreen = false,
        onToggleFullscreen = {},
        playlists = emptyList(),
        onAddToPlaylist = {},
        onViewArtist = {},
        onViewAlbum = {},
        onDownload = {},
        onListenTogether = {},
        isQueueVisible = false,
        onQueueVisibilityChanged = {},
    )
}
