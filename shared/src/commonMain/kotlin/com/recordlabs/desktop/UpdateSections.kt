package com.recordlabs.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/** Settings > About > Updates of the desktop app (nothing on iOS). */
@Composable
internal expect fun DesktopUpdateSection()

/** Settings > About > Updates on Android. */
@Composable
internal expect fun AndroidUpdateSection()

internal expect val desktopUpdatesConfigured: Boolean

/** The number on the profile icon while an update waits. */
internal expect val updateBadgeCount: Int

/** Android: a new release on GitHub as a banner over the pages. */
@Composable
internal expect fun PlatformUpdateBanner(modifier: Modifier)

/** Desktop: the mouse's back button (nothing on phones). */
@Composable
internal expect fun DesktopMouseBackButton(onBack: () -> Unit)

/**
 * Desktop: app-wide keys, whatever has focus (space, \). Each returns true when it
 * used the key. Phones use their own key handling.
 */
@Composable
internal expect fun DesktopKeyShortcuts(onSpace: () -> Boolean, onBackslash: () -> Boolean)
