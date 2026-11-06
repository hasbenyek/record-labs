package com.recordlabs.desktop

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner

/**
 * The Record Labs app UI on Android: the same window content as the desktop
 * app. While the app is in the background its animations pause, like a
 * minimized desktop window; the music keeps playing.
 */
@Composable
fun RecordLabsAndroidContent() {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> MainWindowActivity.update(true)
                Lifecycle.Event.ON_STOP -> {
                    MainWindowActivity.update(false)
                    // Android may end the app while it is in the background, before the
                    // settings' own delayed save: write them now.
                    Thread { runCatching { java.util.prefs.Preferences.userRoot().flush() } }.start()
                }
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    androidx.compose.foundation.layout.Box(androidx.compose.ui.Modifier.fillMaxSize()) {
        RecordLabsWindow(isFullscreen = false, onToggleFullscreen = {})
        // A music video opened full screen from the portrait player (turned landscape).
        AndroidMusicVideoFullscreen()
    }
}
