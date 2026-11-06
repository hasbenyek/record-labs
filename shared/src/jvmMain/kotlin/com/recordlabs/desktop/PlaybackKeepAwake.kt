package com.recordlabs.desktop

import com.sun.jna.Library
import com.sun.jna.Native
import java.util.concurrent.Executors

/**
 * Keeps the screen on (no screen saver, no display sleep, no system sleep)
 * while music or a video is playing, and gives that back as soon as playback
 * stops, so Windows' own timers apply again.
 *
 * Windows ties the request to the thread that made it, so every call runs on
 * one long-lived thread; when the app exits that thread ends and Windows drops
 * the request by itself.
 */
internal object PlaybackKeepAwake {
    private val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)
    private val worker by lazy {
        Executors.newSingleThreadExecutor { task -> Thread(task, "record-labs-keep-awake").apply { isDaemon = true } }
    }
    private val kernel32: PowerRequests? by lazy {
        runCatching { Native.load("kernel32", PowerRequests::class.java) }.getOrNull()
    }
    @Volatile
    private var keepingAwake = false

    fun setPlaying(playing: Boolean) {
        if (!isWindows || playing == keepingAwake) return
        keepingAwake = playing
        runCatching {
            worker.execute {
                // Only the latest wish counts if calls pile up.
                val awake = keepingAwake
                runCatching {
                    kernel32?.SetThreadExecutionState(
                        if (awake) ES_CONTINUOUS or ES_SYSTEM_REQUIRED or ES_DISPLAY_REQUIRED else ES_CONTINUOUS,
                    )
                }
            }
        }
    }

    @Suppress("FunctionName")
    private interface PowerRequests : Library {
        fun SetThreadExecutionState(esFlags: Int): Int
    }

    private const val ES_CONTINUOUS = 0x80000000.toInt()
    private const val ES_SYSTEM_REQUIRED = 0x00000001
    private const val ES_DISPLAY_REQUIRED = 0x00000002
}
