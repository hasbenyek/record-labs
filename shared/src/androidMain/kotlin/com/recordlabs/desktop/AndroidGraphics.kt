package com.recordlabs.desktop

import android.graphics.BitmapFactory
import android.graphics.RuntimeShader
import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import coil3.toBitmap
import com.recordlabs.desktop.data.Platform
import com.recordlabs.desktop.ui.RecordLabsConnectIcon
import java.io.File

// Android side of the drawing helpers (icons, fonts, ambient shader, floating letters).

private object AndroidResources {
    fun bytes(name: String): ByteArray? =
        AndroidResources::class.java.classLoader?.getResourceAsStream(name.removePrefix("/"))?.use { it.readBytes() }
}

@Composable
actual fun appPainter(name: String): Painter {
    if (name.endsWith(".svg", ignoreCase = true)) return rememberVectorPainter(RecordLabsConnectIcon)
    return remember(name) {
        AndroidResources.bytes(name)?.let { bytes -> runCatching { BitmapPainter(decodeImageBitmap(bytes)) }.getOrNull() }
            ?: ColorPainter(Color.Transparent)
    }
}

actual fun decodeImageBitmap(bytes: ByteArray): ImageBitmap =
    checkNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size)) { "Not a picture" }.asImageBitmap()

// Fonts: no desktop font families on a phone; the bundled ones are copied out of the APK once.

internal actual fun systemFontInstalled(name: String): Boolean = false

internal actual fun systemFontFamily(name: String, weights: List<FontWeight>): FontFamily = FontFamily.Default

internal actual fun bundledFontFamily(fonts: List<Pair<String, FontWeight>>): FontFamily {
    val dir = File(Platform.cacheRoot, "fonts").apply { mkdirs() }
    val loaded = fonts.mapNotNull { (path, weight) ->
        val file = File(dir, path.substringAfterLast('/'))
        if (!file.isFile || file.length() == 0L) {
            val bytes = AndroidResources.bytes(path) ?: return@mapNotNull null
            file.writeBytes(bytes)
        }
        androidx.compose.ui.text.font.Font(file, weight)
    }
    return if (loaded.isEmpty()) FontFamily.Default else FontFamily(loaded)
}

// Ambient background: the same shader as AGSL (Android 13+).

private class AgslAmbientShaderRenderer(private val shader: RuntimeShader) : AmbientShaderRenderer {
    private val brush = ShaderBrush(shader)
    override fun brush(width: Float, height: Float, time: Float, spread: Float, colors: FloatArray, seeds: FloatArray): Brush {
        if (Build.VERSION.SDK_INT >= 33) {
            shader.setFloatUniform("resolution", width, height)
            shader.setFloatUniform("time", time)
            shader.setFloatUniform("spread", spread)
            shader.setFloatUniform("colors", colors)
            shader.setFloatUniform("seeds", seeds)
        }
        // A new brush object each frame so the draw is not skipped as unchanged.
        return ShaderBrush(shader)
    }
    override fun close() = Unit
}

internal actual fun createAmbientShaderRenderer(source: String): AmbientShaderRenderer? {
    if (Build.VERSION.SDK_INT < 33) return null
    return runCatching { AgslAmbientShaderRenderer(RuntimeShader(source)) }
        .onFailure { System.err.println("Ambient background shader unavailable: ${it.message}") }
        .getOrNull()
}

internal actual fun coil3.Image.argbPixels(): IntArray {
    // Coil decodes into hardware bitmaps on Android, whose pixels can't be read:
    // read a software copy (the picture is tiny, 48 px a side).
    val decoded = toBitmap()
    val bitmap = if (decoded.config == android.graphics.Bitmap.Config.HARDWARE) {
        decoded.copy(android.graphics.Bitmap.Config.ARGB_8888, false)
    } else decoded
    val pixels = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    return pixels
}

// Floating lyrics letters, drawn with Compose images.

private const val LETTER_IMAGE_SCALE = 2f
private const val LETTER_GLOW_IMAGE_SCALE = 0.5f
private const val LETTER_GLOW_PAD = 66f

internal actual class FloatingLetterSprite(
    val glyph: ImageBitmap,
    val glyphPad: Float,
    val glow: ImageBitmap,
    val wideGlow: ImageBitmap,
    actual val width: Float,
    actual val height: Float,
    actual val baseline: Float,
)

internal actual class FloatingLetterSprites actual constructor() {
    private val sprites = HashMap<Int, FloatingLetterSprite>()
    private val layouts = HashMap<Int, TextLayoutResult>()

    actual fun find(start: Int): FloatingLetterSprite? = sprites[start]

    actual fun put(start: Int, sprite: FloatingLetterSprite): FloatingLetterSprite {
        sprites[start] = sprite
        layouts.remove(start)
        return sprite
    }

    actual fun layout(start: Int, measure: () -> TextLayoutResult): TextLayoutResult = layouts.getOrPut(start, measure)
}

internal actual fun DrawScope.floatingLetterSprite(text: String, measurer: TextMeasurer, style: TextStyle): FloatingLetterSprite {
    val layout = measurer.measure(text, style, overflow = TextOverflow.Visible, softWrap = false, maxLines = 1)
    val glyphPad = kotlin.math.ceil(style.fontSize.toPx() * 0.25f)
    return FloatingLetterSprite(
        glyph = renderLetterImage(layout, LETTER_IMAGE_SCALE, glyphPad, shadowBlur = null),
        glyphPad = glyphPad,
        glow = renderLetterImage(layout, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, shadowBlur = 12f),
        wideGlow = renderLetterImage(layout, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, shadowBlur = 22f),
        width = layout.size.width.toFloat(),
        height = layout.size.height.toFloat(),
        baseline = layout.firstBaseline,
    )
}

private fun DrawScope.renderLetterImage(layout: TextLayoutResult, scale: Float, pad: Float, shadowBlur: Float?): ImageBitmap {
    val width = kotlin.math.ceil((layout.size.width + pad * 2f) * scale).toInt().coerceAtLeast(1)
    val height = kotlin.math.ceil((layout.size.height + pad * 2f) * scale).toInt().coerceAtLeast(1)
    val image = ImageBitmap(width, height)
    CanvasDrawScope().draw(this, layoutDirection, Canvas(image), Size(width.toFloat(), height.toFloat())) {
        drawContext.transform.scale(scale, scale, Offset.Zero)
        if (shadowBlur == null) {
            drawText(layout, color = Color.White, topLeft = Offset(pad, pad))
        } else {
            drawText(layout, color = Color.Transparent, topLeft = Offset(pad, pad), shadow = lyricGlowShadow(Color.White, shadowBlur * scale))
        }
    }
    return image
}

private fun DrawScope.drawLetterImage(image: ImageBitmap, scale: Float, pad: Float, alpha: Float) {
    if (alpha <= 0.002f) return
    translate(-pad, -pad) {
        scale(1f / scale, 1f / scale, Offset.Zero) {
            drawImage(
                image,
                srcOffset = IntOffset.Zero,
                srcSize = IntSize(image.width, image.height),
                dstOffset = IntOffset.Zero,
                dstSize = IntSize(image.width, image.height),
                alpha = alpha.coerceAtMost(1f),
                filterQuality = FilterQuality.Medium,
            )
        }
    }
}

internal actual fun DrawScope.drawFloatingLetter(
    word: FloatingWordFrame,
    sprite: FloatingLetterSprite,
    sprites: FloatingLetterSprites,
    dimAlpha: Float,
    emphasis: Float,
    emPx: Float,
) {
    val canvas = drawContext.canvas
    canvas.save()
    drawContext.transform.translate(word.x, word.baseline - sprite.baseline - word.lift)
    drawContext.transform.scale(word.scale, word.scale, Offset(word.pivotX, sprite.baseline - emPx * 0.05f))
    drawLetterImage(sprite.glyph, LETTER_IMAGE_SCALE, sprite.glyphPad, dimAlpha)
    if (word.fullyLit || word.wipeX > 0f) {
        val margin = 160f
        if (!word.fullyLit) {
            canvas.saveLayer(Rect(-margin, -margin, sprite.width + margin, sprite.height + margin), Paint())
        }
        val glowAlpha = emphasis * word.litRamp
        val wide = word.glow.coerceIn(0f, 1f)
        drawLetterImage(sprite.glow, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, 0.45f * (1f - wide) * glowAlpha)
        drawLetterImage(sprite.wideGlow, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, 0.75f * wide * glowAlpha)
        drawLetterImage(sprite.glyph, LETTER_IMAGE_SCALE, sprite.glyphPad, emphasis)
        if (!word.fullyLit) {
            drawRect(
                brush = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = word.wipeX - word.fade, endX = word.wipeX + 2f),
                topLeft = Offset(-margin, -margin),
                size = Size(sprite.width + margin * 2, sprite.height + margin * 2),
                blendMode = BlendMode.DstOut,
            )
            canvas.restore()
        }
    }
    canvas.restore()
}

internal actual fun platformUiFontFamily(): FontFamily? = null
