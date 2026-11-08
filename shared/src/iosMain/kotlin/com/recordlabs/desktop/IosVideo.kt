@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import com.recordlabs.desktop.data.AppDiagnostics
import com.recordlabs.desktop.data.IosStreamHeaders
import com.recordlabs.desktop.platform.currentTimeMillis
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsAnimatedArtworkRepository
import com.recordlabs.desktop.data.WindowsMusicRepository
import com.recordlabs.desktop.data.createPlaybackEngine
import com.recordlabs.desktop.data.hasMusicVideo
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusFailed
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerItemVideoOutput
import platform.AVFoundation.AVPlayerLooper
import platform.AVFoundation.AVQueuePlayer
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.addOutput
import platform.AVFoundation.allowsExternalPlayback
import platform.AVFoundation.automaticallyWaitsToMinimizeStalling
import platform.AVFoundation.reasonForWaitingToPlay
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.muted
import platform.AVFoundation.preferredMaximumResolution
import platform.AVFoundation.preventsDisplaySleepDuringVideoPlayback
import platform.AVFoundation.rate
import platform.AVFoundation.removeOutput
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.timeControlStatus
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMake
import platform.CoreMedia.CMTimeRangeMake
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRelease
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.Foundation.NSURL
import platform.QuartzCore.CACurrentMediaTime
import platform.posix.memcpy
import kotlin.math.abs

/**
 * Videos on the iPhone: Apple Music's animated artwork and music videos, played
 * muted by AVPlayer. Each picture is taken from the player (AVPlayerItemVideoOutput)
 * and drawn by Compose like any image, so the video takes part in everything the
 * artwork does: rounded corners, the fade at the bottom of the player, the blur
 * as the cover gives way, and the player card growing and shrinking. (A UIKit video
 * view would sit outside Compose's drawing and miss all of these.)
 */
internal class IosVideoFrames(url: String, userAgent: String?, loop: Boolean, maxSide: Double, private val label: String = "video", loopMs: Long? = null) {
    private val player: AVPlayer
    private var looper: AVPlayerLooper? = null
    /** Each item the player plays (a looped clip is several copies) with its picture output. */
    private val outputs = ArrayList<Pair<AVPlayerItem, AVPlayerItemVideoOutput>>()

    /** The newest picture (drawn by [VideoPicture]). */
    var frame by mutableStateOf<ImageBitmap?>(null)
        private set
    /** Width / height of the video, once known. */
    var aspect by mutableStateOf<Float?>(null)
        private set
    /** The first picture is there. */
    var pictureShown by mutableStateOf(false)
        private set
    /** The stream could not be played. */
    var failed by mutableStateOf(false)
        private set

    /** Pictures shown lately, closed a little later (Compose may still be drawing the last one). */
    private val recent = ArrayDeque<org.jetbrains.skia.Image>()
    private var lastPullAt = 0.0
    /** The picture's bytes, reused from one picture to the next (Skia copies them). */
    private var pixels = ByteArray(0)
    private val createdAt = CACurrentMediaTime()
    private var lastStallLogAt = 0.0
    private var loggedFailure = false
    private var loggedError = false
    private var readyAt = 0.0
    private var reattached = 0

    private fun log(message: String) = AppDiagnostics.log("video", "$label: $message")

    init {
        val bare = url.substringBefore('?')
        log("open ${bare.substringAfter("://").substringBefore('/')}/…${bare.takeLast(32)} (${if (loop) "loop" else "follows the song"})")
        val link = NSURL.URLWithString(url)
        val options: Map<Any?, Any?>? = userAgent?.let {
            mapOf<Any?, Any?>("AVURLAssetHTTPHeaderFieldsKey" to mapOf("User-Agent" to it))
        }
        // A music video's picture stream as HLS pieces when its index was read.
        val pieces = IosStreamHeaders.playlistFor(url)
        if (pieces != null) log("as HLS pieces (${pieces.lineSequence().count { it.startsWith("#EXTINF") }})")
        fun newItem(): AVPlayerItem {
            val asset = pieces?.let { com.recordlabs.desktop.data.sharedIndexPlaylists.asset(url, it, options) }
                ?: AVURLAsset(uRL = link!!, options = options)
            return AVPlayerItem(asset = asset).apply {
                // Sharp enough for the artwork's box, light on decoding and copying.
                preferredMaximumResolution = CGSizeMake(maxSide, maxSide)
            }
        }
        if (loop) {
            val queue = AVQueuePlayer(items = emptyList<AVPlayerItem>())
            // Seamless loop: AVPlayerLooper queues copies of the clip back to back. Apple's
            // single-file clips run on ~10 s past the real loop (frames nobody shows): the
            // loop ends where the clip really does when that is known.
            looper = if (loopMs != null && loopMs > 1_000L) {
                AVPlayerLooper(player = queue, templateItem = newItem(), timeRange = CMTimeRangeMake(CMTimeMake(0, 1000), CMTimeMake(loopMs, 1000)))
            } else {
                AVPlayerLooper.playerLooperWithPlayer(queue, templateItem = newItem())
            }
            player = queue
        } else {
            val item = newItem()
            player = AVPlayer(playerItem = item)
        }
        player.muted = true
        // Plays as soon as there is a picture, rather than waiting for a long buffer (a
        // stall is picked up again below).
        player.automaticallyWaitsToMinimizeStalling = false
        player.allowsExternalPlayback = false
        player.preventsDisplaySleepDuringVideoPlayback = false
        attachOutputs()
    }

    /** A picture output on every item (AVPlayerLooper makes its copies when it starts). */
    private fun attachOutputs() {
        // (Items compared with == (isEqual:): the same item read twice from the player is
        // not always the same Kotlin reference, so === never matched and a new output was
        // added on every frame while none was ever read: no picture at all.)
        val items: List<AVPlayerItem> = (looper?.loopingPlayerItems?.filterIsInstance<AVPlayerItem>().orEmpty() +
            listOfNotNull(player.currentItem)).distinct()
        for (item in items) {
            if (outputs.any { it.first == item }) continue
            val output = AVPlayerItemVideoOutput(
                pixelBufferAttributes = mapOf<Any?, Any?>(
                    // kCVPixelBufferPixelFormatTypeKey: kCVPixelFormatType_32BGRA
                    "PixelFormatType" to 0x42475241,
                ),
            )
            item.addOutput(output)
            outputs += item to output
        }
    }

    /** Whether it should be playing (a stalled player is started again: see [pull]). */
    private var wantsPlay = false
    private var lastKickAt = 0.0

    fun play() {
        wantsPlay = true
        player.play()
    }
    fun pause() {
        wantsPlay = false
        player.pause()
    }

    /** The video is loaded (seeks before this only held it back). */
    val ready: Boolean get() = player.currentItem?.status == AVPlayerItemStatusReadyToPlay

    // How it is going, for the diagnostics (a few times after the first picture).
    private var picturesSinceLog = 0
    private var lastHealthAt = 0.0
    private var healthLogs = 0

    var rate: Float
        get() = player.rate
        set(value) {
            wantsPlay = value > 0f
            player.rate = value
        }

    val positionMs: Long
        get() = (CMTimeGetSeconds(player.currentTime()) * 1000.0).takeIf { !it.isNaN() }?.toLong() ?: 0L

    fun seekTo(positionMs: Long) {
        player.seekToTime(CMTimeMake(positionMs, 1000))
    }

    /** Takes the newest picture from the player, when there is one (on the UI thread). */
    fun pull() {
        val now = CACurrentMediaTime()
        // At most ~30 pictures a second: the clips are 24-30 fps.
        if (now - lastPullAt < 0.03) return
        lastPullAt = now
        val item = player.currentItem
        if (item != null && item.status == AVPlayerItemStatusFailed) {
            failed = true
            if (!loggedFailure) {
                loggedFailure = true
                log("cannot play: ${item.error?.localizedDescription ?: "unknown error"}")
            }
            return
        }
        // Nothing shown after a while: what the player is doing (every 5 s).
        if (!pictureShown && now - createdAt > 5.0 && now - lastStallLogAt > 5.0) {
            lastStallLogAt = now
            log(
                "no picture yet after ${(now - createdAt).toInt()} s: item ${item?.status ?: "none"}, " +
                    "playing ${player.timeControlStatus}, rate ${player.rate}, outputs ${outputs.size}, " +
                    "looping items ${looper?.loopingPlayerItems?.size ?: -1}, error ${item?.error?.localizedDescription ?: "none"}",
            )
        }
        if (item == null) return
        // Stalled (it stops by itself when the pictures run out): start it again.
        if (wantsPlay && player.rate == 0f && item.status == AVPlayerItemStatusReadyToPlay && now - lastKickAt > 1.0) {
            lastKickAt = now
            player.play()
        }
        if (pictureShown && healthLogs < 4 && now - lastHealthAt > 6.0) {
            lastHealthAt = now
            healthLogs++
            log(
                "$picturesSinceLog new pictures in 6 s, at ${positionMs / 1000.0} s, playing ${player.timeControlStatus}" +
                    (player.reasonForWaitingToPlay?.let { " ($it)" } ?: "") + ", rate ${player.rate}, wants ${if (wantsPlay) "play" else "pause"}" +
                    (looper?.let { ", looper ${it.status}${it.error?.localizedDescription?.let { e -> " $e" } ?: ""}, loop ${it.loopCount}" } ?: ""),
            )
            picturesSinceLog = 0
        }
        if (outputs.none { it.first == item }) attachOutputs()
        // HLS: an output added before the item was ready can stay empty for good; a fresh
        // one is added once it is ready and still shows nothing (twice at most).
        if (item.status == AVPlayerItemStatusReadyToPlay && !pictureShown) {
            if (readyAt == 0.0) readyAt = now
            if (now - readyAt > 1.2 * (reattached + 1) && reattached < 2) {
                reattached++
                outputs.filter { it.first == item }.forEach { (old, output) -> old.removeOutput(output) }
                outputs.removeAll { it.first == item }
                attachOutputs()
                log("no picture ${((now - readyAt) * 1000).toInt()} ms after ready: new picture output ($reattached)")
            }
        }
        val output = outputs.firstOrNull { it.first == item }?.second ?: return
        val time = output.itemTimeForHostTime(now)
        if (!output.hasNewPixelBufferForItemTime(time)) return
        val buffer = output.copyPixelBufferForItemTime(time, itemTimeForDisplay = null) ?: return
        try {
            CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
            val width = CVPixelBufferGetWidth(buffer).toInt()
            val height = CVPixelBufferGetHeight(buffer).toInt()
            val rowBytes = CVPixelBufferGetBytesPerRow(buffer).toInt()
            val base = CVPixelBufferGetBaseAddress(buffer)
            if (width <= 0 || height <= 0 || base == null) {
                CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
                return
            }
            val size = rowBytes * height
            if (pixels.size != size) pixels = ByteArray(size)
            val bytes = pixels
            bytes.usePinned { memcpy(it.addressOf(0), base, size.convert()) }
            CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
            val image = org.jetbrains.skia.Image.makeRaster(
                org.jetbrains.skia.ImageInfo(width, height, org.jetbrains.skia.ColorType.BGRA_8888, org.jetbrains.skia.ColorAlphaType.PREMUL),
                bytes,
                rowBytes,
            )
            frame = image.toComposeImageBitmap()
            picturesSinceLog++
            recent.addLast(image)
            while (recent.size > 3) recent.removeFirst().close()
            if (aspect == null) aspect = width.toFloat() / height
            if (!pictureShown) log("first picture ${width}x$height after ${((now - createdAt) * 1000).toInt()} ms")
            pictureShown = true
        } catch (error: Throwable) {
            if (!loggedError) {
                loggedError = true
                log("picture failed: ${error::class.simpleName}: ${error.message}")
            }
        } finally {
            CVPixelBufferRelease(buffer)
        }
    }

    fun release() {
        looper?.disableLooping()
        player.pause()
        outputs.forEach { (item, output) -> item.removeOutput(output) }
        outputs.clear()
        player.replaceCurrentItemWithPlayerItem(null)
        looper = null
        frame = null
        recent.forEach { it.close() }
        recent.clear()
    }
}

/** Draws the video's newest picture, filling ([ContentScale.Crop]) or fitting its box. */
@Composable
private fun VideoPicture(frames: IosVideoFrames, scale: ContentScale, modifier: Modifier) {
    LaunchedEffect(frames) {
        while (isActive) {
            withFrameNanos { }
            frames.pull()
        }
    }
    Spacer(
        modifier.drawBehind {
            val image = frames.frame ?: return@drawBehind
            val imageWidth = image.width.toFloat()
            val imageHeight = image.height.toFloat()
            if (imageWidth <= 0f || imageHeight <= 0f || size.width <= 0f || size.height <= 0f) return@drawBehind
            val crop = scale == ContentScale.Crop
            val factor = if (crop) maxOf(size.width / imageWidth, size.height / imageHeight)
            else minOf(size.width / imageWidth, size.height / imageHeight)
            if (crop) {
                // The middle of the picture, as large as the box allows.
                val srcWidth = size.width / factor
                val srcHeight = size.height / factor
                drawImage(
                    image,
                    srcOffset = IntOffset(((imageWidth - srcWidth) / 2f).toInt(), ((imageHeight - srcHeight) / 2f).toInt()),
                    srcSize = IntSize(srcWidth.toInt().coerceAtLeast(1), srcHeight.toInt().coerceAtLeast(1)),
                    dstSize = IntSize(size.width.toInt(), size.height.toInt()),
                )
            } else {
                val width = imageWidth * factor
                val height = imageHeight * factor
                drawImage(
                    image,
                    dstOffset = IntOffset(((size.width - width) / 2f).toInt(), ((size.height - height) / 2f).toInt()),
                    dstSize = IntSize(width.toInt(), height.toInt()),
                )
            }
        },
    )
}

/** The cover over the video until its first picture: it blurs and fades away (as on Android). */
@Composable
private fun CoverOverVideo(coverUrl: String?, pictureShown: Boolean, shape: RoundedCornerShape) {
    val reveal = remember { Animatable(0f) }
    LaunchedEffect(pictureShown) {
        reveal.animateTo(if (pictureShown) 1f else 0f, tween(1_000, easing = LinearEasing))
    }
    Artwork(
        coverUrl,
        Modifier.fillMaxSize().graphicsLayer {
            val progress = reveal.value
            alpha = 1f - ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
            renderEffect = if (progress > 0f && progress < 1f) {
                val t = (progress / 0.6f).coerceIn(0f, 1f)
                val radius = 22f * density * ((1.0 - kotlin.math.cos(kotlin.math.PI * t)) / 2.0).toFloat()
                BlurEffect(radius, radius, TileMode.Clamp)
            } else null
        },
        shape,
    )
}

/**
 * Apple Music animated artwork (found like on the desktop and Android), looping muted
 * under the cover; the cover stays when the song has none.
 */
@Composable
internal fun IosAnimatedArtwork(
    track: Track,
    coverUrl: String?,
    modifier: Modifier,
    shape: RoundedCornerShape,
    scale: ContentScale,
    onAspectRatio: (Float?) -> Unit,
) {
    var streamUrl by remember(track.id) { mutableStateOf<String?>(null) }
    var attempt by remember(track.id) { mutableStateOf(0) }
    LaunchedEffect(track.id, track.title, track.artist, attempt) {
        if (attempt > 0) {
            WindowsAnimatedArtworkRepository.invalidate(track)
            delay(1_500L * attempt)
        }
        var waitMs = 2_500L
        while (isActive && streamUrl == null) {
            val started = currentTimeMillis()
            val lookup = runCatching { WindowsAnimatedArtworkRepository.find(track) }
            val found = lookup.getOrNull()
            AppDiagnostics.log(
                "video",
                "artwork \"${track.title}\": lookup ${currentTimeMillis() - started} ms, " +
                    (found?.let { "found ${it.streamUrl.substringBefore('?').takeLast(32)}" }
                        ?: lookup.exceptionOrNull()?.let { "failed: ${it.message?.take(100)}" }
                        ?: if (WindowsAnimatedArtworkRepository.isDefinitiveMiss(track)) "this song has none" else "not found yet"),
            )
            if (found != null) {
                // An iPhone decodes Apple's HEVC clip itself.
                streamUrl = found.streamUrl
                break
            }
            if (WindowsAnimatedArtworkRepository.isDefinitiveMiss(track) || waitMs > 12_000L) break
            delay(waitMs)
            waitMs = waitMs * 3 / 2
        }
    }
    val latestAspect by rememberUpdatedState(onAspectRatio)
    // The artwork stays square: the clip is cropped into the box.
    LaunchedEffect(Unit) { latestAspect(null) }
    DisposableEffect(track.id) { onDispose { latestAspect(null) } }
    val windowShown = MainWindowActivity.isShown
    var pictureShown by remember(track.id) { mutableStateOf(false) }
    Box(modifier.clip(shape)) {
        streamUrl?.let { url ->
            key(url) {
                val frames = remember(url) {
                    IosVideoFrames(url, userAgent = null, loop = true, maxSide = 720.0, label = "artwork \"${track.title}\"", loopMs = WindowsAnimatedArtworkRepository.clipDurationMs(url))
                }
                DisposableEffect(frames) { onDispose { frames.release() } }
                LaunchedEffect(frames, windowShown) { if (windowShown) frames.play() else frames.pause() }
                LaunchedEffect(frames.pictureShown) { pictureShown = frames.pictureShown }
                // A clip that fails (or shows nothing): the H.264 one, then the cover.
                LaunchedEffect(frames) {
                    var waited = 0L
                    while (isActive && !frames.pictureShown && !frames.failed && waited < 10_000L) {
                        delay(250)
                        waited += 250
                    }
                    if (frames.pictureShown) return@LaunchedEffect
                    pictureShown = false
                    AppDiagnostics.log("video", "artwork \"${track.title}\": nothing shown from this clip, trying another")
                    val alternate = WindowsAnimatedArtworkRepository.alternateStreamUrl(url)
                    if (alternate != null && alternate != url) streamUrl = alternate
                    else {
                        // The next lookup picks another of the song's clips (the vertical one).
                        WindowsAnimatedArtworkRepository.markFailedStreamUrl(url)
                        streamUrl = null
                        if (attempt < 2) attempt++
                    }
                }
                VideoPicture(frames, ContentScale.Crop, Modifier.fillMaxSize())
            }
        }
        CoverOverVideo(coverUrl, pictureShown, shape)
    }
}

private object IosMusicVideoSource {
    val repository by lazy { WindowsMusicRepository() }
}

/** Picture height asked for (as on Android: sharp in the artwork's box, light on the phone). */
private const val MUSIC_VIDEO_HEIGHT = 480

/** The player page's music video, shown at once by the full screen view (while its sharper one loads). */
internal object IosMusicVideoShare {
    var frames: Pair<String, IosVideoFrames>? by androidx.compose.runtime.mutableStateOf(null)
}

/** The player page's video picture for [trackId] (full screen, under its own sharper one). */
@Composable
internal fun SharedMusicVideoPicture(trackId: String, modifier: Modifier) {
    val shared = IosMusicVideoShare.frames?.takeIf { it.first == trackId }?.second ?: return
    VideoPicture(shared, ContentScale.Fit, modifier)
}
/** Full screen: a sharper picture. */
private const val MUSIC_VIDEO_FULLSCREEN_HEIGHT = 720
/** The picture jumps to the sound only when further apart than this (else it catches up). */
private const val MUSIC_VIDEO_MAX_DRIFT_MS = 2_500L

/**
 * A music video in the artwork's place (video mode): muted, kept in step with the song
 * the player plays (its sound is the video's own).
 */
@Composable
internal fun IosMusicVideo(
    track: Track,
    coverUrl: String?,
    modifier: Modifier,
    shape: RoundedCornerShape,
    scale: ContentScale,
    onAspectRatio: (Float?) -> Unit,
    /** The round full-screen button at the bottom right (the player page's video). */
    showFullscreenButton: Boolean = false,
    /** This is the full-screen view's own video (sharper; see IosMusicVideoFullscreen). */
    fullscreen: Boolean = false,
) {
    val pictureHeight = if (fullscreen) MUSIC_VIDEO_FULLSCREEN_HEIGHT else MUSIC_VIDEO_HEIGHT
    // Where the picture comes from, tried in turn until one shows: 0 = the picture
    // stream (as on Android), then YouTube's own iPhone streams (HLS, then a plain MP4).
    var stage by remember(track.id) { mutableStateOf(0) }
    var source by remember(track.id) { mutableStateOf<Pair<String, String?>?>(null) }
    var iphoneStreams by remember(track.id) { mutableStateOf<List<Pair<String, String>>?>(null) }
    var gaveUp by remember(track.id) { mutableStateOf(false) }
    fun stageName(at: Int) = when (at) {
        0 -> "picture stream"
        1 -> "iPhone stream 1"
        else -> "iPhone stream $at"
    }
    LaunchedEffect(track.id, stage) {
        source = null
        if (stage == 0) {
            var waitMs = 1_500L
            while (isActive && waitMs <= 3_000L) {
                val found = runCatching { IosMusicVideoSource.repository.musicVideoStreamUrl(track.id, pictureHeight) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }
                if (found != null) {
                    AppDiagnostics.log("video", "music video \"${track.title}\": picture stream found")
                    source = found to IosStreamHeaders.userAgentFor(found)
                    return@LaunchedEffect
                }
                delay(waitMs)
                waitMs *= 2
            }
            AppDiagnostics.log("video", "music video \"${track.title}\": no picture stream, trying the iPhone streams")
            stage = 1
            return@LaunchedEffect
        }
        val streams = iphoneStreams
            ?: runCatching { IosMusicVideoSource.repository.musicVideoIosStreams(track.id) }.getOrDefault(emptyList())
                .also {
                    iphoneStreams = it
                    AppDiagnostics.log("video", "music video \"${track.title}\": ${it.size} iPhone stream(s)")
                }
        val next = streams.getOrNull(stage - 1)
        if (next == null) {
            AppDiagnostics.log("video", "music video \"${track.title}\": nothing plays, showing the cover")
            gaveUp = true
            return@LaunchedEffect
        }
        source = next.first to next.second
    }
    val latestAspect by rememberUpdatedState(onAspectRatio)
    DisposableEffect(track.id) { onDispose { latestAspect(null) } }
    var pictureShown by remember(track.id) { mutableStateOf(false) }
    Box(modifier.clip(shape)) {
        source?.let { (url, agent) ->
            key(url) {
                val frames = remember(url) { IosVideoFrames(url, agent, loop = false, maxSide = 1280.0, label = "music video (${stageName(stage)}) \"${track.title}\"") }
                DisposableEffect(frames) { onDispose { frames.release() } }
                LaunchedEffect(frames.pictureShown) {
                    pictureShown = frames.pictureShown
                    // The player page's picture: the full screen view starts with it at once.
                    if (!fullscreen && frames.pictureShown) IosMusicVideoShare.frames = track.id to frames
                }
                DisposableEffect(frames) {
                    onDispose { if (IosMusicVideoShare.frames?.second === frames) IosMusicVideoShare.frames = null }
                }
                LaunchedEffect(frames.aspect) { frames.aspect?.let { latestAspect((it * 100f).toInt() / 100f) } }
                // Kept in step with the song: play and pause with it, jump to its place when
                // far apart (start, a seek), otherwise run a little faster or slower.
                LaunchedEffect(frames) {
                    val engine = createPlaybackEngine()
                    var waited = 0L
                    var started = false
                    var lastSeekAt = 0L
                    while (isActive) {
                        // The page's video goes on under the full screen view (which starts with its picture).
                        val playing = engine.isPlaying && engine.currentTrack?.id == track.id && MainWindowActivity.shownNow
                        val audioMs = engine.positionMs
                        // Seeks only once it is loaded, and not again before the last one has
                        // had time (a seek every moment while it loaded kept it from playing).
                        if (!started && frames.ready) {
                            frames.seekTo(audioMs)
                            lastSeekAt = currentTimeMillis()
                            started = true
                        }
                        if (playing) {
                            val drift = frames.positionMs - audioMs
                            var speed = 1f
                            if (abs(drift) > MUSIC_VIDEO_MAX_DRIFT_MS) {
                                if (started && currentTimeMillis() - lastSeekAt > 3_000L) {
                                    frames.seekTo(audioMs + 300L)
                                    lastSeekAt = currentTimeMillis()
                                }
                            }
                            else if (abs(drift) > 80L) speed = (1f - drift / 1000f * 0.5f).coerceIn(0.85f, 1.15f)
                            if (abs(frames.rate - speed) > 0.01f) frames.rate = speed
                        } else if (frames.rate != 0f) {
                            frames.pause()
                        }
                        // Nothing shown after a while of playing (or it failed): the next source.
                        if (!frames.pictureShown) {
                            if (playing) waited += 250
                            if (frames.failed || waited > 8_000L) {
                                AppDiagnostics.log("video", "music video \"${track.title}\": nothing shown from the ${stageName(stage)}")
                                stage++
                                return@LaunchedEffect
                            }
                        }
                        delay(250)
                    }
                }
                if (!gaveUp) VideoPicture(frames, scale, Modifier.fillMaxSize())
            }
        }
        // Full screen: no cover or loading sign (the player page's picture shows under it).
        if (!fullscreen) CoverOverVideo(coverUrl, pictureShown && !gaveUp, shape)
        if (showFullscreenButton && !fullscreen && pictureShown && !gaveUp) {
            LiquidGlassCircleButton(
                icon = androidx.compose.material.icons.Icons.Default.Fullscreen,
                contentDescription = "Full screen video",
                onClick = { MusicVideoFullscreen.open(track.id, null, MusicVideoFullscreen.videoBoxBounds) },
                modifier = Modifier.align(androidx.compose.ui.Alignment.BottomEnd).padding(10.dp),
                size = 38.dp,
                iconSize = 24.dp,
                backdrop = null,
            )
        }
    }
}

/** iPhone: the song's artwork, animated artwork or music video (see AnimatedTrackArtwork). */
@Composable
internal fun IosTrackArtwork(
    track: Track,
    modifier: Modifier,
    shape: RoundedCornerShape,
    enabled: Boolean,
    musicVideoScale: ContentScale,
    onMusicVideoAspectRatio: (Float?) -> Unit,
    showFullscreenButton: Boolean = false,
) {
    val firstArtist = remember(track.artist) {
        track.artist.split(", ", " & ", " x ", " feat. ", " ft. ").firstOrNull()?.trim().orEmpty()
    }
    val coverUrl = remember(track.artworkUrl) { track.artworkUrl?.highQualityArtworkUrl() ?: track.artworkUrl }
    if (track.hasMusicVideo) {
        IosMusicVideo(track, coverUrl, modifier, shape, musicVideoScale, onMusicVideoAspectRatio, showFullscreenButton)
        return
    }
    if (!enabled || track.localPath != null || track.title.isBlank() || firstArtist.isBlank()) {
        Artwork(coverUrl, modifier, shape)
        return
    }
    IosAnimatedArtwork(track, coverUrl, modifier, shape, musicVideoScale, onMusicVideoAspectRatio)
}
