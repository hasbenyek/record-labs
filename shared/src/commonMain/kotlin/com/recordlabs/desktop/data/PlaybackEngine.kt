package com.recordlabs.desktop.data

import kotlinx.coroutines.flow.StateFlow

/** What the player reports a few times a second. */
data class WindowsPlaybackSnapshot(
    val trackId: String? = null,
    val isPlaying: Boolean = false,
    val isReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val error: String? = null,
)

/**
 * The audio player the app drives. Desktop: [WindowsMediaPlayer] (Windows
 * native helper or VLC on Linux). Android: ExoPlayer (Media3).
 */
interface PlaybackEngine {
    val isPlaying: Boolean
    val isReady: Boolean
    val currentTrack: Track?
    val positionMs: Long
    val durationMs: Long
    val lastError: String?
    var onPlaybackEnded: (() -> Unit)?
    /** Media keys / system media controls ("play_pause", "next", "previous", ...). */
    var onMediaCommand: ((String) -> Unit)?
    var onBluetoothConnectPacket: ((String) -> Unit)?
    val state: StateFlow<WindowsPlaybackSnapshot>

    fun play(track: Track, url: String, startPositionMs: Long = 0L)
    fun pause()
    fun resume()
    fun seekTo(positionMs: Long)
    fun presetState(volume: Float, tempo: Float, pitch: Int)
    fun setAudioTuning(tempo: Float, pitch: Int)
    fun setVolume(volume: Float)
    /** Record Labs Connect over Bluetooth (desktop helper); no-op where unsupported. */
    fun sendBluetoothConnectPacket(payload: String)
    fun stop()
    fun release()
}

/** The platform's player. */
expect fun createPlaybackEngine(): PlaybackEngine
