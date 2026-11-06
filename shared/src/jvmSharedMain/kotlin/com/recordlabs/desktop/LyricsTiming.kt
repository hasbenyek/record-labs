package com.recordlabs.desktop

import com.recordlabs.desktop.data.AppSettings
import com.recordlabs.desktop.data.Platform

/** Where in the song the lyrics are, from the player's reported position. */
object LyricsTiming {
    /**
     * Linux plays through VLC, whose clock reaches the app later than Windows'
     * MediaPlayer position (coarser updates, then the 250 ms status poll), so
     * the lyrics trailed the singing. They run this much ahead there.
     * Fine-tuning per song stays in the lyrics menu's Lyrics offset.
     */
    val platformLeadMs: Long = if (Platform.isWindows) 0L else 500L

    /** [positionMs] with the Lyrics offset (positive = later) and the platform lead applied. */
    fun position(positionMs: Long): Long =
        (positionMs - AppSettings.lyricsOffsetMs.value + platformLeadMs).coerceAtLeast(0L)
}
