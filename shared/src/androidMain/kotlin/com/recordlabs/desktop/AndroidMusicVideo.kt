package com.recordlabs.desktop

import android.net.Uri
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import coil3.compose.AsyncImage
import com.recordlabs.desktop.data.AndroidPlaybackEngine
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.WindowsMusicRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs

/** Music video picture streams (the same lookup the desktop app uses). */
internal object AndroidMusicVideoSource {
    val repository by lazy { WindowsMusicRepository() }
}

/** Picture height asked for, like the desktop artwork box (sharp, but light on a phone). */
// 480p: WSA (and slower phones) could not decode 720p in real time, so the picture
// kept falling behind and jumping back (it felt heavy). The full-screen view
// asks for more; the quality menu there picks any height.
internal const val MUSIC_VIDEO_HEIGHT = 480
/** The picture jumps to the sound only when it is further apart than this (else it catches up). */
private const val MUSIC_VIDEO_MAX_DRIFT_MS = 2_500L
/** A video no view shows any more is kept this long (the page and the full-screen view hand it over). */
private const val MUSIC_VIDEO_KEEP_MS = 2_000L

/**
 * One music video playing: its stream, its player (muted, in step with the song)
 * and the views that show it. The player page's artwork and the full-screen view
 * share it: opening full screen only moves the picture to the bigger view, so the
 * video carries on (before, full screen started a second player from scratch,
 * with the cover showing for seconds while it loaded).
 *
 * Only one view gets the picture at a time: the one attached last (the full-screen
 * view over the page), then the one before it again when that one goes.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class MusicVideoSession(
    private val context: android.content.Context,
    val trackId: String,
    val height: Int,
) {
    var player by mutableStateOf<ExoPlayer?>(null)
        private set
    var pictureShown by mutableStateOf(false)
        private set
    var videoAspect by mutableStateOf<Float?>(null)
        private set
    /** No stream could be played: the cover stays. */
    var failed by mutableStateOf(false)
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** The views showing this video, with their priority (the full-screen view comes first). */
    private val views = ArrayList<Pair<android.view.View, Int>>()
    private var boundView: android.view.View? = null
    internal var users = 0
    internal var closeJob: Job? = null

    init {
        scope.launch { runSession() }
    }

    private suspend fun runSession() {
        var attempt = 0
        while (scope.isActive && attempt <= 2) {
            if (attempt > 0) delay(1_000L * attempt)
            val url = lookUp()
            if (url == null) {
                attempt++
                continue
            }
            // Decoder trouble: one more player, with the software decoders first.
            var softwareRetry = true
            while (scope.isActive) {
                val result = play(url)
                if (result == PlayResult.DecoderSwitched && softwareRetry) {
                    softwareRetry = false
                    continue
                }
                break
            }
            if (!scope.isActive) return
            attempt++
        }
        failed = true
    }

    private suspend fun lookUp(): String? {
        var waitMs = 1_500L
        while (scope.isActive && waitMs <= 6_000L) {
            // The original Android app's client first (VISIONOS), the desktop lookup as a fallback.
            val url = runCatching { com.recordlabs.desktop.data.AndroidYouTubeEngine.musicVideoUrl(trackId, height) }.getOrNull()
                ?: runCatching { AndroidMusicVideoSource.repository.musicVideoStreamUrl(trackId, height) }.getOrNull()
            android.util.Log.i("RecordLabsVideo", "music video stream $trackId @$height: ${url?.take(80)}")
            if (url != null) return url
            delay(waitMs)
            waitMs *= 2
        }
        return null
    }

    private enum class PlayResult { Failed, DecoderSwitched }

    /** Plays [url] until the player fails (or the session ends). */
    private suspend fun play(url: String): PlayResult {
        val selector = DefaultTrackSelector(context).apply {
            setParameters(
                buildUponParameters()
                    .setMaxVideoSize(1920, 1080)
                    // Picture only: the sound is the song the engine plays.
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build(),
            )
        }
        val created = ExoPlayer.Builder(context, VideoDecoders.renderersFactory(context)).setTrackSelector(selector).build().apply {
            volume = 0f
            setMediaSource(
                ProgressiveMediaSource.Factory(OkHttpDataSource.Factory(okhttp3.OkHttpClient()))
                    .createMediaSource(MediaItem.fromUri(Uri.parse(url))),
                AndroidPlaybackEngine.instance().positionMs,
            )
            prepare()
        }
        var result: PlayResult? = null
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                android.util.Log.i("RecordLabsVideo", "music video first frame: $trackId @$height")
                pictureShown = true
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height > 0) {
                    val ratio = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
                    videoAspect = (ratio * 100f).toInt() / 100f
                }
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                android.util.Log.w("RecordLabsVideo", "music video error $trackId: ${error.errorCodeName}", error)
                pictureShown = false
                result = if (VideoDecoders.switchToSoftwareAfter(error)) PlayResult.DecoderSwitched else PlayResult.Failed
            }
        }
        created.addListener(listener)
        player = created
        bindTop()
        try {
            // Kept in step with the song: play/pause with it, and jump to its position
            // when they drift apart (start, seek, a stall on either side).
            val engine = AndroidPlaybackEngine.instance()
            while (scope.isActive && result == null) {
                val playing = engine.isPlaying && engine.currentTrack?.id == trackId
                if (created.playWhenReady != playing) created.playWhenReady = playing
                val audioMs = engine.positionMs
                val durationMs = created.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
                // Small drift: the picture runs a little faster or slower until it is
                // back with the sound (a seek restarts decoding from a key frame and
                // stutters). Only a big jump (start, the user seeking) seeks.
                val drift = created.currentPosition - audioMs
                var speed = 1f
                if (playing && audioMs < durationMs) {
                    if (abs(drift) > MUSIC_VIDEO_MAX_DRIFT_MS) {
                        android.util.Log.i("RecordLabsVideo", "music video re-sync $trackId: drift $drift ms")
                        created.seekTo(audioMs)
                    } else if (abs(drift) > 80L) {
                        speed = (1f - drift / 1000f * 0.5f).coerceIn(0.85f, 1.15f)
                    }
                }
                if (abs(created.playbackParameters.speed - speed) > 0.01f) created.setPlaybackSpeed(speed)
                delay(250)
            }
        } finally {
            created.removeListener(listener)
            if (player === created) player = null
            boundView = null
            created.release()
        }
        return result ?: PlayResult.Failed
    }

    /**
     * A view that shows this video. The picture goes to the full-screen view while
     * there is one ([priority] 1), otherwise to the view attached last.
     */
    fun attach(view: android.view.View, priority: Int) {
        views.removeAll { it.first === view }
        views.add(view to priority)
        bindTop()
    }

    fun detach(view: android.view.View) {
        views.removeAll { it.first === view }
        if (boundView === view) {
            player?.let { VideoDecoders.unbind(it, view) }
            boundView = null
        }
        bindTop()
    }

    private fun bindTop() {
        val current = player ?: return
        val top = views.lastOrNull { it.second == views.maxOf { v -> v.second } }?.first ?: return
        if (boundView === top) return
        // Straight from one view to the other (the decoder keeps going, no blank frame).
        VideoDecoders.bind(current, top)
        boundView = top
    }

    fun close() {
        scope.cancel()
        views.clear()
    }
}

/**
 * The running music videos, one per song and picture height. The full-screen view
 * asks for the song's video at the height already playing, so it takes over the
 * page's video instead of starting another one.
 */
internal object MusicVideoSessions {
    private val sessions = HashMap<String, MusicVideoSession>()
    /** The height each song's video plays at now (the one a new view joins). */
    val activeHeight = mutableStateMapOf<String, Int>()

    private fun key(trackId: String, height: Int) = "$trackId@$height"

    fun acquire(context: android.content.Context, trackId: String, height: Int): MusicVideoSession {
        val session = sessions.getOrPut(key(trackId, height)) {
            MusicVideoSession(context.applicationContext, trackId, height)
        }
        session.closeJob?.cancel()
        session.closeJob = null
        session.users++
        if (activeHeight[trackId] == null) activeHeight[trackId] = height
        return session
    }

    fun release(session: MusicVideoSession) {
        session.users--
        if (session.users > 0) return
        session.closeJob = CoroutineScope(Dispatchers.Main).launch {
            delay(MUSIC_VIDEO_KEEP_MS)
            if (session.users > 0) return@launch
            sessions.remove(key(session.trackId, session.height))
            if (activeHeight[session.trackId] == session.height) {
                val other = sessions.values.firstOrNull { it.trackId == session.trackId }
                if (other != null) activeHeight[session.trackId] = other.height else activeHeight.remove(session.trackId)
            }
            session.close()
        }
    }

    /** A song's video now plays at [height] (a quality picked in full screen, once its picture runs). */
    fun promote(trackId: String, height: Int) {
        activeHeight[trackId] = height
    }

    fun heightFor(trackId: String): Int? = activeHeight[trackId]
}

/**
 * A music video in the artwork's place, as on desktop: the video's picture,
 * muted and kept in step with the song playing (the sound comes from the
 * playback engine). The cover stays on top until the first picture is shown,
 * then blurs and fades away; it comes back if the video can't be played.
 */
@Composable
internal fun AndroidMusicVideo(
    track: Track,
    coverUrl: String?,
    modifier: Modifier,
    scale: ContentScale,
    onAspectRatio: (Float?) -> Unit,
    /** Picture height asked for (the full-screen quality menu picks a bigger one). */
    maxHeight: Int = MUSIC_VIDEO_HEIGHT,
    /** This is the full-screen view. */
    fullscreen: Boolean = false,
    /** Portrait player: a button that opens the video full screen (landscape). */
    showFullscreenButton: Boolean = false,
) {
    val context = LocalContext.current
    // The song's video, if it plays already (the page's, when full screen opens): it
    // is shown at once, and a different height asked for (the full-screen quality,
    // or a height picked there for the page) fades in over it once its picture runs.
    val targetHeight = if (fullscreen) maxHeight else (MusicVideoSessions.heightFor(track.id) ?: maxHeight)
    var shownHeight by remember(track.id) { mutableStateOf(MusicVideoSessions.heightFor(track.id) ?: targetHeight) }
    val shown = remember(track.id, shownHeight) { MusicVideoSessions.acquire(context, track.id, shownHeight) }
    DisposableEffect(shown) { onDispose { MusicVideoSessions.release(shown) } }
    val target = if (targetHeight == shownHeight) null else {
        val session = remember(track.id, targetHeight) { MusicVideoSessions.acquire(context, track.id, targetHeight) }
        DisposableEffect(session) { onDispose { MusicVideoSessions.release(session) } }
        session
    }
    val targetAlpha = remember(target) { Animatable(0f) }
    LaunchedEffect(target, target?.pictureShown, target?.failed) {
        if (target == null) return@LaunchedEffect
        if (target.failed) {
            // That height cannot be played here: stay with the one running.
            if (fullscreen) MusicVideoSessions.promote(track.id, shownHeight)
            return@LaunchedEffect
        }
        if (!target.pictureShown) return@LaunchedEffect
        targetAlpha.animateTo(1f, tween(350))
        if (fullscreen) MusicVideoSessions.promote(track.id, target.height)
        shownHeight = target.height
    }

    val layers = listOfNotNull(shown, target)
    val pictureShown = layers.any { it.pictureShown }
    val aspect = (target?.takeIf { it.pictureShown } ?: shown).videoAspect
    val latestAspect by rememberUpdatedState(onAspectRatio)
    LaunchedEffect(aspect, pictureShown) { latestAspect(aspect?.takeIf { pictureShown }) }
    DisposableEffect(track.id) { onDispose { latestAspect(null) } }

    // Joined while already playing (full screen over the page): no cover, the picture is just there.
    val reveal = remember(track.id) { Animatable(if (shown.pictureShown) 1f else 0f) }
    LaunchedEffect(pictureShown) {
        reveal.animateTo(if (pictureShown) 1f else 0f, tween(if (pictureShown) 1_000 else 300, easing = LinearEasing))
    }
    // The full-screen view gets the picture; while it fades out (closing) the page's
    // view has it again, so the page under it is already live.
    val priority = if (!fullscreen) 0 else if (MusicVideoFullscreen.trackId == track.id) 1 else -1

    Box(
        modifier
            .background(Color.Black)
            .onGloballyPositioned {
                if (!fullscreen) {
                    val bounds = it.boundsInWindow()
                    if (MusicVideoFullscreen.videoBoxBounds != bounds) MusicVideoFullscreen.videoBoxBounds = bounds
                }
            },
    ) {
        // Keyed by session: when the new height takes over, its view stays as it is
        // (no new view, no blank frame), and the old one goes.
        for (session in layers) {
            key(session) {
                val isTarget = session === target
                Box(Modifier.fillMaxSize().graphicsLayer { if (isTarget) alpha = targetAlpha.value.coerceAtLeast(0.01f) }) {
                    SessionView(session, scale, session.pictureShown, priority)
                }
            }
        }
        // The cover on top until the picture runs (blur + fade, as on desktop); gone
        // (not drawn at all) once the video shows.
        if (!pictureShown || reveal.isRunning || reveal.targetValue < 1f) {
            AsyncImage(
                model = coverUrl,
                contentDescription = null,
                contentScale = if (fullscreen) ContentScale.Fit else ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val progress = reveal.value
                        alpha = 1f - ((progress - 0.15f) / 0.85f).coerceIn(0f, 1f)
                        renderEffect = if (progress > 0f && progress < 1f && android.os.Build.VERSION.SDK_INT >= 31) {
                            val t = (progress / 0.6f).coerceIn(0f, 1f)
                            val radius = 22f * density * ((1.0 - kotlin.math.cos(Math.PI * t)) / 2.0).toFloat()
                            BlurEffect(radius, radius, TileMode.Clamp)
                        } else null
                    },
            )
        }
        if (showFullscreenButton && pictureShown && !fullscreen) {
            Box(
                Modifier
                    .align(androidx.compose.ui.Alignment.BottomEnd)
                    .padding(10.dp)
                    .size(36.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { MusicVideoFullscreen.open(track.id, null, MusicVideoFullscreen.videoBoxBounds) },
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                androidx.compose.material.Icon(
                    androidx.compose.material.icons.Icons.Default.Fullscreen,
                    "Full screen video",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
    }
}

/** One session's picture in its own view, attached to the session while it is on screen. */
@Composable
private fun SessionView(session: MusicVideoSession, scale: ContentScale, visible: Boolean, priority: Int) {
    val holder = remember { arrayOfNulls<android.view.View>(1) }
    AndroidView(
        factory = { viewContext ->
            VideoDecoders.newVideoView(viewContext).also {
                holder[0] = it
                session.attach(it, priority)
            }
        },
        modifier = Modifier
            .fillMaxSize()
            .clipToBounds()
            // Never fully transparent: a view at alpha 0 is not drawn, and a
            // TextureView gets its surface only when drawn (no frame ever came).
            .alpha(if (visible) 1f else 0.01f),
        // The TextureView stretches the picture to the box: its transform undoes that.
        update = { view -> VideoDecoders.fitVideo(view, session.videoAspect, crop = scale == ContentScale.Crop) },
    )
    LaunchedEffect(session, priority) { holder[0]?.let { session.attach(it, priority) } }
    DisposableEffect(session) {
        onDispose { holder[0]?.let(session::detach) }
    }
}
