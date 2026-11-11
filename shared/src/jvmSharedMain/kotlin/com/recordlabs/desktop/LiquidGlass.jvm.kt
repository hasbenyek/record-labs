package com.recordlabs.desktop

/** San Francisco is the iPhone's; the desktop and Android keep their own fonts. */
internal actual object AppleSystemFont {
    actual val available: Boolean get() = false
    actual val hasOpticalSizes: Boolean get() = false
    actual fun family(weight: Int, display: Boolean): androidx.compose.ui.text.font.FontFamily? = null
}
