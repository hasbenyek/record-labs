package com.recordlabs.desktop.data

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.FragmentedMp4Extractor
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.pow

/**
 * The Android player behind the shared app, built like the original Record
 * Labs Android app's MusicService: YouTube songs ("rlyt://<videoId>") are
 * resolved by [AndroidYouTubeEngine] when ExoPlayer opens them and streamed in
 * 512 KB ranges through an on-disk player cache; local files play directly. The app's playback service puts
 * [player] in a media session (notification, lock screen, headset buttons) and
 * sends next/previous back here as media commands, like the desktop media keys.
 */
class AndroidPlaybackEngine private constructor(context: Context) : PlaybackEngine {
    companion object {
        @Volatile private var appContext: Context? = null
        @Volatile private var engine: AndroidPlaybackEngine? = null

        /** Same range size as the original app (YouTube throttles longer requests). */
        private const val CHUNK_LENGTH = 512 * 1024L
        private const val SCHEME = "rlyt://"

        /** The URI the shared app plays for a YouTube song on Android. */
        fun uriFor(videoId: String) = SCHEME + videoId

        /** Called by the Android app before the UI starts. */
        fun init(context: Context) {
            appContext = context.applicationContext
            AndroidYouTubeEngine.init(context)
            // The shared repository hands YouTube songs to this engine unresolved; they are
            // resolved when ExoPlayer opens them (see youTubeDataSource).
            Platform.androidStreamUri = ::uriFor
            Platform.androidStreamResolver = { videoId -> AndroidYouTubeEngine.resolve(videoId) }
            Platform.androidAccountProfile = { AndroidYouTubeEngine.accountProfile() }
            Platform.androidValidateSession = { AndroidYouTubeEngine.validateSession() }
            Platform.androidPrefetch = { videoId -> instance().prefetch(videoId) }
        }

        /** The one player of the app (created on first use, on the main thread). */
        fun instance(): AndroidPlaybackEngine = engine ?: synchronized(this) {
            engine ?: AndroidPlaybackEngine(checkNotNull(appContext) { "AndroidPlaybackEngine.init was not called" }).also { engine = it }
        }
    }

    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())

    private val playerCache: SimpleCache by lazy {
        SimpleCache(
            File(context.cacheDir, "exoplayer"),
            LeastRecentlyUsedCacheEvictor(1024L * 1024 * 1024),
            StandaloneDatabaseProvider(context),
        )
    }

    private val httpClient = OkHttpClient.Builder().proxy(com.recordlabs.innertube.YouTube.proxy).build()

    private fun cacheDataSource(): CacheDataSource.Factory = CacheDataSource.Factory()
        .setCache(playerCache)
        .setUpstreamDataSourceFactory(DefaultDataSource.Factory(context, OkHttpDataSource.Factory(httpClient)))
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    /** Resolves "rlyt://<videoId>" on ExoPlayer's loading thread, exactly like MusicService.createDataSourceFactory. */
    private val youTubeDataSource = ResolvingDataSource.Factory(cacheDataSource()) { dataSpec ->
        val uri = dataSpec.uri.toString()
        if (!uri.startsWith(SCHEME)) return@Factory dataSpec
        val videoId = dataSpec.key ?: uri.removePrefix(SCHEME)
        val length = if (dataSpec.length >= 0) dataSpec.length else CHUNK_LENGTH
        if (playerCache.isCached(videoId, dataSpec.position, length)) return@Factory dataSpec
        val streamUrl = AndroidYouTubeEngine.cachedUrl(videoId)
            ?: runBlocking(Dispatchers.IO) { AndroidYouTubeEngine.resolve(videoId) }
            ?: throw PlaybackException("No stream for $videoId", null, PlaybackException.ERROR_CODE_REMOTE_ERROR)
        dataSpec.withUri(Uri.parse(streamUrl)).subrange(dataSpec.uriPositionOffset, CHUNK_LENGTH)
    }

    /**
     * Resolves [videoId] and writes its first range into the player cache, so
     * the song starts without waiting when it comes up next.
     */
    suspend fun prefetch(videoId: String) = kotlinx.coroutines.withContext(Dispatchers.IO) {
        if (playerCache.isCached(videoId, 0, CHUNK_LENGTH)) return@withContext
        val url = AndroidYouTubeEngine.resolve(videoId) ?: return@withContext
        val spec = DataSpec.Builder()
            .setUri(Uri.parse(url))
            .setKey(videoId)
            .setPosition(0)
            .setLength(CHUNK_LENGTH)
            .build()
        runCatching { androidx.media3.datasource.cache.CacheWriter(cacheDataSource().createDataSource(), spec, null, null).cache() }
    }

    /** Recoveries per song (expired URL, rejected client), so a broken song can't loop forever. */
    private val retries = HashMap<String, Int>()

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(
            AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MUSIC).build(),
            /* handleAudioFocus = */ true,
        )
        // Same as the original app: start once ~750 ms is buffered.
        .setLoadControl(DefaultLoadControl.Builder().setBufferDurationsMs(50_000, 50_000, 750, 2_000).build())
        .setHandleAudioBecomingNoisy(true)
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()

    /** The original app only probes these containers (YouTube audio is WebM/Opus or MP4/AAC). */
    private val youTubeExtractors = ExtractorsFactory {
        arrayOf(MatroskaExtractor(), FragmentedMp4Extractor(), Mp4Extractor())
    }

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

    private var volume = 1f
    private var tempo = 1f
    private var pitch = 0

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) = publish()
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    publish()
                    onPlaybackEnded?.invoke()
                } else publish()
            }
            override fun onPlayerError(error: PlaybackException) {
                if (recover(error)) return
                lastError = "Playback failed (${error.errorCodeName})"
                publish()
            }
        })
        // Position updates a few times a second, like the desktop player.
        main.post(object : Runnable {
            override fun run() {
                if (player.playbackState != Player.STATE_IDLE) publish()
                main.postDelayed(this, 250)
            }
        })
    }

    private fun publish() {
        isPlaying = player.isPlaying
        isReady = player.playbackState == Player.STATE_READY || player.playbackState == Player.STATE_ENDED
        positionMs = player.currentPosition.coerceAtLeast(0L)
        durationMs = player.duration.takeIf { it > 0 } ?: currentTrack?.durationMs ?: 0L
        _state.value = WindowsPlaybackSnapshot(currentTrack?.id, isPlaying, isReady, positionMs, durationMs, lastError)
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    override fun play(track: Track, url: String, startPositionMs: Long) = onMain {
        currentTrack = track
        lastError = null
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album.ifBlank { null })
            .setArtworkUri(track.artworkUrl?.let(Uri::parse))
            .build()
        val item = MediaItem.Builder().setUri(url).setMediaId(track.id).setMediaMetadata(metadata).build()
        val source = if (url.startsWith(SCHEME)) {
            retries.remove(track.id)
            val keyed = item.buildUpon().setCustomCacheKey(url.removePrefix(SCHEME)).build()
            ProgressiveMediaSource.Factory(youTubeDataSource, youTubeExtractors).createMediaSource(keyed)
        } else if (url.startsWith("http", ignoreCase = true)) {
            val http = OkHttpDataSource.Factory(httpClient).setUserAgent(WindowsAudioProxy.userAgentFor(url))
            ProgressiveMediaSource.Factory(http).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(DefaultDataSource.Factory(context)).createMediaSource(item)
        }
        player.setMediaSource(source, startPositionMs.coerceAtLeast(0L))
        player.volume = volume
        player.playbackParameters = PlaybackParameters(tempo, 2f.pow(pitch / 12f))
        player.prepare()
        player.play()
        publish()
    }

    /**
     * The original app's recovery for a stream that stops working: expired or
     * rejected URL (403, other bad HTTP status) -> resolve again without that
     * client; damaged cache (416, missing file) -> drop the cached song too.
     * Playback continues where it stopped.
     */
    private fun recover(error: PlaybackException): Boolean {
        val videoId = currentTrack?.id ?: return false
        val item = player.currentMediaItem ?: return false
        if (item.localConfiguration?.customCacheKey == null) return false
        val attempts = retries.getOrDefault(videoId, 0)
        if (attempts >= 3) return false
        val status = (error.cause as? HttpDataSource.InvalidResponseCodeException)?.responseCode
        val io = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_REMOTE_ERROR,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        )
        if (!io && status == null) return false
        retries[videoId] = attempts + 1
        AndroidYouTubeEngine.invalidate(videoId, rejectClient = status == 403 || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS)
        if (status == 416 || error.errorCode == PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND ||
            error.errorCode == PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED
        ) {
            runCatching { playerCache.removeResource(videoId) }
        }
        val position = player.currentPosition.coerceAtLeast(0L)
        main.postDelayed({
            if (currentTrack?.id != videoId) return@postDelayed
            player.seekTo(position)
            player.prepare()
            player.play()
        }, 600L * (attempts + 1))
        return true
    }

    override fun pause() = onMain { player.pause(); publish() }
    override fun resume() = onMain { player.play(); publish() }
    override fun seekTo(positionMs: Long) = onMain { player.seekTo(positionMs.coerceAtLeast(0L)); publish() }

    override fun presetState(volume: Float, tempo: Float, pitch: Int) {
        this.volume = volume
        this.tempo = tempo
        this.pitch = pitch
    }

    override fun setAudioTuning(tempo: Float, pitch: Int) = onMain {
        this.tempo = tempo
        this.pitch = pitch
        player.playbackParameters = PlaybackParameters(tempo, 2f.pow(pitch / 12f))
    }

    override fun setVolume(volume: Float) = onMain {
        this.volume = volume
        player.volume = volume.coerceIn(0f, 1f)
    }

    override fun sendBluetoothConnectPacket(payload: String) = Unit

    override fun stop() = onMain { player.stop(); currentTrack = null; publish() }

    /** The app-wide player stays alive for the playback service. */
    override fun release() = Unit
}

/** The app-wide ExoPlayer engine (see [AndroidPlaybackEngine.init]). */
actual fun createPlaybackEngine(): PlaybackEngine = AndroidPlaybackEngine.instance()
