package com.recordlabs.desktop.data

// Desktop-only features, absent on Android.

internal actual fun openWindowsDiscordPipe(path: String): DiscordIpcTransport? = null

internal actual fun launchWindowsProcessWithoutWindow(commandLine: String): Boolean = false

internal actual fun decodeRgbThumbnail(bytes: ByteArray, size: Int): IntArray? {
    val source = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
    val scaled = android.graphics.Bitmap.createScaledBitmap(source, size, size, true)
    val pixels = IntArray(size * size)
    scaled.getPixels(pixels, 0, size, 0, 0, size, size)
    if (scaled !== source) scaled.recycle()
    source.recycle()
    return pixels
}
