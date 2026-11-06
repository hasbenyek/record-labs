@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, androidx.compose.ui.text.ExperimentalTextApi::class)

package com.recordlabs.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import com.recordlabs.desktop.data.Track
import com.recordlabs.desktop.platform.File
import com.recordlabs.desktop.platform.toByteArray
import platform.Foundation.NSBundle
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.UIKit.UIApplication
import platform.UIKit.UIPasteboard

// iOS side of PlatformUi.kt, AppFonts.kt (Compose on iOS draws with Skia, like the desktop).

@Composable
actual fun Modifier.progressiveHeaderBlur(
    compact: Boolean,
    footerHeight: Dp,
    headerHeight: Dp,
    headerHide: () -> Float,
): Modifier = this

actual object PlatformDisplayScale {
    actual val supported: Boolean get() = false
    actual val options: List<Pair<String, String>> get() = emptyList()
    actual val mode: State<String> = mutableStateOf("auto")
    actual fun saveMode(mode: String) = Unit
    actual fun restart() = Unit
}

/** The screen stays on while a song plays on the open player or lyrics page (like Android). */
actual object PlatformKeepAwake {
    private var playing = false
    private var pageOpen = false
    private fun apply() {
        UIApplication.sharedApplication.idleTimerDisabled = playing && pageOpen
    }
    actual fun setPlaying(playing: Boolean) { this.playing = playing; apply() }
    actual fun setPlayerPageOpen(open: Boolean) { pageOpen = open; apply() }
}

actual fun prefetchAnimatedArtwork(remoteUrl: String) = Unit

/** iOS (for now): the cover only; moving artwork and music videos come with the player. */
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
    Artwork(track.artworkUrl, modifier, shape)
}

@Composable
actual fun GoogleLoginView(onAuthenticated: () -> Unit) = IosGoogleLoginView(onAuthenticated)

/** The app's pictures (src/main/resources, copied into the app as "resources"). */
internal object IosResources {
    private val cache = HashMap<String, ByteArray?>()
    fun bytes(name: String): ByteArray? = com.recordlabs.desktop.platform.locked(cache) {
        cache.getOrPut(name) {
            val base = NSBundle.mainBundle.resourcePath ?: return@getOrPut null
            NSData.dataWithContentsOfFile("$base/resources/$name")?.toByteArray()
        }
    }
}

private class SvgPainter(private val dom: org.jetbrains.skia.svg.SVGDOM, private val size: Size) : Painter() {
    override val intrinsicSize: Size get() = size
    override fun DrawScope.onDraw() {
        drawIntoCanvas { canvas ->
            val native = canvas.nativeCanvas
            dom.setContainerSize(this.size.width, this.size.height)
            native.save()
            dom.render(native)
            native.restore()
        }
    }
}

private class EmptyPainter : Painter() {
    override val intrinsicSize: Size get() = Size(24f, 24f)
    override fun DrawScope.onDraw() = Unit
}

@Composable
actual fun appPainter(name: String): Painter = remember(name) {
    val bytes = IosResources.bytes(name) ?: return@remember EmptyPainter()
    runCatching {
        if (name.endsWith(".svg", ignoreCase = true)) {
            val dom = org.jetbrains.skia.svg.SVGDOM(org.jetbrains.skia.Data.makeFromBytes(bytes))
            val root = dom.root
            val width = root?.width?.value ?: 24f
            val height = root?.height?.value ?: 24f
            SvgPainter(dom, Size(width, height))
        } else BitmapPainter(decodeImageBitmap(bytes))
    }.getOrElse { EmptyPainter() }
}

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap =
    org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap()

internal actual fun systemFontInstalled(name: String): Boolean = runCatching {
    val fonts = org.jetbrains.skia.FontMgr.default
    (0 until fonts.familiesCount).any { fonts.getFamilyName(it).equals(name, ignoreCase = true) }
}.getOrDefault(false)

internal actual fun systemFontFamily(name: String, weights: List<FontWeight>): FontFamily =
    FontFamily(weights.map { androidx.compose.ui.text.platform.SystemFont(name, it) })

internal actual fun bundledFontFamily(fonts: List<Pair<String, FontWeight>>): FontFamily =
    FontFamily(
        fonts.mapNotNull { (path, weight) ->
            val bytes = IosResources.bytes(path) ?: return@mapNotNull null
            androidx.compose.ui.text.platform.Font(path, bytes, weight)
        }.ifEmpty { return FontFamily.Default },
    )

actual fun copyToClipboard(text: String) {
    UIPasteboard.generalPasteboard.string = text
}

actual fun pickMusicFolder(title: String, onPicked: (File) -> Unit) = Unit

/** iOS: the edge swipe goes back in the app's own navigation (IosBackGesture). */
@Composable
actual fun PlatformBackHandler(onBack: () -> Boolean) = IosBackGesture(onBack)

actual fun pickBackupSaveFile(defaultName: String, onPicked: (File) -> Unit) = Unit

actual fun pickBackupOpenFile(onPicked: (File) -> Unit) = Unit

actual fun lyricGlowShadow(color: Color, desktopBlurRadius: Float): androidx.compose.ui.graphics.Shadow =
    androidx.compose.ui.graphics.Shadow(color, blurRadius = desktopBlurRadius)

