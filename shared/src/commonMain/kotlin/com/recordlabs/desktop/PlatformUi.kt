package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * The few things the shared app UI needs that desktop and Android do
 * differently. Desktop implementations are in jvmMain, Android ones in
 * androidMain.
 */

/** Blurs the page under the see-through header (and, in portrait, under the bottom bar). */
@Composable
expect fun Modifier.progressiveHeaderBlur(
    compact: Boolean,
    footerHeight: Dp = 0.dp,
    headerHeight: Dp = 72.dp,
    /** 0..1: how far the header has slid away (the top band shrinks with it). */
    headerHide: () -> Float = { 0f },
): Modifier

/** Display scale setting (Linux desktops); not offered elsewhere. */
expect object PlatformDisplayScale {
    val supported: Boolean
    val options: List<Pair<String, String>>
    val mode: androidx.compose.runtime.State<String>
    fun saveMode(mode: String)
    fun restart()
}

/** Keeps the computer/phone from sleeping while music plays (where needed). */
expect object PlatformKeepAwake {
    fun setPlaying(playing: Boolean)
    /** The player or lyrics page is open (phones keep the screen on there while playing). */
    fun setPlayerPageOpen(open: Boolean)
}

/**
 * Android: the app full screen (status and navigation bars hidden), in landscape.
 * The desktop window has its own full-screen mode.
 */
object PlatformAppFullscreen {
    var enabled by androidx.compose.runtime.mutableStateOf(false)
}

/** Starts downloading an animated artwork video so it shows at once later. */
expect fun prefetchAnimatedArtwork(remoteUrl: String)

/**
 * The song's artwork, or its animated artwork / music video where the
 * platform can show one.
 */
@Composable
expect fun AnimatedTrackArtwork(
    track: com.recordlabs.desktop.data.Track,
    modifier: Modifier,
    shape: androidx.compose.foundation.shape.RoundedCornerShape,
    enabled: Boolean = true,
    musicVideoScale: androidx.compose.ui.layout.ContentScale = androidx.compose.ui.layout.ContentScale.Fit,
    onMusicVideoAspectRatio: (Float?) -> Unit = {},
    showFullscreenButton: Boolean = false,
)

/** Google sign-in for YouTube Music (desktop: the browser; Android: a web view). */
@Composable
expect fun GoogleLoginView(onAuthenticated: () -> Unit)

/** An icon from the app's resources ("lyrics_icon.png", "record_labs_connect.svg"...). */
@Composable
expect fun appPainter(name: String): androidx.compose.ui.graphics.painter.Painter

/** A PNG/JPEG/WebP picture decoded for drawing. */
expect fun decodeImageBitmap(bytes: ByteArray): androidx.compose.ui.graphics.ImageBitmap

/** Puts text on the clipboard. */
expect fun copyToClipboard(text: String)

/** Lets the user choose a folder of music files (desktop file dialog; not offered on Android). */
expect fun pickMusicFolder(title: String, onPicked: (com.recordlabs.desktop.platform.File) -> Unit)

/**
 * The system back action (Android back gesture/button). [onBack] returns false
 * when there is nothing to go back to; the app then goes to the background.
 * Desktop has no system back; the mouse back button is handled separately.
 */
@Composable
expect fun PlatformBackHandler(onBack: () -> Boolean)

/** Where to save a backup (.zip): a save dialog on desktop, the app's backup folder on Android. */
expect fun pickBackupSaveFile(defaultName: String, onPicked: (com.recordlabs.desktop.platform.File) -> Unit)

/** A backup to restore: an open dialog on desktop, the newest backup in the app's backup folder on Android. */
expect fun pickBackupOpenFile(onPicked: (com.recordlabs.desktop.platform.File) -> Unit)

/**
 * The glow behind lit lyrics, the same on every platform: [desktopBlurRadius]
 * is the blur as it looks on desktop (Skia text shadows use it as the blur's
 * sigma); Android converts it, since its shadow radius makes a tighter blur.
 */
expect fun lyricGlowShadow(color: androidx.compose.ui.graphics.Color, desktopBlurRadius: Float): androidx.compose.ui.graphics.Shadow

/**
 * The phone-style (portrait) layout: narrow windows, and on Android every
 * screen taller than wide, so a tablet held upright gets it too. A desktop window
 * taller than wide gets it as well up to 1000 dp wide (a window ~740 dp wide was
 * shown side by side, and the lyrics page had only a narrow column).
 */
internal fun isCompactLayout(width: Dp, height: Dp): Boolean =
    width < 700.dp || (height > width && (AppPlatform.isPhone || width < 1000.dp))
