package com.recordlabs.desktop
import kotlinx.coroutines.isActive
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import com.sun.jna.NativeLibrary
import java.io.File
import java.nio.ByteBuffer
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import kotlin.math.roundToInt
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asComposeImageBitmap
import androidx.compose.ui.graphics.asSkiaBitmap
import androidx.compose.runtime.SideEffect
import kotlinx.coroutines.flow.first
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.material.Icon
import androidx.compose.material.Text
import com.recordlabs.desktop.data.textAt
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import coil3.compose.AsyncImage
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsAnimatedArtworkRepository
import com.recordlabs.desktop.data.WindowsLocalMusicLibrary
import com.recordlabs.desktop.data.WindowsOfflineArtworkCache
import com.recordlabs.desktop.data.WindowsStorage
import com.recordlabs.desktop.data.hasMusicVideo
import javafx.animation.KeyFrame
import javafx.animation.Timeline
import javafx.application.Platform
import javafx.scene.SnapshotParameters
import javafx.scene.Scene
import javafx.stage.Stage
import javafx.stage.StageStyle
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaView
import javafx.util.Duration
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.jetbrains.skia.Bitmap

private val javaFxMediaNativesLoaded = AtomicBoolean(false)
private val javaFxToolkitStarted = AtomicBoolean(false)

/**
 * Resolve each remote artwork video to an untouched local MP4 before decoding.
 * JavaFX's Windows network media path stalls unpredictably; local playback is
 * both smoother and keeps the static cover visible during preparation.
 */
internal object AnimatedArtworkDiskCache {
    private val directory = File(WindowsStorage.directory, "animated-artwork").apply { mkdirs() }
    private val waiters = ConcurrentHashMap<String, Pair<MutableList<(String?) -> Unit>, okhttp3.Call>>()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .build()

    fun localUri(remoteUrl: String): String? = cacheFile(remoteUrl)
        .takeIf { it.isFile && it.length() > 16_384L }
        ?.absolutePath

    fun evict(remoteUrl: String) {
        val destination = cacheFile(remoteUrl)
        destination.delete()
        File(destination.parentFile, destination.name + ".part").delete()
    }

    fun prefetch(remoteUrl: String) {
        if (localUri(remoteUrl) != null) return
        resolve(remoteUrl, retries = 2) { /* background download into cache */ }
    }

    fun resolve(remoteUrl: String, retries: Int = 3, onResolved: (String?) -> Unit): AutoCloseable {
        localUri(remoteUrl)?.let {
            onResolved(it)
            return AutoCloseable {}
        }
        val call: okhttp3.Call
        synchronized(waiters) {
            localUri(remoteUrl)?.let {
                onResolved(it)
                return AutoCloseable {}
            }
            val pending = waiters[remoteUrl]
            if (pending != null) {
                pending.first.add(onResolved)
                call = pending.second
            } else {
                call = client.newCall(Request.Builder().url(remoteUrl).build())
                waiters[remoteUrl] = Pair(mutableListOf(onResolved), call)
                
                val destination = cacheFile(remoteUrl)
                val partial = File(destination.parentFile, destination.name + ".part")
                call.enqueue(object : Callback {
                    override fun onFailure(call: Call, error: java.io.IOException) {
                        partial.delete()
                        if (!call.isCanceled() && retries > 0) {
                            val callbacks = synchronized(waiters) {
                                if (waiters[remoteUrl]?.second == call) waiters.remove(remoteUrl)?.first else null
                            }
                            if (callbacks != null) {
                                callbacks.forEach { resolve(remoteUrl, retries - 1, it) }
                                return
                            }
                        }
                        finishResolve(remoteUrl, null)
                    }

                    override fun onResponse(call: Call, response: Response) {
                        response.use { result ->
                            val local = runCatching {
                                if (!result.isSuccessful) error("HTTP " + result.code)
                                val body = result.body ?: error("Empty artwork response")
                                partial.outputStream().buffered().use { output ->
                                    body.byteStream().use { input -> input.copyTo(output, 128 * 1024) }
                                }
                                if (partial.length() <= 16_384L) error("Artwork video is incomplete")
                                partial.renameTo(destination)
                                destination.absolutePath
                            }.onFailure { e ->
                                if (e !is java.io.IOException || (e.message?.contains("CANCEL") != true && e.message?.contains("Socket closed") != true && e.message?.contains("Canceled") != true)) {
                                    e.printStackTrace()
                                }
                                partial.delete()
                            }.getOrNull()
                            
                            if (local == null && !call.isCanceled() && retries > 0) {
                                val callbacks = synchronized(waiters) {
                                    if (waiters[remoteUrl]?.second == call) waiters.remove(remoteUrl)?.first else null
                                }
                                if (callbacks != null) {
                                    callbacks.forEach { resolve(remoteUrl, retries - 1, it) }
                                    return
                                }
                            }
                            finishResolve(remoteUrl, local)
                        }
                    }
                })
            }
        }
        
        return AutoCloseable {
            synchronized(waiters) {
                val pending = waiters[remoteUrl]
                if (pending != null) {
                    pending.first.remove(onResolved)
                    if (pending.first.isEmpty()) {
                        waiters.remove(remoteUrl)
                        pending.second.cancel()
                        File(cacheFile(remoteUrl).parentFile, cacheFile(remoteUrl).name + ".part").delete()
                    }
                }
            }
        }
    }

    private fun finishResolve(remoteUrl: String, localUri: String?) {
        val callbacks = synchronized(waiters) { waiters.remove(remoteUrl)?.first }
        callbacks?.forEach { it(localUri) }
    }

    private fun cacheFile(url: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(directory, digest + ".mp4")
    }
}

/**
 * Watches the app's memory while animated artwork / music videos play. Past
 * [LIMIT_BYTES] of committed memory they stop (the static cover shows) instead
 * of the whole system running out of memory. Samples are written to
 * record-labs-memory.log in the working folder, to see what grew.
 */
internal object ArtworkMemoryGuard {
    private const val LIMIT_BYTES = 5L * 1024 * 1024 * 1024
    var tripped by mutableStateOf(false)
        private set
    val activeDecoders = java.util.concurrent.atomic.AtomicInteger(0)
    private val started = AtomicBoolean(false)

    fun ensureStarted() {
        if (!started.compareAndSet(false, true)) return
        Thread({
            val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean() as? com.sun.management.OperatingSystemMXBean
            val log = runCatching { File(System.getProperty("user.dir"), "record-labs-memory.log") }.getOrNull()
            runCatching { log?.writeText("") }
            var last = 0L
            while (true) {
                Thread.sleep(1_000)
                // Windows: the process's committed (private) memory. Linux / macOS
                // report the whole address space there, so the resident size instead.
                val committed = runCatching {
                    if (System.getProperty("os.name").orEmpty().startsWith("Windows")) os?.committedVirtualMemorySize ?: 0L
                    else File("/proc/self/status").takeIf { it.exists() }?.readLines()
                        ?.firstOrNull { it.startsWith("VmRSS:") }
                        ?.filter(Char::isDigit)?.toLongOrNull()?.times(1024) ?: 0L
                }.getOrDefault(0L)
                val heap = Runtime.getRuntime().let { it.totalMemory() - it.freeMemory() }
                val line = "${java.time.LocalTime.now()} committed=${committed / 1_048_576}MB heap=${heap / 1_048_576}MB decoders=${activeDecoders.get()} tripped=$tripped"
                if (kotlin.math.abs(committed - last) > 50L * 1_048_576 || tripped) runCatching { log?.appendText(line + "\n") }
                last = committed
                if (!tripped && committed > LIMIT_BYTES) {
                    logArtwork("Memory guard: $line - animated artwork and videos stopped")
                    runCatching { log?.appendText("$line  -> STOPPED animated artwork / videos\n") }
                    javax.swing.SwingUtilities.invokeLater { tripped = true }
                }
            }
        }, "record-labs-memory-guard").apply { isDaemon = true }.start()
    }
}

private fun logArtwork(message: String) {
    runCatching {
        val logFile = File(WindowsStorage.directory, "artwork.log")
        logFile.appendText("${java.time.Instant.now()}: $message\n")
    }
}

fun ensureJavaFxMediaNatives() {
    if (!javaFxMediaNativesLoaded.compareAndSet(false, true)) return
    try {
        val resourceDir = System.getProperty("compose.application.resources.dir")
        val isWindows = com.recordlabs.desktop.data.Platform.isWindows
        val platformVlcDirName = if (isWindows) "windows-x64/vlc" else "linux-x64/vlc"

        val candidates = buildList {
            if (!resourceDir.isNullOrBlank()) {
                add(File(resourceDir, "vlc"))
                add(File(resourceDir))
            }
            System.getProperty("java.home")?.let { javaHome ->
                val runtimeDir = File(javaHome)
                runtimeDir.parentFile?.let { installRoot ->
                    add(File(installRoot, "app/resources/vlc"))
                    add(File(installRoot, "app/resources"))
                }
            }
            add(File(System.getProperty("user.dir"), "src/appResources/$platformVlcDirName"))
            add(File("src/appResources/$platformVlcDirName"))
            if (isWindows) {
                add(File(System.getProperty("user.dir"), "src/main/resources/vlc"))
                add(File("src/main/resources/vlc"))
                // Last resort: a 64-bit VLC installed on the PC (when the app was built
                // without its own copy of VLC, pictures and music videos still play).
                System.getenv("ProgramFiles")?.let { add(File(it, "VideoLAN/VLC")) }
                add(File("C:/Program Files/VideoLAN/VLC"))
            }
        }

        val vlcDir = candidates.map(File::getAbsoluteFile).firstOrNull { dir ->
            if (!dir.isDirectory) return@firstOrNull false
            if (isWindows) {
                File(dir, "libvlc.dll").isFile
            } else {
                File(dir, "libvlc.so.5").isFile || File(dir, "libvlc.so").isFile
            }
        }

        if (vlcDir != null) {
            NativeLibrary.addSearchPath("libvlc", vlcDir.absolutePath)
            NativeLibrary.addSearchPath("libvlccore", vlcDir.absolutePath)
            val pluginsDir = File(vlcDir, "plugins").takeIf { it.isDirectory } ?: vlcDir
            System.setProperty("VLC_PLUGIN_PATH", pluginsDir.absolutePath)
            System.setProperty("jna.library.path", vlcDir.absolutePath)
            System.setProperty("jna.load_library_altered_search_path", "true")
            logArtwork("VLC natives configured from: ${vlcDir.absolutePath}")
        } else {
            if (!isWindows) {
                val systemLibDir = sequenceOf("/usr/lib64", "/usr/lib/x86_64-linux-gnu", "/usr/lib")
                    .map(::File).firstOrNull { File(it, "libvlc.so.5").isFile || File(it, "libvlc.so").isFile }
                if (systemLibDir != null) {
                    NativeLibrary.addSearchPath("libvlc", systemLibDir.absolutePath)
                    NativeLibrary.addSearchPath("libvlccore", systemLibDir.absolutePath)
                }
                val systemPluginDir = sequenceOf("/usr/lib64/vlc/plugins", "/usr/lib/x86_64-linux-gnu/vlc/plugins", "/usr/lib/vlc/plugins")
                    .map(::File).firstOrNull { it.isDirectory }
                if (systemPluginDir != null && System.getProperty("VLC_PLUGIN_PATH").isNullOrBlank()) {
                    System.setProperty("VLC_PLUGIN_PATH", systemPluginDir.absolutePath)
                }
                logArtwork("System Linux VLC configured from: ${systemLibDir?.absolutePath}, plugins: ${systemPluginDir?.absolutePath}")
            } else {
                logArtwork("VLC natives NOT found! Candidates: ${candidates.joinToString { it.absolutePath }}")
            }
        }
    } catch (e: Throwable) {
        logArtwork("ensureJavaFxMediaNatives failed: ${e.stackTraceToString()}")
    }
}
/** The main window was minimized (false) or is on screen again (true). */
fun setAnimatedArtworkAppVisible(visible: Boolean) {
    MainWindowActivity.update(visible)
}


@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun DesktopAnimatedTrackArtwork(
    track: Track,
    modifier: Modifier,
    shape: RoundedCornerShape,
    enabled: Boolean = true,
    /** How a music video fills this box: Fit shows the whole 16:9 picture. */
    musicVideoScale: ContentScale = ContentScale.Fit,
    /**
     * Width/height of the music video while it is shown (null otherwise), so
     * the caller can give the box the video's own shape instead of a square.
     */
    onMusicVideoAspectRatio: (Float?) -> Unit = {},
    /** Offer a full-screen button (bottom right, on hover) while a music video plays. */
    showFullscreenButton: Boolean = false,
) {
    var streamUrl by remember(track.id, enabled) { mutableStateOf<String?>(null) }
    // Picture height [streamUrl] was fetched for (music videos only, 0 = not known).
    var streamHeight by remember(track.id, enabled) { mutableStateOf(0) }
    // Quality choice [streamUrl] was fetched for (null = Auto).
    var streamQuality by remember(track.id, enabled) { mutableStateOf<Int?>(null) }
    // Last picture of the previous stream, kept on screen while the music video
    // switches quality instead of falling back to the cover.
    val swapFrame = remember(track.id) { arrayOfNulls<ImageBitmap>(1) }
    var decoderEdge by remember(track.id) { mutableStateOf(540) }
    var retryTrigger by remember(track.id, enabled) { mutableStateOf(0) }
    // Music videos replace the cover with the video itself (muted, synced to the
    // audio). Every other track keeps the animated-artwork lookup below.
    val showsMusicVideo = enabled && track.hasMusicVideo
    // Memory running out: no decoders at all (the cover stays).
    if (ArtworkMemoryGuard.tripped && streamUrl != null) streamUrl = null

    LaunchedEffect(track.id, track.title, track.artist, track.album, track.streams, enabled, retryTrigger, ArtworkMemoryGuard.tripped) {
        if (!enabled || track.localPath != null || ArtworkMemoryGuard.tripped) {
            streamUrl = null
            logArtwork("AnimatedTrackArtwork skipped: enabled=$enabled, localPath=${track.localPath}")
            return@LaunchedEffect
        }
        if (showsMusicVideo) {
            // A few attempts only: a video without a usable picture stream simply
            // keeps its cover, exactly like a song without animated artwork.
            if (retryTrigger > 0) WindowsMusicVideoSource.invalidate(track.id)
            if (retryTrigger > 2) {
                // No picture after all: a full-screen view waiting for it closes.
                if (MusicVideoFullscreen.trackId == track.id) MusicVideoFullscreen.close()
                return@LaunchedEffect
            }
            var delayMs = 1_500L
            while (isActive && streamUrl == null) {
                val quality = MusicVideoFullscreen.quality
                val height = quality ?: musicVideoHeightFor(decoderEdge)
                val url = WindowsMusicVideoSource.streamUrl(track.id, height)
                if (url != null) {
                    logArtwork("Music video stream for ${track.id}: $url")
                    streamHeight = height
                    streamQuality = quality
                    streamUrl = url
                    break
                }
                if (delayMs > 6_000L) {
                    // No picture after all: a full-screen view waiting for it closes.
                    if (MusicVideoFullscreen.trackId == track.id) MusicVideoFullscreen.close()
                    break
                }
                kotlinx.coroutines.delay(delayMs)
                delayMs *= 2
            }
            return@LaunchedEffect
        }
        logArtwork("AnimatedTrackArtwork searching for: ${track.title} - ${track.artist}")
        // A decoder that keeps failing must not be restarted without end: every
        // attempt opens a new VLC instance (memory), so a few tries, spaced out,
        // then the cover simply stays (like a song without animated artwork).
        if (retryTrigger > 3) {
            logArtwork("AnimatedTrackArtwork gave up after $retryTrigger failed attempts: ${track.title}")
            return@LaunchedEffect
        }
        if (retryTrigger > 0) kotlinx.coroutines.delay(2_000L * retryTrigger)

        // When enabled changes or retry is requested, clear definitive miss to allow fresh lookups
        if (retryTrigger > 0) {
            WindowsAnimatedArtworkRepository.invalidate(track)
        } else {
            WindowsAnimatedArtworkRepository.clearDefinitiveMiss(track)
        }

        var retryDelayMs = 2_500L
        while (isActive && streamUrl == null) {
            val artwork = WindowsAnimatedArtworkRepository.find(track)
            if (artwork != null) {
                logArtwork("AnimatedTrackArtwork found artwork streamUrl: ${artwork.streamUrl}")
                streamUrl = artwork.streamUrl
                break
            }

            // If the server definitively answered 404 / metadata mismatch, stop automatic polling
            if (WindowsAnimatedArtworkRepository.isDefinitiveMiss(track)) {
                logArtwork("AnimatedTrackArtwork definitive miss for: ${track.title}")
                break
            }

            // Temporary network failure: wait with backoff, then retry while track is playing
            kotlinx.coroutines.delay(retryDelayMs)
            retryDelayMs = (retryDelayMs * 1.5f).toLong().coerceAtMost(12_000L)
        }
    }

    // Latest decoded animated frame. Reset whenever the video source changes,
    // so a new track never starts with the previous track's frame.
    var animatedFrame by remember(streamUrl) {
        mutableStateOf<ImageBitmap?>(swapFrame[0].also { swapFrame[0] = null }, androidx.compose.runtime.neverEqualPolicy())
    }
    // The quality picked in the full-screen video: fetch that stream and hand
    // over to it, keeping the current picture until the new one runs.
    val selectedQuality = MusicVideoFullscreen.quality
    LaunchedEffect(track.id, enabled, selectedQuality, streamUrl == null) {
        if (!showsMusicVideo || track.localPath != null) return@LaunchedEffect
        // Also runs when a stream arrives, in case the choice changed while it was fetched.
        val current = streamUrl ?: return@LaunchedEffect
        if (selectedQuality == streamQuality) return@LaunchedEffect
        val wanted = selectedQuality ?: musicVideoHeightFor(decoderEdge)
        if (wanted == streamHeight) {
            streamQuality = selectedQuality
            return@LaunchedEffect
        }
        val url = WindowsMusicVideoSource.streamUrl(track.id, wanted)
        if (url == null) {
            logArtwork("Music video quality ${wanted}p unavailable for ${track.id}")
            return@LaunchedEffect
        }
        if (streamUrl != current || url == current) return@LaunchedEffect
        logArtwork("Music video quality ${wanted}p for ${track.id}: $url")
        swapFrame[0] = animatedFrame
        streamHeight = wanted
        streamQuality = selectedQuality
        streamUrl = url
    }
    val animatedFrameReady = streamUrl != null && animatedFrame != null
    val musicVideoAspect = animatedFrame?.takeIf { showsMusicVideo && animatedFrameReady && it.height > 0 }
        ?.let { frame -> (frame.width.toFloat() / frame.height * 100f).roundToInt() / 100f }
    val latestAspectCallback by rememberUpdatedState(onMusicVideoAspectRatio)
    LaunchedEffect(musicVideoAspect) { latestAspectCallback(musicVideoAspect) }
    // 0 = static cover only, 1 = animated artwork only. Keyed by track so a new
    // song always starts on its sharp static cover instead of fading in from black.
    val reveal = remember(track.id) { Animatable(0f) }
    // The last animated frame shown, so switching animated artwork off can
    // hand back to the cover through the same blur (in reverse) instead of
    // the picture popping to the cover.
    val heldFrame = remember(track.id) { arrayOfNulls<ImageBitmap>(1) }
    var switchingOff by remember(track.id) { mutableStateOf(false) }
    if (!enabled && streamUrl == null && heldFrame[0] != null && reveal.value > 0f && !switchingOff) {
        // Set during this very frame, so the last picture never blinks away.
        switchingOff = true
    }
    LaunchedEffect(track.id, animatedFrameReady) {
        if (animatedFrameReady) {
            switchingOff = false
            // Linear time base; the blur and fade curves below shape it.
            reveal.animateTo(1f, tween(ARTWORK_REVEAL_DURATION_MS, easing = LinearEasing))
        } else if (!enabled && heldFrame[0] != null && reveal.value > 0f) {
            // Switched off: the cover comes back, sharpening as it fades in.
            switchingOff = true
            reveal.animateTo(0f, tween((ARTWORK_REVEAL_DURATION_MS * reveal.value).toInt().coerceAtLeast(1), easing = LinearEasing))
            switchingOff = false
            heldFrame[0] = null
        } else {
            // Video unavailable or failed: show the cover at once.
            switchingOff = false
            reveal.snapTo(0f)
        }
    }

    val artworkModel = remember(track.id, track.artworkUrl, track.localPath) {
        WindowsLocalMusicLibrary.resolveArtworkModel(track)
    }
    // A new song: what the previous one showed stays on top and fades out once
    // the new cover is ready, instead of the picture switching abruptly.
    val lastShown = remember { ShownArtwork() }
    var outgoing by remember(track.id) { mutableStateOf(lastShown.takeOver(track.id, artworkModel)) }
    // The same cover as the previous song (same album) is shown at once.
    var newCoverReady by remember(track.id) {
        mutableStateOf(artworkModel == null || (lastShown.cover != null && lastShown.coverModel == artworkModel))
    }
    val outgoingAlpha = remember(track.id) { Animatable(1f) }
    LaunchedEffect(track.id) {
        if (outgoing == null) return@LaunchedEffect
        kotlinx.coroutines.withTimeoutOrNull(ARTWORK_CHANGE_WAIT_MS) {
            androidx.compose.runtime.snapshotFlow { newCoverReady }.first { it }
        }
        outgoingAlpha.animateTo(0f, tween(ARTWORK_CHANGE_FADE_MS, easing = FastOutSlowInEasing))
        // Done: the copy of the previous picture is let go.
        outgoing = null
    }
    val currentFrame = { animatedFrame }
    SideEffect {
        lastShown.trackId = track.id
        lastShown.model = artworkModel
        lastShown.isMusicVideo = showsMusicVideo
        lastShown.visibleFrame = { currentFrame().takeIf { streamUrl != null && reveal.value > 0.5f } }
    }

    var hovered by remember { mutableStateOf(false) }
    // Where the box is on screen, so full screen can grow out of it.
    val boxBounds = remember { arrayOfNulls<androidx.compose.ui.geometry.Rect>(1) }
    Box(
        modifier
            .then(if (showFullscreenButton) Modifier.androidxOnGloballyPositioned { bounds ->
                boxBounds[0] = bounds
                if (showsMusicVideo && MusicVideoFullscreen.videoBoxBounds != bounds) MusicVideoFullscreen.videoBoxBounds = bounds
            } else Modifier)
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Enter) { hovered = true }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Exit) { hovered = false }
            .onSizeChanged { size ->
                // A music video's box takes the video's shape: its long side
                // is the edge the square used to have.
                val edgePx = if (showsMusicVideo) maxOf(size.width, size.height) else minOf(size.width, size.height)
                val requested = when (edgePx) {
                    in 0..419 -> 360
                    in 420..659 -> 540
                    else -> 720
                }
                if (requested != decoderEdge) decoderEdge = requested
            }
            .clip(shape)
            .background(Color.Black),
    ) {
        // Bottom layer: the animated artwork. It is drawn underneath the static
        // cover, so the hand-over is a reveal instead of a frame popping on top.
        streamUrl?.let { videoUrl ->
            DisposableEffect(videoUrl) {
                val decoder: ArtworkFrameSource = if (showsMusicVideo) {
                    // The stream may be a higher step than asked for (a remembered
                    // 1440p choice on a video that has 4K): size for that one.
                    val opened = com.recordlabs.desktop.data.MusicVideoStreamServer.streamFor(videoUrl)?.pictureQuality ?: 0
                    val height = if (opened > 1080) opened else streamHeight.takeIf { it > 0 } ?: musicVideoHeightFor(decoderEdge)
                    // Above 1080p the picture is decoded at most at the screen's
                    // own size: a 4K stream on a 2K screen is scaled down once
                    // instead of copying 4K frames that are shrunk anyway.
                    val (screenWidth, screenHeight) = if (height > 1080) largestScreenPixels() else (Int.MAX_VALUE to Int.MAX_VALUE)
                    VlcMusicVideoDecoder(
                        videoUrl,
                        track.id,
                        maxWidth = minOf(height * 16 / 9, maxOf(screenWidth, 1920)),
                        maxHeight = minOf(height, maxOf(screenHeight, 1080)),
                    )
                } else {
                    VlcArtworkFrameDecoder(videoUrl, decoderEdge)
                }
                decoder.onFrame = { frame ->
                    animatedFrame = frame
                    heldFrame[0] = frame
                    if (showsMusicVideo && MusicVideoFullscreen.trackId == track.id) MusicVideoFullscreen.frame = frame
                }
                decoder.onFailed = {
                    // A full-screen video without a picture any more closes.
                    if (showsMusicVideo && MusicVideoFullscreen.trackId == track.id) MusicVideoFullscreen.close()
                    if (!showsMusicVideo) {
                        AnimatedArtworkDiskCache.evict(videoUrl)
                        WindowsAnimatedArtworkRepository.markFailedStreamUrl(videoUrl)
                    }
                    streamUrl = null
                    retryTrigger++
                }
                onDispose {
                    decoder.release()
                }
            }
            animatedFrame?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    contentScale = if (showsMusicVideo) musicVideoScale else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        // Animated artwork just switched off: its last picture stays under the
        // cover while the cover un-blurs and fades back in.
        if (switchingOff && streamUrl == null) {
            heldFrame[0]?.let { frame ->
                Image(
                    bitmap = frame,
                    contentDescription = null,
                    contentScale = if (track.hasMusicVideo) musicVideoScale else ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        // Top layer: the static cover. Once the first animated frame is ready it
        // blurs and fades out together; it stays fully visible (and sharp) at
        // all other times, including the moment a new track starts.
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer {
                    val progress = reveal.value
                    alpha = 1f - artworkRevealFade(progress)
                    renderEffect = if (progress > 0f && progress < 1f) {
                        val radius = ARTWORK_REVEAL_MAX_BLUR.toPx() * artworkRevealBlur(progress)
                        BlurEffect(radius, radius, TileMode.Clamp)
                    } else null
                },
        ) {
            if (artworkModel != null) {
                AsyncImage(
                    model = artworkModel,
                    contentDescription = "Album artwork",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                    onSuccess = { state ->
                        lastShown.cover = state.painter
                        lastShown.coverModel = artworkModel
                        newCoverReady = true
                    },
                    onError = { newCoverReady = true },
                )
            } else {
                Box(Modifier.fillMaxSize().background(Color(0xFF1E1E1E)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Album, null, tint = Color.Gray, modifier = Modifier.fillMaxSize(0.4f))
                }
            }
        }
        // The previous song's picture, fading out over the new one.
        outgoing?.let { outgoing ->
            Box(Modifier.fillMaxSize().graphicsLayer { alpha = outgoingAlpha.value }.background(Color.Black)) {
                val frame = outgoing.frame
                if (frame != null) {
                    Image(
                        bitmap = frame,
                        contentDescription = null,
                        contentScale = if (outgoing.isMusicVideo) musicVideoScale else ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else if (outgoing.cover != null) {
                    Image(
                        painter = outgoing.cover,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
        if (showFullscreenButton && showsMusicVideo) {
            androidx.compose.animation.AnimatedVisibility(
                visible = hovered && animatedFrameReady,
                enter = androidx.compose.animation.fadeIn(tween(160)),
                exit = androidx.compose.animation.fadeOut(tween(200)),
                modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp),
            ) {
                Box(
                    Modifier
                        .size(38.dp)
                        .background(Color.Black.copy(alpha = .58f), androidx.compose.foundation.shape.CircleShape)
                        .clickable { MusicVideoFullscreen.open(track.id, animatedFrame, boxBounds[0]) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Fullscreen, "Full screen video", tint = Color.White, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/**
 * Full-window music video, opened from the artwork's full-screen button. It
 * puts the window into full screen for as long as it is open (and back when it
 * closes); leaving full screen (Esc, F11), a new song, or the button closes it.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
internal fun MusicVideoFullscreenOverlay(
    currentTrackId: String?,
    isWindowFullscreen: Boolean,
    onWindowFullscreen: (Boolean) -> Unit,
) {
    val trackId = MusicVideoFullscreen.trackId
    var enteredWindowFullscreen by remember { mutableStateOf(false) }
    var windowFullscreenSeen by remember { mutableStateOf(false) }
    LaunchedEffect(trackId) {
        if (trackId != null) {
            windowFullscreenSeen = isWindowFullscreen
            if (!isWindowFullscreen) {
                enteredWindowFullscreen = true
                onWindowFullscreen(true)
            }
        } else if (enteredWindowFullscreen) {
            enteredWindowFullscreen = false
            onWindowFullscreen(false)
        }
    }
    LaunchedEffect(isWindowFullscreen) {
        if (isWindowFullscreen) windowFullscreenSeen = true
        // Esc / F11 left full screen: the video goes with it.
        else if (trackId != null && windowFullscreenSeen) {
            enteredWindowFullscreen = false
            MusicVideoFullscreen.close()
        }
    }
    LaunchedEffect(currentTrackId, trackId) {
        if (trackId != null && currentTrackId != trackId) {
            // A new song (Previous / Next, or the queue moving on): stay full
            // screen when it has a music video too, otherwise close.
            val next = FloatingLyricsState.currentTrack
            if (currentTrackId != null && next?.id == currentTrackId && next.hasMusicVideo && next.localPath == null) {
                MusicVideoFullscreen.follow(currentTrackId)
            } else {
                MusicVideoFullscreen.close()
            }
        }
    }
    if (trackId == null) {
        MusicVideoFullscreen.exitFrame?.let { MusicVideoFullscreenExit(it) }
        return
    }
    // What this video offers: picture heights for the quality menu and the
    // uploader's caption tracks for the subtitle menu.
    var options by remember(trackId) { mutableStateOf<com.recordlabs.desktop.data.MusicVideoOptions?>(null) }
    LaunchedEffect(trackId) { options = WindowsMusicVideoSource.options(trackId) }
    val captionTrack = options?.captions.orEmpty().forCaptionLanguage(MusicVideoFullscreen.captionLanguage, MusicVideoFullscreen.captionName)
    var captionCues by remember(trackId) { mutableStateOf<List<com.recordlabs.desktop.data.MusicVideoCaptionCue>>(emptyList()) }
    LaunchedEffect(trackId, captionTrack) {
        captionCues = emptyList()
        if (captionTrack != null) captionCues = WindowsMusicVideoSource.captions(captionTrack)
    }
    // Opening grows the video out of where it was on the page to the whole
    // screen (the black around it fades in at the same time).
    val growth = remember(trackId) { androidx.compose.animation.core.Animatable(if (MusicVideoFullscreen.openedFrom != null) 0f else 1f) }
    LaunchedEffect(trackId) {
        growth.animateTo(1f, tween(FULLSCREEN_GROW_MS, easing = FastOutSlowInEasing))
    }
    var overlayBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    var controlsVisible by remember { mutableStateOf(true) }
    var lastMoveNanos by remember { mutableStateOf(System.nanoTime()) }
    // Seeking or the quality menu open, or the pointer resting on the bar:
    // the controls stay up.
    var controlsBusy by remember { mutableStateOf(false) }
    var pointerOnControls by remember { mutableStateOf(false) }
    LaunchedEffect(lastMoveNanos, controlsBusy, pointerOnControls) {
        controlsVisible = true
        if (controlsBusy || pointerOnControls) return@LaunchedEffect
        kotlinx.coroutines.delay(2_500L)
        controlsVisible = false
    }
    Box(
        Modifier
            .fillMaxSize()
            .androidxOnGloballyPositioned { overlayBounds = it }
            .drawBehind { drawRect(Color.Black.copy(alpha = growth.value.coerceIn(0f, 1f))) }
            .onPointerEvent(androidx.compose.ui.input.pointer.PointerEventType.Move) { lastMoveNanos = System.nanoTime() }
            // The mouse pointer hides together with the controls and comes back
            // as soon as it moves.
            .pointerHoverIcon(
                if (controlsVisible) androidx.compose.ui.input.pointer.PointerIcon.Default else HiddenPointerIcon,
                overrideDescendants = true,
            )
            // Keep clicks and scrolling away from the page underneath.
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
            ) { lastMoveNanos = System.nanoTime() },
        contentAlignment = Alignment.Center,
    ) {
        val frame = MusicVideoFullscreen.frame
        if (frame != null) {
            Image(
                bitmap = frame,
                contentDescription = "Music video",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    // From the page's video box to the fitted full-screen picture.
                    val from = MusicVideoFullscreen.openedFrom
                    val full = overlayBounds
                    val t = growth.value
                    // Its place is not known for the very first frame: not shown yet.
                    if (from != null && full == null && t < 1f) alpha = 0f
                    if (from != null && full != null && t < 1f && frame.width > 0 && frame.height > 0 && size.width > 0f) {
                        val fit = minOf(size.width / frame.width, size.height / frame.height)
                        val fittedWidth = frame.width * fit
                        val startScale = (from.width / fittedWidth).coerceIn(0.05f, 1f)
                        val scaleNow = startScale + (1f - startScale) * t
                        scaleX = scaleNow
                        scaleY = scaleNow
                        translationX = (from.center.x - full.center.x) * (1f - t)
                        translationY = (from.center.y - full.center.y) * (1f - t)
                    }
                },
            )
        } else {
            // The next song's video is still starting: show its cover meanwhile.
            val coverTrack = FloatingLyricsState.currentTrack?.takeIf { it.id == trackId }
            val coverModel = remember(coverTrack?.id, coverTrack?.artworkUrl) {
                coverTrack?.let { WindowsLocalMusicLibrary.resolveArtworkModel(it) }
            }
            if (coverModel != null) {
                AsyncImage(
                    model = coverModel,
                    contentDescription = "Album artwork",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize(0.6f),
                )
            }
        }
        if (captionTrack != null && captionCues.isNotEmpty()) {
            MusicVideoCaptionText(cues = captionCues, liftedAboveControls = controlsVisible)
        }
        androidx.compose.animation.AnimatedVisibility(
            visible = controlsVisible,
            enter = androidx.compose.animation.fadeIn(tween(160)),
            exit = androidx.compose.animation.fadeOut(tween(300)),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            androidx.compose.material.MaterialTheme(
                colors = androidx.compose.material.darkColors(
                    primary = MusicVideoFullscreen.accent,
                    secondary = MusicVideoFullscreen.accent,
                    surface = Color(0xFF1A1C20),
                    onPrimary = Color.Black,
                ),
            ) {
                MusicVideoFullscreenControls(
                    onExit = { MusicVideoFullscreen.close() },
                    onBusyChange = { controlsBusy = it },
                    onPointerOnControls = { pointerOnControls = it },
                    availableHeights = options?.heights.orEmpty(),
                    captionTracks = options?.captions.orEmpty(),
                    selectedCaption = captionTrack,
                    onSelectCaption = {
                        MusicVideoFullscreen.captionLanguage = it?.languageCode
                        MusicVideoFullscreen.captionName = it?.name
                    },
                )
            }
        }
    }
}

/**
 * The subtitle line of the full-screen video, at the bottom centre and moved
 * up while the controls show, the way YouTube places it.
 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.MusicVideoCaptionText(
    cues: List<com.recordlabs.desktop.data.MusicVideoCaptionCue>,
    liftedAboveControls: Boolean,
) {
    // Read here, so only the subtitle follows the playback position.
    val text = cues.textAt(FloatingLyricsState.positionMs)
    val bottom by androidx.compose.animation.core.animateDpAsState(if (liftedAboveControls) 150.dp else 56.dp, tween(250))
    androidx.compose.foundation.layout.BoxWithConstraints(
        Modifier.align(Alignment.BottomCenter).fillMaxSize().padding(start = 48.dp, end = 48.dp, bottom = bottom),
        contentAlignment = Alignment.BottomCenter,
    ) {
        if (text.isNullOrBlank()) return@BoxWithConstraints
        val fontSize = (maxHeight.value * 0.038f).coerceIn(18f, 46f)
        Text(
            text,
            color = Color.White,
            fontSize = androidx.compose.ui.unit.TextUnit(fontSize, androidx.compose.ui.unit.TextUnitType.Sp),
            lineHeight = androidx.compose.ui.unit.TextUnit(fontSize * 1.25f, androidx.compose.ui.unit.TextUnitType.Sp),
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            modifier = Modifier
                .background(Color.Black.copy(alpha = .62f), RoundedCornerShape(6.dp))
                .padding(horizontal = 14.dp, vertical = 6.dp),
        )
    }
}

/** Pixel size of the largest screen (4K when it cannot be read). */
private fun largestScreenPixels(): Pair<Int, Int> = runCatching {
    java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices
        .map { it.displayMode.width to it.displayMode.height }
        .filter { (width, height) -> width > 0 && height > 0 }
        .maxByOrNull { (width, height) -> width.toLong() * height }
}.getOrNull() ?: (3840 to 2160)



/** Leaving full screen: the last picture shrinks back into the page's video box while the black fades. */
@Composable
private fun MusicVideoFullscreenExit(frame: ImageBitmap) {
    val shrink = remember(frame) { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(frame) {
        shrink.animateTo(0f, tween(FULLSCREEN_SHRINK_MS, easing = FastOutSlowInEasing))
        MusicVideoFullscreen.exitDone()
    }
    var overlayBounds by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(
        Modifier
            .fillMaxSize()
            .androidxOnGloballyPositioned { overlayBounds = it }
            .drawBehind { drawRect(Color.Black.copy(alpha = shrink.value.coerceIn(0f, 1f))) },
    ) {
        Image(
            bitmap = frame,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val to = MusicVideoFullscreen.videoBoxBounds
                val full = overlayBounds
                val t = shrink.value
                if (to == null || full == null || frame.width <= 0 || frame.height <= 0 || size.width <= 0f) {
                    alpha = t
                } else {
                    val fit = minOf(size.width / frame.width, size.height / frame.height)
                    val endScale = (to.width / (frame.width * fit)).coerceIn(0.05f, 1f)
                    val scaleNow = endScale + (1f - endScale) * t
                    scaleX = scaleNow
                    scaleY = scaleNow
                    translationX = (to.center.x - full.center.x) * (1f - t)
                    translationY = (to.center.y - full.center.y) * (1f - t)
                    // Hands over to the live picture in the box at the very end.
                    alpha = (t / 0.12f).coerceIn(0f, 1f)
                }
            },
        )
    }
}

/** Duration of the full-screen video shrinking back into its box. */
private const val FULLSCREEN_SHRINK_MS = 380

/** Duration of the music video growing from its box to full screen. */
private const val FULLSCREEN_GROW_MS = 420

/** Bounds of this element in the window. */
private fun Modifier.androidxOnGloballyPositioned(onBounds: (androidx.compose.ui.geometry.Rect) -> Unit): Modifier =
    this.onGloballyPositioned { coordinates -> onBounds(coordinates.boundsInWindow()) }

/** Duration of the square <-> video-shape change of the artwork box. */

/** Picture height requested for a music video shown in a box of this decoder edge. */
private fun musicVideoHeightFor(edge: Int): Int = when {
    edge <= 360 -> 360
    edge <= 540 -> 480
    else -> 720
}

/** Duration of the static-to-animated artwork hand-over. */
private const val ARTWORK_REVEAL_DURATION_MS = 1_000
/** A new song: how long the previous picture waits for the new cover at most, then fades out. */
private const val ARTWORK_CHANGE_WAIT_MS = 900L
private const val ARTWORK_CHANGE_FADE_MS = 450

/** What the artwork showed last, so a new song can fade it out. */
private class ShownArtwork {
    var trackId: String? = null
    var isMusicVideo = false
    var visibleFrame: () -> ImageBitmap? = { null }
    /** The cover model of the song shown last. */
    var model: Any? = null
    var cover: androidx.compose.ui.graphics.painter.Painter? = null
    /** The model [cover] was loaded from. */
    var coverModel: Any? = null

    /**
     * The previous song's picture when [newTrackId] is a different song: a
     * copy of the animated frame it showed, or its cover (nothing when the
     * new song has the same cover).
     */
    fun takeOver(newTrackId: String, newModel: Any?): OutgoingArtwork? {
        val previous = trackId ?: return null
        if (previous == newTrackId) return null
        val frame = runCatching { visibleFrame() }.getOrNull()
            // Up to Full HD; a larger video picture fades out as its cover instead.
            ?.takeIf { it.width.toLong() * it.height <= 1920L * 1080L }
            ?.let { visible ->
                // The decoder reuses its frames and is released soon: keep a copy.
                runCatching { visible.asSkiaBitmap().makeClone().asComposeImageBitmap() }.getOrNull()
            }
        val previousCover = cover.takeIf { coverModel != null && coverModel == model }
        if (frame == null && (previousCover == null || newModel == model)) return null
        return OutgoingArtwork(frame, previousCover, isMusicVideo && frame != null)
    }
}

private class OutgoingArtwork(
    val frame: ImageBitmap?,
    val cover: androidx.compose.ui.graphics.painter.Painter?,
    val isMusicVideo: Boolean,
)

/**
 * Blur strength (0..1) of the static cover during the hand-over: eases in and
 * out over the first 60 %, so the cover softens gradually before it dissolves.
 */
private fun artworkRevealBlur(progress: Float): Float {
    val t = (progress / 0.6f).coerceIn(0f, 1f)
    return ((1.0 - kotlin.math.cos(Math.PI * t)) / 2.0).toFloat()
}

/**
 * Fade-out amount (0..1) of the static cover: starts once the blur is under
 * way (15 %) and follows an ease-in-out curve to the end.
 */
private fun artworkRevealFade(progress: Float): Float {
    val t = ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
    return if (t < 0.5f) 4f * t * t * t else 1f - (-2f * t + 2f).let { it * it * it } / 2f
}

/** Strongest blur applied to the static cover while it fades out. */
private val ARTWORK_REVEAL_MAX_BLUR = 22.dp

private class VlcArtworkFrameDecoder(private val url: String, private val edge: Int) : ArtworkFrameSource {
    private val released = AtomicBoolean(false)
    private val pendingUiFrame = AtomicBoolean(false)
    // Creating, starting and stopping VLC can block for a moment, so all of it
    // runs on this thread instead of the UI thread.
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "record-labs-animated-artwork").apply { isDaemon = true }
    }
    @Volatile private var downloadLease: AutoCloseable? = null
    @Volatile private var factory: MediaPlayerFactory? = null
    @Volatile private var mediaPlayer: EmbeddedMediaPlayer? = null
    // Render thread: the copy buffer and a few bitmaps, reused frame after frame
    // instead of allocating a new picture each time.
    private var frameBytes: ByteArray? = null
    private val framePool = arrayOfNulls<Bitmap>(ARTWORK_FRAME_POOL_SIZE)
    private val frameImages = arrayOfNulls<ImageBitmap>(ARTWORK_FRAME_POOL_SIZE)
    private var framePoolWidth = 0
    private var framePoolHeight = 0
    private var nextFrameSlot = 0
    override var onFrame: ((ImageBitmap) -> Unit)? = null
    override var onFailed: (() -> Unit)? = null
    // Paused while the main window is minimized; the music is not affected.
    private val visibilityListener: (Boolean) -> Unit = { shown ->
        runCatching { worker.execute { applyVisibility(shown) } }
    }

    init {
        ArtworkMemoryGuard.ensureStarted()
        ArtworkMemoryGuard.activeDecoders.incrementAndGet()
        MainWindowActivity.addListener(visibilityListener)
        worker.execute { resolve() }
    }

    /** Worker thread. */
    private fun applyVisibility(shown: Boolean) {
        if (released.get()) return
        runCatching { mediaPlayer?.controls()?.setPause(!shown) }
    }

    private fun resolve() {
        if (released.get()) return
        ensureJavaFxMediaNatives()
        // A file already on disk answers right away (inside this call); a
        // failure then was never reported before either, so it still is not.
        val resolvingThread = Thread.currentThread()
        downloadLease = AnimatedArtworkDiskCache.resolve(url, 3) { resolvedUrl ->
            if (released.get()) return@resolve
            if (resolvedUrl == null) {
                logArtwork("Animated artwork resolve failed (null) for url: $url")
                reportFailure()
                return@resolve
            }
            logArtwork("Animated artwork resolved to: $resolvedUrl")
            val reportFailures = Thread.currentThread() !== resolvingThread
            if (reportFailures) {
                runCatching { worker.execute { start(resolvedUrl, reportFailures = true) } }
            } else {
                start(resolvedUrl, reportFailures = false)
            }
        }
    }

    /** Worker thread. */
    private fun start(resolvedUrl: String, reportFailures: Boolean) {
        if (released.get()) return
        try {
            ensureJavaFxMediaNatives()
            val createdFactory = MediaPlayerFactory(
                "--intf=dummy",
                "--vout=dummy",
                "--no-audio",
                "--no-osd",
                "--no-video-title-show",
                "--no-avcodec-dr",
                "--drop-late-frames"
            )
            factory = createdFactory
            val mp = createdFactory.mediaPlayers().newEmbeddedMediaPlayer()
            mediaPlayer = mp

            var firstFrameLogged = false
            mp.videoSurface().set(createdFactory.videoSurfaces().newVideoSurface(object : BufferFormatCallback {
                override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                    if (sourceWidth <= 0 || sourceHeight <= 0) {
                        return RV32BufferFormat(1080, 1080)
                    }
                    // About the size it is shown at (the box is at most ~720 px): a
                    // 2160 x 2160 picture per frame was 18 MB that the graphics card
                    // had to take in 30 times a second.
                    val maxTarget = when {
                        edge <= 360 -> 540
                        edge <= 540 -> 720
                        else -> 1080
                    }
                    val longest = maxOf(sourceWidth, sourceHeight)
                    val scale = if (longest > maxTarget) maxTarget.toFloat() / longest else 1f
                    val targetWidth = (sourceWidth * scale).roundToInt().coerceAtLeast(1)
                    val targetHeight = (sourceHeight * scale).roundToInt().coerceAtLeast(1)
                    return RV32BufferFormat(targetWidth, targetHeight)
                }
                override fun allocatedBuffers(buffers: Array<out ByteBuffer>) {}
            }, object : RenderCallback {
                override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<out ByteBuffer>, bufferFormat: BufferFormat) {
                    if (released.get() || onFrame == null) return
                    if (!MainWindowActivity.shownNow) return
                    if (!firstFrameLogged) {
                        firstFrameLogged = true
                        logArtwork("VLC decoded first frame (${bufferFormat.width}x${bufferFormat.height}) for: $resolvedUrl")
                    }
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
                        nextFrameSlot = (slot + 1) % ARTWORK_FRAME_POOL_SIZE
                        val bitmap = framePool[slot] ?: Bitmap().also { framePool[slot] = it }
                        bitmap.installPixels(ImageInfo(width, height, ColorType.BGRA_8888, ColorAlphaType.PREMUL), bytes, width * 4)
                        val frame = frameImages[slot] ?: bitmap.asComposeImageBitmap().also { frameImages[slot] = it }

                        javax.swing.SwingUtilities.invokeLater {
                            if (!released.get()) {
                                onFrame?.invoke(frame)
                            }
                            pendingUiFrame.set(false)
                        }
                    } catch (e: Throwable) {
                        pendingUiFrame.set(false)
                    }
                }
            }, true))

            mp.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
                override fun playing(mediaPlayer: MediaPlayer) {
                    // A pause asked for while VLC was still opening is not kept.
                    if (!released.get() && !MainWindowActivity.shownNow) runCatching {
                        worker.execute { applyVisibility(MainWindowActivity.shownNow) }
                    }
                }

                override fun error(mediaPlayer: MediaPlayer) {
                    logArtwork("VLC artwork decoder error for: $resolvedUrl")
                    if (reportFailures) reportFailure()
                }
            })

            logArtwork("Starting VLC media play: $resolvedUrl")
            // VLC repeats the clip itself: starting it again on every "finished"
            // opened a new decoder each loop and the old one's memory was never
            // given back (the app grew by hundreds of MB per loop until the
            // system ran out of memory).
            mp.media().play(resolvedUrl, ":input-repeat=65535")
            // Started while the main window is minimized: wait until it is back.
            if (!MainWindowActivity.shownNow) runCatching { worker.execute { applyVisibility(MainWindowActivity.shownNow) } }
        } catch (e: Throwable) {
            e.printStackTrace()
            logArtwork("VlcArtworkFrameDecoder fatal error: ${e.javaClass.name}: ${e.message}\n${e.stackTraceToString()}")
            if (reportFailures) reportFailure()
        }
    }

    private fun reportFailure() {
        javax.swing.SwingUtilities.invokeLater { if (!released.get()) onFailed?.invoke() }
    }

    override fun release() {
        if (!released.compareAndSet(false, true)) return
        ArtworkMemoryGuard.activeDecoders.decrementAndGet()
        MainWindowActivity.removeListener(visibilityListener)
        onFrame = null
        runCatching {
            worker.execute {
                runCatching { downloadLease?.close() }
                runCatching { mediaPlayer?.controls()?.stop() }
                runCatching { mediaPlayer?.release() }
                runCatching { factory?.release() }
                downloadLease = null
                mediaPlayer = null
                factory = null
            }
        }
        worker.shutdown()
    }
}

/** Reused artwork bitmaps: one on screen, one handed over, one being written. */
private const val ARTWORK_FRAME_POOL_SIZE = 3
