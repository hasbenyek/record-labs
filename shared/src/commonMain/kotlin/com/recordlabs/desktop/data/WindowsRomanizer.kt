package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.UScript

/** Pronunciation in Latin letters for non-Latin lyrics (ICU + Kuromoji on the desktop and Android). */
expect object WindowsRomanizer {
    fun romanize(text: String): String?

    /**
     * The pronunciation word by word, each with where its word is in [text] (shown under
     * that word, as Apple Music). Null when the text has nothing to read out.
     */
    fun romanizeWords(text: String): List<RomanWord>?
}

/** The Latin reading of text[start until end]. */
data class RomanWord(val start: Int, val end: Int, val latin: String)

/** Simplified Chinese as Traditional (so 后来 and 後來 compare as the same title). */
expect fun chineseToTraditional(text: String): String

/**
 * Words of a non-Japanese line, read out one by one with [toLatin]: the words between
 * spaces (Korean, Cyrillic, Greek...), and Chinese character by character (a reading
 * under every character).
 */
internal fun romanWordsOf(text: String, toLatin: (String) -> String): List<RomanWord> {
    val out = ArrayList<RomanWord>()
    fun add(start: Int, end: Int) {
        val word = text.substring(start, end)
        if (!word.hasNonLatinLetter()) return
        val latin = toLatin(word).trim().lowercase()
        if (latin.isNotBlank() && !latin.equals(word.trim(), ignoreCase = true)) out += RomanWord(start, end, latin)
    }
    Regex("\\S+").findAll(text).forEach { match ->
        val wordStart = match.range.first
        val wordEnd = match.range.last + 1
        if (!match.value.hasScript(UScript.HAN)) {
            add(wordStart, wordEnd)
            return@forEach
        }
        var i = wordStart
        while (i < wordEnd) {
            val next = if (text[i].isHighSurrogate() && i + 1 < wordEnd) i + 2 else i + 1
            if (text.substring(i, next).hasScript(UScript.HAN)) add(i, next)
            i = next
        }
    }
    return out
}
