package com.recordlabs.desktop.platform

/**
 * Where the app runs, for code shared with iOS (the desktop and Android keep using
 * com.recordlabs.desktop.data.Platform, which this follows there).
 */
expect object AppPlatform {
    val isWindows: Boolean
    val isLinux: Boolean
    val isAndroid: Boolean
    val isIos: Boolean
    /** The app's own data folder. */
    val dataRoot: File
    /** The app's cache folder. */
    val cacheRoot: File
    /** Name shown to other devices (Record Labs Connect). */
    val deviceName: String
    fun openUrl(url: String)
    /** Android debug switch (adb setprop debug.recordlabs.<name>); empty elsewhere. */
    fun debugProp(name: String): String
}

/** The app's storage folder and installation id (WindowsStorage on the desktop and Android). */
expect object AppStorage {
    val installId: String
    val directory: File
}

/** The longer side of the main screen in pixels, when known (desktop: lyrics size defaults). */
expect fun screenLongSidePixels(): Int?

/** A phone (Android or iPhone): the touch layout, the portrait player and so on. */
val AppPlatform.isPhone: Boolean get() = isAndroid || isIos
