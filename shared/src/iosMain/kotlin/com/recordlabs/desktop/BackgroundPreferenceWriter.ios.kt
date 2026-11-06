package com.recordlabs.desktop

/** iOS: settings writes (NSUserDefaults) are quick, so they run right away. */
actual object BackgroundPreferenceWriter {
    private val closeWrites = HashMap<String, () -> (() -> Unit)?>()

    actual fun writeOnClose(key: String, latest: (() -> (() -> Unit)?)?) {
        if (latest == null) closeWrites.remove(key) else closeWrites[key] = latest
    }

    actual fun flushOnClose() {
        closeWrites.values.toList().forEach { latest -> runCatching { latest()?.invoke() } }
    }

    actual fun later(key: String, write: () -> Unit) { runCatching(write) }

    actual fun now(key: String, write: () -> Unit) { runCatching(write) }
}
