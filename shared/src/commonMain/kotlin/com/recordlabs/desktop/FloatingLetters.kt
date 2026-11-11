package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle

/** One letter of the lit lyric line drawn once into images (white, soft glow, wide glow). */
internal expect class FloatingLetterSprite {
    val width: Float
    val height: Float
    /** Top of the letter's box to its baseline. */
    val baseline: Float
}

/** The letter images of one lit line; freed as soon as the line no longer needs them. */
internal expect class FloatingLetterSprites() {
    fun find(start: Int): FloatingLetterSprite?
    fun put(start: Int, sprite: FloatingLetterSprite): FloatingLetterSprite
    fun layout(start: Int, measure: () -> TextLayoutResult): TextLayoutResult
}

internal expect fun DrawScope.floatingLetterSprite(text: String, measurer: TextMeasurer, style: TextStyle): FloatingLetterSprite

internal expect fun DrawScope.drawFloatingLetter(
    word: FloatingWordFrame,
    sprite: FloatingLetterSprite,
    sprites: FloatingLetterSprites,
    dimAlpha: Float,
    emphasis: Float,
    emPx: Float,
)
