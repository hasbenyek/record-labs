@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.UScript
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import platform.CoreFoundation.CFLocaleRef
import platform.CoreFoundation.CFRangeMake
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFStringTokenizerAdvanceToNextToken
import platform.CoreFoundation.CFStringTokenizerCopyCurrentTokenAttribute
import platform.CoreFoundation.CFStringTokenizerCreate
import platform.CoreFoundation.CFStringTokenizerGetCurrentTokenRange
import platform.CoreFoundation.kCFStringTokenizerAttributeLatinTranscription
import platform.CoreFoundation.kCFStringTokenizerTokenNone
import platform.CoreFoundation.kCFStringTokenizerUnitWord
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSLocale
import platform.Foundation.NSString
import platform.Foundation.NSStringTransformStripCombiningMarks
import platform.Foundation.NSStringTransformToLatin
import platform.Foundation.stringByApplyingTransform

/**
 * iOS: Apple's own transliteration (the same ICU transforms underneath).
 *
 * Japanese (a line with kana, as on the desktop and Android) is read word by word in
 * Japanese: Apple's Japanese word tokenizer gives each word's reading in Latin letters,
 * kanji included. The plain "to Latin" transform reads kanji as Chinese (Mandarin
 * pinyin): 重圧 came out as "zhong ya" instead of "juuatsu".
 */
actual object WindowsRomanizer {
    private val cache = HashMap<String, String>()

    @Suppress("CAST_NEVER_SUCCEEDS")
    actual fun romanize(text: String): String? = com.recordlabs.desktop.platform.locked(cache) {
        cache.getOrPut(text) {
            if (!text.hasNonLatinLetter()) return@getOrPut ""
            runCatching {
                val latin = if (text.hasScript(UScript.HIRAGANA, UScript.KATAKANA)) {
                    japaneseLatin(text) ?: toLatin(text)
                } else toLatin(text)
                ((latin as NSString).stringByApplyingTransform(NSStringTransformStripCombiningMarks, false) ?: latin)
                    .replace(Regex("\\s+([、。！？,.!?」』）)])"), "$1")
                    .replace(Regex("([「『（(])\\s+"), "$1")
                    .replace(Regex("\\s+"), " ")
                    .trim()
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals(text.trim(), ignoreCase = true) }.orEmpty()
        }
    }.takeIf(String::isNotBlank)

    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun toLatin(text: String): String =
        (text as NSString).stringByApplyingTransform(NSStringTransformToLatin, false) ?: text

    /** [text] read as Japanese, word by word (null when the tokenizer is not available). */
    @Suppress("CAST_NEVER_SUCCEEDS")
    private fun japaneseLatin(text: String): String? {
        val cfText: CFStringRef = CFBridgingRetain(text as NSString)?.reinterpret() ?: return null
        val cfLocale: CFLocaleRef? = CFBridgingRetain(NSLocale(localeIdentifier = "ja_JP"))?.reinterpret()
        try {
            val tokenizer = CFStringTokenizerCreate(
                null,
                cfText,
                CFRangeMake(0, text.length.toLong()),
                kCFStringTokenizerUnitWord,
                cfLocale,
            ) ?: return null
            try {
                val out = StringBuilder()
                var last = 0
                while (CFStringTokenizerAdvanceToNextToken(tokenizer) != kCFStringTokenizerTokenNone) {
                    val (start, length) = CFStringTokenizerGetCurrentTokenRange(tokenizer).useContents {
                        location.toInt() to this.length.toInt()
                    }
                    if (start < last || start + length > text.length) continue
                    // What lies between words (spaces, punctuation, Latin letters) stays as it is.
                    if (start > last) out.append(text, last, start)
                    val word = text.substring(start, start + length)
                    val reading = CFStringTokenizerCopyCurrentTokenAttribute(tokenizer, kCFStringTokenizerAttributeLatinTranscription)
                        ?.let { CFBridgingRelease(it) as? String }
                        ?.takeIf { it.isNotBlank() }
                    out.append(' ').append(reading ?: toLatin(word)).append(' ')
                    last = start + length
                }
                if (last < text.length) out.append(text, last, text.length)
                return out.toString()
            } finally {
                CFRelease(tokenizer)
            }
        } finally {
            CFRelease(cfText)
            if (cfLocale != null) CFRelease(cfLocale)
        }
    }
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun chineseToTraditional(text: String): String =
    (text as NSString).stringByApplyingTransform("Hans-Hant", false) ?: text
