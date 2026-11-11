package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.UScript
import com.recordlabs.desktop.platform.forEachCodePoint
import com.recordlabs.desktop.platform.isLetterCodePoint
import com.recordlabs.desktop.platform.unicodeScriptOf

/*
 * Script checks without regex script classes: "\p{IsLatin}", "\p{IsHan}"...
 * are Java-only syntax; Android's (ICU) regex engine does not accept them and
 * throws as soon as such a pattern is compiled.
 */

private inline fun CharSequence.anyCodePoint(test: (Int) -> Boolean): Boolean {
    forEachCodePoint { if (test(it)) return true }
    return false
}

/** A letter that is not Latin script (what "[\p{L}&&[^\p{IsLatin}]]" matched). */
internal fun CharSequence.hasNonLatinLetter(): Boolean =
    anyCodePoint { isLetterCodePoint(it) && unicodeScriptOf(it) != UScript.LATIN }

/** Any character of one of [scripts] (what "[\p{IsX}\p{IsY}]" matched). */
internal fun CharSequence.hasScript(vararg scripts: UScript): Boolean =
    anyCodePoint { unicodeScriptOf(it) in scripts }
