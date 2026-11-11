package com.recordlabs.desktop.platform

import com.recordlabs.desktop.data.Platform

actual object AppPlatform {
    actual val isWindows: Boolean get() = Platform.isWindows
    actual val isLinux: Boolean get() = Platform.isLinux
    actual val isAndroid: Boolean get() = Platform.isAndroid
    actual val isIos: Boolean get() = false
    actual val dataRoot: File get() = Platform.dataRoot.common()
    actual val cacheRoot: File get() = Platform.cacheRoot.common()
    actual val diagnosticsRoot: File get() = (Platform.androidDiagnosticsRoot ?: Platform.dataRoot).common()
    actual val deviceName: String get() = Platform.deviceName
    actual fun openUrl(url: String) = Platform.openUrl(url)
    actual fun debugProp(name: String): String = Platform.debugProp(name)
}

actual object AppStorage {
    actual val installId: String get() = com.recordlabs.desktop.data.WindowsStorage.installId
    actual val directory: File get() = com.recordlabs.desktop.data.WindowsStorage.directory.common()
}

actual fun screenLongSidePixels(): Int? = runCatching {
    val size = java.awt.Toolkit.getDefaultToolkit().screenSize
    maxOf(size.width, size.height)
}.getOrNull()
