package com.recordlabs.desktop

import com.recordlabs.desktop.platform.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
/**
 * Whether the main window's moving pictures run: the animated artwork / music video
 * picture and the moving colours of the player and lyrics pages' background.
 *
 * They stop while the main window is minimized, and on the desktop (Settings >
 * Appearance > Pause visuals in the background, on by default) also while another
 * window is in use, the app open on a second screen say: on a laptop's integrated
 * graphics the app alone took more than half of the graphics card. The music plays on
 * either way, and the lyrics keep moving unless the window is minimized ([isOnScreen]).
 * The floating lyrics window is not affected.
 */
internal object MainWindowActivity {
    /** The moving pictures run (composables, UI thread). */
    var isShown by mutableStateOf(true)
        private set

    /** The same, for decoder threads. */
    @kotlin.concurrent.Volatile
    var shownNow = true
        private set

    /** Not minimized (the lyrics follow the song while this is true). */
    var isOnScreen by mutableStateOf(true)
        private set

    private var visible = true
    private var active = true

    private val listeners = CopyOnWriteList<(Boolean) -> Unit>()

    /** The main window is on screen (true) or minimized (false). UI thread. */
    fun update(shown: Boolean) {
        visible = shown
        isOnScreen = shown
        apply()
    }

    /**
     * The main window is the one in use, or the pause in the background is off (desktop).
     * UI thread.
     */
    fun updateActive(inUse: Boolean) {
        active = inUse
        apply()
    }

    private fun apply() {
        val run = visible && active
        if (shownNow == run && isShown == run) return
        shownNow = run
        isShown = run
        listeners.forEach { listener -> runCatching { listener(run) } }
    }

    fun addListener(listener: (Boolean) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (Boolean) -> Unit) {
        listeners.remove(listener)
    }
}
