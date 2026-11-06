package com.recordlabs.desktop.data

/** Pronunciation in Latin letters for non-Latin lyrics (ICU + Kuromoji on the desktop and Android). */
expect object WindowsRomanizer {
    fun romanize(text: String): String?
}

/** Simplified Chinese as Traditional (so 后来 and 後來 compare as the same title). */
expect fun chineseToTraditional(text: String): String
