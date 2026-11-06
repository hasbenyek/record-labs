package com.recordlabs.desktop.platform

import kotlinx.cinterop.useContents
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSURL
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIApplication
import platform.UIKit.UIDevice

actual object AppPlatform {
    actual val isWindows: Boolean get() = false
    actual val isLinux: Boolean get() = false
    actual val isAndroid: Boolean get() = false
    actual val isIos: Boolean get() = true

    private fun folder(kind: ULong): String =
        (NSSearchPathForDirectoriesInDomains(kind, NSUserDomainMask, true).firstOrNull() as? String).orEmpty()

    actual val dataRoot: File by lazy { File(folder(NSDocumentDirectory), "record-labs").also { it.mkdirs() } }
    actual val cacheRoot: File by lazy { File(folder(NSCachesDirectory), "record-labs").also { it.mkdirs() } }
    actual val deviceName: String get() = UIDevice.currentDevice.name

    actual fun openUrl(url: String) {
        val link = NSURL.URLWithString(url) ?: return
        UIApplication.sharedApplication.openURL(link, emptyMap<Any?, Any>(), null)
    }

    actual fun debugProp(name: String): String = ""
}

actual object AppStorage {
    actual val installId: String get() = "ios"
    actual val directory: File get() = AppPlatform.dataRoot
}

@OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
actual fun screenLongSidePixels(): Int? {
    val screen = platform.UIKit.UIScreen.mainScreen
    val bounds = screen.nativeBounds
    return bounds.useContents { maxOf(size.width, size.height).toInt() }
}
