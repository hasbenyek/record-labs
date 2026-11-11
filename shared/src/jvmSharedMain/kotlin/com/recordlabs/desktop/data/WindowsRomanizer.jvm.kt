package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.UScript

import com.ibm.icu.text.Transliterator
import com.atilika.kuromoji.ipadic.Tokenizer
import java.util.concurrent.ConcurrentHashMap

/** Local pronunciation text matching Android's per-line romanization model. */
actual object WindowsRomanizer {
    // Script checks via ScriptDetection.kt (Android rejects "\\p{IsLatin}" regexes).
    private val cache = ConcurrentHashMap<String, String>()
    private val genericTransliterator by lazy {
        Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    }
    private val kanaTransliterator by lazy {
        Transliterator.getInstance("Katakana-Latin; Hiragana-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    }
    private val japaneseTokenizer by lazy { Tokenizer() }


    actual fun romanize(text: String): String? = cache.getOrPut(text) {
        if (!text.hasNonLatinLetter()) return@getOrPut ""
        runCatching {
            if (text.hasScript(UScript.HIRAGANA, UScript.KATAKANA)) romanizeJapanese(text)
            else genericTransliterator.transliterate(text).trim()
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(text.trim(), ignoreCase = true) }
            .orEmpty()
    }.takeIf(String::isNotBlank)

    private val wordCache = ConcurrentHashMap<String, List<RomanWord>>()

    actual fun romanizeWords(text: String): List<RomanWord>? = wordCache.getOrPut(text) {
        if (!text.hasNonLatinLetter()) return@getOrPut emptyList()
        runCatching {
            if (text.hasScript(UScript.HIRAGANA, UScript.KATAKANA)) japaneseWords(text)
            else romanWordsOf(text) { synchronized(genericTransliterator) { genericTransliterator.transliterate(it) } }
        }.getOrNull().orEmpty()
    }.takeIf { it.isNotEmpty() }

    /** Kuromoji's words, each with its reading (kanji read as Japanese). */
    private fun japaneseWords(text: String): List<RomanWord> = japaneseTokenizer.tokenize(text).mapNotNull { token ->
        val surface = token.surface ?: return@mapNotNull null
        val start = token.position
        val end = start + surface.length
        if (start < 0 || end > text.length || !surface.hasNonLatinLetter()) return@mapNotNull null
        val reading = token.reading.takeUnless { it.isNullOrBlank() || it == "*" } ?: surface
        val latin = synchronized(kanaTransliterator) { kanaTransliterator.transliterate(reading) }.trim().lowercase()
        if (latin.isBlank() || latin == surface) null else RomanWord(start, end, latin)
    }

    private fun romanizeJapanese(text: String): String = japaneseTokenizer.tokenize(text)
        .joinToString(" ") { token ->
            val reading = token.reading.takeUnless { it.isNullOrBlank() || it == "*" }
            if (reading != null) kanaTransliterator.transliterate(reading)
            else kanaTransliterator.transliterate(token.surface)
        }
        .replace(Regex("\\s+([、。！？,.!?])"), "$1")
        .replace(Regex("\\s+"), " ")
        .trim()
}

private val hansToHant by lazy { runCatching { Transliterator.getInstance("Hans-Hant") }.getOrNull() }

actual fun chineseToTraditional(text: String): String {
    val transliterator = hansToHant ?: return text
    return synchronized(transliterator) { transliterator.transliterate(text) }
}
