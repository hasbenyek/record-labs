package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * The typeface of the whole app, lyrics included.
 *
 * On Windows Compose's default family is Segoe UI, and that stays untouched.
 * Linux's default is Noto Sans / DejaVu Sans, which made text (especially the
 * lyrics) look different. Linux therefore uses:
 *  1. Segoe UI when it is installed (for example copied from a Windows install), else
 *  2. Selawik, Microsoft's open-source Segoe UI replacement with the same metrics,
 *     bundled in resources/fonts/selawik (SIL Open Font License).
 * Characters these fonts lack (Japanese, Korean, emoji...) fall back to the
 * system fonts, as before.
 */
object AppFonts {
    val family: FontFamily by lazy {
        if (AppPlatform.isWindows) FontFamily.Default else runCatching { linuxFamily() }.getOrDefault(FontFamily.Default)
    }

    /**
     * The lyrics (lyrics page and floating lyrics), as chosen in the player /
     * lyrics page ⋮ menu (AppSettings.lyricsFontStyle). It is Compose state, so
     * every lyrics view switches at once.
     */
    val lyrics: FontFamily
        get() = lyricsFamilyFor(com.recordlabs.desktop.data.AppSettings.lyricsFontStyle.value)

    /** "display" (default, Windows and Linux) or "classic". */
    fun lyricsFamilyFor(style: String): FontFamily = if (style == "classic") classicLyrics else displayLyrics

    /**
     * SF Pro Display when it is installed, else the bundled Inter Display, a
     * free typeface drawn after it (bold, rounded, tight). Same on every OS.
     */
    private val displayLyrics: FontFamily by lazy {
        runCatching { displayLyricsFamily() }.getOrDefault(if (AppPlatform.isWindows) FontFamily.Default else family)
    }

    /** The app's UI face: Segoe UI on Windows, Segoe UI or Selawik on Linux. */
    private val classicLyrics: FontFamily get() = if (AppPlatform.isWindows) FontFamily.Default else family

    /** Which typeface the Linux build ended up with (shown in the self-test). */
    val description: String
        get() = when {
            AppPlatform.isWindows -> "Segoe UI (Windows default)"
            segoeUiInstalled -> "Segoe UI (installed)"
            else -> "Selawik (bundled Segoe UI replacement)"
        }

    val lyricsDescription: String
        get() = when {
            com.recordlabs.desktop.data.AppSettings.lyricsFontStyle.value == "classic" ->
                if (AppPlatform.isWindows) "Segoe UI (classic)" else "$description (classic)"
            sfProInstalled -> "SF Pro Display (installed)"
            else -> "Inter Display (bundled)"
        }

    private val segoeUiInstalled: Boolean by lazy { installed("Segoe UI") }
    private val sfProInstalled: Boolean by lazy { installed("SF Pro Display") }

    // Exact family names only: fontconfig answers a lookup for a missing
    // family with a substitute, so matchFamily() alone can't tell.
    private fun installed(name: String): Boolean = systemFontInstalled(name)

    private fun displayLyricsFamily(): FontFamily = if (sfProInstalled) {
        systemFontFamily(
            "SF Pro Display",
            listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold, FontWeight.ExtraBold, FontWeight.Black),
        )
    } else {
        bundledFontFamily(
            listOf(
                "fonts/inter/InterDisplay-Regular.ttf" to FontWeight.Normal,
                "fonts/inter/InterDisplay-Medium.ttf" to FontWeight.Medium,
                "fonts/inter/InterDisplay-SemiBold.ttf" to FontWeight.SemiBold,
                "fonts/inter/InterDisplay-Bold.ttf" to FontWeight.Bold,
                "fonts/inter/InterDisplay-ExtraBold.ttf" to FontWeight.ExtraBold,
                "fonts/inter/InterDisplay-Black.ttf" to FontWeight.Black,
            ),
        )
    }

    private fun linuxFamily(): FontFamily = if (segoeUiInstalled) {
        systemFontFamily(
            "Segoe UI",
            listOf(FontWeight.Light, FontWeight.Normal, FontWeight.SemiBold, FontWeight.Bold, FontWeight.Black),
        )
    } else {
        bundledFontFamily(
            listOf(
                "fonts/selawik/Selawik-Light.ttf" to FontWeight.Light,
                "fonts/selawik/Selawik-Semilight.ttf" to FontWeight(350),
                "fonts/selawik/Selawik-Regular.ttf" to FontWeight.Normal,
                "fonts/selawik/Selawik-Semibold.ttf" to FontWeight.SemiBold,
                "fonts/selawik/Selawik-Bold.ttf" to FontWeight.Bold,
            ),
        )
    }
}

/** Whether a font family with exactly this name is installed. */
internal expect fun systemFontInstalled(name: String): Boolean

/** An installed font family in these weights. */
internal expect fun systemFontFamily(name: String, weights: List<FontWeight>): FontFamily

/** Fonts bundled with the app (resource paths). */
internal expect fun bundledFontFamily(fonts: List<Pair<String, FontWeight>>): FontFamily

/**
 * The typeface of the app's own text (not the lyrics, which keep [AppFonts.lyrics]):
 * the iPhone's system font (San Francisco, as in Apple Music); null elsewhere (the
 * platform's default stays).
 */
internal expect fun platformUiFontFamily(): FontFamily?
