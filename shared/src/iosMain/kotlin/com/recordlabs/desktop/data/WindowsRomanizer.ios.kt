package com.recordlabs.desktop.data

import platform.Foundation.NSString
import platform.Foundation.NSStringTransformStripCombiningMarks
import platform.Foundation.NSStringTransformToLatin
import platform.Foundation.stringByApplyingTransform

/** iOS: Apple's own transliteration (the same ICU transforms underneath). */
actual object WindowsRomanizer {
    private val cache = HashMap<String, String>()

    @Suppress("CAST_NEVER_SUCCEEDS")
    actual fun romanize(text: String): String? = com.recordlabs.desktop.platform.locked(cache) {
        cache.getOrPut(text) {
            if (!text.hasNonLatinLetter()) return@getOrPut ""
            runCatching {
                val latin = (text as NSString).stringByApplyingTransform(NSStringTransformToLatin, false) ?: return@runCatching ""
                ((latin as NSString).stringByApplyingTransform(NSStringTransformStripCombiningMarks, false) ?: latin)
                    .replace(Regex("\\s+"), " ").trim()
            }.getOrNull()?.takeIf { it.isNotBlank() && !it.equals(text.trim(), ignoreCase = true) }.orEmpty()
        }
    }.takeIf(String::isNotBlank)
}

@Suppress("CAST_NEVER_SUCCEEDS")
actual fun chineseToTraditional(text: String): String =
    (text as NSString).stringByApplyingTransform("Hans-Hant", false) ?: text
