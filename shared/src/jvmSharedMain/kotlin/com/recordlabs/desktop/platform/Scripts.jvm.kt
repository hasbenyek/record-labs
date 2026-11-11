package com.recordlabs.desktop.platform

private val scriptsByName = UScript.entries.associateBy { it.name }

actual fun unicodeScriptOf(codePoint: Int): UScript =
    runCatching { scriptsByName[Character.UnicodeScript.of(codePoint).name] }.getOrNull() ?: UScript.OTHER

actual fun isLetterCodePoint(codePoint: Int): Boolean = Character.isLetter(codePoint)
