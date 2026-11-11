package com.recordlabs.desktop

/** Writes small settings off the UI thread (see the desktop/Android version). */
expect object BackgroundPreferenceWriter {
    fun writeOnClose(key: String, latest: (() -> (() -> Unit)?)?)
    fun flushOnClose()
    fun later(key: String, write: () -> Unit)
    fun now(key: String, write: () -> Unit)
}
