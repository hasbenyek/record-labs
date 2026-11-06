package com.recordlabs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// iOS: the app is installed with Sideloadly; new versions are announced on GitHub.

@Composable
internal actual fun DesktopUpdateSection() = Unit

@Composable
internal actual fun AndroidUpdateSection() = Unit

internal actual val desktopUpdatesConfigured: Boolean get() = false

internal actual val updateBadgeCount: Int get() = 0

@Composable
internal actual fun PlatformUpdateBanner(modifier: Modifier) = Unit

@Composable
internal actual fun DesktopMouseBackButton(onBack: () -> Unit) = Unit

@Composable
internal actual fun DesktopKeyShortcuts(onSpace: () -> Boolean, onBackslash: () -> Boolean) = Unit
