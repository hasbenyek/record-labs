package com.recordlabs.desktop

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.mediacodec.MediaCodecUtil

/**
 * Which video decoders the animated artwork and music video players use.
 *
 * Some hardware decoders cannot draw into the video surface: WSA's
 * "OMX.android.latte.h264.decoder" fails on every clip (error 0x80001001),
 * so the player errored and the cover stayed on top for good. Once a decoder
 * fails, every new player puts the software decoders first; on WSA they come
 * first from the start. Apple's 10-bit HEVC clips always prefer software (the
 * hardware ones of WSA and many phones can't do them).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal object VideoDecoders {
    private val isWsa: Boolean =
        android.os.Build.MODEL.contains("Subsystem for Android", ignoreCase = true) ||
            android.os.Build.MANUFACTURER.contains("Microsoft", ignoreCase = true)

    /** Software decoders first for every video (set after a hardware decoder failed). */
    @Volatile
    var preferSoftware: Boolean = isWsa
        private set

    /** Debug switch read with `adb shell getprop` (empty when not set). */
    private fun debugProp(name: String): String = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, name) as String
    }.getOrDefault("")

    /**
     * Synchronous MediaCodec queueing. Media3's default asynchronous mode breaks
     * some old OMX decoders (WSA's "latte" decoders fail with 0x80001001 right
     * after the start). On by default on WSA; `setprop debug.recordlabs.vsync 0/1`
     * overrides it.
     */
    private val synchronousQueueing: Boolean
        get() = when (debugProp("debug.recordlabs.vsync")) {
            "1" -> true
            "0" -> false
            else -> isWsa
        }

    /**
     * SurfaceView instead of TextureView (a composer layer instead of a GL texture).
     * `setprop debug.recordlabs.vsurface surface/texture` overrides the default
     * (TextureView, which follows the artwork's rounded corners and fades).
     */
    private val useSurfaceView: Boolean
        get() = when (debugProp("debug.recordlabs.vsurface")) {
            "surface" -> true
            "texture" -> false
            else -> false
        }

    /** Renderers for a video player: these decoders, fallback on, queueing as above. */
    fun renderersFactory(context: android.content.Context): androidx.media3.exoplayer.DefaultRenderersFactory {
        val sync = synchronousQueueing
        android.util.Log.i("RecordLabsVideo", "video player: softwareFirst=$preferSoftware sync=$sync surfaceView=$useSurfaceView wsa=$isWsa")
        return androidx.media3.exoplayer.DefaultRenderersFactory(context)
            .setMediaCodecSelector(selector)
            .setEnableDecoderFallback(true)
            .apply { if (sync) forceDisableMediaCodecAsynchronousQueueing() }
    }

    /** The view the player draws into (see [useSurfaceView]). */
    fun createVideoView(context: android.content.Context, player: androidx.media3.exoplayer.ExoPlayer): android.view.View =
        if (useSurfaceView) {
            android.view.SurfaceView(context).also { player.setVideoSurfaceView(it) }
        } else {
            android.view.TextureView(context).apply {
                isOpaque = false
                player.setVideoTextureView(this)
                // The crop/fit follows the view's size (it is laid out after the first update).
                addOnLayoutChangeListener { view, _, _, _, _, _, _, _, _ -> applyFit(view as android.view.TextureView) }
            }
        }

    private data class VideoFit(val videoAspect: Float?, val crop: Boolean)

    /**
     * Shapes the picture inside the video view: [crop] fills the view (the square
     * artwork), otherwise the whole picture fits in it. A TextureView stretches the
     * picture to its own size; its transform undoes that within the view's bounds.
     * (Scaling the view itself from Compose overflowed its box on some systems,
     * e.g. Waydroid, where the artwork was no longer square.)
     */
    fun fitVideo(view: android.view.View, videoAspect: Float?, crop: Boolean) {
        val texture = view as? android.view.TextureView ?: return
        val fit = VideoFit(videoAspect, crop)
        if (texture.tag == fit) return
        texture.tag = fit
        applyFit(texture)
    }

    private fun applyFit(texture: android.view.TextureView) {
        val fit = texture.tag as? VideoFit ?: return
        val video = fit.videoAspect ?: return
        val width = texture.width.toFloat()
        val height = texture.height.toFloat()
        if (width <= 0f || height <= 0f || video <= 0f) return
        val box = width / height
        var scaleX = 1f
        var scaleY = 1f
        if (fit.crop) {
            if (video > box) scaleX = video / box else scaleY = box / video
        } else {
            if (video > box) scaleY = box / video else scaleX = video / box
        }
        texture.setTransform(android.graphics.Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }

    val selector = MediaCodecSelector { mimeType, secure, tunneling ->
        val decoders = MediaCodecUtil.getDecoderInfos(mimeType, secure, tunneling)
        if (preferSoftware || mimeType == MimeTypes.VIDEO_H265) decoders.sortedBy { !it.softwareOnly } else decoders
    }

    /**
     * Called on a player error. True when it was a decoder failure and the
     * software decoders were not tried yet: the caller then builds a new player,
     * which now starts with them.
     */
    fun switchToSoftwareAfter(error: PlaybackException): Boolean {
        val decoderError = error.errorCode in setOf(
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        )
        if (!decoderError || preferSoftware) return false
        android.util.Log.i("RecordLabsVideo", "hardware video decoder failed (${error.errorCodeName}): software decoders first from now on")
        preferSoftware = true
        return true
    }
}
