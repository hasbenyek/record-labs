package com.recordlabs.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.material.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.recordlabs.desktop.data.AppInfo
import com.recordlabs.desktop.data.AppUpdater
import com.recordlabs.desktop.platform.AppPlatform
import kotlin.math.roundToInt

// The desktop's and Android's own update screens (their updaters are in jvmSharedMain).

@Composable
internal actual fun DesktopUpdateSection() = UpdateSection()

@Composable
internal actual fun AndroidUpdateSection() = AndroidUpdateSectionContent()

internal actual val desktopUpdatesConfigured: Boolean get() = AppUpdater.configured

internal actual val updateBadgeCount: Int get() = AppUpdater.badgeCount

@Composable
internal actual fun PlatformUpdateBanner(modifier: Modifier) = AndroidUpdateBanner(modifier)

@Composable
private fun UpdateCard(
    release: AppUpdater.Release,
    title: String,
    subtitle: String,
    progress: Float? = null,
    actions: @Composable () -> Unit,
) = UpdateCard(release.notes, title, subtitle, progress, actions)

/** Settings > About > Updates: check, download in the background, then install. */
@Composable
private fun UpdateSection() {
    val state = AppUpdater.state.value
    val problem = AppUpdater.lastInstallProblem.value
    if (problem != null && state !is AppUpdater.State.Downloading) SettingNote(problem)
    when (state) {
        AppUpdater.State.Idle, AppUpdater.State.Checking, is AppUpdater.State.UpToDate -> SettingRow(
            "Check for updates",
            when (state) {
                AppUpdater.State.Checking -> "Checking…"
                is AppUpdater.State.UpToDate -> "You're on the latest version · checked " +
                    java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(java.time.Instant.ofEpochMilli(state.checkedAt).atZone(java.time.ZoneId.systemDefault()))
                else -> "Record Labs checks automatically every few hours"
            },
            icon = null,
            enabled = state != AppUpdater.State.Checking,
            value = if (state == AppUpdater.State.Checking) null else "Check now",
        ) { AppUpdater.checkNow() }
        is AppUpdater.State.Available -> UpdateCard(
            state.release,
            "Version ${state.release.version} is available",
            "You have ${AppInfo.version}.",
        ) {
            if (AppUpdater.canInstall) {
                GlassButton(prominent = true, onClick = { AppUpdater.lastInstallProblem.value = null; AppUpdater.download() }) { Text("Update now") }
            } else {
                // A development run has no installer to replace: offer the release page.
                GlassButton(prominent = true, onClick = { AppPlatform.openUrl(state.release.pageUrl) }) { Text("Open release page") }
            }
        }
        is AppUpdater.State.Downloading -> UpdateCard(
            state.release,
            "Downloading version ${state.release.version}… ${(state.progress * 100).roundToInt()}%",
            "You can keep using Record Labs while it downloads.",
            progress = state.progress,
        ) {
            TextButton(onClick = { AppUpdater.cancelDownload() }) { Text("Cancel", color = SecondaryText) }
        }
        is AppUpdater.State.Ready -> UpdateCard(
            state.release,
            "Version ${state.release.version} is ready",
            "It installs when you close Record Labs, or restart now to update straight away.",
        ) {
            GlassButton(prominent = true, onClick = { AppUpdater.restartToUpdate() }) { Text("Restart to update") }
        }
        is AppUpdater.State.Failed -> if (state.release != null) UpdateCard(state.release, "Version ${state.release.version} is available", state.message) {
            GlassButton(prominent = true, onClick = { AppUpdater.download() }) { Text("Try again") }
        } else SettingRow("Check for updates", state.message, icon = null, value = "Try again") { AppUpdater.checkNow() }
    }
}


/** Android's Settings > About > Updates (see AndroidUpdates): the same choices as the desktop's. */
@Composable
private fun AndroidUpdateSectionContent() {
    val updates = com.recordlabs.desktop.data.AndroidUpdates
    val uriHandler = androidx.compose.ui.platform.LocalUriHandler.current
    val openPage: (String) -> Unit = { url -> runCatching { uriHandler.openUri(url) } }
    // Opening About asks GitHub again (at most every few hours, like the banner).
    androidx.compose.runtime.LaunchedEffect(Unit) { updates.checkInBackground() }
    when (val state = updates.banner.value) {
        com.recordlabs.desktop.data.AndroidUpdates.Banner.Hidden -> {
            val status = updates.manualStatus.value
            SettingRow(
                "Check for updates",
                status ?: "New versions also show as a banner at the top of the app",
                icon = null,
                enabled = status != "Checking…",
                value = if (status == "Checking…") null else "Check now",
            ) { updates.checkNow() }
        }
        is com.recordlabs.desktop.data.AndroidUpdates.Banner.Available -> UpdateCard(
            state.release.notes,
            "Version ${state.release.version} is available",
            "You have ${AppInfo.version}. The update downloads here, then Android installs it.",
        ) {
            GlassButton(prominent = true, onClick = { updates.update(openPage) }) { Text("Update now") }
            TextButton(onClick = { openPage(state.release.pageUrl) }) { Text("Release page", color = SecondaryText) }
        }
        is com.recordlabs.desktop.data.AndroidUpdates.Banner.Downloading -> UpdateCard(
            state.release.notes,
            "Downloading ${state.release.version}…",
            "${(state.progress * 100).toInt()}% · you can keep using Record Labs",
            progress = state.progress,
        ) {
            TextButton(onClick = { updates.cancelDownload() }) { Text("Cancel", color = SecondaryText) }
        }
        is com.recordlabs.desktop.data.AndroidUpdates.Banner.Ready -> UpdateCard(
            state.release.notes,
            "Version ${state.release.version} is ready",
            "Android asks to install it (the first time, to allow updates from Record Labs).",
        ) {
            GlassButton(prominent = true, onClick = { updates.update(openPage) }) { Text("Install") }
        }
        is com.recordlabs.desktop.data.AndroidUpdates.Banner.Failed -> UpdateCard(
            state.release.notes,
            "Version ${state.release.version} is available",
            state.message,
        ) {
            GlassButton(prominent = true, onClick = { updates.update(openPage) }) { Text("Try again") }
            TextButton(onClick = { openPage(state.release.pageUrl) }) { Text("Release page", color = SecondaryText) }
        }
    }
}

@Composable
internal actual fun DesktopMouseBackButton(onBack: () -> Unit) {
    val latestBack by rememberUpdatedState(onBack)
    DisposableEffect(Unit) {
        if (com.recordlabs.desktop.data.Platform.isAndroid) return@DisposableEffect onDispose {}
        val mouseListener = java.awt.event.AWTEventListener { awtEvent ->
            val mouse = awtEvent as? java.awt.event.MouseEvent ?: return@AWTEventListener
            if (mouse.id == java.awt.event.MouseEvent.MOUSE_PRESSED && mouse.button == 4) {
                latestBack()
                mouse.consume()
            }
        }
        java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(mouseListener, java.awt.AWTEvent.MOUSE_EVENT_MASK)
        onDispose { java.awt.Toolkit.getDefaultToolkit().removeAWTEventListener(mouseListener) }
    }
}

@Composable
internal actual fun DesktopKeyShortcuts(onSpace: () -> Boolean, onBackslash: () -> Boolean) {
    val latestSpace by rememberUpdatedState(onSpace)
    val latestBackslash by rememberUpdatedState(onBackslash)
    DisposableEffect(Unit) {
        if (com.recordlabs.desktop.data.Platform.isAndroid) return@DisposableEffect onDispose {}
        var spaceHeld = false
        val dispatcher = java.awt.KeyEventDispatcher { event ->
            when (event.keyCode) {
                java.awt.event.KeyEvent.VK_SPACE -> {
                    if (event.id == java.awt.event.KeyEvent.KEY_RELEASED) {
                        spaceHeld = false
                        false
                    } else if (event.id == java.awt.event.KeyEvent.KEY_PRESSED && !spaceHeld && !event.isConsumed && latestSpace()) {
                        spaceHeld = true
                        event.consume()
                        true
                    } else false
                }
                java.awt.event.KeyEvent.VK_BACK_SLASH -> {
                    if (event.id == java.awt.event.KeyEvent.KEY_PRESSED && !event.isConsumed && latestBackslash()) {
                        event.consume()
                        true
                    } else false
                }
                else -> false
            }
        }
        val keyboardManager = java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager()
        keyboardManager.addKeyEventDispatcher(dispatcher)
        onDispose { keyboardManager.removeKeyEventDispatcher(dispatcher) }
    }
}
