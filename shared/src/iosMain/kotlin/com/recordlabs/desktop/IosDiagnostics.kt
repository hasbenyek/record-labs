@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlinx.cinterop.BetaInteropApi::class)

package com.recordlabs.desktop

import com.recordlabs.desktop.data.AppDiagnostics
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSRecursiveLock
import platform.Foundation.NSUserDomainMask
import platform.darwin.dispatch_async
import platform.darwin.dispatch_queue_create

/**
 * The iPhone's diagnostics: the recent diary for the Connect page's "Copy diagnostics"
 * (AppDiagnostics), the Xcode console, and "Record Labs log.txt" in the app's Documents.
 * 
 */
internal object IosDiagnostics {
    private val queue = dispatch_queue_create("record-labs.diagnostics", null)
    private val lock = NSRecursiveLock()
    private val recent = ArrayDeque<String>()
    private val formatter = NSDateFormatter().apply { dateFormat = "dd/MM HH:mm:ss.SSS" }

    private val path: String? by lazy {
        val directory = NSFileManager.defaultManager.URLsForDirectory(NSDocumentDirectory, NSUserDomainMask)
            .firstOrNull()?.let { it as? platform.Foundation.NSURL }?.path ?: return@lazy null
        "$directory/Record Labs log.txt"
    }

    fun install() {
        AppDiagnostics.sink = { tag, message -> write(tag, message) }
        write("app", "Record Labs ${com.recordlabs.desktop.data.AppInfo.version} started on ${com.recordlabs.desktop.platform.AppPlatform.deviceName}")
    }

    /** The last lines with [tag] (newest last), for the screen. */
    fun recent(tag: String, count: Int): List<String> {
        lock.lock()
        try {
            return recent.filter { it.contains("[$tag]") }.takeLast(count)
        } finally {
            lock.unlock()
        }
    }

    private fun write(tag: String, message: String) {
        val line = "${formatter.stringFromDate(NSDate())} [$tag] ${message.replace('\n', ' ')}"
        println("RL $line")
        lock.lock()
        try {
            recent.addLast(line)
            while (recent.size > 400) recent.removeFirst()
        } finally {
            lock.unlock()
        }
        val target = path ?: return
        dispatch_async(queue) {
            runCatching {
                val file = platform.posix.fopen(target, "a") ?: return@runCatching
                platform.posix.fputs(line + "\n", file)
                val size = platform.posix.ftell(file)
                platform.posix.fclose(file)
                // Kept small: past ~2 MB it starts again.
                if (size > 2_000_000L) platform.posix.remove(target)
            }
        }
    }
}
