package com.recordlabs.desktop

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.layout.fillMaxSize
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.data.hasMusicVideo

// Android side of PlatformUi.kt.

/**
 * The page under the header (and the portrait bottom navigation), blurred
 * toward the edges like the desktop. Android 13+ mixes the sharp page and a
 * blurred copy exactly as the desktop shader does: sharp x (1 - amount) plus
 * blurred x amount, added together. Nothing of the blurred copy shows where
 * the amount is 0, so artwork and text get no halo (with a plain "over" blend
 * the blur showed around them wherever the page is transparent). Older
 * Android shows the page as it is.
 */
@Composable
actual fun Modifier.progressiveHeaderBlur(compact: Boolean, footerHeight: Dp, headerHeight: Dp, headerHide: () -> Float): Modifier {
    if (compact || android.os.Build.VERSION.SDK_INT < 33) return this
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    // A shader the device cannot compile leaves the page unblurred instead of failing.
    val shaders = remember {
        runCatching { android.graphics.RuntimeShader(EDGE_MASK_AGSL) to android.graphics.RuntimeShader(EDGE_MASK_AGSL) }.getOrNull()
    } ?: return this
    val (sharpMask, blurMask) = shaders
    val blurRadius = 18f * density
    val key = remember(density, footerHeight, headerHeight) { FloatArray(2) { -1f } }
    val cache = remember(density, footerHeight, headerHeight) { arrayOfNulls<androidx.compose.ui.graphics.RenderEffect>(1) }
    fun effectFor(height: Float, hide: Float): androidx.compose.ui.graphics.RenderEffect {
        cache[0]?.let { if (key[0] == height && key[1] == hide) return it }
        for ((shader, blurredPart) in listOf(sharpMask to 0f, blurMask to 1f)) {
            shader.setFloatUniform("headerHeight", headerHeight.value * density * (1f - hide))
            shader.setFloatUniform("footerHeight", footerHeight.value * density)
            shader.setFloatUniform("viewHeight", height)
            shader.setFloatUniform("blurredPart", blurredPart)
        }
        val blur = android.graphics.RenderEffect.createBlurEffect(blurRadius, blurRadius, android.graphics.Shader.TileMode.CLAMP)
        val blurred = android.graphics.RenderEffect.createChainEffect(
            android.graphics.RenderEffect.createRuntimeShaderEffect(blurMask, "content"),
            blur,
        )
        val sharp = android.graphics.RenderEffect.createRuntimeShaderEffect(sharpMask, "content")
        val effect = android.graphics.RenderEffect.createBlendModeEffect(blurred, sharp, android.graphics.BlendMode.PLUS)
        return effect.asComposeRenderEffect().also { key[0] = height; key[1] = hide; cache[0] = it }
    }
    return this.graphicsLayer {
        renderEffect = effectFor(size.height, headerHide().coerceIn(0f, 1f))
        clip = true
    }
}

/**
 * How blurred each row of the page is (0 sharp .. 1 blurred), the same curve
 * as the desktop. [blurredPart] 0 keeps the sharp page's share, 1 the blurred
 * copy's share.
 */
private const val EDGE_MASK_AGSL = """
    uniform shader content;
    uniform float headerHeight;
    uniform float footerHeight;
    uniform float viewHeight;
    uniform float blurredPart;

    half4 main(float2 coord) {
        half4 color = content.eval(coord);
        float blurAmount = 0.0;
        if (headerHeight > 0.5) {
            blurAmount = 1.0 - smoothstep(headerHeight * 0.2, headerHeight * 1.15, coord.y);
        }
        float footerStart = viewHeight - footerHeight;
        if (footerHeight > 0.5) {
            blurAmount = max(blurAmount, smoothstep(footerStart, viewHeight - footerHeight * 0.1, coord.y));
        }
        float share = blurredPart > 0.5 ? blurAmount : 1.0 - blurAmount;
        return color * half(share);
    }
"""

actual object PlatformDisplayScale {
    actual val supported: Boolean = false
    actual val options: List<Pair<String, String>> = emptyList()
    actual val mode: State<String> = mutableStateOf("auto")
    actual fun saveMode(mode: String) = Unit
    actual fun restart() = Unit
}

/** The playback service keeps the phone awake while music plays. */
actual object PlatformKeepAwake {
    actual fun setPlaying(playing: Boolean) = Unit
}

actual fun prefetchAnimatedArtwork(remoteUrl: String) = Unit

/**
 * Animated artwork exactly as the original Record Labs Android app shows it
 * (its AnimatedArtwork: Apple Music motion artwork looked up by title, artist,
 * album and duration, played muted and looping by ExoPlayer over the cover).
 */
@Composable
actual fun AnimatedTrackArtwork(
    track: Track,
    modifier: Modifier,
    shape: RoundedCornerShape,
    enabled: Boolean,
    musicVideoScale: ContentScale,
    onMusicVideoAspectRatio: (Float?) -> Unit,
    showFullscreenButton: Boolean,
) {
    // Only another song crossfades. The same song with updated details (duration,
    // artwork URL...) is a new Track value but must keep its running player: crossfading
    // on the Track itself started the animated artwork / music video over each time.
    // So the crossfade follows the song's id, and each id shows its latest Track.
    val latestById = remember { LinkedHashMap<String, Track>() }
    latestById.remove(track.id)
    latestById[track.id] = track
    while (latestById.size > 4) latestById.remove(latestById.keys.first())
    // A new song: the previous picture fades into the new one (as on desktop).
    androidx.compose.animation.Crossfade(
        targetState = track.id,
        animationSpec = androidx.compose.animation.core.tween(450),
        modifier = modifier,
        label = "artwork-change",
    ) { shownId ->
        val shown = if (shownId == track.id) track else latestById[shownId] ?: track
        AndroidTrackArtwork(shown, Modifier.fillMaxSize(), shape, enabled, musicVideoScale, onMusicVideoAspectRatio, showFullscreenButton)
    }
}

@Composable
private fun AndroidTrackArtwork(
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
    val coverUrl = remember(track.artworkUrl) { track.artworkUrl?.highQualityArtworkUrl() }
    // Music videos show the video itself (muted, in step with the song), as on desktop.
    // Video mode always shows the video: the animated artwork switch is hidden there
    // (landscape), so it must not be able to keep the video off.
    if (track.hasMusicVideo) {
        AndroidMusicVideo(track, coverUrl, modifier.clip(shape), musicVideoScale, onMusicVideoAspectRatio, showFullscreenButton = showFullscreenButton)
        return
    }
    if (!enabled || track.localPath != null || track.title.isBlank() || firstArtist.isBlank()) {
        coil3.compose.AsyncImage(
            model = coverUrl,
            contentDescription = null,
            contentScale = if (musicVideoScale == ContentScale.Crop) ContentScale.Crop else ContentScale.Fit,
            modifier = modifier.clip(shape),
        )
        return
    }
    // Animated artwork found the same way as on desktop (the same, right clip
    // everywhere), played by ExoPlayer under the cover.
    AndroidAnimatedArtwork(
        track = track,
        coverUrl = coverUrl,
        modifier = modifier.clip(shape),
        scale = musicVideoScale,
        onAspectRatio = onMusicVideoAspectRatio,
    )
}

@Composable
actual fun GoogleLoginView(onAuthenticated: () -> Unit) = AndroidGoogleLogin(onAuthenticated)

@Composable
actual fun PlatformBackHandler(onBack: () -> Boolean) {
    val activity = androidx.compose.ui.platform.LocalContext.current as? android.app.Activity
    val latest = androidx.compose.runtime.rememberUpdatedState(onBack)
    androidx.activity.compose.BackHandler(enabled = true) {
        if (!latest.value()) activity?.moveTaskToBack(true)
    }
}

/**
 * Android turns a shadow radius r into a blur of sigma 0.57735 r + 0.5, while
 * the desktop glow uses its value as sigma directly: convert, so the glow is as
 * soft and wide as on desktop. A fully opaque shadow colour would take the
 * text paint's alpha (zero for glow-only drawing), so it stays just below 1.
 */
actual fun lyricGlowShadow(color: androidx.compose.ui.graphics.Color, desktopBlurRadius: Float): androidx.compose.ui.graphics.Shadow =
    if (!LyricGlowSwitch.enabled) androidx.compose.ui.graphics.Shadow.None else androidx.compose.ui.graphics.Shadow(
        color.copy(alpha = color.alpha.coerceAtMost(0.996f)),
        blurRadius = ((desktopBlurRadius - 0.5f) / 0.57735f).coerceAtLeast(0f),
    )

/** Measuring only: `adb shell setprop debug.recordlabs.glow 0` draws the lyric glow without its shadow (read at launch). */
private object LyricGlowSwitch {
    val enabled: Boolean = com.recordlabs.desktop.data.Platform.debugProp("glow") != "0"
}
