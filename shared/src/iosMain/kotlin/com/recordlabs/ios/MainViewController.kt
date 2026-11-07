package com.recordlabs.ios

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.ComposeUIViewController
import com.recordlabs.desktop.MainWindowActivity
import com.recordlabs.desktop.PlatformAppFullscreen
import com.recordlabs.desktop.RecordLabsWindow
import com.recordlabs.desktop.iosEdgeSwipeBack
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification
import platform.UIKit.UIApplicationWillEnterForegroundNotification

/**
 * The iPhone app's window (called from Swift, iosApp): the same Record Labs app
 * as on Android. While the app is in the background its animations pause (like
 * Android); the music keeps playing.
 */
fun MainViewController(): platform.UIKit.UIViewController {
    // The diagnostics diary (copied from the Connect page).
    com.recordlabs.desktop.IosDiagnostics.install()
    return ComposeUIViewController { RecordLabsIosApp() }
}

@Composable
private fun RecordLabsIosApp() {
    DisposableEffect(Unit) {
        val center = NSNotificationCenter.defaultCenter
        val observers = listOf(
            center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
                MainWindowActivity.update(false)
            },
            center.addObserverForName(UIApplicationWillEnterForegroundNotification, null, NSOperationQueue.mainQueue) { _ ->
                MainWindowActivity.update(true)
            },
        )
        onDispose { observers.forEach(center::removeObserver) }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).iosEdgeSwipeBack()) {
        RecordLabsWindow(
            isFullscreen = PlatformAppFullscreen.enabled,
            onToggleFullscreen = { PlatformAppFullscreen.enabled = !PlatformAppFullscreen.enabled },
        )
        // The music video full screen (the video's own button), over everything.
        com.recordlabs.desktop.IosMusicVideoFullscreen()
    }
}
