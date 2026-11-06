package com.recordlabs.desktop.data

/*
 * Script checks without regex script classes: "\p{IsLatin}", "\p{IsHan}"...
 * are Java-only syntax; Android's (ICU) regex engine does not accept them and
 * throws as soon as such a pattern is compiled.
 */

private fun CharSequence.anyCodePoint(test: (Int) -> Boolean): Boolean {
    var i = 0
    while (i < length) {
        val cp = Character.codePointAt(this, i)
        if (test(cp)) return true
        i += Character.charCount(cp)
    }
    return false
}

private fun scriptOf(cp: Int): Character.UnicodeScript? = runCatching { Character.UnicodeScript.of(cp) }.getOrNull()

/** A letter that is not Latin script (what "[\p{L}&&[^\p{IsLatin}]]" matched). */
internal fun CharSequence.hasNonLatinLetter(): Boolean =
    anyCodePoint { Character.isLetter(it) && scriptOf(it) != Character.UnicodeScript.LATIN }

/** Any character of one of [scripts] (what "[\p{IsX}\p{IsY}]" matched). */
internal fun CharSequence.hasScript(vararg scripts: Character.UnicodeScript): Boolean =
    anyCodePoint { scriptOf(it) in scripts }
