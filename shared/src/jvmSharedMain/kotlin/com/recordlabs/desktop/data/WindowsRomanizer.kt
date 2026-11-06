package com.recordlabs.desktop.data

import com.ibm.icu.text.Transliterator
import com.atilika.kuromoji.ipadic.Tokenizer
import java.util.concurrent.ConcurrentHashMap

/** Local pronunciation text matching Android's per-line romanization model. */
object WindowsRomanizer {
    // Script checks via ScriptDetection.kt (Android rejects "\\p{IsLatin}" regexes).
    private val cache = ConcurrentHashMap<String, String>()
    private val genericTransliterator by lazy {
        Transliterator.getInstance("Any-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    }
    private val kanaTransliterator by lazy {
        Transliterator.getInstance("Katakana-Latin; Hiragana-Latin; NFD; [:Nonspacing Mark:] Remove; NFC")
    }
    private val japaneseTokenizer by lazy { Tokenizer() }


    fun romanize(text: String): String? = cache.getOrPut(text) {
        if (!text.hasNonLatinLetter()) return@getOrPut ""
        runCatching {
            if (text.hasScript(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.KATAKANA)) romanizeJapanese(text)
            else genericTransliterator.transliterate(text).trim()
        }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && !it.equals(text.trim(), ignoreCase = true) }
            .orEmpty()
    }.takeIf(String::isNotBlank)

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
