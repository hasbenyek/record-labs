package com.recordlabs.desktop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import com.recordlabs.desktop.data.MusicVideoCaptionCue
import com.recordlabs.desktop.data.MusicVideoCaptionTrack
import com.recordlabs.desktop.data.MusicVideoClock
import com.recordlabs.desktop.data.MusicVideoOptions
import com.recordlabs.desktop.data.MusicVideoStream
import com.recordlabs.desktop.data.MusicVideoStreamServer
import com.recordlabs.desktop.data.WindowsMusicRepository
import com.recordlabs.desktop.data.WindowsStorage
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.media.MediaRef
import uk.co.caprica.vlcj.media.MediaStatistics
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.io.File
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.roundToInt

/** Common surface of the animated-artwork and music-video frame producers. */
internal interface ArtworkFrameSource {
    var onFrame: ((ImageBitmap) -> Unit)?
    var onFailed: (() -> Unit)?
    fun release()
}

/** Resolves music-video picture streams for the player and lyrics artwork. */
internal object WindowsMusicVideoSource {
    // Same pattern as WindowsLyricsProviderRegistry: a private repository for
    // background lookups; the visitor id and caches are shared process-wide.
    private val repository by lazy { WindowsMusicRepository() }

    suspend fun streamUrl(videoId: String, targetHeight: Int): String? =
        runCatching { repository.musicVideoStreamUrl(videoId, targetHeight) }.getOrNull()

    fun invalidate(videoId: String) = repository.invalidateMusicVideo(videoId)

    /** Heights and uploader captions offered by the video (empty when unknown). */
    suspend fun options(videoId: String): MusicVideoOptions = try {
        repository.musicVideoOptions(videoId)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        MusicVideoOptions()
    }

    suspend fun captions(track: MusicVideoCaptionTrack): List<MusicVideoCaptionCue> = try {
        repository.musicVideoCaptions(track)
    } catch (cancelled: kotlinx.coroutines.CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        emptyList()
    }
}

private fun logMusicVideo(message: String) {
    runCatching {
        File(WindowsStorage.directory, "artwork.log").appendText("${java.time.Instant.now()}: [MV] $message\n")
    }
}

/**
 * Muted VLC decoder for a music video's picture, kept in step with the audio
 * that [WindowsMediaPlayer] is actually playing (read through [MusicVideoClock]).
 *
 * Starting/seeking: VLC 3 cannot seek in YouTube's fragmented MP4, and speeding
 * VLC up to catch up makes it drop and skip pictures. So a start plays the
 * virtual file that begins with the next fragment boundary ahead of the audio
 * (see [MusicVideoStream]; fragment start times are exact), holds its first
 * picture hidden until the audio arrives there, and is shown from then on, in
 * step. The previous picture (or the cover) stays up meanwhile, i.e. a few
 * seconds at most after a start or a seek.
 *
 * Position: VLC's own time/position are not trusted (they report the demuxer,
 * not the picture, and freeze in several cases). The picture position is the
 * fragment start plus the time VLC has been playing at the requested rate,
 * frozen while VLC reports no new pictures (network stall). VLC re-sends the
 * last picture while starved or paused, so render callbacks are not proof of
 * progress; its picture statistics are.
 *
 * - Play/pause and speed follow the audio; while another track plays it pauses.
 * - Picture ahead of the audio: it holds (pauses) for exactly the difference.
 * - Picture behind (stall, seek forward) or far ahead (seek back): restart at
 *   the next fragment boundary.
 * - End of the picture (videos can be shorter than their audio): the last frame
 *   stays until the audio moves back before it.
 */
internal class VlcMusicVideoDecoder(
    private val streamUrl: String,
    private val trackId: String,
    private val maxWidth: Int,
    private val maxHeight: Int,
) : ArtworkFrameSource {
    override var onFrame: ((ImageBitmap) -> Unit)? = null
    override var onFailed: (() -> Unit)? = null

    private val released = AtomicBoolean(false)
    private val failed = AtomicBoolean(false)
    private val pendingUiFrame = AtomicBoolean(false)
    private val worker: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "record-labs-music-video").apply { isDaemon = true }
    }
    @Volatile private var factory: MediaPlayerFactory? = null
    @Volatile private var mediaPlayer: EmbeddedMediaPlayer? = null
    @Volatile private var stream: MusicVideoStream? = null
    // Render thread: the copy buffer and a few bitmaps, reused frame after frame
    // instead of allocating a whole picture (up to tens of MB at 4K) each time.
    private var frameBytes: ByteArray? = null
    private val framePool = arrayOfNulls<Bitmap>(FRAME_POOL_SIZE)
    private val frameImages = arrayOfNulls<ImageBitmap>(FRAME_POOL_SIZE)
    private var framePoolWidth = 0
    private var framePoolHeight = 0
    private var nextFrameSlot = 0
    private val statistics = MediaStatistics()

    // Worker-thread state, except where noted.
    private var estimatedVideoMs = 0.0
    private var lastTickNanos = 0L
    private var lastAudioMs = -1L
    private var lastSeekNanos = 0L
    private var awaitingWhilePlayingMs = 0.0
    private var pictureCount = -1
    private var pictureCountChangedNanos = 0L
    private var estimateAtPictureChange = 0.0
    private var startGeneration = 0
    // Number of media handed to VLC; matched against its events.
    private var mediaGeneration = 0
    // Render thread: last time a picture with different content arrived.
    @Volatile private var newPictureNanos = 0L
    private var lastPictureHash = 0
    private var lastProgressNanos = 0L
    @Volatile private var awaitingFirstFrame = true
    @Volatile private var firstFrameNanos = 0L
    @Volatile private var startRequestedNanos = 0L
    // Started ahead of the audio: pictures stay hidden until it arrives there.
    @Volatile private var waitingForAudio = false
    // How long a start takes until its first picture (learned, wall time).
    @Volatile private var startupLeadMs = INITIAL_STARTUP_LEAD_MS
    @Volatile private var leadMeasurable = false
    @Volatile private var holdingForAudio = false
    // What this decoder last told VLC; VLC's own status is not trusted.
    @Volatile private var requestedPlaying = true
    @Volatile private var requestedRate = 1f
    @Volatile private var rateDirty = true
    // Worker thread: a seek started at the fragment the audio is in, played
    // hidden at CATCH_UP_RATE until the picture reaches the audio (see startAt).
    private var catchingUp = false
    // Worker thread: a picture a little behind the audio plays slightly faster
    // (visibly) until it is in step again, instead of a restart that would
    // freeze the picture until the next fragment boundary.
    private var nudging = false
    private var nudgeStartedNanos = 0L
    private var catchUpStartedNanos = 0L
    private var catchUpBudgetMs = 0L
    private var endHeld = false
    // Worker thread: the audio played on while the main window was minimized.
    private var pausedWhileHidden = false
    private var holdToken = 0
    // Size of the virtual file of the current start (see MusicVideoStream).
    private var virtualLength = 0L
    private var heldAtAudioMs = 0L
    // VLC event thread: media changes and end-of-stream, matched by generation
    // so a late "finished" of the previous start is never taken for this one.
    @Volatile private var eventGeneration = 0
    @Volatile private var finishedGeneration = -1
    @Volatile private var playingGeneration = -1
    // Restart throttling: repeated corrections back off instead of looping.
    private var recentRestarts = 0
    private var lastRestartNanos = 0L
    private var nextRestartAllowedNanos = 0L

    init {
        ArtworkMemoryGuard.ensureStarted()
        ArtworkMemoryGuard.activeDecoders.incrementAndGet()
        worker.execute { start() }
    }

    private fun start() {
        if (released.get()) return
        // Let the audio get going first: the video stream is large and must not
        // compete with the audio start. The cover stays on screen meanwhile.
        val waiting = MusicVideoClock.read()
        // A fresh track that is still loading (at its start) may begin at once:
        // fragment 0 then waits, paused on its first picture, for the audio.
        val freshTrack = waiting.trackId == trackId && waiting.positionMs < PRESTART_WINDOW_MS
        if (waiting.trackId != trackId || !(waiting.isPlaying || freshTrack)) {
            runCatching { worker.schedule(::start, 150L, TimeUnit.MILLISECONDS) }
            return
        }
        try {
            val resolved = MusicVideoStreamServer.streamFor(streamUrl)
            if (resolved == null) {
                logMusicVideo("Stream for $trackId is no longer registered")
                fail()
                return
            }
            stream = resolved
            ensureJavaFxMediaNatives()
            val createdFactory = MediaPlayerFactory(
                "--intf=dummy", "--vout=dummy", "--no-audio", "--no-osd",
                "--network-caching=800", "--http-reconnect",
                // Read ahead ~10-30 s instead of up to 16 MiB, so the picture
                // never competes much with the audio stream for bandwidth.
                "--prefetch-buffer-size=2048",
            )
            factory = createdFactory
            val player = createdFactory.mediaPlayers().newEmbeddedMediaPlayer()
            mediaPlayer = player
            player.videoSurface().set(createdFactory.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    // Keep the source aspect ratio (16:9, 4:3, vertical...) inside the target box.
                    // VLC may report a padded size (AV1 is aligned to 128 pixels)
                    // and stretches the picture into whatever is asked for, so
                    // the real size from the format list sets the shape.
                    val known = stream?.takeIf { it.pictureWidth > 0 && it.pictureHeight > 0 }
                    val pictureWidth = known?.pictureWidth ?: sourceWidth
                    val pictureHeight = known?.pictureHeight ?: sourceHeight
                    if (pictureWidth <= 0 || pictureHeight <= 0) return RV32BufferFormat(maxWidth, maxHeight)
                    val scale = minOf(maxWidth.toFloat() / pictureWidth, maxHeight.toFloat() / pictureHeight, 1f)
                    val width = ((pictureWidth * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
                    val height = ((pictureHeight * scale).roundToInt() / 2 * 2).coerceAtLeast(2)
                    return RV32BufferFormat(width, height)
                }
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
            }, object : RenderCallback {
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
                    if (released.get()) return
                    // Pictures before the new media is playing are left over
                    // from the previous start, not its first frame.
                    if (awaitingFirstFrame && playingGeneration != eventGeneration) return
                    if (awaitingFirstFrame) {
                        firstFrameNanos = System.nanoTime()
                        if (leadMeasurable) {
                            val measured = (firstFrameNanos - startRequestedNanos) / 1_000_000L
                            startupLeadMs = ((startupLeadMs + measured) / 2).coerceIn(0L, MAX_STARTUP_LEAD_MS)
                        }
                        awaitingFirstFrame = false
                        // Stop at once instead of waiting for the next sync.
                        runCatching { worker.execute { sync() } }
                    }
                    val hash = pictureHash(nativeBuffers[0])
                    if (hash != lastPictureHash) {
                        lastPictureHash = hash
                        newPictureNanos = System.nanoTime()
                    }
                    if (onFrame == null) return
                    if (!MainWindowActivity.shownNow) return
                    // Hidden until the audio reaches the picture.
                    if (waitingForAudio) return
                    // While paused VLC keeps re-sending the same picture; the UI
                    // already shows it, so skip the copy.
                    if (!requestedPlaying && !wasJustStarted()) return
                    // Drop frames while the UI is still showing the previous one.
                    if (!pendingUiFrame.compareAndSet(false, true)) return
                    try {
                        val buffer = nativeBuffers[0]
                        val width = bufferFormat.width
                        val height = bufferFormat.height
                        val size = width * height * 4
                        val bytes = frameBytes?.takeIf { it.size == size } ?: ByteArray(size).also { frameBytes = it }
                        buffer.get(bytes)
                        buffer.rewind()
                        if (width != framePoolWidth || height != framePoolHeight) {
                            // New size: start a fresh set (the old pictures may still be on screen).
                            framePool.fill(null)
                            frameImages.fill(null)
                            framePoolWidth = width
                            framePoolHeight = height
                        }
                        // Only one picture is in flight and the UI shows the latest,
                        // so the slot written now was handed over two frames ago.
                        val slot = nextFrameSlot
                        nextFrameSlot = (slot + 1) % FRAME_POOL_SIZE
                        val bitmap = framePool[slot] ?: Bitmap().also { framePool[slot] = it }
                        bitmap.installPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, width * 4)
                        val frame = frameImages[slot] ?: bitmap.asComposeImageBitmap().also { frameImages[slot] = it }
                        javax.swing.SwingUtilities.invokeLater {
                            // Always released: a failing frame must not stop every later one.
                            try {
                                if (!released.get()) onFrame?.invoke(frame)
                            } finally {
                                pendingUiFrame.set(false)
                            }
                        }
                    } catch (_: Throwable) {
                        pendingUiFrame.set(false)
                    }
                }
            }, true))
            player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                // VLC event thread: never call back into VLC from here.
                override fun mediaChanged(mediaPlayer: MediaPlayer, media: MediaRef) {
                    eventGeneration++
                }

                override fun playing(mediaPlayer: MediaPlayer) {
                    playingGeneration = eventGeneration
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    logMusicVideo("VLC error for $trackId")
                    fail()
                }

                override fun finished(mediaPlayer: MediaPlayer) {
                    finishedGeneration = eventGeneration
                }
            })
            val clock = MusicVideoClock.read()
            val startMs = if (clock.trackId == trackId) clock.positionMs else 0L
            logMusicVideo("Starting $trackId at $startMs ms (${resolved.fragments.size} fragments, ${resolved.durationMs} ms)")
            if (!startAt(player, startMs, clock.rate, clock.trackId == trackId && clock.isPlaying)) {
                fail()
                return
            }
            worker.scheduleWithFixedDelay(::sync, 150L, SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS)
        } catch (error: Throwable) {
            logMusicVideo("Decoder start failed for $trackId: ${error.javaClass.simpleName}: ${error.message}")
            fail()
        }
    }

    /** Cheap fingerprint of a picture: a few pixels spread over the buffer. */
    private fun pictureHash(buffer: ByteBuffer): Int {
        val limit = buffer.capacity() / 4
        if (limit <= 0) return 0
        var hash = 17
        val step = maxOf(1, limit / 61)
        var index = step / 2
        while (index < limit) {
            hash = hash * 31 + buffer.getInt(index * 4)
            index += step
        }
        return hash
    }

    /** The first frames after a (re)start are always shown, even while paused. */
    private fun wasJustStarted(): Boolean = System.nanoTime() - firstFrameNanos < RESTART_SETTLE_MS * 1_000_000L * 4

    /**
     * (Re)starts the picture at the first fragment boundary the audio (now at
     * [audioMs], moving at [rate]) has not reached by the time pictures flow.
     */
    private fun startAt(player: EmbeddedMediaPlayer, audioMs: Long, rate: Float, audioPlaying: Boolean, allowCatchUp: Boolean = false): Boolean {
        val source = stream ?: return false
        startGeneration++
        catchingUp = false
        nudging = false
        val index = nextBoundaryIndex(source, audioMs, rate, audioPlaying)
        // After a seek, waiting for the next fragment boundary can leave the old
        // picture up for most of a fragment (2-10 s). Instead the fragment the
        // audio is in is started and played hidden at CATCH_UP_RATE until it
        // reaches the audio, when that is clearly sooner.
        if (allowCatchUp && audioPlaying && rate > 0f) {
            val current = source.fragmentIndexFor(audioMs.coerceAtLeast(0L))
            val leadMs = startupLeadMs + START_MARGIN_MS
            val behindMs = (audioMs - source.fragments[current].startMs).coerceAtLeast(0L)
            // Wall time until the picture catches up / until the audio reaches the next boundary.
            val catchUpMs = ((behindMs + CATCH_UP_RATE * leadMs) / (CATCH_UP_RATE - rate)).toLong()
            val boundaryWaitMs = index?.let { ((source.fragments[it].startMs - audioMs) / rate).toLong() } ?: Long.MAX_VALUE
            if (catchUpMs + CATCH_UP_ADVANTAGE_MS < boundaryWaitMs) {
                leadMeasurable = false
                awaitingFirstFrame = true
                startRequestedNanos = System.nanoTime()
                awaitingWhilePlayingMs = 0.0
                estimatedVideoMs = source.fragments[current].startMs.toDouble()
                pictureCount = -1
                waitingForAudio = true
                holdingForAudio = false
                requestedPlaying = true
                catchingUp = true
                catchUpStartedNanos = System.nanoTime()
                catchUpBudgetMs = catchUpMs * 2 + CATCH_UP_EXTRA_MS
                requestedRate = CATCH_UP_RATE
                rateDirty = false
                endHeld = false
                virtualLength = source.init.size + (source.contentLength - source.fragments[current].offset)
                val started = player.media().play(source.urlFrom(current))
                if (started) {
                    mediaGeneration++
                    player.controls().setRate(CATCH_UP_RATE)
                    logMusicVideo("Seek catch-up for $trackId from ${source.fragments[current].startMs} ms to audio $audioMs ms (~$catchUpMs ms instead of $boundaryWaitMs ms)")
                }
                return started
            }
        }
        if (index == null) {
            // Inside the last fragment: nothing to start in step with. Keep the
            // current picture (or the cover) until the audio moves back.
            holdingForAudio = false
            waitingForAudio = false
            heldAtAudioMs = audioMs
            endHeld = true
            pausePicture(player)
            return true
        }
        leadMeasurable = true
        awaitingFirstFrame = true
        startRequestedNanos = System.nanoTime()
        awaitingWhilePlayingMs = 0.0
        estimatedVideoMs = source.fragments[index].startMs.toDouble()
        pictureCount = -1
        waitingForAudio = true
        holdingForAudio = false
        requestedPlaying = true
        rateDirty = true
        endHeld = false
        virtualLength = source.init.size + (source.contentLength - source.fragments[index].offset)
        val started = player.media().play(source.urlFrom(index))
        if (started) mediaGeneration++
        return started
    }

    /** First fragment the audio (at [audioMs]) has not reached once pictures can flow. */
    private fun nextBoundaryIndex(source: MusicVideoStream, audioMs: Long, rate: Float, audioPlaying: Boolean): Int? {
        // Paused audio does not move while the picture starts up.
        val leadMs = if (audioPlaying) ((startupLeadMs + START_MARGIN_MS) * rate).toLong() else 0L
        val arrivalMs = audioMs.coerceAtLeast(0L) + leadMs
        val current = source.fragmentIndexFor(arrivalMs)
        return when {
            source.fragments[current].startMs >= arrivalMs -> current
            current < source.fragments.lastIndex -> current + 1
            else -> null
        }
    }

    /** A restart used for corrections; backs off when they keep repeating (seeks are exempt). */
    private fun restartAt(player: EmbeddedMediaPlayer, clock: MusicVideoClock.Reading, now: Long) {
        val positionMs = clock.positionMs
        val afterSeek = now - lastSeekNanos < SEEK_GRACE_MS * 1_000_000L
        logMusicVideo("Restart $trackId: audio $positionMs ms, picture ${estimatedVideoMs.toLong()} ms${if (afterSeek) " (seek)" else ""}")
        if (!afterSeek) {
            if (now < nextRestartAllowedNanos) return
            recentRestarts = if (now - lastRestartNanos > RESTART_WINDOW_MS * 1_000_000L) 1 else recentRestarts + 1
            lastRestartNanos = now
            if (recentRestarts >= RESTARTS_BEFORE_BACKOFF) {
                val backoffMs = (RESTART_BACKOFF_MS * (recentRestarts - RESTARTS_BEFORE_BACKOFF + 1)).coerceAtMost(MAX_RESTART_BACKOFF_MS)
                nextRestartAllowedNanos = now + backoffMs * 1_000_000L
                logMusicVideo("Picture of $trackId keeps drifting; next correction in $backoffMs ms")
            }
        }
        startAt(player, positionMs, clock.rate, clock.isPlaying, allowCatchUp = afterSeek)
    }

    private fun sync() {
        if (released.get()) return
        val player = mediaPlayer ?: return
        val source = stream ?: return
        runCatching {
            val now = System.nanoTime()
            val clock = MusicVideoClock.read()
            val ownTrack = clock.trackId == trackId
            // A minimized main window pauses the picture; the audio plays on.
            val shown = MainWindowActivity.shownNow
            val shouldPlay = ownTrack && clock.isPlaying && shown
            if (!shown && ownTrack && clock.isPlaying) pausedWhileHidden = true
            val tickMs = if (lastTickNanos == 0L) 0.0 else (now - lastTickNanos) / 1_000_000.0
            // An audio jump that is not explained by playback is a user seek.
            if (ownTrack && lastAudioMs >= 0L && tickMs > 0.0) {
                val expected = if (clock.isPlaying) tickMs * clock.rate else 0.0
                if (abs(clock.positionMs - lastAudioMs - expected) > SEEK_DETECT_MS) lastSeekNanos = now
            }
            val seekedNow = lastSeekNanos == now
            lastAudioMs = if (ownTrack) clock.positionMs else -1L

            // Evidence that new pictures arrive: VLC's statistics (they also
            // grow for still images, but stop once the demuxer has read the
            // whole remaining file) or picture content that changed (VLC
            // re-sends the same picture while starved or paused).
            val haveStatistics = player.media().info().statistics(statistics)
            val count = if (haveStatistics) statistics.picturesDisplayed() + statistics.picturesLost() else pictureCount
            // Once the whole remaining file has been read, starving is impossible.
            val fullyRead = haveStatistics && (statistics.inputBytesRead().toLong() and 0xffffffffL) >= virtualLength - 4_096L
            if (count != pictureCount) {
                pictureCount = count
                pictureCountChangedNanos = now
            }
            val progressNanos = maxOf(pictureCountChangedNanos, newPictureNanos)
            if (progressNanos != lastProgressNanos) {
                lastProgressNanos = progressNanos
                estimateAtPictureChange = estimatedVideoMs
            }
            val sinceFirstFrameMs = (now - firstFrameNanos) / 1_000_000L
            val stalled = !awaitingFirstFrame && !fullyRead && sinceFirstFrameMs > STATS_WARMUP_MS &&
                (now - progressNanos) / 1_000_000L > STALL_MS
            if (!awaitingFirstFrame && requestedPlaying && !holdingForAudio) {
                if (stalled) {
                    // Undo what was counted since the last real picture (the
                    // statistics run up to one update period behind the picture).
                    estimatedVideoMs = minOf(estimatedVideoMs, estimateAtPictureChange + STATS_PERIOD_MS * requestedRate)
                } else {
                    val fromNanos = maxOf(lastTickNanos, firstFrameNanos)
                    if (fromNanos in 1 until now) estimatedVideoMs += (now - fromNanos) / 1_000_000.0 * requestedRate
                }
            }
            lastTickNanos = now

            // End of the picture: by its known length, or VLC reaching the end.
            val finishedNow = finishedGeneration == mediaGeneration && !awaitingFirstFrame
            if (!endHeld && !awaitingFirstFrame && (estimatedVideoMs >= source.durationMs - END_GUARD_MS || finishedNow)) {
                if (finishedNow && estimatedVideoMs < source.durationMs - EARLY_END_MS) {
                    // The stream was cut off (network), not the real end.
                    if (shouldPlay) restartAt(player, clock, now)
                    return
                }
                endHeld = true
                heldAtAudioMs = if (ownTrack) clock.positionMs else source.durationMs
                holdingForAudio = false
                pausePicture(player)
            }
            if (endHeld) {
                // Keep the last frame; a seek back clearly before it restarts.
                if (ownTrack && clock.positionMs < heldAtAudioMs - END_REWIND_MS &&
                    nextBoundaryIndex(source, clock.positionMs, clock.rate, clock.isPlaying) != null
                ) {
                    restartAt(player, clock, now)
                }
                return
            }

            if (pausedWhileHidden && shown) {
                pausedWhileHidden = false
                // Back on screen after the audio moved on: catch up at once, like a seek.
                if (shouldPlay && abs(estimatedVideoMs - clock.positionMs) > MAX_BEHIND_MS) {
                    lastSeekNanos = now
                    restartAt(player, clock, now)
                    return
                }
            }
            if (holdingForAudio) {
                // A seek while holding needs a fresh decision, not the old wait.
                if (!shouldPlay || seekedNow) holdingForAudio = false else return
            }
            if (shouldPlay && !requestedPlaying) resumePicture(player)
            if (!shouldPlay) {
                // Let a start reach its first picture (hidden) before pausing:
                // VLC paused while opening shows nothing and loses the head start.
                if (!awaitingFirstFrame) pausePicture(player)
                return
            }
            // While a seek catches up, the picture runs faster on purpose.
            if (!catchingUp && !nudging && (rateDirty || abs(requestedRate - clock.rate) > 0.005f)) {
                requestedRate = clock.rate
                rateDirty = false
                player.controls().setRate(clock.rate)
            }
            if (catchingUp && !awaitingFirstFrame) runCatching { if (abs(player.status().rate() - CATCH_UP_RATE) > 0.01f) player.controls().setRate(CATCH_UP_RATE) }
            if (awaitingFirstFrame) {
                // A start that never produces a picture while the audio plays is
                // a failure, not a wait (time spent paused does not count).
                awaitingWhilePlayingMs += tickMs
                if (awaitingWhilePlayingMs > START_TIMEOUT_MS) {
                    logMusicVideo("No picture for $trackId after ${START_TIMEOUT_MS} ms")
                    fail()
                }
                return
            }

            val audioMs = clock.positionMs
            val drift = estimatedVideoMs - audioMs // > 0: picture ahead of the audio
            if (catchingUp) {
                when {
                    // Another seek while catching up: a fresh start for the new position.
                    seekedNow -> restartAt(player, clock, now)
                    // Caught up (or a little past): back to the audio's speed;
                    // a picture ahead waits hidden for exactly the difference.
                    drift >= -IN_SYNC_MS -> {
                        catchingUp = false
                        requestedRate = clock.rate
                        player.controls().setRate(clock.rate)
                        if (drift > IN_SYNC_MS) hold(player, drift, clock.rate) else waitingForAudio = false
                    }
                    // Not keeping up (slow network/CPU): fall back to the next boundary.
                    (now - catchUpStartedNanos) / 1_000_000L > catchUpBudgetMs -> {
                        logMusicVideo("Seek catch-up for $trackId too slow; waiting for the next fragment")
                        catchingUp = false
                        requestedRate = clock.rate
                        rateDirty = true
                        startAt(player, audioMs, clock.rate, clock.isPlaying)
                    }
                }
                return
            }
            if (nudging) {
                when {
                    seekedNow -> { stopNudge(player, clock.rate); restartAt(player, clock, now) }
                    // In step again: back to the audio's speed.
                    drift >= -IN_SYNC_MS -> stopNudge(player, clock.rate)
                    // Not getting closer (slow network/CPU): the usual restart.
                    drift < -MAX_NUDGE_BEHIND_MS || (now - nudgeStartedNanos) / 1_000_000L > NUDGE_BUDGET_MS -> {
                        stopNudge(player, clock.rate)
                        restartAt(player, clock, now)
                    }
                }
                return
            }
            if (waitingForAudio) {
                when {
                    // The first picture came a little late: show it and let it catch up.
                    drift < -MAX_BEHIND_MS && drift > -MAX_NUDGE_BEHIND_MS && !stalled -> {
                        waitingForAudio = false
                        startNudge(player, drift, clock.rate, now)
                    }
                    // The audio passed the boundary first (slow start): next one.
                    drift < -MAX_BEHIND_MS -> restartAt(player, clock, now)
                    drift > MAX_WAIT_MS -> restartAt(player, clock, now)
                    drift > IN_SYNC_MS -> hold(player, drift, clock.rate)
                    else -> waitingForAudio = false
                }
                return
            }
            if (sinceFirstFrameMs < RESTART_SETTLE_MS) return
            when {
                drift > MAX_HOLD_MS -> restartAt(player, clock, now)
                // A little behind: catch up visibly instead of freezing until a restart shows.
                drift < -MAX_BEHIND_MS && drift > -MAX_NUDGE_BEHIND_MS && !stalled -> startNudge(player, drift, clock.rate, now)
                drift < -MAX_BEHIND_MS -> restartAt(player, clock, now)
                drift > IN_SYNC_MS -> hold(player, drift, clock.rate)
            }
        }
    }

    private fun startNudge(player: EmbeddedMediaPlayer, drift: Double, rate: Float, now: Long) {
        nudging = true
        nudgeStartedNanos = now
        val nudgeRate = rate * (if (drift < -NUDGE_FAST_BEHIND_MS) NUDGE_FAST_FACTOR else NUDGE_FACTOR)
        requestedRate = nudgeRate
        rateDirty = false
        runCatching { player.controls().setRate(nudgeRate) }
        logMusicVideo("Catch-up $trackId: picture ${(-drift).toLong()} ms behind, playing at ${nudgeRate}x")
    }

    private fun stopNudge(player: EmbeddedMediaPlayer, rate: Float) {
        nudging = false
        requestedRate = rate
        rateDirty = false
        runCatching { player.controls().setRate(rate) }
    }

    /** Ahead of the audio: pause for exactly the difference, then continue. */
    private fun hold(player: EmbeddedMediaPlayer, drift: Double, rate: Float) {
        holdingForAudio = true
        requestedPlaying = false
        player.controls().setPause(true)
        val holdMs = (drift / rate).toLong().coerceAtLeast(1L)
        // Each hold has its own token: a hold cancelled by a seek must not end a later one.
        val token = ++holdToken
        worker.schedule({
            if (!released.get() && holdingForAudio && token == holdToken) {
                holdingForAudio = false
                waitingForAudio = false
                lastTickNanos = System.nanoTime()
                val latest = MusicVideoClock.read()
                if (latest.trackId == trackId && latest.isPlaying) runCatching { resumePicture(player) }
            }
        }, holdMs, TimeUnit.MILLISECONDS)
    }

    private fun resumePicture(player: EmbeddedMediaPlayer) {
        requestedPlaying = true
        // Statistics were idle while paused; that is not a stall.
        pictureCountChangedNanos = System.nanoTime()
        estimateAtPictureChange = estimatedVideoMs
        player.controls().play()
    }

    private fun pausePicture(player: EmbeddedMediaPlayer) {
        if (awaitingFirstFrame) leadMeasurable = false
        if (!requestedPlaying) return
        requestedPlaying = false
        player.controls().setPause(true)
    }

    /** Current picture position estimate (diagnostics/tests). */
    internal val estimatedPositionMs: Long get() = estimatedVideoMs.toLong()

    private fun fail() {
        if (released.get() || !failed.compareAndSet(false, true)) return
        javax.swing.SwingUtilities.invokeLater { if (!released.get()) onFailed?.invoke() }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        ArtworkMemoryGuard.activeDecoders.decrementAndGet()
        onFrame = null
        // Stopping VLC can block for a moment; never do it on the UI thread.
        worker.execute {
            runCatching { mediaPlayer?.controls()?.stop() }
            runCatching { mediaPlayer?.release() }
            runCatching { factory?.release() }
            mediaPlayer = null
            factory = null
        }
        worker.shutdown()
    }

    private companion object {
        const val SYNC_INTERVAL_MS = 200L
        /** Hidden playback speed of a seek catching up with the audio. */
        const val CATCH_UP_RATE = 3f
        /** Catching up is used only when it shows the picture at least this much sooner. */
        const val CATCH_UP_ADVANTAGE_MS = 600L
        /** Extra time a catch-up may take beyond twice its estimate. */
        const val CATCH_UP_EXTRA_MS = 2_000L
        /** Drift treated as "in sync": no correction at all. */
        const val IN_SYNC_MS = 120L
        /** Picture this far behind the audio is corrected (sped up, or restarted). */
        const val MAX_BEHIND_MS = 450L
        /** Up to this far behind, the picture catches up by playing faster instead of restarting. */
        const val MAX_NUDGE_BEHIND_MS = 4_000L
        /** Catch-up speed (times the audio's speed), and the faster one for a larger gap. */
        const val NUDGE_FACTOR = 1.25f
        const val NUDGE_FAST_FACTOR = 1.5f
        const val NUDGE_FAST_BEHIND_MS = 1_500L
        /** A catch-up that has not closed the gap by then falls back to a restart. */
        const val NUDGE_BUDGET_MS = 12_000L
        /** Picture further ahead than this (a seek back) is restarted rather than held. */
        const val MAX_HOLD_MS = 1_500L
        /** A fresh start may wait this long for the audio (fragments are ~2-10 s). */
        const val MAX_WAIT_MS = 30_000L
        /** A new track this close to its start may start the picture before the audio plays. */
        const val PRESTART_WINDOW_MS = 1_000L
        /** First guess of the time from a start to its first picture. */
        const val INITIAL_STARTUP_LEAD_MS = 700L
        /** Reused picture bitmaps: one on screen, one handed over, one being written. */
        const val FRAME_POOL_SIZE = 3
        const val MAX_STARTUP_LEAD_MS = 4_000L
        /** Extra room so the first picture is ready before the audio arrives. */
        const val START_MARGIN_MS = 250L
        /** No new picture for this long while playing = stalled (estimate stops). */
        const val STALL_MS = 1_200L
        /** VLC refreshes its statistics about every half second. */
        const val STATS_PERIOD_MS = 500L
        /** VLC's statistics need a moment after the first frame. */
        const val STATS_WARMUP_MS = 1_000L
        /** Time after a (re)start before drift is judged again. */
        const val RESTART_SETTLE_MS = 600L
        /** Audio moving this much more/less than playback explains = seek. */
        const val SEEK_DETECT_MS = 1_000L
        const val SEEK_GRACE_MS = 1_500L
        /** Corrections within this window count as "repeating". */
        const val RESTART_WINDOW_MS = 8_000L
        const val RESTARTS_BEFORE_BACKOFF = 4
        const val RESTART_BACKOFF_MS = 2_000L
        const val MAX_RESTART_BACKOFF_MS = 10_000L
        const val START_TIMEOUT_MS = 15_000L
        /** The picture counts as ended this close to its known length. */
        const val END_GUARD_MS = 150L
        /** After the end, a seek back to before this distance restarts the picture. */
        const val END_REWIND_MS = 1_000L
        /** A VLC end this long before the known length is a cut-off stream. */
        const val EARLY_END_MS = 5_000L
    }
}
