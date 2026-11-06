package com.recordlabs.desktop

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Whether the main window is on screen. Only a minimized main window counts as
 * hidden: losing focus, another app in front or the window on another monitor
 * keep everything running. While hidden, the main window's animations (animated
 * artwork / music video picture, rotating background, lyrics) pause; the music
 * keeps playing, and the floating lyrics window is not affected.
 */
internal object MainWindowActivity {
    /** For composables (UI thread). */
    var isShown by mutableStateOf(true)
        private set

    /** For decoder threads. */
    @Volatile
    var shownNow = true
        private set

    private val listeners = CopyOnWriteArrayList<(Boolean) -> Unit>()

    /** UI thread. */
    fun update(shown: Boolean) {
        if (shownNow == shown && isShown == shown) return
        shownNow = shown
        isShown = shown
        listeners.forEach { listener -> runCatching { listener(shown) } }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners -= listener
    }
}
