@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop.data

import kotlinx.cinterop.CValue
import kotlinx.cinterop.useContents
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.AVAudioSessionInterruptionNotification
import platform.AVFAudio.AVAudioSessionInterruptionOptionKey
import platform.AVFAudio.AVAudioSessionInterruptionOptionShouldResume
import platform.AVFAudio.AVAudioSessionInterruptionTypeEnded
import platform.AVFAudio.AVAudioSessionInterruptionTypeKey
import platform.AVFAudio.setActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemFailedToPlayToEndTimeNotification
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerTimeControlStatusPlaying
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVAudioTimePitchAlgorithmSpectral
import platform.AVFoundation.audioTimePitchAlgorithm
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.rate
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.AVFoundation.volume
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSData
import platform.Foundation.NSNotification
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSNumber
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSThread
import platform.Foundation.NSTimer
import platform.Foundation.NSURL
import platform.Foundation.dataWithContentsOfURL
import platform.MediaPlayer.MPChangePlaybackPositionCommandEvent
import platform.MediaPlayer.MPMediaItemArtwork
import platform.MediaPlayer.MPMediaItemPropertyAlbumTitle
import platform.MediaPlayer.MPMediaItemPropertyArtist
import platform.MediaPlayer.MPMediaItemPropertyArtwork
import platform.MediaPlayer.MPMediaItemPropertyPlaybackDuration
import platform.MediaPlayer.MPMediaItemPropertyTitle
import platform.MediaPlayer.MPNowPlayingInfoCenter
import platform.MediaPlayer.MPNowPlayingInfoPropertyElapsedPlaybackTime
import platform.MediaPlayer.MPNowPlayingInfoPropertyPlaybackRate
import platform.MediaPlayer.MPRemoteCommandCenter
import platform.MediaPlayer.MPRemoteCommandEvent
import platform.MediaPlayer.MPRemoteCommandHandlerStatus
import platform.MediaPlayer.MPRemoteCommandHandlerStatusCommandFailed
import platform.MediaPlayer.MPRemoteCommandHandlerStatusSuccess
import platform.UIKit.UIApplication
import platform.UIKit.beginReceivingRemoteControlEvents
import platform.UIKit.UIImage
import platform.darwin.NSObjectProtocol
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_global_queue
import platform.darwin.dispatch_get_main_queue
import platform.darwin.DISPATCH_QUEUE_PRIORITY_DEFAULT
import kotlin.math.abs

/**
 * The iPhone's player: AVPlayer, playing in the background (audio session
 * "playback"), with the song on the lock screen / Control Center and its
 * buttons (play, pause, next, previous, scrubbing) sent to the app like the
 * Android notification's buttons.
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

    private val player = AVPlayer()
    private var volume = 1f
    private var tempo = 1f
    /** The app wants it playing (AVPlayer may still be buffering). */
    private var wantsPlay = false
    private var itemObservers = emptyList<NSObjectProtocol>()
    private var artworkFor: String? = null
    private var artwork: MPMediaItemArtwork? = null
    private var lastInfoPlaying: Boolean? = null
    private var lastInfoPositionMs = 0L

    init {
        val session = AVAudioSession.sharedInstance()
        session.setCategory(AVAudioSessionCategoryPlayback, null)
        session.setActive(true, null)
        UIApplication.sharedApplication.beginReceivingRemoteControlEvents()
        setUpRemoteCommands()
        // A call or another app's audio pauses the song; it continues afterwards when iOS says so.
        NSNotificationCenter.defaultCenter.addObserverForName(
            AVAudioSessionInterruptionNotification, null, NSOperationQueue.mainQueue,
        ) { note: NSNotification? ->
            val info = note?.userInfo
            val type = (info?.get(AVAudioSessionInterruptionTypeKey) as? NSNumber)?.unsignedLongValue
            if (type == AVAudioSessionInterruptionTypeEnded) {
                val options = (info[AVAudioSessionInterruptionOptionKey] as? NSNumber)?.unsignedLongValue ?: 0uL
                if (options and AVAudioSessionInterruptionOptionShouldResume != 0uL && wantsPlay) {
                    AVAudioSession.sharedInstance().setActive(true, null)
                    startPlayer()
                }
            }
            publish()
        }
        // Position updates a few times a second, like the Android and desktop players.
        NSTimer.scheduledTimerWithTimeInterval(0.25, repeats = true) { _ -> tick() }
    }

    private fun setUpRemoteCommands() {
        val center = MPRemoteCommandCenter.sharedCommandCenter()
        fun send(command: String): MPRemoteCommandHandlerStatus {
            val handler = onMediaCommand ?: return MPRemoteCommandHandlerStatusCommandFailed
            handler(command)
            return MPRemoteCommandHandlerStatusSuccess
        }
        center.playCommand.addTargetWithHandler { _: MPRemoteCommandEvent? -> send("play") }
        center.pauseCommand.addTargetWithHandler { _: MPRemoteCommandEvent? -> send("pause") }
        center.togglePlayPauseCommand.addTargetWithHandler { _: MPRemoteCommandEvent? -> send("play_pause") }
        center.nextTrackCommand.addTargetWithHandler { _: MPRemoteCommandEvent? -> send("next") }
        center.previousTrackCommand.addTargetWithHandler { _: MPRemoteCommandEvent? -> send("previous") }
        center.changePlaybackPositionCommand.addTargetWithHandler { event: MPRemoteCommandEvent? ->
            val seconds = (event as? MPChangePlaybackPositionCommandEvent)?.positionTime
                ?: return@addTargetWithHandler MPRemoteCommandHandlerStatusCommandFailed
            seekTo((seconds * 1000).toLong())
            MPRemoteCommandHandlerStatusSuccess
        }
    }

    private fun onMain(block: () -> Unit) {
        if (NSThread.isMainThread) block() else dispatch_async(dispatch_get_main_queue()) { block() }
    }

    private fun seconds(time: CValue<CMTime>): Double = CMTimeGetSeconds(time)

    private fun startPlayer() {
        player.play()
        if (abs(tempo - 1f) > 0.001f) player.rate = tempo
    }

    private fun tick() {
        val item = player.currentItem ?: return
        if (item.status == AVPlayerItemStatusFailed && lastError == null) {
            lastError = "Playback failed (${item.error?.localizedDescription ?: "unknown error"})"
            wantsPlay = false
        }
        publish()
    }

    private var loggedLengthFor: String? = null

    private fun publish() {
        val item = player.currentItem
        isPlaying = item != null && player.timeControlStatus == AVPlayerTimeControlStatusPlaying
        isReady = item?.status == AVPlayerItemStatusReadyToPlay
        val position = seconds(player.currentTime())
        if (!position.isNaN() && position >= 0.0) positionMs = (position * 1000).toLong()
        val length = item?.let { seconds(it.duration) }
        val playerLength = length?.takeIf { !it.isNaN() && !it.isInfinite() && it > 0.0 }?.let { (it * 1000).toLong() }
        val known = currentTrack?.durationMs?.takeIf { it > 0L }
        durationMs = when {
            playerLength == null -> known ?: 0L
            // AVPlayer's own length far from the song's real one (a guessed length of a
            // fragmented stream, often double): the song's length. A music video's own
            // length (a little longer than the song) is kept.
            known != null && (playerLength > known * 14 / 10 || playerLength < known * 7 / 10) -> known
            else -> playerLength
        }
        if (isReady && loggedLengthFor != currentTrack?.id) {
            loggedLengthFor = currentTrack?.id
            AppDiagnostics.log("playback", "\"${currentTrack?.title}\": AVPlayer length ${playerLength ?: -1} ms, song ${known ?: -1} ms, shown ${durationMs} ms")
        }
        _state.value = WindowsPlaybackSnapshot(currentTrack?.id, isPlaying, isReady, positionMs, durationMs, lastError)
        updateNowPlaying(force = false)
    }

    /** The lock screen / Control Center card; refreshed when something changes (iOS runs the clock itself). */
    private fun updateNowPlaying(force: Boolean) {
        val track = currentTrack
        if (track == null) {
            if (force) MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = null
            return
        }
        val expected = lastInfoPositionMs + if (lastInfoPlaying == true) 250L else 0L
        val jumped = abs(positionMs - expected) > 1_500L
        if (!force && lastInfoPlaying == isPlaying && !jumped) {
            lastInfoPositionMs = positionMs
            return
        }
        lastInfoPlaying = isPlaying
        lastInfoPositionMs = positionMs
        val info = mutableMapOf<Any?, Any?>(
            MPMediaItemPropertyTitle to track.title,
            MPMediaItemPropertyArtist to track.artist,
            MPMediaItemPropertyPlaybackDuration to durationMs / 1000.0,
            MPNowPlayingInfoPropertyElapsedPlaybackTime to positionMs / 1000.0,
            MPNowPlayingInfoPropertyPlaybackRate to if (isPlaying) tempo.toDouble() else 0.0,
        )
        if (track.album.isNotBlank()) info[MPMediaItemPropertyAlbumTitle] = track.album
        if (artworkFor == track.id) artwork?.let { info[MPMediaItemPropertyArtwork] = it }
        MPNowPlayingInfoCenter.defaultCenter().nowPlayingInfo = info
    }

    private fun loadArtwork(track: Track) {
        artworkFor = track.id
        artwork = null
        val url = track.artworkUrl?.let { NSURL.URLWithString(it) } ?: return
        dispatch_async(dispatch_get_global_queue(DISPATCH_QUEUE_PRIORITY_DEFAULT.toLong(), 0u)) {
            val data: NSData? = NSData.dataWithContentsOfURL(url)
            val image = data?.let { UIImage.imageWithData(it) }
            dispatch_async(dispatch_get_main_queue()) {
                if (image != null && currentTrack?.id == track.id) {
                    artwork = MPMediaItemArtwork(boundsSize = image.size) { _ -> image }
                    updateNowPlaying(force = true)
                }
            }
        }
    }

    override fun play(track: Track, url: String, startPositionMs: Long) = onMain {
        currentTrack = track
        lastError = null
        itemObservers.forEach { NSNotificationCenter.defaultCenter.removeObserver(it) }
        val link = (if (url.startsWith("file://")) NSURL.fileURLWithPath(url.removePrefix("file://")) else NSURL.URLWithString(url))
        if (link == null) {
            lastError = "Playback failed (bad address)"
            publish()
            return@onMain
        }
        // YouTube's stream URLs only answer the app (client) they were made for.
        val options = IosStreamHeaders.userAgentFor(url)?.let { agent ->
            mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("User-Agent" to agent))
        }
        val asset = AVURLAsset(uRL = link, options = options)
        val item = AVPlayerItem(asset = asset)
        item.audioTimePitchAlgorithm = AVAudioTimePitchAlgorithmSpectral
        itemObservers = listOf(
            NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemDidPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
                wantsPlay = false
                publish()
                onPlaybackEnded?.invoke()
            },
            NSNotificationCenter.defaultCenter.addObserverForName(AVPlayerItemFailedToPlayToEndTimeNotification, item, NSOperationQueue.mainQueue) { _ ->
                lastError = "Playback stopped (network)"
                publish()
            },
        )
        player.replaceCurrentItemWithPlayerItem(item)
        player.volume = volume.coerceIn(0f, 1f)
        if (startPositionMs > 0L) player.seekToTime(CMTimeMakeWithSeconds(startPositionMs / 1000.0, 1000))
        AVAudioSession.sharedInstance().setActive(true, null)
        wantsPlay = true
        startPlayer()
        loadArtwork(track)
        publish()
        updateNowPlaying(force = true)
    }

    override fun pause() = onMain {
        wantsPlay = false
        player.pause()
        publish()
    }

    override fun resume() = onMain {
        if (player.currentItem == null) return@onMain
        wantsPlay = true
        AVAudioSession.sharedInstance().setActive(true, null)
        startPlayer()
        publish()
    }

    override fun seekTo(positionMs: Long) = onMain {
        val target = positionMs.coerceAtLeast(0L)
        player.seekToTime(CMTimeMakeWithSeconds(target / 1000.0, 1000), CMTimeMake(0, 1), CMTimeMake(0, 1))
        this.positionMs = target
        publish()
        updateNowPlaying(force = true)
    }

    override fun presetState(volume: Float, tempo: Float, pitch: Int) {
        this.volume = volume
        this.tempo = tempo
    }

    /** Speed keeps the pitch (AVPlayer has no separate pitch shift); [pitch] is not applied on iPhone. */
    override fun setAudioTuning(tempo: Float, pitch: Int) = onMain {
        this.tempo = tempo
        if (isPlaying || wantsPlay) player.rate = tempo
    }

    override fun setVolume(volume: Float) = onMain {
        this.volume = volume
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun sendBluetoothConnectPacket(payload: String) = Unit

    override fun stop() = onMain {
        wantsPlay = false
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        currentTrack = null
        publish()
        updateNowPlaying(force = true)
    }

    /** One player for the whole app (it keeps playing in the background). */
    override fun release() = Unit
}

private val sharedEngine by lazy { IosPlaybackEngine() }

actual fun createPlaybackEngine(): PlaybackEngine = sharedEngine
