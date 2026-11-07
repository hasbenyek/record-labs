@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.recordlabs.desktop

import androidx.compose.ui.text.font.FontFamily
import com.recordlabs.desktop.data.AppDiagnostics
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import org.jetbrains.skia.Typeface
import platform.CoreFoundation.CFRelease
import platform.CoreText.CTFontCopyAttribute
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.kCTFontURLAttribute
import platform.CoreText.kCTFontUIFontSystem
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSURL

/**
 * San Francisco from the system's own font file: iOS keeps it as one variable font
 * (weight, and optical size: the Text and Display designs). Skia cannot find it by
 * name, so the file CoreText uses for the system font is opened directly.
 */
internal actual object AppleSystemFont {
    private val base: Typeface? by lazy { load() }
    private val axes by lazy { base?.variationAxes.orEmpty().associateBy { it.tag } }

    actual val available: Boolean get() = base != null && "wght" in axes
    actual val hasOpticalSizes: Boolean get() = "opsz" in axes

    // Composition runs on the main thread only.
    private val families = HashMap<String, FontFamily>()

    actual fun family(weight: Int, display: Boolean): FontFamily? {
        val face = base ?: return null
        val weightAxis = axes["wght"] ?: return null
        // A handful of weights (each one a typeface): the nearest of these.
        val bucket = listOf(300, 400, 500, 600, 700, 800, 900).minByOrNull { kotlin.math.abs(it - weight) } ?: 400
        val key = "$bucket-${if (display) "d" else "t"}"
        families[key]?.let { return it }
        return runCatching {
            val variations = buildList {
                add(FontVariation("wght", bucket.toFloat().coerceIn(weightAxis.minValue, weightAxis.maxValue)))
                axes["opsz"]?.let { axis ->
                    add(FontVariation("opsz", (if (display) 28f else 17f).coerceIn(axis.minValue, axis.maxValue)))
                }
            }
            val clone = face.makeClone(variations.toTypedArray(), 0)
            FontFamily(androidx.compose.ui.text.platform.Typeface(clone, "SFUI-$key"))
        }.onFailure { AppDiagnostics.log("app", "SF $key unavailable: ${it.message}") }
            .getOrNull()
            ?.also { families[key] = it }
    }

    private fun load(): Typeface? = runCatching {
        val font = CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, 17.0, null) ?: return@runCatching null
        val url = CFBridgingRelease(CTFontCopyAttribute(font, kCTFontURLAttribute)) as? NSURL
        CFRelease(font)
        val path = url?.path ?: run {
            AppDiagnostics.log("app", "SF font: no file for the system font")
            return@runCatching null
        }
        val face = FontMgr.default.makeFromFile(path, 0)
        AppDiagnostics.log(
            "app",
            "SF font: $path, ${if (face == null) "not readable" else "axes " + face.variationAxes.orEmpty().joinToString { "${it.tag} ${it.minValue.toInt()}-${it.maxValue.toInt()}" }}",
        )
        face
    }.onFailure { AppDiagnostics.log("app", "SF font failed: ${it.message}") }.getOrNull()
}
