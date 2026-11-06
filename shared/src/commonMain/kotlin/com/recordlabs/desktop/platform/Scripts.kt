package com.recordlabs.desktop.platform

/** Writing systems the app tells apart (java.lang.Character.UnicodeScript names). */
enum class UScript {
    LATIN, GREEK, CYRILLIC, ARMENIAN, GEORGIAN, HEBREW, ARABIC, DEVANAGARI,
    THAI, LAO, KHMER, MYANMAR, HAN, HIRAGANA, KATAKANA, HANGUL, COMMON, INHERITED, OTHER,
}

/** Character.UnicodeScript.of(codePoint), as [UScript]. */
expect fun unicodeScriptOf(codePoint: Int): UScript

/** Character.isLetter(codePoint). */
expect fun isLetterCodePoint(codePoint: Int): Boolean

/** Calls [action] with every code point of the text (surrogate pairs joined). */
inline fun CharSequence.forEachCodePoint(action: (Int) -> Unit) {
    var i = 0
    while (i < length) {
        val high = this[i]
        if (high.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) {
            action(((high.code - 0xD800) shl 10) + (this[i + 1].code - 0xDC00) + 0x10000)
            i += 2
        } else {
            action(high.code)
            i++
        }
    }
}
