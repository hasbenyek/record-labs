package com.recordlabs.desktop

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Writes small settings (last playback, window size) on one background thread,
 * so the registry writes behind them never run on the UI thread.
 *
 * Writes run in the order they were asked for; for each [key] only the latest
 * one still waiting is kept. [now] writes right away (used when the app
 * closes) after anything still waiting, so an older value never lands last.
 */
object BackgroundPreferenceWriter {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "record-labs-preferences").apply { isDaemon = true }
    }
    private val pending = LinkedHashMap<String, () -> Unit>()
    /** For each key, what to write once more when the app closes. */
    private val closeWrites = ConcurrentHashMap<String, () -> (() -> Unit)?>()

    fun writeOnClose(key: String, latest: (() -> (() -> Unit)?)?) {
        if (latest == null) closeWrites.remove(key) else closeWrites[key] = latest
    }

    /** Called on the UI thread right before the app exits. */
    fun flushOnClose() {
        closeWrites.forEach { (key, latest) ->
            val write = runCatching { latest() }.getOrNull() ?: return@forEach
            now(key, write)
        }
    }

    fun later(key: String, write: () -> Unit) {
        val schedule = synchronized(pending) {
            val idle = pending.isEmpty()
            pending[key] = write
            idle
        }
        if (schedule) runCatching { worker.execute(::drain) }.onFailure { drain() }
    }

    fun now(key: String, write: () -> Unit) {
        synchronized(pending) { pending.remove(key) }
        val done = runCatching {
            worker.submit {
                drain()
                runCatching(write)
            }.get(1_500L, TimeUnit.MILLISECONDS)
        }
        if (done.isFailure) runCatching(write)
    }

    private fun drain() {
        while (true) {
            val write = synchronized(pending) {
                val first = pending.keys.firstOrNull() ?: return
                pending.remove(first)
            } ?: return
            runCatching(write)
        }
    }
}
