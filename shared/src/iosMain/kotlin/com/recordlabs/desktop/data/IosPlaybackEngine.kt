package com.recordlabs.desktop.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * iOS player (in progress): keeps the state the shared app expects. The AVPlayer
 * playback (background audio, lock screen) comes with the playback step of the port.
 */
private class IosPlaybackEngine : PlaybackEngine {
    override var isPlaying: Boolean = false
        private set
    override var isReady: Boolean = false
        private set
    override var currentTrack: Track? = null
        private set
    override var positionMs: Long = 0L
        private set
    override var durationMs: Long = 0L
        private set
    override var lastError: String? = null
        private set
    override var onPlaybackEnded: (() -> Unit)? = null
    override var onMediaCommand: ((String) -> Unit)? = null
    override var onBluetoothConnectPacket: ((String) -> Unit)? = null

    private val _state = MutableStateFlow(WindowsPlaybackSnapshot())
    override val state: StateFlow<WindowsPlaybackSnapshot> = _state.asStateFlow()

    private fun publish() {
        _state.value = WindowsPlaybackSnapshot(currentTrack?.id, isPlaying, isReady, positionMs, durationMs, lastError)
    }

    override fun play(track: Track, url: String, startPositionMs: Long) {
        currentTrack = track
        positionMs = startPositionMs
        durationMs = track.durationMs
        lastError = "Playback is not available on iOS yet"
        publish()
    }

    override fun pause() { isPlaying = false; publish() }
    override fun resume() { publish() }
    override fun seekTo(positionMs: Long) { this.positionMs = positionMs; publish() }
    override fun presetState(volume: Float, tempo: Float, pitch: Int) = Unit
    override fun setAudioTuning(tempo: Float, pitch: Int) = Unit
    override fun setVolume(volume: Float) = Unit
    override fun sendBluetoothConnectPacket(payload: String) = Unit
    override fun stop() { isPlaying = false; currentTrack = null; publish() }
    override fun release() = Unit
}

actual fun createPlaybackEngine(): PlaybackEngine = IosPlaybackEngine()
