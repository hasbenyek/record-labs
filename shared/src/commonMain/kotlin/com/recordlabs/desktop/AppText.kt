package com.recordlabs.desktop

import androidx.compose.foundation.text.InlineTextContent
import androidx.compose.material.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.platform.AppPlatform

/**
 * The app's text, as Material's Text (same parameters), which on the iPhone is set the
 * way iOS sets San Francisco: the weight and the optical size (Text below 20 pt, Display
 * from 20 pt) chosen from the variable system font, and Apple's tracking for each size
 * (Material's own letter spacing made the text look wider than in Apple Music).
 *
 * Text with its own typeface (the lyrics, monospace) and every other platform are left
 * exactly as before.
 */
@Composable
internal fun Text(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    onTextLayout: ((TextLayoutResult) -> Unit)? = null,
    style: TextStyle = LocalTextStyle.current,
) {
    val apple = appleTextStyle(style, fontSize, fontWeight, fontFamily, letterSpacing)
    androidx.compose.material.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = apple?.family ?: fontFamily,
        letterSpacing = apple?.letterSpacing ?: letterSpacing, textDecoration = textDecoration,
        textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, minLines = minLines, onTextLayout = onTextLayout,
        style = if (apple != null) style.copy(fontSynthesis = FontSynthesis.None) else style,
    )
}

@Composable
internal fun Text(
    text: AnnotatedString,
    modifier: Modifier = Modifier,
    color: Color = Color.Unspecified,
    fontSize: TextUnit = TextUnit.Unspecified,
    fontStyle: FontStyle? = null,
    fontWeight: FontWeight? = null,
    fontFamily: FontFamily? = null,
    letterSpacing: TextUnit = TextUnit.Unspecified,
    textDecoration: TextDecoration? = null,
    textAlign: TextAlign? = null,
    lineHeight: TextUnit = TextUnit.Unspecified,
    overflow: TextOverflow = TextOverflow.Clip,
    softWrap: Boolean = true,
    maxLines: Int = Int.MAX_VALUE,
    minLines: Int = 1,
    inlineContent: Map<String, InlineTextContent> = mapOf(),
    onTextLayout: (TextLayoutResult) -> Unit = {},
    style: TextStyle = LocalTextStyle.current,
) {
    val apple = appleTextStyle(style, fontSize, fontWeight, fontFamily, letterSpacing)
    androidx.compose.material.Text(
        text = text, modifier = modifier, color = color, fontSize = fontSize, fontStyle = fontStyle,
        fontWeight = fontWeight, fontFamily = apple?.family ?: fontFamily,
        letterSpacing = apple?.letterSpacing ?: letterSpacing, textDecoration = textDecoration,
        textAlign = textAlign, lineHeight = lineHeight, overflow = overflow, softWrap = softWrap,
        maxLines = maxLines, minLines = minLines, inlineContent = inlineContent, onTextLayout = onTextLayout,
        style = if (apple != null) style.copy(fontSynthesis = FontSynthesis.None) else style,
    )
}

private class AppleTextStyle(val family: FontFamily, val letterSpacing: TextUnit)

/** San Francisco for this text (iPhone), or null to leave it as it is. */
private fun appleTextStyle(
    style: TextStyle,
    fontSize: TextUnit,
    fontWeight: FontWeight?,
    fontFamily: FontFamily?,
    letterSpacing: TextUnit,
): AppleTextStyle? {
    if (!AppPlatform.isIos || !AppleSystemFont.available) return null
    // Text with its own typeface keeps it (the lyrics, monospace…).
    if (fontFamily != null) return null
    if (style.fontFamily != null && style.fontFamily != appUiFontFamily) return null
    val size = when {
        fontSize.isSpecified -> fontSize.value
        style.fontSize.isSpecified -> style.fontSize.value
        else -> 14f
    }
    val weight = (fontWeight ?: style.fontWeight ?: FontWeight.Normal).weight
    val display = size >= 20f
    val family = AppleSystemFont.family(weight, display) ?: return null
    // Spacing asked for by the text itself stays; Material's defaults give way to Apple's.
    val spacing = if (letterSpacing.isSpecified) letterSpacing else appleTracking(size, AppleSystemFont.hasOpticalSizes).sp
    return AppleTextStyle(family, spacing)
}

/**
 * Apple's tracking for San Francisco, in points, by point size (Human Interface
 * Guidelines). From 20 pt it is drawn for the Display design; without the optical size
 * axis the Text design is used there too, which needs no extra space.
 */
private fun appleTracking(size: Float, displayDesign: Boolean): Float {
    val table = floatArrayOf(
        // 6 … 40 pt
        0.24f, 0.23f, 0.21f, 0.17f, 0.12f, 0.06f, 0f, -0.08f, -0.15f, -0.23f, -0.31f, -0.43f, -0.44f, -0.45f,
        -0.45f, -0.36f, -0.26f, -0.17f, 0.07f, 0.15f, 0.22f, 0.29f, 0.38f, 0.40f, 0.40f, 0.39f, 0.41f, 0.40f,
        0.40f, 0.38f, 0.37f, 0.36f, 0.37f, 0.38f, 0.37f,
    )
    val index = (size.toInt() - 6).coerceIn(0, table.lastIndex)
    val value = table[index]
    return if (size >= 24f && !displayDesign) 0f else value
}

/** The app's default typeface (Main.kt's MaterialTheme). */
internal val appUiFontFamily: FontFamily by lazy {
    AppleSystemFont.family(400, display = false) ?: platformUiFontFamily() ?: FontFamily.Default
}

/**
 * iPhone: San Francisco read straight from the system's variable font file (Skia cannot
 * find it by name), as one typeface per weight and optical size; elsewhere unavailable.
 */
internal expect object AppleSystemFont {
    val available: Boolean
    val hasOpticalSizes: Boolean
    fun family(weight: Int, display: Boolean): FontFamily?
}
