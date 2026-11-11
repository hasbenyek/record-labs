package com.recordlabs.desktop.data

import com.recordlabs.desktop.platform.currentTimeMillis

/**
 * A short diary of what the app does in the background (Record Labs Connect, lyrics,
 * videos), for finding out why something does not work on a phone nobody can debug
 * from a computer. Only the iPhone keeps it (the Connect page copies it to send);
 * elsewhere nothing is recorded ([sink] is null) and these calls do nothing.
 *
 * Never record secrets: no account ids, cookies, keys or full stream addresses.
 */
object AppDiagnostics {
    /** Where entries go (set by the iPhone app at start). */
    @kotlin.concurrent.Volatile var sink: ((tag: String, message: String) -> Unit)? = null

    val enabled: Boolean get() = sink != null

    private val recentLines = ArrayDeque<String>()

    fun log(tag: String, message: String) {
        val target = sink ?: return
        com.recordlabs.desktop.platform.locked(recentLines) {
            recentLines.addLast("[$tag] ${clock()} $message")
            while (recentLines.size > 300) recentLines.removeFirst()
        }
        target(tag, message)
    }

    /** The time of day (UTC) to the millisecond, for the copied diary. */
    private fun clock(): String {
        val ms = currentTimeMillis() % 86_400_000L
        fun two(v: Long) = v.toString().padStart(2, '0')
        return "${two(ms / 3_600_000)}:${two(ms / 60_000 % 60)}:${two(ms / 1000 % 60)}.${(ms % 1000).toString().padStart(3, '0')}Z"
    }

    /** The last [count] entries with [tag] (oldest first). */
    fun recent(tag: String, count: Int): List<String> = com.recordlabs.desktop.platform.locked(recentLines) {
        recentLines.filter { it.startsWith("[$tag]") }.takeLast(count).map { it.removePrefix("[$tag] ") }
    }

    /** Everything recorded lately (oldest first), to copy and send. */
    fun allRecent(): String = com.recordlabs.desktop.platform.locked(recentLines) { recentLines.joinToString("\n") }

    /** Runs [block] and records how long it took (ms) with [what] it returned. */
    inline fun <T> timed(tag: String, label: String, block: () -> T): T {
        if (sink == null) return block()
        val started = currentTimeMillis()
        val result = runCatching(block)
        val took = currentTimeMillis() - started
        log(tag, "$label: ${took} ms" + result.exceptionOrNull()?.let { " (failed: ${it.message?.take(120)})" }.orEmpty())
        return result.getOrThrow()
    }
}
