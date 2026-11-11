package com.recordlabs.android

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.recordlabs.desktop.RecordLabsAndroidContent

/**
 * The whole Record Labs app: the same Compose UI as the desktop app (from
 * :shared), drawn inside the phone's safe area. Playback runs in
 * [PlaybackService] so it keeps going in the background.
 */
class MainActivity : ComponentActivity() {
    private var controller: ListenableFuture<MediaController>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        takeWidgetCommand(intent)
        setContent {
            // No system-bar padding here: the app draws under the navigation bar itself and
            // keeps its controls clear of it (EdgeToEdge.kt). Padding here left a black strip
            // round the gesture bar, in portrait and landscape.
            Box(Modifier.fillMaxSize().background(Color.Black)) {
                RecordLabsAndroidContent()
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        takeWidgetCommand(intent)
    }

    /** A home-screen widget button pressed while the app was closed: the app runs it once open. */
    private fun takeWidgetCommand(intent: android.content.Intent?) {
        val command = intent?.getStringExtra(NowPlayingWidget.EXTRA_COMMAND) ?: return
        intent.removeExtra(NowPlayingWidget.EXTRA_COMMAND)
        com.recordlabs.desktop.NowPlayingFeed.pendingCommand = command
    }

    override fun onDestroy() {
        super.onDestroy()
        // The app's player is gone with its window: the widget's buttons open the app again.
        android.os.Handler(mainLooper).post { NowPlayingWidgetUpdater.render(applicationContext) }
    }

    // Hardware keyboard shortcuts of the app (\ shows the header on the player pages).
    override fun dispatchKeyEvent(event: android.view.KeyEvent): Boolean =
        com.recordlabs.desktop.AndroidHardwareKeys.onKeyEvent(event) || super.dispatchKeyEvent(event)

    override fun onStart() {
        super.onStart()
        // Connecting a controller starts the playback service (media notification, lock screen).
        controller = MediaController.Builder(this, SessionToken(this, ComponentName(this, PlaybackService::class.java))).buildAsync()
    }

    override fun onStop() {
        controller?.let(MediaController::releaseFuture)
        controller = null
        super.onStop()
    }
}
