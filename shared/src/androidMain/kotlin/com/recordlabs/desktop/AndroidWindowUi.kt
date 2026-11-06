package com.recordlabs.desktop

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * The phone's status and navigation bars, and whether the screen stays on.
 *  - Hidden while a music video is full screen, and in landscape while the app is
 *    full screen (the button next to Back on the player pages, or in the header).
 *    A swipe from the edge shows them for a moment.
 *  - The screen stays on while the player or lyrics page shows a playing song, and
 *    while a music video is full screen.
 */
internal object AndroidWindowUi {
    var videoFullscreen by mutableStateOf(false)
}

@Composable
internal fun AndroidWindowUiEffects() {
    val view = LocalView.current
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val hideBars = AndroidWindowUi.videoFullscreen || (PlatformAppFullscreen.enabled && landscape)
    LaunchedEffect(hideBars, view) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (hideBars) controller.hide(WindowInsetsCompat.Type.systemBars())
        else controller.show(WindowInsetsCompat.Type.systemBars())
    }
    val keepOn = (PlatformKeepAwake.musicPlaying && PlatformKeepAwake.pageOpen) || AndroidWindowUi.videoFullscreen
    DisposableEffect(keepOn, view) {
        view.keepScreenOn = keepOn
        onDispose { view.keepScreenOn = false }
    }
}
