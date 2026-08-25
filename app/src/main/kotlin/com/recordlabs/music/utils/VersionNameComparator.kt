package com.recordlabs.music.utils

import java.util.Locale

private data class VersionNamePart(
    val number: Int,
    val suffix: String,
)

private fun versionNameParts(version: String): List<VersionNamePart> = version
    .removePrefix("v")
    .substringBefore('-')
    .split('.')
    .map { rawPart ->
        val numericPrefix = rawPart.takeWhile(Char::isDigit)
        VersionNamePart(
            number = numericPrefix.toIntOrNull() ?: 0,
            suffix = rawPart.drop(numericPrefix.length).lowercase(Locale.ROOT),
        )
    }

/**
 * Compares dotted Android version names while supporting revision suffixes such as 1.9.9.a.
 * A suffix is newer than the same numeric version without one, while the next numeric version
 * remains newer than every suffix of the previous version.
 */
internal fun compareVersionNames(left: String, right: String): Int {
    val leftParts = versionNameParts(left)
    val rightParts = versionNameParts(right)
    repeat(maxOf(leftParts.size, rightParts.size)) { index ->
        val leftPart = leftParts.getOrNull(index) ?: VersionNamePart(0, "")
        val rightPart = rightParts.getOrNull(index) ?: VersionNamePart(0, "")
        leftPart.number.compareTo(rightPart.number).takeIf { it != 0 }?.let { return it }
        leftPart.suffix.compareTo(rightPart.suffix).takeIf { it != 0 }?.let { return it }
    }
    return 0
}
