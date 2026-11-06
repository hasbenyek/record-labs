package com.recordlabs.desktop

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.drawText

// The floating lyrics' magnified letters, drawn with Skia images (desktop).

/** How much larger than on screen a letter is drawn into its image (kept sharp while magnified). */
private const val LETTER_IMAGE_SCALE = 2f
/** Its glow is soft, so a smaller image is enough. */
private const val LETTER_GLOW_IMAGE_SCALE = 0.5f
/** Room around a glow for its blur, px. */
private const val LETTER_GLOW_PAD = 66f
/** How many letter images are made per frame at most. */
private val LetterSampling = org.jetbrains.skia.CubicResampler(1f / 3f, 1f / 3f)
private val LetterGlowSampling = org.jetbrains.skia.FilterMipmap(org.jetbrains.skia.FilterMode.LINEAR, org.jetbrains.skia.MipmapMode.NONE)

/** One letter drawn once: white, with a soft glow and a wider glow (for when it is magnified). */
internal actual class FloatingLetterSprite(
    val glyph: org.jetbrains.skia.Image,
    val glyphPad: Float,
    val glow: org.jetbrains.skia.Image,
    val wideGlow: org.jetbrains.skia.Image,
    actual val width: Float,
    actual val height: Float,
    /** Top of the letter's box to its baseline. */
    actual val baseline: Float,
) {
    fun close() {
        glyph.close()
        glow.close()
        wideGlow.close()
    }
}

/** The letter images of one lit line; freed as soon as the line no longer needs them. */
internal actual class FloatingLetterSprites actual constructor() : androidx.compose.runtime.RememberObserver {
    private val sprites = HashMap<Int, FloatingLetterSprite>()
    private val layouts = HashMap<Int, androidx.compose.ui.text.TextLayoutResult>()
    val paint = org.jetbrains.skia.Paint()

    actual fun find(start: Int): FloatingLetterSprite? = sprites[start]

    actual fun put(start: Int, sprite: FloatingLetterSprite): FloatingLetterSprite {
        sprites[start] = sprite
        layouts.remove(start)
        return sprite
    }

    actual fun layout(start: Int, measure: () -> androidx.compose.ui.text.TextLayoutResult): androidx.compose.ui.text.TextLayoutResult = layouts.getOrPut(start, measure)

    private fun release() {
        sprites.values.forEach { it.close() }
        sprites.clear()
        layouts.clear()
        paint.close()
    }

    override fun onRemembered() = Unit
    override fun onForgotten() = release()
    override fun onAbandoned() = release()
}

internal actual fun androidx.compose.ui.graphics.drawscope.DrawScope.floatingLetterSprite(
    text: String,
    measurer: androidx.compose.ui.text.TextMeasurer,
    style: androidx.compose.ui.text.TextStyle,
): FloatingLetterSprite {
    val layout = measurer.measure(text, style, overflow = androidx.compose.ui.text.style.TextOverflow.Visible, softWrap = false, maxLines = 1)
    val glyphPad = kotlin.math.ceil(style.fontSize.toPx() * 0.25f)
    val made = ArrayList<org.jetbrains.skia.Image>(3)
    try {
        made += renderLetterImage(layout, LETTER_IMAGE_SCALE, glyphPad, shadowBlur = null)
        made += renderLetterImage(layout, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, shadowBlur = 12f)
        made += renderLetterImage(layout, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, shadowBlur = 22f)
    } catch (error: Throwable) {
        made.forEach { it.close() }
        throw error
    }
    return FloatingLetterSprite(
        glyph = made[0],
        glyphPad = glyphPad,
        glow = made[1],
        wideGlow = made[2],
        width = layout.size.width.toFloat(),
        height = layout.size.height.toFloat(),
        baseline = layout.firstBaseline,
    )
}

/** The letter (or only its glow, when [shadowBlur] is set) in white, [scale] times larger, [pad] px around it. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.renderLetterImage(
    layout: androidx.compose.ui.text.TextLayoutResult,
    scale: Float,
    pad: Float,
    shadowBlur: Float?,
): org.jetbrains.skia.Image {
    val width = kotlin.math.ceil((layout.size.width + pad * 2f) * scale).toInt().coerceAtLeast(1)
    val height = kotlin.math.ceil((layout.size.height + pad * 2f) * scale).toInt().coerceAtLeast(1)
    val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(width, height)
    try {
        androidx.compose.ui.graphics.drawscope.CanvasDrawScope().draw(
            this,
            layoutDirection,
            surface.canvas.asComposeCanvas(),
            androidx.compose.ui.geometry.Size(width.toFloat(), height.toFloat()),
        ) {
            // The blur of a shadow does not follow the scale, so it is scaled here.
            drawContext.transform.scale(scale, scale, androidx.compose.ui.geometry.Offset.Zero)
            if (shadowBlur == null) {
                drawText(layout, color = Color.White, topLeft = androidx.compose.ui.geometry.Offset(pad, pad))
            } else {
                drawText(
                    layout,
                    color = Color.Transparent,
                    topLeft = androidx.compose.ui.geometry.Offset(pad, pad),
                    shadow = androidx.compose.ui.graphics.Shadow(Color.White, blurRadius = shadowBlur * scale),
                )
            }
        }
        return surface.makeImageSnapshot()
    } finally {
        surface.close()
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLetterImage(
    image: org.jetbrains.skia.Image,
    scale: Float,
    pad: Float,
    alpha: Float,
    paint: org.jetbrains.skia.Paint,
    sampling: org.jetbrains.skia.SamplingMode,
) {
    if (alpha <= 0.002f) return
    paint.setAlphaf(alpha.coerceAtMost(1f))
    drawContext.canvas.nativeCanvas.drawImageRect(
        image,
        org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()),
        org.jetbrains.skia.Rect.makeXYWH(-pad, -pad, image.width / scale, image.height / scale),
        sampling,
        paint,
        false,
    )
}

internal actual fun androidx.compose.ui.graphics.drawscope.DrawScope.drawFloatingLetter(
    word: FloatingWordFrame,
    sprite: FloatingLetterSprite,
    sprites: FloatingLetterSprites,
    dimAlpha: Float,
    emphasis: Float,
    emPx: Float,
) {
    val paint = sprites.paint
    val canvas = drawContext.canvas
    canvas.save()
    drawContext.transform.translate(word.x, word.baseline - sprite.baseline - word.lift)
    // Grows from just above its baseline, as under a magnifying glass.
    drawContext.transform.scale(
        word.scale,
        word.scale,
        androidx.compose.ui.geometry.Offset(word.pivotX, sprite.baseline - emPx * 0.05f),
    )
    drawLetterImage(sprite.glyph, LETTER_IMAGE_SCALE, sprite.glyphPad, dimAlpha, paint, LetterSampling)
    if (word.fullyLit || word.wipeX > 0f) {
        val margin = 160f
        if (!word.fullyLit) {
            canvas.saveLayer(
                androidx.compose.ui.geometry.Rect(-margin, -margin, sprite.width + margin, sprite.height + margin),
                androidx.compose.ui.graphics.Paint(),
            )
        }
        // The glow brightens and widens as the letter is magnified (as before).
        val glowAlpha = emphasis * word.litRamp
        val wide = word.glow.coerceIn(0f, 1f)
        drawLetterImage(sprite.glow, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, 0.45f * (1f - wide) * glowAlpha, paint, LetterGlowSampling)
        drawLetterImage(sprite.wideGlow, LETTER_GLOW_IMAGE_SCALE, LETTER_GLOW_PAD, 0.75f * wide * glowAlpha, paint, LetterGlowSampling)
        drawLetterImage(sprite.glyph, LETTER_IMAGE_SCALE, sprite.glyphPad, emphasis, paint, LetterSampling)
        if (!word.fullyLit) {
            // Soft wipe: lit on the left, fading out over [fade] px at the edge.
            drawRect(
                brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                    0f to Color.Transparent,
                    1f to Color.Black,
                    startX = word.wipeX - word.fade,
                    endX = word.wipeX + 2f,
                ),
                topLeft = androidx.compose.ui.geometry.Offset(-margin, -margin),
                size = androidx.compose.ui.geometry.Size(sprite.width + margin * 2, sprite.height + margin * 2),
                blendMode = androidx.compose.ui.graphics.BlendMode.DstOut,
            )
            canvas.restore()
        }
    }
    canvas.restore()
}

